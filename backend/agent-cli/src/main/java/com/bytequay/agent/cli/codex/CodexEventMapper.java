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

import com.bytequay.agent.cli.development.DevelopmentResultTool;
import com.bytequay.agent.cli.mcp.ToolActivityLine;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

final class CodexEventMapper
{
    /** The Claude lane's bound: a whole build log in one event would exceed the task log's record limit. */
    private static final int MAX_OUTPUT_CHARACTERS = 64 * 1024;

    private final Path worktree;
    private final SecretRedactor redactor;
    private final boolean exposeReasoningSummaries;

    CodexEventMapper()
    {
        this(Path.of("/").toAbsolutePath(), SecretRedactor.fromEnvironment(ImmutableMap.of()));
    }

    CodexEventMapper(Path worktree, SecretRedactor redactor)
    {
        this(worktree, redactor, true);
    }

    CodexEventMapper(Path worktree, SecretRedactor redactor, boolean exposeReasoningSummaries)
    {
        this.worktree = requireNonNull(worktree, "worktree is null").toAbsolutePath().normalize();
        this.redactor = requireNonNull(redactor, "redactor is null");
        this.exposeReasoningSummaries = exposeReasoningSummaries;
    }

    /**
     * Maps one inbound notification to the events ByteQuay publishes.
     *
     * @param notification the notification
     * @param answersForTheTurn whether it came from the thread that speaks for the turn. A subagent
     *     produces its own final answer, and only the root thread's may be published as the turn's
     *     answer, because that is what the task's recorded final response is read from.
     * @return the mapped events, possibly empty
     */
    /**
     * Where a thread sits in the task's agent tree: its direct parent and the label it is known by.
     * The driver keeps the tree; the mapper only reads it, so every event a subagent produces
     * carries the same lineage as the discovery event that announced it.
     */
    interface ThreadLineage
    {
        ThreadLineage NONE = new ThreadLineage()
        {
            @Override
            public Optional<String> parentOf(String threadId)
            {
                return Optional.empty();
            }

            @Override
            public Optional<String> labelOf(String threadId)
            {
                return Optional.empty();
            }
        };

        Optional<String> parentOf(String threadId);

        Optional<String> labelOf(String threadId);
    }

    /** Called only for live notifications, never for transcript replay or child backfill. */
    Optional<ProviderAgentEvent.DevelopmentResult> developmentResult(
            CodexProtocol.Notification notification,
            boolean rootThread,
            String nativeTurnId,
            Optional<DevelopmentResultBridge> development)
    {
        if (!rootThread || !notification.turnId().filter(nativeTurnId::equals).isPresent()
                || !(notification instanceof CodexProtocol.ItemCompletedNotification completed)
                || !(completed.params().item() instanceof CodexProtocol.McpToolCallItem tool)
                || !tool.server().equals("bytequay") || !tool.tool().equals(DevelopmentResultBridge.TOOL_NAME)
                || tool.status() != CodexProtocol.ActivityStatus.COMPLETED || tool.error().isPresent()) {
            return Optional.empty();
        }
        return development.flatMap(bridge -> tool.result().flatMap(result -> DevelopmentResultTool.readReceipt(result, bridge, redactor)))
                .map(report -> new ProviderAgentEvent.DevelopmentResult(
                        Optional.of(tool.id() + ":development-result"),
                        report,
                        source(notification, Optional.of(tool.id()), ThreadLineage.NONE)));
    }

    List<ProviderAgentEvent> map(CodexProtocol.Notification notification, boolean rootThread, ThreadLineage lineage)
    {
        return switch (notification) {
            case CodexProtocol.TurnStartedNotification started -> rootThread
                    ? ImmutableList.of(lifecycle(
                            started.params().turn().id() + ":started",
                            ProviderAgentEvent.LifecyclePhase.TURN_STARTED,
                            "Codex turn started",
                            source(started, Optional.empty(), lineage)))
                    // A subagent's turn starting is not a task turn; its discovery already said it began.
                    : ImmutableList.of();
            case CodexProtocol.TurnCompletedNotification completed -> ImmutableList.of(rootThread
                    ? lifecycle(
                            completed.params().turn().id() + ":completed",
                            completed.params().turn().status() == CodexProtocol.TurnStatus.INTERRUPTED
                                    ? ProviderAgentEvent.LifecyclePhase.INTERRUPTED
                                    : ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED,
                            "Codex turn " + statusText(completed.params().turn().status()),
                            source(completed, Optional.empty(), lineage))
                    : lifecycle(
                            completed.params().turn().id() + ":agent-completed",
                            completed.params().turn().status() == CodexProtocol.TurnStatus.COMPLETED
                                    ? ProviderAgentEvent.LifecyclePhase.AGENT_COMPLETED
                                    : ProviderAgentEvent.LifecyclePhase.AGENT_FAILED,
                            "Agent " + statusText(completed.params().turn().status()),
                            source(completed, Optional.empty(), lineage)));
            case CodexProtocol.ItemStartedNotification started ->
                mapItem(started.params().item(), true, rootThread,
                        source(started, Optional.of(started.params().item().id()), lineage));
            case CodexProtocol.ItemCompletedNotification completed ->
                mapItem(completed.params().item(), false, rootThread,
                        source(completed, Optional.of(completed.params().item().id()), lineage));
            case CodexProtocol.AutoApprovalReviewStartedNotification started ->
                ImmutableList.of(autoApprovalReview(started, started.params(), lineage));
            case CodexProtocol.AutoApprovalReviewCompletedNotification completed ->
                ImmutableList.of(autoApprovalReview(completed, completed.params(), lineage));
            // The driver announces a new thread once its lineage is known; nothing to show here.
            case CodexProtocol.ThreadStartedNotification ignored -> ImmutableList.of();
            case CodexProtocol.CommandOutputNotification output -> ImmutableList.of(
                    new ProviderAgentEvent.Command(
                            Optional.empty(),
                            ProviderAgentEvent.ActivityState.PROGRESS,
                            ImmutableList.of(),
                            optionalText(redactor.redact(output.params().delta())),
                            Optional.empty(),
                            false,
                            source(output, Optional.of(output.params().itemId()), lineage)));
            case CodexProtocol.AgentMessageDeltaNotification message -> ImmutableList.of(
                    new ProviderAgentEvent.Message(
                            Optional.empty(),
                            ProviderAgentEvent.MessagePhase.PARTIAL,
                            redactor.redact(message.params().delta()),
                            source(message, Optional.of(message.params().itemId()), lineage)));
            case CodexProtocol.PlanDeltaNotification plan -> ImmutableList.of(
                    new ProviderAgentEvent.Message(
                            Optional.empty(),
                            ProviderAgentEvent.MessageKind.PLAN_TEXT,
                            ProviderAgentEvent.MessagePhase.PARTIAL,
                            redactor.redact(plan.params().delta()),
                            source(plan, Optional.of(plan.params().itemId()), lineage)));
            case CodexProtocol.PlanUpdatedNotification plan -> mapPlan(plan.params(), source(plan, Optional.empty(), lineage));
            case CodexProtocol.TokenUsageUpdatedNotification usage -> mapUsage(usage.params(), source(usage, Optional.empty(), lineage));
            case CodexProtocol.ErrorNotification error -> ImmutableList.of(new ProviderAgentEvent.Error(
                    Optional.empty(),
                    "codex",
                    redactor.redact(error.params().error().message()),
                    error.params().willRetry(),
                    source(error, Optional.empty(), lineage)));
            case CodexProtocol.UnknownNotification ignored -> ImmutableList.of();
        };
    }

    private ProviderAgentEvent autoApprovalReview(
            CodexProtocol.Notification notification,
            CodexProtocol.AutoApprovalReviewParams params,
            ThreadLineage lineage)
    {
        // A review has its own identity: using the target command's item ID would overwrite its output.
        String itemId = "approval-review:" + params.reviewId();
        CodexProtocol.AutoApprovalReview review = params.review();
        String outcome = switch (review.status()) {
            case "inProgress" -> "Reviewing action";
            case "approved" -> "Approved";
            case "denied" -> "Denied";
            case "timedOut" -> "Timed out";
            case "aborted" -> "Stopped";
            default -> "Unknown review status";
        };
        ProviderAgentEvent.ActivityState state = switch (review.status()) {
            case "inProgress" -> ProviderAgentEvent.ActivityState.STARTED;
            case "approved" -> ProviderAgentEvent.ActivityState.COMPLETED;
            default -> ProviderAgentEvent.ActivityState.FAILED;
        };
        String summary = outcome
                + review.riskLevel().map(risk -> " · " + risk + " risk").orElse("")
                + review.rationale().filter(reason -> !reason.isBlank()).map(reason -> " — " + reason).orElse("");
        return new ProviderAgentEvent.Tool(
                Optional.of(itemId + ":" + review.status()),
                "Automatic approval",
                state,
                redactor.redact(summary),
                source(notification, Optional.of(itemId), lineage),
                Optional.empty());
    }

    private List<ProviderAgentEvent> mapItem(
            CodexProtocol.ThreadItem item,
            boolean started,
            boolean rootThread,
            ProviderAgentEventSource source)
    {
        String eventId = item.id() + (started ? ":started" : ":completed");
        return switch (item) {
            case CodexProtocol.AgentMessageItem message -> started
                    ? ImmutableList.of()
                    : ImmutableList.of(new ProviderAgentEvent.Message(
                            Optional.of(eventId),
                            // A subagent's final answer is FINAL for that agent; its source names
                            // the parent, which is what keeps it from being read as the task's.
                            message.phase().orElse(CodexProtocol.MessagePhase.UNKNOWN)
                                    == CodexProtocol.MessagePhase.FINAL_ANSWER
                                    ? ProviderAgentEvent.MessagePhase.FINAL
                                    : ProviderAgentEvent.MessagePhase.PARTIAL,
                            redactor.redact(message.text()),
                            source));
            // The root's own prompt is already durable as the user's message; a subagent's prompt
            // is the only record of what its parent asked it to do.
            case CodexProtocol.UserMessageItem prompt -> started || rootThread || prompt.text().isBlank()
                    ? ImmutableList.of()
                    : ImmutableList.of(new ProviderAgentEvent.Message(
                            Optional.of(eventId),
                            ProviderAgentEvent.MessageKind.PROMPT,
                            ProviderAgentEvent.MessagePhase.FINAL,
                            redactor.redact(prompt.text()),
                            source));
            case CodexProtocol.CommandExecutionItem command ->
                ImmutableList.of(command(command, eventId, started, source));
            case CodexProtocol.FileChangeItem fileChange ->
                mapFileChanges(fileChange, started, source);
            case CodexProtocol.McpToolCallItem tool -> ImmutableList.of(tool(
                    tool.server() + "/" + tool.tool(),
                    tool.status(),
                    eventId,
                    started,
                    source,
                    tool.durationMs(),
                    Optional.empty(),
                    !started && tool.server().equals("bytequay") && tool.status() == CodexProtocol.ActivityStatus.COMPLETED && tool.error().isEmpty()
                            ? tool.result().flatMap(ToolActivityLine::fromResult) : Optional.empty()));
            case CodexProtocol.DynamicToolCallItem tool -> ImmutableList.of(tool(
                    tool.tool(),
                    tool.status(),
                    eventId,
                    started,
                    source,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()));
            // Spawning is shown as the subagent it creates, not as a tool row; the other
            // collaboration tools are activity like any other.
            case CodexProtocol.CollabAgentToolCallItem tool -> tool.tool().equals("spawnAgent")
                    ? ImmutableList.of()
                    : ImmutableList.of(tool(
                            tool.tool(),
                            tool.status(),
                            eventId,
                            started,
                            source,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
            case CodexProtocol.ReasoningItem reasoning -> {
                // Only the readable summary is mapped; the hidden reasoning text was never decoded.
                List<String> summary = reasoning.summary().orElse(ImmutableList.of());
                yield !exposeReasoningSummaries || started || summary.isEmpty()
                        ? ImmutableList.of()
                        : ImmutableList.of(new ProviderAgentEvent.Message(
                                Optional.of(eventId),
                                ProviderAgentEvent.MessageKind.REASONING_SUMMARY,
                                ProviderAgentEvent.MessagePhase.FINAL,
                                redactor.redact(String.join("\n\n", summary)),
                                source));
            }
            case CodexProtocol.PlanTextItem plan -> started
                    ? ImmutableList.of()
                    : ImmutableList.of(new ProviderAgentEvent.Message(
                            Optional.of(eventId),
                            ProviderAgentEvent.MessageKind.PLAN_TEXT,
                            ProviderAgentEvent.MessagePhase.FINAL,
                            redactor.redact(plan.text()),
                            source));
            case CodexProtocol.WebSearchItem search -> ImmutableList.of(builtIn(
                    "Web search", webStep(search), eventId, started, source));
            case CodexProtocol.ImageViewItem view -> ImmutableList.of(builtIn(
                    "Image view", displayPath(view.path()), eventId, started, source));
            case CodexProtocol.ContextCompactionItem ignored -> ImmutableList.of(builtIn(
                    "Context compaction", "", eventId, started, source));
            case CodexProtocol.SubAgentActivityItem ignored -> ImmutableList.of();
            case CodexProtocol.UnknownThreadItem ignored -> ImmutableList.of();
        };
    }

    private ProviderAgentEvent command(
            CodexProtocol.CommandExecutionItem item,
            String eventId,
            boolean started,
            ProviderAgentEventSource source)
    {
        Optional<String> output = started ? Optional.empty() : item.aggregatedOutput().map(redactor::redact);
        return new ProviderAgentEvent.Command(
                Optional.of(eventId),
                started
                        ? ProviderAgentEvent.ActivityState.STARTED
                        : activityState(item.status()),
                ImmutableList.of(redactor.redact(item.command())),
                output.map(CodexEventMapper::bounded).flatMap(CodexEventMapper::optionalText),
                item.exitCode(),
                output.filter(text -> text.length() > MAX_OUTPUT_CHARACTERS).isPresent(),
                source,
                relativeDirectory(item.cwd()),
                item.durationMs().map(Duration::ofMillis),
                item.commandActions().stream().map(this::action).collect(toImmutableList()));
    }

    private ProviderAgentEvent.CommandAction action(CodexProtocol.CommandAction action)
    {
        ProviderAgentEvent.CommandActionKind kind = switch (action.type()) {
            case "read" -> ProviderAgentEvent.CommandActionKind.READ;
            case "listFiles" -> ProviderAgentEvent.CommandActionKind.LIST_FILES;
            case "search" -> ProviderAgentEvent.CommandActionKind.SEARCH;
            default -> ProviderAgentEvent.CommandActionKind.OTHER;
        };
        return new ProviderAgentEvent.CommandAction(
                kind,
                action.name().map(redactor::redact).flatMap(CodexEventMapper::optionalText),
                action.path().flatMap(this::relativePath),
                action.query().map(redactor::redact).flatMap(CodexEventMapper::optionalText));
    }

    private ProviderAgentEvent tool(
            String name,
            CodexProtocol.ActivityStatus status,
            String eventId,
            boolean started,
            ProviderAgentEventSource source,
            Optional<Long> durationMs,
            Optional<String> summary,
            Optional<String> description)
    {
        return new ProviderAgentEvent.Tool(
                Optional.of(eventId),
                redactor.redact(name),
                started
                        ? ProviderAgentEvent.ActivityState.STARTED
                        : activityState(status),
                redactor.redact(summary.orElse(name)),
                source,
                durationMs.map(Duration::ofMillis),
                description.map(redactor::redact));
    }

    /** A built-in App Server tool has no status of its own: it runs until its item completes. */
    private ProviderAgentEvent builtIn(
            String name,
            String summary,
            String eventId,
            boolean started,
            ProviderAgentEventSource source)
    {
        return new ProviderAgentEvent.Tool(
                Optional.of(eventId),
                name,
                started
                        ? ProviderAgentEvent.ActivityState.STARTED
                        : ProviderAgentEvent.ActivityState.COMPLETED,
                redactor.redact(summary),
                source,
                Optional.empty());
    }

    /** The page a web step opened or searched, not only its query. */
    private static String webStep(CodexProtocol.WebSearchItem search)
    {
        return search.action().flatMap(action -> action.url().map(url -> switch (action.type()) {
            case "openPage" -> "Opened " + url;
            case "findInPage" -> action.pattern().map(pattern -> "Searched " + url + " for \u201c" + pattern + "\u201d").orElse("Searched " + url);
            default -> url;
        })).orElse(search.query());
    }

    /** A worktree-relative path when the image is inside the worktree, otherwise just its file name. */
    private String displayPath(String path)
    {
        return relativePath(path).orElseGet(() -> {
            Path name = Path.of(path).getFileName();
            return name == null ? "" : name.toString();
        });
    }

    private List<ProviderAgentEvent> mapFileChanges(
            CodexProtocol.FileChangeItem item,
            boolean started,
            ProviderAgentEventSource source)
    {
        if (started || item.status() != CodexProtocol.ActivityStatus.COMPLETED) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        for (int index = 0; index < item.changes().size(); index++) {
            CodexProtocol.FileUpdateChange change = item.changes().get(index);
            Optional<String> relativePath = relativePath(change);
            Optional<ProviderAgentEvent.ChangeKind> kind = changeKind(change.kind());
            if (relativePath.isPresent() && kind.isPresent()) {
                events.add(new ProviderAgentEvent.FileChange(
                        Optional.of(item.id() + ":completed:" + index),
                        relativePath.orElseThrow(),
                        kind.orElseThrow(),
                        optionalText(bounded(redactor.redact(change.diff()))),
                        source.withItem(Optional.of(item.id() + ":" + index))));
            }
        }
        return events.build();
    }

    private static String bounded(String text)
    {
        return text.length() <= MAX_OUTPUT_CHARACTERS ? text : text.substring(0, MAX_OUTPUT_CHARACTERS);
    }

    private Optional<String> relativePath(CodexProtocol.FileUpdateChange change)
    {
        return relativePath(change.kind().movePath().orElse(change.path()));
    }

    /** A path inside the worktree, made relative to it; anything else is dropped rather than shown. */
    private Optional<String> relativePath(String value)
    {
        return insideWorktree(value).map(Path::toString).filter(text -> !text.isBlank());
    }

    /** Like {@link #relativePath(String)}, except the worktree itself reads as the current directory. */
    private Optional<String> relativeDirectory(String value)
    {
        return insideWorktree(value).map(path -> path.toString().isBlank() ? "." : path.toString());
    }

    private Optional<Path> insideWorktree(String value)
    {
        Path path = Path.of(value).normalize();
        if (path.isAbsolute()) {
            if (!path.startsWith(worktree)) {
                return Optional.empty();
            }
            path = worktree.relativize(path);
        }
        if (path.startsWith("..")) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    private static Optional<ProviderAgentEvent.ChangeKind> changeKind(CodexProtocol.FileChangeKind kind)
    {
        return switch (kind.type()) {
            case "add" -> Optional.of(ProviderAgentEvent.ChangeKind.ADDED);
            case "delete" -> Optional.of(ProviderAgentEvent.ChangeKind.DELETED);
            case "update" -> Optional.of(kind.movePath().isPresent()
                    ? ProviderAgentEvent.ChangeKind.RENAMED
                    : ProviderAgentEvent.ChangeKind.MODIFIED);
            default -> Optional.empty();
        };
    }

    private List<ProviderAgentEvent> mapPlan(
            CodexProtocol.PlanUpdatedParams params,
            ProviderAgentEventSource source)
    {
        ImmutableList.Builder<ProviderAgentEvent.PlanItem> items = ImmutableList.builder();
        for (CodexProtocol.PlanStep step : params.plan()) {
            if (!step.step().isBlank()) {
                items.add(new ProviderAgentEvent.PlanItem(redactor.redact(step.step()), switch (step.status()) {
                    case COMPLETED -> ProviderAgentEvent.PlanItemState.COMPLETED;
                    case IN_PROGRESS -> ProviderAgentEvent.PlanItemState.IN_PROGRESS;
                    case PENDING, UNKNOWN -> ProviderAgentEvent.PlanItemState.PENDING;
                }));
            }
        }
        List<ProviderAgentEvent.PlanItem> plan = items.build();
        if (!params.plan().isEmpty() && plan.isEmpty()) {
            return ImmutableList.of();
        }
        return ImmutableList.of(new ProviderAgentEvent.Plan(
                Optional.of(params.turnId() + ":plan"),
                plan,
                source));
    }

    private static List<ProviderAgentEvent> mapUsage(
            CodexProtocol.TokenUsageParams params,
            ProviderAgentEventSource source)
    {
        CodexProtocol.TokenUsageBreakdown total = params.tokenUsage().total();
        return ImmutableList.of(new ProviderAgentEvent.Usage(new ProviderUsageSample(
                Optional.of(params.turnId() + ":usage:" + total.totalTokens()),
                ProviderUsageSample.Semantics.CUMULATIVE_FOR_INVOCATION,
                total.inputTokens(),
                total.cachedInputTokens(),
                total.outputTokens(),
                Optional.empty()),
                source));
    }

    private static ProviderAgentEvent.Lifecycle lifecycle(
            String eventId,
            ProviderAgentEvent.LifecyclePhase phase,
            String message,
            ProviderAgentEventSource source)
    {
        return new ProviderAgentEvent.Lifecycle(Optional.of(eventId), phase, message, source);
    }

    private static ProviderAgentEventSource source(
            CodexProtocol.Notification notification,
            Optional<String> providerItemId,
            ThreadLineage lineage)
    {
        String threadId = notification.threadId();
        return new ProviderAgentEventSource(
                Optional.of(threadId),
                lineage.parentOf(threadId),
                lineage.labelOf(threadId),
                notification.turnId(),
                providerItemId);
    }

    private static ProviderAgentEvent.ActivityState activityState(CodexProtocol.ActivityStatus status)
    {
        return switch (status) {
            case IN_PROGRESS -> ProviderAgentEvent.ActivityState.PROGRESS;
            case COMPLETED -> ProviderAgentEvent.ActivityState.COMPLETED;
            case FAILED, DECLINED, INTERRUPTED, UNKNOWN -> ProviderAgentEvent.ActivityState.FAILED;
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
