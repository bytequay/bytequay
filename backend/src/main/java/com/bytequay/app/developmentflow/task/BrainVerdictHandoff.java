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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** One-transaction Task-Brain-to-exact-Stage handoff in owner order. */
public final class BrainVerdictHandoff
{
    private final TaskCommandExecutor commands;
    private final TaskManager tasks;
    private final PlanStageManager plan;
    private final LocalDevelopmentStageManager localDevelopment;

    public BrainVerdictHandoff(
            TaskCommandExecutor commands,
            TaskManager tasks,
            PlanStageManager plan,
            LocalDevelopmentStageManager localDevelopment)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.plan = requireNonNull(plan, "plan is null");
        this.localDevelopment = requireNonNull(localDevelopment, "localDevelopment is null");
    }

    public Result accept(Command command)
    {
        requireNonNull(command, "command is null");
        return commands.execute(command.taskCommand().taskId(), () -> acceptInCommand(command));
    }

    private Result acceptInCommand(Command command)
    {
        TaskManager.BrainVerdictResult taskResult = tasks.acceptBrainVerdictInCommand(
                command.taskCommand(), command.verdict());
        if (taskResult.accepted().isEmpty()) {
            return new Result(taskResult.task(), Optional.empty());
        }

        TaskManager.AcceptedBrainVerdict accepted = taskResult.accepted().orElseThrow();
        CommandResult<StageManager.State> stageResult = switch (command.target()) {
            case PLAN -> switch (accepted.verdict()) {
                case APPROVED -> plan.acceptBrainApprovalInCommand(accepted);
                case CHANGES_REQUESTED -> plan.acceptBrainFindingsInCommand(accepted);
            };
            case LOCAL_DEVELOPMENT -> switch (accepted.verdict()) {
                case APPROVED -> localDevelopment.acceptBrainApprovalInCommand(accepted);
                case CHANGES_REQUESTED -> localDevelopment.acceptBrainFindingsInCommand(accepted);
            };
        };
        if (stageResult.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new CommandRejectedException(
                    CommandRejectedException.Reason.INVALID_STATE,
                    "Task accepted a Brain verdict that its exact Stage cannot consume");
        }
        return new Result(taskResult.task(), Optional.of(stageResult));
    }

    public record Command(
            TaskManager.ResultCommand taskCommand,
            TaskManager.BrainVerdict verdict,
            Target target)
    {
        public Command
        {
            requireNonNull(taskCommand, "taskCommand is null");
            requireNonNull(verdict, "verdict is null");
            requireNonNull(target, "target is null");
            if (taskCommand.resultFence().stageId() == null
                    || taskCommand.resultFence().expectedCodeFingerprint() == null
                    || taskCommand.resultFence().expectedCodeFingerprint().isBlank()) {
                throw new IllegalArgumentException(
                        "Brain handoff requires an exact Stage subject revision");
            }
        }
    }

    public enum Target
    {
        PLAN,
        LOCAL_DEVELOPMENT
    }

    public record Result(
            CommandResult<TaskManager.State> task,
            Optional<CommandResult<StageManager.State>> stage)
    {
        public Result
        {
            requireNonNull(task, "task is null");
            requireNonNull(stage, "stage is null");
        }
    }
}
