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

/** Moves Cleanup into I/O only after the exact Task quiescence barrier is satisfied. */
public final class CleanupQuiescenceHandoff
{
    private final TaskCommandExecutor commands;
    private final TaskManager tasks;
    private final CleanupStageManager cleanup;

    public CleanupQuiescenceHandoff(
            TaskCommandExecutor commands, TaskManager tasks, CleanupStageManager cleanup)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.cleanup = requireNonNull(cleanup, "cleanup is null");
    }

    public CommandResult<StageManager.State> accept(
            TaskManager.CleanupQuiescenceCommand command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.taskId(), () -> {
            var replay = cleanup.replayQuiescenceInCommand(command);
            if (replay.isPresent()) {
                return replay.orElseThrow();
            }
            TaskManager.AcceptedCleanupQuiescence accepted =
                    tasks.requireSatisfiedCleanupQuiescenceInCommand(command);
            return cleanup.acceptQuiescenceInCommand(accepted);
        });
    }
}
