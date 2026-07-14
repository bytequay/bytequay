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
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type { AnchorSide, RowDecoration } from '../../diff/DiffFileList';
import { DiffReviewShell, type DiffReviewExtraTab } from '../../diff/DiffReviewShell';
import { ExpandableFileDiffBody } from '../../diff/ExpandableFileDiffBody';
import {
  DiffInlineComments, diffInlineCommentFromLocalPr, isPendingLocalComment, rangeLabel,
} from '../../diff/DiffInlineComments';
import { InlineReviewThread } from '../../diff/InlineReviewThread';
import { ReviewTabPendingList } from '../../diff/PendingCommentsList';
import { commitSubject, formatShortSha } from '../../diff/commitDisplay';
import { formatRelativeTime } from '../utils';
import { useDiffRangeComposer } from '../../diff/useDiffRangeComposer';
import { SubmitReviewDrawer, type ReviewVerdict } from '../../pages/SubmitReviewDrawer';
import type { DiffFileDto, ReviewThreadDto } from '../../types';
import type { LocalPRComment, LocalPRCommit } from '../../types/localPr';
import type { AgentReviewData } from '../../review/agentReviewTypes';

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

/** Everything {@link InlineReviewThread} needs to render live GitHub review
 *  threads (reply / resolve / unresolve) on this diff. Absent for a PR with no
 *  remote identity — the diff then shows only local draft comments. */
export type GithubThreadContext = {
  threads: ReviewThreadDto[];
  repo: string;
  prNumber: number;
  /** Legacy PR primary key the resolve path prefers; the backend falls back
   *  to resolving it from the thread's own comment id when this is stale. */
  prId: number;
  prAuthor: string | null;
  /** Re-fetch the GitHub feed after a reply / resolve so the thread updates. */
  onChanged: () => void;
};

/**
 * One file's diff body for the local review page. It renders through the
 * shared expandable diff body and injects local {@code file-line} comments
 * through the row overlay hooks. Shift-click / drag-select range handling
 * comes from the shared composer hook used by task and remote PR diffs too.
 */
function LocalFileDiff({
  file, comments, allowLocalComments, fetchFileBlob,
  onAddComment, onReplyComment, onResolveComment, onDismissComment, reviewData, github,
}: {
  file: DiffFileDto;
  comments: LocalPRComment[];
  allowLocalComments: boolean;
  github?: GithubThreadContext;
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
  reviewData?: AgentReviewData;
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
      // Only LOCAL drafts / agent findings render here; GitHub review threads
      // (origin=remote) render via <InlineReviewThread> from the live feed, so
      // including them would double every synced thread on the diff. Once a
      // local draft is published it becomes one of those live threads, so drop
      // it here too when the feed is active — otherwise the same comment shows
      // both as a local draft and as its published thread.
      if (c.scope !== 'file-line' || c.origin !== 'local'
          || c.filePath !== file.filename || c.lineNumber === null
          || (github !== undefined && c.publishedAt !== null)) continue;
      const k = lineKey(file.filename, c.side, c.lineNumber);
      const lineComments = m.get(k);
      if (lineComments !== undefined) lineComments.push(c);
      else m.set(k, [c]);
    }
    return m;
  }, [comments, file.filename, github]);

  // Live GitHub review threads anchored in this file, keyed by side:line so
  // they render under the same rows as local comments. Threads whose line no
  // longer exists (force-push) have no anchor row, so they surface in a
  // separate section above the diff (matching the remote-PR diff screen).
  const threadsByLine = useMemo(() => {
    const m = new Map<string, ReviewThreadDto[]>();
    for (const t of github?.threads ?? []) {
      if (t.filePath !== file.filename || t.line === null || t.outdated) continue;
      const side: AnchorSide = t.side === 'LEFT' ? 'LEFT' : 'RIGHT';
      const k = `${side}:${t.line}`;
      const list = m.get(k);
      if (list !== undefined) list.push(t);
      else m.set(k, [t]);
    }
    return m;
  }, [github?.threads, file.filename]);
  const outdatedThreads = useMemo(
    () => (github?.threads ?? []).filter(t => t.filePath === file.filename && (t.outdated || t.line === null)),
    [github?.threads, file.filename],
  );

  const renderThreads = (side: AnchorSide, line: number) => {
    if (github === undefined) return null;
    const threads = threadsByLine.get(`${side}:${line}`);
    if (threads === undefined || threads.length === 0) return null;
    return threads.map(thread => (
      <InlineReviewThread
        key={thread.rootGithubId}
        thread={thread}
        prAuthor={github.prAuthor}
        repo={github.repo}
        prId={github.prId}
        prNumber={github.prNumber}
        onReplied={github.onChanged}
      />
    ));
  };

  const renderAfterRow = (side: AnchorSide, line: number) => {
    const key = lineKey(file.filename, side, line);
    const lineComments = byLine.get(key) ?? [];
    const composerHere = composer !== null && composer.side === side && composer.line === line;
    const threads = renderThreads(side, line);
    if (lineComments.length === 0 && !composerHere && threads === null) return null;
    return (
      <>
      {threads}
      {(lineComments.length > 0 || composerHere) && (
      <DiffInlineComments
        comments={lineComments.map(comment => diffInlineCommentFromLocalPr(comment, reviewData))}
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
        singleActionLabel={reviewData === undefined ? undefined : 'Comment now'}
      />
      )}
      </>
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
    <>
      {github !== undefined && outdatedThreads.length > 0 && (
        <div className="diff-outdated diff-outdated--inline">
          <div className="diff-outdated__hint">
            Outdated review {outdatedThreads.length === 1 ? 'thread' : 'threads'} — anchored to lines no longer in the diff
          </div>
          {outdatedThreads.map(thread => (
            <InlineReviewThread
              key={thread.rootGithubId}
              thread={thread}
              prAuthor={github.prAuthor}
              repo={github.repo}
              prId={github.prId}
              prNumber={github.prNumber}
              onReplied={github.onChanged}
            />
          ))}
        </div>
      )}
      <ExpandableFileDiffBody
        file={file}
        fetchFileBlob={fetchFileBlob}
        renderAfterRow={renderAfterRow}
        rowDecoration={rowDecoration}
      />
    </>
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
  reviewData, github,
  selectedFindingId, onSelectFinding,
  submitReviewControl, onStartAgentReview,
}: {
  title: string;
  /** Live GitHub review threads to render on the diff (reply / resolve /
   *  unresolve). Omit for a PR with no remote identity. */
  github?: GithubThreadContext;
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
  reviewData?: AgentReviewData;
  selectedFindingId?: string | null;
  onSelectFinding?: (findingId: string, filePath: string | null, lineNumber: number | null) => void;
  submitReviewControl?: ReactNode;
  onStartAgentReview?: () => void;
}) {
  const [submitReviewOpen, setSubmitReviewOpen] = useState(false);
  const [activeTab, setActiveTab] = useState('files');
  const pending = useMemo(() => comments.filter(isPendingLocalComment), [comments]);
  const commentCountByFile = useMemo(() => {
    const counts = new Map<string, number>();
    for (const comment of comments) {
      if (comment.filePath === null || comment.dismissedAt !== null) continue;
      counts.set(comment.filePath, (counts.get(comment.filePath) ?? 0) + 1);
    }
    return counts;
  }, [comments]);

  useEffect(() => {
    if (selectedFindingId == null || activeTab !== 'files') return;
    requestAnimationFrame(() => {
      document.querySelector<HTMLElement>(`[data-finding-id="${CSS.escape(selectedFindingId)}"]`)
        ?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    });
  }, [activeTab, selectedFindingId]);

  const extraTabs: DiffReviewExtraTab[] = showAuxTabs ? [
    { key: 'commits', label: 'Commits', icon: 'commits', count: commits.length, content: <LocalCommitsList commits={commits} /> },
    {
      key: 'review',
      label: 'Review',
      icon: 'review',
      count: pending.length,
      content: (
        <div className="diff-viewer__review-tab">
          <ReviewTabPendingList
            comments={pending.map(comment => diffInlineCommentFromLocalPr(comment, reviewData))}
            onRemove={onDismissComment}
            onJump={comment => {
              setActiveTab('files');
              if (comment.finding !== undefined) onSelectFinding?.(comment.finding.finding.id, comment.filePath, comment.lineNumber);
            }}
            onOpenSubmitPanel={onSubmitReview !== undefined ? () => setSubmitReviewOpen(true) : undefined}
            emptyHint={(
              <>No pending comments yet.<br />Click a line in the diff to add one.</>
            )}
          />
        </div>
      ),
    },
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
            github={github}
            fetchFileBlob={fetchFileBlob}
            onAddComment={onAddComment}
            onReplyComment={onReplyComment}
            onResolveComment={onResolveComment}
            onDismissComment={onDismissComment}
            reviewData={reviewData}
          />
        )}
        fileDecoration={file => {
          const count = commentCountByFile.get(file.filename) ?? 0;
          return count > 0 ? <span className="diff-file-row__comment-count">{count}</span> : null;
        }}
        toolbarActions={submitReviewControl ?? ((onSubmitReview !== undefined || onStartAgentReview !== undefined) && (
          <div className="diff-viewer__review-actions">
            {onStartAgentReview !== undefined && reviewData === undefined && (
              <button type="button" className="button button--ai" onClick={onStartAgentReview}>
                ⚖ Review with agent
              </button>
            )}
            {onSubmitReview !== undefined && <div className="diff-viewer__submit-wrap">
              <button
                type="button"
                className="button button--submit"
                onClick={() => setSubmitReviewOpen(true)}
                disabled={submittingReview}
              >
                {submittingReview ? 'Submitting…' : 'Submit review'}
                {pending.length > 0 && <span className="button--submit__count">{pending.length}</span>}
              </button>
            </div>}
          </div>
        ))}
      />
      {onSubmitReview !== undefined && (
        <SubmitReviewDrawer
          open={submitReviewOpen}
          submitting={submittingReview}
          pendingComments={pending.map(comment => diffInlineCommentFromLocalPr(comment, reviewData))}
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
