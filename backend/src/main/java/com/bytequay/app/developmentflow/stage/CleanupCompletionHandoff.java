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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** One-transaction Cleanup-Stage-to-terminal-Task handoff. */
public final class CleanupCompletionHandoff
{
    private final TaskCommandExecutor commands;
    private final CleanupStageManager cleanup;
    private final TaskManager tasks;

    public CleanupCompletionHandoff(
            TaskCommandExecutor commands,
            CleanupStageManager cleanup,
            TaskManager tasks)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.cleanup = requireNonNull(cleanup, "cleanup is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    public Result accept(Command command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.task().taskId(), () -> acceptInCommand(command));
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
}
