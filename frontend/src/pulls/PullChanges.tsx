/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Fragment, type CSSProperties, useEffect, useId, useMemo, useRef, useState } from 'react';
import { Check, ChevronRight, FileDiff, Folder, GitCommitHorizontal, PanelLeft } from 'lucide-react';
import Avatar from '../Avatar';
import { parseUnifiedDiff, type DiffRow } from '../diffParse';
import type {
  Bridge,
  DiffCommitDto,
  DiffFileContentDto,
  DiffFileDto,
  InlineReviewCommentDto,
  PullRequestDetailDto,
  PullRequestDto,
  UserProfileDto,
} from '../types';
import { useResizablePane } from '../useResizablePane';
import BetterWordsButton from './BetterWordsButton';
import MarkdownBody, { type SuggestionBase } from './MarkdownBody';
import { CopyLinkButton, remoteErrorMessage, ReviewThread, useViewer, type PullDetailUpdater } from './PullOverview';
import type { ReviewNavigationTarget } from './agent-review/AgentReviewTab';
import type { AgentReviewDto } from './agent-review/contracts';
import ReviewFindingCard from './agent-review/ReviewFindingCard';
import { useFindingActions } from './agent-review/useFindingActions';
import { relativeTime } from '../relativeTime';

export type CommitRange = { start: number; end: number } | null;

/**
 * The Agent review findings projected onto this diff.
 *
 * <p>A finding renders at the validated end line of its range, as the same card the Agent review
 * tab draws: one finding ID and one discussion, shown in two places. An outdated finding has no
 * trustworthy location here, so it stays in the tab with its recorded snapshot instead.
 */
type AgentReviewProjection = {
  review: AgentReviewDto;
  viewer: UserProfileDto | null;
  /** The range Agent review asked to show; a new token re-scrolls even to the same lines. */
  navigation: ReviewNavigationTarget | null;
  onReviewChange: (review: AgentReviewDto) => void;
  onOpenInReview: (findingId: string) => void;
};

const SINGLE_FILE_MODE_THRESHOLD = 20;

type ReviewThreads = {
  activities: PullRequestDetailDto['recentActivity'];
  pr: PullRequestDto;
  workspaceId?: string;
  unavailable: boolean;
  onRefresh: () => Promise<void>;
  onDetailUpdate: PullDetailUpdater;
};

type PullChangesProps = {
  repo: string;
  number: number;
  workspaceId?: string;
  files: DiffFileDto[];
  commits: DiffCommitDto[];
  commitRange: CommitRange;
  loading: boolean;
  canReview: boolean;
  pendingComments: InlineReviewCommentDto[];
  reviewThreads: ReviewThreads;
  agentReview?: AgentReviewProjection;
  onCommitRangeChange: (range: CommitRange) => void;
  onPendingCommentsChange: (comments: InlineReviewCommentDto[]) => void;
  onDetailChange: (detail: PullRequestDetailDto) => void;
};

type ReviewOptions = {
  repo: string;
  number: number;
  workspaceId?: string;
  canReview: boolean;
  pendingComments: InlineReviewCommentDto[];
  onPendingCommentsChange: (comments: InlineReviewCommentDto[]) => void;
  onDetailChange: (detail: PullRequestDetailDto) => void;
};

export type DiffViewerProps = {
  files: DiffFileDto[];
  commits?: DiffCommitDto[];
  commitRange?: CommitRange;
  loading?: boolean;
  onCommitRangeChange?: (range: CommitRange) => void;
  review?: ReviewOptions;
  reviewThreads?: ReviewThreads;
  agentReview?: AgentReviewProjection;
  loadFileContent?: (
    path: string,
    baseSha?: string,
    headSha?: string,
  ) => Promise<DiffFileContentDto>;
};

export default function PullChanges({
  repo,
  number,
  workspaceId,
  files,
  commits,
  commitRange,
  loading,
  canReview,
  pendingComments,
  reviewThreads,
  agentReview,
  onCommitRangeChange,
  onPendingCommentsChange,
  onDetailChange,
}: PullChangesProps) {
  return (
    <DiffViewer
      files={files}
      commits={commits}
      commitRange={commitRange}
      loading={loading}
      reviewThreads={reviewThreads}
      agentReview={agentReview}
      onCommitRangeChange={onCommitRangeChange}
      review={{
        repo,
        number,
        workspaceId,
        canReview,
        pendingComments,
        onPendingCommentsChange,
        onDetailChange,
      }}
      loadFileContent={(path, baseSha, headSha) =>
        window.bridge.refreshPrDiffFileContent(repo, number, path, baseSha, headSha)}
    />
  );
}

export function DiffViewer({
  files,
  commits = [],
  commitRange = null,
  loading = false,
  onCommitRangeChange,
  review,
  reviewThreads,
  agentReview,
  loadFileContent,
}: DiffViewerProps) {
  const viewer = useViewer(Boolean(review?.canReview || review?.pendingComments.length));
  const [treeOpen, setTreeOpen] = useState(false);
  const [selected, setSelected] = useState(files[0]?.filename ?? null);
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
  const [allFilesOpen, setAllFilesOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileTreeId = useId();
  const filePane = useResizablePane(260, 160, 480);
  const treeItems = useMemo(() => buildFileTree(files), [files]);
  const singleFileMode = files.length > SINGLE_FILE_MODE_THRESHOLD;
  const totals = useMemo(() => files.reduce((sum, file) => ({
    additions: sum.additions + file.additions,
    deletions: sum.deletions + file.deletions,
  }), { additions: 0, deletions: 0 }), [files]);
  const baseSha = commitRange === null ? undefined : commits[commitRange.start]?.parentSha ?? undefined;
  const headSha = commitRange === null
    ? commits[commits.length - 1]?.sha
    : commits[commitRange.end]?.sha;
  const isFileOpen = (filename: string) =>
    !closed.has(filename) && (allFilesOpen || !singleFileMode || selected === filename);
  const everyFileOpen = files.length > 0 && files.every(file => isFileOpen(file.filename));

  useEffect(() => {
    if (files.length === 0) setSelected(null);
    else if (!files.some(file => file.filename === selected)) setSelected(files[0].filename);
  }, [files, selected]);

  useEffect(() => {
    setClosed(new Set());
    setAllFilesOpen(false);
  }, [files]);

  // Opening a finding's range: the file first, so a single-file view has it mounted, then the row.
  const navigation = agentReview?.navigation ?? null;
  const navigationToken = navigation?.token ?? null;
  useEffect(() => {
    if (navigation === null) return;
    if (!files.some(file => file.filename === navigation.path)) return;
    setSelected(navigation.path);
    setClosed(previous => {
      if (!previous.has(navigation.path)) return previous;
      const next = new Set(previous);
      next.delete(navigation.path);
      return next;
    });
    const scroll = () => {
      const container = scrollRef.current;
      const row = container?.querySelector(
        `[data-diff-row="${CSS.escape(`${navigation.path}:${navigation.side}:${navigation.startLine}`)}"]`);
      const card = container?.querySelector(`[data-file-card="${CSS.escape(navigation.path)}"]`);
      const target = row ?? card;
      if (!container || !target) return;
      const top = target.getBoundingClientRect().top - container.getBoundingClientRect().top
        + container.scrollTop - 96;
      container.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
    };
    // Two frames: the first mounts the file the click may just have opened, the second measures it.
    requestAnimationFrame(() => requestAnimationFrame(scroll));
  }, [files, navigation, navigationToken]);

  const pick = (filename: string) => {
    setSelected(filename);
    setClosed(previous => {
      if (!previous.has(filename)) return previous;
      const next = new Set(previous);
      next.delete(filename);
      return next;
    });
    const scrollToFile = () => {
      const container = scrollRef.current;
      const card = container?.querySelector(`[data-file-card="${CSS.escape(filename)}"]`);
      if (!container || !card) return;
      const top = card.getBoundingClientRect().top - container.getBoundingClientRect().top
        + container.scrollTop - 12;
      container.scrollTo({ top: Math.max(0, top), behavior: 'instant' });
    };
    if (singleFileMode) requestAnimationFrame(scrollToFile);
    else scrollToFile();
  };

  const toggle = (filename: string) => {
    setClosed(previous => {
      const next = new Set(previous);
      const open = !previous.has(filename)
        && (allFilesOpen || !singleFileMode || selected === filename);
      if (open) next.add(filename);
      else next.delete(filename);
      return next;
    });
    setSelected(filename);
  };

  return (
    <div className="pull-changes">
      <div className="pull-changes__toolbar">
        <button
          type="button"
          className={`pull-changes__files-toggle${treeOpen ? ' is-active' : ''}`}
          onClick={() => setTreeOpen(value => !value)}
          aria-expanded={treeOpen}
          aria-controls={fileTreeId}
          title={`${treeOpen ? 'Hide' : 'Show'} changed files`}
        >
          <PanelLeft size={14} strokeWidth={1.7} aria-hidden="true" />
          <span>{files.length} changed {files.length === 1 ? 'file' : 'files'}</span>
        </button>
        {onCommitRangeChange && (
          <CommitPicker commits={commits} range={commitRange} onChange={onCommitRangeChange} />
        )}
        {files.length > 0 && (
          <button
            type="button"
            className="pull-changes__bulk-toggle"
            onClick={() => {
              setAllFilesOpen(!everyFileOpen);
              setClosed(everyFileOpen
                ? new Set(files.map(file => file.filename))
                : new Set());
            }}
            aria-label={`${everyFileOpen ? 'Collapse' : 'Expand'} all file diffs`}
            title={`${everyFileOpen ? 'Collapse' : 'Expand'} all file diffs`}
          >
            {everyFileOpen ? 'Collapse all' : 'Expand all'}
          </button>
        )}
        <span className="pull-changes__totals">
          <span>+{totals.additions}</span>
          <span>−{totals.deletions}</span>
        </span>
      </div>
      <div className="pull-changes__panes">
        {treeOpen && (
          <nav
            id={fileTreeId}
            className="pull-file-tree"
            aria-label="Changed files"
            style={{ width: filePane.width, flexBasis: filePane.width }}
          >
            <FileTreeItems items={treeItems} selected={selected} onPick={pick} />
          </nav>
        )}
        {treeOpen && (
          <div
            className={`pull-file-tree__resize${filePane.resizing ? ' is-resizing' : ''}`}
            role="separator"
            aria-label="Resize changed files"
            aria-orientation="vertical"
            title="Drag to resize"
            onMouseDown={filePane.startResize}
          />
        )}
        <div className="pull-changes__scroll" ref={scrollRef}>
          {loading && <div className="pull-changes__loading">Loading selected commits…</div>}
          {reviewThreads?.unavailable && <p className="pull-diff-file__content-error" role="alert">
            Review comments could not be loaded. Refresh the pull request to try again.
          </p>}
          {!loading && files.length === 0 && <div className="hp-empty">No changed files reported.</div>}
          {files.map(file => (
            <DiffFileCard
              key={`${file.filename}:${baseSha ?? 'base'}:${headSha ?? 'head'}`}
              file={file}
              baseSha={baseSha}
              headSha={headSha}
              review={review}
              reviewThreads={reviewThreads}
              agentReview={agentReview}
              fullDiff={commitRange === null}
              viewer={viewer}
              loadFileContent={loadFileContent}
              open={isFileOpen(file.filename)}
              onToggle={() => toggle(file.filename)}
            />
          ))}
          {reviewThreads && <UnplacedReviewThreads
            reviewThreads={reviewThreads}
            activities={reviewThreads.activities.filter(activity => activity.eventType === 'review_thread'
              && !files.some(file => file.filename === activity.payload.path))}
            label="Review comments on files outside this diff"
          />}
        </div>
      </div>
    </div>
  );
}

type FileTreeDirectory = {
  kind: 'directory';
  name: string;
  path: string;
  children: FileTreeItem[];
};

type FileTreeFile = {
  kind: 'file';
  name: string;
  path: string;
  file: DiffFileDto;
};

type FileTreeItem = FileTreeDirectory | FileTreeFile;

export function buildFileTree(files: DiffFileDto[]): FileTreeItem[] {
  const root: FileTreeItem[] = [];

  files.forEach(file => {
    const segments = file.filename.split('/');
    const name = segments.pop() ?? file.filename;
    let path = '';
    let children = root;

    segments.forEach(segment => {
      path = path ? `${path}/${segment}` : segment;
      let directory = children.find((item): item is FileTreeDirectory =>
        item.kind === 'directory' && item.name === segment);
      if (directory === undefined) {
        directory = { kind: 'directory', name: segment, path, children: [] };
        children.push(directory);
      }
      children = directory.children;
    });

    children.push({ kind: 'file', name, path: file.filename, file });
  });

  return compactFileTree(root);
}

function compactFileTree(items: FileTreeItem[]): FileTreeItem[] {
  return items.map(item => {
    if (item.kind === 'file') return item;

    let name = item.name;
    let path = item.path;
    let children = compactFileTree(item.children);
    while (children.length === 1) {
      const child = children[0];
      if (child.kind !== 'directory') break;
      name = `${name}/${child.name}`;
      path = child.path;
      children = child.children;
    }
    return { kind: 'directory', name, path, children };
  });
}

function FileTreeItems({ items, selected, onPick, depth = 0 }: {
  items: FileTreeItem[];
  selected: string | null;
  onPick: (filename: string) => void;
  depth?: number;
}) {
  const branchStyle = depth === 0
    ? undefined
    : { '--tree-line-left': `${13 + (depth - 1) * 18}px` } as CSSProperties;
  const rowStyle = { paddingLeft: 7 + depth * 18 };

  return (
    <ul className={`pull-file-tree__list${depth > 0 ? ' is-branch' : ''}`} style={branchStyle}>
      {items.map(item => item.kind === 'directory' ? (
        <li className="pull-file-tree__folder" key={`directory:${item.path}`}>
          <details open>
            <summary title={item.path} aria-label={item.path} style={rowStyle}>
              <ChevronRight className="pull-file-tree__chevron" size={12} strokeWidth={1.8} aria-hidden="true" />
              <Folder className="pull-file-tree__folder-icon" size={16} aria-hidden="true" />
              <span className="pull-file-tree__label">{item.name}</span>
            </summary>
            <FileTreeItems items={item.children} selected={selected} onPick={onPick} depth={depth + 1} />
          </details>
        </li>
      ) : (
        <li key={`file:${item.path}`}>
          <button
            type="button"
            className={`pull-file-tree__file${selected === item.path ? ' is-active' : ''}`}
            aria-current={selected === item.path ? 'true' : undefined}
            aria-label={`${item.path}, ${fileStatusBadge(item.file.status).title}`}
            onClick={() => onPick(item.path)}
            onMouseEnter={event => {
              const label = event.currentTarget.querySelector<HTMLElement>('.pull-file-tree__label');
              const title = filenameTooltip(item.name, label?.scrollWidth ?? 0, label?.clientWidth ?? 0);
              if (title === undefined) event.currentTarget.removeAttribute('title');
              else event.currentTarget.title = title;
            }}
            onMouseLeave={event => event.currentTarget.removeAttribute('title')}
            style={rowStyle}
          >
            <span className="pull-file-tree__spacer" aria-hidden="true" />
            <FileDiff className={`pull-file-tree__file-icon is-${fileStatusBadge(item.file.status).kind}`} size={16} strokeWidth={1.5} aria-hidden="true" />
            <span className="pull-file-tree__label">{item.name}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

export function filenameTooltip(filename: string, contentWidth: number, visibleWidth: number): string | undefined {
  return contentWidth > visibleWidth ? filename : undefined;
}

function CommitPicker({ commits, range, onChange }: {
  commits: DiffCommitDto[];
  range: CommitRange;
  onChange: (range: CommitRange) => void;
}) {
  const pickerRef = useRef<HTMLDetailsElement>(null);

  useEffect(() => {
    const closeOnOutsidePointer = (event: PointerEvent) => {
      const picker = pickerRef.current;
      if (picker?.open && !picker.contains(event.target as Node)) picker.open = false;
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      const picker = pickerRef.current;
      if (event.key !== 'Escape' || !picker?.open) return;
      event.preventDefault();
      picker.open = false;
      picker.querySelector<HTMLElement>('summary')?.focus();
    };
    document.addEventListener('pointerdown', closeOnOutsidePointer);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsidePointer);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, []);

  if (commits.length === 0) return null;
  const selectedCount = range === null ? commits.length : range.end - range.start + 1;

  return (
    <details className="pull-commit-picker" ref={pickerRef}>
      <summary aria-label={`Choose commits, ${selectedCount} selected`}>
        <GitCommitHorizontal size={16} strokeWidth={1.5} aria-hidden="true" />
        <span>Commits</span>
        <span>{selectedCount}</span>
      </summary>
      <div className="pull-commit-picker__menu">
        <button
          type="button"
          className="pull-commit-picker__all"
          aria-pressed={range === null}
          onClick={() => {
            if (range !== null) onChange(null);
            if (pickerRef.current) {
              pickerRef.current.open = false;
              pickerRef.current.querySelector<HTMLElement>('summary')?.focus();
            }
          }}
        >
          <span className="pull-commit-picker__check" aria-hidden="true">
            {range === null && <Check size={16} strokeWidth={1.8} aria-hidden="true" />}
          </span>
          <span>All commits</span>
          <span>{commits.length} {commits.length === 1 ? 'commit' : 'commits'}</span>
        </button>
        <div className="pull-commit-picker__divider" />
        <div className="pull-commit-picker__list" role="group"
          aria-label="Select a consecutive commit range; commits between selected endpoints are included">
          {commits.map((commit, index) => {
            const selected = range !== null && index >= range.start && index <= range.end;
            const subject = commit.message?.split(/\r?\n/, 1)[0].trim() || 'Untitled commit';
            const age = relativeTime(commit.authoredAt);
            return (
              <label className={`pull-commit-picker__row${selected ? ' is-selected' : ''}`} key={commit.sha}
                title={subject}>
                <input
                  type="checkbox"
                  checked={selected}
                  aria-label={`${selected ? 'Deselect' : 'Select'} ${commit.sha.slice(0, 7)}: ${subject}`}
                  onChange={() => {
                    const next = nextCommitRange(range, index, commits.length);
                    if (next !== range) onChange(next);
                  }}
                />
                <code>{commit.sha.slice(0, 7)}</code>
                <span>{subject}</span>
                {commit.authoredAt && <time dateTime={commit.authoredAt}>{age}</time>}
              </label>
            );
          })}
        </div>
      </div>
    </details>
  );
}

export function nextCommitRange(range: CommitRange, index: number, commitCount: number): CommitRange {
  if (!Number.isInteger(index) || index < 0 || index >= commitCount) return range;
  if (range === null) return { start: index, end: index };
  if (index < range.start) return { start: index, end: range.end };
  if (index > range.end) return { start: range.start, end: index };
  if (range.start === range.end) return null;
  if (index === range.start) return { start: range.start + 1, end: range.end };
  if (index === range.end) return { start: range.start, end: range.end - 1 };
  // Clearing an interior commit trims the nearer side because the diff API cannot represent a gap.
  return index - range.start <= range.end - index
    ? { start: index + 1, end: range.end }
    : { start: range.start, end: index - 1 };
}

export type MarkdownCommand =
  | 'heading' | 'bold' | 'italic' | 'quote' | 'code' | 'link' | 'bullet' | 'number' | 'task';

const MARKDOWN_PREFIXES: Partial<Record<MarkdownCommand, string>> = {
  heading: '### ',
  quote: '> ',
  bullet: '- ',
  number: '1. ',
  task: '- [ ] ',
};

const MARKDOWN_WRAPS: Partial<Record<MarkdownCommand, string>> = {
  bold: '**',
  italic: '_',
  code: '`',
};

/**
 * Applies a toolbar command to `value` over the [start, end) selection and
 * returns the text plus the selection to restore. Insert-only: clicking Bold
 * twice nests markers rather than toggling them off, which is the cheap
 * behaviour the textarea's own undo already covers.
 */
export function applyMarkdownCommand(value: string, start: number, end: number, command: MarkdownCommand):
  { value: string; start: number; end: number } {
  const prefix = MARKDOWN_PREFIXES[command];
  if (prefix !== undefined) {
    const lineStart = value.lastIndexOf('\n', start - 1) + 1;
    const lineBreak = value.indexOf('\n', end);
    const lineEnd = lineBreak === -1 ? value.length : lineBreak;
    const block = value.slice(lineStart, lineEnd).split('\n')
      .map((line, index) => (command === 'number' ? `${index + 1}. ` : prefix) + line)
      .join('\n');
    return {
      value: value.slice(0, lineStart) + block + value.slice(lineEnd),
      start: lineStart,
      end: lineStart + block.length,
    };
  }
  const selected = value.slice(start, end);
  if (command === 'link') {
    const urlStart = start + selected.length + 3;
    return {
      value: `${value.slice(0, start)}[${selected}](url)${value.slice(end)}`,
      start: urlStart,
      end: urlStart + 3,
    };
  }
  // A fenced block is the only thing that renders a multi-line code selection.
  const fenced = command === 'code' && selected.includes('\n');
  const open = fenced ? '```\n' : MARKDOWN_WRAPS[command] ?? '';
  const close = fenced ? '\n```' : open;
  return {
    value: value.slice(0, start) + open + selected + close + value.slice(end),
    start: start + open.length,
    end: end + open.length,
  };
}

/**
 * Replaces the [start, end) selection with a ```suggestion block holding `lines`, with the caret
 * at the end of the last suggested line. The fence outgrows any backtick run inside the lines.
 */
export function insertSuggestion(value: string, start: number, end: number, lines: string[]):
  { value: string; start: number; end: number } {
  const before = value.slice(0, start);
  const after = value.slice(end);
  const longestRun = Math.max(0, ...(lines.join('\n').match(/`+/g) ?? []).map(run => run.length));
  const fence = '`'.repeat(Math.max(3, longestRun + 1));
  const opening = `${before && !before.endsWith('\n') ? '\n' : ''}${fence}suggestion\n${lines.join('\n')}`;
  const caret = before.length + opening.length;
  return {
    value: `${before}${opening}\n${fence}${after && !after.startsWith('\n') ? '\n' : ''}${after}`,
    start: caret,
    end: caret,
  };
}

/** Head-side text of new lines start..end from the diff rows, else the loaded head file; null if any line is unknown. */
export function headLines(hunks: Array<{ rows: DiffRow[] }>, newLines: string[], start: number, end: number): string[] | null {
  const rows = new Map(hunks.flatMap(hunk => hunk.rows).map(row => [row.newLine, row.content] as const));
  const lines: string[] = [];
  for (let line = start; line <= end; line++) {
    const text = rows.get(line) ?? newLines[line - 1];
    if (text === undefined) return null;
    lines.push(text);
  }
  return lines.length > 0 ? lines : null;
}

const SUGGESTION_ICON = [
  'M9.5 2H4.2A1.2 1.2 0 0 0 3 3.2v9.6A1.2 1.2 0 0 0 4.2 14h7.6a1.2 1.2 0 0 0 1.2-1.2V5.5z',
  'M9.5 2v3.5H13',
  'M8 6v3',
  'M6.5 7.5h3',
  'M6.5 11.3h3',
];

function ToolbarIcon({ paths }: { paths: string[] }) {
  return (
    <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor"
      strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths.map((path, index) => <path d={path} key={index} />)}
    </svg>
  );
}

const MARKDOWN_ICONS: Record<MarkdownCommand, string[]> = {
  heading: ['M4 3v10', 'M12 3v10', 'M4 8h8'],
  bold: ['M5.5 3h3.2a2.4 2.4 0 0 1 0 4.8H5.5z', 'M5.5 7.8h3.8a2.6 2.6 0 0 1 0 5.2H5.5z'],
  italic: ['M6.5 3h4.5', 'M5 13h4.5', 'M9.5 3 7 13'],
  quote: ['M3.2 3.5v9', 'M6.5 5h6.3', 'M6.5 8h6.3', 'M6.5 11h4'],
  code: ['m5.8 4.8-3 3.2 3 3.2', 'm10.2 4.8 3 3.2-3 3.2'],
  link: [
    'M6.6 9.4 9.4 6.6',
    'M8.6 5.4 10 4a2.6 2.6 0 0 1 3.7 3.7L12.3 9.1',
    'M7.4 10.6 6 12a2.6 2.6 0 0 1-3.7-3.7L3.7 6.9',
  ],
  bullet: ['M3 4.4h.01', 'M3 8h.01', 'M3 11.6h.01', 'M6.2 4.4h7', 'M6.2 8h7', 'M6.2 11.6h7'],
  number: ['M2.3 3.7 3.4 3v3.4', 'M2.2 10.2h1.9L2.2 12.6h2', 'M6.2 4.4h7', 'M6.2 8h7', 'M6.2 11.6h7'],
  task: ['m2 4.3 1.2 1.3L5.6 3', 'm2 10.8 1.2 1.3L5.6 9.5', 'M8 4.4h5.4', 'M8 10.9h5.4'],
};

const MARKDOWN_GROUPS: Array<Array<{ command: MarkdownCommand; label: string }>> = [
  [
    { command: 'heading', label: 'Heading' },
    { command: 'bold', label: 'Bold' },
    { command: 'italic', label: 'Italic' },
  ],
  [
    { command: 'quote', label: 'Quote' },
    { command: 'code', label: 'Code' },
    { command: 'link', label: 'Link' },
  ],
  [
    { command: 'bullet', label: 'Bulleted list' },
    { command: 'number', label: 'Numbered list' },
    { command: 'task', label: 'Task list' },
  ],
];

export function MarkdownToolbar({ onCommand, onSuggest }: {
  onCommand: (command: MarkdownCommand) => void;
  onSuggest?: () => void;
}) {
  return (
    <div className="pull-diff-review__toolbar">
      {onSuggest && <>
        <button type="button" title="Add a suggestion" aria-label="Add a suggestion"
          onMouseDown={event => event.preventDefault()}
          onClick={onSuggest}>
          <ToolbarIcon paths={SUGGESTION_ICON} />
        </button>
        <span className="pull-diff-review__divider" aria-hidden="true" />
      </>}
      {MARKDOWN_GROUPS.map((group, groupIndex) => (
        <Fragment key={groupIndex}>
          {groupIndex > 0 && <span className="pull-diff-review__divider" aria-hidden="true" />}
          {group.map(({ command, label }) => (
            <button type="button" key={command} title={label} aria-label={label}
              // The textarea keeps its selection because the button never takes focus.
              onMouseDown={event => event.preventDefault()}
              onClick={() => onCommand(command)}>
              <ToolbarIcon paths={MARKDOWN_ICONS[command]} />
            </button>
          ))}
        </Fragment>
      ))}
    </div>
  );
}

function DiffFileCard({
  file,
  baseSha,
  headSha,
  review,
  reviewThreads,
  agentReview,
  fullDiff,
  viewer,
  loadFileContent,
  open,
  onToggle,
}: {
  file: DiffFileDto;
  baseSha?: string;
  headSha?: string;
  review?: ReviewOptions;
  reviewThreads?: ReviewThreads;
  agentReview?: AgentReviewProjection;
  fullDiff: boolean;
  viewer?: UserProfileDto | null;
  loadFileContent?: DiffViewerProps['loadFileContent'];
  open: boolean;
  onToggle: () => void;
}) {
  const hunks = useMemo(() => open ? parseUnifiedDiff(file.patch) : [], [file.patch, open]);
  const blocks = changeBlocks(file.additions, file.deletions);
  const filenameStart = file.filename.lastIndexOf('/') + 1;
  const directory = file.filename.slice(0, filenameStart);
  const filename = file.filename.slice(filenameStart);
  const [content, setContent] = useState<DiffFileContentDto | null>(null);
  const [contentLoading, setContentLoading] = useState(false);
  const [contentError, setContentError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const [trailingChecked, setTrailingChecked] = useState(false);
  const [selection, setSelection] = useState<LineSelection | null>(null);
  const [selectingRange, setSelectingRange] = useState(false);
  const dragAnchor = useRef<DragAnchor | null>(null);
  const [reviewBody, setReviewBody] = useState('');
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [preview, setPreview] = useState(false);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const [posting, setPosting] = useState(false);
  const [pendingEdit, setPendingEdit] = useState<{ comment: InlineReviewCommentDto; body: string } | null>(null);
  const oldLines = sourceLines(content?.oldContent);
  const newLines = sourceLines(content?.newContent);
  const canReview = review?.canReview ?? false;
  const fileThreads = reviewThreads?.activities.filter(activity =>
    activity.eventType === 'review_thread' && activity.payload.path === file.filename) ?? [];
  const placedThreads = new Set<PullRequestDetailDto['recentActivity'][number]>();
  // An outdated finding has no trustworthy line here; its card stays in Agent review with its
  // recorded snapshot rather than pointing at an unrelated line of the current diff.
  const fileFindings = (fullDiff && agentReview !== undefined
    ? agentReview.review.findings.filter(finding =>
      finding.path === file.filename && finding.state !== 'WITHDRAWN' && !finding.outdated)
    : []);
  const findingActions = useFindingActions({
    review: agentReview?.review ?? PLACEHOLDER_REVIEW,
    onReviewChange: agentReview?.onReviewChange ?? (() => {}),
    onNavigate: finding => agentReview?.onOpenInReview(finding.id),
    onAskReason: () => window.prompt('Why are you resolving this finding locally? (optional)'),
  });

  useEffect(() => {
    if (!selectingRange) return;
    const finishSelection = () => {
      dragAnchor.current = null;
      setSelectingRange(false);
    };
    window.addEventListener('mouseup', finishSelection);
    window.addEventListener('blur', finishSelection);
    return () => {
      window.removeEventListener('mouseup', finishSelection);
      window.removeEventListener('blur', finishSelection);
    };
  }, [selectingRange]);

  const loadContent = async () => {
    if (content !== null) return content;
    if (loadFileContent === undefined) return null;
    setContentLoading(true);
    setContentError(null);
    try {
      const value = await loadFileContent(file.filename, baseSha, headSha);
      setContent(value);
      return value;
    }
    catch (error) {
      setContentError(error instanceof Error ? error.message : String(error));
      return null;
    }
    finally {
      setContentLoading(false);
    }
  };

  const expand = async (key: string) => {
    const loaded = await loadContent();
    if (loaded === null) return;
    setExpanded(previous => new Set(previous).add(key));
    if (key === 'trailing') setTrailingChecked(true);
  };

  const selectLine = (side: ReviewSide, line: number, extend: boolean, row: number) => {
    setReviewError(null);
    setSelection(previous => nextLineSelection(previous, side, line, extend, row));
  };

  const startLineSelection = (
    side: ReviewSide,
    line: number,
    extend: boolean,
    oldLine: number | null,
    newLine: number | null,
    row: number,
  ) => {
    dragAnchor.current = { side, line, oldLine, newLine, row };
    setSelectingRange(true);
    selectLine(side, line, extend, row);
  };

  const extendLineSelection = (oldLine: number | null, newLine: number | null, row: number) => {
    const anchor = dragAnchor.current;
    const next = draggedLineSelection(anchor, oldLine, newLine, row);
    if (next === null || anchor === null) return;
    if (next.side !== anchor.side) {
      const migratedLine = next.side === 'LEFT' ? anchor.oldLine : anchor.newLine;
      if (migratedLine !== null) dragAnchor.current = { ...anchor, side: next.side, line: migratedLine };
    }
    setSelection(next);
  };

  const comment = (): InlineReviewCommentDto | null => {
    if (review === undefined || selection === null || !reviewBody.trim()) return null;
    return {
      path: file.filename,
      body: reviewBody.trim(),
      line: selection.end,
      side: selection.side,
      startLine: selection.start === selection.end ? null : selection.start,
      startSide: selection.start === selection.end ? null : selection.side,
      commitId: headSha ?? null,
    };
  };

  // Suggestions change the head file, so deleted (LEFT) lines cannot be suggested.
  const suggestionLines = selection?.side === 'RIGHT' ? headLines(hunks, newLines, selection.start, selection.end) : null;

  const editReviewBody = (edit: (value: string, start: number, end: number) => { value: string; start: number; end: number }) => {
    const textarea = bodyRef.current;
    if (textarea === null) return;
    const next = edit(textarea.value, textarea.selectionStart, textarea.selectionEnd);
    setReviewBody(next.value);
    requestAnimationFrame(() => {
      textarea.focus();
      textarea.setSelectionRange(next.start, next.end);
    });
  };

  const closeComposer = () => {
    setReviewBody('');
    setSelection(null);
    setPreview(false);
  };

  const addPending = () => {
    const value = comment();
    if (value === null || review === undefined) return;
    review.onPendingCommentsChange([...review.pendingComments, value]);
    closeComposer();
  };

  const postNow = async () => {
    const value = comment();
    if (value === null || review === undefined) return;
    setPosting(true);
    setReviewError(null);
    try {
      await submitInlineReviewComment(
        window.bridge, review.repo, review.number, value, review.onDetailChange);
      closeComposer();
    }
    catch (error) {
      setReviewError(remoteErrorMessage(error));
    }
    finally {
      setPosting(false);
    }
  };

  let renderedRow = 0;
  const codeRow = (displayRow: DisplayRow) => {
    const row = renderedRow++;
    const oldLine = displayRow.oldLine;
    const newLine = displayRow.newLine;
    const target = reviewTarget(oldLine, newLine);
    const rowSelected = selection !== null && row >= selection.rowStart && row <= selection.rowEnd;
    const composerAfter = selection?.rowEnd === row;
    const selectionPrefix = selection?.side === 'RIGHT' ? 'R' : 'L';
    const rowComments = pendingReviewCommentsForLine(
      review?.pendingComments ?? [], file.filename, oldLine, newLine);
    const rowSide: ReviewSide | null = newLine !== null ? 'RIGHT' : oldLine !== null ? 'LEFT' : null;
    const rowLine = rowSide === 'RIGHT' ? newLine : oldLine;
    const rowFindings = rowSide === null || rowLine === null
      ? []
      : fileFindings.filter(finding => finding.side === rowSide && finding.endLine === rowLine);
    const navigation = agentReview?.navigation ?? null;
    const inNavigatedRange = navigation !== null
      && rowSide !== null
      && rowLine !== null
      && navigation.path === file.filename
      && navigation.side === rowSide
      && rowLine >= navigation.startLine
      && rowLine <= navigation.endLine;
    const rowThreads = fullDiff ? fileThreads.filter(activity => !activity.payload.outdated
      && activity.payload.line !== null
      && (activity.payload.side === 'LEFT' ? activity.payload.line === oldLine
        : activity.payload.side === 'RIGHT' && activity.payload.line === newLine)) : [];
    rowThreads.forEach(activity => placedThreads.add(activity));
    return (
      <Fragment key={displayRow.key}>
        <tr
          className={`is-${displayRow.kind}${rowSelected ? ' is-selected' : ''}${inNavigatedRange ? ' is-review-target' : ''}`}
          data-diff-row={rowSide === null || rowLine === null ? undefined : `${file.filename}:${rowSide}:${rowLine}`}
          onMouseEnter={() => extendLineSelection(oldLine, newLine, row)}>
          <td className="is-line">
            {oldLine !== null && canReview && (
              <button type="button"
                aria-label={`Select old line ${oldLine}`}
                onMouseDown={event => {
                  if (event.button !== 0) return;
                  event.preventDefault();
                  startLineSelection('LEFT', oldLine, event.shiftKey, oldLine, newLine, row);
                }}
                onKeyDown={event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  selectLine('LEFT', oldLine, event.shiftKey, row);
                }}>{oldLine}</button>
            )}
            {oldLine !== null && !canReview && oldLine}
          </td>
          <td className="is-line">
            {newLine !== null && canReview && (
              <button type="button"
                aria-label={`Select new line ${newLine}`}
                onMouseDown={event => {
                  if (event.button !== 0) return;
                  event.preventDefault();
                  startLineSelection('RIGHT', newLine, event.shiftKey, oldLine, newLine, row);
                }}
                onKeyDown={event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  selectLine('RIGHT', newLine, event.shiftKey, row);
                }}>{newLine}</button>
            )}
            {newLine !== null && !canReview && newLine}
          </td>
          <td className="is-source">
            {canReview && target !== null && (
              <button type="button" className="pull-diff-code__add-comment"
                aria-label={`Add a comment on ${target.side === 'RIGHT' ? 'new' : 'old'} line ${target.line}`}
                onMouseDown={event => {
                  if (event.button !== 0) return;
                  event.preventDefault();
                  startLineSelection(target.side, target.line, event.shiftKey, oldLine, newLine, row);
                }}
                onClick={event => {
                  if (event.detail === 0) selectLine(target.side, target.line, event.shiftKey, row);
                }}
                onKeyDown={event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  selectLine(target.side, target.line, event.shiftKey, row);
                }}>+</button>
            )}
            <span>{displayRow.kind === 'add' ? '+' : displayRow.kind === 'del' ? '−' : ' '}</span>{displayRow.content || ' '}
          </td>
        </tr>
        {agentReview !== undefined && rowFindings.map(finding => (
          <tr className="is-review-finding" key={`finding:${finding.id}`}><td colSpan={3}>
            <ReviewFindingCard
              review={agentReview.review}
              finding={finding}
              viewerLogin={agentReview.viewer?.login ?? ''}
              viewerAvatarUrl={agentReview.viewer?.avatarUrl ?? null}
              projection="changes"
              actions={findingActions.actions}
              busy={findingActions.busy} />
            {findingActions.error !== null && <div className="agr-error" role="alert">{findingActions.error}</div>}
          </td></tr>
        ))}
        {reviewThreads && rowThreads.map(activity => (
          <tr className={`is-review-thread is-${displayRow.kind}`} key={`thread:${activity.githubId}`}><td colSpan={3}>
            <ReviewThread {...reviewThreads} activity={activity} showCode={false} />
          </td></tr>
        ))}
        {review && rowComments.map((comment, index) => (
          <PendingReviewCommentRow
            key={`${comment.side}:${comment.startLine ?? comment.line}:${comment.line}:${index}`}
            comment={comment}
            rowKind={displayRow.kind}
            viewer={viewer}
            repository={review.repo}
            suggestion={comment.side === 'RIGHT' ? {
              startLine: comment.startLine ?? comment.line,
              lines: headLines(hunks, newLines, comment.startLine ?? comment.line, comment.line),
            } : undefined}
            editBody={pendingEdit?.comment === comment ? pendingEdit.body : null}
            onEdit={body => setPendingEdit(body === null ? null : { comment, body })}
            onSave={() => {
              if (pendingEdit?.comment !== comment || !pendingEdit.body.trim()) return;
              review.onPendingCommentsChange(updatePendingReviewComment(review.pendingComments, comment, pendingEdit.body));
              setPendingEdit(null);
            }}
            onDiscard={() => {
              review.onPendingCommentsChange(updatePendingReviewComment(review.pendingComments, comment, null));
              if (pendingEdit?.comment === comment) setPendingEdit(null);
            }}
          />
        ))}
        {composerAfter && !selectingRange && (
          <tr className={`is-review-composer is-${displayRow.kind}`}><td colSpan={3}>
            <div className="pull-diff-review">
              <header className="pull-diff-review__header">
                <Avatar login={viewer?.login ?? ''} avatarUrl={viewer?.avatarUrl} size={22}
                  allowGitHubFallback={false} />
                <strong>{selection.start === selection.end
                  ? `Add a comment on line ${selectionPrefix}${selection.start}`
                  : `Add a comment on lines ${selectionPrefix}${selection.start} to ${selectionPrefix}${selection.end}`}</strong>
              </header>
              <div className="pull-diff-review__bar">
                <div className="pull-diff-review__tabs" role="tablist">
                  <button type="button" role="tab" aria-selected={!preview}
                    className={preview ? '' : 'is-active'} onClick={() => {
                      setPreview(false);
                      requestAnimationFrame(() => bodyRef.current?.focus());
                    }}>Write</button>
                  <button type="button" role="tab" aria-selected={preview}
                    className={preview ? 'is-active' : ''} onClick={() => setPreview(true)}>Preview</button>
                </div>
                {!preview && <MarkdownToolbar
                  onCommand={command => editReviewBody((value, start, end) => applyMarkdownCommand(value, start, end, command))}
                  onSuggest={suggestionLines === null
                    ? undefined
                    : () => editReviewBody((value, start, end) => insertSuggestion(value, start, end, suggestionLines))} />}
              </div>
              {preview && <div className="pull-diff-review__preview">
                {reviewBody.trim()
                  ? <MarkdownBody body={reviewBody} repository={review.repo}
                    suggestion={selection.side === 'RIGHT' ? { startLine: selection.start, lines: suggestionLines } : undefined} />
                  : <p className="pull-diff-review__nothing">Nothing to preview</p>}
              </div>}
              <textarea ref={bodyRef} value={reviewBody} autoFocus hidden={preview}
                placeholder="Leave a review comment…" onChange={event => setReviewBody(event.target.value)} />
              {reviewError && <span role="alert">{reviewError}</span>}
              <footer>
                <BetterWordsButton text={reviewBody} textareaRef={bodyRef} repository={review.repo}
                  workspaceId={review.workspaceId}
                  disabled={preview || posting}
                  onError={failure => setReviewError(failure === null ? null : remoteErrorMessage(failure))} />
                <button type="button" onClick={closeComposer}>Cancel</button>
                <button type="button" disabled={!reviewBody.trim() || posting} onClick={addPending}>Add to review</button>
                <button className="is-primary" type="button" disabled={!reviewBody.trim() || posting}
                  onClick={() => { void postNow(); }}>{posting ? 'Posting…' : 'Post now'}</button>
              </footer>
            </div>
          </td></tr>
        )}
      </Fragment>
    );
  };

  let previousOld = 1;
  let previousNew = 1;
  const lastHunk = hunks[hunks.length - 1];
  const trailingOld = lastHunk ? lastHunk.oldStart + lastHunk.oldCount : 1;
  const trailingNew = lastHunk ? lastHunk.newStart + lastHunk.newCount : 1;
  const trailingCount = content === null ? null : Math.max(0, Math.min(
    oldLines.length - trailingOld + 1,
    newLines.length - trailingNew + 1,
  ));

  return (
    <article className={`pull-diff-file${selectingRange ? ' is-selecting' : ''}`} data-file-card={file.filename}>
      <div className="pull-diff-file__header">
        <button type="button" className="pull-diff-file__toggle" onClick={onToggle} aria-expanded={open}>
          <span className={`pull-diff-file__chevron${open ? ' is-open' : ''}`} aria-hidden="true">›</span>
          <span className="pull-diff-file__path" aria-label={file.filename}>
            {directory && <span className="pull-diff-file__directory"><bdi dir="ltr">{directory}</bdi></span>}
            <span className="pull-diff-file__name">{filename}</span>
          </span>
        </button>
        <CopyLinkButton value={file.filename} label="file path" />
        <span className="pull-diff-file__status">{file.status}</span>
        <span className="pull-diff-file__stats">
          <span>+{file.additions}</span><span>−{file.deletions}</span>
        </span>
        <span className="pull-diff-file__blocks" aria-hidden="true">
          {blocks.map((kind, index) => <i className={`is-${kind}`} key={index} />)}
        </span>
      </div>
      {open && file.patch === null && (
        <div className="pull-diff-file__empty">Binary file or diff unavailable.</div>
      )}
      {open && file.patch !== null && hunks.length === 0 && (
        <div className="pull-diff-file__empty">No textual diff was returned.</div>
      )}
      {open && hunks.length > 0 && (
        <div className="pull-diff-file__code-scroll">
          {contentError && <p className="pull-diff-file__content-error" role="alert">{contentError}</p>}
          <table className="pull-diff-code" onMouseUp={() => {
            dragAnchor.current = null;
            setSelectingRange(false);
          }}>
            <colgroup>
              <col className="pull-diff-code__line-column" />
              <col className="pull-diff-code__line-column" />
              <col />
            </colgroup>
            <tbody>
            {hunks.map((hunk, hunkIndex) => {
              const oldStart = previousOld;
              const newStart = previousNew;
              const hiddenLines = Math.max(0, Math.min(hunk.oldStart - oldStart, hunk.newStart - newStart));
              previousOld = hunk.oldStart + hunk.oldCount;
              previousNew = hunk.newStart + hunk.newCount;
              const gapKey = `before-${hunkIndex}`;
              return (
                <Fragment key={`${hunk.header}:${hunkIndex}`}>
                  {hiddenLines > 0 && !expanded.has(gapKey) && (
                    <tr className="is-gap"><td colSpan={3}>
                      {loadFileContent === undefined
                        ? `↕ ${hiddenLines} unmodified lines`
                        : <button type="button" disabled={contentLoading}
                            onClick={() => { void expand(gapKey); }}>↕ {hiddenLines} unmodified lines</button>}
                    </td></tr>
                  )}
                  {hiddenLines > 0 && expanded.has(gapKey) && Array.from({ length: hiddenLines }, (_, index) => codeRow({
                    key: `${gapKey}:${index}`,
                    kind: 'context',
                    oldLine: oldStart + index,
                    newLine: newStart + index,
                    content: newLines[newStart + index - 1] ?? oldLines[oldStart + index - 1] ?? '',
                  }))}
                  <tr className="is-hunk"><td colSpan={3}>{hunk.header}</td></tr>
                  {hunk.rows.map((row, rowIndex) => codeRow({ ...row, key: `${hunkIndex}:${rowIndex}` }))}
                </Fragment>
              );
            })}
            {loadFileContent !== undefined && !expanded.has('trailing')
              && (!trailingChecked || (trailingCount ?? 1) > 0) && (
              <tr className="is-gap is-trailing"><td colSpan={3}><button type="button" disabled={contentLoading}
                onClick={() => { void expand('trailing'); }}>
                {contentLoading ? 'Loading unchanged lines…' : trailingCount === null
                  ? '↕ Show unchanged lines below'
                  : `↕ ${trailingCount} unmodified lines`}
              </button></td></tr>
            )}
            {expanded.has('trailing') && trailingCount !== null && Array.from({ length: trailingCount }, (_, index) => codeRow({
              key: `trailing:${index}`,
              kind: 'context',
              oldLine: trailingOld + index,
              newLine: trailingNew + index,
              content: newLines[trailingNew + index - 1] ?? oldLines[trailingOld + index - 1] ?? '',
            }))}
            </tbody>
          </table>
        </div>
      )}
      {open && reviewThreads && <UnplacedReviewThreads
        reviewThreads={reviewThreads}
        activities={fileThreads.filter(activity => !placedThreads.has(activity))}
        label={fullDiff ? 'Review comments outside the visible lines' : 'Review comments on the full pull request'}
      />}
    </article>
  );
}

function UnplacedReviewThreads({ reviewThreads, activities, label }: {
  reviewThreads: ReviewThreads;
  activities: PullRequestDetailDto['recentActivity'];
  label: string;
}) {
  if (activities.length === 0) return null;
  return <section className="pull-diff-threads" aria-label={label}>
    <p>{label}</p>
    {activities.map(activity => <ReviewThread {...reviewThreads} activity={activity} key={activity.githubId} />)}
  </section>;
}

export function PendingReviewCommentRow({ comment, rowKind, viewer, repository, suggestion, editBody, onEdit, onSave, onDiscard }: {
  comment: InlineReviewCommentDto;
  rowKind: DisplayRow['kind'];
  viewer?: UserProfileDto | null;
  repository: string;
  suggestion?: SuggestionBase;
  editBody: string | null;
  onEdit: (body: string | null) => void;
  onSave: () => void;
  onDiscard: () => void;
}) {
  const endLabel = `${comment.side === 'RIGHT' ? 'R' : 'L'}${comment.line}`;
  const isRange = comment.startLine !== null && comment.startLine !== comment.line;
  const startSide = comment.startSide ?? comment.side;
  const startLabel = `${startSide === 'RIGHT' ? 'R' : 'L'}${comment.startLine ?? comment.line}`;

  return (
    <tr className={`is-pending-review is-${rowKind}`}>
      <td colSpan={3}>
        <details className="pull-diff-pending" open>
          <summary>
            <span className="pull-diff-pending__chevron" aria-hidden="true">›</span>
            <Avatar login={viewer?.login ?? ''} avatarUrl={viewer?.avatarUrl} size={22} allowGitHubFallback={false} />
            <span>Comment on {isRange ? 'lines' : 'line'} <strong>{isRange ? `${startLabel}–${endLabel}` : endLabel}</strong></span>
            <span className="pull-diff-pending__badge">Pending</span>
          </summary>
          {editBody === null ? <>
            <div className="pull-diff-pending__body"><MarkdownBody body={comment.body} repository={repository} suggestion={suggestion} /></div>
            <footer className="pull-diff-pending__actions">
              <button type="button" onClick={() => onEdit(comment.body)}>Edit</button>
              <button type="button" className="is-destructive" onClick={onDiscard}>Discard</button>
            </footer>
          </> : <form className="pull-diff-review pull-diff-pending__editor" onSubmit={event => {
            event.preventDefault();
            if (editBody.trim()) onSave();
          }}>
            <textarea autoFocus aria-label="Edit pending review comment" value={editBody}
              onChange={event => onEdit(event.target.value)} />
            <footer>
              <button type="button" onClick={() => onEdit(null)}>Cancel</button>
              <button type="submit" className="is-primary" disabled={!editBody.trim()}>Save</button>
            </footer>
          </form>}
        </details>
      </td>
    </tr>
  );
}

export function updatePendingReviewComment(
  comments: InlineReviewCommentDto[],
  target: InlineReviewCommentDto,
  body: string | null,
) {
  if (body === null) return comments.filter(comment => comment !== target);
  if (!body.trim()) return comments;
  return comments.map(comment => comment === target ? { ...comment, body: body.trim() } : comment);
}

export async function submitInlineReviewComment(
  bridge: Pick<Bridge, 'postPrReviewComment'>,
  repo: string,
  number: number,
  comment: InlineReviewCommentDto,
  onDetailChange: (detail: PullRequestDetailDto) => void,
) {
  onDetailChange(await bridge.postPrReviewComment(repo, number, comment));
}

type ReviewSide = 'LEFT' | 'RIGHT';

/** Stands in while a file card has no review; its actions are unreachable without one. */
const PLACEHOLDER_REVIEW = { id: '', repo: '', number: 0, draft: null } as unknown as AgentReviewDto;
type LineSelection = { side: ReviewSide; start: number; end: number; rowStart: number; rowEnd: number };
type ReviewTarget = { side: ReviewSide; line: number };
type DragAnchor = {
  side: ReviewSide;
  line: number;
  oldLine: number | null;
  newLine: number | null;
  row: number;
};
type DisplayRow = DiffRow & { key: string };

export function reviewTarget(oldLine: number | null, newLine: number | null): ReviewTarget | null {
  if (newLine !== null) return { side: 'RIGHT', line: newLine };
  if (oldLine !== null) return { side: 'LEFT', line: oldLine };
  return null;
}

function pendingReviewCommentsForLine(
  comments: InlineReviewCommentDto[],
  path: string,
  oldLine: number | null,
  newLine: number | null,
) {
  return comments.filter(comment => comment.path === path
    && (comment.side === 'LEFT' ? comment.line === oldLine : comment.line === newLine));
}

export function nextLineSelection(
  previous: LineSelection | null,
  side: ReviewSide,
  line: number,
  extend: boolean,
  row: number,
): LineSelection {
  if (!extend || previous === null || previous.side !== side) return { side, start: line, end: line, rowStart: row, rowEnd: row };
  return {
    side,
    start: Math.min(previous.start, line),
    end: Math.max(previous.end, line),
    rowStart: Math.min(previous.rowStart, row),
    rowEnd: Math.max(previous.rowEnd, row),
  };
}

export function draggedLineSelection(
  anchor: DragAnchor | null,
  oldLine: number | null,
  newLine: number | null,
  row: number,
): LineSelection | null {
  if (anchor === null) return null;
  const line = anchor.side === 'LEFT' ? oldLine : newLine;
  if (line !== null) {
    return {
      side: anchor.side,
      start: Math.min(anchor.line, line),
      end: Math.max(anchor.line, line),
      rowStart: Math.min(anchor.row, row),
      rowEnd: Math.max(anchor.row, row),
    };
  }

  const otherSide = anchor.side === 'LEFT' ? 'RIGHT' : 'LEFT';
  const otherAnchorLine = otherSide === 'LEFT' ? anchor.oldLine : anchor.newLine;
  const otherLine = otherSide === 'LEFT' ? oldLine : newLine;
  if (otherAnchorLine === null || otherLine === null) return null;
  return {
    side: otherSide,
    start: Math.min(otherAnchorLine, otherLine),
    end: Math.max(otherAnchorLine, otherLine),
    rowStart: Math.min(anchor.row, row),
    rowEnd: Math.max(anchor.row, row),
  };
}

function sourceLines(content: string | null | undefined) {
  if (!content) return [];
  const lines = content.replace(/\r\n/g, '\n').split('\n');
  if (lines[lines.length - 1] === '') lines.pop();
  return lines;
}

function changeBlocks(additions: number, deletions: number): Array<'add' | 'del' | 'empty'> {
  const total = additions + deletions;
  if (total === 0) return Array.from({ length: 5 }, () => 'empty');
  const added = Math.round((additions / total) * 5);
  return Array.from({ length: 5 }, (_, index) => index < added ? 'add' : 'del');
}

/** Normalizes both git letter statuses (M/A/D/R) and GitHub word statuses
 *  ("modified", "removed", …) to one status badge. First-letter alone would
 *  collide: "removed" and "renamed" both start with R. */
function fileStatusBadge(status: string): { letter: string; kind: string; title: string } {
  const normalized = status.toLowerCase();
  if (normalized === 'a' || normalized === 'added') return { letter: 'A', kind: 'added', title: 'added' };
  if (normalized === 'd' || normalized === 'removed' || normalized === 'deleted') {
    return { letter: 'D', kind: 'removed', title: 'removed' };
  }
  if (normalized === 'r' || normalized === 'renamed') return { letter: 'R', kind: 'renamed', title: 'renamed' };
  if (normalized === 'm' || normalized === 'modified' || normalized === 'changed') {
    return { letter: 'M', kind: 'modified', title: 'modified' };
  }
  return { letter: status.charAt(0).toUpperCase() || '?', kind: 'other', title: status };
}
