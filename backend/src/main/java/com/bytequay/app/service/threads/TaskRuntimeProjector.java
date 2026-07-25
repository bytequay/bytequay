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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Objects.requireNonNull;

/**
 * The task runtime projection: {@code PENDING / RUNNING / IDLE /
 * ERRORED} derive from the task's own liveness-affecting turns — never
 * from the shared thread. Authority is the one turn
 * {@code tasks.current_liveness_turn_id} points at; a delayed event or
 * sweep for a superseded turn is a no-op because the projection reloads
 * under the task command and only trusts the pointer.
 *
 * <p>The projection may only move between those four statuses, may only
 * move <em>into</em> ERRORED (its exits belong to the explicit retry
 * intent), and never touches a gate, stop, archive, or terminal status.
 * A FAILED/CANCELLED pointer turn owned by a live coordinator (brain
 * fix, CI fix, review round, local addressing) leaves the task IDLE —
 * that owner retries or parks; only an uncoordinated failure projects
 * ERRORED and copies the turn's {@code endedAt}/{@code errorMessage}.
 */
@Component
public class TaskRuntimeProjector
{
    private static final Logger log = LoggerFactory.getLogger(TaskRuntimeProjector.class);

    /** Statuses this projection is allowed to read-modify. */
    private static final Set<TaskStatus> PROJECTABLE =
            EnumSet.of(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.IDLE, TaskStatus.ERRORED);

    /** Initiator sources whose failed turns are retried/parked by their
     *  own durable coordinator — the generic projection must not expose
     *  ERRORED between those steps. */
    private static final Set<String> COORDINATOR_SOURCES = Set.of(
            "brain-review-fix", "local-ci-fix", "ci-fix-shipped", "auto-fix-ci-fail",
            "review-round", "branch-guard-fix", "cherry-pick-conflict",
            "address-local-comments");

    private static final int SWEEP_PAGE = 500;
    private static final int LIVENESS_PAGE = 200;

    private final TaskStore taskStore;
    private final ThreadTurnStore turnStore;
    private final TaskCommandExecutor commands;
    private final AgentScheduler scheduler;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "task-runtime-projector");
        thread.setDaemon(true);
        return thread;
    });

    public TaskRuntimeProjector(
            TaskStore taskStore,
            ThreadTurnStore turnStore,
            TaskCommandExecutor commands,
            AgentScheduler scheduler)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.clock = Clock.systemUTC();
    }

    @EventListener
    public void onTurnStatusChanged(TaskTurnStatusChanged event)
    {
        executor.execute(() -> projectSafely(event.taskId()));
    }

    /** Durable-turn sweep: the delivery guarantee when a wake signal was
     *  lost. Scans every projectable task, not just thread-active ones. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void sweep()
    {
        for (Task task : taskStore.listByStatuses(PROJECTABLE, SWEEP_PAGE)) {
            projectSafely(task.id());
        }
    }

    private void projectSafely(String taskId)
    {
        try {
            project(taskId);
        }
        catch (RuntimeException e) {
            log.warn("task runtime projection for {} failed: {}", taskId, e.getMessage());
        }
    }

    /** Reload-and-project one task under its command. */
    public void project(String taskId)
    {
        boolean promoted = commands.execute(taskId, () -> projectInCommand(taskId));
        if (promoted) {
            // A queued follower just took the pointer — its lane row was
            // deferred behind the finished turn, so poke the drain.
            scheduler.kickDrain();
        }
    }

    private boolean projectInCommand(String taskId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null || !PROJECTABLE.contains(task.status())) {
            return false;
        }
        Optional<String> pointer = taskStore.currentLivenessTurnId(taskId);
        if (pointer.isEmpty()) {
            return false;
        }
        ThreadTurn turn = turnStore.findTurnById(pointer.get()).orElse(null);
        if (turn == null) {
            log.warn("task {} liveness pointer {} has no turn row", taskId, pointer.get());
            return false;
        }
        boolean promoted = false;
        TaskStatus target;
        switch (turn.status()) {
            case RUNNING -> target = TaskStatus.RUNNING;
            case QUEUED -> target = task.status() == TaskStatus.PENDING
                    ? TaskStatus.PENDING
                    : TaskStatus.IDLE;
            case COMPLETED -> {
                promoted = promoteNextQueued(taskId, turn.id());
                target = TaskStatus.IDLE;
            }
            case FAILED, CANCELLED -> {
                if (coordinatorOwned(turn)) {
                    target = TaskStatus.IDLE;
                }
                else {
                    target = TaskStatus.ERRORED;
                }
            }
            default -> target = task.status();
        }
        applyTarget(task, turn, target);
        return promoted;
    }

    private void applyTarget(Task task, ThreadTurn turn, TaskStatus target)
    {
        TaskStatus current = task.status();
        if (current == target) {
            return;
        }
        // ERRORED's exits belong to the explicit retry intent, never the
        // generic projection.
        if (current == TaskStatus.ERRORED) {
            return;
        }
        if (!taskStore.updateStatusIf(task.id(), current, target)) {
            return;
        }
        if (target == TaskStatus.ERRORED) {
            taskStore.updateRuntimeFailure(task.id(), turn.finishedAt(), turn.errorMessage());
        }
        taskStore.appendStatusEvent(
                task.id(), current, target, Actor.SCHEDULER,
                "runtime_projection:" + turn.status().name().toLowerCase(Locale.ROOT),
                clock.instant());
    }

    /** After a successful current turn, the oldest queued liveness turn
     *  becomes authoritative. */
    private boolean promoteNextQueued(String taskId, String completedTurnId)
    {
        List<ThreadTurn> livenessTurns = turnStore.listLivenessTurns(taskId, LIVENESS_PAGE);
        return livenessTurns.stream()
                .filter(candidate -> candidate.status() == ThreadTurnStatus.QUEUED)
                .findFirst()
                .map(next -> taskStore.setCurrentLivenessTurnIdIf(taskId, completedTurnId, next.id()))
                .orElse(false);
    }

    private static boolean coordinatorOwned(ThreadTurn turn)
    {
        return turn.initiator() != null
                && COORDINATOR_SOURCES.contains(turn.initiator().source());
    }
}
