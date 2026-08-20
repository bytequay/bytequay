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
package com.bytequay.app.cherrypick;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.spi.CostProvenance;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A scripted agent: each turn runs one action against the worktree and ends in
 * a state the test chose.
 *
 * <p>Several scripts have it report success while staging nothing, which is
 * what proves the service reads Git rather than the agent.
 */
final class ScriptedCherryPickAgent
        implements AgentTaskService, AgentTurnService
{
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    private final Path worktree;
    private final Deque<Turn> script = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();
    private final List<AgentTurn> started = new ArrayList<>();
    private AgentTask task;

    ScriptedCherryPickAgent(Path worktree)
    {
        this.worktree = worktree;
    }

    /** Queues one turn: what it does to the worktree, and how it ends. */
    ScriptedCherryPickAgent then(Runnable action, TurnState state)
    {
        script.add(new Turn(action, state));
        return this;
    }

    List<String> prompts()
    {
        return ImmutableList.copyOf(prompts);
    }

    int turnsStarted()
    {
        return started.size();
    }

    @Override
    public AgentTask create(CreateAgentTaskCommand command)
    {
        task = new AgentTask(
                TaskId.random(),
                command.title(),
                command.laneId(),
                command.repository(),
                command.worktree(),
                command.permissionPolicy(),
                com.bytequay.agent.core.TaskState.READY,
                0,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new com.bytequay.agent.core.TaskMetrics(
                        0, 0, 0, Optional.empty(), CostProvenance.UNAVAILABLE, java.time.Duration.ZERO),
                Optional.empty(),
                com.bytequay.agent.core.CleanupState.RETAINED,
                Optional.empty(),
                Optional.empty());
        return task;
    }

    @Override
    public AgentTask get(TaskId taskId)
    {
        return task;
    }

    @Override
    public List<AgentTask> list(int limit)
    {
        return task == null ? ImmutableList.of() : ImmutableList.of(task);
    }

    @Override
    public AgentTurn start(StartAgentTurnCommand command)
    {
        prompts.add(command.message());
        Turn scripted = script.poll();
        if (scripted == null) {
            throw new IllegalStateException("no scripted turn remains");
        }
        scripted.action().run();
        AgentTurn turn = turn(scripted.state());
        started.add(turn);
        return turn;
    }

    @Override
    public AgentTurn cancel(TurnId turnId)
    {
        return started.getLast();
    }

    @Override
    public AgentTurn get(TurnId turnId)
    {
        return started.stream()
                .filter(turn -> turn.id().equals(turnId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown turn: " + turnId));
    }

    private AgentTurn turn(TurnState state)
    {
        return new AgentTurn(
                TurnId.random(),
                task.id(),
                "scripted-" + started.size(),
                state,
                0,
                NOW,
                Optional.of(NOW),
                Optional.of(NOW),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                state == TurnState.COMPLETED
                        ? Optional.empty()
                        : Optional.of("Everything is fine and validation passed."),
                new TurnMetrics(0, 0, 0, Optional.empty(), CostProvenance.UNAVAILABLE, java.time.Duration.ZERO));
    }

    private record Turn(Runnable action, TurnState state) {}
}
