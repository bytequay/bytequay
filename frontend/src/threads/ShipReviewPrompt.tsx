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
 * The in-conversation callout shown when development has finished. The
 * canonical local-PR flow opens its confirmation dialog from here; a legacy
 * task with no local PR may still resolve its parked `ship_task` here.
 */
export function ShipReviewPrompt({
  onReview, onApprove, approveDisabled = false, onAskAgent, onDiscard, onReviewChanges, busy = false, note,
}: {
  onReview: () => void;
  onApprove?: () => void;
  approveDisabled?: boolean;
  /** Steer the dev agent to address findings + fix the tests. Rendered only
   *  while shipping is blocked, as the forward action out of that state. */
  onAskAgent?: () => void;
  onDiscard?: () => void;
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
          Review the <button type="button" className="review-callout__link" onClick={onReview}>changes</button>,
          leave any comments, then approve to ship.
        </div>
        <div className="review-callout__actions">
          {onApprove !== undefined && (
            <button
              type="button"
              className="review-callout__btn review-callout__btn--ok"
              onClick={onApprove}
              disabled={busy || approveDisabled}
              title={approveDisabled ? note ?? undefined : undefined}
            >
              <CheckIcon size={13} strokeWidth={2.4} />{busy ? 'Shipping…' : 'Approve & ship'}
            </button>
          )}
          {onReviewChanges !== undefined && (
            <button type="button" className="review-callout__btn" onClick={onReviewChanges}>
              Review changes
            </button>
          )}
          {onAskAgent !== undefined && approveDisabled && (
            <button type="button" className="review-callout__btn" onClick={onAskAgent} disabled={busy}>
              Ask agent to address &amp; fix
            </button>
          )}
          {onDiscard !== undefined && (
            <button type="button" className="review-callout__btn" onClick={onDiscard} disabled={busy}>
              Discard gate
            </button>
          )}
        </div>
        {note != null && <div className="review-callout__note">{note}</div>}
      </div>
    </div>
  );
}

/** Recovery for an obsolete `ship_task` proposal that survived into the
 * local-PR review flow. It deliberately offers no approval path: approving
 * the legacy gate can skip the Brain/comment loop, while discarding it
 * releases the publish gate so Development can keep editing. */
export function StaleShipGatePrompt({ onDiscard, busy = false, note }: {
  onDiscard: () => void;
  busy?: boolean;
  note?: string | null;
}) {
  return (
    <div className="review-callout">
      <span className="review-callout__icon" aria-hidden><WarnTriangleIcon /></span>
      <div className="review-callout__body">
        <div className="review-callout__title">Stale publish gate</div>
        <div className="review-callout__tx">
          An older ship proposal is blocking the current local review flow. Discard it so Development can continue.
        </div>
        <div className="review-callout__actions">
          <button type="button" className="review-callout__btn" onClick={onDiscard} disabled={busy}>
            {busy ? 'Discarding…' : 'Discard stale gate'}
          </button>
        </div>
        {note != null && <div className="review-callout__note">{note}</div>}
      </div>
    </div>
  );
}

/** A pre-auto-undraft `mark_ready` proposal can survive an upgrade. It may
 * be discarded, but never approved: green CI now marks the draft ready by
 * itself and this old gate must not reintroduce a second human checkpoint. */
export function StaleMarkReadyGatePrompt({ onDiscard, busy = false, note }: {
  onDiscard: () => void;
  busy?: boolean;
  note?: string | null;
}) {
  return (
    <div className="review-callout">
      <span className="review-callout__icon" aria-hidden><WarnTriangleIcon /></span>
      <div className="review-callout__body">
        <div className="review-callout__title">Obsolete ready-for-review gate</div>
        <div className="review-callout__tx">
          Green CI now marks the Draft PR ready automatically. Discard this older gate to continue.
        </div>
        <div className="review-callout__actions">
          <button type="button" className="review-callout__btn" onClick={onDiscard} disabled={busy}>
            {busy ? 'Discarding…' : 'Discard obsolete gate'}
          </button>
        </div>
        {note != null && <div className="review-callout__note">{note}</div>}
      </div>
    </div>
  );
}
