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
import type { DiffInlineComment } from '../diff/DiffInlineComments';
import { PendingCommentsList } from '../diff/PendingCommentsList';

export type ReviewVerdict = 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES';

const VERDICT_OPTIONS: Array<{ value: ReviewVerdict; label: string; desc: string }> = [
  { value: 'COMMENT', label: 'Comment', desc: 'Submit general feedback without explicit approval.' },
  { value: 'APPROVE', label: 'Approve', desc: 'Submit feedback and approve merging these changes.' },
  { value: 'REQUEST_CHANGES', label: 'Request changes', desc: 'Submit feedback that must be addressed before merging.' },
];

/**
 * Right-side drawer for submitting a review on the task's own diff — a
 * top-level comment plus a verdict, mirroring github.com's "Finish your
 * review" panel. There's no GitHub API call here: the body/verdict are
 * folded into the same steering-turn message {@code submitReview} already
 * sends the dev agent (see StageDetailRoute/TaskBrainRoute), alongside any
 * unresolved local line comments.
 */
export function SubmitReviewDrawer({
  open, submitting = false, onClose, onSubmit, pendingComments = [], onRemovePending,
}: {
  open: boolean;
  submitting?: boolean;
  onClose: () => void;
  onSubmit: (body: string, verdict: ReviewVerdict) => void;
  /** Draft comments not yet published — shown above the overall body so the
   *  reviewer sees exactly what this submission will send, and can drop one
   *  before submitting. Omit where no draft-comment source is wired up. */
  pendingComments?: DiffInlineComment[];
  onRemovePending?: (commentId: string) => void;
}) {
  const [body, setBody] = useState('');
  const [verdict, setVerdict] = useState<ReviewVerdict>('COMMENT');

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // Fresh draft each time the drawer opens, so a prior submission's text
  // doesn't linger the next time it's reopened.
  useEffect(() => {
    if (open) { setBody(''); setVerdict('COMMENT'); }
  }, [open]);

  if (!open) return null;
  return (
    <div className="submit-review-drawer-backdrop" role="presentation" onClick={onClose}>
      <div
        className="submit-review-drawer"
        role="dialog"
        aria-label="Submit review"
        onClick={e => e.stopPropagation()}
      >
        <div className="submit-review-drawer__head">
          <strong>Submit review</strong>
          {pendingComments.length > 0 && (
            <span className="submit-review-drawer__pending-badge">{pendingComments.length} pending</span>
          )}
          <button type="button" className="submit-review-drawer__close" aria-label="Close" onClick={onClose}>×</button>
        </div>
        <PendingCommentsList
          comments={pendingComments}
          onRemove={submitting ? undefined : onRemovePending}
          emptyHint="No pending comments. You can still leave overall feedback below."
        />
        <textarea
          className="submit-review-drawer__body"
          value={body}
          onChange={e => setBody(e.target.value)}
          placeholder="Leave a comment on this pull request…"
          disabled={submitting}
        />
        <div className="submit-review-drawer__verdicts" role="radiogroup" aria-label="Review verdict">
          {VERDICT_OPTIONS.map(opt => (
            <label
              key={opt.value}
              className={verdict === opt.value
                ? 'submit-review-verdict submit-review-verdict--active'
                : 'submit-review-verdict'}
            >
              <input
                type="radio"
                name="submit-review-verdict"
                value={opt.value}
                checked={verdict === opt.value}
                onChange={() => setVerdict(opt.value)}
                disabled={submitting}
              />
              <span className="submit-review-verdict__text">
                <b>{opt.label}</b>
                <span className="submit-review-verdict__desc">{opt.desc}</span>
              </span>
            </label>
          ))}
        </div>
        <div className="submit-review-drawer__foot">
          <button type="button" className="submit-review-drawer__cancel" onClick={onClose} disabled={submitting}>
            Cancel
          </button>
          <button
            type="button"
            className="submit-review-drawer__submit"
            onClick={() => onSubmit(body, verdict)}
            disabled={submitting}
          >
            {submitting ? 'Submitting…' : 'Submit review'}
          </button>
        </div>
      </div>
    </div>
  );
}
