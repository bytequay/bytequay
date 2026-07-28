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
package com.bytequay.app.developmentflow.persistence;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler.ExactTurn;
import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler.McpOwner;
import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler.StartDisposition;
import com.bytequay.app.service.review.ReviewAssignmentTurnResultDeliveryPort;
import com.bytequay.app.service.review.ReviewAssignmentTurnResultDeliveryPort.ResultCommand;
import com.bytequay.app.service.review.ReviewAssignmentTurnResultDeliveryPort.ResultReceipt;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.Admission;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.RetryCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Exact SQLite admission, execution fence, and projection for review Turns. */
@Repository
public class SqliteReviewAssignmentTurnStore
        implements ReviewAssignmentTurnRuntime.Store,
        ReviewAssignmentTurnOperationHandler.Store,
        ReviewAssignmentTurnResultDeliveryPort.Store
{
    private static final String CURRENT_HEAD = """
            CASE WHEN session.owner_task_id IS NULL THEN COALESCE((
                SELECT commit_row.sha
                FROM pr_commit commit_row
                WHERE commit_row.pr_id = session.pr_id
                ORDER BY commit_row.authored_at_ms DESC, commit_row.rowid DESC
                LIMIT 1), round.start_commit)
            ELSE code.head_sha END
            """;

    private final JdbcTemplate jdbc;
    private final SqliteDispatchWakeStore wakes;
    private final ObjectMapper json;

    public SqliteReviewAssignmentTurnStore(
            JdbcTemplate jdbc,
            SqliteDispatchWakeStore wakes,
            ObjectMapper json)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.wakes = requireNonNull(wakes, "wakes is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    @Transactional
    public void admitRound(
            String roundId,
            String startCommit,
            List<Admission> admissions,
            Instant requestedAt)
    {
        requireText(roundId, "roundId");
        requireText(startCommit, "startCommit");
        requireNonNull(admissions, "admissions is null");
        requireNonNull(requestedAt, "requestedAt is null");
        Scope scope = requireScope(roundId, startCommit);
        if (admissions.isEmpty()) {
            throw new IllegalArgumentException("review admission is empty");
        }
        for (Admission admission : admissions) {
            insertAdmission(scope, admission, requestedAt, false);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RetryCandidate> retryCandidate(String assignmentId)
    {
        requireText(assignmentId, "assignmentId");
        return jdbc.query("""
                SELECT turn.assignment_id, turn.start_commit, turn.attempt,
                       turn.launch_input
                FROM review_assignment_turn turn
                JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                JOIN review_round round ON round.id = assignment.round_id
                JOIN review_session session ON session.id = round.session_id
                WHERE turn.assignment_id = ?
                  AND turn.status = 'FAILED'
                  AND round.status = 'ERRORED'
                  AND session.status = 'ACTIVE'
                  AND turn.attempt = (
                      SELECT MAX(candidate.attempt)
                      FROM review_assignment_turn candidate
                      WHERE candidate.assignment_id = turn.assignment_id)
                """, (rs, row) -> new RetryCandidate(
                        rs.getString("assignment_id"),
                        rs.getString("start_commit"),
                        rs.getInt("attempt"),
                        rs.getString("launch_input")), assignmentId)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public void retry(Admission admission, Instant requestedAt)
    {
        requireNonNull(admission, "admission is null");
        requireNonNull(requestedAt, "requestedAt is null");
        RetryState state = jdbc.query("""
                SELECT assignment.round_id, turn.attempt, turn.status
                FROM review_assignment assignment
                JOIN review_assignment_turn turn ON turn.assignment_id = assignment.id
                WHERE assignment.id = ?
                ORDER BY turn.attempt DESC
                LIMIT 1
                """, (rs, row) -> new RetryState(
                        rs.getString("round_id"), rs.getInt("attempt"),
                        rs.getString("status")), admission.assignmentId())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "review assignment has no prior Turn"));
        if (!"FAILED".equals(state.status())
                || admission.attempt() != state.attempt() + 1) {
            throw new IllegalStateException("review retry no longer names the latest failure");
        }
        int reopened = jdbc.update("""
                UPDATE review_round
                SET status = 'RUNNING', end_commit = NULL, finished_at_ms = NULL,
                    message_gate_open = 1, lifecycle_finalized = 0
                WHERE id = ? AND status = 'ERRORED'
                  AND start_commit = ?
                """, state.roundId(), admission.startCommit());
        if (reopened != 1) {
            throw new IllegalStateException("review round is no longer retryable");
        }
        jdbc.update("""
                UPDATE agent_run
                SET status = 'running', headline = NULL, finished_at_ms = NULL
                WHERE id = (SELECT agent_run_id FROM review_round WHERE id = ?)
                  AND status = 'failed'
                """, state.roundId());
        jdbc.update("""
                UPDATE review_assignment
                SET status = 'queued'
                WHERE id = ? AND status = 'errored'
                """, admission.assignmentId());
        Scope scope = requireScope(state.roundId(), admission.startCommit());
        insertAdmission(scope, admission, requestedAt, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> ticketIds(String roundId)
    {
        requireText(roundId, "roundId");
        return jdbc.queryForList("""
                SELECT ticket.id
                FROM review_assignment assignment
                JOIN review_assignment_turn turn ON turn.assignment_id = assignment.id
                JOIN dispatch_ticket ticket ON ticket.owner_id = turn.id
                WHERE assignment.round_id = ?
                  AND ticket.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
                  AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')
                ORDER BY ticket.created_at_ms, ticket.id
                """, String.class, roundId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean ownsRound(String roundId)
    {
        requireText(roundId, "roundId");
        return count("""
                SELECT COUNT(*)
                FROM review_assignment assignment
                JOIN review_assignment_turn turn ON turn.assignment_id = assignment.id
                WHERE assignment.round_id = ?
                """, roundId) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExactTurn> find(String turnId)
    {
        requireText(turnId, "turnId");
        String sql = """
                SELECT turn.id AS turn_id, turn.assignment_id, assignment.round_id,
                       round.session_id AS review_id, turn.purpose,
                       turn.status AS turn_status, turn.operation_id, turn.attempt,
                       turn.start_commit, turn.launch_input,
                       round.status AS round_status, session.status AS review_status,
                       session.workspace_id, session.owner_thread_id,
                       session.owner_task_id, task.epoch AS task_epoch,
                       task.lifecycle_state AS task_lifecycle,
                       %s AS current_head_sha
                FROM review_assignment_turn turn
                JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                JOIN review_round round ON round.id = assignment.round_id
                JOIN review_session session ON session.id = round.session_id
                LEFT JOIN tasks task ON task.id = session.owner_task_id
                LEFT JOIN task_current_code_subject_v230 code
                  ON code.task_id = session.owner_task_id
                WHERE turn.id = ?
                  AND (session.owner_task_id IS NULL OR code.head_sha IS NOT NULL)
                """.formatted(CURRENT_HEAD);
        return jdbc.query(sql, this::turn, turnId).stream().findFirst();
    }

    @Override
    @Transactional
    public StartDisposition tryStart(
            String turnId,
            String operationId,
            Instant startedAt)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        requireNonNull(startedAt, "startedAt is null");
        jdbc.update("""
                UPDATE review_round
                SET status = 'RUNNING', message_gate_open = 1
                WHERE id = (
                    SELECT assignment.round_id
                    FROM review_assignment_turn turn
                    JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                    WHERE turn.id = ? AND turn.operation_id = ?)
                  AND status = 'QUEUED'
                  AND NOT EXISTS (
                      SELECT 1 FROM review_round running
                      WHERE running.session_id = review_round.session_id
                        AND running.id <> review_round.id
                        AND running.status = 'RUNNING')
                  AND NOT EXISTS (
                      SELECT 1 FROM review_round earlier
                      WHERE earlier.session_id = review_round.session_id
                        AND earlier.id <> review_round.id
                        AND earlier.lifecycle_finalized = 0
                        AND earlier.rowid < review_round.rowid)
                """, turnId, operationId);
        String roundStatus = jdbc.query("""
                SELECT round.status
                FROM review_assignment_turn turn
                JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                JOIN review_round round ON round.id = assignment.round_id
                WHERE turn.id = ? AND turn.operation_id = ?
                """, (rs, row) -> rs.getString("status"), turnId, operationId)
                .stream().findFirst().orElse(null);
        if ("QUEUED".equals(roundStatus)) {
            return StartDisposition.ROUND_WAITING;
        }
        if (!"RUNNING".equals(roundStatus)) {
            return StartDisposition.STALE;
        }
        ExactTurn turn = find(turnId).orElse(null);
        if (turn == null
                || !operationId.equals(turn.operationId())
                || !"ACTIVE".equals(turn.reviewStatus())
                || turn.taskId() != null && !"ACTIVE".equals(turn.taskLifecycle())
                || !turn.startCommit().equals(turn.currentHeadSha())) {
            return StartDisposition.STALE;
        }
        int updated = jdbc.update("""
                UPDATE review_assignment_turn
                SET status = 'RUNNING', started_at_ms = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED')
                  AND started_at_ms IS NULL AND finished_at_ms IS NULL
                """, startedAt.toEpochMilli(), turnId, operationId);
        if (updated != 1) {
            return StartDisposition.STALE;
        }
        jdbc.update("""
                UPDATE review_assignment
                SET status = 'investigating'
                WHERE id = ? AND status = 'queued'
                """, turn.assignmentId());
        return StartDisposition.STARTED;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<McpOwner> findMcpOwner(
            String turnId,
            String operationId,
            Instant now)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        requireNonNull(now, "now is null");
        String sql = """
                SELECT round.session_id AS review_id, turn.assignment_id
                FROM review_assignment_turn turn
                JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                JOIN review_round round ON round.id = assignment.round_id
                JOIN review_session session ON session.id = round.session_id
                JOIN dispatch_ticket ticket
                  ON ticket.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
                 AND ticket.owner_id = turn.id
                 AND ticket.operation_id = turn.operation_id
                LEFT JOIN tasks task ON task.id = session.owner_task_id
                LEFT JOIN task_current_code_subject_v230 code
                  ON code.task_id = session.owner_task_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.status = 'RUNNING'
                  AND round.status = 'RUNNING'
                  AND session.status = 'ACTIVE'
                  AND ticket.status = 'RUNNING'
                  AND ticket.cancel_requested_at_ms IS NULL
                  AND ticket.claim_expires_at_ms > ?
                  AND turn.start_commit = %s
                  AND (session.owner_task_id IS NULL
                    OR (task.workflow_version = 'V2'
                      AND task.lifecycle_state = 'ACTIVE'))
                """.formatted(CURRENT_HEAD);
        return jdbc.query(sql, (rs, row) -> new McpOwner(
                rs.getString("review_id"), rs.getString("assignment_id")),
                turnId, operationId, now.toEpochMilli()).stream().findFirst();
    }

    @Override
    @Transactional
    public ResultReceipt accept(ResultCommand command)
    {
        requireNonNull(command, "command is null");
        Optional<StoredReceipt> existing = receipt(command.operationId());
        if (existing.isPresent()) {
            StoredReceipt receipt = existing.orElseThrow();
            if (!receipt.rawResultDigest().equals(command.rawResultDigest())) {
                return new ResultReceipt(
                        DispatchTicket.Acceptance.REJECTED,
                        rejectionEvidence(
                                "duplicate operation has different raw evidence"));
            }
            return new ResultReceipt(
                    DispatchTicket.Acceptance.valueOf(receipt.acceptance()),
                    receipt.evidenceJson());
        }

        ResultOwner owner = resultOwner(command.turnId()).orElse(null);
        if (owner == null) {
            return new ResultReceipt(
                    DispatchTicket.Acceptance.REJECTED,
                    rejectionEvidence("review result has no exact Turn owner"));
        }
        boolean exact = owner.operationId().equals(command.operationId())
                && owner.attempt() == command.attempt()
                && owner.startCommit().equals(command.expectedHeadSha())
                && equal(owner.taskEpoch(), command.taskEpoch());
        boolean current = exact
                && "ACTIVE".equals(owner.reviewStatus())
                && "RUNNING".equals(owner.roundStatus())
                && owner.startCommit().equals(owner.currentHeadSha())
                && (owner.taskId() == null || "ACTIVE".equals(owner.taskLifecycle()));
        DispatchTicket.Acceptance acceptance = current
                ? DispatchTicket.Acceptance.ACCEPTED
                : DispatchTicket.Acceptance.SUPERSEDED;
        String terminal = current ? terminal(command.outcome()) : "SUPERSEDED";
        int transitioned = jdbc.update("""
                UPDATE review_assignment_turn
                SET status = ?, finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """, terminal, command.recordedAt().toEpochMilli(), command.error(),
                command.turnId(), command.operationId());
        if (transitioned != 1) {
            throw new IllegalStateException("review Turn result is stale or already terminal");
        }

        if (acceptance == DispatchTicket.Acceptance.ACCEPTED) {
            projectAssignment(owner.assignmentId(), terminal, command.finalText());
        }
        String receiptId = "review-result:" + UUID.randomUUID();
        String evidence = evidence(receiptId, owner.roundId(), terminal);
        jdbc.update("""
                INSERT INTO review_assignment_turn_result_receipt(
                    id, turn_id, assignment_id, round_id, review_id,
                    operation_id, attempt, start_commit, raw_result_digest,
                    raw_outcome, disposition, acceptance, terminal_status,
                    final_text, input_tokens, output_tokens, cost_usd_milli,
                    provider_session_id, payload_json, evidence_json,
                    error_message, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receiptId, command.turnId(), owner.assignmentId(), owner.roundId(),
                owner.reviewId(), command.operationId(), command.attempt(),
                command.expectedHeadSha(), command.rawResultDigest(),
                command.outcome().name(),
                command.disposition() == null ? null : command.disposition().name(),
                acceptance.name(), terminal, command.finalText(), command.inputTokens(),
                command.outputTokens(), command.costUsdMilli(), command.providerSessionId(),
                command.payloadJson(), evidence, command.error(),
                command.recordedAt().toEpochMilli());
        projectRound(owner.roundId(), command.recordedAt());
        return new ResultReceipt(acceptance, evidence);
    }

    private Scope requireScope(String roundId, String startCommit)
    {
        String sql = """
                SELECT round.id AS round_id, session.workspace_id,
                       session.owner_thread_id, session.owner_task_id,
                       task.epoch AS task_epoch
                FROM review_round round
                JOIN review_session session ON session.id = round.session_id
                LEFT JOIN tasks task ON task.id = session.owner_task_id
                LEFT JOIN task_current_code_subject_v230 code
                  ON code.task_id = session.owner_task_id
                WHERE round.id = ? AND round.start_commit = ?
                  AND round.status IN ('QUEUED', 'RUNNING')
                  AND session.status = 'ACTIVE'
                  AND round.start_commit = %s
                  AND (session.owner_task_id IS NULL
                    OR (task.workflow_version = 'V2'
                      AND task.lifecycle_state = 'ACTIVE'
                      AND code.head_sha IS NOT NULL))
                """.formatted(CURRENT_HEAD);
        return jdbc.query(sql, (rs, row) -> new Scope(
                rs.getString("round_id"), rs.getString("workspace_id"),
                rs.getString("owner_thread_id"), rs.getString("owner_task_id"),
                nullableLong(rs, "task_epoch")), roundId, startCommit)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "review round, owner, or start commit is no longer current"));
    }

    private void insertAdmission(
            Scope scope,
            Admission admission,
            Instant requestedAt,
            boolean retry)
    {
        int assignment = count("""
                SELECT COUNT(*) FROM review_assignment
                WHERE id = ? AND round_id = ?
                """, admission.assignmentId(), scope.roundId());
        if (assignment != 1) {
            throw new IllegalStateException("review assignment does not belong to round");
        }
        int existing = count("""
                SELECT COUNT(*) FROM review_assignment_turn
                WHERE assignment_id = ?
                """, admission.assignmentId());
        if ((!retry && existing != 0) || (retry && existing == 0)) {
            throw new IllegalStateException("review assignment Turn admission is not exact");
        }
        jdbc.update("""
                INSERT INTO review_assignment_turn(
                    id, assignment_id, purpose, status, operation_id, attempt,
                    start_commit, delivery_lane, launch_input, requested_at_ms,
                    started_at_ms, finished_at_ms, error_message)
                VALUES (?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
                """, admission.turnId(), admission.assignmentId(), admission.purpose(),
                admission.operationId(), admission.attempt(), admission.startCommit(),
                admission.transport().name(), admission.launchInput(),
                requestedAt.toEpochMilli());
        int laneMask = 8 | (admission.transport()
                == AgentTurnProviderSession.Transport.CLI ? 1 : 2);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, version, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha, expected_base_sha,
                    status, next_attempt_at_ms, infrastructure_attempts, created_at_ms)
                VALUES (?, 0, ?, 'EXECUTE_REVIEW_ASSIGNMENT_TURN', 'AGENT_TURN',
                    'REVIEW_ASSIGNMENT_TURN', ?, 'REVIEW_ASSIGNMENT_TURN_RESULT', ?,
                    0, 0, 0, ?, ?, ?, ?, NULL, NULL, ?, NULL, ?, NULL,
                    'REQUESTED', ?, 0, ?)
                """, admission.ticketId(), admission.operationId(), admission.turnId(),
                laneMask, scope.workspaceId(), scope.trunkId(), scope.taskId(),
                scope.taskEpoch(), admission.attempt(), admission.startCommit(),
                requestedAt.toEpochMilli(), requestedAt.toEpochMilli());
        jdbc.update("""
                INSERT INTO review_assignment_turn_request_receipt(
                    id, turn_id, assignment_id, round_id, operation_id,
                    dispatch_ticket_id, attempt, start_commit, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "review-request:" + admission.turnId(), admission.turnId(),
                admission.assignmentId(), scope.roundId(), admission.operationId(),
                admission.ticketId(), admission.attempt(), admission.startCommit(),
                requestedAt.toEpochMilli());
        wakes.enqueue(admission.ticketId(), requestedAt);
    }

    private ExactTurn turn(ResultSet rs, int row)
            throws SQLException
    {
        return new ExactTurn(
                rs.getString("turn_id"), rs.getString("assignment_id"),
                rs.getString("round_id"), rs.getString("review_id"),
                rs.getString("purpose"), rs.getString("turn_status"),
                rs.getString("operation_id"), rs.getInt("attempt"),
                rs.getString("start_commit"), rs.getString("launch_input"),
                rs.getString("round_status"), rs.getString("review_status"),
                rs.getString("workspace_id"), rs.getString("owner_thread_id"),
                rs.getString("owner_task_id"), nullableLong(rs, "task_epoch"),
                rs.getString("task_lifecycle"), rs.getString("current_head_sha"));
    }

    private Optional<ResultOwner> resultOwner(String turnId)
    {
        String sql = """
                SELECT turn.assignment_id, assignment.round_id,
                       round.session_id AS review_id, turn.operation_id,
                       turn.attempt, turn.start_commit,
                       round.status AS round_status,
                       session.status AS review_status,
                       session.owner_task_id, task.epoch AS task_epoch,
                       task.lifecycle_state AS task_lifecycle,
                       %s AS current_head_sha
                FROM review_assignment_turn turn
                JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                JOIN review_round round ON round.id = assignment.round_id
                JOIN review_session session ON session.id = round.session_id
                LEFT JOIN tasks task ON task.id = session.owner_task_id
                LEFT JOIN task_current_code_subject_v230 code
                  ON code.task_id = session.owner_task_id
                WHERE turn.id = ?
                """.formatted(CURRENT_HEAD);
        return jdbc.query(sql, (rs, row) -> new ResultOwner(
                rs.getString("assignment_id"), rs.getString("round_id"),
                rs.getString("review_id"), rs.getString("operation_id"),
                rs.getInt("attempt"), rs.getString("start_commit"),
                rs.getString("round_status"), rs.getString("review_status"),
                rs.getString("owner_task_id"), nullableLong(rs, "task_epoch"),
                rs.getString("task_lifecycle"), rs.getString("current_head_sha")),
                turnId).stream().findFirst();
    }

    private Optional<StoredReceipt> receipt(String operationId)
    {
        return jdbc.query("""
                SELECT raw_result_digest, acceptance,
                       COALESCE(evidence_json, '{}') AS evidence_json
                FROM review_assignment_turn_result_receipt
                WHERE operation_id = ?
                """, (rs, row) -> new StoredReceipt(
                        rs.getString("raw_result_digest"),
                        rs.getString("acceptance"),
                        rs.getString("evidence_json")), operationId)
                .stream().findFirst();
    }

    private void projectAssignment(
            String assignmentId,
            String terminal,
            String finalText)
    {
        String status = switch (terminal) {
            case "SUCCEEDED" -> "completed";
            case "CANCELED" -> "cancelled";
            default -> "errored";
        };
        String fallback = switch (terminal) {
            case "SUCCEEDED" -> finalText == null || finalText.isBlank()
                    ? "Investigation complete." : finalText.strip();
            case "CANCELED" -> "Investigation cancelled.";
            default -> "Investigation failed.";
        };
        jdbc.update("""
                UPDATE review_assignment
                SET status = ?, understanding_summary = CASE
                    WHEN understanding_summary = '' THEN ? ELSE understanding_summary END
                WHERE id = ?
                """, status, fallback, assignmentId);
    }

    private void projectRound(String roundId, Instant recordedAt)
    {
        jdbc.update("""
                UPDATE review_round
                SET cost_cents = (
                    SELECT CAST((COALESCE(SUM(receipt.cost_usd_milli), 0) + 9) / 10 AS INTEGER)
                    FROM review_assignment_turn_result_receipt receipt
                    WHERE receipt.round_id = review_round.id
                      AND receipt.acceptance = 'ACCEPTED')
                WHERE id = ?
                """, roundId);
        int liveLatest = count("""
                SELECT COUNT(*)
                FROM review_assignment assignment
                WHERE assignment.round_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM review_assignment_turn turn
                      WHERE turn.assignment_id = assignment.id
                        AND turn.attempt = (
                            SELECT MAX(latest.attempt)
                            FROM review_assignment_turn latest
                            WHERE latest.assignment_id = assignment.id)
                        AND turn.status IN (
                            'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED'))
                """, roundId);
        if (liveLatest > 0) {
            return;
        }
        int failures = latestStatusCount(roundId, "FAILED");
        int cancellations = latestStatusCount(roundId, "CANCELED");
        int superseded = latestStatusCount(roundId, "SUPERSEDED");
        if (failures > 0 || cancellations > 0 || superseded > 0) {
            String roundStatus = failures > 0 || superseded > 0
                    ? "ERRORED" : "CANCELLED";
            String runStatus = failures > 0 || superseded > 0
                    ? "failed" : "cancelled";
            jdbc.update("""
                    UPDATE review_round
                    SET status = CASE WHEN status = 'RUNNING' THEN ? ELSE status END,
                        end_commit = NULL, finished_at_ms = COALESCE(finished_at_ms, ?),
                        message_gate_open = 0, lifecycle_finalized = 1
                    WHERE id = ? AND status IN ('RUNNING', 'ERRORED', 'CANCELLED')
                    """, roundStatus, recordedAt.toEpochMilli(), roundId);
            jdbc.update("""
                    UPDATE agent_run
                    SET status = ?, headline = ?, finished_at_ms = ?
                    WHERE id = (SELECT agent_run_id FROM review_round WHERE id = ?)
                      AND status IN ('queued', 'running')
                    """, runStatus,
                    failures > 0 || superseded > 0
                            ? "Review investigation failed" : "Review cancelled",
                    recordedAt.toEpochMilli(), roundId);
            return;
        }

        jdbc.update("""
                UPDATE finding
                SET verification_status = 'unknown',
                    last_checked_commit = (
                        SELECT start_commit FROM review_round WHERE id = ?)
                WHERE round_id = ? AND lifecycle_status <> 'dropped'
                """, roundId, roundId);
        jdbc.update("""
                UPDATE review_objective
                SET resolution_status = CASE
                    WHEN applicability_status <> 'applicable' THEN 'not-applicable'
                    WHEN EXISTS (
                        SELECT 1 FROM finding
                        WHERE finding.objective_id = review_objective.id
                          AND finding.lifecycle_status <> 'dropped') THEN 'unknown'
                    WHEN EXISTS (
                        SELECT 1
                        FROM hypothesis
                        JOIN review_assignment assignment
                          ON assignment.id = hypothesis.assignment_id
                        WHERE assignment.round_id = review_objective.round_id
                          AND hypothesis.objective_id = review_objective.id)
                        THEN 'investigated-clean'
                    ELSE 'not-covered-budget'
                END
                WHERE round_id = ?
                """, roundId);
        int questions = count("""
                SELECT COUNT(*) FROM review_objective
                WHERE round_id = ?
                  AND resolution_status IN ('unknown', 'not-covered-budget')
                """, roundId);
        String status = questions > 0 ? "COMPLETED_WITH_QUESTIONS" : "COMPLETED";
        jdbc.update("""
                UPDATE review_round
                SET status = ?, end_commit = start_commit, finished_at_ms = ?,
                    message_gate_open = 0, lifecycle_finalized = 1
                WHERE id = ? AND status = 'RUNNING'
                """, status, recordedAt.toEpochMilli(), roundId);
        jdbc.update("""
                UPDATE review_session
                SET reviewed_head_commit = (
                        SELECT start_commit FROM review_round WHERE id = ?),
                    status = 'ACTIVE', updated_at_ms = ?
                WHERE id = (SELECT session_id FROM review_round WHERE id = ?)
                """, roundId, recordedAt.toEpochMilli(), roundId);
        jdbc.update("""
                UPDATE agent_run
                SET status = 'succeeded',
                    headline = (SELECT COUNT(*) || ' findings'
                        FROM finding WHERE round_id = ?),
                    cost_usd_milli = (SELECT cost_cents * 10
                        FROM review_round WHERE id = ?),
                    tokens_in = (SELECT COALESCE(SUM(input_tokens), 0)
                        FROM review_assignment_turn_result_receipt WHERE round_id = ?),
                    tokens_out = (SELECT COALESCE(SUM(output_tokens), 0)
                        FROM review_assignment_turn_result_receipt WHERE round_id = ?),
                    finished_at_ms = ?
                WHERE id = (SELECT agent_run_id FROM review_round WHERE id = ?)
                  AND status IN ('queued', 'running')
                """, roundId, roundId, roundId, roundId,
                recordedAt.toEpochMilli(), roundId);
    }

    private int latestStatusCount(String roundId, String status)
    {
        return count("""
                SELECT COUNT(*)
                FROM review_assignment assignment
                JOIN review_assignment_turn turn ON turn.assignment_id = assignment.id
                WHERE assignment.round_id = ? AND turn.status = ?
                  AND turn.attempt = (
                      SELECT MAX(latest.attempt)
                      FROM review_assignment_turn latest
                      WHERE latest.assignment_id = assignment.id)
                """, roundId, status);
    }

    private String evidence(String receiptId, String roundId, String terminal)
    {
        ObjectNode node = json.createObjectNode();
        if (receiptId != null) {
            node.put("receiptId", receiptId);
        }
        if (roundId != null) {
            node.put("roundId", roundId);
        }
        if (terminal != null) {
            node.put("terminalStatus", terminal);
        }
        return node.toString();
    }

    private String rejectionEvidence(String reason)
    {
        return json.createObjectNode().put("reason", reason).toString();
    }

    private int count(String sql, Object... arguments)
    {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private static String terminal(DispatchTicket.Outcome outcome)
    {
        return switch (outcome) {
            case SUCCEEDED -> "SUCCEEDED";
            case CANCELED -> "CANCELED";
            case FAILED, INDETERMINATE -> "FAILED";
        };
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static boolean equal(Long left, Long right)
    {
        return left == null ? right == null : left.equals(right);
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    private record Scope(
            String roundId,
            String workspaceId,
            String trunkId,
            String taskId,
            Long taskEpoch) {}

    private record RetryState(String roundId, int attempt, String status) {}

    private record ResultOwner(
            String assignmentId,
            String roundId,
            String reviewId,
            String operationId,
            int attempt,
            String startCommit,
            String roundStatus,
            String reviewStatus,
            String taskId,
            Long taskEpoch,
            String taskLifecycle,
            String currentHeadSha) {}

    private record StoredReceipt(
            String rawResultDigest,
            String acceptance,
            String evidenceJson) {}
}
