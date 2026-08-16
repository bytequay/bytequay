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
package com.bytequay.agent.core;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.bytequay.agent.spi.AgentChecks.checkPresent;

public record CleanupResult(
        TaskId taskId,
        CleanupState state,
        Optional<Instant> deletedAt,
        Optional<String> failure)
{
    public CleanupResult
    {
        checkPresent(taskId, "taskId");
        checkPresent(state, "state");
        checkPresent(deletedAt, "deletedAt");
        checkOptionalText(failure, "failure");
        if ((state == CleanupState.DELETED) != deletedAt.isPresent()) {
            throw new IllegalArgumentException("deletedAt and cleanup state disagree");
        }
        if ((state == CleanupState.RETRY_PENDING) != failure.isPresent()) {
            throw new IllegalArgumentException("failure and cleanup state disagree");
        }
    }
}
