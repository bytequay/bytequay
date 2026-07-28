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

import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.BrainVerdictHandoff;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBrainVerdictHandoff
{
    @Test
    void acceptsTaskBrainThenLocalStageInsideOneTaskTransaction()
    {
        List<String> commitOrder = new ArrayList<>();
        CommandTestSupport.CountingTransactionManager transactions =
                new CommandTestSupport.CountingTransactionManager();
        TaskCommandExecutor executor = new TaskCommandExecutor(transactions);
        ResultFence fence = fence();

        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks(commitOrder);
        tasks.put(task("stage", fence));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null), commitOrder);
        stages.put(stage());

        BrainVerdictHandoff handoff = new BrainVerdictHandoff(
                executor,
                new TaskManager(executor, tasks),
                new PlanStageManager(executor, stages),
                new LocalDevelopmentStageManager(executor, stages));
        BrainVerdictHandoff.Command command = command(
                "brain-verdict", fence, TaskManager.BrainVerdict.APPROVED,
                BrainVerdictHandoff.Target.LOCAL_DEVELOPMENT);

        BrainVerdictHandoff.Result result = handoff.accept(command);
        assertThat(commitOrder).containsExactly("task", "stage");
        assertThat(transactions.begins()).isEqualTo(1);
        assertThat(transactions.commits()).isEqualTo(1);
        assertThat(result.task().state().lastBrainVerdict())
                .isEqualTo(TaskManager.BrainVerdict.APPROVED);
        assertThat(result.stage().orElseThrow().state().checkpoint())
                .isEqualTo(StageCheckpoint.LOCAL_REVIEW);

        BrainVerdictHandoff.Result duplicate = handoff.accept(command);
        assertThat(duplicate.task().disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(duplicate.stage().orElseThrow().disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(commitOrder).containsExactly("task", "stage");

        BrainVerdictHandoff.Command opposite = command(
                "brain-verdict", fence, TaskManager.BrainVerdict.CHANGES_REQUESTED,
                BrainVerdictHandoff.Target.LOCAL_DEVELOPMENT);
        assertThatThrownBy(() -> handoff.accept(opposite))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                CommandRejectedException.Reason.COMMAND_ID_CONFLICT));

        ResultFence otherFence = new ResultFence(
                7, "stage", 4, "other-operation", 2,
                "fingerprint", "head", "base");
        assertThatThrownBy(() -> handoff.accept(command(
                "brain-verdict", otherFence, TaskManager.BrainVerdict.APPROVED,
                BrainVerdictHandoff.Target.LOCAL_DEVELOPMENT)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                CommandRejectedException.Reason.COMMAND_ID_CONFLICT));
    }

    @Test
    void changesRequestedUsesTheOtherExplicitStageCommand()
    {
        TaskCommandExecutor executor = CommandTestSupport.executor();
        ResultFence fence = fence();
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks();
        tasks.put(task("stage", fence));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null));
        stages.put(stage());
        BrainVerdictHandoff handoff = new BrainVerdictHandoff(
                executor,
                new TaskManager(executor, tasks),
                new PlanStageManager(executor, stages),
                new LocalDevelopmentStageManager(executor, stages));

        BrainVerdictHandoff.Result result = handoff.accept(command(
                "task-findings",
                fence,
                TaskManager.BrainVerdict.CHANGES_REQUESTED,
                BrainVerdictHandoff.Target.LOCAL_DEVELOPMENT));
        assertThat(result.stage().orElseThrow().state().checkpoint())
                .isEqualTo(StageCheckpoint.ADDRESSING_BRAIN_FINDINGS);
    }

    @Test
    void planSelfReviewConsumesTheSameTaskOwnedExactRevisionCapability()
    {
        TaskCommandExecutor executor = CommandTestSupport.executor();
        ResultFence fence = new ResultFence(
                7, "stage", 4, "plan-brain", 1,
                "plan-revision-9", null, "planning-base");
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks();
        tasks.put(task("stage", fence));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null));
        stages.put(new StageManager.State(
                "stage", "task", StageKind.PLAN, 4, 53,
                StageCheckpoint.SELF_REVIEW, null, null));
        BrainVerdictHandoff handoff = new BrainVerdictHandoff(
                executor,
                new TaskManager(executor, tasks),
                new PlanStageManager(executor, stages),
                new LocalDevelopmentStageManager(executor, stages));

        BrainVerdictHandoff.Result result = handoff.accept(command(
                "plan-verdict", fence, TaskManager.BrainVerdict.APPROVED,
                BrainVerdictHandoff.Target.PLAN));

        assertThat(result.stage().orElseThrow().state().checkpoint())
                .isEqualTo(StageCheckpoint.AWAITING_APPROVAL);
        assertThat(result.task().state().lastBrainResult()).isEqualTo(fence);
    }

    @Test
    void staleTaskOwnerStopsBeforeTheStageWriter()
    {
        List<String> commitOrder = new ArrayList<>();
        TaskCommandExecutor executor = CommandTestSupport.executor();
        ResultFence fence = fence();
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks(commitOrder);
        tasks.put(task("replacement", fence));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null), commitOrder);
        stages.put(stage());
        BrainVerdictHandoff handoff = new BrainVerdictHandoff(
                executor,
                new TaskManager(executor, tasks),
                new PlanStageManager(executor, stages),
                new LocalDevelopmentStageManager(executor, stages));

        BrainVerdictHandoff.Result result = handoff.accept(command(
                "late-task", fence, TaskManager.BrainVerdict.APPROVED,
                BrainVerdictHandoff.Target.LOCAL_DEVELOPMENT));
        assertThat(result.task().disposition())
                .isEqualTo(CommandResult.Disposition.SUPERSEDED);
        assertThat(result.stage()).isEmpty();
        assertThat(commitOrder).isEmpty();
    }

    @Test
    void multipleSupersededTaskReceiptsAtOneVersionReplayTheirImmutableState()
    {
        TaskCommandExecutor executor = CommandTestSupport.executor();
        ResultFence firstFence = new ResultFence(
                7, "stage", 4, "stale-brain-one", 1,
                "fingerprint", "head", "base");
        ResultFence secondFence = new ResultFence(
                7, "stage", 4, "stale-brain-two", 1,
                "fingerprint", "head", "base");
        CommandTestSupport.Tasks tasks = new CommandTestSupport.Tasks();
        tasks.put(task("replacement", firstFence));
        CommandTestSupport.Stages stages = new CommandTestSupport.Stages(
                taskId -> tasks.findById(taskId).orElse(null));
        stages.put(stage());
        BrainVerdictHandoff handoff = new BrainVerdictHandoff(
                executor,
                new TaskManager(executor, tasks),
                new PlanStageManager(executor, stages),
                new LocalDevelopmentStageManager(executor, stages));
        BrainVerdictHandoff.Command first = command(
                "stale-task-one", firstFence, TaskManager.BrainVerdict.APPROVED,
                BrainVerdictHandoff.Target.LOCAL_DEVELOPMENT);
        BrainVerdictHandoff.Command second = command(
                "stale-task-two", secondFence, TaskManager.BrainVerdict.APPROVED,
                BrainVerdictHandoff.Target.LOCAL_DEVELOPMENT);

        assertThat(handoff.accept(first).task().disposition())
                .isEqualTo(CommandResult.Disposition.SUPERSEDED);
        assertThat(handoff.accept(second).task().disposition())
                .isEqualTo(CommandResult.Disposition.SUPERSEDED);

        ResultFence validFence = fence();
        tasks.put(task("stage", validFence));
        assertThat(handoff.accept(command(
                        "valid-task", validFence, TaskManager.BrainVerdict.APPROVED,
                        BrainVerdictHandoff.Target.LOCAL_DEVELOPMENT))
                .task().disposition()).isEqualTo(CommandResult.Disposition.APPLIED);

        BrainVerdictHandoff.Result replayed = handoff.accept(first);
        CommandResult<TaskManager.State> replay = replayed.task();
        assertThat(replay.disposition()).isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(replayed.stage()).isEmpty();
        assertThat(replay.state())
                .extracting(TaskManager.State::currentStageId, TaskManager.State::version)
                .containsExactly("replacement", 31L);
    }

    private static BrainVerdictHandoff.Command command(
            String taskCommandId,
            ResultFence fence,
            TaskManager.BrainVerdict verdict,
            BrainVerdictHandoff.Target target)
    {
        return new BrainVerdictHandoff.Command(
                new TaskManager.ResultCommand(taskCommandId, "dispatcher", "task", fence),
                verdict,
                target);
    }

    private static TaskManager.State task(String currentStageId, ResultFence pending)
    {
        return new TaskManager.State(
                "task", "trunk", TaskLifecycle.ACTIVE, 7, 31, currentStageId,
                pending, null, null, null);
    }

    private static StageManager.State stage()
    {
        return new StageManager.State(
                "stage", "task", StageKind.LOCAL_DEVELOPMENT, 4, 53,
                StageCheckpoint.BRAIN_REVIEW, null, null);
    }

    private static ResultFence fence()
    {
        return new ResultFence(
                7, "stage", 4, "brain-operation", 2,
                "fingerprint", "head", "base");
    }
}
