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

import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderUsageSample;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

/** Temporary normalization boundary for existing provider events. */
public final class ProviderAgentEventAdapter
{
    private ProviderAgentEventAdapter() {}

    public static CanonicalEvent adapt(ProviderAgentEvent event)
    {
        requireNonNull(event, "event is null");
        ProviderAgentEventSource providerSource = event.source();
        AgentEventSource source = new AgentEventSource(
                providerSource.providerThreadId(),
                providerSource.parentProviderThreadId(),
                providerSource.logicalAgentPath(),
                providerSource.providerTurnId(),
                providerSource.providerItemId(),
                event.providerEventId());
        AgentEventPayload payload = switch (event) {
            case ProviderAgentEvent.DevelopmentResult result -> new AgentEventPayload.DevelopmentResult(result.report());
            case ProviderAgentEvent.Message message -> new AgentEventPayload.AssistantMessage(
                    message.messageKind(),
                    message.phase(),
                    message.text());
            case ProviderAgentEvent.Plan plan -> new AgentEventPayload.PlanUpdated(plan.items());
            case ProviderAgentEvent.Tool tool -> new AgentEventPayload.ToolActivity(
                    tool.name(),
                    tool.state(),
                    tool.summary(),
                    tool.duration().map(Duration::toMillis),
                    tool.description());
            case ProviderAgentEvent.Command command -> new AgentEventPayload.CommandActivity(
                    command.state(),
                    command.argv(),
                    command.output(),
                    command.exitCode(),
                    command.truncated(),
                    command.cwd(),
                    command.duration().map(Duration::toMillis),
                    command.actions(),
                    command.background(),
                    command.description());
            case ProviderAgentEvent.FileChange file -> new AgentEventPayload.FileActivity(
                    ActivityState.COMPLETED,
                    file.relativePath(),
                    file.change(),
                    file.diff());
            case ProviderAgentEvent.Usage usage -> usage(usage.sample());
            case ProviderAgentEvent.Lifecycle lifecycle -> new AgentEventPayload.Lifecycle(
                    AgentEventPayload.LifecyclePhase.valueOf(lifecycle.phase().name()),
                    lifecycle.message());
            case ProviderAgentEvent.Error error -> new AgentEventPayload.Error(
                    error.code(),
                    error.message(),
                    error.retryable());
        };
        return new CanonicalEvent(source, payload);
    }

    private static AgentEventPayload.UsageUpdated usage(ProviderUsageSample sample)
    {
        return new AgentEventPayload.UsageUpdated(
                AgentEventPayload.UsageSemantics.valueOf(sample.semantics().name()),
                sample.inputTokens(),
                sample.cachedInputTokens(),
                sample.outputTokens(),
                sample.cost());
    }

    public record CanonicalEvent(AgentEventSource source, AgentEventPayload payload)
    {
        public CanonicalEvent
        {
            requireNonNull(source, "source is null");
            requireNonNull(payload, "payload is null");
        }
    }
}
