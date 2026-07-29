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

import com.bytequay.app.developmentflow.stage.V2ReadinessNotificationProjector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/** SQLite adapter for exact-head readiness-notification projection claims. */
@Repository
public class SqliteReadinessNotificationProjectionStore
        implements V2ReadinessNotificationProjector.Store
{
    private static final int SCAN_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SqliteReadinessNotificationProjectionStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = requireNonNull(transactions, "transactions is null");
    }

    @Override
    public void project(
            Instant now,
            Consumer<V2ReadinessNotificationProjector.ReadyNotification> delivery)
    {
        requireNonNull(now, "now is null");
        requireNonNull(delivery, "delivery is null");
        long nowMs = now.toEpochMilli();
        transactions.executeWithoutResult(ignored -> resetRegressedEdges(nowMs));
        for (String stageId : readyStageIds()) {
            transactions.executeWithoutResult(
                    ignored -> deliverIfNewReadyEdge(stageId, nowMs, delivery));
        }
    }

    private void resetRegressedEdges(long nowMs)
    {
        jdbc.update("""
                UPDATE remote_readiness_notification_marker_v271 AS marker
                SET state = 'REGRESSED', regressed_at_ms = ?, updated_at_ms = ?
                WHERE marker.state = 'DELIVERED'
                  AND EXISTS (
                    SELECT 1
                    FROM stage_transition transition
                    WHERE transition.stage_id = marker.stage_id
                      AND transition.stage_version > marker.ready_stage_version
                      AND transition.from_checkpoint = 'READY_TO_MERGE'
                      AND transition.to_checkpoint IN (
                        'WAITING_CI', 'WAITING_REMOTE_REVIEW'))
                """, nowMs, nowMs);
    }

    private List<String> readyStageIds()
    {
        return jdbc.queryForList("""
                SELECT owner.id
                FROM stage owner
                JOIN tasks task ON task.id = owner.task_id
                JOIN task_current_stage current
                  ON current.task_id = task.id
                 AND current.stage_id = owner.id
                 AND current.stage_generation = owner.generation
                JOIN remote_development_stage remote
                  ON remote.stage_id = owner.id
                 AND remote.task_id = task.id
                 AND remote.generation = owner.generation
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                 AND snapshot.remote_development_stage_id = remote.stage_id
                 AND snapshot.task_id = task.id
                 AND snapshot.task_epoch = task.epoch
                 AND snapshot.stage_generation = owner.generation
                 AND snapshot.observation_revision =
                       remote.accepted_observation_revision
                 AND snapshot.head_sha = remote.current_head_sha
                 AND snapshot.base_sha = remote.current_base_sha
                JOIN remote_readiness_evidence readiness
                  ON readiness.remote_development_stage_id = remote.stage_id
                 AND readiness.task_id = task.id
                 AND readiness.task_epoch = task.epoch
                 AND readiness.stage_generation = owner.generation
                 AND readiness.remote_pr_snapshot_id = snapshot.id
                 AND readiness.head_sha = remote.current_head_sha
                 AND readiness.base_sha = remote.current_base_sha
                 AND readiness.ready = 1
                JOIN task_automation_policy policy
                  ON policy.id = readiness.automation_policy_id
                 AND policy.task_id = task.id
                 AND policy.revision = (
                    SELECT MAX(latest.revision)
                    FROM task_automation_policy latest
                    WHERE latest.task_id = task.id)
                WHERE task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.checkpoint = 'READY_TO_MERGE'
                  AND owner.completed_at_ms IS NULL
                ORDER BY owner.opened_at_ms, owner.id
                LIMIT ?
                """, String.class, SCAN_LIMIT);
    }

    private void deliverIfNewReadyEdge(
            String stageId,
            long nowMs,
            Consumer<V2ReadinessNotificationProjector.ReadyNotification> delivery)
    {
        int claimed = jdbc.update("""
                INSERT INTO remote_readiness_notification_marker_v271(
                    stage_id, workspace_id, trunk_id, task_id, task_epoch,
                    stage_generation, ready_stage_version,
                    readiness_evidence_id, head_sha, state, delivered_at_ms,
                    regressed_at_ms, updated_at_ms)
                SELECT owner.id, trunk.workspace_id, trunk.id, task.id,
                       task.epoch, owner.generation, owner.version,
                       readiness.id, remote.current_head_sha, 'DELIVERED', ?,
                       NULL, ?
                FROM stage owner
                JOIN tasks task ON task.id = owner.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current
                  ON current.task_id = task.id
                 AND current.stage_id = owner.id
                 AND current.stage_generation = owner.generation
                JOIN remote_development_stage remote
                  ON remote.stage_id = owner.id
                 AND remote.task_id = task.id
                 AND remote.generation = owner.generation
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                 AND snapshot.remote_development_stage_id = remote.stage_id
                 AND snapshot.task_id = task.id
                 AND snapshot.task_epoch = task.epoch
                 AND snapshot.stage_generation = owner.generation
                 AND snapshot.observation_revision =
                       remote.accepted_observation_revision
                 AND snapshot.head_sha = remote.current_head_sha
                 AND snapshot.base_sha = remote.current_base_sha
                JOIN remote_readiness_evidence readiness
                  ON readiness.remote_development_stage_id = remote.stage_id
                 AND readiness.task_id = task.id
                 AND readiness.task_epoch = task.epoch
                 AND readiness.stage_generation = owner.generation
                 AND readiness.remote_pr_snapshot_id = snapshot.id
                 AND readiness.head_sha = remote.current_head_sha
                 AND readiness.base_sha = remote.current_base_sha
                 AND readiness.ready = 1
                JOIN task_automation_policy policy
                  ON policy.id = readiness.automation_policy_id
                 AND policy.task_id = task.id
                 AND policy.revision = (
                    SELECT MAX(latest.revision)
                    FROM task_automation_policy latest
                    WHERE latest.task_id = task.id)
                WHERE owner.id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.checkpoint = 'READY_TO_MERGE'
                  AND owner.completed_at_ms IS NULL
                ON CONFLICT(stage_id) DO UPDATE SET
                    workspace_id = excluded.workspace_id,
                    trunk_id = excluded.trunk_id,
                    task_id = excluded.task_id,
                    task_epoch = excluded.task_epoch,
                    stage_generation = excluded.stage_generation,
                    ready_stage_version = excluded.ready_stage_version,
                    readiness_evidence_id = excluded.readiness_evidence_id,
                    head_sha = excluded.head_sha,
                    state = 'DELIVERED',
                    delivered_at_ms = excluded.delivered_at_ms,
                    regressed_at_ms = NULL,
                    updated_at_ms = excluded.updated_at_ms
                WHERE remote_readiness_notification_marker_v271.state = 'REGRESSED'
                   OR EXISTS (
                    SELECT 1
                    FROM stage_transition transition
                    WHERE transition.stage_id = excluded.stage_id
                      AND transition.stage_version >
                            remote_readiness_notification_marker_v271.ready_stage_version
                      AND transition.stage_version < excluded.ready_stage_version
                      AND transition.from_checkpoint = 'READY_TO_MERGE'
                      AND transition.to_checkpoint IN (
                        'WAITING_CI', 'WAITING_REMOTE_REVIEW'))
                """, nowMs, nowMs, stageId);
        if (claimed != 1) {
            return;
        }

        V2ReadinessNotificationProjector.ReadyNotification ready =
                jdbc.queryForObject("""
                        SELECT marker.workspace_id, marker.trunk_id,
                               marker.task_id, marker.stage_id,
                               marker.ready_stage_version,
                               marker.readiness_evidence_id, marker.head_sha,
                               snapshot.remote_pr_number
                        FROM remote_readiness_notification_marker_v271 marker
                        JOIN remote_readiness_evidence readiness
                          ON readiness.id = marker.readiness_evidence_id
                        JOIN remote_pr_snapshot snapshot
                          ON snapshot.id = readiness.remote_pr_snapshot_id
                        WHERE marker.stage_id = ?
                          AND marker.state = 'DELIVERED'
                        """, (row, ignored) ->
                                new V2ReadinessNotificationProjector.ReadyNotification(
                                        row.getString("workspace_id"),
                                        row.getString("trunk_id"),
                                        row.getString("task_id"),
                                        row.getString("stage_id"),
                                        row.getLong("ready_stage_version"),
                                        row.getString("readiness_evidence_id"),
                                        row.getString("head_sha"),
                                        row.getInt("remote_pr_number")), stageId);
        delivery.accept(requireNonNull(
                ready, "claimed readiness marker disappeared"));
    }
}
