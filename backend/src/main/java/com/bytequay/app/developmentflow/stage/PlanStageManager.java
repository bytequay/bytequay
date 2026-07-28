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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;

import java.util.Optional;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static java.util.Objects.requireNonNull;

/** Synchronous writer for one Plan Stage generation. */
public final class PlanStageManager
        extends StageManager
{
    private final ApprovalStore approvals;
    private final RevisionStore revisions;

    public PlanStageManager(TaskCommandExecutor commands, Store store)
    {
        this(
                commands,
                store,
                (taskId, stageId, generation, approvalId) -> Optional.empty(),
                (taskId, stageId, generation, revisionId) -> Optional.empty());
    }

    public PlanStageManager(
            TaskCommandExecutor commands, Store store, ApprovalStore approvals)
    {
        this(
                commands,
                store,
                approvals,
                (taskId, stageId, generation, revisionId) -> Optional.empty());
    }

    public PlanStageManager(
            TaskCommandExecutor commands,
            Store store,
            ApprovalStore approvals,
            RevisionStore revisions)
    {
        super(commands, store, StageKind.PLAN);
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.revisions = requireNonNull(revisions, "revisions is null");
    }

    AcceptedOpening openFromTaskInCommand(TaskManager.StageOpening opening)
    {
        CommandResult<State> stage = openInCommand(
                opening,
                StageCheckpoint.DRAFTING,
                opening.subjectFence() == null ? "OPEN_REPLAN_PLAN" : "OPEN_INITIAL_PLAN");
        return new AcceptedOpening(opening, stage);
    }

    public CommandResult<State> acceptDrafted(ResultCommand command)
    {
        return execute(command, () -> acceptDraftedInCommand(command));
    }

    public CommandResult<State> acceptDraftedInCommand(ResultCommand command)
    {
        return acceptResultInCommand(
                command, "ACCEPT_DRAFTED",
                StageCheckpoint.DRAFTING, StageCheckpoint.SELF_REVIEW);
    }

    public CommandResult<State> requestDraftInCommand(
            Command command, String taskTurnId, ResultFence result)
    {
        return requestInitialResultInCommand(
                command,
                new InitialResultOwner(InitialResultOwnerKind.TASK_TURN, taskTurnId),
                result, "REQUEST_PLAN_DRAFT", StageCheckpoint.DRAFTING);
    }

    public CommandResult<State> acceptDraftedAndRequestSelfReviewInCommand(
            ResultCommand drafted,
            ResultFence selfReview)
    {
        return acceptResultAndRequestNextInCommand(
                drafted, selfReview, "ACCEPT_DRAFTED",
                StageCheckpoint.DRAFTING, StageCheckpoint.SELF_REVIEW);
    }

    public CommandResult<State> acceptSelfReviewFindingsAndRequestDraftInCommand(
            ResultCommand reviewed,
            ResultFence nextDraft)
    {
        return acceptResultAndRequestNextInCommand(
                reviewed, nextDraft, "ACCEPT_PLAN_BRAIN_FINDINGS",
                StageCheckpoint.SELF_REVIEW, StageCheckpoint.DRAFTING);
    }

    public CommandResult<State> acceptSelfReviewApprovalInCommand(
            ResultCommand reviewed)
    {
        return acceptResultInCommand(
                reviewed, "ACCEPT_PLAN_BRAIN_APPROVAL",
                StageCheckpoint.SELF_REVIEW,
                StageCheckpoint.AWAITING_APPROVAL);
    }

    public CommandResult<State> retrySelfReviewInCommand(
            ResultCommand failed,
            String selfReviewId,
            String replacementTurnId,
            ResultFence replacement)
    {
        return retryPlanSelfReviewInCommand(
                failed, selfReviewId, replacementTurnId, replacement);
    }

    public CommandResult<State> acceptTerminalTurnInCommand(
            ResultCommand command,
            String cause,
            String proofId,
            StageCheckpoint checkpoint)
    {
        return acceptPlanTerminalResultInCommand(
                command, cause, proofId, checkpoint);
    }

    public CommandResult<State> acceptBrainFindingsInCommand(
            TaskManager.AcceptedBrainVerdict accepted)
    {
        return acceptBrainVerdictInCommand(
                accepted,
                TaskManager.BrainVerdict.CHANGES_REQUESTED,
                "ACCEPT_PLAN_BRAIN_FINDINGS",
                StageCheckpoint.SELF_REVIEW, StageCheckpoint.DRAFTING);
    }

    public CommandResult<State> acceptBrainApprovalInCommand(
            TaskManager.AcceptedBrainVerdict accepted)
    {
        return acceptBrainVerdictInCommand(
                accepted,
                TaskManager.BrainVerdict.APPROVED,
                "ACCEPT_PLAN_BRAIN_APPROVAL",
                StageCheckpoint.SELF_REVIEW, StageCheckpoint.AWAITING_APPROVAL);
    }

    public CommandResult<State> reviseBeforeApproval(RevisionCommand command)
    {
        requireNonNull(command, "command is null");
        return execute(command.stage(), () -> reviseBeforeApprovalInCommand(command));
    }

    public CommandResult<State> reviseBeforeApprovalInCommand(RevisionCommand command)
    {
        requireNonNull(command, "command is null");
        Optional<CommandResult<State>> replay = replayStructuralInCommand(
                command.stage(), "REVISE_BEFORE_APPROVAL", null,
                command.revisionId(), StageCheckpoint.AWAITING_APPROVAL);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        RevisionEvidence evidence = revisions.findRevision(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.revisionId())
                .orElseThrow(() -> new CommandRejectedException(
                        INVALID_STATE, "Exact persisted Plan revision is missing"));
        if (!evidence.matches(command)) {
            throw new CommandRejectedException(
                    INVALID_STATE, "Plan revision does not match its exact predecessor");
        }
        return moveWithProofInCommand(
                command.stage(), command.revisionId(), "REVISE_BEFORE_APPROVAL",
                StageCheckpoint.AWAITING_APPROVAL, StageCheckpoint.DRAFTING);
    }

    /** Arms the mandatory review for an exact user-edited revision. */
    public CommandResult<State> requestEditedRevisionReviewInCommand(
            RevisionCommand command, ResultFence reviewResult)
    {
        requireNonNull(command, "command is null");
        requireNonNull(reviewResult, "reviewResult is null");
        Optional<CommandResult<State>> replay = replayStructuralInCommand(
                command.stage(), "REQUEST_EDITED_PLAN_SELF_REVIEW", reviewResult,
                command.revisionId(), StageCheckpoint.DRAFTING);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        RevisionEvidence evidence = revisions.findRevision(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.revisionId())
                .orElseThrow(() -> new CommandRejectedException(
                        INVALID_STATE, "Exact edited Plan revision is missing"));
        if (!evidence.matches(command)) {
            throw new CommandRejectedException(
                    INVALID_STATE, "Edited Plan revision does not match its predecessor");
        }
        return requestEditedPlanReviewInCommand(
                command.stage(), command.revisionId(), reviewResult);
    }

    AcceptedCompletion acceptApprovedForHandoffInCommand(ApprovalCommand command)
    {
        requireNonNull(command, "command is null");
        Optional<CommandResult<State>> replay = replayStructuralInCommand(
                command.stage(), "APPROVE_PLAN", null,
                command.approvalId(), StageCheckpoint.AWAITING_APPROVAL);
        if (replay.isPresent()) {
            return new AcceptedCompletion(command, replay.orElseThrow());
        }
        ApprovalEvidence evidence = approvals.findLatestApproval(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.approvalId())
                .orElseThrow(() -> new CommandRejectedException(
                        INVALID_STATE, "Latest exact Plan approval is missing"));
        if (!evidence.matches(command)) {
            throw new CommandRejectedException(
                    INVALID_STATE, "Plan approval does not match the latest reviewed revision");
        }
        CommandResult<State> result = moveWithProofInCommand(
                command.stage(), command.approvalId(), "APPROVE_PLAN",
                StageCheckpoint.AWAITING_APPROVAL, StageCheckpoint.COMPLETED);
        return new AcceptedCompletion(command, result);
    }

    @Override
    protected boolean accepts(TaskLifecycle lifecycle)
    {
        return lifecycle == TaskLifecycle.ACTIVE;
    }

    /** Exact Plan completion proof consumable only by TaskManager. */
    public static final class AcceptedCompletion
    {
        private final ApprovalCommand command;
        private final CommandResult<State> stage;

        private AcceptedCompletion(
                ApprovalCommand command,
                CommandResult<State> stage)
        {
            this.command = requireNonNull(command, "command is null");
            this.stage = requireNonNull(stage, "stage is null");
        }

        public String commandId() { return command.stage().commandId(); }

        public String actor() { return command.stage().actor(); }

        public String taskId() { return command.stage().taskId(); }

        public long taskEpoch() { return command.stage().expectedTaskEpoch(); }

        public String stageId() { return command.stage().stageId(); }

        public long stageGeneration() { return command.stage().expectedStageGeneration(); }

        public String approvalId() { return command.approvalId(); }

        public CommandResult<State> stage() { return stage; }
    }

    /** Opaque proof that PlanStageManager opened this exact Plan generation. */
    public static final class AcceptedOpening
    {
        private final TaskManager.StageOpening opening;
        private final CommandResult<State> stage;

        private AcceptedOpening(
                TaskManager.StageOpening opening, CommandResult<State> stage)
        {
            this.opening = requireNonNull(opening, "opening is null");
            this.stage = requireNonNull(stage, "stage is null");
        }

        public String taskId() { return opening.taskId(); }

        public String stageId() { return opening.stageId(); }

        public long stageGeneration() { return opening.stageGeneration(); }

        public String proofId() { return opening.proofId(); }

        public CommandResult<State> stage() { return stage; }
    }

    public record ApprovalCommand(
            Command stage,
            String approvalId,
            String planRevisionId,
            String selfReviewId,
            String reviewedDigest)
    {
        public ApprovalCommand
        {
            requireNonNull(stage, "stage is null");
            requireText(approvalId, "approvalId");
            requireText(planRevisionId, "planRevisionId");
            requireText(selfReviewId, "selfReviewId");
            requireText(reviewedDigest, "reviewedDigest");
        }
    }

    public record RevisionCommand(
            Command stage,
            String revisionId,
            String previousRevisionId,
            String contentDigest)
    {
        public RevisionCommand
        {
            requireNonNull(stage, "stage is null");
            requireText(revisionId, "revisionId");
            requireText(previousRevisionId, "previousRevisionId");
            requireText(contentDigest, "contentDigest");
            if (revisionId.equals(previousRevisionId)) {
                throw new IllegalArgumentException("Plan revision must advance identity");
            }
        }
    }

    /** Immutable Plan revision loaded from the Plan-owned protocol store. */
    public record RevisionEvidence(
            String taskId,
            String stageId,
            long stageGeneration,
            String revisionId,
            String previousRevisionId,
            String contentDigest)
    {
        public RevisionEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(revisionId, "revisionId");
            requireText(previousRevisionId, "previousRevisionId");
            requireText(contentDigest, "contentDigest");
            if (stageGeneration < 1) {
                throw new IllegalArgumentException("stageGeneration must be positive");
            }
        }

        private boolean matches(RevisionCommand command)
        {
            return taskId.equals(command.stage().taskId())
                    && stageId.equals(command.stage().stageId())
                    && stageGeneration == command.stage().expectedStageGeneration()
                    && revisionId.equals(command.revisionId())
                    && previousRevisionId.equals(command.previousRevisionId())
                    && contentDigest.equals(command.contentDigest());
        }
    }

    /** Exact latest approved Plan revision returned by the Plan-owned store. */
    public record ApprovalEvidence(
            String taskId,
            String stageId,
            long stageGeneration,
            String approvalId,
            String planRevisionId,
            String selfReviewId,
            String reviewedDigest)
    {
        public ApprovalEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(approvalId, "approvalId");
            requireText(planRevisionId, "planRevisionId");
            requireText(selfReviewId, "selfReviewId");
            requireText(reviewedDigest, "reviewedDigest");
            if (stageGeneration < 1) {
                throw new IllegalArgumentException("stageGeneration must be positive");
            }
        }

        private boolean matches(ApprovalCommand command)
        {
            return taskId.equals(command.stage().taskId())
                    && stageId.equals(command.stage().stageId())
                    && stageGeneration == command.stage().expectedStageGeneration()
                    && approvalId.equals(command.approvalId())
                    && planRevisionId.equals(command.planRevisionId())
                    && selfReviewId.equals(command.selfReviewId())
                    && reviewedDigest.equals(command.reviewedDigest());
        }
    }

    public interface ApprovalStore
    {
        /**
         * Returns the immutable approval for the latest revision in this Plan
         * generation, including after that exact approval completed the Stage.
         */
        Optional<ApprovalEvidence> findLatestApproval(
                String taskId, String stageId, long stageGeneration, String approvalId);
    }

    public interface RevisionStore
    {
        Optional<RevisionEvidence> findRevision(
                String taskId, String stageId, long stageGeneration, String revisionId);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
