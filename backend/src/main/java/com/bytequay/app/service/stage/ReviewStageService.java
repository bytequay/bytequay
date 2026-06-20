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
package com.bytequay.app.service.stage;

import com.bytequay.app.beans.stage.SpawnReviewResult;

import java.util.UUID;

/**
 * Write API for the callable review sub-stage. Bridges the stage timeline
 * (open a {@code REVIEW_STAGE} under the caller) and the existing review-
 * pass subsystem (seat a TASK_PHASE-hosted pass over the task's PR), then
 * stamps the stage ↔ pass link. The panel body runs asynchronously, same
 * as the standalone "Assign review" flow.
 */
public interface ReviewStageService
{
    /**
     * Spawn a panel review for the task that owns {@code parentStageId}.
     * The parent must be an open stage of a task whose phase is in an
     * internal-review context; otherwise the call is rejected. Returns the
     * handles needed to open the new review panel.
     *
     * @param parentStageId the stage the review is called from (its task's
     *                      PR is what gets reviewed)
     * @return the opened review stage, the seated pass, and the review thread
     */
    SpawnReviewResult spawnReview(UUID parentStageId);
}
