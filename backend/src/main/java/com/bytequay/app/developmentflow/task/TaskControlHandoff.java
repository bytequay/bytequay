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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import static java.util.Objects.requireNonNull;

/** Proof-gated synchronous completion of pause, resume, and archive controls. */
public final class TaskControlHandoff
{
    private final TaskCommandExecutor commands;
    private final TaskManager tasks;

    public TaskControlHandoff(TaskCommandExecutor commands, TaskManager tasks)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    public CommandResult<TaskManager.State> completePause(
            TaskManager.PauseCompletionCommand command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.task().taskId(), () -> {
            TaskManager.AcceptedPause accepted = tasks.requirePauseCompletionInCommand(command);
            return tasks.acceptPauseCompletionInCommand(accepted);
        });
    }

    public CommandResult<TaskManager.State> completeResume(
            TaskManager.ResumeCompletionCommand command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.task().taskId(), () -> {
            TaskManager.AcceptedResume accepted = tasks.requireResumeCompletionInCommand(command);
            return tasks.acceptResumeCompletionInCommand(accepted);
        });
    }

    public CommandResult<TaskManager.State> completeArchive(
            TaskManager.ArchiveCompletionCommand command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.task().taskId(), () -> {
            TaskManager.AcceptedArchive accepted = tasks.requireArchiveCompletionInCommand(command);
            return tasks.acceptArchiveCompletionInCommand(accepted);
        });
    }
}
