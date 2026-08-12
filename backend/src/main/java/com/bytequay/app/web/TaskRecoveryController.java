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
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.BranchSyncRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.BranchSyncRecoveryResult;
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
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.WorktreeRecoveryCommand;
import com.bytequay.app.developmentflow.task.V2RecoveryControlService.WorktreeRecoveryResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** Explicit recovery choices for exact V2 CI episodes and Cleanup steps. */
@RestController
public class TaskRecoveryController
{
    private final V2RecoveryControlService recovery;

    public TaskRecoveryController(V2RecoveryControlService recovery)
    {
        this.recovery = requireNonNull(recovery, "recovery is null");
    }

    @PostMapping("/api/tasks/{taskId}/branch-sync/{episodeId}/recover")
    public BranchSyncRecoveryResult recoverBranchSync(
            @PathVariable String taskId,
            @PathVariable String episodeId,
            @RequestBody BranchSyncRecoveryCommand command)
    {
        return recovery.recoverBranchSync(taskId, episodeId, command);
    }

    @PostMapping("/api/tasks/{taskId}/worktree-quarantines/"
            + "{quarantineId}/recover")
    public WorktreeRecoveryResult recoverWorktree(
            @PathVariable String taskId,
            @PathVariable String quarantineId,
            @RequestBody WorktreeRecoveryCommand command)
    {
        return recovery.recoverWorktree(taskId, quarantineId, command);
    }

    @PostMapping("/api/tasks/{taskId}/local-publish/base-sync/blockers/"
            + "{blockerId}/approve")
    public LocalPublishBaseSyncApprovalResult approveLocalPublishBaseSync(
            @PathVariable String taskId,
            @PathVariable String blockerId)
    {
        return recovery.approveLocalPublishBaseSync(taskId, blockerId);
    }

    @PostMapping("/api/tasks/{taskId}/local-publish/base-sync/episodes/"
            + "{episodeId}/blockers/{blockerId}/extend")
    public LocalPublishBaseSyncExtensionResult extendLocalPublishBaseSync(
            @PathVariable String taskId,
            @PathVariable String episodeId,
            @PathVariable String blockerId,
            @RequestBody LocalPublishBaseSyncExtensionCommand command)
    {
        return recovery.extendLocalPublishBaseSync(
                taskId, episodeId, blockerId, command);
    }

    @PostMapping("/api/tasks/{taskId}/cleanup/steps/{stepId}/recover")
    public CleanupRecoveryResult recoverCleanup(
            @PathVariable String taskId,
            @PathVariable String stepId,
            @RequestBody CleanupRecoveryCommand command)
    {
        return recovery.recoverCleanup(taskId, stepId, command);
    }

    @PostMapping("/api/tasks/{taskId}/plan/turns/{failedTurnId}/recover")
    public PlanRecoveryResult recoverPlanDraft(
            @PathVariable String taskId,
            @PathVariable String failedTurnId,
            @RequestBody PlanRecoveryCommand command)
    {
        return recovery.recoverPlanDraft(taskId, failedTurnId, command);
    }

    @PostMapping("/api/tasks/{taskId}/local-development/turns/"
            + "{failedTurnId}/recover")
    public LocalStageRecoveryResult recoverLocalStageTurn(
            @PathVariable String taskId,
            @PathVariable String failedTurnId,
            @RequestBody LocalStageRecoveryCommand command)
    {
        return recovery.recoverLocalStageTurn(taskId, failedTurnId, command);
    }

    @PostMapping("/api/tasks/{taskId}/local-development/brain-turns/"
            + "{failedTurnId}/recover")
    public DevelopmentBrainRecoveryResult recoverDevelopmentBrain(
            @PathVariable String taskId,
            @PathVariable String failedTurnId,
            @RequestBody DevelopmentBrainRecoveryCommand command)
    {
        return recovery.recoverDevelopmentBrain(taskId, failedTurnId, command);
    }

    @PostMapping("/api/tasks/{taskId}/remote-repair/brain-turns/"
            + "{failedTurnId}/recover")
    public RemoteRepairBrainRecoveryResult recoverRemoteRepairBrain(
            @PathVariable String taskId,
            @PathVariable String failedTurnId,
            @RequestBody RemoteRepairBrainRecoveryCommand command)
    {
        return recovery.recoverRemoteRepairBrain(
                taskId, failedTurnId, command);
    }
}
