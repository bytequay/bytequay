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
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

final class ClaudeEventMapper
{
    private static final int MAX_OUTPUT_CHARACTERS = 64 * 1024;

    private final SecretRedactor redactor;
    private final Map<String, ToolCall> tools = new HashMap<>();
    private final Set<String> activeTools = new HashSet<>();
    private final Set<String> activeHooks = new HashSet<>();
    private final Map<String, AgentCall> agents = new HashMap<>();
    private final Map<String, AgentText> latestAgentText = new HashMap<>();
    private final Set<String> activeAgents = new HashSet<>();
    private final Set<String> backgroundAgents = new HashSet<>();
    private final Map<String, TaskActivity> tasks = new HashMap<>();
    private final Set<String> unidentifiedBackgroundTools = new HashSet<>();
    private final Map<TextStreamKey, TextStream> textStreams = new HashMap<>();
    private Optional<String> latestRootTextItemId = Optional.empty();
    private Optional<String> sessionId = Optional.empty();
    private boolean rootTerminal;
    private boolean activityUncertain;

    ClaudeEventMapper(SecretRedactor redactor)
    {
        this.redactor = requireNonNull(redactor, "redactor is null");
    }

    List<ProviderAgentEvent> map(ClaudeProtocol.Message message)
    {
        requireNonNull(message, "message is null");
        message.sessionId().ifPresent(value -> sessionId = Optional.of(value));
        return switch (message) {
            case ClaudeProtocol.SystemMessage system -> mapSystem(system);
            case ClaudeProtocol.StreamEventMessage stream -> mapStream(stream);
            case ClaudeProtocol.AssistantMessage assistant -> mapAssistant(assistant);
            case ClaudeProtocol.UserMessage user -> mapUser(user);
            case ClaudeProtocol.ToolProgressMessage progress -> mapProgress(progress);
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

    boolean hasFinishedWork()
    {
        return rootTerminal
                && !activityUncertain
                && activeTools.isEmpty()
                && activeHooks.isEmpty()
                && activeAgents.isEmpty()
                && unidentifiedBackgroundTools.isEmpty()
                && tasks.values().stream().allMatch(task -> task.terminal() && switch (task.taskType().orElse("")) {
                    case "local_agent" -> task.toolUseId().filter(agents::containsKey).isPresent();
                    case "local_bash" -> true;
                    default -> false;
                });
    }

    private List<ProviderAgentEvent> mapSystem(ClaudeProtocol.SystemMessage message)
    {
        return switch (message.subtype()) {
            case "init" -> {
                rootTerminal = false;
                yield ImmutableList.of(lifecycle(message.uuid(), ProviderAgentEvent.LifecyclePhase.SESSION_OPENED, "Claude session opened"));
            }
            case "status" -> {
                if (message.status().filter(status -> status.equals("requesting") || status.equals("compacting")).isPresent()) {
                    rootTerminal = false;
                }
                yield ImmutableList.of();
            }
            case "hook_started" -> ImmutableList.of(hook(message, ProviderAgentEvent.ActivityState.STARTED));
            case "hook_progress" -> ImmutableList.of(hook(message, ProviderAgentEvent.ActivityState.PROGRESS));
            case "hook_response" -> ImmutableList.of(hook(
                    message,
                    message.exitCode().orElse(0) == 0 && !message.outcome().orElse("success").equals("error")
                            ? ProviderAgentEvent.ActivityState.COMPLETED
                            : ProviderAgentEvent.ActivityState.FAILED));
            case "task_started" -> mapTask(message, ProviderAgentEvent.ActivityState.STARTED);
            case "task_progress" -> mapTask(message, ProviderAgentEvent.ActivityState.PROGRESS);
            case "task_notification" -> mapTask(message, switch (message.status().orElse("")) {
                case "completed" -> ProviderAgentEvent.ActivityState.COMPLETED;
                case "failed", "stopped" -> ProviderAgentEvent.ActivityState.FAILED;
                default -> ProviderAgentEvent.ActivityState.PROGRESS;
            });
            case "task_updated" -> {
                Optional<String> status = message.patch().flatMap(ClaudeProtocol.TaskPatch::status);
                if (status.isEmpty()) {
                    if (message.taskId().filter(tasks::containsKey).isEmpty()) {
                        activityUncertain = true;
                    }
                    yield ImmutableList.of();
                }
                yield mapTask(message, switch (status.orElseThrow()) {
                    case "completed" -> ProviderAgentEvent.ActivityState.COMPLETED;
                    case "failed", "stopped" -> ProviderAgentEvent.ActivityState.FAILED;
                    case "running", "pending" -> ProviderAgentEvent.ActivityState.PROGRESS;
                    default -> {
                        activityUncertain = true;
                        yield ProviderAgentEvent.ActivityState.PROGRESS;
                    }
                });
            }
            case "background_tasks_changed" -> {
                if (message.tasks().isEmpty()) {
                    activityUncertain = true;
                }
                message.tasks().stream().flatMap(List::stream).forEach(task -> tasks.compute(task.taskId(), (id, previous) ->
                        new TaskActivity(Optional.ofNullable(previous).flatMap(TaskActivity::toolUseId), task.taskType(), false)));
                yield ImmutableList.of();
            }
            case "api_retry" -> ImmutableList.of(lifecycle(
                    message.uuid(),
                    ProviderAgentEvent.LifecyclePhase.RETRYING,
                    "Claude API retry " + message.attempt().map(String::valueOf).orElse("")));
            default -> ImmutableList.of();
        };
    }

    private List<ProviderAgentEvent> mapStream(ClaudeProtocol.StreamEventMessage message)
    {
        if (message.parentToolUseId().isEmpty()) {
            rootTerminal = false;
        }
        if (message.event() instanceof ClaudeProtocol.ContentBlockDeltaEvent content
                && content.delta() instanceof ClaudeProtocol.TextDelta text
                && !text.text().isEmpty()) {
            TextStreamKey key = new TextStreamKey(message.parentToolUseId(), content.index());
            TextStream stream = textStreams.computeIfAbsent(
                    key,
                    ignored -> new TextStream(message.uuid().orElseGet(() -> "claude-message-" + UUID.randomUUID())));
            stream.append(text.text());
            return ImmutableList.of(new ProviderAgentEvent.Message(
                    Optional.empty(),
                    Optional.empty(),
                    ProviderAgentEvent.MessageKind.COMMENTARY,
                    ProviderAgentEvent.MessagePhase.PARTIAL,
                    bounded(text.text()),
                    message.parentToolUseId(),
                    source(message.parentToolUseId(), Optional.of(stream.itemId()))));
        }
        return ImmutableList.of();
    }

    private List<ProviderAgentEvent> mapAssistant(ClaudeProtocol.AssistantMessage message)
    {
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        if (message.parentToolUseId().isEmpty()) {
            rootTerminal = false;
        }
        Optional<Instant> timestamp = timestamp(message.timestamp());
        int index = 0;
        for (ClaudeProtocol.ContentBlock block : message.message().content()) {
            if (block instanceof ClaudeProtocol.TextBlock text && !text.text().isEmpty()) {
                int contentIndex = index;
                String itemId = takeTextStream(message.parentToolUseId(), contentIndex, text.text())
                        .map(TextStream::itemId)
                        .orElseGet(() -> eventId(message.uuid(), "text-item", contentIndex)
                                .orElseGet(() -> "claude-message-" + UUID.randomUUID()));
                events.add(new ProviderAgentEvent.Message(
                        eventId(message.uuid(), "text", index),
                        timestamp,
                        ProviderAgentEvent.MessageKind.COMMENTARY,
                        ProviderAgentEvent.MessagePhase.FINAL,
                        bounded(text.text()),
                        message.parentToolUseId(),
                        source(message.parentToolUseId(), Optional.of(itemId))));
                if (message.parentToolUseId().isEmpty()) {
                    latestRootTextItemId = Optional.of(itemId);
                }
                else if (agents.containsKey(message.parentToolUseId().orElseThrow())) {
                    latestAgentText.put(message.parentToolUseId().orElseThrow(), new AgentText(itemId, bounded(text.text())));
                }
            }
            else if (block instanceof ClaudeProtocol.ToolUseBlock tool) {
                ToolCall call = new ToolCall(tool.name(), tool.input(), message.parentToolUseId());
                tools.put(tool.id(), call);
                activeTools.add(tool.id());
                events.add(startedTool(eventId(message.uuid(), "tool", index), timestamp, tool.id(), call));
                if (call.isAgent()) {
                    startAgent(tool.id(), call, message.uuid(), timestamp, events);
                }
            }
            index++;
        }
        return events.build();
    }

    private Optional<TextStream> takeTextStream(Optional<String> parentToolUseId, int contentIndex, String text)
    {
        TextStream exact = textStreams.remove(new TextStreamKey(parentToolUseId, contentIndex));
        if (exact != null) {
            return Optional.of(exact);
        }

        TextStreamKey matchingKey = null;
        TextStream matchingStream = null;
        for (Map.Entry<TextStreamKey, TextStream> entry : textStreams.entrySet()) {
            if (entry.getKey().parentToolUseId().equals(parentToolUseId) && entry.getValue().matches(text)) {
                if (matchingStream != null) {
                    return Optional.empty();
                }
                matchingKey = entry.getKey();
                matchingStream = entry.getValue();
            }
        }
        if (matchingKey != null) {
            textStreams.remove(matchingKey);
        }
        return Optional.ofNullable(matchingStream);
    }

    private List<ProviderAgentEvent> mapUser(ClaudeProtocol.UserMessage message)
    {
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        if (message.parentToolUseId().isEmpty()) {
            rootTerminal = false;
        }
        Optional<Instant> timestamp = timestamp(message.timestamp());
        int index = 0;
        for (ClaudeProtocol.ContentBlock block : message.message().content()) {
            if (block instanceof ClaudeProtocol.ToolResultBlock result) {
                ToolCall call = tools.get(result.toolUseId());
                if (call == null) {
                    call = new ToolCall("tool", new ClaudeProtocol.ToolInput(
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), message.parentToolUseId());
                }
                activeTools.remove(result.toolUseId());
                events.add(completedTool(
                        eventId(message.uuid(), "tool-result", index),
                        timestamp,
                        result,
                        call));
                completeInvocation(message, result, call, events);
            }
            index++;
        }
        return events.build();
    }

    private List<ProviderAgentEvent> mapResult(ClaudeProtocol.ResultMessage result)
    {
        rootTerminal = true;
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        Instant observedAt = Instant.now();
        if (result.successful()) {
            result.result()
                    .filter(value -> !value.isBlank())
                    .ifPresent(response -> events.add(new ProviderAgentEvent.Message(
                            eventId(result.uuid(), "answer", 0),
                            Optional.empty(),
                            ProviderAgentEvent.MessageKind.ANSWER,
                            ProviderAgentEvent.MessagePhase.FINAL,
                            bounded(response),
                            Optional.empty(),
                            source(Optional.empty(), Optional.of(latestRootTextItemId.orElseGet(() ->
                                    eventId(result.uuid(), "answer-item", 0)
                                            .orElseGet(() -> "claude-message-" + UUID.randomUUID())))))));
        }
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
        List<ProviderAgentEvent> mapped = events.build();
        latestRootTextItemId = Optional.empty();
        textStreams.keySet().removeIf(key -> key.parentToolUseId().isEmpty());
        return mapped;
    }

    private List<ProviderAgentEvent> mapProgress(ClaudeProtocol.ToolProgressMessage progress)
    {
        ToolCall call = tools.get(progress.toolUseId());
        activeTools.add(progress.toolUseId());
        if (call == null && (progress.toolName().equals("Agent") || progress.toolName().equals("Task"))) {
            activityUncertain = true;
        }
        Optional<String> parentToolId = progress.parentToolUseId().or(() -> progress.taskId().map(tasks::get).flatMap(TaskActivity::toolUseId).filter(agents::containsKey));
        if (call != null && call.isCommand()) {
            return ImmutableList.of(new ProviderAgentEvent.Command(
                    progress.uuid(),
                    Optional.empty(),
                    progress.toolUseId(),
                    ProviderAgentEvent.ActivityState.PROGRESS,
                    call.input().command().stream().map(this::bounded).collect(toImmutableList()),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    source(call.parentToolUseId(), Optional.of(progress.toolUseId())),
                    Optional.empty(),
                    Optional.of(Duration.ofMillis(Math.max(0, Math.round(progress.elapsedTimeSeconds() * 1000)))),
                    ImmutableList.of()));
        }
        return ImmutableList.of(new ProviderAgentEvent.Tool(
                progress.uuid(),
                Optional.empty(),
                progress.toolUseId(),
                progress.toolName(),
                ProviderAgentEvent.ActivityState.PROGRESS,
                "Running for %.1f seconds".formatted(progress.elapsedTimeSeconds()),
                parentToolId,
                source(parentToolId, Optional.of(progress.toolUseId()))));
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
                    false,
                    source(call.parentToolUseId(), Optional.of(toolUseId)));
        }
        return new ProviderAgentEvent.Tool(
                eventId,
                timestamp,
                toolUseId,
                call.name(),
                ProviderAgentEvent.ActivityState.STARTED,
                bounded(call.input().description().orElse(call.name())),
                call.parentToolUseId(),
                source(call.parentToolUseId(), Optional.of(toolUseId)));
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
                    result.text().length() > MAX_OUTPUT_CHARACTERS,
                    source(call.parentToolUseId(), Optional.of(result.toolUseId())));
        }
        return new ProviderAgentEvent.Tool(
                eventId,
                timestamp,
                result.toolUseId(),
                call.name(),
                state,
                bounded(result.text()),
                call.parentToolUseId(),
                source(call.parentToolUseId(), Optional.of(result.toolUseId())));
    }

    private ProviderAgentEvent.Tool hook(
            ClaudeProtocol.SystemMessage message,
            ProviderAgentEvent.ActivityState state)
    {
        String id = message.hookId().orElseGet(() -> message.uuid().orElse("claude-hook"));
        if (terminal(state)) {
            activeHooks.remove(id);
        }
        else {
            activeHooks.add(id);
        }
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

    private void startAgent(
            String toolId,
            ToolCall call,
            Optional<String> eventId,
            Optional<Instant> timestamp,
            ImmutableList.Builder<ProviderAgentEvent> events)
    {
        ProviderAgentEventSource parent = source(call.parentToolUseId(), Optional.empty());
        if (parent.providerThreadId().isEmpty() || agents.containsKey(toolId)) {
            return;
        }
        String label = call.input().description().filter(value -> !value.isBlank())
                .or(() -> call.input().subagentType().filter(value -> !value.isBlank())).orElse("Claude subagent");
        // Claude supplies invocation edges; the supplied description is a display label, not a resumable session or filesystem path.
        ProviderAgentEventSource child = new ProviderAgentEventSource(
                Optional.of("claude-agent:" + toolId), parent.providerThreadId(), Optional.of(bounded(label)), Optional.empty(), Optional.empty());
        agents.put(toolId, new AgentCall(child));
        activeAgents.add(toolId);
        events.add(new ProviderAgentEvent.Lifecycle(
                eventId.map(value -> value + ":agent-start:" + toolId), timestamp, ProviderAgentEvent.LifecyclePhase.AGENT_STARTED, bounded(label), child));
        call.input().prompt().filter(value -> !value.isBlank()).ifPresent(prompt -> events.add(new ProviderAgentEvent.Message(
                eventId.map(value -> value + ":agent-prompt:" + toolId),
                timestamp,
                ProviderAgentEvent.MessageKind.PROMPT,
                ProviderAgentEvent.MessagePhase.FINAL,
                bounded(prompt),
                Optional.of(toolId),
                withItem(child, Optional.of(toolId + ":prompt")))));
    }

    private void completeInvocation(
            ClaudeProtocol.UserMessage message,
            ClaudeProtocol.ToolResultBlock result,
            ToolCall call,
            ImmutableList.Builder<ProviderAgentEvent> events)
    {
        String toolId = result.toolUseId();
        if (call.isAgent()) {
            Optional<String> nativeId = metadataText(message.toolUseResult(), "agentId");
            nativeId.ifPresent(id -> rememberTask(id, toolId, "local_agent"));
            Optional<String> status = metadataText(message.toolUseResult(), "status");
            if (status.filter(value -> !value.equals("completed") && !value.equals("async_launched")).isPresent()) {
                activityUncertain = true;
                return;
            }
            if (status.filter("async_launched"::equals).isPresent()
                    || (!result.error() && status.isEmpty() && (call.input().runInBackground().orElse(false) || backgroundAgents.contains(toolId)))) {
                backgroundAgents.add(toolId);
                if (tasks.values().stream().noneMatch(task -> task.toolUseId().filter(toolId::equals).isPresent())) {
                    unidentifiedBackgroundTools.add(toolId);
                }
                return;
            }
            finishAgent(toolId, result.error() ? ProviderAgentEvent.ActivityState.FAILED : ProviderAgentEvent.ActivityState.COMPLETED,
                    result.error() ? "Claude subagent failed" : "Claude subagent completed", message.uuid(), events);
        }
        else if (call.isCommand()) {
            Optional<String> backgroundId = metadataText(message.toolUseResult(), "backgroundTaskId");
            backgroundId.ifPresent(id -> rememberTask(id, toolId, "local_bash"));
            if (!result.error() && call.input().runInBackground().orElse(false)
                    && tasks.values().stream().noneMatch(task -> task.toolUseId().filter(toolId::equals).isPresent())) {
                unidentifiedBackgroundTools.add(toolId);
            }
        }
    }

    private void rememberTask(String id, String toolId, String taskType)
    {
        TaskActivity previous = tasks.get(id);
        if (previous != null && (previous.toolUseId().filter(value -> !value.equals(toolId)).isPresent()
                || previous.taskType().filter(value -> !value.equals(taskType)).isPresent())) {
            activityUncertain = true;
            return;
        }
        tasks.put(id, new TaskActivity(Optional.of(toolId), Optional.of(taskType), previous != null && previous.terminal()));
        unidentifiedBackgroundTools.remove(toolId);
    }

    private void finishAgent(
            String toolId,
            ProviderAgentEvent.ActivityState state,
            String summary,
            Optional<String> eventId,
            ImmutableList.Builder<ProviderAgentEvent> events)
    {
        if (!activeAgents.remove(toolId)) {
            return;
        }
        tasks.replaceAll((id, task) -> task.toolUseId().filter(toolId::equals).isPresent() ? new TaskActivity(task.toolUseId(), task.taskType(), true) : task);
        unidentifiedBackgroundTools.remove(toolId);
        Optional.ofNullable(latestAgentText.remove(toolId)).ifPresent(text -> events.add(new ProviderAgentEvent.Message(
                eventId.map(value -> value + ":agent-answer:" + toolId),
                Optional.empty(),
                ProviderAgentEvent.MessageKind.ANSWER,
                ProviderAgentEvent.MessagePhase.FINAL,
                text.text(),
                Optional.of(toolId),
                withItem(agents.get(toolId).source(), Optional.of(text.itemId())))));
        events.add(new ProviderAgentEvent.Lifecycle(
                eventId.map(value -> value + ":agent-end:" + toolId),
                Optional.empty(),
                state == ProviderAgentEvent.ActivityState.FAILED ? ProviderAgentEvent.LifecyclePhase.AGENT_FAILED : ProviderAgentEvent.LifecyclePhase.AGENT_COMPLETED,
                bounded(summary),
                agents.get(toolId).source()));
    }

    private ProviderAgentEventSource source(Optional<String> parentToolUseId, Optional<String> itemId)
    {
        if (parentToolUseId.isPresent()) {
            AgentCall parent = agents.get(parentToolUseId.orElseThrow());
            if (parent != null) {
                return withItem(parent.source(), itemId);
            }
            activityUncertain = true;
            return withItem(ProviderAgentEventSource.root(), itemId);
        }
        return new ProviderAgentEventSource(sessionId, Optional.empty(), Optional.empty(), Optional.empty(), itemId);
    }

    private static ProviderAgentEventSource withItem(ProviderAgentEventSource source, Optional<String> itemId)
    {
        return new ProviderAgentEventSource(source.providerThreadId(), source.parentProviderThreadId(), source.logicalAgentPath(), source.providerTurnId(), itemId);
    }

    private Optional<String> metadataText(Optional<JsonNode> metadata, String name)
    {
        Optional<JsonNode> field = metadata.map(value -> value.get(name));
        if (field.filter(value -> !value.isTextual() || value.textValue().isBlank()).isPresent()) {
            activityUncertain = true;
        }
        return field.filter(JsonNode::isTextual).map(JsonNode::textValue).filter(value -> !value.isBlank());
    }

    private static boolean terminal(ProviderAgentEvent.ActivityState state)
    {
        return state == ProviderAgentEvent.ActivityState.COMPLETED || state == ProviderAgentEvent.ActivityState.FAILED;
    }

    private List<ProviderAgentEvent> mapTask(
            ClaudeProtocol.SystemMessage message,
            ProviderAgentEvent.ActivityState state)
    {
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        String id = message.taskId().orElseGet(() -> message.uuid().orElse("claude-task"));
        if (message.taskId().isEmpty()) {
            activityUncertain = true;
        }
        TaskActivity previous = tasks.get(id);
        Optional<String> reportedToolId = message.toolUseId().or(() -> Optional.ofNullable(previous).flatMap(TaskActivity::toolUseId));
        boolean conflictingTool = previous != null && previous.toolUseId().isPresent() && !previous.toolUseId().equals(reportedToolId);
        if (conflictingTool) {
            activityUncertain = true;
        }
        Optional<String> toolId = conflictingTool ? previous.toolUseId() : reportedToolId;
        boolean finished = terminal(state);
        Optional<String> taskType = message.taskType()
                .or(() -> Optional.ofNullable(previous).flatMap(TaskActivity::taskType))
                .or(() -> toolId.filter(agents::containsKey).map(ignored -> "local_agent"))
                .or(() -> toolId.map(tools::get).filter(ToolCall::isCommand).map(ignored -> "local_bash"));
        tasks.put(id, new TaskActivity(toolId, taskType, finished));
        toolId.ifPresent(unidentifiedBackgroundTools::remove);
        Optional<AgentCall> agent = toolId.map(agents::get);
        if (message.backgrounded().orElse(false)) {
            toolId.filter(agents::containsKey).ifPresent(backgroundAgents::add);
        }
        if (message.subtype().equals("task_notification") && !finished) {
            activityUncertain = true;
        }
        String summary = message.description()
                .or(() -> message.summary())
                .or(() -> message.prompt())
                .or(() -> message.status())
                .orElse("Claude task");
        ProviderAgentEventSource eventSource = agent.map(value -> withItem(value.source(), Optional.of(id)))
                .orElseGet(() -> toolId.map(tools::get)
                        .map(call -> source(call.parentToolUseId(), Optional.of(id)))
                        .orElseGet(() -> ProviderAgentEventSource.item(id)));
        events.add(new ProviderAgentEvent.Tool(
                message.uuid(),
                Optional.empty(),
                id,
                "Task",
                state,
                bounded(summary),
                message.parentToolUseId().or(() -> message.toolUseId()),
                eventSource));
        if (finished && agent.isPresent()) {
            finishAgent(toolId.orElseThrow(), state, summary, message.uuid(), events);
        }
        else if (agent.isPresent() && activeAgents.add(toolId.orElseThrow())) {
            events.add(new ProviderAgentEvent.Lifecycle(
                    message.uuid().map(value -> value + ":agent-resume"),
                    Optional.empty(),
                    ProviderAgentEvent.LifecyclePhase.AGENT_STARTED,
                    bounded(summary),
                    agent.orElseThrow().source()));
        }
        return events.build();
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

        private boolean isAgent()
        {
            return name.equals("Agent") || name.equals("Task");
        }
    }

    private record AgentCall(ProviderAgentEventSource source) {}

    private record AgentText(String itemId, String text) {}

    private record TaskActivity(Optional<String> toolUseId, Optional<String> taskType, boolean terminal) {}

    private record TextStreamKey(Optional<String> parentToolUseId, int contentIndex)
    {
        private TextStreamKey
        {
            requireNonNull(parentToolUseId, "parentToolUseId is null");
        }
    }

    private static final class TextStream
    {
        private final String itemId;
        private final StringBuilder text = new StringBuilder();
        private boolean exactContentAvailable = true;

        private TextStream(String itemId)
        {
            this.itemId = requireNonNull(itemId, "itemId is null");
        }

        private String itemId()
        {
            return itemId;
        }

        private void append(String value)
        {
            requireNonNull(value, "value is null");
            if (!exactContentAvailable) {
                return;
            }
            if (value.length() > MAX_OUTPUT_CHARACTERS - text.length()) {
                text.setLength(0);
                exactContentAvailable = false;
                return;
            }
            text.append(value);
        }

        private boolean matches(String value)
        {
            return exactContentAvailable && value.contentEquals(text);
        }
    }
}
