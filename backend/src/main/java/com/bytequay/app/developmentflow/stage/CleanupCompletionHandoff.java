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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.developmentflow.CommandResult.Disposition.SUPERSEDED;
import static java.util.Objects.requireNonNull;

/** One-transaction Cleanup-Stage-to-terminal-Task handoff. */
public final class CleanupCompletionHandoff
{
    private final TaskCommandExecutor commands;
    private final CleanupStageManager cleanup;
    private final TaskManager tasks;
    private final List<PostCompletionHook> postCompletionHooks;
    private final Clock clock;

    public CleanupCompletionHandoff(
            TaskCommandExecutor commands,
            CleanupStageManager cleanup,
            TaskManager tasks)
    {
        this(commands, cleanup, tasks, List.of(), Clock.systemUTC());
    }

    public CleanupCompletionHandoff(
            TaskCommandExecutor commands,
            CleanupStageManager cleanup,
            TaskManager tasks,
            List<PostCompletionHook> postCompletionHooks,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.cleanup = requireNonNull(cleanup, "cleanup is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.postCompletionHooks = List.copyOf(requireNonNull(
                postCompletionHooks, "postCompletionHooks is null"));
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Result accept(Command command)
    {
        requireNonNull(command, "command is null");
        Result result = commands.execute(
                command.task().taskId(), () -> acceptInCommand(command));
        result.task()
                .filter(task -> task.disposition()
                        != SUPERSEDED)
                .ifPresent(task -> {
                    Completion completion = new Completion(
                            task.state().id(), task.state().trunkId(),
                            task.state().epoch(), task.state().lifecycle().name(),
                            command.task().actor(), clock.instant());
                    invokePostCompletionHooks(completion);
                });
        return result;
    }

    private void invokePostCompletionHooks(Completion completion)
    {
        RuntimeException firstFailure = null;
        for (PostCompletionHook hook : postCompletionHooks) {
            try {
                hook.afterCommit(completion);
            }
            catch (RuntimeException failure) {
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

    private Result acceptInCommand(Command command)
    {
        CleanupStageManager.CompletionResult stage =
                cleanup.acceptCleanupCompleteInCommand(command.cleanupResult());
        if (stage.accepted().isEmpty()) {
            return new Result(stage.stage(), Optional.empty());
        }
        CommandResult<TaskManager.State> task = tasks.acceptCleanupCompletionInCommand(
                command.task(), stage.accepted().orElseThrow());
        return new Result(stage.stage(), Optional.of(task));
    }

    public record Command(
            TaskManager.Command task,
            StageManager.ResultCommand cleanupResult)
    {
        public Command
        {
            requireNonNull(task, "task is null");
            requireNonNull(cleanupResult, "cleanupResult is null");
            if (!task.taskId().equals(cleanupResult.taskId())) {
                throw new IllegalArgumentException("Cleanup handoff spans two Tasks");
            }
            if (!task.commandId().equals(cleanupResult.commandId())
                    || !task.actor().equals(cleanupResult.actor())) {
                throw new IllegalArgumentException("Cleanup handoff command identity differs");
            }
            if (task.expectedEpoch() != cleanupResult.resultFence().taskEpoch()) {
                throw new IllegalArgumentException("Cleanup handoff Task epochs differ");
            }
        }
    }

    public record Result(
            CommandResult<StageManager.State> stage,
            Optional<CommandResult<TaskManager.State>> task)
    {
        public Result
        {
            requireNonNull(stage, "stage is null");
            requireNonNull(task, "task is null");
        }
    }

    /** Composable, idempotent hook invoked only after the Task transaction commits. */
    @FunctionalInterface
    public interface PostCompletionHook
    {
        void afterCommit(Completion completion);
    }

    public record Completion(
            String taskId,
            String trunkId,
            long taskEpoch,
            String terminalLifecycle,
            String actor,
            Instant completedAt)
    {
        public Completion
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(trunkId, "trunkId is null");
            requireNonNull(terminalLifecycle, "terminalLifecycle is null");
            requireNonNull(actor, "actor is null");
            requireNonNull(completedAt, "completedAt is null");
        }
    }
}
