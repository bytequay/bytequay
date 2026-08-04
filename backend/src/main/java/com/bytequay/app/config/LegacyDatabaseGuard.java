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
package com.bytequay.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * Moves aside a database written before the V308 squashed baseline.
 *
 * <p>V308__baseline.sql replaced the V1..V307 chain, so a database that
 * stopped anywhere in that chain can never be migrated forward: Flyway's
 * validation fails on the applied-but-unresolvable history rows, the
 * context never starts, and the app looks like it has no backend at all.
 * Renaming the file lets the app rebuild a fresh database on the next
 * migrate. The old file is kept, not deleted, so nothing is lost that the
 * user could still open by hand.
 *
 * <p>Must run before the DataSource is created — renaming a SQLite file
 * out from under an open connection leaves the connection on the old inode.
 */
public final class LegacyDatabaseGuard
{
    private static final Logger log = LoggerFactory.getLogger(LegacyDatabaseGuard.class);

    /** Version of the squashed baseline; anything older has no upgrade path. */
    private static final int BASELINE_VERSION = 308;

    private LegacyDatabaseGuard() {}

    public static void quarantinePreBaselineDatabase(Path database)
            throws IOException
    {
        if (!Files.exists(database)) {
            return;
        }
        int oldest = oldestAppliedVersion(database);
        if (oldest < 0 || oldest >= BASELINE_VERSION) {
            return;
        }
        String suffix = ".pre-%d-%d".formatted(BASELINE_VERSION, Instant.now().toEpochMilli());
        // The write-ahead log and shared-memory files belong to the old
        // database; leaving them behind would attach stale pages to the new one.
        for (String sidecar : List.of("", "-wal", "-shm")) {
            Path file = database.resolveSibling(database.getFileName() + sidecar);
            if (Files.exists(file)) {
                Files.move(file, file.resolveSibling(file.getFileName() + suffix));
            }
        }
        log.warn("Database was at schema version {}, older than the {} baseline — moved it aside "
                + "(suffix {}) and starting a fresh one.", oldest, BASELINE_VERSION, suffix);
    }

    /**
     * Oldest successfully applied migration version, or -1 when the file has no
     * Flyway history yet (fresh install) or cannot be read.
     */
    private static int oldestAppliedVersion(Path database)
    {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT MIN(CAST(version AS INTEGER)) FROM flyway_schema_history "
                                + "WHERE version IS NOT NULL AND success = 1")) {
            if (!rs.next()) {
                return -1;
            }
            int oldest = rs.getInt(1);
            // MIN() over an empty history returns NULL, which getInt reports as 0.
            return rs.wasNull() ? -1 : oldest;
        }
        catch (SQLException e) {
            // No history table, or not a readable SQLite file — let Flyway decide.
            log.debug("Could not read Flyway history from {}", database, e);
            return -1;
        }
    }
}
