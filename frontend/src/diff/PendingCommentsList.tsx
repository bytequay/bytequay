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
import type { ReactNode } from 'react';
import Avatar from '../Avatar';
import {
  avatarKind,
  commentLocationLabel,
  githubAvatarLogin,
  initials,
  type DiffInlineComment,
} from './DiffInlineComments';

/**
 * Draft-comment cards — "what's about to ship" — for the Submit-review
 * drawer specifically (location + text). The diff page's Review tab uses the
 * richer {@link ReviewTabPendingList} card instead; see that component.
 */
export function PendingCommentsList({
  comments, onRemove, onJump, emptyHint = 'No pending comments yet.',
}: {
  comments: DiffInlineComment[];
  onRemove?: (commentId: string) => void;
  /** Selects the comment's file/line in the diff — omit to render plain,
   *  non-interactive cards (the Submit-review drawer has nothing to jump to). */
  onJump?: (comment: DiffInlineComment) => void;
  emptyHint?: ReactNode;
}) {
  if (comments.length === 0) {
    return <div className="pending-comments__empty">{emptyHint}</div>;
  }
  return (
    <div className="pending-comments">
      {comments.map(c => (
        <div
          key={c.id}
          className={`pending-comments__item${onJump !== undefined ? ' pending-comments__item--jumpable' : ''}`}
          onClick={onJump !== undefined ? () => onJump(c) : undefined}
          role={onJump !== undefined ? 'button' : undefined}
          tabIndex={onJump !== undefined ? 0 : undefined}
        >
          <div className="pending-comments__item-head">
            <span className="pending-comments__loc">
              {c.filePath}{c.lineNumber !== null ? ` · L${c.lineNumber}` : ''}
            </span>
            {onRemove !== undefined && (
              <button
                type="button"
                className="pending-comments__remove"
                aria-label="Remove comment"
                onClick={e => { e.stopPropagation(); onRemove(c.id); }}
              >
                ×
              </button>
            )}
          </div>
          <span className="pending-comments__text">{c.body}</span>
        </div>
      ))}
    </div>
  );
}

/**
 * The diff page's whole Review tab panel — transcribed from
 * docs/mockups/design/claude_design_v1/PR Review.dc.html's REVIEW LIST: an
 * uppercase "Pending review N" header, one card per draft (author avatar +
 * name + optional AGENT badge, comment text, a footer with the file/line
 * location), then an "Open submit panel →" button. Agent comments use the
 * purple AI treatment when the comment source marks them as AGENT.
 */
export function ReviewTabPendingList({
  comments, onRemove, onJump, onOpenSubmitPanel, emptyHint = 'No pending comments yet.',
}: {
  comments: DiffInlineComment[];
  onRemove?: (commentId: string) => void;
  onJump?: (comment: DiffInlineComment) => void;
  /** Renders the "Open submit panel →" button below the cards — omit where
   *  this mode has no submit drawer to open (e.g. the task ship-review gate,
   *  which submits via its own toolbar action instead). */
  onOpenSubmitPanel?: () => void;
  emptyHint?: ReactNode;
}) {
  return (
    <div className="review-pending-panel">
      <div className="review-pending-panel__head">
        <span className="review-pending-panel__label">Pending review</span>
        <span className="review-pending-panel__count">{comments.length}</span>
      </div>
      {comments.length === 0 ? (
        <div className="pending-comments__empty">{emptyHint}</div>
      ) : (
        <div className="review-pending">
          {comments.map(c => {
            const kind = avatarKind(c);
            const avatarLogin = githubAvatarLogin(c);
            return (
              <div
                key={c.id}
                className={`review-pending__card review-pending__card--${kind}${onJump !== undefined ? ' review-pending__card--jumpable' : ''}`}
                onClick={onJump !== undefined ? () => onJump(c) : undefined}
                role={onJump !== undefined ? 'button' : undefined}
                tabIndex={onJump !== undefined ? 0 : undefined}
              >
                <div className="review-pending__head">
                  {avatarLogin !== null ? (
                    <Avatar
                      login={avatarLogin}
                      size={28}
                      className={`review-pending__avatar-img review-pending__avatar--${kind}`}
                    />
                  ) : (
                    <span className={`review-pending__avatar review-pending__avatar--${kind}`}>
                      {initials(c.author)}
                    </span>
                  )}
                  <span className="review-pending__author">{c.author}</span>
                  {c.sourceLabel !== undefined && <span className="review-pending__agent-badge">{c.sourceLabel}</span>}
                  {onRemove !== undefined && (
                    <button
                      type="button"
                      className="review-pending__remove"
                      aria-label="Remove comment"
                      onClick={e => { e.stopPropagation(); onRemove(c.id); }}
                    >
                      ×
                    </button>
                  )}
                </div>
                <span className="review-pending__text">{c.body}</span>
                <div className="review-pending__footer">
                  <span className="review-pending__loc">{commentLocationLabel(c)}</span>
                </div>
              </div>
            );
          })}
          {onOpenSubmitPanel !== undefined && (
            <button type="button" className="review-pending-panel__open-submit" onClick={onOpenSubmitPanel}>
              Open submit panel →
            </button>
          )}
        </div>
      )}
    </div>
  );
}
