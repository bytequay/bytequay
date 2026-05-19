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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskResourceLane;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.TaskTurn;
import com.bytequay.app.domain.TaskTurnEvent;
import com.bytequay.app.domain.TaskTurnEventType;
import com.bytequay.app.domain.TaskTurnStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.TaskTurnEventStore;
import com.bytequay.app.repository.TaskTurnStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static com.bytequay.app.domain.TaskResourceLane.API;
import static com.bytequay.app.domain.TaskResourceLane.CLI;
import static com.bytequay.app.domain.TaskTurnEventType.TURN_CANCELLED;
import static com.bytequay.app.domain.TaskTurnEventType.TURN_FINISHED;
import static com.bytequay.app.domain.TaskTurnEventType.TURN_QUEUED;
import static com.bytequay.app.domain.TaskTurnEventType.TURN_STARTED;
import static com.bytequay.app.domain.TaskTurnStatus.CANCELLED;
import static com.bytequay.app.domain.TaskTurnStatus.COMPLETED;
import static com.bytequay.app.domain.TaskTurnStatus.FAILED;
import static com.bytequay.app.domain.TaskTurnStatus.QUEUED;
import static com.bytequay.app.domain.TaskTurnStatus.RUNNING;
import static java.util.Objects.requireNonNull;

/**
 * Resource gate for task turns.
 *
 * <p>The scheduler limits active CLI subprocess turns separately from
 * API-backed loops. Extra turns stay queued, including follow-up turns
 * for a task that already has a turn in flight.
 */
@Component
public class AgentScheduler
        implements TaskTurnScheduler
{
    private static final int RECOVERY_LIMIT = 1_000;
    // Usually one or two follow-up turns. Keep the page large enough
    // for normal use, but bounded so a pathological task cannot load
    // every durable queued turn in one SQLite read.
    private static final int TURN_CANCELLATION_PAGE_SIZE = 1_000;

    private final TaskStore tasks;
    private final TaskTurnStore turns;
    private final TaskTurnEventStore events;
    private final TaskSessionRegistry sessions;
    private final EnumMap<TaskResourceLane, LaneState> lanes = new EnumMap<>(TaskResourceLane.class);
    private final Set<String> runningTaskIds = new HashSet<>();
    private final Object lock = new Object();

    public AgentScheduler(
            TaskStore tasks,
            TaskTurnStore turns,
            TaskTurnEventStore events,
            TaskSessionRegistry sessions,
            @Value("${bytequay.tasks.scheduler.max-cli-running:4}") int maxCliRunning,
            @Value("${bytequay.tasks.scheduler.max-api-running:4}") int maxApiRunning)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.events = requireNonNull(events, "events is null");
        this.sessions = requireNonNull(sessions, "sessions is null");
        lanes.put(CLI, new LaneState(checkedLimit(maxCliRunning, "maxCliRunning")));
        lanes.put(API, new LaneState(checkedLimit(maxApiRunning, "maxApiRunning")));
    }

    /**
     * Queue a user turn and start it immediately when the lane has
     * capacity.
     */
    @Override
    public String enqueueTurn(Task task, String input)
    {
        requireNonNull(task, "task is null");
        requireNonNull(input, "input is null");
        if (input.isBlank()) {
            throw new IllegalArgumentException("input is blank");
        }
        Instant now = Instant.now();
        TaskTurn turn = new TaskTurn(
                UUID.randomUUID().toString(),
                task.id(),
                laneFor(task),
                QUEUED,
                input,
                now,
                now,
                /* startedAt */ null,
                /* finishedAt */ null,
                /* errorMessage */ null);
        turns.saveTurn(turn);
        appendEvent(turn, TURN_QUEUED, null);
        enqueuePersistedTurn(turn);
        return turn.id();
    }

    /**
     * Remove queued turns for a task from both the durable queue and
     * the in-memory lane queues.
     */
    @Override
    public int cancelQueuedTurns(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        int cancelled = 0;
        synchronized (lock) {
            List<TaskTurn> queuedTurns;
            do {
                queuedTurns = turns.listTurnsByTaskIdAndStatus(taskId, QUEUED, TURN_CANCELLATION_PAGE_SIZE);
                if (queuedTurns.isEmpty()) {
                    break;
                }

                Set<String> queuedTurnIds = new HashSet<>();
                for (TaskTurn turn : queuedTurns) {
                    queuedTurnIds.add(turn.id());
                }
                for (LaneState lane : lanes.values()) {
                    removeQueuedTurns(lane, queuedTurnIds);
                }

                Instant now = Instant.now();
                for (TaskTurn turn : queuedTurns) {
                    turns.saveTurn(updateTurn(
                            turn,
                            CANCELLED,
                            turn.startedAt(),
                            now,
                            "cancelled by task lifecycle action"));
                    appendEvent(turn, TURN_CANCELLED, "cancelled by task lifecycle action");
                }
                cancelled += queuedTurns.size();
            }
            // Each read returns at most TURN_CANCELLATION_PAGE_SIZE rows.
            // A full page means there may be more queued rows after this
            // page was marked CANCELLED, so fetch the next page.
            while (queuedTurns.size() == TURN_CANCELLATION_PAGE_SIZE);

            drainLocked();
        }
        return cancelled;
    }

    /**
     * Replays durable queued turns after backend startup. Orphaned
     * RUNNING turns are downgraded to QUEUED because their local
     * process/coroutine died with the previous backend.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverQueuedTurns()
    {
        for (TaskTurn turn : turns.listTurnsByStatus(RUNNING, RECOVERY_LIMIT)) {
            TaskTurn queued = updateTurn(
                    turn,
                    QUEUED,
                    /* startedAt */ null,
                    /* finishedAt */ null,
                    "interrupted by app restart");
            turns.saveTurn(queued);
            appendEvent(queued, TURN_QUEUED, "interrupted by app restart");
            enqueuePersistedTurn(queued);
        }
        for (TaskTurn turn : turns.listTurnsByStatus(QUEUED, RECOVERY_LIMIT)) {
            enqueuePersistedTurn(turn);
        }
    }

    private void enqueuePersistedTurn(TaskTurn turn)
    {
        requireNonNull(turn, "turn is null");
        synchronized (lock) {
            LaneState lane = lane(turn.lane());
            if (lane.knownTurnIds.add(turn.id())) {
                lane.queue.addLast(turn);
            }
            drainLocked();
        }
    }

    private void drainLocked()
    {
        boolean madeProgress;
        do {
            madeProgress = false;
            for (LaneState lane : lanes.values()) {
                while (lane.running < lane.maxRunning) {
                    Optional<TaskTurn> maybeTurn = pollNextEligible(lane);
                    if (maybeTurn.isEmpty()) {
                        break;
                    }
                    TaskTurn turn = maybeTurn.get();
                    lane.running++;
                    runningTaskIds.add(turn.taskId());
                    dispatch(turn);
                    madeProgress = true;
                }
            }
        }
        while (madeProgress);
    }

    private Optional<TaskTurn> pollNextEligible(LaneState lane)
    {
        Iterator<TaskTurn> iterator = lane.queue.iterator();
        while (iterator.hasNext()) {
            TaskTurn turn = iterator.next();
            if (runningTaskIds.contains(turn.taskId())) {
                continue;
            }
            iterator.remove();
            lane.knownTurnIds.remove(turn.id());
            return Optional.of(turn);
        }
        return Optional.empty();
    }

    private static void removeQueuedTurns(LaneState lane, Set<String> turnIds)
    {
        Iterator<TaskTurn> iterator = lane.queue.iterator();
        while (iterator.hasNext()) {
            TaskTurn turn = iterator.next();
            if (turnIds.contains(turn.id())) {
                iterator.remove();
                lane.knownTurnIds.remove(turn.id());
            }
        }
    }

    private void dispatch(TaskTurn queuedTurn)
    {
        TaskTurn runningTurn = updateTurn(
                queuedTurn,
                RUNNING,
                Instant.now(),
                /* finishedAt */ null,
                /* errorMessage */ null);
        turns.saveTurn(runningTurn);
        appendEvent(runningTurn, TURN_STARTED, null);

        Task task = tasks.findTaskById(runningTurn.taskId()).orElse(null);
        if (task == null) {
            completeTurn(runningTurn, null, new NoSuchElementException("no task: " + runningTurn.taskId()));
            return;
        }

        AgentSession session;
        try {
            session = sessions.getOrCreate(task);
        }
        catch (RuntimeException e) {
            completeTurn(runningTurn, null, e);
            return;
        }

        CompletionStage<Void> completion;
        try {
            completion = requireNonNull(
                    session.send(runningTurn.input()),
                    "session send returned null");
        }
        catch (RuntimeException e) {
            completeTurn(runningTurn, session, e);
            return;
        }
        completion.whenComplete((ignored, failure) -> completeTurn(runningTurn, session, failure));
    }

    private void completeTurn(TaskTurn runningTurn, AgentSession session, Throwable failure)
    {
        Throwable unwrapped = unwrap(failure);
        boolean failed = unwrapped != null
                || (session != null && session.status() == TaskStatus.ERRORED);
        Instant now = Instant.now();
        TaskTurn finished = updateTurn(
                runningTurn,
                failed ? FAILED : COMPLETED,
                runningTurn.startedAt(),
                now,
                unwrapped == null ? null : unwrapped.getMessage());
        turns.saveTurn(finished);
        appendEvent(finished, TURN_FINISHED, finished.errorMessage());

        synchronized (lock) {
            LaneState lane = lane(runningTurn.lane());
            lane.running = Math.max(0, lane.running - 1);
            runningTaskIds.remove(runningTurn.taskId());
            drainLocked();
        }
    }

    private LaneState lane(TaskResourceLane lane)
    {
        LaneState state = lanes.get(lane);
        if (state == null) {
            throw new IllegalArgumentException("unknown task resource lane: " + lane);
        }
        return state;
    }

    static TaskResourceLane laneFor(Task task)
    {
        return switch (task.kind()) {
            case CLI_AGENT -> CLI;
            case LOGIC_LOOP -> API;
        };
    }

    private void appendEvent(TaskTurn turn, TaskTurnEventType event, String message)
    {
        events.appendEvent(new TaskTurnEvent(
                UUID.randomUUID().toString(),
                turn.id(),
                turn.taskId(),
                event,
                Instant.now(),
                message));
    }

    private static TaskTurn updateTurn(
            TaskTurn turn,
            TaskTurnStatus status,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage)
    {
        Instant now = Instant.now();
        return new TaskTurn(
                turn.id(),
                turn.taskId(),
                turn.lane(),
                status,
                turn.input(),
                turn.createdAt(),
                now,
                startedAt,
                finishedAt,
                errorMessage);
    }

    private static Throwable unwrap(Throwable failure)
    {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static int checkedLimit(int value, String name)
    {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static final class LaneState
    {
        private final int maxRunning;
        private final ArrayDeque<TaskTurn> queue = new ArrayDeque<>();
        private final Set<String> knownTurnIds = new HashSet<>();
        private int running;

        private LaneState(int maxRunning)
        {
            this.maxRunning = maxRunning;
        }
    }
}
