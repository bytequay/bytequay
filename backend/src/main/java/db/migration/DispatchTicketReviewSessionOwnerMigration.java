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
package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Forward-only SQLite table rebuild that adds REVIEW_SESSION owner support. */
public final class DispatchTicketReviewSessionOwnerMigration
        implements JavaMigration
{
    private static final String OLD_OWNER_CHECK =
            "'STAGE_TURN', 'REVIEW_ASSIGNMENT_TURN'))";
    private static final String NEW_OWNER_CHECK =
            "'STAGE_TURN', 'REVIEW_ASSIGNMENT_TURN', 'REVIEW_SESSION'))";

    @Override
    public MigrationVersion getVersion()
    {
        return MigrationVersion.fromVersion("292");
    }

    @Override
    public String getDescription()
    {
        return "rebuild dispatch ticket review session owner";
    }

    @Override
    public Integer getChecksum()
    {
        return 292001;
    }

    @Override
    public boolean canExecuteInTransaction()
    {
        // SQLite requires foreign_keys to be disabled before BEGIN. The
        // migration opens and commits one explicit transaction itself.
        return false;
    }

    @Override
    public void migrate(Context context)
    {
        Connection connection = context.getConnection();
        try {
            requireForeignKeys(connection);
            String tableSql = schemaSql(connection, "table", "dispatch_ticket");
            if (!tableSql.contains(OLD_OWNER_CHECK)
                    || tableSql.contains("'REVIEW_SESSION'")) {
                throw new FlywayException(
                        "dispatch_ticket owner CHECK is not the expected pre-V292 schema");
            }
            List<SchemaObject> objects = rebuildObjects(connection);
            long before = count(connection, "dispatch_ticket");

            execute(connection, "PRAGMA foreign_keys = OFF");
            execute(connection, "PRAGMA legacy_alter_table = ON");
            try {
                execute(connection, "BEGIN IMMEDIATE");
                try {
                    String replacement = tableSql
                            .replaceFirst(
                                    "(?i)CREATE\\s+TABLE\\s+dispatch_ticket",
                                    "CREATE TABLE dispatch_ticket_v292")
                            .replace(OLD_OWNER_CHECK, NEW_OWNER_CHECK);
                    if (!replacement.contains(NEW_OWNER_CHECK)) {
                        throw new FlywayException(
                                "could not widen dispatch_ticket owner CHECK");
                    }
                    execute(connection, replacement);
                    execute(connection, """
                            INSERT INTO dispatch_ticket_v292
                            SELECT * FROM dispatch_ticket
                            """);
                    if (count(connection, "dispatch_ticket_v292") != before) {
                        throw new FlywayException(
                                "dispatch_ticket row count changed during V292 rebuild");
                    }
                    for (SchemaObject object : objects) {
                        String drop = object.type().equals("index")
                                ? "DROP INDEX IF EXISTS \""
                                : "DROP TRIGGER IF EXISTS \"";
                        execute(connection, drop
                                + object.name().replace("\"", "\"\"") + "\"");
                    }
                    execute(connection,
                            "ALTER TABLE dispatch_ticket "
                                    + "RENAME TO dispatch_ticket_v292_old");
                    execute(connection,
                            "ALTER TABLE dispatch_ticket_v292 RENAME TO dispatch_ticket");
                    execute(connection, "DROP TABLE dispatch_ticket_v292_old");
                    for (SchemaObject object : objects) {
                        execute(connection, object.sql());
                    }
                    requireNoRows(connection, "PRAGMA foreign_key_check",
                            "foreign_key_check failed after dispatch_ticket rebuild");
                    requireIntegrity(connection);
                    execute(connection, "COMMIT");
                }
                catch (Exception e) {
                    rollback(connection);
                    throw e;
                }
            }
            finally {
                execute(connection, "PRAGMA legacy_alter_table = OFF");
                execute(connection, "PRAGMA foreign_keys = ON");
            }

            if (count(connection, "dispatch_ticket") != before
                    || !schemaSql(connection, "table", "dispatch_ticket")
                            .contains(NEW_OWNER_CHECK)) {
                throw new FlywayException(
                        "dispatch_ticket V292 rebuild did not preserve its exact state");
            }
            for (SchemaObject object : objects) {
                if (!schemaObjectExists(connection, object.type(), object.name())) {
                    throw new FlywayException(
                            "dispatch_ticket schema object was not restored: "
                                    + object.name());
                }
            }
        }
        catch (SQLException e) {
            throw new FlywayException("could not rebuild dispatch_ticket", e);
        }
    }

    private static List<SchemaObject> rebuildObjects(Connection connection)
            throws SQLException
    {
        List<SchemaObject> objects = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT type, name, sql
                        FROM sqlite_schema
                        WHERE sql IS NOT NULL
                          AND (type = 'trigger'
                               OR (type = 'index'
                                   AND tbl_name = 'dispatch_ticket'))
                        ORDER BY CASE type WHEN 'trigger' THEN 0 ELSE 1 END, name
                        """)) {
            while (rows.next()) {
                objects.add(new SchemaObject(
                        rows.getString("type"), rows.getString("name"),
                        rows.getString("sql")));
            }
        }
        return List.copyOf(objects);
    }

    private static String schemaSql(
            Connection connection, String type, String name)
            throws SQLException
    {
        try (var statement = connection.prepareStatement("""
                SELECT sql FROM sqlite_schema WHERE type = ? AND name = ?
                """)) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) {
                    throw new FlywayException(
                            "missing SQLite schema object " + type + " " + name);
                }
                return rows.getString(1);
            }
        }
    }

    private static boolean schemaObjectExists(
            Connection connection, String type, String name)
            throws SQLException
    {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM sqlite_schema WHERE type = ? AND name = ?
                """)) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static long count(Connection connection, String table)
            throws SQLException
    {
        if (!table.equals("dispatch_ticket")
                && !table.equals("dispatch_ticket_v292")) {
            throw new IllegalArgumentException("unexpected table " + table);
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void requireForeignKeys(Connection connection)
            throws SQLException
    {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA foreign_keys")) {
            if (!rows.next() || rows.getInt(1) != 1) {
                throw new FlywayException(
                        "V292 requires SQLite foreign-key enforcement before migration");
            }
        }
    }

    private static void requireNoRows(
            Connection connection, String query, String message)
            throws SQLException
    {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(query)) {
            if (rows.next()) {
                throw new FlywayException(message);
            }
        }
    }

    private static void requireIntegrity(Connection connection)
            throws SQLException
    {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA integrity_check")) {
            if (!rows.next() || !"ok".equalsIgnoreCase(rows.getString(1))) {
                throw new FlywayException(
                        "integrity_check failed after dispatch_ticket rebuild");
            }
        }
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void rollback(Connection connection)
    {
        try {
            execute(connection, "ROLLBACK");
        }
        catch (SQLException ignored) {
        }
    }

    private record SchemaObject(String type, String name, String sql) {}
}
