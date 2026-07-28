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

/** Seals an approved Plan, repoints Task, and opens Local in one transaction. */
public final class PlanToLocalHandoff
{
    private final TaskCommandExecutor commands;
    private final PlanStageManager plan;
    private final TaskManager tasks;
    private final LocalDevelopmentStageManager local;

    public PlanToLocalHandoff(
            TaskCommandExecutor commands,
            PlanStageManager plan,
            TaskManager tasks,
            LocalDevelopmentStageManager local)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.plan = requireNonNull(plan, "plan is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.local = requireNonNull(local, "local is null");
    }

    public Result accept(Command command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(
                command.plan().stage().taskId(), () -> acceptInCommand(command));
    }

    private Result acceptInCommand(Command command)
    {
        PlanStageManager.AcceptedCompletion planCompletion =
                plan.acceptApprovedForHandoffInCommand(command.plan());
        TaskManager.StageOpening opening = tasks.acceptPlanCompletionInCommand(
                command.task(), planCompletion);
        CommandResult<StageManager.State> localStage = local.openFromTaskInCommand(opening);
        return new Result(planCompletion.stage(), opening.taskState(), localStage);
    }

    public record Command(
            PlanStageManager.ApprovalCommand plan,
            TaskManager.StageAdvanceCommand task)
    {
        public Command
        {
            requireNonNull(plan, "plan is null");
            requireNonNull(task, "task is null");
            requireSameTransition(plan, task);
        }
    }

    public record Result(
            CommandResult<StageManager.State> plan,
            TaskManager.State task,
            CommandResult<StageManager.State> local)
    {
        public Result
        {
            requireNonNull(plan, "plan is null");
            requireNonNull(task, "task is null");
            requireNonNull(local, "local is null");
        }
    }

    private static void requireSameTransition(
            PlanStageManager.ApprovalCommand approval,
            TaskManager.StageAdvanceCommand task)
    {
        StageManager.Command previous = approval.stage();
        if (!previous.taskId().equals(task.taskId())
                || !previous.commandId().equals(task.commandId())
                || !previous.actor().equals(task.actor())
                || previous.expectedTaskEpoch() != task.expectedEpoch()
                || previous.stageId().equals(task.nextStageId())) {
            throw new IllegalArgumentException("Plan-to-Local handoff identity differs");
        }
    }
}
