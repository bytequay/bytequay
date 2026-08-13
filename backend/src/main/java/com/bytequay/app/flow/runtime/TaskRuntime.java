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

import com.bytequay.app.flow.runtime.FlowRuntime.MutationRejectedException;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleOwnerRevisionException;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentSession;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PrDraftRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskBaseRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskLifecycleRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Owns Task acceptance, provisioning, lifecycle, local PR identity, and drafts.
 *
 * <p>{@link FlowRuntime} remains the synchronized facade during vertical
 * extraction. Cross-owner checks still route through its narrow package
 * contracts until dispatch, agent, publish, and writer runtimes are extracted.
 */
final class TaskRuntime
{
    private static final int PROVISION_PRIORITY = 1_000;

    private final FlowRuntime runtime;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    TaskRuntime(FlowRuntime runtime, JdbcTemplate jdbc, Clock clock)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Persists one Task plus its provisioning operation/ticket. It performs no
     * Git, process, or model work.
     */
    Task startTask(TaskProvisioning.FrozenLaunch launch)
    {
        requireNonNull(launch, "launch is null");
        return runtime.inTransaction(() -> {
            Instant now = clock.instant();
            String taskId = launch.taskId();
            String lifecycleId = FlowRuntime.stableId("task-lifecycle", taskId, "1");
            int accepted = jdbc.update(
                    """
                    INSERT OR IGNORE INTO flow_runtime_task (
                        task_id, request_key, repository_id,
                        repository_owner, repository_name, goal_text, status,
                        repository_root, git_common_dir, remote_name,
                        base_ref,
                        launch_digest,
                        epoch, branch_name, worktree_path,
                        current_lifecycle_revision_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?,
                        1, ?, ?, ?)
                    """,
                    taskId,
                    launch.requestKey(),
                    launch.repositoryId(),
                    launch.repositoryOwner(),
                    launch.repositoryName(),
                    launch.goalText(),
                    launch.repositoryRoot(),
                    launch.gitCommonDir(),
                    launch.remoteName(),
                    launch.baseRef(),
                    launch.launchDigest(),
                    launch.branchName(),
                    launch.worktreePath(),
                    lifecycleId);
            if (accepted == 0) {
                Task task = runtime.taskForRequestKey(launch.requestKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "Task launch conflicts with another subject"));
                TaskProvisioning.assertStoredLaunch(task);
                if (!task.repositoryId().equals(launch.repositoryId())
                        || !task.goalText().equals(launch.goalText())) {
                    throw new IllegalStateException(
                            "requestKey already owns a different Task command");
                }
                return task;
            }
            if (accepted != 1) {
                throw new IllegalStateException(
                        "Task acceptance changed an unexpected row count");
            }
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_task_lifecycle_revision (
                        lifecycle_revision_id, task_id, sequence, from_status,
                        to_status, reason_code, recorded_at
                    ) VALUES (?, ?, 1, NULL, 'CREATED', 'TASK_STARTED', ?)
                    """,
                    lifecycleId,
                    taskId,
                    now.toEpochMilli());

            String subjectDigest = TaskProvisioning.provisionSubjectDigest(
                    taskId, launch.launchDigest());
            String operationId = FlowRuntime.stableId(
                    "operation", taskId, "PROVISION_TASK", subjectDigest);
            runtime.insertOperationAndTicket(
                    operationId,
                    "TASK",
                    taskId,
                    taskId,
                    OperationKind.PROVISION_TASK,
                    subjectDigest,
                    "task:" + taskId,
                    null,
                    PROVISION_PRIORITY,
                    now);
            return runtime.requireTask(taskId);
        });
    }

    /** Completes objective provisioning and creates the one idle Task session. */
    AgentSession provisionTask(
            Claim claim, TaskProvisioning.ProvisionedWorktree proof)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(proof, "proof is null");
        return runtime.inTransaction(() -> {
            Operation operation = runtime.requireOperation(claim.operationId());
            if (operation.kind() != OperationKind.PROVISION_TASK) {
                throw new IllegalArgumentException(
                        "claim does not own Task provisioning");
            }
            Task task = runtime.requireTask(operation.taskId());
            proof.assertMatches(claim, task, operation);
            String baseSha = proof.baseSha();
            String headSha = proof.headSha();
            if (operation.state() == OperationState.SUCCEEDED) {
                runtime.assertFinalizedClaim(
                        claim, "provisioned:" + task.taskId());
                if (!Objects.equals(task.launchBaseSha(), baseSha)
                        || !Objects.equals(runtime.initialTaskHead(task.taskId()), headSha)) {
                    throw new IllegalStateException(
                            "provisioning redelivery changed the frozen subject");
                }
                TaskBaseRevision base = runtime.baseRevisionForSource(
                        operation.operationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "provisioned Task has no initial base revision"));
                if (!base.baseSha().equals(baseSha)
                        || !base.sourceOperationId().equals(operation.operationId())) {
                    throw new IllegalStateException(
                            "provisioned base revision changed after completion");
                }
                return runtime.requireSession(task.taskSessionId());
            }
            runtime.assertCurrentClaim(claim, OperationState.CLAIMED);
            if (task.status() != TaskStatus.CREATED) {
                throw new IllegalStateException(
                        "Task is not awaiting provisioning");
            }

            Instant now = clock.instant();
            String sessionId = FlowRuntime.stableId(
                    "agent-session", task.taskId(), AgentRole.TASK_AGENT.name());
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_session (
                        session_id, task_id, role, state, created_at, updated_at
                    ) VALUES (?, ?, 'TASK_AGENT', 'IDLE', ?, ?)
                    """,
                    sessionId,
                    task.taskId(),
                    now.toEpochMilli(),
                    now.toEpochMilli());
            TaskLifecycleRevision active = runtime.appendLifecycle(
                    task,
                    TaskStatus.ACTIVE,
                    "PROVISIONED",
                    "base:" + baseSha,
                    operation.operationId(),
                    now);
            String baseRevisionId = FlowRuntime.stableId(
                    "task-base-revision", task.taskId(), "1");
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_task_base_revision (
                        base_revision_id, task_id, sequence, previous_base_sha,
                        base_sha, reason_code, evidence_ref,
                        source_operation_id, recorded_at
                    ) VALUES (?, ?, 1, NULL, ?, 'INITIAL', ?, ?, ?)
                    """,
                    baseRevisionId,
                    task.taskId(),
                    baseSha,
                    "provision-operation:" + operation.operationId(),
                    operation.operationId(),
                    now.toEpochMilli());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET launch_base_sha = ?, current_base_sha = ?,
                        current_base_revision_id = ?, current_head_sha = ?,
                        task_session_id = ?,
                        status = 'ACTIVE', current_lifecycle_revision_id = ?
                    WHERE task_id = ? AND status = 'CREATED'
                        AND current_lifecycle_revision_id = ?
                    """,
                    baseSha,
                    baseSha,
                    baseRevisionId,
                    headSha,
                    sessionId,
                    active.lifecycleRevisionId(),
                    task.taskId(),
                    task.currentLifecycleRevisionId());
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "Task changed during provisioning");
            }
            Task activeTask = runtime.requireTask(task.taskId());
            runtime.appendPendingWork(
                    FlowRuntime.stableId("inbox", "TASK", task.taskId(), "INITIAL"),
                    activeTask,
                    null,
                    "TASK",
                    task.taskId(),
                    "1",
                    PendingKind.INITIAL_TASK,
                    headSha,
                    "task-goal:" + task.taskId(),
                    null,
                    GateIntent.INITIAL_PUBLISH,
                    now);
            runtime.ensureReconciliation(task.taskId());
            runtime.settleDispatch(operation.operationId(), OperationState.SUCCEEDED,
                    "provisioned:" + task.taskId());
            return runtime.requireSession(sessionId);
        });
    }

    /** Appends one immutable Task lifecycle revision using exact-owner CAS. */
    TaskLifecycleRevision transitionTask(
            String taskId,
            String expectedLifecycleRevisionId,
            TaskStatus nextStatus,
            String reasonCode,
            String evidenceRef)
    {
        requireText(taskId, "taskId");
        requireText(expectedLifecycleRevisionId,
                "expectedLifecycleRevisionId");
        requireNonNull(nextStatus, "nextStatus is null");
        requireText(reasonCode, "reasonCode");
        return runtime.inTransaction(() -> {
            int taskLocked = jdbc.update(
                    """
                    UPDATE flow_runtime_task SET task_id = task_id
                    WHERE task_id = ? AND current_lifecycle_revision_id = ?
                    """,
                    taskId,
                    expectedLifecycleRevisionId);
            Task task = runtime.requireTask(taskId);
            if (taskLocked != 1
                    || !task.currentLifecycleRevisionId()
                    .equals(expectedLifecycleRevisionId)) {
                throw new StaleOwnerRevisionException(
                        "Task lifecycle revision is stale");
            }
            if (!allowedTransition(task.status(), nextStatus)) {
                throw new IllegalStateException(
                        "Invalid Task transition: " + task.status()
                                + " -> " + nextStatus);
            }
            if (task.status() == TaskStatus.NEEDS_ATTENTION
                    && nextStatus == TaskStatus.ACTIVE) {
                throw new MutationRejectedException(
                        "Task attention requires a typed recovery cause");
            }
            if (task.waitingMutationStateRef() != null) {
                throw new MutationRejectedException(
                        "Task has unresolved local mutation state");
            }
            if (task.selectedWriterOperationId() != null
                    || runtime.writerFence(taskId).isPresent()) {
                throw new MutationRejectedException(
                        "Task lifecycle cannot change while a writer is live");
            }
            if (runtime.hasNonterminalReviewer(taskId)) {
                throw new MutationRejectedException(
                        "Task lifecycle cannot change while review is live");
            }
            if (runtime.hasNonterminalPublish(taskId)) {
                throw new MutationRejectedException(
                        "Task lifecycle cannot change while publication is live");
            }
            if (isTerminal(nextStatus) && runtime.hasClaimedOperation(taskId)) {
                throw new MutationRejectedException(
                        "Task cannot terminate while an operation is claimed");
            }
            TaskLifecycleRevision revision = runtime.appendLifecycle(
                    task,
                    nextStatus,
                    reasonCode,
                    evidenceRef,
                    null,
                    clock.instant());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET status = ?, current_lifecycle_revision_id = ?
                    WHERE task_id = ? AND current_lifecycle_revision_id = ?
                    """,
                    nextStatus.name(),
                    revision.lifecycleRevisionId(),
                    taskId,
                    expectedLifecycleRevisionId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "Task lifecycle revision is stale");
            }
            if (nextStatus == TaskStatus.ACTIVE) {
                runtime.resumeWaitingReconciliation(taskId);
            }
            else if (isTerminal(nextStatus)) {
                runtime.settleTaskOperationsAtTerminal(taskId, nextStatus);
            }
            return revision;
        });
    }

    /** Materializes the Task's one stable local PR at an exact adopted head. */
    PullRequestSubject materializePullRequest(
            String taskId,
            String expectedChangeSetRevisionId,
            String baseRef,
            String targetBaseRef,
            String scopeKey)
    {
        requireText(taskId, "taskId");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireText(baseRef, "baseRef");
        requireText(targetBaseRef, "targetBaseRef");
        requireText(scopeKey, "scopeKey");
        return runtime.inTransaction(() -> {
            Task task = runtime.requireTask(taskId);
            if (task.prId() != null) {
                PullRequestSubject current = runtime.requirePullRequest(task.prId());
                if (!current.createdFromChangeSetRevisionId().equals(
                        expectedChangeSetRevisionId)
                        || !current.baseRef().equals(baseRef)
                        || !current.targetBaseRef().equals(targetBaseRef)
                        || !current.scopeKey().equals(scopeKey)) {
                    throw new IllegalStateException(
                            "Task already owns a different PR subject");
                }
                return current;
            }
            ChangeSetRevision changeSet = runtime.requireChangeSetRevision(
                    expectedChangeSetRevisionId);
            if (task.status() != TaskStatus.ACTIVE
                    || !expectedChangeSetRevisionId.equals(
                            changeSet.changeSetRevisionId())
                    || !changeSet.changeSetRevisionId().equals(
                            task.currentChangeSetRevisionId())
                    || !changeSet.baseRevisionId().equals(
                            task.currentBaseRevisionId())
                    || !changeSet.headSha().equals(task.currentHeadSha())
                    || !changeSet.baseSha().equals(task.currentBaseSha())) {
                throw new IllegalStateException(
                        "PR subject is not the current active Task head");
            }
            if (!changeSet.differsFromBase()) {
                throw new IllegalStateException(
                        "An empty Task cannot materialize a PR");
            }
            String prId = FlowRuntime.stableId("pr", taskId);
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_pr (
                        pr_id, task_id, repository_id, base_ref, base_sha,
                        target_base_ref, scope_key, branch_name,
                        created_from_change_set_revision_id,
                        created_from_head_sha, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    prId,
                    taskId,
                    task.repositoryId(),
                    baseRef,
                    changeSet.baseSha(),
                    targetBaseRef,
                    scopeKey,
                    task.branchName(),
                    changeSet.changeSetRevisionId(),
                    changeSet.headSha(),
                    clock.instant().toEpochMilli());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET pr_id = ?
                    WHERE task_id = ? AND pr_id IS NULL
                        AND current_change_set_revision_id = ?
                    """,
                    prId,
                    taskId,
                    changeSet.changeSetRevisionId());
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "Task head changed while materializing its PR");
            }
            return runtime.requirePullRequest(prId);
        });
    }

    /** Appends one exact-head local draft; it never performs a remote call. */
    PrDraftRevision savePrDraft(
            Claim claim,
            WriterFence fence,
            String capabilityId,
            String prId,
            String expectedChangeSetRevisionId,
            String expectedHeadSha,
            String createdByRunId,
            String title,
            String body)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireText(capabilityId, "capabilityId");
        requireText(prId, "prId");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireText(expectedHeadSha, "expectedHeadSha");
        requireText(createdByRunId, "createdByRunId");
        requireText(title, "title");
        requireNonNull(body, "body is null");
        if (title.length() > 256 || body.length() > 65_536) {
            throw new IllegalArgumentException("PR draft is too large");
        }
        return runtime.inTransaction(() -> {
            runtime.assertInProcessWriterToolCapability(
                    createdByRunId, claim, fence, capabilityId);
            PullRequestSubject pr = runtime.requirePullRequest(prId);
            Task task = runtime.requireTask(pr.taskId());
            ChangeSetRevision changeSet = runtime.requireChangeSetRevision(
                    expectedChangeSetRevisionId);
            AgentRun run = runtime.requireRun(createdByRunId);
            Optional<PrDraftRevision> replay = jdbc.query(
                    "SELECT * FROM flow_runtime_pr_draft_revision "
                            + "WHERE created_by_run_id = ?",
                    (result, row) -> readPrDraftRevision(result),
                    createdByRunId).stream().findFirst();
            if (replay.isPresent()) {
                PrDraftRevision existing = replay.orElseThrow();
                if (!existing.prId().equals(prId)
                        || !existing.changeSetRevisionId().equals(
                                expectedChangeSetRevisionId)
                        || !existing.headSha().equals(expectedHeadSha)
                        || !existing.title().equals(title)
                        || !existing.body().equals(body)
                        || !Objects.equals(pr.currentDraftRevisionId(),
                                existing.draftRevisionId())) {
                    throw new IllegalStateException(
                            "Task run already owns a different PR draft");
                }
                return existing;
            }
            if (pr.published()) {
                throw new StaleOwnerRevisionException(
                        "published PR metadata is immutable in this flow");
            }
            if (task.status() != TaskStatus.ACTIVE
                    || !Objects.equals(task.currentChangeSetRevisionId(),
                            expectedChangeSetRevisionId)
                    || !Objects.equals(task.currentHeadSha(), expectedHeadSha)
                    || !changeSet.taskId().equals(task.taskId())
                    || !changeSet.headSha().equals(expectedHeadSha)
                    || !changeSet.differsFromBase()
                    || !run.operationId().equals(claim.operationId())
                    || run.role() != AgentRole.TASK_AGENT
                    || run.state() != RunState.RUNNING
                    || !fence.taskId().equals(task.taskId())
                    || !Objects.equals(task.selectedWriterOperationId(),
                            claim.operationId())) {
                throw new StaleOwnerRevisionException(
                        "PR draft is not bound to the current authored head");
            }
            long sequence = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sequence), 0) + 1 "
                            + "FROM flow_runtime_pr_draft_revision WHERE pr_id = ?",
                    Long.class,
                    prId);
            String digest = FlowRuntime.stableId(
                    "pr-draft:v1",
                    prId,
                    Long.toString(sequence),
                    expectedChangeSetRevisionId,
                    expectedHeadSha,
                    title,
                    body);
            String revisionId = FlowRuntime.stableId("pr-draft-revision:v1", digest);
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_pr_draft_revision (
                        draft_revision_id, pr_id, sequence,
                        change_set_revision_id, head_sha, title, body,
                        draft_digest, created_by_run_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    revisionId, prId, sequence, expectedChangeSetRevisionId,
                    expectedHeadSha, title, body, digest, createdByRunId,
                    now.toEpochMilli());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_pr SET current_draft_revision_id = ?
                    WHERE pr_id = ? AND remote_identity_id IS NULL
                      AND (current_draft_revision_id IS NULL
                           OR current_draft_revision_id = ?)
                    """,
                    revisionId,
                    prId,
                    pr.currentDraftRevisionId());
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "PR draft changed concurrently");
            }
            return requirePrDraftRevision(revisionId);
        });
    }

    public Optional<PrDraftRevision> currentPrDraft(String prId)
    {
        requireText(prId, "prId");
        return jdbc.query(
                """
                SELECT d.* FROM flow_runtime_pr p
                JOIN flow_runtime_pr_draft_revision d
                  ON d.draft_revision_id = p.current_draft_revision_id
                WHERE p.pr_id = ?
                """,
                (result, row) -> readPrDraftRevision(result),
                prId).stream().findFirst();
    }

    public PrDraftRevision requirePrDraftRevision(String revisionId)
    {
        requireText(revisionId, "revisionId");
        return jdbc.query(
                "SELECT * FROM flow_runtime_pr_draft_revision "
                        + "WHERE draft_revision_id = ?",
                (result, row) -> readPrDraftRevision(result),
                revisionId).stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown PR draft revision: " + revisionId));
    }


    private static PrDraftRevision readPrDraftRevision(ResultSet result)
            throws SQLException
    {
        return new PrDraftRevision(
                result.getString("draft_revision_id"),
                result.getString("pr_id"),
                result.getLong("sequence"),
                result.getString("change_set_revision_id"),
                result.getString("head_sha"),
                result.getString("title"),
                result.getString("body"),
                result.getString("draft_digest"),
                result.getString("created_by_run_id"),
                Instant.ofEpochMilli(result.getLong("created_at")));
    }

    private static boolean isTerminal(TaskStatus status)
    {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.CANCELED;
    }

    private static boolean allowedTransition(TaskStatus from, TaskStatus to)
    {
        if (to == TaskStatus.CANCELED) {
            return from != TaskStatus.COMPLETED
                    && from != TaskStatus.CANCELED;
        }
        return switch (from) {
            case CREATED -> to == TaskStatus.CANCELED;
            case ACTIVE -> to == TaskStatus.WAITING_USER
                    || to == TaskStatus.NEEDS_ATTENTION
                    || to == TaskStatus.COMPLETED;
            case WAITING_USER -> to == TaskStatus.ACTIVE
                    || to == TaskStatus.NEEDS_ATTENTION
                    || to == TaskStatus.COMPLETED;
            case NEEDS_ATTENTION -> to == TaskStatus.ACTIVE
                    || to == TaskStatus.COMPLETED;
            case COMPLETED, CANCELED -> false;
        };
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
