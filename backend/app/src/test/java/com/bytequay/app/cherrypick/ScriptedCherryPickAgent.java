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

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.spi.CostProvenance;
import com.google.common.collect.ImmutableList;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * A scripted agent: each turn runs one action against the worktree and ends in
 * a state the test chose.
 *
 * <p>Several scripts have it report success while staging nothing, which is
 * what proves the service reads Git rather than the agent.
 */
final class ScriptedCherryPickAgent
        implements AgentTaskService, AgentTurnService, AgentCompletionService
{
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    private final WorktreeService worktrees;
    private final Deque<Turn> script = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();
    private final List<AgentTurn> started = new ArrayList<>();
    private Runnable beforeTaskCreate = () -> {};
    private Optional<Runnable> beforeNextCompletion = Optional.empty();
    private Optional<Runnable> beforeNextCleanup = Optional.empty();
    private AgentTask task;
    private boolean completed;
    private boolean terminalizeOnComplete;
    private Optional<RuntimeException> completionFailure = Optional.empty();
    private Optional<RuntimeException> cleanupFailure = Optional.empty();

    ScriptedCherryPickAgent(WorktreeService worktrees)
    {
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
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

    ScriptedCherryPickAgent beforeTaskCreate(Runnable action)
    {
        beforeTaskCreate = action;
        return this;
    }

    ScriptedCherryPickAgent failNextCleanup(RuntimeException failure)
    {
        cleanupFailure = Optional.of(requireNonNull(failure, "failure is null"));
        return this;
    }

    ScriptedCherryPickAgent beforeNextCompletion(Runnable action)
    {
        beforeNextCompletion = Optional.of(requireNonNull(action, "action is null"));
        return this;
    }

    ScriptedCherryPickAgent beforeNextCleanup(Runnable action)
    {
        beforeNextCleanup = Optional.of(requireNonNull(action, "action is null"));
        return this;
    }

    ScriptedCherryPickAgent terminalizeOnComplete()
    {
        terminalizeOnComplete = true;
        return this;
    }

    ScriptedCherryPickAgent failNextCompletion(RuntimeException failure)
    {
        completionFailure = Optional.of(requireNonNull(failure, "failure is null"));
        return this;
    }

    @Override
    public AgentTask createInteractive(CreateInteractiveAgentTaskCommand command)
    {
        throw new UnsupportedOperationException("interactive creation is not used by cherry-pick tests");
    }

    @Override
    public AgentTask create(CreateAgentTaskCommand command)
    {
        TaskWorktree worktree = worktrees.provision(new ProvisionWorktreeCommand(
                command.taskId(),
                command.workspaceId(),
                command.physicalRepository(),
                command.startCommit()));
        Runnable callback = beforeTaskCreate;
        beforeTaskCreate = () -> {};
        callback.run();
        if (task != null) {
            throw new AgentConflictException("Task already exists: " + command.taskId());
        }
        task = AgentTask.created(
                command.taskId(),
                false,
                command.title(),
                command.laneId(),
                worktree,
                command.defaults(),
                NOW);
        return task;
    }

    @Override
    public AgentTask get(TaskId taskId)
    {
        if (task == null || !task.id().equals(taskId)) {
            throw new AgentTaskNotFoundException(taskId);
        }
        return task;
    }

    @Override
    public AgentTask getInteractive(String workspaceId, TaskId taskId)
    {
        if (task == null || !task.interactive() || task.workspaceId().stream().noneMatch(workspaceId::equals)) {
            throw new AgentTaskNotFoundException(taskId);
        }
        return task;
    }

    @Override
    public AgentTask updateDefaults(UpdateAgentTaskDefaultsCommand command)
    {
        if (task.version() != command.expectedVersion()) {
            throw new AgentConflictException("Task version changed: " + task.id());
        }
        task = task.withDefaults(command.defaults(), NOW).withVersion(task.version() + 1);
        return task;
    }

    @Override
    public List<AgentTask> list(int limit)
    {
        return task == null ? ImmutableList.of() : ImmutableList.of(task);
    }

    @Override
    public List<AgentTask> list(String workspaceId, int limit)
    {
        return task != null && task.interactive() && task.workspaceId().stream().anyMatch(workspaceId::equals)
                ? ImmutableList.of(task)
                : ImmutableList.of();
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
    public com.bytequay.agent.core.TaskCompletion complete(CompleteAgentTaskCommand command)
    {
        beforeNextCompletion.ifPresent(Runnable::run);
        beforeNextCompletion = Optional.empty();
        if (completionFailure.isPresent()) {
            RuntimeException failure = completionFailure.orElseThrow();
            completionFailure = Optional.empty();
            throw failure;
        }
        completed = true;
        if (terminalizeOnComplete && !task.state().terminal()) {
            task = task.fail("scripted completion", NOW.plusSeconds(1), NOW.plus(Duration.ofDays(7)));
        }
        return null;
    }

    @Override
    public AgentTask abandon(TaskId taskId, String reason)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void cleanupWorktree(TaskId taskId)
    {
        beforeNextCleanup.ifPresent(Runnable::run);
        beforeNextCleanup = Optional.empty();
        if (cleanupFailure.isPresent()) {
            RuntimeException failure = cleanupFailure.orElseThrow();
            cleanupFailure = Optional.empty();
            throw failure;
        }
        worktrees.cleanup(task.taskWorktree().orElseThrow());
    }

    @Override
    public com.bytequay.agent.core.CleanupResult cleanup(TaskId taskId)
    {
        return null;
    }

    /** True once the range closed the task it opened. */
    boolean taskCompleted()
    {
        return completed;
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
                Optional.of(new AgentTurnSettings(
                        task.defaults().model(),
                        task.defaults().effort(),
                        task.permissionPolicy(),
                        AgentSandboxPolicy.WORKSPACE_WRITE,
                        java.time.Duration.ofMinutes(5),
                        ImmutableList.of())),
                state == TurnState.COMPLETED
                        ? Optional.empty()
                        : Optional.of("Everything is fine and validation passed."),
                new TurnMetrics(0, 0, 0, Optional.empty(), CostProvenance.UNAVAILABLE, java.time.Duration.ZERO));
    }

    private record Turn(Runnable action, TurnState state) {}
}
