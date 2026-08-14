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
import com.bytequay.app.flow.runtime.FlowRuntime.StaleClaimException;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleOwnerRevisionException;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentSession;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Owns the shared durable dispatch-ticket lifecycle.
 *
 * <p>Owner-specific admission and recovery predicates remain with their
 * runtime owners. This component owns the common operation/ticket invariants:
 * one atomic claim generation, live renewal, retry rearming, expiry discovery,
 * operation/ticket creation, and terminal settlement.
 */
final class DispatchRuntime
{
    private static final int RECONCILIATION_PRIORITY = 900;
    private static final int CI_FIX_PRIORITY = 800;
    private static final int UPSTREAM_SYNC_PRIORITY = 750;
    private static final int TASK_TURN_PRIORITY = 700;

    private final FlowRuntime runtime;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    DispatchRuntime(FlowRuntime runtime, JdbcTemplate jdbc, Clock clock)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Claims one eligible durable ticket and increments its generation. */
    Optional<Claim> claimNext(String workerId, Duration claimTtl)
    {
        return claimNext(workerId, claimTtl, null, Integer.MAX_VALUE);
    }

    /** Claims one exact wired local-runtime kind under the capacity bound. */
    Optional<Claim> claimNextForDispatch(
            Set<OperationKind> kinds,
            String workerId,
            Duration claimTtl,
            int capacity)
    {
        requireNonNull(kinds, "kinds is null");
        if (kinds.isEmpty()) {
            return Optional.empty();
        }
        Set<OperationKind> exactKinds = Set.copyOf(kinds);
        if (exactKinds.contains(OperationKind.PUBLISH)
                || exactKinds.contains(OperationKind.OBSERVE_CI)
                || exactKinds.contains(OperationKind.RUN_CI_LEARNING)) {
            throw new IllegalArgumentException(
                    "owner-specific kinds require their own claim path");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return claimNext(workerId, claimTtl, exactKinds, capacity);
    }

    Optional<Claim> claimNextCiAutofix(
            String workerId, Duration claimTtl, int capacity)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return runtime.inTransaction(() -> {
            if (activeDispatchCount() >= capacity) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            List<ClaimCandidate> candidates = jdbc.query(
                    """
                    SELECT o.operation_id, o.task_id, o.kind,
                           d.claim_generation
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    JOIN flow_runtime_task t ON t.task_id = o.task_id
                    JOIN flow_runtime_agent_session ts
                      ON ts.session_id = t.task_session_id
                    WHERE o.state = 'READY'
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                      AND t.status = 'ACTIVE'
                      AND t.waiting_mutation_state_ref IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM flow_runtime_operation po
                          WHERE po.task_id = t.task_id
                            AND po.kind = 'PUBLISH'
                            AND po.state IN (
                                'READY', 'CLAIMED', 'WAITING', 'RETRYABLE'
                            )
                      )
                      AND (
                          (o.kind = 'RECONCILE_TASK'
                           AND t.selected_writer_operation_id IS NULL
                           AND ts.state <> 'PARKED_CHILD'
                           AND EXISTS (
                               SELECT 1 FROM flow_runtime_inbox i
                               WHERE i.task_id = t.task_id
                                 AND i.selected_by_operation_id IS NULL
                                 AND i.handled_by_operation_id IS NULL
                                 AND i.terminal_reason IS NULL
                                 AND i.kind IN (
                                     'FINAL_RED', 'CI_FIX_READY',
                                     'AGENT_RESULT_READY'
                                 )
                                 AND (i.kind <> 'AGENT_RESULT_READY'
                                      OR EXISTS (
                                          SELECT 1
                                          FROM flow_runtime_agent_run rr
                                          JOIN flow_runtime_reviewer_request q
                                            ON q.reviewer_operation_id =
                                                rr.operation_id
                                          WHERE rr.run_id = i.external_key
                                            AND q.intended_gate_kind =
                                                'CI_UPDATE'
                                            AND q.origin_ci_fix_pending_id
                                                IS NOT NULL
                                      ))
                           ))
                          OR (o.kind = 'RUN_CI_FIXER'
                              AND o.owner_kind IN ('CI_ROUND', 'CI_CLEANUP')
                              AND t.selected_writer_operation_id =
                                  o.operation_id)
                          OR (o.kind = 'RUN_TASK_TURN'
                              AND t.selected_writer_operation_id =
                                  o.operation_id
                              AND (
                                  o.owner_kind = 'CI_ATTEMPT'
                                  OR (o.owner_kind = 'AGENT_RUN' AND EXISTS (
                                      SELECT 1
                                      FROM flow_runtime_agent_run rr
                                      JOIN flow_runtime_reviewer_request q
                                        ON q.reviewer_operation_id =
                                            rr.operation_id
                                      WHERE rr.run_id = o.owner_id
                                        AND q.intended_gate_kind = 'CI_UPDATE'
                                        AND q.origin_ci_fix_pending_id
                                            IS NOT NULL
                                  ))
                              ))
                          OR (o.kind = 'RUN_REVIEWER'
                              AND o.owner_kind = 'REVIEW_REQUEST'
                              AND t.selected_writer_operation_id IS NULL
                              AND ts.state = 'PARKED_CHILD'
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_writer_lease l
                                  WHERE l.task_id = t.task_id
                              )
                              AND EXISTS (
                                  SELECT 1
                                  FROM flow_runtime_reviewer_request q
                                  JOIN flow_runtime_operation p
                                    ON p.operation_id = q.parent_operation_id
                                  WHERE q.reviewer_operation_id =
                                            o.operation_id
                                    AND q.request_id = o.owner_id
                                    AND q.intended_gate_kind = 'CI_UPDATE'
                                    AND q.origin_ci_fix_pending_id IS NOT NULL
                                    AND p.state IN (
                                        'SUCCEEDED', 'FAILED', 'CANCELED'
                                    )
                                    AND p.result_ref IS NOT NULL
                              ))
                      )
                    ORDER BY d.priority DESC, d.not_before, o.operation_id
                    LIMIT 32
                    """,
                    (result, row) -> new ClaimCandidate(
                            result.getString("operation_id"),
                            result.getString("task_id"),
                            OperationKind.valueOf(result.getString("kind")),
                            result.getLong("claim_generation")),
                    now.toEpochMilli());
            for (ClaimCandidate candidate : candidates) {
                long generation = candidate.generation() + 1;
                String token = UUID.randomUUID().toString();
                Instant expiresAt = now.plus(claimTtl);
                int ticketUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_dispatch_ticket
                        SET claim_owner = ?, claim_expires_at = ?,
                            claim_generation = ?, claim_token = ?,
                            delivery_state = 'CLAIMED'
                        WHERE operation_id = ?
                          AND delivery_state = 'AVAILABLE'
                          AND claim_generation = ?
                        """,
                        workerId, expiresAt.toEpochMilli(), generation, token,
                        candidate.operationId(), candidate.generation());
                if (ticketUpdated == 0) {
                    continue;
                }
                int operationUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_operation
                        SET state = 'CLAIMED', attempt = attempt + 1
                        WHERE operation_id = ? AND state = 'READY'
                        """,
                        candidate.operationId());
                if (operationUpdated != 1) {
                    throw new IllegalStateException(
                            "CI ticket claimed without its READY operation");
                }
                return Optional.of(new Claim(
                        candidate.operationId(), candidate.taskId(),
                        candidate.kind(), generation, token, workerId,
                        expiresAt));
            }
            return Optional.empty();
        });
    }

    Optional<Claim> claimNextInitialTask(
            String workerId, Duration claimTtl, int capacity)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return runtime.inTransaction(() -> {
            if (activeDispatchCount() >= capacity) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            List<ClaimCandidate> candidates = jdbc.query(
                    """
                    SELECT o.operation_id, o.task_id, o.kind,
                           d.claim_generation
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    JOIN flow_runtime_task t ON t.task_id = o.task_id
                    JOIN flow_runtime_agent_session ts
                      ON ts.session_id = t.task_session_id
                    WHERE o.state = 'READY'
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                      AND t.status = 'ACTIVE'
                      AND t.waiting_mutation_state_ref IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM flow_runtime_operation po
                          WHERE po.task_id = t.task_id
                            AND po.kind = 'PUBLISH'
                            AND po.state IN (
                                'READY', 'CLAIMED', 'WAITING', 'RETRYABLE'
                            )
                      )
                      AND (
                          (o.kind = 'RECONCILE_TASK'
                           AND t.selected_writer_operation_id IS NULL
                           AND ts.state <> 'PARKED_CHILD'
                           AND EXISTS (
                               SELECT 1 FROM flow_runtime_inbox i
                               WHERE i.task_id = t.task_id
                                 AND i.selected_by_operation_id IS NULL
                                 AND i.handled_by_operation_id IS NULL
                                 AND i.terminal_reason IS NULL
                                 AND (
                                     (i.kind = 'INITIAL_TASK'
                                      AND i.intended_gate_kind =
                                            'INITIAL_PUBLISH'
                                      AND i.external_key = t.task_id
                                      AND i.pr_id IS NULL
                                      AND i.agent_result_id IS NULL
                                      AND i.payload_ref =
                                            'task-goal:' || t.task_id
                                      AND i.subject_head = t.current_head_sha)
                                     OR (i.kind = 'AGENT_RESULT_READY'
                                         AND i.intended_gate_kind =
                                            'INITIAL_PUBLISH'
                                         AND EXISTS (
                                             SELECT 1
                                             FROM flow_runtime_agent_run rr
                                             JOIN flow_runtime_reviewer_request q
                                               ON q.reviewer_operation_id =
                                                    rr.operation_id
                                             JOIN flow_runtime_agent_result ar
                                               ON ar.run_id = rr.run_id
                                             WHERE rr.run_id = i.external_key
                                               AND q.intended_gate_kind =
                                                    'INITIAL_PUBLISH'
                                               AND q.task_id = t.task_id
                                               AND ar.result_id =
                                                    i.agent_result_id
                                               AND i.payload_ref =
                                                    'reviewer-request:'
                                                    || q.request_id
                                                    || ':result:'
                                                    || ar.result_id
                                               AND i.subject_head =
                                                    q.reviewed_head_sha
                                         ))
                                 )
                           ))
                          OR (o.kind = 'RUN_TASK_TURN'
                              AND t.selected_writer_operation_id =
                                    o.operation_id
                              AND EXISTS (
                                  SELECT 1 FROM flow_runtime_inbox i
                                  WHERE i.selected_by_operation_id =
                                            o.operation_id
                                    AND i.handled_by_operation_id IS NULL
                                    AND i.terminal_reason IS NULL
                                    AND o.input_ref = 'inbox:' || i.inbox_id
                                    AND i.task_id = t.task_id
                                    AND i.intended_gate_kind =
                                        'INITIAL_PUBLISH'
                                    AND i.subject_head = t.current_head_sha
                                    AND (
                                        (o.owner_kind = 'TASK'
                                         AND o.owner_id = t.task_id
                                         AND i.kind = 'INITIAL_TASK'
                                         AND i.external_key = t.task_id
                                         AND i.pr_id IS NULL
                                         AND i.agent_result_id IS NULL
                                         AND i.payload_ref =
                                            'task-goal:' || t.task_id)
                                        OR (o.owner_kind = 'AGENT_RUN'
                                            AND i.kind =
                                                'AGENT_RESULT_READY'
                                            AND i.external_key = o.owner_id
                                            AND EXISTS (
                                                SELECT 1
                                                FROM flow_runtime_agent_run rr
                                                JOIN flow_runtime_reviewer_request q
                                                  ON q.reviewer_operation_id =
                                                       rr.operation_id
                                                JOIN flow_runtime_agent_result ar
                                                  ON ar.run_id = rr.run_id
                                                WHERE rr.run_id = o.owner_id
                                                  AND q.task_id = t.task_id
                                                  AND q.intended_gate_kind =
                                                    'INITIAL_PUBLISH'
                                                  AND ar.result_id =
                                                    i.agent_result_id
                                                  AND i.payload_ref =
                                                    'reviewer-request:'
                                                    || q.request_id
                                                    || ':result:'
                                                    || ar.result_id
                                                  AND i.subject_head =
                                                    q.reviewed_head_sha
                                            ))
                                    )
                              ))
                          OR (o.kind = 'UPSTREAM_SYNC'
                              AND o.owner_kind = 'UPSTREAM_RUN'
                              AND t.selected_writer_operation_id =
                                    o.operation_id
                              AND ts.state = 'IDLE'
                              AND EXISTS (
                                  SELECT 1
                                  FROM flow_runtime_inbox i
                                  WHERE i.selected_by_operation_id =
                                            o.operation_id
                                    AND i.handled_by_operation_id IS NULL
                                    AND i.terminal_reason IS NULL
                                    AND o.input_ref = 'inbox:' || i.inbox_id
                                    AND i.task_id = t.task_id
                                    AND i.kind = 'INITIAL_TASK'
                                    AND i.intended_gate_kind =
                                        'INITIAL_PUBLISH'
                              ))
                          OR (o.kind = 'RUN_REVIEWER'
                              AND o.owner_kind = 'REVIEW_REQUEST'
                              AND t.selected_writer_operation_id IS NULL
                              AND ts.state = 'PARKED_CHILD'
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_writer_lease l
                                  WHERE l.task_id = t.task_id
                              )
                              AND EXISTS (
                                  SELECT 1
                                  FROM flow_runtime_reviewer_request q
                                  JOIN flow_runtime_operation p
                                    ON p.operation_id = q.parent_operation_id
                                  WHERE q.reviewer_operation_id =
                                        o.operation_id
                                    AND q.request_id = o.owner_id
                                    AND q.intended_gate_kind =
                                        'INITIAL_PUBLISH'
                                    AND q.origin_ci_fix_pending_id IS NULL
                                    AND q.origin_ci_fix_source_kind IS NULL
                                    AND q.origin_ci_fix_source_id IS NULL
                                    AND p.state IN (
                                        'SUCCEEDED', 'FAILED', 'CANCELED'
                                    )
                                    AND p.result_ref IS NOT NULL
                              ))
                      )
                    ORDER BY d.priority DESC, d.not_before, o.operation_id
                    LIMIT 32
                    """,
                    (result, row) -> new ClaimCandidate(
                            result.getString("operation_id"),
                            result.getString("task_id"),
                            OperationKind.valueOf(result.getString("kind")),
                            result.getLong("claim_generation")),
                    now.toEpochMilli());
            for (ClaimCandidate candidate : candidates) {
                long generation = candidate.generation() + 1;
                String token = UUID.randomUUID().toString();
                Instant expiresAt = now.plus(claimTtl);
                int ticketUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_dispatch_ticket
                        SET claim_owner = ?, claim_expires_at = ?,
                            claim_generation = ?, claim_token = ?,
                            delivery_state = 'CLAIMED'
                        WHERE operation_id = ?
                          AND delivery_state = 'AVAILABLE'
                          AND claim_generation = ?
                        """,
                        workerId, expiresAt.toEpochMilli(), generation, token,
                        candidate.operationId(), candidate.generation());
                if (ticketUpdated == 0) {
                    continue;
                }
                int operationUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_operation
                        SET state = 'CLAIMED', attempt = attempt + 1
                        WHERE operation_id = ? AND state = 'READY'
                        """,
                        candidate.operationId());
                if (operationUpdated != 1) {
                    throw new IllegalStateException(
                            "INITIAL ticket claimed without READY operation");
                }
                return Optional.of(new Claim(
                        candidate.operationId(), candidate.taskId(),
                        candidate.kind(), generation, token, workerId,
                        expiresAt));
            }
            return Optional.empty();
        });
    }


    private Optional<Claim> claimNext(
            String workerId,
            Duration claimTtl,
            Set<OperationKind> exactKinds,
            int capacity)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        return runtime.inTransaction(() -> {
            if (activeDispatchCount() >= capacity) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            List<String> kindNames = exactKinds == null
                    ? List.of()
                    : exactKinds.stream()
                            .map(Enum::name)
                            .sorted()
                            .toList();
            String kindFilter = kindNames.isEmpty()
                    ? ""
                    : "AND o.kind IN ("
                            + String.join(", ", Collections.nCopies(
                                    kindNames.size(), "?"))
                            + ")";
            String claimSql = """
                    SELECT o.operation_id, o.task_id, o.kind,
                           d.claim_generation
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    LEFT JOIN flow_runtime_task t ON t.task_id = o.task_id
                    LEFT JOIN flow_runtime_agent_session ts
                      ON ts.session_id = t.task_session_id
                    WHERE o.state = 'READY'
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                      %s
                      AND (
                          o.kind = 'PROVISION_TASK'
                          OR (o.kind = 'RECONCILE_TASK'
                              AND t.status = 'ACTIVE'
                              AND t.waiting_mutation_state_ref IS NULL
                              AND t.selected_writer_operation_id IS NULL
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_operation po
                                  WHERE po.task_id = t.task_id
                                    AND po.kind = 'PUBLISH'
                                    AND po.state IN (
                                        'READY', 'CLAIMED', 'WAITING',
                                        'RETRYABLE'
                                    )
                              )
                              AND ts.state <> 'PARKED_CHILD'
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_operation ro
                                  WHERE ro.task_id = t.task_id
                                    AND ro.kind = 'RUN_REVIEWER'
                                    AND ro.state IN (
                                        'READY', 'CLAIMED', 'WAITING',
                                        'RETRYABLE'
                                    )
                              ))
                          OR (o.kind IN (
                                  'UPSTREAM_SYNC',
                                  'RUN_TASK_TURN',
                                  'RUN_CI_FIXER'
                              )
                              AND t.status = 'ACTIVE'
                              AND t.waiting_mutation_state_ref IS NULL
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_operation po
                                  WHERE po.task_id = t.task_id
                                    AND po.kind = 'PUBLISH'
                                    AND po.state IN (
                                        'READY', 'CLAIMED', 'WAITING',
                                        'RETRYABLE'
                                    )
                              )
                              AND t.selected_writer_operation_id = o.operation_id)
                          OR (o.kind = 'RUN_REVIEWER'
                              AND o.owner_kind = 'REVIEW_REQUEST'
                              AND t.status = 'ACTIVE'
                              AND t.waiting_mutation_state_ref IS NULL
                              AND t.selected_writer_operation_id IS NULL
                              AND ts.state = 'PARKED_CHILD'
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_writer_lease l
                                  WHERE l.task_id = t.task_id
                              )
                              AND EXISTS (
                                  SELECT 1
                                  FROM flow_runtime_reviewer_request q
                                  JOIN flow_runtime_operation p
                                    ON p.operation_id = q.parent_operation_id
                                  WHERE q.reviewer_operation_id = o.operation_id
                                    AND q.request_id = o.owner_id
                                    AND p.state IN (
                                        'SUCCEEDED', 'FAILED', 'CANCELED'
                                    )
                                    AND p.result_ref IS NOT NULL
                              ))
                      )
                    ORDER BY d.priority DESC, d.not_before, o.operation_id
                    LIMIT 32
                    """.formatted(kindFilter);
            List<Object> parameters = new ArrayList<>();
            parameters.add(now.toEpochMilli());
            parameters.addAll(kindNames);
            List<ClaimCandidate> candidates = jdbc.query(
                    claimSql,
                    (result, row) -> new ClaimCandidate(
                            result.getString("operation_id"),
                            result.getString("task_id"),
                            OperationKind.valueOf(result.getString("kind")),
                            result.getLong("claim_generation")),
                    parameters.toArray());
            for (ClaimCandidate candidate : candidates) {
                long generation = candidate.generation() + 1;
                String token = UUID.randomUUID().toString();
                Instant expiresAt = now.plus(claimTtl);
                int ticketUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_dispatch_ticket
                        SET claim_owner = ?, claim_expires_at = ?,
                            claim_generation = ?, claim_token = ?,
                            delivery_state = 'CLAIMED'
                        WHERE operation_id = ?
                          AND delivery_state = 'AVAILABLE'
                          AND claim_generation = ?
                        """,
                        workerId,
                        expiresAt.toEpochMilli(),
                        generation,
                        token,
                        candidate.operationId(),
                        candidate.generation());
                if (ticketUpdated == 0) {
                    continue;
                }
                int operationUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_operation
                        SET state = 'CLAIMED', attempt = attempt + 1
                        WHERE operation_id = ? AND state = 'READY'
                        """,
                        candidate.operationId());
                if (operationUpdated != 1) {
                    throw new IllegalStateException(
                            "ticket claimed without its READY operation");
                }
                return Optional.of(new Claim(
                        candidate.operationId(),
                        candidate.taskId(),
                        candidate.kind(),
                        generation,
                        token,
                        workerId,
                        expiresAt));
            }
            return Optional.empty();
        });
    }

    /** Counts shared-capacity work; read-only learners are excluded. */
    int activeDispatchCount()
    {
        Integer active = jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT operation_id)
                FROM (
                    SELECT o.operation_id
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    WHERE o.state = 'CLAIMED'
                      AND d.delivery_state = 'CLAIMED'
                      AND o.kind <> 'RUN_CI_LEARNING'
                    UNION ALL
                    SELECT p.operation_id
                    FROM flow_runtime_agent_process_attempt p
                    JOIN flow_runtime_agent_run r ON r.run_id = p.run_id
                    WHERE p.state = 'ACTIVATED'
                      AND p.quarantine_reason IS NULL
                      AND r.role <> 'CI_LEARNER'
                ) active
                """,
                Integer.class);
        return requireNonNull(active, "active dispatch count is null");
    }

    Optional<Operation> operation(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query(
                "SELECT * FROM flow_runtime_operation WHERE operation_id = ?",
                (result, row) -> readOperation(result),
                operationId).stream().findFirst();
    }

    List<PendingWork> pendingWork(String taskId)
    {
        requireText(taskId, "taskId");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_inbox
                WHERE task_id = ?
                ORDER BY work_watermark
                """,
                (result, row) -> readInbox(result),
                taskId);
    }

    /** Renews only the live current claim generation. */
    Claim renewClaim(Claim claim, Duration claimTtl)
    {
        requireNonNull(claim, "claim is null");
        requirePositive(claimTtl, "claimTtl");
        return runtime.inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Instant requestedExpiry = clock.instant().plus(claimTtl);
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET claim_expires_at = CASE
                        WHEN claim_expires_at > ? THEN claim_expires_at
                        ELSE ? END
                    WHERE operation_id = ? AND claim_generation = ?
                      AND claim_token = ? AND claim_owner = ?
                      AND delivery_state = 'CLAIMED'
                      AND claim_expires_at > ?
                    """,
                    requestedExpiry.toEpochMilli(),
                    requestedExpiry.toEpochMilli(),
                    claim.operationId(),
                    claim.generation(),
                    claim.claimToken(),
                    claim.workerId(),
                    clock.instant().toEpochMilli());
            if (updated != 1) {
                throw new StaleClaimException(
                        "claim generation changed during renewal");
            }
            Instant expiresAt = currentClaimExpiry(claim);
            return new Claim(
                    claim.operationId(),
                    claim.taskId(),
                    claim.kind(),
                    claim.generation(),
                    claim.claimToken(),
                    claim.workerId(),
                    expiresAt);
        });
    }

    /** Finds expired generations entirely from durable state after restart. */
    List<ExpiredClaim> expiredClaims()
    {
        return jdbc.query(
                """
                SELECT o.operation_id, o.task_id, o.kind,
                       d.claim_generation, d.claim_expires_at,
                       r.run_id, p.process_attempt_id, p.state process_state
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                LEFT JOIN flow_runtime_agent_run r
                  ON r.operation_id = o.operation_id
                LEFT JOIN flow_runtime_agent_process_attempt p
                  ON p.run_id = r.run_id
                 AND p.claim_generation = d.claim_generation
                WHERE o.state = 'CLAIMED'
                  AND d.delivery_state = 'CLAIMED'
                  AND d.claim_expires_at <= ?
                ORDER BY d.claim_expires_at, o.operation_id
                """,
                (result, row) -> new ExpiredClaim(
                        result.getString("operation_id"),
                        result.getString("task_id"),
                        OperationKind.valueOf(result.getString("kind")),
                        result.getLong("claim_generation"),
                        Instant.ofEpochMilli(
                                result.getLong("claim_expires_at")),
                        result.getString("run_id"),
                        result.getString("process_attempt_id"),
                        nullableProcessState(result.getString("process_state"))),
                clock.instant().toEpochMilli());
    }

    /**
     * Recovers only objectively never-launched work. Activated agent process
     * attempts are handed to the agent owner for quarantine and are not
     * redriven here.
     */
    boolean recoverExpiredClaim(String operationId, long generation)
    {
        requireText(operationId, "operationId");
        return runtime.inTransaction(() -> {
            Operation operation = runtime.requireOperation(operationId);
            if (operation.kind() == OperationKind.PUBLISH
                    || operation.kind() == OperationKind.OBSERVE_CI
                    || operation.kind() == OperationKind.PROVISION_TASK
                    || operation.kind()
                        == OperationKind.RUN_CI_LEARNING) {
                throw new IllegalArgumentException(
                        operation.kind() + " requires owner-specific recovery");
            }
            if (operation.state() == OperationState.RETRYABLE
                    && ticketMatches(
                            operationId, generation, "AVAILABLE")) {
                return true;
            }
            if (operation.state() == OperationState.FAILED
                    && ticketMatches(operationId, generation, "DONE")) {
                return false;
            }
            ExpiredClaim expired = requireExpiredClaim(operationId, generation);
            if (expired.processAttemptId() != null) {
                if (expired.kind() == OperationKind.RUN_REVIEWER
                        && expired.processAttemptState()
                                == ProcessAttemptState.RESERVED) {
                    recoverNeverLaunchedClaim(expired);
                    return true;
                }
                if (expired.kind() == OperationKind.RUN_REVIEWER) {
                    runtime.quarantineExpiredReviewerProcessAttempt(expired);
                }
                else {
                    runtime.quarantineExpiredProcessAttempt(expired);
                }
                return false;
            }
            recoverNeverLaunchedClaim(expired);
            return true;
        });
    }

    /** Makes one proven-dead retry generation eligible for claiming. */
    void redriveRetryable(String operationId)
    {
        requireText(operationId, "operationId");
        runtime.inTransaction(() -> {
            Operation operation = runtime.requireOperation(operationId);
            if (operation.state() == OperationState.READY) {
                return Boolean.TRUE;
            }
            Integer available = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_runtime_dispatch_ticket
                    WHERE operation_id = ? AND delivery_state = 'AVAILABLE'
                    """,
                    Integer.class,
                    operationId);
            if (operation.state() != OperationState.RETRYABLE
                    || requireNonNull(
                            available, "available ticket count is null") != 1) {
                throw new IllegalStateException(
                        "operation is not a never-launched retry generation");
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'READY'
                    WHERE operation_id = ? AND state = 'RETRYABLE'
                    """,
                    operationId);
            if (updated != 1) {
                throw new StaleClaimException(
                        "retry operation changed during redrive");
            }
            return Boolean.TRUE;
        });
    }

    Optional<Operation> selectNext(Claim claim)
    {
        return selectNext(claim, null);
    }

    Optional<Operation> selectNextInitial(Claim claim)
    {
        return selectNext(claim, GateIntent.INITIAL_PUBLISH);
    }

    private Optional<Operation> selectNext(
            Claim claim, GateIntent exactIntent)
    {
        requireNonNull(claim, "claim is null");
        return runtime.inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Operation reconciliation = runtime.requireOperation(claim.operationId());
            if (reconciliation.kind() != OperationKind.RECONCILE_TASK
                    || reconciliation.workWatermark() == null) {
                throw new IllegalArgumentException(
                        "claim does not own reconciliation");
            }
            int taskLocked = jdbc.update(
                    "UPDATE flow_runtime_task SET task_id = task_id "
                            + "WHERE task_id = ?",
                    reconciliation.taskId());
            if (taskLocked != 1) {
                throw new StaleOwnerRevisionException(
                        "reconciliation Task is unavailable");
            }
            Task task = runtime.requireTask(reconciliation.taskId());
            if (runtime.hasNonterminalPublish(task.taskId())) {
                parkReconciliation(reconciliation.operationId());
                return Optional.empty();
            }
            if (task.selectedWriterOperationId() != null) {
                throw new IllegalStateException(
                        "Task already has a selected writer");
            }
            if (task.status() != TaskStatus.ACTIVE) {
                if (task.status() == TaskStatus.WAITING_USER
                        || task.status() == TaskStatus.NEEDS_ATTENTION
                        || task.status() == TaskStatus.CREATED) {
                    parkReconciliation(reconciliation.operationId());
                    return Optional.empty();
                }
                jdbc.update(
                        """
                        UPDATE flow_runtime_inbox
                        SET handled_by_operation_id = ?
                        WHERE task_id = ? AND handled_by_operation_id IS NULL
                          AND work_watermark <= ?
                        """,
                        reconciliation.operationId(),
                        task.taskId(),
                        reconciliation.workWatermark());
                settleDispatch(
                        reconciliation.operationId(),
                        OperationState.CANCELED,
                        "TASK_NOT_ACTIVE");
                jdbc.update(
                        """
                        UPDATE flow_runtime_task
                        SET last_reconciled_work_watermark = MAX(
                            last_reconciled_work_watermark, ?)
                        WHERE task_id = ?
                        """,
                        reconciliation.workWatermark(),
                        task.taskId());
                return Optional.empty();
            }
            AgentSession taskSession = runtime.requireSession(task.taskSessionId());
            Integer liveReviewer = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_runtime_operation
                    WHERE task_id = ? AND kind = 'RUN_REVIEWER'
                      AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                    """,
                    Integer.class,
                    task.taskId());
            if (taskSession.state() == SessionState.PARKED_CHILD
                    || requireNonNull(
                            liveReviewer, "live reviewer count is null") > 0) {
                parkReconciliation(reconciliation.operationId());
                return Optional.empty();
            }

            Optional<Operation> selected = Optional.empty();
            List<PendingWork> pending = jdbc.query(
                    """
                    SELECT * FROM flow_runtime_inbox
                    WHERE task_id = ?
                      AND work_watermark <= ?
                      AND handled_by_operation_id IS NULL
                      AND selected_by_operation_id IS NULL
                      AND terminal_reason IS NULL
                    ORDER BY CASE kind
                        WHEN 'AGENT_RESULT_READY' THEN 1
                        WHEN 'CI_FIX_READY' THEN 2
                        WHEN 'FINAL_RED' THEN 3
                        WHEN 'INITIAL_TASK' THEN 4
                    END, work_watermark
                    """,
                    (result, row) -> readInbox(result),
                    task.taskId(),
                    reconciliation.workWatermark());
            for (PendingWork item : pending) {
                if (!pendingSubjectIsCurrent(task, item)) {
                    jdbc.update(
                            """
                            UPDATE flow_runtime_inbox
                            SET handled_by_operation_id = ?
                            WHERE inbox_id = ?
                              AND handled_by_operation_id IS NULL
                            """,
                            reconciliation.operationId(),
                            item.pendingId());
                    continue;
                }
                if (exactIntent == GateIntent.INITIAL_PUBLISH
                        && !isInitialPending(item)) {
                    continue;
                }

                OperationKind writerKind = item.kind() == PendingKind.FINAL_RED
                        ? OperationKind.RUN_CI_FIXER
                        : upstreamWriterKind(task, item);
                String ownerKind = switch (item.kind()) {
                    case FINAL_RED -> "CI_ROUND";
                    case CI_FIX_READY -> "CI_ATTEMPT";
                    case AGENT_RESULT_READY -> "AGENT_RUN";
                    case INITIAL_TASK -> "TASK";
                };
                String ownerId = item.externalKey();
                if (writerKind == OperationKind.UPSTREAM_SYNC) {
                    ownerKind = "UPSTREAM_RUN";
                    ownerId = upstreamRunId(task.taskId());
                }
                String subjectDigest = FlowRuntime.stableId(
                        "writer-subject",
                        task.taskId(),
                        Long.toString(task.epoch()),
                        item.kind().name(),
                        item.externalKey(),
                        // The exact fact, not just its owner: a re-armed
                        // INITIAL fact reuses the Task's own external key and
                        // head, and only its revision — carried by the pending
                        // id — separates the resume from the first attempt.
                        item.pendingId(),
                        item.subjectHead());
                String operationId = FlowRuntime.stableId(
                        "operation",
                        ownerKind,
                        ownerId,
                        writerKind.name(),
                        subjectDigest);
                insertOperationAndTicket(
                        operationId,
                        ownerKind,
                        ownerId,
                        task.taskId(),
                        writerKind,
                        subjectDigest,
                        "inbox:" + item.pendingId(),
                        null,
                        writerKind == OperationKind.RUN_CI_FIXER
                                ? CI_FIX_PRIORITY
                                : writerKind == OperationKind.UPSTREAM_SYNC
                                        ? UPSTREAM_SYNC_PRIORITY
                                        : TASK_TURN_PRIORITY,
                        clock.instant());
                int pointerUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_task
                        SET selected_writer_operation_id = ?
                        WHERE task_id = ?
                          AND selected_writer_operation_id IS NULL
                          AND epoch = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM flow_runtime_operation po
                              WHERE po.task_id = flow_runtime_task.task_id
                                AND po.kind = 'PUBLISH'
                                AND po.state IN (
                                    'READY', 'CLAIMED', 'WAITING', 'RETRYABLE'
                                )
                          )
                        """,
                        operationId,
                        task.taskId(),
                        task.epoch());
                if (pointerUpdated != 1) {
                    throw new StaleOwnerRevisionException(
                            "Task writer selection changed concurrently");
                }
                jdbc.update(
                        """
                        UPDATE flow_runtime_inbox
                        SET selected_by_operation_id = ?
                        WHERE inbox_id = ?
                          AND selected_by_operation_id IS NULL
                        """,
                        operationId,
                        item.pendingId());
                selected = Optional.of(runtime.requireOperation(operationId));
                break;
            }

            settleDispatch(
                    reconciliation.operationId(),
                    OperationState.SUCCEEDED,
                    selected.map(Operation::operationId).orElse("NO_CURRENT_WORK"));
            jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET last_reconciled_work_watermark = MAX(
                        last_reconciled_work_watermark, ?)
                    WHERE task_id = ?
                    """,
                    reconciliation.workWatermark(),
                    task.taskId());
            ensureReconciliationIfPending(task.taskId());
            return selected;
        });
    }

    private boolean isInitialPending(PendingWork item)
    {
        if (item.kind() == PendingKind.INITIAL_TASK) {
            return item.intendedGateKind() == GateIntent.INITIAL_PUBLISH;
        }
        if (item.kind() != PendingKind.AGENT_RESULT_READY
                || item.intendedGateKind() != GateIntent.INITIAL_PUBLISH) {
            return false;
        }
        ReviewerRequest request = runtime.reviewerRequestForReviewerRun(
                item.externalKey()).orElse(null);
        AgentResult result = runtime.resultForRun(item.externalKey()).orElse(null);
        return request != null && result != null
                && request.intendedGateKind() == GateIntent.INITIAL_PUBLISH
                && Objects.equals(item.agentResultId(), result.resultId())
                && item.payloadRef().equals(reviewerResultPayload(
                        request.requestId(), result.resultId()))
                && item.subjectHead().equals(request.reviewedHeadSha());
    }

    private OperationKind upstreamWriterKind(Task task, PendingWork item)
    {
        if (item.kind() != PendingKind.INITIAL_TASK) {
            return OperationKind.RUN_TASK_TURN;
        }
        if (!upstreamSchemaInstalled()) {
            return OperationKind.RUN_TASK_TURN;
        }
        List<String> states = jdbc.queryForList(
                "SELECT state FROM flow_upstream_sync_run WHERE task_id = ?",
                String.class, task.taskId());
        if (states.isEmpty()) {
            return OperationKind.RUN_TASK_TURN;
        }
        if (states.size() != 1) {
            throw new IllegalStateException(
                    "Task owns more than one upstream sync run");
        }
        return switch (states.getFirst()) {
            case "READY", "PICKING" -> OperationKind.UPSTREAM_SYNC;
            case "WAITING_CONFLICT_REPAIR", "FINAL_REVIEW" ->
                    OperationKind.RUN_TASK_TURN;
            default -> throw new IllegalStateException(
                    "upstream run state cannot select INITIAL work: "
                            + states.getFirst());
        };
    }

    private boolean upstreamSchemaInstalled()
    {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master "
                        + "WHERE type = 'table' "
                        + "AND name = 'flow_upstream_sync_run'",
                Integer.class);
        return requireNonNull(count, "schema count is null") == 1;
    }

    private String upstreamRunId(String taskId)
    {
        return jdbc.queryForObject(
                "SELECT run_id FROM flow_upstream_sync_run WHERE task_id = ?",
                String.class, taskId);
    }

    boolean ownsCurrentInitialDispatch(Operation operation)
    {
        requireNonNull(operation, "operation is null");
        Task task = runtime.requireTask(operation.taskId());
        return switch (operation.kind()) {
            case RECONCILE_TASK -> runtime.pendingWork(task.taskId()).stream()
                    .filter(input -> input.selectedByOperationId() == null
                            && input.handledByOperationId() == null
                            && input.terminalReason() == null)
                    .anyMatch(input -> pendingSubjectIsCurrent(task, input)
                            && isInitialPending(input));
            case RUN_TASK_TURN -> runtime.pendingWork(task.taskId()).stream()
                    .filter(input -> Objects.equals(
                            input.selectedByOperationId(),
                            operation.operationId()))
                    .filter(input -> input.handledByOperationId() == null
                            && input.terminalReason() == null
                            && operation.inputRef().equals(
                                "inbox:" + input.pendingId()))
                    .anyMatch(input -> pendingSubjectIsCurrent(task, input)
                            && isInitialPending(input)
                            && (operation.ownerKind().equals("TASK")
                                && operation.ownerId().equals(task.taskId())
                                && input.kind() == PendingKind.INITIAL_TASK
                                || operation.ownerKind().equals("AGENT_RUN")
                                && operation.ownerId().equals(
                                    input.externalKey())
                                && input.kind()
                                    == PendingKind.AGENT_RESULT_READY));
            case UPSTREAM_SYNC -> operation.ownerKind()
                        .equals("UPSTREAM_RUN")
                    && runtime.pendingWork(task.taskId()).stream()
                            .filter(input -> Objects.equals(
                                    input.selectedByOperationId(),
                                    operation.operationId()))
                            .filter(input -> input.handledByOperationId() == null
                                    && input.terminalReason() == null
                                    && input.kind() == PendingKind.INITIAL_TASK
                                    && operation.inputRef().equals(
                                            "inbox:" + input.pendingId()))
                            .anyMatch(input -> jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM "
                                            + "flow_upstream_sync_run "
                                            + "WHERE run_id = ? AND task_id = ? "
                                            + "AND state IN ("
                                            + "'READY','PICKING',"
                                            + "'WAITING_CONFLICT_REPAIR',"
                                            + "'FINAL_REVIEW','WAITING_USER',"
                                            + "'CANCELED','NEEDS_ATTENTION')",
                                    Integer.class, operation.ownerId(),
                                    task.taskId()) == 1);
            case RUN_REVIEWER -> operation.ownerKind()
                        .equals("REVIEW_REQUEST")
                    && runtime.reviewerRequest(operation.ownerId())
                            .filter(request -> request.reviewerOperationId()
                                    .equals(operation.operationId()))
                            .filter(request -> request.intendedGateKind()
                                    == GateIntent.INITIAL_PUBLISH)
                            .isPresent();
            default -> false;
        };
    }

    PendingWork appendPendingWork(
            String pendingId,
            Task task,
            String prId,
            String source,
            String externalKey,
            String revision,
            PendingKind kind,
            String subjectHead,
            String payloadRef,
            String agentResultId,
            GateIntent intendedGateKind,
            Instant now)
    {
        Optional<PendingWork> existing = inbox(pendingId);
        if (existing.isPresent()) {
            PendingWork pending = existing.get();
            if (!pending.taskId().equals(task.taskId())
                    || !Objects.equals(pending.prId(), prId)
                    || pending.kind() != kind
                    || !pending.externalKey().equals(externalKey)
                    || !pending.subjectHead().equals(subjectHead)
                    || !pending.payloadRef().equals(payloadRef)
                    || !Objects.equals(pending.agentResultId(), agentResultId)
                    || pending.intendedGateKind() != intendedGateKind) {
                throw new IllegalStateException(
                        "pending-work identity has conflicting content");
            }
            return pending;
        }
        long watermark = task.pendingWorkWatermark() + 1;
        jdbc.update(
                """
                INSERT INTO flow_runtime_inbox (
                    inbox_id, task_id, pr_id, source, external_key,
                    revision, kind, subject_head, payload_ref,
                    agent_result_id, intended_gate_kind,
                    work_watermark, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                pendingId,
                task.taskId(),
                prId,
                source,
                externalKey,
                revision,
                kind.name(),
                subjectHead,
                payloadRef,
                agentResultId,
                intendedGateKind == null ? null : intendedGateKind.name(),
                watermark,
                now.toEpochMilli());
        int advanced = jdbc.update(
                """
                UPDATE flow_runtime_task
                SET pending_work_watermark = ?
                WHERE task_id = ? AND pending_work_watermark = ?
                """,
                watermark,
                task.taskId(),
                task.pendingWorkWatermark());
        if (advanced != 1) {
            throw new StaleOwnerRevisionException(
                    "Task work watermark changed concurrently");
        }
        return inbox(pendingId).orElseThrow();
    }

    boolean pendingSubjectIsCurrent(Task task, PendingWork pending)
    {
        if (!pending.taskId().equals(task.taskId())) {
            return false;
        }
        return switch (pending.kind()) {
            case INITIAL_TASK -> pending.intendedGateKind()
                        == GateIntent.INITIAL_PUBLISH
                    && pending.externalKey().equals(task.taskId())
                    && pending.prId() == null
                    && pending.agentResultId() == null
                    && pending.payloadRef().equals(
                            "task-goal:" + task.taskId())
                    && pending.subjectHead().equals(task.currentHeadSha())
                    && task.prId() == null;
            case FINAL_RED -> {
                if (pending.prId() == null) {
                    yield false;
                }
                PullRequestSubject pr = runtime.requirePullRequest(pending.prId());
                yield pr.published()
                        && pr.taskId().equals(task.taskId())
                        && pending.subjectHead().equals(pr.currentRemoteHead())
                        && pending.subjectHead().equals(task.currentHeadSha());
            }
            case CI_FIX_READY -> {
                Integer resultExists = jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM flow_runtime_agent_result
                        WHERE result_id = ?
                        """,
                        Integer.class,
                        pending.agentResultId());
                yield requireNonNull(resultExists, "result count is null") == 1
                        && pending.subjectHead().equals(task.currentHeadSha());
            }
            case AGENT_RESULT_READY -> {
                AgentRun reviewer = runtime.run(pending.externalKey()).orElse(null);
                AgentResult result = runtime.resultForRun(
                        pending.externalKey()).orElse(null);
                ReviewerRequest request = runtime.reviewerRequestForReviewerRun(
                        pending.externalKey()).orElse(null);
                PullRequestSubject pr = task.prId() == null
                        ? null : runtime.pullRequest(task.prId()).orElse(null);
                yield reviewer != null && result != null && request != null
                        && pr != null
                        && reviewer.role()
                            == AgentRole.ADVERSARIAL_REVIEWER
                        && reviewer.operationId().equals(
                                request.reviewerOperationId())
                        && result.runId().equals(reviewer.runId())
                        && Objects.equals(
                                pending.agentResultId(), result.resultId())
                        && pending.payloadRef().equals(reviewerResultPayload(
                                request.requestId(), result.resultId()))
                        && pending.intendedGateKind()
                            == request.intendedGateKind()
                        && pending.taskId().equals(request.taskId())
                        && Objects.equals(pending.prId(), pr.prId())
                        && pr.taskId().equals(task.taskId())
                        && request.changeSetRevisionId().equals(
                                task.currentChangeSetRevisionId())
                        && request.reviewedHeadSha().equals(
                                task.currentHeadSha())
                        && pending.subjectHead().equals(
                                request.reviewedHeadSha());
            }
        };
    }

    Operation ensureReconciliation(String taskId)
    {
        Optional<Operation> live = jdbc.query(
                """
                SELECT * FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RECONCILE_TASK'
                  AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                LIMIT 1
                """,
                (result, row) -> readOperation(result),
                taskId).stream().findFirst();
        if (live.isPresent()) {
            return live.get();
        }
        Task task = runtime.requireTask(taskId);
        long sequence = task.reconciliationSequence() + 1;
        long throughWatermark = task.pendingWorkWatermark();
        int advanced = jdbc.update(
                """
                UPDATE flow_runtime_task
                SET reconciliation_sequence = ?
                WHERE task_id = ? AND reconciliation_sequence = ?
                """,
                sequence,
                taskId,
                task.reconciliationSequence());
        if (advanced != 1) {
            throw new StaleOwnerRevisionException(
                    "reconciliation sequence changed concurrently");
        }
        String subjectDigest = FlowRuntime.stableId(
                "reconciliation-subject",
                taskId,
                Long.toString(task.epoch()),
                Long.toString(sequence),
                Long.toString(throughWatermark));
        String operationId = FlowRuntime.stableId(
                "operation",
                taskId,
                OperationKind.RECONCILE_TASK.name(),
                subjectDigest);
        insertOperationAndTicket(
                operationId,
                "TASK",
                taskId,
                taskId,
                OperationKind.RECONCILE_TASK,
                subjectDigest,
                "work-watermark:" + throughWatermark,
                throughWatermark,
                RECONCILIATION_PRIORITY,
                clock.instant());
        return runtime.requireOperation(operationId);
    }

    private void parkReconciliation(String operationId)
    {
        int operationUpdated = jdbc.update(
                """
                UPDATE flow_runtime_operation
                SET state = 'WAITING', result_ref = 'TASK_PAUSED'
                WHERE operation_id = ? AND kind = 'RECONCILE_TASK'
                  AND state = 'CLAIMED'
                """,
                operationId);
        int ticketUpdated = jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET delivery_state = 'DONE'
                WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                """,
                operationId);
        if (operationUpdated != 1 || ticketUpdated != 1) {
            throw new StaleClaimException(
                    "reconciliation changed while parking");
        }
    }

    void resumeWaitingReconciliation(String taskId)
    {
        List<String> waiting = jdbc.query(
                """
                SELECT operation_id FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RECONCILE_TASK'
                  AND state = 'WAITING'
                """,
                (result, row) -> result.getString("operation_id"),
                taskId);
        for (String operationId : waiting) {
            int operationUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'READY', result_ref = NULL
                    WHERE operation_id = ? AND state = 'WAITING'
                    """,
                    operationId);
            int ticketUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'AVAILABLE', claim_owner = NULL,
                        claim_expires_at = NULL, claim_token = NULL
                    WHERE operation_id = ? AND delivery_state = 'DONE'
                    """,
                    operationId);
            if (operationUpdated != 1 || ticketUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "waiting reconciliation changed during Task resume");
            }
        }
    }

    void cancelReviewerBlockedReconciliation(String taskId)
    {
        List<BlockedReconciliation> blocked = jdbc.query(
                """
                SELECT o.operation_id, o.state, d.delivery_state
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.task_id = ? AND o.kind = 'RECONCILE_TASK'
                  AND o.state IN ('READY', 'WAITING')
                """,
                (result, row) -> new BlockedReconciliation(
                        result.getString("operation_id"),
                        OperationState.valueOf(result.getString("state")),
                        result.getString("delivery_state")),
                taskId);
        for (BlockedReconciliation reconciliation : blocked) {
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'CANCELED',
                        result_ref = 'REVIEWER_RESULT_ADVANCED'
                    WHERE operation_id = ? AND state = ?
                    """,
                    reconciliation.operationId(),
                    reconciliation.state().name());
            int ticketUpdated;
            if (reconciliation.state() == OperationState.READY
                    && reconciliation.deliveryState().equals("AVAILABLE")) {
                ticketUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_dispatch_ticket
                        SET delivery_state = 'DONE'
                        WHERE operation_id = ?
                          AND delivery_state = 'AVAILABLE'
                        """,
                        reconciliation.operationId());
            }
            else if (reconciliation.state() == OperationState.WAITING
                    && reconciliation.deliveryState().equals("DONE")) {
                ticketUpdated = 1;
            }
            else {
                ticketUpdated = 0;
            }
            if (updated != 1 || ticketUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "reviewer-blocked reconciliation changed concurrently");
            }
        }
    }

    Operation reconciliationCovering(String taskId, long watermark)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RECONCILE_TASK'
                  AND work_watermark >= ?
                ORDER BY work_watermark, created_at, operation_id
                LIMIT 1
                """,
                (result, row) -> readOperation(result),
                taskId,
                watermark).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "selected pending work has no reconciliation owner"));
    }

    void ensureReconciliationIfPending(String taskId)
    {
        Integer pending = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_inbox
                WHERE task_id = ? AND handled_by_operation_id IS NULL
                  AND selected_by_operation_id IS NULL
                  AND terminal_reason IS NULL
                """,
                Integer.class,
                taskId);
        if (requireNonNull(pending, "pending count is null") > 0) {
            ensureReconciliation(taskId);
        }
    }

    void rearmReconciliationAfterWriter(String taskId)
    {
        Task task = runtime.requireTask(taskId);
        List<String> stale = jdbc.query(
                """
                SELECT operation_id FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RECONCILE_TASK'
                  AND state = 'READY' AND work_watermark < ?
                """,
                (result, row) -> result.getString("operation_id"),
                taskId,
                task.pendingWorkWatermark());
        for (String operationId : stale) {
            int operationUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'CANCELED', result_ref = 'WRITER_ADVANCED'
                    WHERE operation_id = ? AND state = 'READY'
                    """,
                    operationId);
            int ticketUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'DONE'
                    WHERE operation_id = ? AND delivery_state = 'AVAILABLE'
                    """,
                    operationId);
            if (operationUpdated != 1 || ticketUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "reconciliation changed during writer release");
            }
        }
    }

    void insertOperationAndTicket(
            String operationId,
            String ownerKind,
            String ownerId,
            String taskId,
            OperationKind kind,
            String subjectDigest,
            String inputRef,
            Long workWatermark,
            int priority,
            Instant now)
    {
        jdbc.update(
                """
                INSERT OR IGNORE INTO flow_runtime_operation (
                    operation_id, owner_kind, owner_id, task_id, kind,
                    subject_digest, input_ref, work_watermark, state, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?)
                """,
                operationId,
                ownerKind,
                ownerId,
                taskId,
                kind.name(),
                subjectDigest,
                inputRef,
                workWatermark,
                now.toEpochMilli());
        Operation stored = runtime.requireOperation(operationId);
        if (!stored.ownerKind().equals(ownerKind)
                || !stored.ownerId().equals(ownerId)
                || !Objects.equals(stored.taskId(), taskId)
                || stored.kind() != kind
                || !stored.subjectDigest().equals(subjectDigest)
                || !stored.inputRef().equals(inputRef)
                || !Objects.equals(stored.workWatermark(), workWatermark)) {
            throw new IllegalStateException(
                    "operation identity has conflicting content");
        }
        jdbc.update(
                """
                INSERT OR IGNORE INTO flow_runtime_dispatch_ticket (
                    operation_id, not_before, priority, delivery_state
                ) VALUES (?, ?, ?, 'AVAILABLE')
                """,
                operationId,
                now.toEpochMilli(),
                priority);
    }

    void settleDispatch(
            String operationId, OperationState state, String resultRef)
    {
        int operationUpdated = jdbc.update(
                """
                UPDATE flow_runtime_operation
                SET state = ?, result_ref = ?
                WHERE operation_id = ? AND state = 'CLAIMED'
                """,
                state.name(),
                resultRef,
                operationId);
        int ticketUpdated = jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET delivery_state = 'DONE'
                WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                """,
                operationId);
        if (operationUpdated != 1 || ticketUpdated != 1) {
            throw new StaleClaimException(
                    "dispatch operation is no longer claimed");
        }
    }

    void assertCurrentClaim(
            Claim claim, OperationState expectedOperationState)
    {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_dispatch_ticket d
                JOIN flow_runtime_operation o
                  ON o.operation_id = d.operation_id
                WHERE d.operation_id = ?
                  AND d.claim_generation = ?
                  AND d.claim_token = ?
                  AND d.claim_owner = ?
                  AND d.delivery_state = 'CLAIMED'
                  AND o.state = ?
                  AND d.claim_expires_at > ?
                """,
                Integer.class,
                claim.operationId(),
                claim.generation(),
                claim.claimToken(),
                claim.workerId(),
                expectedOperationState.name(),
                clock.instant().toEpochMilli());
        if (requireNonNull(matches, "claim count is null") != 1) {
            throw new StaleClaimException(
                    "dispatch claim generation is stale");
        }
    }

    Instant currentClaimExpiry(Claim claim)
    {
        return jdbc.query(
                """
                SELECT claim_expires_at
                FROM flow_runtime_dispatch_ticket
                WHERE operation_id = ? AND claim_generation = ?
                  AND claim_token = ? AND claim_owner = ?
                  AND delivery_state = 'CLAIMED'
                  AND claim_expires_at > ?
                """,
                (result, row) -> Instant.ofEpochMilli(
                        result.getLong("claim_expires_at")),
                claim.operationId(),
                claim.generation(),
                claim.claimToken(),
                claim.workerId(),
                clock.instant().toEpochMilli()).stream().findFirst()
                .orElseThrow(() -> new StaleClaimException(
                        "dispatch claim generation is stale"));
    }

    ExpiredClaim requireExpiredClaim(String operationId, long generation)
    {
        return expiredClaims().stream()
                .filter(claim -> claim.operationId().equals(operationId)
                        && claim.generation() == generation)
                .findFirst()
                .orElseThrow(() -> new StaleClaimException(
                        "claim generation is not durably expired"));
    }

    void recoverNeverLaunchedClaim(ExpiredClaim expired)
    {
        var run = expired.runId() == null
                ? null
                : runtime.run(expired.runId()).orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown AgentRun: " + expired.runId()));
        if (run != null && run.state() != RunState.QUEUED) {
            throw new IllegalStateException(
                    "missing process attempt conflicts with running AgentRun");
        }
        jdbc.update(
                """
                DELETE FROM flow_runtime_writer_lease
                WHERE task_id = ? AND operation_id = ?
                """,
                expired.taskId(),
                expired.operationId());
        int operationUpdated = jdbc.update(
                """
                UPDATE flow_runtime_operation
                SET state = 'RETRYABLE'
                WHERE operation_id = ? AND state = 'CLAIMED'
                """,
                expired.operationId());
        int ticketUpdated = jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET delivery_state = 'AVAILABLE', claim_owner = NULL,
                    claim_expires_at = NULL, claim_token = NULL
                WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                  AND claim_generation = ?
                """,
                expired.operationId(),
                expired.generation());
        if (operationUpdated != 1 || ticketUpdated != 1) {
            throw new StaleClaimException(
                    "never-launched claim changed during recovery");
        }
    }

    boolean ticketMatches(
            String operationId, long generation, String deliveryState)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_dispatch_ticket
                WHERE operation_id = ? AND claim_generation = ?
                  AND delivery_state = ?
                """,
                Integer.class,
                operationId,
                generation,
                deliveryState);
        return requireNonNull(count, "ticket count is null") == 1;
    }

    Optional<PendingWork> inbox(String pendingId)
    {
        return jdbc.query(
                "SELECT * FROM flow_runtime_inbox WHERE inbox_id = ?",
                (result, row) -> readInbox(result),
                pendingId).stream().findFirst();
    }

    PendingWork selectedInput(Operation operation)
    {
        List<PendingWork> selected = jdbc.query(
                """
                SELECT * FROM flow_runtime_inbox
                WHERE selected_by_operation_id = ?
                  AND handled_by_operation_id IS NULL
                  AND terminal_reason IS NULL
                """,
                (result, row) -> readInbox(result),
                operation.operationId());
        if (selected.size() != 1) {
            throw new MutationRejectedException(
                    "Task Agent operation must own one current input");
        }
        PendingWork pending = selected.get(0);
        if (!operation.inputRef().equals("inbox:" + pending.pendingId())
                || pending.kind() == PendingKind.FINAL_RED) {
            throw new MutationRejectedException(
                    "Task Agent input does not match its selected operation");
        }
        return pending;
    }

    private static Operation readOperation(ResultSet result)
            throws SQLException
    {
        long rawWatermark = result.getLong("work_watermark");
        Long watermark = result.wasNull() ? null : rawWatermark;
        return new Operation(
                result.getString("operation_id"),
                result.getString("owner_kind"),
                result.getString("owner_id"),
                result.getString("task_id"),
                OperationKind.valueOf(result.getString("kind")),
                result.getString("subject_digest"),
                result.getString("input_ref"),
                watermark,
                OperationState.valueOf(result.getString("state")),
                result.getInt("attempt"),
                result.getString("result_ref"),
                Instant.ofEpochMilli(result.getLong("created_at")));
    }

    private static PendingWork readInbox(ResultSet result)
            throws SQLException
    {
        return new PendingWork(
                result.getString("inbox_id"),
                result.getString("task_id"),
                result.getString("pr_id"),
                PendingKind.valueOf(result.getString("kind")),
                result.getString("external_key"),
                result.getString("subject_head"),
                result.getString("payload_ref"),
                result.getString("agent_result_id"),
                nullableEnum(result, "intended_gate_kind", GateIntent.class),
                result.getLong("work_watermark"),
                result.getString("selected_by_operation_id"),
                result.getString("handled_by_operation_id"),
                result.getString("terminal_reason"));
    }

    private static <E extends Enum<E>> E nullableEnum(
            ResultSet result, String column, Class<E> type)
            throws SQLException
    {
        String value = result.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String reviewerResultPayload(
            String requestId, String resultId)
    {
        return "reviewer-request:" + requestId + ":result:" + resultId;
    }

    private static ProcessAttemptState nullableProcessState(String state)
    {
        return state == null ? null : ProcessAttemptState.valueOf(state);
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static void requirePositive(Duration value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private record ClaimCandidate(
            String operationId,
            String taskId,
            OperationKind kind,
            long generation) {}

    private record BlockedReconciliation(
            String operationId,
            OperationState state,
            String deliveryState) {}
}
