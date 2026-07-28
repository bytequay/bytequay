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
package com.bytequay.app.developmentflow.trunk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static java.util.Objects.requireNonNull;

/** Transaction-local authorization for deleting one archived, quiescent V2 Trunk. */
@Component
public final class V2TrunkPurge
{
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public V2TrunkPurge(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = new TransactionTemplate(requireNonNull(
                transactionManager, "transactionManager is null"));
    }

    public void delete(String trunkId, long archivedVersion, Runnable deleteRows)
    {
        requireText(trunkId, "trunkId");
        if (archivedVersion < 0) {
            throw new IllegalArgumentException("archivedVersion is negative");
        }
        requireNonNull(deleteRows, "deleteRows is null");
        transactions.executeWithoutResult(ignored -> {
            int authorized = jdbc.update("""
                    INSERT INTO v2_trunk_purge_authorization_v269(
                        trunk_id, archived_version, authorized_at_ms)
                    VALUES (?, ?, ?)
                    """, trunkId, archivedVersion, System.currentTimeMillis());
            if (authorized != 1) {
                throw new IllegalStateException(
                        "V2 Trunk purge authorization was not recorded: " + trunkId);
            }
            deleteProtectedHistoryRows(trunkId);
            deleteRows.run();
            Integer remains = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM threads WHERE id = ?",
                    Integer.class, trunkId);
            if (remains == null || remains != 0) {
                throw new IllegalStateException(
                        "V2 Trunk purge did not delete its exact Trunk: " + trunkId);
            }
            int cleared = jdbc.update("""
                    DELETE FROM v2_trunk_purge_authorization_v269
                    WHERE trunk_id = ? AND archived_version = ?
                    """, trunkId, archivedVersion);
            if (cleared != 1) {
                throw new IllegalStateException(
                        "V2 Trunk purge authorization was not cleared: " + trunkId);
            }
        });
    }

    private void deleteProtectedHistoryRows(String trunkId)
    {
        // Delete immutable leaves while their owning rows still exist. This
        // keeps every V269 trigger exact even though SQLite removes a parent
        // before it runs cascading child deletes.
        if (tableExists("v2_user_remote_action_v270")) {
            jdbc.update("""
                    DELETE FROM v2_user_remote_action_draft_v270
                    WHERE action_id IN (
                        SELECT action.id
                        FROM v2_user_remote_action_v270 action
                        JOIN tasks task ON task.id = action.task_id
                        WHERE task.thread_id = ?
                          AND task.workflow_version = 'V2')
                    """, trunkId);
            jdbc.update("""
                    DELETE FROM v2_user_remote_action_dispatch_v270
                    WHERE action_id IN (
                        SELECT action.id
                        FROM v2_user_remote_action_v270 action
                        JOIN tasks task ON task.id = action.task_id
                        WHERE task.thread_id = ?
                          AND task.workflow_version = 'V2')
                    """, trunkId);
            jdbc.update("""
                    DELETE FROM v2_user_remote_action_v270
                    WHERE task_id IN (
                        SELECT id FROM tasks
                        WHERE thread_id = ? AND workflow_version = 'V2')
                    """, trunkId);
        }
        if (tableExists("remote_readiness_assistance_v273")) {
            jdbc.update("""
                    DELETE FROM remote_readiness_assistance_receipt_v273
                    WHERE operation_id IN (
                        SELECT assistance.operation_id
                        FROM remote_readiness_assistance_v273 assistance
                        JOIN tasks task ON task.id = assistance.task_id
                        WHERE task.thread_id = ?
                          AND task.workflow_version = 'V2')
                    """, trunkId);
            jdbc.update("""
                    DELETE FROM remote_readiness_assistance_dispatch_v273
                    WHERE assistance_id IN (
                        SELECT assistance.id
                        FROM remote_readiness_assistance_v273 assistance
                        JOIN tasks task ON task.id = assistance.task_id
                        WHERE task.thread_id = ?
                          AND task.workflow_version = 'V2')
                    """, trunkId);
            jdbc.update("""
                    DELETE FROM remote_readiness_assistance_v273
                    WHERE task_id IN (
                        SELECT id FROM tasks
                        WHERE thread_id = ? AND workflow_version = 'V2')
                    """, trunkId);
        }
        jdbc.update("""
                DELETE FROM review_build_outcome_receipt
                WHERE thread_id = ?
                """, trunkId);
        jdbc.update("""
                DELETE FROM review_build_selection_item
                WHERE thread_id = ?
                """, trunkId);
        jdbc.update("""
                DELETE FROM review_build_selection
                WHERE thread_id = ?
                """, trunkId);
        jdbc.update("""
                DELETE FROM task_assignment_review_finding
                WHERE assignment_id IN (
                    SELECT id FROM task_assignment WHERE trunk_id = ?)
                """, trunkId);
        jdbc.update("""
                DELETE FROM local_feedback_batch_item
                WHERE batch_id IN (
                    SELECT batch.id
                    FROM local_feedback_batch batch
                    JOIN tasks task ON task.id = batch.task_id
                    WHERE task.thread_id = ? AND task.workflow_version = 'V2')
                """, trunkId);
        jdbc.update("""
                DELETE FROM publish_override_item
                WHERE override_id IN (
                    SELECT override.id
                    FROM publish_override override
                    JOIN tasks task ON task.id = override.task_id
                    WHERE task.thread_id = ? AND task.workflow_version = 'V2')
                """, trunkId);
        jdbc.update("""
                DELETE FROM local_review_imported_finding
                WHERE request_id IN (
                    SELECT request.id
                    FROM local_review_agent_request request
                    JOIN tasks task ON task.id = request.task_id
                    WHERE task.thread_id = ? AND task.workflow_version = 'V2')
                """, trunkId);
        jdbc.update("""
                DELETE FROM stage_initial_result_request
                WHERE task_id IN (
                    SELECT id FROM tasks
                    WHERE thread_id = ? AND workflow_version = 'V2')
                """, trunkId);
        jdbc.update("""
                DELETE FROM cleanup_step_retry_request
                WHERE task_id IN (
                    SELECT id FROM tasks
                    WHERE thread_id = ? AND workflow_version = 'V2')
                """, trunkId);
        jdbc.update("""
                DELETE FROM remote_observation_stage_receipt
                WHERE task_id IN (
                    SELECT id FROM tasks
                    WHERE thread_id = ? AND workflow_version = 'V2')
                """, trunkId);
        jdbc.update("""
                DELETE FROM task_outcome_summary_operation
                WHERE task_id IN (
                    SELECT id FROM tasks
                    WHERE thread_id = ? AND workflow_version = 'V2')
                """, trunkId);
        jdbc.update("DELETE FROM trunk_outcome_inbox WHERE trunk_id = ?", trunkId);
        jdbc.update("DELETE FROM task_outcome WHERE trunk_id = ?", trunkId);
        jdbc.update("""
                DELETE FROM planning_base_refresh_operation
                WHERE trunk_id = ?
                """, trunkId);
    }

    private boolean tableExists(String table)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = ?
                """, Integer.class, table);
        return count != null && count == 1;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
