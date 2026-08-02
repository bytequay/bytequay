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

import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairRuntime;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator.CiPreconditionStart;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.LocalPublishBaseSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.PlanDraftRetryReceipt;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainProtocolRetryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.StageTurnRetryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Admission;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Episode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Operation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.BrainRetryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchControlReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.CONTINUE_WITH_PER_PUSH_APPROVAL;
import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.EXTEND_BUDGET;
import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.MANUAL_TAKEOVER;
import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.RETRY_ONCE;
import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.START_BASE_REPAIR;
import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.START_BRANCH_SYNC;
import static com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction.STOP_AUTOMATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestV2RecoveryControlService
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    private final TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
    private final RemoteCiRepairRuntimeCoordinator ciRepair =
            mock(RemoteCiRepairRuntimeCoordinator.class);
    private final RemoteObservationRuntimeCoordinator observations =
            mock(RemoteObservationRuntimeCoordinator.class);
    private final SqliteCleanupOperationStore cleanup =
            mock(SqliteCleanupOperationStore.class);
    private final DispatchTicketControl tickets = mock(DispatchTicketControl.class);
    private final PlanRuntimeCoordinator plans = mock(PlanRuntimeCoordinator.class);
    private final LocalDevelopmentRuntimeCoordinator localStages =
            mock(LocalDevelopmentRuntimeCoordinator.class);
    private final RemoteRepairTurnRuntime remoteRepairs =
            mock(RemoteRepairTurnRuntime.class);
    private final LocalPublishBaseSyncRuntimeCoordinator localPublishBaseSync =
            mock(LocalPublishBaseSyncRuntimeCoordinator.class);
    private final BranchSyncRuntimeCoordinator branchSync =
            mock(BranchSyncRuntimeCoordinator.class);
    private final WorktreeQuarantineRepairRuntime worktreeRepairs =
            mock(WorktreeQuarantineRepairRuntime.class);
    private final V2RecoveryControlService controls = new V2RecoveryControlService(
            commands, ciRepair, observations, cleanup, tickets, plans, localStages,
            remoteRepairs, Clock.fixed(NOW, ZoneOffset.UTC));

    {
        controls.setLocalPublishBaseSync(localPublishBaseSync);
        controls.setBranchSync(branchSync);
        controls.setWorktreeRepairs(worktreeRepairs);
    }

    @Test
    void routesBranchSyncTerminalControlWithoutCallingCi()
    {
        when(branchSync.controlExhausted(
                "task-1", "branch-episode-1", "branch-blocker-1",
                "branch-command-1",
                BranchSyncRuntimeCoordinator.BranchControlAction.STOP_AUTOMATION,
                "user", "stop exact exhausted sync"))
                .thenReturn(new BranchControlReceipt(
                        "task-1", "branch-episode-1", "branch-blocker-1",
                        "stage-1", "branch-command-1", "STOP_AUTOMATION",
                        "user", "stop exact exhausted sync", NOW));

        var result = controls.recoverBranchSync(
                "task-1", "branch-episode-1",
                new V2RecoveryControlService.BranchSyncRecoveryCommand(
                        "branch-blocker-1", "branch-command-1",
                        V2RecoveryControlService.BranchSyncRecoveryAction
                                .STOP_AUTOMATION,
                        "stop exact exhausted sync"));

        assertThat(result.status()).isEqualTo("SUPPRESSED");
        assertThat(result.stageId()).isEqualTo("stage-1");
        verifyNoInteractions(ciRepair, observations);
    }

    @Test
    void armsOneExactDurableWorktreeRepair()
    {
        WorktreeQuarantineRepairOperationHandler.Operation operation = mock(
                WorktreeQuarantineRepairOperationHandler.Operation.class);
        when(operation.taskId()).thenReturn("task-1");
        when(operation.stageId()).thenReturn("stage-1");
        when(operation.id()).thenReturn("repair-row-1");
        when(operation.operationId()).thenReturn("repair-operation-1");
        when(operation.ticketId()).thenReturn("repair-ticket-1");
        when(operation.status()).thenReturn("DISPATCHED");
        var admission = new com.bytequay.app.developmentflow.persistence
                .SqliteWorktreeQuarantineRepairStore.Admission(operation, true);
        when(worktreeRepairs.request(
                "task-1", "quarantine-1", "quarantine-blocker-1",
                1, "stage-1", 2, "/worktrees/task-1", "dev/task-1",
                "fingerprint-1", "head-1", "base-1",
                "repair-command-1", "user", "restore exact worktree"))
                .thenReturn(admission);

        var result = controls.recoverWorktree(
                "task-1", "quarantine-1",
                new V2RecoveryControlService.WorktreeRecoveryCommand(
                        "quarantine-blocker-1", 1, "stage-1", 2,
                        "/worktrees/task-1", "dev/task-1",
                        "fingerprint-1", "head-1", "base-1",
                        "repair-command-1",
                        V2RecoveryControlService.WorktreeRecoveryAction
                                .REPAIR_WORKTREE,
                        "restore exact worktree"));

        assertThat(result.operationId()).isEqualTo("repair-operation-1");
        assertThat(result.dispatchTicketId()).isEqualTo("repair-ticket-1");
        assertThat(result.created()).isTrue();
        verify(worktreeRepairs).request(
                "task-1", "quarantine-1", "quarantine-blocker-1",
                1, "stage-1", 2, "/worktrees/task-1", "dev/task-1",
                "fingerprint-1", "head-1", "base-1",
                "repair-command-1", "user", "restore exact worktree");
        verifyNoInteractions(ciRepair, observations);
    }

    @Test
    void approvesOnlyTheExactLocalPublishBaseSyncBlocker()
    {
        Admission admission = mock(Admission.class);
        Episode episode = mock(Episode.class);
        Operation fetch = mock(Operation.class);
        when(admission.episode()).thenReturn(episode);
        when(admission.fetchOperation()).thenReturn(fetch);
        when(episode.id()).thenReturn("base-sync-episode-1");
        when(episode.stageId()).thenReturn("local-stage-1");
        when(fetch.operationId()).thenReturn("base-sync-operation-1");
        when(localPublishBaseSync.approveManual(
                "task-1", "base-sync-blocker-1", "user"))
                .thenReturn(admission);

        V2RecoveryControlService.LocalPublishBaseSyncApprovalResult result =
                controls.approveLocalPublishBaseSync(
                        "task-1", "base-sync-blocker-1");

        assertThat(result.stageId()).isEqualTo("local-stage-1");
        assertThat(result.blockerId()).isEqualTo("base-sync-blocker-1");
        assertThat(result.episodeId()).isEqualTo("base-sync-episode-1");
        assertThat(result.operationId()).isEqualTo("base-sync-operation-1");
        verify(localPublishBaseSync).approveManual(
                "task-1", "base-sync-blocker-1", "user");
    }

    @Test
    void extendsOnlyTheExactExhaustedBaseSyncEpisodeByOneAttempt()
    {
        Admission admission = mock(Admission.class);
        Episode episode = mock(Episode.class);
        Operation fetch = mock(Operation.class);
        when(admission.episode()).thenReturn(episode);
        when(admission.fetchOperation()).thenReturn(fetch);
        when(episode.id()).thenReturn("base-sync-retry-episode-1");
        when(episode.stageId()).thenReturn("local-stage-1");
        when(episode.attemptNo()).thenReturn(4);
        when(episode.attemptLimit()).thenReturn(4);
        when(fetch.operationId()).thenReturn("base-sync-retry-operation-1");
        when(localPublishBaseSync.extendExhausted(
                "task-1", "base-sync-exhausted-episode-1",
                "base-sync-exhausted-blocker-1", "extend-command-1", "user"))
                .thenReturn(admission);

        var result = controls.extendLocalPublishBaseSync(
                "task-1", "base-sync-exhausted-episode-1",
                "base-sync-exhausted-blocker-1",
                new V2RecoveryControlService.LocalPublishBaseSyncExtensionCommand(
                        "extend-command-1", "allow one more exact attempt"));

        assertThat(result.exhaustedEpisodeId())
                .isEqualTo("base-sync-exhausted-episode-1");
        assertThat(result.retryEpisodeId())
                .isEqualTo("base-sync-retry-episode-1");
        assertThat(result.operationId())
                .isEqualTo("base-sync-retry-operation-1");
        assertThat(result.attemptNo()).isEqualTo(4);
        assertThat(result.attemptLimit()).isEqualTo(4);
        verify(localPublishBaseSync).extendExhausted(
                "task-1", "base-sync-exhausted-episode-1",
                "base-sync-exhausted-blocker-1", "extend-command-1", "user");
    }

    @Test
    void exposesCiRecoveryAndProvenBaseRepairChoices()
    {
        CiEpisode open = episode("OPEN");
        CiEpisode stopped = episode("STOPPED");
        when(ciRepair.extendBudget(
                "task-1", "episode-1", "extend", 1, 2, 3,
                "user", "extend budgets"))
                .thenReturn(open);
        when(ciRepair.continueWithPerPushApproval(
                "task-1", "episode-1", "per-push", "user",
                "approve one push"))
                .thenReturn(open);
        when(ciRepair.manualTakeover(
                "task-1", "episode-1", "manual", "user", "take over"))
                .thenReturn(stopped);
        when(ciRepair.stopAutomation(
                "task-1", "episode-1", "stop", "user", "stop"))
                .thenReturn(stopped);
        when(ciRepair.startBaseRepair(
                "task-1", "episode-1", "base-blocker-1", "base-repair", "user",
                "repair proven base failure"))
                .thenReturn(open);
        when(ciRepair.retryNoChange(
                "task-1", "episode-1", "no-change-blocker-1",
                "retry-once", "user", "retry no-change once"))
                .thenReturn(open);
        when(observations.requestObservation("task-1", "stage-1"))
                .thenReturn(new ObservationRequest(
                        "observation-row", "observation-operation",
                        "task-1", "stage-1", 2, NOW));
        ObservationRequest branchObservation = new ObservationRequest(
                "branch-observation-row", "branch-observation-operation",
                "task-1", "stage-1", 3, NOW);
        when(branchSync.startCiPrecondition(
                "task-1", "episode-1", "branch-blocker-1",
                "start-branch", "user", "sync before CI"))
                .thenReturn(new CiPreconditionStart(
                        open, null, branchObservation));

        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "extend", EXTEND_BUDGET, 1, 2, 3,
                        "extend budgets")).status())
                .isEqualTo("OPEN");
        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "per-push", CONTINUE_WITH_PER_PUSH_APPROVAL,
                        0, 0, 0, "approve one push"))
                .observationOperationId()).isEqualTo("observation-operation");
        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "manual", MANUAL_TAKEOVER, 0, 0, 0,
                        "take over")).status())
                .isEqualTo("STOPPED");
        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "stop", STOP_AUTOMATION, 0, 0, 0, "stop")).status())
                .isEqualTo("STOPPED");
        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "base-repair", "base-blocker-1", START_BASE_REPAIR,
                        0, 0, 0,
                        "repair proven base failure")).status())
                .isEqualTo("OPEN");
        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "retry-once", "no-change-blocker-1", RETRY_ONCE,
                        0, 0, 0, "retry no-change once"))
                .observationOperationId()).isEqualTo("observation-operation");
        assertThat(controls.recoverCi(
                "task-1", "episode-1",
                new V2RecoveryControlService.CiRecoveryCommand(
                        "start-branch", "branch-blocker-1", START_BRANCH_SYNC,
                        0, 0, 0, "sync before CI"))
                .observationOperationId())
                .isEqualTo("branch-observation-operation");

        verify(ciRepair).extendBudget(
                "task-1", "episode-1", "extend", 1, 2, 3,
                "user", "extend budgets");
        verify(ciRepair).continueWithPerPushApproval(
                "task-1", "episode-1", "per-push", "user",
                "approve one push");
        verify(ciRepair).manualTakeover(
                "task-1", "episode-1", "manual", "user", "take over");
        verify(ciRepair).stopAutomation(
                "task-1", "episode-1", "stop", "user", "stop");
        verify(ciRepair).startBaseRepair(
                "task-1", "episode-1", "base-blocker-1", "base-repair", "user",
                "repair proven base failure");
        verify(ciRepair).retryNoChange(
                "task-1", "episode-1", "no-change-blocker-1",
                "retry-once", "user", "retry no-change once");
        verify(branchSync).startCiPrecondition(
                "task-1", "episode-1", "branch-blocker-1",
                "start-branch", "user", "sync before CI");
    }

    @Test
    void rejectsBudgetDeltasForNonExtensionChoices()
    {
        assertThatThrownBy(() ->
                controls.recoverCi(
                        "task-1", "episode-1",
                        new V2RecoveryControlService.CiRecoveryCommand(
                                "stop", STOP_AUTOMATION, 0, 1, 0, "stop")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only EXTEND_BUDGET accepts positive CI budget deltas");
        verifyNoInteractions(ciRepair, observations);
    }

    @Test
    void requiresTheExactBlockerForManualBaseRepair()
    {
        assertThatThrownBy(() ->
                controls.recoverCi(
                        "task-1", "episode-1",
                        new V2RecoveryControlService.CiRecoveryCommand(
                                "base-repair", START_BASE_REPAIR,
                                0, 0, 0, "repair proven base failure")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("blockerId is required for START_BASE_REPAIR");
        assertThatThrownBy(() ->
                controls.recoverCi(
                        "task-1", "episode-1",
                        new V2RecoveryControlService.CiRecoveryCommand(
                                "base-repair", " ", START_BASE_REPAIR,
                                0, 0, 0, "repair proven base failure")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("blockerId is required for START_BASE_REPAIR");

        verifyNoInteractions(ciRepair, observations);
    }

    @Test
    void routesPlanRecoveryToTheExactFailedTurnAndBlocker()
    {
        when(plans.retryFailedDraft(
                "task-1", "turn-1", "blocker-1", "plan-command-1",
                "user", "retry planning"))
                .thenReturn(new PlanDraftRetryReceipt(
                        "plan-command-1", "user", "retry planning",
                        "task-1", 2, "stage-1", 1, 3, 4,
                        "blocker-1", "turn-1", "operation-1",
                        "turn-2", "operation-2", "ticket-2", NOW));

        V2RecoveryControlService.PlanRecoveryResult result =
                controls.recoverPlanDraft(
                        "task-1", "turn-1",
                        new V2RecoveryControlService.PlanRecoveryCommand(
                                "blocker-1", "plan-command-1",
                                "retry planning"));

        assertThat(result.replacementTurnId()).isEqualTo("turn-2");
        assertThat(result.replacementOperationId()).isEqualTo("operation-2");
        verify(plans).retryFailedDraft(
                "task-1", "turn-1", "blocker-1", "plan-command-1",
                "user", "retry planning");
    }

    @Test
    void routesLocalStageRecoveryToTheExactFailedTurnAndBlocker()
    {
        when(localStages.retryFailedStageTurn(
                "task-1", "turn-1", "blocker-1", "stage-command-1",
                "user", "retry development"))
                .thenReturn(new StageTurnRetryReceipt(
                        "retry-1", "task-1", "stage-1", 2,
                        "stage-command-1", "user", "retry development",
                        "blocker-1", "turn-1", "request-2", "turn-2",
                        "operation-2", "ticket-2", NOW));

        V2RecoveryControlService.LocalStageRecoveryResult result =
                controls.recoverLocalStageTurn(
                        "task-1", "turn-1",
                        new V2RecoveryControlService.LocalStageRecoveryCommand(
                                "blocker-1", "stage-command-1",
                                "retry development"));

        assertThat(result.replacementTurnId()).isEqualTo("turn-2");
        assertThat(result.replacementOperationId()).isEqualTo("operation-2");
        verify(localStages).retryFailedStageTurn(
                "task-1", "turn-1", "blocker-1", "stage-command-1",
                "user", "retry development");
    }

    @Test
    void routesDevelopmentBrainRecoveryToTheExactFailedTurnAndBlocker()
    {
        when(localStages.retryFailedBrainReview(
                "task-1", "turn-1", "blocker-1", "brain-command-1",
                "user", "retry malformed Brain output"))
                .thenReturn(new BrainProtocolRetryReceipt(
                        "retry-1", "task-1", "stage-1", 2,
                        "brain-command-1", "user",
                        "retry malformed Brain output", "blocker-1", "turn-1",
                        "episode-2", "turn-2", "operation-2", "ticket-2", NOW));

        V2RecoveryControlService.DevelopmentBrainRecoveryResult result =
                controls.recoverDevelopmentBrain(
                        "task-1", "turn-1",
                        new V2RecoveryControlService.DevelopmentBrainRecoveryCommand(
                                "blocker-1", "brain-command-1",
                                "retry malformed Brain output"));

        assertThat(result.replacementEpisodeId()).isEqualTo("episode-2");
        assertThat(result.replacementTurnId()).isEqualTo("turn-2");
        assertThat(result.replacementOperationId()).isEqualTo("operation-2");
        assertThat(result.replacementTicketId()).isEqualTo("ticket-2");
        verify(localStages).retryFailedBrainReview(
                "task-1", "turn-1", "blocker-1", "brain-command-1",
                "user", "retry malformed Brain output");
    }

    @Test
    void routesBranchRepairBrainRecoveryToTheExactFailedTurnAndBlocker()
    {
        when(remoteRepairs.retryFailedBrain(
                "task-1", "turn-1", "blocker-1", "brain-command-1",
                "user", "retry failed Remote Brain review"))
                .thenReturn(new BrainRetryReceipt(
                        "BRANCH", "task-1", "stage-1", "episode-1", "turn-1",
                        "blocker-1", "brain-command-1", "user",
                        "retry failed Remote Brain review", "turn-2",
                        "operation-2", "ticket-2", NOW));

        V2RecoveryControlService.RemoteRepairBrainRecoveryResult result =
                controls.recoverRemoteRepairBrain(
                        "task-1", "turn-1",
                        new V2RecoveryControlService.RemoteRepairBrainRecoveryCommand(
                                "blocker-1", "brain-command-1",
                                "retry failed Remote Brain review"));

        assertThat(result.family()).isEqualTo("BRANCH");
        assertThat(result.episodeId()).isEqualTo("episode-1");
        assertThat(result.replacementTurnId()).isEqualTo("turn-2");
        assertThat(result.replacementOperationId()).isEqualTo("operation-2");
        assertThat(result.replacementTicketId()).isEqualTo("ticket-2");
        verify(remoteRepairs).retryFailedBrain(
                "task-1", "turn-1", "blocker-1", "brain-command-1",
                "user", "retry failed Remote Brain review");
    }

    private static CiEpisode episode(String status)
    {
        return new CiEpisode(
                "episode-1", "stage-1", "task-1", 1, 1,
                "binding-1", "evaluation-1", "head-1", "base-1",
                "FLAKY", status, 1, 2, 1, 3, 0, 3,
                1, 4, null, null, null, NOW,
                "STOPPED".equals(status) ? NOW : null, null);
    }
}
