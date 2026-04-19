import { useState } from 'react';
import type { ReviewThreadDto } from '../types';
import Avatar from '../Avatar';
import PolishButtons from '../ai/PolishButtons';
import MarkdownComposer from '../MarkdownComposer';
import { authorAssociationLabel, formatRelativeTime, type ReactionContent } from './utils';
import { ReactionChips } from './Reactions';
import { CommentBodyWithSuggestions, DiffHunk } from './CommentBody';

/**
 * Renders a single review thread plus an inline reply composer. Clicking the
 * thread (or the explicit Reply button) opens a small textarea below the
 * messages; submitting hits {@code POST /prs/review-threads/{id}/reply} via
 * the bridge. The parent supplies an {@code onReply} callback which is
 * responsible for refreshing the PR detail after the reply succeeds.
 */
export function ReviewThreadCard({
  thread,
  prAuthor,
  onReply,
  onReact,
  onSetResolved,
}: {
  thread: ReviewThreadDto;
  prAuthor: string | null;
  onReply: (body: string) => Promise<void>;
  /** Add an emoji reaction to a specific message. Optional — when
   *  omitted, the smiley-add button is hidden (e.g. when the thread
   *  appears in a context where reactions don't make sense). */
  onReact?: (commentGithubId: number, content: ReactionContent) => Promise<void>;
  /** Toggle the thread's resolved state. Optional — omitted when the
   *  GraphQL node id isn't available yet (resolved == null). */
  onSetResolved?: (rootGithubId: number, resolved: boolean) => Promise<void>;
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
  const [folded, setFolded] = useState<boolean>(thread.resolved === true);

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
          onClick={() => setFolded(v => !v)}
          aria-expanded={!folded}
          title={folded ? 'Expand thread' : 'Collapse thread'}
        >
          {folded ? '›' : '⌄'}
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
      {!folded && thread.diffHunk && (
        <DiffHunk hunk={thread.diffHunk} />
      )}
      {!folded && (
        <div className="prc-review-thread__msgs">
          {thread.messages.map((msg) => (
            <div key={msg.githubId} className="prc-review-thread__msg">
              <Avatar login={msg.author ?? ''} size={18} className="prc-review-thread__avatar" />
              <div className="prc-review-thread__msg-body">
                <div className="prc-review-thread__msg-head">
                  {msg.author && <span className="prc-comment-author">{msg.author}</span>}
                  {prAuthor === msg.author
                    ? <span className="prc-comment-role">AUTHOR</span>
                    : authorAssociationLabel(msg.authorAssociation) && (
                      <span className="prc-comment-role prc-comment-role--association">
                        {authorAssociationLabel(msg.authorAssociation)}
                      </span>
                    )}
                  {msg.createdAt && <span className="prc-comment-time">{formatRelativeTime(msg.createdAt)}</span>}
                </div>
                {msg.body && (
                  <CommentBodyWithSuggestions body={msg.body} hunk={thread.diffHunk} />
                )}
                <ReactionChips
                  reactions={msg.reactions}
                  onAddReaction={onReact ? (content) => { void onReact(msg.githubId, content); } : undefined}
                />
              </div>
            </div>
          ))}
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
