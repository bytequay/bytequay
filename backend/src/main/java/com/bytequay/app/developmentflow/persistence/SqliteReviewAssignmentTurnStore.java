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
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.ContinuationCandidate;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.FlowPhase;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.RetryCandidate;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.RoundFlow;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.TurnState;
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
import java.util.Set;
import java.util.UUID;

import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.ROUND_GUIDANCE;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.guidanceMessageId;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.guidanceTarget;
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
        jdbc.update("""
                INSERT OR IGNORE INTO review_round_followup_v262(
                    round_id, start_commit, phase, created_at_ms, updated_at_ms)
                VALUES (?, ?, 'PRIMARY', ?, ?)
                """, roundId, startCommit, requestedAt.toEpochMilli(),
                requestedAt.toEpochMilli());
        for (Admission admission : admissions) {
            insertAdmission(scope, admission, requestedAt, false);
        }
    }

    @Override
    @Transactional
    public String admitFollowUp(
            String roundId,
            String startCommit,
            Admission admission,
            Instant requestedAt)
    {
        requireText(roundId, "roundId");
        requireText(startCommit, "startCommit");
        requireNonNull(admission, "admission is null");
        requireNonNull(requestedAt, "requestedAt is null");
        Scope scope = requireScope(roundId, startCommit);
        String existing = logicalTurnId(admission).orElse(null);
        if (existing != null) {
            return existing;
        }
        String phase = jdbc.queryForObject("""
                SELECT phase FROM review_round_followup_v262 WHERE round_id = ?
                """, String.class, roundId);
        boolean eligible = switch (admission.purpose()) {
            case ROUND_GUIDANCE -> "PRIMARY".equals(phase)
                    && exactGuidance(admission);
            case "self-refutation" -> "SELF_REFUTATION".equals(phase)
                    && hasPrimaryAssignment(admission.assignmentId());
            case "blind-reconstruction" -> "VERIFYING".equals(phase)
                    && exactVerifierAssignment(roundId, admission.assignmentId());
            case "independent-verification" -> "VERIFYING".equals(phase)
                    && exactVerifier(roundId, admission);
            default -> false;
        };
        if (!eligible) {
            throw new IllegalStateException(
                    "review follow-up purpose is not eligible in phase " + phase);
        }
        insertAdmission(scope, admission, requestedAt, false);
        return admission.turnId();
    }

    private boolean exactGuidance(Admission admission)
    {
        return count("""
                SELECT COUNT(*)
                FROM review_round_message message
                JOIN review_assignment assignment
                  ON assignment.id = message.assignment_id
                WHERE message.id = ? AND message.target = ?
                  AND message.status = 'processing'
                  AND message.round_id = assignment.round_id
                  AND assignment.id = ?
                """, guidanceMessageId(admission.subjectKey()),
                guidanceTarget(admission.subjectKey()),
                admission.assignmentId()) == 1;
    }

    private boolean hasPrimaryAssignment(String assignmentId)
    {
        return count("""
                SELECT COUNT(*) FROM review_assignment_turn
                WHERE assignment_id = ? AND purpose = 'investigate'
                """, assignmentId) > 0;
    }

    private boolean exactVerifierAssignment(String roundId, String assignmentId)
    {
        return count("""
                SELECT COUNT(*) FROM review_round_followup_v262
                WHERE round_id = ? AND verifier_assignment_id = ?
                """, roundId, assignmentId) == 1;
    }

    private boolean exactVerifier(String roundId, Admission admission)
    {
        return count("""
                SELECT COUNT(*) FROM review_round_followup_v262
                WHERE round_id = ? AND verifier_assignment_id = ?
                  AND verifier_run_id = ?
                """, roundId, admission.assignmentId(),
                admission.verifierRunId()) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RetryCandidate> retryCandidate(String assignmentId)
    {
        requireText(assignmentId, "assignmentId");
        return jdbc.query("""
                SELECT turn.assignment_id, turn.start_commit, turn.purpose,
                       turn.subject_key, turn.verifier_run_id, turn.attempt,
                       turn.cost_cap_usd_milli, turn.launch_input
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
                      WHERE candidate.assignment_id = turn.assignment_id
                        AND candidate.purpose = turn.purpose
                        AND candidate.subject_key = turn.subject_key)
                ORDER BY turn.finished_at_ms DESC, turn.requested_at_ms DESC
                LIMIT 1
                """, (rs, row) -> new RetryCandidate(
                        rs.getString("assignment_id"),
                        rs.getString("start_commit"),
                        rs.getString("purpose"),
                        rs.getString("subject_key"),
                        rs.getString("verifier_run_id"),
                        rs.getInt("attempt"),
                        rs.getLong("cost_cap_usd_milli"),
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
                SELECT assignment.round_id, turn.attempt, turn.status,
                       turn.purpose, turn.subject_key
                FROM review_assignment assignment
                JOIN review_assignment_turn turn ON turn.assignment_id = assignment.id
                WHERE assignment.id = ?
                  AND turn.purpose = ? AND turn.subject_key = ?
                ORDER BY turn.attempt DESC
                LIMIT 1
                """, (rs, row) -> new RetryState(
                        rs.getString("round_id"), rs.getInt("attempt"),
                        rs.getString("status"), rs.getString("purpose"),
                        rs.getString("subject_key")), admission.assignmentId(),
                        admission.purpose(), admission.subjectKey())
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
                UPDATE review_assignment
                SET status = 'queued'
                WHERE id = ? AND status = 'errored'
                """, admission.assignmentId());
        jdbc.update("""
                UPDATE review_round_followup_v262
                SET phase = CASE ?
                        WHEN 'investigate' THEN 'PRIMARY'
                        WHEN 'round-guidance' THEN 'PRIMARY'
                        WHEN 'self-refutation' THEN 'SELF_REFUTATION'
                        ELSE 'VERIFYING' END,
                    version = version + 1, updated_at_ms = ?
                WHERE round_id = ? AND phase = 'BLOCKED'
                """, admission.purpose(), requestedAt.toEpochMilli(), state.roundId());
        Scope scope = requireScope(state.roundId(), admission.startCommit());
        insertAdmission(scope, admission, requestedAt, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> userWaitSuccessor(String waitKind, String waitId)
    {
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        return jdbc.query("""
                SELECT successor_turn_id
                FROM review_turn_user_wait_continuation_v265
                WHERE wait_kind = ? AND wait_id = ?
                """, (rs, row) -> rs.getString("successor_turn_id"),
                waitKind, waitId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContinuationCandidate> userWaitCandidate(
            String predecessorTurnId,
            String predecessorOperationId,
            String waitKind,
            String waitId)
    {
        requireText(predecessorTurnId, "predecessorTurnId");
        requireText(predecessorOperationId, "predecessorOperationId");
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        return jdbc.query("""
                SELECT turn.id AS turn_id, turn.operation_id,
                       assignment.round_id, turn.assignment_id,
                       turn.start_commit, turn.purpose, turn.subject_key,
                       turn.verifier_run_id, turn.attempt,
                       turn.cost_cap_usd_milli, turn.launch_input
                FROM review_assignment_turn turn
                JOIN review_assignment assignment
                  ON assignment.id = turn.assignment_id
                JOIN review_round round ON round.id = assignment.round_id
                JOIN review_session session ON session.id = round.session_id
                JOIN typed_user_wait_result result
                  ON result.operation_id = turn.operation_id
                JOIN dispatch_ticket ticket
                  ON ticket.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
                 AND ticket.owner_id = turn.id
                 AND ticket.operation_id = turn.operation_id
                LEFT JOIN tasks task ON task.id = session.owner_task_id
                LEFT JOIN task_current_code_subject_v230 code
                  ON code.task_id = session.owner_task_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.status = 'SUCCEEDED'
                  AND result.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
                  AND result.turn_id = turn.id
                  AND result.wait_kind = ? AND result.wait_id = ?
                  AND ticket.status = 'SUCCEEDED'
                  AND ticket.attempt = turn.attempt
                  AND ticket.expected_head_sha = turn.start_commit
                  AND round.status = 'RUNNING' AND session.status = 'ACTIVE'
                  AND turn.start_commit = %s
                  AND (session.owner_task_id IS NULL
                    OR (task.workflow_version = 'V2'
                      AND task.lifecycle_state = 'ACTIVE'
                      AND ticket.task_epoch = task.epoch))
                  AND ((? = 'QUESTION' AND EXISTS (
                        SELECT 1 FROM review_assignment_question question
                        WHERE question.id = ? AND question.turn_id = turn.id
                          AND question.state = 'ANSWERED'
                          AND question.continuation_state = 'READY'))
                    OR (? = 'PERMISSION' AND EXISTS (
                        SELECT 1 FROM permission_request permission
                        WHERE permission.id = ?
                          AND permission.turn_kind = 'REVIEW_ASSIGNMENT'
                          AND permission.turn_id = turn.id
                          AND permission.operation_id = turn.operation_id
                          AND permission.state <> 'OPEN'
                          AND permission.continuation_state = 'READY')))
                """.formatted(CURRENT_HEAD), (rs, row) ->
                        new ContinuationCandidate(
                                rs.getString("turn_id"),
                                rs.getString("operation_id"),
                                rs.getString("round_id"),
                                rs.getString("assignment_id"),
                                rs.getString("start_commit"),
                                rs.getString("purpose"),
                                rs.getString("subject_key"),
                                rs.getString("verifier_run_id"),
                                rs.getInt("attempt"),
                                rs.getLong("cost_cap_usd_milli"),
                                rs.getString("launch_input")),
                predecessorTurnId, predecessorOperationId, waitKind, waitId,
                waitKind, waitId, waitKind, waitId).stream().findFirst();
    }

    @Override
    @Transactional
    public String continueUserWait(
            ContinuationCandidate predecessor,
            String waitKind,
            String waitId,
            Admission admission,
            Instant requestedAt)
    {
        requireNonNull(predecessor, "predecessor is null");
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        requireNonNull(admission, "admission is null");
        requireNonNull(requestedAt, "requestedAt is null");
        Optional<String> existing = userWaitSuccessor(waitKind, waitId);
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(admission.turnId())) {
                throw new IllegalStateException(
                        "review wait already names another successor");
            }
            return existing.orElseThrow();
        }
        ContinuationCandidate current = userWaitCandidate(
                        predecessor.turnId(), predecessor.operationId(),
                        waitKind, waitId)
                .orElseThrow(() -> new IllegalStateException(
                        "review user-wait predecessor is stale"));
        if (!current.equals(predecessor)) {
            throw new IllegalStateException(
                    "review user-wait predecessor changed before admission");
        }
        Scope scope = requireScope(predecessor.roundId(), predecessor.startCommit());
        insertAdmission(scope, admission, requestedAt, true);
        jdbc.update("""
                INSERT INTO review_turn_user_wait_continuation_v265(
                    wait_kind, wait_id, predecessor_turn_id,
                    predecessor_operation_id, successor_turn_id,
                    successor_operation_id, admitted_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, waitKind, waitId, predecessor.turnId(),
                predecessor.operationId(), admission.turnId(),
                admission.operationId(), requestedAt.toEpochMilli());
        return admission.turnId();
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
    @Transactional
    public void cancelFlow(String roundId, Instant canceledAt)
    {
        requireText(roundId, "roundId");
        requireNonNull(canceledAt, "canceledAt is null");
        jdbc.update("""
                UPDATE review_round_followup_v262
                SET phase = 'CANCELED', version = version + 1, updated_at_ms = ?
                WHERE round_id = ?
                  AND phase NOT IN ('COMPLETED', 'CANCELED')
                  AND EXISTS (
                      SELECT 1 FROM review_round round
                      WHERE round.id = review_round_followup_v262.round_id
                        AND round.status = 'CANCELLED')
                """, canceledAt.toEpochMilli(), roundId);
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
    public Optional<RoundFlow> flow(String roundId)
    {
        requireText(roundId, "roundId");
        return jdbc.query("""
                SELECT round_id, start_commit, phase, verifier_assignment_id,
                       verifier_run_id, version
                FROM review_round_followup_v262
                WHERE round_id = ?
                """, (rs, row) -> new RoundFlow(
                rs.getString("round_id"), rs.getString("start_commit"),
                FlowPhase.valueOf(rs.getString("phase")),
                rs.getString("verifier_assignment_id"),
                rs.getString("verifier_run_id"), rs.getLong("version")), roundId)
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> incompleteRoundIds()
    {
        return jdbc.queryForList("""
                SELECT round_id
                FROM review_round_followup_v262
                WHERE phase IN ('PRIMARY', 'SELF_REFUTATION', 'VERIFYING', 'FINALIZING')
                ORDER BY created_at_ms, round_id
                """, String.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TurnState> turns(String roundId)
    {
        requireText(roundId, "roundId");
        return jdbc.query("""
                SELECT turn.id, turn.assignment_id, turn.purpose,
                       turn.subject_key, turn.verifier_run_id, turn.attempt,
                       turn.status, turn.launch_input,
                       COALESCE(receipt.final_text, '') AS final_text,
                       COALESCE(receipt.input_tokens, 0) AS input_tokens,
                       COALESCE(receipt.output_tokens, 0) AS output_tokens,
                       COALESCE(receipt.cost_usd_milli, 0) AS cost_usd_milli
                FROM review_assignment_turn turn
                JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                LEFT JOIN review_assignment_turn_result_receipt receipt
                  ON receipt.turn_id = turn.id
                 AND receipt.acceptance = 'ACCEPTED'
                WHERE assignment.round_id = ?
                  AND turn.attempt = (
                      SELECT MAX(latest.attempt)
                      FROM review_assignment_turn latest
                      WHERE latest.assignment_id = turn.assignment_id
                        AND latest.purpose = turn.purpose
                        AND latest.subject_key = turn.subject_key)
                ORDER BY turn.requested_at_ms, turn.id
                """, (rs, row) -> new TurnState(
                rs.getString("id"), rs.getString("assignment_id"),
                rs.getString("purpose"), rs.getString("subject_key"),
                rs.getString("verifier_run_id"), rs.getInt("attempt"),
                rs.getString("status"), rs.getString("launch_input"),
                rs.getString("final_text"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getLong("cost_usd_milli")),
                roundId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> roundId(String turnId)
    {
        requireText(turnId, "turnId");
        return jdbc.query("""
                SELECT assignment.round_id
                FROM review_assignment_turn turn
                JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                WHERE turn.id = ?
                """, (rs, row) -> rs.getString("round_id"), turnId)
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public long remainingCostUsdMilli(String roundId)
    {
        requireText(roundId, "roundId");
        return jdbc.query("""
                SELECT MAX(0,
                    CAST(COALESCE(
                        json_extract(round.budget_json, '$.cost_cap_cents'),
                        json_extract(round.budget_json, '$.costCapCents')) AS INTEGER) * 10
                    - COALESCE((
                        SELECT SUM(receipt.cost_usd_milli)
                        FROM review_assignment_turn_result_receipt receipt
                        WHERE receipt.round_id = round.id), 0)
                    - COALESCE((
                        SELECT SUM(turn.cost_cap_usd_milli)
                        FROM review_assignment_turn turn
                        JOIN review_assignment assignment
                          ON assignment.id = turn.assignment_id
                        WHERE assignment.round_id = round.id
                          AND turn.status IN (
                              'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')), 0))
                FROM review_round round
                WHERE round.id = ?
                """, (rs, row) -> rs.getLong(1), roundId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "review round does not exist"));
    }

    @Override
    @Transactional(readOnly = true)
    public long protectedCostUsdMilli(String roundId)
    {
        requireText(roundId, "roundId");
        return jdbc.query("""
                SELECT COALESCE((
                        SELECT SUM(receipt.cost_usd_milli)
                        FROM review_assignment_turn_result_receipt receipt
                        WHERE receipt.round_id = round.id), 0)
                    + COALESCE((
                        SELECT SUM(turn.cost_cap_usd_milli)
                        FROM review_assignment_turn turn
                        JOIN review_assignment assignment
                          ON assignment.id = turn.assignment_id
                        WHERE assignment.round_id = round.id
                          AND turn.status IN (
                              'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')), 0)
                FROM review_round round
                WHERE round.id = ?
                """, (rs, row) -> rs.getLong(1), roundId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "review round does not exist"));
    }

    @Override
    @Transactional
    public boolean movePhase(
            String roundId, FlowPhase expected, FlowPhase next, Instant changedAt)
    {
        requireText(roundId, "roundId");
        requireNonNull(expected, "expected is null");
        requireNonNull(next, "next is null");
        requireNonNull(changedAt, "changedAt is null");
        if (!legalPhaseMove(expected, next)) {
            throw new IllegalArgumentException(
                    "illegal review follow-up phase " + expected + " -> " + next);
        }
        return jdbc.update("""
                UPDATE review_round_followup_v262
                SET phase = ?, version = version + 1, updated_at_ms = ?
                WHERE round_id = ? AND phase = ?
                """, next.name(), changedAt.toEpochMilli(), roundId,
                expected.name()) == 1;
    }

    @Override
    @Transactional
    public void bindVerifier(
            String roundId, String assignmentId, String runId, Instant changedAt)
    {
        requireText(roundId, "roundId");
        requireText(assignmentId, "assignmentId");
        requireText(runId, "runId");
        requireNonNull(changedAt, "changedAt is null");
        int updated = jdbc.update("""
                UPDATE review_round_followup_v262
                SET verifier_assignment_id = ?, verifier_run_id = ?,
                    version = version + 1, updated_at_ms = ?
                WHERE round_id = ? AND phase = 'VERIFYING'
                  AND verifier_assignment_id IS NULL AND verifier_run_id IS NULL
                  AND EXISTS (
                      SELECT 1 FROM review_assignment assignment
                      WHERE assignment.id = ? AND assignment.round_id = ?)
                  AND EXISTS (
                      SELECT 1 FROM agent_run run
                      WHERE run.id = ? AND run.review_round_id = ?)
                """, assignmentId, runId, changedAt.toEpochMilli(), roundId,
                assignmentId, roundId, runId, roundId);
        if (updated == 1) {
            return;
        }
        RoundFlow current = flow(roundId).orElseThrow(() ->
                new IllegalStateException("review follow-up flow disappeared"));
        if (!assignmentId.equals(current.verifierAssignmentId())
                || !runId.equals(current.verifierRunId())) {
            throw new IllegalStateException("review verifier ownership is already bound");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExactTurn> find(String turnId)
    {
        requireText(turnId, "turnId");
        String sql = """
                SELECT turn.id AS turn_id, turn.assignment_id, assignment.round_id,
                       round.session_id AS review_id, turn.purpose,
                       turn.subject_key, turn.verifier_run_id,
                       turn.status AS turn_status, turn.operation_id, turn.attempt,
                       turn.start_commit, turn.launch_input,
                       turn.cost_cap_usd_milli,
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
                SELECT round.session_id AS review_id, turn.assignment_id,
                       turn.purpose, turn.subject_key, turn.verifier_run_id
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
                rs.getString("review_id"), rs.getString("assignment_id"),
                rs.getString("purpose"), rs.getString("subject_key"),
                rs.getString("verifier_run_id")),
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
        boolean exceeded = command.costUsdMilli() > owner.costCapUsdMilli();
        String terminal = current
                ? exceeded ? "FAILED" : terminal(command.outcome())
                : "SUPERSEDED";
        String error = exceeded
                ? "provider exceeded the frozen review Turn cost cap"
                : command.error();
        int transitioned = jdbc.update("""
                UPDATE review_assignment_turn
                SET status = ?, finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """, terminal, command.recordedAt().toEpochMilli(), error,
                command.turnId(), command.operationId());
        if (transitioned != 1) {
            throw new IllegalStateException("review Turn result is stale or already terminal");
        }

        if (acceptance == DispatchTicket.Acceptance.ACCEPTED) {
            projectAssignment(
                    owner.assignmentId(), owner.purpose(), terminal, command.finalText());
        }
        if (ROUND_GUIDANCE.equals(owner.purpose())
                && !"SUCCEEDED".equals(terminal)) {
            projectGuidanceFailure(owner, terminal, error, command.recordedAt());
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
                command.payloadJson(), evidence, error,
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
                WHERE assignment_id = ? AND purpose = ? AND subject_key = ?
                """, admission.assignmentId(), admission.purpose(),
                admission.subjectKey());
        if ((!retry && existing != 0) || (retry && existing == 0)) {
            throw new IllegalStateException("review assignment Turn admission is not exact");
        }
        long costCapUsdMilli = Math.min(
                admission.costCapUsdMilli(), remainingCostUsdMilli(scope.roundId()));
        if (costCapUsdMilli < 1) {
            throw new IllegalStateException("review round cost cap is exhausted");
        }
        jdbc.update("""
                INSERT INTO review_assignment_turn(
                    id, assignment_id, purpose, subject_key, verifier_run_id,
                    status, operation_id, attempt,
                    start_commit, delivery_lane, cost_cap_usd_milli,
                    launch_input, requested_at_ms,
                    started_at_ms, finished_at_ms, error_message)
                VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
                """, admission.turnId(), admission.assignmentId(), admission.purpose(),
                admission.subjectKey(), admission.verifierRunId(),
                admission.operationId(), admission.attempt(), admission.startCommit(),
                admission.transport().name(), costCapUsdMilli,
                admission.launchInput(),
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
                rs.getString("purpose"), rs.getString("subject_key"),
                rs.getString("verifier_run_id"), rs.getString("turn_status"),
                rs.getString("operation_id"), rs.getInt("attempt"),
                rs.getString("start_commit"), rs.getString("launch_input"),
                rs.getLong("cost_cap_usd_milli"),
                rs.getString("round_status"), rs.getString("review_status"),
                rs.getString("workspace_id"), rs.getString("owner_thread_id"),
                rs.getString("owner_task_id"), nullableLong(rs, "task_epoch"),
                rs.getString("task_lifecycle"), rs.getString("current_head_sha"));
    }

    private Optional<ResultOwner> resultOwner(String turnId)
    {
        String sql = """
                SELECT turn.assignment_id, assignment.round_id,
                       round.session_id AS review_id, turn.purpose, turn.subject_key,
                       turn.operation_id,
                       turn.attempt, turn.start_commit, turn.cost_cap_usd_milli,
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
                rs.getString("review_id"), rs.getString("purpose"),
                rs.getString("subject_key"),
                rs.getString("operation_id"),
                rs.getInt("attempt"), rs.getString("start_commit"),
                rs.getLong("cost_cap_usd_milli"),
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

    private Optional<String> logicalTurnId(Admission admission)
    {
        return jdbc.query("""
                SELECT id FROM review_assignment_turn
                WHERE assignment_id = ? AND purpose = ? AND subject_key = ?
                  AND attempt = ?
                """, (rs, row) -> rs.getString("id"), admission.assignmentId(),
                admission.purpose(), admission.subjectKey(), admission.attempt())
                .stream().findFirst();
    }

    private void projectAssignment(
            String assignmentId,
            String purpose,
        String terminal,
        String finalText)
    {
        if (!"investigate".equals(purpose) && "SUCCEEDED".equals(terminal)) {
            jdbc.update("""
                    UPDATE review_assignment
                    SET status = ?
                    WHERE id = ? AND status IN ('queued', 'verifying', 'completed')
                    """, Set.of("self-refutation", ROUND_GUIDANCE).contains(purpose)
                    ? "completed" : "verifying", assignmentId);
            return;
        }
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

    private void projectGuidanceFailure(
            ResultOwner owner, String terminal, String error, Instant recordedAt)
    {
        String response = switch (terminal) {
            case "CANCELED" -> "Guidance provider was cancelled before completing.";
            case "SUPERSEDED" -> "Guidance became stale before it could complete.";
            default -> "Guidance could not be processed: "
                    + (error == null || error.isBlank()
                    ? "provider failed" : error.strip());
        };
        jdbc.update("""
                UPDATE review_round_message
                SET status = 'failed', response = ?, completed_at_ms = ?
                WHERE id = ? AND assignment_id = ? AND status = 'processing'
                """, response, recordedAt.toEpochMilli(),
                guidanceMessageId(owner.subjectKey()), owner.assignmentId());
    }

    private void projectRound(String roundId, Instant recordedAt)
    {
        jdbc.update("""
                UPDATE review_round
                SET cost_cents = (
                    SELECT CAST((COALESCE(SUM(receipt.cost_usd_milli), 0) + 9) / 10 AS INTEGER)
                    FROM review_assignment_turn_result_receipt receipt
                    WHERE receipt.round_id = review_round.id)
                WHERE id = ?
                """, roundId);
        int liveLatest = count("""
                SELECT COUNT(*)
                FROM review_assignment_turn turn
                JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                WHERE assignment.round_id = ?
                  AND turn.attempt = (
                      SELECT MAX(latest.attempt)
                      FROM review_assignment_turn latest
                      WHERE latest.assignment_id = turn.assignment_id
                        AND latest.purpose = turn.purpose
                        AND latest.subject_key = turn.subject_key)
                  AND turn.status NOT IN (
                      'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
                """, roundId);
        if (liveLatest > 0) {
            return;
        }
        int failures = latestStatusCount(roundId, "FAILED", false);
        int cancellations = latestStatusCount(roundId, "CANCELED", false);
        int superseded = latestStatusCount(roundId, "SUPERSEDED", true);
        if (failures > 0 || cancellations > 0 || superseded > 0) {
            String roundStatus = failures > 0 || superseded > 0
                    ? "ERRORED" : "CANCELLED";
            jdbc.update("""
                    UPDATE review_round
                    SET status = CASE WHEN status = 'RUNNING' THEN ? ELSE status END,
                        end_commit = NULL, finished_at_ms = COALESCE(finished_at_ms, ?),
                        message_gate_open = 0, lifecycle_finalized = 1
                    WHERE id = ? AND status IN ('RUNNING', 'ERRORED', 'CANCELLED')
                    """, roundStatus, recordedAt.toEpochMilli(), roundId);
            jdbc.update("""
                    UPDATE review_round_followup_v262
                    SET phase = CASE WHEN (
                            SELECT status FROM review_round WHERE id = ?)
                            = 'CANCELLED' THEN 'CANCELED' ELSE 'BLOCKED' END,
                        version = version + 1, updated_at_ms = ?
                    WHERE round_id = ? AND phase NOT IN ('COMPLETED', 'CANCELED')
                    """, roundId, recordedAt.toEpochMilli(), roundId);
            return;
        }

    }

    private int latestStatusCount(
            String roundId, String status, boolean includeGuidance)
    {
        return count("""
                SELECT COUNT(*)
                FROM review_assignment assignment
                JOIN review_assignment_turn turn ON turn.assignment_id = assignment.id
                WHERE assignment.round_id = ? AND turn.status = ?
                  AND (? <> 0 OR turn.purpose <> 'round-guidance')
                  AND turn.attempt = (
                      SELECT MAX(latest.attempt)
                      FROM review_assignment_turn latest
                      WHERE latest.assignment_id = assignment.id
                        AND latest.purpose = turn.purpose
                        AND latest.subject_key = turn.subject_key)
                """, roundId, status, includeGuidance ? 1 : 0);
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

    private static boolean legalPhaseMove(FlowPhase expected, FlowPhase next)
    {
        return switch (expected) {
            case PRIMARY -> next == FlowPhase.SELF_REFUTATION
                    || next == FlowPhase.VERIFYING
                    || next == FlowPhase.BLOCKED
                    || next == FlowPhase.CANCELED;
            case SELF_REFUTATION -> next == FlowPhase.VERIFYING
                    || next == FlowPhase.BLOCKED
                    || next == FlowPhase.CANCELED;
            case VERIFYING -> next == FlowPhase.FINALIZING
                    || next == FlowPhase.BLOCKED
                    || next == FlowPhase.CANCELED;
            case FINALIZING -> next == FlowPhase.COMPLETED
                    || next == FlowPhase.BLOCKED
                    || next == FlowPhase.CANCELED;
            case COMPLETED, BLOCKED, CANCELED -> false;
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

    private record RetryState(
            String roundId,
            int attempt,
            String status,
            String purpose,
            String subjectKey) {}

    private record ResultOwner(
            String assignmentId,
            String roundId,
            String reviewId,
            String purpose,
            String subjectKey,
            String operationId,
            int attempt,
            String startCommit,
            long costCapUsdMilli,
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
