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
package com.bytequay.app.beans.stage;

/**
 * The handles a successful "spawn a panel review" returns: the callable
 * {@code REVIEW_STAGE} that was opened, the review pass seated under it,
 * and the review thread the frontend navigates to. The app routes its
 * review panel by thread id (the custom {@code review-thread} nav), so
 * {@code reviewThreadId} is what the caller needs to open the panel.
 *
 * @param reviewStageId  the opened REVIEW_STAGE row, caller = the parent stage
 * @param reviewPassId   the seated review pass, linked back to the stage
 * @param reviewThreadId the review thread hosting the panel transcript
 */
public record SpawnReviewResult(
        String reviewStageId,
        String reviewPassId,
        String reviewThreadId)
{
}
