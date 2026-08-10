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

import java.time.Instant;

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
        RUN_CI_FIXER
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
        CI_FIXER
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
        AGENT_RESULT_READY
    }

    public enum ProcessAttemptState
    {
        RESERVED,
        ACTIVATED,
        STOPPED
    }

    public record Task(
            String taskId,
            String requestKey,
            String repositoryId,
            String goalText,
            TaskStatus status,
            long epoch,
            String launchBaseSha,
            String currentBaseSha,
            String branchName,
            String worktreePath,
            String currentHeadSha,
            String taskSessionId,
            String ciSessionId,
            String prId,
            String currentLifecycleRevisionId,
            long pendingWorkWatermark,
            long lastReconciledWorkWatermark,
            long reconciliationSequence,
            String selectedWriterOperationId,
            long writerFenceSequence)
    {
        public Task
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(requestKey, "requestKey is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(goalText, "goalText is null");
            requireNonNull(status, "status is null");
            requireNonNull(branchName, "branchName is null");
            requireNonNull(worktreePath, "worktreePath is null");
            requireNonNull(currentLifecycleRevisionId,
                    "currentLifecycleRevisionId is null");
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
    public record PullRequestSubject(
            String prId,
            String taskId,
            String repositoryId,
            String baseRef,
            String baseSha,
            String targetBaseRef,
            String scopeKey,
            String branchName,
            String createdFromHeadSha,
            String remoteIdentityId,
            String provider,
            String repositoryExternalId,
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
            requireNonNull(createdFromHeadSha,
                    "createdFromHeadSha is null");
        }

        public boolean published()
        {
            return remoteIdentityId != null;
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

    /** Program-observed committed worktree state at writer admission. */
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
            String processMetadataRef,
            Instant storedAt)
    {
        public AgentResult
        {
            requireNonNull(resultId, "resultId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(terminalOutcome,
                    "terminalOutcome is null");
            requireNonNull(processMetadataRef,
                    "processMetadataRef is null");
            requireNonNull(storedAt, "storedAt is null");
        }
    }

    public record AgentProcessAttempt(
            String processAttemptId,
            String runId,
            String operationId,
            long claimGeneration,
            String executionId,
            String capabilityId,
            ProcessAttemptState state,
            String processIdentity,
            Instant reservedAt,
            Instant activatedAt,
            String processMetadataRef,
            Instant stoppedAt)
    {
        public AgentProcessAttempt
        {
            requireNonNull(processAttemptId,
                    "processAttemptId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(operationId, "operationId is null");
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
        }
    }
}
