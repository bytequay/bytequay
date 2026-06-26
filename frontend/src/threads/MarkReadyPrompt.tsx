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
import { EventRow, InlineAction } from '../ui/conv';

/**
 * The in-conversation prompt shown when a shipped draft PR's CI has gone
 * green and the lifecycle has parked a one-time `mark_ready` gate. Routes to
 * the Changes page, where the pull-request pane hosts the gate: an optional
 * reviewers field and the "Mark ready for review" action. Distinct from
 * {@link ShipReviewPrompt} (the pre-push diff review) — by here the PR
 * already exists; this only flips it out of draft and requests reviewers.
 */
export function MarkReadyPrompt({ onReview }: { onReview: () => void }) {
  return (
    <EventRow kind="followup" who="CI is green">
      <EventRow.Tx>
        Checks are passing on the draft pull request. Mark it ready for review
        — optionally requesting reviewers — to hand it off.
      </EventRow.Tx>
      <InlineAction icon="▢" onClick={onReview}>Mark ready for review →</InlineAction>
    </EventRow>
  );
}
