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

import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Explicit physical-delete boundary for standalone full-review execution.
 * ReviewSession owns its snapshot and review-seat tickets without a Trunk, so
 * the ordinary review cascade cannot remove their Workspace foreign keys.
 */
@Component
public final class ReviewSessionPurge
{
    private final JdbcTemplate jdbc;
    private final DispatchTicketControl tickets;
    private final TransactionTemplate transactions;

    public ReviewSessionPurge(
            JdbcTemplate jdbc,
            DispatchTicketControl tickets,
            PlatformTransactionManager transactionManager)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.transactions = new TransactionTemplate(requireNonNull(
                transactionManager, "transactionManager is null"));
    }

    /**
     * Cancels every exact standalone review ticket before deleting its owner,
     * then removes only that now-ownerless infrastructure graph. The callback
     * and cleanup share one transaction with the enclosing Workspace delete.
     */
    public void purgeWorkspace(String workspaceId, Runnable deleteReviewRows)
    {
        requireText(workspaceId, "workspaceId");
        requireNonNull(deleteReviewRows, "deleteReviewRows is null");
        transactions.executeWithoutResult(ignored -> {
            Set<String> ticketIds = new LinkedHashSet<>(ticketIds(workspaceId));
            requireNoUnexpectedTickets(workspaceId, ticketIds.size());
            ticketIds.forEach(tickets::requestCancel);

            int authorizationCount = jdbc.update("""
                    INSERT INTO review_session_purge_authorization_v293(
                        review_id, workspace_id, authorized_at_ms)
                    SELECT review.id, review.workspace_id, ?
                    FROM review_session review
                    WHERE review.workspace_id = ?
                      AND review.owner_thread_id IS NULL
                      AND review.owner_task_id IS NULL
                    """, System.currentTimeMillis(), workspaceId);

            deleteReviewRows.run();

            int reviews = count("""
                    SELECT COUNT(*) FROM review_session
                    WHERE workspace_id = ?
                    """, workspaceId);
            if (reviews != 0) {
                throw new IllegalStateException(
                        "Workspace still owns " + reviews
                                + " ReviewSession row(s): " + workspaceId);
            }

            List<String> raced = ticketIds(workspaceId);
            requireNoUnexpectedTickets(workspaceId, raced.size());
            raced.forEach(tickets::requestCancel);
            ticketIds.addAll(raced);

            for (String ticketId : ticketIds) {
                jdbc.update("""
                        DELETE FROM outbox
                        WHERE aggregate_kind = 'DISPATCH_TICKET'
                          AND aggregate_id = ?
                        """, ticketId);
                jdbc.update("""
                        DELETE FROM dispatch_ticket
                        WHERE id = ? AND workspace_id = ?
                          AND trunk_id IS NULL AND task_id IS NULL
                          AND stage_id IS NULL
                          AND ((owner_kind = 'REVIEW_SESSION'
                                AND operation_kind =
                                    'CAPTURE_REVIEW_SESSION_SNAPSHOT'
                                AND callback_route =
                                    'REVIEW_SESSION_SNAPSHOT_RESULT'
                                AND async_family = 'LOCAL_GIT'
                                AND lane_mask = 48)
                            OR (owner_kind = 'REVIEW_ASSIGNMENT_TURN'
                                AND operation_kind =
                                    'EXECUTE_REVIEW_ASSIGNMENT_TURN'
                                AND callback_route =
                                    'REVIEW_ASSIGNMENT_TURN_RESULT'
                                AND async_family = 'AGENT_TURN'
                                AND lane_mask IN (9, 10)))
                        """, ticketId, workspaceId);
            }

            requireNoUnexpectedTickets(workspaceId, 0);
            int cleared = jdbc.update("""
                    DELETE FROM review_session_purge_authorization_v293
                    WHERE workspace_id = ?
                    """, workspaceId);
            if (cleared != authorizationCount) {
                throw new IllegalStateException(
                        "ReviewSession purge authorization changed during cleanup");
            }
        });
    }

    private List<String> ticketIds(String workspaceId)
    {
        return jdbc.query("""
                SELECT id FROM dispatch_ticket
                WHERE workspace_id = ?
                  AND trunk_id IS NULL AND task_id IS NULL AND stage_id IS NULL
                  AND ((owner_kind = 'REVIEW_SESSION'
                        AND operation_kind = 'CAPTURE_REVIEW_SESSION_SNAPSHOT'
                        AND callback_route = 'REVIEW_SESSION_SNAPSHOT_RESULT'
                        AND async_family = 'LOCAL_GIT' AND lane_mask = 48)
                    OR (owner_kind = 'REVIEW_ASSIGNMENT_TURN'
                        AND operation_kind = 'EXECUTE_REVIEW_ASSIGNMENT_TURN'
                        AND callback_route = 'REVIEW_ASSIGNMENT_TURN_RESULT'
                        AND async_family = 'AGENT_TURN' AND lane_mask IN (9, 10)))
                ORDER BY created_at_ms, id
                """, (rs, row) -> rs.getString("id"), workspaceId);
    }

    private void requireNoUnexpectedTickets(String workspaceId, int exactCount)
    {
        int broadCount = count("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE workspace_id = ?
                  AND trunk_id IS NULL AND task_id IS NULL AND stage_id IS NULL
                  AND owner_kind IN (
                      'REVIEW_SESSION', 'REVIEW_ASSIGNMENT_TURN')
                """, workspaceId);
        if (broadCount != exactCount) {
            throw new IllegalStateException(
                    "Workspace has an unexpected standalone review ticket shape: "
                            + workspaceId);
        }
    }

    private int count(String sql, String workspaceId)
    {
        Integer result = jdbc.queryForObject(sql, Integer.class, workspaceId);
        return result == null ? 0 : result;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
