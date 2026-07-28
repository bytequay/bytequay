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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Accepts publish, repoints Task, and opens Remote in one transaction. */
public final class LocalToRemoteHandoff
{
    private final TaskCommandExecutor commands;
    private final LocalDevelopmentStageManager local;
    private final TaskManager tasks;
    private final RemoteDevelopmentStageManager remote;

    public LocalToRemoteHandoff(
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            TaskManager tasks,
            RemoteDevelopmentStageManager remote)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.local = requireNonNull(local, "local is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.remote = requireNonNull(remote, "remote is null");
    }

    public Result accept(Command command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.published().taskId(), () -> acceptInCommand(command));
    }

    private Result acceptInCommand(Command command)
    {
        LocalDevelopmentStageManager.PublicationResult publication =
                local.acceptPublishedForHandoffInCommand(command.published());
        if (publication.accepted().isEmpty()) {
            return new Result(publication.stage(), Optional.empty(), Optional.empty());
        }
        TaskManager.StageOpening opening = tasks.acceptLocalCompletionInCommand(
                command.task(), publication.accepted().orElseThrow());
        CommandResult<StageManager.State> remoteStage = remote.openFromTaskInCommand(opening);
        if (remoteStage.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new CommandRejectedException(
                    CommandRejectedException.Reason.INVALID_STATE,
                    "Task authorized a Remote Stage that cannot open");
        }
        return new Result(
                publication.stage(), Optional.of(opening.taskState()), Optional.of(remoteStage));
    }

    public record Command(
            StageManager.ResultCommand published,
            TaskManager.StageAdvanceCommand task)
    {
        public Command
        {
            requireNonNull(published, "published is null");
            requireNonNull(task, "task is null");
            if (!published.taskId().equals(task.taskId())
                    || !published.commandId().equals(task.commandId())
                    || !published.actor().equals(task.actor())
                    || published.resultFence().taskEpoch() != task.expectedEpoch()
                    || published.resultFence().stageId().equals(task.nextStageId())) {
                throw new IllegalArgumentException("Local-to-Remote handoff identity differs");
            }
        }
    }

    public record Result(
            CommandResult<StageManager.State> local,
            Optional<TaskManager.State> task,
            Optional<CommandResult<StageManager.State>> remote)
    {
        public Result
        {
            requireNonNull(local, "local is null");
            requireNonNull(task, "task is null");
            requireNonNull(remote, "remote is null");
        }
    }
}
