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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.OperationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Exact persistence boundary for first-publish base synchronization. */
@Repository
public class SqliteLocalPublishBaseSyncStore
        implements LocalPublishBaseSyncOperationHandler.Store
{
    private final JdbcTemplate jdbc;

    public SqliteLocalPublishBaseSyncStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    /** Opens one exact episode and atomically arms its fetch Operation. */
    public Admission open(OpenRequest request)
    {
        requireTransaction();
        requireNonNull(request, "request is null");
        String episodeId = id("local-publish-base-sync-episode", request.commandId());
        Optional<Episode> duplicate = findEpisode(episodeId);
        if (duplicate.isPresent()) {
            Episode episode = duplicate.orElseThrow();
            requireDuplicate(request, episode);
            return new Admission(episode, requireOperation(episode.id(), Kind.FETCH_COMPARE));
        }

        Source source = requireSource(
                request.sourcePublishOperationId(),
                request.branchSyncPolicyRevisionId());
        int attemptNo = requireInt("""
                SELECT COUNT(*) + 1
                FROM local_publish_base_sync_episode
                WHERE local_development_stage_id = ?
                """, source.stageId());
        jdbc.update("""
                INSERT INTO local_publish_base_sync_episode(
                    id, source_publish_operation_id,
                    local_development_stage_id, task_id, task_epoch,
                    stage_generation, source_code_fingerprint,
                    source_head_sha, source_base_sha, target_base_sha,
                    authority_kind, standing_policy_revision_id, blocker_id,
                    actor, branch_sync_policy_revision_id, command_id,
                    attempt_no, attempt_limit, status, opened_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'FETCHING', ?)
                """, episodeId, source.publishOperationId(), source.stageId(),
                source.taskId(), source.taskEpoch(), source.stageGeneration(),
                source.codeFingerprint(), source.headSha(), source.baseSha(),
                request.targetBaseSha(), request.authorityKind().name(),
                request.standingPolicyRevisionId(), request.blockerId(),
                request.actor(), request.branchSyncPolicyRevisionId(),
                request.commandId(), attemptNo, source.attemptLimit(),
                request.openedAt().toEpochMilli());
        if (request.authorityKind() == AuthorityKind.MANUAL) {
            updateOne("""
                    UPDATE task_blocker
                    SET status = 'RESOLVED', resolved_at_ms = ?,
                        resolution_evidence = 'local publish base sync authorized'
                    WHERE id = ? AND status = 'OPEN'
                    """, "Manual base-sync blocker was not resolved",
                    request.openedAt().toEpochMilli(), request.blockerId());
        }
        Episode episode = findEpisode(episodeId).orElseThrow();
        return new Admission(episode, insertOperation(episode, Kind.FETCH_COMPARE,
                request.openedAt()));
    }

    /** Arms the exact mechanical rebase after an accepted fetch result. */
    public Operation requestRebase(String episodeId, Instant requestedAt)
    {
        requireTransaction();
        requireText(episodeId, "episodeId");
        requireNonNull(requestedAt, "requestedAt is null");
        Optional<Operation> duplicate = findOperation(
                episodeId, Kind.MECHANICAL_REBASE);
        if (duplicate.isPresent()) {
            return duplicate.orElseThrow();
        }
        Episode episode = findEpisode(episodeId).orElseThrow(() ->
                new IllegalArgumentException("No local publish base-sync Episode: "
                        + episodeId));
        return insertOperation(episode, Kind.MECHANICAL_REBASE, requestedAt);
    }

    public Optional<Episode> findEpisode(String episodeId)
    {
        requireText(episodeId, "episodeId");
        return jdbc.query("""
                SELECT id, source_publish_operation_id,
                       local_development_stage_id, task_id, task_epoch,
                       stage_generation, source_code_fingerprint,
                       source_head_sha, source_base_sha, target_base_sha,
                       authority_kind, standing_policy_revision_id, blocker_id,
                       actor, branch_sync_policy_revision_id, command_id,
                       retry_of_episode_id, attempt_no, attempt_limit, status,
                       resume_cursor, opened_at_ms, completed_at_ms,
                       error_message
                FROM local_publish_base_sync_episode WHERE id = ?
                """, (rs, row) -> episode(rs), episodeId).stream().findFirst();
    }

    /** Applies the retry policy only after one exact determinate failure. */
    public FailureResolution settleFailure(String episodeId, Instant at)
    {
        requireTransaction();
        requireText(episodeId, "episodeId");
        requireNonNull(at, "at is null");
        Episode failed = findEpisode(episodeId).orElseThrow(() ->
                new IllegalArgumentException(
                        "No local publish base-sync Episode: " + episodeId));
        if ("EXHAUSTED".equals(failed.status())) {
            return new FailureResolution(
                    failed, null, openFailureBlocker(
                            failed, "LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED", at));
        }
        if (!"FAILED".equals(failed.status())) {
            throw new IllegalStateException(
                    "Local publish base-sync failure is not settleable");
        }
        if (failed.authorityKind() == AuthorityKind.STANDING_TASK_POLICY) {
            Admission retry = openRetry(
                    failed,
                    id("retry-local-publish-base-sync", failed.id()),
                    AuthorityKind.STANDING_TASK_POLICY,
                    failed.standingPolicyRevisionId(), null, null,
                    failed.attemptLimit(), at);
            return new FailureResolution(failed, retry, null);
        }
        return new FailureResolution(
                failed, null, openFailureBlocker(
                        failed, "LOCAL_PUBLISH_BASE_SYNC_RETRY_REQUIRED", at));
    }

    /** User authority retries one failed attempt or extends one exhausted limit. */
    public Admission approveFailureBlocker(
            ManualBlocker blocker, String commandId, String actor, Instant at)
    {
        requireTransaction();
        requireNonNull(blocker, "blocker is null");
        requireText(commandId, "commandId");
        requireText(actor, "actor");
        requireNonNull(at, "at is null");
        Episode predecessor = findEpisode(blocker.subjectRevision())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Base-sync blocker predecessor is missing"));
        boolean extension = "LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED".equals(
                blocker.blockerType());
        if (!extension && !"LOCAL_PUBLISH_BASE_SYNC_RETRY_REQUIRED".equals(
                blocker.blockerType())) {
            throw new IllegalArgumentException(
                    "Base-sync blocker does not authorize a retry");
        }
        int nextLimit = extension
                ? predecessor.attemptLimit() + 1 : predecessor.attemptLimit();
        String retryEpisodeId = id(
                "local-publish-base-sync-episode", commandId);
        Optional<Episode> replay = findEpisode(retryEpisodeId);
        if (replay.isPresent()) {
            Episode episode = replay.orElseThrow();
            return new Admission(
                    episode,
                    requireOperation(episode.id(), Kind.FETCH_COMPARE));
        }
        if (extension) {
            jdbc.update("""
                    INSERT INTO local_publish_base_sync_budget_extension(
                        id, exhausted_episode_id, blocker_id, command_id,
                        task_id, local_development_stage_id, previous_limit,
                        new_limit, actor, retry_episode_id, recorded_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id("local-publish-base-sync-budget-extension", commandId),
                    predecessor.id(), blocker.id(), commandId,
                    predecessor.taskId(), predecessor.stageId(),
                    predecessor.attemptLimit(), nextLimit, actor,
                    retryEpisodeId,
                    at.toEpochMilli());
        }
        return openRetry(
                predecessor, commandId, AuthorityKind.MANUAL,
                null, blocker.id(), actor, nextLimit, at);
    }

    private Admission openRetry(
            Episode predecessor,
            String commandId,
            AuthorityKind authority,
            String standingPolicyRevisionId,
            String blockerId,
            String actor,
            int attemptLimit,
            Instant at)
    {
        String episodeId = id("local-publish-base-sync-episode", commandId);
        Optional<Episode> duplicate = findEpisode(episodeId);
        if (duplicate.isPresent()) {
            Episode replay = duplicate.orElseThrow();
            if (!Objects.equals(replay.retryOfEpisodeId(), predecessor.id())) {
                throw new IllegalArgumentException(
                        "Base-sync retry command was reused");
            }
            return new Admission(
                    replay, requireOperation(replay.id(), Kind.FETCH_COMPARE));
        }
        jdbc.update("""
                INSERT INTO local_publish_base_sync_episode(
                    id, source_publish_operation_id,
                    local_development_stage_id, task_id, task_epoch,
                    stage_generation, source_code_fingerprint,
                    source_head_sha, source_base_sha, target_base_sha,
                    authority_kind, standing_policy_revision_id, blocker_id,
                    actor, branch_sync_policy_revision_id, command_id,
                    retry_of_episode_id, attempt_no, attempt_limit, status,
                    opened_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'FETCHING', ?)
                """, episodeId, predecessor.sourcePublishOperationId(),
                predecessor.stageId(), predecessor.taskId(),
                predecessor.taskEpoch(), predecessor.stageGeneration(),
                predecessor.sourceCodeFingerprint(), predecessor.sourceHeadSha(),
                predecessor.sourceBaseSha(), predecessor.targetBaseSha(),
                authority.name(), standingPolicyRevisionId, blockerId, actor,
                predecessor.branchSyncPolicyRevisionId(), commandId,
                predecessor.id(), predecessor.attemptNo() + 1, attemptLimit,
                at.toEpochMilli());
        if (blockerId != null) {
            resolveBlocker(blockerId, at,
                    "local publish base sync retry authorized");
        }
        Episode retry = findEpisode(episodeId).orElseThrow();
        return new Admission(
                retry, insertOperation(retry, Kind.FETCH_COMPARE, at));
    }

    private ManualBlocker openFailureBlocker(
            Episode episode, String blockerType, Instant at)
    {
        String blockerId = id(blockerType, episode.id());
        jdbc.update("""
                INSERT OR IGNORE INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'STAGE', ?, ?, ?, 'OPEN',
                    json_object(
                        'episodeId', ?,
                        'sourcePublishOperationId', ?,
                        'sourceBaseSha', ?, 'targetBaseSha', ?,
                        'attemptNo', ?, 'attemptLimit', ?), ?)
                """, blockerId, episode.taskId(), episode.stageId(),
                episode.stageId(), episode.id(), blockerType, episode.id(),
                episode.sourcePublishOperationId(), episode.sourceBaseSha(),
                episode.targetBaseSha(), episode.attemptNo(),
                episode.attemptLimit(), at.toEpochMilli());
        return findManualBlocker(blockerId).orElseThrow();
    }

    private void resolveBlocker(
            String blockerId, Instant at, String evidence)
    {
        updateOne("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = ?
                WHERE id = ? AND status = 'OPEN'
                """, "Manual base-sync blocker was not resolved",
                at.toEpochMilli(), evidence, blockerId);
    }

    /** Opens or replays the exact user blocker when standing authority is absent. */
    public ManualBlocker openManualBlocker(
            String publishOperationId, String targetBaseSha, Instant openedAt)
    {
        requireTransaction();
        requireText(publishOperationId, "sourcePublishOperationId");
        requireText(targetBaseSha, "targetBaseSha");
        requireNonNull(openedAt, "openedAt is null");
        String blockerId = id("local-publish-base-sync-blocker", publishOperationId);
        Optional<ManualBlocker> duplicate = findManualBlocker(blockerId);
        if (duplicate.isPresent()) {
            ManualBlocker blocker = duplicate.orElseThrow();
            if (!blocker.sourcePublishOperationId().equals(publishOperationId)
                    || !blocker.targetBaseSha().equals(targetBaseSha)) {
                throw new IllegalStateException(
                        "Local publish base-sync blocker changed on replay");
            }
            return blocker;
        }
        int inserted = jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                SELECT ?, publish.task_id,
                       publish.local_development_stage_id, 'STAGE',
                       publish.local_development_stage_id, publish.id,
                       'LOCAL_PUBLISH_BASE_SYNC_REQUIRED', 'OPEN',
                       json_object(
                           'sourcePublishOperationId', publish.id,
                           'sourceBaseSha', publish.expected_base_sha,
                           'targetBaseSha', ?), ?
                FROM publish_operation publish
                JOIN publish_authorization authorization
                  ON authorization.id = publish.publish_authorization_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = publish.operation_id
                JOIN tasks task ON task.id = publish.task_id
                JOIN stage owner
                  ON owner.id = publish.local_development_stage_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                WHERE publish.id = ?
                  AND publish.status = 'FAILED'
                  AND authorization.revoked_at_ms IS NOT NULL
                  AND authorization.consumed_at_ms IS NULL
                  AND ticket.status = 'RESULT_PENDING'
                  AND ticket.pending_result_outcome = 'FAILED'
                  AND ticket.pending_result_task_epoch = publish.task_epoch
                  AND ticket.pending_result_stage_id =
                      publish.local_development_stage_id
                  AND ticket.pending_result_stage_generation =
                      publish.stage_generation
                  AND ticket.pending_result_operation_id = publish.operation_id
                  AND ticket.pending_result_attempt = publish.semantic_attempt
                  AND ticket.pending_result_expected_code_fingerprint =
                      publish.code_fingerprint
                  AND ticket.pending_result_expected_head_sha =
                      publish.expected_head_sha
                  AND ticket.pending_result_expected_base_sha =
                      publish.expected_base_sha
                  AND json_valid(ticket.pending_result_payload)
                  AND json_extract(ticket.pending_result_payload,
                      '$.version') = 1
                  AND json_extract(ticket.pending_result_payload,
                      '$.publishOperationId') = publish.id
                  AND json_extract(ticket.pending_result_payload,
                      '$.operationId') = publish.operation_id
                  AND json_extract(ticket.pending_result_payload,
                      '$.taskId') = publish.task_id
                  AND json_extract(ticket.pending_result_payload,
                      '$.stageId') = publish.local_development_stage_id
                  AND json_extract(ticket.pending_result_payload,
                      '$.disposition') = 'BASE_MOVED'
                  AND json_extract(ticket.pending_result_payload,
                      '$.observedBaseSha') = ?
                  AND length(trim(json_extract(ticket.pending_result_payload,
                      '$.error'))) > 0
                  AND json_extract(ticket.pending_result_payload, '$.error') =
                      ticket.pending_result_error
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = publish.task_epoch
                  AND current.stage_id = publish.local_development_stage_id
                  AND current.stage_generation = publish.stage_generation
                  AND owner.checkpoint = 'LOCAL_REVIEW'
                  AND owner.completed_at_ms IS NULL
                  AND code.code_fingerprint = publish.code_fingerprint
                  AND code.head_sha = publish.expected_head_sha
                  AND code.base_sha = publish.expected_base_sha
                """, blockerId, targetBaseSha, openedAt.toEpochMilli(),
                publishOperationId, targetBaseSha);
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Manual base-sync blocker lacks its exact base-move subject");
        }
        return findManualBlocker(blockerId).orElseThrow();
    }

    public Optional<ManualBlocker> findManualBlocker(String blockerId)
    {
        requireText(blockerId, "blockerId");
        return jdbc.query("""
                SELECT id, task_id, stage_id, subject_revision, status,
                       blocker_type,
                       json_extract(payload_json, '$.sourcePublishOperationId')
                           AS source_publish_operation_id,
                       json_extract(payload_json, '$.sourceBaseSha') AS source_base_sha,
                       json_extract(payload_json, '$.targetBaseSha') AS target_base_sha,
                       opened_at_ms
                FROM task_blocker
                WHERE id = ?
                  AND owner_kind = 'STAGE'
                  AND blocker_type IN (
                      'LOCAL_PUBLISH_BASE_SYNC_REQUIRED',
                      'LOCAL_PUBLISH_BASE_SYNC_RETRY_REQUIRED',
                      'LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED')
                  AND json_valid(payload_json)
                """, (rs, row) -> new ManualBlocker(
                        rs.getString("id"), rs.getString("task_id"),
                        rs.getString("stage_id"),
                        rs.getString("blocker_type"),
                        rs.getString("subject_revision"),
                        rs.getString("source_publish_operation_id"),
                        rs.getString("source_base_sha"),
                        rs.getString("target_base_sha"),
                        rs.getString("status"), instant(rs, "opened_at_ms")),
                blockerId).stream().findFirst();
    }

    @Override
    public OperationContext requireByOperationId(String operationId)
    {
        requireText(operationId, "operationId");
        List<OperationContext> rows = jdbc.query("""
                SELECT operation.operation_id, operation.kind,
                       operation.status AS operation_status,
                       trunk.workspace_id, task.thread_id AS trunk_id,
                       episode.task_id, episode.task_epoch,
                       episode.local_development_stage_id AS stage_id,
                       episode.stage_generation, operation.semantic_attempt,
                       task.workflow_version,
                       task.lifecycle_state AS task_lifecycle,
                       task.epoch AS current_task_epoch,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.checkpoint AS stage_checkpoint,
                       identity.repository_id, identity.branch_name,
                       identity.worktree_path,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       operation.target_base_sha
                FROM local_publish_base_sync_operation operation
                JOIN local_publish_base_sync_episode episode
                  ON episode.id = operation.episode_id
                JOIN tasks task ON task.id = episode.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner
                  ON owner.id = episode.local_development_stage_id
                JOIN task_code_identity identity ON identity.task_id = task.id
                WHERE operation.operation_id = ?
                """, (rs, row) -> new OperationContext(
                        rs.getString("operation_id"),
                        operationKind(Kind.valueOf(rs.getString("kind"))),
                        rs.getString("operation_status"),
                        rs.getString("workspace_id"), rs.getString("trunk_id"),
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("workflow_version"),
                        rs.getString("task_lifecycle"),
                        rs.getLong("current_task_epoch"),
                        rs.getString("current_stage_id"),
                        rs.getLong("current_stage_generation"),
                        rs.getString("stage_checkpoint"),
                        rs.getString("repository_id"),
                        rs.getString("branch_name"),
                        rs.getString("worktree_path"),
                        rs.getString("expected_code_fingerprint"),
                        rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha"),
                        rs.getString("target_base_sha")), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one local publish base-sync Operation context, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    /** Works after terminal delivery, when the dispatch context is no longer live. */
    public String requireTaskId(String operationId)
    {
        requireText(operationId, "operationId");
        List<String> rows = jdbc.query("""
                SELECT episode.task_id
                FROM local_publish_base_sync_operation operation
                JOIN local_publish_base_sync_episode episode
                  ON episode.id = operation.episode_id
                WHERE operation.operation_id = ?
                """, (rs, row) -> rs.getString("task_id"), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one local publish base-sync Task owner, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    /** Frozen launch context for the semantic BASE_SYNC StageTurn bridge. */
    public TurnContext requireTurnContext(String episodeId)
    {
        requireText(episodeId, "episodeId");
        List<TurnContext> rows = jdbc.query("""
                SELECT episode.id AS episode_id, episode.task_id,
                       episode.task_epoch,
                       episode.local_development_stage_id AS stage_id,
                       episode.stage_generation, episode.target_base_sha,
                       episode.status AS episode_status,
                       rebase.result_disposition,
                       rebase.result_evidence_json,
                       owner.version AS stage_version, owner.checkpoint,
                       owner.reasoning_effort, task.name AS task_name,
                       task.thread_id AS trunk_id, trunk.workspace_id,
                       identity.worktree_path, identity.branch_name,
                       pull_request.base_branch,
                       creation.engine_snapshot, creation.work_model_snapshot,
                       brain.provider, brain.model, brain.role_skill,
                       predecessor.stage_turn_id AS predecessor_stage_turn_id,
                       CASE rebase.result_disposition
                           WHEN 'REBASED' THEN rebase.result_code_fingerprint
                           ELSE episode.source_code_fingerprint END
                           AS code_fingerprint,
                       CASE rebase.result_disposition
                           WHEN 'REBASED' THEN rebase.result_head_sha
                           ELSE episode.source_head_sha END AS head_sha,
                       CASE rebase.result_disposition
                           WHEN 'REBASED' THEN rebase.result_base_sha
                           ELSE episode.source_base_sha END AS base_sha
                FROM local_publish_base_sync_episode episode
                JOIN local_publish_base_sync_operation rebase
                  ON rebase.id = (
                      SELECT candidate.id
                      FROM local_publish_base_sync_operation candidate
                      WHERE candidate.episode_id = episode.id
                        AND candidate.kind = 'MECHANICAL_REBASE'
                        AND candidate.status = 'SUCCEEDED'
                      ORDER BY candidate.generation DESC
                      LIMIT 1)
                JOIN local_publish_base_sync_delivery_receipt receipt
                  ON receipt.operation_row_id = rebase.id
                JOIN tasks task ON task.id = episode.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN stage owner
                  ON owner.id = episode.local_development_stage_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context creation ON creation.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN pr pull_request
                  ON pull_request.task_id = task.id AND pull_request.origin = 'task'
                LEFT JOIN dev_report predecessor ON predecessor.id = (
                    SELECT candidate.id FROM dev_report candidate
                    WHERE candidate.task_id = task.id
                      AND candidate.local_development_stage_id = owner.id
                    ORDER BY candidate.created_at_ms DESC, candidate.id DESC
                    LIMIT 1)
                WHERE episode.id = ?
                  AND episode.status IN ('RECONCILING', 'HANDED_OFF')
                  AND rebase.status = 'SUCCEEDED'
                  AND rebase.result_disposition IN ('REBASED', 'CONFLICT')
                  AND receipt.acceptance IN ('ACCEPTED', 'PARKED')
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = episode.task_epoch
                  AND current.stage_id = owner.id
                  AND current.stage_generation = owner.generation
                  AND owner.completed_at_ms IS NULL
                  AND owner.checkpoint = 'LOCAL_REVIEW'
                  AND code.code_fingerprint = CASE rebase.result_disposition
                      WHEN 'REBASED' THEN rebase.result_code_fingerprint
                      ELSE episode.source_code_fingerprint END
                  AND code.head_sha = CASE rebase.result_disposition
                      WHEN 'REBASED' THEN rebase.result_head_sha
                      ELSE episode.source_head_sha END
                  AND code.base_sha = CASE rebase.result_disposition
                      WHEN 'REBASED' THEN rebase.result_base_sha
                      ELSE episode.source_base_sha END
                """, (rs, row) -> turnContext(rs), episodeId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact BASE_SYNC Turn context, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    /** Persists the queued BASE_SYNC Turn, subtype request, and exact ticket. */
    public void insertBaseSyncTurn(BaseSyncTurn turn)
    {
        requireTransaction();
        requireNonNull(turn, "turn is null");
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, 'BASE_SYNC', 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turn.turnId(), turn.stageId(), turn.stageGeneration(),
                turn.operationId(), turn.attempt(), turn.taskEpoch(),
                turn.codeFingerprint(), turn.headSha(), turn.baseSha(),
                turn.deliveryLane(), turn.launchInput(),
                turn.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, predecessor_turn_id,
                    brain_review_episode_id, local_feedback_batch_id,
                    base_sync_episode_id, target_base_sha, prompt_digest,
                    requested_by, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'BASE_SYNC', 'IMMEDIATE',
                    NULL, NULL, NULL, ?, ?, ?, ?, ?)
                """, turn.requestId(), turn.commandId(), turn.turnId(),
                turn.taskId(), turn.stageId(), turn.taskEpoch(),
                turn.stageGeneration(), turn.episodeId(), turn.targetBaseSha(),
                turn.promptDigest(), turn.requestedBy(),
                turn.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_STAGE_TURN', 'AGENT_TURN',
                    'STAGE_TURN', ?, 'STAGE_TURN_RESULT', ?, 0, 1, 1,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, turn.ticketId(), turn.operationId(), turn.turnId(),
                turn.laneMask(), turn.workspaceId(), turn.trunkId(), turn.taskId(),
                turn.taskEpoch(), turn.stageId(), turn.stageGeneration(),
                turn.attempt(), turn.codeFingerprint(), turn.headSha(),
                turn.baseSha(), turn.requestedAt().toEpochMilli());
    }

    /** Reads the receipt written solely by {@link V2StageStore}. */
    public Optional<StartReceiptEvidence> findStartReceipt(String episodeId)
    {
        requireText(episodeId, "episodeId");
        return jdbc.query("""
                SELECT id, episode_id, stage_turn_request_id, command_id,
                       actor, task_id,
                       local_development_stage_id AS stage_id,
                       task_epoch, stage_generation, expected_stage_version,
                       returned_stage_version, operation_id, semantic_attempt,
                       expected_code_fingerprint, expected_head_sha,
                       expected_base_sha, target_base_sha, recorded_at_ms
                FROM local_publish_base_sync_start_receipt
                WHERE episode_id = ?
                """, (rs, row) -> startReceipt(rs), episodeId)
                .stream().findFirst();
    }

    /** Verifies the Stage-owned receipt, then closes Episode ownership. */
    public StartReceiptEvidence completeHandoff(
            String episodeId, Instant completedAt)
    {
        requireTransaction();
        requireText(episodeId, "episodeId");
        requireNonNull(completedAt, "completedAt is null");
        StartReceiptEvidence receipt = findStartReceipt(episodeId).orElseThrow(() ->
                new IllegalStateException(
                        "Local publish base-sync Stage receipt is missing"));
        Episode episode = findEpisode(episodeId).orElseThrow();
        if ("HANDED_OFF".equals(episode.status())) {
            return receipt;
        }
        updateOne("""
                UPDATE local_publish_base_sync_episode
                SET status = 'HANDED_OFF', completed_at_ms = ?
                WHERE id = ? AND status = 'RECONCILING'
                """, "Local publish base-sync Episode is not ready to hand off",
                completedAt.toEpochMilli(), episodeId);
        return receipt;
    }

    public Optional<Operation> findOperation(String episodeId, Kind kind)
    {
        requireText(episodeId, "episodeId");
        requireNonNull(kind, "kind is null");
        return jdbc.query("""
                SELECT id, episode_id, kind, generation, operation_id,
                       semantic_attempt,
                       expected_code_fingerprint, expected_head_sha,
                       expected_base_sha, target_base_sha, status,
                       result_disposition, result_code_fingerprint,
                       result_head_sha, result_base_sha, result_evidence_json,
                       requested_at_ms, completed_at_ms, error_message
                FROM local_publish_base_sync_operation
                WHERE episode_id = ? AND kind = ?
                ORDER BY generation DESC
                LIMIT 1
                """, (rs, row) -> operation(rs), episodeId, kind.name())
                .stream().findFirst();
    }

    public Delivery requireDelivery(String operationId)
    {
        requireText(operationId, "operationId");
        List<Delivery> rows = jdbc.query("""
                SELECT operation.id AS operation_row_id,
                       operation.operation_id, operation.episode_id,
                       operation.kind, operation.generation,
                       operation.semantic_attempt,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       operation.target_base_sha,
                       episode.task_id, episode.task_epoch,
                       episode.local_development_stage_id AS stage_id,
                       episode.stage_generation, episode.attempt_no,
                       episode.attempt_limit, episode.authority_kind,
                       episode.status AS episode_status,
                       task.lifecycle_state, task.epoch AS current_task_epoch,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.checkpoint, owner.completed_at_ms,
                       code.code_fingerprint AS current_code_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha
                FROM local_publish_base_sync_operation operation
                JOIN local_publish_base_sync_episode episode
                  ON episode.id = operation.episode_id
                JOIN tasks task ON task.id = episode.task_id
                JOIN stage owner
                  ON owner.id = episode.local_development_stage_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> delivery(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one local publish base-sync delivery, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    /** Finds control-path episodes whose dispatcher work is already stopped. */
    public List<ControlSettlement> pendingControlSettlements(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT episode.id AS episode_id, episode.task_id,
                       episode.status AS episode_status,
                       episode.resume_cursor, task.lifecycle_state,
                       operation.id AS operation_row_id,
                       operation.operation_id, operation.kind,
                       operation.generation, operation.status AS operation_status,
                       ticket.status AS ticket_status
                FROM local_publish_base_sync_episode episode
                JOIN tasks task ON task.id = episode.task_id
                LEFT JOIN local_publish_base_sync_operation operation
                  ON operation.id = (
                      SELECT candidate.id
                      FROM local_publish_base_sync_operation candidate
                      WHERE candidate.episode_id = episode.id
                        AND candidate.kind = CASE episode.status
                            WHEN 'FETCHING' THEN 'FETCH_COMPARE'
                            WHEN 'REBASING' THEN 'MECHANICAL_REBASE'
                            ELSE candidate.kind END
                      ORDER BY candidate.requested_at_ms DESC,
                               candidate.generation DESC
                      LIMIT 1)
                LEFT JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE episode.status IN (
                          'FETCHING', 'REBASING', 'PAUSED', 'EXHAUSTED')
                  AND task.lifecycle_state IN (
                          'PAUSING', 'PAUSED', 'RESUMING',
                          'CANCELING', 'CLEANING', 'CANCELED')
                  AND ((episode.status IN ('PAUSED', 'EXHAUSTED')
                        AND task.lifecycle_state IN (
                            'CANCELING', 'CLEANING', 'CANCELED'))
                    OR (operation.status = 'DISPATCHED'
                        AND ticket.status = 'CANCELED'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM local_publish_base_sync_delivery_receipt receipt
                            WHERE receipt.operation_row_id = operation.id)))
                ORDER BY episode.opened_at_ms, episode.id
                LIMIT ?
                """, (rs, row) -> new ControlSettlement(
                        rs.getString("episode_id"), rs.getString("task_id"),
                        rs.getString("episode_status"),
                        nullableEnum(rs, "resume_cursor", ResumeCursor.class),
                        rs.getString("lifecycle_state"),
                        rs.getString("operation_row_id"),
                        rs.getString("operation_id"),
                        nullableEnum(rs, "kind", Kind.class),
                        rs.getInt("generation"),
                        rs.getString("operation_status"),
                        rs.getString("ticket_status")), limit);
    }

    /** Parks pause cancellation, or terminalizes cancel after exact stop proof. */
    public void settleControl(ControlSettlement settlement, Instant at)
    {
        requireTransaction();
        requireNonNull(settlement, "settlement is null");
        requireNonNull(at, "at is null");
        ControlSettlement exact = pendingControlSettlements(128).stream()
                .filter(candidate -> candidate.episodeId().equals(
                        settlement.episodeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Base-sync control settlement is no longer exact"));
        if (!exact.equals(settlement)) {
            throw new IllegalStateException(
                    "Base-sync control settlement changed");
        }
        boolean canceling = switch (settlement.taskLifecycle()) {
            case "CANCELING", "CLEANING", "CANCELED" -> true;
            default -> false;
        };
        if (settlement.operationId() != null
                && "DISPATCHED".equals(settlement.operationStatus())) {
            updateOne("""
                    UPDATE local_publish_base_sync_operation
                    SET status = 'CANCELED', completed_at_ms = ?,
                        error_message = ?
                    WHERE id = ? AND status = 'DISPATCHED'
                    """, "Stopped base-sync Operation changed",
                    at.toEpochMilli(),
                    canceling ? "task cancellation stopped the operation"
                            : "task pause stopped the operation",
                    settlement.operationRowId());
        }
        if (canceling) {
            jdbc.update("""
                    INSERT INTO local_publish_base_sync_cancel_receipt(
                        id, episode_id, operation_id, prior_episode_status,
                        task_lifecycle, recorded_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id("local-publish-base-sync-cancel",
                            settlement.episodeId()),
                    settlement.episodeId(), settlement.operationId(),
                    settlement.episodeStatus(), settlement.taskLifecycle(),
                    at.toEpochMilli());
            updateOne("""
                    UPDATE local_publish_base_sync_episode
                    SET status = 'CANCELED', resume_cursor = NULL,
                        completed_at_ms = ?, error_message = ?
                    WHERE id = ? AND status IN (
                        'FETCHING', 'REBASING', 'PAUSED', 'EXHAUSTED')
                    """, "Canceled base-sync Episode changed",
                    at.toEpochMilli(),
                    "task cancellation discarded local base-sync work",
                    settlement.episodeId());
            return;
        }
        ResumeCursor cursor = settlement.kind() == Kind.FETCH_COMPARE
                ? ResumeCursor.FETCH : ResumeCursor.REBASE;
        Delivery delivery = deliveryForSettlement(settlement);
        insertPauseReceipt(
                delivery, cursor, "CANCELED_BEFORE_START", null, null, at);
        updateOne("""
                UPDATE local_publish_base_sync_episode
                SET status = 'PAUSED', resume_cursor = ?,
                    completed_at_ms = NULL, error_message = NULL
                WHERE id = ? AND status = ?
                """, "Paused base-sync Episode changed", cursor.name(),
                settlement.episodeId(), settlement.episodeStatus());
    }

    private Delivery deliveryForSettlement(ControlSettlement settlement)
    {
        Episode episode = findEpisode(settlement.episodeId()).orElseThrow();
        Operation operation = findOperation(
                episode.id(), settlement.kind()).orElseThrow();
        return new Delivery(
                operation.id(), operation.operationId(), episode.id(),
                operation.kind(), operation.generation(), episode.taskId(),
                episode.taskEpoch(), episode.stageId(), episode.stageGeneration(),
                episode.attemptNo(), episode.attemptLimit(),
                episode.authorityKind(), operation.semanticAttempt(),
                operation.expectedCodeFingerprint(), operation.expectedHeadSha(),
                operation.expectedBaseSha(), operation.targetBaseSha(),
                settlement.episodeStatus(), settlement.taskLifecycle(),
                DeliveryState.PAUSING);
    }

    /** Promotes exactly one parked cursor after the Task is ACTIVE again. */
    public Optional<ResumeSettlement> resumePaused(
            String handoffId,
            String taskId,
            String stageId,
            long taskEpoch,
            long stageGeneration,
            Instant at)
    {
        requireTransaction();
        requireText(handoffId, "handoffId");
        requireText(taskId, "taskId");
        requireText(stageId, "stageId");
        requireNonNull(at, "at is null");
        List<Episode> rows = jdbc.query("""
                SELECT episode.id, episode.source_publish_operation_id,
                       episode.local_development_stage_id, episode.task_id,
                       episode.task_epoch, episode.stage_generation,
                       episode.source_code_fingerprint,
                       episode.source_head_sha, episode.source_base_sha,
                       episode.target_base_sha, episode.authority_kind,
                       episode.standing_policy_revision_id, episode.blocker_id,
                       episode.actor, episode.branch_sync_policy_revision_id,
                       episode.command_id, episode.retry_of_episode_id,
                       episode.attempt_no, episode.attempt_limit,
                       episode.status, episode.resume_cursor,
                       episode.opened_at_ms, episode.completed_at_ms,
                       episode.error_message
                FROM local_publish_base_sync_episode episode
                JOIN tasks task ON task.id = episode.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                WHERE episode.task_id = ?
                  AND episode.local_development_stage_id = ?
                  AND episode.task_epoch = ?
                  AND episode.stage_generation = ?
                  AND episode.status = 'PAUSED'
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = episode.task_epoch
                  AND current.stage_id = episode.local_development_stage_id
                  AND current.stage_generation = episode.stage_generation
                  AND owner.checkpoint = 'LOCAL_REVIEW'
                  AND owner.completed_at_ms IS NULL
                """, (rs, row) -> episode(rs),
                taskId, stageId, taskEpoch, stageGeneration);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one parked local publish base-sync Episode");
        }
        Episode episode = rows.getFirst();
        PauseEvidence pause = requirePauseEvidence(episode.id());
        Operation parked = findOperation(
                episode.id(), pause.operationKind()).orElseThrow();
        ResumeDisposition disposition;
        Operation successor = null;
        FailureResolution failure = null;
        if (parked.status().equals("FAILED")) {
            String terminal = episode.attemptNo() >= episode.attemptLimit()
                    ? "EXHAUSTED" : "FAILED";
            updateOne("""
                    UPDATE local_publish_base_sync_episode
                    SET status = ?, resume_cursor = NULL,
                        completed_at_ms = ?, error_message = ?
                    WHERE id = ? AND status = 'PAUSED'
                    """, "Parked base-sync failure changed", terminal,
                    at.toEpochMilli(), parked.errorMessage(), episode.id());
            disposition = ResumeDisposition.FAILURE;
        }
        else if (episode.resumeCursor() == ResumeCursor.HANDOFF) {
            updateOne("""
                    UPDATE local_publish_base_sync_episode
                    SET status = 'RECONCILING', resume_cursor = NULL
                    WHERE id = ? AND status = 'PAUSED'
                    """, "Parked base-sync handoff changed", episode.id());
            disposition = ResumeDisposition.HANDOFF;
        }
        else {
            Kind nextKind = episode.resumeCursor() == ResumeCursor.FETCH
                    ? Kind.FETCH_COMPARE : Kind.MECHANICAL_REBASE;
            String nextStatus = nextKind == Kind.FETCH_COMPARE
                    ? "FETCHING" : "REBASING";
            updateOne("""
                    UPDATE local_publish_base_sync_episode
                    SET status = ?, resume_cursor = NULL
                    WHERE id = ? AND status = 'PAUSED'
                    """, "Parked base-sync cursor changed",
                    nextStatus, episode.id());
            Episode resumed = findEpisode(episode.id()).orElseThrow();
            successor = insertOperation(resumed, nextKind, at);
            disposition = ResumeDisposition.OPERATION;
        }
        jdbc.update("""
                INSERT INTO local_publish_base_sync_resume_receipt(
                    id, episode_id, pause_receipt_id, handoff_id,
                    resume_cursor, disposition, successor_operation_id,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id("local-publish-base-sync-resume", handoffId),
                episode.id(), pause.id(), handoffId,
                episode.resumeCursor().name(), disposition.name(),
                successor == null ? null : successor.operationId(),
                at.toEpochMilli());
        if (disposition == ResumeDisposition.FAILURE) {
            failure = settleFailure(episode.id(), at);
        }
        return Optional.of(new ResumeSettlement(
                findEpisode(episode.id()).orElseThrow(), disposition,
                successor, failure));
    }

    private PauseEvidence requirePauseEvidence(String episodeId)
    {
        List<PauseEvidence> rows = jdbc.query("""
                SELECT pause.id, pause.operation_id, operation.kind,
                       pause.resume_cursor, pause.raw_outcome
                FROM local_publish_base_sync_pause_receipt pause
                JOIN local_publish_base_sync_operation operation
                  ON operation.id = pause.operation_row_id
                WHERE pause.episode_id = ?
                ORDER BY pause.recorded_at_ms DESC, pause.id DESC
                LIMIT 1
                """, (rs, row) -> new PauseEvidence(
                        rs.getString("id"), rs.getString("operation_id"),
                        Kind.valueOf(rs.getString("kind")),
                        ResumeCursor.valueOf(rs.getString("resume_cursor")),
                        nullableEnum(rs, "raw_outcome",
                                DispatchTicket.Outcome.class)), episodeId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Parked base-sync Episode lacks its pause receipt");
        }
        return rows.getFirst();
    }

    public Optional<DeliveryReceipt> findReceipt(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT operation_row_id, operation_id, raw_outcome,
                       raw_result_digest, acceptance, recorded_at_ms
                FROM local_publish_base_sync_delivery_receipt
                WHERE operation_id = ?
                """, (rs, row) -> receipt(rs), operationId).stream().findFirst();
    }

    /** Records one terminal raw result and advances only its owning Episode. */
    public DeliveryReceipt finish(
            Delivery delivery,
            DispatchTicket.Outcome rawOutcome,
            String rawResultDigest,
            DispatchTicket.Acceptance acceptance,
            Result result,
            Instant completedAt)
    {
        return finishClassified(delivery, rawOutcome, rawResultDigest,
                acceptance == DispatchTicket.Acceptance.ACCEPTED
                        ? DeliveryAcceptance.ACCEPTED
                        : DeliveryAcceptance.SUPERSEDED,
                result, completedAt);
    }

    /** Records one terminal raw result and advances only its owning Episode. */
    public DeliveryReceipt finishClassified(
            Delivery delivery,
            DispatchTicket.Outcome rawOutcome,
            String rawResultDigest,
            DeliveryAcceptance acceptance,
            Result result,
            Instant completedAt)
    {
        requireTransaction();
        requireNonNull(delivery, "delivery is null");
        requireNonNull(rawOutcome, "rawOutcome is null");
        requireDigest(rawResultDigest);
        requireNonNull(acceptance, "acceptance is null");
        requireNonNull(completedAt, "completedAt is null");
        Optional<DeliveryReceipt> duplicate = findReceipt(delivery.operationId());
        if (duplicate.isPresent()) {
            DeliveryReceipt receipt = duplicate.orElseThrow();
            if (receipt.rawOutcome() != rawOutcome
                    || !receipt.rawResultDigest().equals(rawResultDigest)) {
                throw new IllegalStateException(
                        "Local publish base-sync result changed on redelivery");
            }
            return receipt;
        }

        Delivery current = requireDelivery(delivery.operationId());
        if (!current.equals(delivery)) {
            throw new IllegalStateException(
                    "Local publish base-sync delivery context changed");
        }
        DeliveryAcceptance expectedAcceptance = switch (current.state()) {
            case ACTIVE -> DeliveryAcceptance.ACCEPTED;
            case PAUSING -> DeliveryAcceptance.PARKED;
            case CANCELING, STALE -> DeliveryAcceptance.SUPERSEDED;
        };
        if (acceptance != expectedAcceptance) {
            throw new IllegalArgumentException(
                    "Local publish base-sync acceptance does not match current state");
        }
        Terminal terminal = terminal(
                delivery.kind(), rawOutcome, acceptance, result);
        updateOne("""
                UPDATE local_publish_base_sync_operation
                SET status = ?, result_disposition = ?,
                    result_code_fingerprint = ?, result_head_sha = ?,
                    result_base_sha = ?, result_evidence_json = ?,
                    completed_at_ms = ?, error_message = ?
                WHERE id = ? AND status = 'DISPATCHED'
                """, "Local publish base-sync Operation changed before delivery",
                terminal.status(), terminal.disposition(),
                terminal.codeFingerprint(), terminal.headSha(),
                terminal.baseSha(), terminal.evidenceJson(),
                completedAt.toEpochMilli(), terminal.error(),
                delivery.operationRowId());
        jdbc.update("""
                INSERT INTO local_publish_base_sync_delivery_receipt(
                    operation_row_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, delivery.operationRowId(), delivery.operationId(),
                rawOutcome.name(), rawResultDigest, acceptance.name(),
                completedAt.toEpochMilli());

        String nextEpisodeStatus;
        String episodeError = null;
        ResumeCursor resumeCursor = null;
        if (acceptance == DeliveryAcceptance.PARKED) {
            nextEpisodeStatus = "PAUSED";
            resumeCursor = rawOutcome == DispatchTicket.Outcome.SUCCEEDED
                    ? delivery.kind() == Kind.FETCH_COMPARE
                            ? ResumeCursor.REBASE : ResumeCursor.HANDOFF
                    : delivery.kind() == Kind.FETCH_COMPARE
                            ? ResumeCursor.FETCH : ResumeCursor.REBASE;
        }
        else if (acceptance == DeliveryAcceptance.ACCEPTED
                && "SUCCEEDED".equals(terminal.status())) {
            nextEpisodeStatus = delivery.kind() == Kind.FETCH_COMPARE
                    ? "REBASING" : "RECONCILING";
        }
        else {
            nextEpisodeStatus = acceptance == DeliveryAcceptance.SUPERSEDED
                    ? delivery.state() == DeliveryState.CANCELING
                            ? "CANCELED" : "SUPERSEDED"
                    : rawOutcome == DispatchTicket.Outcome.CANCELED
                            ? "CANCELED"
                            : delivery.attemptNo() >= delivery.attemptLimit()
                                    ? "EXHAUSTED" : "FAILED";
            episodeError = terminal.error();
        }
        if (episodeError == null && ("CANCELED".equals(nextEpisodeStatus)
                || "SUPERSEDED".equals(nextEpisodeStatus))) {
            episodeError = "local publish base-sync result was not accepted";
        }
        if (acceptance == DeliveryAcceptance.PARKED) {
            insertPauseReceipt(
                    delivery, resumeCursor, "DELIVERED", rawOutcome,
                    rawResultDigest, completedAt);
        }
        if (acceptance == DeliveryAcceptance.SUPERSEDED
                && delivery.state() == DeliveryState.CANCELING) {
            jdbc.update("""
                    INSERT INTO local_publish_base_sync_cancel_receipt(
                        id, episode_id, operation_id, prior_episode_status,
                        task_lifecycle, recorded_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id("local-publish-base-sync-cancel",
                            delivery.episodeId()),
                    delivery.episodeId(), delivery.operationId(),
                    delivery.episodeStatus(), delivery.taskLifecycle(),
                    completedAt.toEpochMilli());
        }
        updateOne("""
                UPDATE local_publish_base_sync_episode
                SET status = ?, resume_cursor = ?, completed_at_ms = ?,
                    error_message = ?
                WHERE id = ? AND status = ?
                """, "Local publish base-sync Episode cursor changed before delivery",
                nextEpisodeStatus, resumeCursor == null ? null : resumeCursor.name(),
                isTerminal(nextEpisodeStatus) ? completedAt.toEpochMilli() : null,
                episodeError, delivery.episodeId(),
                delivery.kind() == Kind.FETCH_COMPARE ? "FETCHING" : "REBASING");
        return findReceipt(delivery.operationId()).orElseThrow();
    }

    private void insertPauseReceipt(
            Delivery delivery,
            ResumeCursor cursor,
            String settlementKind,
            DispatchTicket.Outcome rawOutcome,
            String rawDigest,
            Instant at)
    {
        jdbc.update("""
                INSERT INTO local_publish_base_sync_pause_receipt(
                    id, episode_id, operation_row_id, operation_id,
                    operation_generation, prior_episode_status,
                    resume_cursor, settlement_kind, raw_outcome,
                    raw_result_digest, task_lifecycle, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id("local-publish-base-sync-pause", delivery.operationId()),
                delivery.episodeId(), delivery.operationRowId(),
                delivery.operationId(), delivery.operationGeneration(),
                delivery.episodeStatus(), cursor.name(), settlementKind,
                rawOutcome == null ? null : rawOutcome.name(), rawDigest,
                delivery.taskLifecycle(), at.toEpochMilli());
    }

    private Operation insertOperation(Episode episode, Kind kind, Instant at)
    {
        int generation = requireInt("""
                SELECT COALESCE(MAX(generation), 0) + 1
                FROM local_publish_base_sync_operation
                WHERE episode_id = ? AND kind = ?
                """, episode.id(), kind.name());
        String suffix = episode.id() + ":" + kind.name() + ":" + generation;
        String rowId = id("local-publish-base-sync-operation-row", suffix);
        String operationId = id("local-publish-base-sync-operation", suffix);
        String ticketId = id("local-publish-base-sync-ticket", suffix);
        jdbc.update("""
                INSERT INTO local_publish_base_sync_operation(
                    id, episode_id, kind, generation, operation_id,
                    semantic_attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, target_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, rowId, episode.id(), kind.name(), generation, operationId,
                episode.attemptNo(), episode.sourceCodeFingerprint(),
                episode.sourceHeadSha(), episode.sourceBaseSha(),
                episode.targetBaseSha(), at.toEpochMilli());
        Source source = requireSource(
                episode.sourcePublishOperationId(),
                episode.branchSyncPolicyRevisionId());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, ?, 'LOCAL_GIT', 'STAGE', ?, ?, 16, 0, 1, 1,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, operationKind(kind),
                source.stageId(), callback(kind), source.workspaceId(),
                source.trunkId(), source.taskId(), source.taskEpoch(),
                source.stageId(), source.stageGeneration(), episode.attemptNo(),
                episode.sourceCodeFingerprint(), episode.sourceHeadSha(),
                episode.sourceBaseSha(), at.toEpochMilli());
        updateOne("""
                UPDATE local_publish_base_sync_operation
                SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "Local publish base-sync Operation was not dispatched", rowId);
        return requireOperation(episode.id(), kind);
    }

    private Source requireSource(String publishOperationId, String policyId)
    {
        requireText(publishOperationId, "sourcePublishOperationId");
        requireText(policyId, "branchSyncPolicyRevisionId");
        List<Source> rows = jdbc.query("""
                SELECT publish.id AS publish_operation_id,
                       publish.task_id, publish.task_epoch,
                       publish.local_development_stage_id AS stage_id,
                       publish.stage_generation, publish.code_fingerprint,
                       publish.expected_head_sha, publish.expected_base_sha,
                       task.thread_id AS trunk_id, trunk.workspace_id,
                       policy.attempt_limit
                FROM publish_operation publish
                JOIN tasks task ON task.id = publish.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_branch_sync_policy_revision policy
                  ON policy.task_id = task.id
                WHERE publish.id = ? AND policy.id = ?
                """, (rs, row) -> new Source(
                        rs.getString("publish_operation_id"),
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getString("code_fingerprint"),
                        rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha"),
                        rs.getString("trunk_id"), rs.getString("workspace_id"),
                        rs.getInt("attempt_limit")), publishOperationId, policyId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException(
                    "No exact failed publish and branch-sync policy");
        }
        return rows.getFirst();
    }

    private Operation requireOperation(String episodeId, Kind kind)
    {
        return findOperation(episodeId, kind).orElseThrow(() ->
                new IllegalStateException("Local publish base-sync Operation is missing"));
    }

    private static Terminal terminal(
            Kind kind,
            DispatchTicket.Outcome rawOutcome,
            DeliveryAcceptance acceptance,
            Result result)
    {
        if (acceptance == DeliveryAcceptance.SUPERSEDED) {
            return Terminal.error("SUPERSEDED", "local publish base-sync subject changed");
        }
        if (acceptance != DeliveryAcceptance.ACCEPTED
                && acceptance != DeliveryAcceptance.PARKED) {
            throw new IllegalArgumentException(
                    "Unsupported local publish base-sync acceptance");
        }
        if (rawOutcome == DispatchTicket.Outcome.SUCCEEDED) {
            requireNonNull(result, "successful result is null");
            if (result.disposition() == ResultDisposition.FAILED
                    || result.error() != null) {
                throw new IllegalArgumentException("Successful result has failure fields");
            }
            if ((kind == Kind.FETCH_COMPARE
                        && result.disposition() != ResultDisposition.FETCHED)
                    || (kind == Kind.MECHANICAL_REBASE
                        && result.disposition() != ResultDisposition.REBASED
                        && result.disposition() != ResultDisposition.CONFLICT)) {
                throw new IllegalArgumentException(
                        "Successful result disposition does not match its Operation");
            }
            requireText(result.codeFingerprint(), "result.codeFingerprint");
            requireText(result.headSha(), "result.headSha");
            requireText(result.baseSha(), "result.baseSha");
            requireJson(result.evidenceJson());
            return new Terminal("SUCCEEDED", result.disposition().name(),
                    result.codeFingerprint(), result.headSha(), result.baseSha(),
                    result.evidenceJson(), null);
        }
        String error = result == null ? "local publish base-sync operation failed"
                : result.error();
        requireText(error, "result.error");
        if (rawOutcome == DispatchTicket.Outcome.FAILED) {
            if (result == null || result.disposition() != ResultDisposition.FAILED) {
                throw new IllegalArgumentException("Failed result lacks typed disposition");
            }
            return Terminal.error("FAILED", error);
        }
        return Terminal.error(rawOutcome.name(), error);
    }

    private static Delivery delivery(ResultSet rs)
            throws SQLException
    {
        Kind kind = Kind.valueOf(rs.getString("kind"));
        boolean episodeCurrent = kind == Kind.FETCH_COMPARE
                ? "FETCHING".equals(rs.getString("episode_status"))
                : "REBASING".equals(rs.getString("episode_status"));
        String lifecycle = rs.getString("lifecycle_state");
        boolean exactOwner = episodeCurrent
                && rs.getLong("task_epoch") == rs.getLong("current_task_epoch")
                && Objects.equals(
                        rs.getString("stage_id"), rs.getString("current_stage_id"))
                && rs.getLong("stage_generation")
                        == rs.getLong("current_stage_generation")
                && "LOCAL_REVIEW".equals(rs.getString("checkpoint"))
                && rs.getObject("completed_at_ms") == null
                && rs.getString("expected_code_fingerprint").equals(
                        rs.getString("current_code_fingerprint"))
                && rs.getString("expected_head_sha").equals(
                        rs.getString("current_head_sha"))
                && rs.getString("expected_base_sha").equals(
                        rs.getString("current_base_sha"));
        DeliveryState state;
        if ("ACTIVE".equals(lifecycle) && exactOwner) {
            state = DeliveryState.ACTIVE;
        }
        else if (("PAUSING".equals(lifecycle)
                    || "PAUSED".equals(lifecycle)
                    || "RESUMING".equals(lifecycle))
                && exactOwner) {
            state = DeliveryState.PAUSING;
        }
        else if (("CANCELING".equals(lifecycle)
                    || "CLEANING".equals(lifecycle)
                    || "CANCELED".equals(lifecycle))
                && rs.getLong("current_task_epoch")
                        == rs.getLong("task_epoch") + 1) {
            state = DeliveryState.CANCELING;
        }
        else {
            state = DeliveryState.STALE;
        }
        return new Delivery(
                rs.getString("operation_row_id"), rs.getString("operation_id"),
                rs.getString("episode_id"), kind, rs.getInt("generation"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getInt("attempt_no"), rs.getInt("attempt_limit"),
                AuthorityKind.valueOf(rs.getString("authority_kind")),
                rs.getInt("semantic_attempt"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("target_base_sha"),
                rs.getString("episode_status"), lifecycle, state);
    }

    private static TurnContext turnContext(ResultSet rs)
            throws SQLException
    {
        return new TurnContext(
                rs.getString("episode_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getLong("stage_version"),
                rs.getString("checkpoint"), rs.getString("episode_status"),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("target_base_sha"),
                ResultDisposition.valueOf(rs.getString("result_disposition")),
                rs.getString("result_evidence_json"),
                rs.getString("worktree_path"), rs.getString("branch_name"),
                rs.getString("base_branch"), rs.getString("workspace_id"),
                rs.getString("trunk_id"), rs.getString("engine_snapshot"),
                rs.getString("work_model_snapshot"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"),
                rs.getString("reasoning_effort"), rs.getString("task_name"),
                rs.getString("predecessor_stage_turn_id"));
    }

    private static Episode episode(ResultSet rs)
            throws SQLException
    {
        return new Episode(
                rs.getString("id"), rs.getString("source_publish_operation_id"),
                rs.getString("local_development_stage_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getLong("stage_generation"),
                rs.getString("source_code_fingerprint"),
                rs.getString("source_head_sha"),
                rs.getString("source_base_sha"),
                rs.getString("target_base_sha"),
                AuthorityKind.valueOf(rs.getString("authority_kind")),
                rs.getString("standing_policy_revision_id"),
                rs.getString("blocker_id"), rs.getString("actor"),
                rs.getString("branch_sync_policy_revision_id"),
                rs.getString("command_id"),
                rs.getString("retry_of_episode_id"), rs.getInt("attempt_no"),
                rs.getInt("attempt_limit"), rs.getString("status"),
                nullableEnum(rs, "resume_cursor", ResumeCursor.class),
                instant(rs, "opened_at_ms"), nullableInstant(rs, "completed_at_ms"),
                rs.getString("error_message"));
    }

    private static Operation operation(ResultSet rs)
            throws SQLException
    {
        String disposition = rs.getString("result_disposition");
        return new Operation(
                rs.getString("id"), rs.getString("episode_id"),
                Kind.valueOf(rs.getString("kind")), rs.getInt("generation"),
                rs.getString("operation_id"),
                rs.getInt("semantic_attempt"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("target_base_sha"), rs.getString("status"),
                disposition == null ? null : ResultDisposition.valueOf(disposition),
                rs.getString("result_code_fingerprint"),
                rs.getString("result_head_sha"), rs.getString("result_base_sha"),
                rs.getString("result_evidence_json"),
                instant(rs, "requested_at_ms"),
                nullableInstant(rs, "completed_at_ms"),
                rs.getString("error_message"));
    }

    private static DeliveryReceipt receipt(ResultSet rs)
            throws SQLException
    {
        DeliveryAcceptance local = DeliveryAcceptance.valueOf(
                rs.getString("acceptance"));
        return new DeliveryReceipt(
                rs.getString("operation_row_id"), rs.getString("operation_id"),
                DispatchTicket.Outcome.valueOf(rs.getString("raw_outcome")),
                rs.getString("raw_result_digest"),
                local == DeliveryAcceptance.ACCEPTED
                        ? DispatchTicket.Acceptance.ACCEPTED
                        : DispatchTicket.Acceptance.SUPERSEDED,
                local,
                instant(rs, "recorded_at_ms"));
    }

    private static StartReceiptEvidence startReceipt(ResultSet rs)
            throws SQLException
    {
        return new StartReceiptEvidence(
                rs.getString("id"), rs.getString("episode_id"),
                rs.getString("stage_turn_request_id"),
                rs.getString("command_id"), rs.getString("actor"),
                rs.getString("task_id"), rs.getString("stage_id"),
                rs.getLong("task_epoch"), rs.getLong("stage_generation"),
                rs.getLong("expected_stage_version"),
                rs.getLong("returned_stage_version"),
                rs.getString("operation_id"), rs.getInt("semantic_attempt"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("target_base_sha"),
                instant(rs, "recorded_at_ms"));
    }

    private static void requireDuplicate(OpenRequest request, Episode episode)
    {
        if (!episode.sourcePublishOperationId().equals(
                    request.sourcePublishOperationId())
                || !episode.targetBaseSha().equals(request.targetBaseSha())
                || episode.authorityKind() != request.authorityKind()
                || !Objects.equals(episode.standingPolicyRevisionId(),
                        request.standingPolicyRevisionId())
                || !Objects.equals(episode.blockerId(), request.blockerId())
                || !Objects.equals(episode.actor(), request.actor())
                || !episode.branchSyncPolicyRevisionId().equals(
                        request.branchSyncPolicyRevisionId())) {
            throw new IllegalArgumentException(
                    "Base-sync command was already used with other values");
        }
    }

    private int requireInt(String sql, Object... values)
    {
        Integer result = jdbc.queryForObject(sql, Integer.class, values);
        if (result == null) {
            throw new IllegalStateException("Expected one integer result");
        }
        return result;
    }

    private void updateOne(String sql, String error, Object... values)
    {
        if (jdbc.update(sql, values) != 1) {
            throw new IllegalStateException(error);
        }
    }

    private static String operationKind(Kind kind)
    {
        return kind == Kind.FETCH_COMPARE
                ? "FETCH_LOCAL_PUBLISH_BASE" : "REBASE_LOCAL_PUBLISH_BASE";
    }

    private static String callback(Kind kind)
    {
        return kind == Kind.FETCH_COMPARE
                ? "LOCAL_PUBLISH_BASE_FETCH_RESULT"
                : "LOCAL_PUBLISH_BASE_REBASE_RESULT";
    }

    private static boolean isTerminal(String status)
    {
        return switch (status) {
            case "HANDED_OFF", "FAILED", "EXHAUSTED", "CANCELED",
                    "SUPERSEDED" -> true;
            default -> false;
        };
    }

    private static Instant instant(ResultSet rs, String column)
            throws SQLException
    {
        return Instant.ofEpochMilli(rs.getLong(column));
    }

    private static Instant nullableInstant(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static <T extends Enum<T>> T nullableEnum(
            ResultSet rs, String column, Class<T> type)
            throws SQLException
    {
        String value = rs.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Local publish base-sync mutation requires a Task command transaction");
        }
    }

    private static void requireDigest(String digest)
    {
        requireText(digest, "rawResultDigest");
        if (digest.length() != 64) {
            throw new IllegalArgumentException("rawResultDigest must have 64 characters");
        }
    }

    private static void requireJson(String value)
    {
        requireText(value, "result.evidenceJson");
        String trimmed = value.trim();
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            throw new IllegalArgumentException("result.evidenceJson must be a JSON object");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum AuthorityKind
    {
        STANDING_TASK_POLICY,
        MANUAL
    }

    public enum Kind
    {
        FETCH_COMPARE,
        MECHANICAL_REBASE
    }

    public enum ResultDisposition
    {
        FETCHED,
        REBASED,
        CONFLICT,
        FAILED
    }

    public enum DeliveryAcceptance
    {
        ACCEPTED,
        PARKED,
        SUPERSEDED
    }

    public enum DeliveryState
    {
        ACTIVE,
        PAUSING,
        CANCELING,
        STALE
    }

    public enum ResumeCursor
    {
        FETCH,
        REBASE,
        HANDOFF
    }

    public enum ResumeDisposition
    {
        OPERATION,
        HANDOFF,
        FAILURE
    }

    public record OpenRequest(
            String commandId,
            String sourcePublishOperationId,
            String targetBaseSha,
            String branchSyncPolicyRevisionId,
            AuthorityKind authorityKind,
            String standingPolicyRevisionId,
            String blockerId,
            String actor,
            Instant openedAt)
    {
        public OpenRequest
        {
            requireText(commandId, "commandId");
            requireText(sourcePublishOperationId, "sourcePublishOperationId");
            requireText(targetBaseSha, "targetBaseSha");
            requireText(branchSyncPolicyRevisionId, "branchSyncPolicyRevisionId");
            requireNonNull(authorityKind, "authorityKind is null");
            requireNonNull(openedAt, "openedAt is null");
        }
    }

    public record Admission(Episode episode, Operation fetchOperation) {}

    public record FailureResolution(
            Episode failedEpisode,
            Admission automaticRetry,
            ManualBlocker blocker) {}

    public record ManualBlocker(
            String id,
            String taskId,
            String stageId,
            String blockerType,
            String subjectRevision,
            String sourcePublishOperationId,
            String sourceBaseSha,
            String targetBaseSha,
            String status,
            Instant openedAt)
    {
        public ManualBlocker(
                String id, String taskId, String stageId,
                String sourcePublishOperationId, String sourceBaseSha,
                String targetBaseSha, String status, Instant openedAt)
        {
            this(id, taskId, stageId,
                    "LOCAL_PUBLISH_BASE_SYNC_REQUIRED",
                    sourcePublishOperationId, sourcePublishOperationId,
                    sourceBaseSha, targetBaseSha, status, openedAt);
        }
    }

    public record Episode(
            String id,
            String sourcePublishOperationId,
            String stageId,
            String taskId,
            long taskEpoch,
            long stageGeneration,
            String sourceCodeFingerprint,
            String sourceHeadSha,
            String sourceBaseSha,
            String targetBaseSha,
            AuthorityKind authorityKind,
            String standingPolicyRevisionId,
            String blockerId,
            String actor,
            String branchSyncPolicyRevisionId,
            String commandId,
            String retryOfEpisodeId,
            int attemptNo,
            int attemptLimit,
            String status,
            ResumeCursor resumeCursor,
            Instant openedAt,
            Instant completedAt,
            String errorMessage)
    {
        public Episode(
                String id, String sourcePublishOperationId, String stageId,
                String taskId, long taskEpoch, long stageGeneration,
                String sourceCodeFingerprint, String sourceHeadSha,
                String sourceBaseSha, String targetBaseSha,
                AuthorityKind authorityKind, String standingPolicyRevisionId,
                String blockerId, String actor,
                String branchSyncPolicyRevisionId, String commandId,
                int attemptNo, int attemptLimit, String status,
                Instant openedAt, Instant completedAt, String errorMessage)
        {
            this(id, sourcePublishOperationId, stageId, taskId, taskEpoch,
                    stageGeneration, sourceCodeFingerprint, sourceHeadSha,
                    sourceBaseSha, targetBaseSha, authorityKind,
                    standingPolicyRevisionId, blockerId, actor,
                    branchSyncPolicyRevisionId, commandId, null, attemptNo,
                    attemptLimit, status, null, openedAt, completedAt,
                    errorMessage);
        }
    }

    public record Operation(
            String id,
            String episodeId,
            Kind kind,
            int generation,
            String operationId,
            int semanticAttempt,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String targetBaseSha,
            String status,
            ResultDisposition resultDisposition,
            String resultCodeFingerprint,
            String resultHeadSha,
            String resultBaseSha,
            String resultEvidenceJson,
            Instant requestedAt,
            Instant completedAt,
            String errorMessage)
    {
        public Operation(
                String id, String episodeId, Kind kind, String operationId,
                int semanticAttempt, String expectedCodeFingerprint,
                String expectedHeadSha, String expectedBaseSha,
                String targetBaseSha, String status,
                ResultDisposition resultDisposition,
                String resultCodeFingerprint, String resultHeadSha,
                String resultBaseSha, String resultEvidenceJson,
                Instant requestedAt, Instant completedAt, String errorMessage)
        {
            this(id, episodeId, kind, 1, operationId, semanticAttempt,
                    expectedCodeFingerprint, expectedHeadSha,
                    expectedBaseSha, targetBaseSha, status, resultDisposition,
                    resultCodeFingerprint, resultHeadSha, resultBaseSha,
                    resultEvidenceJson, requestedAt, completedAt, errorMessage);
        }
    }

    public record TurnContext(
            String episodeId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String checkpoint,
            String episodeStatus,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String targetBaseSha,
            ResultDisposition resultDisposition,
            String resultEvidenceJson,
            String worktreePath,
            String branchName,
            String baseBranch,
            String workspaceId,
            String trunkId,
            String engineSnapshot,
            String workModelSnapshot,
            String provider,
            String model,
            String roleSkill,
            String reasoningEffort,
            String taskName,
            String predecessorStageTurnId) {}

    public record BaseSyncTurn(
            String episodeId,
            String requestId,
            String commandId,
            String turnId,
            String operationId,
            String ticketId,
            String workspaceId,
            String trunkId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int attempt,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String targetBaseSha,
            String deliveryLane,
            int laneMask,
            String launchInput,
            String promptDigest,
            String requestedBy,
            Instant requestedAt) {}

    public record StartReceiptEvidence(
            String id,
            String episodeId,
            String requestId,
            String commandId,
            String actor,
            String taskId,
            String stageId,
            long taskEpoch,
            long stageGeneration,
            long expectedStageVersion,
            long returnedStageVersion,
            String operationId,
            int attempt,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String targetBaseSha,
            Instant recordedAt) {}

    public record Delivery(
            String operationRowId,
            String operationId,
            String episodeId,
            Kind kind,
            int operationGeneration,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int attemptNo,
            int attemptLimit,
            AuthorityKind authorityKind,
            int semanticAttempt,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String targetBaseSha,
            String episodeStatus,
            String taskLifecycle,
            DeliveryState state)
    {
        public Delivery(
                String operationRowId, String operationId, String episodeId,
                Kind kind, String taskId, long taskEpoch, String stageId,
                long stageGeneration, int semanticAttempt,
                String expectedCodeFingerprint, String expectedHeadSha,
                String expectedBaseSha, String targetBaseSha,
                String episodeStatus, boolean current)
        {
            this(operationRowId, operationId, episodeId, kind, 1, taskId,
                    taskEpoch, stageId, stageGeneration, semanticAttempt, 3,
                    AuthorityKind.STANDING_TASK_POLICY, semanticAttempt,
                    expectedCodeFingerprint, expectedHeadSha, expectedBaseSha,
                    targetBaseSha, episodeStatus, "ACTIVE",
                    current ? DeliveryState.ACTIVE : DeliveryState.STALE);
        }

        public boolean current()
        {
            return state == DeliveryState.ACTIVE;
        }
    }

    public record Result(
            ResultDisposition disposition,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String evidenceJson,
            String error) {}

    public record DeliveryReceipt(
            String operationRowId,
            String operationId,
            DispatchTicket.Outcome rawOutcome,
            String rawResultDigest,
            DispatchTicket.Acceptance acceptance,
            DeliveryAcceptance localAcceptance,
            Instant recordedAt)
    {
        public DeliveryReceipt(
                String operationRowId, String operationId,
                DispatchTicket.Outcome rawOutcome, String rawResultDigest,
                DispatchTicket.Acceptance acceptance, Instant recordedAt)
        {
            this(operationRowId, operationId, rawOutcome, rawResultDigest,
                    acceptance,
                    acceptance == DispatchTicket.Acceptance.ACCEPTED
                        ? DeliveryAcceptance.ACCEPTED
                        : DeliveryAcceptance.SUPERSEDED,
                    recordedAt);
        }
    }

    public record ControlSettlement(
            String episodeId,
            String taskId,
            String episodeStatus,
            ResumeCursor resumeCursor,
            String taskLifecycle,
            String operationRowId,
            String operationId,
            Kind kind,
            int operationGeneration,
            String operationStatus,
            String ticketStatus) {}

    public record ResumeSettlement(
            Episode episode,
            ResumeDisposition disposition,
            Operation successorOperation,
            FailureResolution failure) {}

    private record Source(
            String publishOperationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String trunkId,
            String workspaceId,
            int attemptLimit) {}

    private record PauseEvidence(
            String id,
            String operationId,
            Kind operationKind,
            ResumeCursor cursor,
            DispatchTicket.Outcome rawOutcome) {}

    private record Terminal(
            String status,
            String disposition,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String evidenceJson,
            String error)
    {
        private static Terminal error(String status, String error)
        {
            return new Terminal(
                    status, "FAILED".equals(status) ? "FAILED" : null,
                    null, null, null, null, error);
        }
    }
}
