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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One queued or running user input for a thread.
 *
 * <p>A thread can be idle while its next turn is waiting for scheduler
 * capacity. Keeping that state here avoids overloading
 * {@link ThreadStatus} with queue details.
 */
public record ThreadTurn(
        String id,
        // JSON key kept as "taskId" through Phase 4; the frontend renames in lockstep then.
        @JsonProperty("taskId") String threadId,
        ThreadResourceLane lane,
        ThreadTurnStatus status,
        String input,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage)
{
}
