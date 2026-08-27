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
import Avatar from '../Avatar';
import { parseUnifiedDiff, type DiffRow } from '../diffParse';
import type {
  Bridge,
  DiffCommitDto,
  DiffFileContentDto,
  DiffFileDto,
  InlineReviewCommentDto,
  PullRequestDetailDto,
  UserProfileDto,
} from '../types';
import { useResizablePane } from '../useResizablePane';
import MarkdownBody from './MarkdownBody';
import { CopyLinkButton, relativeTime, remoteErrorMessage } from './PullOverview';

export type CommitRange = { start: number; end: number } | null;

const SINGLE_FILE_MODE_THRESHOLD = 20;

type PullChangesProps = {
  repo: string;
  number: number;
  files: DiffFileDto[];
  commits: DiffCommitDto[];
  commitRange: CommitRange;
  loading: boolean;
  canReview: boolean;
  pendingComments: InlineReviewCommentDto[];
  onCommitRangeChange: (range: CommitRange) => void;
  onPendingCommentsChange: (comments: InlineReviewCommentDto[]) => void;
  onDetailChange: (detail: PullRequestDetailDto) => void;
};

type ReviewOptions = {
  repo: string;
  number: number;
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
  loadFileContent?: (
    path: string,
    baseSha?: string,
    headSha?: string,
  ) => Promise<DiffFileContentDto>;
};

export default function PullChanges({
  repo,
  number,
  files,
  commits,
  commitRange,
  loading,
  canReview,
  pendingComments,
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
      onCommitRangeChange={onCommitRangeChange}
      review={{
        repo,
        number,
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

/** The signed-in account, for the avatar on the review composer. */
function useViewer(enabled: boolean): UserProfileDto | null {
  const [viewer, setViewer] = useState<UserProfileDto | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    window.bridge.getUserProfile()
      .then(profile => { if (live) setViewer(profile); })
      .catch(() => { /* the composer just falls back to an initial */ });
    return () => { live = false; };
  }, [enabled]);
  return viewer;
}

export function DiffViewer({
  files,
  commits = [],
  commitRange = null,
  loading = false,
  onCommitRangeChange,
  review,
  loadFileContent,
}: DiffViewerProps) {
  const viewer = useViewer(review?.canReview ?? false);
  const [treeOpen, setTreeOpen] = useState(true);
  const [selected, setSelected] = useState(files[0]?.filename ?? null);
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
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

  useEffect(() => {
    if (files.length === 0) setSelected(null);
    else if (!files.some(file => file.filename === selected)) setSelected(files[0].filename);
  }, [files, selected]);

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
      container.scrollTo({ top: Math.max(0, top), behavior: singleFileMode ? 'auto' : 'smooth' });
    };
    if (singleFileMode) requestAnimationFrame(scrollToFile);
    else scrollToFile();
  };

  const toggle = (filename: string) => {
    setClosed(previous => {
      const next = new Set(previous);
      const open = !previous.has(filename) && (!singleFileMode || selected === filename);
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
          <PanelIcon />
          <span>{files.length} changed {files.length === 1 ? 'file' : 'files'}</span>
        </button>
        {onCommitRangeChange && (
          <CommitPicker commits={commits} range={commitRange} onChange={onCommitRangeChange} />
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
          {!loading && files.length === 0 && <div className="hp-empty">No changed files reported.</div>}
          {files.map(file => (
            <DiffFileCard
              key={`${file.filename}:${baseSha ?? 'base'}:${headSha ?? 'head'}`}
              file={file}
              baseSha={baseSha}
              headSha={headSha}
              review={review}
              viewer={viewer}
              loadFileContent={loadFileContent}
              open={!closed.has(file.filename) && (!singleFileMode || selected === file.filename)}
              onToggle={() => toggle(file.filename)}
            />
          ))}
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
              <ChevronIcon />
              <FolderIcon />
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
            <FileDiffIcon kind={fileStatusBadge(item.file.status).kind} />
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
  if (commits.length === 0) return null;
  const selectedCount = range === null ? commits.length : range.end - range.start + 1;

  return (
    <details className="pull-commit-picker">
      <summary aria-label={`Choose commits, ${selectedCount} selected`}>
        <CommitPickerIcon />
        <span>Commits</span>
        <span>{selectedCount}</span>
      </summary>
      <div className="pull-commit-picker__menu">
        <button
          type="button"
          className="pull-commit-picker__all"
          aria-pressed={range === null}
          onClick={() => { if (range !== null) onChange(null); }}
        >
          <span className="pull-commit-picker__check" aria-hidden="true">
            {range === null && <CheckIcon />}
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

function MarkdownToolbar({ onCommand }: { onCommand: (command: MarkdownCommand) => void }) {
  return (
    <div className="pull-diff-review__toolbar">
      {MARKDOWN_GROUPS.map((group, groupIndex) => (
        <Fragment key={groupIndex}>
          {groupIndex > 0 && <span className="pull-diff-review__divider" aria-hidden="true" />}
          {group.map(({ command, label }) => (
            <button type="button" key={command} title={label} aria-label={label}
              // The textarea keeps its selection because the button never takes focus.
              onMouseDown={event => event.preventDefault()}
              onClick={() => onCommand(command)}>
              <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor"
                strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                {MARKDOWN_ICONS[command].map((path, index) => <path d={path} key={index} />)}
              </svg>
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
  viewer,
  loadFileContent,
  open,
  onToggle,
}: {
  file: DiffFileDto;
  baseSha?: string;
  headSha?: string;
  review?: ReviewOptions;
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
  const oldLines = sourceLines(content?.oldContent);
  const newLines = sourceLines(content?.newContent);
  const canReview = review?.canReview ?? false;

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

  const runMarkdownCommand = (command: MarkdownCommand) => {
    const textarea = bodyRef.current;
    if (textarea === null) return;
    const next = applyMarkdownCommand(
      textarea.value, textarea.selectionStart, textarea.selectionEnd, command);
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
    return (
      <Fragment key={displayRow.key}>
        <tr className={`is-${displayRow.kind}${rowSelected ? ' is-selected' : ''}`}
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
        {rowComments.map((comment, index) => (
          <PendingReviewCommentRow
            key={`${comment.side}:${comment.startLine ?? comment.line}:${comment.line}:${index}`}
            comment={comment}
            rowKind={displayRow.kind}
          />
        ))}
        {composerAfter && !selectingRange && (
          <tr className="is-review-composer"><td colSpan={3}>
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
                    className={preview ? '' : 'is-active'} onClick={() => setPreview(false)}>Write</button>
                  <button type="button" role="tab" aria-selected={preview}
                    className={preview ? 'is-active' : ''} onClick={() => setPreview(true)}>Preview</button>
                </div>
                {!preview && <MarkdownToolbar onCommand={runMarkdownCommand} />}
              </div>
              {preview ? (
                <div className="pull-diff-review__preview">
                  {reviewBody.trim()
                    ? <MarkdownBody body={reviewBody} />
                    : <p className="pull-diff-review__nothing">Nothing to preview</p>}
                </div>
              ) : (
                <textarea ref={bodyRef} value={reviewBody} autoFocus placeholder="Leave a review comment…"
                  onChange={event => setReviewBody(event.target.value)} />
              )}
              {reviewError && <span role="alert">{reviewError}</span>}
              <footer>
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
          {canReview && <p className="pull-diff-file__hint">Click + to comment on one line. Drag across line numbers, or Shift-click another line, to select a range.</p>}
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
    </article>
  );
}

function PendingReviewCommentRow({ comment, rowKind }: {
  comment: InlineReviewCommentDto;
  rowKind: DisplayRow['kind'];
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
            <span>Comment on {isRange ? 'lines' : 'line'} <strong>{isRange ? `${startLabel}–${endLabel}` : endLabel}</strong></span>
            <span className="pull-diff-pending__badge">Pending</span>
          </summary>
          <div className="pull-diff-pending__body"><p>{comment.body}</p></div>
        </details>
      </td>
    </tr>
  );
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

function ChevronIcon() {
  return (
    <svg className="pull-file-tree__chevron" width="12" height="12" viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m6 3.5 4.5 4.5L6 12.5" />
    </svg>
  );
}

function FolderIcon() {
  return (
    <svg className="pull-file-tree__folder-icon" width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
      <path d="M1.75 2.5h4.1l1.2 1.5h7.2c.69 0 1.25.56 1.25 1.25v7.5c0 .69-.56 1.25-1.25 1.25H1.75C1.06 14 .5 13.44.5 12.75v-9C.5 3.06 1.06 2.5 1.75 2.5Z" />
    </svg>
  );
}

function FileDiffIcon({ kind }: { kind: string }) {
  return (
    <svg className={`pull-file-tree__file-icon is-${kind}`} width="16" height="16" viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3.25 1.5h5l3.5 3.5v9.5h-8.5z" /><path d="M8.25 1.5V5h3.5M5.5 8h4M7.5 6v4M5.5 12h4" />
    </svg>
  );
}

function PanelIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="18" height="16" rx="2.2" /><path d="M9 4v16" />
    </svg>
  );
}

function CommitPickerIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor"
      strokeWidth="1.5" strokeLinecap="round" aria-hidden="true">
      <path d="M2 8h3m6 0h3" /><circle cx="8" cy="8" r="2.5" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m3 8.5 3 3 7-7" />
    </svg>
  );
}

/** Normalizes both git letter statuses (M/A/D/R) and GitHub word statuses
 *  ("modified", "removed", …) to one status badge. First-letter alone would
 *  collide: "removed" and "renamed" both start with R. */
export function fileStatusBadge(status: string): { letter: string; kind: string; title: string } {
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
