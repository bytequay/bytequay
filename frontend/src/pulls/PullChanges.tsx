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
import { Fragment, type CSSProperties, type MouseEvent as ReactMouseEvent, type RefObject, useEffect, useId, useMemo, useReducer, useRef, useState } from 'react';
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
import { buildFileTree, type FileTreeItem } from './fileTree';
import BetterWordsButton from './BetterWordsButton';
import MarkdownBody, { type SuggestionBase } from './MarkdownBody';
import MarkdownEditor from './MarkdownEditor';
import CopyLinkButton from '../CopyLinkButton';
import { remoteErrorMessage, ReviewThread, useViewer, type PullDetailUpdater } from './PullOverview';
import type { ReviewNavigationTarget } from './agent-review/AgentReviewTab';
import type { AgentReviewDto, ReviewSide } from './agent-review/contracts';
import ReviewFindingCard from './agent-review/ReviewFindingCard';
import { useFindingActions } from './agent-review/useFindingActions';
import { relativeTime } from '../relativeTime';
import LocalReviewThreadCard, { localReviewMatchesRevision } from './local-review/LocalReviewThreadCard';
import type { LocalReviewThreadDto, LocalReviewThreads } from './local-review/types';
import { inlineReviewComment, NO_RANGE_SELECTION, rangeHeading, rangeSelectionReducer } from './lineRangeSelection';

export type CommitRange = { start: number; end: number } | null;

export type DiffAgentComment = {
  comment: InlineReviewCommentDto;
  baseSha: string | null;
  code: string;
};

/**
 * The Agent review findings projected onto this diff.
 *
 * <p>A finding renders at the validated end line of its range, as the same card the Agent review
 * tab draws: one finding ID and one discussion, shown in two places. An outdated finding has no
 * trustworthy location here, so it stays in the tab with its recorded snapshot instead.
 */
export type AgentReviewProjection = {
  review: AgentReviewDto;
  viewer: UserProfileDto | null;
  /** The range Agent review asked to show; a new token re-scrolls even to the same lines. */
  navigation: ReviewNavigationTarget | null;
  onReviewChange: (review: AgentReviewDto) => void;
  onOpenInReview: (findingId: string) => void;
};

const SINGLE_FILE_MODE_THRESHOLD = 20;

export type ReviewThreads = {
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
  localReview?: LocalReviewThreads;
  onAskAgent?: (comment: DiffAgentComment) => Promise<void>;
  onAskAgentDisabled?: string | null;
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
  localReview?: LocalReviewThreads;
  onAskAgent?: (comment: DiffAgentComment) => Promise<void>;
  onAskAgentDisabled?: string | null;
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
  localReview,
  onAskAgent,
  onAskAgentDisabled,
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
      localReview={localReview}
      onAskAgent={onAskAgent}
      onAskAgentDisabled={onAskAgentDisabled}
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
  localReview,
  onAskAgent,
  onAskAgentDisabled,
  loadFileContent,
}: DiffViewerProps) {
  const viewer = useViewer(Boolean(review?.canReview || review?.pendingComments.length || reviewThreads));
  const [treeOpen, setTreeOpen] = useState(false);
  const [selected, setSelected] = useState(files[0]?.filename ?? null);
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
  const [allFilesOpen, setAllFilesOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileTreeId = useId();
  const filePane = useResizablePane(260, 160, 480);
  const treeItems = useMemo(() => buildFileTree(files, file => file.filename), [files]);
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
              localReview={localReview}
              onAskAgent={onAskAgent}
              onAskAgentDisabled={onAskAgentDisabled}
              fullDiff={commitRange === null}
              viewer={viewer}
              loadFileContent={loadFileContent}
              open={isFileOpen(file.filename)}
              onToggle={() => toggle(file.filename)}
            />
          ))}
          {reviewThreads && <UnplacedReviewThreads
            reviewThreads={reviewThreads}
            viewerLogin={viewer?.login}
            activities={reviewThreads.activities.filter(activity => activity.eventType === 'review_thread'
              && !files.some(file => file.filename === activity.payload.path))}
            label="Review comments on files outside this diff"
          />}
          {localReview && <UnplacedLocalReviewThreads localReview={localReview}
            threads={localReview.threads.filter(thread => thread.learningAnchor == null && !files.some(file => file.filename === thread.path))}
            baseSha={baseSha} headSha={headSha} />}
        </div>
      </div>
    </div>
  );
}

function FileTreeItems({ items, selected, onPick, depth = 0 }: {
  items: FileTreeItem<DiffFileDto>[];
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
            aria-label={`${item.path}, ${fileStatusBadge(item.entry.status).title}`}
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
            <FileDiff className={`pull-file-tree__file-icon is-${fileStatusBadge(item.entry.status).kind}`} size={16} strokeWidth={1.5} aria-hidden="true" />
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
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set());

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
    <details className="pull-commit-picker" ref={pickerRef}
      onToggle={event => { if (!event.currentTarget.open) setExpanded(new Set()); }}>
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
            const [firstLine = '', ...rest] = (commit.message ?? '').split(/\r?\n/);
            const subject = firstLine.trim() || 'Untitled commit';
            // Drop the blank line Git puts after the subject, but keep the body's own indentation and blank lines.
            const body = rest.join('\n').replace(/^\s*\n/, '').trimEnd();
            const open = body !== '' && expanded.has(commit.sha);
            const age = relativeTime(commit.authoredAt);
            return (
              <Fragment key={commit.sha}>
                <label className={`pull-commit-picker__row${selected ? ' is-selected' : ''}${open ? ' is-expanded' : ''}`}
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
                  {/* A nested button is the click target, so the label never forwards its click to the checkbox. */}
                  {body === '' ? <span aria-hidden="true" /> : (
                    <button
                      type="button"
                      className="pull-commit-picker__disclosure"
                      aria-expanded={open}
                      aria-label={open ? 'Hide message' : 'Show message'}
                      onClick={() => setExpanded(current => {
                        const next = new Set(current);
                        if (!next.delete(commit.sha)) next.add(commit.sha);
                        return next;
                      })}
                    >
                      <ChevronRight size={14} strokeWidth={1.75} aria-hidden="true" />
                    </button>
                  )}
                  <code>{commit.sha.slice(0, 7)}</code>
                  <span>{subject}</span>
                  {commit.authoredAt && <time dateTime={commit.authoredAt}>{age}</time>}
                </label>
                {open && <pre className="pull-commit-picker__body">{body}</pre>}
              </Fragment>
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

/** Head-side text of new lines start..end from the diff rows, else the loaded head file; null if any line is unknown. */
export function headLines(hunks: Array<{ rows: DiffRow[] }>, newLines: string[], start: number, end: number): string[] | null {
  return diffSideLines(hunks, newLines, 'RIGHT', start, end);
}

function diffSideLines(hunks: Array<{ rows: DiffRow[] }>, source: string[], side: ReviewSide, start: number, end: number): string[] | null {
  const rows = new Map(hunks.flatMap(hunk => hunk.rows).map(row => [side === 'LEFT' ? row.oldLine : row.newLine, row.content] as const));
  const lines: string[] = [];
  for (let line = start; line <= end; line++) {
    const text = rows.get(line) ?? source[line - 1];
    if (text === undefined) return null;
    lines.push(text);
  }
  return lines.length > 0 ? lines : null;
}

const CONTEXT_LINES_PER_EXPAND = 200;

/**
 * Lines shown at the top and bottom of a hidden gap. Each expansion reveals at most 200 more beside the
 * neighboring hunks, so the expander stays over the lines that are still hidden.
 */
function revealedGap(hidden: number, expansions: number, edge: 'leading' | 'between' | 'trailing'): { top: number; bottom: number } {
  const shown = Math.min(hidden, expansions * CONTEXT_LINES_PER_EXPAND);
  const top = edge === 'leading' ? 0 : edge === 'trailing' ? shown : Math.ceil(shown / 2);
  return { top, bottom: shown - top };
}

export type ReviewLineComposerProps = {
  heading: string;
  viewer?: UserProfileDto | null;
  review?: { repo: string; workspaceId?: string };
  canReview: boolean;
  /** Why Add to review and Post now are unavailable on this range, given instead of letting GitHub refuse it. */
  reviewDisabledReason?: string | null;
  body: string;
  onBodyChange: (body: string) => void;
  preview: boolean;
  onPreviewChange: (preview: boolean) => void;
  error: string | null;
  onError: (error: string | null) => void;
  bodyRef: RefObject<HTMLTextAreaElement>;
  suggestion?: SuggestionBase;
  submitting: boolean;
  posting: boolean;
  askAgent?: { sending: boolean; disabledReason?: string | null; onSend: () => void };
  onCancel: () => void;
  onAddPending: () => void;
  onPostNow: () => void;
};

/** The line review-comment card; its state is owned by the caller so a host can keep a half-written comment. */
export function ReviewLineComposer({
  heading,
  viewer,
  review,
  canReview,
  reviewDisabledReason = null,
  body,
  onBodyChange,
  preview,
  onPreviewChange,
  error,
  onError,
  bodyRef,
  suggestion,
  submitting,
  posting,
  askAgent,
  onCancel,
  onAddPending,
  onPostNow,
}: ReviewLineComposerProps) {
  return (
    <MarkdownEditor
      value={body}
      onChange={onBodyChange}
      ariaLabel={heading}
      placeholder={canReview ? 'Leave a review comment…' : 'Ask about this implementation…'}
      repository={review?.repo}
      suggestion={suggestion}
      canSuggest
      preview={preview}
      onPreviewChange={onPreviewChange}
      textareaRef={bodyRef}
      header={<header className="pull-diff-review__header">
        <Avatar login={viewer?.login ?? ''} avatarUrl={viewer?.avatarUrl} size={22}
          allowGitHubFallback={false} />
        <strong>{heading}</strong>
      </header>}
      isPrivate={askAgent !== undefined && !canReview}
      statusMessage={[askAgent?.sending ? null : askAgent?.disabledReason, canReview ? reviewDisabledReason : null].filter(Boolean).join(' ')}
      error={error}
      busy={submitting}
      autoFocus
      onSubmit={() => {
        if (!body.trim()) return;
        if (canReview) {
          if (reviewDisabledReason === null) onPostNow();
        }
        else if (askAgent && !askAgent.disabledReason) askAgent.onSend();
      }}
      // Escape must not throw away a half-written comment.
      onCancel={() => { if (!body.trim()) onCancel(); }}>
      {review && <BetterWordsButton text={body} textareaRef={bodyRef} repository={review.repo}
        workspaceId={review.workspaceId}
        disabled={preview || submitting}
        onError={failure => onError(failure === null ? null : remoteErrorMessage(failure))} />}
      <button type="button" disabled={submitting} onClick={onCancel}>Cancel</button>
      {askAgent && <button type="button" className={canReview ? '' : 'is-primary'}
        disabled={!body.trim() || submitting || Boolean(askAgent.disabledReason)}
        title={askAgent.disabledReason ?? undefined}
        onClick={askAgent.onSend}>{askAgent.sending ? 'Sending…' : 'Send to agent'}</button>}
      {canReview && <>
        <button type="button" disabled={!body.trim() || submitting || reviewDisabledReason !== null} title={reviewDisabledReason ?? undefined}
          onClick={onAddPending}>Add to review</button>
        <button className="is-primary" type="button" disabled={!body.trim() || submitting || reviewDisabledReason !== null}
          title={reviewDisabledReason ?? undefined} onClick={onPostNow}>{posting ? 'Posting…' : 'Post now'}</button>
      </>}
    </MarkdownEditor>
  );
}

function DiffFileCard({
  file,
  baseSha,
  headSha,
  review,
  reviewThreads,
  agentReview,
  localReview,
  onAskAgent,
  onAskAgentDisabled,
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
  localReview?: LocalReviewThreads;
  onAskAgent?: DiffViewerProps['onAskAgent'];
  onAskAgentDisabled?: string | null;
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
  const [expansions, setExpansions] = useState<ReadonlyMap<string, number>>(new Map());
  const [range, dispatchRange] = useReducer(rangeSelectionReducer, NO_RANGE_SELECTION);
  const selection = range.selection;
  const selectingRange = range.anchor !== null;
  // The press can collapse the composer above it, moving rows under a still pointer; only real movement drags.
  const pressPoint = useRef<{ x: number; y: number } | null>(null);
  const dragOverRow = (oldLine: number | null, newLine: number | null, row: number) => (event: ReactMouseEvent) => {
    const press = pressPoint.current;
    if (press !== null && Math.hypot(event.clientX - press.x, event.clientY - press.y) <= 3) return;
    pressPoint.current = null;
    dispatchRange({ type: 'drag', oldLine, newLine, row });
  };
  const [reviewBody, setReviewBody] = useState('');
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [preview, setPreview] = useState(false);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const [posting, setPosting] = useState(false);
  const [sendingToAgent, setSendingToAgent] = useState(false);
  const submissionInFlight = useRef(false);
  const submitting = posting || sendingToAgent;
  const [pendingEdit, setPendingEdit] = useState<{ comment: InlineReviewCommentDto; body: string } | null>(null);
  const oldLines = sourceLines(content?.oldContent);
  const newLines = sourceLines(content?.newContent);
  const canReview = review?.canReview ?? false;
  const canComment = canReview || onAskAgent !== undefined;
  const fileThreads = reviewThreads?.activities.filter(activity =>
    activity.eventType === 'review_thread' && activity.payload.path === file.filename) ?? [];
  const placedThreads = new Set<PullRequestDetailDto['recentActivity'][number]>();
  const fileLocalThreads = localReview?.threads.filter(thread => thread.path === file.filename) ?? [];
  const anchoredLocalThreads = fileLocalThreads.filter(thread => localReviewMatchesRevision(thread, baseSha, headSha)
    && diffSideLines(hunks, thread.side === 'LEFT' ? oldLines : newLines,
      thread.side, thread.startLine ?? thread.line, thread.line)?.join('\n') === thread.code);
  const placedLocalThreads = new Set<string>();
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
  });

  useEffect(() => {
    if (!selectingRange) return;
    const finishSelection = () => dispatchRange({ type: 'finish' });
    const cancelSelection = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      // Captured and handled first, so the screen's own Escape does not also exit zoom or close the pull request.
      event.preventDefault();
      dispatchRange({ type: 'clear' });
    };
    window.addEventListener('mouseup', finishSelection);
    window.addEventListener('blur', finishSelection);
    window.addEventListener('keydown', cancelSelection, true);
    return () => {
      window.removeEventListener('mouseup', finishSelection);
      window.removeEventListener('blur', finishSelection);
      window.removeEventListener('keydown', cancelSelection, true);
    };
  }, [selectingRange]);

  const loadContent = async () => {
    if (content !== null) return content;
    if (loadFileContent === undefined) return null;
    setContentLoading(true);
    setContentError(null);
    try {
      const value = await loadFileContent(file.filename, fullDiff ? undefined : baseSha, fullDiff ? undefined : headSha);
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
    setExpansions(previous => new Map(previous).set(key, (previous.get(key) ?? 0) + 1));
  };

  const selectLine = (side: ReviewSide, line: number, extend: boolean, row: number) => {
    if (submitting) return;
    setReviewError(null);
    dispatchRange({ type: 'select', side, line, extend, row });
  };

  const startLineSelection = (
    side: ReviewSide,
    line: number,
    press: Pick<MouseEvent, 'shiftKey' | 'clientX' | 'clientY'>,
    oldLine: number | null,
    newLine: number | null,
    row: number,
  ) => {
    if (submitting) return;
    setReviewError(null);
    pressPoint.current = { x: press.clientX, y: press.clientY };
    dispatchRange({ type: 'start', side, line, extend: press.shiftKey, oldLine, newLine, row });
  };

  const comment = () => selection === null ? null : inlineReviewComment(file.filename, reviewBody, selection, headSha ?? null);

  // Suggestions change the head file, so deleted (LEFT) lines cannot be suggested.
  const suggestionLines = selection?.side === 'RIGHT' ? headLines(hunks, newLines, selection.start, selection.end) : null;

  const closeComposer = () => {
    setReviewBody('');
    dispatchRange({ type: 'clear' });
    setPreview(false);
  };

  const addPending = () => {
    const value = comment();
    if (value === null || !review?.canReview || submissionInFlight.current) return;
    review.onPendingCommentsChange([...review.pendingComments, value]);
    closeComposer();
  };

  const postNow = async () => {
    const value = comment();
    if (value === null || !review?.canReview || submissionInFlight.current) return;
    submissionInFlight.current = true;
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
      submissionInFlight.current = false;
      setPosting(false);
    }
  };

  const askAgent = async () => {
    const value = comment();
    if (value === null || onAskAgent === undefined || onAskAgentDisabled || submissionInFlight.current) return;
    submissionInFlight.current = true;
    const lines = diffSideLines(hunks, value.side === 'LEFT' ? oldLines : newLines,
      value.side, value.startLine ?? value.line, value.line);
    const gutter = bodyRef.current?.closest('.pull-diff-file')?.querySelector<HTMLButtonElement>(
      `[aria-label="Select ${value.side === 'LEFT' ? 'old' : 'new'} line ${value.line}"]`);
    setSendingToAgent(true);
    setReviewError(null);
    try {
      await onAskAgent({ comment: value, baseSha: baseSha ?? null, code: lines?.join('\n') ?? '' });
      closeComposer();
      requestAnimationFrame(() => gutter?.focus());
    }
    catch (error) {
      setReviewError(remoteErrorMessage(error));
    }
    finally {
      submissionInFlight.current = false;
      setSendingToAgent(false);
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
    const rowLocalThreads = anchoredLocalThreads.filter(thread => thread.line === (thread.side === 'LEFT' ? oldLine : newLine));
    rowLocalThreads.forEach(thread => placedLocalThreads.add(thread.id));
    /** One gutter cell. The selection call takes both line numbers whichever side was clicked. */
    const lineCell = (side: ReviewSide, line: number | null) => (
      <td className="is-line">
        {line !== null && canComment && (
          <button type="button"
            disabled={submitting}
            aria-label={`Select ${side === 'LEFT' ? 'old' : 'new'} line ${line}`}
            onMouseDown={event => {
              if (event.button !== 0) return;
              event.preventDefault();
              startLineSelection(side, line, event, oldLine, newLine, row);
            }}
            onKeyDown={event => {
              if (event.key !== 'Enter' && event.key !== ' ') return;
              event.preventDefault();
              selectLine(side, line, event.shiftKey, row);
            }}>{line}</button>
        )}
        {line !== null && !canComment && line}
      </td>
    );
    return (
      <Fragment key={displayRow.key}>
        <tr
          className={`is-${displayRow.kind}${rowSelected ? ' is-selected' : ''}${inNavigatedRange ? ' is-review-target' : ''}`}
          data-diff-row={rowSide === null || rowLine === null ? undefined : `${file.filename}:${rowSide}:${rowLine}`}
          // Moves count too: an enter dropped inside the 3px press slop fires only once, so the row would otherwise stay unselected.
          onMouseEnter={selectingRange ? dragOverRow(oldLine, newLine, row) : undefined}
          onMouseMove={selectingRange ? dragOverRow(oldLine, newLine, row) : undefined}>
          {lineCell('LEFT', oldLine)}
          {lineCell('RIGHT', newLine)}
          <td className="is-source">
            {canComment && target !== null && (
              <button type="button" className="pull-diff-code__add-comment"
                disabled={submitting}
                aria-label={`Add a comment on ${target.side === 'RIGHT' ? 'new' : 'old'} line ${target.line}`}
                onMouseDown={event => {
                  if (event.button !== 0) return;
                  event.preventDefault();
                  startLineSelection(target.side, target.line, event, oldLine, newLine, row);
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
            <ReviewThread {...reviewThreads} activity={activity} viewerLogin={viewer?.login} showCode={false} />
          </td></tr>
        ))}
        {localReview && rowLocalThreads.map(thread => <tr className="is-review-thread is-local-review-thread" key={`local:${thread.id}`}>
          <td colSpan={3}><LocalReviewThreadCard {...localReview} thread={thread} showCode={false} /></td>
        </tr>)}
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
            <ReviewLineComposer
              heading={rangeHeading(selection)}
              viewer={viewer}
              review={review}
              canReview={canReview}
              body={reviewBody}
              onBodyChange={setReviewBody}
              preview={preview}
              onPreviewChange={setPreview}
              error={reviewError}
              onError={setReviewError}
              bodyRef={bodyRef}
              suggestion={selection.side === 'RIGHT' ? { startLine: selection.start, lines: suggestionLines } : undefined}
              submitting={submitting}
              posting={posting}
              askAgent={onAskAgent === undefined ? undefined : {
                sending: sendingToAgent,
                disabledReason: onAskAgentDisabled,
                onSend: () => { void askAgent(); },
              }}
              onCancel={closeComposer}
              onAddPending={addPending}
              onPostNow={() => { void postNow(); }} />
          </td></tr>
        )}
      </Fragment>
    );
  };

  const contextRows = (key: string, oldStart: number, newStart: number, start: number, end: number) =>
    Array.from({ length: end - start }, (_, offset) => {
      const index = start + offset;
      return codeRow({
        key: `${key}:${index}`,
        kind: 'context',
        oldLine: oldStart + index,
        newLine: newStart + index,
        content: newLines[newStart + index - 1] ?? oldLines[oldStart + index - 1] ?? '',
      });
    });

  let previousOld = 1;
  let previousNew = 1;
  const lastHunk = hunks[hunks.length - 1];
  const trailingOld = lastHunk ? lastHunk.oldStart + lastHunk.oldCount : 1;
  const trailingNew = lastHunk ? lastHunk.newStart + lastHunk.newCount : 1;
  const trailingCount = content === null ? null : Math.max(0, Math.min(
    oldLines.length - trailingOld + 1,
    newLines.length - trailingNew + 1,
  ));
  const trailingShown = revealedGap(trailingCount ?? 0, expansions.get('trailing') ?? 0, 'trailing').top;

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
          <table className="pull-diff-code">
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
              const gap = revealedGap(hiddenLines, expansions.get(gapKey) ?? 0, hunkIndex === 0 ? 'leading' : 'between');
              const remaining = hiddenLines - gap.top - gap.bottom;
              return (
                <Fragment key={`${hunk.header}:${hunkIndex}`}>
                  {contextRows(gapKey, oldStart, newStart, 0, gap.top)}
                  {remaining > 0 && (
                    <tr className="is-gap"><td colSpan={3}>
                      {loadFileContent === undefined
                        ? `↕ ${remaining} unmodified lines`
                        : <button type="button" disabled={contentLoading}
                            onClick={() => { void expand(gapKey); }}>↕ {remaining} unmodified lines</button>}
                    </td></tr>
                  )}
                  {contextRows(gapKey, oldStart, newStart, hiddenLines - gap.bottom, hiddenLines)}
                  <tr className="is-hunk"><td colSpan={3}>{hunk.header}</td></tr>
                  {hunk.rows.map((row, rowIndex) => codeRow({ ...row, key: `${hunkIndex}:${rowIndex}` }))}
                </Fragment>
              );
            })}
            {contextRows('trailing', trailingOld, trailingNew, 0, trailingShown)}
            {loadFileContent !== undefined && (trailingCount === null || trailingShown < trailingCount) && (
              <tr className="is-gap is-trailing"><td colSpan={3}><button type="button" disabled={contentLoading}
                onClick={() => { void expand('trailing'); }}>
                {contentLoading ? 'Loading unchanged lines…' : trailingCount === null
                  ? '↕ Show unchanged lines below'
                  : `↕ ${trailingCount - trailingShown} unmodified lines`}
              </button></td></tr>
            )}
            </tbody>
          </table>
        </div>
      )}
      {open && reviewThreads && <UnplacedReviewThreads
        reviewThreads={reviewThreads}
        viewerLogin={viewer?.login}
        activities={fileThreads.filter(activity => !placedThreads.has(activity))}
        label={fullDiff ? 'Review comments outside the visible lines' : 'Review comments on the full pull request'}
      />}
      {open && localReview && <UnplacedLocalReviewThreads localReview={localReview}
        threads={fileLocalThreads.filter(thread => !placedLocalThreads.has(thread.id))} baseSha={baseSha} headSha={headSha} />}
    </article>
  );
}

function UnplacedLocalReviewThreads({ localReview, threads, baseSha, headSha }: {
  localReview: LocalReviewThreads;
  threads: LocalReviewThreadDto[];
  baseSha?: string;
  headSha?: string;
}) {
  if (threads.length === 0) return null;
  return <section className="pull-diff-threads" aria-label="Local review comments outside the visible lines">
    <p>Local review comments outside the visible lines</p>
    {threads.map(thread => <LocalReviewThreadCard key={thread.id} {...localReview} thread={thread}
      outdated={!localReviewMatchesRevision(thread, baseSha, headSha)} />)}
  </section>;
}

function UnplacedReviewThreads({ reviewThreads, viewerLogin, activities, label }: {
  reviewThreads: ReviewThreads;
  viewerLogin?: string;
  activities: PullRequestDetailDto['recentActivity'];
  label: string;
}) {
  if (activities.length === 0) return null;
  return <section className="pull-diff-threads" aria-label={label}>
    <p>{label}</p>
    {activities.map(activity => <ReviewThread {...reviewThreads} activity={activity} viewerLogin={viewerLogin} key={activity.githubId} />)}
  </section>;
}

type PendingReviewCardProps = {
  comment: InlineReviewCommentDto;
  viewer?: UserProfileDto | null;
  repository: string;
  suggestion?: SuggestionBase;
  editBody: string | null;
  onEdit: (body: string | null) => void;
  onSave: () => void;
  onDiscard: () => void;
};

export function PendingReviewCommentRow({ rowKind, ...card }: PendingReviewCardProps & { rowKind: DisplayRow['kind'] }) {
  return (
    <tr className={`is-pending-review is-${rowKind}`}>
      <td colSpan={3}>
        <PendingReviewCard {...card} />
      </td>
    </tr>
  );
}

export function PendingReviewCard({ comment, viewer, repository, suggestion, editBody, onEdit, onSave, onDiscard }: PendingReviewCardProps) {
  const endLabel = `${comment.side === 'RIGHT' ? 'R' : 'L'}${comment.line}`;
  const isRange = comment.startLine !== null && comment.startLine !== comment.line;
  const startSide = comment.startSide ?? comment.side;
  const startLabel = `${startSide === 'RIGHT' ? 'R' : 'L'}${comment.startLine ?? comment.line}`;

  return (
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

/** Stands in while a file card has no review; its actions are unreachable without one. */
export const PLACEHOLDER_REVIEW = { id: '', repo: '', number: 0, draft: null } as unknown as AgentReviewDto;
type ReviewTarget = { side: ReviewSide; line: number };
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
