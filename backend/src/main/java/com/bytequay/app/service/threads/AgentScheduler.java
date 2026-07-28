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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.LegacyCapacityBridge;
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
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.AgentRunStore;
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
import com.bytequay.app.statemachine.StateMachine;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.bytequay.app.domain.ThreadResourceLane.API;
import static com.bytequay.app.domain.ThreadResourceLane.CLI;
import static com.bytequay.app.domain.ThreadTurnEventType.CODEGRAPH_POLICY;
import static com.bytequay.app.domain.ThreadTurnEventType.SCHEDULER_ALERT;
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
    private static final int SHARED_CLI_LIMIT = 4;
    private static final int SHARED_API_LIMIT = 6;
    private static final long LEGACY_TASK_EPOCH = 1L;
    // Usually one or two follow-up turns. Keep the page large enough
    // for normal use, but bounded so a pathological thread cannot load
    // every durable queued turn in one SQLite read.
    private static final int TURN_CANCELLATION_PAGE_SIZE = 1_000;
    private static final StateMachine<ThreadTurnStatus> TURN_GRAPH =
            StateMachine.<ThreadTurnStatus>builder("thread turn")
                    .edge(QUEUED, RUNNING, FAILED, CANCELLED)
                    .edge(RUNNING, COMPLETED, FAILED, CANCELLED, QUEUED)
                    .terminal(COMPLETED, FAILED, CANCELLED)
                    .build();

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
    private final AgentRunStore agentRunStore;
    private final TaskCommandExecutor taskCommands;
    private final TransactionTemplate detachedTransactions;
    private final SessionBudgetPolicy sessionBudgets;
    private final GitRunner git;
    private final LegacyCapacityBridge legacyCapacity;
    private final CapacityManager.AvailabilityRegistration capacityWakeRegistration;
    /** Agent metrics are cumulative; snapshot each turn's starting point so
     *  only that turn's delta is added to its public Session. */
    private final ConcurrentHashMap<String, AgentMetrics> usageBaselines = new ConcurrentHashMap<>();
    // HEAD sha per running turn, captured at dispatch. A different sha by the
    // time the turn finishes means the round touched code — the signal that
    // local CI should run as part of the round.
    private final ConcurrentHashMap<String, String> headBaselines = new ConcurrentHashMap<>();
    private final EnumMap<ThreadResourceLane, LaneState> lanes = new EnumMap<>(ThreadResourceLane.class);
    /** Per-agent-identity run gate. The key is the thread id for a trunk
     *  turn and the Task id for task/stage work, so stages of one Task
     *  serialize while sibling Tasks may run concurrently. */
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
    /** Exact shared-capacity permit for each locally reserved legacy turn. */
    private final ConcurrentHashMap<String, LegacyCapacityBridge.Permit> capacityPermitsByTurnId =
            new ConcurrentHashMap<>();
    /** A denied turn is skipped until CapacityManager advances this version. */
    private final Map<String, Long> capacityDeniedAtVersion = new HashMap<>();
    /** Lease loss wins over a nominal provider success and fails the turn. */
    private final Map<String, String> capacityFailuresByTurnId = new HashMap<>();
    private final Object lock = new Object();
    private boolean draining;
    private boolean drainRequested;
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
            AgentRunStore agentRunStore,
            TaskCommandExecutor taskCommands,
            PlatformTransactionManager transactionManager,
            SessionBudgetPolicy sessionBudgets,
            GitRunner git,
            LegacyCapacityBridge legacyCapacity,
            @Value("${bytequay.threads.scheduler.max-cli-running:4}") int maxCliRunning,
            @Value("${bytequay.threads.scheduler.max-api-running:6}") int maxApiRunning)
    {
        this(threads, turns, events, sessions, stages, tasks,
                managedSkillPolicy, contextCompiler, activeContexts, agentRuns,
                agentRunStore, taskCommands, transactionManager, sessionBudgets,
                git, legacyCapacity,
                sharedLimit(maxCliRunning, SHARED_CLI_LIMIT, "maxCliRunning"),
                sharedLimit(maxApiRunning, SHARED_API_LIMIT, "maxApiRunning"),
                true);
    }

    /** Production-shape constructor retained for focused tests. */
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
            AgentRunStore agentRunStore,
            TaskCommandExecutor taskCommands,
            PlatformTransactionManager transactionManager,
            SessionBudgetPolicy sessionBudgets,
            GitRunner git,
            int maxCliRunning,
            int maxApiRunning)
    {
        this(threads, turns, events, sessions, stages, tasks,
                managedSkillPolicy, contextCompiler, activeContexts, agentRuns,
                agentRunStore, taskCommands, transactionManager, sessionBudgets,
                git, null, maxCliRunning, maxApiRunning, true);
    }

    /** Dependency-light constructor retained for focused scheduler tests. */
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
            int maxCliRunning,
            int maxApiRunning)
    {
        this(threads, turns, events, sessions, stages, tasks,
                managedSkillPolicy, contextCompiler, activeContexts, agentRuns,
                null, null, null, sessionBudgets, git,
                null, maxCliRunning, maxApiRunning, true);
    }

    /** Dependency-light constructor that exercises the mixed-version bridge. */
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
            LegacyCapacityBridge legacyCapacity,
            int maxCliRunning,
            int maxApiRunning)
    {
        this(threads, turns, events, sessions, stages, tasks,
                managedSkillPolicy, contextCompiler, activeContexts, agentRuns,
                null, null, null, sessionBudgets, git,
                legacyCapacity, maxCliRunning, maxApiRunning, true);
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
                null, null, null, null, null, null, null, null, null,
                null, maxCliRunning, maxApiRunning, true);
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
            AgentRunStore agentRunStore,
            TaskCommandExecutor taskCommands,
            PlatformTransactionManager transactionManager,
            SessionBudgetPolicy sessionBudgets,
            GitRunner git,
            LegacyCapacityBridge legacyCapacity,
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
        this.agentRunStore = agentRunStore;
        this.taskCommands = taskCommands;
        this.detachedTransactions = transactionManager == null
                ? null
                : new TransactionTemplate(transactionManager);
        this.sessionBudgets = sessionBudgets;
        // Nullable: the minimal test constructor omits it, disabling per-round
        // HEAD-delta detection (codeChanged stays false).
        this.git = git;
        this.legacyCapacity = legacyCapacity;
        lanes.put(CLI, new LaneState(checkedLimit(maxCliRunning, "maxCliRunning")));
        lanes.put(API, new LaneState(checkedLimit(maxApiRunning, "maxApiRunning")));
        this.capacityWakeRegistration = legacyCapacity == null
                ? () -> {}
                : legacyCapacity.onCapacityAvailable(this::capacityAvailable);
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher)
    {
        this.eventPublisher = publisher;
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
                TurnLiveness.NARRATION, /* kickKey */ null, ThreadScope.TRUNK);
    }

    @Override
    public String enqueueTrunkTurn(Thread thread, String input, TurnInitiator initiator)
    {
        return enqueueTurnInternal(
                thread, input, /* taskId */ null, /* stageId */ null,
                requireNonNull(initiator, "initiator is null"), /* agentRunId */ null,
                TurnLiveness.NARRATION, /* kickKey */ null, ThreadScope.TRUNK);
    }

    @Override
    public String enqueueTrunkTurn(Thread thread, String input, String agentRunId)
    {
        return enqueueTurnInternal(
                thread, input, /* taskId */ null, /* stageId */ null,
                TurnInitiator.user(), agentRunId,
                TurnLiveness.NARRATION, /* kickKey */ null, ThreadScope.TRUNK);
    }

    /** Queue an attended, explicitly task-scoped turn. */
    @Override
    public String enqueueTaskTurn(Thread thread, String input, String taskId)
    {
        return enqueueTaskTurn(thread, input, taskId, TurnInitiator.user());
    }

    @Override
    public String enqueueTaskTurn(Thread thread, String input, String taskId, TurnInitiator initiator)
    {
        requireScopeId(taskId, "taskId");
        return enqueueTurnInternal(
                thread, input, taskId, null, initiator, null,
                TurnLiveness.CODE, /* kickKey */ null, ThreadScope.TASK);
    }

    @Override
    public String enqueueTaskTurn(
            Thread thread, String input, String taskId, TurnInitiator initiator,
            String agentRunId, TurnLiveness liveness)
    {
        requireScopeId(taskId, "taskId");
        return enqueueTurnInternal(
                thread, input, taskId, null, initiator, agentRunId,
                requireNonNull(liveness, "liveness is null"), /* kickKey */ null,
                ThreadScope.TASK);
    }

    @Override
    public String enqueueTaskTurnOnce(
            String kickKey, Thread thread, String input, String taskId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        requireNonNull(kickKey, "kickKey is null");
        requireScopeId(taskId, "taskId");
        return enqueueTurnInternal(
                thread, input, taskId, null, initiator, agentRunId,
                requireNonNull(liveness, "liveness is null"), kickKey,
                ThreadScope.TASK);
    }

    @Override
    public String enqueueStageTurn(
            Thread thread, String input, String taskId, String stageId, TurnInitiator initiator)
    {
        requireScopeId(taskId, "taskId");
        requireScopeId(stageId, "stageId");
        return enqueueTurnInternal(
                thread, input, taskId, stageId, initiator, null,
                TurnLiveness.CODE, /* kickKey */ null, ThreadScope.STAGE);
    }

    @Override
    public String enqueueStageTurn(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId)
    {
        requireScopeId(taskId, "taskId");
        requireScopeId(stageId, "stageId");
        return enqueueTurnInternal(
                thread, input, taskId, stageId, initiator, agentRunId,
                TurnLiveness.CODE, /* kickKey */ null, ThreadScope.STAGE);
    }

    @Override
    public String enqueueStageTurn(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        requireScopeId(taskId, "taskId");
        requireScopeId(stageId, "stageId");
        return enqueueTurnInternal(
                thread, input, taskId, stageId, initiator, agentRunId,
                requireNonNull(liveness, "liveness is null"), /* kickKey */ null,
                ThreadScope.STAGE);
    }

    @Override
    public String enqueueStageTurnOnce(
            String kickKey, Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        requireNonNull(kickKey, "kickKey is null");
        requireScopeId(taskId, "taskId");
        requireScopeId(stageId, "stageId");
        return enqueueTurnInternal(
                thread, input, taskId, stageId, initiator, agentRunId,
                requireNonNull(liveness, "liveness is null"), kickKey,
                ThreadScope.STAGE);
    }

    private String enqueueTurnInternal(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId,
            TurnLiveness liveness, String kickKey, ThreadScope scope)
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
        // Resolve before opening the correlated run: an invalid task/stage
        // must not leave an orphan run, and the persisted lane must match the
        // exact audience-specific runtime this turn will spawn.
        ThreadResourceLane resourceLane = laneFor(thread, taskId, stageId, scope);
        String correlatedRunId = agentRunId;
        if (correlatedRunId == null && agentRuns != null) {
            correlatedRunId = agentRuns.openSchedulerSession(
                    thread, taskId, stageId, sessionKind(thread, stageId, scope), input).id();
        }
        Instant now = Instant.now();
        ThreadTurn turn = new ThreadTurn(
                UUID.randomUUID().toString(),
                thread.id(),
                taskId,
                resourceLane,
                QUEUED,
                input,
                now,
                now,
                /* startedAt */ null,
                /* finishedAt */ null,
                /* errorMessage */ null,
                initiator,
                stageId,
                requireNonNull(scope, "scope is null"),
                correlatedRunId);
        boolean affectsLiveness = scope != ThreadScope.TRUNK && liveness.affectsTask();
        ThreadTurnStore.InsertResult insert = turns.insertTurn(
                turn, affectsLiveness, kickKey);
        if (!insert.inserted()) {
            return insert.turnId();
        }
        if (affectsLiveness) {
            engageLivenessPointer(turn);
        }
        appendEvent(turn, TURN_QUEUED, null);
        publishTurnStatus(turn);
        enqueueAfterCommit(turn);
        return insert.turnId();
    }

    private static void requireScopeId(String id, String name)
    {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    static boolean isLegalTurnTransition(ThreadTurnStatus from, ThreadTurnStatus to)
    {
        return TURN_GRAPH.isLegal(from, to);
    }

    /**
     * A fresh runtime enqueue takes the task's liveness pointer only when
     * nothing live holds it: unset, or held by a successfully COMPLETED
     * turn (the natural promotion point). A live QUEUED/RUNNING holder
     * keeps it — the new turn waits behind — and a FAILED/CANCELLED
     * holder normally keeps it too, because {@code retryErrored} owns that
     * replacement and sets the pointer itself. The one exception is a new
     * attended stage steer on a task whose lifecycle still permits that
     * turn: the steer is itself the user's explicit recovery intent.
     */
    private void engageLivenessPointer(ThreadTurn turn)
    {
        String taskId = requireNonNull(turn.taskId(), "turn taskId is null");
        String turnId = turn.id();
        if (tasks.setCurrentLivenessTurnIdIf(taskId, null, turnId)) {
            return;
        }
        String current = tasks.currentLivenessTurnId(taskId).orElse(null);
        if (current == null || current.equals(turnId)) {
            return;
        }
        ThreadTurn holder = turns.findTurnById(current).orElse(null);
        boolean attendedSteeringRecovery = holder != null
                && (holder.status() == FAILED || holder.status() == CANCELLED)
                && isAttendedStageSteering(turn)
                && stoppedTaskReason(turn) == null;
        if (holder == null || holder.status() == COMPLETED || attendedSteeringRecovery) {
            tasks.setCurrentLivenessTurnIdIf(taskId, current, turnId);
        }
    }

    private static boolean isAttendedStageSteering(ThreadTurn turn)
    {
        TurnInitiator initiator = turn.initiator();
        return turn.scope() == ThreadScope.STAGE
                && initiator != null
                && initiator.attended()
                && ("steering".equals(initiator.source())
                || TurnInitiator.SOURCE_PARKED_STEERING.equals(initiator.source()));
    }

    /** Task-scoped turn-status wake signal for the runtime projection —
     *  published after commit so the handler reloads durable state. */
    private void publishTurnStatus(ThreadTurn turn)
    {
        if (eventPublisher == null || turn.scope() == ThreadScope.TRUNK) {
            return;
        }
        TaskTurnStatusChanged changed =
                new TaskTurnStatusChanged(turn.requireTaskId(), turn.id(), turn.status());
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
            capacityDeniedAtVersion.clear();
            lock.notifyAll();
        }
        drain();
    }

    /** A CapacityManager release is only a wake hint; durable rows are re-read. */
    private void capacityAvailable()
    {
        // Some cancellation/recovery paths release an exact lease while they
        // already hold this monitor. Their caller drains after unlocking.
        if (java.lang.Thread.holdsLock(lock)) {
            return;
        }
        synchronized (lock) {
            lock.notifyAll();
        }
        drain();
    }

    @PreDestroy
    void closeCapacityWakeRegistration()
    {
        capacityWakeRegistration.close();
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
        List<CapacityBoundWork<T>> admitted = work.stream()
                .map(item -> new CapacityBoundWork<T>(API, null, item))
                .toList();
        return invokeCapacityBoundAll(admitted);
    }

    /**
     * Run already-durable legacy review attempts under one exact shared
     * REVIEW + provider-lane lease each. The request identity is supplied by
     * the review protocol; this scheduler only owns admission and execution.
     */
    public <T> List<T> invokeReviewAll(List<ReviewWork<T>> work)
    {
        requireNonNull(work, "work is null");
        List<CapacityBoundWork<T>> admitted = work.stream()
                .map(item -> new CapacityBoundWork<>(
                        reviewResourceLane(item.request()),
                        item.request(),
                        item.work()))
                .toList();
        return invokeCapacityBoundAll(admitted);
    }

    /**
     * Admit a progress-making seed for a nested review fan-out without
     * waiting, then run the rest through normal bounded admission. The seed
     * guarantees capacity can be released while the Lead waits, avoiding a
     * nested deadlock without serializing a normal multi-reviewer panel.
     */
    public <T> Optional<List<T>> tryInvokeReviewAll(List<ReviewWork<T>> work)
    {
        requireNonNull(work, "work is null");
        List<CapacityBoundWork<T>> admitted = work.stream()
                .map(item -> new CapacityBoundWork<>(
                        reviewResourceLane(item.request()),
                        item.request(),
                        item.work()))
                .toList();
        return tryInvokeCapacityBoundAll(admitted);
    }

    public <T> T invokeReviewApi(
            CapacityManager.CapacityRequest request,
            Callable<T> work)
    {
        validateReviewRequest(request, API);
        return invokeCapacityBound(API, request, work, "API review");
    }

    public <T> T invokeReviewCli(
            CapacityManager.CapacityRequest request,
            Callable<T> work)
    {
        validateReviewRequest(request, CLI);
        return invokeCapacityBound(CLI, request, work, "CLI review");
    }

    /**
     * Try one nested review launch without waiting for capacity. Review tools
     * execute inside the Lead provider call, so blocking when the Lead owns
     * the last REVIEW or API slot would deadlock that protocol round.
     */
    public <T> Optional<T> tryInvokeReviewApi(
            CapacityManager.CapacityRequest request,
            Callable<T> work)
    {
        validateReviewRequest(request, API);
        return tryInvokeCapacityBound(API, request, work, "API review");
    }

    /** CLI counterpart of {@link #tryInvokeReviewApi}. */
    public <T> Optional<T> tryInvokeReviewCli(
            CapacityManager.CapacityRequest request,
            Callable<T> work)
    {
        validateReviewRequest(request, CLI);
        return tryInvokeCapacityBound(CLI, request, work, "CLI review");
    }

    private <T> List<T> invokeCapacityBoundAll(List<CapacityBoundWork<T>> work)
    {
        List<CompletableFuture<T>> futures = new ArrayList<>();
        for (CapacityBoundWork<T> item : work) {
            CompletableFuture<T> future = new CompletableFuture<>();
            futures.add(future);
            java.lang.Thread.startVirtualThread(() -> runCapacityBound(item, future));
        }
        return collectResults(futures, "invokeAll");
    }

    private <T> Optional<List<T>> tryInvokeCapacityBoundAll(
            List<CapacityBoundWork<T>> work)
    {
        if (work.isEmpty()) {
            return Optional.of(List.of());
        }
        int seedIndex = 0;
        for (int index = 0; index < work.size(); index++) {
            if (work.get(index).resourceLane() == API) {
                seedIndex = index;
                break;
            }
        }
        CapacityBoundWork<T> seedWork = work.get(seedIndex);
        LossGuard guard = new LossGuard();
        Optional<SharedSlot> seedSlot = tryAcquireSharedSlot(
                seedWork.resourceLane(), seedWork.request(), guard::stop);
        if (seedSlot.isEmpty()) {
            return Optional.empty();
        }
        PreparedWork<T> seed = new PreparedWork<>(
                seedWork, seedSlot.orElseThrow(), guard);

        List<CompletableFuture<T>> futures = new ArrayList<>(work.size());
        for (int index = 0; index < work.size(); index++) {
            futures.add(new CompletableFuture<>());
        }
        List<Integer> submitted = new ArrayList<>();
        try {
            int admittedIndex = seedIndex;
            java.lang.Thread.startVirtualThread(() ->
                    runPrepared(seed, futures.get(admittedIndex)));
            submitted.add(seedIndex);
            for (int index = 0; index < work.size(); index++) {
                if (index == seedIndex) {
                    continue;
                }
                int workIndex = index;
                java.lang.Thread.startVirtualThread(() ->
                        runCapacityBound(work.get(workIndex), futures.get(workIndex)));
                submitted.add(index);
            }
        }
        catch (RuntimeException | Error submissionFailure) {
            if (!submitted.contains(seedIndex)) {
                try {
                    releasePrepared(seed);
                }
                catch (RuntimeException releaseFailure) {
                    submissionFailure.addSuppressed(releaseFailure);
                }
            }
            for (int index : submitted) {
                try {
                    futures.get(index).join();
                }
                catch (CompletionException ignored) {
                    // Submission failure remains the primary failure.
                }
            }
            throw submissionFailure;
        }
        return Optional.of(collectResults(futures, "review"));
    }

    private <T> void runCapacityBound(
            CapacityBoundWork<T> item,
            CompletableFuture<T> future)
    {
        LegacyCapacityBridge.Permit permit = null;
        boolean acquired = false;
        T result = null;
        Throwable failure = null;
        try {
            java.lang.Thread worker = java.lang.Thread.currentThread();
            permit = item.request() == null
                    ? acquireSharedSlot(
                            item.resourceLane(),
                            "legacy-api-call:" + UUID.randomUUID(),
                            worker::interrupt)
                    : acquireSharedSlot(
                            item.resourceLane(), item.request(), worker::interrupt);
            acquired = true;
            result = item.work().call();
            requireLivePermit(permit);
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            failure = e;
        }
        catch (Throwable t) {
            failure = t;
        }
        finally {
            if (acquired) {
                try {
                    releaseSharedSlot(item.resourceLane(), permit);
                }
                catch (Throwable releaseFailure) {
                    if (failure == null) {
                        failure = releaseFailure;
                    }
                    else {
                        failure.addSuppressed(releaseFailure);
                    }
                }
            }
        }
        complete(future, result, failure);
    }

    private <T> void runPrepared(
            PreparedWork<T> item,
            CompletableFuture<T> future)
    {
        T result = null;
        Throwable failure = null;
        try {
            item.guard().bindCurrentWorker();
            requireLivePermit(item.slot().permit());
            result = item.work().work().call();
            requireLivePermit(item.slot().permit());
        }
        catch (Throwable t) {
            failure = t;
        }
        finally {
            item.guard().clearCurrentWorker();
            try {
                releasePrepared(item);
            }
            catch (Throwable releaseFailure) {
                if (failure == null) {
                    failure = releaseFailure;
                }
                else {
                    failure.addSuppressed(releaseFailure);
                }
            }
        }
        complete(future, result, failure);
    }

    private void releasePrepared(PreparedWork<?> item)
    {
        releaseSharedSlot(item.work().resourceLane(), item.slot().permit());
    }

    private static <T> void complete(
            CompletableFuture<T> future,
            T result,
            Throwable failure)
    {
        if (failure == null) {
            future.complete(result);
        }
        else {
            future.completeExceptionally(failure);
        }
    }

    private static <T> List<T> collectResults(
            List<CompletableFuture<T>> futures,
            String label)
    {
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
                            : new IllegalStateException(
                                    label + " work item failed", e.getCause());
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return List.copyOf(results);
    }

    /** Run one out-of-band CLI turn under the same CLI capacity gate used by
     * durable thread turns. The owning AgentRun remains the durable status/log. */
    public <T> T invokeCli(Callable<T> work)
    {
        requireNonNull(work, "work is null");
        return invokeCapacityBound(CLI, null, work, "CLI");
    }

    private <T> T invokeCapacityBound(
            ThreadResourceLane resourceLane,
            CapacityManager.CapacityRequest request,
            Callable<T> work,
            String label)
    {
        requireNonNull(resourceLane, "resourceLane is null");
        requireNonNull(work, "work is null");
        LegacyCapacityBridge.Permit permit = null;
        boolean acquired = false;
        try {
            java.lang.Thread worker = java.lang.Thread.currentThread();
            permit = request == null
                    ? acquireSharedSlot(
                            resourceLane,
                            "legacy-" + resourceLane.name().toLowerCase(Locale.ROOT)
                                    + "-call:" + UUID.randomUUID(),
                            worker::interrupt)
                    : acquireSharedSlot(resourceLane, request, worker::interrupt);
            acquired = true;
            T result = work.call();
            requireLivePermit(permit);
            return result;
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted waiting for " + label + " capacity", e);
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException(label + " work item failed", e);
        }
        finally {
            if (acquired) {
                releaseSharedSlot(resourceLane, permit);
            }
        }
    }

    private <T> Optional<T> tryInvokeCapacityBound(
            ThreadResourceLane resourceLane,
            CapacityManager.CapacityRequest request,
            Callable<T> work,
            String label)
    {
        requireNonNull(resourceLane, "resourceLane is null");
        requireNonNull(work, "work is null");
        Optional<SharedSlot> slot = tryAcquireSharedSlot(
                resourceLane, request, java.lang.Thread.currentThread()::interrupt);
        if (slot.isEmpty()) {
            return Optional.empty();
        }
        try {
            T result = requireNonNull(work.call(), label + " work item returned null");
            requireLivePermit(slot.orElseThrow().permit());
            return Optional.of(result);
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException(label + " work item failed", e);
        }
        finally {
            releaseSharedSlot(resourceLane, slot.orElseThrow().permit());
        }
    }

    private static ThreadResourceLane reviewResourceLane(
            CapacityManager.CapacityRequest request)
    {
        requireNonNull(request, "request is null");
        if (request.lanes().contains(CapacityManager.CapacityLane.CLI)) {
            validateReviewRequest(request, CLI);
            return CLI;
        }
        validateReviewRequest(request, API);
        return API;
    }

    private static void validateReviewRequest(
            CapacityManager.CapacityRequest request,
            ThreadResourceLane resourceLane)
    {
        requireNonNull(request, "request is null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "review capacity must be acquired outside a database transaction");
        }
        Set<CapacityManager.CapacityLane> expected = Set.of(
                CapacityManager.CapacityLane.REVIEW,
                capacityLane(resourceLane));
        if (request.source() != CapacityManager.WorkflowSource.LEGACY
                || !request.lanes().equals(expected)
                || request.trunkControl()
                || request.exclusiveTask()
                || request.writerRequired()) {
            throw new IllegalArgumentException(
                    "legacy review admission requires exact read-only REVIEW + "
                            + resourceLane + " capacity");
        }
    }

    private LegacyCapacityBridge.Permit acquireSharedSlot(
            ThreadResourceLane resourceLane,
            String operationId,
            Runnable stopOnLeaseLoss)
            throws InterruptedException
    {
        if (legacyCapacity == null) {
            acquireSlot(resourceLane);
            return null;
        }
        String leaseOwner = operationId;
        CapacityManager.CapacityRequest request = unscopedLegacyRequest(
                operationId, resourceLane);
        while (true) {
            long observedVersion = legacyCapacity.availabilityVersion();
            acquireSlot(resourceLane);
            Optional<LegacyCapacityBridge.Permit> permit;
            try {
                permit = legacyCapacity.tryAcquire(
                        request, leaseOwner, stopOnLeaseLoss);
            }
            catch (RuntimeException e) {
                releaseSlot(resourceLane);
                throw e;
            }
            if (permit.isPresent()) {
                return permit.orElseThrow();
            }
            releaseSlot(resourceLane);
            synchronized (lock) {
                while (legacyCapacity.availabilityVersion() == observedVersion) {
                    lock.wait();
                }
            }
        }
    }

    private LegacyCapacityBridge.Permit acquireSharedSlot(
            ThreadResourceLane resourceLane,
            CapacityManager.CapacityRequest request,
            Runnable stopOnLeaseLoss)
            throws InterruptedException
    {
        if (legacyCapacity == null) {
            acquireSlot(resourceLane);
            return null;
        }
        String leaseOwner = request.operationId();
        while (true) {
            long observedVersion = legacyCapacity.availabilityVersion();
            acquireSlot(resourceLane);
            Optional<LegacyCapacityBridge.Permit> permit;
            try {
                permit = legacyCapacity.tryAcquire(
                        request, leaseOwner, stopOnLeaseLoss);
            }
            catch (RuntimeException e) {
                releaseSlot(resourceLane);
                throw e;
            }
            if (permit.isPresent()) {
                return permit.orElseThrow();
            }
            releaseSlot(resourceLane);
            synchronized (lock) {
                while (legacyCapacity.availabilityVersion() == observedVersion) {
                    lock.wait();
                }
            }
        }
    }

    private Optional<SharedSlot> tryAcquireSharedSlot(
            ThreadResourceLane resourceLane,
            CapacityManager.CapacityRequest request,
            Runnable stopOnLeaseLoss)
    {
        if (!tryAcquireSlot(resourceLane)) {
            return Optional.empty();
        }
        if (legacyCapacity == null) {
            return Optional.of(new SharedSlot(null));
        }
        try {
            return legacyCapacity.tryAcquire(
                            request, request.operationId(), stopOnLeaseLoss)
                    .map(SharedSlot::new)
                    .or(() -> {
                        releaseSlot(resourceLane);
                        return Optional.empty();
                    });
        }
        catch (RuntimeException e) {
            releaseSlot(resourceLane);
            throw e;
        }
    }

    public record ReviewWork<T>(
            CapacityManager.CapacityRequest request,
            Callable<T> work)
    {
        public ReviewWork
        {
            requireNonNull(request, "request is null");
            requireNonNull(work, "work is null");
        }
    }

    private record CapacityBoundWork<T>(
            ThreadResourceLane resourceLane,
            CapacityManager.CapacityRequest request,
            Callable<T> work)
    {
        private CapacityBoundWork
        {
            requireNonNull(resourceLane, "resourceLane is null");
            requireNonNull(work, "work is null");
        }
    }

    private record SharedSlot(LegacyCapacityBridge.Permit permit) {}

    private record PreparedWork<T>(
            CapacityBoundWork<T> work,
            SharedSlot slot,
            LossGuard guard)
    {
        private PreparedWork
        {
            requireNonNull(work, "work is null");
            requireNonNull(slot, "slot is null");
            requireNonNull(guard, "guard is null");
        }
    }

    private static final class LossGuard
    {
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicReference<java.lang.Thread> worker = new AtomicReference<>();

        private void bindCurrentWorker()
        {
            java.lang.Thread currentWorker = java.lang.Thread.currentThread();
            worker.set(currentWorker);
            if (lost.get()) {
                currentWorker.interrupt();
                throw new IllegalStateException("legacy capacity permit was lost before launch");
            }
        }

        private void clearCurrentWorker()
        {
            worker.set(null);
        }

        private void stop()
        {
            lost.set(true);
            java.lang.Thread currentWorker = worker.get();
            if (currentWorker != null) {
                currentWorker.interrupt();
            }
        }
    }

    private void releaseSharedSlot(
            ThreadResourceLane resourceLane,
            LegacyCapacityBridge.Permit permit)
    {
        try {
            if (permit != null) {
                permit.close();
            }
        }
        finally {
            releaseSlot(resourceLane);
        }
    }

    private static void requireLivePermit(LegacyCapacityBridge.Permit permit)
    {
        if (permit != null) {
            permit.lease();
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

    private boolean tryAcquireSlot(ThreadResourceLane resourceLane)
    {
        synchronized (lock) {
            LaneState lane = lane(resourceLane);
            if (lane.running >= lane.maxRunning) {
                return false;
            }
            lane.running++;
            return true;
        }
    }

    private void releaseSlot(ThreadResourceLane resourceLane)
    {
        synchronized (lock) {
            LaneState lane = lane(resourceLane);
            lane.running = Math.max(0, lane.running - 1);
            lock.notifyAll();
        }
        drain();
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

                for (ThreadTurn turn : queuedTurns) {
                    String reason = "cancelled by thread lifecycle action";
                    TurnTransition stopped = transitionTurn(
                            turn, CANCELLED, turn.startedAt(), Instant.now(),
                            reason, false);
                    if (stopped.applied()) {
                        appendEvent(stopped.turn(), TURN_CANCELLED, reason);
                        cancelled++;
                    }
                }
            }
            // Each read returns at most TURN_CANCELLATION_PAGE_SIZE rows.
            // A full page means there may be more queued rows after this
            // page was marked CANCELLED, so fetch the next page.
            while (queuedTurns.size() == TURN_CANCELLATION_PAGE_SIZE);
        }
        drain();
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
            for (ThreadTurn turn : sessionTurns) {
                if (turn.status() != QUEUED) {
                    continue;
                }
                String reason = "cancelled by session control";
                TurnTransition stopped = transitionTurn(
                        turn, CANCELLED, turn.startedAt(), Instant.now(), reason, false);
                if (stopped.applied()) {
                    appendEvent(stopped.turn(), TURN_CANCELLED, reason);
                    cancelled++;
                }
            }
            for (ThreadTurn turn : sessionTurns) {
                if (turn.status() != RUNNING || cancelReasonsByTurnId.containsKey(turn.id())) {
                    continue;
                }
                Optional<ThreadAgent> session = findRunningSession(turn);
                if (session.isEmpty()) {
                    if (cancelOrphanedRunningTurnLocked(turn, "cancelled by session control")) {
                        cancelled++;
                    }
                    continue;
                }
                cancelReasonsByTurnId.put(turn.id(), "cancelled by session control");
                runningSessions.add(session.orElseThrow());
                cancelled++;
            }
        }
        drain();
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
        String reason = "cancelled by task lifecycle action";
        int cancelled = 0;
        List<ThreadAgent> runningSessions = new ArrayList<>();
        Set<String> schedulerOwnedRuns = new HashSet<>();
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
                for (ThreadTurn turn : queuedTurns) {
                    TurnTransition stopped = transitionTurn(
                            turn, CANCELLED, turn.startedAt(), Instant.now(), reason, false);
                    if (stopped.applied()) {
                        appendEvent(stopped.turn(), TURN_CANCELLED, reason);
                        collectSchedulerOwnedRun(stopped.turn(), schedulerOwnedRuns);
                        cancelled++;
                    }
                }
            }
            while (queuedTurns.size() == TURN_CANCELLATION_PAGE_SIZE);

            for (ThreadTurn turn : turns.listTurnsByExactTaskIdAndStatus(
                    taskId, RUNNING, TURN_CANCELLATION_PAGE_SIZE)) {
                if (cancelReasonsByTurnId.containsKey(turn.id())) {
                    continue;
                }
                Optional<ThreadAgent> session = findRunningSession(turn);
                if (session.isEmpty()) {
                    if (cancelOrphanedRunningTurnLocked(turn, reason)) {
                        collectSchedulerOwnedRun(turn, schedulerOwnedRuns);
                        cancelled++;
                    }
                    continue;
                }
                cancelReasonsByTurnId.put(turn.id(), reason);
                collectSchedulerOwnedRun(turn, schedulerOwnedRuns);
                runningSessions.add(session.orElseThrow());
                cancelled++;
            }
        }
        // Run status is outside the scheduler monitor: AgentRunService enters
        // the task command for task-owned runs. Coordinator commands retain
        // their PAUSED/gated/terminal status; only standalone session runs are
        // scheduler-owned here.
        schedulerOwnedRuns.forEach(runId ->
                transitionRun(runId, AgentRun.STATUS_CANCELLED, reason));
        drain();
        runningSessions.stream().distinct().forEach(ThreadAgent::interrupt);
        return cancelled;
    }

    private static void collectSchedulerOwnedRun(ThreadTurn turn, Set<String> runIds)
    {
        if (!coordinatorOwnsRunCompletion(turn)
                && turn.agentRunId() != null
                && !turn.agentRunId().isBlank()) {
            runIds.add(turn.agentRunId());
        }
    }

    private Optional<ThreadAgent> findRunningSession(ThreadTurn turn)
    {
        return Optional.ofNullable(runningTurnSessions.get(turn.id()));
    }

    /** A durable RUNNING row can survive a process/session teardown even
     *  though no provider remains to invoke completeTurn. Close it here so
     *  cancellation cannot leave an immortal RUNNING row. */
    private boolean cancelOrphanedRunningTurnLocked(ThreadTurn turn, String reason)
    {
        TurnTransition stopped = transitionTurn(
                turn, CANCELLED, turn.startedAt(), Instant.now(), reason, false);
        if (stopped.applied()) {
            appendEvent(stopped.turn(), TURN_CANCELLED, reason);
        }
        cancelReasonsByTurnId.remove(turn.id());
        runningTurnSessions.remove(turn.id());
        usageBaselines.remove(turn.id());
        headBaselines.remove(turn.id());
        activeContexts.remove(
                turn.threadId(),
                PermissionResolver.agentKeyFor(turn.scope(), turn.taskId()));
        releaseRecoveredCapacity(turn);

        // No provider entry means this durable orphan owns no local lane slot
        // or agent-key gate, but it may own a durable shared-capacity lease
        // left before registry/session publication.
        return stopped.applied();
    }

    /**
     * Replays durable queued turns after backend startup. Orphaned
     * RUNNING turns are downgraded to QUEUED because their local
     * process/coroutine died with the previous backend.
     */
    @Order(20)
    @EventListener(ApplicationReadyEvent.class)
    public void recoverQueuedTurns()
    {
        recoverInterruptedRunningTurns();
        recoverQueuedTurnsFromStore();
    }

    private void recoverInterruptedRunningTurns()
    {
        recoverTurns(RUNNING, turn -> {
            ThreadTurn recovered = recoverInterruptedTurn(turn);
            if (recovered != null && recovered.status() == QUEUED) {
                publishTurnStatus(recovered);
                enqueuePersistedTurn(recovered);
            }
        });
    }

    /** Recover a startup orphan atomically with its owning AgentRun. A
     *  paused/gated/terminal owner wins and only the stale turn is closed. */
    ThreadTurn recoverInterruptedTurn(ThreadTurn snapshot)
    {
        try {
            releaseRecoveredCapacity(snapshot);
            if (snapshot.scope() != ThreadScope.TRUNK && taskCommands != null) {
                return taskCommands.execute(
                        snapshot.requireTaskId(), () -> recoverInterruptedTurnInTransaction(snapshot.id()));
            }
            if (snapshot.scope() == ThreadScope.TRUNK && detachedTransactions != null) {
                return detachedTransactions.execute(
                        ignored -> recoverInterruptedTurnInTransaction(snapshot.id()));
            }
            // Dependency-light unit tests still exercise legality/CAS; the
            // production constructor always supplies both transaction paths.
            TurnTransition recovered = transitionTurn(
                    snapshot, QUEUED, null, null,
                    "interrupted by app restart", false);
            if (recovered.applied()) {
                appendEvent(recovered.turn(), TURN_QUEUED, "interrupted by app restart");
            }
            return recovered.turn();
        }
        catch (RuntimeException e) {
            ThreadTurn current = turns.findTurnById(snapshot.id()).orElse(snapshot);
            recordSchedulerAlert(current,
                    "startup recovery failed: " + safeMessage(e));
            return current;
        }
    }

    private ThreadTurn recoverInterruptedTurnInTransaction(String turnId)
    {
        ThreadTurn turn = turns.findTurnById(turnId).orElse(null);
        if (turn == null || turn.status() != RUNNING) {
            return turn;
        }
        String stopped = stoppedTaskReason(turn);
        if (stopped != null) {
            return transitionRecoveredTurn(turn, CANCELLED, stopped);
        }
        if (turn.agentRunId() != null && agentRunStore != null) {
            AgentRun run = agentRunStore.findById(turn.agentRunId()).orElse(null);
            if (run == null) {
                String reason = "startup recovery found no owning AgentRun";
                recordSchedulerAlert(turn, reason);
                return transitionRecoveredTurn(turn, CANCELLED, reason);
            }
            if (AgentRun.STATUS_RUNNING.equals(run.status())) {
                if (!agentRunStore.updateStatusIf(
                        run.id(), AgentRun.STATUS_RUNNING, AgentRun.STATUS_QUEUED, null)) {
                    throw new IllegalStateException(
                            "owning AgentRun changed while turn recovery was requeueing it");
                }
            }
            else if (!AgentRun.STATUS_QUEUED.equals(run.status())) {
                String reason = "startup recovery left stale turn behind "
                        + run.status() + " AgentRun";
                recordSchedulerAlert(turn, reason);
                return transitionRecoveredTurn(turn, CANCELLED, reason);
            }
        }
        return transitionRecoveredTurn(
                turn, QUEUED, "interrupted by app restart");
    }

    private ThreadTurn transitionRecoveredTurn(
            ThreadTurn turn, ThreadTurnStatus to, String reason)
    {
        TURN_GRAPH.checkTransition(turn.id(), turn.status(), to);
        Instant now = Instant.now();
        Instant finishedAt = TURN_GRAPH.isTerminal(to) ? now : null;
        if (!turns.updateStatusIf(
                turn.id(), turn.status(), to, now,
                to == QUEUED ? null : turn.startedAt(), finishedAt, reason)) {
            throw new IllegalStateException(
                    "turn changed while startup recovery was moving it to " + to);
        }
        ThreadTurn changed = updateTurn(
                turn, to, now,
                to == QUEUED ? null : turn.startedAt(), finishedAt, reason);
        appendEvent(changed, to == QUEUED ? TURN_QUEUED : TURN_CANCELLED, reason);
        return changed;
    }

    private void recoverQueuedTurnsFromStore()
    {
        recoverTurns(QUEUED, turn -> {
            releaseRecoveredCapacity(turn);
            enqueuePersistedTurn(turn);
        });
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
        // afterCommit/recovery callbacks carry a snapshot. A lifecycle action
        // may have cancelled that row before this callback runs, so reload the
        // durable authority before queueing. Stopped-task cancellation may
        // enter a task command for the run projection and must stay outside
        // the scheduler monitor.
        ThreadTurn persisted = turns.findTurnById(turn.id()).orElse(null);
        if (persisted == null || persisted.status() != QUEUED
                || cancelIfTaskStopped(persisted)) {
            return;
        }
        // Startup recovery can rediscover a durable attended steer whose
        // predecessor failed before this process began. Repair the same
        // liveness pointer that a fresh enqueue would repair before asking
        // the lane to dispatch it.
        if (persisted.taskId() != null && turns.turnAffectsTaskLiveness(persisted.id())) {
            engageLivenessPointer(persisted);
        }
        boolean enqueued;
        synchronized (lock) {
            LaneState lane = lane(persisted.lane());
            enqueued = lane.knownTurnIds.add(persisted.id());
            if (enqueued) {
                lane.queue.addLast(persisted);
            }
        }
        drain();
        synchronized (lock) {
            LaneState lane = lane(persisted.lane());
            if (enqueued && lane.knownTurnIds.contains(persisted.id())) {
                appendEvent(persisted, WAITING_FOR_CAPACITY, waitingReason(persisted, lane));
            }
        }
    }

    /** Reserve eligible lane slots under the monitor, then perform durable
     * task-command admission and provider launch after releasing it. This
     * keeps scheduler-lock -> task-command inversion impossible while
     * preserving synchronous enqueue semantics for callers. */
    private void drain()
    {
        synchronized (lock) {
            if (draining) {
                drainRequested = true;
                return;
            }
            draining = true;
        }
        try {
            while (true) {
                List<ThreadTurn> ready = new ArrayList<>();
                synchronized (lock) {
                    drainRequested = false;
                    for (LaneState lane : lanes.values()) {
                        while (lane.running < lane.maxRunning) {
                            Optional<ThreadTurn> maybeTurn = pollNextEligible(lane);
                            if (maybeTurn.isEmpty()) {
                                break;
                            }
                            ThreadTurn turn = maybeTurn.get();
                            lane.running++;
                            runningAgentKeys.add(agentKeyOf(turn));
                            ready.add(turn);
                        }
                    }
                }
                ready.forEach(this::dispatch);
                synchronized (lock) {
                    if (!drainRequested) {
                        draining = false;
                        return;
                    }
                }
            }
        }
        finally {
            synchronized (lock) {
                draining = false;
            }
        }
    }

    private Optional<ThreadTurn> pollNextEligible(LaneState lane)
    {
        Iterator<ThreadTurn> iterator = lane.queue.iterator();
        while (iterator.hasNext()) {
            ThreadTurn turn = iterator.next();
            Long deniedVersion = capacityDeniedAtVersion.get(turn.id());
            if (legacyCapacity != null
                    && deniedVersion != null
                    && deniedVersion == legacyCapacity.availabilityVersion()) {
                continue;
            }
            if (runningAgentKeys.contains(agentKeyOf(turn))) {
                continue;
            }
            // A runtime turn dispatches only while it holds the task's
            // liveness pointer — queued followers stay durable and deferred
            // behind the current turn until the projection promotes them.
            if (turn.scope() != ThreadScope.TRUNK
                    && turns.turnAffectsTaskLiveness(turn.id())
                    && !turn.id().equals(tasks.currentLivenessTurnId(
                            turn.requireTaskId()).orElse(null))) {
                continue;
            }
            iterator.remove();
            lane.knownTurnIds.remove(turn.id());
            capacityDeniedAtVersion.remove(turn.id());
            ThreadTurn persisted = turns.findTurnById(turn.id()).orElse(null);
            if (persisted == null || persisted.status() != QUEUED) {
                continue;
            }
            return Optional.of(persisted);
        }
        return Optional.empty();
    }

    private boolean cancelIfTaskStopped(ThreadTurn turn)
    {
        String reason = stoppedTaskReason(turn);
        if (reason == null) {
            return false;
        }
        TurnTransition cancelled = transitionTurn(
                turn, CANCELLED, turn.startedAt(), Instant.now(), reason, false);
        if (!cancelled.applied()) {
            return cancelled.turn().status() == CANCELLED;
        }
        appendEvent(cancelled.turn(), TURN_CANCELLED, reason);
        // Coordinator commands own their AgentRun state. The scheduler only
        // terminalizes the queued turn when a task stop wins the race.
        if (!coordinatorOwnsRunCompletion(cancelled.turn())) {
            transitionRun(
                    cancelled.turn().agentRunId(), AgentRun.STATUS_CANCELLED, reason);
        }
        return true;
    }

    private String stoppedTaskReason(ThreadTurn turn)
    {
        if (turn.scope() == ThreadScope.TRUNK) {
            return null; // Trunk work is not governed by a Task row.
        }
        Task task = tasks.findTaskById(turn.requireTaskId()).orElse(null);
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

    private void removeQueuedTurns(LaneState lane, Set<String> turnIds)
    {
        Iterator<ThreadTurn> iterator = lane.queue.iterator();
        while (iterator.hasNext()) {
            ThreadTurn turn = iterator.next();
            if (turnIds.contains(turn.id())) {
                iterator.remove();
                lane.knownTurnIds.remove(turn.id());
                capacityDeniedAtVersion.remove(turn.id());
            }
        }
    }

    private void dispatch(ThreadTurn queuedTurn)
    {
        if (!acquireTurnCapacity(queuedTurn)) {
            return;
        }
        ThreadTurn runningTurn;
        try {
            runningTurn = admitDispatch(queuedTurn);
        }
        catch (RuntimeException e) {
            recordSchedulerAlert(queuedTurn,
                    "dispatch admission failed: " + safeMessage(e));
            publishTaskSchedulerConflict(
                    queuedTurn, "dispatch admission failed: " + safeMessage(e));
            releaseDispatchReservation(queuedTurn, false);
            return;
        }
        if (runningTurn == null || runningTurn.status() != RUNNING) {
            releaseDispatchReservation(
                    queuedTurn, runningTurn != null && runningTurn.status() == QUEUED);
            return;
        }
        publishTurnStatus(runningTurn);
        String capacityFailure = capacityFailure(runningTurn.id());
        if (capacityFailure != null) {
            completeTurn(runningTurn, null, new IllegalStateException(capacityFailure));
            return;
        }

        Thread thread = threads.findThreadById(runningTurn.threadId()).orElse(null);
        if (thread == null) {
            completeTurn(runningTurn, null, new NoSuchElementException("no thread: " + runningTurn.threadId()));
            return;
        }

        ThreadAgent session;
        try {
            // ThreadTurn.scope is the altitude authority. IDs are read only
            // through its checked accessors; nullable columns are payload,
            // never a discriminator. A stage selects the transcript scope,
            // while the provider session remains owned by the Task.
            if (thread.kind() == ThreadKind.BRAIN_AGENT) {
                runningTurn.requireTaskId();
                session = sessions.getOrCreateTaskBrainAgent(thread);
            }
            else {
                session = switch (runningTurn.scope()) {
                    case TRUNK -> sessions.getOrCreateTrunkAgent(thread);
                    case TASK -> {
                        Task task = tasks.findTaskById(runningTurn.requireTaskId())
                                .orElseThrow(() -> new NoSuchElementException(
                                        "no task: " + runningTurn.requireTaskId()));
                        yield sessions.getOrCreateTaskAgent(thread, task);
                    }
                    case STAGE -> {
                        Task task = tasks.findTaskById(runningTurn.requireTaskId())
                                .orElseThrow(() -> new NoSuchElementException(
                                        "no task: " + runningTurn.requireTaskId()));
                        yield sessions.getOrCreateTaskAgent(
                                thread, task, runningTurn.requireStageId());
                    }
                };
            }
        }
        catch (RuntimeException e) {
            completeTurn(runningTurn, null, e);
            return;
        }
        runningTurnSessions.put(runningTurn.id(), session);
        capacityFailure = capacityFailure(runningTurn.id());
        if (capacityFailure != null) {
            completeTurn(runningTurn, session, new IllegalStateException(capacityFailure));
            return;
        }

        CompletionStage<Void> completion;
        try {
            // A task brain reuses one read-only provider session across its
            // ordinary conversation (trunk key) and stage-scoped review
            // turns (PlanStage / review-stage key). Point the MCP bridge at
            // this turn's active context before spawning the provider.
            session.setMcpAgentKey(PermissionResolver.agentKeyFor(
                    runningTurn.scope(), runningTurn.taskId()));
            session.setActiveTask(runningTurn.taskId());
            // Tell the session which stage this turn runs under so the
            // messages it emits inherit an explicit stage_id.
            session.setActiveStage(runningTurn.stageId());
            session.setActiveScope(runningTurn.scope());
            session.setActiveAgentRun(runningTurn.agentRunId());
            ResolvedAgentContext context = contextCompiler.resolve(
                    thread.kind(), runningTurn, stageType(runningTurn.stageId()), session.workingDir());
            session.setResolvedAgentContext(context);
            activeContexts.put(
                    runningTurn.threadId(),
                    PermissionResolver.agentKeyFor(runningTurn.scope(), runningTurn.taskId()),
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

    private boolean acquireTurnCapacity(ThreadTurn queuedTurn)
    {
        if (legacyCapacity == null) {
            return true;
        }
        long observedVersion = legacyCapacity.availabilityVersion();
        Optional<LegacyCapacityBridge.Permit> permit;
        try {
            permit = legacyCapacity.tryAcquire(
                    legacyRequest(queuedTurn),
                    legacyTurnOwner(queuedTurn.id()),
                    () -> stopTurnAfterCapacityLoss(queuedTurn.id()));
        }
        catch (RuntimeException e) {
            recordSchedulerAlert(queuedTurn,
                    "shared capacity admission failed: " + safeMessage(e));
            deferForSharedCapacity(queuedTurn, observedVersion,
                    "shared capacity admission failed; waiting to retry");
            return false;
        }
        if (permit.isEmpty()) {
            deferForSharedCapacity(queuedTurn, observedVersion,
                    "waiting for shared "
                            + queuedTurn.lane().name().toLowerCase(Locale.ROOT)
                            + " capacity");
            return false;
        }
        LegacyCapacityBridge.Permit acquired = permit.orElseThrow();
        LegacyCapacityBridge.Permit existing = capacityPermitsByTurnId.putIfAbsent(
                queuedTurn.id(), acquired);
        if (existing != null) {
            acquired.close();
            throw new IllegalStateException(
                    "turn already owns shared capacity: " + queuedTurn.id());
        }
        return true;
    }

    private void deferForSharedCapacity(
            ThreadTurn turn,
            long observedVersion,
            String reason)
    {
        boolean requeued = false;
        synchronized (lock) {
            LaneState lane = lane(turn.lane());
            lane.running = Math.max(0, lane.running - 1);
            runningAgentKeys.remove(agentKeyOf(turn));
            ThreadTurn persisted = turns.findTurnById(turn.id()).orElse(null);
            if (persisted != null && persisted.status() == QUEUED
                    && lane.knownTurnIds.add(persisted.id())) {
                lane.queue.addLast(persisted);
                capacityDeniedAtVersion.put(persisted.id(), observedVersion);
                requeued = true;
            }
            lock.notifyAll();
        }
        if (requeued) {
            appendEvent(turn, WAITING_FOR_CAPACITY, reason);
        }
        // The guarded drain skips this version-denied turn, but may admit a
        // reserved Trunk-control turn or work in another scope/lane.
        drain();
    }

    private CapacityManager.CapacityRequest legacyRequest(ThreadTurn turn)
    {
        CapacityManager.CapacityLane capacityLane = capacityLane(turn.lane());
        String operationId = legacyTurnOperation(turn.id());
        if (turn.scope() == ThreadScope.TRUNK) {
            Thread trunk = threads.findThreadById(turn.threadId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "no trunk for capacity: " + turn.threadId()));
            return new CapacityManager.CapacityRequest(
                    operationId,
                    CapacityManager.WorkflowSource.LEGACY,
                    Set.of(capacityLane),
                    new CapacityManager.CapacityScope(
                            trunk.workspaceId(), trunk.id(), null, null),
                    true,
                    false,
                    false);
        }

        Task task = tasks.findTaskById(turn.requireTaskId())
                .orElseThrow(() -> new NoSuchElementException(
                        "no task for capacity: " + turn.requireTaskId()));
        Thread trunk = threads.findThreadById(task.threadId())
                .orElseThrow(() -> new NoSuchElementException(
                        "no trunk for capacity: " + task.threadId()));
        Thread runtimeThread = threads.findThreadById(turn.threadId()).orElse(trunk);
        boolean writer = runtimeThread.kind() != ThreadKind.BRAIN_AGENT;
        return new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(capacityLane),
                new CapacityManager.CapacityScope(
                        trunk.workspaceId(), trunk.id(), task.id(), LEGACY_TASK_EPOCH),
                false,
                true,
                writer);
    }

    private static CapacityManager.CapacityRequest unscopedLegacyRequest(
            String operationId,
            ThreadResourceLane resourceLane)
    {
        return new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(capacityLane(resourceLane)),
                new CapacityManager.CapacityScope(null, null, null, null),
                false,
                false,
                false);
    }

    private static CapacityManager.CapacityLane capacityLane(
            ThreadResourceLane resourceLane)
    {
        return switch (resourceLane) {
            case CLI -> CapacityManager.CapacityLane.CLI;
            case API -> CapacityManager.CapacityLane.API;
        };
    }

    private static String legacyTurnOperation(String turnId)
    {
        return "legacy-thread-turn:" + turnId;
    }

    private static String legacyTurnOwner(String turnId)
    {
        return "agent-scheduler:" + turnId;
    }

    private void stopTurnAfterCapacityLoss(String turnId)
    {
        ThreadAgent session;
        synchronized (lock) {
            ThreadTurn current = turns.findTurnById(turnId).orElse(null);
            if (current == null || (current.status() != QUEUED && current.status() != RUNNING)) {
                return;
            }
            capacityFailuresByTurnId.putIfAbsent(
                    turnId, "shared capacity lease was lost");
            session = runningTurnSessions.get(turnId);
        }
        if (session != null) {
            session.interrupt();
        }
    }

    private String capacityFailure(String turnId)
    {
        synchronized (lock) {
            return capacityFailuresByTurnId.get(turnId);
        }
    }

    /** The QUEUED -> RUNNING edge for a task-owned turn is one task command:
     * it rechecks stopped state and liveness authority, starts the turn, and
     * starts its AgentRun in the same transaction. Taskless trunk turns keep
     * their detached scheduler path. */
    private ThreadTurn admitDispatch(ThreadTurn snapshot)
    {
        if (snapshot.scope() != ThreadScope.TRUNK && taskCommands != null) {
            return taskCommands.execute(
                    snapshot.requireTaskId(), () -> admitTaskDispatchInCommand(snapshot.id()));
        }
        if (cancelIfTaskStopped(snapshot)) {
            return turns.findTurnById(snapshot.id()).orElse(snapshot);
        }
        TurnTransition started = transitionTurn(
                snapshot, RUNNING, Instant.now(),
                /* finishedAt */ null, /* errorMessage */ null, false);
        if (started.applied()) {
            appendEvent(started.turn(), TURN_STARTED, null);
            transitionRun(
                    started.turn().agentRunId(), AgentRun.STATUS_RUNNING, "scheduler started");
        }
        return started.turn();
    }

    private ThreadTurn admitTaskDispatchInCommand(String turnId)
    {
        ThreadTurn turn = turns.findTurnById(turnId).orElse(null);
        if (turn == null || turn.status() != QUEUED) {
            return turn;
        }
        String stoppedReason = stoppedTaskReason(turn);
        if (stoppedReason != null) {
            TurnTransition cancelled = transitionTurn(
                    turn, CANCELLED, turn.startedAt(), Instant.now(), stoppedReason, false);
            if (cancelled.applied()) {
                appendEvent(cancelled.turn(), TURN_CANCELLED, stoppedReason);
                if (!coordinatorOwnsRunCompletion(cancelled.turn())
                        && agentRuns != null
                        && cancelled.turn().agentRunId() != null) {
                    agentRuns.transitionInCommand(
                            turn.taskId(), cancelled.turn().agentRunId(),
                            AgentRun.STATUS_CANCELLED, stoppedReason);
                }
            }
            return cancelled.turn();
        }
        if (turns.turnAffectsTaskLiveness(turn.id())
                && !turn.id().equals(tasks.currentLivenessTurnId(turn.taskId()).orElse(null))) {
            return turn;
        }
        TurnTransition started = transitionTurn(
                turn, RUNNING, Instant.now(),
                /* finishedAt */ null, /* errorMessage */ null, false);
        if (!started.applied()) {
            return started.turn();
        }
        appendEvent(started.turn(), TURN_STARTED, null);
        if (agentRuns != null && started.turn().agentRunId() != null) {
            AgentRun run = agentRuns.transitionInCommand(
                    turn.taskId(), started.turn().agentRunId(),
                    AgentRun.STATUS_RUNNING, "scheduler started");
            if (run == null || !AgentRun.STATUS_RUNNING.equals(run.status())) {
                throw new IllegalStateException(
                        "AgentRun did not enter running with turn " + turn.id());
            }
        }
        return started.turn();
    }

    private void releaseDispatchReservation(ThreadTurn turn, boolean requeue)
    {
        synchronized (lock) {
            LaneState lane = lane(turn.lane());
            lane.running = Math.max(0, lane.running - 1);
            runningAgentKeys.remove(agentKeyOf(turn));
            lock.notifyAll();
        }
        releaseTurnCapacity(turn);
        if (requeue) {
            enqueuePersistedTurn(turn);
        }
        else {
            drain();
        }
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
        String providerErrorMessage = unwrapped != null
                ? unwrapped.getMessage()
                : (providerFailed && session != null ? session.lastErrorDetail() : null);
        boolean cancelled;
        boolean failed;
        boolean codeChanged;
        String cancelReason;
        String capacityFailure;
        ThreadTurn finished;
        TurnTransition completion;
        synchronized (lock) {
            // Keep the dispatched-session marker until the durable terminal
            // write. A concurrent cancel can therefore only (a) mark this
            // live turn for cancellation before this block, or (b) observe
            // its terminal row after this block — never misclassify the gap
            // between those two states as an orphan.
            cancelReason = cancelReasonsByTurnId.remove(runningTurn.id());
            capacityFailure = capacityFailuresByTurnId.remove(runningTurn.id());
            cancelled = cancelReason != null;
            failed = !cancelled && (providerFailed || capacityFailure != null);
            codeChanged = !cancelled && detectedCodeChanged;
            completion = transitionTurn(
                    runningTurn,
                    cancelled ? CANCELLED : failed ? FAILED : COMPLETED,
                    runningTurn.startedAt(), now,
                    cancelled ? cancelReason
                            : capacityFailure != null ? capacityFailure : providerErrorMessage,
                    true);
            finished = completion.turn();
            runningTurnSessions.remove(runningTurn.id());
        }
        if (completion.conflictReason() != null) {
            publishTaskSchedulerConflict(finished, completion.conflictReason());
        }
        cancelled = finished.status() == CANCELLED;
        failed = finished.status() == FAILED;
        codeChanged = completion.applied() && !completion.reconciled()
                && !cancelled && detectedCodeChanged;
        activeContexts.remove(
                runningTurn.threadId(),
                PermissionResolver.agentKeyFor(runningTurn.scope(), runningTurn.taskId()));
        if (completion.applied()) {
            appendEvent(
                    finished,
                    cancelled ? TURN_CANCELLED : failed ? TURN_FAILED : TURN_FINISHED,
                    finished.errorMessage());
            publishTurnStatus(finished);
        }
        CodeGraphFirstRuntime.Metrics codeGraphMetrics = CodeGraphFirstRuntime.finishTurn(
                runningTurn.threadId(), agentKeyOf(runningTurn));
        if (!codeGraphMetrics.isEmpty()) {
            appendEvent(finished, CODEGRAPH_POLICY, codeGraphMetrics.toJson());
        }
        // Persist the turn's usage before deciding whether the run may close.
        // A newly exhausted budget parks the run and suppresses the normal
        // success transition; cancelled turns still retain their usage.
        boolean budgetPaused = completion.applied() && accountRun(finished, session);
        // A Session can own several serialized turns. It stays queued while
        // another bound turn remains, rather than becoming terminal between
        // turns and then failing the next admission. Some coordinators also
        // keep their Session open after the last currently queued turn.
        if (completion.applied() && !cancelled && !budgetPaused) {
            if (finished.agentRunId() != null
                    && turns.hasOtherActiveTurn(finished.agentRunId(), finished.id())) {
                transitionRun(
                        finished.agentRunId(), AgentRun.STATUS_QUEUED,
                        "scheduler session continues");
            }
            else if (coordinatorOwnsRunCompletion(finished)) {
                // Multi-turn owners consume the terminal turn after this
                // checkpoint. QUEUED means the run owes its next domain step
                // (retry, validation, or gate); it is not complete yet.
                transitionRun(
                        finished.agentRunId(), AgentRun.STATUS_QUEUED,
                        "coordinator turn completed");
            }
            else {
                transitionRun(
                        finished.agentRunId(),
                        failed ? AgentRun.STATUS_FAILED : AgentRun.STATUS_SUCCEEDED,
                        failed ? finished.errorMessage() : "scheduler turn completed");
            }
        }

        synchronized (lock) {
            LaneState lane = lane(runningTurn.lane());
            lane.running = Math.max(0, lane.running - 1);
            runningAgentKeys.remove(agentKeyOf(runningTurn));
            // Wake blocked invokeAll slot acquisitions — they share
            // the lane capacity with turns.
            lock.notifyAll();
        }
        releaseTurnCapacity(runningTurn);
        drain();

        // Outside the lock: let listeners react to a finished turn. Task
        // turns carry their taskId directly; a brain turn carries none, so
        // resolve its parent task so plan-stage listeners (the record_plan
        // nudge + failure surfacing) can react. Pure trunk turns stay
        // unlinked.
        if (completion.applied() && !cancelled && eventPublisher != null) {
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

    private void releaseTurnCapacity(ThreadTurn turn)
    {
        LegacyCapacityBridge.Permit permit = capacityPermitsByTurnId.remove(turn.id());
        synchronized (lock) {
            capacityFailuresByTurnId.remove(turn.id());
        }
        if (permit == null) {
            return;
        }
        try {
            permit.close();
        }
        catch (RuntimeException e) {
            recordSchedulerAlert(turn,
                    "shared capacity release failed: " + safeMessage(e));
        }
    }

    private void releaseRecoveredCapacity(ThreadTurn turn)
    {
        if (legacyCapacity == null) {
            return;
        }
        LegacyCapacityBridge.Permit permit = capacityPermitsByTurnId.remove(turn.id());
        try {
            if (permit != null) {
                permit.close();
            }
            else {
                legacyCapacity.releaseOperation(
                        legacyTurnOperation(turn.id()),
                        legacyTurnOwner(turn.id()));
            }
        }
        catch (RuntimeException e) {
            // The bridge retains an exact pending release for its maintenance
            // tick. Until then the old lease still blocks new admission.
            recordSchedulerAlert(turn,
                    "shared capacity recovery release failed: " + safeMessage(e));
        }
        synchronized (lock) {
            capacityFailuresByTurnId.remove(turn.id());
        }
    }

    /** Records the worktree HEAD at dispatch so completeTurn can tell whether
     *  the round moved it. Task turns only; best-effort (a missing baseline
     *  just means the round won't be classified as code-changed). */
    private void captureHeadBaseline(ThreadTurn turn, ThreadAgent session)
    {
        if (git == null || turn.scope() == ThreadScope.TRUNK) {
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

    private String sessionKind(Thread thread, String stageId, ThreadScope scope)
    {
        if (thread.flow() == ThreadFlow.REVIEW) {
            return AgentRun.KIND_REVIEW;
        }
        StageType type = scope == ThreadScope.STAGE ? stageType(stageId) : null;
        if (type == StageType.CI_FIXING_STAGE) {
            return AgentRun.KIND_CI_FIX;
        }
        if (type == StageType.PLAN_STAGE || thread.kind() == ThreadKind.BRAIN_AGENT
                || scope != ThreadScope.STAGE) {
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
            case "address-local-comments", "local-ci-fix", "ci-fix-shipped",
                    "review-round", "brain-review", "brain-review-fix",
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
     * same key {@link ThreadRegistry} uses to find that agent. Stage scope
     * does not create a second provider session: every turn for one Task
     * serializes on that Task's key.
     */
    private String agentKeyOf(ThreadTurn turn)
    {
        return turn.runtimeAgentKey();
    }

    /** The lane a turn belongs on, resolved at its actual trunk/task/stage
     *  audience through the same path that builds the runtime. */
    ThreadResourceLane laneFor(
            Thread thread, String taskId, String stageId, ThreadScope scope)
    {
        Task task = switch (scope) {
            case TRUNK -> null;
            case TASK, STAGE -> tasks.findTaskById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("no task " + taskId));
        };
        WorkModel resolved = sessions.resolvedWorkModelForTurn(thread, task, stageId);
        if (resolved == null) {
            throw new IllegalStateException("no work model for " + scope + " turn");
        }
        return resolved.kind() == WorkModelKind.CLI ? CLI : API;
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

    /** Apply one legal turn edge through an expected-state compare-and-set.
     *  When provider work has already happened, a stale/illegal write is
     *  converted to a durable failure (when still live) and always leaves a
     *  visible scheduler alert. */
    private TurnTransition transitionTurn(
            ThreadTurn snapshot,
            ThreadTurnStatus to,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            boolean postHoc)
    {
        requireNonNull(snapshot, "snapshot is null");
        requireNonNull(to, "to is null");
        try {
            if (!TURN_GRAPH.checkTransition(snapshot.id(), snapshot.status(), to)) {
                return new TurnTransition(snapshot, false, false, null);
            }
        }
        catch (RuntimeException e) {
            if (!postHoc) {
                recordSchedulerAlert(snapshot,
                        "illegal turn transition " + snapshot.status() + " -> " + to);
                return new TurnTransition(snapshot, false, false, null);
            }
            return reconcilePostHocTransition(snapshot, to, e.getMessage());
        }

        Instant updatedAt = Instant.now();
        if (turns.updateStatusIf(
                snapshot.id(), snapshot.status(), to, updatedAt,
                startedAt, finishedAt, errorMessage)) {
            return new TurnTransition(updateTurn(
                    snapshot, to, updatedAt, startedAt, finishedAt, errorMessage), true, false, null);
        }

        ThreadTurn current = turns.findTurnById(snapshot.id()).orElse(snapshot);
        if (current.status() == to) {
            return new TurnTransition(current, false, false, null);
        }
        if (postHoc) {
            return reconcilePostHocTransition(
                    current, to,
                    "turn changed from " + snapshot.status() + " to " + current.status());
        }
        recordSchedulerAlert(current,
                "turn changed before " + snapshot.status() + " -> " + to);
        return new TurnTransition(current, false, false, null);
    }

    private TurnTransition reconcilePostHocTransition(
            ThreadTurn current, ThreadTurnStatus intended, String detail)
    {
        String reason = "post-hoc scheduler conflict while recording "
                + intended + ": " + detail;
        recordSchedulerAlert(current, reason);
        if (TURN_GRAPH.isTerminal(current.status())) {
            return new TurnTransition(current, false, true, reason);
        }
        Instant now = Instant.now();
        if (TURN_GRAPH.isLegal(current.status(), FAILED)
                && turns.updateStatusIf(
                        current.id(), current.status(), FAILED, now,
                        current.startedAt(), now, reason)) {
            ThreadTurn failed = updateTurn(
                    current, FAILED, now, current.startedAt(), now, reason);
            return new TurnTransition(failed, true, true, reason);
        }
        ThreadTurn reloaded = turns.findTurnById(current.id()).orElse(current);
        recordSchedulerAlert(reloaded,
                reason + "; controlled failure CAS also lost");
        return new TurnTransition(reloaded, false, true, reason);
    }

    private void recordSchedulerAlert(ThreadTurn turn, String message)
    {
        appendEvent(turn, SCHEDULER_ALERT, message);
    }

    private void publishTaskSchedulerConflict(ThreadTurn turn, String reason)
    {
        if (eventPublisher == null || turn.scope() == ThreadScope.TRUNK) {
            return;
        }
        TaskSchedulerConflictEvent conflict =
                new TaskSchedulerConflictEvent(turn.requireTaskId(), turn.id(), reason);
        if (!deferUntilAfterCommit(() -> eventPublisher.publishEvent(conflict))) {
            eventPublisher.publishEvent(conflict);
        }
    }

    private static ThreadTurn updateTurn(
            ThreadTurn turn,
            ThreadTurnStatus status,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage)
    {
        return new ThreadTurn(
                turn.id(),
                turn.threadId(),
                turn.taskId(),
                turn.lane(),
                status,
                turn.input(),
                turn.createdAt(),
                updatedAt,
                startedAt,
                finishedAt,
                errorMessage,
                turn.initiator(),
                // Preserve the stamped stage id + scope across status
                // updates so transcript attribution and permission role stay
                // attached to the authoritative turn.
                turn.stageId(),
                turn.scope(),
                turn.agentRunId());
    }

    private record TurnTransition(
            ThreadTurn turn, boolean applied, boolean reconciled, String conflictReason) {}

    private static Throwable unwrap(Throwable failure)
    {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static String safeMessage(Throwable failure)
    {
        String message = failure == null ? null : failure.getMessage();
        return message == null || message.isBlank()
                ? failure == null ? "unknown failure" : failure.getClass().getSimpleName()
                : message;
    }

    private static int checkedLimit(int value, String name)
    {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int sharedLimit(int value, int expected, String name)
    {
        checkedLimit(value, name);
        if (value != expected) {
            throw new IllegalArgumentException(
                    name + " must match the shared CapacityManager ceiling " + expected);
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
