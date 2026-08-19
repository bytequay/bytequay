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
package com.bytequay.agent.spi;

import java.time.Duration;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record AgentTurnResult(
        State state,
        Optional<String> finalResponse,
        Optional<String> terminalReason,
        Duration elapsed)
{
    public AgentTurnResult
    {
        requireNonNull(state, "state is null");
        checkOptionalText(finalResponse, "finalResponse");
        checkOptionalText(terminalReason, "terminalReason");
        requireNonNull(elapsed, "elapsed is null");
        checkArgument(!elapsed.isNegative(), "elapsed is negative");
        checkArgument(state != State.COMPLETED || terminalReason.isEmpty(), "a completed turn cannot have a terminal reason");
        checkArgument(state == State.COMPLETED || terminalReason.isPresent(), "a non-completed turn requires a terminal reason");
    }

    public enum State
    {
        COMPLETED,
        FAILED,
        CANCELLED,
        INTERRUPTED
    }
}
