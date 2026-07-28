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

/** Seals the current Stage, advances Task epoch, and opens Plan atomically. */
public final class ReplanHandoff
{
    private final TaskCommandExecutor commands;
    private final StageManager source;
    private final TaskManager tasks;
    private final PlanStageManager plan;

    public ReplanHandoff(
            TaskCommandExecutor commands,
            StageManager source,
            TaskManager tasks,
            PlanStageManager plan)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.source = requireNonNull(source, "source is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.plan = requireNonNull(plan, "plan is null");
    }

    public Result accept(TaskManager.ReplanCommand command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.taskId(), () -> acceptInCommand(command));
    }

    private Result acceptInCommand(TaskManager.ReplanCommand command)
    {
        TaskManager.AcceptedReplan accepted =
                tasks.requireSatisfiedReplanInCommand(command);
        StageManager.AcceptedSeal sealed =
                source.sealForReplanInCommand(accepted);
        TaskManager.StageOpening opening =
                tasks.acceptReplanStageInCommand(accepted, sealed);
        PlanStageManager.AcceptedOpening newPlan =
                plan.openFromTaskInCommand(opening);
        tasks.finishReplanInCommand(accepted, newPlan);
        return new Result(sealed.stage(), opening.taskState(), newPlan.stage());
    }

    public record Result(
            CommandResult<StageManager.State> source,
            TaskManager.State task,
            CommandResult<StageManager.State> plan)
    {
        public Result
        {
            requireNonNull(source, "source is null");
            requireNonNull(task, "task is null");
            requireNonNull(plan, "plan is null");
        }
    }
}
