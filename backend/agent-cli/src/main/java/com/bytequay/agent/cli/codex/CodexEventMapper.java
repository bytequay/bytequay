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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class CodexEventMapper
{
    List<ProviderAgentEvent> map(CodexProtocol.Notification notification)
    {
        Optional<Instant> timestamp = notification.emittedAtMs().map(Instant::ofEpochMilli);
        return switch (notification) {
            case CodexProtocol.TurnStartedNotification started -> ImmutableList.of(lifecycle(
                    started.params().turn().id() + ":started",
                    timestamp,
                    ProviderAgentEvent.LifecyclePhase.TURN_STARTED,
                    "Codex turn started"));
            case CodexProtocol.TurnCompletedNotification completed -> ImmutableList.of(lifecycle(
                    completed.params().turn().id() + ":completed",
                    timestamp,
                    completed.params().turn().status() == CodexProtocol.TurnStatus.INTERRUPTED
                            ? ProviderAgentEvent.LifecyclePhase.INTERRUPTED
                            : ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED,
                    "Codex turn " + statusText(completed.params().turn().status())));
            case CodexProtocol.ItemStartedNotification started ->
                mapItem(started.params().item(), timestamp, true);
            case CodexProtocol.ItemCompletedNotification completed ->
                mapItem(completed.params().item(), timestamp, false);
            case CodexProtocol.CommandOutputNotification output -> ImmutableList.of(
                    new ProviderAgentEvent.Command(
                            Optional.empty(),
                            timestamp,
                            output.params().itemId(),
                            ProviderAgentEvent.ActivityState.PROGRESS,
                            ImmutableList.of(),
                            optionalText(output.params().delta()),
                            Optional.empty(),
                            false));
            case CodexProtocol.PlanUpdatedNotification plan -> mapPlan(plan.params(), timestamp);
            case CodexProtocol.TokenUsageUpdatedNotification usage -> mapUsage(usage.params(), timestamp);
            case CodexProtocol.ErrorNotification error -> ImmutableList.of(new ProviderAgentEvent.Error(
                    Optional.empty(),
                    timestamp,
                    "codex",
                    error.params().error().message(),
                    error.params().willRetry()));
            case CodexProtocol.UnknownNotification ignored -> ImmutableList.of();
        };
    }

    private static List<ProviderAgentEvent> mapItem(
            CodexProtocol.ThreadItem item,
            Optional<Instant> timestamp,
            boolean started)
    {
        String eventId = item.id() + (started ? ":started" : ":completed");
        return switch (item) {
            case CodexProtocol.AgentMessageItem message -> started
                    ? ImmutableList.of()
                    : ImmutableList.of(new ProviderAgentEvent.Message(
                            Optional.of(eventId),
                            timestamp,
                            ProviderAgentEvent.MessagePhase.FINAL,
                            message.text(),
                            Optional.empty()));
            case CodexProtocol.CommandExecutionItem command ->
                ImmutableList.of(command(command, eventId, timestamp, started));
            case CodexProtocol.McpToolCallItem tool -> ImmutableList.of(tool(
                    tool.id(),
                    tool.server() + "/" + tool.tool(),
                    tool.status(),
                    eventId,
                    timestamp,
                    started));
            case CodexProtocol.DynamicToolCallItem tool -> ImmutableList.of(tool(
                    tool.id(),
                    tool.tool(),
                    tool.status(),
                    eventId,
                    timestamp,
                    started));
            case CodexProtocol.CollabAgentToolCallItem tool -> ImmutableList.of(tool(
                    tool.id(),
                    tool.tool(),
                    tool.status(),
                    eventId,
                    timestamp,
                    started));
            case CodexProtocol.UnknownThreadItem ignored -> ImmutableList.of();
        };
    }

    private static ProviderAgentEvent command(
            CodexProtocol.CommandExecutionItem item,
            String eventId,
            Optional<Instant> timestamp,
            boolean started)
    {
        return new ProviderAgentEvent.Command(
                Optional.of(eventId),
                timestamp,
                item.id(),
                started
                        ? ProviderAgentEvent.ActivityState.STARTED
                        : activityState(item.status()),
                ImmutableList.of(item.command()),
                started ? Optional.empty() : item.aggregatedOutput().flatMap(CodexEventMapper::optionalText),
                item.exitCode(),
                false);
    }

    private static ProviderAgentEvent tool(
            String itemId,
            String name,
            CodexProtocol.ActivityStatus status,
            String eventId,
            Optional<Instant> timestamp,
            boolean started)
    {
        return new ProviderAgentEvent.Tool(
                Optional.of(eventId),
                timestamp,
                itemId,
                name,
                started
                        ? ProviderAgentEvent.ActivityState.STARTED
                        : activityState(status),
                name,
                Optional.empty());
    }

    private static List<ProviderAgentEvent> mapPlan(
            CodexProtocol.PlanUpdatedParams params,
            Optional<Instant> timestamp)
    {
        ImmutableList.Builder<ProviderAgentEvent.PlanItem> items = ImmutableList.builder();
        for (CodexProtocol.PlanStep step : params.plan()) {
            if (!step.step().isBlank()) {
                items.add(new ProviderAgentEvent.PlanItem(step.step(), switch (step.status()) {
                    case COMPLETED -> ProviderAgentEvent.PlanItemState.COMPLETED;
                    case IN_PROGRESS -> ProviderAgentEvent.PlanItemState.IN_PROGRESS;
                    case PENDING, UNKNOWN -> ProviderAgentEvent.PlanItemState.PENDING;
                }));
            }
        }
        List<ProviderAgentEvent.PlanItem> plan = items.build();
        return plan.isEmpty()
                ? ImmutableList.of()
                : ImmutableList.of(new ProviderAgentEvent.Plan(
                        Optional.of(params.turnId() + ":plan"),
                        timestamp,
                        plan));
    }

    private static List<ProviderAgentEvent> mapUsage(
            CodexProtocol.TokenUsageParams params,
            Optional<Instant> timestamp)
    {
        CodexProtocol.TokenUsageBreakdown total = params.tokenUsage().total();
        return ImmutableList.of(new ProviderAgentEvent.Usage(new ProviderUsageSample(
                Optional.of(params.turnId() + ":usage:" + total.totalTokens()),
                ProviderUsageSample.Semantics.CUMULATIVE,
                total.inputTokens(),
                total.cachedInputTokens(),
                total.outputTokens(),
                Optional.empty(),
                CostProvenance.UNAVAILABLE,
                timestamp.orElseGet(Instant::now))));
    }

    private static ProviderAgentEvent.Lifecycle lifecycle(
            String eventId,
            Optional<Instant> timestamp,
            ProviderAgentEvent.LifecyclePhase phase,
            String message)
    {
        return new ProviderAgentEvent.Lifecycle(Optional.of(eventId), timestamp, phase, message);
    }

    private static ProviderAgentEvent.ActivityState activityState(CodexProtocol.ActivityStatus status)
    {
        return switch (status) {
            case IN_PROGRESS -> ProviderAgentEvent.ActivityState.PROGRESS;
            case COMPLETED -> ProviderAgentEvent.ActivityState.COMPLETED;
            case FAILED, DECLINED, UNKNOWN -> ProviderAgentEvent.ActivityState.FAILED;
        };
    }

    private static String statusText(CodexProtocol.TurnStatus status)
    {
        return switch (status) {
            case COMPLETED -> "completed";
            case INTERRUPTED -> "interrupted";
            case FAILED -> "failed";
            case IN_PROGRESS -> "in progress";
            case UNKNOWN -> "finished with unknown status";
        };
    }

    private static Optional<String> optionalText(String value)
    {
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
