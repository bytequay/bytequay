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

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * What is kept about one subagent after its transcript is gone: identity, lineage, label, the
 * prompt it was given, how it ended, and its answer. Derived from the task's events when the task
 * closes; never a second transcript.
 */
public record ChildSummary(
        String threadId,
        Optional<String> parentThreadId,
        String label,
        Optional<String> purpose,
        Outcome outcome,
        Optional<String> result,
        Optional<Instant> startedAt,
        Optional<Instant> endedAt)
{
    public ChildSummary
    {
        checkText(threadId, "threadId");
        checkOptionalText(parentThreadId, "parentThreadId");
        checkText(label, "label");
        requireNonNull(purpose, "purpose is null");
        requireNonNull(outcome, "outcome is null");
        requireNonNull(result, "result is null");
        requireNonNull(startedAt, "startedAt is null");
        requireNonNull(endedAt, "endedAt is null");
    }

    public enum Outcome
    {
        COMPLETED,
        FAILED,
        /** The task closed without a terminal event for this child; nothing invents one. */
        UNKNOWN
    }
}
