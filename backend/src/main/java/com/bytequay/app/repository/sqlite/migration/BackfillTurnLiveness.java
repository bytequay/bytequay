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
package com.bytequay.app.repository.sqlite.migration;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Backfills the turn-liveness flag and each task's current-liveness
 * pointer from exact persisted evidence — the turn's initiator source,
 * never the shared thread id.
 *
 * <p>Pointer rules per task status: RUNNING requires the one exact
 * RUNNING liveness turn (several live candidates cancel those turns and
 * park the task visibly; none normalizes the stale RUNNING to IDLE);
 * IDLE/PENDING take the oldest QUEUED liveness turn when one exists and
 * otherwise stay unset — historical terminal rows are never promoted
 * into current authority; ERRORED takes its latest FAILED/CANCELLED
 * liveness turn so the retry intent has its required identity, and
 * parks with a checkpoint when none is provable.
 */
@Component
public class BackfillTurnLiveness
        implements JavaMigration
{
    private static final Logger log = LoggerFactory.getLogger(BackfillTurnLiveness.class);

    /** Initiator sources whose turns execute the task's own code work. */
    private static final String CODE_SOURCES =
            "'user', 'steering', 'plan-approved', 'automation-plan-approved', "
                    + "'local-ci-fix', 'ci-fix-shipped', 'auto-fix-ci-fail', "
                    + "'address-local-comments', 'brain-review-fix', 'review-round', "
                    + "'branch-guard-fix', 'cherry-pick-conflict'";

    @Override
    public MigrationVersion getVersion()
    {
        return MigrationVersion.fromVersion("207");
    }

    @Override
    public String getDescription()
    {
        return "backfill turn liveness and task pointers";
    }

    @Override
    public Integer getChecksum()
    {
        return 202_001;
    }

    @Override
    public boolean canExecuteInTransaction()
    {
        return true;
    }

    @Override
    public void migrate(Context context)
            throws SQLException
    {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE thread_turns SET affects_task_liveness = 1 "
                            + "WHERE task_id IS NOT NULL AND initiator_source IN (" + CODE_SOURCES + ")");
        }
        for (String[] task : rows(connection,
                "SELECT id, status, phase FROM tasks WHERE status IN "
                        + "('PENDING', 'RUNNING', 'IDLE', 'ERRORED')")) {
            backfillPointer(connection, task[0], task[1], task[2]);
        }
    }

    private void backfillPointer(Connection connection, String taskId, String status, String phase)
            throws SQLException
    {
        switch (status) {
            case "RUNNING" -> {
                List<String> running = livenessTurns(connection, taskId, "'RUNNING'");
                if (running.size() == 1) {
                    setPointer(connection, taskId, running.get(0));
                }
                else if (running.isEmpty()) {
                    log.warn("migration: RUNNING task {} has no live runtime turn; normalizing to IDLE", taskId);
                    exec(connection, "UPDATE tasks SET status = 'IDLE' WHERE id = ?", taskId);
                    statusEvent(connection, taskId, "RUNNING", "IDLE", "migration_no_live_turn");
                }
                else {
                    log.warn("migration: RUNNING task {} has {} live runtime turns {}; cancelling and parking",
                            taskId, running.size(), running);
                    for (String turnId : running) {
                        exec(connection,
                                "UPDATE thread_turns SET status = 'CANCELLED', "
                                        + "error_message = 'cancelled by lifecycle migration: ambiguous live turn' "
                                        + "WHERE id = ?", turnId);
                    }
                    park(connection, taskId, "RUNNING", phase, "migration_ambiguous_live_turns");
                }
            }
            case "PENDING", "IDLE" -> {
                List<String> queued = livenessTurns(connection, taskId, "'QUEUED'");
                if (!queued.isEmpty()) {
                    setPointer(connection, taskId, queued.get(0));
                }
            }
            case "ERRORED" -> {
                List<String> failed = livenessTurnsNewestFirst(connection, taskId, "'FAILED', 'CANCELLED'");
                if (!failed.isEmpty()) {
                    setPointer(connection, taskId, failed.get(0));
                }
                else {
                    log.warn("migration: ERRORED task {} has no provable failed runtime turn; parking", taskId);
                    park(connection, taskId, "ERRORED", phase, "migration_unprovable_errored");
                }
            }
            default -> {
                // Gate, stop, and terminal statuses keep a null pointer.
            }
        }
    }

    private static void park(
            Connection connection, String taskId, String fromStatus, String phase, String reason)
            throws SQLException
    {
        exec(connection,
                "UPDATE tasks SET status = 'NEEDS_ATTENTION', phase = 'NEEDS_ATTENTION', "
                        + "recovery_phase = '" + phase + "' WHERE id = ?", taskId);
        statusEvent(connection, taskId, fromStatus, "NEEDS_ATTENTION", reason);
    }

    private static void statusEvent(
            Connection connection, String taskId, String from, String to, String reason)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO task_status_event(task_id, from_status, to_status, actor, reason, occurred_at_ms) "
                        + "VALUES (?, ?, ?, 'SCHEDULER', ?, "
                        + "CAST(strftime('%s', 'now') AS INTEGER) * 1000)")) {
            statement.setString(1, taskId);
            statement.setString(2, from);
            statement.setString(3, to);
            statement.setString(4, reason);
            statement.executeUpdate();
        }
    }

    private static void setPointer(Connection connection, String taskId, String turnId)
            throws SQLException
    {
        exec(connection,
                "UPDATE tasks SET current_liveness_turn_id = '" + turnId + "' WHERE id = ?", taskId);
    }

    private static List<String> livenessTurns(Connection connection, String taskId, String statuses)
            throws SQLException
    {
        return turnIds(connection, taskId, statuses, "ASC");
    }

    private static List<String> livenessTurnsNewestFirst(
            Connection connection, String taskId, String statuses)
            throws SQLException
    {
        return turnIds(connection, taskId, statuses, "DESC");
    }

    private static List<String> turnIds(
            Connection connection, String taskId, String statuses, String order)
            throws SQLException
    {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM thread_turns WHERE task_id = ? AND affects_task_liveness = 1 "
                        + "AND status IN (" + statuses + ") ORDER BY created_at_ms " + order + ", id " + order)) {
            statement.setString(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
            }
        }
        return ids;
    }

    private static List<String[]> rows(Connection connection, String sql)
            throws SQLException
    {
        List<String[]> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(new String[] {rows.getString(1), rows.getString(2), rows.getString(3)});
            }
        }
        return result;
    }

    private static void exec(Connection connection, String sql, String arg)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, arg);
            statement.executeUpdate();
        }
    }
}
