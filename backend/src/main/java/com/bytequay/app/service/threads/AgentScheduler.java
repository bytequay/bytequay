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

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnEventType;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
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
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static com.bytequay.app.domain.ThreadResourceLane.API;
import static com.bytequay.app.domain.ThreadResourceLane.CLI;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_CANCELLED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_FAILED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_FINISHED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_QUEUED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_STARTED;
import static com.bytequay.app.domain.ThreadTurnEventType.WAITING_FOR_CAPACITY;
import static com.bytequay.app.domain.ThreadTurnStatus.CANCELLED;
import static com.bytequay.app.domain.ThreadTurnStatus.COMPLETED;
import static com.bytequay.app.domain.ThreadTurnStatus.FAILED;
import static com.bytequay.app.domain.ThreadTurnStatus.QUEUED;
import static com.bytequay.app.domain.ThreadTurnStatus.RUNNING;
import static java.util.Objects.requireNonNull;

/**
 * Resource gate for thread turns.
 *
 * <p>The scheduler limits active CLI subprocess turns separately from
 * API-backed loops. Extra turns stay queued, including follow-up turns
 * for a thread that already has a turn in flight.
 */
@Component
public class AgentScheduler
        implements ThreadTurnScheduler
{
    private static final int RECOVERY_PAGE_SIZE = 1_000;
    // Usually one or two follow-up turns. Keep the page large enough
    // for normal use, but bounded so a pathological thread cannot load
    // every durable queued turn in one SQLite read.
    private static final int TURN_CANCELLATION_PAGE_SIZE = 1_000;

    private final ThreadStore threads;
    private final ThreadTurnStore turns;
    private final ThreadTurnEventStore events;
    private final ThreadRegistry sessions;
    private final EnumMap<ThreadResourceLane, LaneState> lanes = new EnumMap<>(ThreadResourceLane.class);
    private final Set<String> runningTaskIds = new HashSet<>();
    private final Object lock = new Object();

    public AgentScheduler(
            ThreadStore threads,
            ThreadTurnStore turns,
            ThreadTurnEventStore events,
            ThreadRegistry sessions,
            @Value("${bytequay.threads.scheduler.max-cli-running:4}") int maxCliRunning,
            @Value("${bytequay.threads.scheduler.max-api-running:6}") int maxApiRunning)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.events = requireNonNull(events, "events is null");
        this.sessions = requireNonNull(sessions, "sessions is null");
        lanes.put(CLI, new LaneState(checkedLimit(maxCliRunning, "maxCliRunning")));
        lanes.put(API, new LaneState(checkedLimit(maxApiRunning, "maxApiRunning")));
    }

    /**
     * Queue a user turn and start it immediately when the lane has
     * capacity. Routes to the foreground Task when one exists; sends a
     * trunk planning turn otherwise.
     */
    @Override
    public String enqueueTurn(Thread thread, String input)
    {
        return enqueueTurnInternal(thread, input,
                thread.activeTask() == null ? null : thread.activeTask().id());
    }

    /**
     * Queue a trunk-scope turn — forces {@code task_id = null} on the
     * row even when the thread has a foreground Task. The trunk window's
     * composer calls this so cross-task planning never pollutes a task's
     * conversation slice.
     */
    @Override
    public String enqueueTrunkTurn(Thread thread, String input)
    {
        return enqueueTurnInternal(thread, input, /* taskId */ null);
    }

    private String enqueueTurnInternal(Thread thread, String input, String taskId)
    {
        requireNonNull(thread, "thread is null");
        requireNonNull(input, "input is null");
        if (input.isBlank()) {
            throw new IllegalArgumentException("input is blank");
        }
        Instant now = Instant.now();
        ThreadTurn turn = new ThreadTurn(
                UUID.randomUUID().toString(),
                thread.id(),
                taskId,
                laneFor(thread),
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
     * Remove queued turns for a thread from both the durable queue and
     * the in-memory lane queues.
     */
    @Override
    public int cancelQueuedTurns(String threadId)
    {
        requireNonNull(threadId, "threadId is null");
        int cancelled = 0;
        synchronized (lock) {
            List<ThreadTurn> queuedTurns;
            do {
                queuedTurns = turns.listTurnsByTaskIdAndStatus(threadId, QUEUED, TURN_CANCELLATION_PAGE_SIZE);
                if (queuedTurns.isEmpty()) {
                    break;
                }

                Set<String> queuedTurnIds = new HashSet<>();
                for (ThreadTurn turn : queuedTurns) {
                    queuedTurnIds.add(turn.id());
                }
                for (LaneState lane : lanes.values()) {
                    removeQueuedTurns(lane, queuedTurnIds);
                }

                Instant now = Instant.now();
                for (ThreadTurn turn : queuedTurns) {
                    turns.saveTurn(updateTurn(
                            turn,
                            CANCELLED,
                            turn.startedAt(),
                            now,
                            "cancelled by thread lifecycle action"));
                    appendEvent(turn, TURN_CANCELLED, "cancelled by thread lifecycle action");
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
        recoverInterruptedRunningTurns();
        recoverQueuedTurnsFromStore();
    }

    private void recoverInterruptedRunningTurns()
    {
        List<ThreadTurn> runningTurns = turns.listTurnsByStatus(RUNNING, RECOVERY_PAGE_SIZE);
        while (!runningTurns.isEmpty()) {
            for (ThreadTurn turn : runningTurns) {
                ThreadTurn queued = updateTurn(
                        turn,
                        QUEUED,
                        /* startedAt */ null,
                        /* finishedAt */ null,
                        "interrupted by app restart");
                turns.saveTurn(queued);
                appendEvent(queued, TURN_QUEUED, "interrupted by app restart");
                enqueuePersistedTurn(queued);
            }
            ThreadTurn cursor = runningTurns.get(runningTurns.size() - 1);
            if (runningTurns.size() < RECOVERY_PAGE_SIZE) {
                return;
            }
            runningTurns = turns.listTurnsByStatusAfter(
                    RUNNING,
                    cursor.createdAt(),
                    cursor.id(),
                    RECOVERY_PAGE_SIZE);
        }
    }

    private void recoverQueuedTurnsFromStore()
    {
        List<ThreadTurn> queuedTurns = turns.listTurnsByStatus(QUEUED, RECOVERY_PAGE_SIZE);
        while (!queuedTurns.isEmpty()) {
            for (ThreadTurn turn : queuedTurns) {
                enqueuePersistedTurn(turn);
            }
            ThreadTurn cursor = queuedTurns.get(queuedTurns.size() - 1);
            if (queuedTurns.size() < RECOVERY_PAGE_SIZE) {
                return;
            }
            queuedTurns = turns.listTurnsByStatusAfter(
                    QUEUED,
                    cursor.createdAt(),
                    cursor.id(),
                    RECOVERY_PAGE_SIZE);
        }
    }

    private void enqueuePersistedTurn(ThreadTurn turn)
    {
        requireNonNull(turn, "turn is null");
        synchronized (lock) {
            LaneState lane = lane(turn.lane());
            boolean enqueued = lane.knownTurnIds.add(turn.id());
            if (enqueued) {
                lane.queue.addLast(turn);
            }
            drainLocked();
            if (enqueued && lane.knownTurnIds.contains(turn.id())) {
                appendEvent(turn, WAITING_FOR_CAPACITY, waitingReason(turn, lane));
            }
        }
    }

    private void drainLocked()
    {
        boolean madeProgress;
        do {
            madeProgress = false;
            for (LaneState lane : lanes.values()) {
                while (lane.running < lane.maxRunning) {
                    Optional<ThreadTurn> maybeTurn = pollNextEligible(lane);
                    if (maybeTurn.isEmpty()) {
                        break;
                    }
                    ThreadTurn turn = maybeTurn.get();
                    lane.running++;
                    runningTaskIds.add(turn.threadId());
                    dispatch(turn);
                    madeProgress = true;
                }
            }
        }
        while (madeProgress);
    }

    private Optional<ThreadTurn> pollNextEligible(LaneState lane)
    {
        Iterator<ThreadTurn> iterator = lane.queue.iterator();
        while (iterator.hasNext()) {
            ThreadTurn turn = iterator.next();
            if (runningTaskIds.contains(turn.threadId())) {
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
        Iterator<ThreadTurn> iterator = lane.queue.iterator();
        while (iterator.hasNext()) {
            ThreadTurn turn = iterator.next();
            if (turnIds.contains(turn.id())) {
                iterator.remove();
                lane.knownTurnIds.remove(turn.id());
            }
        }
    }

    private void dispatch(ThreadTurn queuedTurn)
    {
        ThreadTurn runningTurn = updateTurn(
                queuedTurn,
                RUNNING,
                Instant.now(),
                /* finishedAt */ null,
                /* errorMessage */ null);
        turns.saveTurn(runningTurn);
        appendEvent(runningTurn, TURN_STARTED, null);

        Thread thread = threads.findThreadById(runningTurn.threadId()).orElse(null);
        if (thread == null) {
            completeTurn(runningTurn, null, new NoSuchElementException("no thread: " + runningTurn.threadId()));
            return;
        }

        ThreadAgent session;
        try {
            // Trunk turn (task_id IS NULL) routes to the trunk-scope
            // agent — no worktree lease, planning altitude. Task turns
            // keep going through the worktree-leased task agent.
            session = runningTurn.taskId() == null
                    ? sessions.getOrCreateTrunk(thread)
                    : sessions.getOrCreate(thread);
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

    private void completeTurn(ThreadTurn runningTurn, ThreadAgent session, Throwable failure)
    {
        Throwable unwrapped = unwrap(failure);
        boolean failed = unwrapped != null
                || (session != null && session.status() == ThreadStatus.ERRORED);
        Instant now = Instant.now();
        ThreadTurn finished = updateTurn(
                runningTurn,
                failed ? FAILED : COMPLETED,
                runningTurn.startedAt(),
                now,
                unwrapped == null ? null : unwrapped.getMessage());
        turns.saveTurn(finished);
        appendEvent(finished, failed ? TURN_FAILED : TURN_FINISHED, finished.errorMessage());

        synchronized (lock) {
            LaneState lane = lane(runningTurn.lane());
            lane.running = Math.max(0, lane.running - 1);
            runningTaskIds.remove(runningTurn.threadId());
            drainLocked();
        }
    }

    private LaneState lane(ThreadResourceLane lane)
    {
        LaneState state = lanes.get(lane);
        if (state == null) {
            throw new IllegalArgumentException("unknown thread resource lane: " + lane);
        }
        return state;
    }

    static ThreadResourceLane laneFor(Thread thread)
    {
        return switch (thread.kind()) {
            case CLI_AGENT -> CLI;
            case LOGIC_LOOP -> API;
        };
    }

    private String waitingReason(ThreadTurn turn, LaneState lane)
    {
        if (lane.running >= lane.maxRunning) {
            return "waiting for " + turn.lane().name().toLowerCase(Locale.ROOT) + " lane capacity";
        }
        if (runningTaskIds.contains(turn.threadId())) {
            return "waiting for previous turn for this thread";
        }
        return "waiting for scheduler capacity";
    }

    private void appendEvent(ThreadTurn turn, ThreadTurnEventType event, String message)
    {
        events.appendEvent(new ThreadTurnEvent(
                UUID.randomUUID().toString(),
                turn.id(),
                turn.threadId(),
                turn.taskId(),
                event,
                Instant.now(),
                message));
    }

    private static ThreadTurn updateTurn(
            ThreadTurn turn,
            ThreadTurnStatus status,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage)
    {
        Instant now = Instant.now();
        return new ThreadTurn(
                turn.id(),
                turn.threadId(),
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
        private final ArrayDeque<ThreadTurn> queue = new ArrayDeque<>();
        private final Set<String> knownTurnIds = new HashSet<>();
        private int running;

        private LaneState(int maxRunning)
        {
            this.maxRunning = maxRunning;
        }
    }
}
