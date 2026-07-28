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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.AuthorityKind;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Controller adapter for the two user-authorized V2 remote effects. */
@Component
public final class V2PrRemoteControlService
{
    private static final int EFFECT_ATTEMPT_LIMIT = 3;
    private static final String USER = "user";

    private final JdbcTemplate jdbc;
    private final TaskCommandExecutor commands;
    private final LocalDevelopmentStageManager local;
    private final RemoteMergeRuntimeCoordinator merges;
    private final GitRunner git;
    private final CodeFingerprints fingerprints;
    private final PatResolver pats;
    private final Clock clock;

    @Autowired
    public V2PrRemoteControlService(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            RemoteMergeRuntimeCoordinator merges,
            GitRunner git,
            CodeFingerprints fingerprints,
            PatResolver pats)
    {
        this(jdbc, commands, local, merges, git, fingerprints, pats,
                Clock.systemUTC());
    }

    V2PrRemoteControlService(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            RemoteMergeRuntimeCoordinator merges,
            GitRunner git,
            CodeFingerprints fingerprints,
            PatResolver pats,
            Clock clock)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.local = requireNonNull(local, "local is null");
        this.merges = requireNonNull(merges, "merges is null");
        this.git = requireNonNull(git, "git is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Freezes one exact local subject and leaves its Git/GitHub work to the dispatcher. */
    public void approveAndShip(String taskId, String prId, boolean humanOverride)
    {
        requireText(taskId, "taskId");
        requireText(prId, "prId");
        if (hasLivePublish(taskId, prId)) {
            return;
        }
        PublishCandidate observed = requirePublishCandidate(taskId, prId);
        LocalProof proof = proveLocalSubject(observed);
        String operationId = UUID.randomUUID().toString();
        try {
            commands.executeVoid(taskId, () -> startPublishInCommand(
                    observed, proof, operationId, humanOverride));
        }
        catch (DataAccessException failure) {
            throw conflict("Approve & ship no longer matches the exact Local Review subject");
        }
    }

    /** Consumes only the latest accepted exact-head readiness evidence. */
    public void merge(String taskId, String method)
    {
        requireText(taskId, "taskId");
        if (hasLiveMerge(taskId)) {
            return;
        }
        MergeCandidate candidate = requireMergeCandidate(taskId);
        String normalized = method == null ? "squash" : method.toLowerCase(Locale.ROOT);
        if (!List.of("merge", "squash", "rebase").contains(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "unknown merge method " + method);
        }
        if ("UNSUPPORTED".equals(candidate.queueCapability())
                && !"squash".equals(normalized)) {
            throw conflict("V2 direct merge currently supports the squash method only");
        }
        String operationId = UUID.randomUUID().toString();
        try {
            merges.start(new RemoteMergeRuntimeCoordinator.Command(
                    id("manual-merge-command", operationId), USER,
                    taskId, candidate.stageId(), candidate.readinessEvidenceId(),
                    id("manual-merge-authorization", operationId), operationId,
                    id("manual-merge-ticket", operationId), AuthorityKind.MANUAL,
                    EFFECT_ATTEMPT_LIMIT));
        }
        catch (IllegalStateException failure) {
            throw conflict("manual merge requires fresh accepted exact-head readiness");
        }
    }

    private void startPublishInCommand(
            PublishCandidate expected,
            LocalProof proof,
            String operationId,
            boolean humanOverride)
    {
        if (hasLivePublish(expected.taskId(), expected.prId())) {
            return;
        }
        PublishCandidate current = requirePublishCandidate(
                expected.taskId(), expected.prId());
        if (!expected.sameSubject(current) || !proof.matches(current)) {
            throw conflict("Local Review changed while Approve & ship was starting");
        }
        requireNoBlockingAgentReview(current);
        requireConsent(current, humanOverride);

        Instant now = clock.instant();
        int attempt = nextPublishAttempt(current.stageId());
        String manifestId = id("promotion-manifest", operationId);
        String authorizationId = id("publish-authorization", operationId);
        String publishOperationId = id("publish-operation", operationId);
        String ticketId = id("publish-ticket", operationId);
        String contentDigest = digest(
                "title:" + current.prTitle() + "\nbody:" + current.prBody());
        int manifestRevision = nextManifestRevision(current.stageId());

        insertManifest(
                current, proof, manifestId, contentDigest, manifestRevision, now);
        List<OverrideItem> overrides = overrideItems(current, humanOverride);
        String overrideId = overrides.isEmpty()
                ? null : id("publish-override", operationId);
        if (overrideId != null) {
            insertOverride(current, overrideId, overrides, now);
        }
        String brainBasis = "BUDGET_EXHAUSTED".equals(current.brainStatus())
                ? "HUMAN_ESCALATION" : "APPROVED";
        String escalationReason = "HUMAN_ESCALATION".equals(brainBasis)
                ? "explicit user approval after Brain review budget exhaustion"
                : null;
        String consentKind = humanOverride ? "HUMAN" : "STANDING_TASK";
        String consentId = humanOverride
                ? id("publish-human-consent", operationId)
                : current.policyRevisionId();
        String actor = humanOverride ? USER : "task-policy";
        insertAuthorization(
                current, manifestId, authorizationId, overrideId, contentDigest,
                manifestRevision, consentKind, consentId, actor, brainBasis,
                escalationReason, operationId, attempt, now);
        insertOperation(
                current, publishOperationId, authorizationId, operationId,
                attempt, now);
        insertSteps(publishOperationId, operationId);
        insertTicket(current, ticketId, operationId, attempt, now);

        ResultFence fence = new ResultFence(
                current.taskEpoch(), current.stageId(), current.stageGeneration(),
                operationId, attempt, current.codeFingerprint(),
                current.headSha(), current.baseSha());
        CommandResult<StageManager.State> authorized = local.authorizePublishInCommand(
                new LocalDevelopmentStageManager.PublishCommand(
                        new StageManager.Command(
                                id("approve-and-ship", operationId), actor,
                                current.taskId(), current.taskEpoch(), current.stageId(),
                                current.stageGeneration(), current.stageVersion()),
                        authorizationId, current.policyRevisionId(), consentId, fence));
        if (authorized.disposition() != CommandResult.Disposition.APPLIED) {
            throw conflict("Approve & ship was superseded by another Local command");
        }
        if (jdbc.update("""
                UPDATE publish_operation SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, publishOperationId) != 1) {
            throw new IllegalStateException("PublishOperation was not dispatched");
        }
    }

    private LocalProof proveLocalSubject(PublishCandidate candidate)
    {
        Path worktree = Path.of(candidate.worktreePath());
        try {
            if (!Files.isDirectory(worktree) || !git.isGitWorkingTree(worktree)) {
                throw conflict("Approve & ship requires the Task worktree");
            }
            String branch = git.currentBranch(worktree);
            String head = git.headSha(worktree);
            String fingerprint = fingerprints.fingerprint(worktree);
            String status = git.statusPorcelainZ(worktree);
            Integer commitsAhead = git.commitCountUniqueTo(
                    worktree, "HEAD", candidate.baseSha());
            if (!candidate.branchName().equals(branch)
                    || !candidate.headSha().equals(head)
                    || !candidate.codeFingerprint().equals(fingerprint)) {
                throw conflict("Approve & ship subject differs from the reviewed code");
            }
            if (!status.isEmpty()) {
                throw conflict("Approve & ship requires a clean committed worktree");
            }
            if (commitsAhead == null || commitsAhead < 1) {
                throw conflict("Approve & ship requires at least one commit above the exact base");
            }
            pats.resolve(candidate.baseRepositoryId());
            return new LocalProof(head, fingerprint, commitsAhead);
        }
        catch (ResponseStatusException failure) {
            throw failure;
        }
        catch (IOException failure) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "inspecting the exact publish subject failed: " + failure.getMessage());
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "inspecting the exact publish subject was interrupted");
        }
    }

    private void requireConsent(PublishCandidate candidate, boolean humanOverride)
    {
        if (!humanOverride && !candidate.autoApprove()) {
            throw conflict("automatic Approve & ship requires current Task auto-approve consent");
        }
        boolean approved = "SUCCEEDED".equals(candidate.brainStatus())
                && "APPROVED".equals(candidate.brainVerdict())
                && candidate.unresolvedBrainFindings() == 0;
        if (!approved && !(humanOverride
                && "BUDGET_EXHAUSTED".equals(candidate.brainStatus()))) {
            throw conflict("Approve & ship requires an approved Brain review or an explicit budget-exhaustion override");
        }
        if (!humanOverride && !candidate.validationPassed()) {
            throw conflict("automatic Approve & ship requires green exact validation");
        }
    }

    private List<OverrideItem> overrideItems(
            PublishCandidate candidate, boolean humanOverride)
    {
        List<OverrideItem> items = new ArrayList<>();
        if (!candidate.validationPassed()) {
            if (!humanOverride) {
                throw conflict("failed validation requires an explicit human publish override");
            }
            List<OverrideItem> validation = jdbc.query("""
                    SELECT blocker.id AS blocker_id,
                           evidence.id AS subject_id
                    FROM validation_evidence evidence
                    JOIN task_blocker blocker
                      ON blocker.task_id = evidence.task_id
                     AND blocker.stage_id = evidence.stage_id
                     AND blocker.owner_kind = 'STAGE'
                     AND blocker.owner_id = evidence.stage_id
                     AND blocker.blocker_type = 'LOCAL_VALIDATION_FAILED'
                     AND blocker.subject_revision = evidence.id
                     AND blocker.status = 'OPEN'
                    WHERE evidence.id = ? AND evidence.passed = 0
                    """, (rs, row) -> new OverrideItem(
                            "VALIDATION_FAILURE", rs.getString("blocker_id"),
                            rs.getString("subject_id")),
                    candidate.validationEvidenceId());
            if (validation.size() != 1) {
                throw conflict("failed validation has no exact open blocker to override");
            }
            items.add(validation.getFirst());
        }

        List<OverrideItem> feedback = jdbc.query("""
                SELECT blocker.id AS blocker_id, revision.id AS subject_id
                FROM local_review_comment_revision revision
                LEFT JOIN task_blocker blocker
                  ON blocker.task_id = revision.task_id
                 AND blocker.stage_id = revision.local_development_stage_id
                 AND blocker.owner_kind = 'STAGE'
                 AND blocker.owner_id = revision.local_development_stage_id
                 AND blocker.blocker_type = 'LOCAL_FEEDBACK_OPEN'
                 AND blocker.subject_revision = revision.id
                 AND blocker.status = 'OPEN'
                WHERE revision.task_id = ?
                  AND revision.local_development_stage_id = ?
                  AND revision.stage_generation = ?
                  AND revision.dev_report_id = ?
                  AND revision.code_fingerprint = ?
                  AND revision.head_sha = ? AND revision.base_sha = ?
                  AND revision.state IN ('DRAFT', 'PENDING', 'SUBMITTED')
                  AND (revision.author_kind = 'USER'
                       OR revision.state = 'SUBMITTED')
                ORDER BY revision.created_at_ms, revision.id
                """, (rs, row) -> new OverrideItem(
                        "LOCAL_FEEDBACK", rs.getString("blocker_id"),
                        rs.getString("subject_id")),
                candidate.taskId(), candidate.stageId(),
                candidate.stageGeneration(), candidate.devReportId(),
                candidate.codeFingerprint(), candidate.headSha(),
                candidate.baseSha());
        if (!feedback.isEmpty() && !humanOverride) {
            throw conflict("automatic Approve & ship cannot bypass open local feedback");
        }
        if (feedback.stream().anyMatch(item -> item.blockerId() == null)) {
            throw conflict("open local feedback has no exact blocker to override");
        }
        items.addAll(feedback);
        return List.copyOf(items);
    }

    private void requireNoBlockingAgentReview(PublishCandidate candidate)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM local_review_agent_request request
                JOIN task_blocker blocker ON blocker.id = request.task_blocker_id
                WHERE request.task_id = ?
                  AND request.task_epoch = ?
                  AND request.local_development_stage_id = ?
                  AND request.stage_generation = ?
                  AND request.dev_report_id = ?
                  AND request.code_fingerprint = ?
                  AND request.head_sha = ? AND request.base_sha = ?
                  AND request.mode = 'BLOCKING'
                  AND request.status = 'REQUESTED'
                  AND blocker.blocker_type = 'LOCAL_AGENT_REVIEW_BLOCKING'
                  AND blocker.status = 'OPEN'
                """, Integer.class, candidate.taskId(), candidate.taskEpoch(),
                candidate.stageId(), candidate.stageGeneration(),
                candidate.devReportId(), candidate.codeFingerprint(),
                candidate.headSha(), candidate.baseSha());
        if (count != null && count > 0) {
            throw conflict("Approve & ship is waiting for the blocking AgentReview");
        }
    }

    private PublishCandidate requirePublishCandidate(String taskId, String prId)
    {
        List<PublishCandidate> rows = jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                       trunk.workspace_id, task.epoch AS task_epoch,
                       task.policy_revision_id,
                       stage.id AS stage_id, stage.generation AS stage_generation,
                       stage.version AS stage_version,
                       report.id AS dev_report_id, report.code_fingerprint,
                       report.head_sha, report.base_sha,
                       validation.id AS validation_evidence_id,
                       validation.passed AS validation_passed,
                       brain.id AS brain_episode_id, brain.status AS brain_status,
                       brain.verdict AS brain_verdict,
                       brain.unresolved_finding_count,
                       pr.id AS pr_id, pr.title AS pr_title,
                       pr.description AS pr_body, pr.branch_name,
                       pr.base_branch, identity.worktree_path,
                       context.repository_id, context.upstream_repository_id,
                       context.publish_repository_id, policy.auto_approve
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage stage ON stage.id = current.stage_id
                JOIN local_development_stage local ON local.stage_id = stage.id
                JOIN dev_report report
                  ON report.local_development_stage_id = stage.id
                 AND report.revision = (
                     SELECT MAX(latest.revision) FROM dev_report latest
                     WHERE latest.workflow_version = 'V2'
                       AND latest.local_development_stage_id = stage.id)
                JOIN validation_operation validation_operation
                  ON validation_operation.dev_report_id = report.id
                 AND validation_operation.semantic_attempt = (
                     SELECT MAX(latest.semantic_attempt)
                     FROM validation_operation latest
                     WHERE latest.dev_report_id = report.id)
                 AND validation_operation.status = 'COMPLETED'
                JOIN validation_evidence validation
                  ON validation.validation_operation_id = validation_operation.id
                JOIN brain_review_episode brain
                  ON brain.dev_report_id = report.id
                 AND brain.semantic_attempt = (
                     SELECT MAX(latest.semantic_attempt)
                     FROM brain_review_episode latest
                     WHERE latest.dev_report_id = report.id)
                JOIN pr pr ON pr.id = ? AND pr.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND stage.kind = 'LOCAL_DEVELOPMENT'
                  AND stage.checkpoint = 'LOCAL_REVIEW'
                  AND stage.completed_at_ms IS NULL
                  AND current.stage_generation = stage.generation
                  AND local.generation = stage.generation
                  AND local.opened_for_epoch = task.epoch
                  AND report.task_epoch = task.epoch
                  AND report.stage_generation = stage.generation
                  AND pr.origin = 'task' AND pr.status = 'local-open'
                """, (rs, row) -> publishCandidate(rs), prId, taskId);
        if (rows.size() != 1) {
            throw conflict("V2 Approve & ship requires one exact Local Review subject");
        }
        return rows.getFirst();
    }

    private MergeCandidate requireMergeCandidate(String taskId)
    {
        List<MergeCandidate> rows = jdbc.query("""
                SELECT stage.id AS stage_id, readiness.id AS readiness_id,
                       readiness.merge_queue_capability
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage stage ON stage.id = current.stage_id
                JOIN remote_development_stage remote ON remote.stage_id = stage.id
                JOIN remote_readiness_evidence readiness
                  ON readiness.remote_development_stage_id = remote.stage_id
                 AND readiness.remote_pr_snapshot_id = remote.accepted_snapshot_id
                 AND readiness.head_sha = remote.current_head_sha
                 AND readiness.base_sha = remote.current_base_sha
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND stage.kind = 'REMOTE_DEVELOPMENT'
                  AND stage.checkpoint = 'READY_TO_MERGE'
                  AND stage.completed_at_ms IS NULL
                  AND current.stage_generation = stage.generation
                  AND remote.generation = stage.generation
                  AND readiness.task_epoch = task.epoch
                  AND readiness.ready = 1
                  AND readiness.merge_queue_capability <> 'UNKNOWN'
                """, (rs, row) -> new MergeCandidate(
                        rs.getString("stage_id"), rs.getString("readiness_id"),
                        rs.getString("merge_queue_capability")), taskId);
        if (rows.size() != 1) {
            throw conflict("manual merge requires fresh accepted exact-head readiness");
        }
        return rows.getFirst();
    }

    private boolean hasLivePublish(String taskId, String prId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM publish_operation operation
                JOIN publish_authorization authorization
                  ON authorization.id = operation.publish_authorization_id
                WHERE operation.task_id = ? AND authorization.pr_id = ?
                  AND operation.status IN ('REQUESTED', 'DISPATCHED')
                """, Integer.class, taskId, prId);
        return count != null && count > 0;
    }

    private boolean hasLiveMerge(String taskId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM remote_merge_operation
                WHERE task_id = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED')
                """, Integer.class, taskId);
        return count != null && count > 0;
    }

    private int nextManifestRevision(String stageId)
    {
        Integer revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM promotion_manifest WHERE local_development_stage_id = ?
                """, Integer.class, stageId);
        return requireNonNull(revision, "manifest revision is null");
    }

    private int nextPublishAttempt(String stageId)
    {
        Integer attempt = jdbc.queryForObject("""
                SELECT COALESCE(MAX(semantic_attempt), 0) + 1
                FROM publish_operation WHERE local_development_stage_id = ?
                """, Integer.class, stageId);
        return requireNonNull(attempt, "publish attempt is null");
    }

    private void insertManifest(
            PublishCandidate candidate,
            LocalProof proof,
            String manifestId,
            String contentDigest,
            int revision,
            Instant now)
    {
        jdbc.update("""
                INSERT INTO promotion_manifest(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, pr_id, policy_revision_id,
                    revision, code_fingerprint, head_sha, base_sha, route,
                    base_repository_id, head_repository_id,
                    publish_repository_id, branch_name, head_ref, base_branch,
                    worktree_clean, commits_ahead, branch_verified, base_verified,
                    permission_clear, pr_title, pr_body, pr_content_revision,
                    pr_content_digest, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    1, ?, 1, 1, 1, ?, ?, ?, ?, ?)
                """, manifestId, candidate.stageId(), candidate.taskId(),
                candidate.taskEpoch(), candidate.stageGeneration(),
                candidate.devReportId(), candidate.prId(),
                candidate.policyRevisionId(), revision, proof.fingerprint(),
                proof.headSha(), candidate.baseSha(), candidate.route(),
                candidate.baseRepositoryId(), candidate.headRepositoryId(),
                candidate.publishRepositoryId(), candidate.branchName(),
                candidate.headRef(), candidate.baseBranch(), proof.commitsAhead(),
                candidate.prTitle(), candidate.prBody(), revision, contentDigest,
                now.toEpochMilli());
    }

    private void insertOverride(
            PublishCandidate candidate,
            String overrideId,
            List<OverrideItem> items,
            Instant now)
    {
        jdbc.update("""
                INSERT INTO publish_override(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, code_fingerprint,
                    head_sha, base_sha, actor_kind, actor_id, reason, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'HUMAN', ?, ?, ?)
                """, overrideId, candidate.stageId(), candidate.taskId(),
                candidate.taskEpoch(), candidate.stageGeneration(),
                candidate.devReportId(), candidate.codeFingerprint(),
                candidate.headSha(), candidate.baseSha(), USER,
                "explicit user Approve & ship override", now.toEpochMilli());
        for (int index = 0; index < items.size(); index++) {
            OverrideItem item = items.get(index);
            jdbc.update("""
                    INSERT INTO publish_override_item(
                        override_id, position, kind, task_blocker_id,
                        validation_evidence_id, comment_revision_id,
                        acknowledged_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, overrideId, index + 1, item.kind(), item.blockerId(),
                    "VALIDATION_FAILURE".equals(item.kind())
                            ? item.subjectId() : null,
                    "LOCAL_FEEDBACK".equals(item.kind())
                            ? item.subjectId() : null,
                    now.toEpochMilli());
        }
    }

    private void insertAuthorization(
            PublishCandidate candidate,
            String manifestId,
            String authorizationId,
            String overrideId,
            String contentDigest,
            int contentRevision,
            String consentKind,
            String consentId,
            String actor,
            String brainBasis,
            String escalationReason,
            String operationId,
            int attempt,
            Instant now)
    {
        jdbc.update("""
                INSERT INTO publish_authorization(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, manifest_id, dev_report_id,
                    validation_evidence_id, brain_review_episode_id, pr_id,
                    policy_revision_id, publish_override_id, code_fingerprint,
                    head_sha, base_sha, route, base_repository_id,
                    head_repository_id, publish_repository_id, branch_name,
                    head_ref, base_branch, pr_content_revision, pr_content_digest,
                    consent_kind, consent_id, actor_id, brain_basis,
                    brain_escalation_reason, authorized_operation_id,
                    authorized_attempt, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, authorizationId, candidate.stageId(), candidate.taskId(),
                candidate.taskEpoch(), candidate.stageGeneration(), manifestId,
                candidate.devReportId(), candidate.validationEvidenceId(),
                candidate.brainEpisodeId(), candidate.prId(),
                candidate.policyRevisionId(), overrideId,
                candidate.codeFingerprint(), candidate.headSha(), candidate.baseSha(),
                candidate.route(), candidate.baseRepositoryId(),
                candidate.headRepositoryId(), candidate.publishRepositoryId(),
                candidate.branchName(), candidate.headRef(), candidate.baseBranch(),
                contentRevision, contentDigest, consentKind, consentId, actor,
                brainBasis, escalationReason, operationId, attempt,
                now.toEpochMilli());
    }

    private void insertOperation(
            PublishCandidate candidate,
            String publishOperationId,
            String authorizationId,
            String operationId,
            int attempt,
            Instant now)
    {
        jdbc.update("""
                INSERT INTO publish_operation(
                    id, publish_authorization_id, local_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, publishOperationId, authorizationId, candidate.stageId(),
                candidate.taskId(), candidate.taskEpoch(),
                candidate.stageGeneration(), operationId, attempt,
                candidate.codeFingerprint(), candidate.headSha(),
                candidate.baseSha(), now.toEpochMilli());
    }

    private void insertSteps(String publishOperationId, String operationId)
    {
        EffectKind[] kinds = EffectKind.values();
        for (int index = 0; index < kinds.length; index++) {
            EffectKind kind = kinds[index];
            jdbc.update("""
                    INSERT INTO publish_effect_step(
                        id, publish_operation_id, ordinal, kind, idempotency_key,
                        status, attempt_limit)
                    VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?)
                    """, id("publish-step-" + kind.name(), operationId),
                    publishOperationId, index + 1, kind.name(),
                    operationId + ":" + kind.name(), EFFECT_ATTEMPT_LIMIT);
        }
    }

    private void insertTicket(
            PublishCandidate candidate,
            String ticketId,
            String operationId,
            int attempt,
            Instant now)
    {
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'PUBLISH_LOCAL_DEVELOPMENT', 'GITHUB_EFFECT',
                    'STAGE', ?, 'STAGE_PUBLISH_RESULT', 48, 0, 1, 1,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, candidate.stageId(),
                candidate.workspaceId(), candidate.trunkId(), candidate.taskId(),
                candidate.taskEpoch(), candidate.stageId(),
                candidate.stageGeneration(), attempt, candidate.codeFingerprint(),
                candidate.headSha(), candidate.baseSha(), now.toEpochMilli());
    }

    private static PublishCandidate publishCandidate(ResultSet rs)
            throws SQLException
    {
        String repository = rs.getString("repository_id");
        String upstream = rs.getString("upstream_repository_id");
        String publish = rs.getString("publish_repository_id");
        String branch = rs.getString("branch_name");
        String route = upstream == null ? "DIRECT" : "FORK";
        String baseRepository = upstream == null ? repository : upstream;
        String headRef = upstream == null
                ? branch : repositoryOwner(publish) + ":" + branch;
        return new PublishCandidate(
                rs.getString("task_id"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getLong("task_epoch"),
                rs.getString("policy_revision_id"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getLong("stage_version"),
                rs.getString("dev_report_id"), rs.getString("code_fingerprint"),
                rs.getString("head_sha"), rs.getString("base_sha"),
                rs.getString("validation_evidence_id"),
                rs.getInt("validation_passed") == 1,
                rs.getString("brain_episode_id"), rs.getString("brain_status"),
                rs.getString("brain_verdict"),
                rs.getInt("unresolved_finding_count"), rs.getString("pr_id"),
                rs.getString("pr_title"), rs.getString("pr_body"), branch,
                rs.getString("base_branch"), rs.getString("worktree_path"),
                route, baseRepository, publish, publish, headRef,
                rs.getInt("auto_approve") == 1);
    }

    private static String repositoryOwner(String repository)
    {
        requireText(repository, "publish repository");
        int separator = repository.indexOf('/');
        if (separator < 1) {
            throw new IllegalArgumentException(
                    "publish repository must be owner/name");
        }
        return repository.substring(0, separator);
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record LocalProof(String headSha, String fingerprint, int commitsAhead)
    {
        boolean matches(PublishCandidate candidate)
        {
            return headSha.equals(candidate.headSha())
                    && fingerprint.equals(candidate.codeFingerprint())
                    && commitsAhead > 0;
        }
    }

    private record OverrideItem(String kind, String blockerId, String subjectId) {}

    private record MergeCandidate(
            String stageId, String readinessEvidenceId, String queueCapability) {}

    private record PublishCandidate(
            String taskId,
            String trunkId,
            String workspaceId,
            long taskEpoch,
            String policyRevisionId,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String devReportId,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String validationEvidenceId,
            boolean validationPassed,
            String brainEpisodeId,
            String brainStatus,
            String brainVerdict,
            int unresolvedBrainFindings,
            String prId,
            String prTitle,
            String prBody,
            String branchName,
            String baseBranch,
            String worktreePath,
            String route,
            String baseRepositoryId,
            String headRepositoryId,
            String publishRepositoryId,
            String headRef,
            boolean autoApprove)
    {
        boolean sameSubject(PublishCandidate other)
        {
            return Objects.equals(this, other);
        }
    }
}
