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
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.google.common.collect.ImmutableList;

import java.time.Duration;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
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
                event.providerEventId(),
                event.providerTimestamp());
        AgentEventPayload payload = switch (event) {
            case ProviderAgentEvent.Message message -> new AgentEventPayload.AssistantMessage(
                    AgentEventPayload.MessageKind.valueOf(message.messageKind().name()),
                    AgentEventPayload.MessagePhase.valueOf(message.phase().name()),
                    message.text());
            case ProviderAgentEvent.Plan plan -> new AgentEventPayload.PlanUpdated(plan.items().stream()
                    .map(item -> new AgentEventPayload.PlanItem(
                            item.text(),
                            AgentEventPayload.PlanItemState.valueOf(item.state().name())))
                    .collect(toImmutableList()));
            case ProviderAgentEvent.Tool tool -> new AgentEventPayload.ToolActivity(
                    tool.name(),
                    AgentEventPayload.ActivityState.valueOf(tool.state().name()),
                    tool.summary(),
                    tool.duration().map(Duration::toMillis));
            case ProviderAgentEvent.Command command -> new AgentEventPayload.CommandActivity(
                    AgentEventPayload.ActivityState.valueOf(command.state().name()),
                    command.argv(),
                    command.output(),
                    command.exitCode(),
                    command.truncated(),
                    command.cwd(),
                    command.duration().map(Duration::toMillis),
                    command.actions().stream()
                            .map(action -> new AgentEventPayload.CommandAction(
                                    AgentEventPayload.CommandActionKind.valueOf(action.kind().name()),
                                    action.path(),
                                    action.query()))
                            .collect(toImmutableList()));
            case ProviderAgentEvent.FileChange file -> new AgentEventPayload.FileActivity(
                    AgentEventPayload.ActivityState.COMPLETED,
                    file.relativePath(),
                    AgentEventPayload.ChangeKind.valueOf(file.change().name()),
                    file.diff());
            case ProviderAgentEvent.Approval approval -> new AgentEventPayload.ApprovalRequested(
                    approval.approvalId(),
                    AgentEventPayload.ApprovalKind.OTHER,
                    approval.title(),
                    approval.detail(),
                    ImmutableList.of(),
                    false,
                    ImmutableList.of());
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

    public static CanonicalEvent adaptLegacy(ProviderAgentEvent event)
    {
        CanonicalEvent canonical = adapt(event);
        AgentEventSource source = new AgentEventSource(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                event.providerEventId(),
                event.providerTimestamp());
        return new CanonicalEvent(source, canonical.payload());
    }

    private static AgentEventPayload.UsageUpdated usage(ProviderUsageSample sample)
    {
        return new AgentEventPayload.UsageUpdated(
                AgentEventPayload.UsageSemantics.valueOf(sample.semantics().name()),
                sample.inputTokens(),
                sample.cachedInputTokens(),
                sample.outputTokens(),
                sample.cost(),
                sample.costProvenance());
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
