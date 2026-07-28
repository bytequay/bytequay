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

/** Accepts exact provisioning evidence, activates Task, and opens Plan atomically. */
public final class ProvisionToPlanHandoff
{
    private final TaskCommandExecutor commands;
    private final TaskManager tasks;
    private final PlanStageManager plan;

    public ProvisionToPlanHandoff(
            TaskCommandExecutor commands, TaskManager tasks, PlanStageManager plan)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.plan = requireNonNull(plan, "plan is null");
    }

    public Result accept(TaskManager.ProvisioningCommand command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.taskId(), () -> acceptInCommand(command));
    }

    private Result acceptInCommand(TaskManager.ProvisioningCommand command)
    {
        TaskManager.StageOpening opening = tasks.acceptProvisioningInCommand(command);
        PlanStageManager.AcceptedOpening planStage =
                plan.openFromTaskInCommand(opening);
        return new Result(opening.taskState(), planStage.stage());
    }

    public record Result(
            TaskManager.State task,
            CommandResult<StageManager.State> plan)
    {
        public Result
        {
            requireNonNull(task, "task is null");
            requireNonNull(plan, "plan is null");
        }
    }
}
