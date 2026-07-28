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

import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Synchronous writer for one Remote Development Stage generation. */
public final class RemoteDevelopmentStageManager
        extends StageManager
{
    private final EvidenceStore evidence;

    public RemoteDevelopmentStageManager(TaskCommandExecutor commands, Store store)
    {
        this(commands, store, EvidenceStore.empty());
    }

    public RemoteDevelopmentStageManager(
            TaskCommandExecutor commands, Store store, EvidenceStore evidence)
    {
        super(commands, store, StageKind.REMOTE_DEVELOPMENT);
        this.evidence = requireNonNull(evidence, "evidence is null");
    }

    CommandResult<State> openFromTaskInCommand(TaskManager.StageOpening opening)
    {
        return openInCommand(
                opening, StageCheckpoint.WAITING_CI, "OPEN_REMOTE_DEVELOPMENT");
    }

    public CommandResult<State> acceptCi(ResultCommand command)
    {
        return execute(command, () -> acceptCiInCommand(command));
    }

    public CommandResult<State> acceptCiInCommand(ResultCommand command)
    {
        return acceptResultInCommand(
                command, "ACCEPT_CI",
                StageCheckpoint.WAITING_CI, StageCheckpoint.AWAITING_READY);
    }

    /** Accepts recurring RemoteObserver CI truth without pretending it is a
     * one-shot pending Stage result. */
    public CommandResult<State> acceptCiEvidenceInCommand(RemoteGateCommand command)
    {
        requireNonNull(command, "command is null");
        RemoteGateEvidence persisted = evidence.findAcceptedCi(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.proofId())
                .orElseThrow(() -> rejected(
                        "Fresh exact-head accepted CI evidence is missing"));
        if (!persisted.matches(command)) {
            throw rejected("CI evidence does not match the current exact head");
        }
        return moveWithProofInCommand(
                command.stage(), command.proofId(), "ACCEPT_REMOTE_CI",
                StageCheckpoint.WAITING_CI, StageCheckpoint.AWAITING_READY);
    }

    public CommandResult<State> acceptReady(ResultCommand command)
    {
        return execute(command, () -> acceptReadyInCommand(command));
    }

    public CommandResult<State> acceptReadyInCommand(ResultCommand command)
    {
        return acceptResultInCommand(
                command,
                "ACCEPT_READY",
                StageCheckpoint.AWAITING_READY,
                StageCheckpoint.WAITING_REMOTE_REVIEW);
    }

    /** Accepts a fresh non-Draft observation. A PR that was already marked
     * ready must not require a synthetic mark-ready Operation. */
    public CommandResult<State> acceptObservedReadyInCommand(RemoteGateCommand command)
    {
        requireNonNull(command, "command is null");
        RemoteGateEvidence persisted = evidence.findObservedReady(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.proofId())
                .orElseThrow(() -> rejected(
                        "Fresh exact-head open-PR evidence is missing"));
        if (!persisted.matches(command)) {
            throw rejected("Open-PR evidence does not match the current exact head");
        }
        return moveWithProofInCommand(
                command.stage(), command.proofId(), "ACCEPT_OBSERVED_READY",
                StageCheckpoint.AWAITING_READY,
                StageCheckpoint.WAITING_REMOTE_REVIEW);
    }

    /** A newly accepted head invalidates every old-head gate and armed merge
     * result before CI for the new subject may advance. */
    public CommandResult<State> acceptHeadChangeInCommand(
            RemoteGateCommand command, StageCheckpoint source)
    {
        requireNonNull(command, "command is null");
        requireNonNull(source, "source is null");
        RemoteGateEvidence persisted = evidence.findHeadChange(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.proofId())
                .orElseThrow(() -> rejected(
                        "Fresh exact-head change evidence is missing"));
        if (!persisted.matches(command)) {
            throw rejected("Head-change evidence does not match the current subject");
        }
        return moveWithProofClearingPendingInCommand(
                command.stage(), command.proofId(), "ACCEPT_REMOTE_HEAD_CHANGE",
                source, StageCheckpoint.WAITING_CI);
    }

    public CommandResult<State> beginRemoteFeedback(FeedbackCommand command)
    {
        requireNonNull(command, "command is null");
        return execute(command.stage(), () -> beginRemoteFeedbackInCommand(command));
    }

    public CommandResult<State> beginRemoteFeedbackInCommand(FeedbackCommand command)
    {
        requireNonNull(command, "command is null");
        Optional<CommandResult<State>> replay = replayStructuralInCommand(
                command.stage(), "BEGIN_REMOTE_FEEDBACK", null,
                command.batchId(), StageCheckpoint.WAITING_REMOTE_REVIEW);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        FeedbackEvidence persisted = evidence.findRemoteFeedback(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.batchId())
                .orElseThrow(() -> rejected("Exact Remote feedback batch is missing"));
        if (!persisted.matches(command)) {
            throw rejected("Remote feedback command does not match its immutable batch");
        }
        return moveWithProofInCommand(
                command.stage(), command.batchId(),
                "BEGIN_REMOTE_FEEDBACK",
                StageCheckpoint.WAITING_REMOTE_REVIEW,
                StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK);
    }

    public CommandResult<State> acceptRemoteFeedbackPush(ResultCommand command)
    {
        return execute(command, () -> acceptRemoteFeedbackPushInCommand(command));
    }

    public CommandResult<State> acceptRemoteFeedbackPushInCommand(ResultCommand command)
    {
        return acceptResultInCommand(
                command,
                "ACCEPT_REMOTE_FEEDBACK_PUSH",
                StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK,
                StageCheckpoint.WAITING_CI);
    }

    /** Completes one fully applied feedback batch from its immutable effect proof. */
    public CommandResult<State> completeRemoteFeedback(
            FeedbackCompletionCommand command)
    {
        requireNonNull(command, "command is null");
        return execute(command.stage(), () -> completeRemoteFeedbackInCommand(command));
    }

    public CommandResult<State> completeRemoteFeedbackInCommand(
            FeedbackCompletionCommand command)
    {
        requireNonNull(command, "command is null");
        FeedbackCompletionEvidence persisted = evidence.findCompletedRemoteFeedback(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.batchId())
                .orElseThrow(() -> rejected(
                        "Exact completed Remote feedback batch is missing"));
        if (!persisted.matches(command)) {
            throw rejected("Remote feedback completion does not match current exact head");
        }
        String cause = command.pushed()
                ? "COMPLETE_REMOTE_FEEDBACK_PUSH"
                : "COMPLETE_REMOTE_FEEDBACK_NO_PUSH";
        StageCheckpoint target = command.pushed()
                ? StageCheckpoint.WAITING_CI
                : StageCheckpoint.WAITING_REMOTE_REVIEW;
        return moveWithProofInCommand(
                command.stage(), command.batchId(), cause,
                StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK, target);
    }

    public CommandResult<State> acceptReadiness(ResultCommand command)
    {
        return execute(command, () -> acceptReadinessInCommand(command));
    }

    public CommandResult<State> acceptReadinessInCommand(ResultCommand command)
    {
        return acceptResultInCommand(
                command,
                "ACCEPT_READINESS",
                StageCheckpoint.WAITING_REMOTE_REVIEW,
                StageCheckpoint.READY_TO_MERGE);
    }

    /** Accepts an observed non-Draft snapshot after a durable mark-ready effect. */
    public CommandResult<State> completeMarkReady(RemoteGateCommand command)
    {
        requireNonNull(command, "command is null");
        return execute(command.stage(), () -> completeMarkReadyInCommand(command));
    }

    public CommandResult<State> completeMarkReadyInCommand(RemoteGateCommand command)
    {
        requireNonNull(command, "command is null");
        RemoteGateEvidence persisted = evidence.findCompletedMarkReady(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.proofId())
                .orElseThrow(() -> rejected(
                        "Exact completed mark-ready operation is missing"));
        if (!persisted.matches(command)) {
            throw rejected("Mark-ready proof does not match the current exact head");
        }
        return moveWithProofInCommand(
                command.stage(), command.proofId(), "COMPLETE_REMOTE_MARK_READY",
                StageCheckpoint.AWAITING_READY,
                StageCheckpoint.WAITING_REMOTE_REVIEW);
    }

    /** Promotes only fresh, ready exact-head evidence owned by this Stage. */
    public CommandResult<State> acceptReadinessEvidence(RemoteGateCommand command)
    {
        requireNonNull(command, "command is null");
        return execute(command.stage(), () -> acceptReadinessEvidenceInCommand(command));
    }

    public CommandResult<State> acceptReadinessEvidenceInCommand(
            RemoteGateCommand command)
    {
        requireNonNull(command, "command is null");
        RemoteGateEvidence persisted = evidence.findReadyEvidence(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.proofId())
                .orElseThrow(() -> rejected("Fresh exact-head readiness proof is missing"));
        if (!persisted.matches(command)) {
            throw rejected("Readiness proof does not match the current exact head");
        }
        return moveWithProofInCommand(
                command.stage(), command.proofId(), "ACCEPT_REMOTE_READINESS",
                StageCheckpoint.WAITING_REMOTE_REVIEW,
                StageCheckpoint.READY_TO_MERGE);
    }

    public CommandResult<State> authorizeMerge(MergeAuthorizationCommand command)
    {
        requireNonNull(command, "command is null");
        return execute(command.stage(), () -> authorizeMergeInCommand(command));
    }

    /** Starts an exact merge while a Remote runtime coordinator owns the Task command. */
    public CommandResult<State> authorizeMergeInCommand(MergeAuthorizationCommand command)
    {
        requireNonNull(command, "command is null");
        String proofIdentity = mergeProofIdentity(command);
        Optional<CommandResult<State>> replay = replayStructuralInCommand(
                command.stage(), "AUTHORIZE_MERGE", command.resultFence(),
                proofIdentity, StageCheckpoint.READY_TO_MERGE);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        AcceptedMergeAuthorization accepted = requireMergeAuthorization(command);
        return moveWithProofAndPendingResultInCommand(
                accepted.command.stage(), accepted.command.resultFence(),
                proofIdentity, "AUTHORIZE_MERGE",
                StageCheckpoint.READY_TO_MERGE, StageCheckpoint.MERGING);
    }

    /** Consumes one exact terminal merge failure and re-arms readiness recovery. */
    MergeFailureResult acceptMergeFailureInCommand(ResultCommand command)
    {
        ResultResolution resolution = acceptDrainingResultForHandoffInCommand(
                command, "ACCEPT_MERGE_FAILURE",
                StageCheckpoint.MERGING, StageCheckpoint.READY_TO_MERGE);
        return new MergeFailureResult(
                resolution.result(), resolution.wasAccepted());
    }

    AcceptedTerminal acceptTerminalObservationInCommand(TerminalObservationCommand command)
    {
        requireNonNull(command, "command is null");
        StageEndReason reason = command.outcome() == TerminalOutcome.MERGED
                ? StageEndReason.REMOTE_MERGED
                : StageEndReason.REMOTE_CLOSED;
        String proofIdentity = terminalProofIdentity(command);
        Optional<CommandResult<State>> replay = replayStructuralInCommand(
                command.stage(), terminalCause(reason), null,
                proofIdentity, null);
        if (replay.isPresent()) {
            return new AcceptedTerminal(command, replay.orElseThrow(), reason);
        }
        TerminalObservationEvidence persisted = evidence.findTerminalObservation(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.observationId())
                .orElseThrow(() -> rejected("Exact Remote terminal observation is missing"));
        RemoteSubjectEvidence subject = evidence.findCurrentRemoteSubject(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration())
                .orElseThrow(() -> rejected("Current accepted Remote subject is missing"));
        if (!persisted.matches(command) || !subject.matches(command)) {
            throw rejected("Remote terminal observation does not match the current exact head");
        }
        CommandResult<State> stage = sealRemoteObservationInCommand(
                command.stage(), proofIdentity, reason);
        return new AcceptedTerminal(command, stage, reason);
    }

    private AcceptedMergeAuthorization requireMergeAuthorization(
            MergeAuthorizationCommand command)
    {
        MergeAuthorizationEvidence persisted = evidence.findMergeAuthorization(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration(), command.authorizationId())
                .orElseThrow(() -> rejected("Exact persisted Merge authorization is missing"));
        RemoteSubjectEvidence subject = evidence.findCurrentRemoteSubject(
                        command.stage().taskId(), command.stage().stageId(),
                        command.stage().expectedStageGeneration())
                .orElseThrow(() -> rejected("Current accepted Remote subject is missing"));
        if (!persisted.matches(command) || !subject.matches(command)) {
            throw rejected("Merge authorization does not match its exact readiness subject");
        }
        return new AcceptedMergeAuthorization(command);
    }

    @Override
    protected boolean accepts(TaskLifecycle lifecycle)
    {
        return lifecycle == TaskLifecycle.ACTIVE;
    }

    public record FeedbackCommand(
            Command stage, String batchId, String observationId, String contentDigest)
    {
        public FeedbackCommand
        {
            requireNonNull(stage, "stage is null");
            requireText(batchId, "batchId");
            requireText(observationId, "observationId");
            requireText(contentDigest, "contentDigest");
        }
    }

    public record FeedbackEvidence(
            String taskId,
            String stageId,
            long stageGeneration,
            String batchId,
            String observationId,
            String contentDigest)
    {
        public FeedbackEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(batchId, "batchId");
            requireText(observationId, "observationId");
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
                    && observationId.equals(command.observationId())
                    && contentDigest.equals(command.contentDigest());
        }
    }

    public record FeedbackCompletionCommand(
            Command stage,
            String batchId,
            boolean pushed,
            String resultHeadSha,
            String resultSnapshotId)
    {
        public FeedbackCompletionCommand
        {
            requireNonNull(stage, "stage is null");
            requireText(batchId, "batchId");
            if (pushed) {
                requireText(resultHeadSha, "resultHeadSha");
                requireText(resultSnapshotId, "resultSnapshotId");
            }
            else if (resultHeadSha != null || resultSnapshotId != null) {
                throw new IllegalArgumentException(
                        "Reply-only completion cannot invent a new head");
            }
        }
    }

    public record FeedbackCompletionEvidence(
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String batchId,
            boolean pushed,
            String resultHeadSha,
            String resultSnapshotId)
    {
        public FeedbackCompletionEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(batchId, "batchId");
            if (taskEpoch < 1 || stageGeneration < 1) {
                throw new IllegalArgumentException(
                        "Remote feedback completion fence is invalid");
            }
        }

        private boolean matches(FeedbackCompletionCommand command)
        {
            return taskId.equals(command.stage().taskId())
                    && taskEpoch == command.stage().expectedTaskEpoch()
                    && stageId.equals(command.stage().stageId())
                    && stageGeneration == command.stage().expectedStageGeneration()
                    && batchId.equals(command.batchId())
                    && pushed == command.pushed()
                    && Objects.equals(resultHeadSha, command.resultHeadSha())
                    && Objects.equals(resultSnapshotId, command.resultSnapshotId());
        }
    }

    public record RemoteGateCommand(
            Command stage, String proofId, String headSha, String baseSha)
    {
        public RemoteGateCommand
        {
            requireNonNull(stage, "stage is null");
            requireText(proofId, "proofId");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
        }
    }

    public record RemoteGateEvidence(
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String proofId,
            String headSha,
            String baseSha)
    {
        public RemoteGateEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(proofId, "proofId");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
            if (taskEpoch < 1 || stageGeneration < 1) {
                throw new IllegalArgumentException("Remote gate fence is invalid");
            }
        }

        private boolean matches(RemoteGateCommand command)
        {
            return taskId.equals(command.stage().taskId())
                    && taskEpoch == command.stage().expectedTaskEpoch()
                    && stageId.equals(command.stage().stageId())
                    && stageGeneration == command.stage().expectedStageGeneration()
                    && proofId.equals(command.proofId())
                    && headSha.equals(command.headSha())
                    && baseSha.equals(command.baseSha());
        }
    }

    public record MergeAuthorizationCommand(
            Command stage,
            String authorizationId,
            String readinessEvidenceId,
            long subjectRevision,
            String policyRevisionId,
            String consentId,
            ResultFence resultFence)
    {
        public MergeAuthorizationCommand
        {
            requireNonNull(stage, "stage is null");
            requireText(authorizationId, "authorizationId");
            requireText(readinessEvidenceId, "readinessEvidenceId");
            requireText(policyRevisionId, "policyRevisionId");
            requireText(consentId, "consentId");
            requireNonNull(resultFence, "resultFence is null");
            if (subjectRevision < 1) {
                throw new IllegalArgumentException("subjectRevision must be positive");
            }
        }
    }

    record MergeFailureResult(CommandResult<State> stage, boolean accepted)
    {
        MergeFailureResult
        {
            requireNonNull(stage, "stage is null");
        }
    }

    public record MergeAuthorizationEvidence(
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String authorizationId,
            String readinessEvidenceId,
            long subjectRevision,
            String policyRevisionId,
            String consentId,
            ResultFence resultFence)
    {
        public MergeAuthorizationEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(authorizationId, "authorizationId");
            requireText(readinessEvidenceId, "readinessEvidenceId");
            requireText(policyRevisionId, "policyRevisionId");
            requireText(consentId, "consentId");
            requireNonNull(resultFence, "resultFence is null");
            if (taskEpoch < 1 || stageGeneration < 1 || subjectRevision < 1) {
                throw new IllegalArgumentException("Merge authorization identity is invalid");
            }
        }

        private boolean matches(MergeAuthorizationCommand command)
        {
            return taskId.equals(command.stage().taskId())
                    && taskEpoch == command.stage().expectedTaskEpoch()
                    && stageId.equals(command.stage().stageId())
                    && stageGeneration == command.stage().expectedStageGeneration()
                    && authorizationId.equals(command.authorizationId())
                    && readinessEvidenceId.equals(command.readinessEvidenceId())
                    && subjectRevision == command.subjectRevision()
                    && policyRevisionId.equals(command.policyRevisionId())
                    && consentId.equals(command.consentId())
                    && resultFence.equals(command.resultFence());
        }
    }

    public record TerminalObservationCommand(
            Command stage,
            String observationId,
            long observationRevision,
            String remoteHeadSha,
            String baseSha,
            TerminalOutcome outcome)
    {
        public TerminalObservationCommand
        {
            requireNonNull(stage, "stage is null");
            requireText(observationId, "observationId");
            requireText(remoteHeadSha, "remoteHeadSha");
            requireText(baseSha, "baseSha");
            requireNonNull(outcome, "outcome is null");
            if (observationRevision < 1) {
                throw new IllegalArgumentException("observationRevision must be positive");
            }
        }
    }

    public record TerminalObservationEvidence(
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String observationId,
            long observationRevision,
            String remoteHeadSha,
            String baseSha,
            TerminalOutcome outcome)
    {
        public TerminalObservationEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(observationId, "observationId");
            requireText(remoteHeadSha, "remoteHeadSha");
            requireText(baseSha, "baseSha");
            requireNonNull(outcome, "outcome is null");
            if (taskEpoch < 1 || stageGeneration < 1 || observationRevision < 1) {
                throw new IllegalArgumentException("Remote observation identity is invalid");
            }
        }

        private boolean matches(TerminalObservationCommand command)
        {
            return taskId.equals(command.stage().taskId())
                    && taskEpoch == command.stage().expectedTaskEpoch()
                    && stageId.equals(command.stage().stageId())
                    && stageGeneration == command.stage().expectedStageGeneration()
                    && observationId.equals(command.observationId())
                    && observationRevision == command.observationRevision()
                    && remoteHeadSha.equals(command.remoteHeadSha())
                    && baseSha.equals(command.baseSha())
                    && outcome == command.outcome();
        }
    }

    /** Current accepted head/base for one Remote Development generation. */
    public record RemoteSubjectEvidence(
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            long subjectRevision,
            String headSha,
            String baseSha)
    {
        public RemoteSubjectEvidence
        {
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
            if (taskEpoch < 1 || stageGeneration < 1 || subjectRevision < 1) {
                throw new IllegalArgumentException("Remote subject identity is invalid");
            }
        }

        private boolean matches(MergeAuthorizationCommand command)
        {
            ResultFence fence = command.resultFence();
            return matchesOwner(command.stage())
                    && subjectRevision == command.subjectRevision()
                    && headSha.equals(fence.expectedHeadSha())
                    && baseSha.equals(fence.expectedBaseSha());
        }

        private boolean matches(TerminalObservationCommand command)
        {
            return matchesOwner(command.stage())
                    && subjectRevision == command.observationRevision()
                    && headSha.equals(command.remoteHeadSha())
                    && baseSha.equals(command.baseSha());
        }

        private boolean matchesOwner(Command command)
        {
            return taskId.equals(command.taskId())
                    && taskEpoch == command.expectedTaskEpoch()
                    && stageId.equals(command.stageId())
                    && stageGeneration == command.expectedStageGeneration();
        }
    }

    public enum TerminalOutcome
    {
        MERGED,
        CLOSED
    }

    public static final class AcceptedMergeAuthorization
    {
        private final MergeAuthorizationCommand command;

        private AcceptedMergeAuthorization(MergeAuthorizationCommand command)
        {
            this.command = requireNonNull(command, "command is null");
        }
    }

    public static final class AcceptedTerminal
    {
        private final TerminalObservationCommand command;
        private final CommandResult<State> stage;
        private final StageEndReason reason;

        private AcceptedTerminal(
                TerminalObservationCommand command,
                CommandResult<State> stage,
                StageEndReason reason)
        {
            this.command = requireNonNull(command, "command is null");
            this.stage = requireNonNull(stage, "stage is null");
            this.reason = requireNonNull(reason, "reason is null");
        }

        public String commandId() { return command.stage().commandId(); }
        public String actor() { return command.stage().actor(); }
        public String taskId() { return command.stage().taskId(); }
        public long taskEpoch() { return command.stage().expectedTaskEpoch(); }
        public String stageId() { return command.stage().stageId(); }
        public long stageGeneration() { return command.stage().expectedStageGeneration(); }
        public String observationId() { return command.observationId(); }
        public long observationRevision() { return command.observationRevision(); }
        public String remoteHeadSha() { return command.remoteHeadSha(); }
        public String baseSha() { return command.baseSha(); }
        public StageEndReason reason() { return reason; }
        public CommandResult<State> stage() { return stage; }

        public ResultFence resultFence()
        {
            return new ResultFence(
                    taskEpoch(), stageId(), stageGeneration(),
                    "remote-observation:" + observationId(), 1, null,
                    remoteHeadSha(), baseSha());
        }
    }

    public interface EvidenceStore
    {
        Optional<FeedbackEvidence> findRemoteFeedback(
                String taskId, String stageId, long stageGeneration, String batchId);

        default Optional<FeedbackCompletionEvidence> findCompletedRemoteFeedback(
                String taskId, String stageId, long stageGeneration, String batchId)
        {
            return Optional.empty();
        }

        default Optional<RemoteGateEvidence> findCompletedMarkReady(
                String taskId, String stageId, long stageGeneration, String operationId)
        {
            return Optional.empty();
        }

        default Optional<RemoteGateEvidence> findAcceptedCi(
                String taskId, String stageId, long stageGeneration, String evidenceId)
        {
            return Optional.empty();
        }

        default Optional<RemoteGateEvidence> findObservedReady(
                String taskId, String stageId, long stageGeneration, String snapshotId)
        {
            return Optional.empty();
        }

        default Optional<RemoteGateEvidence> findHeadChange(
                String taskId, String stageId, long stageGeneration, String snapshotId)
        {
            return Optional.empty();
        }

        default Optional<RemoteGateEvidence> findReadyEvidence(
                String taskId, String stageId, long stageGeneration, String evidenceId)
        {
            return Optional.empty();
        }

        Optional<MergeAuthorizationEvidence> findMergeAuthorization(
                String taskId, String stageId, long stageGeneration, String authorizationId);

        Optional<TerminalObservationEvidence> findTerminalObservation(
                String taskId, String stageId, long stageGeneration, String observationId);

        Optional<RemoteSubjectEvidence> findCurrentRemoteSubject(
                String taskId, String stageId, long stageGeneration);

        static EvidenceStore empty()
        {
            return new EvidenceStore()
            {
                @Override
                public Optional<FeedbackEvidence> findRemoteFeedback(
                        String taskId, String stageId, long stageGeneration, String batchId)
                {
                    return Optional.empty();
                }

                @Override
                public Optional<FeedbackCompletionEvidence> findCompletedRemoteFeedback(
                        String taskId, String stageId, long stageGeneration,
                        String batchId)
                {
                    return Optional.empty();
                }

                @Override
                public Optional<MergeAuthorizationEvidence> findMergeAuthorization(
                        String taskId, String stageId, long stageGeneration, String authorizationId)
                {
                    return Optional.empty();
                }

                @Override
                public Optional<TerminalObservationEvidence> findTerminalObservation(
                        String taskId, String stageId, long stageGeneration, String observationId)
                {
                    return Optional.empty();
                }

                @Override
                public Optional<RemoteSubjectEvidence> findCurrentRemoteSubject(
                        String taskId, String stageId, long stageGeneration)
                {
                    return Optional.empty();
                }
            };
        }
    }

    private static String terminalCause(StageEndReason reason)
    {
        return "ACCEPT_REMOTE_" + (reason == StageEndReason.REMOTE_MERGED
                ? "MERGED" : "CLOSED");
    }

    private static String mergeProofIdentity(MergeAuthorizationCommand command)
    {
        return proofIdentity(
                command.authorizationId(), command.readinessEvidenceId(),
                command.subjectRevision(), command.policyRevisionId(), command.consentId());
    }

    private static String terminalProofIdentity(TerminalObservationCommand command)
    {
        return proofIdentity(
                command.observationId(), command.observationRevision(),
                command.remoteHeadSha(), command.baseSha(), command.outcome());
    }

    private static String proofIdentity(Object... parts)
    {
        StringBuilder identity = new StringBuilder();
        for (Object part : parts) {
            String value = String.valueOf(part);
            identity.append(value.length()).append(':').append(value);
        }
        return identity.toString();
    }

    private static CommandRejectedException rejected(String message)
    {
        return new CommandRejectedException(
                CommandRejectedException.Reason.INVALID_STATE, message);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
