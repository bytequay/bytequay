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
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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

    public StageServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            StageBudgetService budgetService,
            ThreadTurnEventStore turnEventStore)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.budgetService = requireNonNull(budgetService, "budgetService is null");
        this.turnEventStore = requireNonNull(turnEventStore, "turnEventStore is null");
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
                .collect(Collectors.toMap(StageInstance::id, StageInstance::type));

        return new TaskBrainViewData(
                buildTask(task),
                buildAggregate(allStages),
                topLevel,
                subStages,
                buildBrainFeed(allEvents, stageTypes, turnEventStore.listSummaryEventsByTask(taskId)),
                buildRightRail(task, allStages),
                buildScrubbers(allEvents));
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
                "");
    }

    private TaskBrainViewData.Aggregate buildAggregate(List<StageInstance> stages)
    {
        long activeTimeSec = stages.stream()
                .filter(s -> s.closedAt().isPresent())
                .mapToLong(s -> Math.max(0,
                        (s.closedAt().get().toEpochMilli() - s.openedAt().toEpochMilli()) / 1000))
                .sum();
        // Autonomous pushes = budget spent across the task's ci-fixing stages.
        int pushes = stages.stream()
                .filter(s -> s.type() == StageType.CI_FIXING_STAGE)
                .map(s -> budgetService.readMetrics(s.id()).autoPushBudget())
                .filter(b -> b != null)
                .mapToInt(StageMetrics.AutoPushBudget::used)
                .sum();
        int panels = (int) stages.stream()
                .filter(s -> s.type() == StageType.REVIEW_STAGE)
                .count();
        TaskBrainViewData.AutoPushBudget budget = latestCiFixingBudget(stages)
                .map(b -> new TaskBrainViewData.AutoPushBudget(b.used(), b.limit()))
                .orElse(null);
        // Tool calls / turns / messages / waiting-time / cost depend on
        // machinery that lands later; left at zero.
        return new TaskBrainViewData.Aggregate(pushes, activeTimeSec, 0, 0, 0, 0, panels, 0, budget);
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

    private static List<BrainFeedRow> buildBrainFeed(
            List<StageEvent> events,
            Map<UUID, StageType> stageTypes,
            List<ThreadTurnEvent> summaries)
    {
        List<FeedEntry> entries = new ArrayList<>();
        for (StageEvent e : events) {
            brainFeedRow(e, stageTypes)
                    .ifPresent(row -> entries.add(new FeedEntry(e.eventAt(), row)));
        }
        for (ThreadTurnEvent s : summaries) {
            entries.add(new FeedEntry(s.createdAt(), summaryRow(s, stageTypes)));
        }
        entries.sort(Comparator.comparing(FeedEntry::ts));
        return entries.stream().map(FeedEntry::row).toList();
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
                "ITERATION_SUMMARY",
                event.stageId(),
                stageType == null ? null : stageType.name(),
                event.createdAt().toString(),
                event.message() == null ? "" : event.message(),
                null);
    }

    private static Optional<BrainFeedRow> brainFeedRow(StageEvent e, Map<UUID, StageType> stageTypes)
    {
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
        StageType stageType = stageTypes.get(e.stageId());
        String stageLabel = stageType == null ? "Stage" : humanize(stageType);
        String body = switch (e.eventType()) {
            case OPENED -> stageLabel + " opened";
            case CLOSED -> stageLabel + " closed";
            case NOTIFY_FIRED -> "Ready to merge";
            case BUDGET_EXHAUSTED -> stageLabel + " auto-push budget exhausted";
            default -> "";
        };
        return Optional.of(new BrainFeedRow(
                e.id().toString(),
                type,
                e.stageId().toString(),
                stageType == null ? null : stageType.name(),
                e.eventAt().toString(),
                body,
                null));
    }

    private TaskBrainViewData.RightRail buildRightRail(Task task, List<StageInstance> stages)
    {
        boolean mergeable = taskStore.mergeNotificationSentAt(task.id()).isPresent();
        LinkedPrDto linkedPr = task.prNumber() == null ? null : buildLinkedPr(task, mergeable);
        ContextWindowDto context = new ContextWindowDto(0, DEFAULT_CONTEXT_TOKEN_LIMIT, "safe");
        return new TaskBrainViewData.RightRail(
                buildApproval(stages), linkedPr, context, List.<CommitDto>of());
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

    private static TaskBrainViewData.Scrubbers buildScrubbers(List<StageEvent> events)
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
        return new TaskBrainViewData.Scrubbers(stageEvents, List.<ScrubberDash>of());
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
        // Server-computed label; a humanised phase name is the placeholder
        // until the richer "CI FIXING · iter #N" form lands.
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
            case DEVELOPMENT_STAGE -> "Development";
            case CI_FIXING_STAGE -> "CI fixing";
            case REVIEW_MONITOR_STAGE -> "Review monitor";
            case CLEANUP_STAGE -> "Cleanup";
            case REVIEW_STAGE -> "Review panel";
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
