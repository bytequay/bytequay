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
package com.bytequay.app.service.review;

import com.bytequay.app.developmentflow.stage.CleanupCompletionHandoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Review-owned, synchronous consumer of exact V2 TaskOutcome facts. */
@Service
public final class ReviewBuildOutcomeService
        implements CleanupCompletionHandoff.PostCompletionHook
{
    private static final Logger log = LoggerFactory.getLogger(
            ReviewBuildOutcomeService.class);
    private static final int RECOVERY_LIMIT = 1_000;

    private final JdbcTemplate jdbc;
    private final ReviewBuildSelectionStore selections;
    private final TransactionTemplate transactions;

    public ReviewBuildOutcomeService(
            JdbcTemplate jdbc,
            ReviewBuildSelectionStore selections,
            PlatformTransactionManager transactionManager)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.selections = requireNonNull(selections, "selections is null");
        this.transactions = new TransactionTemplate(requireNonNull(
                transactionManager, "transactionManager is null"));
    }

    @Override
    public void afterCommit(CleanupCompletionHandoff.Completion completion)
    {
        acceptTaskOutcome(completion.taskId());
    }

    public void acceptTaskOutcome(String taskId)
    {
        requireText(taskId, "taskId");
        transactions.executeWithoutResult(ignored -> acceptInTransaction(taskId));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnprocessedOutcomes()
    {
        List<String> pending = jdbc.query("""
                SELECT outcome.task_id
                FROM task_outcome outcome
                JOIN tasks task ON task.id = outcome.task_id
                JOIN task_assignment assignment ON assignment.id = task.assignment_id
                LEFT JOIN review_build_outcome_receipt receipt
                  ON receipt.task_outcome_id = outcome.id
                WHERE task.workflow_version = 'V2'
                  AND assignment.kind = 'REVIEW_FINDINGS'
                  AND receipt.task_outcome_id IS NULL
                ORDER BY outcome.recorded_at_ms, outcome.id
                LIMIT ?
                """, (rs, row) -> rs.getString("task_id"), RECOVERY_LIMIT);
        for (String taskId : pending) {
            try {
                acceptTaskOutcome(taskId);
            }
            catch (RuntimeException failure) {
                log.warn("Review TaskOutcome recovery for {} failed: {}",
                        taskId, failure.getMessage());
            }
        }
    }

    private void acceptInTransaction(String taskId)
    {
        OwnedOutcome outcome = findOwnedOutcome(taskId).orElse(null);
        if (outcome == null || hasReceipt(outcome.outcomeId())) {
            return;
        }
        ReviewBuildSelectionStore.Selection selection = selections
                .find(outcome.threadId())
                .orElseThrow(() -> new IllegalStateException(
                        "review TaskOutcome lost its frozen selection"));
        if (!outcome.reviewPassId().equals(selection.reviewPassId())
                || !outcome.selectionDigest().equals(selection.selectionDigest())) {
            throw new IllegalStateException(
                    "review TaskOutcome names a sibling selection");
        }

        List<AssignedFinding> assigned = jdbc.query("""
                SELECT source_review_id, finding_id, finding_revision,
                       content_digest
                FROM task_assignment_review_finding
                WHERE assignment_id = ? ORDER BY position
                """, (rs, row) -> new AssignedFinding(
                rs.getString("source_review_id"), rs.getString("finding_id"),
                rs.getInt("finding_revision"), rs.getString("content_digest")),
                outcome.assignmentId());
        boolean exactAssignment = assigned.equals(selection.findings().stream()
                .map(finding -> new AssignedFinding(
                        finding.reviewPassId(), finding.findingId(),
                        finding.findingRevision(), finding.contentDigest()))
                .toList());

        if (!"COMPLETED".equals(outcome.terminalReason())) {
            insertReceipt(outcome, "IGNORED_TERMINAL", 0,
                    "TaskOutcome is not COMPLETED");
            return;
        }
        if (!exactAssignment || !selections.matchesCurrent(selection)) {
            insertReceipt(outcome, "STALE_SELECTION", 0,
                    exactAssignment
                            ? "mutable finding revision no longer matches the freeze"
                            : "Task assignment does not equal the frozen selection");
            return;
        }

        int resolved = 0;
        for (ReviewBuildSelectionStore.Finding finding : selection.findings()) {
            int changed = jdbc.update("""
                    UPDATE review_findings
                    SET status = 'resolved', resolution = ?
                    WHERE id = ? AND review_pass_id = ? AND revision = ?
                      AND status IN ('agreed', 'arbitrated')
                    """,
                    "task_outcome:" + outcome.outcomeId(),
                    finding.findingId(), finding.reviewPassId(),
                    finding.findingRevision());
            if (changed != 1) {
                throw new IllegalStateException(
                        "review finding changed during TaskOutcome resolution: "
                                + finding.findingId());
            }
            resolved++;
        }
        insertReceipt(outcome, "RESOLVED", resolved,
                "exact frozen findings resolved by completed TaskOutcome");
    }

    private Optional<OwnedOutcome> findOwnedOutcome(String taskId)
    {
        return jdbc.query("""
                SELECT outcome.id AS outcome_id, outcome.task_id,
                       outcome.terminal_reason, task.thread_id,
                       task.assignment_id, assignment.source_id,
                       selection.selection_digest
                FROM task_outcome outcome
                JOIN tasks task ON task.id = outcome.task_id
                JOIN task_assignment assignment ON assignment.id = task.assignment_id
                JOIN review_build_selection selection
                  ON selection.thread_id = task.thread_id
                 AND selection.review_pass_id = assignment.source_id
                WHERE outcome.task_id = ?
                  AND task.workflow_version = 'V2'
                  AND assignment.kind = 'REVIEW_FINDINGS'
                """, (rs, row) -> new OwnedOutcome(
                rs.getString("outcome_id"), rs.getString("task_id"),
                rs.getString("terminal_reason"), rs.getString("thread_id"),
                rs.getString("assignment_id"), rs.getString("source_id"),
                rs.getString("selection_digest")), taskId).stream().findFirst();
    }

    private boolean hasReceipt(String outcomeId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM review_build_outcome_receipt
                WHERE task_outcome_id = ?
                """, Integer.class, outcomeId);
        return count != null && count == 1;
    }

    private void insertReceipt(
            OwnedOutcome outcome,
            String disposition,
            int resolvedCount,
            String detail)
    {
        int inserted = jdbc.update("""
                INSERT OR IGNORE INTO review_build_outcome_receipt(
                    task_outcome_id, task_id, thread_id, review_pass_id,
                    terminal_reason, disposition, selection_digest,
                    resolved_count, detail, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                outcome.outcomeId(), outcome.taskId(), outcome.threadId(),
                outcome.reviewPassId(), outcome.terminalReason(), disposition,
                outcome.selectionDigest(), resolvedCount, detail,
                System.currentTimeMillis());
        if (inserted != 1 && !hasReceipt(outcome.outcomeId())) {
            throw new IllegalStateException(
                    "review TaskOutcome receipt lost its idempotency fence");
        }
    }

    private record OwnedOutcome(
            String outcomeId,
            String taskId,
            String terminalReason,
            String threadId,
            String assignmentId,
            String reviewPassId,
            String selectionDigest)
    {}

    private record AssignedFinding(
            String sourceReviewId,
            String findingId,
            int findingRevision,
            String contentDigest)
    {}

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
