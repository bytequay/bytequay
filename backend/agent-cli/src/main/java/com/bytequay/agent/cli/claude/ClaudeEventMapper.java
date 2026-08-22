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
package com.bytequay.agent.cli.claude;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

final class ClaudeEventMapper
{
    private static final int MAX_OUTPUT_CHARACTERS = 64 * 1024;

    private final SecretRedactor redactor;
    private final Map<String, ToolCall> tools = new HashMap<>();

    ClaudeEventMapper(SecretRedactor redactor)
    {
        this.redactor = requireNonNull(redactor, "redactor is null");
    }

    List<ProviderAgentEvent> map(ClaudeProtocol.Message message)
    {
        requireNonNull(message, "message is null");
        return switch (message) {
            case ClaudeProtocol.SystemMessage system -> mapSystem(system);
            case ClaudeProtocol.StreamEventMessage stream -> mapStream(stream);
            case ClaudeProtocol.AssistantMessage assistant -> mapAssistant(assistant);
            case ClaudeProtocol.UserMessage user -> mapUser(user);
            case ClaudeProtocol.ToolProgressMessage progress -> ImmutableList.of(new ProviderAgentEvent.Tool(
                    progress.uuid(),
                    Optional.empty(),
                    progress.toolUseId(),
                    progress.toolName(),
                    ProviderAgentEvent.ActivityState.PROGRESS,
                    "Running for %.1f seconds".formatted(progress.elapsedTimeSeconds()),
                    progress.parentToolUseId()));
            case ClaudeProtocol.RateLimitMessage rateLimit ->
                rateLimit.rateLimitInfo().status().equalsIgnoreCase("allowed")
                        ? ImmutableList.of()
                        : ImmutableList.of(lifecycle(
                                rateLimit.uuid(),
                                ProviderAgentEvent.LifecyclePhase.RATE_LIMITED,
                                "Claude rate limit: " + rateLimit.rateLimitInfo().status()));
            case ClaudeProtocol.ResultMessage result -> mapResult(result);
            case ClaudeProtocol.UnknownMessage ignored -> ImmutableList.of();
        };
    }

    private List<ProviderAgentEvent> mapSystem(ClaudeProtocol.SystemMessage message)
    {
        return switch (message.subtype()) {
            case "init" -> ImmutableList.of(lifecycle(
                    message.uuid(),
                    ProviderAgentEvent.LifecyclePhase.SESSION_OPENED,
                    "Claude session opened"));
            case "hook_started" -> ImmutableList.of(hook(message, ProviderAgentEvent.ActivityState.STARTED));
            case "hook_progress" -> ImmutableList.of(hook(message, ProviderAgentEvent.ActivityState.PROGRESS));
            case "hook_response" -> ImmutableList.of(hook(
                    message,
                    message.exitCode().orElse(0) == 0 && !message.outcome().orElse("success").equals("error")
                            ? ProviderAgentEvent.ActivityState.COMPLETED
                            : ProviderAgentEvent.ActivityState.FAILED));
            case "task_started" -> ImmutableList.of(task(message, ProviderAgentEvent.ActivityState.STARTED));
            case "task_progress" -> ImmutableList.of(task(message, ProviderAgentEvent.ActivityState.PROGRESS));
            case "task_notification" -> ImmutableList.of(task(
                    message,
                    message.status().filter("failed"::equalsIgnoreCase).isPresent()
                            ? ProviderAgentEvent.ActivityState.FAILED
                            : ProviderAgentEvent.ActivityState.COMPLETED));
            case "api_retry" -> ImmutableList.of(lifecycle(
                    message.uuid(),
                    ProviderAgentEvent.LifecyclePhase.RETRYING,
                    "Claude API retry " + message.attempt().map(String::valueOf).orElse("")));
            default -> ImmutableList.of();
        };
    }

    private List<ProviderAgentEvent> mapStream(ClaudeProtocol.StreamEventMessage message)
    {
        if (message.event() instanceof ClaudeProtocol.ContentBlockDeltaEvent content
                && content.delta() instanceof ClaudeProtocol.TextDelta text
                && !text.text().isEmpty()) {
            return ImmutableList.of(new ProviderAgentEvent.Message(
                    message.uuid(),
                    Optional.empty(),
                    ProviderAgentEvent.MessagePhase.PARTIAL,
                    redactor.redact(text.text()),
                    message.parentToolUseId()));
        }
        return ImmutableList.of();
    }

    private List<ProviderAgentEvent> mapAssistant(ClaudeProtocol.AssistantMessage message)
    {
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        Optional<Instant> timestamp = timestamp(message.timestamp());
        int index = 0;
        for (ClaudeProtocol.ContentBlock block : message.message().content()) {
            if (block instanceof ClaudeProtocol.TextBlock text && !text.text().isEmpty()) {
                events.add(new ProviderAgentEvent.Message(
                        eventId(message.uuid(), "text", index),
                        timestamp,
                        ProviderAgentEvent.MessagePhase.FINAL,
                        bounded(text.text()),
                        message.parentToolUseId()));
            }
            else if (block instanceof ClaudeProtocol.ToolUseBlock tool) {
                ToolCall call = new ToolCall(tool.name(), tool.input(), message.parentToolUseId());
                tools.put(tool.id(), call);
                events.add(startedTool(eventId(message.uuid(), "tool", index), timestamp, tool.id(), call));
            }
            index++;
        }
        return events.build();
    }

    private List<ProviderAgentEvent> mapUser(ClaudeProtocol.UserMessage message)
    {
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        Optional<Instant> timestamp = timestamp(message.timestamp());
        int index = 0;
        for (ClaudeProtocol.ContentBlock block : message.message().content()) {
            if (block instanceof ClaudeProtocol.ToolResultBlock result) {
                ToolCall call = tools.remove(result.toolUseId());
                if (call == null) {
                    call = new ToolCall("tool", new ClaudeProtocol.ToolInput(
                            Optional.empty(), Optional.empty(), Optional.empty()), message.parentToolUseId());
                }
                events.add(completedTool(
                        eventId(message.uuid(), "tool-result", index),
                        timestamp,
                        result,
                        call));
            }
            index++;
        }
        return events.build();
    }

    private List<ProviderAgentEvent> mapResult(ClaudeProtocol.ResultMessage result)
    {
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        Instant observedAt = Instant.now();
        ClaudeProtocol.Usage usage = result.usage();
        events.add(new ProviderAgentEvent.Usage(new ProviderUsageSample(
                result.uuid(),
                ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION,
                Math.addExact(
                        usage.inputTokens(),
                        Math.addExact(usage.cacheCreationInputTokens(), usage.cacheReadInputTokens())),
                usage.cacheReadInputTokens(),
                usage.outputTokens(),
                result.totalCostUsd(),
                result.totalCostUsd().isPresent() ? CostProvenance.CLI_ESTIMATE : CostProvenance.UNAVAILABLE,
                observedAt)));
        if (!result.successful()) {
            events.add(new ProviderAgentEvent.Error(
                    eventId(result.uuid(), "error", 0),
                    Optional.of(observedAt),
                    "claude_result",
                    bounded(result.errorMessage()),
                    false));
        }
        return events.build();
    }

    private ProviderAgentEvent startedTool(
            Optional<String> eventId,
            Optional<Instant> timestamp,
            String toolUseId,
            ToolCall call)
    {
        if (call.isCommand()) {
            return new ProviderAgentEvent.Command(
                    eventId,
                    timestamp,
                    toolUseId,
                    ProviderAgentEvent.ActivityState.STARTED,
                    call.input().command().stream().map(this::bounded).collect(toImmutableList()),
                    Optional.empty(),
                    Optional.empty(),
                    false);
        }
        return new ProviderAgentEvent.Tool(
                eventId,
                timestamp,
                toolUseId,
                call.name(),
                ProviderAgentEvent.ActivityState.STARTED,
                bounded(call.input().description().orElse(call.name())),
                call.parentToolUseId());
    }

    private ProviderAgentEvent completedTool(
            Optional<String> eventId,
            Optional<Instant> timestamp,
            ClaudeProtocol.ToolResultBlock result,
            ToolCall call)
    {
        ProviderAgentEvent.ActivityState state = result.error()
                ? ProviderAgentEvent.ActivityState.FAILED
                : ProviderAgentEvent.ActivityState.COMPLETED;
        if (call.isCommand()) {
            return new ProviderAgentEvent.Command(
                    eventId,
                    timestamp,
                    result.toolUseId(),
                    state,
                    call.input().command().stream().map(this::bounded).collect(toImmutableList()),
                    Optional.of(bounded(result.text())),
                    Optional.empty(),
                    result.text().length() > MAX_OUTPUT_CHARACTERS);
        }
        return new ProviderAgentEvent.Tool(
                eventId,
                timestamp,
                result.toolUseId(),
                call.name(),
                state,
                bounded(result.text()),
                call.parentToolUseId());
    }

    private ProviderAgentEvent.Tool hook(
            ClaudeProtocol.SystemMessage message,
            ProviderAgentEvent.ActivityState state)
    {
        String id = message.hookId().orElseGet(() -> message.uuid().orElse("claude-hook"));
        String name = message.hookName().orElseGet(() -> message.hookEvent().orElse("hook"));
        String summary = message.output()
                .or(() -> message.stdout())
                .or(() -> message.stderr())
                .orElse(name);
        return new ProviderAgentEvent.Tool(
                message.uuid(),
                Optional.empty(),
                id,
                "hook/" + name,
                state,
                bounded(summary),
                Optional.empty());
    }

    private ProviderAgentEvent.Tool task(
            ClaudeProtocol.SystemMessage message,
            ProviderAgentEvent.ActivityState state)
    {
        String id = message.taskId().orElseGet(() -> message.uuid().orElse("claude-task"));
        String summary = message.description()
                .or(() -> message.summary())
                .or(() -> message.prompt())
                .or(() -> message.status())
                .orElse("Claude subagent");
        return new ProviderAgentEvent.Tool(
                message.uuid(),
                Optional.empty(),
                id,
                "Task",
                state,
                bounded(summary),
                message.parentToolUseId().or(() -> message.toolUseId()));
    }

    private ProviderAgentEvent.Lifecycle lifecycle(
            Optional<String> eventId,
            ProviderAgentEvent.LifecyclePhase phase,
            String message)
    {
        return new ProviderAgentEvent.Lifecycle(eventId, Optional.empty(), phase, bounded(message));
    }

    private String bounded(String text)
    {
        String redacted = redactor.redact(text);
        return redacted.length() <= MAX_OUTPUT_CHARACTERS
                ? redacted
                : redacted.substring(0, MAX_OUTPUT_CHARACTERS);
    }

    private static Optional<String> eventId(Optional<String> uuid, String suffix, int index)
    {
        return uuid.map(value -> value + ":" + suffix + ":" + index);
    }

    private static Optional<Instant> timestamp(Optional<String> timestamp)
    {
        try {
            return timestamp.map(Instant::parse);
        }
        catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private record ToolCall(
            String name,
            ClaudeProtocol.ToolInput input,
            Optional<String> parentToolUseId)
    {
        private ToolCall
        {
            requireNonNull(name, "name is null");
            requireNonNull(input, "input is null");
            requireNonNull(parentToolUseId, "parentToolUseId is null");
        }

        private boolean isCommand()
        {
            return name.equalsIgnoreCase("Bash");
        }
    }
}
