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

import static java.util.Objects.requireNonNull;

/** Seals a quiescent canceled Stage, repoints Task, and opens Cleanup atomically. */
public final class CancellationToCleanupHandoff
{
    private final TaskCommandExecutor commands;
    private final StageManager source;
    private final TaskManager tasks;
    private final CleanupStageManager cleanup;

    public CancellationToCleanupHandoff(
            TaskCommandExecutor commands,
            StageManager source,
            TaskManager tasks,
            CleanupStageManager cleanup)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.source = requireNonNull(source, "source is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.cleanup = requireNonNull(cleanup, "cleanup is null");
    }

    public Result accept(TaskManager.CancellationCommand command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.taskId(), () -> acceptInCommand(command));
    }

    public StageKind sourceKind()
    {
        return source.kind();
    }

    private Result acceptInCommand(TaskManager.CancellationCommand command)
    {
        TaskManager.AcceptedCancellation accepted =
                tasks.requireSatisfiedCancellationInCommand(command);
        StageManager.AcceptedSeal sealed =
                source.sealForTaskCancellationInCommand(accepted);
        TaskManager.StageOpening opening =
                tasks.acceptCancellationStageInCommand(accepted, sealed);
        CommandResult<StageManager.State> waiting = cleanup.openFromCancellationInCommand(opening);
        return new Result(sealed.stage(), opening.taskState(), waiting);
    }

    public record Result(
            CommandResult<StageManager.State> source,
            TaskManager.State task,
            CommandResult<StageManager.State> cleanup)
    {
        public Result
        {
            requireNonNull(source, "source is null");
            requireNonNull(task, "task is null");
            requireNonNull(cleanup, "cleanup is null");
        }
    }
}
