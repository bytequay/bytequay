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
package com.bytequay.app.testing;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Copies a once-per-JVM Flyway baseline into an isolated SQLite test file. */
public final class MigratedSqliteDatabase
{
    private static final String LATEST = "latest";
    private static final Path BASELINE_DIRECTORY = createBaselineDirectory();
    private static final Map<String, Path> BASELINES = new HashMap<>();

    private MigratedSqliteDatabase() {}

    public static void copyTo(Path database)
    {
        copyTo(database, null);
    }

    public static void copyTo(Path database, String target)
    {
        requireNonNull(database, "database is null");
        try {
            Path parent = database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(baseline(target), database);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not copy migrated SQLite test database", e);
        }
    }

    private static synchronized Path baseline(String target)
    {
        String key = target == null ? LATEST : target;
        Path existing = BASELINES.get(key);
        if (existing != null) {
            return existing;
        }

        try {
            Path database = Files.createTempFile(BASELINE_DIRECTORY, "schema-", ".db");
            database.toFile().deleteOnExit();
            String url = "jdbc:sqlite:" + database + "?foreign_keys=ON";
            FluentConfiguration configuration = Flyway.configure().dataSource(url, "", "");
            if (target != null) {
                configuration.target(target);
            }
            configuration.load().migrate();
            BASELINES.put(key, database);
            return database;
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not create migrated SQLite test baseline", e);
        }
    }

    private static Path createBaselineDirectory()
    {
        try {
            Path directory = Files.createTempDirectory("bytequay-flyway-baselines-");
            directory.toFile().deleteOnExit();
            return directory;
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not create SQLite test baseline directory", e);
        }
    }
}
