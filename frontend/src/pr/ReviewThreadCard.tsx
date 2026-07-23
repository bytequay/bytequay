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
import { useState, type ReactNode } from 'react';
import type { ReviewMessageDto, ReviewThreadDto } from '../types';
import type { MarkdownRepoContext } from '../markdown';
import Avatar from '../Avatar';
import PolishButtons from '../ai/PolishButtons';
import MarkdownComposer from '../MarkdownComposer';
import { authorAssociationLabel, buildQuotedReply, type ReactionContent } from './utils';
import { RelativeTime } from './RelativeTime';
import { CommentActionsMenu, reviewCommentLink } from './CommentActionsMenu';
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
  prHtmlUrl,
  currentUserLogin,
  onReply,
  onReact,
  onSetResolved,
  onEditMessage,
  onDeleteMessage,
  canDeleteMessage,
  repoContext,
  locationLabel,
  renderMessageBody,
  renderMessageBadges,
  avatarLoginFor,
  onLocationClick,
  onAskAgent,
  footerActions,
  compact = false,
  actionsDisabled = false,
}: {
  thread: ReviewThreadDto;
  prAuthor: string | null;
  /** PR url — base for the per-message "Copy link" github.com anchor. */
  prHtmlUrl: string;
  /** Login of the authenticated user. Used to gate the per-message
   *  ✎ Edit affordance — only the message's own author sees it. */
  currentUserLogin?: string | null;
  onReply?: (body: string) => Promise<void>;
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
  /** Delete one of this thread's messages. The parent owns the confirm-
   *  gated bridge call + local-state removal. Per-message visibility is
   *  decided by {@link canDeleteMessage}. */
  onDeleteMessage?: (commentGithubId: number) => void | Promise<void>;
  /** Whether the Delete action shows for a given message — author or
   *  write-access, same rule the top-level comments use. */
  canDeleteMessage?: (author: string | null, githubId: number) => boolean;
  /** Forwarded to the inner {@link EditableMarkdownBody} so {@code #N}
   *  issue chips know which repo they came from. */
  repoContext?: MarkdownRepoContext;
  /** Overrides the file/line label for local PR-level review threads. */
  locationLabel?: string;
  /** When set, the file:line label becomes a button that jumps to the code. */
  onLocationClick?: () => void;
  /** When set, an "⚡ Ask agent" action routes this thread's context into the
   *  owning stage composer. Provided only where a stage composer owns the PR. */
  onAskAgent?: () => void;
  /** Lets local findings keep their richer evidence rendering while using
   *  this same GitHub-shaped thread chrome. */
  renderMessageBody?: (message: ReviewMessageDto, index: number) => ReactNode;
  /** Local-only status pills (for example Local / queued for review). */
  renderMessageBadges?: (message: ReviewMessageDto, index: number) => ReactNode;
  /** Resolve the avatar's GitHub handle for a message when its display author
   *  isn't a real login (local threads show "You"/"brain"). Returns undefined
   *  to fall back to the message author. */
  avatarLoginFor?: (message: ReviewMessageDto, index: number) => string | undefined;
  /** Extra local actions beside Resolve, without changing remote threads. */
  footerActions?: ReactNode;
  /** Compact desktop timeline treatment: full hunk, flat messages, action footer. */
  compact?: boolean;
  /** Prevents mutually exclusive local workflow actions racing each other. */
  actionsDisabled?: boolean;
}) {
  const [resolving, setResolving] = useState(false);
  // Which message is in edit mode (by GitHub id), so the per-message
  // "⋯ → Edit" menu item can open its editor. One at a time.
  const [editingMsgId, setEditingMsgId] = useState<number | null>(null);
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
  const visibleFolded = folded;

  const submit = async () => {
    const trimmed = body.trim();
    if (!trimmed || onReply === undefined || actionsDisabled) return;
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

  /** "Quote reply" for an inline message — drops the quoted body into
   *  this thread's own reply composer (not the top-level box) and opens
   *  it, matching where a thread reply actually posts. */
  const quoteMessage = (quoted: string) => {
    setBody(prev => buildQuotedReply(quoted, prev));
    setReplyExpanded(true);
  };

  const lastMsg = thread.messages[thread.messages.length - 1];
  const summary = lastMsg?.body ? lastMsg.body.replace(/\s+/g, ' ').trim() : '';
  const summaryClipped = summary.length > 80 ? `${summary.slice(0, 80)}…` : summary;
  const replyCount = Math.max(0, thread.messages.length - 1);

  const resolveButton = (shortLabel: boolean) => onSetResolved && thread.resolved != null ? (
    <button
      type="button"
      className={`prc-review-thread__resolve-btn${thread.resolved ? ' prc-review-thread__resolve-btn--unresolve' : ''}`}
      onClick={async () => {
        if (resolving || actionsDisabled) return;
        setResolving(true);
        try {
          await onSetResolved(thread.rootGithubId, !thread.resolved);
        } finally {
          setResolving(false);
        }
      }}
      disabled={resolving || actionsDisabled}
      title={thread.resolved ? 'Mark this conversation unresolved' : 'Mark this conversation resolved'}
    >
      {shortLabel && !thread.resolved && (
        <svg width="15" height="15" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
          <path d="M8 16A8 8 0 1 0 8 0a8 8 0 0 0 0 16Zm3.78-9.72-4.25 4.25a.75.75 0 0 1-1.06 0L4.22 8.28a.75.75 0 0 1 1.06-1.06L7 8.94l3.72-3.72a.75.75 0 1 1 1.06 1.06Z" />
        </svg>
      )}
      {resolving ? '…' : shortLabel
        ? (thread.resolved ? 'Unresolve' : 'Resolve')
        : (thread.resolved ? 'Unresolve conversation' : 'Resolve conversation')}
    </button>
  ) : null;

  const replyEditor = (
    <>
      <MarkdownComposer
        value={body}
        onChange={setBody}
        placeholder="Write a reply"
        rows={4}
        disabled={pending || actionsDisabled}
        autoFocus
        textareaClassName="prc-review-thread__reply-input"
      />
      <div className="prc-review-thread__reply-actions">
        <button
          type="button"
          className="button button--primary"
          onClick={submit}
          disabled={pending || actionsDisabled || !body.trim()}
        >
          {pending ? 'Sending…' : 'Reply'}
        </button>
        <PolishButtons
          value={body}
          onChange={setBody}
          onError={setError}
          disabled={pending || actionsDisabled}
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
  );

  return (
    <article className={`prc-review-thread${thread.resolved === true ? ' prc-review-thread--resolved' : ''}${compact ? ' prc-review-thread--compact' : ''}`}>
      <header className="prc-review-thread__head">
        <button
          type="button"
          className="prc-review-thread__fold"
          onClick={() => setFoldOverride(!folded)}
          aria-expanded={!folded}
          title={folded ? 'Expand thread' : 'Collapse thread'}
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            {folded
              ? <path d="M6.22 3.22a.75.75 0 0 1 1.06 0l4.25 4.25a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 0 1-1.06-1.06L9.94 8 6.22 4.28a.75.75 0 0 1 0-1.06Z" />
              : <path d="M12.78 5.22a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 0 1-1.06 0L3.22 6.28a.75.75 0 0 1 1.06-1.06L8 8.94l3.72-3.72a.75.75 0 0 1 1.06 0Z" />}
          </svg>
        </button>
        <span className="prc-review-thread__loc">
          {onLocationClick !== undefined ? (
            <button
              type="button"
              className="prc-review-thread__loc-btn"
              onClick={onLocationClick}
              title="Jump to this line in the diff"
            >
              <code>{locationLabel ?? `${thread.filePath ?? '?'}${thread.line != null ? `:${thread.line}` : ''}`}</code>
            </button>
          ) : (
            <code>{locationLabel ?? `${thread.filePath ?? '?'}${thread.line != null ? `:${thread.line}` : ''}`}</code>
          )}
        </span>
        {thread.outdated && (
          <span className="prc-review-thread__outdated-pill" title="Anchored to a line that no longer exists in the current diff">outdated</span>
        )}
        {thread.resolved === true && (
          <span className="prc-review-thread__resolved-pill">resolved</span>
        )}
        {visibleFolded && !compact && (
          <span className="prc-review-thread__summary">
            {thread.messages.length} comment{thread.messages.length === 1 ? '' : 's'}
            {lastMsg?.author ? ` · last by ${lastMsg.author}` : ''}
            {summaryClipped ? ` — ${summaryClipped}` : ''}
          </span>
        )}
        {compact && (
          <span className="prc-review-thread__reply-count">
            <svg width="15" height="15" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
              <path d="M1.75 1h8.5A1.75 1.75 0 0 1 12 2.75v5.5A1.75 1.75 0 0 1 10.25 10H6.81l-2.28 2.28A.75.75 0 0 1 3.25 11.75V10h-1.5A1.75 1.75 0 0 1 0 8.25v-5.5A1.75 1.75 0 0 1 1.75 1Zm0 1.5a.25.25 0 0 0-.25.25v5.5c0 .14.11.25.25.25H4a.75.75 0 0 1 .75.75v.69l1.22-1.22a.75.75 0 0 1 .53-.22h3.75a.25.25 0 0 0 .25-.25v-5.5a.25.25 0 0 0-.25-.25h-8.5Z" />
              <path d="M14.5 5.75a.75.75 0 0 1 1.5 0v5.5A1.75 1.75 0 0 1 14.25 13H13v1.25a.75.75 0 0 1-1.28.53L9.94 13H7.75a.75.75 0 0 1 0-1.5h2.5c.2 0 .39.08.53.22l.72.72v-.19a.75.75 0 0 1 .75-.75h2a.25.25 0 0 0 .25-.25v-5.5Z" />
            </svg>
            {replyCount} {replyCount === 1 ? 'reply' : 'replies'}
          </span>
        )}
      </header>
      {!visibleFolded && thread.diffHunk && (() => {
        // Prefer the original-line coordinates (V38). They match the
        // diff_hunk verbatim. Fall back to the current line on legacy
        // rows where the original fields are null.
        const endLine = thread.originalLine ?? thread.line;
        const startLine = thread.originalStartLine ?? thread.startLine ?? endLine;
        return (
          <DiffHunk
            hunk={thread.diffHunk}
            contextFilePath={compact ? (thread.filePath ?? '') : undefined}
            range={
              !compact && endLine != null
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
      {!visibleFolded && (
        <div className="prc-review-thread__msgs">
          {thread.messages.map((msg, index) => {
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
                <Avatar login={avatarLoginFor?.(msg, index) ?? msg.author ?? ''} size={compact ? 24 : 20} className="prc-review-thread__avatar" />
                <div className="prc-review-thread__msg-body">
                  <div className="prc-review-thread__msg-head">
                    <span className="prc-review-thread__msg-head-left">
                      {msg.author && <span className="prc-comment-author">{msg.author}</span>}
                      {msg.createdAt && (
                        <RelativeTime className="prc-comment-time" timestamp={msg.createdAt} />
                      )}
                    </span>
                    <span className="prc-review-thread__msg-head-right">
                      {!compact && associationLabel && (
                        <span className="prc-comment-role prc-comment-role--association">
                          {associationLabel}
                        </span>
                      )}
                      {!compact && isPrAuthor && (
                        <span className="prc-comment-role">AUTHOR</span>
                      )}
                      {renderMessageBadges?.(msg, index)}
                      {!compact && msg.githubId > 0 && (
                        <CommentActionsMenu
                          linkHref={reviewCommentLink(prHtmlUrl, msg.githubId)}
                          onQuote={msg.body ? () => quoteMessage(msg.body!) : undefined}
                          onEdit={onEditMessage && msg.body && currentUserLogin && currentUserLogin === msg.author
                            ? () => setEditingMsgId(msg.githubId)
                            : undefined}
                          onDelete={onDeleteMessage && canDeleteMessage?.(msg.author, msg.githubId)
                            ? () => onDeleteMessage(msg.githubId)
                            : undefined}
                        />
                      )}
                    </span>
                  </div>
                  {renderMessageBody !== undefined
                    ? renderMessageBody(msg, index)
                    : msg.body && (
                    <EditableMarkdownBody
                      body={msg.body}
                      canEdit={!!(onEditMessage && currentUserLogin && currentUserLogin === msg.author)}
                      onSave={(newBody) => onEditMessage!(msg.githubId, newBody)}
                      editing={editingMsgId === msg.githubId}
                      onEditingChange={(v) => setEditingMsgId(v ? msg.githubId : null)}
                      renderViewSlot={(b) => <CommentBodyWithSuggestions body={b} hunk={thread.diffHunk} />}
                      repoContext={repoContext}
                    />
                  )}
                  <ReactionChips
                    reactions={msg.reactions}
                    onAddReaction={onReact ? (content) => { void onReact(msg.githubId, content); } : undefined}
                    addIcon={compact ? (
                      <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" aria-hidden="true">
                        <circle cx="7" cy="8" r="5" />
                        <path d="M5 9.5c.8 1 3.2 1 4 0M5.2 6.5h.01M8.8 6.5h.01M12.5 1.5v3M11 3h3" />
                      </svg>
                    ) : undefined}
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
      {!visibleFolded && (onReply !== undefined || onSetResolved !== undefined
          || onAskAgent !== undefined || footerActions !== undefined) && (
        <div className={`prc-review-thread__reply${compact ? ' prc-review-thread__reply--compact' : ' prc-review-thread__reply--inline'}`}>
          {/* Always-visible reply box (avatar + input) matching github.com;
              clicking the stub expands the full Write/Preview composer. */}
          {onReply !== undefined && (
            <div className="prc-review-thread__reply-row">
              <Avatar
                login={currentUserLogin ?? ''}
                size={compact ? 24 : 28}
                className="prc-review-thread__reply-avatar"
              />
              {!replyExpanded && body.length === 0 ? (
                <input
                  type="text"
                  className="prc-review-thread__reply-stub-input"
                  placeholder="Reply…"
                  onFocus={() => { if (!actionsDisabled) setReplyExpanded(true); }}
                  onClick={() => { if (!actionsDisabled) setReplyExpanded(true); }}
                  disabled={actionsDisabled}
                  readOnly
                />
              ) : (
                <div className="prc-review-thread__reply-editor">{replyEditor}</div>
              )}
            </div>
          )}
          {error && <div className="pr-comment-box__error">{error}</div>}
          {/* Resolve / Unresolve on its own row under the reply box, with the
              "X marked this conversation as resolved" attribution — github.com
              placement. Button text mirrors "Resolve/Unresolve conversation". */}
          {(footerActions !== undefined || onAskAgent !== undefined
              || (onSetResolved !== undefined && thread.resolved != null)) && (
            <div className="prc-review-thread__resolve-row">
              {resolveButton(false)}
              {onAskAgent !== undefined && (
                <button
                  type="button"
                  className="prc-review-thread__resolve-btn prc-review-thread__ask-agent-btn"
                  onClick={onAskAgent}
                  title="Route this comment into the agent composer"
                >
                  ⚡ Ask agent
                </button>
              )}
              {thread.resolved === true && thread.resolvedBy != null && thread.resolvedBy !== '' && (
                <span className="prc-review-thread__resolved-attr">
                  <strong>{thread.resolvedBy}</strong> marked this conversation as resolved.
                </span>
              )}
              {footerActions}
            </div>
          )}
        </div>
      )}
    </article>
  );
}
