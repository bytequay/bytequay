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
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore.RecoveryAuthorization;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairRuntime;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator.BranchControlAction;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.LocalPublishBaseSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.PlanDraftRetryReceipt;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Admission;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

import static java.util.Objects.requireNonNull;

/** Exact owner commands for V2 CI exhaustion and Cleanup recovery. */
@Component
public final class V2RecoveryControlService
{
    private static final String ACTOR = "user";

    private final TaskCommandExecutor commands;
    private final SqliteCleanupOperationStore cleanup;
    private final DispatchTicketControl tickets;
    private final PlanRuntimeCoordinator plans;
    private final LocalDevelopmentRuntimeCoordinator localStages;
    private final RemoteRepairTurnRuntime remoteRepairs;
    private final Clock clock;
    private LocalPublishBaseSyncRuntimeCoordinator localPublishBaseSync;
    private BranchSyncRuntimeCoordinator branchSync;
    private WorktreeQuarantineRepairRuntime worktreeRepairs;

    @Autowired
    public V2RecoveryControlService(
            TaskCommandExecutor commands,
            SqliteCleanupOperationStore cleanup,
            DispatchTicketControl tickets,
            PlanRuntimeCoordinator plans,
            LocalDevelopmentRuntimeCoordinator localStages,
            RemoteRepairTurnRuntime remoteRepairs)
    {
        this(commands, cleanup, tickets, plans,
                localStages, remoteRepairs, Clock.systemUTC());
    }

    V2RecoveryControlService(
            TaskCommandExecutor commands,
            SqliteCleanupOperationStore cleanup,
            DispatchTicketControl tickets,
            PlanRuntimeCoordinator plans,
            LocalDevelopmentRuntimeCoordinator localStages,
            RemoteRepairTurnRuntime remoteRepairs,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.cleanup = requireNonNull(cleanup, "cleanup is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.plans = requireNonNull(plans, "plans is null");
        this.localStages = requireNonNull(localStages, "localStages is null");
        this.remoteRepairs = requireNonNull(remoteRepairs, "remoteRepairs is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Autowired
    void setLocalPublishBaseSync(
            LocalPublishBaseSyncRuntimeCoordinator localPublishBaseSync)
    {
        this.localPublishBaseSync = requireNonNull(
                localPublishBaseSync, "localPublishBaseSync is null");
    }

    @Autowired
    void setBranchSync(BranchSyncRuntimeCoordinator branchSync)
    {
        this.branchSync = requireNonNull(branchSync, "branchSync is null");
    }

    @Autowired
    void setWorktreeRepairs(WorktreeQuarantineRepairRuntime worktreeRepairs)
    {
        this.worktreeRepairs = requireNonNull(
                worktreeRepairs, "worktreeRepairs is null");
    }

    /** Grants user authority only to the exact open first-publish blocker. */
    public LocalPublishBaseSyncApprovalResult approveLocalPublishBaseSync(
            String taskId, String blockerId)
    {
        requireText(taskId, "taskId");
        requireText(blockerId, "blockerId");
        try {
            Admission accepted = requireLocalPublishBaseSync().approveManual(
                    taskId, blockerId, ACTOR);
            return new LocalPublishBaseSyncApprovalResult(
                    taskId, accepted.episode().stageId(), blockerId,
                    accepted.episode().id(),
                    accepted.fetchOperation().operationId());
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw conflict(
                    "Local publish base sync does not own the exact blocker",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    /** Extends only the exact exhausted local base-sync episode by one attempt. */
    public LocalPublishBaseSyncExtensionResult extendLocalPublishBaseSync(
            String taskId,
            String episodeId,
            String blockerId,
            LocalPublishBaseSyncExtensionCommand command)
    {
        requireText(taskId, "taskId");
        requireText(episodeId, "episodeId");
        requireText(blockerId, "blockerId");
        requireNonNull(command, "command is null");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        try {
            Admission accepted = requireLocalPublishBaseSync().extendExhausted(
                    taskId, episodeId, blockerId, command.commandId(), ACTOR);
            return new LocalPublishBaseSyncExtensionResult(
                    taskId, accepted.episode().stageId(), episodeId, blockerId,
                    command.commandId(), accepted.episode().id(),
                    accepted.fetchOperation().operationId(),
                    accepted.episode().attemptNo(),
                    accepted.episode().attemptLimit());
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw conflict(
                    "Local publish base sync does not own the exact exhausted episode",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    public LocalStageRecoveryResult recoverLocalStageTurn(
            String taskId,
            String failedTurnId,
            LocalStageRecoveryCommand command)
    {
        requireText(taskId, "taskId");
        requireText(failedTurnId, "failedTurnId");
        requireNonNull(command, "command is null");
        requireText(command.blockerId(), "blockerId");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        try {
            var accepted = localStages.retryFailedStageTurn(
                    taskId, failedTurnId, command.blockerId(),
                    command.commandId(), ACTOR, command.reason());
            return new LocalStageRecoveryResult(
                    accepted.taskId(), accepted.stageId(),
                    accepted.failedTurnId(), accepted.blockerId(),
                    accepted.commandId(), accepted.replacementTurnId(),
                    accepted.replacementOperationId(),
                    accepted.replacementTicketId());
        }
        catch (CommandRejectedException | DataAccessException
                | IllegalStateException failure) {
            throw conflict(
                    "Local Stage recovery does not own the exact failed Turn",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    public DevelopmentBrainRecoveryResult recoverDevelopmentBrain(
            String taskId,
            String failedTurnId,
            DevelopmentBrainRecoveryCommand command)
    {
        requireText(taskId, "taskId");
        requireText(failedTurnId, "failedTurnId");
        requireNonNull(command, "command is null");
        requireText(command.blockerId(), "blockerId");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        try {
            var accepted = localStages.retryFailedBrainReview(
                    taskId, failedTurnId, command.blockerId(),
                    command.commandId(), ACTOR, command.reason());
            return new DevelopmentBrainRecoveryResult(
                    accepted.taskId(), accepted.stageId(),
                    accepted.failedTurnId(), accepted.blockerId(),
                    accepted.commandId(), accepted.replacementEpisodeId(),
                    accepted.replacementTurnId(),
                    accepted.replacementOperationId(),
                    accepted.replacementTicketId());
        }
        catch (CommandRejectedException | DataAccessException
                | IllegalStateException failure) {
            throw conflict(
                    "Development Brain recovery does not own the exact failed Turn",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    public RemoteRepairBrainRecoveryResult recoverRemoteRepairBrain(
            String taskId,
            String failedTurnId,
            RemoteRepairBrainRecoveryCommand command)
    {
        requireText(taskId, "taskId");
        requireText(failedTurnId, "failedTurnId");
        requireNonNull(command, "command is null");
        requireText(command.blockerId(), "blockerId");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        try {
            var accepted = remoteRepairs.retryFailedBrain(
                    taskId, failedTurnId, command.blockerId(),
                    command.commandId(), ACTOR, command.reason());
            return new RemoteRepairBrainRecoveryResult(
                    accepted.family(), accepted.taskId(), accepted.stageId(),
                    accepted.episodeId(), accepted.failedTurnId(),
                    accepted.blockerId(), accepted.commandId(),
                    accepted.replacementTurnId(),
                    accepted.replacementOperationId(),
                    accepted.replacementTicketId());
        }
        catch (CommandRejectedException | DataAccessException
                | IllegalStateException failure) {
            throw conflict(
                    "Remote repair Brain recovery does not own the exact failed Turn",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    public PlanRecoveryResult recoverPlanDraft(
            String taskId,
            String failedTurnId,
            PlanRecoveryCommand command)
    {
        requireText(taskId, "taskId");
        requireText(failedTurnId, "failedTurnId");
        requireNonNull(command, "command is null");
        requireText(command.blockerId(), "blockerId");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        try {
            PlanDraftRetryReceipt accepted = plans.retryFailedDraft(
                            taskId, failedTurnId, command.blockerId(),
                            command.commandId(), ACTOR, command.reason());
            return new PlanRecoveryResult(
                    accepted.taskId(), accepted.failedTurnId(),
                    accepted.blockerId(), accepted.commandId(),
                    accepted.replacementTurnId(),
                    accepted.replacementOperationId(),
                    accepted.replacementTicketId());
        }
        catch (CommandRejectedException | DataAccessException
                | IllegalStateException failure) {
            throw conflict(
                    "Plan recovery does not own the exact failed draft",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    /** Terminalizes one exact exhausted BranchSync episode; it never calls CI. */
    public BranchSyncRecoveryResult recoverBranchSync(
            String taskId,
            String episodeId,
            BranchSyncRecoveryCommand command)
    {
        requireText(taskId, "taskId");
        requireText(episodeId, "episodeId");
        requireNonNull(command, "command is null");
        requireText(command.blockerId(), "blockerId");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        requireNonNull(command.action(), "action is null");
        try {
            var receipt = requireBranchSync().controlExhausted(
                    taskId, episodeId, command.blockerId(),
                    command.commandId(),
                    BranchControlAction.valueOf(command.action().name()),
                    ACTOR, command.reason());
            return new BranchSyncRecoveryResult(
                    receipt.taskId(), receipt.episodeId(), receipt.blockerId(),
                    receipt.stageId(), receipt.commandId(), command.action(),
                    "SUPPRESSED");
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw conflict(
                    "BranchSync recovery does not own the exact exhausted episode",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    /** Arms one exact durable local-Git repair; no Git runs in this command. */
    public WorktreeRecoveryResult recoverWorktree(
            String taskId,
            String quarantineId,
            WorktreeRecoveryCommand command)
    {
        requireText(taskId, "taskId");
        requireText(quarantineId, "quarantineId");
        requireNonNull(command, "command is null");
        requireText(command.blockerId(), "blockerId");
        if (command.taskEpoch() < 1 || command.stageGeneration() < 1) {
            throw new IllegalArgumentException(
                    "Worktree recovery epoch or Stage generation is invalid");
        }
        requireText(command.stageId(), "stageId");
        requireText(command.worktreePath(), "worktreePath");
        requireText(command.expectedBranchName(), "expectedBranchName");
        requireText(command.expectedCodeFingerprint(),
                "expectedCodeFingerprint");
        requireText(command.expectedHeadSha(), "expectedHeadSha");
        requireText(command.expectedBaseSha(), "expectedBaseSha");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        if (command.action() != WorktreeRecoveryAction.REPAIR_WORKTREE) {
            throw new IllegalArgumentException(
                    "Unsupported worktree recovery action");
        }
        try {
            var admission = requireWorktreeRepairs().request(
                    taskId, quarantineId, command.blockerId(),
                    command.taskEpoch(), command.stageId(),
                    command.stageGeneration(), command.worktreePath(),
                    command.expectedBranchName(),
                    command.expectedCodeFingerprint(),
                    command.expectedHeadSha(), command.expectedBaseSha(),
                    command.commandId(), ACTOR, command.reason());
            var operation = admission.operation();
            return new WorktreeRecoveryResult(
                    operation.taskId(), operation.stageId(), quarantineId,
                    command.blockerId(), command.commandId(), command.action(),
                    operation.id(), operation.operationId(),
                    operation.ticketId(), operation.status(),
                    admission.created());
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw conflict(
                    "Worktree recovery does not own the exact quarantine",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    public CleanupRecoveryResult recoverCleanup(
            String taskId, String stepId, CleanupRecoveryCommand command)
    {
        requireText(taskId, "taskId");
        requireText(stepId, "stepId");
        requireNonNull(command, "command is null");
        requireText(command.commandId(), "commandId");
        requireText(command.reason(), "reason");
        requireNonNull(command.action(), "action is null");
        try {
            RecoveryAuthorization authorization = commands.execute(
                    taskId, () -> switch (command.action()) {
                        case RETRY -> cleanup.authorizeRetry(
                                taskId, stepId, command.commandId(), ACTOR,
                                command.reason(), clock.instant());
                        case WAIVE_OPTIONAL -> cleanup.authorizeOptionalWaiver(
                                taskId, stepId, command.commandId(), ACTOR,
                                command.reason(), clock.instant());
                    });
            boolean rearmed = tickets.resumeDeferred(
                    authorization.dispatchTicketId());
            return new CleanupRecoveryResult(
                    taskId, stepId, command.commandId(), command.action(),
                    authorization.cleanupOperationId(),
                    authorization.dispatchTicketId(), authorization.created(),
                    rearmed);
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw conflict("Cleanup recovery does not own the exact failed step",
                    failure);
        }
        catch (IllegalArgumentException failure) {
            throw conflict(failure.getMessage(), failure);
        }
    }

    private static ResponseStatusException conflict(
            String message, RuntimeException cause)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message, cause);
    }

    private LocalPublishBaseSyncRuntimeCoordinator requireLocalPublishBaseSync()
    {
        if (localPublishBaseSync == null) {
            throw new IllegalStateException(
                    "Local publish base-sync recovery is not configured");
        }
        return localPublishBaseSync;
    }

    private BranchSyncRuntimeCoordinator requireBranchSync()
    {
        if (branchSync == null) {
            throw new IllegalStateException(
                    "CI branch-sync recovery is not configured");
        }
        return branchSync;
    }

    private WorktreeQuarantineRepairRuntime requireWorktreeRepairs()
    {
        if (worktreeRepairs == null) {
            throw new IllegalStateException(
                    "Worktree quarantine recovery is not configured");
        }
        return worktreeRepairs;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum BranchSyncRecoveryAction
    {
        MANUAL_TAKEOVER,
        STOP_AUTOMATION
    }

    public record BranchSyncRecoveryCommand(
            String blockerId,
            String commandId,
            BranchSyncRecoveryAction action,
            String reason) {}

    public record BranchSyncRecoveryResult(
            String taskId,
            String episodeId,
            String blockerId,
            String stageId,
            String commandId,
            BranchSyncRecoveryAction action,
            String status) {}

    public enum WorktreeRecoveryAction
    {
        REPAIR_WORKTREE
    }

    public record WorktreeRecoveryCommand(
            String blockerId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String worktreePath,
            String expectedBranchName,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String commandId,
            WorktreeRecoveryAction action,
            String reason) {}

    public record WorktreeRecoveryResult(
            String taskId,
            String stageId,
            String quarantineId,
            String blockerId,
            String commandId,
            WorktreeRecoveryAction action,
            String repairOperationId,
            String operationId,
            String dispatchTicketId,
            String status,
            boolean created) {}

    public enum CleanupRecoveryAction
    {
        RETRY,
        WAIVE_OPTIONAL
    }

    public record CleanupRecoveryCommand(
            String commandId,
            CleanupRecoveryAction action,
            String reason) {}

    public record CleanupRecoveryResult(
            String taskId,
            String stepId,
            String commandId,
            CleanupRecoveryAction action,
            String cleanupOperationId,
            String dispatchTicketId,
            boolean created,
            boolean rearmed) {}

    public record LocalPublishBaseSyncApprovalResult(
            String taskId,
            String stageId,
            String blockerId,
            String episodeId,
            String operationId) {}

    public record LocalPublishBaseSyncExtensionCommand(
            String commandId, String reason) {}

    public record LocalPublishBaseSyncExtensionResult(
            String taskId,
            String stageId,
            String exhaustedEpisodeId,
            String blockerId,
            String commandId,
            String retryEpisodeId,
            String operationId,
            int attemptNo,
            int attemptLimit) {}

    public record PlanRecoveryCommand(
            String blockerId, String commandId, String reason) {}

    public record PlanRecoveryResult(
            String taskId,
            String failedTurnId,
            String blockerId,
            String commandId,
            String replacementTurnId,
            String replacementOperationId,
            String replacementTicketId) {}

    public record LocalStageRecoveryCommand(
            String blockerId, String commandId, String reason) {}

    public record LocalStageRecoveryResult(
            String taskId,
            String stageId,
            String failedTurnId,
            String blockerId,
            String commandId,
            String replacementTurnId,
            String replacementOperationId,
            String replacementTicketId) {}

    public record DevelopmentBrainRecoveryCommand(
            String blockerId, String commandId, String reason) {}

    public record DevelopmentBrainRecoveryResult(
            String taskId,
            String stageId,
            String failedTurnId,
            String blockerId,
            String commandId,
            String replacementEpisodeId,
            String replacementTurnId,
            String replacementOperationId,
            String replacementTicketId) {}

    public record RemoteRepairBrainRecoveryCommand(
            String blockerId, String commandId, String reason) {}

    public record RemoteRepairBrainRecoveryResult(
            String family,
            String taskId,
            String stageId,
            String episodeId,
            String failedTurnId,
            String blockerId,
            String commandId,
            String replacementTurnId,
            String replacementOperationId,
            String replacementTicketId) {}
}
