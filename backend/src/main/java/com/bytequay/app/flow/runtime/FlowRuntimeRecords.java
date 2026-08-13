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
package com.bytequay.app.flow.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Immutable records returned by the greenfield runtime transaction boundary. */
public final class FlowRuntimeRecords
{
    private FlowRuntimeRecords() {}

    public enum TaskStatus
    {
        CREATED,
        ACTIVE,
        WAITING_USER,
        NEEDS_ATTENTION,
        COMPLETED,
        CANCELED
    }

    public enum OperationKind
    {
        PROVISION_TASK,
        RECONCILE_TASK,
        RUN_TASK_TURN,
        RUN_REVIEWER,
        RUN_CI_FIXER,
        RUN_CI_LEARNING,
        OBSERVE_CI,
        PUBLISH
    }

    public enum OperationState
    {
        READY,
        CLAIMED,
        WAITING,
        SUCCEEDED,
        RETRYABLE,
        FAILED,
        CANCELED
    }

    public enum AgentRole
    {
        TASK_AGENT,
        ADVERSARIAL_REVIEWER,
        CI_FIXER,
        CI_LEARNER
    }

    public enum GateIntent
    {
        INITIAL_PUBLISH,
        CI_UPDATE
    }

    public enum WakeKind
    {
        INITIAL_TASK,
        CI_FIX_READY,
        AGENT_RESULT_READY
    }

    public enum SessionState
    {
        NEW,
        IDLE,
        RUNNING,
        PARKED_CHILD,
        CLOSED
    }

    public enum RunState
    {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELED
    }

    public enum TerminalOutcome
    {
        COMPLETED,
        FAILED,
        CANCELED
    }

    public enum PendingKind
    {
        INITIAL_TASK,
        FINAL_RED,
        CI_FIX_READY,
        AGENT_RESULT_READY
    }

    public enum TaskTerminalRequestKind
    {
        REVIEWER,
        READY_FOR_REVIEW
    }

    public enum ProcessAttemptState
    {
        RESERVED,
        ACTIVATED,
        STOPPED
    }

    public enum InProcessStopType
    {
        NORMAL_RETURN,
        COOPERATIVE_CANCELLATION
    }

    public enum ProcessQuarantineReason
    {
        UNCOOPERATIVE_CANCELLATION,
        IN_PROCESS_OWNER_UNAVAILABLE
    }

    public enum ChangeSetSource
    {
        TASK_AGENT,
        CI_FIXER,
        UPSTREAM_SYNC
    }

    public enum CiFixOutcome
    {
        FIX_PREPARED,
        NO_HEAD_CHANGE
    }

    public enum CiFixSourceKind
    {
        REPAIR_ATTEMPT,
        CLEANUP
    }

    /** Program-owned CI source inherited through every reviewer cycle. */
    public record CiFixReviewOrigin(
            String pendingId,
            CiFixSourceKind sourceKind,
            String sourceId)
    {
        public CiFixReviewOrigin
        {
            requireNonNull(pendingId, "pendingId is null");
            requireNonNull(sourceKind, "sourceKind is null");
            requireNonNull(sourceId, "sourceId is null");
        }
    }

    public enum LocalCheckConclusion
    {
        PASSED,
        FAILED,
        UNAVAILABLE
    }

    public record LocalCheckPolicyRevision(
            String policyRevisionId,
            String repositoryId,
            long sequence,
            String sourceRevision,
            String sourceDigest,
            Instant recordedAt)
    {
        public LocalCheckPolicyRevision
        {
            requireNonNull(policyRevisionId, "policyRevisionId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            if (sequence < 1) {
                throw new IllegalArgumentException(
                        "sequence must be positive");
            }
            requireNonNull(sourceRevision, "sourceRevision is null");
            requireNonNull(sourceDigest, "sourceDigest is null");
            requireNonNull(recordedAt, "recordedAt is null");
        }
    }

    public record LocalCheckProfile(
            String profileId,
            String policyRevisionId,
            String name,
            List<String> command,
            String workingDirectory,
            List<String> environmentAllowlist,
            Duration timeout,
            List<GateIntent> requiredForGateKinds)
    {
        public LocalCheckProfile
        {
            requireNonNull(profileId, "profileId is null");
            requireNonNull(policyRevisionId, "policyRevisionId is null");
            requireNonNull(name, "name is null");
            command = List.copyOf(command);
            requireNonNull(workingDirectory, "workingDirectory is null");
            environmentAllowlist = List.copyOf(environmentAllowlist);
            requireNonNull(timeout, "timeout is null");
            requiredForGateKinds = List.copyOf(requiredForGateKinds);
        }
    }

    public record LocalCheckRun(
            String checkRunId,
            String taskId,
            String changeSetRevisionId,
            String policyRevisionId,
            String profileId,
            String operationId,
            String agentRunId,
            List<String> command,
            String workingDirectory,
            long attemptSequence,
            String observedStartHead,
            String observedEndHead,
            Instant startedAt,
            Instant completedAt,
            LocalCheckConclusion conclusion,
            Integer exitCode,
            String unavailableReasonCode,
            String outputRef,
            String outputText,
            boolean outputTruncated,
            boolean trackedTreeCleanBefore,
            boolean trackedTreeCleanAfter)
    {
        public LocalCheckRun
        {
            requireNonNull(checkRunId, "checkRunId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(changeSetRevisionId,
                    "changeSetRevisionId is null");
            requireNonNull(policyRevisionId,
                    "policyRevisionId is null");
            requireNonNull(profileId, "profileId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(agentRunId, "agentRunId is null");
            command = List.copyOf(command);
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command is empty");
            }
            requireNonNull(workingDirectory, "workingDirectory is null");
            if (attemptSequence < 1) {
                throw new IllegalArgumentException(
                        "attemptSequence must be positive");
            }
            requireNonNull(observedStartHead,
                    "observedStartHead is null");
            if (observedEndHead == null
                    && conclusion != LocalCheckConclusion.UNAVAILABLE) {
                throw new IllegalArgumentException(
                        "only unavailable checks may lack an observed end head");
            }
            requireNonNull(startedAt, "startedAt is null");
            requireNonNull(completedAt, "completedAt is null");
            requireNonNull(conclusion, "conclusion is null");
            if ((conclusion == LocalCheckConclusion.UNAVAILABLE)
                    != (unavailableReasonCode != null)) {
                throw new IllegalArgumentException(
                        "only unavailable checks have a reason code");
            }
            requireNonNull(outputRef, "outputRef is null");
            requireNonNull(outputText, "outputText is null");
        }
    }

    public record LocalCheckEvidence(
            String taskId,
            String changeSetRevisionId,
            String policyRevisionId,
            GateIntent gateKind,
            List<LocalCheckRun> runs,
            List<String> blockerCodes)
    {
        public LocalCheckEvidence
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(changeSetRevisionId,
                    "changeSetRevisionId is null");
            requireNonNull(gateKind, "gateKind is null");
            runs = List.copyOf(runs);
            blockerCodes = List.copyOf(blockerCodes);
        }

        public List<String> checkRunRefs()
        {
            return runs.stream().map(LocalCheckRun::checkRunId).toList();
        }
    }

    public record Task(
            String taskId,
            String requestKey,
            String repositoryId,
            String repositoryOwner,
            String repositoryName,
            String goalText,
            String repositoryRoot,
            String gitCommonDir,
            String remoteName,
            String baseRef,
            String launchDigest,
            TaskStatus status,
            long epoch,
            String launchBaseSha,
            String currentBaseSha,
            String currentBaseRevisionId,
            String branchName,
            String worktreePath,
            String currentHeadSha,
            String currentChangeSetRevisionId,
            String taskSessionId,
            String ciSessionId,
            String prId,
            String currentLifecycleRevisionId,
            long pendingWorkWatermark,
            long lastReconciledWorkWatermark,
            long reconciliationSequence,
            String selectedWriterOperationId,
            String waitingMutationStateRef,
            long writerFenceSequence)
    {
        public Task
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(requestKey, "requestKey is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(repositoryOwner, "repositoryOwner is null");
            requireNonNull(repositoryName, "repositoryName is null");
            requireNonNull(goalText, "goalText is null");
            requireNonNull(repositoryRoot, "repositoryRoot is null");
            requireNonNull(gitCommonDir, "gitCommonDir is null");
            requireNonNull(remoteName, "remoteName is null");
            requireNonNull(baseRef, "baseRef is null");
            requireNonNull(launchDigest, "launchDigest is null");
            requireNonNull(status, "status is null");
            requireNonNull(branchName, "branchName is null");
            requireNonNull(worktreePath, "worktreePath is null");
            requireNonNull(currentLifecycleRevisionId,
                    "currentLifecycleRevisionId is null");
        }
    }

    public record TaskBaseRevision(
            String baseRevisionId,
            String taskId,
            long sequence,
            String previousBaseSha,
            String baseSha,
            String reasonCode,
            String evidenceRef,
            String sourceOperationId,
            Instant recordedAt)
    {
        public TaskBaseRevision
        {
            requireNonNull(baseRevisionId, "baseRevisionId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(baseSha, "baseSha is null");
            requireNonNull(reasonCode, "reasonCode is null");
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            boolean supportedReason = switch (reasonCode) {
                case "INITIAL", "UPSTREAM_TARGET_INTEGRATION",
                        "EXPLICIT_RECONCILIATION" -> true;
                default -> false;
            };
            if (!supportedReason
                    || reasonCode.equals("INITIAL") != (sequence == 1)
                    || (sequence == 1) != (previousBaseSha == null)) {
                throw new IllegalArgumentException(
                        "invalid Task base revision reason/predecessor");
            }
            requireNonNull(evidenceRef, "evidenceRef is null");
            requireNonNull(sourceOperationId, "sourceOperationId is null");
            requireNonNull(recordedAt, "recordedAt is null");
        }
    }

    public record ChangeSetRevision(
            String changeSetRevisionId,
            String taskId,
            long sequence,
            String previousChangeSetRevisionId,
            String previousHeadSha,
            String headSha,
            String baseRevisionId,
            String baseSha,
            String headTreeDigest,
            String diffDigest,
            boolean differsFromBase,
            ChangeSetSource source,
            String sourceRunId,
            String sourceOperationId,
            Instant adoptedAt)
    {
        public ChangeSetRevision
        {
            requireNonNull(changeSetRevisionId,
                    "changeSetRevisionId is null");
            requireNonNull(taskId, "taskId is null");
            if (sequence < 1
                    || (sequence == 1)
                            != (previousChangeSetRevisionId == null)) {
                throw new IllegalArgumentException(
                        "invalid change-set revision sequence/predecessor");
            }
            requireNonNull(previousHeadSha, "previousHeadSha is null");
            requireNonNull(headSha, "headSha is null");
            requireNonNull(baseRevisionId, "baseRevisionId is null");
            requireNonNull(baseSha, "baseSha is null");
            requireNonNull(headTreeDigest, "headTreeDigest is null");
            requireNonNull(diffDigest, "diffDigest is null");
            requireNonNull(source, "source is null");
            if (source == ChangeSetSource.UPSTREAM_SYNC
                    && sourceRunId != null) {
                throw new IllegalArgumentException(
                        "UPSTREAM_SYNC must not name an AgentRun");
            }
            if (source != ChangeSetSource.UPSTREAM_SYNC) {
                requireNonNull(sourceRunId, "sourceRunId is null");
            }
            requireNonNull(sourceOperationId, "sourceOperationId is null");
            requireNonNull(adoptedAt, "adoptedAt is null");
        }
    }

    public record TaskLifecycleRevision(
            String lifecycleRevisionId,
            String taskId,
            long sequence,
            TaskStatus fromStatus,
            TaskStatus toStatus,
            String reasonCode,
            String evidenceRef,
            String operationId,
            Instant recordedAt)
    {
        public TaskLifecycleRevision
        {
            requireNonNull(lifecycleRevisionId,
                    "lifecycleRevisionId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(toStatus, "toStatus is null");
            requireNonNull(reasonCode, "reasonCode is null");
            requireNonNull(recordedAt, "recordedAt is null");
        }
    }

    /** The stable local PR plus its optional set-once GitHub identity. */
    public record GitHubRepositoryLocator(
            String repositoryExternalId,
            String owner,
            String name)
    {
        public GitHubRepositoryLocator
        {
            requireNonNull(repositoryExternalId,
                    "repositoryExternalId is null");
            requireNonNull(owner, "owner is null");
            requireNonNull(name, "name is null");
        }
    }

    /** The stable local PR plus its optional set-once GitHub identity. */
    public record PullRequestSubject(
            String prId,
            String taskId,
            String repositoryId,
            String baseRef,
            String baseSha,
            String targetBaseRef,
            String scopeKey,
            String branchName,
            String createdFromChangeSetRevisionId,
            String createdFromHeadSha,
            String currentDraftRevisionId,
            String remoteIdentityId,
            String provider,
            String repositoryExternalId,
            String repositoryOwner,
            String repositoryName,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            Long prNumber,
            String currentRemoteHead)
    {
        public PullRequestSubject
        {
            requireNonNull(prId, "prId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(baseRef, "baseRef is null");
            requireNonNull(baseSha, "baseSha is null");
            requireNonNull(targetBaseRef, "targetBaseRef is null");
            requireNonNull(scopeKey, "scopeKey is null");
            requireNonNull(branchName, "branchName is null");
            requireNonNull(createdFromChangeSetRevisionId,
                    "createdFromChangeSetRevisionId is null");
            requireNonNull(createdFromHeadSha,
                    "createdFromHeadSha is null");
            boolean hasIdentity = remoteIdentityId != null;
            if (hasIdentity != (provider != null)
                    || hasIdentity != (repositoryExternalId != null)
                    || hasIdentity != (repositoryOwner != null)
                    || hasIdentity != (repositoryName != null)
                    || hasIdentity != (headRepositoryExternalId != null)
                    || hasIdentity != (headRepositoryOwner != null)
                    || hasIdentity != (headRepositoryName != null)
                    || hasIdentity != (prNumber != null)
                    || hasIdentity != (currentRemoteHead != null)
                    || hasIdentity && !provider.equals("GITHUB")) {
                throw new IllegalArgumentException(
                        "published PR GitHub identity is incomplete");
            }
        }

        public boolean published()
        {
            return remoteIdentityId != null;
        }
    }

    /** Immutable, exact-head local title/body proposed for first publication. */
    public record PrDraftRevision(
            String draftRevisionId,
            String prId,
            long sequence,
            String changeSetRevisionId,
            String headSha,
            String title,
            String body,
            String draftDigest,
            String createdByRunId,
            Instant createdAt)
    {
        public PrDraftRevision
        {
            requireNonNull(draftRevisionId, "draftRevisionId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(changeSetRevisionId,
                    "changeSetRevisionId is null");
            requireNonNull(headSha, "headSha is null");
            requireNonNull(title, "title is null");
            requireNonNull(body, "body is null");
            requireNonNull(draftDigest, "draftDigest is null");
            requireNonNull(createdByRunId, "createdByRunId is null");
            requireNonNull(createdAt, "createdAt is null");
            if (sequence < 1 || title.isBlank() || title.length() > 256
                    || body.length() > 65_536) {
                throw new IllegalArgumentException("invalid PR draft revision");
            }
        }
    }

    public enum ReadyPolicy
    {
        KEEP_DRAFT,
        MARK_READY_ON_EXACT_GREEN
    }

    /** Set-once policy established by the consumed initial authorization. */
    public record PrReadyPolicyRevision(
            String readyPolicyRevisionId,
            String prId,
            long sequence,
            ReadyPolicy policy,
            String requiredCiPolicyRevisionId,
            String authorizationId,
            String operationId,
            String effectPlanId,
            String actionDigest,
            String proposedHead,
            String publicationReceiptId,
            String publicationReceiptDigest,
            String policyDigest,
            Instant createdAt)
    {
        public PrReadyPolicyRevision
        {
            requireNonNull(readyPolicyRevisionId,
                    "readyPolicyRevisionId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(policy, "policy is null");
            requireNonNull(requiredCiPolicyRevisionId,
                    "requiredCiPolicyRevisionId is null");
            requireNonNull(authorizationId, "authorizationId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(effectPlanId, "effectPlanId is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(publicationReceiptId,
                    "publicationReceiptId is null");
            requireNonNull(publicationReceiptDigest,
                    "publicationReceiptDigest is null");
            requireNonNull(policyDigest, "policyDigest is null");
            requireNonNull(createdAt, "createdAt is null");
            if (sequence < 1) {
                throw new IllegalArgumentException(
                        "ready-policy sequence must be positive");
            }
        }
    }

    public record Operation(
            String operationId,
            String ownerKind,
            String ownerId,
            String taskId,
            OperationKind kind,
            String subjectDigest,
            String inputRef,
            Long workWatermark,
            OperationState state,
            int attempt,
            String resultRef,
            Instant createdAt)
    {
        public Operation
        {
            requireNonNull(operationId, "operationId is null");
            requireNonNull(ownerKind, "ownerKind is null");
            requireNonNull(ownerId, "ownerId is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(subjectDigest, "subjectDigest is null");
            requireNonNull(inputRef, "inputRef is null");
            requireNonNull(state, "state is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    /** One current dispatch generation. The token is never model-visible. */
    public record Claim(
            String operationId,
            String taskId,
            OperationKind kind,
            long generation,
            String claimToken,
            String workerId,
            Instant expiresAt)
    {
        public Claim
        {
            requireNonNull(operationId, "operationId is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(claimToken, "claimToken is null");
            requireNonNull(workerId, "workerId is null");
            requireNonNull(expiresAt, "expiresAt is null");
        }
    }

    /** Fenced local-mutation authority, also never model-visible. */
    public record WriterFence(
            String taskId,
            String operationId,
            long taskEpoch,
            AgentRole holderKind,
            long fencingToken,
            long claimGeneration,
            String claimTokenDigest,
            String headSha,
            String treeDigest,
            String snapshotEvidenceRef,
            Instant expiresAt)
    {
        public WriterFence
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(holderKind, "holderKind is null");
            requireNonNull(claimTokenDigest,
                    "claimTokenDigest is null");
            requireNonNull(headSha, "headSha is null");
            requireNonNull(treeDigest, "treeDigest is null");
            requireNonNull(snapshotEvidenceRef,
                    "snapshotEvidenceRef is null");
            requireNonNull(expiresAt, "expiresAt is null");
        }
    }

    /**
     * Writer-admission input only. It is not mechanically inspected yet and
     * must never be reused as change-set evidence.
     */
    public record WorktreeSnapshot(
            String headSha, String treeDigest, String evidenceRef)
    {
        public WorktreeSnapshot
        {
            requireNonNull(headSha, "headSha is null");
            requireNonNull(treeDigest, "treeDigest is null");
            requireNonNull(evidenceRef, "evidenceRef is null");
        }
    }

    public record AgentSession(
            String sessionId,
            String taskId,
            AgentRole role,
            SessionState state,
            String lastRunId,
            Instant createdAt,
            Instant updatedAt)
    {
        public AgentSession
        {
            requireNonNull(sessionId, "sessionId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(role, "role is null");
            requireNonNull(state, "state is null");
            requireNonNull(createdAt, "createdAt is null");
            requireNonNull(updatedAt, "updatedAt is null");
        }
    }

    public record AgentRun(
            String runId,
            String operationId,
            String sessionId,
            AgentRole role,
            String headSha,
            String promptManifestRef,
            String capabilitySetRef,
            String inputRef,
            String inputChangeSetRevisionId,
            String inputRemoteHeadSha,
            WakeKind wakeKind,
            GateIntent intendedGateKind,
            RunState state,
            String failureReasonCode,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt)
    {
        public AgentRun
        {
            requireNonNull(runId, "runId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(sessionId, "sessionId is null");
            requireNonNull(role, "role is null");
            requireNonNull(headSha, "headSha is null");
            requireNonNull(promptManifestRef,
                    "promptManifestRef is null");
            requireNonNull(capabilitySetRef,
                    "capabilitySetRef is null");
            requireNonNull(inputRef, "inputRef is null");
            boolean completeTaskInput = wakeKind != null
                    && intendedGateKind != null
                    && ((intendedGateKind == GateIntent.INITIAL_PUBLISH
                            && inputRemoteHeadSha == null
                            && ((wakeKind == WakeKind.INITIAL_TASK
                                    && inputChangeSetRevisionId == null)
                                || (wakeKind == WakeKind.AGENT_RESULT_READY
                                    && inputChangeSetRevisionId != null)))
                        || (intendedGateKind == GateIntent.CI_UPDATE
                            && inputChangeSetRevisionId != null
                            && inputRemoteHeadSha != null));
            boolean anyTaskInput = wakeKind != null
                    || intendedGateKind != null
                    || inputChangeSetRevisionId != null
                    || inputRemoteHeadSha != null;
            if ((role == AgentRole.TASK_AGENT && !completeTaskInput)
                    || (role != AgentRole.TASK_AGENT && anyTaskInput)) {
                throw new IllegalArgumentException(
                        "only Task Agent runs carry input change set, wake, and gate intent");
            }
            requireNonNull(state, "state is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    /** Final content is deliberately opaque; only the tagged outcome is typed. */
    public record AgentResult(
            String resultId,
            String runId,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String stopProofRef,
            Instant storedAt)
    {
        public AgentResult
        {
            requireNonNull(resultId, "resultId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(terminalOutcome,
                    "terminalOutcome is null");
            requireNonNull(stopProofRef, "stopProofRef is null");
            requireNonNull(storedAt, "storedAt is null");
        }
    }

    public record AgentProcessAttempt(
            String processAttemptId,
            String runId,
            String operationId,
            long claimGeneration,
            String claimTokenDigest,
            String executionId,
            String capabilityId,
            ProcessAttemptState state,
            Long jvmPid,
            Instant jvmStartedAt,
            Long threadId,
            String threadName,
            Long agentPid,
            Long agentPgid,
            Instant agentStartedAt,
            Instant reservedAt,
            Instant activatedAt,
            Instant capabilityRevokedAt,
            InProcessStopType stopType,
            String stopProofRef,
            Instant stoppedAt,
            ProcessQuarantineReason quarantineReason,
            Instant quarantinedAt)
    {
        public AgentProcessAttempt
        {
            requireNonNull(processAttemptId,
                    "processAttemptId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(claimTokenDigest,
                    "claimTokenDigest is null");
            requireNonNull(executionId, "executionId is null");
            requireNonNull(capabilityId, "capabilityId is null");
            requireNonNull(state, "state is null");
            requireNonNull(reservedAt, "reservedAt is null");
        }
    }

    public record ExpiredClaim(
            String operationId,
            String taskId,
            OperationKind kind,
            long generation,
            Instant expiredAt,
            String runId,
            String processAttemptId,
            ProcessAttemptState processAttemptState)
    {
        public ExpiredClaim
        {
            requireNonNull(operationId, "operationId is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(expiredAt, "expiredAt is null");
        }
    }

    public record FinalRedRegistration(
            String inboxId,
            String reconciliationOperationId,
            long workWatermark,
            String terminalReason)
    {
        public FinalRedRegistration
        {
            requireNonNull(inboxId, "inboxId is null");
            if ((reconciliationOperationId == null)
                    == (terminalReason == null)) {
                throw new IllegalArgumentException(
                        "registration needs reconciliation or terminal reason");
            }
        }
    }

    /** Immutable pending owner fact consumed by one reconciliation selection. */
    public record PendingWork(
            String pendingId,
            String taskId,
            String prId,
            PendingKind kind,
            String externalKey,
            String subjectHead,
            String payloadRef,
            String agentResultId,
            GateIntent intendedGateKind,
            long workWatermark,
            String selectedByOperationId,
            String handledByOperationId,
            String terminalReason)
    {
        public PendingWork
        {
            requireNonNull(pendingId, "pendingId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(externalKey, "externalKey is null");
            requireNonNull(subjectHead, "subjectHead is null");
            requireNonNull(payloadRef, "payloadRef is null");
            if ((kind != PendingKind.FINAL_RED)
                    != (intendedGateKind != null)) {
                throw new IllegalArgumentException(
                        "only Task-turn pending work carries gate intent");
            }
        }
    }

    /** Immutable program-owned subject for one fresh adversarial review. */
    public record ReviewerRequest(
            String requestId,
            String taskId,
            String parentOperationId,
            String parentRunId,
            String reviewerOperationId,
            String repositoryRoot,
            String baseHeadSha,
            String reviewedHeadSha,
            String remoteHeadSha,
            String originCiFixPendingId,
            String originCiFixSourceKind,
            String originCiFixSourceId,
            String changeSetRevisionId,
            String localCheckPolicyRevisionId,
            String headTreeDigest,
            String diffDigest,
            List<String> checkRunRefs,
            GateIntent intendedGateKind,
            Instant createdAt)
    {
        public ReviewerRequest
        {
            requireNonNull(requestId, "requestId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(parentOperationId,
                    "parentOperationId is null");
            requireNonNull(parentRunId, "parentRunId is null");
            requireNonNull(reviewerOperationId,
                    "reviewerOperationId is null");
            requireNonNull(repositoryRoot, "repositoryRoot is null");
            requireNonNull(baseHeadSha, "baseHeadSha is null");
            requireNonNull(reviewedHeadSha, "reviewedHeadSha is null");
            requireNonNull(changeSetRevisionId,
                    "changeSetRevisionId is null");
            requireNonNull(localCheckPolicyRevisionId,
                    "localCheckPolicyRevisionId is null");
            requireNonNull(headTreeDigest, "headTreeDigest is null");
            requireNonNull(diffDigest, "diffDigest is null");
            checkRunRefs = List.copyOf(checkRunRefs);
            requireNonNull(intendedGateKind,
                    "intendedGateKind is null");
            boolean initial = intendedGateKind == GateIntent.INITIAL_PUBLISH;
            boolean noRemoteOrCiOrigin = remoteHeadSha == null
                    && originCiFixPendingId == null
                    && originCiFixSourceKind == null
                    && originCiFixSourceId == null;
            boolean completeRemoteAndCiOrigin = remoteHeadSha != null
                    && originCiFixPendingId != null
                    && originCiFixSourceKind != null
                    && originCiFixSourceId != null;
            if ((initial && !noRemoteOrCiOrigin)
                    || (!initial && !completeRemoteAndCiOrigin)) {
                throw new IllegalArgumentException(
                        "reviewer request remote/CI origin does not match gate intent");
            }
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    /** One durable mutually exclusive terminal command for a Task run. */
    public record TaskTerminalRequest(
            String runId,
            TaskTerminalRequestKind kind,
            String requestId,
            Instant createdAt)
    {
        public TaskTerminalRequest
        {
            requireNonNull(runId, "runId is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(requestId, "requestId is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    /** Runtime-owned terminal request pointing at one frozen gate subject. */
    public record ReadyForReviewRequest(
            String requestId,
            String runId,
            String operationId,
            String taskId,
            String prId,
            String subjectRef,
            String subjectDigest,
            String actionRef,
            String actionDigest,
            Instant createdAt)
    {
        public ReadyForReviewRequest
        {
            requireNonNull(requestId, "requestId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(subjectRef, "subjectRef is null");
            requireNonNull(subjectDigest, "subjectDigest is null");
            requireNonNull(actionRef, "actionRef is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }
}
