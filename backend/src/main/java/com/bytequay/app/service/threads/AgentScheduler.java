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

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnEventType;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.AgentContextCompiler;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.agents.ToolExposurePolicy;
import com.bytequay.app.service.codegraph.CodeGraphFirstRuntime;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.runs.SessionBudgetPolicy;
import com.bytequay.app.service.skills.ManagedSkillPolicy;
import com.bytequay.app.service.tools.PermissionResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.bytequay.app.domain.ThreadResourceLane.API;
import static com.bytequay.app.domain.ThreadResourceLane.CLI;
import static com.bytequay.app.domain.ThreadTurnEventType.CODEGRAPH_POLICY;
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
        implements ThreadTurnScheduler, ApplicationEventPublisherAware
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
    private final StageStore stages;
    private final TaskStore tasks;
    private final ManagedSkillPolicy managedSkillPolicy;
    private final AgentContextCompiler contextCompiler;
    private final ActiveAgentContextRegistry activeContexts;
    private final AgentRunService agentRuns;
    private final SessionBudgetPolicy sessionBudgets;
    private final GitRunner git;
    /** Agent metrics are cumulative; snapshot each turn's starting point so
     *  only that turn's delta is added to its public Session. */
    private final ConcurrentHashMap<String, AgentMetrics> usageBaselines = new ConcurrentHashMap<>();
    // HEAD sha per running turn, captured at dispatch. A different sha by the
    // time the turn finishes means the round touched code — the signal that
    // local CI should run as part of the round.
    private final ConcurrentHashMap<String, String> headBaselines = new ConcurrentHashMap<>();
    private final EnumMap<ThreadResourceLane, LaneState> lanes = new EnumMap<>(ThreadResourceLane.class);
    /** Per-agent-identity run gate: holds the agent key of every turn
     *  currently dispatched, so two turns for the SAME agent serialize
     *  while different stages/tasks of one thread run concurrently. The
     *  key is the thread id for a trunk turn (one trunk agent per thread)
     *  and the registry stage key (stage id, else task id) for a
     *  task/stage turn — the same key the registry uses to find the live
     *  agent. The global lane cap still bounds total concurrent agents. */
    private final Set<String> runningAgentKeys = new HashSet<>();
    /** Running turns explicitly cancelled through run/task lifecycle control,
     *  with the durable reason to record. They keep their lane/agent lock
     *  until the provider actually exits, then complete as CANCELLED rather
     *  than masquerading as successful. */
    private final Map<String, String> cancelReasonsByTurnId = new HashMap<>();
    /** The session actually dispatched for each in-flight turn. Keep this
     *  independently of ThreadRegistry: lifecycle teardown may evict the
     *  registry entry before cancellation reaches the scheduler. */
    private final ConcurrentHashMap<String, ThreadAgent> runningTurnSessions =
            new ConcurrentHashMap<>();
    private final Object lock = new Object();
    /** Wired by Spring via {@link ApplicationEventPublisherAware}; stays
     *  null in POJO unit tests that construct the scheduler directly, where
     *  turn-finished side effects (mutex release) aren't under test. */
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    public AgentScheduler(
            ThreadStore threads,
            ThreadTurnStore turns,
            ThreadTurnEventStore events,
            ThreadRegistry sessions,
            StageStore stages,
            TaskStore tasks,
            ManagedSkillPolicy managedSkillPolicy,
            AgentContextCompiler contextCompiler,
            ActiveAgentContextRegistry activeContexts,
            AgentRunService agentRuns,
            SessionBudgetPolicy sessionBudgets,
            GitRunner git,
            @Value("${bytequay.threads.scheduler.max-cli-running:4}") int maxCliRunning,
            @Value("${bytequay.threads.scheduler.max-api-running:6}") int maxApiRunning)
    {
        this(threads, turns, events, sessions, stages, tasks,
                managedSkillPolicy, contextCompiler, activeContexts, agentRuns, sessionBudgets,
                git, maxCliRunning, maxApiRunning, true);
    }

    public AgentScheduler(
            ThreadStore threads,
            ThreadTurnStore turns,
            ThreadTurnEventStore events,
            ThreadRegistry sessions,
            StageStore stages,
            TaskStore tasks,
            @Value("${bytequay.threads.scheduler.max-cli-running:4}") int maxCliRunning,
            @Value("${bytequay.threads.scheduler.max-api-running:6}") int maxApiRunning)
    {
        this(threads, turns, events, sessions, stages, tasks,
                null, null, null, null, null, null, maxCliRunning, maxApiRunning, true);
    }

    private AgentScheduler(
            ThreadStore threads,
            ThreadTurnStore turns,
            ThreadTurnEventStore events,
            ThreadRegistry sessions,
            StageStore stages,
            TaskStore tasks,
            ManagedSkillPolicy managedSkillPolicy,
            AgentContextCompiler contextCompiler,
            ActiveAgentContextRegistry activeContexts,
            AgentRunService agentRuns,
            SessionBudgetPolicy sessionBudgets,
            GitRunner git,
            int maxCliRunning,
            int maxApiRunning,
            @SuppressWarnings("unused") boolean ignored)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.events = requireNonNull(events, "events is null");
        this.sessions = requireNonNull(sessions, "sessions is null");
        this.stages = requireNonNull(stages, "stages is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.managedSkillPolicy = managedSkillPolicy == null ? new ManagedSkillPolicy() : managedSkillPolicy;
        this.contextCompiler = contextCompiler == null
                ? new AgentContextCompiler(this.managedSkillPolicy, new ToolExposurePolicy())
                : contextCompiler;
        this.activeContexts = activeContexts == null ? new ActiveAgentContextRegistry() : activeContexts;
        this.agentRuns = agentRuns;
        this.sessionBudgets = sessionBudgets;
        // Nullable: the minimal test constructor omits it, disabling per-round
        // HEAD-delta detection (codeChanged stays false).
        this.git = git;
        lanes.put(CLI, new LaneState(checkedLimit(maxCliRunning, "maxCliRunning")));
        lanes.put(API, new LaneState(checkedLimit(maxApiRunning, "maxApiRunning")));
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher)
    {
        this.eventPublisher = publisher;
    }

    /**
     * Queue a user turn and start it immediately when the lane has
     * capacity. Routes to the foreground Task when one exists; sends a
     * trunk planning turn otherwise.
     */
    @Override
    public String enqueueTurn(Thread thread, String input)
    {
        return enqueueTurn(thread, input, TurnInitiator.user());
    }

    @Override
    public String enqueueTurn(Thread thread, String input, TurnInitiator initiator)
    {
        // Route to the thread's newest active task when one exists (a thread
        // may run several at once); otherwise a trunk planning turn. The 4-arg
        // overload resolves the task's active stage for the turn's scope.
        return enqueueTaskTurn(thread, input,
                tasks.activeTasksForThread(thread.id()).stream().findFirst()
                        .map(Task::id).orElse(null),
                initiator);
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
        // Trunk turn: no task, no stage — planning altitude.
        return enqueueTurnInternal(
                thread, input, /* taskId */ null, /* stageId */ null,
                TurnInitiator.user(), /* agentRunId */ null,
                TurnLiveness.NARRATION, /* kickKey */ null);
    }

    @Override
    public String enqueueTrunkTurn(Thread thread, String input, TurnInitiator initiator)
    {
        return enqueueTurnInternal(
                thread, input, /* taskId */ null, /* stageId */ null,
                requireNonNull(initiator, "initiator is null"), /* agentRunId */ null,
                TurnLiveness.NARRATION, /* kickKey */ null);
    }

    @Override
    public String enqueueTrunkTurn(Thread thread, String input, String agentRunId)
    {
        return enqueueTurnInternal(
                thread, input, /* taskId */ null, /* stageId */ null,
                TurnInitiator.user(), agentRunId,
                TurnLiveness.NARRATION, /* kickKey */ null);
    }

    /**
     * Queue an attended task-scope turn bound to an explicit {@code
     * taskId} the caller resolved (active-or-latest), rather than
     * re-deriving it from {@link Thread#activeTask()}. The task composer
     * uses this so a turn lands on its task even when that task is parked
     * / awaiting review / phase-complete — states the active-task
     * projection drops to null, which would otherwise stamp the row
     * {@code task_id = null} and surface it in the trunk slice. A null
     * {@code taskId} falls through to a trunk turn.
     */
    @Override
    public String enqueueTaskTurn(Thread thread, String input, String taskId)
    {
        return enqueueTaskTurn(thread, input, taskId, TurnInitiator.user());
    }

    @Override
    public String enqueueTaskTurn(Thread thread, String input, String taskId, TurnInitiator initiator)
    {
        // No explicit stage: resolve the task's active stage so the turn (and
        // its messages) carry a stage_id + scope rather than leaving stage
        // attribution to a time window. A trunk turn (no task) stays stage-less.
        String stageId = taskId == null || taskId.isBlank()
                ? null
                : stages.findActiveStage(taskId).map(s -> s.id().toString()).orElse(null);
        // Attended composer/steering turns are the dev agent doing the
        // user's code work — task-runtime liveness by definition.
        return enqueueTurnInternal(
                thread, input, taskId, stageId, initiator, null,
                TurnLiveness.CODE, /* kickKey */ null);
    }

    @Override
    public String enqueueTaskTurn(
            Thread thread, String input, String taskId, String stageId, TurnInitiator initiator)
    {
        // Caller pins the stage explicitly (automation/iteration turns whose
        // stage is known) — bypass findActiveStage so the turn is stage-scoped
        // even if the active-stage projection is momentarily empty. A turn that
        // carries a stage_id writes to stage_messages, never the thread slice.
        return enqueueTurnInternal(
                thread, input, taskId, stageId, initiator, null,
                TurnLiveness.CODE, /* kickKey */ null);
    }

    @Override
    public String enqueueTaskTurn(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId)
    {
        return enqueueTurnInternal(
                thread, input, taskId, stageId, initiator, agentRunId,
                TurnLiveness.CODE, /* kickKey */ null);
    }

    @Override
    public String enqueueTaskTurn(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        return enqueueTurnInternal(
                thread, input, taskId, stageId, initiator, agentRunId,
                requireNonNull(liveness, "liveness is null"), /* kickKey */ null);
    }

    @Override
    public String enqueueTaskTurnOnce(
            String kickKey, Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        requireNonNull(kickKey, "kickKey is null");
        return enqueueTurnInternal(
                thread, input, taskId, stageId, initiator, agentRunId,
                requireNonNull(liveness, "liveness is null"), kickKey);
    }

    private String enqueueTurnInternal(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId,
            TurnLiveness liveness, String kickKey)
    {
        requireNonNull(thread, "thread is null");
        requireNonNull(input, "input is null");
        requireNonNull(initiator, "initiator is null");
        if (input.isBlank()) {
            throw new IllegalArgumentException("input is blank");
        }
        if (kickKey != null) {
            // Keyed enqueue is claim-once: the durable turn IS the kick
            // claim, so a repeat (listener + sweep racing, retry after a
            // crash-before-launch) returns the existing turn instead of
            // inserting a duplicate. Startup recovery launches a stranded
            // QUEUED row, so claim-without-launch self-heals.
            var existing = turns.findTurnIdByKickKey(kickKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String correlatedRunId = agentRunId;
        if (correlatedRunId == null && agentRuns != null) {
            correlatedRunId = agentRuns.openSchedulerSession(
                    thread, taskId, stageId, sessionKind(thread, stageId), input).id();
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
                /* errorMessage */ null,
                initiator,
                stageId,
                ThreadScope.of(taskId, stageId),
                correlatedRunId);
        boolean affectsLiveness = taskId != null && liveness.affectsTask();
        turns.insertTurn(turn, affectsLiveness, kickKey);
        if (affectsLiveness) {
            engageLivenessPointer(taskId, turn.id());
        }
        appendEvent(turn, TURN_QUEUED, null);
        publishTurnStatus(turn);
        enqueueAfterCommit(turn);
        return turn.id();
    }

    /**
     * A fresh runtime enqueue takes the task's liveness pointer only when
     * nothing live holds it: unset, or held by a successfully COMPLETED
     * turn (the natural promotion point). A live QUEUED/RUNNING holder
     * keeps it — the new turn waits behind — and a FAILED/CANCELLED
     * holder keeps it too, because {@code retryErrored} owns that
     * replacement and sets the pointer itself.
     */
    private void engageLivenessPointer(String taskId, String turnId)
    {
        if (tasks.setCurrentLivenessTurnIdIf(taskId, null, turnId)) {
            return;
        }
        String current = tasks.currentLivenessTurnId(taskId).orElse(null);
        if (current == null || current.equals(turnId)) {
            return;
        }
        ThreadTurn holder = turns.findTurnById(current).orElse(null);
        if (holder == null || holder.status() == COMPLETED) {
            tasks.setCurrentLivenessTurnIdIf(taskId, current, turnId);
        }
    }

    /** Task-scoped turn-status wake signal for the runtime projection —
     *  published after commit so the handler reloads durable state. */
    private void publishTurnStatus(ThreadTurn turn)
    {
        if (eventPublisher == null || turn.taskId() == null) {
            return;
        }
        TaskTurnStatusChanged changed =
                new TaskTurnStatusChanged(turn.taskId(), turn.id(), turn.status());
        if (!deferUntilAfterCommit(() -> eventPublisher.publishEvent(changed))) {
            eventPublisher.publishEvent(changed);
        }
    }

    /** Re-run lane draining — the projection pokes this after promoting a
     *  queued follower to the liveness pointer, so the follower's deferred
     *  row dispatches without waiting for the next natural drain. */
    public void kickDrain()
    {
        synchronized (lock) {
            drainLocked();
        }
    }

    /** Never launch provider work for rows that can still roll back. */
    private void enqueueAfterCommit(ThreadTurn turn)
    {
        if (!deferUntilAfterCommit(() -> enqueuePersistedTurn(turn))) {
            enqueuePersistedTurn(turn);
        }
    }

    /** Register an in-memory scheduler mutation only after its surrounding
     *  database transaction commits. The virtual thread is intentional:
     *  Spring keeps transaction-bound resources attached while invoking
     *  {@code afterCommit}, so repository work must leave that callback
     *  thread first. Returns false when there is no transaction boundary and
     *  the caller should run the action immediately. */
    private static boolean deferUntilAfterCommit(Runnable action)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization()
                {
                    @Override
                    public void afterCommit()
                    {
                        java.lang.Thread.startVirtualThread(action);
                    }
                });
        return true;
    }

    /**
     * Run a batch of API-lane work items concurrently, each bounded by
     * the same {@code max-api-running} capacity that gates thread
     * turns — one global cap for every in-JVM model call, whether it
     * came from a chat turn or a review-panel fan-out. Results come
     * back in submission order. Blocks until every item finished.
     *
     * <p>Each item occupies one API slot only while it runs; items
     * beyond the free capacity wait. Items must not call back into
     * {@code invokeAll} (no nested acquisition), which keeps the
     * blocking acquisition deadlock-free.
     *
     * <p>An item that throws surfaces as a {@link RuntimeException}
     * after the whole batch has finished — partial results are not
     * returned, matching {@link java.util.concurrent.ExecutorService}
     * semantics.
     */
    public <T> List<T> invokeAll(List<Callable<T>> work)
    {
        requireNonNull(work, "work is null");
        List<CompletableFuture<T>> futures = new ArrayList<>();
        for (Callable<T> item : work) {
            CompletableFuture<T> future = new CompletableFuture<>();
            futures.add(future);
            java.lang.Thread.startVirtualThread(() -> {
                try {
                    acquireSlot(API);
                }
                catch (InterruptedException e) {
                    java.lang.Thread.currentThread().interrupt();
                    future.completeExceptionally(e);
                    return;
                }
                try {
                    future.complete(item.call());
                }
                catch (Throwable t) {
                    future.completeExceptionally(t);
                }
                finally {
                    releaseSlot(API);
                }
            });
        }
        List<T> results = new ArrayList<>(futures.size());
        RuntimeException failure = null;
        for (CompletableFuture<T> future : futures) {
            try {
                results.add(future.join());
            }
            catch (CompletionException e) {
                if (failure == null) {
                    failure = e.getCause() instanceof RuntimeException re
                            ? re
                            : new IllegalStateException("invokeAll work item failed", e.getCause());
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return results;
    }

    /** Run one out-of-band CLI turn under the same CLI capacity gate used by
     * durable thread turns. The owning AgentRun remains the durable status/log. */
    public <T> T invokeCli(Callable<T> work)
    {
        requireNonNull(work, "work is null");
        boolean acquired = false;
        try {
            acquireSlot(CLI);
            acquired = true;
            return work.call();
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for CLI capacity", e);
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("CLI work item failed", e);
        }
        finally {
            if (acquired) {
                releaseSlot(CLI);
            }
        }
    }

    private void acquireSlot(ThreadResourceLane resourceLane)
            throws InterruptedException
    {
        synchronized (lock) {
            LaneState lane = lane(resourceLane);
            while (lane.running >= lane.maxRunning) {
                lock.wait();
            }
            lane.running++;
        }
    }

    private void releaseSlot(ThreadResourceLane resourceLane)
    {
        synchronized (lock) {
            LaneState lane = lane(resourceLane);
            lane.running = Math.max(0, lane.running - 1);
            drainLocked();
            lock.notifyAll();
        }
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

                Set<String> queuedTurnIds = queuedTurns.stream()
                        .map(ThreadTurn::id)
                        .collect(Collectors.toSet());
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

    @Override
    public int cancelSessionTurns(String agentRunId)
    {
        requireNonNull(agentRunId, "agentRunId is null");
        if (deferUntilAfterCommit(() -> cancelSessionTurnsNow(agentRunId))) {
            return 0;
        }
        return cancelSessionTurnsNow(agentRunId);
    }

    private int cancelSessionTurnsNow(String agentRunId)
    {
        int cancelled = 0;
        List<ThreadAgent> runningSessions = new ArrayList<>();
        synchronized (lock) {
            List<ThreadTurn> sessionTurns = turns.listTurnsByAgentRunId(
                    agentRunId, TURN_CANCELLATION_PAGE_SIZE);
            Set<String> queuedIds = sessionTurns.stream()
                    .filter(turn -> turn.status() == QUEUED)
                    .map(ThreadTurn::id)
                    .collect(Collectors.toSet());
            for (LaneState lane : lanes.values()) {
                removeQueuedTurns(lane, queuedIds);
            }
            Instant now = Instant.now();
            for (ThreadTurn turn : sessionTurns) {
                if (turn.status() != QUEUED) {
                    continue;
                }
                ThreadTurn stopped = updateTurn(
                        turn, CANCELLED, turn.startedAt(), now,
                        "cancelled by session control");
                turns.saveTurn(stopped);
                appendEvent(stopped, TURN_CANCELLED, "cancelled by session control");
                cancelled++;
            }
            for (ThreadTurn turn : sessionTurns) {
                if (turn.status() != RUNNING || cancelReasonsByTurnId.containsKey(turn.id())) {
                    continue;
                }
                Optional<ThreadAgent> session = findRunningSession(turn);
                if (session.isEmpty()) {
                    cancelOrphanedRunningTurnLocked(turn, "cancelled by session control");
                    cancelled++;
                    continue;
                }
                cancelReasonsByTurnId.put(turn.id(), "cancelled by session control");
                runningSessions.add(session.orElseThrow());
                cancelled++;
            }
            drainLocked();
        }
        // interrupt() may synchronously complete an API-backed future; keep
        // it outside the scheduler lock so completeTurn can release its lane.
        runningSessions.forEach(ThreadAgent::interrupt);
        return cancelled;
    }

    @Override
    public int cancelTaskTurns(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        if (deferUntilAfterCommit(() -> cancelTaskTurnsNow(taskId))) {
            return 0;
        }
        return cancelTaskTurnsNow(taskId);
    }

    private int cancelTaskTurnsNow(String taskId)
    {
        int cancelled = 0;
        List<ThreadAgent> runningSessions = new ArrayList<>();
        synchronized (lock) {
            List<ThreadTurn> queuedTurns;
            do {
                queuedTurns = turns.listTurnsByExactTaskIdAndStatus(
                        taskId, QUEUED, TURN_CANCELLATION_PAGE_SIZE);
                if (queuedTurns.isEmpty()) {
                    break;
                }
                Set<String> queuedIds = queuedTurns.stream()
                        .map(ThreadTurn::id)
                        .collect(Collectors.toSet());
                for (LaneState lane : lanes.values()) {
                    removeQueuedTurns(lane, queuedIds);
                }
                Instant now = Instant.now();
                for (ThreadTurn turn : queuedTurns) {
                    ThreadTurn stopped = updateTurn(
                            turn, CANCELLED, turn.startedAt(), now,
                            "cancelled by task lifecycle action");
                    turns.saveTurn(stopped);
                    appendEvent(stopped, TURN_CANCELLED, "cancelled by task lifecycle action");
                }
                cancelled += queuedTurns.size();
            }
            while (queuedTurns.size() == TURN_CANCELLATION_PAGE_SIZE);

            for (ThreadTurn turn : turns.listTurnsByExactTaskIdAndStatus(
                    taskId, RUNNING, TURN_CANCELLATION_PAGE_SIZE)) {
                if (cancelReasonsByTurnId.containsKey(turn.id())) {
                    continue;
                }
                Optional<ThreadAgent> session = findRunningSession(turn);
                if (session.isEmpty()) {
                    cancelOrphanedRunningTurnLocked(
                            turn, "cancelled by task lifecycle action");
                    cancelled++;
                    continue;
                }
                cancelReasonsByTurnId.put(turn.id(), "cancelled by task lifecycle action");
                runningSessions.add(session.orElseThrow());
                cancelled++;
            }
            drainLocked();
        }
        runningSessions.stream().distinct().forEach(ThreadAgent::interrupt);
        return cancelled;
    }

    private Optional<ThreadAgent> findRunningSession(ThreadTurn turn)
    {
        return Optional.ofNullable(runningTurnSessions.get(turn.id()));
    }

    /** A durable RUNNING row can survive a process/session teardown even
     *  though no provider remains to invoke completeTurn. Close it here so
     *  cancellation cannot leave an immortal RUNNING row. */
    private void cancelOrphanedRunningTurnLocked(ThreadTurn turn, String reason)
    {
        Instant now = Instant.now();
        ThreadTurn stopped = updateTurn(turn, CANCELLED, turn.startedAt(), now, reason);
        turns.saveTurn(stopped);
        appendEvent(stopped, TURN_CANCELLED, reason);
        cancelReasonsByTurnId.remove(turn.id());
        runningTurnSessions.remove(turn.id());
        usageBaselines.remove(turn.id());
        headBaselines.remove(turn.id());
        activeContexts.remove(
                turn.threadId(),
                PermissionResolver.agentKeyFor(turn.taskId(), turn.stageId()));

        // Every locally dispatched turn is present in runningTurnSessions
        // until completion. No entry means this durable orphan owns no local
        // lane slot or agent-key gate to release.
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
        recoverTurns(RUNNING, turn -> {
            ThreadTurn queued = updateTurn(
                    turn,
                    QUEUED,
                    /* startedAt */ null,
                    /* finishedAt */ null,
                    "interrupted by app restart");
            turns.saveTurn(queued);
            appendEvent(queued, TURN_QUEUED, "interrupted by app restart");
            enqueuePersistedTurn(queued);
        });
    }

    private void recoverQueuedTurnsFromStore()
    {
        recoverTurns(QUEUED, this::enqueuePersistedTurn);
    }

    private void recoverTurns(ThreadTurnStatus status, Consumer<ThreadTurn> action)
    {
        List<ThreadTurn> page = turns.listTurnsByStatus(status, RECOVERY_PAGE_SIZE);
        while (!page.isEmpty()) {
            for (ThreadTurn turn : page) {
                action.accept(turn);
            }
            if (page.size() < RECOVERY_PAGE_SIZE) {
                return;
            }
            ThreadTurn cursor = page.get(page.size() - 1);
            page = turns.listTurnsByStatusAfter(
                    status,
                    cursor.createdAt(),
                    cursor.id(),
                    RECOVERY_PAGE_SIZE);
        }
    }

    private void enqueuePersistedTurn(ThreadTurn turn)
    {
        requireNonNull(turn, "turn is null");
        synchronized (lock) {
            // afterCommit/recovery callbacks carry a snapshot. A lifecycle
            // action may have cancelled that row before this callback won the
            // scheduler lock, so reload the durable authority before queueing.
            ThreadTurn persisted = turns.findTurnById(turn.id()).orElse(null);
            if (persisted == null || persisted.status() != QUEUED) {
                return;
            }
            if (cancelIfTaskStoppedLocked(persisted)) {
                return;
            }
            LaneState lane = lane(persisted.lane());
            boolean enqueued = lane.knownTurnIds.add(persisted.id());
            if (enqueued) {
                lane.queue.addLast(persisted);
            }
            drainLocked();
            if (enqueued && lane.knownTurnIds.contains(persisted.id())) {
                appendEvent(persisted, WAITING_FOR_CAPACITY, waitingReason(persisted, lane));
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
                    runningAgentKeys.add(agentKeyOf(turn));
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
            if (runningAgentKeys.contains(agentKeyOf(turn))) {
                continue;
            }
            // A runtime turn dispatches only while it holds the task's
            // liveness pointer — queued followers stay durable and deferred
            // behind the current turn until the projection promotes them.
            if (turn.taskId() != null
                    && turns.turnAffectsTaskLiveness(turn.id())
                    && !turn.id().equals(tasks.currentLivenessTurnId(turn.taskId()).orElse(null))) {
                continue;
            }
            iterator.remove();
            lane.knownTurnIds.remove(turn.id());
            ThreadTurn persisted = turns.findTurnById(turn.id()).orElse(null);
            if (persisted == null || persisted.status() != QUEUED) {
                continue;
            }
            // A turn can wait in memory after the task is parked. Re-read the
            // task immediately before dispatch so a delayed/failed lifecycle
            // cancellation cannot launch provider work for stopped state.
            if (cancelIfTaskStoppedLocked(persisted)) {
                continue;
            }
            return Optional.of(persisted);
        }
        return Optional.empty();
    }

    private boolean cancelIfTaskStoppedLocked(ThreadTurn turn)
    {
        String reason = stoppedTaskReason(turn);
        if (reason == null) {
            return false;
        }
        ThreadTurn cancelled = updateTurn(
                turn, CANCELLED, turn.startedAt(), Instant.now(), reason);
        turns.saveTurn(cancelled);
        appendEvent(cancelled, TURN_CANCELLED, reason);
        transitionRun(
                cancelled.agentRunId(), AgentRun.STATUS_CANCELLED, reason);
        return true;
    }

    private String stoppedTaskReason(ThreadTurn turn)
    {
        if (turn.taskId() == null || turn.taskId().isBlank()) {
            return null; // Trunk work is not governed by a Task row.
        }
        Task task = tasks.findTaskById(turn.taskId()).orElse(null);
        if (task == null) {
            return "cancelled because task no longer exists";
        }
        boolean parkedSteering = task.phase() == TaskPhase.NEEDS_ATTENTION
                && task.status() == TaskStatus.NEEDS_ATTENTION
                && turn.initiator() != null
                && turn.initiator().attended()
                && TurnInitiator.SOURCE_PARKED_STEERING.equals(turn.initiator().source());
        if (parkedSteering) {
            return null;
        }
        if (task.phase() == TaskPhase.NEEDS_ATTENTION
                || task.phase() == TaskPhase.COMPLETED) {
            return "cancelled because task phase is "
                    + task.phase().name().toLowerCase(Locale.ROOT);
        }
        return switch (task.status()) {
            case PAUSED, NEEDS_ATTENTION, COMPLETED, REMOTE_CLOSED,
                    ERRORED, CANCELED, ARCHIVED ->
                    "cancelled because task is " + task.status().name().toLowerCase(Locale.ROOT);
            default -> null;
        };
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
        publishTurnStatus(runningTurn);
        transitionRun(runningTurn.agentRunId(), AgentRun.STATUS_RUNNING, "scheduler started");

        Thread thread = threads.findThreadById(runningTurn.threadId()).orElse(null);
        if (thread == null) {
            completeTurn(runningTurn, null, new NoSuchElementException("no thread: " + runningTurn.threadId()));
            return;
        }

        ThreadAgent session;
        try {
            // A brain thread always drives the TaskBrainAgent, even for a
            // task-scoped turn like plan self-review (task_id + plan stage
            // id both set) — the PLAN_STAGE has no per-stage CLI agent, so
            // routing it through getOrCreateStageAgent throws. Otherwise:
            // a trunk turn (task_id IS NULL) routes to the trunk-scope
            // agent — no worktree lease, planning altitude; a task turn
            // routes to the per-stage agent keyed off the turn's stamped
            // stage id (each stage — Development, CI-fixing, Comments-
            // addressing — gets its own fresh agent); a task-level turn
            // with no stage falls back to keying by task id inside the
            // registry.
            if (thread.kind() == ThreadKind.BRAIN_AGENT) {
                session = sessions.getOrCreateTaskBrainAgent(thread);
            }
            else if (runningTurn.taskId() == null) {
                session = sessions.getOrCreateTrunkAgent(thread);
            }
            else {
                Task task = tasks.findTaskById(runningTurn.taskId()).orElse(null);
                session = sessions.getOrCreateStageAgent(thread, task, runningTurn.stageId());
            }
        }
        catch (RuntimeException e) {
            completeTurn(runningTurn, null, e);
            return;
        }
        runningTurnSessions.put(runningTurn.id(), session);

        CompletionStage<Void> completion;
        try {
            // A task brain reuses one read-only provider session across its
            // ordinary conversation (trunk key) and stage-scoped review
            // turns (PlanStage / review-stage key). Point the MCP bridge at
            // this turn's active context before spawning the provider.
            session.setMcpAgentKey(PermissionResolver.agentKeyFor(
                    runningTurn.taskId(), runningTurn.stageId()));
            session.setActiveTask(runningTurn.taskId());
            // Tell the session which stage this turn runs under so the
            // messages it emits inherit an explicit stage_id.
            session.setActiveStage(runningTurn.stageId());
            session.setActiveAgentRun(runningTurn.agentRunId());
            ResolvedAgentContext context = contextCompiler.resolve(
                    thread.kind(), runningTurn, stageType(runningTurn.stageId()), session.workingDir());
            session.setResolvedAgentContext(context);
            activeContexts.put(
                    runningTurn.threadId(),
                    PermissionResolver.agentKeyFor(runningTurn.taskId(), runningTurn.stageId()),
                    context);
            CodeGraphFirstRuntime.beginTurn(runningTurn.threadId(), agentKeyOf(runningTurn));
            usageBaselines.put(runningTurn.id(), session.metrics());
            captureHeadBaseline(runningTurn, session);
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
        boolean providerFailed = unwrapped != null
                || (session != null && session.status() == ThreadStatus.ERRORED);
        boolean detectedCodeChanged = detectCodeChanged(runningTurn, session, providerFailed);
        Instant now = Instant.now();
        // Prefer the thrown exception's message; when the session failed
        // without throwing (a subprocess that exited non-zero and went
        // ERRORED internally), fall back to its retained failure detail so
        // the turn records the real cause instead of an empty message.
        String errorMessage = unwrapped != null
                ? unwrapped.getMessage()
                : (providerFailed && session != null ? session.lastErrorDetail() : null);
        boolean cancelled;
        boolean failed;
        boolean codeChanged;
        String cancelReason;
        ThreadTurn finished;
        synchronized (lock) {
            // Keep the dispatched-session marker until the durable terminal
            // write. A concurrent cancel can therefore only (a) mark this
            // live turn for cancellation before this block, or (b) observe
            // its terminal row after this block — never misclassify the gap
            // between those two states as an orphan.
            cancelReason = cancelReasonsByTurnId.remove(runningTurn.id());
            cancelled = cancelReason != null;
            failed = !cancelled && providerFailed;
            codeChanged = !cancelled && detectedCodeChanged;
            finished = updateTurn(
                    runningTurn,
                    cancelled ? CANCELLED : failed ? FAILED : COMPLETED,
                    runningTurn.startedAt(),
                    now,
                    cancelled ? cancelReason : errorMessage);
            turns.saveTurn(finished);
            runningTurnSessions.remove(runningTurn.id());
        }
        activeContexts.remove(
                runningTurn.threadId(),
                PermissionResolver.agentKeyFor(runningTurn.taskId(), runningTurn.stageId()));
        appendEvent(
                finished,
                cancelled ? TURN_CANCELLED : failed ? TURN_FAILED : TURN_FINISHED,
                finished.errorMessage());
        publishTurnStatus(finished);
        CodeGraphFirstRuntime.Metrics codeGraphMetrics = CodeGraphFirstRuntime.finishTurn(
                runningTurn.threadId(), agentKeyOf(runningTurn));
        if (!codeGraphMetrics.isEmpty()) {
            appendEvent(finished, CODEGRAPH_POLICY, codeGraphMetrics.toJson());
        }
        // Persist the turn's usage before deciding whether the run may close.
        // A newly exhausted budget parks the run and suppresses the normal
        // success transition; cancelled turns still retain their usage.
        boolean budgetPaused = accountRun(finished, session);
        // Some turns are one step inside a coordinator-owned, multi-turn run.
        // Their coordinator alone decides when live CI is green, review drafts
        // need a gate, or the whole review episode has concluded.
        if (!cancelled
                && (failed || !budgetPaused)
                && !coordinatorOwnsRunCompletion(finished)) {
            transitionRun(
                    finished.agentRunId(),
                    failed ? AgentRun.STATUS_FAILED : AgentRun.STATUS_SUCCEEDED,
                    failed ? finished.errorMessage() : "scheduler turn completed");
        }

        synchronized (lock) {
            LaneState lane = lane(runningTurn.lane());
            lane.running = Math.max(0, lane.running - 1);
            runningAgentKeys.remove(agentKeyOf(runningTurn));
            drainLocked();
            // Wake blocked invokeAll slot acquisitions — they share
            // the lane capacity with turns.
            lock.notifyAll();
        }

        // Outside the lock: let listeners react to a finished turn. Task
        // turns carry their taskId directly; a brain turn carries none, so
        // resolve its parent task so plan-stage listeners (the record_plan
        // nudge + failure surfacing) can react. Pure trunk turns stay
        // unlinked.
        if (!cancelled && eventPublisher != null) {
            String taskId = finished.taskId();
            if (taskId == null) {
                taskId = threads.findThreadById(finished.threadId())
                        .filter(t -> t.kind() == ThreadKind.BRAIN_AGENT)
                        .map(Thread::parentTaskId)
                        .orElse(null);
            }
            if (taskId != null) {
                if (budgetPaused) {
                    eventPublisher.publishEvent(new TaskTurnBudgetPausedEvent(taskId, finished.id()));
                }
                else {
                    eventPublisher.publishEvent(
                            new TaskTurnFinishedEvent(taskId, finished.id(), failed, codeChanged));
                }
            }
        }
    }

    /** Records the worktree HEAD at dispatch so completeTurn can tell whether
     *  the round moved it. Task turns only; best-effort (a missing baseline
     *  just means the round won't be classified as code-changed). */
    private void captureHeadBaseline(ThreadTurn turn, ThreadAgent session)
    {
        if (git == null || turn.taskId() == null) {
            return;
        }
        String workingDir = session.workingDir();
        if (workingDir == null || workingDir.isBlank()) {
            return;
        }
        try {
            headBaselines.put(turn.id(), git.headSha(Path.of(workingDir)));
        }
        catch (IOException ignored) {
            // No baseline captured — the round won't count as code-changed.
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
        }
    }

    /** True when this round moved the worktree HEAD (i.e. touched code).
     *  Always clears the baseline entry so the map can't leak. */
    private boolean detectCodeChanged(ThreadTurn turn, ThreadAgent session, boolean failed)
    {
        String baseline = headBaselines.remove(turn.id());
        if (failed || baseline == null || git == null || session == null) {
            return false;
        }
        String workingDir = session.workingDir();
        if (workingDir == null || workingDir.isBlank()) {
            return false;
        }
        try {
            return !baseline.equals(git.headSha(Path.of(workingDir)));
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            return false;
        }
    }

    private StageType stageType(String stageId)
    {
        if (stageId == null || stageId.isBlank()) {
            return null;
        }
        try {
            return stages.findStageById(UUID.fromString(stageId))
                    .map(StageInstance::type)
                    .orElse(null);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String sessionKind(Thread thread, String stageId)
    {
        if (thread.flow() == ThreadFlow.REVIEW) {
            return AgentRun.KIND_REVIEW;
        }
        StageType type = stageType(stageId);
        if (type == StageType.CI_FIXING_STAGE) {
            return AgentRun.KIND_CI_FIX;
        }
        if (type == StageType.PLAN_STAGE || thread.kind() == ThreadKind.BRAIN_AGENT
                || stageId == null) {
            return AgentRun.KIND_PLAN;
        }
        if (type == StageType.REVIEW_STAGE || type == StageType.REVIEW_ROUND_STAGE) {
            return AgentRun.KIND_REVIEW;
        }
        return AgentRun.KIND_DEV;
    }

    private void transitionRun(String runId, String status, String reason)
    {
        if (agentRuns == null || runId == null || runId.isBlank()) {
            return;
        }
        try {
            agentRuns.transition(runId, status, reason);
        }
        catch (RuntimeException e) {
            // A session projection must never strand or fail the scheduler's
            // authoritative turn state.
        }
    }

    private static boolean coordinatorOwnsRunCompletion(ThreadTurn turn)
    {
        if (turn.agentRunId() == null || turn.initiator() == null) {
            return false;
        }
        String source = turn.initiator().source();
        if (source == null) {
            return false;
        }
        return switch (source) {
            case "ci-fix-shipped", "review-round", "brain-review", "brain-review-fix",
                    "branch-guard-fix" -> true;
            default -> false;
        };
    }

    private boolean accountRun(ThreadTurn turn, ThreadAgent session)
    {
        AgentMetrics before = usageBaselines.remove(turn.id());
        if (sessionBudgets == null || session == null || before == null) {
            return false;
        }
        try {
            return sessionBudgets.account(turn.agentRunId(), before, session.metrics());
        }
        catch (RuntimeException ignored) {
            // Session accounting is a projection. A failure here must never
            // strand the scheduler's authoritative turn state.
            return false;
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

    /**
     * The run-gate key identifying the AGENT a turn dispatches to — the
     * same key {@link ThreadRegistry} uses to find that agent. A trunk
     * turn (no task) routes to the one-per-thread trunk agent, so it keys
     * by thread id and stays serialized. A task/stage turn keys by its
     * stamped stage id (each stage gets its own agent), falling back to
     * the task id for a task-level turn with no stage. Two turns sharing
     * a key serialize; turns for different stages/tasks of one thread do
     * not block each other.
     */
    private String agentKeyOf(ThreadTurn turn)
    {
        // A Brain thread has exactly one reusable TaskBrainAgent in
        // trunkSessions. All of its stage-scoped turns therefore share the
        // thread key; stage-keying allowed concurrent sends to one session.
        if (threads.findThreadById(turn.threadId())
                .map(Thread::kind)
                .filter(kind -> kind == ThreadKind.BRAIN_AGENT)
                .isPresent()) {
            return turn.threadId();
        }
        if (turn.taskId() == null || turn.taskId().isBlank()) {
            return turn.threadId();
        }
        if (turn.stageId() != null && !turn.stageId().isBlank()) {
            return turn.stageId();
        }
        return turn.taskId();
    }

    /** The lane a turn belongs on. Every thread follows its resolved work
     *  model — the same cascade the registry builds the runtime from — so a
     *  CLI subprocess always counts against the small CLI cap even when the
     *  thread row was stamped LOGIC_LOOP at creation, and vice versa. */
    ThreadResourceLane laneFor(Thread thread)
    {
        return sessions.resolvedWorkModel(thread).kind() == WorkModelKind.CLI ? CLI : API;
    }

    private String waitingReason(ThreadTurn turn, LaneState lane)
    {
        if (lane.running >= lane.maxRunning) {
            return "waiting for " + turn.lane().name().toLowerCase(Locale.ROOT) + " lane capacity";
        }
        if (runningAgentKeys.contains(agentKeyOf(turn))) {
            return "waiting for previous turn for this agent";
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
                errorMessage,
                turn.initiator(),
                // Preserve the stamped stage id + scope across status
                // updates — the dispatcher keys the per-stage agent off the
                // running turn's stage id, and the running row must keep the
                // scope so the permission resolver reads the right role.
                turn.stageId(),
                turn.scope(),
                turn.agentRunId());
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
