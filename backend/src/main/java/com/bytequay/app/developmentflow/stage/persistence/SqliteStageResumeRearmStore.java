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

import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Idempotent owner proof for the RESUMING-to-ACTIVE rearm handoff. */
@Component
public final class SqliteStageResumeRearmStore
{
    private static final String ACTOR = "stage-resume-owner";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public SqliteStageResumeRearmStore(JdbcTemplate jdbc)
    {
        this(jdbc, Clock.systemUTC());
    }

    SqliteStageResumeRearmStore(JdbcTemplate jdbc, Clock clock)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public TaskResumeOwner.Acceptance accept(
            TaskResumeOwner.Request request, StageKind ownerKind)
    {
        requireNonNull(request, "request is null");
        if (request.stageKind() != ownerKind) {
            throw new IllegalArgumentException(
                    ownerKind + " resume owner received " + request.stageKind());
        }
        Intent duplicate = find(request.handoffId());
        if (duplicate != null) {
            return requireReplay(duplicate, request, ownerKind);
        }
        String acceptedBy = ownerKind.name() + "_STAGE_OWNER";
        String proofId = id("stage-resume-rearm", request.handoffId(), ownerKind);
        try {
            jdbc.update("""
                    INSERT INTO stage_resume_rearm_intent_v257(
                        handoff_id, owner_proof_id, accepted_by, task_id,
                        task_epoch, task_version, stage_id, stage_kind,
                        stage_generation, stage_version, restore_checkpoint,
                        reconciliation_id, code_fingerprint, head_sha, base_sha,
                        status, accepted_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'PENDING', ?)
                    """, request.handoffId(), proofId, acceptedBy,
                    request.taskId(), request.taskEpoch(), request.taskVersion(),
                    request.stageId(), request.stageKind().name(),
                    request.stageGeneration(), request.stageVersion(),
                    request.restoreCheckpoint().name(), request.reconciliationId(),
                    request.codeFingerprint(), request.headSha(), request.baseSha(),
                    clock.millis());
        }
        catch (DataAccessException concurrent) {
            Intent replay = find(request.handoffId());
            if (replay == null) {
                throw concurrent;
            }
            return requireReplay(replay, request, ownerKind);
        }
        return requireReplay(requireNonNull(find(request.handoffId())), request, ownerKind);
    }

    public List<Intent> pending(StageKind kind, int limit)
    {
        requireNonNull(kind, "kind is null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT intent.handoff_id, intent.owner_proof_id,
                       intent.accepted_by, intent.task_id, intent.task_epoch,
                       intent.task_version, intent.stage_id, intent.stage_kind,
                       intent.stage_generation, intent.stage_version,
                       intent.restore_checkpoint, intent.reconciliation_id,
                       intent.code_fingerprint, intent.head_sha, intent.base_sha,
                       task.lifecycle_state
                FROM stage_resume_rearm_intent_v257 intent
                JOIN task_resume_handoff_v256 handoff
                  ON handoff.id = intent.handoff_id
                JOIN tasks task ON task.id = intent.task_id
                WHERE intent.status = 'PENDING'
                  AND intent.diagnostic_code IS NULL
                  AND intent.stage_kind = ?
                  AND handoff.status = 'ACCEPTED'
                ORDER BY intent.accepted_at_ms, intent.handoff_id
                LIMIT ?
                """, (rs, row) -> intent(rs), kind.name(), limit);
    }

    public void materializePlanDraft(Intent intent, Instant now)
    {
        requireOwner(intent, StageKind.PLAN, StageCheckpoint.DRAFTING);
        FrozenTurn previous = requireFrozenTurn(intent, "TASK_TURN", "PLAN_DRAFT");
        Successor next = successor(intent, "TASK_TURN", "PLAN_DRAFT",
                previous.attempt() + 1);
        prepare(next, now);
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                SELECT ?, task_id, purpose, 'REQUESTED', ?, ?, task_epoch,
                       trigger_stage_id, trigger_stage_generation,
                       expected_code_fingerprint, expected_head_sha,
                       expected_base_sha, delivery_lane,
                       json_set(launch_input,
                         '$.toolEndpoint.ownerId', ?,
                         '$.toolEndpoint.operationId', ?,
                         '$.toolEndpoint.url', replace(replace(
                           json_extract(launch_input, '$.toolEndpoint.url'),
                           id, ?), operation_id, ?)), ?
                FROM task_turn WHERE id = ? AND operation_id = ?
                  AND status = 'CANCELED'
                """, next.ownerId(), next.operationId(), next.attempt(),
                next.ownerId(), next.operationId(), next.ownerId(),
                next.operationId(), now.toEpochMilli(), previous.ownerId(),
                previous.operationId());
        cloneTicket(previous, next, now);
        armStage(intent, next, now);
    }

    public void materializeLocalTurn(Intent intent, Instant now)
    {
        if (intent.stageKind() != StageKind.LOCAL_DEVELOPMENT
                || intent.restoreCheckpoint() != StageCheckpoint.IMPLEMENTING
                    && intent.restoreCheckpoint()
                        != StageCheckpoint.ADDRESSING_BRAIN_FINDINGS
                    && intent.restoreCheckpoint()
                        != StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK) {
            throw new IllegalArgumentException(
                    "Local resume checkpoint is not a code Turn");
        }
        String purpose = switch (intent.restoreCheckpoint()) {
            case IMPLEMENTING -> "IMPLEMENT_LOCAL_PLAN";
            case ADDRESSING_BRAIN_FINDINGS -> "ADDRESS_BRAIN_FINDINGS";
            case ADDRESSING_LOCAL_FEEDBACK -> "ADDRESS_LOCAL_FEEDBACK";
            default -> throw new IllegalStateException("unreachable");
        };
        FrozenTurn previous = requireFrozenTurn(intent, "STAGE_TURN", purpose);
        Successor next = successor(intent, "STAGE_TURN", purpose,
                previous.attempt() + 1);
        prepare(next, now);
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                SELECT ?, stage_id, stage_generation, purpose, 'QUEUED', ?, ?,
                       task_epoch, expected_code_fingerprint, expected_head_sha,
                       expected_base_sha, delivery_lane,
                       json_set(launch_input,
                         '$.toolEndpoint.ownerId', ?,
                         '$.toolEndpoint.operationId', ?,
                         '$.toolEndpoint.url', replace(replace(
                           json_extract(launch_input, '$.toolEndpoint.url'),
                           id, ?), operation_id, ?)), ?
                FROM stage_turn WHERE id = ? AND operation_id = ?
                  AND status = 'CANCELED'
                """, next.ownerId(), next.operationId(), next.attempt(),
                next.ownerId(), next.operationId(), next.ownerId(),
                next.operationId(), now.toEpochMilli(), previous.ownerId(),
                previous.operationId());
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, predecessor_turn_id,
                    brain_review_episode_id, local_feedback_batch_id,
                    prompt_digest, requested_by, requested_at_ms)
                SELECT ?, ?, ?, task_id, local_development_stage_id,
                       task_epoch, stage_generation, kind, 'IMMEDIATE', NULL,
                       brain_review_episode_id, local_feedback_batch_id,
                       prompt_digest, ?, ?
                FROM local_stage_turn_request WHERE stage_turn_id = ?
                """, id("local-resume-request", intent.handoffId()),
                next.commandId(), next.ownerId(), ACTOR, now.toEpochMilli(),
                previous.ownerId());
        cloneTicket(previous, next, now);
        armStage(intent, next, now);
    }

    /** Restores an existing UI-owned gate without creating asynchronous work. */
    public void materializePassiveWait(
            Intent intent,
            StageKind kind,
            StageCheckpoint checkpoint,
            Instant now)
    {
        requireOwner(intent, kind, checkpoint);
        String waitKind;
        if (kind == StageKind.PLAN
                && checkpoint == StageCheckpoint.AWAITING_APPROVAL) {
            waitKind = "PLAN_APPROVAL";
        }
        else if (kind == StageKind.LOCAL_DEVELOPMENT
                && checkpoint == StageCheckpoint.LOCAL_REVIEW) {
            waitKind = "LOCAL_REVIEW";
        }
        else {
            throw new IllegalArgumentException(
                    "checkpoint is not a passive resume wait");
        }
        jdbc.update("""
                INSERT OR IGNORE INTO stage_resume_passive_wait_v263(
                    handoff_id, wait_kind, stage_id, stage_generation,
                    checkpoint, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, intent.handoffId(), waitKind, intent.stageId(),
                intent.stageGeneration(), checkpoint.name(), now.toEpochMilli());
        complete(intent, now);
    }

    public void materializeRemoteFeedback(Intent intent, Instant now)
    {
        requireOwner(intent, StageKind.REMOTE_DEVELOPMENT,
                StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK);
        FrozenTurn previous = requireFrozenTurn(
                intent, "STAGE_TURN", "ADDRESS_REMOTE_FEEDBACK");
        Successor next = successor(intent, "STAGE_TURN",
                "ADDRESS_REMOTE_FEEDBACK", previous.attempt() + 1);
        prepare(next, now);
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                SELECT ?, stage_id, stage_generation, purpose, 'QUEUED', ?, ?,
                       task_epoch, expected_code_fingerprint, expected_head_sha,
                       expected_base_sha, delivery_lane,
                       json_set(launch_input,
                         '$.toolEndpoint.ownerId', ?,
                         '$.toolEndpoint.operationId', ?,
                         '$.toolEndpoint.url', replace(replace(
                           json_extract(launch_input, '$.toolEndpoint.url'),
                           id, ?), operation_id, ?)), ?
                FROM stage_turn WHERE id = ? AND operation_id = ?
                  AND status = 'CANCELED'
                """, next.ownerId(), next.operationId(), next.attempt(),
                next.ownerId(), next.operationId(), next.ownerId(),
                next.operationId(), now.toEpochMilli(), previous.ownerId(),
                previous.operationId());
        jdbc.update("""
                INSERT INTO remote_feedback_stage_turn_request(
                    id, remote_feedback_batch_id, stage_turn_id, task_id,
                    remote_development_stage_id, task_epoch, stage_generation,
                    semantic_attempt, predecessor_turn_id, prompt_digest,
                    requested_by, requested_at_ms)
                SELECT ?, remote_feedback_batch_id, ?, task_id,
                       remote_development_stage_id, task_epoch, stage_generation,
                       ?, stage_turn_id, prompt_digest, ?, ?
                FROM remote_feedback_stage_turn_request
                WHERE stage_turn_id = ?
                """, id("remote-feedback-resume-request", intent.handoffId()),
                next.ownerId(), next.attempt(), ACTOR, now.toEpochMilli(),
                previous.ownerId());
        cloneTicket(previous, next, now);
        armSuccessor(next, null, now);
        complete(intent, now);
    }

    public void materializeObservation(
            Intent intent, ObservationRequest observation, Instant now)
    {
        requireNonNull(observation, "observation is null");
        if (intent.stageKind() != StageKind.REMOTE_DEVELOPMENT
                || !intent.taskId().equals(observation.taskId())
                || !intent.stageId().equals(observation.stageId())) {
            throw new IllegalArgumentException(
                    "Remote observation belongs to another resume owner");
        }
        String ticketId = jdbc.queryForObject("""
                SELECT id FROM dispatch_ticket
                WHERE operation_id = ? AND owner_kind = 'STAGE'
                  AND owner_id = ? AND status = 'REQUESTED'
                """, String.class, observation.operationId(), intent.stageId());
        Successor next = new Successor(
                intent.handoffId(), id("resume-observation-command", intent.handoffId()),
                "REMOTE_OBSERVATION", observation.rowId(), observation.operationId(),
                ticketId, "REMOTE_OBSERVATION", observation.semanticAttempt());
        jdbc.update("""
                INSERT INTO stage_resume_rearm_successor_v257(
                    handoff_id, command_id, owner_kind, owner_id, operation_id,
                    dispatch_ticket_id, purpose, semantic_attempt, status,
                    created_at_ms, armed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ARMED', ?, ?)
                """, next.handoffId(), next.commandId(), next.ownerKind(),
                next.ownerId(), next.operationId(), next.ticketId(), next.purpose(),
                next.attempt(), now.toEpochMilli(), now.toEpochMilli());
        complete(intent, now);
    }

    public void diagnose(Intent intent, String code, String detail, Instant now)
    {
        requireNonNull(intent, "intent is null");
        requireText(code, "code");
        requireText(detail, "detail");
        jdbc.update("""
                UPDATE stage_resume_rearm_intent_v257
                   SET diagnostic_code = ?, diagnostic_detail = ?,
                       diagnosed_at_ms = ?
                 WHERE handoff_id = ? AND status = 'PENDING'
                   AND diagnostic_code IS NULL
                """, code, detail, now.toEpochMilli(), intent.handoffId());
    }

    private FrozenTurn requireFrozenTurn(
            Intent intent, String ownerKind, String purpose)
    {
        String table = ownerKind.equals("TASK_TURN") ? "task_turn" : "stage_turn";
        String owner = ownerKind.equals("TASK_TURN")
                ? "turn.trigger_stage_id = intent.stage_id"
                    + " AND turn.trigger_stage_generation = intent.stage_generation"
                    + " AND turn.task_id = intent.task_id"
                : "turn.stage_id = intent.stage_id"
                    + " AND turn.stage_generation = intent.stage_generation";
        List<FrozenTurn> rows = jdbc.query("""
                SELECT turn.id AS owner_id, turn.operation_id, turn.attempt,
                       ticket.id AS ticket_id
                FROM %s turn
                JOIN stage_resume_rearm_intent_v257 intent
                  ON intent.handoff_id = ?
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                WHERE %s AND turn.task_epoch = intent.task_epoch
                  AND turn.purpose = ? AND turn.status = 'CANCELED'
                  AND ticket.owner_kind = ? AND ticket.owner_id = turn.id
                  AND ticket.status = 'CANCELED'
                  AND ticket.task_id = intent.task_id
                  AND ticket.task_epoch = intent.task_epoch
                  AND ticket.stage_id = intent.stage_id
                  AND ticket.stage_generation = intent.stage_generation
                  AND ticket.attempt = turn.attempt
                  AND ticket.expected_code_fingerprint = intent.code_fingerprint
                  AND ticket.expected_head_sha = intent.head_sha
                  AND ticket.expected_base_sha = intent.base_sha
                  AND turn.expected_code_fingerprint = intent.code_fingerprint
                  AND turn.expected_head_sha = intent.head_sha
                  AND turn.expected_base_sha = intent.base_sha
                  AND turn.attempt = (
                    SELECT MAX(candidate.attempt)
                    FROM %s candidate
                    JOIN dispatch_ticket candidate_ticket
                      ON candidate_ticket.operation_id = candidate.operation_id
                    WHERE %s
                      AND candidate.task_epoch = intent.task_epoch
                      AND candidate.purpose = turn.purpose
                      AND candidate.status = 'CANCELED'
                      AND candidate.expected_code_fingerprint = intent.code_fingerprint
                      AND candidate.expected_head_sha = intent.head_sha
                      AND candidate.expected_base_sha = intent.base_sha
                      AND candidate_ticket.owner_kind = ?
                      AND candidate_ticket.owner_id = candidate.id
                      AND candidate_ticket.status = 'CANCELED'
                      AND candidate_ticket.task_id = intent.task_id
                      AND candidate_ticket.task_epoch = intent.task_epoch
                      AND candidate_ticket.stage_id = intent.stage_id
                      AND candidate_ticket.stage_generation =
                          intent.stage_generation
                      AND candidate_ticket.attempt = candidate.attempt
                      AND candidate_ticket.expected_code_fingerprint =
                          intent.code_fingerprint
                      AND candidate_ticket.expected_head_sha = intent.head_sha
                      AND candidate_ticket.expected_base_sha = intent.base_sha)
                ORDER BY turn.id
                LIMIT 2
                """.formatted(table, owner, table,
                        owner.replace("turn.", "candidate.")),
                (rs, row) -> new FrozenTurn(
                        rs.getString("owner_id"), rs.getString("operation_id"),
                        rs.getString("ticket_id"), rs.getInt("attempt")),
                intent.handoffId(), purpose, ownerKind, ownerKind);
        if (rows.size() != 1) {
            throw new IllegalStateException("Expected one exact canceled "
                    + purpose + " predecessor, found " + rows.size());
        }
        return rows.getFirst();
    }

    private Successor successor(
            Intent intent, String ownerKind, String purpose,
            int attempt)
    {
        String ownerId = id("stage-resume-owner", intent.handoffId(), ownerKind);
        String operationId = id(
                "stage-resume-operation", intent.handoffId(), ownerKind);
        String ticketId = id("stage-resume-ticket", intent.handoffId(), ownerKind);
        return new Successor(
                intent.handoffId(), id("stage-resume-command", intent.handoffId()),
                ownerKind, ownerId, operationId, ticketId, purpose, attempt);
    }

    private void prepare(Successor successor, Instant now)
    {
        jdbc.update("""
                INSERT INTO stage_resume_rearm_successor_v257(
                    handoff_id, command_id, owner_kind, owner_id, operation_id,
                    dispatch_ticket_id, purpose, semantic_attempt, status,
                    created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                """, successor.handoffId(), successor.commandId(),
                successor.ownerKind(), successor.ownerId(),
                successor.operationId(), successor.ticketId(), successor.purpose(),
                successor.attempt(), now.toEpochMilli());
    }

    private void cloneTicket(FrozenTurn previous, Successor next, Instant now)
    {
        int inserted = jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                SELECT ?, ?, operation_kind, async_family, ?, ?, callback_route,
                       lane_mask, trunk_control, exclusive_task, writer_required,
                       workspace_id, trunk_id, task_id, task_epoch, stage_id,
                       stage_generation, ?, expected_code_fingerprint,
                       expected_head_sha, expected_base_sha, 'REQUESTED', ?
                FROM dispatch_ticket
                WHERE id = ? AND operation_id = ? AND status = 'CANCELED'
                """, next.ticketId(), next.operationId(), next.ownerKind(),
                next.ownerId(), next.attempt(), now.toEpochMilli(),
                previous.ticketId(), previous.operationId());
        if (inserted != 1) {
            throw new IllegalStateException("Canceled DispatchTicket disappeared");
        }
    }

    private void armStage(Intent intent, Successor next, Instant now)
    {
        int changed = jdbc.update("""
                UPDATE stage
                   SET version = version + 1
                 WHERE id = ? AND task_id = ? AND kind = ? AND generation = ?
                   AND version = ? AND checkpoint = ?
                   AND completed_at_ms IS NULL AND end_reason IS NULL
                   AND EXISTS (
                     SELECT 1 FROM tasks task
                     JOIN task_current_stage current ON current.task_id = task.id
                     WHERE task.id = stage.task_id
                       AND task.workflow_version = 'V2'
                       AND task.lifecycle_state = 'ACTIVE'
                       AND task.epoch = ?
                       AND current.stage_id = stage.id
                       AND current.stage_generation = stage.generation)
                """, intent.stageId(), intent.taskId(), intent.stageKind().name(),
                intent.stageGeneration(), intent.stageVersion(),
                intent.restoreCheckpoint().name(), intent.taskEpoch());
        if (changed != 1) {
            throw new IllegalStateException("Resume Stage owner changed before rearm");
        }
        long returnedVersion = intent.stageVersion() + 1;
        jdbc.update("""
                INSERT INTO stage_transition(
                    id, stage_id, command_id, generation, from_checkpoint,
                    to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REARM_STAGE_OWNER', ?, ?)
                """, id("stage-resume-transition", intent.handoffId()),
                intent.stageId(), next.commandId(), intent.stageGeneration(),
                intent.restoreCheckpoint().name(), intent.restoreCheckpoint().name(),
                returnedVersion, ACTOR, now.toEpochMilli());
        armSuccessor(next, returnedVersion, now);
        complete(intent, now);
    }

    private void armSuccessor(
            Successor successor, Long returnedStageVersion, Instant now)
    {
        int changed = jdbc.update("""
                UPDATE stage_resume_rearm_successor_v257
                   SET status = 'ARMED', returned_stage_version = ?,
                       armed_at_ms = ?
                 WHERE handoff_id = ? AND status = 'PREPARED'
                """, returnedStageVersion, now.toEpochMilli(),
                successor.handoffId());
        if (changed != 1) {
            throw new IllegalStateException("Resume successor changed before arming");
        }
    }

    private void complete(Intent intent, Instant now)
    {
        int changed = jdbc.update("""
                UPDATE stage_resume_rearm_intent_v257
                   SET status = 'MATERIALIZED', materialized_at_ms = ?,
                       diagnostic_code = NULL, diagnostic_detail = NULL,
                       diagnosed_at_ms = NULL
                 WHERE handoff_id = ? AND status = 'PENDING'
                """, now.toEpochMilli(), intent.handoffId());
        if (changed != 1) {
            throw new IllegalStateException("Resume intent changed before materialization");
        }
    }

    private static void requireOwner(
            Intent intent, StageKind kind, StageCheckpoint checkpoint)
    {
        requireNonNull(intent, "intent is null");
        if (intent.stageKind() != kind || intent.restoreCheckpoint() != checkpoint) {
            throw new IllegalArgumentException(
                    kind + " resume owner cannot materialize "
                            + intent.restoreCheckpoint());
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private Intent find(String handoffId)
    {
        List<Intent> rows = jdbc.query("""
                SELECT intent.handoff_id, intent.owner_proof_id,
                       intent.accepted_by, intent.task_id, intent.task_epoch,
                       intent.task_version, intent.stage_id, intent.stage_kind,
                       intent.stage_generation, intent.stage_version,
                       intent.restore_checkpoint, intent.reconciliation_id,
                       intent.code_fingerprint, intent.head_sha, intent.base_sha,
                       task.lifecycle_state
                FROM stage_resume_rearm_intent_v257 intent
                JOIN tasks task ON task.id = intent.task_id
                WHERE intent.handoff_id = ?
                """, (rs, row) -> intent(rs), handoffId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Intent intent(ResultSet rs)
            throws SQLException
    {
        return new Intent(
                rs.getString("handoff_id"), rs.getString("owner_proof_id"),
                rs.getString("accepted_by"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getLong("task_version"),
                rs.getString("stage_id"),
                StageKind.valueOf(rs.getString("stage_kind")),
                rs.getLong("stage_generation"), rs.getLong("stage_version"),
                StageCheckpoint.valueOf(rs.getString("restore_checkpoint")),
                rs.getString("reconciliation_id"),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("lifecycle_state"));
    }

    private static TaskResumeOwner.Acceptance requireReplay(
            Intent intent, TaskResumeOwner.Request request, StageKind ownerKind)
    {
        if (!intent.taskId().equals(request.taskId())
                || intent.taskEpoch() != request.taskEpoch()
                || intent.taskVersion() != request.taskVersion()
                || !intent.stageId().equals(request.stageId())
                || intent.stageKind() != ownerKind
                || intent.stageGeneration() != request.stageGeneration()
                || intent.stageVersion() != request.stageVersion()
                || intent.restoreCheckpoint() != request.restoreCheckpoint()
                || !intent.reconciliationId().equals(request.reconciliationId())
                || !intent.codeFingerprint().equals(request.codeFingerprint())
                || !intent.headSha().equals(request.headSha())
                || !intent.baseSha().equals(request.baseSha())) {
            throw new IllegalArgumentException(
                    "Resume handoff id already names another Stage owner fence");
        }
        return new TaskResumeOwner.Acceptance(
                intent.handoffId(), intent.ownerProofId(), intent.acceptedBy());
    }

    private static String id(String namespace, Object... parts)
    {
        StringBuilder value = new StringBuilder(namespace);
        for (Object part : parts) {
            value.append('\u001f').append(part);
        }
        return UUID.nameUUIDFromBytes(
                value.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record Intent(
            String handoffId, String ownerProofId, String acceptedBy,
            String taskId, long taskEpoch, long taskVersion, String stageId,
            StageKind stageKind, long stageGeneration, long stageVersion,
            StageCheckpoint restoreCheckpoint, String reconciliationId,
            String codeFingerprint, String headSha, String baseSha,
            String taskLifecycle) {}

    private record FrozenTurn(
            String ownerId, String operationId, String ticketId, int attempt) {}

    private record Successor(
            String handoffId, String commandId, String ownerKind, String ownerId,
            String operationId, String ticketId, String purpose, Integer attempt) {}
}
