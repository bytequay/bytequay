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

import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Tracks monitor-stage loop iterations and their summaries against the
 * async-turn model. One monitor-enqueued turn is one iteration:
 * {@link #begin} opens the row when a driver enqueues the turn, and the
 * {@link #onTurnFinished} listener closes it when that turn finishes.
 *
 * <p>When an iteration ends without an in-line summary, the service
 * solicits one via a single dedicated follow-up turn; if the agent still
 * doesn't record it, a synthetic placeholder is written so the brain feed
 * never has a gap. The mandatory-summary contract is data-only here — no
 * lock is held, matching the codebase's non-blocking scheduler.
 */
@Component
public class IterationService
{
    /** Max summary length; longer text is truncated when stored. */
    public static final int SUMMARY_MAX_CHARS = 280;

    public static final String TRIGGER_RED_CI = "red_ci";
    public static final String TRIGGER_NEW_COMMENTS = "new_comments";
    /** A user steering message kicked this iteration off, rather than a
     *  monitor poll — see the stage steering endpoint. */
    public static final String TRIGGER_USER_STEERING = "user_steering";

    private static final Logger log = LoggerFactory.getLogger(IterationService.class);

    private final IterationStore iterationStore;
    private final StageStore stageStore;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final ThreadTurnEventStore turnEventStore;
    private final ThreadTurnScheduler scheduler;

    public IterationService(
            IterationStore iterationStore,
            StageStore stageStore,
            TaskStore taskStore,
            ThreadStore threadStore,
            ThreadTurnEventStore turnEventStore,
            ThreadTurnScheduler scheduler)
    {
        this.iterationStore = requireNonNull(iterationStore, "iterationStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.turnEventStore = requireNonNull(turnEventStore, "turnEventStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
    }

    /**
     * Open an iteration for a monitor turn a driver just enqueued. A no-op
     * unless the task's active stage is a monitor stage (so non-monitor
     * turns never spawn iterations). Writes the {@code LOOP_ITERATION_STARTED}
     * stage event.
     */
    @Transactional
    public void begin(String taskId, String turnId, String trigger)
    {
        begin(taskId, turnId, trigger, null);
    }

    /**
     * Variant that enriches a {@code red_ci} iteration's event with the
     * failing check that triggered it — its name, the truncated error
     * summary, and the GitHub Actions run URL — so the stage detail CI-fix
     * history can show per-check detail. {@code ci} is null for non-CI
     * triggers (the bare {@link #begin(String, String, String)} path).
     */
    @Transactional
    public void begin(String taskId, String turnId, String trigger, CiFixContext ci)
    {
        Optional<StageInstance> active = stageStore.findActiveStage(taskId)
                .filter(IterationService::isMonitorStage);
        if (active.isEmpty()) {
            return;
        }
        StageInstance stage = active.get();
        int number = iterationStore.nextIterationNumber(stage.id());
        UUID id = UUID.randomUUID();
        iterationStore.save(TaskStageIteration.opened(
                id, stage.id(), taskId, turnId, number, trigger, Instant.now()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iterationNumber", number);
        payload.put("trigger", trigger);
        payload.put("iterationId", id.toString());
        if (ci != null) {
            payload.put("failedCheck", ci.failedCheck());
            payload.put("errorMessage", ci.errorMessage());
            payload.put("actionsRunUrl", ci.actionsRunUrl());
        }
        stageStore.recordEvent(stage.id(), taskId, StageEventType.LOOP_ITERATION_STARTED, payload);
    }

    /** Per-fix CI detail enriching a {@code red_ci} iteration event. */
    public record CiFixContext(String failedCheck, String errorMessage, String actionsRunUrl) {}

    @EventListener
    @Transactional
    public void onTurnFinished(TaskTurnFinishedEvent event)
    {
        // A monitor turn finishing closes its iteration.
        Optional<TaskStageIteration> monitor = iterationStore.findByTurnId(event.turnId())
                .filter(it -> it.endedAt() == null);
        if (monitor.isPresent()) {
            endIteration(monitor.get(), event.failed());
            return;
        }
        // A summary-request follow-up finishing finalises the summary.
        iterationStore.findBySummaryRequestTurnId(event.turnId())
                .filter(it -> it.summaryText() == null)
                .ifPresent(this::writePlaceholder);
    }

    /**
     * Record a summary for an iteration: dual-writes the iteration row's
     * {@code summary_text} and an {@code is_summary} thread-turn-event row
     * for the brain feed. Shared by the {@code record_iteration_summary}
     * tool and the placeholder path. Text is truncated to
     * {@link #SUMMARY_MAX_CHARS}.
     */
    @Transactional
    public TaskStageIteration recordSummary(UUID iterationId, String text)
    {
        TaskStageIteration iteration = iterationStore.findById(iterationId)
                .orElseThrow(() -> new IllegalArgumentException("no iteration: " + iterationId));
        String trimmed = truncate(text);
        Instant now = Instant.now();
        TaskStageIteration summarised = iteration.withSummary(trimmed, now);
        iterationStore.save(summarised);

        String threadId = taskStore.findTaskById(iteration.taskId())
                .map(Task::threadId)
                .orElse(null);
        if (threadId != null) {
            turnEventStore.appendEvent(ThreadTurnEvent.summary(
                    UUID.randomUUID().toString(),
                    iteration.turnId(),
                    threadId,
                    iteration.taskId(),
                    iteration.stageId().toString(),
                    now,
                    trimmed));
        }
        return summarised;
    }

    /**
     * The recorded iteration summaries of the task's most recent
     * CI-fixing stage, oldest-first — the cross-stage context the
     * Comments-addressing stage seeds its first prompt with so the agent
     * knows what the CI-fix loop just did. Empty when the task never ran a
     * CI-fixing stage or that stage recorded no summaries.
     */
    public List<String> latestCiFixingSummaries(String taskId)
    {
        StageInstance latest = latestCiFixingStage(taskId);
        if (latest == null) {
            return List.of();
        }
        List<String> summaries = new ArrayList<>();
        for (TaskStageIteration it : iterationStore.findByStage(latest.id())) {
            if (it.summaryText() != null && !it.summaryText().isBlank()) {
                summaries.add(it.summaryText().strip());
            }
        }
        return summaries;
    }

    /** A recorded CI-fixing iteration summary with the time it was
     *  recorded — drives the {@code get_new_updated_ci_fixing_log} tool's
     *  newer-than-marker filter. */
    public record CiFixingSummaryEntry(int iterationNumber, String text, Instant summarizedAt) {}

    /**
     * The recorded iteration summaries of the task's most recent CI-fixing
     * stage, oldest-first, each with its recorded-at timestamp. The
     * {@code get_new_updated_ci_fixing_log} tool filters these against its
     * per-task last-query marker. Empty when no CI-fixing stage ran or it
     * recorded no summaries.
     */
    public List<CiFixingSummaryEntry> latestCiFixingSummaryEntries(String taskId)
    {
        StageInstance latest = latestCiFixingStage(taskId);
        if (latest == null) {
            return List.of();
        }
        List<CiFixingSummaryEntry> out = new ArrayList<>();
        for (TaskStageIteration it : iterationStore.findByStage(latest.id())) {
            if (it.summaryText() != null && !it.summaryText().isBlank() && it.summarizedAt() != null) {
                out.add(new CiFixingSummaryEntry(
                        it.iterationNumber(), it.summaryText().strip(), it.summarizedAt()));
            }
        }
        return out;
    }

    private StageInstance latestCiFixingStage(String taskId)
    {
        StageInstance latest = null;
        for (StageInstance stage : stageStore.findStagesByTask(taskId)) {
            if (stage.type() != StageType.CI_FIXING_STAGE) {
                continue;
            }
            if (latest == null || stage.openedAt().isAfter(latest.openedAt())) {
                latest = stage;
            }
        }
        return latest;
    }

    private void endIteration(TaskStageIteration iteration, boolean failed)
    {
        TaskStageIteration ended = iteration.withEnded(Instant.now(), endedReason(iteration.taskId(), failed));
        iterationStore.save(ended);
        if (ended.summaryText() != null) {
            // The agent recorded a summary in-line during the iteration turn.
            return;
        }
        solicitSummary(ended);
    }

    private String endedReason(String taskId, boolean failed)
    {
        if (failed) {
            return "failed";
        }
        TaskPhase phase = taskStore.findTaskById(taskId).map(Task::phase).orElse(null);
        return phase == TaskPhase.NEEDS_ATTENTION ? "needs_attention" : "push_completed";
    }

    /** Enqueue a single follow-up turn asking the agent to record the
     *  summary. If we can't (no thread / enqueue failure), write the
     *  placeholder immediately so the iteration never hangs unsummarised. */
    private void solicitSummary(TaskStageIteration iteration)
    {
        Optional<Thread> thread = taskStore.findTaskById(iteration.taskId())
                .flatMap(task -> threadStore.findThreadById(task.threadId()));
        if (thread.isEmpty()) {
            writePlaceholder(iteration);
            return;
        }
        String prompt = "The monitor iteration just completed. Call "
                + "record_iteration_summary(iteration_id='" + iteration.id() + "', text='…') "
                + "with a one-line description (max " + SUMMARY_MAX_CHARS + " chars) of what you "
                + "did this iteration. Do not do any other work in this turn.";
        try {
            // Bind the task id AND the iteration's own stage so the summary
            // request runs on that stage's agent and its messages land in
            // stage_messages, not the thread slice. (The stage is PAUSED while
            // it waits, which findActiveStage misses — the iteration knows its
            // stage id directly, so pin it.)
            String turnId = scheduler.enqueueTaskTurn(
                    thread.get(), prompt, iteration.taskId(), iteration.stageId().toString(),
                    TurnInitiator.unattended("iteration-summary-request"));
            iterationStore.save(iteration.withSummaryRequestTurnId(turnId));
        }
        catch (RuntimeException e) {
            log.warn("iteration {} summary-request enqueue failed: {}", iteration.id(), e.getMessage());
            writePlaceholder(iteration);
        }
    }

    private void writePlaceholder(TaskStageIteration iteration)
    {
        String reason = iteration.endedReason() == null ? "" : ", ended " + iteration.endedReason();
        String text = "[no summary recorded] iteration #" + iteration.iterationNumber()
                + " triggered by " + iteration.trigger() + reason;
        recordSummary(iteration.id(), text);
    }

    private static boolean isMonitorStage(StageInstance stage)
    {
        return stage.type() == StageType.CI_FIXING_STAGE
                || stage.type() == StageType.REVIEW_MONITOR_STAGE;
    }

    private static String truncate(String text)
    {
        if (text == null) {
            return "";
        }
        return text.length() <= SUMMARY_MAX_CHARS ? text : text.substring(0, SUMMARY_MAX_CHARS);
    }
}
