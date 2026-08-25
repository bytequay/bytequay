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
import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { parseUnifiedDiff, type DiffRow } from '../diffParse';
import type {
  Bridge,
  DiffCommitDto,
  DiffFileContentDto,
  DiffFileDto,
  InlineReviewCommentDto,
  PullRequestDetailDto,
} from '../types';
import { useResizablePane } from '../useResizablePane';
import { remoteErrorMessage } from './PullOverview';

export type CommitRange = { start: number; end: number } | null;

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

export function DiffViewer({
  files,
  commits = [],
  commitRange = null,
  loading = false,
  onCommitRangeChange,
  review,
  loadFileContent,
}: DiffViewerProps) {
  const [treeOpen, setTreeOpen] = useState(true);
  const [selected, setSelected] = useState(files[0]?.filename ?? null);
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileTree = useResizablePane(260, 160, 480);
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
    const container = scrollRef.current;
    const card = container?.querySelector(`[data-file-card="${CSS.escape(filename)}"]`);
    if (!container || !card) return;
    const top = card.getBoundingClientRect().top - container.getBoundingClientRect().top
      + container.scrollTop - 12;
    container.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
  };

  const toggle = (filename: string) => setClosed(previous => {
    const next = new Set(previous);
    if (next.has(filename)) next.delete(filename);
    else next.add(filename);
    return next;
  });

  return (
    <div className="pull-changes">
      <div className="pull-changes__toolbar">
        <button
          type="button"
          className={treeOpen ? 'is-active' : ''}
          onClick={() => setTreeOpen(value => !value)}
          aria-label="Toggle changed files"
          title="Toggle changed files"
        >
          <PanelIcon />
        </button>
        <strong>{files.length} changed {files.length === 1 ? 'file' : 'files'}</strong>
        {onCommitRangeChange && (
          <CommitPicker commits={commits} range={commitRange} onChange={onCommitRangeChange} />
        )}
        {(review?.pendingComments.length ?? 0) > 0 && (
          <span className="pull-changes__pending">{review?.pendingComments.length} pending</span>
        )}
        <span className="pull-changes__totals">
          <span>+{totals.additions}</span>
          <span>−{totals.deletions}</span>
        </span>
      </div>
      <div className="pull-changes__panes">
        {treeOpen && (
          <nav
            className="pull-file-tree"
            aria-label="Changed files"
            style={{ width: fileTree.width, flexBasis: fileTree.width }}
          >
            {files.map(file => {
              const segments = file.filename.split('/');
              const name = segments.pop() ?? file.filename;
              const badge = fileStatusBadge(file.status);
              return (
                <button
                  type="button"
                  key={file.filename}
                  className={selected === file.filename ? 'is-active' : ''}
                  onClick={() => pick(file.filename)}
                  title={file.filename}
                >
                  <i className={`pull-file-tree__badge is-${badge.kind}`} title={badge.title}>
                    {badge.letter}
                  </i>
                  <span className="pull-file-tree__name">
                    <strong>{name}</strong>
                    {segments.length > 0 && <small>{segments.join('/')}</small>}
                  </span>
                  <span className="pull-file-tree__stats">
                    {file.additions > 0 && <span className="is-add">+{file.additions}</span>}
                    {file.deletions > 0 && <span className="is-del">−{file.deletions}</span>}
                  </span>
                </button>
              );
            })}
          </nav>
        )}
        {treeOpen && (
          <div
            className={`pull-file-tree__resize${fileTree.resizing ? ' is-resizing' : ''}`}
            role="separator"
            aria-label="Resize changed files"
            aria-orientation="vertical"
            title="Drag to resize"
            onMouseDown={fileTree.startResize}
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
              loadFileContent={loadFileContent}
              open={!closed.has(file.filename)}
              onToggle={() => toggle(file.filename)}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function CommitPicker({ commits, range, onChange }: {
  commits: DiffCommitDto[];
  range: CommitRange;
  onChange: (range: CommitRange) => void;
}) {
  if (commits.length === 0) return null;
  const start = range?.start ?? 0;
  const end = range?.end ?? commits.length - 1;
  const label = range === null
    ? 'All commits'
    : start === end
      ? commits[start].sha.slice(0, 7)
      : `${commits[start].sha.slice(0, 7)}…${commits[end].sha.slice(0, 7)}`;

  return (
    <details className="pull-commit-picker">
      <summary>Commits: {label}</summary>
      <div className="pull-commit-picker__menu">
        <button type="button" className={range === null ? 'is-active' : ''} onClick={() => onChange(null)}>
          All commits
        </button>
        <label>
          From
          <select value={start} onChange={event => {
            const nextStart = Number(event.target.value);
            onChange({ start: nextStart, end: Math.max(nextStart, end) });
          }}>
            {commits.map((commit, index) => (
              <option value={index} key={commit.sha}>{index + 1}. {commit.message ?? commit.sha.slice(0, 7)}</option>
            ))}
          </select>
        </label>
        <label>
          To
          <select value={end} onChange={event => onChange({ start, end: Number(event.target.value) })}>
            {commits.map((commit, index) => (
              <option value={index} disabled={index < start} key={commit.sha}>
                {index + 1}. {commit.message ?? commit.sha.slice(0, 7)}
              </option>
            ))}
          </select>
        </label>
        <small>Commit ranges are consecutive.</small>
      </div>
    </details>
  );
}

function DiffFileCard({
  file,
  baseSha,
  headSha,
  review,
  loadFileContent,
  open,
  onToggle,
}: {
  file: DiffFileDto;
  baseSha?: string;
  headSha?: string;
  review?: ReviewOptions;
  loadFileContent?: DiffViewerProps['loadFileContent'];
  open: boolean;
  onToggle: () => void;
}) {
  const hunks = useMemo(() => parseUnifiedDiff(file.patch), [file.patch]);
  const blocks = changeBlocks(file.additions, file.deletions);
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

  const selectLine = (side: ReviewSide, line: number, extend: boolean) => {
    setReviewError(null);
    setSelection(previous => nextLineSelection(previous, side, line, extend));
  };

  const startLineSelection = (
    side: ReviewSide,
    line: number,
    extend: boolean,
    oldLine: number | null,
    newLine: number | null,
  ) => {
    dragAnchor.current = { side, line, oldLine, newLine };
    setSelectingRange(true);
    selectLine(side, line, extend);
  };

  const extendLineSelection = (oldLine: number | null, newLine: number | null) => {
    const anchor = dragAnchor.current;
    const next = draggedLineSelection(anchor, oldLine, newLine);
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

  const addPending = () => {
    const value = comment();
    if (value === null || review === undefined) return;
    review.onPendingCommentsChange([...review.pendingComments, value]);
    setReviewBody('');
    setSelection(null);
  };

  const postNow = async () => {
    const value = comment();
    if (value === null || review === undefined) return;
    setPosting(true);
    setReviewError(null);
    try {
      await submitInlineReviewComment(
        window.bridge, review.repo, review.number, value, review.onDetailChange);
      setReviewBody('');
      setSelection(null);
    }
    catch (error) {
      setReviewError(remoteErrorMessage(error));
    }
    finally {
      setPosting(false);
    }
  };

  const codeRow = (row: DisplayRow) => {
    const oldLine = row.oldLine;
    const newLine = row.newLine;
    const leftSelected = oldLine !== null && isSelected(selection, 'LEFT', oldLine);
    const rightSelected = newLine !== null && isSelected(selection, 'RIGHT', newLine);
    const composerAfter = selection !== null
      && (selection.side === 'LEFT' ? row.oldLine : row.newLine) === selection.end;
    return (
      <Fragment key={row.key}>
        <tr className={`is-${row.kind}${leftSelected || rightSelected ? ' is-selected' : ''}`}
          onMouseEnter={() => extendLineSelection(oldLine, newLine)}>
          <td className={`is-line${leftSelected ? ' is-selected' : ''}`}>
            {oldLine !== null && canReview && (
              <button type="button"
                onMouseDown={event => {
                  event.preventDefault();
                  startLineSelection('LEFT', oldLine, event.shiftKey, oldLine, newLine);
                }}
                onKeyDown={event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  selectLine('LEFT', oldLine, event.shiftKey);
                }}>{oldLine}</button>
            )}
            {oldLine !== null && !canReview && oldLine}
          </td>
          <td className={`is-line${rightSelected ? ' is-selected' : ''}`}>
            {newLine !== null && canReview && (
              <button type="button"
                onMouseDown={event => {
                  event.preventDefault();
                  startLineSelection('RIGHT', newLine, event.shiftKey, oldLine, newLine);
                }}
                onKeyDown={event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  selectLine('RIGHT', newLine, event.shiftKey);
                }}>{newLine}</button>
            )}
            {newLine !== null && !canReview && newLine}
          </td>
          <td className="is-source">
            <span>{row.kind === 'add' ? '+' : row.kind === 'del' ? '−' : ' '}</span>{row.content || ' '}
          </td>
        </tr>
        {composerAfter && !selectingRange && (
          <tr className="is-review-composer"><td colSpan={3}>
            <div className="pull-diff-review">
              <strong>{selection.start === selection.end
                ? `Add a comment on line L${selection.start}`
                : `Add a comment on lines L${selection.start} to L${selection.end}`}</strong>
              <textarea value={reviewBody} autoFocus placeholder="Leave a review comment…"
                onChange={event => setReviewBody(event.target.value)} />
              {reviewError && <span role="alert">{reviewError}</span>}
              <footer>
                <button type="button" onClick={() => { setSelection(null); setReviewBody(''); }}>Cancel</button>
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
      <button type="button" className="pull-diff-file__header" onClick={onToggle} aria-expanded={open}>
        <span className={`pull-diff-file__chevron${open ? ' is-open' : ''}`} aria-hidden="true">›</span>
        <span className="pull-diff-file__path">{file.filename}</span>
        <span className="pull-diff-file__status">{file.status}</span>
        <span className="pull-diff-file__stats">
          <span>+{file.additions}</span><span>−{file.deletions}</span>
        </span>
        <span className="pull-diff-file__blocks" aria-hidden="true">
          {blocks.map((kind, index) => <i className={`is-${kind}`} key={index} />)}
        </span>
      </button>
      {open && file.patch === null && (
        <div className="pull-diff-file__empty">Binary file or diff unavailable.</div>
      )}
      {open && file.patch !== null && hunks.length === 0 && (
        <div className="pull-diff-file__empty">No textual diff was returned.</div>
      )}
      {open && hunks.length > 0 && (
        <div className="pull-diff-file__code-scroll">
          {canReview && <p className="pull-diff-file__hint">Drag across line numbers, or Shift-click another line, to select a range.</p>}
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
type LineSelection = { side: ReviewSide; start: number; end: number };
type DragAnchor = {
  side: ReviewSide;
  line: number;
  oldLine: number | null;
  newLine: number | null;
};
type DisplayRow = DiffRow & { key: string };

export function nextLineSelection(
  previous: LineSelection | null,
  side: ReviewSide,
  line: number,
  extend: boolean,
): LineSelection {
  if (!extend || previous === null || previous.side !== side) return { side, start: line, end: line };
  return { side, start: Math.min(previous.start, line), end: Math.max(previous.end, line) };
}

export function draggedLineSelection(
  anchor: DragAnchor | null,
  oldLine: number | null,
  newLine: number | null,
): LineSelection | null {
  if (anchor === null) return null;
  const line = anchor.side === 'LEFT' ? oldLine : newLine;
  if (line !== null) {
    return { side: anchor.side, start: Math.min(anchor.line, line), end: Math.max(anchor.line, line) };
  }

  const otherSide = anchor.side === 'LEFT' ? 'RIGHT' : 'LEFT';
  const otherAnchorLine = otherSide === 'LEFT' ? anchor.oldLine : anchor.newLine;
  const otherLine = otherSide === 'LEFT' ? oldLine : newLine;
  if (otherAnchorLine === null || otherLine === null) return null;
  return {
    side: otherSide,
    start: Math.min(otherAnchorLine, otherLine),
    end: Math.max(otherAnchorLine, otherLine),
  };
}

function isSelected(selection: LineSelection | null, side: ReviewSide, line: number) {
  return selection?.side === side && line >= selection.start && line <= selection.end;
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

function PanelIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="18" height="16" rx="2.2" /><path d="M9 4v16" />
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
