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
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.RunningTurn;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Objects.requireNonNull;

/**
 * Tracks provider sessions and live turn handles owned by this backend process.
 *
 * <p>Durable identity remains in the task and turn stores. An empty registry
 * after restart causes the next turn to resume from the persisted session
 * reference rather than treating this in-memory state as authoritative.
 */
public final class AgentRuntimeRegistry
{
    private final ConcurrentMap<TaskId, AgentSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<TurnId, RunningTurn> runningTurns = new ConcurrentHashMap<>();

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
            return existing;
        }
        return session;
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
}
