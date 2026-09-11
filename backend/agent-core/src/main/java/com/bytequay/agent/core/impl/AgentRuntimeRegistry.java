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
import com.bytequay.agent.core.DevelopmentTurnCompletion;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.RunningTurn;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;

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
    private final ConcurrentMap<TaskId, AgentSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<TurnId, RunningTurn> runningTurns = new ConcurrentHashMap<>();
    private final Striped<Lock> taskLocks = Striped.lock(64);
    private final Striped<Lock> steerLocks = Striped.lock(64);
    private final ConcurrentMap<TaskId, RuntimeException> unverifiedCloses = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, DevelopmentOwner> developmentOwners = new ConcurrentHashMap<>();
    private final Set<TaskId> stoppedDevelopmentTasks = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;

    boolean closing()
    {
        return closing;
    }

    void stopDevelopment(TaskId taskId)
    {
        stoppedDevelopmentTasks.add(taskId);
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
        return owner != null && owner.sourceTurnId().equals(requireNonNull(sourceTurnId, "sourceTurnId is null"));
    }

    void reserveDevelopmentOwner(TaskId taskId, TurnId sourceTurnId)
    {
        if (closing) {
            throw new AgentConflictException("Backend is shutting down");
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
        return owner == null || !owner.sourceTurnId().equals(origin.parentUserTurnId())
                ? Optional.empty()
                : Optional.ofNullable(owner.turns().get(origin.kind()));
    }

    CompletableFuture<DevelopmentTurnCompletion> registerDevelopmentTurn(TaskId taskId, DevelopmentTurnOrigin origin)
    {
        synchronized (taskLock(taskId)) {
            DevelopmentOwner owner = developmentOwners.get(taskId);
            if (closing || owner == null || !owner.sourceTurnId().equals(origin.parentUserTurnId())) {
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

    Object taskLock(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return taskLocks.get(taskId);
    }

    /** Serializes provider steer requests without holding the task state lock while awaiting I/O. */
    Object steerLock(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return steerLocks.get(taskId);
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
        AgentSession existing = sessions.putIfAbsent(taskId, session);
        if (existing != null) {
            session.close();
            if (closing) {
                throw new AgentConflictException("Backend is shutting down");
            }
            return existing;
        }
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
        try {
            session.close();
        }
        catch (RuntimeException failure) {
            unverifiedCloses.put(taskId, failure);
            throw failure;
        }
        sessions.remove(taskId, session);
        unverifiedCloses.remove(taskId);
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
     * <p>A session owns the provider process serving its task, and completing a task is the only
     * thing that closes one. Without this, shutting the backend down while any task is incomplete
     * would leave that task's process running with nothing left to reap it until the next start.
     */
    @Override
    public void close()
    {
        closing = true;
        for (TaskId taskId : developmentOwners.keySet()) {
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
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public void registerRunning(TurnId turnId, RunningTurn runningTurn)
    {
        requireNonNull(turnId, "turnId is null");
        requireNonNull(runningTurn, "runningTurn is null");
        if (runningTurns.putIfAbsent(turnId, runningTurn) != null) {
            throw new AgentConflictException("Turn already has a live provider handle: " + turnId);
        }
    }

    public Optional<RunningTurn> running(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return Optional.ofNullable(runningTurns.get(turnId));
    }

    public void removeRunning(TurnId turnId, RunningTurn runningTurn)
    {
        requireNonNull(turnId, "turnId is null");
        requireNonNull(runningTurn, "runningTurn is null");
        runningTurns.remove(turnId, runningTurn);
    }

    private record DevelopmentOwner(TurnId sourceTurnId, Map<DevelopmentTurnOrigin.Kind, CompletableFuture<DevelopmentTurnCompletion>> turns) {}
}
