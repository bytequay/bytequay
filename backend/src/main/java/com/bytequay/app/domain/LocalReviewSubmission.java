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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * One durable submitted-local-review batch: an immutable submission
 * identity + frozen root snapshot plus monotonic outcome timestamps.
 * Deliberately not another state machine — current progress derives
 * from the task phase, the open roots, the bound run/turn, and its
 * validation claim. {@code submissionSeq} is the task-wide
 * high-watermark; {@code activatedAt} stays null while a batch
 * submitted during INTERNAL_REVIEW defers.
 */
public record LocalReviewSubmission(
        String id,
        String timelineEventId,
        String taskId,
        String prId,
        String agentRunId,
        long submissionSeq,
        String rootIdsJson,
        String rootSnapshotJson,
        Instant submittedThroughAt,
        Instant addressedThroughAt,
        int attempt,
        int failures,
        Instant createdAt,
        Instant activatedAt,
        Instant completedAt,
        Instant canceledAt,
        String cancelReason)
{
    /** Open = neither completed nor canceled; the owed-action claim. */
    public boolean isOpen()
    {
        return completedAt == null && canceledAt == null;
    }
}
