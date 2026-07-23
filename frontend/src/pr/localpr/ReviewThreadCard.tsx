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
import { useEffect, useState } from 'react';
import type { ReviewMessageDto, ReviewThreadDto } from '../../types';
import type { LocalPR, LocalPRComment } from '../../types/localPr';
import { MarkdownProse } from '../../threads/MarkdownProse';
import { AgentFindingContent, presentFinding } from '../../review/AgentEvidence';
import type { AgentReviewData } from '../../review/agentReviewTypes';
import { ReviewThreadCard as GitHubReviewThreadCard } from '../ReviewThreadCard';
import { actorRole, displayName } from './prViewMeta';

/**
 * Adapts a persisted local review conversation to the same card used by a
 * live GitHub review thread. Local ids stay synthetic/non-positive so the
 * shared card never exposes GitHub-only edit, reaction, or deep-link menus.
 */
export function ReviewThreadCard({
  pr,
  filePath,
  lineNumber,
  comments,
  resolved: resolvedOverride,
  onResolve,
  onSetResolved,
  onDismiss,
  onReply,
  onAnswerFinding,
  reviewData,
  canPromote = false,
  promoted = false,
  onTogglePromotion,
  compact = false,
  statusLabel,
  onSubmitToDev,
  currentUserLogin,
  onOpenLocation,
}: {
  pr: LocalPR;
  filePath?: string;
  lineNumber?: number;
  /** Root comment first, then direct replies, oldest-first. */
  comments: LocalPRComment[];
  resolved?: boolean;
  /** Legacy one-way resolve callback retained for read-only brain reviews. */
  onResolve?: () => void | Promise<void>;
  onSetResolved?: (resolved: boolean) => void | Promise<unknown>;
  onDismiss?: () => void | Promise<void>;
  onReply?: (rootCommentId: string, body: string) => void | Promise<void>;
  onAnswerFinding?: (findingId: string, body: string) => void | Promise<unknown>;
  reviewData?: AgentReviewData;
  canPromote?: boolean;
  promoted?: boolean;
  onTogglePromotion?: () => void | Promise<unknown>;
  compact?: boolean;
  /** Local workflow state shown on the root message (for example pending or sent to Dev). */
  statusLabel?: string;
  /** Explicitly dispatch this pending local review thread to Development. */
  onSubmitToDev?: () => void | Promise<void>;
  /** GitHub handle of the signed-in user, used to render their real avatar on
   *  their own ("You") messages instead of a placeholder. */
  currentUserLogin?: string | null;
  /** Jump to this comment's line in the Changes diff. Wired only for
   *  file-line comments (a PR-level comment has no line to jump to). */
  onOpenLocation?: (filePath: string, lineNumber: number | null, side: 'LEFT' | 'RIGHT') => void;
}) {
  const [action, setAction] = useState<'resolve' | 'submit' | 'resolve-done' | 'submit-done' | null>(null);
  const [resolutionTarget, setResolutionTarget] = useState<boolean | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const root = comments[0];
  const resolved = resolvedOverride ?? (root !== undefined
    && (root.resolvedAt !== null || root.dismissedAt !== null));
  useEffect(() => {
    if (action === 'resolve-done' && resolutionTarget === resolved) {
      setAction(null);
      setResolutionTarget(null);
    }
    if (action === 'submit-done' && onSubmitToDev === undefined) setAction(null);
  }, [action, onSubmitToDev, resolutionTarget, resolved]);
  if (root === undefined) return null;

  const rootFinding = root.findingId == null || reviewData === undefined
    ? undefined
    : presentFinding(reviewData, root.findingId);
  const commentDisplayName = (comment: LocalPRComment, index: number) =>
    comment.author === 'agent' && index === 0 && comment.findingId != null
      ? 'brain'
      : displayName(comment.author);
  const thread: ReviewThreadDto = {
    rootGithubId: -1,
    filePath: root.filePath ?? filePath ?? null,
    line: root.lineNumber ?? lineNumber ?? null,
    side: root.side,
    diffHunk: null,
    messages: comments.map<ReviewMessageDto>((comment, index) => ({
      githubId: -(index + 1),
      author: commentDisplayName(comment, index),
      body: comment.body,
      createdAt: new Date(comment.createdAt).toISOString(),
      reactions: null,
      reviewId: null,
      authorAssociation: null,
    })),
    resolved,
    resolvedBy: resolved && root.resolvedBy != null && root.resolvedBy !== ''
      ? displayName(root.resolvedBy)
      : null,
    outdated: false,
    startLine: root.startLine,
    startSide: root.startSide,
    originalLine: root.lineNumber ?? lineNumber ?? null,
    originalStartLine: root.startLine,
  };

  // A resolved review conversation can still continue, matching GitHub's
  // thread card. Discarded local drafts stay closed.
  const canReply = root.dismissedAt === null && onReply !== undefined;
  const setResolved = onSetResolved !== undefined || onResolve !== undefined
    ? async (_rootId: number, next: boolean) => {
        if (action !== null) return;
        setAction('resolve');
        setResolutionTarget(next);
        setActionError(null);
        try {
          if (onSetResolved !== undefined) await onSetResolved(next);
          else if (next) await onResolve?.();
          setAction('resolve-done');
        }
        catch {
          setActionError('Could not update this conversation. Try again.');
          setAction(null);
          setResolutionTarget(null);
        }
      }
    : undefined;

  return (
    <GitHubReviewThreadCard
      thread={thread}
      prAuthor={pr.author?.replace(/^@/, '') ?? null}
      prHtmlUrl={pr.remotePrUrl ?? ''}
      currentUserLogin={currentUserLogin}
      compact={compact}
      actionsDisabled={action !== null}
      locationLabel={root.scope === 'pr'
        ? 'Pull request review'
        : `${thread.filePath ?? '?'}${thread.line === null ? '' : `:${thread.line}`}`}
      onLocationClick={root.scope !== 'pr' && thread.filePath !== null && onOpenLocation !== undefined
        ? () => onOpenLocation(thread.filePath!, thread.line, root.side)
        : undefined}
      onReply={canReply ? async body => {
        await onReply(root.id, body);
        if (root.findingId !== null && root.findingId !== undefined) {
          await onAnswerFinding?.(root.findingId, body);
        }
      } : undefined}
      onSetResolved={setResolved}
      avatarLoginFor={(_message, index) => comments[index]?.author === 'you'
        ? (currentUserLogin ?? undefined)
        : undefined}
      renderMessageBadges={(_message, index) => {
        const comment = comments[index];
        if (comment === undefined) return null;
        const role = actorRole(comment.author, pr);
        return (
          <>
            {role === 'agent' && <span className="prc-comment-role">{commentDisplayName(comment, index).toUpperCase()}</span>}
            {comment.origin === 'local' && <span className="prc-comment-role prc-comment-role--local">LOCAL</span>}
            {index === 0 && statusLabel !== undefined && <span className="prc-comment-role prc-comment-role--queued">{statusLabel}</span>}
            {index === 0 && promoted && <span className="prc-comment-role prc-comment-role--queued">REMOTE REVIEW DRAFT</span>}
            {index === 0 && comment.publishedAt !== null && <span className="prc-comment-role">PUBLISHED</span>}
          </>
        );
      }}
      renderMessageBody={(_message, index) => {
        const comment = comments[index];
        if (comment === undefined) return null;
        return index === 0 && rootFinding !== undefined
          ? <AgentFindingContent view={rootFinding} body={comment.body} pending={!resolved && comment.publishedAt === null} />
          : <MarkdownProse text={comment.body} />;
      }}
      footerActions={(canPromote && onTogglePromotion !== undefined)
          || (!resolved && onDismiss !== undefined) || (!resolved && onSubmitToDev !== undefined)
          || actionError !== null ? (
        <>
          {!resolved && onSubmitToDev !== undefined && (
            <button
              type="button"
              className="prc-review-thread__resolve-btn prc-review-thread__promote-btn"
              disabled={action !== null}
              onClick={() => {
                if (action !== null) return;
                setAction('submit');
                setActionError(null);
                void Promise.resolve(onSubmitToDev())
                  .then(() => setAction('submit-done'))
                  .catch(() => {
                    setActionError('Could not send this review to dev. Try again.');
                    setAction(null);
                  });
              }}
            >
              {action === 'submit' ? 'Sending…' : 'Send to dev'}
            </button>
          )}
          {canPromote && onTogglePromotion !== undefined && (
            <button
              type="button"
              className="prc-review-thread__resolve-btn prc-review-thread__promote-btn"
              aria-pressed={promoted}
              onClick={() => { void onTogglePromotion(); }}
            >
              {promoted ? 'Remove from remote review' : 'Add to remote review'}
            </button>
          )}
          {!resolved && onDismiss !== undefined && (
            <button
              type="button"
              className="prc-review-thread__resolve-btn prc-review-thread__discard-btn"
              onClick={() => { void onDismiss(); }}
            >
              Discard local comment
            </button>
          )}
          {actionError !== null && <span role="alert" className="pr-comment-box__error">{actionError}</span>}
        </>
      ) : undefined}
    />
  );
}
