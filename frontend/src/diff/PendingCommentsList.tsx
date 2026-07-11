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
import type { DiffInlineComment } from './DiffInlineComments';

/**
 * Draft-comment cards — "what's about to ship" — shared by the Submit-review
 * drawer and the diff page's Review tab so both read as the same list.
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
