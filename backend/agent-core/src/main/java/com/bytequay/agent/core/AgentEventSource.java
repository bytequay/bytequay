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
import static java.util.Objects.requireNonNull;

/** Provider source identities preserved without interpreting their values. */
public record AgentEventSource(
        Optional<String> providerThreadId,
        Optional<String> parentProviderThreadId,
        Optional<String> logicalAgentPath,
        Optional<String> providerTurnId,
        Optional<String> providerItemId,
        Optional<String> providerEventId,
        Optional<Instant> providerOccurredAt)
{
    public AgentEventSource
    {
        checkOptionalText(providerThreadId, "providerThreadId");
        checkOptionalText(parentProviderThreadId, "parentProviderThreadId");
        checkOptionalText(logicalAgentPath, "logicalAgentPath");
        checkOptionalText(providerTurnId, "providerTurnId");
        checkOptionalText(providerItemId, "providerItemId");
        checkOptionalText(providerEventId, "providerEventId");
        requireNonNull(providerOccurredAt, "providerOccurredAt is null");
    }

    public static AgentEventSource root()
    {
        return new AgentEventSource(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
