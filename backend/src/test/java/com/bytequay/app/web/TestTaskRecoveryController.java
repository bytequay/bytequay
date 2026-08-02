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
package com.bytequay.app.web;

import com.bytequay.app.developmentflow.task.V2RecoveryControlService;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.BranchSyncRecoveryAction;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.BranchSyncRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.BranchSyncRecoveryResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryAction;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CiRecoveryResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CleanupRecoveryAction;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CleanupRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.CleanupRecoveryResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.DevelopmentBrainRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.DevelopmentBrainRecoveryResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.LocalPublishBaseSyncApprovalResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.LocalPublishBaseSyncExtensionCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.LocalPublishBaseSyncExtensionResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.LocalStageRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.LocalStageRecoveryResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.PlanRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.PlanRecoveryResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.RemoteRepairBrainRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.RemoteRepairBrainRecoveryResult;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.WorktreeRecoveryAction;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.WorktreeRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.WorktreeRecoveryResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskRecoveryController.class)
class TestTaskRecoveryController
{
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private V2RecoveryControlService recovery;

    @Test
    void routesTheExactLocalPublishBaseSyncBlockerApproval()
            throws Exception
    {
        when(recovery.approveLocalPublishBaseSync(
                "task-1", "base-sync-blocker-1"))
                .thenReturn(new LocalPublishBaseSyncApprovalResult(
                        "task-1", "local-stage-1", "base-sync-blocker-1",
                        "base-sync-episode-1", "base-sync-operation-1"));

        mvc.perform(post("/api/tasks/task-1/local-publish/base-sync/"
                        + "blockers/base-sync-blocker-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.stageId").value("local-stage-1"))
                .andExpect(jsonPath("$.blockerId")
                        .value("base-sync-blocker-1"))
                .andExpect(jsonPath("$.episodeId")
                        .value("base-sync-episode-1"))
                .andExpect(jsonPath("$.operationId")
                        .value("base-sync-operation-1"));

        verify(recovery).approveLocalPublishBaseSync(
                "task-1", "base-sync-blocker-1");
    }

    @Test
    void routesOneExactLocalPublishBaseSyncBudgetExtension()
            throws Exception
    {
        LocalPublishBaseSyncExtensionCommand command =
                new LocalPublishBaseSyncExtensionCommand(
                        "extend-command-1", "allow one more exact attempt");
        when(recovery.extendLocalPublishBaseSync(
                "task-1", "base-sync-episode-3", "base-sync-blocker-3",
                command))
                .thenReturn(new LocalPublishBaseSyncExtensionResult(
                        "task-1", "local-stage-1", "base-sync-episode-3",
                        "base-sync-blocker-3", "extend-command-1",
                        "base-sync-episode-4", "base-sync-operation-4", 4, 4));

        mvc.perform(post("/api/tasks/task-1/local-publish/base-sync/episodes/"
                        + "base-sync-episode-3/blockers/base-sync-blocker-3/extend")
                        .contentType("application/json")
                        .content("""
                                {"commandId":"extend-command-1",
                                 "reason":"allow one more exact attempt"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exhaustedEpisodeId")
                        .value("base-sync-episode-3"))
                .andExpect(jsonPath("$.retryEpisodeId")
                        .value("base-sync-episode-4"))
                .andExpect(jsonPath("$.attemptNo").value(4))
                .andExpect(jsonPath("$.attemptLimit").value(4));

        verify(recovery).extendLocalPublishBaseSync(
                "task-1", "base-sync-episode-3", "base-sync-blocker-3",
                command);
    }

    @Test
    void routesAnExactCiRecoveryChoice()
            throws Exception
    {
        CiRecoveryCommand command = new CiRecoveryCommand(
                "ci-command-1", CiRecoveryAction.EXTEND_BUDGET,
                1, 2, 3, "allow another repair");
        when(recovery.recoverCi("task-1", "episode-1", command))
                .thenReturn(new CiRecoveryResult(
                        "task-1", "episode-1", "ci-command-1",
                        CiRecoveryAction.EXTEND_BUDGET, "OPEN",
                        2, 3, 4, "observation-2"));

        mvc.perform(post("/api/tasks/task-1/ci-repair/episode-1/recover")
                        .contentType("application/json")
                        .content("""
                                {"commandId":"ci-command-1",
                                 "action":"EXTEND_BUDGET",
                                 "rerunDelta":1,"fixDelta":2,"pushDelta":3,
                                 "reason":"allow another repair"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeId").value("episode-1"))
                .andExpect(jsonPath("$.action").value("EXTEND_BUDGET"))
                .andExpect(jsonPath("$.observationOperationId")
                        .value("observation-2"));

        verify(recovery).recoverCi("task-1", "episode-1", command);
    }

    @Test
    void routesAnExactBranchSyncTerminalChoiceOutsideCi()
            throws Exception
    {
        BranchSyncRecoveryCommand command = new BranchSyncRecoveryCommand(
                "branch-blocker-1", "branch-command-1",
                BranchSyncRecoveryAction.MANUAL_TAKEOVER,
                "take over exact exhausted sync");
        when(recovery.recoverBranchSync(
                "task-1", "branch-episode-1", command))
                .thenReturn(new BranchSyncRecoveryResult(
                        "task-1", "branch-episode-1", "branch-blocker-1",
                        "stage-1", "branch-command-1",
                        BranchSyncRecoveryAction.MANUAL_TAKEOVER,
                        "SUPPRESSED"));

        mvc.perform(post("/api/tasks/task-1/branch-sync/"
                        + "branch-episode-1/recover")
                        .contentType("application/json")
                        .content("""
                                {"blockerId":"branch-blocker-1",
                                 "commandId":"branch-command-1",
                                 "action":"MANUAL_TAKEOVER",
                                 "reason":"take over exact exhausted sync"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeId")
                        .value("branch-episode-1"))
                .andExpect(jsonPath("$.status").value("SUPPRESSED"));

        verify(recovery).recoverBranchSync(
                "task-1", "branch-episode-1", command);
    }

    @Test
    void routesOneExactDurableWorktreeRepair()
            throws Exception
    {
        WorktreeRecoveryCommand command = new WorktreeRecoveryCommand(
                "quarantine-blocker-1", 1, "stage-1", 2,
                "/worktrees/task-1", "dev/task-1", "fingerprint-1",
                "head-1", "base-1", "repair-command-1",
                WorktreeRecoveryAction.REPAIR_WORKTREE,
                "restore frozen worktree subject");
        when(recovery.recoverWorktree("task-1", "quarantine-1", command))
                .thenReturn(new WorktreeRecoveryResult(
                        "task-1", "stage-1", "quarantine-1",
                        "quarantine-blocker-1", "repair-command-1",
                        WorktreeRecoveryAction.REPAIR_WORKTREE,
                        "repair-row-1", "repair-operation-1",
                        "repair-ticket-1", "DISPATCHED", true));

        mvc.perform(post("/api/tasks/task-1/worktree-quarantines/"
                        + "quarantine-1/recover")
                        .contentType("application/json")
                        .content("""
                                {"blockerId":"quarantine-blocker-1",
                                 "taskEpoch":1,
                                 "stageId":"stage-1",
                                 "stageGeneration":2,
                                 "worktreePath":"/worktrees/task-1",
                                 "expectedBranchName":"dev/task-1",
                                 "expectedCodeFingerprint":"fingerprint-1",
                                 "expectedHeadSha":"head-1",
                                 "expectedBaseSha":"base-1",
                                 "commandId":"repair-command-1",
                                 "action":"REPAIR_WORKTREE",
                                 "reason":"restore frozen worktree subject"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantineId")
                        .value("quarantine-1"))
                .andExpect(jsonPath("$.operationId")
                        .value("repair-operation-1"))
                .andExpect(jsonPath("$.dispatchTicketId")
                        .value("repair-ticket-1"));

        verify(recovery).recoverWorktree(
                "task-1", "quarantine-1", command);
    }

    @Test
    void routesAnExactCleanupRecoveryChoice()
            throws Exception
    {
        CleanupRecoveryCommand command = new CleanupRecoveryCommand(
                "cleanup-command-1", CleanupRecoveryAction.WAIVE_OPTIONAL,
                "leave merged remote branch");
        when(recovery.recoverCleanup("task-1", "step-10", command))
                .thenReturn(new CleanupRecoveryResult(
                        "task-1", "step-10", "cleanup-command-1",
                        CleanupRecoveryAction.WAIVE_OPTIONAL,
                        "cleanup-1", "ticket-1", true, true));

        mvc.perform(post("/api/tasks/task-1/cleanup/steps/step-10/recover")
                        .contentType("application/json")
                        .content("""
                                {"commandId":"cleanup-command-1",
                                 "action":"WAIVE_OPTIONAL",
                                 "reason":"leave merged remote branch"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stepId").value("step-10"))
                .andExpect(jsonPath("$.action").value("WAIVE_OPTIONAL"))
                .andExpect(jsonPath("$.dispatchTicketId").value("ticket-1"))
                .andExpect(jsonPath("$.rearmed").value(true));

        verify(recovery).recoverCleanup("task-1", "step-10", command);
    }

    @Test
    void routesAnExactFailedPlanDraftRecovery()
            throws Exception
    {
        PlanRecoveryCommand command = new PlanRecoveryCommand(
                "blocker-1", "plan-command-1", "retry failed planning");
        when(recovery.recoverPlanDraft("task-1", "turn-1", command))
                .thenReturn(new PlanRecoveryResult(
                        "task-1", "turn-1", "blocker-1", "plan-command-1",
                        "turn-2", "operation-2", "ticket-2"));

        mvc.perform(post("/api/tasks/task-1/plan/turns/turn-1/recover")
                        .contentType("application/json")
                        .content("""
                                {"blockerId":"blocker-1",
                                 "commandId":"plan-command-1",
                                 "reason":"retry failed planning"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedTurnId").value("turn-1"))
                .andExpect(jsonPath("$.blockerId").value("blocker-1"))
                .andExpect(jsonPath("$.replacementTurnId").value("turn-2"));

        verify(recovery).recoverPlanDraft("task-1", "turn-1", command);
    }

    @Test
    void routesAnExactFailedLocalStageTurnRecovery()
            throws Exception
    {
        LocalStageRecoveryCommand command = new LocalStageRecoveryCommand(
                "blocker-1", "stage-command-1", "retry development");
        when(recovery.recoverLocalStageTurn("task-1", "turn-1", command))
                .thenReturn(new LocalStageRecoveryResult(
                        "task-1", "stage-1", "turn-1", "blocker-1",
                        "stage-command-1", "turn-2", "operation-2", "ticket-2"));

        mvc.perform(post("/api/tasks/task-1/local-development/turns/turn-1/recover")
                        .contentType("application/json")
                        .content("""
                                {"blockerId":"blocker-1",
                                 "commandId":"stage-command-1",
                                 "reason":"retry development"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedTurnId").value("turn-1"))
                .andExpect(jsonPath("$.blockerId").value("blocker-1"))
                .andExpect(jsonPath("$.replacementTurnId").value("turn-2"));

        verify(recovery).recoverLocalStageTurn("task-1", "turn-1", command);
    }

    @Test
    void routesAnExactFailedDevelopmentBrainRecovery()
            throws Exception
    {
        DevelopmentBrainRecoveryCommand command =
                new DevelopmentBrainRecoveryCommand(
                        "blocker-1", "brain-command-1",
                        "retry malformed Brain output");
        when(recovery.recoverDevelopmentBrain("task-1", "turn-1", command))
                .thenReturn(new DevelopmentBrainRecoveryResult(
                        "task-1", "stage-1", "turn-1", "blocker-1",
                        "brain-command-1", "episode-2", "turn-2",
                        "operation-2", "ticket-2"));

        mvc.perform(post("/api/tasks/task-1/local-development/brain-turns/turn-1/recover")
                        .contentType("application/json")
                        .content("""
                                {"blockerId":"blocker-1",
                                 "commandId":"brain-command-1",
                                 "reason":"retry malformed Brain output"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedTurnId").value("turn-1"))
                .andExpect(jsonPath("$.blockerId").value("blocker-1"))
                .andExpect(jsonPath("$.replacementEpisodeId")
                        .value("episode-2"))
                .andExpect(jsonPath("$.replacementTurnId").value("turn-2"))
                .andExpect(jsonPath("$.replacementOperationId")
                        .value("operation-2"))
                .andExpect(jsonPath("$.replacementTicketId")
                        .value("ticket-2"));

        verify(recovery).recoverDevelopmentBrain("task-1", "turn-1", command);
    }

    @Test
    void routesAnExactFailedRemoteRepairBrainRecovery()
            throws Exception
    {
        RemoteRepairBrainRecoveryCommand command =
                new RemoteRepairBrainRecoveryCommand(
                        "blocker-1", "brain-command-1",
                        "retry failed Remote Brain review");
        when(recovery.recoverRemoteRepairBrain("task-1", "turn-1", command))
                .thenReturn(new RemoteRepairBrainRecoveryResult(
                        "CI", "task-1", "stage-1", "episode-1", "turn-1",
                        "blocker-1", "brain-command-1", "turn-2",
                        "operation-2", "ticket-2"));

        mvc.perform(post("/api/tasks/task-1/remote-repair/brain-turns/turn-1/recover")
                        .contentType("application/json")
                        .content("""
                                {"blockerId":"blocker-1",
                                 "commandId":"brain-command-1",
                                 "reason":"retry failed Remote Brain review"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.family").value("CI"))
                .andExpect(jsonPath("$.episodeId").value("episode-1"))
                .andExpect(jsonPath("$.failedTurnId").value("turn-1"))
                .andExpect(jsonPath("$.replacementTurnId").value("turn-2"))
                .andExpect(jsonPath("$.replacementOperationId")
                        .value("operation-2"))
                .andExpect(jsonPath("$.replacementTicketId")
                        .value("ticket-2"));

        verify(recovery).recoverRemoteRepairBrain(
                "task-1", "turn-1", command);
    }
}
