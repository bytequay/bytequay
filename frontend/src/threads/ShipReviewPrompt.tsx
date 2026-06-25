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
 * The in-conversation prompt shown when development has finished and a
 * `ship_task` proposal is parked awaiting the user. Makes the otherwise
 * buried review gate discoverable: it explains the task is waiting and
 * routes to the Changes page, where the diff, the drafted PR description,
 * and the Approve &amp; ship button live.
 */
export function ShipReviewPrompt({ onReview }: { onReview: () => void }) {
  return (
    <EventRow kind="followup" who="Ready for review">
      <EventRow.Tx>
        Development finished and parked a pull request for your review.
        Review the diff and the drafted PR description, leave any comments,
        then approve to ship.
      </EventRow.Tx>
      <InlineAction icon="▢" onClick={onReview}>Review changes &amp; approve →</InlineAction>
    </EventRow>
  );
}
