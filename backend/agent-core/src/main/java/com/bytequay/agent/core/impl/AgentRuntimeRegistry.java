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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.DevelopmentTurnCompletion;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.RunningTurn;
import com.google.common.collect.ImmutableList;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Tracks provider sessions and live turn handles owned by this backend process.
 *
 * <p>Durable identity remains in the task and turn stores. An empty registry
 * after restart causes the next turn to resume from the persisted session
 * reference rather than treating this in-memory state as authoritative.
 */
public final class AgentRuntimeRegistry
        implements AutoCloseable
{
    private static final Duration IDLE_RETENTION = Duration.ofMinutes(5);
    private static final int MAXIMUM_IDLE_SESSIONS = 4;
    private final ConcurrentMap<TaskId, AgentSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, Optional<TaskWorktree>> sessionWorktrees = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, AgentSession> retiring = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, IdleSession> idleSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<TurnId, LiveTurn> runningTurns = new ConcurrentHashMap<>();
    // One monitor per worktree owner for the life of the process: unrelated worktrees must not wait on each
    // other's provider launch. ponytail: never evicted, which is a few bytes per task this backend has seen.
    private final ConcurrentMap<TaskId, Object> taskLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, Object> steerLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, Object> stopLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, TurnId> latestAcceptedTurns = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, RuntimeException> unverifiedCloses = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, DevelopmentOwner> developmentOwners = new ConcurrentHashMap<>();
    private final Set<TaskId> stoppedDevelopmentTasks = ConcurrentHashMap.newKeySet();
    private final Consumer<AgentTask> branchAdmission;
    private final Predicate<AgentTask> heldBetweenTurns;
    private final AgentTaskStore taskStore;
    private final boolean processReuse;
    private volatile boolean closing;

    /**
     * @param heldBetweenTurns whether a task keeps its shared working copy between its own turns, as a review does while its
     *     run moves from one step to the next; another task sharing that copy must not start in the gap
     */
    public AgentRuntimeRegistry(AgentTaskStore taskStore, Consumer<AgentTask> branchAdmission, Predicate<AgentTask> heldBetweenTurns, boolean processReuse)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.branchAdmission = requireNonNull(branchAdmission, "branchAdmission is null");
        this.heldBetweenTurns = requireNonNull(heldBetweenTurns, "heldBetweenTurns is null");
        this.processReuse = processReuse;
    }

    void checkBranchAdmission(AgentTask task)
    {
        requireNotRetiring(task.id());
        for (AgentTask other : sharedTasks(task)) {
            if (other.worktreeMutationClaim().isPresent() || other.developmentContinuationUnfinished() || hasDevelopmentOwner(other.id())
                    || unverifiedClose(other.id()).isPresent() || heldBetweenTurns.test(other) || retained(other.id()) || retiring.containsKey(other.id())) {
                throw new AgentConflictException("The shared working copy is in use by " + other.title() + "; wait for it to finish or stop it first");
            }
        }
        branchAdmission.accept(task);
        task.taskWorktree().filter(worktree -> !task.ownsWorktree()).map(TaskWorktree::taskId).flatMap(taskStore::find).ifPresent(branchAdmission);
    }

    void requireNoWorktreeBorrowers(AgentTask task)
    {
        if (task.ownsWorktree() && sharedTasks(task).stream().anyMatch(other -> !other.state().terminal())) {
            throw new AgentConflictException("End the review before removing the development working copy");
        }
    }

    private List<AgentTask> sharedTasks(AgentTask task)
    {
        return task.workspaceId().map(taskStore::listByWorkspace).orElseGet(ImmutableList::of).stream()
                .filter(other -> !other.id().equals(task.id()) && other.worktreeRemovedAt().isEmpty()
                        && other.taskWorktree().map(TaskWorktree::taskId).equals(task.taskWorktree().map(TaskWorktree::taskId)))
                .filter(other -> other.taskWorktree().isPresent()).collect(toImmutableList());
    }

    boolean closing()
    {
        return closing;
    }

    List<CompletableFuture<DevelopmentTurnCompletion>> stopDevelopment(TaskId taskId)
    {
        stoppedDevelopmentTasks.add(taskId);
        DevelopmentOwner owner = developmentOwners.get(taskId);
        return owner == null ? ImmutableList.of() : ImmutableList.copyOf(owner.turns().values());
    }

    /** Records causal acceptance order after commit; stored turn timestamps may be equal. */
    void recordAcceptedTurn(AgentTurn turn)
    {
        idleSessions.remove(turn.taskId());
        latestAcceptedTurns.put(turn.taskId(), turn.id());
        if (turn.developmentOrigin().isEmpty()) {
            stoppedDevelopmentTasks.remove(turn.taskId());
        }
    }

    Optional<TurnId> latestAcceptedTurn(TaskId taskId)
    {
        return Optional.ofNullable(latestAcceptedTurns.get(taskId));
    }

    boolean developmentStopped(TaskId taskId)
    {
        return stoppedDevelopmentTasks.contains(taskId);
    }

    public boolean hasDevelopmentOwner(TaskId taskId)
    {
        return developmentOwners.containsKey(requireNonNull(taskId, "taskId is null"));
    }

    public boolean ownsDevelopmentContinuation(TaskId taskId, TurnId sourceTurnId)
    {
        DevelopmentOwner owner = developmentOwners.get(requireNonNull(taskId, "taskId is null"));
        return !closing && !developmentStopped(taskId) && owner != null && owner.sourceTurnId().equals(requireNonNull(sourceTurnId, "sourceTurnId is null"));
    }

    void executeDevelopmentAction(TaskId taskId, TurnId sourceTurnId, Runnable action)
    {
        requireNonNull(action, "action is null");
        DevelopmentOwner owner = developmentOwners.get(taskId);
        if (owner == null) {
            throw new AgentConflictException("Development continuation is no longer active: " + taskId);
        }
        synchronized (owner) {
            if (developmentOwners.get(taskId) != owner || !ownsDevelopmentContinuation(taskId, sourceTurnId)) {
                throw new AgentConflictException("Development continuation is no longer active: " + taskId);
            }
            action.run();
        }
    }

    /** Must run after revocation and outside the task lock, which the admitted action may need. */
    void drainDevelopmentActions(TaskId taskId)
    {
        DevelopmentOwner owner = developmentOwners.get(taskId);
        if (owner != null) {
            synchronized (owner) {
                // Acquiring this monitor proves any admitted program action has returned.
            }
        }
    }

    void reserveDevelopmentOwner(TaskId taskId, TurnId sourceTurnId)
    {
        if (closing) {
            throw new AgentConflictException("Backend is shutting down");
        }
        if (developmentStopped(taskId)) {
            throw new AgentConflictException("Development continuation is stopped: " + taskId);
        }
        DevelopmentOwner owner = new DevelopmentOwner(sourceTurnId, new EnumMap<>(DevelopmentTurnOrigin.Kind.class));
        if (developmentOwners.putIfAbsent(taskId, owner) != null) {
            throw new AgentConflictException("Task already has a development continuation: " + taskId);
        }
        if (closing) {
            developmentOwners.remove(taskId, owner);
            throw new AgentConflictException("Backend is shutting down");
        }
    }

    Optional<CompletableFuture<DevelopmentTurnCompletion>> developmentTurn(TaskId taskId, DevelopmentTurnOrigin origin)
    {
        DevelopmentOwner owner = developmentOwners.get(taskId);
        return owner == null || !owner.sourceTurnId().equals(origin.parentUserTurnId()) ? Optional.empty() : Optional.ofNullable(owner.turns().get(origin.kind()));
    }

    CompletableFuture<DevelopmentTurnCompletion> registerDevelopmentTurn(TaskId taskId, DevelopmentTurnOrigin origin)
    {
        synchronized (taskLock(taskId)) {
            DevelopmentOwner owner = developmentOwners.get(taskId);
            if (closing || developmentStopped(taskId) || owner == null || !owner.sourceTurnId().equals(origin.parentUserTurnId())) {
                throw new AgentConflictException("Development continuation is no longer active: " + taskId);
            }
            return owner.turns().computeIfAbsent(origin.kind(), ignored -> new CompletableFuture<>());
        }
    }

    List<CompletableFuture<DevelopmentTurnCompletion>> releaseDevelopmentOwner(TaskId taskId, TurnId sourceTurnId)
    {
        DevelopmentOwner owner = developmentOwners.get(taskId);
        if (owner == null || !owner.sourceTurnId().equals(sourceTurnId)) {
            return ImmutableList.of();
        }
        developmentOwners.remove(taskId, owner);
        return ImmutableList.copyOf(owner.turns().values());
    }

    Optional<TurnId> developmentOwner(TaskId taskId)
    {
        return Optional.ofNullable(developmentOwners.get(taskId)).map(DevelopmentOwner::sourceTurnId);
    }

    /** Shared monitor for task admission and branch-writer reservations. */
    public Object taskLock(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        TaskId owner = taskStore.find(taskId).flatMap(AgentTask::taskWorktree).map(TaskWorktree::taskId).orElse(taskId);
        return taskLocks.computeIfAbsent(owner, ignored -> new Object());
    }

    /** Serializes provider steer requests without holding the task state lock while awaiting I/O. */
    Object steerLock(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return steerLocks.computeIfAbsent(taskId, ignored -> new Object());
    }

    /** Keeps overlapping shutdowns from reaching a newly opened session after another stop settles. */
    Object stopLock(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        // Parent shutdown nests child shutdown, so unrelated tasks must never share a monitor.
        return stopLocks.computeIfAbsent(taskId, ignored -> new Object());
    }

    public Optional<AgentSession> session(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return Optional.ofNullable(sessions.get(taskId));
    }

    public AgentSession registerSession(TaskId taskId, AgentSession session)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(session, "session is null");
        requireNotRetiring(taskId);
        AgentSession existing = sessions.putIfAbsent(taskId, session);
        if (existing != null) {
            session.close();
            if (closing) {
                throw new AgentConflictException("Backend is shutting down");
            }
            return existing;
        }
        sessionWorktrees.put(taskId, taskStore.find(taskId).flatMap(AgentTask::taskWorktree));
        // Registration may race the shutdown iterator. Keep the session tracked until its
        // process is verified stopped, including when this late close fails.
        if (closing) {
            closeSession(taskId);
            throw new AgentConflictException("Backend is shutting down");
        }
        return session;
    }

    /**
     * Closes a task's session, which stops the provider process it owns.
     *
     * <p>A close that fails is one whose process tree could not be verified stopped. The session
     * then stays registered so the close can be retried, and the task is recorded as refusing new
     * work until it succeeds: nothing may be handed a worktree that something may still be
     * writing to.
     */
    void closeSession(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        AgentSession session = sessions.get(taskId);
        if (session == null) {
            return;
        }
        closeSession(taskId, session);
    }

    void closeSession(TaskId taskId, AgentSession session)
    {
        synchronized (taskLock(taskId)) {
            if (sessions.get(taskId) != session) {
                return;
            }
            reserveRetirement(taskId, session);
        }
        closeReserved(taskId, session);
    }

    private void reserveRetirement(TaskId taskId, AgentSession session)
    {
        if (retiring.putIfAbsent(taskId, session) != null) {
            throw new AgentConflictException("Task provider process is stopping: " + taskId);
        }
        idleSessions.remove(taskId);
    }

    private void closeReserved(TaskId taskId, AgentSession session)
    {
        try {
            session.close();
            sessions.remove(taskId, session);
            sessionWorktrees.remove(taskId);
            unverifiedCloses.remove(taskId);
        }
        catch (RuntimeException failure) {
            unverifiedCloses.put(taskId, failure);
            throw failure;
        }
        finally {
            retiring.remove(taskId, session);
        }
    }

    boolean retained(TaskId taskId)
    {
        return session(taskId).map(AgentSession::retained).orElse(false);
    }

    boolean processReuse()
    {
        return processReuse;
    }

    boolean sameWorktree(AgentTask task)
    {
        return task.taskWorktree().equals(sessionWorktrees.get(task.id()));
    }

    public void requireStoppedSession(TaskId taskId)
    {
        requireNotRetiring(taskId);
        if (retained(taskId)) {
            throw new AgentConflictException("Task provider process must stop before changing its working copy: " + taskId);
        }
        unverifiedClose(taskId).ifPresent(failure -> {
            throw new AgentConflictException("Task provider process is not verified stopped: " + failure.getMessage());
        });
    }

    private void requireNotRetiring(TaskId taskId)
    {
        if (retiring.containsKey(taskId)) {
            throw new AgentConflictException("Task provider process is stopping: " + taskId);
        }
    }

    /** Reserves shutdown under the owner lock, but never waits for provider callbacks while holding it. */
    void retireIdleSessions(TaskId taskId, boolean includeOwn)
    {
        Map<TaskId, AgentSession> selected = new LinkedHashMap<>();
        synchronized (taskLock(taskId)) {
            AgentTask task = taskStore.require(taskId);
            List<AgentTask> candidates = ImmutableList.<AgentTask>builder().addAll(sharedTasks(task)).add(task).build();
            for (AgentTask candidate : candidates) {
                if ((!includeOwn && candidate.id().equals(taskId)) || candidate.worktreeMutationClaim().isPresent()) {
                    continue;
                }
                AgentSession session = sessions.get(candidate.id());
                if (session != null && session.retained()) {
                    requireNotRetiring(candidate.id());
                    selected.put(candidate.id(), session);
                }
            }
            selected.forEach(this::reserveRetirement);
        }
        RuntimeException failure = null;
        for (Map.Entry<TaskId, AgentSession> entry : selected.entrySet()) {
            try {
                closeReserved(entry.getKey(), entry.getValue());
            }
            catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                }
                else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    void retainFinishedSession(TaskId taskId)
    {
        IdleSession idle;
        synchronized (taskLock(taskId)) {
            AgentSession session = sessions.get(taskId);
            if (session == null || !session.retained() || taskStore.find(taskId).filter(task -> task.worktreeMutationClaim().isEmpty()).isEmpty() || closing) {
                return;
            }
            idle = new IdleSession(session, Instant.now());
            idleSessions.put(taskId, idle);
        }
        CompletableFuture.delayedExecutor(IDLE_RETENTION.toMillis(), TimeUnit.MILLISECONDS).execute(() -> expire(taskId, idle));
        idleSessions.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.comparing(IdleSession::since)))
                .limit(Math.max(0, idleSessions.size() - MAXIMUM_IDLE_SESSIONS))
                .forEach(entry -> CompletableFuture.runAsync(() -> expire(entry.getKey(), entry.getValue())));
    }

    private void expire(TaskId taskId, IdleSession expected)
    {
        synchronized (taskLock(taskId)) {
            if (idleSessions.get(taskId) != expected || sessions.get(taskId) != expected.session()
                    || taskStore.find(taskId).filter(task -> task.worktreeMutationClaim().isEmpty()).isEmpty() || retiring.containsKey(taskId)) {
                return;
            }
            reserveRetirement(taskId, expected.session());
        }
        try {
            closeReserved(taskId, expected.session());
        }
        catch (RuntimeException ignored) {
            // The retained session and unverified-close record keep the worktree blocked for Stop/recovery.
        }
    }

    /** The failure that left a task's process unverified, while its last close has not succeeded. */
    Optional<RuntimeException> unverifiedClose(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return Optional.ofNullable(unverifiedCloses.get(taskId));
    }

    /**
     * Releases every session this backend still holds.
     *
     * <p>Idle sessions retain process ownership too. Shutdown must verify every tracked process
     * stopped, including tasks with no active turn.
     */
    @Override
    public void close()
    {
        closing = true;
        for (TaskId taskId : developmentOwners.keySet()) {
            drainDevelopmentActions(taskId);
            List<CompletableFuture<DevelopmentTurnCompletion>> pending;
            synchronized (taskLock(taskId)) {
                pending = developmentOwner(taskId).map(owner -> releaseDevelopmentOwner(taskId, owner)).orElseGet(ImmutableList::of);
            }
            pending.forEach(future -> future.completeExceptionally(new AgentConflictException("Backend shut down during development automation")));
        }
        RuntimeException firstFailure = null;
        for (TaskId taskId : sessions.keySet()) {
            try {
                closeSession(taskId);
            }
            catch (RuntimeException failure) {
                // One session that will not close must not strand the ones behind it, but the
                // failure still has to surface once every other session has been released.
                if (firstFailure == null) {
                    firstFailure = failure;
                }
                else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public void registerRunning(TaskId taskId, TurnId turnId, RunningTurn runningTurn)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(runningTurn, "runningTurn is null");
        if (runningTurns.putIfAbsent(turnId, new LiveTurn(taskId, runningTurn)) != null) {
            throw new AgentConflictException("Turn already has a live provider handle: " + turnId);
        }
    }

    public Optional<RunningTurn> running(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return Optional.ofNullable(runningTurns.get(turnId)).map(LiveTurn::handle);
    }

    /** The task's turns that still hold a live provider handle, including a settled one whose cleanup has to be retried. */
    public List<TurnId> runningTurns(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return runningTurns.entrySet().stream()
                .filter(entry -> entry.getValue().taskId().equals(taskId))
                .map(Map.Entry::getKey)
                .collect(toImmutableList());
    }

    public void removeRunning(TurnId turnId, RunningTurn runningTurn)
    {
        requireNonNull(turnId, "turnId is null");
        requireNonNull(runningTurn, "runningTurn is null");
        runningTurns.computeIfPresent(turnId, (ignored, live) -> live.handle() == runningTurn ? null : live);
    }

    private record LiveTurn(TaskId taskId, RunningTurn handle) {}

    private record IdleSession(AgentSession session, Instant since) {}

    private record DevelopmentOwner(TurnId sourceTurnId, Map<DevelopmentTurnOrigin.Kind, CompletableFuture<DevelopmentTurnCompletion>> turns) {}
}
