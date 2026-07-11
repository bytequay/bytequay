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

import com.bytequay.app.beans.stage.ApprovalDto;
import com.bytequay.app.beans.stage.BrainFeedRow;
import com.bytequay.app.beans.stage.CommitDto;
import com.bytequay.app.beans.stage.ContextWindowDto;
import com.bytequay.app.beans.stage.LinkedPrDto;
import com.bytequay.app.beans.stage.ScrubberDash;
import com.bytequay.app.beans.stage.StageDetailDto;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.beans.stage.StageEventDto;
import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.review.BranchGuardService;
import com.bytequay.app.service.review.ReviewRoundService;
import com.bytequay.app.service.runs.AgentRunService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@Service
public class StageServiceImpl
        implements StageService
{
    /** Placeholder context-window cap until the token accounting lands. */
    private static final int DEFAULT_CONTEXT_TOKEN_LIMIT = 200_000;

    /** Max events returned with a stage detail payload. */
    private static final int STAGE_DETAIL_EVENT_LIMIT = 50;

    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final StageBudgetService budgetService;
    private final ThreadTurnEventStore turnEventStore;
    private final ThreadStore threadStore;
    private final ThreadTurnStore turnStore;
    private final AgentRunService agentRuns;
    private final BranchGuardService branchGuards;
    private final ReviewRoundService reviewRounds;
    private final ObjectMapper mapper;

    public StageServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            StageBudgetService budgetService,
            ThreadTurnEventStore turnEventStore,
            ThreadStore threadStore,
            ThreadTurnStore turnStore,
            AgentRunService agentRuns,
            BranchGuardService branchGuards,
            ReviewRoundService reviewRounds,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.budgetService = requireNonNull(budgetService, "budgetService is null");
        this.turnEventStore = requireNonNull(turnEventStore, "turnEventStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
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
    public TaskBrainViewData getBrain(String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> notFound("no task: " + taskId));
        List<StageInstance> allStages = stageStore.findStagesByTask(taskId);
        List<StageEvent> allEvents = stageStore.findEventsByTask(taskId);

        List<StageDto> topLevel = allStages.stream()
                .filter(s -> s.callerStageId().isEmpty())
                .map(StageServiceImpl::toDto)
                .toList();
        List<StageDto> subStages = allStages.stream()
                .filter(s -> s.callerStageId().isPresent())
                .map(StageServiceImpl::toDto)
                .toList();

        Map<UUID, StageType> stageTypes = allStages.stream()
                .collect(Collectors.toMap(StageInstance::id, StageInstance::type, (a, b) -> a));

        // The brain thread is the single source for the plan-stage
        // conversation: the trunk's seed (copied onto it when the brain thread
        // was created) followed by the brain agent's planning and any user
        // steering. The feed never reads the trunk/dev thread on the fly — dev
        // work shows only as stage-event checkpoints below.
        List<ThreadMessage> brainMessages = brainMessages(taskId);
        // Index of stage display names → id for resolving drill-in chips.
        Map<String, String> stageNameIndex = stageNameIndex(allStages);

        boolean terminal = isTerminal(task.status());
        TaskBrainViewData.CostBreakdown cost = buildCostBreakdown(task, allStages, brainMessages);
        List<AgentRun> liveRuns = terminal ? List.of() : agentRuns.liveRunsByTask(taskId);
        ReviewRound liveRound = terminal ? null : liveRound(taskId);
        StageInstance dev = allStages.stream()
                .filter(s -> s.type() == StageType.DEVELOPMENT_STAGE)
                .findFirst()
                .orElse(null);

        return new TaskBrainViewData(
                buildTask(task),
                buildAggregate(task, allStages, brainMessages, cost.totalCents()),
                topLevel,
                subStages,
                threadStore.findBrainThreadByTask(taskId).map(Thread::id).orElse(null),
                buildBrainFeed(allEvents, stageTypes, turnEventStore.listSummaryEventsByTask(taskId),
                        brainMessages, stageNameIndex, buildStageStats(task, allStages)),
                buildRightRail(task, allStages, cost),
                buildScrubbers(allEvents, brainMessages),
                liveRuns,
                branchGuards.get(taskId),
                liveRound,
                buildDevPhases(task.phase(), dev, liveRuns, reviewRounds.findByTask(taskId)));
    }

    /** Development's in-stage phase ladder (plan-rail-runs.md R29):
     *  Implementing → Validation → Brain review. {@code status} values are
     *  already in the rail's vocabulary so the frontend applies them as-is. */
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

    /** The task's brain-thread conversation (user + assistant text rows),
     *  oldest-first; empty when no brain thread exists yet. */
    private List<ThreadMessage> brainMessages(String taskId)
    {
        return threadStore.findBrainThreadByTask(taskId)
                .map(t -> threadStore.listMessages(t.id()))
                .orElseGet(List::of)
                .stream()
                .filter(m -> "text".equals(m.type()))
                .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                .toList();
    }

    /** Display-name → stage id, for resolving stage mentions in brain
     *  replies. When several stages share a type the most recent wins. */
    private static Map<String, String> stageNameIndex(List<StageInstance> stages)
    {
        Map<String, String> index = new HashMap<>();
        for (StageInstance s : stages) {
            index.put(s.type().displayName(), s.id().toString());
        }
        return index;
    }

    @Override
    public List<StageDto> getStages(String taskId)
    {
        return stageStore.findStagesByTask(taskId).stream()
                .filter(s -> s.callerStageId().isEmpty())
                .map(StageServiceImpl::toDto)
                .toList();
    }

    @Override
    public List<StageDto> getActiveStages(String taskId)
    {
        return stageStore.findStagesByTask(taskId).stream()
                .filter(s -> s.state() == StageState.OPEN || s.state() == StageState.ACTIVE)
                .map(StageServiceImpl::toDto)
                .toList();
    }

    @Override
    public StageDetailDto getStageDetail(UUID stageId)
    {
        StageInstance stage = stageStore.findStageById(stageId)
                .orElseThrow(() -> notFound("no stage: " + stageId));
        List<StageEventDto> events = stageStore
                .findRecentEventsByStage(stageId, STAGE_DETAIL_EVENT_LIMIT).stream()
                .map(StageServiceImpl::toEventDto)
                .toList();
        return new StageDetailDto(toDto(stage), events);
    }

    // ── brain-view builders ─────────────────────────────────────────────

    private static TaskBrainViewData.BrainTask buildTask(Task task)
    {
        return new TaskBrainViewData.BrainTask(
                task.id(),
                title(task),
                task.seq(),
                nullToEmpty(task.branchName()),
                repoFullName(task.linkedPrRef()),
                task.prNumber(),
                isDraft(task.prState()),
                task.phase().name(),
                statusLabel(task),
                "CLI",
                "",
                task.status() == TaskStatus.PAUSED,
                isTerminal(task.status()));
    }

    /** Terminal task statuses — the rail shows a closed state, not controls. */
    private static boolean isTerminal(TaskStatus status)
    {
        return switch (status) {
            case COMPLETED, REMOTE_CLOSED, ERRORED, CANCELED, ARCHIVED -> true;
            default -> false;
        };
    }

    /** Scan cap for counting the task's agent turns. */
    private static final int TURN_SCAN_CAP = 1000;

    /** Phases where the loop is parked on the user — counted as waiting time. */
    private static final Set<TaskPhase> USER_GATED_PHASES = EnumSet.of(
            TaskPhase.INTERNAL_REVIEW, TaskPhase.AWAITING_PUSH, TaskPhase.NEEDS_ATTENTION);

    /** A task's full dev-side transcript: the per-thread rows plus the
     *  decoupled per-stage stage_messages, merged chronologically. Until the
     *  backfill consolidates them, stage turns live in stage_messages while
     *  legacy stage rows may still sit in thread_messages. */
    private List<ThreadMessage> devMessagesFor(Task task)
    {
        if (task.threadId() == null) {
            return List.of();
        }
        List<ThreadMessage> merged = new ArrayList<>(threadStore.listMessages(task.threadId()));
        merged.addAll(threadStore.listStageMessagesByTask(task.id()));
        merged.sort(Comparator.comparing(ThreadMessage::ts));
        return merged;
    }

    private TaskBrainViewData.Aggregate buildAggregate(
            Task task, List<StageInstance> stages, List<ThreadMessage> brainMessages, long costCents)
    {
        long activeTimeSec = stages.stream()
                .filter(s -> s.closedAt().isPresent())
                .mapToLong(s -> Math.max(0,
                        (s.closedAt().get().toEpochMilli() - s.openedAt().toEpochMilli()) / 1000))
                .sum();
        int pushes = autonomousPushes(stages);
        int panels = (int) stages.stream()
                .filter(s -> s.type() == StageType.REVIEW_STAGE)
                .count();
        TaskBrainViewData.AutoPushBudget budget = latestCiFixingBudget(stages)
                .map(b -> new TaskBrainViewData.AutoPushBudget(b.used(), b.limit()))
                .orElse(null);

        // Task-wide totals across the dev thread (tool calls + agent messages)
        // and the brain thread (planning/steering messages); turns and
        // user-gated waiting time are counted for the whole task.
        List<ThreadMessage> devMessages = devMessagesFor(task);
        int toolCalls = (int) devMessages.stream()
                .filter(m -> "tool_call".equals(m.type()))
                .count();
        int turns = task.threadId() == null ? 0
                : turnStore.listTurnsByTaskId(task.id(), TURN_SCAN_CAP).size();
        int messages = devMessages.size() + brainMessages.size();
        long waitingUserTimeSec = waitingUserTimeSec(taskStore.listPhaseEvents(task.id()));

        return new TaskBrainViewData.Aggregate(
                pushes, activeTimeSec, waitingUserTimeSec, toolCalls, turns, messages,
                panels, (int) costCents, budget);
    }

    /** Sum of wall-clock the task spent parked in user-gated phases. Walks
     *  consecutive phase events; the final phase runs to now. */
    private static long waitingUserTimeSec(List<TaskPhaseEvent> events)
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
            Instant to = i + 1 < sorted.size() ? sorted.get(i + 1).transitionedAt() : Instant.now();
            if (to.isAfter(from)) {
                sec += (to.toEpochMilli() - from.toEpochMilli()) / 1000;
            }
        }
        return sec;
    }

    /** Autonomous pushes = auto-push budget spent across ci-fixing stages. */
    private int autonomousPushes(List<StageInstance> stages)
    {
        return stages.stream()
                .filter(s -> s.type() == StageType.CI_FIXING_STAGE)
                .map(s -> budgetService.readMetrics(s.id()).autoPushBudget())
                .filter(b -> b != null)
                .mapToInt(StageMetrics.AutoPushBudget::used)
                .sum();
    }

    /**
     * Per-Task spend, attributed from {@code thread_messages} costs (the
     * thread-level counter is lifetime-cumulative across the task chain, so
     * it can't be used per-task). Dev-thread cost is split across stages by
     * the same time window the metrics use; the brain agent is its own
     * bucket. Panel-reviewer spend isn't attributed here (the review
     * subsystem owns its own cost surface).
     */
    private TaskBrainViewData.CostBreakdown buildCostBreakdown(
            Task task, List<StageInstance> stages, List<ThreadMessage> brainMessages)
    {
        List<ThreadMessage> devMessages = devMessagesFor(task).stream()
                .filter(m -> task.id().equals(m.taskId()))
                .toList();
        long devMilli = devMessages.stream().mapToLong(m -> nz(m.costUsdMilli())).sum();
        long brainMilli = brainMessages.stream().mapToLong(m -> nz(m.costUsdMilli())).sum();
        long totalCents = (devMilli + brainMilli) / 10;

        List<TaskBrainViewData.StageCost> perStage = stages.stream()
                .filter(s -> s.callerStageId().isEmpty())
                .map(s -> {
                    Instant end = s.closedAt().orElse(Instant.now());
                    long milli = devMessages.stream()
                            .filter(m -> inWindow(m.ts(), s.openedAt(), end))
                            .mapToLong(m -> nz(m.costUsdMilli())).sum();
                    return new TaskBrainViewData.StageCost(
                            s.id().toString(), s.type().name(), milli / 10);
                })
                .filter(c -> c.costCents() > 0)
                .toList();

        List<TaskBrainViewData.AgentCost> perAgent = new ArrayList<>();
        if (devMilli > 0) {
            perAgent.add(new TaskBrainViewData.AgentCost("dev", devMilli / 10));
        }
        if (brainMilli > 0) {
            perAgent.add(new TaskBrainViewData.AgentCost("brain", brainMilli / 10));
        }

        int pushes = autonomousPushes(stages);
        Long costPerPush = pushes > 0 ? totalCents / pushes : null;
        return new TaskBrainViewData.CostBreakdown(totalCents, perStage, perAgent, costPerPush);
    }

    /** Per-stage rollup surfaced on a STAGE_CLOSED feed row: how long the
     *  stage ran, how many tokens it spent, and how many distinct files it
     *  touched. */
    private record StageStat(long durationSec, long tokens, int files) {}

    /**
     * Compute the duration / tokens / changed-files rollup for each
     * top-level stage of a task, keyed by stage id. Tokens are attributed
     * by the stamped {@code stage_id} on the dev thread's {@code turn_done}
     * rows; files are the distinct {@code thread_files} paths last touched
     * within the stage's open→close window (a window proxy, since file rows
     * carry no stage id). Used to enrich the brain feed's stage-closed rows.
     */
    private Map<UUID, StageStat> buildStageStats(Task task, List<StageInstance> stages)
    {
        if (task.threadId() == null) {
            return Map.of();
        }
        List<ThreadMessage> devMessages = devMessagesFor(task).stream()
                .filter(m -> task.id().equals(m.taskId()))
                .toList();
        List<ThreadFile> files = threadStore.listFiles(task.threadId());
        Map<UUID, StageStat> out = new HashMap<>();
        for (StageInstance s : stages) {
            if (s.callerStageId().isPresent()) {
                continue;
            }
            Instant end = s.closedAt().orElse(Instant.now());
            String stageId = s.id().toString();
            long tokens = devMessages.stream()
                    .filter(m -> stageId.equals(m.stageId()))
                    .mapToLong(m -> nz(m.tokensIn()) + nz(m.tokensOut()))
                    .sum();
            // Fall back to the time window for legacy rows that predate the
            // stamped stage id, so an old stage still shows a token count.
            if (tokens == 0) {
                tokens = devMessages.stream()
                        .filter(m -> m.stageId() == null && inWindow(m.ts(), s.openedAt(), end))
                        .mapToLong(m -> nz(m.tokensIn()) + nz(m.tokensOut()))
                        .sum();
            }
            int touched = (int) files.stream()
                    .filter(f -> inWindow(f.lastTouchedAt(), s.openedAt(), end))
                    .count();
            out.put(s.id(), new StageStat(
                    Math.max(0, (end.toEpochMilli() - s.openedAt().toEpochMilli()) / 1000),
                    tokens, touched));
        }
        return out;
    }

    /** Append a "· 3m · 30k tokens · 2 files" suffix to a stage-closed body
     *  from its rollup. Omits any segment that's zero so a no-op stage stays
     *  terse. */
    private static String appendStageStats(String body, StageStat stat)
    {
        if (stat == null) {
            return body;
        }
        StringBuilder out = new StringBuilder(body);
        if (stat.durationSec() > 0) {
            out.append(" · ").append(humanizeDuration(stat.durationSec()));
        }
        if (stat.tokens() > 0) {
            out.append(" · ").append(humanizeTokens(stat.tokens())).append(" tokens");
        }
        if (stat.files() > 0) {
            out.append(" · ").append(stat.files()).append(stat.files() == 1 ? " file" : " files");
        }
        return out.toString();
    }

    private static String humanizeDuration(long sec)
    {
        if (sec < 60) {
            return sec + "s";
        }
        long minutes = sec / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        long remMin = minutes % 60;
        return remMin == 0 ? hours + "h" : hours + "h " + remMin + "m";
    }

    private static String humanizeTokens(long tokens)
    {
        if (tokens < 1_000) {
            return Long.toString(tokens);
        }
        if (tokens < 1_000_000) {
            return (tokens / 1_000) + "k";
        }
        return String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0);
    }

    /** The most-recent ci-fixing stage's budget, if any has one. */
    private Optional<StageMetrics.AutoPushBudget> latestCiFixingBudget(List<StageInstance> stages)
    {
        return latestCiFixing(stages)
                .map(s -> budgetService.readMetrics(s.id()).autoPushBudget())
                .filter(b -> b != null);
    }

    /** The most-recent ci-fixing stage instance (stages arrive oldest-first). */
    private static Optional<StageInstance> latestCiFixing(List<StageInstance> stages)
    {
        StageInstance latest = null;
        for (StageInstance s : stages) {
            if (s.type() == StageType.CI_FIXING_STAGE) {
                latest = s;
            }
        }
        return Optional.ofNullable(latest);
    }

    private List<BrainFeedRow> buildBrainFeed(
            List<StageEvent> events,
            Map<UUID, StageType> stageTypes,
            List<ThreadTurnEvent> summaries,
            List<ThreadMessage> brainMessages,
            Map<String, String> stageNameIndex,
            Map<UUID, StageStat> stageStats)
    {
        List<FeedEntry> entries = new ArrayList<>();
        for (StageEvent e : events) {
            brainFeedRow(e, stageTypes, stageStats)
                    .ifPresent(row -> entries.add(new FeedEntry(e.eventAt(), row)));
        }
        for (ThreadTurnEvent s : summaries) {
            entries.add(new FeedEntry(s.createdAt(), summaryRow(s, stageTypes)));
        }
        // The whole plan-stage conversation lives on the brain thread (trunk
        // seed copied in + the brain's planning + user steering); render it.
        for (ThreadMessage m : brainMessages) {
            entries.add(new FeedEntry(m.ts(), brainMessageRow(m, stageNameIndex)));
        }
        entries.sort(Comparator.comparing(FeedEntry::ts));
        return entries.stream().map(FeedEntry::row).toList();
    }

    /** Map a brain-thread message to a feed row. User messages are the YOU
     *  bubble; assistant messages are the brain reply, with the first
     *  mentioned stage resolved to a drill-in chip id. */
    private BrainFeedRow brainMessageRow(ThreadMessage m, Map<String, String> stageNameIndex)
    {
        boolean user = "user".equals(m.role());
        String body = decodeText(m.contentJson());
        String referencedStageId = user ? null : firstReferencedStage(body, stageNameIndex);
        return new BrainFeedRow(
                m.id(),
                m.seq(),
                user ? "USER_MESSAGE" : "BRAIN_AGENT_RESPONSE",
                null,
                null,
                m.ts().toString(),
                body,
                referencedStageId,
                user ? decodeImages(m.contentJson()) : List.of());
    }

    /** The id of the first stage whose display name the reply mentions, or
     *  null. Drives the primary drill-in chip. */
    private static String firstReferencedStage(String body, Map<String, String> stageNameIndex)
    {
        if (body == null || body.isBlank()) {
            return null;
        }
        String earliest = null;
        int earliestAt = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : stageNameIndex.entrySet()) {
            int at = body.indexOf(e.getKey());
            if (at >= 0 && at < earliestAt) {
                earliestAt = at;
                earliest = e.getValue();
            }
        }
        return earliest;
    }

    /** Extract the display text from a {@code {"text": "..."}} message blob. */
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

    /** Extract a message's attached-screenshot paths, if any — see
     *  {@code MessageAttachments.encodeMessage}. */
    private List<String> decodeImages(String contentJson)
    {
        if (contentJson == null || contentJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(contentJson).path("images");
            List<String> images = new ArrayList<>();
            node.forEach(n -> images.add(n.asText()));
            return images;
        }
        catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** A brain-feed row paired with its timestamp, for chronological merge. */
    private record FeedEntry(Instant ts, BrainFeedRow row)
    {
    }

    private static BrainFeedRow summaryRow(ThreadTurnEvent event, Map<UUID, StageType> stageTypes)
    {
        StageType stageType = null;
        if (event.stageId() != null) {
            try {
                stageType = stageTypes.get(UUID.fromString(event.stageId()));
            }
            catch (IllegalArgumentException ignore) {
                stageType = null;
            }
        }
        return new BrainFeedRow(
                event.id(),
                null,
                "ITERATION_SUMMARY",
                event.stageId(),
                stageType == null ? null : stageType.name(),
                event.createdAt().toString(),
                event.message() == null ? "" : event.message(),
                null,
                List.of());
    }

    private Optional<BrainFeedRow> brainFeedRow(
            StageEvent e, Map<UUID, StageType> stageTypes, Map<UUID, StageStat> stageStats)
    {
        StageType stageType = stageTypes.get(e.stageId());

        // A closing review panel gets its own feed entry, carrying the panel
        // summary and pointing back at the review stage for drill-in.
        if (e.eventType() == StageEventType.CLOSED && stageType == StageType.REVIEW_STAGE) {
            return Optional.of(new BrainFeedRow(
                    e.id().toString(),
                    null,
                    "PANEL_REVIEW_COMPLETED",
                    e.stageId().toString(),
                    stageType.name(),
                    e.eventAt().toString(),
                    panelReviewBody(e.payloadJson()),
                    e.stageId().toString(),
                    List.of()));
        }

        String type = switch (e.eventType()) {
            case OPENED -> "STAGE_OPENED";
            case CLOSED -> "STAGE_CLOSED";
            case NOTIFY_FIRED -> "NOTIFY_READY_FOR_MERGE";
            case BUDGET_EXHAUSTED -> "NEEDS_ATTENTION";
            // Mutex skips, notify-skips, and budget decisions stay in the
            // stage detail view; they don't surface on the brain feed.
            default -> null;
        };
        if (type == null) {
            return Optional.empty();
        }
        String stageLabel = stageType == null ? "Stage" : humanize(stageType);
        String body = switch (e.eventType()) {
            case OPENED -> stageLabel + " opened";
            // A closed stage carries its rollup: "<label> finished · 3m ·
            // 30k tokens · 2 files".
            case CLOSED -> appendStageStats(stageLabel + " finished", stageStats.get(e.stageId()));
            case NOTIFY_FIRED -> mergeReadyBody(e.payloadJson());
            case BUDGET_EXHAUSTED -> stageLabel + " auto-push budget exhausted";
            default -> "";
        };
        return Optional.of(new BrainFeedRow(
                e.id().toString(),
                null,
                type,
                e.stageId().toString(),
                stageType == null ? null : stageType.name(),
                e.eventAt().toString(),
                body,
                null,
                List.of()));
    }

    /** Human-readable line for a finished panel, read from the CLOSED event's
     *  {@code seatNames}/{@code findingCount}/{@code agreedCount} payload.
     *  Degrades to a bare label when the payload is missing or malformed. */
    /** Body for the "ready to merge" feed row. The lifecycle service records
     *  a human-readable {@code summary} in the NOTIFY_FIRED payload (CI /
     *  comments / approvals checklist, and whether the user can merge or must
     *  nudge reviewers); fall back to the bare label if it's absent. */
    private String mergeReadyBody(String payloadJson)
    {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "Ready to merge";
        }
        try {
            String summary = mapper.readTree(payloadJson).path("summary").asText("");
            return summary.isBlank() ? "Ready to merge" : summary;
        }
        catch (JsonProcessingException ex) {
            return "Ready to merge";
        }
    }

    private String panelReviewBody(String payloadJson)
    {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "Panel review complete";
        }
        try {
            JsonNode node = mapper.readTree(payloadJson);
            List<String> seats = new ArrayList<>();
            JsonNode seatNames = node.get("seatNames");
            if (seatNames != null && seatNames.isArray()) {
                seatNames.forEach(s -> seats.add(s.asText()));
            }
            int findingCount = node.path("findingCount").asInt(0);
            int agreedCount = node.path("agreedCount").asInt(0);
            StringBuilder out = new StringBuilder("Panel review complete");
            if (!seats.isEmpty()) {
                out.append(" — ").append(String.join(", ", seats));
            }
            out.append(" · ").append(agreedCount).append(" of ").append(findingCount)
                    .append(findingCount == 1 ? " finding agreed" : " findings agreed");
            return out.toString();
        }
        catch (JsonProcessingException ex) {
            return "Panel review complete";
        }
    }

    private TaskBrainViewData.RightRail buildRightRail(
            Task task, List<StageInstance> stages, TaskBrainViewData.CostBreakdown costBreakdown)
    {
        boolean mergeable = taskStore.mergeNotificationSentAt(task.id()).isPresent();
        LinkedPrDto linkedPr = task.prNumber() == null ? null : buildLinkedPr(task, mergeable);
        ContextWindowDto context = new ContextWindowDto(0, DEFAULT_CONTEXT_TOKEN_LIMIT, "safe");
        // A panel review is launchable while the task is reviewing its own
        // work (internal-review phases) over an existing PR, called from the
        // current top-level stage. The frontend renders the launch card off
        // these two fields; the spawn endpoint re-validates them server-side.
        StageInstance parentStage = task.prNumber() != null && PANEL_SPAWNABLE_PHASES.contains(task.phase())
                ? topLevelActiveStage(stages).orElse(null)
                : null;
        return new TaskBrainViewData.RightRail(
                buildApproval(stages), linkedPr, context, List.<CommitDto>of(),
                parentStage != null,
                parentStage == null ? null : parentStage.id().toString(),
                costBreakdown,
                buildPlanCard(stages));
    }

    /**
     * The plan card for the task's most-recent PlanStage, or null when none
     * exists. State: {@code locked} when that PlanStage is closed (approved),
     * {@code awaiting} when its latest plan is finalized, {@code draft}
     * otherwise. Built from the {@code PLAN_RECORDED} payloads + the
     * {@code PLAN_FOLLOWUP_NOTED} events on the stage.
     */
    private TaskBrainViewData.PlanCard buildPlanCard(List<StageInstance> stages)
    {
        StageInstance plan = stages.stream()
                .filter(s -> s.type() == StageType.PLAN_STAGE)
                .reduce((first, second) -> second)
                .orElse(null);
        if (plan == null) {
            return null;
        }
        List<StageEvent> events = stageStore.findEventsByStage(plan.id());
        List<JsonNode> recorded = events.stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_RECORDED)
                .map(e -> parseJson(e.payloadJson()))
                .toList();
        JsonNode latest = recorded.isEmpty() ? mapper.createObjectNode()
                : recorded.get(recorded.size() - 1);
        String status = latest.path("status").asText("suggested");

        List<TaskBrainViewData.PlanStep> steps = new ArrayList<>();
        // record_plan's own schema says steps nest under intent.steps, but the
        // brain sometimes writes them at the payload's top level instead
        // (occasionally alongside an "intent_steps_note" pointing there) — fall
        // back rather than render a 0-step, unapprovable "draft" card for an
        // otherwise-complete plan.
        JsonNode stepNodes = latest.path("intent").path("steps");
        if (!stepNodes.isArray() || stepNodes.isEmpty()) {
            stepNodes = latest.path("steps");
        }
        if (stepNodes.isArray()) {
            int index = 0;
            for (JsonNode s : stepNodes) {
                index++;
                // record_plan takes free-form JSON, so the brain emits steps as
                // either {ordinal, action, …} objects or plain strings. Accept
                // both, and fall back to a few common text keys so the step
                // never renders as a blank numbered line when the text is just
                // under a different name.
                String action = s.isTextual()
                        ? s.asText("")
                        : firstNonBlank(
                                s.path("action").asText(""),
                                s.path("step").asText(""),
                                s.path("description").asText(""),
                                s.path("text").asText(""),
                                s.path("summary").asText(""));
                if (action.isBlank()) {
                    continue;
                }
                // The brain often prefixes the action with its own ordinal
                // ("1. edit X"); the rendered <ol> already numbers each step,
                // so strip a leading "N." / "N)" to avoid double numbering.
                action = action.replaceFirst("^\\s*\\d+[.)]\\s+", "");
                List<String> files = textList(s.path("files"));
                String detail = firstNonBlank(s.path("rationale").asText(""), s.path("detail").asText(""));
                String risk = s.path("risk").asText("");
                steps.add(new TaskBrainViewData.PlanStep(
                        s.path("ordinal").asInt(index),
                        action,
                        files,
                        detail.isBlank() ? null : detail,
                        risk.isBlank() ? null : risk));
            }
        }
        JsonNode signals = latest.path("signals");
        String riskLevel = signals.path("riskLevel").asText("low");
        TaskBrainViewData.PlanSignals planSignals = new TaskBrainViewData.PlanSignals(
                riskLevel,
                signals.path("estimatedComplexity").asText("small"),
                signals.path("componentsCount").asInt(0),
                signals.path("expectedGain").asText(""),
                firstNonBlank(signals.path("confidence").asText(""), confidenceFromRisk(riskLevel)));

        // Surface the latest planning failure, but only when it's the most
        // recent planning outcome (a later PLAN_RECORDED clears it).
        Optional<StageEvent> lastFailed = events.stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_FAILED)
                .max(Comparator.comparing(StageEvent::eventAt));
        Optional<StageEvent> lastRecorded = events.stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_RECORDED)
                .max(Comparator.comparing(StageEvent::eventAt));
        String error = null;
        if (lastFailed.isPresent() && (lastRecorded.isEmpty()
                || lastFailed.get().eventAt().isAfter(lastRecorded.get().eventAt()))) {
            error = parseJson(lastFailed.get().payloadJson()).path("error").asText("");
        }

        List<TaskBrainViewData.PlanFollowup> followups = events.stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_FOLLOWUP_NOTED)
                .map(e -> {
                    JsonNode p = parseJson(e.payloadJson());
                    return new TaskBrainViewData.PlanFollowup(
                            e.id().toString(),
                            p.path("note").asText(""),
                            "dev",
                            p.path("createdAt").asText(""),
                            p.path("status").asText("open"));
                })
                .toList();

        String understandingSummary = latest.path("understanding").path("summary").asText("");
        String goal = firstNonBlank(latest.path("goal").asText(""), understandingSummary);
        // A model can call record_plan more than once per turn (an early
        // low-detail stake, then a refined version) — a call already carrying
        // status=finalized but with no goal/steps yet parses successfully and
        // would otherwise render as a fully actionable "awaiting" card (with
        // Approve enabled) for the few seconds until the real, complete call
        // lands. Gate "awaiting" on actual structural completeness too, not
        // status alone, so an in-between stake reads as still drafting.
        boolean structurallyComplete = !goal.isBlank() && !steps.isEmpty();
        String state = plan.state() == StageState.CLOSED ? "locked"
                : "finalized".equals(status) && structurallyComplete ? "awaiting" : "draft";
        return new TaskBrainViewData.PlanCard(
                plan.id().toString(),
                state,
                status,
                latest.path("source").asText(""),
                goal,
                understandingSummary,
                latest.path("intent").path("summary").asText(""),
                steps,
                latest.path("intent").path("validationStrategy").asText(""),
                latest.path("intent").path("pushStrategy").asText("await_approval"),
                planSignals,
                recorded.size(),
                followups,
                textList(latest.path("outOfScope")),
                error);
    }

    /** A JSON array node read as a list of non-blank strings; empty otherwise. */
    private static List<String> textList(JsonNode node)
    {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            String v = n.asText("").strip();
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private JsonNode parseJson(String json)
    {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        }
        catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    /** Whether {@code ts} falls in the half-open window {@code [start, end)}. */
    private static boolean inWindow(Instant ts, Instant start, Instant end)
    {
        return ts != null && !ts.isBefore(start) && ts.isBefore(end);
    }

    private static long nz(Long v)
    {
        return v == null ? 0 : v;
    }

    /** The phases in which a callable panel review can be spawned — kept in
     *  step with {@code ReviewStageServiceImpl}'s server-side guard. */
    private static final Set<TaskPhase> PANEL_SPAWNABLE_PHASES = EnumSet.of(TaskPhase.INTERNAL_REVIEW);

    /** The open/active top-level stage a panel review would be called from —
     *  the callable sub-stage itself never qualifies as a parent. */
    private static Optional<StageInstance> topLevelActiveStage(List<StageInstance> stages)
    {
        return stages.stream()
                .filter(s -> s.callerStageId().isEmpty())
                .filter(s -> s.state() == StageState.OPEN || s.state() == StageState.ACTIVE)
                .reduce((first, second) -> second);
    }

    /** A pending approval card when a ci-fixing stage's budget is exhausted
     *  and waiting on the user. Null otherwise. */
    private ApprovalDto buildApproval(List<StageInstance> stages)
    {
        return latestCiFixing(stages)
                .map(stage -> Map.entry(stage, budgetService.readMetrics(stage.id())))
                .filter(e -> e.getValue().budgetExhausted() && e.getValue().autoPushBudget() != null)
                .map(e -> {
                    StageInstance stage = e.getKey();
                    StageMetrics.AutoPushBudget budget = e.getValue().autoPushBudget();
                    return new ApprovalDto(
                            stage.id().toString(),
                            "CiFixingStage · push",
                            "Auto-push budget exhausted (" + budget.used() + "/" + budget.limit() + ")",
                            "",
                            new ApprovalDto.PrimaryAction(
                                    "Review & approve push",
                                    "/api/stages/" + stage.id() + "/budget/extend"));
                })
                .orElse(null);
    }

    private static LinkedPrDto buildLinkedPr(Task task, boolean mergeable)
    {
        return new LinkedPrDto(
                task.prNumber(),
                nullToEmpty(task.branchName()),
                prStatus(task.prState()),
                ciStatus(task.ciState()),
                "",
                0,
                0,
                "unknown",
                mergeable);
    }

    /** The "major" stage events that earn a scrubber dash. */
    private static final Set<StageEventType> SCRUBBER_MAJOR = EnumSet.of(
            StageEventType.OPENED,
            StageEventType.CLOSED,
            StageEventType.BUDGET_EXHAUSTED,
            StageEventType.NOTIFY_FIRED);

    /** Max chars of a user message shown as a scrubber dash label. */
    private static final int SCRUBBER_LABEL_MAX = 30;

    private TaskBrainViewData.Scrubbers buildScrubbers(
            List<StageEvent> events, List<ThreadMessage> brainMessages)
    {
        List<StageEvent> major = events.stream()
                .filter(e -> SCRUBBER_MAJOR.contains(e.eventType()))
                .toList();
        int lastIdx = major.size() - 1;
        List<ScrubberDash> stageEvents = new ArrayList<>(major.size());
        for (int i = 0; i < major.size(); i++) {
            StageEvent e = major.get(i);
            stageEvents.add(new ScrubberDash(
                    e.id().toString(),
                    e.eventAt().toString(),
                    i == lastIdx));
        }

        // One dash per user message; id = message id so the frontend's
        // click-to-scroll target matches the feed row.
        List<ThreadMessage> userMsgs = brainMessages.stream()
                .filter(m -> "user".equals(m.role()))
                .toList();
        int lastUser = userMsgs.size() - 1;
        List<ScrubberDash> userMessages = new ArrayList<>(userMsgs.size());
        for (int i = 0; i < userMsgs.size(); i++) {
            ThreadMessage m = userMsgs.get(i);
            userMessages.add(new ScrubberDash(
                    m.id(),
                    ellipsis(decodeText(m.contentJson())),
                    i == lastUser));
        }
        return new TaskBrainViewData.Scrubbers(stageEvents, userMessages);
    }

    private static String ellipsis(String text)
    {
        if (text == null) {
            return "";
        }
        return text.length() <= SCRUBBER_LABEL_MAX ? text : text.substring(0, SCRUBBER_LABEL_MAX) + "…";
    }

    /** First non-blank candidate, or "" when all are blank. */
    private static String firstNonBlank(String... candidates)
    {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    /** Fallback confidence for plans recorded before the brain emitted it:
     *  invert the risk level — low risk reads as high confidence, high risk
     *  as low. Unknown risk maps to medium. */
    private static String confidenceFromRisk(String riskLevel)
    {
        return switch (riskLevel == null ? "" : riskLevel.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> "high";
            case "high" -> "low";
            default -> "medium";
        };
    }

    // ── mappers + placeholders ──────────────────────────────────────────

    private static StageDto toDto(StageInstance s)
    {
        return new StageDto(
                s.id().toString(),
                s.taskId(),
                s.type().name(),
                s.state().name(),
                s.openedAt().toString(),
                s.closedAt().map(Instant::toString).orElse(null),
                s.callerStageId().map(UUID::toString).orElse(null),
                humanize(s.type()),
                0);
    }

    private static StageEventDto toEventDto(StageEvent e)
    {
        return new StageEventDto(
                e.id().toString(),
                e.stageId().toString(),
                e.taskId(),
                e.eventType().name(),
                e.eventAt().toString(),
                e.payloadJson());
    }

    private static String title(Task task)
    {
        if (task.name() != null && !task.name().isBlank()) {
            return task.name();
        }
        return nullToEmpty(task.branchName());
    }

    private static String statusLabel(Task task)
    {
        // A terminal task reports its terminal status (so a manually-closed
        // task reads CANCELLED, not its last phase); otherwise a humanised
        // phase name stands in until the richer "CI FIXING · iter #N" lands.
        if (isTerminal(task.status())) {
            return switch (task.status()) {
                case CANCELED -> "CANCELLED";
                case COMPLETED -> "COMPLETED";
                case REMOTE_CLOSED -> "REMOTE CLOSED";
                case ERRORED -> "ERRORED";
                case ARCHIVED -> "ARCHIVED";
                default -> task.status().name();
            };
        }
        return task.phase().name().replace('_', ' ');
    }

    /** Parse {@code owner/repo} out of a {@code owner/repo#n} link ref. */
    private static String repoFullName(String linkedPrRef)
    {
        if (linkedPrRef == null || linkedPrRef.isBlank()) {
            return "";
        }
        int hash = linkedPrRef.indexOf('#');
        return hash < 0 ? linkedPrRef : linkedPrRef.substring(0, hash);
    }

    private static boolean isDraft(String prState)
    {
        return prState != null && prState.toLowerCase(Locale.ROOT).contains("draft");
    }

    private static String prStatus(String prState)
    {
        if (prState == null) {
            return "open";
        }
        String s = prState.toLowerCase(Locale.ROOT);
        if (s.contains("draft")) {
            return "draft";
        }
        if (s.contains("merg")) {
            return "merged";
        }
        if (s.contains("close")) {
            return "closed";
        }
        return "open";
    }

    private static String ciStatus(String ciState)
    {
        if (ciState == null || ciState.isBlank()) {
            return "unknown";
        }
        String s = ciState.toLowerCase(Locale.ROOT);
        if (s.contains("success") || s.contains("green") || s.contains("pass")) {
            return "green";
        }
        if (s.contains("fail") || s.contains("red") || s.contains("error")) {
            return "failing";
        }
        if (s.contains("pend") || s.contains("running") || s.contains("queue")) {
            return "pending";
        }
        return "unknown";
    }

    private static String humanize(StageType type)
    {
        return switch (type) {
            case PLAN_STAGE -> "Planning";
            case DEVELOPMENT_STAGE -> "Development";
            case REMOTE_DEVELOPMENT_STAGE -> "Remote development";
            case CI_FIXING_STAGE -> "CI fixing";
            case REVIEW_MONITOR_STAGE -> "Review monitor";
            case CLEANUP_STAGE -> "Cleanup";
            case REVIEW_STAGE -> "Review panel";
            case REVIEW_ROUND_STAGE -> "Review round";
            case BRANCH_GUARD_STAGE -> "Branch guard";
        };
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    private static ResponseStatusException notFound(String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(404), message);
    }
}
