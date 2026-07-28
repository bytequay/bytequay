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
package com.bytequay.app.developmentflow.stage.persistence;

import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager.FeedbackCompletionEvidence;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager.FeedbackEvidence;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager.RemoteGateEvidence;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager.RemoteSubjectEvidence;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** SQLite boundary for exact-head Remote feedback and readiness work. */
@Repository
public class SqliteRemoteDevelopmentRuntimeStore
        extends SqliteRemoteDevelopmentEvidenceStore
        implements RemoteDevelopmentStageManager.EvidenceStore
{
    private final JdbcTemplate jdbc;

    public SqliteRemoteDevelopmentRuntimeStore(JdbcTemplate jdbc)
    {
        super(jdbc);
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public IngestResult ingest(InboxItem item)
    {
        requireTransaction();
        Optional<InboxItem> existing = jdbc.query("""
                SELECT id, remote_development_stage_id, task_id, task_epoch,
                       stage_generation, remote_pr_binding_id,
                       remote_pr_snapshot_id, kind, external_key,
                       external_revision, head_sha, base_sha, actor_login,
                       provenance, ignored, thread_id, comment_id, review_id,
                       requested_reviewer, body, body_digest, verdict,
                       previous_head_sha, new_head_sha, observed_at_ms,
                       raw_evidence
                FROM remote_inbox_item
                WHERE remote_pr_binding_id = ? AND external_key = ?
                  AND external_revision = ?
                """, (rs, row) -> inboxItem(rs), item.remotePrBindingId(),
                item.externalKey(), item.externalRevision()).stream().findFirst();
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(item)) {
                throw new DataIntegrityViolationException(
                        "Remote inbox identity was reused with different evidence");
            }
            return new IngestResult(existing.orElseThrow().id(), false);
        }
        jdbc.update("""
                INSERT INTO remote_inbox_item(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    remote_pr_snapshot_id, kind, external_key,
                    external_revision, head_sha, base_sha, actor_login,
                    provenance, ignored, thread_id, comment_id, review_id,
                    requested_reviewer, body, body_digest, verdict,
                    previous_head_sha, new_head_sha, observed_at_ms,
                    raw_evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, item.id(), item.stageId(), item.taskId(), item.taskEpoch(),
                item.stageGeneration(), item.remotePrBindingId(),
                item.remotePrSnapshotId(), item.kind().name(), item.externalKey(),
                item.externalRevision(), item.headSha(), item.baseSha(),
                item.actorLogin(), item.provenance().name(), item.ignored() ? 1 : 0,
                item.threadId(), item.commentId(), item.reviewId(),
                item.requestedReviewer(), item.body(), item.bodyDigest(),
                name(item.verdict()), item.previousHeadSha(), item.newHeadSha(),
                item.observedAt().toEpochMilli(), item.rawEvidence());
        return new IngestResult(item.id(), true);
    }

    /** Freezes only the unbatched latest revisions visible in this transaction. */
    public Optional<FrozenBatch> freezeNextBatch(
            String batchId,
            String taskId,
            String stageId,
            boolean brainReviewRequired,
            String selectedBy,
            Instant now)
    {
        requireTransaction();
        requireText(batchId, "batchId");
        requireText(selectedBy, "selectedBy");
        RemoteContext context = requireContext(taskId, stageId);
        List<FrozenItem> items = jdbc.query("""
                SELECT item.id, item.external_revision, item.kind, item.body,
                       item.body_digest,
                       COALESCE(item.thread_id, item.comment_id, item.review_id,
                                item.requested_reviewer) AS external_target,
                       item.external_key
                FROM remote_inbox_item item
                WHERE item.remote_development_stage_id = ?
                  AND item.task_id = ? AND item.task_epoch = ?
                  AND item.stage_generation = ?
                  AND item.remote_pr_binding_id = ?
                  AND item.head_sha = ? AND item.base_sha = ?
                  AND item.ignored = 0
                  AND item.kind NOT IN ('HEAD_CHANGED', 'THREAD_RESOLVED')
                  AND item.external_revision = (
                      SELECT MAX(latest.external_revision)
                      FROM remote_inbox_item latest
                      WHERE latest.remote_pr_binding_id = item.remote_pr_binding_id
                        AND latest.external_key = item.external_key)
                  AND NOT EXISTS (
                      SELECT 1 FROM remote_feedback_batch_item selected
                      WHERE selected.remote_inbox_item_id = item.id)
                ORDER BY item.observed_at_ms, item.external_key, item.id
                """, (rs, row) -> new FrozenItem(
                        rs.getString("id"), rs.getLong("external_revision"),
                        InboxKind.valueOf(rs.getString("kind")), rs.getString("body"),
                        rs.getString("body_digest"), rs.getString("external_target"),
                        rs.getString("external_key")),
                stageId, taskId, context.taskEpoch(), context.stageGeneration(),
                context.remotePrBindingId(), context.headSha(), context.baseSha());
        if (items.isEmpty()) {
            return Optional.empty();
        }

        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) + 1
                FROM remote_feedback_batch
                WHERE remote_development_stage_id = ?
                """, Integer.class, stageId);
        int sequence = requireNonNull(next, "next Remote batch sequence is null");
        jdbc.update("""
                INSERT INTO remote_feedback_batch(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, source_snapshot_id,
                    sequence, head_sha, base_sha, status,
                    brain_review_required, item_count, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BUILDING', ?, ?, ?)
                """, batchId, stageId, taskId, context.taskEpoch(),
                context.stageGeneration(), context.remotePrBindingId(),
                context.snapshotId(), sequence, context.headSha(), context.baseSha(),
                brainReviewRequired ? 1 : 0, items.size(), now.toEpochMilli());
        int ordinal = 1;
        for (FrozenItem item : items) {
            jdbc.update("""
                    INSERT INTO remote_feedback_batch_item(
                        remote_feedback_batch_id, ordinal, remote_inbox_item_id,
                        external_revision, kind, frozen_body, body_digest,
                        external_target, selected_by, selected_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, batchId, ordinal++, item.id(), item.externalRevision(),
                    item.kind().name(), item.body(), item.bodyDigest(),
                    item.externalTarget(), selectedBy, now.toEpochMilli());
        }
        String digest = digest(items.stream()
                .map(item -> item.externalKey() + "\u0000" + item.externalRevision()
                        + "\u0000" + item.kind() + "\u0000"
                        + Objects.toString(item.bodyDigest(), ""))
                .toList());
        int frozen = jdbc.update("""
                UPDATE remote_feedback_batch
                SET status = 'FROZEN', content_digest = ?, frozen_at_ms = ?
                WHERE id = ? AND status = 'BUILDING'
                """, digest, now.toEpochMilli(), batchId);
        if (frozen != 1) {
            throw new IllegalStateException("Remote feedback batch changed before freeze");
        }
        return Optional.of(new FrozenBatch(
                batchId, sequence, context.snapshotId(), context.headSha(),
                context.baseSha(), digest, List.copyOf(items)));
    }

    public AutomationPolicy appendAutomationPolicy(
            String policyId,
            String taskId,
            String source,
            Boolean autoApprove,
            Boolean autoMerge,
            Boolean keepDraft,
            Integer minimumWriteApprovals,
            Integer maxMergeQueueReenqueues,
            Boolean requireLowRisk,
            Boolean requireSmallEffort,
            Boolean stewardshipException,
            String createdBy,
            Instant now)
    {
        requireTransaction();
        AutomationPolicy previous = requireAutomationPolicy(taskId);
        boolean merge = autoMerge == null ? previous.autoMerge() : autoMerge;
        boolean approve = autoApprove == null ? previous.autoApprove() : autoApprove;
        if (merge) {
            approve = true;
        }
        boolean exception = stewardshipException == null
                ? previous.stewardshipException() : stewardshipException;
        if (exception) {
            approve = false;
            merge = false;
        }
        AutomationPolicy policy = new AutomationPolicy(
                policyId, taskId, previous.revision() + 1, source, approve, merge,
                keepDraft == null ? previous.keepDraft() : keepDraft,
                minimumWriteApprovals == null
                        ? previous.minimumWriteApprovals() : minimumWriteApprovals,
                maxMergeQueueReenqueues == null
                        ? previous.maxMergeQueueReenqueues() : maxMergeQueueReenqueues,
                requireLowRisk == null ? previous.requireLowRisk() : requireLowRisk,
                requireSmallEffort == null
                        ? previous.requireSmallEffort() : requireSmallEffort,
                exception, createdBy, now);
        insertAutomationPolicy(policy);
        return policy;
    }

    public AutomationPolicy requireAutomationPolicy(String taskId)
    {
        List<AutomationPolicy> rows = jdbc.query("""
                SELECT id, task_id, revision, source, auto_approve, auto_merge,
                       keep_draft, minimum_write_approvals,
                       max_merge_queue_reenqueues, require_low_risk,
                       require_small_effort, stewardship_exception,
                       created_by, created_at_ms
                FROM task_automation_policy
                WHERE task_id = ?
                ORDER BY revision DESC LIMIT 1
                """, (rs, row) -> automationPolicy(rs), taskId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Current Task automation policy is missing");
        }
        return rows.getFirst();
    }

    public ReadinessEvidence proveReadiness(
            String evidenceId,
            String taskId,
            String stageId,
            String automationEligibilityEvidenceId,
            String evidence,
            Instant now)
    {
        requireTransaction();
        RemoteContext context = requireContext(taskId, stageId);
        AutomationPolicy policy = requireAutomationPolicy(taskId);
        ReadinessInputs inputs = requireReadinessInputs(
                context, policy, automationEligibilityEvidenceId);
        boolean ready = inputs.prOpen()
                && inputs.nonDraft()
                && inputs.ciAccepted()
                && inputs.writeApprovalCount() >= policy.minimumWriteApprovals()
                && inputs.changesRequestedCount() == 0
                && inputs.unresolvedThreadCount() == 0
                && inputs.unresolvedCommentCount() == 0
                && inputs.openFeedbackBatchCount() == 0
                && inputs.blockingGateCount() == 0
                && (!policy.requireLowRisk() || inputs.lowRiskEligible())
                && (!policy.requireSmallEffort() || inputs.smallEffortEligible())
                && inputs.mergeability() == Mergeability.MERGEABLE;
        jdbc.update("""
                INSERT INTO remote_readiness_evidence(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_snapshot_id, ci_evaluation_id,
                    automation_policy_id, automation_eligibility_evidence_id,
                    head_sha, base_sha, pr_open, non_draft, ci_accepted,
                    write_approval_count, required_write_approval_count,
                    changes_requested_count, unresolved_thread_count,
                    unresolved_comment_count, open_feedback_batch_count,
                    blocking_gate_count, low_risk_required,
                    small_effort_required, low_risk_eligible,
                    small_effort_eligible, mergeability,
                    merge_queue_capability, ready, evidence, observed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, evidenceId, stageId, taskId, context.taskEpoch(),
                context.stageGeneration(), context.snapshotId(), inputs.ciEvaluationId(),
                policy.id(), automationEligibilityEvidenceId, context.headSha(),
                context.baseSha(), inputs.prOpen() ? 1 : 0,
                inputs.nonDraft() ? 1 : 0, inputs.ciAccepted() ? 1 : 0,
                inputs.writeApprovalCount(), policy.minimumWriteApprovals(),
                inputs.changesRequestedCount(), inputs.unresolvedThreadCount(),
                inputs.unresolvedCommentCount(), inputs.openFeedbackBatchCount(),
                inputs.blockingGateCount(), policy.requireLowRisk() ? 1 : 0,
                policy.requireSmallEffort() ? 1 : 0,
                inputs.lowRiskEligible() ? 1 : 0,
                inputs.smallEffortEligible() ? 1 : 0,
                inputs.mergeability().name(), inputs.mergeQueueCapability().name(),
                ready ? 1 : 0, evidence, inputs.observedAt().toEpochMilli());
        return new ReadinessEvidence(
                evidenceId, context.snapshotId(), inputs.ciEvaluationId(),
                policy.id(), context.headSha(), context.baseSha(), ready);
    }

    /** Freezes one user-approved effect set; policy automation cannot call this API. */
    public AuthorizedFeedback authorizeFeedback(
            String authorizationId,
            String batchId,
            String authorizedBy,
            String reason,
            List<EffectDraft> effects,
            Instant now)
    {
        requireTransaction();
        requireText(authorizationId, "authorizationId");
        requireText(batchId, "batchId");
        requireText(authorizedBy, "authorizedBy");
        if (effects.isEmpty()) {
            throw new IllegalArgumentException("Remote feedback requires an effect set");
        }
        for (int index = 0; index < effects.size(); index++) {
            EffectDraft effect = effects.get(index);
            if (effect.kind() == EffectKind.PUSH_COMMITS && index != effects.size() - 1) {
                throw new IllegalArgumentException("Remote feedback push must be last");
            }
        }
        FeedbackGate gate = requireFeedbackGate(batchId);
        jdbc.update("""
                INSERT INTO remote_feedback_authorization(
                    id, remote_feedback_batch_id, validation_evidence_id,
                    brain_review_evidence_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, head_sha, base_sha,
                    item_count, content_digest, effect_count, authority_kind,
                    authorized_by, reason, authorized_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'USER_ACTION', ?, ?, ?)
                """, authorizationId, batchId, gate.validationEvidenceId(),
                gate.brainEvidenceId(), gate.stageId(), gate.taskId(), gate.taskEpoch(),
                gate.stageGeneration(), gate.headSha(), gate.baseSha(), gate.itemCount(),
                gate.contentDigest(), effects.size(), authorizedBy, reason,
                now.toEpochMilli());
        int ordinal = 1;
        for (EffectDraft effect : effects) {
            String payloadDigest = digest(effect.payload());
            jdbc.update("""
                    INSERT INTO remote_feedback_effect_step(
                        id, remote_feedback_authorization_id,
                        remote_feedback_batch_id, ordinal, kind,
                        remote_inbox_item_id, external_target, review_action,
                        payload_digest, idempotency_key, status,
                        attempt_count, attempt_limit)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', 0, ?)
                    """, effect.id(), authorizationId, batchId, ordinal++,
                    effect.kind().name(), effect.remoteInboxItemId(),
                    effect.externalTarget(), effect.reviewAction(), payloadDigest,
                    effect.idempotencyKey(), effect.attemptLimit());
            jdbc.update("""
                    INSERT INTO remote_feedback_effect_payload(
                        remote_feedback_effect_step_id, payload_kind,
                        payload, payload_digest, created_at_ms)
                    VALUES (?, ?, ?, ?, ?)
                    """, effect.id(), effect.payloadKind().name(), effect.payload(),
                    payloadDigest, now.toEpochMilli());
        }
        int authorized = jdbc.update("""
                UPDATE remote_feedback_batch SET status = 'AUTHORIZED'
                WHERE id = ? AND status = 'AWAITING_APPROVAL'
                """, batchId);
        if (authorized != 1) {
            throw new IllegalStateException("Remote feedback gate changed before consent");
        }
        int applying = jdbc.update("""
                UPDATE remote_feedback_batch SET status = 'APPLYING'
                WHERE id = ? AND status = 'AUTHORIZED'
                """, batchId);
        if (applying != 1) {
            throw new IllegalStateException("Remote feedback batch did not start applying");
        }
        EffectDispatch first = dispatchNextEffect(batchId, now)
                .orElseThrow(() -> new IllegalStateException(
                        "Authorized Remote feedback has no first effect"));
        return new AuthorizedFeedback(authorizationId, batchId, first);
    }

    public Optional<EffectDispatch> dispatchNextEffect(String batchId, Instant now)
    {
        requireTransaction();
        List<EffectDispatchCandidate> rows = jdbc.query("""
                SELECT step.id, step.kind, step.attempt_count,
                       batch.task_id, batch.task_epoch,
                       batch.remote_development_stage_id,
                       batch.stage_generation, authorization.head_sha,
                       authorization.base_sha, task.thread_id AS trunk_id,
                       trunk.workspace_id
                FROM remote_feedback_effect_step step
                JOIN remote_feedback_batch batch
                  ON batch.id = step.remote_feedback_batch_id
                JOIN remote_feedback_authorization authorization
                  ON authorization.id = step.remote_feedback_authorization_id
                JOIN tasks task ON task.id = batch.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                WHERE batch.id = ? AND batch.status = 'APPLYING'
                  AND step.status <> 'SUCCEEDED'
                  AND NOT EXISTS (
                      SELECT 1 FROM remote_feedback_effect_step previous
                      WHERE previous.remote_feedback_batch_id = batch.id
                        AND previous.ordinal < step.ordinal
                        AND previous.status <> 'SUCCEEDED')
                  AND NOT EXISTS (
                      SELECT 1 FROM remote_feedback_effect_dispatch dispatch
                      WHERE dispatch.remote_feedback_effect_step_id = step.id)
                ORDER BY step.ordinal LIMIT 1
                """, (rs, row) -> effectDispatchCandidate(rs), batchId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        EffectDispatchCandidate effect = rows.getFirst();
        String operationId = effect.id() + ":attempt:" + (effect.attemptCount() + 1);
        String ticketId = "remote-feedback-effect-ticket-" + digest(operationId);
        boolean push = effect.kind() == EffectKind.PUSH_COMMITS;
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'APPLY_REMOTE_FEEDBACK_EFFECT', ?,
                    'STAGE', ?, 'REMOTE_FEEDBACK_EFFECT_RESULT', ?,
                    0, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, push ? "LOCAL_GIT" : "GITHUB_EFFECT",
                effect.stageId(), push ? 16 : 32, push ? 1 : 0,
                effect.workspaceId(), effect.trunkId(), effect.taskId(),
                effect.taskEpoch(), effect.stageId(), effect.stageGeneration(),
                effect.attemptCount() + 1, effect.headSha(), effect.baseSha(),
                now.toEpochMilli());
        jdbc.update("""
                INSERT INTO remote_feedback_effect_dispatch(
                    remote_feedback_effect_step_id, dispatch_ticket_id,
                    operation_id, dispatched_at_ms)
                VALUES (?, ?, ?, ?)
                """, effect.id(), ticketId, operationId, now.toEpochMilli());
        return Optional.of(new EffectDispatch(
                effect.id(), operationId, ticketId, effect.kind()));
    }

    public Optional<EffectCompletion> finishBatchIfEffectsProven(
            String batchId, Instant now)
    {
        requireTransaction();
        List<BatchCompletionCandidate> rows = jdbc.query("""
                SELECT batch.id, batch.task_id, batch.task_epoch,
                       batch.remote_development_stage_id,
                       batch.stage_generation, batch.head_sha, batch.base_sha,
                       push.external_effect_id AS result_head_sha,
                       snapshot.id AS result_snapshot_id
                FROM remote_feedback_batch batch
                LEFT JOIN remote_feedback_effect_step push
                  ON push.remote_feedback_batch_id = batch.id
                 AND push.kind = 'PUSH_COMMITS'
                LEFT JOIN remote_development_stage remote
                  ON remote.stage_id = batch.remote_development_stage_id
                LEFT JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                 AND snapshot.head_sha = push.external_effect_id
                 AND snapshot.base_sha = batch.base_sha
                WHERE batch.id = ? AND batch.status = 'APPLYING'
                  AND NOT EXISTS (
                      SELECT 1 FROM remote_feedback_effect_step unfinished
                      WHERE unfinished.remote_feedback_batch_id = batch.id
                        AND unfinished.status <> 'SUCCEEDED')
                """, (rs, row) -> batchCompletionCandidate(rs), batchId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BatchCompletionCandidate candidate = rows.getFirst();
        boolean pushed = candidate.resultHeadSha() != null;
        if (pushed && candidate.resultSnapshotId() == null) {
            return Optional.empty();
        }
        int changed = jdbc.update("""
                UPDATE remote_feedback_batch
                SET status = 'COMPLETED', result_head_sha = ?,
                    result_snapshot_id = ?, completed_at_ms = ?
                WHERE id = ? AND status = 'APPLYING'
                """, candidate.resultHeadSha(), candidate.resultSnapshotId(),
                now.toEpochMilli(), batchId);
        if (changed != 1) {
            throw new IllegalStateException("Remote feedback batch completion lost");
        }
        return Optional.of(new EffectCompletion(
                candidate.taskId(), candidate.taskEpoch(), candidate.stageId(),
                candidate.stageGeneration(), candidate.id(), pushed,
                candidate.resultHeadSha(), candidate.resultSnapshotId()));
    }

    public MarkReadyDispatch authorizeMarkReady(
            String authorizationId,
            String operationId,
            String taskId,
            String stageId,
            AuthorityKind authority,
            String actorId,
            int attemptLimit,
            Instant now)
    {
        requireTransaction();
        RemoteContext context = requireContext(taskId, stageId);
        AutomationPolicy policy = requireAutomationPolicy(taskId);
        List<MarkReadyProof> proofs = jdbc.query("""
                SELECT snapshot.id AS snapshot_id, ci.id AS ci_id,
                       snapshot.pr_state, ci.policy_outcome
                FROM remote_pr_snapshot snapshot
                JOIN remote_ci_evaluation ci
                  ON ci.remote_pr_snapshot_id = snapshot.id
                WHERE snapshot.id = ? AND snapshot.task_id = ?
                  AND snapshot.head_sha = ? AND snapshot.base_sha = ?
                ORDER BY ci.evaluated_at_ms DESC LIMIT 1
                """, (rs, row) -> new MarkReadyProof(
                        rs.getString("snapshot_id"), rs.getString("ci_id"),
                        rs.getString("pr_state"), rs.getString("policy_outcome")),
                context.snapshotId(), taskId, context.headSha(), context.baseSha());
        if (proofs.size() != 1) {
            throw new IllegalStateException("Exact Draft/CI mark-ready proof is missing");
        }
        MarkReadyProof proof = proofs.getFirst();
        if (!"DRAFT".equals(proof.prState())
                || !"ACCEPTED".equals(proof.policyOutcome())) {
            throw new IllegalStateException(
                    "Mark-ready requires the current Draft and accepted CI policy");
        }
        jdbc.update("""
                INSERT INTO remote_mark_ready_authorization(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_snapshot_id, ci_evaluation_id,
                    automation_policy_id, head_sha, base_sha, authority_kind,
                    actor_id, status, authorized_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, authorizationId, stageId, taskId, context.taskEpoch(),
                context.stageGeneration(), proof.snapshotId(), proof.ciId(),
                policy.id(), context.headSha(), context.baseSha(), authority.name(),
                actorId, now.toEpochMilli());
        String id = "mark-ready-" + digest(operationId);
        jdbc.update("""
                INSERT INTO remote_mark_ready_operation(
                    id, mark_ready_authorization_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, operation_id, semantic_attempt,
                    head_sha, base_sha, status, attempt_count, attempt_limit,
                    requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'REQUESTED', 0, ?, ?)
                """, id, authorizationId, stageId, taskId, context.taskEpoch(),
                context.stageGeneration(), operationId, context.headSha(),
                context.baseSha(), attemptLimit, now.toEpochMilli());
        jdbc.update("""
                UPDATE remote_mark_ready_authorization
                SET status = 'CONSUMED', terminal_at_ms = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, now.toEpochMilli(), authorizationId);
        String ticketId = "remote-mark-ready-ticket-" + digest(operationId);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'MARK_REMOTE_PR_READY', 'GITHUB_EFFECT',
                    'STAGE', ?, 'REMOTE_MARK_READY_RESULT', 32,
                    0, 1, 0, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, stageId, context.workspaceId(),
                context.trunkId(), taskId, context.taskEpoch(), stageId,
                context.stageGeneration(), context.headSha(), context.baseSha(),
                now.toEpochMilli());
        jdbc.update("""
                INSERT INTO remote_mark_ready_dispatch(
                    remote_mark_ready_operation_id, dispatch_ticket_id,
                    operation_id, dispatched_at_ms)
                VALUES (?, ?, ?, ?)
                """, id, ticketId, operationId, now.toEpochMilli());
        return new MarkReadyDispatch(
                authorizationId, id, operationId, ticketId, authority,
                policy.revision());
    }

    public EffectDeliveryContext requireEffectDelivery(String operationId)
    {
        List<EffectDeliveryContext> rows = jdbc.query("""
                SELECT dispatch.operation_id, step.id AS effect_id,
                       step.status AS effect_status,
                       batch.id AS batch_id, batch.status AS batch_status,
                       batch.task_id, batch.task_epoch,
                       batch.remote_development_stage_id,
                       batch.stage_generation, authorization.head_sha,
                       authorization.base_sha,
                       CASE WHEN task.lifecycle_state = 'ACTIVE'
                              AND task.epoch = batch.task_epoch
                              AND current.stage_id = batch.remote_development_stage_id
                              AND current.stage_generation = batch.stage_generation
                              AND remote.current_head_sha = authorization.head_sha
                              AND remote.current_base_sha = authorization.base_sha
                            THEN 1 ELSE 0 END AS is_current
                FROM remote_feedback_effect_dispatch dispatch
                JOIN remote_feedback_effect_step step
                  ON step.id = dispatch.remote_feedback_effect_step_id
                JOIN remote_feedback_batch batch
                  ON batch.id = step.remote_feedback_batch_id
                JOIN remote_feedback_authorization authorization
                  ON authorization.id = step.remote_feedback_authorization_id
                JOIN tasks task ON task.id = batch.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                JOIN remote_development_stage remote
                  ON remote.stage_id = batch.remote_development_stage_id
                WHERE dispatch.operation_id = ?
                """, (rs, row) -> new EffectDeliveryContext(
                        rs.getString("operation_id"), rs.getString("effect_id"),
                        rs.getString("effect_status"), rs.getString("batch_id"),
                        rs.getString("batch_status"), rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("stage_generation"), rs.getString("head_sha"),
                        rs.getString("base_sha"), rs.getBoolean("is_current")),
                operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Exact Remote effect delivery is missing");
        }
        return rows.getFirst();
    }

    public MarkReadyDeliveryContext requireMarkReadyDelivery(String operationId)
    {
        List<MarkReadyDeliveryContext> rows = jdbc.query("""
                SELECT operation.id, operation.operation_id, operation.status,
                       operation.task_id, operation.task_epoch,
                       operation.remote_development_stage_id,
                       operation.stage_generation, owner.version AS stage_version,
                       operation.head_sha, operation.base_sha,
                       operation.result_snapshot_id,
                       CASE WHEN task.lifecycle_state = 'ACTIVE'
                              AND task.epoch = operation.task_epoch
                              AND current.stage_id = operation.remote_development_stage_id
                              AND current.stage_generation = operation.stage_generation
                              AND remote.current_head_sha = operation.head_sha
                              AND remote.current_base_sha = operation.base_sha
                              AND remote.accepted_snapshot_id = operation.result_snapshot_id
                            THEN 1 ELSE 0 END AS is_current
                FROM remote_mark_ready_operation operation
                JOIN remote_mark_ready_dispatch dispatch
                  ON dispatch.remote_mark_ready_operation_id = operation.id
                JOIN tasks task ON task.id = operation.task_id
                JOIN stage owner ON owner.id = operation.remote_development_stage_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                WHERE dispatch.operation_id = ?
                """, (rs, row) -> new MarkReadyDeliveryContext(
                        rs.getString("id"), rs.getString("operation_id"),
                        rs.getString("status"), rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getLong("stage_version"), rs.getString("head_sha"),
                        rs.getString("base_sha"),
                        rs.getString("result_snapshot_id"),
                        rs.getBoolean("is_current")), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Exact mark-ready delivery is missing");
        }
        return rows.getFirst();
    }

    public Optional<RuntimeDeliveryReceipt> findRuntimeDeliveryReceipt(
            String operationId)
    {
        return jdbc.query("""
                SELECT operation_id, callback_route, raw_result_digest,
                       acceptance, evidence, recorded_at_ms
                FROM remote_runtime_delivery_receipt
                WHERE operation_id = ?
                """, (rs, row) -> new RuntimeDeliveryReceipt(
                        rs.getString("operation_id"),
                        rs.getString("callback_route"),
                        rs.getString("raw_result_digest"),
                        rs.getString("acceptance"), rs.getString("evidence"),
                        Instant.ofEpochMilli(rs.getLong("recorded_at_ms"))),
                operationId).stream().findFirst();
    }

    public void insertRuntimeDeliveryReceipt(RuntimeDeliveryReceipt receipt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO remote_runtime_delivery_receipt(
                    id, operation_id, callback_route, raw_result_digest,
                    acceptance, evidence, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, "remote-delivery-" + digest(receipt.operationId()),
                receipt.operationId(), receipt.callbackRoute(),
                receipt.rawResultDigest(), receipt.acceptance(), receipt.evidence(),
                receipt.recordedAt().toEpochMilli());
    }

    public RemoteContext requireContext(String taskId, String stageId)
    {
        List<RemoteContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                       trunk.workspace_id, task.epoch AS task_epoch,
                       task.aggregate_version AS task_version,
                       remote.stage_id, remote.generation AS stage_generation,
                       owner.version AS stage_version, owner.checkpoint,
                       remote.remote_pr_binding_id, remote.accepted_snapshot_id,
                       remote.accepted_observation_revision,
                       remote.current_head_sha, remote.current_base_sha,
                       code.code_fingerprint, identity.worktree_path
                FROM remote_development_stage remote
                JOIN stage owner ON owner.id = remote.stage_id
                JOIN tasks task ON task.id = remote.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                WHERE task.id = ? AND remote.stage_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch > 0
                  AND current.stage_id = remote.stage_id
                  AND current.stage_generation = remote.generation
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.completed_at_ms IS NULL
                  AND remote.accepted_snapshot_id IS NOT NULL
                """, (rs, row) -> remoteContext(rs), taskId, stageId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Expected one exact Remote context");
        }
        return rows.getFirst();
    }

    @Override
    public Optional<FeedbackEvidence> findRemoteFeedback(
            String taskId, String stageId, long stageGeneration, String batchId)
    {
        return jdbc.query("""
                SELECT task_id, remote_development_stage_id, stage_generation,
                       id, source_snapshot_id, content_digest
                FROM remote_feedback_batch
                WHERE task_id = ? AND remote_development_stage_id = ?
                  AND stage_generation = ? AND id = ?
                  AND status = 'FROZEN'
                """, (rs, row) -> new FeedbackEvidence(
                        rs.getString("task_id"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("stage_generation"), rs.getString("id"),
                        rs.getString("source_snapshot_id"),
                        rs.getString("content_digest")),
                taskId, stageId, stageGeneration, batchId).stream().findFirst();
    }

    @Override
    public Optional<RemoteSubjectEvidence> findCurrentRemoteSubject(
            String taskId, String stageId, long stageGeneration)
    {
        return jdbc.query("""
                SELECT remote.task_id, task.epoch, remote.stage_id,
                       remote.generation, remote.accepted_observation_revision,
                       remote.current_head_sha, remote.current_base_sha
                FROM remote_development_stage remote
                JOIN tasks task ON task.id = remote.task_id
                WHERE remote.task_id = ? AND remote.stage_id = ?
                  AND remote.generation = ? AND task.workflow_version = 'V2'
                """, (rs, row) -> new RemoteSubjectEvidence(
                        rs.getString("task_id"), rs.getLong("epoch"),
                        rs.getString("stage_id"), rs.getLong("generation"),
                        rs.getLong("accepted_observation_revision"),
                        rs.getString("current_head_sha"),
                        rs.getString("current_base_sha")),
                taskId, stageId, stageGeneration).stream().findFirst();
    }

    @Override
    public Optional<FeedbackCompletionEvidence> findCompletedRemoteFeedback(
            String taskId, String stageId, long stageGeneration, String batchId)
    {
        return jdbc.query("""
                SELECT batch.task_id, batch.task_epoch,
                       batch.remote_development_stage_id,
                       batch.stage_generation, batch.id,
                       batch.result_head_sha, batch.result_snapshot_id
                FROM remote_feedback_batch batch
                JOIN remote_development_stage remote
                  ON remote.stage_id = batch.remote_development_stage_id
                WHERE batch.task_id = ?
                  AND batch.remote_development_stage_id = ?
                  AND batch.stage_generation = ? AND batch.id = ?
                  AND batch.status = 'COMPLETED'
                  AND ((batch.result_head_sha IS NULL
                        AND remote.current_head_sha = batch.head_sha)
                    OR (batch.result_head_sha IS NOT NULL
                        AND remote.current_head_sha = batch.result_head_sha
                        AND remote.accepted_snapshot_id = batch.result_snapshot_id))
                """, (rs, row) -> new FeedbackCompletionEvidence(
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("stage_generation"), rs.getString("id"),
                        rs.getString("result_head_sha") != null,
                        rs.getString("result_head_sha"),
                        rs.getString("result_snapshot_id")),
                taskId, stageId, stageGeneration, batchId).stream().findFirst();
    }

    @Override
    public Optional<RemoteGateEvidence> findCompletedMarkReady(
            String taskId, String stageId, long stageGeneration, String operationId)
    {
        return jdbc.query("""
                SELECT operation.task_id, operation.task_epoch,
                       operation.remote_development_stage_id,
                       operation.stage_generation, operation.id,
                       operation.head_sha, operation.base_sha
                FROM remote_mark_ready_operation operation
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                WHERE operation.task_id = ?
                  AND operation.remote_development_stage_id = ?
                  AND operation.stage_generation = ? AND operation.id = ?
                  AND operation.status = 'SUCCEEDED'
                  AND operation.result_snapshot_id = remote.accepted_snapshot_id
                  AND operation.head_sha = remote.current_head_sha
                  AND operation.base_sha = remote.current_base_sha
                """, (rs, row) -> new RemoteGateEvidence(
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("stage_generation"), rs.getString("id"),
                        rs.getString("head_sha"), rs.getString("base_sha")),
                taskId, stageId, stageGeneration, operationId)
                .stream().findFirst();
    }

    @Override
    public Optional<RemoteGateEvidence> findReadyEvidence(
            String taskId, String stageId, long stageGeneration, String evidenceId)
    {
        return jdbc.query("""
                SELECT readiness.task_id, readiness.task_epoch,
                       readiness.remote_development_stage_id,
                       readiness.stage_generation, readiness.id,
                       readiness.head_sha, readiness.base_sha
                FROM remote_readiness_evidence readiness
                JOIN remote_development_stage remote
                  ON remote.stage_id = readiness.remote_development_stage_id
                WHERE readiness.task_id = ?
                  AND readiness.remote_development_stage_id = ?
                  AND readiness.stage_generation = ? AND readiness.id = ?
                  AND readiness.ready = 1
                  AND readiness.remote_pr_snapshot_id = remote.accepted_snapshot_id
                  AND readiness.head_sha = remote.current_head_sha
                  AND readiness.base_sha = remote.current_base_sha
                """, (rs, row) -> new RemoteGateEvidence(
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("stage_generation"), rs.getString("id"),
                        rs.getString("head_sha"), rs.getString("base_sha")),
                taskId, stageId, stageGeneration, evidenceId)
                .stream().findFirst();
    }

    private ReadinessInputs requireReadinessInputs(
            RemoteContext context,
            AutomationPolicy policy,
            String eligibilityId)
    {
        List<ReadinessInputs> rows = jdbc.query("""
                SELECT ci.id AS ci_id, snapshot.pr_state,
                       ci.policy_outcome, snapshot.write_approval_count,
                       snapshot.changes_requested_count,
                       snapshot.unresolved_thread_count,
                       snapshot.unresolved_comment_count,
                       snapshot.mergeability, snapshot.merge_queue_capability,
                       snapshot.observed_at_ms,
                       (SELECT COUNT(*) FROM remote_feedback_batch batch
                        WHERE batch.remote_development_stage_id = remote.stage_id
                          AND batch.head_sha = remote.current_head_sha
                          AND batch.status NOT IN ('COMPLETED', 'SUPERSEDED'))
                            AS open_batch_count,
                       (SELECT COUNT(*) FROM task_blocker blocker
                        WHERE blocker.task_id = remote.task_id
                          AND (blocker.stage_id IS NULL
                            OR blocker.stage_id = remote.stage_id)
                          AND blocker.status = 'OPEN') AS blocker_count,
                       COALESCE(eligibility.low_risk_eligible, 0) AS low_risk,
                       COALESCE(eligibility.small_effort_eligible, 0) AS small_effort
                FROM remote_development_stage remote
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                JOIN remote_ci_evaluation ci
                  ON ci.remote_pr_snapshot_id = snapshot.id
                LEFT JOIN task_automation_eligibility_evidence eligibility
                  ON eligibility.id = ?
                WHERE remote.stage_id = ? AND remote.task_id = ?
                  AND remote.accepted_snapshot_id = ?
                  AND remote.current_head_sha = ?
                  AND remote.current_base_sha = ?
                ORDER BY ci.evaluated_at_ms DESC LIMIT 1
                """, (rs, row) -> new ReadinessInputs(
                        rs.getString("ci_id"),
                        "OPEN".equals(rs.getString("pr_state")),
                        "OPEN".equals(rs.getString("pr_state")),
                        "ACCEPTED".equals(rs.getString("policy_outcome")),
                        rs.getInt("write_approval_count"),
                        rs.getInt("changes_requested_count"),
                        rs.getInt("unresolved_thread_count"),
                        rs.getInt("unresolved_comment_count"),
                        rs.getInt("open_batch_count"), rs.getInt("blocker_count"),
                        rs.getBoolean("low_risk"), rs.getBoolean("small_effort"),
                        Mergeability.valueOf(rs.getString("mergeability")),
                        MergeQueueCapability.valueOf(
                                rs.getString("merge_queue_capability")),
                        Instant.ofEpochMilli(rs.getLong("observed_at_ms"))),
                eligibilityId, context.stageId(), context.taskId(),
                context.snapshotId(), context.headSha(), context.baseSha());
        if (rows.size() != 1) {
            throw new IllegalStateException("Fresh exact-head CI evidence is missing");
        }
        if ((policy.requireLowRisk() || policy.requireSmallEffort())
                && eligibilityId == null) {
            throw new IllegalStateException("Configured automation eligibility is missing");
        }
        return rows.getFirst();
    }

    private FeedbackGate requireFeedbackGate(String batchId)
    {
        List<FeedbackGate> rows = jdbc.query("""
                SELECT batch.id, batch.task_id, batch.task_epoch,
                       batch.remote_development_stage_id,
                       batch.stage_generation, batch.head_sha, batch.base_sha,
                       batch.item_count, batch.content_digest,
                       validation.id AS validation_id,
                       brain.id AS brain_id
                FROM remote_feedback_batch batch
                JOIN remote_feedback_validation_evidence validation
                  ON validation.remote_feedback_batch_id = batch.id
                 AND validation.passed = 1
                LEFT JOIN remote_feedback_brain_review_evidence brain
                  ON brain.remote_feedback_batch_id = batch.id
                 AND brain.verdict = 'APPROVED'
                WHERE batch.id = ? AND batch.status = 'AWAITING_APPROVAL'
                  AND (batch.brain_review_required = 0 OR brain.id IS NOT NULL)
                """, (rs, row) -> new FeedbackGate(
                        rs.getString("id"), rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("stage_generation"), rs.getString("head_sha"),
                        rs.getString("base_sha"), rs.getInt("item_count"),
                        rs.getString("content_digest"),
                        rs.getString("validation_id"), rs.getString("brain_id")),
                batchId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Remote feedback is not at one complete user gate");
        }
        return rows.getFirst();
    }

    private void insertAutomationPolicy(AutomationPolicy policy)
    {
        jdbc.update("""
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, policy.id(), policy.taskId(), policy.revision(), policy.source(),
                policy.autoApprove() ? 1 : 0, policy.autoMerge() ? 1 : 0,
                policy.keepDraft() ? 1 : 0, policy.minimumWriteApprovals(),
                policy.maxMergeQueueReenqueues(), policy.requireLowRisk() ? 1 : 0,
                policy.requireSmallEffort() ? 1 : 0,
                policy.stewardshipException() ? 1 : 0, policy.createdBy(),
                policy.createdAt().toEpochMilli());
    }

    private static InboxItem inboxItem(ResultSet rs)
            throws SQLException
    {
        return new InboxItem(
                rs.getString("id"), rs.getString("task_id"),
                rs.getLong("task_epoch"),
                rs.getString("remote_development_stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("remote_pr_binding_id"),
                rs.getString("remote_pr_snapshot_id"),
                InboxKind.valueOf(rs.getString("kind")),
                rs.getString("external_key"), rs.getLong("external_revision"),
                rs.getString("head_sha"), rs.getString("base_sha"),
                rs.getString("actor_login"),
                Provenance.valueOf(rs.getString("provenance")),
                rs.getBoolean("ignored"), rs.getString("thread_id"),
                rs.getString("comment_id"), rs.getString("review_id"),
                rs.getString("requested_reviewer"), rs.getString("body"),
                rs.getString("body_digest"), verdict(rs.getString("verdict")),
                rs.getString("previous_head_sha"), rs.getString("new_head_sha"),
                Instant.ofEpochMilli(rs.getLong("observed_at_ms")),
                rs.getString("raw_evidence"));
    }

    private static AutomationPolicy automationPolicy(ResultSet rs)
            throws SQLException
    {
        return new AutomationPolicy(
                rs.getString("id"), rs.getString("task_id"),
                rs.getInt("revision"), rs.getString("source"),
                rs.getBoolean("auto_approve"), rs.getBoolean("auto_merge"),
                rs.getBoolean("keep_draft"),
                rs.getInt("minimum_write_approvals"),
                rs.getInt("max_merge_queue_reenqueues"),
                rs.getBoolean("require_low_risk"),
                rs.getBoolean("require_small_effort"),
                rs.getBoolean("stewardship_exception"),
                rs.getString("created_by"),
                Instant.ofEpochMilli(rs.getLong("created_at_ms")));
    }

    private static RemoteContext remoteContext(ResultSet rs)
            throws SQLException
    {
        return new RemoteContext(
                rs.getString("workspace_id"), rs.getString("trunk_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getLong("task_version"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getLong("stage_version"),
                rs.getString("checkpoint"), rs.getString("remote_pr_binding_id"),
                rs.getString("accepted_snapshot_id"),
                rs.getLong("accepted_observation_revision"),
                rs.getString("current_head_sha"), rs.getString("current_base_sha"),
                rs.getString("code_fingerprint"), rs.getString("worktree_path"));
    }

    private static EffectDispatchCandidate effectDispatchCandidate(ResultSet rs)
            throws SQLException
    {
        return new EffectDispatchCandidate(
                rs.getString("id"), EffectKind.valueOf(rs.getString("kind")),
                rs.getInt("attempt_count"), rs.getString("workspace_id"),
                rs.getString("trunk_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"),
                rs.getString("remote_development_stage_id"),
                rs.getLong("stage_generation"), rs.getString("head_sha"),
                rs.getString("base_sha"));
    }

    private static BatchCompletionCandidate batchCompletionCandidate(ResultSet rs)
            throws SQLException
    {
        return new BatchCompletionCandidate(
                rs.getString("id"), rs.getString("task_id"),
                rs.getLong("task_epoch"),
                rs.getString("remote_development_stage_id"),
                rs.getLong("stage_generation"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("result_head_sha"),
                rs.getString("result_snapshot_id"));
    }

    public static String digest(Object value)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static Verdict verdict(String value)
    {
        return value == null ? null : Verdict.valueOf(value);
    }

    private static String name(Enum<?> value)
    {
        return value == null ? null : value.name();
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Remote runtime store requires a transaction");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public enum InboxKind
    {
        INLINE_COMMENT,
        TOP_LEVEL_COMMENT,
        REVIEW_BODY,
        REVIEW_VERDICT,
        REQUESTED_REVIEW,
        THREAD_RESOLVED,
        THREAD_REOPENED,
        HEAD_CHANGED
    }

    public enum Provenance { EXTERNAL, OWN_REPLY }

    public enum Verdict { APPROVED, CHANGES_REQUESTED, COMMENTED, DISMISSED }

    public enum Mergeability { UNKNOWN, MERGEABLE, CONFLICTING, BLOCKED }

    public enum MergeQueueCapability { UNKNOWN, UNSUPPORTED, SUPPORTED }

    public enum EffectKind
    {
        POST_INLINE_REPLY,
        POST_TOP_LEVEL_REPLY,
        SUBMIT_REVIEW,
        REQUEST_REVIEWER,
        POST_MAINTAINER_NUDGE,
        RESOLVE_THREAD,
        PUSH_COMMITS
    }

    public enum PayloadKind { TEXT, REVIEW, REVIEWER, NUDGE, RESOLUTION, PUSH }

    public enum AuthorityKind { MANUAL, AUTO_APPROVE_POLICY }

    public record InboxItem(
            String id,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String remotePrBindingId,
            String remotePrSnapshotId,
            InboxKind kind,
            String externalKey,
            long externalRevision,
            String headSha,
            String baseSha,
            String actorLogin,
            Provenance provenance,
            boolean ignored,
            String threadId,
            String commentId,
            String reviewId,
            String requestedReviewer,
            String body,
            String bodyDigest,
            Verdict verdict,
            String previousHeadSha,
            String newHeadSha,
            Instant observedAt,
            String rawEvidence)
    {
        public InboxItem
        {
            requireText(id, "id");
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(remotePrBindingId, "remotePrBindingId");
            requireText(remotePrSnapshotId, "remotePrSnapshotId");
            requireNonNull(kind, "kind is null");
            requireText(externalKey, "externalKey");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
            requireNonNull(provenance, "provenance is null");
            requireNonNull(observedAt, "observedAt is null");
            if (taskEpoch < 1 || stageGeneration < 1 || externalRevision < 1) {
                throw new IllegalArgumentException("Remote inbox fence is invalid");
            }
            if (ignored != (provenance == Provenance.OWN_REPLY)) {
                throw new IllegalArgumentException("Only own mirrored replies are ignored");
            }
        }
    }

    public record IngestResult(String itemId, boolean inserted) {}

    public record FrozenItem(
            String id,
            long externalRevision,
            InboxKind kind,
            String body,
            String bodyDigest,
            String externalTarget,
            String externalKey) {}

    public record FrozenBatch(
            String id,
            int sequence,
            String sourceSnapshotId,
            String headSha,
            String baseSha,
            String contentDigest,
            List<FrozenItem> items) {}

    public record AutomationPolicy(
            String id,
            String taskId,
            int revision,
            String source,
            boolean autoApprove,
            boolean autoMerge,
            boolean keepDraft,
            int minimumWriteApprovals,
            int maxMergeQueueReenqueues,
            boolean requireLowRisk,
            boolean requireSmallEffort,
            boolean stewardshipException,
            String createdBy,
            Instant createdAt) {}

    public record ReadinessEvidence(
            String id,
            String snapshotId,
            String ciEvaluationId,
            String automationPolicyId,
            String headSha,
            String baseSha,
            boolean ready) {}

    public record EffectDraft(
            String id,
            EffectKind kind,
            String remoteInboxItemId,
            String externalTarget,
            String reviewAction,
            PayloadKind payloadKind,
            String payload,
            String idempotencyKey,
            int attemptLimit)
    {
        public EffectDraft
        {
            requireText(id, "id");
            requireNonNull(kind, "kind is null");
            requireNonNull(payloadKind, "payloadKind is null");
            requireText(payload, "payload");
            requireText(idempotencyKey, "idempotencyKey");
            if (attemptLimit < 1) {
                throw new IllegalArgumentException("attemptLimit must be positive");
            }
            PayloadKind expected = switch (kind) {
                case POST_INLINE_REPLY, POST_TOP_LEVEL_REPLY -> PayloadKind.TEXT;
                case SUBMIT_REVIEW -> PayloadKind.REVIEW;
                case REQUEST_REVIEWER -> PayloadKind.REVIEWER;
                case POST_MAINTAINER_NUDGE -> PayloadKind.NUDGE;
                case RESOLVE_THREAD -> PayloadKind.RESOLUTION;
                case PUSH_COMMITS -> PayloadKind.PUSH;
            };
            if (payloadKind != expected) {
                throw new IllegalArgumentException("Effect payload kind is inconsistent");
            }
        }
    }

    public record AuthorizedFeedback(
            String authorizationId, String batchId, EffectDispatch firstDispatch) {}

    public record EffectDispatch(
            String effectId,
            String operationId,
            String ticketId,
            EffectKind kind) {}

    public record EffectCompletion(
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String batchId,
            boolean pushed,
            String resultHeadSha,
            String resultSnapshotId) {}

    public record MarkReadyDispatch(
            String authorizationId,
            String markReadyOperationId,
            String operationId,
            String ticketId,
            AuthorityKind authority,
            int policyRevision) {}

    public record EffectDeliveryContext(
            String operationId,
            String effectId,
            String effectStatus,
            String batchId,
            String batchStatus,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String headSha,
            String baseSha,
            boolean current) {}

    public record MarkReadyDeliveryContext(
            String id,
            String operationId,
            String status,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String headSha,
            String baseSha,
            String resultSnapshotId,
            boolean current) {}

    public record RuntimeDeliveryReceipt(
            String operationId,
            String callbackRoute,
            String rawResultDigest,
            String acceptance,
            String evidence,
            Instant recordedAt) {}

    public record RemoteContext(
            String workspaceId,
            String trunkId,
            String taskId,
            long taskEpoch,
            long taskVersion,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String checkpoint,
            String remotePrBindingId,
            String snapshotId,
            long observationRevision,
            String headSha,
            String baseSha,
            String codeFingerprint,
            String worktreePath) {}

    private record ReadinessInputs(
            String ciEvaluationId,
            boolean prOpen,
            boolean nonDraft,
            boolean ciAccepted,
            int writeApprovalCount,
            int changesRequestedCount,
            int unresolvedThreadCount,
            int unresolvedCommentCount,
            int openFeedbackBatchCount,
            int blockingGateCount,
            boolean lowRiskEligible,
            boolean smallEffortEligible,
            Mergeability mergeability,
            MergeQueueCapability mergeQueueCapability,
            Instant observedAt) {}

    private record FeedbackGate(
            String id,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String headSha,
            String baseSha,
            int itemCount,
            String contentDigest,
            String validationEvidenceId,
            String brainEvidenceId) {}

    private record EffectDispatchCandidate(
            String id,
            EffectKind kind,
            int attemptCount,
            String workspaceId,
            String trunkId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String headSha,
            String baseSha) {}

    private record BatchCompletionCandidate(
            String id,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String headSha,
            String baseSha,
            String resultHeadSha,
            String resultSnapshotId) {}

    private record MarkReadyProof(
            String snapshotId, String ciId, String prState, String policyOutcome) {}
}
