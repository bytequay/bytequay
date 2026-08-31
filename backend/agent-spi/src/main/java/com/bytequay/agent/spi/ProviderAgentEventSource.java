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

import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;

/** Opaque provider routing and replacement identity carried by one event. */
public record ProviderAgentEventSource(
        Optional<String> providerThreadId,
        Optional<String> parentProviderThreadId,
        Optional<String> logicalAgentPath,
        Optional<String> providerTurnId,
        Optional<String> providerItemId)
{
    public ProviderAgentEventSource
    {
        checkOptionalText(providerThreadId, "providerThreadId");
        checkOptionalText(parentProviderThreadId, "parentProviderThreadId");
        checkOptionalText(logicalAgentPath, "logicalAgentPath");
        checkOptionalText(providerTurnId, "providerTurnId");
        checkOptionalText(providerItemId, "providerItemId");
    }

    public static ProviderAgentEventSource root()
    {
        return new ProviderAgentEventSource(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static ProviderAgentEventSource item(String providerItemId)
    {
        return new ProviderAgentEventSource(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(providerItemId));
    }
}
