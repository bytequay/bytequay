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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes rows holding lifecycle values that no longer have a writer,
 * ahead of deleting those enum values: task phase {@code QUEUED} (written
 * before the task-queue materialiser was retired) becomes {@code
 * PLANNING}, and thread/task status {@code AWAITING} (written by
 * long-removed pause paths) becomes {@code IDLE}. Affected ids are logged
 * for upgrade diagnostics; the migration performs no runtime work — the
 * planning startup reconciler re-arms a normalized PLANNING task
 * idempotently.
 *
 * <p>Stage rows are preflight-only: no historical writer of {@code
 * ACTIVE}/{@code PAUSED} was ever found, so encountering one fails the
 * migration with the offending ids rather than guessing.
 */
@Component
public class NormalizeDeadLifecycleStates
        implements JavaMigration
{
    private static final Logger log = LoggerFactory.getLogger(NormalizeDeadLifecycleStates.class);

    @Override
    public MigrationVersion getVersion()
    {
        return MigrationVersion.fromVersion("204");
    }

    @Override
    public String getDescription()
    {
        return "normalize dead lifecycle states";
    }

    @Override
    public Integer getChecksum()
    {
        return 199_001;
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

        List<String> staleStages = collect(connection,
                "SELECT id FROM task_stage WHERE state IN ('ACTIVE', 'PAUSED')");
        if (!staleStages.isEmpty()) {
            throw new IllegalStateException(
                    "task_stage rows hold ACTIVE/PAUSED, which has no known writer - "
                            + "refusing to guess a normalization for ids " + staleStages);
        }

        normalize(connection, "tasks", "phase", "QUEUED", "PLANNING");
        normalize(connection, "tasks", "status", "AWAITING", "IDLE");
        normalize(connection, "threads", "status", "AWAITING", "IDLE");
    }

    private static void normalize(Connection connection, String table, String column, String from, String to)
            throws SQLException
    {
        List<String> ids = collect(connection,
                "SELECT id FROM " + table + " WHERE " + column + " = '" + from + "'");
        if (ids.isEmpty()) {
            return;
        }
        log.warn("normalizing {}.{} {} -> {} for {} row(s): {}", table, column, from, to, ids.size(), ids);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE " + table + " SET " + column + " = '" + to + "' WHERE " + column + " = '" + from + "'");
        }
    }

    private static List<String> collect(Connection connection, String sql)
            throws SQLException
    {
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        return ids;
    }
}
