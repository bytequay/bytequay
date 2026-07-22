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
import com.bytequay.app.beans.stage.PullRequestCreatedData;
import com.bytequay.app.beans.stage.ScrubberDash;
import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.beans.stage.StageDetailData.CiCheck;
import com.bytequay.app.beans.stage.StageDetailData.CiFixHistoryEntry;
import com.bytequay.app.beans.stage.StageDetailData.ConversationRow;
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
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BranchGuardService;
import com.bytequay.app.service.review.ReviewRoundService;
import com.bytequay.app.service.runs.AgentRunService;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final AgentRunService agentRuns;
    private final BranchGuardService branchGuards;
    private final ReviewRoundService reviewRounds;
    private final ObjectMapper mapper;

    public StageDetailServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            IterationStore iterationStore,
            ThreadStore threadStore,
            ThreadTurnStore turnStore,
            StageBudgetService budgetService,
            PullRequestService pullRequests,
            AgentRunService agentRuns,
            BranchGuardService branchGuards,
            ReviewRoundService reviewRounds,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.iterationStore = requireNonNull(iterationStore, "iterationStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.budgetService = requireNonNull(budgetService, "budgetService is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.branchGuards = requireNonNull(branchGuards, "branchGuards is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    private ReviewRound liveRound(String taskId)
    {
        return reviewRounds.findByTask(taskId).stream().filter(ReviewRound::isLive).findFirst().orElse(null);
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

        TimeWindow window = TimeWindow.forStage(stage);

        List<TaskStageIteration> iters = iterationStore.findByStage(stageId);
        List<StageEvent> events = stageStore.findEventsByStage(stageId);
        // The task's single dev thread carries the operation tool calls; we
        // attribute them to this stage by time window (stages don't overlap).
        // Stage transcripts now live in the decoupled stage_messages store, so
        // merge those in (chronologically) alongside any legacy stage rows
        // still in thread_messages — until the backfill consolidates them.
        List<ThreadMessage> devMessages = new ArrayList<>(threadStore.listMessages(task.threadId()));
        devMessages.addAll(threadStore.listStageMessagesByTask(task.id()));
        devMessages.sort(Comparator.comparing(ThreadMessage::ts));

        List<StageInstance> allStages = stageStore.findStagesByTask(task.id());
        List<StageDto> topLevel = allStages.stream()
                .filter(s -> s.callerStageId().isEmpty()).map(StageDetailServiceImpl::toDto).toList();
        List<StageDto> subStages = allStages.stream()
                .filter(s -> s.callerStageId().isPresent()).map(StageDetailServiceImpl::toDto).toList();

        List<IterationDetail> iterations = iters.stream()
                .map(it -> buildIteration(it, events, devMessages))
                .toList();

        // One PR fetch (cached) feeds both the realtime-CI snapshot and the
        // PR tab — pass it to both so the detail call isn't made twice.
        PullRequestDetail prDetail = fetchPrDetail(task);
        boolean terminal = isTerminal(task.status());
        List<AgentRun> liveRuns = terminal ? List.of() : agentRuns.liveRunsByTask(task.id());
        ReviewRound liveRound = terminal ? null : liveRound(task.id());
        StageInstance dev = allStages.stream()
                .filter(s -> s.type() == StageType.DEVELOPMENT_STAGE)
                .findFirst()
                .orElse(null);

        return new StageDetailData(
                buildTask(task),
                buildStageInfo(stage, iters, window, devMessages),
                topLevel,
                subStages,
                conversationThreadId(stage, task),
                iterations,
                buildConversation(stage, task, devMessages, iters, events),
                buildRealtimeCi(task, prDetail),
                buildCiFixHistory(stage, iters, events),
                buildPrTab(task, prDetail),
                new ContextWindowDto(0, DEFAULT_CONTEXT_TOKEN_LIMIT, "safe"),
                new Scrubber(List.<ScrubberDash>of()),
                liveRuns,
                branchGuards.get(task.id()),
                liveRound,
                buildDevPhases(task.phase(), dev, liveRuns, reviewRounds.findByTask(task.id())));
    }

    private static boolean isTerminal(TaskStatus status)
    {
        return switch (status) {
            case COMPLETED, REMOTE_CLOSED, ERRORED, CANCELED, ARCHIVED -> true;
            default -> false;
        };
    }

    private String conversationThreadId(StageInstance stage, Task task)
    {
        if (stage.type() == StageType.PLAN_STAGE) {
            return threadStore.findBrainThreadByTask(task.id())
                    .map(Thread::id)
                    .orElse(null);
        }
        return task.threadId();
    }

    /** Development's in-stage phase ladder (plan-rail-runs.md R29) — same
     *  derivation as {@code StageServiceImpl.buildDevPhases}, kept local
     *  here the same way {@link #liveRound} duplicates rather than shares. */
    private static List<TaskBrainViewData.DevPhase> buildDevPhases(
            TaskPhase phase, StageInstance dev, List<AgentRun> liveRuns, List<ReviewRound> rounds)
    {
        if (dev == null) {
            return List.of();
        }
        boolean devClosed = dev.state() == StageState.CLOSED;
        boolean pastImplementing = devClosed || phase != TaskPhase.IMPLEMENTING;
        boolean pastValidation = devClosed || VALIDATION_DONE_PHASES.contains(phase);
        AgentRun localCiFix = liveRuns.stream()
                .filter(r -> AgentRun.KIND_CI_FIX.equals(r.kind()))
                .filter(r -> AgentRun.SOURCE_LOCAL.equals(r.source()))
                .filter(r -> dev.id().toString().equals(r.parentStageId()))
                .findFirst()
                .orElse(null);
        ReviewRound brainRound = rounds.stream()
                .filter(r -> ReviewRound.ORIGIN_BRAIN.equals(r.origin()))
                .findFirst()
                .orElse(null);

        return List.of(
                new TaskBrainViewData.DevPhase(
                        "implementing", pastImplementing ? "done" : "running", null, null),
                new TaskBrainViewData.DevPhase(
                        "validation",
                        pastValidation ? "done" : phase == TaskPhase.VALIDATING ? "running" : "future",
                        null,
                        localCiFix != null ? localCiFix.id() : null),
                buildBrainReviewPhase(brainRound));
    }

    private static TaskBrainViewData.DevPhase buildBrainReviewPhase(ReviewRound brainRound)
    {
        if (brainRound == null) {
            return new TaskBrainViewData.DevPhase("brainReview", "future", "next", null);
        }
        if (brainRound.isLive()) {
            return new TaskBrainViewData.DevPhase(
                    "brainReview", "running", "iter " + brainRound.iteration(), brainRound.runId());
        }
        return new TaskBrainViewData.DevPhase("brainReview", "done", null, null);
    }

    /** Phases reached only once Validation has finished. */
    private static final Set<TaskPhase> VALIDATION_DONE_PHASES = EnumSet.of(
            TaskPhase.INTERNAL_REVIEW, TaskPhase.AWAITING_PUSH, TaskPhase.ADDRESSING_LOCAL_COMMENTS,
            TaskPhase.PUSHED_AWAITING_CI, TaskPhase.AWAITING_READY, TaskPhase.AWAITING_REMOTE_REVIEW,
            TaskPhase.COMPLETED);

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
            StageInstance stage, List<TaskStageIteration> iters,
            TimeWindow window, List<ThreadMessage> devMessages)
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
                window.start().toString(),
                window.closedAtText(),
                stage.callerStageId().map(UUID::toString).orElse(null),
                iters.size(),
                currentIter,
                config,
                buildMetrics(stage, iters, window, devMessages));
    }

    private StageMetricsSubset buildMetrics(
            StageInstance stage, List<TaskStageIteration> iters,
            TimeWindow window, List<ThreadMessage> devMessages)
    {
        List<ThreadMessage> stageMsgs = stageMessages(stage, devMessages);
        List<OperationGroup> groups = operationGroups(stageMsgs);
        long toolCalls = groups.stream().mapToLong(g -> g.messages().size()).sum();
        long tokens = stageMsgs.stream()
                .mapToLong(m -> nz(m.tokensIn()) + nz(m.tokensOut())).sum();
        long costMilli = stageMsgs.stream().mapToLong(m -> nz(m.costUsdMilli())).sum();
        long turns = turnStore.listTurnsByTaskId(stage.taskId(), TURN_SCAN_CAP).stream()
                .filter(t -> window.contains(t.createdAt())).count();
        // Steering (interventions): user messages stamped with this stage.
        long interventions = stageMsgs.stream()
                .filter(m -> "user".equals(m.role()) && "text".equals(m.type())).count();
        List<OperationGroup> ops = groups.stream()
                .filter(g -> g.kind() != null)
                .toList();
        long activeTimeSec = ops.stream()
                .mapToLong(StageDetailServiceImpl::groupDurationSec)
                .sum();
        Map<String, Integer> operationsCount = new LinkedHashMap<>();
        for (OperationGroup g : ops) {
            operationsCount.merge(g.kind(), 1, Integer::sum);
        }
        List<TaskPhaseEvent> phaseEvents = taskStore.listPhaseEvents(stage.taskId());
        long waitingUserSec = waitingUserTimeSec(phaseEvents, window);
        long backflows = phaseEvents.stream()
                .filter(e -> window.contains(e.transitionedAt()))
                .filter(StageDetailServiceImpl::isBackflow)
                .count();

        return new StageMetricsSubset(
                window.durationSeconds(),
                iters.size(),
                (int) toolCalls,
                (int) turns,
                stageMsgs.size(),
                tokens,
                Math.round(costMilli * 0.1),
                /* panelInvocationsCount */ 0,
                activeTimeSec,
                waitingUserSec,
                operationsCount,
                (int) interventions,
                (int) backflows,
                terminalState(stage.state()));
    }

    /** A run of consecutive tool calls grouped at read time. {@code kind} is
     *  null for ungrouped calls (each is its own single-message group). */
    private record OperationGroup(String kind, List<ThreadMessage> messages) {}

    /**
     * The dev-thread messages that belong to {@code stage}: rows explicitly
     * stamped with this stage id — every message written by current code
     * carries one (see {@code AbstractCliThreadAgent}/{@code
     * LogicLoopThreadAgent}). A row stamped with a <em>different</em> stage
     * is excluded even when its timestamp overlaps with this one's, which is
     * what makes a callable sub-stage's transcript unambiguous.
     */
    private static List<ThreadMessage> stageMessages(StageInstance stage, List<ThreadMessage> devMessages)
    {
        String id = stage.id().toString();
        return devMessages.stream().filter(m -> id.equals(m.stageId())).toList();
    }

    private record TimeWindow(Instant start, Instant end, String closedAtText)
    {
        static TimeWindow forStage(StageInstance stage)
        { return endingAt(stage.openedAt(), stage.closedAt().orElse(null)); }
        static TimeWindow forIteration(TaskStageIteration iteration)
        { return endingAt(iteration.startedAt(), iteration.endedAt()); }
        private static TimeWindow endingAt(Instant start, Instant closedAt)
        {
            return new TimeWindow(start,
                    closedAt == null ? Instant.now() : closedAt,
                    closedAt == null ? null : closedAt.toString());
        }
        boolean contains(Instant ts) { return ts != null && !ts.isBefore(start) && !ts.isAfter(end); }
        List<ThreadMessage> messages(List<ThreadMessage> messages)
        { return messages.stream().filter(message -> contains(message.ts())).toList(); }
        long durationSeconds() { return Math.max(0, (end.toEpochMilli() - start.toEpochMilli()) / 1000); }
    }

    private static long groupDurationSec(OperationGroup g)
    {
        Instant first = g.messages().get(0).ts();
        Instant last = g.messages().get(g.messages().size() - 1).ts();
        return Math.max(0, (last.toEpochMilli() - first.toEpochMilli()) / 1000);
    }

    private static final long OPERATION_GAP_SECONDS = 60;

    /** Phases where the loop is parked on the user — counted as waiting time. */
    private static final Set<TaskPhase> USER_GATED_PHASES = EnumSet.of(
            TaskPhase.INTERNAL_REVIEW, TaskPhase.AWAITING_PUSH, TaskPhase.NEEDS_ATTENTION);

    /** Backward (rework) phase transitions — moving back to redo earlier work
     *  rather than progressing. Kept as an explicit set since the natural
     *  progression isn't a strict enum ordering. Neither CI red nor a new
     *  remote review batch moves the phase at all anymore (a ci_fix /
     *  review_round {@code AgentRun} handles each beside whatever phase the
     *  task is already on), so neither is a backflow anymore. */
    private static final Set<Map.Entry<TaskPhase, TaskPhase>> BACKFLOWS =
            Set.of(
                    Map.entry(TaskPhase.INTERNAL_REVIEW, TaskPhase.IMPLEMENTING),
                    Map.entry(TaskPhase.VALIDATING, TaskPhase.IMPLEMENTING),
                    Map.entry(TaskPhase.AWAITING_PUSH, TaskPhase.ADDRESSING_LOCAL_COMMENTS));

    private static boolean isBackflow(TaskPhaseEvent e)
    {
        return e.fromPhase() != null && e.toPhase() != null
                && BACKFLOWS.contains(Map.entry(e.fromPhase(), e.toPhase()));
    }

    /** Group tool-call messages into operation runs: a new run starts
     *  when the inferred kind changes or the gap exceeds the threshold. A
     *  null-kind call always starts its own single-message group (rendered
     *  free-floating). */
    private List<OperationGroup> operationGroups(List<ThreadMessage> messages)
    {
        List<ThreadMessage> sortedToolCalls = messages.stream()
                .filter(m -> "tool_call".equals(m.type()))
                .sorted(Comparator.comparing(ThreadMessage::ts))
                .toList();
        List<OperationGroup> groups = new ArrayList<>();
        String curKind = null;
        List<ThreadMessage> cur = null;
        Instant last = null;
        for (ThreadMessage m : sortedToolCalls) {
            String k = operationKind(m);
            boolean continues = cur != null && k != null && k.equals(curKind)
                    && last != null
                    && m.ts().toEpochMilli() - last.toEpochMilli() <= OPERATION_GAP_SECONDS * 1000;
            if (continues) {
                cur.add(m);
                last = m.ts();
                continue;
            }
            if (cur != null) {
                groups.add(new OperationGroup(curKind, cur));
            }
            cur = new ArrayList<>();
            cur.add(m);
            curKind = k;
            last = m.ts();
        }
        if (cur != null) {
            groups.add(new OperationGroup(curKind, cur));
        }
        return groups;
    }

    /** Infer an operation kind from a tool-call message, or null when it
     *  doesn't map to a known operation (renders ungrouped). */
    private String operationKind(ThreadMessage m)
    {
        ToolCallPayload tc = toolCall(m);
        String name = tc.label() == null ? "" : tc.label().toLowerCase(Locale.ROOT);
        String detail = tc.detail() == null ? "" : tc.detail().toLowerCase(Locale.ROOT);
        if (name.contains("read") || name.contains("edit") || name.contains("write")
                || name.contains("notebook")) {
            return "code";
        }
        boolean run = name.contains("run") || name.contains("bash") || name.contains("shell")
                || name.contains("exec");
        if (run && detail.contains("git push")) {
            return "push";
        }
        if (run && detail.contains("gh pr")) {
            return "publish";
        }
        if (run && (detail.contains("mvn verify") || detail.contains("npm test") || detail.contains("tsc")
                || detail.contains("pytest") || detail.contains("npm run test") || detail.contains("lint"))) {
            return "validate";
        }
        return null;
    }

    /** Sum of time the stage spent in user-gated phases, clipped to its
     *  window. Walks consecutive phase events; the final phase runs to the
     *  window end. */
    private static long waitingUserTimeSec(List<TaskPhaseEvent> events, TimeWindow window)
    {
        List<TaskPhaseEvent> sorted = events.stream()
                .sorted(Comparator.comparing(TaskPhaseEvent::transitionedAt))
                .toList();
        long sec = 0;
        for (int i = 0; i < sorted.size(); i++) {
            TaskPhaseEvent e = sorted.get(i);
            if (!USER_GATED_PHASES.contains(e.toPhase())) {
                continue;
            }
            Instant from = e.transitionedAt();
            Instant to = i + 1 < sorted.size() ? sorted.get(i + 1).transitionedAt() : window.end();
            Instant clipFrom = from.isBefore(window.start()) ? window.start() : from;
            Instant clipTo = to.isAfter(window.end()) ? window.end() : to;
            if (clipTo.isAfter(clipFrom)) {
                sec += (clipTo.toEpochMilli() - clipFrom.toEpochMilli()) / 1000;
            }
        }
        return sec;
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
        TimeWindow window = TimeWindow.forIteration(it);
        List<LogRow> rows = new ArrayList<>();

        // Scope to this iteration's own stage first (stage_id is the durable
        // attribution — trunk/task/sibling-stage messages never carry it),
        // then bucket into this iteration's band by time; the window alone,
        // applied to every message on the thread, would leak the same way
        // stageMessages() used to.
        String stageId = it.stageId().toString();
        List<ThreadMessage> stageMsgs = devMessages.stream()
                .filter(m -> stageId.equals(m.stageId()))
                .toList();
        List<ThreadMessage> windowMsgs = window.messages(stageMsgs);

        for (OperationGroup g : operationGroups(windowMsgs)) {
            List<LogRow> nested = g.messages().stream()
                    .map(m -> new LogRow(m.id(), m.ts().toString(), "tool_call",
                            toolCall(m), null, null, null, null))
                    .toList();
            if (g.kind() == null) {
                rows.addAll(nested);
                continue;
            }
            Instant first = g.messages().get(0).ts();
            Instant lastTs = g.messages().get(g.messages().size() - 1).ts();
            rows.add(new LogRow("op:" + g.messages().get(0).id(), first.toString(), "operation",
                    null, null, null, null,
                    new StageDetailData.OperationPayload(g.kind(), first.toString(), lastTs.toString(),
                            Math.max(0, (lastTs.toEpochMilli() - first.toEpochMilli()) / 1000),
                            nested.size(), "ok", nested)));
        }
        for (ThreadMessage m : windowMsgs) {
            if ("user".equals(m.role()) && "text".equals(m.type())) {
                rows.add(new LogRow(m.id(), m.ts().toString(), "user_message",
                        null, null, null, new UserMessagePayload(decodeText(m.contentJson())), null));
            }
        }
        for (StageEvent e : events) {
            if (window.contains(e.eventAt())) {
                rows.add(new LogRow(e.id().toString(), e.eventAt().toString(), "stage_event",
                        null, new StageEventPayload(
                                e.eventType().name(), humanizeEvent(e.eventType().name()), e.payloadJson()),
                        null, null, null));
            }
        }
        String recordedBy = it.summaryText() == null ? null : recordedBy(it);
        if (it.summaryText() != null) {
            Instant at = it.summarizedAt() == null ? window.end() : it.summarizedAt();
            rows.add(new LogRow(it.id() + ":summary", at.toString(), "iteration_summary",
                    null, null,
                    new StageDetailData.IterationSummaryPayload(it.summaryText(), recordedBy, at.toString()),
                    null, null));
        }
        rows.sort(Comparator.comparing(LogRow::ts));

        return new IterationDetail(
                it.id().toString(),
                it.iterationNumber(),
                it.trigger(),
                window.start().toString(),
                window.closedAtText(),
                it.endedReason(),
                it.summaryText(),
                recordedBy,
                rows);
    }

    /**
     * The stage's conversation transcript — the base timeline the detail view
     * renders. A PlanStage is the brain thread (seed → planning → plan); every
     * other stage is the dev agent's turns + tool calls on the task thread
     * within the stage window. Loop boundaries (CI-fixing / addressing-comments)
     * are interleaved as {@code iteration_marker} rows so the same flat timeline
     * renders whether the stage looped or ran once.
     */
    private List<ConversationRow> buildConversation(
            StageInstance stage,
            Task task,
            List<ThreadMessage> devMessages,
            List<TaskStageIteration> iters,
            List<StageEvent> events)
    {
        List<ThreadMessage> source = stage.type() == StageType.PLAN_STAGE
                ? threadStore.findBrainThreadByTask(task.id())
                        .map(t -> threadStore.listMessages(t.id()))
                        .orElseGet(List::of)
                : stageMessages(stage, devMessages);

        // Pair each tool call with its result row (same callId) so the
        // transcript can show command + outcome on a single card.
        Map<String, ToolResultPayload> resultsByCallId = new HashMap<>();
        // Permission prompts that already got an answer (decided or auto-
        // allowed) — a prompt with no entry here is still pending and gets a
        // clickable card.
        Set<String> decidedCallIds = new HashSet<>();
        for (ThreadMessage m : source) {
            if ("tool_result".equals(m.type())) {
                ToolResultPayload r = toolResult(m);
                if (r.callId() != null) {
                    resultsByCallId.put(r.callId(), r);
                }
            }
            else if ("permission_decision".equals(m.type()) || "permission_auto_allowed".equals(m.type())) {
                String cid = callIdOf(m);
                if (cid != null) {
                    decidedCallIds.add(cid);
                }
            }
        }

        List<ConversationRow> rows = new ArrayList<>();
        for (ThreadMessage m : source) {
            if ("tool_call".equals(m.type())) {
                ToolCallPayload tc = toolCall(m);
                String callId = callIdOf(m);
                ToolResultPayload r = callId == null ? null : resultsByCallId.get(callId);
                rows.add(new ConversationRow(m.id(), m.seq(), "tool_call", null,
                        tc.tag(), tc.label(), tc.detail(),
                        r == null ? null : r.output(), r == null ? null : r.isError(),
                        editDiff(m), null, m.ts().toString(), null, List.of(), List.of(), null));
            }
            else if ("text".equals(m.type())) {
                String text = decodeText(m.contentJson());
                boolean user = "user".equals(m.role());
                List<String> images = user ? decodeStringArray(m.contentJson(), "images") : List.of();
                List<String> managedSkills = user ? decodeStringArray(m.contentJson(), "managedSkills") : List.of();
                if ((text == null || text.isBlank()) && images.isEmpty() && managedSkills.isEmpty()) {
                    continue;
                }
                rows.add(new ConversationRow(m.id(), m.seq(), user ? "user" : "agent",
                        text, null, null, null, null, null, null, null, m.ts().toString(), null,
                        images, managedSkills, null));
            }
            else if ("permission_request".equals(m.type())) {
                String callId = callIdOf(m);
                if (callId != null && !decidedCallIds.contains(callId)) {
                    rows.add(new ConversationRow(m.id(), m.seq(), "permission",
                            permissionField(m, "summary"),
                            null, permissionField(m, "toolName"), null,
                            null, null, null, null, m.ts().toString(), callId, List.of(), List.of(), null));
                }
            }
        }
        for (TaskStageIteration it : iters) {
            rows.add(new ConversationRow(it.id() + ":marker", null, "iteration_marker",
                    it.trigger(), null, null, null, null, null, null, it.iterationNumber(),
                    TimeWindow.forIteration(it).start().toString(), null, List.of(), List.of(), null));
        }
        for (StageEvent event : events) {
            if (event.eventType() != StageEventType.PULL_REQUEST_CREATED) {
                continue;
            }
            rows.add(new ConversationRow(
                    event.id().toString(), null, "pull_request_created", "Pull request created",
                    null, null, null, null, null, null, null, event.eventAt().toString(), null,
                    List.of(), List.of(), PullRequestCreatedData.fromPayload(event.payloadJson(), mapper)));
        }
        rows.sort(Comparator.comparing(ConversationRow::ts));
        return rows;
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

    /** The task's PR detail (cached), or null when it has no PR / the fetch
     *  fails. Shared by {@link #buildRealtimeCi} and {@link #buildPrTab}. */
    private PullRequestDetail fetchPrDetail(Task task)
    {
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isEmpty()) {
            return null;
        }
        PullRequestRef r = ref.get();
        try {
            return pullRequests.getPullRequestDetail(r.repoRef().fullName(), r.number());
        }
        catch (RuntimeException e) {
            log.warn("stage detail PR fetch for {} failed: {}", task.id(), e.getMessage());
            return null;
        }
    }

    private RealtimeCi buildRealtimeCi(Task task, PullRequestDetail detail)
    {
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isEmpty() || detail == null) {
            return null;
        }
        PullRequestRef r = ref.get();
        List<CiCheck> checks = detail.checkRuns() == null ? List.of()
                : detail.checkRuns().stream()
                        .map(c -> new CiCheck(c.name(), ciCheckStatus(c.status(), c.conclusion()), null))
                        .toList();
        return new RealtimeCi(
                ciStatus(detail.ciStatus()),
                "https://github.com/" + r.repoRef().fullName() + "/pull/" + r.number(),
                checks,
                Instant.now().toString());
    }

    /** The PR-tab block: status, branch flow, reviewers, labels, a check-run
     *  summary, and the per-line review threads — all from the cached PR
     *  detail (no extra GitHub call). Null when the task has no PR. */
    private StageDetailData.PrTab buildPrTab(Task task, PullRequestDetail detail)
    {
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isEmpty() || detail == null) {
            return null;
        }
        // A non-blank merge-queue entry state means the PR is sitting in the
        // queue (approved + waiting for its slot) rather than plainly open.
        boolean queued = detail.mergeQueueState() != null
                && !detail.mergeQueueState().isBlank()
                && !detail.merged();
        String status = detail.merged() ? "merged"
                : queued ? "queued"
                : detail.draft() ? "draft" : "open";

        int passed = 0;
        int failed = 0;
        int pending = 0;
        var runs = detail.checkRuns() == null ? List.<PullRequestDetail.CheckRun>of() : detail.checkRuns();
        for (var c : runs) {
            switch (ciCheckStatus(c.status(), c.conclusion())) {
                case "ok" -> passed++;
                case "fail" -> failed++;
                default -> pending++;
            }
        }

        var threadSrc = detail.reviewThreads() == null
                ? List.<PullRequestDetail.ReviewThread>of() : detail.reviewThreads();
        List<StageDetailData.PrThread> threads = threadSrc.stream()
                .map(t -> {
                    var msgs = t.messages() == null
                            ? List.<PullRequestDetail.ReviewMessage>of() : t.messages();
                    return new StageDetailData.PrThread(
                            String.valueOf(t.rootGithubId()),
                            t.filePath(),
                            t.line(),
                            Boolean.TRUE.equals(t.resolved()),
                            msgs.stream()
                                    .map(m -> new StageDetailData.PrThreadMsg(m.author(), m.body()))
                                    .toList());
                })
                .toList();

        return new StageDetailData.PrTab(
                ref.get().number(),
                status,
                queued ? detail.mergeQueueState() : null,
                detail.headRef(),
                detail.baseRef(),
                detail.requestedReviewers() == null ? List.of() : detail.requestedReviewers(),
                detail.labels() == null ? List.of() : detail.labels(),
                new StageDetailData.PrChecks(passed, failed, pending, passed + failed + pending),
                threads);
    }

    private List<CiFixHistoryEntry> buildCiFixHistory(
            StageInstance stage, List<TaskStageIteration> iters, List<StageEvent> events)
    {
        if (stage.type() != StageType.CI_FIXING_STAGE
                && stage.type() != StageType.REMOTE_DEVELOPMENT_STAGE) {
            return List.of();
        }
        // The per-fix detail (failing check + error + Actions URL) rides the
        // LOOP_ITERATION_STARTED event payload, keyed by iteration number;
        // absent on iters written before the payload enrichment landed.
        Map<Integer, JsonNode> startedByIter = new HashMap<>();
        for (StageEvent e : events) {
            if (e.eventType() != StageEventType.LOOP_ITERATION_STARTED || e.payloadJson() == null) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(e.payloadJson());
                JsonNode num = node.get("iterationNumber");
                if (num != null && num.isInt()) {
                    startedByIter.put(num.asInt(), node);
                }
            }
            catch (JsonProcessingException ignored) {
                // malformed payload → no enrichment for this iter
            }
        }
        return iters.stream()
                .map(it -> {
                    JsonNode started = startedByIter.get(it.iterationNumber());
                    return new CiFixHistoryEntry(
                            it.iterationNumber(), it.endedReason(), it.summaryText(),
                            text(started, "failedCheck"),
                            text(started, "errorMessage"),
                            text(started, "actionsRunUrl"));
                })
                .toList();
    }

    /** Read a string field from a payload node, or null when absent/blank. */
    private static String text(JsonNode node, String field)
    {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
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
                // Tool args usually nest under an "input" object (the
                // claude-code stream-json shape); fall back to the top level.
                JsonNode args = node.get("input");
                JsonNode argSource = args != null && args.isObject() ? args : node;
                detail = firstText(argSource,
                        "file_path", "path", "file", "filePath", "command", "cmd", "pattern", "query");
            }
            catch (JsonProcessingException ignore) {
                name = null;
            }
        }
        return new ToolCallPayload(tagFor(name), name == null ? "tool" : name, detail);
    }

    /** The {@code callId} that links a tool_call to its tool_result row. */
    private String callIdOf(ThreadMessage m)
    {
        if (m.contentJson() == null) {
            return null;
        }
        try {
            return firstText(mapper.readTree(m.contentJson()), "callId");
        }
        catch (JsonProcessingException ignore) {
            return null;
        }
    }

    /** Read one field from a {@code permission_request} message's JSON body
     *  ({@code toolName} / {@code summary}); null when absent or unparseable. */
    private String permissionField(ThreadMessage m, String field)
    {
        if (m.contentJson() == null) {
            return null;
        }
        try {
            return firstText(mapper.readTree(m.contentJson()), field);
        }
        catch (JsonProcessingException ignore) {
            return null;
        }
    }

    /** Max length of a tool result preview surfaced in the transcript. */
    private static final int TOOL_RESULT_CAP = 2000;

    /** Max length of an edit diff surfaced on a tool card. */
    private static final int TOOL_DIFF_CAP = 6000;

    /**
     * For an edit/write tool call, a compact +/- diff built from the call
     * input: {@code old_string} → {@code new_string} (Edit), each entry of
     * {@code edits} (MultiEdit), or the whole {@code content} as additions
     * (Write). Null for non-editing tools or input without those fields.
     */
    private String editDiff(ThreadMessage m)
    {
        if (m.contentJson() == null) {
            return null;
        }
        try {
            JsonNode input = mapper.readTree(m.contentJson()).get("input");
            if (input == null || !input.isObject()) {
                return null;
            }
            StringBuilder out = new StringBuilder();
            JsonNode edits = input.get("edits");
            if (edits != null && edits.isArray()) {
                for (JsonNode e : edits) {
                    appendEditHunk(out, textOf(e, "old_string"), textOf(e, "new_string"));
                }
            }
            else if (input.has("old_string") || input.has("new_string")) {
                appendEditHunk(out, textOf(input, "old_string"), textOf(input, "new_string"));
            }
            else if (input.has("content")) {
                appendLines(out, textOf(input, "content"), '+');
            }
            if (out.length() == 0) {
                return null;
            }
            String diff = out.toString();
            return diff.length() > TOOL_DIFF_CAP ? diff.substring(0, TOOL_DIFF_CAP) + "\n…" : diff;
        }
        catch (JsonProcessingException ignore) {
            return null;
        }
    }

    private static void appendEditHunk(StringBuilder out, String oldText, String newText)
    {
        appendLines(out, oldText, '-');
        appendLines(out, newText, '+');
    }

    private static void appendLines(StringBuilder out, String text, char sign)
    {
        if (text == null) {
            return;
        }
        for (String line : text.split("\n")) {
            out.append(sign).append(' ').append(line).append('\n');
        }
    }

    private static String textOf(JsonNode node, String field)
    {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Decode a tool_result row into its callId, error flag, and a text
     *  preview of the output (the raw string, or compact JSON, capped). */
    private ToolResultPayload toolResult(ThreadMessage m)
    {
        if (m.contentJson() == null) {
            return new ToolResultPayload(null, false, null);
        }
        try {
            JsonNode node = mapper.readTree(m.contentJson());
            String callId = firstText(node, "callId");
            JsonNode err = node.get("isError");
            boolean isError = err != null && err.asBoolean(false);
            JsonNode out = node.get("output");
            String output = null;
            if (out != null && !out.isNull()) {
                output = out.isValueNode() ? out.asText() : out.toString();
                if (output.length() > TOOL_RESULT_CAP) {
                    output = output.substring(0, TOOL_RESULT_CAP) + "…";
                }
            }
            return new ToolResultPayload(callId, isError, output);
        }
        catch (JsonProcessingException ignore) {
            return new ToolResultPayload(null, false, null);
        }
    }

    private record ToolResultPayload(String callId, boolean isError, String output) {}

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

    /** Extract a string-array field from a message envelope, if any — see
     *  {@code MessageAttachments.encodeMessage}. */
    private List<String> decodeStringArray(String contentJson, String field)
    {
        if (contentJson == null || contentJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(contentJson).path(field);
            List<String> values = new ArrayList<>();
            node.forEach(n -> values.add(n.asText()));
            return values;
        }
        catch (JsonProcessingException e) {
            return List.of();
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
        return PullRequestRef.parse(linkedPrRef).map(p -> p.repoRef().fullName()).orElse("");
    }

    private static boolean isDraft(String prState)
    {
        return prState != null && prState.toLowerCase(Locale.ROOT).contains("draft");
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }
}
