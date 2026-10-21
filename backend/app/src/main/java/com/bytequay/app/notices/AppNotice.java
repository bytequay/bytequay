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
package com.bytequay.app.notices;

import java.time.Instant;
import java.util.Optional;

/**
 * A short projection of task facts. The source owns resolution; notices own no durable lifecycle.
 *
 * @param workItemId the Epic work a {@link Kind#NEXT_WORK} notice suggests starting next
 */
public record AppNotice(
        String id,
        Kind kind,
        String taskId,
        String workspaceId,
        Optional<String> turnId,
        Optional<String> reviewId,
        String title,
        Optional<String> pullRequest,
        Optional<String> pullRequestTitle,
        Optional<Changes> changes,
        String message,
        String repository,
        String laneId,
        Optional<String> workItemId,
        Instant occurredAt)
{
    public enum Kind
    {
        TURN_FINISHED,
        QUESTION,
        APPROVAL,
        FAILURE,
        RECOVERY,
        QUEUE_PAUSED,
        CI_ATTENTION,
        NEXT_WORK
    }

    /** Counts for the latest review run; {@code update} marks a run that covers only changes since the prior review. */
    public record Changes(boolean update, int commits, int files, long additions, long deletions) {}
}
