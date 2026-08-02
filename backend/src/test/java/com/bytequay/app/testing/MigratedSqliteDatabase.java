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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Objects.requireNonNull;

/** Copies a once-per-JVM Flyway baseline into an isolated SQLite test file. */
public final class MigratedSqliteDatabase
{
    private static final String JDBC_SQLITE_PREFIX = "jdbc:sqlite:";
    private static final Path BASELINE_DIRECTORY = createBaselineDirectory();
    private static final Map<String, Path> FIXTURES = new HashMap<>();

    private static Path baseline;

    private MigratedSqliteDatabase() {}

    public static void copyTo(Path database)
    {
        requireNonNull(database, "database is null");
        try {
            createParent(database);
            Files.copy(baseline(), database, REPLACE_EXISTING);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not copy migrated SQLite test database", e);
        }
    }

    /**
     * Brings the SQLite database behind {@code url} up to the current schema.
     *
     * <p>An empty database is seeded from the cached baseline rather than by running
     * Flyway, which is the same end state for a few hundred times less work. A database
     * that already holds a schema goes through Flyway as usual.
     */
    public static void migrate(String url)
    {
        requireNonNull(url, "url is null");
        Path database = databaseFile(url);
        if (database != null && isEmpty(database)) {
            copyTo(database);
            return;
        }
        Flyway.configure().dataSource(url, "", "").load().migrate();
    }

    /**
     * Builds a seeded fixture database once per JVM and hands every caller its own copy.
     *
     * <p>Test classes whose cases all start from the same seeded database otherwise pay
     * for that setup once per test case. {@code key} names the fixture and must cover
     * everything {@code builder} depends on.
     */
    public static void copyFixture(String key, Path database, FixtureBuilder builder)
    {
        requireNonNull(key, "key is null");
        requireNonNull(database, "database is null");
        requireNonNull(builder, "builder is null");
        try {
            createParent(database);
            Files.copy(fixture(key, builder), database, REPLACE_EXISTING);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not copy SQLite test fixture " + key, e);
        }
    }

    /** Populates a database file that {@link #copyFixture} then reuses for every test case. */
    public interface FixtureBuilder
    {
        void build(Path database)
                throws Exception;
    }

    private static synchronized Path fixture(String key, FixtureBuilder builder)
    {
        Path existing = FIXTURES.get(key);
        if (existing != null) {
            return existing;
        }

        try {
            Path database = Files.createTempFile(BASELINE_DIRECTORY, "fixture-", ".db");
            database.toFile().deleteOnExit();
            Files.delete(database);
            builder.build(database);
            FIXTURES.put(key, database);
            return database;
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not build SQLite test fixture " + key, e);
        }
    }

    private static synchronized Path baseline()
    {
        if (baseline != null) {
            return baseline;
        }

        try {
            Path database = Files.createTempFile(
                    BASELINE_DIRECTORY, "bytequay-test-schema-", ".db");
            database.toFile().deleteOnExit();
            Flyway.configure()
                    .dataSource(JDBC_SQLITE_PREFIX + database + "?foreign_keys=ON", "", "")
                    .load()
                    .migrate();
            baseline = database;
            return database;
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not create migrated SQLite test baseline", e);
        }
    }

    private static void createParent(Path database)
            throws IOException
    {
        Path parent = database.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static boolean isEmpty(Path database)
    {
        try {
            return !Files.exists(database) || Files.size(database) == 0;
        }
        catch (IOException e) {
            return false;
        }
    }

    /**
     * Extracts the database file from a SQLite JDBC url, or null when the url names
     * something a baseline cannot be copied over (an in-memory or shared-cache database).
     */
    private static Path databaseFile(String url)
    {
        if (!url.startsWith(JDBC_SQLITE_PREFIX)) {
            return null;
        }
        String path = url.substring(JDBC_SQLITE_PREFIX.length());
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.isEmpty() || path.startsWith(":")) {
            return null;
        }
        return Path.of(path);
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
