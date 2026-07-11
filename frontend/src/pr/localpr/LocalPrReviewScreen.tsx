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
import { useMemo, useState } from 'react';
import type { AnchorSide, RowDecoration } from '../../diff/DiffFileList';
import { DiffReviewShell, type DiffReviewExtraTab } from '../../diff/DiffReviewShell';
import { ExpandableFileDiffBody } from '../../diff/ExpandableFileDiffBody';
import {
  DiffInlineComments, diffInlineCommentFromLocalPr, isPendingLocalComment, rangeLabel,
} from '../../diff/DiffInlineComments';
import { PendingCommentsList } from '../../diff/PendingCommentsList';
import { commitSubject, formatShortSha } from '../../diff/commitDisplay';
import { formatRelativeTime } from '../utils';
import { useDiffRangeComposer } from '../../diff/useDiffRangeComposer';
import { SubmitReviewDrawer, type ReviewVerdict } from '../../pages/SubmitReviewDrawer';
import type { DiffFileDto } from '../../types';
import type { LocalPRComment, LocalPRCommit } from '../../types/localPr';

/** Read-only commit list for the Review tab's Commits view — this page's
 *  diff is always the cumulative local PR (no per-commit scoping), so unlike
 *  {@link CommitsColumn} there's no selection state to wire, just the list. */
function LocalCommitsList({ commits }: { commits: LocalPRCommit[] }) {
  if (commits.length === 0) {
    return <div className="diff-viewer__empty">No commits yet.</div>;
  }
  return (
    <div className="diff-viewer__commits-list">
      {commits.map(c => (
        <div className="diff-viewer__commit-row" key={c.id}>
          <span className="diff-viewer__commit-text">
            <span className="diff-viewer__commit-subject">{commitSubject(c.message)}</span>
            <span className="diff-viewer__commit-meta">
              <span className="diff-viewer__commit-sha">{formatShortSha(c.sha)}</span>
              {' · '}{formatRelativeTime(new Date(c.authoredAt).toISOString())}
            </span>
          </span>
        </div>
      ))}
    </div>
  );
}

function lineKey(filename: string, side: AnchorSide, ln: number): string {
  return `${filename}:${side}:${ln}`;
}

/**
 * One file's diff body for the local review page. It renders through the
 * shared expandable diff body and injects local {@code file-line} comments
 * through the row overlay hooks. Shift-click / drag-select range handling
 * comes from the shared composer hook used by task and remote PR diffs too.
 */
function LocalFileDiff({
  file, comments, allowLocalComments, fetchFileBlob, onAddComment, onReplyComment, onResolveComment, onDismissComment,
}: {
  file: DiffFileDto;
  comments: LocalPRComment[];
  allowLocalComments: boolean;
  fetchFileBlob?: (path: string) => Promise<{ lines: string[] }>;
  onAddComment?: (
    filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onReplyComment?: (
    parentCommentId: string, filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
}) {
  const {
    composer,
    closeComposer,
    handleRowClick,
    onRowPointerDown,
    onRowPointerEnter,
    isInRange,
  } = useDiffRangeComposer();

  const byLine = useMemo(() => {
    const m = new Map<string, LocalPRComment[]>();
    for (const c of comments) {
      if (c.scope !== 'file-line' || c.filePath !== file.filename || c.lineNumber === null) continue;
      const k = lineKey(file.filename, c.side, c.lineNumber);
      const lineComments = m.get(k);
      if (lineComments !== undefined) lineComments.push(c);
      else m.set(k, [c]);
    }
    return m;
  }, [comments, file.filename]);

  const renderAfterRow = (side: AnchorSide, line: number) => {
    const key = lineKey(file.filename, side, line);
    const lineComments = byLine.get(key) ?? [];
    const composerHere = composer !== null && composer.side === side && composer.line === line;
    if (lineComments.length === 0 && !composerHere) return null;
    return (
      <DiffInlineComments
        comments={lineComments.map(diffInlineCommentFromLocalPr)}
        allowLocalComments={allowLocalComments}
        onAdd={onAddComment !== undefined && composerHere
          ? body => {
            onAddComment(file.filename, composer.side, composer.line, composer.startLine, composer.startSide, body);
            closeComposer();
          }
          : undefined}
        onReply={onReplyComment !== undefined
          ? (comment, body) => {
            if (comment.filePath === null || comment.lineNumber === null) return;
            onReplyComment(
              comment.id,
              comment.filePath,
              comment.side,
              comment.lineNumber,
              comment.startLine ?? undefined,
              comment.startSide ?? undefined,
              body);
          }
          : undefined}
        onResolve={onResolveComment}
        onDismiss={onDismissComment}
        onCancel={closeComposer}
        composingOn={composerHere
          ? rangeLabel(composer.side, composer.line, composer.startLine, composer.startSide)
          : undefined}
      />
    );
  };

  const rowDecoration = (side: AnchorSide, line: number): RowDecoration | null => {
    if (!allowLocalComments) return null;
    const hasComment = (byLine.get(lineKey(file.filename, side, line))?.length ?? 0) > 0;
    return {
      className: (hasComment ? ' has-comment' : '') + (isInRange({ side, line }) ? ' diff-row--in-range' : ''),
      addCommentAffordance: true,
      role: 'button',
      title: 'Comment on this line — shift-click or drag to select a range',
      onClick: (e) => {
        handleRowClick({ side, line }, e.shiftKey, { toggleActive: true });
      },
      onPointerDown: () => onRowPointerDown({ side, line }),
      onPointerEnter: () => onRowPointerEnter({ side, line }),
    };
  };

  return (
    <ExpandableFileDiffBody
      file={file}
      fetchFileBlob={fetchFileBlob}
      renderAfterRow={renderAfterRow}
      rowDecoration={rowDecoration}
    />
  );
}

/**
 * Full-page code-diff review for a LOCAL PR — the same two-column shape as the
 * remote PR diff (changed-files tree on the left, continuous diff on the
 * right), reusing the exact same components and CSS. It carries no
 * remote-review machinery (AI sidebar, commits lane, GitHub publish); the only
 * write path is the local {@code file-line} comments.
 */
export function LocalPrReviewScreen({
  title, files, comments, commits = [], allowLocalComments = false,
  onAddComment, onReplyComment, onResolveComment, onDismissComment,
  onBack, error = null, fetchFileBlob, onSubmitReview, submittingReview = false,
  embedded = false, showAuxTabs = true,
}: {
  title: string;
  /** Null = still loading. Empty array = nothing changed. */
  files: DiffFileDto[] | null;
  comments: LocalPRComment[];
  /** Read-only — this page's diff is always the cumulative local PR, no
   *  per-commit scoping (unlike the task code-diff page's Commits tab). */
  commits?: LocalPRCommit[];
  allowLocalComments?: boolean;
  fetchFileBlob?: (path: string) => Promise<{ lines: string[] }>;
  onAddComment?: (
    filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onReplyComment?: (
    parentCommentId: string, filePath: string, side: AnchorSide, line: number,
    startLine: number | undefined, startSide: AnchorSide | undefined, body: string,
  ) => void;
  onResolveComment?: (commentId: string) => void;
  onDismissComment?: (commentId: string) => void;
  onBack: () => void;
  error?: string | null;
  /** Submits the reviewer's body/verdict via the same Submit-review drawer
   *  the task brain's top bar uses — undefined hides the toolbar button, since
   *  this full-page takeover otherwise has no way to reach it. */
  onSubmitReview?: (body: string, verdict: ReviewVerdict) => void;
  submittingReview?: boolean;
  embedded?: boolean;
  showAuxTabs?: boolean;
}) {
  const [submitReviewOpen, setSubmitReviewOpen] = useState(false);
  const [activeTab, setActiveTab] = useState('files');
  const pending = useMemo(() => comments.filter(isPendingLocalComment), [comments]);

  const extraTabs: DiffReviewExtraTab[] = showAuxTabs ? [
    { key: 'commits', label: 'Commits', count: commits.length, content: <LocalCommitsList commits={commits} /> },
    ...(onSubmitReview !== undefined ? [{
      key: 'review',
      label: 'Review',
      count: pending.length,
      content: (
        <div className="diff-viewer__review-tab">
          <PendingCommentsList
            comments={pending.map(diffInlineCommentFromLocalPr)}
            onRemove={onDismissComment}
            emptyHint={(
              <>No pending comments yet.<br />Click a line in the diff to add one.</>
            )}
          />
        </div>
      ),
    }] : []),
  ] : [];

  return (
    <>
      <DiffReviewShell
        title={title}
        files={files}
        error={error}
        onBack={onBack}
        showToolbar={!embedded}
        initialFilesWidth={embedded ? 220 : undefined}
        maxFilesWidth={embedded ? 360 : undefined}
        extraTabs={extraTabs}
        activeTab={activeTab}
        onTabChange={setActiveTab}
        renderFileBody={file => (
          <LocalFileDiff
            file={file}
            comments={comments}
            allowLocalComments={allowLocalComments}
            fetchFileBlob={fetchFileBlob}
            onAddComment={onAddComment}
            onReplyComment={onReplyComment}
            onResolveComment={onResolveComment}
            onDismissComment={onDismissComment}
          />
        )}
        toolbarActions={onSubmitReview !== undefined && (
          <div className="diff-viewer__review-actions">
            <button
              type="button"
              className="button button--ai"
              disabled
              title="AI Review isn't wired up for local PRs yet."
            >
              ✨ AI Review
            </button>
            <div className="diff-viewer__submit-wrap">
              <button
                type="button"
                className="button button--submit"
                onClick={() => setSubmitReviewOpen(true)}
                disabled={submittingReview}
              >
                {submittingReview ? 'Submitting…' : 'Submit review'}
                {pending.length > 0 && <span className="button--submit__count">{pending.length}</span>}
              </button>
            </div>
          </div>
        )}
      />
      {onSubmitReview !== undefined && (
        <SubmitReviewDrawer
          open={submitReviewOpen}
          submitting={submittingReview}
          pendingComments={pending.map(diffInlineCommentFromLocalPr)}
          onRemovePending={onDismissComment}
          onClose={() => setSubmitReviewOpen(false)}
          onSubmit={(body, verdict) => {
            onSubmitReview(body, verdict);
            setSubmitReviewOpen(false);
          }}
        />
      )}
    </>
  );
}
