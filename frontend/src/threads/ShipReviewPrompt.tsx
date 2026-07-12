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
import { CheckIcon, WarnTriangleIcon } from '../ui/TaskBrainDesignIcons';

/**
 * The in-conversation callout shown when development has finished and a
 * `ship_task` proposal is parked awaiting the user. Two exits: Approve &
 * ship resolves the parked gate inline, Review changes unfolds the PR
 * pane onto its code-diff sub-tab. `onReview` (the inline text link)
 * still routes to the full review surface (diff + editable PR
 * description).
 */
export function ShipReviewPrompt({ onReview, onApprove, onReviewChanges, busy = false, note }: {
  onReview: () => void;
  onApprove?: () => void;
  onReviewChanges?: () => void;
  busy?: boolean;
  /** Approve-failure reason surfaced under the actions. */
  note?: string | null;
}) {
  return (
    <div className="review-callout">
      <span className="review-callout__icon" aria-hidden><WarnTriangleIcon /></span>
      <div className="review-callout__body">
        <div className="review-callout__title">Ready for review</div>
        <div className="review-callout__tx">
          Development finished and parked a pull request for your review.
          Review the <button type="button" className="review-callout__link" onClick={onReview}>diff and the drafted PR description</button>,
          leave any comments, then approve to ship.
        </div>
        <div className="review-callout__actions">
          {onApprove !== undefined && (
            <button type="button" className="review-callout__btn review-callout__btn--ok" onClick={onApprove} disabled={busy}>
              <CheckIcon size={13} strokeWidth={2.4} />{busy ? 'Shipping…' : 'Approve & ship'}
            </button>
          )}
          {onReviewChanges !== undefined && (
            <button type="button" className="review-callout__btn" onClick={onReviewChanges}>
              Review changes
            </button>
          )}
        </div>
        {note != null && <div className="review-callout__note">{note}</div>}
      </div>
    </div>
  );
}
