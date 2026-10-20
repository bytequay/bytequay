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
package com.bytequay.core.merge;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * Auto-merge for one task: since when its pull request has been ready to merge at one head, and what the merge did.
 * The start of the wait is saved so a long delay survives a restart, and a started merge is never sent again.
 *
 * @param message why it is waiting, or what the merge did, in words for the task's status
 */
public record AutoMerge(String taskId, String headSha, Optional<Instant> readySince, Optional<Instant> startedAt, Optional<Outcome> outcome, Optional<String> message)
{
    public enum Outcome
    {
        MERGED,
        QUEUED,
        FAILED,
        CANCELLED,
    }

    public AutoMerge
    {
        checkText(taskId, "taskId");
        checkText(headSha, "headSha");
        requireNonNull(readySince, "readySince is null");
        requireNonNull(startedAt, "startedAt is null");
        requireNonNull(outcome, "outcome is null");
        requireNonNull(message, "message is null");
    }

    public static AutoMerge waiting(String taskId, String headSha, String reason)
    {
        return new AutoMerge(taskId, headSha, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason));
    }

    public AutoMerge ready(Instant since)
    {
        return new AutoMerge(taskId, headSha, Optional.of(since), startedAt, outcome, Optional.empty());
    }

    public AutoMerge started(Instant now)
    {
        return new AutoMerge(taskId, headSha, readySince, Optional.of(now), outcome, message);
    }

    public AutoMerge ended(Outcome result, String detail)
    {
        return new AutoMerge(taskId, headSha, readySince, startedAt, Optional.of(result), Optional.of(detail));
    }
}
