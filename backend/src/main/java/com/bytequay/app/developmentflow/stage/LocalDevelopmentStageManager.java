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

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.COMMAND_ID_CONFLICT;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static java.util.Objects.requireNonNull;

/** Synchronous writer for one Local Development Stage generation. */
public final class LocalDevelopmentStageManager
        extends StageManager
{
    private final EvidenceStore evidence;

    public LocalDevelopmentStageManager(TaskCommandExecutor commands, Store store)
    {
        this(commands, store, EvidenceStore.empty());
    }

    public LocalDevelopmentStageManager(
            TaskCommandExecutor commands, Store store, EvidenceStore evidence)
    {
        super(commands, store, StageKind.LOCAL_DEVELOPMENT);
        this.evidence = requireNonNull(evidence, "evidence is null");
    }

    CommandResult<State> openFromTaskInCommand(TaskManager.StageOpening opening)
    {
        return openInCommand(
                opening, StageCheckpoint.IMPLEMENTING, "OPEN_LOCAL_DEVELOPMENT");
    }

    public CommandResult<State> acceptImplementation(ResultCommand command)
    {
        return execute(command, () -> acceptImplementationInCommand(command));
    }

    public CommandResult<State> acceptImplementationInCommand(ResultCommand command)
    {
        return acceptResultInCommand(
                command, "ACCEPT_IMPLEMENTATION",
                StageCheckpoint.IMPLEMENTING, StageCheckpoint.VALIDATING);
    }

    public CommandResult<State> acceptImplementationResultInCommand(
            ResultCommand command, String devReportId)
    {
        return acceptResultWithProofInCommand(
                command, devReportId, "ACCEPT_LOCAL_CODE_RESULT",
                StageCheckpoint.IMPLEMENTING, StageCheckpoint.VALIDATING);
    }

    public CommandResult<State> requestImplementation(
            Command command, ResultFence result, String turnRequestId)
    {
        return execute(command, () -> requestImplementationInCommand(
                command, result, turnRequestId));
    }

    public CommandResult<State> requestImplementationInCommand(
            Command command, ResultFence result, String turnRequestId)
    {
        return armPendingResultInCommand(
                command, result, turnRequestId, "REQUEST_LOCAL_RESULT",
                StageCheckpoint.IMPLEMENTING);
    }

    /** Arms the first implementation Turn immediately after Plan opens Local v0. */
    public CommandResult<State> startInitialImplementationInCommand(
            Command command, ResultFence result, String turnRequestId)
    {
        return requestInitialResultInCommand(
                command,
                new InitialResultOwner(InitialResultOwnerKind.STAGE_TURN, turnRequestId),
                result, "REQUEST_LOCAL_RESULT", StageCheckpoint.IMPLEMENTING);
    }

    public CommandResult<State> acceptValidation(ResultCommand command)
    {
        return execute(command, () -> acceptValidationInCommand(command));
    }

    public CommandResult<State> acceptValidationInCommand(ResultCommand command)
    {
        return acceptResultInCommand(
                command, "ACCEPT_VALIDATION",
                StageCheckpoint.VALIDATING, StageCheckpoint.BRAIN_REVIEW);
    }

    public CommandResult<State> requestValidationInCommand(
            Command command, ResultFence result, String operationId)
    {
        return armPendingResultInCommand(
                command, result, operationId, "REQUEST_LOCAL_RESULT",
                StageCheckpoint.VALIDATING);
    }

    public CommandResult<State> clearValidationInCommand(
            ResultCommand command, String operationId)
    {
        return clearPendingResultInCommand(
                command, operationId, "CLEAR_LOCAL_RESULT",
                StageCheckpoint.VALIDATING);
    }

    public CommandResult<State> acceptBrainApprovalInCommand(
            TaskManager.AcceptedBrainVerdict accepted)
    {
        return acceptBrainVerdictInCommand(
                accepted, TaskManager.BrainVerdict.APPROVED, "ACCEPT_BRAIN_APPROVAL",
                StageCheckpoint.BRAIN_REVIEW, StageCheckpoint.LOCAL_REVIEW);
    }

    public CommandResult<State> acceptBrainFindingsInCommand(
            TaskManager.AcceptedBrainVerdict accepted)
    {
        return acceptBrainVerdictInCommand(
                accepted,
                TaskManager.BrainVerdict.CHANGES_REQUESTED,
                "ACCEPT_BRAIN_FINDINGS",
                StageCheckpoint.BRAIN_REVIEW,
                StageCheckpoint.ADDRESSING_BRAIN_FINDINGS);
    }

    public CommandResult<State> requestBrainFixInCommand(
            Command command, ResultFence result, String turnRequestId)
    {
        return armPendingResultInCommand(
                command, result, turnRequestId, "REQUEST_LOCAL_RESULT",
                StageCheckpoint.ADDRESSING_BRAIN_FINDINGS);
    }

    public CommandResult<State> acceptBrainBudgetExhaustionInCommand(
            Command command, String blockerId)
    {
        return moveWithProofInCommand(
                command, blockerId, "ACCEPT_BRAIN_BUDGET_EXHAUSTION",
                StageCheckpoint.BRAIN_REVIEW, StageCheckpoint.LOCAL_REVIEW);
    }

    public CommandResult<State> acceptBrainFixes(ResultCommand command)
    {
        return execute(command, () -> acceptBrainFixesInCommand(command));
    }

    public CommandResult<State> acceptBrainFixesInCommand(ResultCommand command)
    {
        return acceptResultInCommand(
                command,
                "ACCEPT_BRAIN_FIXES",
                StageCheckpoint.ADDRESSING_BRAIN_FINDINGS,
                StageCheckpoint.IMPLEMENTING);
    }

    public CommandResult<State> acceptBrainFixResultInCommand(
            ResultCommand command, String devReportId)
    {
        return acceptResultWithProofInCommand(
                command, devReportId, "ACCEPT_LOCAL_CODE_RESULT",
                StageCheckpoint.ADDRESSING_BRAIN_FINDINGS,
                StageCheckpoint.IMPLEMENTING);
    }

    public CommandResult<State> beginValidationInCommand(
            Command command, String devReportId)
    {
        return moveWithProofInCommand(
                command, devReportId, "BEGIN_LOCAL_VALIDATION",
                StageCheckpoint.IMPLEMENTING, StageCheckpoint.VALIDATING);
    }

    public CommandResult<State> submitLocalFeedback(FeedbackCommand command)
    {
        requireNonNull(command, "command is null");
        return execute(command.stage(), () -> submitLocalFeedbackInCommand(command));
    }

    public CommandResult<State> submitLocalFeedbackInCommand(FeedbackCommand command)
    {
        requireNonNull(command, "command is null");
        Optional<CommandResult<State>> replay = replayStructuralInCommand(
                command.stage(), "SUBMIT_LOCAL_FEEDBACK", null,
                command.batchId(), StageCheckpoint.LOCAL_REVIEW);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        FeedbackEvidence persisted = evidence.findLocalFeedback(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.batchId())
                .orElseThrow(() -> rejected("Exact submitted Local feedback batch is missing"));
        if (!persisted.matches(command)) {
            throw rejected("Local feedback command does not match its immutable batch");
        }
        return moveWithProofInCommand(
                command.stage(), command.batchId(),
                "SUBMIT_LOCAL_FEEDBACK",
                StageCheckpoint.LOCAL_REVIEW,
                StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK);
    }

    public CommandResult<State> authorizePublish(PublishCommand command)
    {
        requireNonNull(command, "command is null");
        return execute(command.stage(), () -> {
            Optional<CommandResult<State>> replay = replayStructuralInCommand(
                    command.stage(), "AUTHORIZE_PUBLISH", command.resultFence(),
                    command.authorizationId(), StageCheckpoint.LOCAL_REVIEW);
            if (replay.isPresent()) {
                return replay.orElseThrow();
            }
            AcceptedPublishAuthorization accepted = requirePublishAuthorization(command);
            return moveWithProofAndPendingResultInCommand(
                    accepted.command.stage(), accepted.command.resultFence(),
                    accepted.evidence.authorizationId(), "AUTHORIZE_PUBLISH",
                    StageCheckpoint.LOCAL_REVIEW, StageCheckpoint.PUBLISHING);
        });
    }

    private AcceptedPublishAuthorization requirePublishAuthorization(PublishCommand command)
    {
        PublishAuthorizationEvidence persisted = evidence.findPublishAuthorization(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.authorizationId())
                .orElseThrow(() -> rejected("Exact persisted Publish authorization is missing"));
        if (!persisted.matches(command)) {
            throw rejected("Publish authorization does not match its exact subject");
        }
        return new AcceptedPublishAuthorization(command, persisted);
    }

    public CommandResult<State> acceptLocalFeedbackFixes(ResultCommand command)
    {
        return execute(command, () -> acceptLocalFeedbackFixesInCommand(command));
    }

    public CommandResult<State> acceptLocalFeedbackFixesInCommand(ResultCommand command)
    {
        return acceptResultInCommand(
                command,
                "ACCEPT_LOCAL_FEEDBACK_FIXES",
                StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK,
                StageCheckpoint.IMPLEMENTING);
    }

    public CommandResult<State> acceptLocalFeedbackResultInCommand(
            ResultCommand command, String devReportId)
    {
        return acceptResultWithProofInCommand(
                command, devReportId, "ACCEPT_LOCAL_CODE_RESULT",
                StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK,
                StageCheckpoint.IMPLEMENTING);
    }

    public CommandResult<State> requestLocalFeedbackFixInCommand(
            Command command, ResultFence result, String turnRequestId)
    {
        return armPendingResultInCommand(
                command, result, turnRequestId, "REQUEST_LOCAL_RESULT",
                StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK);
    }

    public CommandResult<State> replaceImplementationTurnInCommand(
            ResultCommand completed, ResultFence replacement, String turnRequestId)
    {
        requireReplacement(completed, replacement, turnRequestId);
        return replacePendingResultInCommand(
                completed, replacement, turnRequestId, "REPLACE_LOCAL_RESULT",
                StageCheckpoint.IMPLEMENTING);
    }

    public CommandResult<State> replaceBrainFixTurnInCommand(
            ResultCommand completed, ResultFence replacement, String turnRequestId)
    {
        requireReplacement(completed, replacement, turnRequestId);
        return replacePendingResultInCommand(
                completed, replacement, turnRequestId, "REPLACE_LOCAL_RESULT",
                StageCheckpoint.ADDRESSING_BRAIN_FINDINGS);
    }

    public CommandResult<State> replaceLocalFeedbackTurnInCommand(
            ResultCommand completed, ResultFence replacement, String turnRequestId)
    {
        requireReplacement(completed, replacement, turnRequestId);
        return replacePendingResultInCommand(
                completed, replacement, turnRequestId, "REPLACE_LOCAL_RESULT",
                StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK);
    }

    private void requireReplacement(
            ResultCommand completed, ResultFence replacement, String turnRequestId)
    {
        requireNonNull(completed, "completed is null");
        requireNonNull(replacement, "replacement is null");
        requireText(turnRequestId, "turnRequestId");
        if (replacement.equals(completed.resultFence())) {
            throw rejected("Replacement result must name new work");
        }
        ReplacementEvidence persisted = evidence.findReplacement(
                        completed.taskId(), completed.resultFence().stageId(),
                        completed.resultFence().stageGeneration(), turnRequestId)
                .orElseThrow(() -> rejected("Exact Local replacement request is missing"));
        if (!persisted.matches(completed, replacement)) {
            throw new CommandRejectedException(
                    COMMAND_ID_CONFLICT,
                    "Local replacement command conflicts with its immutable request");
        }
    }

    public CommandResult<State> clearImplementationTurnInCommand(
            ResultCommand completed, String turnRequestId)
    {
        return clearPendingResultInCommand(
                completed, turnRequestId, "CLEAR_LOCAL_RESULT",
                StageCheckpoint.IMPLEMENTING);
    }

    public CommandResult<State> clearBrainFixTurnInCommand(
            ResultCommand completed, String turnRequestId)
    {
        return clearPendingResultInCommand(
                completed, turnRequestId, "CLEAR_LOCAL_RESULT",
                StageCheckpoint.ADDRESSING_BRAIN_FINDINGS);
    }

    public CommandResult<State> clearLocalFeedbackTurnInCommand(
            ResultCommand completed, String turnRequestId)
    {
        return clearPendingResultInCommand(
                completed, turnRequestId, "CLEAR_LOCAL_RESULT",
                StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK);
    }

    PublicationResult acceptPublishedForHandoffInCommand(ResultCommand command)
    {
        ResultResolution resolution = acceptResultForHandoffInCommand(
                command, "ACCEPT_PUBLISHED",
                StageCheckpoint.PUBLISHING, StageCheckpoint.COMPLETED);
        CommandResult<State> result = resolution.result();
        if (!resolution.wasAccepted()) {
            return new PublicationResult(result, Optional.empty());
        }
        return new PublicationResult(
                result, Optional.of(new AcceptedCompletion(command, result)));
    }

    /** Accepts a definite publish failure and returns the Local Stage to review. */
    PublishFailureResult acceptPublishFailureInCommand(ResultCommand command)
    {
        ResultResolution resolution = acceptResultForHandoffInCommand(
                command, "ACCEPT_PUBLISH_FAILURE",
                StageCheckpoint.PUBLISHING, StageCheckpoint.LOCAL_REVIEW);
        return new PublishFailureResult(
                resolution.result(), resolution.wasAccepted());
    }

    @Override
    protected boolean accepts(TaskLifecycle lifecycle)
    {
        return lifecycle == TaskLifecycle.ACTIVE;
    }

    /** Exact publish-result proof consumable only by TaskManager. */
    public static final class AcceptedCompletion
    {
        private final ResultCommand command;
        private final CommandResult<State> stage;

        private AcceptedCompletion(ResultCommand command, CommandResult<State> stage)
        {
            this.command = requireNonNull(command, "command is null");
            this.stage = requireNonNull(stage, "stage is null");
        }

        public String commandId() { return command.commandId(); }

        public String actor() { return command.actor(); }

        public String taskId() { return command.taskId(); }

        public long taskEpoch() { return command.resultFence().taskEpoch(); }

        public String stageId() { return command.resultFence().stageId(); }

        public long stageGeneration() { return command.resultFence().stageGeneration(); }

        public ResultFence resultFence()
        {
            return command.resultFence();
        }

        public CommandResult<State> stage() { return stage; }
    }

    /** Unforgeable capability created only after reading persisted authorization. */
    public static final class AcceptedPublishAuthorization
    {
        private final PublishCommand command;
        private final PublishAuthorizationEvidence evidence;

        private AcceptedPublishAuthorization(
                PublishCommand command, PublishAuthorizationEvidence evidence)
        {
            this.command = requireNonNull(command, "command is null");
            this.evidence = requireNonNull(evidence, "evidence is null");
        }
    }

    public record FeedbackCommand(
            Command stage, String batchId, String submissionId, String contentDigest)
    {
        public FeedbackCommand
        {
            requireNonNull(stage, "stage is null");
            requireText(batchId, "batchId");
            requireText(submissionId, "submissionId");
            requireText(contentDigest, "contentDigest");
        }
    }

    public record FeedbackEvidence(
            String taskId,
            String stageId,
            long stageGeneration,
            String batchId,
            String submissionId,
            String contentDigest)
    {
        public FeedbackEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(batchId, "batchId");
            requireText(submissionId, "submissionId");
            requireText(contentDigest, "contentDigest");
            if (stageGeneration < 1) {
                throw new IllegalArgumentException("stageGeneration must be positive");
            }
        }

        private boolean matches(FeedbackCommand command)
        {
            return taskId.equals(command.stage().taskId())
                    && stageId.equals(command.stage().stageId())
                    && stageGeneration == command.stage().expectedStageGeneration()
                    && batchId.equals(command.batchId())
                    && submissionId.equals(command.submissionId())
                    && contentDigest.equals(command.contentDigest());
        }
    }

    public record PublishCommand(
            Command stage,
            String authorizationId,
            String policyRevisionId,
            String consentId,
            ResultFence resultFence)
    {
        public PublishCommand
        {
            requireNonNull(stage, "stage is null");
            requireText(authorizationId, "authorizationId");
            requireText(policyRevisionId, "policyRevisionId");
            requireText(consentId, "consentId");
            requireNonNull(resultFence, "resultFence is null");
        }
    }

    public record PublishAuthorizationEvidence(
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String authorizationId,
            String policyRevisionId,
            String consentId,
            ResultFence resultFence)
    {
        public PublishAuthorizationEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(authorizationId, "authorizationId");
            requireText(policyRevisionId, "policyRevisionId");
            requireText(consentId, "consentId");
            requireNonNull(resultFence, "resultFence is null");
            if (taskEpoch < 1 || stageGeneration < 1) {
                throw new IllegalArgumentException("Publish authorization identity is invalid");
            }
        }

        private boolean matches(PublishCommand command)
        {
            return taskId.equals(command.stage().taskId())
                    && taskEpoch == command.stage().expectedTaskEpoch()
                    && stageId.equals(command.stage().stageId())
                    && stageGeneration == command.stage().expectedStageGeneration()
                    && authorizationId.equals(command.authorizationId())
                    && policyRevisionId.equals(command.policyRevisionId())
                    && consentId.equals(command.consentId())
                    && resultFence.equals(command.resultFence());
        }
    }

    public record ReplacementEvidence(
            String taskId,
            String stageId,
            long stageGeneration,
            String requestId,
            ResultFence predecessor,
            ResultFence replacement)
    {
        public ReplacementEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(requestId, "requestId");
            requireNonNull(predecessor, "predecessor is null");
            requireNonNull(replacement, "replacement is null");
            if (stageGeneration < 1) {
                throw new IllegalArgumentException("stageGeneration must be positive");
            }
        }

        private boolean matches(ResultCommand completed, ResultFence requestedReplacement)
        {
            return taskId.equals(completed.taskId())
                    && stageId.equals(completed.resultFence().stageId())
                    && stageGeneration == completed.resultFence().stageGeneration()
                    && predecessor.equals(completed.resultFence())
                    && replacement.equals(requestedReplacement);
        }
    }

    public interface EvidenceStore
    {
        Optional<FeedbackEvidence> findLocalFeedback(
                String taskId, String stageId, long stageGeneration, String batchId);

        Optional<PublishAuthorizationEvidence> findPublishAuthorization(
                String taskId, String stageId, long stageGeneration, String authorizationId);

        default Optional<ReplacementEvidence> findReplacement(
                String taskId, String stageId, long stageGeneration, String requestId)
        {
            return Optional.empty();
        }

        static EvidenceStore empty()
        {
            return new EvidenceStore()
            {
                @Override
                public Optional<FeedbackEvidence> findLocalFeedback(
                        String taskId, String stageId, long stageGeneration, String batchId)
                {
                    return Optional.empty();
                }

                @Override
                public Optional<PublishAuthorizationEvidence> findPublishAuthorization(
                        String taskId,
                        String stageId,
                        long stageGeneration,
                        String authorizationId)
                {
                    return Optional.empty();
                }
            };
        }
    }

    public record PublicationResult(
            CommandResult<State> stage,
            Optional<AcceptedCompletion> accepted)
    {
        public PublicationResult
        {
            requireNonNull(stage, "stage is null");
            requireNonNull(accepted, "accepted is null");
            if ((stage.disposition() == CommandResult.Disposition.APPLIED
                    && accepted.isEmpty())
                    || (stage.disposition() == CommandResult.Disposition.SUPERSEDED
                    && accepted.isPresent())) {
                throw new IllegalArgumentException("Publication proof is inconsistent");
            }
        }
    }

    record PublishFailureResult(CommandResult<State> stage, boolean accepted)
    {
        PublishFailureResult
        {
            requireNonNull(stage, "stage is null");
        }
    }

    private static CommandRejectedException rejected(String message)
    {
        return new CommandRejectedException(INVALID_STATE, message);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
