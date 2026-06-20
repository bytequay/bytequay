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
package com.bytequay.app.service.stage;

import com.bytequay.app.beans.stage.ContextWindowDto;
import com.bytequay.app.beans.stage.ScrubberDash;
import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.beans.stage.StageDetailData.CiCheck;
import com.bytequay.app.beans.stage.StageDetailData.CiFixHistoryEntry;
import com.bytequay.app.beans.stage.StageDetailData.DetailTask;
import com.bytequay.app.beans.stage.StageDetailData.IterationDetail;
import com.bytequay.app.beans.stage.StageDetailData.LogRow;
import com.bytequay.app.beans.stage.StageDetailData.RealtimeCi;
import com.bytequay.app.beans.stage.StageDetailData.Scrubber;
import com.bytequay.app.beans.stage.StageDetailData.StageConfig;
import com.bytequay.app.beans.stage.StageDetailData.StageEventPayload;
import com.bytequay.app.beans.stage.StageDetailData.StageInfo;
import com.bytequay.app.beans.stage.StageDetailData.StageMetricsSubset;
import com.bytequay.app.beans.stage.StageDetailData.ToolCallPayload;
import com.bytequay.app.beans.stage.StageDetailData.UserMessagePayload;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

@Service
public class StageDetailServiceImpl
        implements StageDetailService
{
    private static final int DEFAULT_CONTEXT_TOKEN_LIMIT = 200_000;
    /** Generous cap when listing the task's turns to count those in window. */
    private static final int TURN_SCAN_CAP = 1000;

    private static final Logger log = LoggerFactory.getLogger(StageDetailServiceImpl.class);

    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final IterationStore iterationStore;
    private final ThreadStore threadStore;
    private final ThreadTurnStore turnStore;
    private final StageBudgetService budgetService;
    private final PullRequestService pullRequests;
    private final ObjectMapper mapper;

    public StageDetailServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            IterationStore iterationStore,
            ThreadStore threadStore,
            ThreadTurnStore turnStore,
            StageBudgetService budgetService,
            PullRequestService pullRequests,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.iterationStore = requireNonNull(iterationStore, "iterationStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.budgetService = requireNonNull(budgetService, "budgetService is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public StageDetailData getDetail(UUID stageId)
    {
        StageInstance stage = stageStore.findStageById(stageId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no stage: " + stageId));
        Task task = taskStore.findTaskById(stage.taskId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + stage.taskId()));

        Instant openedAt = stage.openedAt();
        Instant closedAt = stage.closedAt().orElse(null);
        Instant windowEnd = closedAt == null ? Instant.now() : closedAt;

        List<TaskStageIteration> iters = iterationStore.findByStage(stageId);
        List<StageEvent> events = stageStore.findEventsByStage(stageId);
        // The task's single dev thread carries the operation tool calls; we
        // attribute them to this stage by time window (stages don't overlap).
        List<ThreadMessage> devMessages = threadStore.listMessages(task.threadId());

        List<StageInstance> allStages = stageStore.findStagesByTask(task.id());
        List<StageDto> topLevel = allStages.stream()
                .filter(s -> s.callerStageId().isEmpty()).map(StageDetailServiceImpl::toDto).toList();
        List<StageDto> subStages = allStages.stream()
                .filter(s -> s.callerStageId().isPresent()).map(StageDetailServiceImpl::toDto).toList();

        List<IterationDetail> iterations = iters.stream()
                .map(it -> buildIteration(it, events, devMessages))
                .toList();

        return new StageDetailData(
                buildTask(task),
                buildStageInfo(stage, iters, openedAt, closedAt, windowEnd, events, devMessages),
                topLevel,
                subStages,
                iterations,
                buildRealtimeCi(task),
                buildCiFixHistory(stage, iters),
                new ContextWindowDto(0, DEFAULT_CONTEXT_TOKEN_LIMIT, "safe"),
                new Scrubber(List.<ScrubberDash>of()));
    }

    // ── task + stage identity ───────────────────────────────────────────

    private static DetailTask buildTask(Task task)
    {
        return new DetailTask(
                task.id(),
                task.seq(),
                task.name() == null || task.name().isBlank()
                        ? nullToEmpty(task.branchName()) : task.name(),
                nullToEmpty(task.branchName()),
                repoFullName(task.linkedPrRef()),
                task.prNumber(),
                isDraft(task.prState()),
                task.phase().name(),
                "CLI",
                "");
    }

    private StageInfo buildStageInfo(
            StageInstance stage, List<TaskStageIteration> iters, Instant openedAt,
            Instant closedAt, Instant windowEnd, List<StageEvent> events, List<ThreadMessage> devMessages)
    {
        StageMetrics metrics = budgetService.readMetrics(stage.id());
        Integer currentIter = iters.stream()
                .filter(it -> it.endedAt() == null)
                .map(TaskStageIteration::iterationNumber)
                .findFirst().orElse(null);
        StageConfig config = new StageConfig(
                metrics.autoPushBudget() == null ? null
                        : new TaskBrainViewData.AutoPushBudget(
                                metrics.autoPushBudget().used(), metrics.autoPushBudget().limit()),
                metrics.internalReviewEnabled());
        return new StageInfo(
                stage.id().toString(),
                stage.type().name(),
                stage.state().name(),
                openedAt.toString(),
                closedAt == null ? null : closedAt.toString(),
                stage.callerStageId().map(UUID::toString).orElse(null),
                iters.size(),
                currentIter,
                config,
                buildMetrics(stage, iters, openedAt, windowEnd, events, devMessages));
    }

    private StageMetricsSubset buildMetrics(
            StageInstance stage, List<TaskStageIteration> iters, Instant openedAt,
            Instant windowEnd, List<StageEvent> events, List<ThreadMessage> devMessages)
    {
        List<ThreadMessage> inWindow = devMessages.stream()
                .filter(m -> inWindow(m.ts(), openedAt, windowEnd))
                .toList();
        long toolCalls = inWindow.stream().filter(m -> "tool_call".equals(m.type())).count();
        long tokens = inWindow.stream()
                .mapToLong(m -> nz(m.tokensIn()) + nz(m.tokensOut())).sum();
        long costMilli = inWindow.stream().mapToLong(m -> nz(m.costUsdMilli())).sum();
        long mutexSkips = events.stream()
                .filter(e -> e.eventType().name().equals("MUTEX_SKIPPED")).count();
        long turns = turnStore.listTurnsByTaskId(stage.taskId(), TURN_SCAN_CAP).stream()
                .filter(t -> inWindow(t.createdAt(), openedAt, windowEnd)).count();

        return new StageMetricsSubset(
                Math.max(0, (windowEnd.toEpochMilli() - openedAt.toEpochMilli()) / 1000),
                iters.size(),
                (int) toolCalls,
                (int) turns,
                inWindow.size(),
                (int) mutexSkips,
                tokens,
                Math.round(costMilli * 0.1),
                /* panelInvocationsCount */ 0,
                terminalState(stage.state()));
    }

    private static String terminalState(StageState state)
    {
        return switch (state) {
            case CLOSED -> "succeeded";
            case PAUSED -> "paused";
            case OPEN, ACTIVE -> null;
        };
    }

    // ── per-iteration log ───────────────────────────────────────────────

    private IterationDetail buildIteration(
            TaskStageIteration it, List<StageEvent> events, List<ThreadMessage> devMessages)
    {
        Instant start = it.startedAt();
        Instant end = it.endedAt() == null ? Instant.now() : it.endedAt();
        List<LogRow> rows = new ArrayList<>();

        for (ThreadMessage m : devMessages) {
            if (!inWindow(m.ts(), start, end)) {
                continue;
            }
            if ("tool_call".equals(m.type())) {
                rows.add(new LogRow(m.id(), m.ts().toString(), "tool_call",
                        toolCall(m), null, null, null));
            }
            else if ("user".equals(m.role()) && "text".equals(m.type())) {
                rows.add(new LogRow(m.id(), m.ts().toString(), "user_message",
                        null, null, null, new UserMessagePayload(decodeText(m.contentJson()))));
            }
        }
        for (StageEvent e : events) {
            if (inWindow(e.eventAt(), start, end)) {
                rows.add(new LogRow(e.id().toString(), e.eventAt().toString(), "stage_event",
                        null, new StageEventPayload(
                                e.eventType().name(), humanizeEvent(e.eventType().name()), e.payloadJson()),
                        null, null));
            }
        }
        String recordedBy = it.summaryText() == null ? null : recordedBy(it);
        if (it.summaryText() != null) {
            Instant at = it.summarizedAt() == null ? end : it.summarizedAt();
            rows.add(new LogRow(it.id() + ":summary", at.toString(), "iteration_summary",
                    null, null,
                    new StageDetailData.IterationSummaryPayload(it.summaryText(), recordedBy, at.toString()),
                    null));
        }
        rows.sort(Comparator.comparing(LogRow::ts));

        return new IterationDetail(
                it.id().toString(),
                it.iterationNumber(),
                it.trigger(),
                start.toString(),
                it.endedAt() == null ? null : it.endedAt().toString(),
                it.endedReason(),
                it.summaryText(),
                recordedBy,
                rows);
    }

    /** Heuristic for how a summary landed (M3.5's three paths). */
    private static String recordedBy(TaskStageIteration it)
    {
        if (it.summaryText() != null && it.summaryText().startsWith("[no summary recorded]")) {
            return "synthesized";
        }
        return it.summaryRequestTurnId() == null ? "agent" : "orchestrator_fallback";
    }

    // ── realtime CI + ci-fix history ────────────────────────────────────

    private RealtimeCi buildRealtimeCi(Task task)
    {
        Optional<RepoRef> ref = parseRef(task.linkedPrRef());
        if (ref.isEmpty()) {
            return null;
        }
        RepoRef r = ref.get();
        PullRequestDetail detail;
        try {
            detail = pullRequests.getPullRequestDetail(r.repoFullName(), r.number());
        }
        catch (RuntimeException e) {
            log.warn("stage detail realtimeCi for {} failed: {}", task.id(), e.getMessage());
            return null;
        }
        if (detail == null) {
            return null;
        }
        List<CiCheck> checks = detail.checkRuns() == null ? List.of()
                : detail.checkRuns().stream()
                        .map(c -> new CiCheck(c.name(), ciCheckStatus(c.status(), c.conclusion()), null))
                        .toList();
        return new RealtimeCi(
                ciStatus(detail.ciStatus()),
                "https://github.com/" + r.repoFullName() + "/pull/" + r.number(),
                checks,
                Instant.now().toString());
    }

    private List<CiFixHistoryEntry> buildCiFixHistory(StageInstance stage, List<TaskStageIteration> iters)
    {
        if (stage.type() != StageType.CI_FIXING_STAGE) {
            return List.of();
        }
        return iters.stream()
                .map(it -> new CiFixHistoryEntry(it.iterationNumber(), it.endedReason(), it.summaryText()))
                .toList();
    }

    // ── tool-call + text decoding ───────────────────────────────────────

    private ToolCallPayload toolCall(ThreadMessage m)
    {
        String name = null;
        String detail = null;
        if (m.contentJson() != null) {
            try {
                JsonNode node = mapper.readTree(m.contentJson());
                name = firstText(node, "name", "tool", "toolName");
                detail = firstText(node, "path", "file", "filePath", "command", "cmd", "input");
            }
            catch (JsonProcessingException ignore) {
                name = null;
            }
        }
        return new ToolCallPayload(tagFor(name), name == null ? "tool" : name, detail);
    }

    private static String tagFor(String toolName)
    {
        if (toolName == null) {
            return "Tool";
        }
        String n = toolName.toLowerCase(Locale.ROOT);
        if (n.contains("read") || n.contains("cat") || n.contains("grep") || n.contains("ls")) {
            return "Read";
        }
        if (n.contains("write") || n.contains("edit") || n.contains("create") || n.contains("apply")) {
            return "Write";
        }
        if (n.contains("run") || n.contains("bash") || n.contains("shell") || n.contains("exec")
                || n.contains("test")) {
            return "Run";
        }
        if (n.contains("mcp")) {
            return "MCP";
        }
        return "Tool";
    }

    private String firstText(JsonNode node, String... fields)
    {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private String decodeText(String contentJson)
    {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(contentJson).get("text");
            return node == null || node.isNull() ? "" : node.asText();
        }
        catch (JsonProcessingException e) {
            return "";
        }
    }

    // ── small mappers ───────────────────────────────────────────────────

    private static StageDto toDto(StageInstance s)
    {
        return new StageDto(
                s.id().toString(), s.taskId(), s.type().name(), s.state().name(),
                s.openedAt().toString(), s.closedAt().map(Instant::toString).orElse(null),
                s.callerStageId().map(UUID::toString).orElse(null), s.type().displayName(), 0);
    }

    private static boolean inWindow(Instant ts, Instant start, Instant end)
    {
        return ts != null && !ts.isBefore(start) && !ts.isAfter(end);
    }

    private static long nz(Long v)
    {
        return v == null ? 0L : v;
    }

    private static String humanizeEvent(String eventType)
    {
        return eventType.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String ciStatus(PullRequestDetail.CiStatus status)
    {
        if (status == null) {
            return "unknown";
        }
        return switch (status) {
            case PASSING -> "green";
            case FAILING -> "failing";
            case PENDING -> "pending";
            case NONE -> "unknown";
        };
    }

    private static String ciCheckStatus(String status, String conclusion)
    {
        String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (!"completed".equals(s) && !s.isEmpty()) {
            return "pending";
        }
        String c = conclusion == null ? "" : conclusion.toLowerCase(Locale.ROOT);
        if ("success".equals(c) || "neutral".equals(c) || "skipped".equals(c)) {
            return "ok";
        }
        if (c.isEmpty()) {
            return "pending";
        }
        return "fail";
    }

    private static String repoFullName(String linkedPrRef)
    {
        return parseRef(linkedPrRef).map(RepoRef::repoFullName).orElse("");
    }

    private static boolean isDraft(String prState)
    {
        return prState != null && prState.toLowerCase(Locale.ROOT).contains("draft");
    }

    private static Optional<RepoRef> parseRef(String ref)
    {
        if (ref == null) {
            return Optional.empty();
        }
        int hash = ref.lastIndexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) {
            return Optional.empty();
        }
        String repoFull = ref.substring(0, hash);
        if (repoFull.indexOf('/') <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RepoRef(repoFull, Integer.parseInt(ref.substring(hash + 1).trim())));
        }
        catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private record RepoRef(String repoFullName, int number)
    {
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }
}
