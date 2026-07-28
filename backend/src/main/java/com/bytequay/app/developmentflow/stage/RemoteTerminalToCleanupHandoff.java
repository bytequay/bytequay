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

/** Atomically seals observed Remote truth, records terminal intent, and opens Cleanup. */
public final class RemoteTerminalToCleanupHandoff
{
    private final TaskCommandExecutor commands;
    private final RemoteDevelopmentStageManager remote;
    private final TaskManager tasks;
    private final CleanupStageManager cleanup;

    public RemoteTerminalToCleanupHandoff(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            TaskManager tasks,
            CleanupStageManager cleanup)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.cleanup = requireNonNull(cleanup, "cleanup is null");
    }

    public Result accept(Command command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.task().taskId(),
                () -> acceptInCommand(command));
    }

    /** Same atomic handoff for callers already folding accepted Remote truth. */
    public Result acceptInCommand(Command command)
    {
        requireNonNull(command, "command is null");
        TaskCommandExecutor.requireCurrent(command.task().taskId());
        RemoteDevelopmentStageManager.AcceptedTerminal terminal =
                remote.acceptTerminalObservationInCommand(command.observation());
        TaskManager.StageOpening opening =
                tasks.acceptRemoteTerminalInCommand(command.task(), terminal);
        CommandResult<StageManager.State> opened =
                cleanup.openFromTerminalInCommand(opening);
        return new Result(terminal.stage(), opening.taskState(), opened);
    }

    public record Command(
            RemoteDevelopmentStageManager.TerminalObservationCommand observation,
            TaskManager.TerminalCleanupCommand task)
    {
        public Command
        {
            requireNonNull(observation, "observation is null");
            requireNonNull(task, "task is null");
            if (!observation.stage().commandId().equals(task.commandId())
                    || !observation.stage().actor().equals(task.actor())
                    || !observation.stage().taskId().equals(task.taskId())
                    || observation.stage().expectedTaskEpoch() != task.expectedTaskEpoch()) {
                throw new IllegalArgumentException("Remote terminal handoff identity differs");
            }
        }
    }

    public record Result(
            CommandResult<StageManager.State> remote,
            TaskManager.State task,
            CommandResult<StageManager.State> cleanup)
    {
        public Result
        {
            requireNonNull(remote, "remote is null");
            requireNonNull(task, "task is null");
            requireNonNull(cleanup, "cleanup is null");
        }
    }
}
