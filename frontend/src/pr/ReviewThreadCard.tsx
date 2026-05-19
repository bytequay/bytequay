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
import { useState } from 'react';
import type { ReviewThreadDto } from '../types';
import Avatar from '../Avatar';
import PolishButtons from '../ai/PolishButtons';
import MarkdownComposer from '../MarkdownComposer';
import { authorAssociationLabel, formatRelativeTime, type ReactionContent } from './utils';
import { ReactionChips } from './Reactions';
import { CommentBodyWithSuggestions, DiffHunk } from './CommentBody';
import { EditableMarkdownBody } from './EditableMarkdownBody';

/**
 * Renders a single review thread plus an inline reply composer. Clicking the
 * thread (or the explicit Reply button) opens a small textarea below the
 * messages; submitting hits {@code POST /prs/review-threads/{id}/reply} via
 * the bridge. The parent supplies an {@code onReply} callback which owns the
 * optimistic local-state patch after the reply succeeds.
 */
export function ReviewThreadCard({
  thread,
  prAuthor,
  currentUserLogin,
  onReply,
  onReact,
  onSetResolved,
  onEditMessage,
}: {
  thread: ReviewThreadDto;
  prAuthor: string | null;
  /** Login of the authenticated user. Used to gate the per-message
   *  ✎ Edit affordance — only the message's own author sees it. */
  currentUserLogin?: string | null;
  onReply: (body: string) => Promise<void>;
  /** Add an emoji reaction to a specific message. Optional — when
   *  omitted, the smiley-add button is hidden (e.g. when the thread
   *  appears in a context where reactions don't make sense). */
  onReact?: (commentGithubId: number, content: ReactionContent) => Promise<void>;
  /** Toggle the thread's resolved state. Optional — omitted when the
   *  GraphQL node id isn't available yet (resolved == null). */
  onSetResolved?: (rootGithubId: number, resolved: boolean) => Promise<void>;
  /** Edit one of this thread's messages (only the message author can
   *  use this). The parent owns the bridge call + local-state patch. */
  onEditMessage?: (commentGithubId: number, newBody: string) => Promise<void>;
}) {
  const [resolving, setResolving] = useState(false);
  const [body, setBody] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Reply composer collapses to a single-line "Reply…" stub by default —
  // expands into the full Write/Preview composer (with PolishButtons + Send)
  // once the user clicks into it. Mirrors github.com's reply UX so resolved
  // threads stay visually compact in long PRs.
  const [replyExpanded, setReplyExpanded] = useState(false);
  // Resolved threads default to folded — same behaviour as github.com.
  // The chevron stays available either way so the user can pop them
  // open / re-collapse mid-review.
  //
  // Derived from props (not a frozen useState initializer) so a late
  // GraphQL refresh — REST alone doesn't carry `resolved`, the value
  // arrives on the next detail fetch — still auto-folds the thread.
  // The override pins the user's manual choice once they touch the
  // chevron, so subsequent refreshes don't re-fold a thread they
  // explicitly expanded.
  const [foldOverride, setFoldOverride] = useState<boolean | null>(null);
  const folded = foldOverride ?? (thread.resolved === true);

  const submit = async () => {
    const trimmed = body.trim();
    if (!trimmed) return;
    setPending(true);
    setError(null);
    try {
      await onReply(trimmed);
      setBody('');
      setReplyExpanded(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setPending(false);
    }
  };

  const cancelReply = () => {
    setBody('');
    setError(null);
    setReplyExpanded(false);
  };

  const lastMsg = thread.messages[thread.messages.length - 1];
  const summary = lastMsg?.body ? lastMsg.body.replace(/\s+/g, ' ').trim() : '';
  const summaryClipped = summary.length > 80 ? `${summary.slice(0, 80)}…` : summary;

  return (
    <article className={`prc-review-thread${thread.resolved === true ? ' prc-review-thread--resolved' : ''}`}>
      <header className="prc-review-thread__head">
        <button
          type="button"
          className="prc-review-thread__fold"
          onClick={() => setFoldOverride(!folded)}
          aria-expanded={!folded}
          title={folded ? 'Expand thread' : 'Collapse thread'}
        >
          {folded ? '▸' : '▾'}
        </button>
        <span className="prc-review-thread__loc">
          <code>{thread.filePath ?? '?'}{thread.line != null ? `:${thread.line}` : ''}</code>
        </span>
        {thread.outdated && (
          <span className="prc-review-thread__outdated-pill" title="Anchored to a line that no longer exists in the current diff">outdated</span>
        )}
        {thread.resolved === true && (
          <span className="prc-review-thread__resolved-pill">resolved</span>
        )}
        {folded && (
          <span className="prc-review-thread__summary">
            {thread.messages.length} comment{thread.messages.length === 1 ? '' : 's'}
            {lastMsg?.author ? ` · last by ${lastMsg.author}` : ''}
            {summaryClipped ? ` — ${summaryClipped}` : ''}
          </span>
        )}
      </header>
      {!folded && thread.diffHunk && (() => {
        // Prefer the original-line coordinates (V38). They match the
        // diff_hunk verbatim. Fall back to the current line on legacy
        // rows where the original fields are null.
        const endLine = thread.originalLine ?? thread.line;
        const startLine = thread.originalStartLine ?? thread.startLine ?? endLine;
        return (
          <DiffHunk
            hunk={thread.diffHunk}
            range={
              endLine != null
                ? {
                    startLine,
                    endLine,
                    side: thread.side === 'LEFT' ? 'LEFT' : 'RIGHT',
                  }
                : undefined
            }
          />
        );
      })()}
      {!folded && (
        <div className="prc-review-thread__msgs">
          {thread.messages.map((msg) => {
            // GitHub-style head row: author + timestamp on the left,
            // role pills on the right (per
            // docs/mockups/issue/pr-details/pr-review-response.png).
            // Show BOTH the association (Member/Contributor/…) and
            // AUTHOR pill when the commenter is the PR author —
            // matches how github.com renders the OP's replies.
            const associationLabel = authorAssociationLabel(msg.authorAssociation);
            const isPrAuthor = !!msg.author && prAuthor === msg.author;
            return (
              <div key={msg.githubId} className="prc-review-thread__msg">
                <Avatar login={msg.author ?? ''} size={20} className="prc-review-thread__avatar" />
                <div className="prc-review-thread__msg-body">
                  <div className="prc-review-thread__msg-head">
                    <span className="prc-review-thread__msg-head-left">
                      {msg.author && <span className="prc-comment-author">{msg.author}</span>}
                      {msg.createdAt && (
                        <span className="prc-comment-time">{formatRelativeTime(msg.createdAt)}</span>
                      )}
                    </span>
                    <span className="prc-review-thread__msg-head-right">
                      {associationLabel && (
                        <span className="prc-comment-role prc-comment-role--association">
                          {associationLabel}
                        </span>
                      )}
                      {isPrAuthor && (
                        <span className="prc-comment-role">AUTHOR</span>
                      )}
                    </span>
                  </div>
                  {msg.body && (
                    <EditableMarkdownBody
                      body={msg.body}
                      canEdit={!!(onEditMessage && currentUserLogin && currentUserLogin === msg.author)}
                      onSave={(newBody) => onEditMessage!(msg.githubId, newBody)}
                      renderViewSlot={(b) => <CommentBodyWithSuggestions body={b} hunk={thread.diffHunk} />}
                    />
                  )}
                  <ReactionChips
                    reactions={msg.reactions}
                    onAddReaction={onReact ? (content) => { void onReact(msg.githubId, content); } : undefined}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}
      {/* Reply composer — see docs/mockups/v2/detail/comment-input.png.
          Collapsed by default to a single-line "Reply…" stub so resolved
          and inactive threads stay visually compact; expands into the
          full Write/Preview composer (with PolishButtons + Send) once
          the user clicks into it. The Resolve / Unresolve button lives
          on its own row underneath, matching github.com's placement. */}
      {!folded && (
        <div className="prc-review-thread__reply prc-review-thread__reply--inline">
          {!replyExpanded && body.length === 0 ? (
            <input
              type="text"
              className="prc-review-thread__reply-stub-input"
              placeholder="Reply…"
              onFocus={() => setReplyExpanded(true)}
              onClick={() => setReplyExpanded(true)}
              readOnly
            />
          ) : (
            <>
              <MarkdownComposer
                value={body}
                onChange={setBody}
                placeholder="Write a reply"
                rows={4}
                disabled={pending}
                autoFocus
                textareaClassName="prc-review-thread__reply-input"
              />
              <div className="prc-review-thread__reply-actions">
                <button
                  type="button"
                  className="button button--primary"
                  onClick={submit}
                  disabled={pending || !body.trim()}
                >
                  {pending ? 'Sending…' : 'Reply'}
                </button>
                <PolishButtons
                  value={body}
                  onChange={setBody}
                  onError={setError}
                  disabled={pending}
                />
                <button
                  type="button"
                  className="pr-comment-box__cancel"
                  onClick={cancelReply}
                  disabled={pending}
                >
                  Cancel
                </button>
              </div>
            </>
          )}
          {error && <div className="pr-comment-box__error">{error}</div>}
          {/* Resolve / Unresolve toggle. Only renders when the backend
              has a GraphQL node id for the thread (i.e. resolved is
              not null) — without it the GraphQL mutation has nothing
              to target. Button text mirrors github.com's
              "Resolve conversation" / "Unresolve conversation". */}
          {onSetResolved && thread.resolved != null && (
            <div className="prc-review-thread__resolve-row">
              <button
                type="button"
                className={`prc-review-thread__resolve-btn${thread.resolved ? ' prc-review-thread__resolve-btn--unresolve' : ''}`}
                onClick={async () => {
                  if (resolving) return;
                  setResolving(true);
                  try {
                    await onSetResolved(thread.rootGithubId, !thread.resolved);
                  } finally {
                    setResolving(false);
                  }
                }}
                disabled={resolving}
                title={thread.resolved ? 'Mark this conversation unresolved' : 'Mark this conversation resolved'}
              >
                {resolving ? '…' : thread.resolved ? 'Unresolve conversation' : 'Resolve conversation'}
              </button>
            </div>
          )}
        </div>
      )}
    </article>
  );
}
