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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.AssignmentBudget;
import com.bytequay.app.domain.InvestigationReviewData.CriterionRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingEvidenceRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRelationRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingVerificationRow;
import com.bytequay.app.domain.InvestigationReviewData.HypothesisRow;
import com.bytequay.app.domain.InvestigationReviewData.InvestigationStepRow;
import com.bytequay.app.domain.InvestigationReviewData.KnowledgeItemRow;
import com.bytequay.app.domain.InvestigationReviewData.KnowledgeProvenanceRow;
import com.bytequay.app.domain.InvestigationReviewData.ObservationRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewAssignmentRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewCapabilities;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewOutcomeRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundMessageRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewedCommitRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewerDefRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Compact JDBC store for the typed investigation-review artifact graph. */
@Repository
public class InvestigationReviewStore
{
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<DiffFile>> DIFF_FILE_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    /** One task-owned review-round charge for workspace spend charts. */
    public record TaskReviewSpend(long costMilli, Instant occurredAt) {}

    /** One AgentReview round for the monthly AI usage ledger. */
    public record AgentReviewSpend(String provider, String runner, long costMilli, long calls) {}

    /** Immutable code subject consumed by every turn in one review round. */
    public record ReviewRoundSnapshot(
            String roundId, String repository, Integer remotePrNumber,
            String baseBranch, String prTitle, String prDescription,
            String baseCommit, String headCommit, String diff,
            List<DiffFile> files, Map<String, String> fileContents,
            String localRoot, String repositoryRoot,
            ReviewCapabilities capabilities, long createdAtMs)
    {
        public ReviewRoundSnapshot
        {
            files = List.copyOf(files);
            fileContents = Map.copyOf(fileContents);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InvestigationReviewStore(JdbcTemplate jdbc, ObjectMapper mapper)
    {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Optional<AgentReviewRow> findActiveReviewByPr(String prId)
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit, status,
                       workspace_id, owner_thread_id, owner_task_id
                FROM review_session WHERE pr_id = ? AND status IN ('ACTIVE','STALE')
                ORDER BY created_at_ms DESC LIMIT 1
                """, this::review, prId).stream().findFirst();
    }

    /** One-query projection for dashboard review-state badges. */
    @Transactional(readOnly = true)
    public Map<String, String> dashboardStates()
    {
        Map<String, String> states = new HashMap<>();
        jdbc.query("""
                SELECT s.pr_id,
                       CASE WHEN s.status = 'STALE' THEN 'stale'
                            WHEN EXISTS (SELECT 1 FROM review_round r
                                         WHERE r.session_id = s.id
                                           AND (r.status IN ('QUEUED', 'RUNNING')
                                                OR r.lifecycle_finalized = 0))
                                 OR EXISTS (
                                     SELECT 1
                                     FROM task_review_snapshot_operation_v286 snapshot
                                     WHERE snapshot.review_id = s.id
                                       AND snapshot.status = 'REQUESTED')
                                 OR EXISTS (
                                     SELECT 1
                                     FROM task_review_round_snapshot_operation_v293 snapshot
                                     WHERE snapshot.review_id = s.id
                                       AND snapshot.status = 'REQUESTED')
                                 OR EXISTS (
                                     SELECT 1
                                     FROM review_session_snapshot_operation_v293 snapshot
                                     WHERE snapshot.review_id = s.id
                                       AND snapshot.status = 'REQUESTED')
                            THEN 'running' ELSE 'done' END AS dashboard_state
                FROM review_session s
                WHERE s.status IN ('ACTIVE','STALE')
                  AND s.id = (SELECT latest.id FROM review_session latest
                              WHERE latest.pr_id = s.pr_id
                                AND latest.status IN ('ACTIVE','STALE')
                              ORDER BY latest.created_at_ms DESC, latest.id DESC LIMIT 1)
                """, (rs, row) -> Map.entry(
                        rs.getString("pr_id"), rs.getString("dashboard_state")))
                .forEach(entry -> states.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(states);
    }

    /** Actual AgentReview spend in a rolling window, used by unattended
     * automation before it reserves another round budget. */
    @Transactional(readOnly = true)
    public long sumRoundCostCentsSince(Instant since)
    {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cost_cents), 0) FROM review_round WHERE created_at_ms >= ?",
                Long.class, since.toEpochMilli());
        return value == null ? 0L : value;
    }

    /**
     * Review-only threads already expose their cumulative spend through the
     * thread row. Development threads do not: AgentReview is a sibling
     * artifact track, so task-owned rounds are added to Workspace Insights
     * directly from their source of truth.
     */
    @Transactional(readOnly = true)
    public List<TaskReviewSpend> taskReviewSpendSince(Instant since)
    {
        return jdbc.query("""
                SELECT r.cost_cents,
                       COALESCE(r.finished_at_ms, r.created_at_ms) AS occurred_at_ms
                FROM review_round r
                JOIN review_session s ON s.id = r.session_id
                WHERE s.owner_task_id IS NOT NULL
                  AND r.cost_cents > 0
                  AND COALESCE(r.finished_at_ms, r.created_at_ms) >= ?
                """, (rs, row) -> new TaskReviewSpend(
                        rs.getLong("cost_cents") * 10L,
                        Instant.ofEpochMilli(rs.getLong("occurred_at_ms"))),
                since.toEpochMilli());
    }

    /** PR-owned AgentReview spend, regardless of whether the review is linked
     * to a development task. Synthetic review-thread cost is deliberately not
     * part of this projection. */
    @Transactional(readOnly = true)
    public List<TaskReviewSpend> reviewSpendSince(Instant since)
    {
        return reviewSpendSince(null, since);
    }

    /** Workspace-scoped PR-owned AgentReview spend. */
    @Transactional(readOnly = true)
    public List<TaskReviewSpend> reviewSpendSince(String workspaceId, Instant since)
    {
        String workspaceClause = workspaceId == null ? "" : " AND s.workspace_id = ?";
        Object[] arguments = workspaceId == null
                ? new Object[] {since.toEpochMilli()}
                : new Object[] {since.toEpochMilli(), workspaceId};
        return jdbc.query("""
                SELECT r.cost_cents,
                       COALESCE(r.finished_at_ms, r.created_at_ms) AS occurred_at_ms
                FROM review_round r
                JOIN review_session s ON s.id = r.session_id
                WHERE r.cost_cents > 0
                  AND COALESCE(r.finished_at_ms, r.created_at_ms) >= ?
                """ + workspaceClause, (rs, row) -> new TaskReviewSpend(
                        rs.getLong("cost_cents") * 10L,
                        Instant.ofEpochMilli(rs.getLong("occurred_at_ms"))),
                arguments);
    }

    /** AgentReview runs do not write synthetic conversation messages, so
     * the monthly ledger reads their authoritative round rows separately. */
    @Transactional(readOnly = true)
    public List<AgentReviewSpend> agentReviewSpend(Instant start, Instant end)
    {
        return jdbc.query("""
                SELECT COALESCE(json_extract(a.metrics_json, '$.provider'), 'agent-review') AS provider,
                       COALESCE(json_extract(a.metrics_json, '$.runner'), '') AS runner,
                       r.cost_cents,
                       COALESCE(CAST(json_extract(a.metrics_json, '$.providerRounds') AS INTEGER), 1) AS calls
                FROM review_round r
                LEFT JOIN agent_run a ON a.id = r.agent_run_id
                WHERE r.cost_cents > 0
                  AND COALESCE(r.finished_at_ms, r.created_at_ms) >= ?
                  AND COALESCE(r.finished_at_ms, r.created_at_ms) < ?
                """, (rs, row) -> new AgentReviewSpend(
                        rs.getString("provider"), rs.getString("runner"),
                        rs.getLong("cost_cents") * 10L,
                        Math.max(1L, rs.getLong("calls"))),
                start.toEpochMilli(), end.toEpochMilli());
    }

    @Transactional(readOnly = true)
    public Optional<AgentReviewRow> findReview(String reviewId)
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit, status,
                       workspace_id, owner_thread_id, owner_task_id
                FROM review_session WHERE id = ?
                """, this::review, reviewId).stream().findFirst();
    }

    /** Workspace-independent review queue. Task-owned internal passes stay
     * on their task and never appear here. */
    @Transactional(readOnly = true)
    public List<AgentReviewRow> standaloneReviews()
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit, status,
                       workspace_id, owner_thread_id, owner_task_id
                FROM review_session
                WHERE owner_task_id IS NULL
                ORDER BY updated_at_ms DESC
                """, this::review);
    }

    @Transactional(readOnly = true)
    public List<AgentReviewRow> remoteReviewsForRepo(String repoId)
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit, status,
                       workspace_id, owner_thread_id, owner_task_id
                FROM review_session
                WHERE owner_task_id IS NULL
                  AND workspace_id IS NULL
                  AND lower(repo_id) = lower(?)
                ORDER BY updated_at_ms DESC
                """, this::review, repoId);
    }

    @Transactional(readOnly = true)
    public Optional<AgentReviewRow> findActiveReviewByOwnerThread(String threadId)
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit, status,
                       workspace_id, owner_thread_id, owner_task_id
                FROM review_session
                WHERE owner_thread_id = ? AND owner_task_id IS NULL
                      AND status IN ('ACTIVE','STALE')
                ORDER BY created_at_ms DESC LIMIT 1
                """, this::review, threadId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<AgentReviewRow> reviewsByOwnerThread(String threadId)
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit, status,
                       workspace_id, owner_thread_id, owner_task_id
                FROM review_session
                WHERE owner_thread_id = ?
                ORDER BY created_at_ms
                """, this::review, threadId);
    }

    @Transactional(readOnly = true)
    public List<AgentReviewRow> reviewsByWorkspace(String workspaceId)
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit, status,
                       workspace_id, owner_thread_id, owner_task_id
                FROM review_session
                WHERE workspace_id = ?
                ORDER BY created_at_ms
                """, this::review, workspaceId);
    }

    @Transactional
    public void insertReview(AgentReviewRow row, Instant now)
    {
        jdbc.update("""
                INSERT INTO review_session
                (id, repo_id, pr_id, base_commit, reviewed_head_commit, status,
                 workspace_id, owner_thread_id, owner_task_id, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.repoId(), row.prId(), row.baseCommit(), row.reviewedHeadCommit(),
                row.status(), row.workspaceId(), row.ownerThreadId(), row.ownerTaskId(),
                now.toEpochMilli(), now.toEpochMilli());
    }

    @Transactional
    public void updateReviewOwner(
            String reviewId, String workspaceId, String ownerThreadId, String ownerTaskId)
    {
        jdbc.update("""
                UPDATE review_session
                SET workspace_id = ?, owner_thread_id = ?, owner_task_id = ?, updated_at_ms = ?
                WHERE id = ?
                """, workspaceId, ownerThreadId, ownerTaskId, Instant.now().toEpochMilli(), reviewId);
    }

    /** Delete one AgentReview aggregate and the PR-local presentation rows it
     * owns. GitHub data is not stored in these tables and is never touched. */
    @Transactional
    public void deleteReview(String reviewId)
    {
        jdbc.update("""
                DELETE FROM pr_timeline_event
                WHERE is_local_only = 1
                  AND json_valid(payload_json)
                  AND (
                    json_extract(payload_json, '$.reviewId') = ?
                    OR json_extract(payload_json, '$.sessionId') = ?
                    OR json_extract(payload_json, '$.roundId') IN (
                        SELECT id FROM review_round WHERE session_id = ?)
                    OR json_extract(payload_json, '$.findingId') IN (
                        SELECT id FROM finding WHERE session_id = ?)
                    OR json_extract(payload_json, '$.commentId') IN (
                        WITH RECURSIVE review_comments(id) AS (
                            SELECT c.id FROM pr_comment c
                            JOIN finding f ON f.id = c.finding_id
                            WHERE f.session_id = ?
                            UNION ALL
                            SELECT child.id FROM pr_comment child
                            JOIN review_comments parent ON child.parent_comment_id = parent.id
                        )
                        SELECT id FROM review_comments)
                  )
                """, reviewId, reviewId, reviewId, reviewId, reviewId);
        jdbc.update("""
                WITH RECURSIVE review_comments(id) AS (
                    SELECT c.id FROM pr_comment c
                    JOIN finding f ON f.id = c.finding_id
                    WHERE f.session_id = ?
                    UNION ALL
                    SELECT child.id FROM pr_comment child
                    JOIN review_comments parent ON child.parent_comment_id = parent.id
                )
                DELETE FROM pr_comment WHERE id IN (SELECT id FROM review_comments)
                """, reviewId);
        jdbc.update("""
                DELETE FROM agent_run
                WHERE review_round_id IN (
                    SELECT id FROM review_round WHERE session_id = ?)
                   OR id IN (
                    SELECT agent_run_id FROM review_round WHERE session_id = ?)
                """, reviewId, reviewId);
        jdbc.update("DELETE FROM review_session WHERE id = ?", reviewId);
    }

    @Transactional
    public void updateReviewStatus(String reviewId, String status)
    {
        jdbc.update("UPDATE review_session SET status = ?, updated_at_ms = ? WHERE id = ?",
                status, Instant.now().toEpochMilli(), reviewId);
    }

    @Transactional
    public void updateReviewHead(String reviewId, String reviewedHeadCommit, String status)
    {
        jdbc.update("""
                UPDATE review_session
                SET reviewed_head_commit = ?, status = ?, updated_at_ms = ? WHERE id = ?
                """, reviewedHeadCommit, status, Instant.now().toEpochMilli(), reviewId);
    }

    @Transactional
    public boolean updateReviewHeadUnlessStale(
            String reviewId, String reviewedHeadCommit, String status)
    {
        return jdbc.update("""
                UPDATE review_session
                SET reviewed_head_commit = ?, status = ?, updated_at_ms = ?
                WHERE id = ? AND status <> 'STALE'
                """, reviewedHeadCommit, status, Instant.now().toEpochMilli(), reviewId) == 1;
    }

    /** Compare-and-set used by PR polling. A round may finish between the
     * poll reading the old head and attempting to mark the review stale; in
     * that case the freshly persisted reviewed head wins. */
    @Transactional
    public boolean markReviewStaleIfHeadDiffers(String reviewId, String currentHeadCommit)
    {
        return jdbc.update("""
                UPDATE review_session
                SET status = 'STALE', updated_at_ms = ?
                WHERE id = ? AND reviewed_head_commit <> ?
                """, Instant.now().toEpochMilli(), reviewId, currentHeadCommit) == 1;
    }

    @Transactional
    public void insertRound(ReviewRoundRow row, Instant now)
    {
        jdbc.update("""
                INSERT INTO review_round
                (id, session_id, agent_run_id, trigger, scope, start_commit, end_commit,
                 status, budget_json, cost_cents, capabilities_json, trigger_stage_id,
                 message_gate_open, lifecycle_finalized, created_at_ms, finished_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """, row.id(), row.reviewId(), row.agentRunId(), row.trigger(), row.scope(),
                row.startCommit(), row.endCommit(), row.status(), json(row.budgetJson()),
                row.costCents(), json(row.capabilitiesJson()), row.triggerStageId(),
                row.messageGateOpen() ? 1 : 0,
                ImmutableSet.of("QUEUED", "RUNNING").contains(row.status()) ? 0 : 1,
                now.toEpochMilli());
    }

    /** Insert a newly launched round and derive its queue state in the same
     * SQLite statement. Concurrent callers therefore cannot both publish a
     * RUNNING round for one review session. */
    @Transactional
    public ReviewRoundRow insertLiveRound(ReviewRoundRow row, Instant now)
    {
        jdbc.update("""
                INSERT INTO review_round
                (id, session_id, agent_run_id, trigger, scope, start_commit, end_commit,
                 status, budget_json, cost_cents, capabilities_json, trigger_stage_id,
                 message_gate_open, lifecycle_finalized, created_at_ms, finished_at_ms)
                SELECT ?, ?, ?, ?, ?, ?, ?,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM review_round live
                           WHERE live.session_id = ?
                             AND (live.status IN ('QUEUED', 'RUNNING')
                                  OR live.lifecycle_finalized = 0))
                       THEN 'QUEUED' ELSE 'RUNNING' END,
                       ?, ?, ?, ?,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM review_round live
                           WHERE live.session_id = ?
                             AND (live.status IN ('QUEUED', 'RUNNING')
                                  OR live.lifecycle_finalized = 0))
                       THEN 0 ELSE 1 END,
                       0, ?, NULL
                """, row.id(), row.reviewId(), row.agentRunId(), row.trigger(), row.scope(),
                row.startCommit(), row.endCommit(), row.reviewId(), json(row.budgetJson()),
                row.costCents(), json(row.capabilitiesJson()), row.triggerStageId(),
                row.reviewId(), now.toEpochMilli());
        return findRound(row.id()).orElseThrow();
    }

    @Transactional
    public void insertRoundSnapshot(ReviewRoundSnapshot snapshot)
    {
        jdbc.update("""
                INSERT INTO review_round_snapshot_v291(
                    round_id, repository, remote_pr_number, base_branch,
                    pr_title, pr_description,
                    base_commit, head_commit, diff, files_json, file_contents_json,
                    local_root, repository_root, capabilities_json, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.roundId(), snapshot.repository(),
                snapshot.remotePrNumber(), snapshot.baseBranch(),
                snapshot.prTitle(), snapshot.prDescription(),
                snapshot.baseCommit(), snapshot.headCommit(),
                snapshot.diff(), json(snapshot.files()),
                json(snapshot.fileContents()), snapshot.localRoot(),
                snapshot.repositoryRoot(), json(snapshot.capabilities()),
                snapshot.createdAtMs());
    }

    @Transactional(readOnly = true)
    public Optional<ReviewRoundSnapshot> findRoundSnapshot(String roundId)
    {
        return jdbc.query("""
                SELECT * FROM review_round_snapshot_v291 WHERE round_id = ?
                """, this::roundSnapshot, roundId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<ReviewRoundSnapshot> findRoundSnapshotByAssignment(
            String assignmentId)
    {
        return jdbc.query("""
                SELECT snapshot.*
                FROM review_round_snapshot_v291 snapshot
                JOIN review_assignment assignment
                  ON assignment.round_id = snapshot.round_id
                WHERE assignment.id = ?
                """, this::roundSnapshot, assignmentId).stream().findFirst();
    }

    @Transactional
    public boolean startQueuedRound(String roundId)
    {
        return jdbc.update("""
                UPDATE review_round
                SET status = 'RUNNING', message_gate_open = 1
                WHERE id = ? AND status = 'QUEUED'
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
                """, roundId) == 1;
    }

    /** Avoid taking SQLite's writer lock while an earlier round still owns
     * the session. {@link #startQueuedRound(String)} remains the atomic guard
     * for the race between this read and the update. */
    @Transactional(readOnly = true)
    public boolean queuedRoundCanStart(String roundId)
    {
        return count("""
                SELECT COUNT(*) FROM review_round
                WHERE id = ? AND status = 'QUEUED'
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
                """, roundId) == 1;
    }

    @Transactional(readOnly = true)
    public List<ReviewRoundRow> liveRounds()
    {
        return jdbc.query("""
                SELECT * FROM review_round
                WHERE status IN ('QUEUED', 'RUNNING') OR lifecycle_finalized = 0
                ORDER BY rowid
                """, this::round);
    }

    @Transactional
    public void markRoundFinalized(String roundId)
    {
        jdbc.update(
                "UPDATE review_round SET lifecycle_finalized = 1 WHERE id = ?",
                roundId);
    }

    @Transactional(readOnly = true)
    public boolean isRoundFinalized(String roundId)
    {
        Integer value = jdbc.queryForObject(
                "SELECT lifecycle_finalized FROM review_round WHERE id = ?",
                Integer.class, roundId);
        return value != null && value != 0;
    }

    /** Last cancellation fence, invoked after the worker has exited. Tool
     * callbacks that were already in flight may have written after the first
     * cancellation update; terminalize them again before lifecycle finality. */
    @Transactional
    public void terminalizeCancelledRoundWork(String roundId)
    {
        Integer cancelled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_round WHERE id = ? AND status = 'CANCELLED'",
                Integer.class, roundId);
        if (cancelled != null && cancelled > 0) {
            terminalizeUnfinishedRoundWork(roundId, "cancelled");
            jdbc.update("""
                    UPDATE review_round_message
                    SET status = 'cancelled',
                        response = COALESCE(response, 'Round was cancelled before this message could be processed.'),
                        completed_at_ms = COALESCE(completed_at_ms, ?)
                    WHERE round_id = ? AND status IN ('pending', 'processing')
                    """, Instant.now().toEpochMilli(), roundId);
        }
    }

    @Transactional
    public boolean cancelLiveRound(String roundId, int costCents)
    {
        int updated = jdbc.update("""
                UPDATE review_round
                SET status = 'CANCELLED', cost_cents = MAX(cost_cents, ?), finished_at_ms = ?,
                    message_gate_open = 0
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """, costCents, Instant.now().toEpochMilli(), roundId);
        if (updated == 1) {
            jdbc.update("""
                    UPDATE review_round_message
                    SET status = 'cancelled',
                        response = COALESCE(response, 'Round was cancelled before this message could be processed.'),
                        completed_at_ms = ?
                    WHERE round_id = ? AND status IN ('pending', 'processing')
                    """, Instant.now().toEpochMilli(), roundId);
            terminalizeUnfinishedRoundWork(roundId, "cancelled");
        }
        return updated == 1;
    }

    @Transactional
    public void updateRound(String roundId, String status, String endCommit, int costCents)
    {
        boolean terminal = !"RUNNING".equals(status);
        jdbc.update("""
                UPDATE review_round SET status = ?, end_commit = ?, cost_cents = ?, finished_at_ms = ?,
                       message_gate_open = ?, lifecycle_finalized = ?
                WHERE id = ?
                """, status, endCommit, costCents,
                terminal ? Instant.now().toEpochMilli() : null, terminal ? 0 : 1,
                terminal ? 1 : 0, roundId);
    }

    @Transactional
    public boolean updateRunningRoundCost(String roundId, int costCents)
    {
        return jdbc.update("""
                UPDATE review_round SET cost_cents = ? WHERE id = ? AND status = 'RUNNING'
                """, costCents, roundId) == 1;
    }

    @Transactional
    public void settleRoundCost(String roundId, int costCents)
    {
        jdbc.update(
                "UPDATE review_round SET cost_cents = MAX(cost_cents, ?) WHERE id = ?",
                costCents, roundId);
    }

    @Transactional
    public boolean finishRunningRound(
            String roundId, String status, String endCommit, int costCents)
    {
        if ("RUNNING".equals(status)) {
            throw new IllegalArgumentException("terminal round status is required");
        }
        boolean completed = status.startsWith("COMPLETED");
        int updated = jdbc.update("""
                UPDATE review_round
                SET status = ?, end_commit = ?, cost_cents = MAX(cost_cents, ?), finished_at_ms = ?,
                    message_gate_open = 0
                WHERE id = ? AND status = 'RUNNING'
                  AND (? = 0 OR NOT EXISTS (
                      SELECT 1 FROM review_round_message m
                      WHERE m.round_id = review_round.id
                        AND m.status IN ('pending', 'processing')))
                """, status, endCommit, costCents, Instant.now().toEpochMilli(), roundId,
                completed ? 1 : 0);
        if (updated == 1) {
            terminalizeUnfinishedRoundWork(
                    roundId, completed ? "skipped"
                            : "CANCELLED".equals(status) ? "cancelled" : "errored");
        }
        if (updated == 1 && !completed) {
            String messageStatus = "CANCELLED".equals(status) ? "cancelled" : "failed";
            String response = "CANCELLED".equals(status)
                    ? "Round was cancelled before this message could be processed."
                    : "Round ended before this message could be processed.";
            jdbc.update("""
                    UPDATE review_round_message
                    SET status = ?, response = COALESCE(response, ?), completed_at_ms = ?
                    WHERE round_id = ? AND status IN ('pending', 'processing')
                    """, messageStatus, response, Instant.now().toEpochMilli(), roundId);
        }
        return updated == 1;
    }

    /** Finish a successful round and advance its session head in the same
     * transaction. This closes the poll window where a terminal round still
     * appeared to have an old reviewed head. */
    @Transactional
    public boolean finishRunningRoundAndAdvanceReview(
            String roundId, String status, String endCommit, int costCents)
    {
        if (!status.startsWith("COMPLETED")) {
            throw new IllegalArgumentException("a completed round status is required");
        }
        if (!finishRunningRound(roundId, status, endCommit, costCents)) {
            return false;
        }
        jdbc.update("""
                UPDATE review_session
                SET reviewed_head_commit = ?, status = 'ACTIVE', updated_at_ms = ?
                WHERE id = (SELECT session_id FROM review_round WHERE id = ?)
                """, endCommit, Instant.now().toEpochMilli(), roundId);
        return true;
    }

    private void terminalizeUnfinishedRoundWork(String roundId, String assignmentStatus)
    {
        jdbc.update("""
                UPDATE investigation_step
                SET status = 'skipped'
                WHERE assignment_id IN (
                    SELECT id FROM review_assignment WHERE round_id = ?)
                  AND status IN ('queued', 'running', 'planned', 'investigating', 'verifying')
                """, roundId);
        jdbc.update("""
                UPDATE review_assignment
                SET status = ?
                WHERE round_id = ?
                  AND status IN ('queued', 'running', 'investigating', 'verifying')
                """, assignmentStatus, roundId);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewRoundRow> findRound(String roundId)
    {
        return jdbc.query("SELECT * FROM review_round WHERE id = ?", this::round, roundId)
                .stream().findFirst();
    }

    @Transactional
    public void insertRoundMessage(ReviewRoundMessageRow row)
    {
        jdbc.update("""
                INSERT INTO review_round_message
                (id, round_id, assignment_id, target, sender, body, status, response,
                 created_at_ms, completed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.roundId(), row.assignmentId(), row.target(), row.sender(), row.body(),
                row.status(), row.response(), row.createdAt(), row.completedAt());
    }

    /** Atomically accepts guidance only while the running round still has an
     * open checkpoint before verification. */
    @Transactional
    public boolean insertPendingRoundMessage(ReviewRoundMessageRow row)
    {
        return jdbc.update("""
                INSERT INTO review_round_message
                (id, round_id, assignment_id, target, sender, body, status, response,
                 created_at_ms, completed_at_ms)
                SELECT ?, id, NULL, ?, ?, ?, 'pending', NULL, ?, NULL
                FROM review_round
                WHERE id = ? AND status = 'RUNNING' AND message_gate_open = 1
                """, row.id(), row.target(), row.sender(), row.body(), row.createdAt(),
                row.roundId()) == 1;
    }

    @Transactional
    public boolean linkRoundMessageAssignment(String messageId, String assignmentId)
    {
        return jdbc.update("""
                UPDATE review_round_message SET assignment_id = ?
                WHERE id = ? AND status = 'processing' AND assignment_id IS NULL
                """, assignmentId, messageId) == 1;
    }

    /** Keep the transient guidance assignment invisible until its message
     * link exists. Both writes commit together, so aggregate polling cannot
     * briefly render it as a normal review stage. */
    @Transactional
    public void insertGuidanceAssignment(
            String messageId, ReviewAssignmentRow row)
    {
        insertAssignment(row);
        if (!linkRoundMessageAssignment(messageId, row.id())) {
            throw new IllegalStateException(
                    "guidance message could not be linked to its assignment");
        }
    }

    @Transactional(readOnly = true)
    public List<ReviewRoundMessageRow> pendingRoundMessages(String roundId)
    {
        return jdbc.query("""
                SELECT * FROM review_round_message
                WHERE round_id = ? AND status = 'pending'
                ORDER BY created_at_ms, id
                """, this::roundMessage, roundId);
    }

    @Transactional
    public boolean claimRoundMessage(String messageId)
    {
        return jdbc.update("""
                UPDATE review_round_message
                SET status = 'processing'
                WHERE id = ? AND status = 'pending'
                  AND EXISTS (
                      SELECT 1 FROM review_round r
                      WHERE r.id = review_round_message.round_id
                        AND r.status = 'RUNNING')
                """, messageId) == 1;
    }

    @Transactional
    public boolean completeRoundMessage(
            String messageId, String status, String response, Instant completedAt)
    {
        return jdbc.update("""
                UPDATE review_round_message
                SET status = ?, response = ?, completed_at_ms = ?
                WHERE id = ? AND status = 'processing'
                """, status, response, completedAt.toEpochMilli(), messageId) == 1;
    }

    /** Close the guidance gate exactly when no accepted message is waiting or
     * in flight. A concurrent insert and this update cannot both cross the
     * checkpoint unnoticed. */
    @Transactional
    public boolean closeMessageGateIfDrained(String roundId)
    {
        return jdbc.update("""
                UPDATE review_round SET message_gate_open = 0
                WHERE id = ? AND status = 'RUNNING'
                  AND NOT EXISTS (
                      SELECT 1 FROM review_round_message m
                      WHERE m.round_id = review_round.id
                        AND m.status IN ('pending', 'processing'))
                """, roundId) == 1;
    }

    @Transactional
    public boolean updateRunningRoundBudget(String roundId, RoundBudget budget)
    {
        return jdbc.update("""
                UPDATE review_round SET budget_json = ?
                WHERE id = ? AND status = 'RUNNING'
                """, json(budget), roundId) == 1;
    }

    @Transactional
    public void insertReviewedCommit(ReviewedCommitRow row)
    {
        jdbc.update("""
                INSERT INTO review_round_commit (round_id, sha, message, position)
                VALUES (?, ?, ?, ?)
                """, row.roundId(), row.sha(), row.message(), row.position());
    }

    @Transactional
    public void insertCriterion(CriterionRow row)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO criterion
                (id, repo_id, kind, statement, source_type, source_ref) VALUES (?, ?, ?, ?, ?, ?)
                """, row.id(), row.repoId(), row.kind(), row.statement(), row.sourceType(), row.sourceRef());
    }

    @Transactional
    public void insertObjective(ReviewObjectiveRow row)
    {
        jdbc.update("""
                INSERT INTO review_objective
                (id, round_id, criterion_id, statement, source, applicability_status, resolution_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.roundId(), row.criterionId(), row.statement(), row.source(),
                row.applicabilityStatus(), row.resolutionStatus());
    }

    @Transactional
    public void updateObjectiveResolution(String objectiveId, String status)
    {
        jdbc.update("UPDATE review_objective SET resolution_status = ? WHERE id = ?", status, objectiveId);
    }

    @Transactional(readOnly = true)
    public List<ReviewerDefRow> reviewerDefs()
    {
        return jdbc.query("SELECT * FROM reviewer_def ORDER BY rowid", this::reviewerDef);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewerDefRow> findReviewerDef(String id)
    {
        return jdbc.query("SELECT * FROM reviewer_def WHERE id = ?", this::reviewerDef, id)
                .stream().findFirst();
    }

    @Transactional
    public void upsertReviewerDef(ReviewerDefRow row)
    {
        jdbc.update("""
                INSERT INTO reviewer_def
                (id, name, description, runner, runner_json, persona, eligible_kinds, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name, description=excluded.description,
                runner=excluded.runner, runner_json=excluded.runner_json, persona=excluded.persona,
                eligible_kinds=excluded.eligible_kinds, enabled=excluded.enabled
                """, row.id(), row.name(), row.description(), row.runner(), json(row.runnerJson()),
                row.persona(), json(row.eligibleKinds()), row.enabled() ? 1 : 0);
    }

    /** Preserve historical assignment foreign keys while removing a reviewer from future panels. */
    @Transactional
    public boolean disableReviewerDef(String id)
    {
        return jdbc.update("UPDATE reviewer_def SET enabled = 0 WHERE id = ?", id) > 0;
    }

    @Transactional
    public void insertAssignment(ReviewAssignmentRow row)
    {
        jdbc.update("""
                INSERT INTO review_assignment
                (id, round_id, reviewer_def_id, runner, status, understanding_summary,
                 assumptions_json, unknowns_json, budget_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.roundId(), row.reviewerDefId(), row.runner(), row.status(),
                row.understandingSummary(), json(row.assumptionsJson()), json(row.unknownsJson()),
                json(row.budgetJson()));
    }

    @Transactional
    public void updateAssignment(String assignmentId, String status, String summary,
            List<String> assumptions, List<String> unknowns)
    {
        jdbc.update("""
                UPDATE review_assignment SET status = ?, understanding_summary = ?,
                assumptions_json = ?, unknowns_json = ? WHERE id = ?
                """, status, summary, json(assumptions), json(unknowns), assignmentId);
    }

    @Transactional
    public boolean updateAssignmentWhileRoundRunning(
            String assignmentId, String status, String summary,
            List<String> assumptions, List<String> unknowns)
    {
        return jdbc.update("""
                UPDATE review_assignment
                SET status = ?, understanding_summary = ?, assumptions_json = ?, unknowns_json = ?
                WHERE id = ? AND EXISTS (
                    SELECT 1 FROM review_round r
                    WHERE r.id = review_assignment.round_id AND r.status = 'RUNNING')
                """, status, summary, json(assumptions), json(unknowns), assignmentId) == 1;
    }

    @Transactional(readOnly = true)
    public boolean assignmentRoundIsRunning(String assignmentId)
    {
        return count("""
                SELECT COUNT(*) FROM review_assignment a
                JOIN review_round r ON r.id = a.round_id
                WHERE a.id = ? AND r.status = 'RUNNING'
                """, assignmentId) == 1;
    }

    @Transactional(readOnly = true)
    public boolean assignmentUsesQuickReviewScope(String assignmentId)
    {
        return count("""
                SELECT COUNT(*) FROM review_assignment assignment
                JOIN review_round round ON round.id = assignment.round_id
                WHERE assignment.id = ? AND round.scope = 'quick'
                """, assignmentId) == 1;
    }

    /** Run one artifact mutation behind the same SQLite write lock as round
     * cancellation. If cancellation won first, no child artifact is written;
     * if this mutation won, cancellation waits and its terminal fence runs
     * after the mutation commits. */
    @Transactional
    public boolean mutateWhileAssignmentRoundRunning(
            String assignmentId, Runnable mutation)
    {
        int locked = jdbc.update("""
                UPDATE review_assignment
                SET status = status
                WHERE id = ? AND EXISTS (
                    SELECT 1 FROM review_round r
                    WHERE r.id = review_assignment.round_id AND r.status = 'RUNNING')
                """, assignmentId);
        if (locked != 1) {
            return false;
        }
        mutation.run();
        return true;
    }

    @Transactional
    public void insertHypothesis(HypothesisRow row)
    {
        jdbc.update("""
                INSERT INTO hypothesis
                (id, assignment_id, objective_id, claim, origin, status, confidence_class)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.assignmentId(), row.objectiveId(), row.claim(), row.origin(),
                row.status(), row.confidenceClass());
    }

    @Transactional
    public void insertStep(InvestigationStepRow row)
    {
        jdbc.update("""
                INSERT INTO investigation_step
                (id, assignment_id, hypothesis_id, action_type, arguments_json, reason,
                 planned, cost_cents, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.assignmentId(), row.hypothesisId(), row.actionType(),
                json(row.argumentsJson()), row.reason(), row.planned() ? 1 : 0,
                row.costCents(), row.status());
    }

    @Transactional
    public void updateStepStatus(String stepId, String status, int costCents)
    {
        jdbc.update("""
                UPDATE investigation_step SET status = ?, cost_cents = ?
                WHERE id = ? AND EXISTS (
                    SELECT 1 FROM review_assignment a
                    JOIN review_round r ON r.id = a.round_id
                    WHERE a.id = investigation_step.assignment_id AND r.status = 'RUNNING')
                """, status, costCents, stepId);
    }

    @Transactional
    public void skipRunningSteps(String assignmentId)
    {
        jdbc.update("UPDATE investigation_step SET status = 'skipped' WHERE assignment_id = ? AND status = 'running'",
                assignmentId);
    }

    @Transactional
    public void insertObservation(ObservationRow row)
    {
        jdbc.update("""
                INSERT INTO observation
                (id, step_id, source_type, commit_sha, path, start_line, end_line, symbol,
                 command, exit_code, artifact_ref, content_digest, preview)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.stepId(), row.sourceType(), row.commitSha(), row.path(),
                row.startLine(), row.endLine(), row.symbol(), row.command(), row.exitCode(),
                row.artifactRef(), row.contentDigest(), row.preview());
    }

    @Transactional
    public void insertFinding(FindingRow row)
    {
        jdbc.update("""
                INSERT INTO finding
                (id, session_id, round_id, objective_id, hypothesis_id, criterion_kind,
                 path, start_line, end_line,
                 claim, severity, confidence_class, verification_status, requested_action,
                 lifecycle_status, last_checked_commit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.reviewId(), row.roundId(), row.objectiveId(),
                row.hypothesisId(), row.criterionKind(), row.path(), row.startLine(), row.endLine(),
                row.claim(), row.severity(),
                row.confidenceClass(), row.verificationStatus(), row.requestedAction(),
                row.lifecycleStatus(), row.lastCheckedCommit());
    }

    @Transactional(readOnly = true)
    public Optional<FindingRow> findFinding(String findingId)
    {
        return jdbc.query("SELECT * FROM finding WHERE id = ?", this::finding, findingId)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public boolean assignmentBelongsToReview(String assignmentId, String reviewId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM review_assignment a
                JOIN review_round r ON r.id = a.round_id
                WHERE a.id = ? AND r.session_id = ?
                """, Integer.class, assignmentId, reviewId);
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean stepBelongsToAssignment(String stepId, String assignmentId)
    {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM investigation_step WHERE id = ? AND assignment_id = ?",
                Integer.class, stepId, assignmentId);
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public boolean hypothesisBelongsToAssignment(String hypothesisId, String assignmentId)
    {
        return count("SELECT COUNT(*) FROM hypothesis WHERE id = ? AND assignment_id = ?",
                hypothesisId, assignmentId) == 1;
    }

    @Transactional(readOnly = true)
    public boolean observationBelongsToReview(String observationId, String reviewId)
    {
        return count("""
                SELECT COUNT(*) FROM observation o
                JOIN investigation_step s ON s.id = o.step_id
                JOIN review_assignment a ON a.id = s.assignment_id
                JOIN review_round r ON r.id = a.round_id
                WHERE o.id = ? AND r.session_id = ?
                """, observationId, reviewId) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<ObservationRow> findObservation(String observationId)
    {
        return jdbc.query("SELECT * FROM observation WHERE id = ?", (rs, n) -> new ObservationRow(
                rs.getString("id"), rs.getString("step_id"), rs.getString("source_type"),
                rs.getString("commit_sha"), rs.getString("path"), integer(rs, "start_line"),
                integer(rs, "end_line"), rs.getString("symbol"), rs.getString("command"),
                integer(rs, "exit_code"), rs.getString("artifact_ref"),
                rs.getString("content_digest"), rs.getString("preview")), observationId)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public int countHypotheses(String assignmentId)
    {
        return count("SELECT COUNT(*) FROM hypothesis WHERE assignment_id = ?", assignmentId);
    }

    @Transactional(readOnly = true)
    public int countActiveHypotheses(String assignmentId)
    {
        return count("SELECT COUNT(*) FROM hypothesis WHERE assignment_id = ? AND status = 'active'", assignmentId);
    }

    @Transactional(readOnly = true)
    public int countSteps(String assignmentId)
    {
        return count("""
                SELECT COUNT(*) FROM investigation_step
                WHERE assignment_id = ? AND status <> 'not-applicable'
                  AND action_type NOT LIKE 'sweep:%'
                  AND action_type <> 'user-answer'
                """, assignmentId);
    }

    @Transactional(readOnly = true)
    public int countFindings(String assignmentId)
    {
        return count("""
                SELECT COUNT(*) FROM finding f JOIN hypothesis h ON h.id = f.hypothesis_id
                WHERE h.assignment_id = ?
                """, assignmentId);
    }

    @Transactional
    public void updateFinding(String findingId, String lifecycleStatus, String verificationStatus,
            String confidenceClass, String claim, int severity)
    {
        jdbc.update("""
                UPDATE finding SET lifecycle_status = ?, verification_status = ?,
                confidence_class = ?, claim = ?, severity = ? WHERE id = ?
                """, lifecycleStatus, verificationStatus, confidenceClass, claim, severity, findingId);
    }

    @Transactional
    public void insertEvidence(FindingEvidenceRow row)
    {
        jdbc.update("""
                INSERT INTO finding_evidence
                (finding_id, observation_id, relation, proposition, strength_class,
                 strength_reason, dependency_mode, dependency_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, row.findingId(), row.observationId(), row.relation(), row.proposition(),
                row.strengthClass(), row.strengthReason(), row.dependencyMode(),
                json(row.dependencyJson()));
    }

    @Transactional
    public void insertVerification(FindingVerificationRow row)
    {
        jdbc.update("""
                INSERT INTO finding_verification
                (id, finding_id, verifier_run_id, evidence_accurate, claim_scope_accurate,
                 severity_accurate, counter_evidence_json, status, confidence_class, explanation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.findingId(), row.verifierRunId(),
                row.evidenceAccurate() ? 1 : 0, row.claimScopeAccurate() ? 1 : 0,
                row.severityAccurate() ? 1 : 0, json(row.counterEvidenceJson()), row.status(),
                row.confidenceClass(), row.explanation());
    }

    @Transactional
    public void insertRelation(FindingRelationRow row)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO finding_relation
                (source_finding_id, target_finding_id, relation) VALUES (?, ?, ?)
                """, row.sourceFindingId(), row.targetFindingId(), row.relation());
    }

    @Transactional
    public void insertOutcome(ReviewOutcomeRow row)
    {
        jdbc.update("""
                INSERT OR REPLACE INTO review_outcome
                (finding_id, user_disposition, author_response, epistemic_resolution,
                 utility_assessment, style_edit_magnitude, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, row.findingId(), row.userDisposition(), row.authorResponse(),
                row.epistemicResolution(), row.utilityAssessment(), row.styleEditMagnitude(),
                Instant.now().toEpochMilli());
    }

    @Transactional(readOnly = true)
    public List<ReviewRoundRow> rounds(String reviewId)
    {
        return jdbc.query("SELECT * FROM review_round WHERE session_id = ? ORDER BY created_at_ms, rowid",
                this::round, reviewId);
    }

    @Transactional(readOnly = true)
    public List<ReviewRoundMessageRow> roundMessages(String reviewId)
    {
        return jdbc.query("""
                SELECT m.* FROM review_round_message m
                JOIN review_round r ON r.id = m.round_id
                WHERE r.session_id = ?
                ORDER BY r.created_at_ms, m.created_at_ms, m.id
                """, this::roundMessage, reviewId);
    }

    @Transactional(readOnly = true)
    public List<ReviewedCommitRow> reviewedCommits(String reviewId)
    {
        return jdbc.query("""
                SELECT c.* FROM review_round_commit c
                JOIN review_round r ON r.id = c.round_id
                WHERE r.session_id = ?
                ORDER BY r.created_at_ms, c.position
                """, (rs, n) -> new ReviewedCommitRow(
                rs.getString("round_id"), rs.getString("sha"),
                rs.getString("message"), rs.getInt("position")), reviewId);
    }

    @Transactional(readOnly = true)
    public List<CriterionRow> criteria(String reviewId)
    {
        return jdbc.query("""
                SELECT DISTINCT c.* FROM criterion c
                JOIN review_objective o ON o.criterion_id = c.id
                JOIN review_round r ON r.id = o.round_id WHERE r.session_id = ? ORDER BY c.id
                """, (rs, n) -> new CriterionRow(rs.getString("id"), rs.getString("repo_id"),
                rs.getString("kind"), rs.getString("statement"), rs.getString("source_type"),
                rs.getString("source_ref")), reviewId);
    }

    @Transactional(readOnly = true)
    public List<ReviewObjectiveRow> objectives(String reviewId)
    {
        return jdbc.query("""
                SELECT o.* FROM review_objective o JOIN review_round r ON r.id = o.round_id
                WHERE r.session_id = ? ORDER BY o.id
                """, (rs, n) -> new ReviewObjectiveRow(rs.getString("id"), rs.getString("round_id"),
                rs.getString("criterion_id"), rs.getString("statement"), rs.getString("source"),
                rs.getString("applicability_status"), rs.getString("resolution_status")), reviewId);
    }

    @Transactional(readOnly = true)
    public List<ReviewAssignmentRow> assignments(String reviewId)
    {
        return jdbc.query("""
                SELECT a.* FROM review_assignment a JOIN review_round r ON r.id = a.round_id
                WHERE r.session_id = ? ORDER BY a.id
                """, (rs, n) -> new ReviewAssignmentRow(rs.getString("id"), rs.getString("round_id"),
                rs.getString("reviewer_def_id"), rs.getString("runner"), rs.getString("status"),
                rs.getString("understanding_summary"), strings(rs.getString("assumptions_json")),
                strings(rs.getString("unknowns_json")), value(rs.getString("budget_json"), AssignmentBudget.class)),
                reviewId);
    }

    @Transactional(readOnly = true)
    public List<HypothesisRow> hypotheses(String reviewId)
    {
        return jdbc.query("""
                SELECT h.* FROM hypothesis h JOIN review_assignment a ON a.id = h.assignment_id
                JOIN review_round r ON r.id = a.round_id WHERE r.session_id = ? ORDER BY h.id
                """, (rs, n) -> new HypothesisRow(rs.getString("id"), rs.getString("assignment_id"),
                rs.getString("objective_id"), rs.getString("claim"), rs.getString("origin"),
                rs.getString("status"), rs.getString("confidence_class")), reviewId);
    }

    @Transactional(readOnly = true)
    public List<InvestigationStepRow> steps(String reviewId)
    {
        return jdbc.query("""
                SELECT s.* FROM investigation_step s JOIN review_assignment a ON a.id = s.assignment_id
                JOIN review_round r ON r.id = a.round_id WHERE r.session_id = ? ORDER BY s.rowid
                """, (rs, n) -> new InvestigationStepRow(rs.getString("id"), rs.getString("assignment_id"),
                rs.getString("hypothesis_id"), rs.getString("action_type"), argumentsTree(rs.getString("arguments_json")),
                rs.getString("reason"), rs.getInt("planned") != 0, rs.getInt("cost_cents"),
                rs.getString("status")), reviewId);
    }

    @Transactional(readOnly = true)
    public List<ObservationRow> observations(String reviewId)
    {
        return jdbc.query("""
                SELECT o.* FROM observation o JOIN investigation_step s ON s.id = o.step_id
                JOIN review_assignment a ON a.id = s.assignment_id JOIN review_round r ON r.id = a.round_id
                WHERE r.session_id = ? ORDER BY o.rowid
                """, (rs, n) -> new ObservationRow(rs.getString("id"), rs.getString("step_id"),
                rs.getString("source_type"), rs.getString("commit_sha"), rs.getString("path"),
                integer(rs, "start_line"), integer(rs, "end_line"), rs.getString("symbol"),
                rs.getString("command"), integer(rs, "exit_code"), rs.getString("artifact_ref"),
                rs.getString("content_digest"), rs.getString("preview")), reviewId);
    }

    @Transactional(readOnly = true)
    public List<FindingRow> findings(String reviewId)
    {
        return jdbc.query("SELECT * FROM finding WHERE session_id = ? ORDER BY rowid", this::finding, reviewId);
    }

    @Transactional(readOnly = true)
    public List<FindingEvidenceRow> evidence(String reviewId)
    {
        return jdbc.query("""
                SELECT e.* FROM finding_evidence e JOIN finding f ON f.id = e.finding_id
                WHERE f.session_id = ? ORDER BY e.finding_id, e.observation_id
                """, (rs, n) -> new FindingEvidenceRow(rs.getString("finding_id"),
                rs.getString("observation_id"), rs.getString("relation"), rs.getString("proposition"),
                rs.getString("strength_class"), rs.getString("strength_reason"),
                rs.getString("dependency_mode"), tree(rs.getString("dependency_json"))), reviewId);
    }

    @Transactional(readOnly = true)
    public List<FindingVerificationRow> verifications(String reviewId)
    {
        return jdbc.query("""
                SELECT v.* FROM finding_verification v JOIN finding f ON f.id = v.finding_id
                WHERE f.session_id = ? ORDER BY v.rowid
                """, (rs, n) -> new FindingVerificationRow(rs.getString("id"), rs.getString("finding_id"),
                rs.getString("verifier_run_id"), rs.getInt("evidence_accurate") != 0,
                rs.getInt("claim_scope_accurate") != 0, rs.getInt("severity_accurate") != 0,
                strings(rs.getString("counter_evidence_json")), rs.getString("status"),
                rs.getString("confidence_class"), rs.getString("explanation")), reviewId);
    }

    @Transactional(readOnly = true)
    public List<FindingRelationRow> relations(String reviewId)
    {
        return jdbc.query("""
                SELECT rel.* FROM finding_relation rel JOIN finding f ON f.id = rel.source_finding_id
                WHERE f.session_id = ? ORDER BY rel.source_finding_id
                """, (rs, n) -> new FindingRelationRow(rs.getString("source_finding_id"),
                rs.getString("target_finding_id"), rs.getString("relation")), reviewId);
    }

    @Transactional(readOnly = true)
    public List<ReviewOutcomeRow> outcomes(String reviewId)
    {
        return jdbc.query("""
                SELECT o.* FROM review_outcome o JOIN finding f ON f.id = o.finding_id
                WHERE f.session_id = ? ORDER BY o.recorded_at_ms
                """, (rs, n) -> new ReviewOutcomeRow(rs.getString("finding_id"),
                rs.getString("user_disposition"), rs.getString("author_response"),
                rs.getString("epistemic_resolution"), rs.getString("utility_assessment"),
                rs.getInt("style_edit_magnitude")), reviewId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeItemRow> knowledge(String repoId)
    {
        return jdbc.query("SELECT * FROM knowledge_item WHERE repo_id = ? ORDER BY id",
                (rs, n) -> new KnowledgeItemRow(rs.getString("id"), rs.getString("repo_id"),
                        rs.getString("subtype"), rs.getString("statement"),
                        nullableStrings(rs.getString("steps_json")), tree(rs.getString("trigger_json")),
                        rs.getString("state")), repoId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeProvenanceRow> knowledgeProvenance(String repoId)
    {
        return jdbc.query("""
                SELECT p.* FROM knowledge_provenance p JOIN knowledge_item k ON k.id = p.knowledge_item_id
                WHERE k.repo_id = ? ORDER BY p.knowledge_item_id
                """, (rs, n) -> new KnowledgeProvenanceRow(rs.getString("knowledge_item_id"),
                rs.getString("source_kind"), rs.getString("source_ref")), repoId);
    }

    private AgentReviewRow review(ResultSet rs, int ignored) throws SQLException
    {
        return new AgentReviewRow(rs.getString("id"), rs.getString("repo_id"),
                rs.getString("pr_id"), rs.getString("base_commit"),
                rs.getString("reviewed_head_commit"), rs.getString("status"),
                rs.getString("workspace_id"), rs.getString("owner_thread_id"),
                rs.getString("owner_task_id"));
    }

    private ReviewerDefRow reviewerDef(ResultSet rs, int ignored) throws SQLException
    {
        return new ReviewerDefRow(
                rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("runner"), tree(rs.getString("runner_json")), rs.getString("persona"),
                strings(rs.getString("eligible_kinds")), rs.getInt("enabled") != 0);
    }

    private ReviewRoundRow round(ResultSet rs, int ignored) throws SQLException
    {
        return new ReviewRoundRow(rs.getString("id"), rs.getString("session_id"),
                rs.getString("agent_run_id"), rs.getString("trigger"), rs.getString("scope"),
                rs.getString("start_commit"), rs.getString("end_commit"), rs.getString("status"),
                value(rs.getString("budget_json"), RoundBudget.class), rs.getInt("cost_cents"),
                value(rs.getString("capabilities_json"), ReviewCapabilities.class),
                rs.getString("trigger_stage_id"), rs.getInt("message_gate_open") != 0);
    }

    private ReviewRoundSnapshot roundSnapshot(ResultSet rs, int ignored)
            throws SQLException
    {
        return new ReviewRoundSnapshot(
                rs.getString("round_id"), rs.getString("repository"),
                integer(rs, "remote_pr_number"), rs.getString("base_branch"),
                rs.getString("pr_title"), rs.getString("pr_description"),
                rs.getString("base_commit"),
                rs.getString("head_commit"), rs.getString("diff"),
                diffFiles(rs.getString("files_json")),
                stringMap(rs.getString("file_contents_json")),
                rs.getString("local_root"),
                rs.getString("repository_root"),
                value(rs.getString("capabilities_json"), ReviewCapabilities.class),
                rs.getLong("created_at_ms"));
    }

    private ReviewRoundMessageRow roundMessage(ResultSet rs, int ignored) throws SQLException
    {
        long completedAt = rs.getLong("completed_at_ms");
        boolean completedAtNull = rs.wasNull();
        return new ReviewRoundMessageRow(
                rs.getString("id"), rs.getString("round_id"), rs.getString("assignment_id"),
                rs.getString("target"),
                rs.getString("sender"), rs.getString("body"), rs.getString("status"),
                rs.getString("response"), rs.getLong("created_at_ms"),
                completedAtNull ? null : completedAt);
    }

    private FindingRow finding(ResultSet rs, int ignored) throws SQLException
    {
        return new FindingRow(rs.getString("id"), rs.getString("session_id"),
                rs.getString("round_id"), rs.getString("objective_id"),
                rs.getString("hypothesis_id"), rs.getString("criterion_kind"),
                rs.getString("path"), integer(rs, "start_line"), integer(rs, "end_line"),
                rs.getString("claim"), rs.getInt("severity"), rs.getString("confidence_class"),
                rs.getString("verification_status"), rs.getString("requested_action"),
                rs.getString("lifecycle_status"), rs.getString("last_checked_commit"));
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("could not encode review artifact", e);
        }
    }

    private <T> T value(String json, Class<T> type)
    {
        try {
            return mapper.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt review artifact JSON", e);
        }
    }

    private List<String> strings(String json)
    {
        try {
            return mapper.readValue(json, STRING_LIST);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt review string list", e);
        }
    }

    private List<DiffFile> diffFiles(String json)
    {
        try {
            return mapper.readValue(json, DIFF_FILE_LIST);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt review diff-file list", e);
        }
    }

    private Map<String, String> stringMap(String json)
    {
        try {
            return mapper.readValue(json, STRING_MAP);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt review string map", e);
        }
    }

    private List<String> nullableStrings(String json)
    {
        return json == null ? null : strings(json);
    }

    // Heals step rows written before arguments were normalized: a JSON object
    // encoded as a string (a text node) is re-parsed so consumers always see an
    // object, never a bare string that breaks `key in arguments` in the UI.
    private JsonNode argumentsTree(String json)
    {
        JsonNode node = tree(json);
        if (node.isTextual()) {
            try {
                JsonNode reparsed = mapper.readTree(node.asText());
                return reparsed.isContainerNode() ? reparsed : mapper.createObjectNode();
            }
            catch (JsonProcessingException e) {
                return mapper.createObjectNode();
            }
        }
        return node;
    }

    private JsonNode tree(String json)
    {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt review JSON", e);
        }
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private int count(String sql, Object... arguments)
    {
        Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }
}
