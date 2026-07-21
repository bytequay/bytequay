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
package com.bytequay.app.scheduler;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.stage.PlanStageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Recovers a stranded DevelopmentStage. Stage-open and turn-enqueue are
 * decoupled: a task can enter {@code IMPLEMENTING} with an open
 * DevelopmentStage while no kickoff turn was ever enqueued (a lost enqueue) or
 * its kickoff turn failed, and nothing else surfaces it — the stage just sits
 * "running" forever.
 *
 * <p>This scan is the safety net. For each {@code IMPLEMENTING} task with an
 * open DevelopmentStage, no {@code QUEUED}/{@code RUNNING} turn, an idle
 * thread, and no activity for the grace window, it acts once and only once:
 * <ul>
 *   <li><b>First hit</b> — re-enqueue the dev kickoff and record a
 *       {@code DEV_KICKOFF_RECOVERED} marker so the recovery can't loop.</li>
 *   <li><b>Still stranded after a recovery</b> — hand it to the human by
 *       parking it at {@code NEEDS_ATTENTION} with a visible reason.</li>
 * </ul>
 * The marker's presence gates the two paths, so the reconciler is idempotent:
 * a healthy stage (a queued/running turn, a busy thread, or recent activity)
 * is never touched.
 */
@Component
public class StrandedDevStageReconciler
{
    private static final Logger log = LoggerFactory.getLogger(StrandedDevStageReconciler.class);

    /** How quiet an open DevelopmentStage must be before it counts as
     *  stranded — long enough that a normal enqueue-then-dispatch gap (or a
     *  turn mid-flight between status writes) never trips it. */
    static final Duration GRACE = Duration.ofMinutes(5);

    private static final int TASK_SCAN_LIMIT = 200;
    private static final int TURN_SCAN_LIMIT = 50;

    private final TaskStore tasks;
    private final StageStore stages;
    private final ThreadTurnStore turns;
    private final ThreadStore threads;
    private final PlanStageService plans;

    public StrandedDevStageReconciler(
            TaskStore tasks,
            StageStore stages,
            ThreadTurnStore turns,
            ThreadStore threads,
            PlanStageService plans)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.stages = requireNonNull(stages, "stages is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.plans = requireNonNull(plans, "plans is null");
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 120_000)
    public void tick()
    {
        Instant now = Instant.now();
        for (Task task : tasks.listByPhases(List.of(TaskPhase.IMPLEMENTING), TASK_SCAN_LIMIT)) {
            try {
                reconcile(task, now);
            }
            catch (RuntimeException e) {
                log.warn("stranded-dev reconcile for task {} failed: {}", task.id(), e.getMessage());
            }
        }
    }

    /** One task's reconcile pass at {@code now}. Package-private so the unit
     *  tests drive it with a fixed clock instead of the scheduler. */
    void reconcile(Task task, Instant now)
    {
        StageInstance dev = stages.findActiveStage(task.id())
                .filter(s -> s.type() == StageType.DEVELOPMENT_STAGE)
                .orElse(null);
        if (dev == null) {
            return;
        }
        // Healthy: a turn is queued or running for this task, so work is (or is
        // about to be) in flight — not stranded.
        if (hasActiveTurn(task)) {
            return;
        }
        // Healthy: the thread's agent is actively executing (a turn's status
        // write can briefly lag the thread's).
        Thread thread = threads.findThreadById(task.threadId()).orElse(null);
        if (thread != null && thread.status() == ThreadStatus.RUNNING) {
            return;
        }
        List<StageEvent> events = stages.findEventsByStage(dev.id());
        // Within the grace window since the last sign of life — give a normal
        // enqueue-then-dispatch (or a just-fired recovery) time to take.
        if (Duration.between(lastActivity(task, dev, events), now).compareTo(GRACE) < 0) {
            return;
        }
        boolean recovered = events.stream()
                .anyMatch(e -> e.eventType() == StageEventType.DEV_KICKOFF_RECOVERED);
        if (!recovered) {
            boolean reenqueued = plans.reenqueueDevKickoff(task.id());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reenqueued", reenqueued);
            payload.put("recoveredAt", now.toString());
            stages.recordEvent(dev.id(), task.id(), StageEventType.DEV_KICKOFF_RECOVERED, payload);
            log.info("recovered stranded DevelopmentStage {} for task {} (re-enqueued={})",
                    dev.id(), task.id(), reenqueued);
            return;
        }
        // Already recovered once and still stranded — hand it to the human.
        plans.surfaceDevFailure(task.id(),
                "Development stalled: the kickoff turn never ran, and an automatic recovery "
                        + "did not take. It needs a manual restart.",
                Actor.SCHEDULER);
        log.info("surfaced still-stranded DevelopmentStage {} for task {}", dev.id(), task.id());
    }

    private boolean hasActiveTurn(Task task)
    {
        return activeTurnExists(task, ThreadTurnStatus.QUEUED)
                || activeTurnExists(task, ThreadTurnStatus.RUNNING);
    }

    private boolean activeTurnExists(Task task, ThreadTurnStatus status)
    {
        return turns.listTurnsByTaskIdAndStatus(task.threadId(), status, TURN_SCAN_LIMIT).stream()
                .anyMatch(turn -> task.id().equals(turn.taskId()));
    }

    /** The most recent sign of life for the stage: its open time, the newest
     *  of this task's turns, and its newest stage event (the recovery marker
     *  lands here, so the grace window also applies after a recovery). */
    private Instant lastActivity(Task task, StageInstance dev, List<StageEvent> events)
    {
        Instant last = dev.openedAt();
        for (ThreadTurn turn : turns.listTurnsByTaskId(task.threadId(), TURN_SCAN_LIMIT)) {
            if (task.id().equals(turn.taskId()) && turn.updatedAt() != null
                    && turn.updatedAt().isAfter(last)) {
                last = turn.updatedAt();
            }
        }
        for (StageEvent event : events) {
            if (event.eventAt() != null && event.eventAt().isAfter(last)) {
                last = event.eventAt();
            }
        }
        return last;
    }
}
