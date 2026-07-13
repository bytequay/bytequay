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
import type { LocalPRComment } from '../types/localPr';
import type { ReviewVerdict } from '../pages/SubmitReviewDrawer';

const OPTIONS: Array<{ value: ReviewVerdict; label: string; description: string }> = [
  { value: 'APPROVE', label: 'Approve', description: 'Approve merging these changes.' },
  { value: 'COMMENT', label: 'Comment', description: 'Submit feedback without a verdict.' },
  { value: 'REQUEST_CHANGES', label: 'Request changes', description: 'Require changes before merge.' },
];

export function SubmitReviewPopover({ comments, excluded, onToggle, onEdit, onRemove, onSubmit }: {
  comments: LocalPRComment[];
  excluded: Set<string>;
  onToggle: (findingId: string) => void;
  onEdit: (commentId: string, body: string) => void;
  onRemove: (commentId: string) => void;
  onSubmit: (verdict: ReviewVerdict) => void;
}) {
  const [open, setOpen] = useState(false);
  const [verdict, setVerdict] = useState<ReviewVerdict>('REQUEST_CHANGES');
  const included = comments.filter(comment => comment.findingId == null || !excluded.has(comment.findingId));
  return (
    <div className="agent-submit-wrap">
      <button type="button" className="agent-submit-button" onClick={() => setOpen(value => !value)}>
        Submit review <span>{included.length}</span> ▾
      </button>
      {open && (
        <div className="agent-submit-popover" role="dialog" aria-label="Submit review">
          <div className="agent-submit-popover__head">
            <b>Submit review</b>
            <span>{included.length} pending</span>
          </div>
          <div className="agent-submit-popover__comments">
            {comments.map(comment => {
              const findingId = comment.findingId;
              const findingBacked = findingId !== undefined && findingId !== null;
              const checked = !findingBacked || !excluded.has(findingId);
              return (
                <label className="agent-submit-comment" key={comment.id}>
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={!findingBacked}
                    aria-label={findingBacked ? 'Include finding' : 'Manual draft included'}
                    onChange={() => { if (findingBacked) onToggle(findingId); }}
                  />
                  <textarea
                    value={comment.body}
                    readOnly={!findingBacked}
                    onChange={event => { if (findingBacked) onEdit(comment.id, event.target.value); }}
                  />
                  <button type="button" onClick={() => onRemove(comment.id)} aria-label="Remove pending comment">×</button>
                </label>
              );
            })}
          </div>
          <div className="agent-submit-popover__verdicts" role="radiogroup" aria-label="Review verdict">
            {OPTIONS.map(option => (
              <label key={option.value} className={verdict === option.value ? 'selected' : ''}>
                <input type="radio" name="agent-review-verdict" checked={verdict === option.value} onChange={() => setVerdict(option.value)} />
                <span><b>{option.label}</b><small>{option.description}</small></span>
              </label>
            ))}
          </div>
          <div className="agent-submit-popover__foot">
            <button type="button" onClick={() => setOpen(false)}>Cancel</button>
            <button type="button" className="primary" disabled={included.length === 0} onClick={() => { onSubmit(verdict); setOpen(false); }}>
              Submit review ({included.length})
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
