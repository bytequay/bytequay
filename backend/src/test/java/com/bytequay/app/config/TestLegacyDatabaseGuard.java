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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestLegacyDatabaseGuard
{
    @TempDir
    private Path dir;

    @Test
    void movesAsideDatabaseFromBeforeTheBaseline()
            throws Exception
    {
        Path database = writeHistory("bytequay.db", 21, 35);
        Files.writeString(dir.resolve("bytequay.db-wal"), "stale");

        LegacyDatabaseGuard.quarantinePreBaselineDatabase(database);

        assertThat(database).doesNotExist();
        // Whether SQLite already reaped the stale -wal or the guard moved it, no
        // sidecar may be left pointing at the new database.
        assertThat(dir.resolve("bytequay.db-wal")).doesNotExist();
        assertThat(quarantined())
                .anyMatch(path -> path.getFileName().toString().startsWith("bytequay.db.pre-308-"));
    }

    @Test
    void keepsDatabaseThatStartsAtTheBaseline()
            throws Exception
    {
        Path database = writeHistory("bytequay.db", 308, 331);

        LegacyDatabaseGuard.quarantinePreBaselineDatabase(database);

        assertThat(database).exists();
        assertThat(quarantined()).isEmpty();
    }

    @Test
    void ignoresAMissingOrHistorylessDatabase()
            throws Exception
    {
        LegacyDatabaseGuard.quarantinePreBaselineDatabase(dir.resolve("absent.db"));

        Path empty = dir.resolve("bytequay.db");
        Files.createFile(empty);
        LegacyDatabaseGuard.quarantinePreBaselineDatabase(empty);

        assertThat(empty).exists();
        assertThat(quarantined()).isEmpty();
    }

    private Path writeHistory(String name, int... versions)
            throws SQLException
    {
        Path database = dir.resolve(name);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE flyway_schema_history (version TEXT, success BOOLEAN)");
            for (int version : versions) {
                statement.execute("INSERT INTO flyway_schema_history VALUES ('%d', 1)".formatted(version));
            }
        }
        return database;
    }

    private List<Path> quarantined()
            throws IOException
    {
        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().contains(".pre-308-")).toList();
        }
    }
}
