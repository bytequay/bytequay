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
package com.bytequay.app.developmentflow.execution.cleanup;

import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.CleanupTarget;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Step;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepResult;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.service.local.GitRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Concrete database and Git effects for the fixed Cleanup ledger. */
@Component
public class SqliteCleanupEffects
        implements CleanupOperationHandler.CleanupEffects
{
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GitRunner git;
    private final V2UserWaitStore userWaits;

    public SqliteCleanupEffects(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            GitRunner git)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = new TransactionTemplate(
                requireNonNull(transactionManager, "transactionManager is null"));
        this.git = requireNonNull(git, "git is null");
        this.userWaits = new V2UserWaitStore(jdbc);
    }

    @Override
    public StepResult execute(Operation operation, Step step, ExecutionContext context)
            throws Exception
    {
        requireNonNull(operation, "operation is null");
        requireNonNull(step, "step is null");
        requireNonNull(context, "context is null");
        return switch (step.kind()) {
            case PROVE_NO_NEW_ADMISSIONS -> proveNoAdmissions(operation);
            case RECONCILE_OPEN_WORK -> proveOpenWorkSettled(operation);
            case STOP_PROVIDER_SESSIONS -> proveProvidersStopped(operation);
            case RECONCILE_VALIDATION -> proveValidationSettled(operation);
            case SEAL_REVIEW_STATE -> proveReviewsSealed(operation);
            case DISMISS_TASK_INTERACTIONS -> dismissInteractions(operation, step);
            case RELEASE_RUNTIME_LEASES -> releaseRuntimeLeases(operation);
            case REMOVE_WORKTREE -> removeWorktree(operation.target(), context);
            case DELETE_LOCAL_BRANCH -> deleteLocalBranch(operation.target(), context);
            case DELETE_REMOTE_BRANCH -> deleteRemoteBranch(operation.target(), context);
            case RECORD_FINAL_EVIDENCE -> throw new IllegalStateException(
                    "final evidence is owned by CleanupOperationHandler");
        };
    }

    @Override
    public StepResult probe(Operation operation, Step step, ExecutionContext context)
            throws Exception
    {
        requireNonNull(operation, "operation is null");
        requireNonNull(step, "step is null");
        requireNonNull(context, "context is null");
        return switch (step.kind()) {
            case REMOVE_WORKTREE -> probeWorktree(operation.target());
            case DELETE_LOCAL_BRANCH -> probeLocalBranch(operation.target());
            case DELETE_REMOTE_BRANCH -> probeRemoteBranch(operation.target());
            default -> execute(operation, step, context);
        };
    }

    private StepResult proveNoAdmissions(Operation operation)
    {
        int owners = count("""
                SELECT COUNT(*) FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'CLEANING'
                  AND task.epoch = ? AND owner.id = ?
                  AND owner.kind = 'CLEANUP'
                """, operation.taskId(), operation.taskEpoch(), operation.cleanupStageId());
        return owners == 1
                ? success(null, "V2 Cleanup admission guard owns the Task")
                : failed("Cleanup admission owner changed", "admission owner is stale");
    }

    private StepResult proveOpenWorkSettled(Operation operation)
    {
        int live = count("""
                SELECT
                    (SELECT COUNT(*) FROM task_turn turn
                      WHERE turn.task_id = ? AND turn.task_epoch = ?
                        AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING'))
                  + (SELECT COUNT(*) FROM stage_turn turn
                      JOIN stage owner ON owner.id = turn.stage_id
                     WHERE owner.task_id = ? AND turn.task_epoch = ?
                       AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING'))
                  + (SELECT COUNT(*) FROM dispatch_ticket ticket
                     WHERE ticket.task_id = ? AND ticket.task_epoch = ?
                       AND ticket.async_family <> 'CLEANUP'
                       AND ticket.status IN ('REQUESTED','RETRY_WAIT','RECONCILE_WAIT',
                           'RESULT_PENDING','CLAIMED','RUNNING','DELIVERING'))
                  + (SELECT COUNT(*) FROM ci_repair_episode episode
                     WHERE episode.task_id = ? AND episode.task_epoch = ?
                       AND episode.status NOT IN ('SUCCEEDED','EXHAUSTED','STOPPED'))
                  + (SELECT COUNT(*) FROM branch_sync_episode episode
                     WHERE episode.task_id = ? AND episode.task_epoch = ?
                       AND episode.status NOT IN ('SUCCEEDED','FAILED','STOPPED'))
                  + (SELECT COUNT(*) FROM local_publish_base_sync_episode episode
                     WHERE episode.task_id = ? AND episode.task_epoch = ?
                       AND episode.status NOT IN (
                           'HANDED_OFF','FAILED','CANCELED','SUPERSEDED'))
                  + (SELECT COUNT(*) FROM remote_mark_ready_operation operation
                     WHERE operation.task_id = ? AND operation.task_epoch = ?
                       AND operation.status NOT IN ('SUCCEEDED','CANCELED'))
                  + (SELECT COUNT(*) FROM remote_merge_operation operation
                     WHERE operation.task_id = ? AND operation.task_epoch = ?
                       AND operation.status NOT IN (
                           'SUCCEEDED','FAILED','BLOCKED','CANCELED'))
                """, operation.taskId(), operation.taskEpoch(),
                operation.taskId(), operation.taskEpoch(),
                operation.taskId(), operation.taskEpoch(),
                operation.taskId(), operation.taskEpoch(),
                operation.taskId(), operation.taskEpoch(),
                operation.taskId(), operation.taskEpoch(),
                operation.taskId(), operation.taskEpoch(),
                operation.taskId(), operation.taskEpoch());
        return live == 0
                ? success(null, "all non-Cleanup Turns and Operations are terminal")
                : indeterminate("open work remains: " + live,
                        "open work still requires cancellation or reconciliation");
    }

    private StepResult proveProvidersStopped(Operation operation)
    {
        int live = count("""
                SELECT COUNT(*) FROM agent_execution execution
                JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                WHERE ticket.task_id = ?
                  AND ticket.async_family = 'AGENT_TURN'
                  AND execution.finished_at_ms IS NULL
                  AND execution.status IN ('STARTING','RUNNING','UNKNOWN')
                """, operation.taskId());
        return live == 0
                ? success(null, "no live provider session remains")
                : indeterminate("live provider sessions remain: " + live,
                        "provider sessions still require stop reconciliation");
    }

    private StepResult proveValidationSettled(Operation operation)
    {
        int live = count("""
                SELECT
                    (SELECT COUNT(*) FROM validation_operation validation
                      WHERE validation.task_id = ?
                        AND validation.status IN ('REQUESTED','DISPATCHED'))
                  + (SELECT COUNT(*) FROM validation_pass validation
                      WHERE validation.task_id = ? AND validation.ended_at_ms IS NULL)
                """, operation.taskId(), operation.taskId());
        return live == 0
                ? success(null, "validation is reconciled")
                : indeterminate("live validation remains: " + live,
                        "validation still requires cancellation reconciliation");
    }

    private StepResult proveReviewsSealed(Operation operation)
    {
        int live = count("""
                SELECT
                    (SELECT COUNT(*) FROM local_feedback_batch batch
                      WHERE batch.task_id = ?
                        AND batch.status IN ('BUILDING','FROZEN','QUEUED','DISPATCHED'))
                  + (SELECT COUNT(*) FROM remote_feedback_batch batch
                      WHERE batch.task_id = ?
                        AND batch.status NOT IN ('COMPLETED','SUPERSEDED'))
                  + (SELECT COUNT(*) FROM remote_mark_ready_authorization authorization
                      WHERE authorization.task_id = ? AND authorization.status = 'ACTIVE')
                  + (SELECT COUNT(*) FROM remote_merge_authorization authorization
                      WHERE authorization.task_id = ? AND authorization.status = 'ACTIVE')
                """, operation.taskId(), operation.taskId(),
                operation.taskId(), operation.taskId());
        return live == 0
                ? success(null, "review batches are sealed and authorizations revoked")
                : failed("open review state remains: " + live,
                        "review state must be sealed before Cleanup can continue");
    }

    private StepResult dismissInteractions(Operation operation, Step step)
    {
        return transactions.execute(ignored -> {
            long now = Instant.now().toEpochMilli();
            jdbc.update("""
                    UPDATE notifications
                       SET status = 'DISMISSED', read_at_ms = COALESCE(read_at_ms, ?)
                     WHERE task_id = ? AND status = 'UNREAD'
                    """, now, operation.taskId());
            int canceledWaits = userWaits.cancelOpenWaitsForTask(
                    operation.taskId(), "cleanup", "Cleanup canceled request",
                    Instant.ofEpochMilli(now));
            // A blocker is an interaction awaiting the user too. Cleanup only
            // resolved the ones it opened itself, so a Stage- or Episode-owned
            // blocker outlived the Task and kept offering an action against a
            // Task that can no longer run.
            int sealedBlockers = jdbc.update("""
                    UPDATE task_blocker
                       SET status = 'RESOLVED', resolved_at_ms = ?,
                           resolution_evidence = 'cleanup: Task reached terminal'
                     WHERE task_id = ? AND status = 'OPEN'
                    """, now, operation.taskId());
            int notifications = count("""
                    SELECT COUNT(*) FROM notifications
                     WHERE task_id = ? AND status = 'DISMISSED'
                       AND read_at_ms <= ?
                    """, operation.taskId(), now);
            int permissions = count("""
                    SELECT COUNT(*) FROM permission_request permission
                     WHERE permission.state = 'CANCELED'
                       AND permission.answered_at_ms <= ? AND (
                         (permission.turn_kind = 'TASK' AND EXISTS (
                             SELECT 1 FROM task_turn turn
                              WHERE turn.id = permission.turn_id
                                AND turn.task_id = ?))
                         OR (permission.turn_kind = 'STAGE' AND EXISTS (
                             SELECT 1 FROM stage_turn turn
                             JOIN stage owner ON owner.id = turn.stage_id
                              WHERE turn.id = permission.turn_id
                                AND owner.task_id = ?)))
                    """, now, operation.taskId(), operation.taskId());
            jdbc.update("""
                    INSERT OR IGNORE INTO cleanup_interaction_dismissal_evidence(
                        id, cleanup_step_id, cleanup_operation_id, task_id,
                        task_epoch, dismissed_notification_count,
                        canceled_permission_count, notification_scope_evidence,
                        permission_scope_evidence, recorded_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "INTERACTIONS:" + step.id(), step.id(), operation.id(),
                    operation.taskId(), operation.taskEpoch(), notifications, permissions,
                    "all Task-scoped notifications dismissed",
                    "all Task/Stage permission prompts canceled", now);
            return success(null, "dismissed notifications=" + notifications
                    + ", permissions=" + permissions
                    + ", typed waits=" + canceledWaits
                    + ", blockers=" + sealedBlockers);
        });
    }

    private StepResult releaseRuntimeLeases(Operation operation)
    {
        long now = Instant.now().toEpochMilli();
        transactions.executeWithoutResult(ignored -> {
            jdbc.update("""
                    UPDATE capacity_lease
                       SET released_at_ms = ?, release_reason = 'Cleanup released lease'
                     WHERE workflow_source = 'V2' AND task_id = ? AND task_epoch = ?
                       AND operation_id <> ? AND released_at_ms IS NULL
                    """, now, operation.taskId(), operation.taskEpoch(), operation.operationId());
            jdbc.update("""
                    DELETE FROM worktree_leases
                     WHERE workflow_version = 'V2' AND task_id = ? AND task_epoch = ?
                    """, operation.taskId(), operation.taskEpoch());
        });
        return success(null, "non-Cleanup capacity and worktree leases released");
    }

    private StepResult removeWorktree(CleanupTarget target, ExecutionContext context)
            throws Exception
    {
        context.requireWriterCapacityLease();
        StepResult before = probeWorktree(target);
        if (before.outcome() == CleanupOperationHandler.EffectOutcome.SUCCEEDED) {
            return before;
        }
        try {
            git.worktreeRemove(Path.of(target.repositoryRoot()), Path.of(target.worktreePath()));
        }
        catch (IOException | RuntimeException failure) {
            StepResult after = probeWorktree(target);
            if (after.outcome() == CleanupOperationHandler.EffectOutcome.SUCCEEDED) {
                return after;
            }
            return failed("worktree still exists after removal error",
                    message(failure));
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return indeterminate("worktree removal was interrupted",
                    message(failure));
        }
        return probeWorktree(target);
    }

    private StepResult probeWorktree(CleanupTarget target)
    {
        Path path = Path.of(target.worktreePath());
        return Files.notExists(path)
                ? success("worktree:" + path, "worktree is absent")
                : failed("worktree still exists: " + path, "worktree removal incomplete");
    }

    private StepResult deleteLocalBranch(CleanupTarget target, ExecutionContext context)
            throws Exception
    {
        context.requireWriterCapacityLease();
        StepResult before = probeLocalBranch(target);
        if (before.outcome() == CleanupOperationHandler.EffectOutcome.SUCCEEDED) {
            return before;
        }
        try {
            git.deleteBranches(
                    Path.of(target.repositoryRoot()), List.of(target.branchName()));
        }
        catch (IOException | RuntimeException failure) {
            StepResult after = probeLocalBranch(target);
            return after.outcome() == CleanupOperationHandler.EffectOutcome.SUCCEEDED
                    ? after
                    : failed("local branch still exists", message(failure));
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return indeterminate("local branch deletion was interrupted", message(failure));
        }
        return probeLocalBranch(target);
    }

    private StepResult probeLocalBranch(CleanupTarget target)
            throws Exception
    {
        try {
            boolean exists = git.refExists(
                    Path.of(target.repositoryRoot()),
                    "refs/heads/" + target.branchName());
            return exists
                    ? failed("local branch still exists: " + target.branchName(),
                            "local branch deletion incomplete")
                    : success("branch:" + target.branchName(), "local branch is absent");
        }
        catch (IOException failure) {
            return indeterminate("local branch probe failed", message(failure));
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return indeterminate("local branch probe was interrupted", message(failure));
        }
    }

    private StepResult deleteRemoteBranch(CleanupTarget target, ExecutionContext context)
            throws Exception
    {
        context.requireWriterCapacityLease();
        String remote = exactRemote(target);
        StepResult before = probeRemoteBranch(target, remote);
        if (before.outcome() == CleanupOperationHandler.EffectOutcome.SUCCEEDED) {
            return before;
        }
        try {
            git.deleteRemoteBranch(
                    Path.of(target.repositoryRoot()), remote, target.branchName());
        }
        catch (IOException | RuntimeException failure) {
            StepResult after = probeRemoteBranch(target, remote);
            return after.outcome() == CleanupOperationHandler.EffectOutcome.SUCCEEDED
                    ? after
                    : failed("remote branch still exists", message(failure));
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return indeterminate("remote branch deletion was interrupted", message(failure));
        }
        return probeRemoteBranch(target, remote);
    }

    private StepResult probeRemoteBranch(CleanupTarget target)
            throws Exception
    {
        return probeRemoteBranch(target, exactRemote(target));
    }

    private StepResult probeRemoteBranch(CleanupTarget target, String remote)
    {
        try {
            Optional<String> head = git.remoteHeadSha(
                    Path.of(target.repositoryRoot()), remote, target.branchName());
            return head.isEmpty()
                    ? success("remote-branch:" + target.publishRepositoryId() + ":"
                                    + target.branchName(),
                            "remote branch is absent")
                    : failed("remote branch still exists at " + head.orElseThrow(),
                            "remote branch deletion incomplete");
        }
        catch (IOException failure) {
            return indeterminate("remote branch probe failed", message(failure));
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return indeterminate("remote branch probe was interrupted", message(failure));
        }
    }

    private String exactRemote(CleanupTarget target)
            throws Exception
    {
        if (target.remoteName() != null) {
            return target.remoteName();
        }
        List<String> matches = new ArrayList<>();
        Path root = Path.of(target.repositoryRoot());
        for (GitRunner.Remote remote : git.listRemotes(root)) {
            if (git.remoteSlug(root, remote.name())
                    .filter(slug -> slug.fullName().equalsIgnoreCase(
                            target.publishRepositoryId()))
                    .isPresent()) {
                matches.add(remote.name());
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "expected one exact configured remote for "
                            + target.publishRepositoryId() + ", found " + matches.size());
        }
        return matches.getFirst();
    }

    private int count(String sql, Object... arguments)
    {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private static StepResult success(String externalId, String evidence)
    {
        return StepResult.succeeded(externalId, evidence, digest(evidence));
    }

    private static StepResult failed(String evidence, String error)
    {
        return StepResult.failed(evidence, digest(evidence), error);
    }

    private static StepResult indeterminate(String evidence, String error)
    {
        return StepResult.indeterminate(evidence, digest(evidence), error);
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String message(Throwable failure)
    {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
