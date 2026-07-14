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
import Avatar from '../Avatar';
import { getCached } from '../dataCache';
import { renderMarkdown } from '../markdown';
import MarkdownComposer from '../MarkdownComposer';
import PolishButtons from '../ai/PolishButtons';
import type { ReviewMessageDto, ReviewThreadDto, UserProfileDto } from '../types';

function formatRelative(iso: string | null): string {
  if (!iso) return '';
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.round(hrs / 24)}d ago`;
}

const THREAD_REACTION_EMOJI: Record<string, string> = {
  plusOne: '👍', minusOne: '👎', laugh: '😄', hooray: '🎉',
  confused: '😕', heart: '❤️', rocket: '🚀', eyes: '👀',
};

function ThreadReactions({ reactions }: { reactions: ReviewThreadDto['messages'][number]['reactions'] }) {
  if (!reactions) return null;
  const items = Object.entries(reactions)
    .filter(([, n]) => typeof n === 'number' && n > 0)
    .filter(([k]) => THREAD_REACTION_EMOJI[k] !== undefined);
  if (items.length === 0) return null;
  return (
    <div className="diff-thread__reactions">
      {items.map(([k, n]) => (
        <span key={k} className="diff-thread__reaction" title={`${n}`}>
          <span aria-hidden="true">{THREAD_REACTION_EMOJI[k]}</span>
          <span className="diff-thread__reaction-count">{n}</span>
        </span>
      ))}
    </div>
  );
}

/**
 * Renders one existing per-line GitHub review thread directly under the diff
 * row it anchors to. Shared by the standalone remote-PR diff and the unified
 * PR / task code-diff pages so all three get identical, GitHub-faithful
 * behaviour: fold, reply, resolve / unresolve, resolved attribution, reactions.
 *
 * Layout follows docs/mockups/v2/codereview/comment-layout.png:
 *   1. Top header with the line range, fold chevron, and Resolved /
 *      Outdated pill on the right.
 *   2. Each message: avatar + author/role/time header row + body +
 *      reactions chips below.
 *   3. Bottom: who-resolved attribution (when resolved) and a collapsed
 *      "Write a reply" stub that expands to the full reply composer.
 *
 * Resolved threads default to folded — same behaviour as github.com.
 * Toggle via the chevron at the top.
 */
export function InlineReviewThread({
  thread,
  prAuthor,
  repo,
  prId,
  prNumber,
  onReplied,
}: {
  thread: ReviewThreadDto;
  prAuthor: string | null;
  repo: string;
  /** PR primary key — required for the resolve / unresolve path. */
  prId: number;
  prNumber: number;
  /** Called after a successful reply / resolve so the parent can patch or
   *  refetch. `optimisticReply` is a synthesised message the parent may
   *  append immediately while the real detail refetches. */
  onReplied: (optimisticReply?: ReviewMessageDto) => void;
}) {
  const [replying, setReplying] = useState(false);
  const [body, setBody] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Collapse resolved threads up-front; the user can toggle them open.
  // Derived from props (not a frozen useState initializer) so a late
  // GraphQL refresh that flips `resolved` from null to true still
  // auto-folds the thread. The override pins the user's manual choice
  // once they touch the chevron, so later refreshes don't re-fold a
  // thread they explicitly expanded.
  // Local optimistic mirror so the pill + button text flip immediately
  // on click. Falls back to the prop when GraphQL hasn't given us a
  // value yet. Sync with thread.resolved on every render so a fresh
  // detail fetch overrides our local state.
  const [resolvedLocal, setResolvedLocal] = useState<boolean | null>(thread.resolved ?? null);
  const [foldOverride, setFoldOverride] = useState<boolean | null>(null);
  const folded = foldOverride ?? (resolvedLocal === true);
  const [resolving, setResolving] = useState(false);
  useEffect(() => {
    setResolvedLocal(thread.resolved ?? null);
  }, [thread.resolved]);

  const submit = async () => {
    const trimmed = body.trim();
    if (!trimmed) return;
    setPending(true); setError(null);
    try {
      await window.bridge.replyToReviewThread(repo, prNumber, thread.rootGithubId, trimmed);
      setBody('');
      setReplying(false);
      // Hand a synthesised optimistic message back so the parent can
      // append it to local state right away. The full-detail refetch
      // (also kicked off by onReplied) reconciles the temp id with
      // GitHub's real one in the background.
      const profile = getCached<UserProfileDto>('home:profile') ?? null;
      const optimistic: ReviewMessageDto = {
        githubId: -Date.now(),
        author: profile?.login ?? null,
        body: trimmed,
        createdAt: new Date().toISOString(),
        reactions: null,
        reviewId: null,
        authorAssociation: null,
      };
      onReplied(optimistic);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setPending(false);
    }
  };

  // Single-line vs. multi-line label. GitHub returns start_line +
  // start_side on multi-line threads (V27 surfaces them); when both are
  // set and the start differs from the end, we render the range. Falls
  // through to "Comment" when line is missing (rare, only on stale
  // threads).
  const lineLabel = thread.line == null
    ? 'Comment'
    : (thread.startLine != null && thread.startLine !== thread.line
      ? `Comment on lines ${(thread.startSide ?? thread.side) === 'LEFT' ? 'L' : 'R'}${thread.startLine} to ${thread.side === 'LEFT' ? 'L' : 'R'}${thread.line}`
      : `Comment on line ${(thread.side === 'LEFT' ? 'L' : 'R')}${thread.line}`);

  return (
    <div
      className={`diff-thread${resolvedLocal === true ? ' diff-thread--resolved' : ''}${thread.outdated ? ' diff-thread--outdated' : ''}`}
    >
      <header className="diff-thread__header">
        <button
          type="button"
          className="diff-thread__fold"
          onClick={() => setFoldOverride(!folded)}
          aria-expanded={!folded}
          title={folded ? 'Expand thread' : 'Collapse thread'}
        >
          <span aria-hidden="true">{folded ? '▸' : '▾'}</span>
        </button>
        <span className="diff-thread__loc">{lineLabel}</span>
        {folded && (
          <span className="diff-thread__head-summary">
            · {thread.messages.length} comment{thread.messages.length === 1 ? '' : 's'}
          </span>
        )}
        {thread.outdated && (
          <span className="diff-thread__pill diff-thread__pill--outdated" title="The line this thread anchors to no longer exists in the current diff.">Outdated</span>
        )}
        {resolvedLocal === true && (
          <span className="diff-thread__pill diff-thread__pill--resolved">Resolved</span>
        )}
        <div className="diff-thread__head-pills">
          {/* Resolve / Unresolve toggle. Hidden when GraphQL hasn't
              populated the resolved flag yet (resolved == null), since
              the mutation needs the GraphQL node id which arrives in
              the same fetch. */}
          {resolvedLocal != null && (
            <button
              type="button"
              className={`diff-thread__resolve-btn${resolvedLocal ? ' diff-thread__resolve-btn--unresolve' : ''}`}
              onClick={async () => {
                if (resolving) return;
                const next = !resolvedLocal;
                setResolving(true);
                setResolvedLocal(next);
                try {
                  await window.bridge.setReviewThreadResolved(repo, prId, thread.rootGithubId, next);
                  onReplied();
                } catch (e) {
                  setResolvedLocal(!next);
                  setError(e instanceof Error ? e.message : String(e));
                } finally {
                  setResolving(false);
                }
              }}
              disabled={resolving}
              title={resolvedLocal ? 'Mark this conversation unresolved' : 'Mark this conversation resolved'}
            >
              {resolving ? '…' : resolvedLocal ? 'Unresolve' : 'Resolve'}
            </button>
          )}
        </div>
      </header>

      {!folded && (
        <>
          {thread.messages.map(msg => (
            <article key={msg.githubId} className="diff-thread__msg">
              <Avatar login={msg.author ?? ''} size={28} className="diff-thread__msg-avatar" />
              <div className="diff-thread__msg-body">
                <header className="diff-thread__msg-head">
                  {msg.author && <span className="diff-thread__msg-author">{msg.author}</span>}
                  {prAuthor === msg.author && (
                    <span className="diff-thread__msg-role">Author</span>
                  )}
                  {msg.createdAt && (
                    <span className="diff-thread__msg-time">{formatRelative(msg.createdAt)}</span>
                  )}
                </header>
                {msg.body && (() => {
                  // `repo` here is GitHub's "owner/repo" form (see prop
                  // declaration). Split so renderMarkdown can produce
                  // clickable #N refs back into this repo's PR list.
                  const [refOwner, refRepo] = repo.split('/');
                  const ctx = refOwner && refRepo ? { owner: refOwner, repo: refRepo } : undefined;
                  return (
                    <div
                      className="diff-thread__msg-text"
                      dangerouslySetInnerHTML={{ __html: renderMarkdown(msg.body, ctx) }}
                    />
                  );
                })()}
                <ThreadReactions reactions={msg.reactions} />
              </div>
            </article>
          ))}

          {resolvedLocal === true && thread.resolvedBy != null && thread.resolvedBy.length > 0 && (
            <div className="diff-thread__resolved-note">
              <b>{thread.resolvedBy}</b> marked this conversation as resolved
            </div>
          )}

          {replying ? (
            <div className="diff-thread__reply">
              <MarkdownComposer
                value={body}
                onChange={setBody}
                placeholder="Reply to this thread — markdown supported."
                rows={2}
                disabled={pending}
                autoFocus
                textareaClassName="diff-thread__reply-input"
              />
              <div className="diff-thread__reply-actions">
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
                  onClick={() => { setReplying(false); setBody(''); setError(null); }}
                  disabled={pending}
                >
                  Cancel
                </button>
              </div>
              {error && <div className="diff-thread__reply-error">{error}</div>}
            </div>
          ) : (
            <button
              type="button"
              className="diff-thread__reply-stub"
              onClick={() => setReplying(true)}
            >
              Write a reply…
            </button>
          )}
        </>
      )}
    </div>
  );
}
