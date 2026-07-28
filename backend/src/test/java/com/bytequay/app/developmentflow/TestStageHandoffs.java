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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.stage.CancellationToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.CleanupCompletionHandoff;
import com.bytequay.app.developmentflow.stage.CleanupStageManager;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.LocalToRemoteHandoff;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.PlanToLocalHandoff;
import com.bytequay.app.developmentflow.stage.ProvisionToPlanHandoff;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.ReplanHandoff;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageEndReason;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestStageHandoffs
{
    @Test
    void planToLocalSealsRepointsAndOpensInOneTransaction()
    {
        List<String> order = new ArrayList<>();
        CommandTestSupport.CountingTransactionManager transactions =
                new CommandTestSupport.CountingTransactionManager();
        TaskCommandExecutor executor = new TaskCommandExecutor(transactions);
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks(order);
        tasks.put(task(TaskLifecycle.ACTIVE, 3, 20, "plan", null));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null), order);
        stages.put(stage(
                "plan", StageKind.PLAN, 1, 5,
                StageCheckpoint.AWAITING_APPROVAL, null));

        PlanStageManager.ApprovalEvidence approval = new PlanStageManager.ApprovalEvidence(
                "task", "plan", 1, "approval", "revision", "self-review", "digest");
        PlanStageManager plan = new PlanStageManager(
                executor, stages,
                (taskId, stageId, generation, approvalId) -> Optional.of(approval));
        PlanToLocalHandoff handoff = new PlanToLocalHandoff(
                executor,
                plan,
                new TaskManager(executor, tasks),
                new LocalDevelopmentStageManager(executor, stages));
        PlanToLocalHandoff.Command command = new PlanToLocalHandoff.Command(
                new PlanStageManager.ApprovalCommand(
                        new StageManager.Command(
                                "advance", "user", "task", 3, "plan", 1, 5),
                        "approval", "revision", "self-review", "digest"),
                new TaskManager.StageAdvanceCommand(
                        "advance", "user", "task", 3, 20, "local", 1));

        PlanToLocalHandoff.Result result = handoff.accept(command);
        assertThat(order).containsExactly("stage", "task", "stage");
        assertThat(transactions.begins()).isEqualTo(1);
        assertThat(transactions.commits()).isEqualTo(1);
        assertThat(result.plan().state().checkpoint()).isEqualTo(StageCheckpoint.COMPLETED);
        assertThat(result.task().currentStageId()).isEqualTo("local");
        assertThat(result.local().state())
                .extracting(StageManager.State::kind, StageManager.State::checkpoint)
                .containsExactly(StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.IMPLEMENTING);

        PlanToLocalHandoff.Result duplicate = handoff.accept(command);
        assertThat(duplicate.plan().disposition()).isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(duplicate.local().disposition()).isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(order).containsExactly("stage", "task", "stage");

        PlanToLocalHandoff.Command changedGeneration = new PlanToLocalHandoff.Command(
                command.plan(), new TaskManager.StageAdvanceCommand(
                        "advance", "user", "task", 3, 20, "local", 2));
        assertThatThrownBy(() -> handoff.accept(changedGeneration))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(CommandRejectedException.Reason.COMMAND_ID_CONFLICT));
    }

    @Test
    void provisioningResultActivatesTaskAndOpensInitialPlanAtomically()
    {
        List<String> order = new ArrayList<>();
        CommandTestSupport.CountingTransactionManager transactions =
                new CommandTestSupport.CountingTransactionManager();
        TaskCommandExecutor executor = new TaskCommandExecutor(transactions);
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks(order);
        tasks.put(task(TaskLifecycle.PROVISIONING, 1, 0, null, null));
        ResultFence result = new ResultFence(
                1, null, 0, "provision", 1, "fingerprint", "head", "base");
        tasks.put(new TaskManager.ProvisioningResult(
                "task", 1, "provision", 1, "base", "head", "fingerprint"));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null), order);
        ProvisionToPlanHandoff handoff = new ProvisionToPlanHandoff(
                executor,
                new TaskManager(executor, tasks),
                new PlanStageManager(executor, stages));
        TaskManager.ProvisioningCommand command = new TaskManager.ProvisioningCommand(
                "activate", "dispatcher", "task", 0, result, "plan", 1);

        ProvisionToPlanHandoff.Result accepted = handoff.accept(command);

        assertThat(order).containsExactly("task", "stage");
        assertThat(transactions.begins()).isEqualTo(1);
        assertThat(transactions.commits()).isEqualTo(1);
        assertThat(accepted.task())
                .extracting(TaskManager.State::lifecycle, TaskManager.State::currentStageId)
                .containsExactly(TaskLifecycle.ACTIVE, "plan");
        assertThat(accepted.plan().state())
                .extracting(StageManager.State::kind, StageManager.State::checkpoint)
                .containsExactly(StageKind.PLAN, StageCheckpoint.DRAFTING);

        assertThat(handoff.accept(command).plan().disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
    }

    @Test
    void provisioningAndPlanCompletionFailClosedOnStaleEvidence()
    {
        TaskCommandExecutor executor = CommandTestSupport.executor();
        CommandTestSupport.Tasks provisioningTasks = new CommandTestSupport.Tasks();
        provisioningTasks.put(task(TaskLifecycle.PROVISIONING, 1, 0, null, null));
        provisioningTasks.put(new TaskManager.ProvisioningResult(
                "task", 1, "provision", 1, "base", "head", "fingerprint"));
        CommandTestSupport.Stages provisioningStages = new CommandTestSupport.Stages(
                taskId -> provisioningTasks.findById(taskId).orElse(null));
        ProvisionToPlanHandoff provisioning = new ProvisionToPlanHandoff(
                executor,
                new TaskManager(executor, provisioningTasks),
                new PlanStageManager(executor, provisioningStages));
        ResultFence staleResult = new ResultFence(
                1, null, 0, "provision", 1,
                "fingerprint", "different-head", "base");

        assertThatThrownBy(() -> provisioning.accept(new TaskManager.ProvisioningCommand(
                "activate", "dispatcher", "task", 0, staleResult, "plan", 1)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(CommandRejectedException.Reason.INVALID_STATE));

        CommandTestSupport.Tasks planTasks = new CommandTestSupport.Tasks();
        planTasks.put(task(TaskLifecycle.ACTIVE, 3, 20, "plan", null));
        CommandTestSupport.Stages planStages = new CommandTestSupport.Stages(
                taskId -> planTasks.findById(taskId).orElse(null));
        planStages.put(stage(
                "plan", StageKind.PLAN, 1, 5,
                StageCheckpoint.AWAITING_APPROVAL, null));
        PlanStageManager.ApprovalEvidence oldApproval =
                new PlanStageManager.ApprovalEvidence(
                        "task", "plan", 1, "approval", "revision",
                        "self-review", "old-digest");
        PlanToLocalHandoff plan = new PlanToLocalHandoff(
                executor,
                new PlanStageManager(
                        executor, planStages,
                        (taskId, stageId, generation, approvalId) ->
                                Optional.of(oldApproval)),
                new TaskManager(executor, planTasks),
                new LocalDevelopmentStageManager(executor, planStages));

        assertThatThrownBy(() -> plan.accept(new PlanToLocalHandoff.Command(
                new PlanStageManager.ApprovalCommand(
                        new StageManager.Command(
                                "advance", "user", "task", 3, "plan", 1, 5),
                        "approval", "revision", "self-review", "new-digest"),
                new TaskManager.StageAdvanceCommand(
                        "advance", "user", "task", 3, 20, "local", 1))))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(CommandRejectedException.Reason.INVALID_STATE));
    }

    @Test
    void replanConsumesSatisfiedBarrierAndRepointsToNewPlanAtomically()
    {
        List<String> order = new ArrayList<>();
        CommandTestSupport.CountingTransactionManager transactions =
                new CommandTestSupport.CountingTransactionManager();
        TaskCommandExecutor executor = new TaskCommandExecutor(transactions);
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks(order);
        tasks.put(task(TaskLifecycle.ACTIVE, 3, 20, "local", null));
        tasks.put(new TaskManager.ReplanEvidence(
                "task", "replan-request", "replan-barrier", "local", 2,
                3, 4, "replan", "user"));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null), order);
        stages.put(stage(
                "local", StageKind.LOCAL_DEVELOPMENT, 2, 8,
                StageCheckpoint.BRAIN_REVIEW, null));
        TaskManager.ReplanCommand command = new TaskManager.ReplanCommand(
                "replan", "user", "task", 20, 3, "local", 2, 8,
                "replan-request", "replan-barrier", "new-plan", 2);
        ReplanHandoff handoff = new ReplanHandoff(
                executor,
                new LocalDevelopmentStageManager(executor, stages),
                new TaskManager(executor, tasks),
                new PlanStageManager(executor, stages));

        ReplanHandoff.Result result = handoff.accept(command);

        assertThat(order).containsExactly("stage", "task", "stage");
        assertThat(transactions.begins()).isEqualTo(1);
        assertThat(transactions.commits()).isEqualTo(1);
        assertThat(result.source().state().endReason())
                .isEqualTo(StageEndReason.SUPERSEDED_BY_REPLAN);
        assertThat(result.task())
                .extracting(TaskManager.State::epoch, TaskManager.State::currentStageId)
                .containsExactly(4L, "new-plan");
        assertThat(tasks.appliedReplan("replan-request")).isEqualTo("new-plan:2");
        assertThat(handoff.accept(command).plan().disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
    }

    @Test
    void cancellationConsumesSatisfiedBarrierAndOpensCleanupAtomically()
    {
        List<String> order = new ArrayList<>();
        CommandTestSupport.CountingTransactionManager transactions =
                new CommandTestSupport.CountingTransactionManager();
        TaskCommandExecutor executor = new TaskCommandExecutor(transactions);
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks(order);
        tasks.put(task(
                TaskLifecycle.CANCELING, 4, 21, "local",
                TaskManager.TerminalOutcome.CANCELED));
        tasks.put(new TaskManager.QuiescenceEvidence(
                "task", 4, "cancel-barrier", TaskManager.QuiescenceReason.CANCEL));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null), order);
        stages.put(stage(
                "local", StageKind.LOCAL_DEVELOPMENT, 2, 8,
                StageCheckpoint.BRAIN_REVIEW, null));
        TaskManager.CancellationCommand command = new TaskManager.CancellationCommand(
                "cancel-cleanup", "system", "task", 4, 21,
                "local", 2, 8, "cancel-barrier", "cleanup", 1);
        CancellationToCleanupHandoff handoff = new CancellationToCleanupHandoff(
                executor,
                new LocalDevelopmentStageManager(executor, stages),
                new TaskManager(executor, tasks),
                new CleanupStageManager(executor, stages));

        CancellationToCleanupHandoff.Result result = handoff.accept(command);

        assertThat(order).containsExactly("stage", "task", "stage", "stage");
        assertThat(result.task())
                .extracting(TaskManager.State::lifecycle, TaskManager.State::currentStageId)
                .containsExactly(TaskLifecycle.CLEANING, "cleanup");
        assertThat(result.cleanupWaiting().state().checkpoint())
                .isEqualTo(StageCheckpoint.WAITING_QUIESCENCE);
        assertThat(result.cleanup().state().checkpoint()).isEqualTo(StageCheckpoint.CLEANING);
        CancellationToCleanupHandoff.Result replay = handoff.accept(command);
        assertThat(replay.cleanupWaiting().disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(replay.cleanup().disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
    }

    @Test
    void localToRemoteAcceptsTheExactPublishResultInOneTransaction()
    {
        List<String> order = new ArrayList<>();
        CommandTestSupport.CountingTransactionManager transactions =
                new CommandTestSupport.CountingTransactionManager();
        TaskCommandExecutor executor = new TaskCommandExecutor(transactions);
        ResultFence fence = fence(3, "local", 2, "publish");
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks(order);
        tasks.put(task(TaskLifecycle.ACTIVE, 3, 30, "local", null));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null), order);
        stages.put(stage(
                "local", StageKind.LOCAL_DEVELOPMENT, 2, 8,
                StageCheckpoint.PUBLISHING, fence));

        LocalToRemoteHandoff handoff = new LocalToRemoteHandoff(
                executor,
                new LocalDevelopmentStageManager(executor, stages),
                new TaskManager(executor, tasks),
                new RemoteDevelopmentStageManager(executor, stages));
        LocalToRemoteHandoff.Command command = new LocalToRemoteHandoff.Command(
                new StageManager.ResultCommand("publish", "dispatcher", "task", fence),
                new TaskManager.StageAdvanceCommand(
                        "publish", "dispatcher", "task", 3, 30, "remote", 1));

        LocalToRemoteHandoff.Result result = handoff.accept(command);
        assertThat(order).containsExactly("stage", "task", "stage");
        assertThat(transactions.begins()).isEqualTo(1);
        assertThat(transactions.commits()).isEqualTo(1);
        assertThat(result.task().orElseThrow().currentStageId()).isEqualTo("remote");
        assertThat(result.remote().orElseThrow().state())
                .extracting(StageManager.State::kind, StageManager.State::checkpoint)
                .containsExactly(StageKind.REMOTE_DEVELOPMENT, StageCheckpoint.WAITING_CI);
    }

    @Test
    void cleanupProofIsTheOnlyPathToATerminalTask()
    {
        List<String> order = new ArrayList<>();
        CommandTestSupport.CountingTransactionManager transactions =
                new CommandTestSupport.CountingTransactionManager();
        TaskCommandExecutor executor = new TaskCommandExecutor(transactions);
        ResultFence fence = fence(4, "cleanup", 1, "cleanup-operation");
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks(order);
        tasks.put(task(
                TaskLifecycle.CLEANING, 4, 40, "cleanup",
                TaskManager.TerminalOutcome.CANCELED));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null), order);
        stages.put(stage(
                "cleanup", StageKind.CLEANUP, 1, 6,
                StageCheckpoint.CLEANING, fence));

        CleanupCompletionHandoff handoff = new CleanupCompletionHandoff(
                executor,
                new CleanupStageManager(executor, stages),
                new TaskManager(executor, tasks));
        CleanupCompletionHandoff.Command command = new CleanupCompletionHandoff.Command(
                new TaskManager.Command("finish", "dispatcher", "task", 4, 40),
                new StageManager.ResultCommand("finish", "dispatcher", "task", fence));

        CleanupCompletionHandoff.Result result = handoff.accept(command);
        assertThat(order).containsExactly("stage", "task");
        assertThat(transactions.begins()).isEqualTo(1);
        assertThat(transactions.commits()).isEqualTo(1);
        assertThat(result.stage().state().checkpoint()).isEqualTo(StageCheckpoint.COMPLETED);
        assertThat(result.task().orElseThrow().state())
                .extracting(TaskManager.State::lifecycle, TaskManager.State::currentStageId)
                .containsExactly(TaskLifecycle.CANCELED, null);

        CleanupCompletionHandoff.Result duplicate = handoff.accept(command);
        assertThat(duplicate.stage().disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(duplicate.task().orElseThrow().disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(order).containsExactly("stage", "task");
    }

    private static TaskManager.State task(
            TaskLifecycle lifecycle,
            long epoch,
            long version,
            String currentStage,
            TaskManager.TerminalOutcome terminalIntent)
    {
        return new TaskManager.State(
                "task", "trunk", lifecycle, epoch, version, currentStage,
                null, null, null, terminalIntent);
    }

    private static StageManager.State stage(
            String id,
            StageKind kind,
            long generation,
            long version,
            StageCheckpoint checkpoint,
            ResultFence pending)
    {
        return new StageManager.State(
                id, "task", kind, generation, version, checkpoint, null, pending);
    }

    private static ResultFence fence(
            long epoch, String stageId, long generation, String operationId)
    {
        return new ResultFence(
                epoch, stageId, generation, operationId, 1,
                "fingerprint", "head", "base");
    }
}
