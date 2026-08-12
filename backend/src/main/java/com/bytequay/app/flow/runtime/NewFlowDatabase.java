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
package com.bytequay.app.flow.runtime;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.function.IntConsumer;

import static java.util.Objects.requireNonNull;

/**
 * Installs and validates the complete new-flow SQLite baseline.
 */
public final class NewFlowDatabase
{
    static final int SCHEMA_VERSION = 1;
    private static final List<String> RESOURCES = List.of("db/new-flow/runtime.sql", "db/new-flow/ci-autofix.sql", "db/new-flow/user-gates.sql", "db/new-flow/github-effects.sql", "db/new-flow/upstream-sync.sql");
    private static final String MARKER = "flow_schema_baseline";

    private final DataSource dataSource;
    private final Clock clock;
    private final IntConsumer afterResource;

    public NewFlowDatabase(DataSource dataSource, Clock clock)
    {
        this(dataSource, clock, ignored -> {});
    }

    NewFlowDatabase(DataSource dataSource, Clock clock, IntConsumer afterResource)
    {
        this.dataSource = requireNonNull(dataSource, "dataSource is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.afterResource = requireNonNull(afterResource, "afterResource is null");
    }

    /**
     * Serializes concurrent starters and either installs or validates.
     */
    public void bootstrap()
    {
        String bundleDigest = bundleDigest();
        try (Connection connection = dataSource.getConnection()) {
            requireAutoCommit(connection);
            enableForeignKeys(connection);
            execute(connection, "BEGIN IMMEDIATE");
            try {
                if (markerExists(connection)) {
                    validate(connection, bundleDigest);
                }
                else {
                    requireEmpty(connection);
                    install(connection, bundleDigest);
                }
                validateDatabase(connection);
                execute(connection, "COMMIT");
            }
            catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
        catch (SQLException failure) {
            throw new IllegalStateException("new-flow database bootstrap failed", failure);
        }
    }

    static String bundleDigest()
    {
        MessageDigest digest = sha256();
        frame(digest, "new-flow-schema-bundle:v1");
        frame(digest, Integer.toString(RESOURCES.size()));
        for (String path : RESOURCES) {
            byte[] bytes = read(path);
            frame(digest, path);
            frame(digest, Integer.toString(bytes.length));
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String catalogDigest(Connection connection)
            throws SQLException
    {
        MessageDigest digest = sha256();
        frame(digest, "new-flow-sqlite-schema:v1");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT type, name, tbl_name, sql
                FROM sqlite_schema
                WHERE name NOT LIKE 'sqlite_%'
                ORDER BY type, name, tbl_name
                """); ResultSet result = statement.executeQuery()) {
            int count = 0;
            while (result.next()) {
                count++;
                frame(digest, result.getString("type"));
                frame(digest, result.getString("name"));
                frame(digest, result.getString("tbl_name"));
                frame(digest, result.getString("sql"));
            }
            frame(digest, "count");
            frame(digest, Integer.toString(count));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void install(Connection connection, String bundleDigest)
            throws SQLException
    {
        for (int index = 0; index < RESOURCES.size(); index++) {
            String path = RESOURCES.get(index);
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(path), StandardCharsets.UTF_8));
            afterResource.accept(index + 1);
        }
        execute(connection, """
                CREATE TABLE flow_schema_baseline (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    schema_version INTEGER NOT NULL,
                    bundle_digest TEXT NOT NULL,
                    catalog_digest TEXT NOT NULL,
                    installed_at INTEGER NOT NULL
                )
                """);
        String catalogDigest = catalogDigest(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO flow_schema_baseline (
                    singleton, schema_version, bundle_digest,
                    catalog_digest, installed_at
                ) VALUES (1, ?, ?, ?, ?)
                """)) {
            statement.setInt(1, SCHEMA_VERSION);
            statement.setString(2, bundleDigest);
            statement.setString(3, catalogDigest);
            statement.setLong(4, clock.instant().toEpochMilli());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("new-flow schema marker was not stored");
            }
        }
    }

    private static void validate(Connection connection, String bundleDigest)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT schema_version, bundle_digest, catalog_digest
                FROM flow_schema_baseline
                WHERE singleton = 1
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw drift("new-flow schema marker is missing");
            }
            int version = result.getInt("schema_version");
            String storedBundle = result.getString("bundle_digest");
            String storedCatalog = result.getString("catalog_digest");
            if (result.next()) {
                throw drift("new-flow schema marker is not unique");
            }
            if (version != SCHEMA_VERSION || !bundleDigest.equals(storedBundle) || !catalogDigest(connection).equals(storedCatalog)) {
                // Deliberately fatal, and deliberately not a migration: this
                // schema is one digest-checked baseline, so drift is either a
                // changed bundle or a tampered/corrupted database, and the two
                // are indistinguishable from here. Naming the remedy is the most
                // that can safely be automated — doing it would turn the tamper
                // detector into a silent rebuild.
                throw drift("new-flow schema does not match its baseline. If you changed db/new-flow/*.sql, delete the"
                        + " new-flow database file and let it reinstall; it carries no history worth migrating until a Task exists.");
            }
        }
    }

    private static void requireEmpty(Connection connection)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM sqlite_schema
                WHERE name NOT LIKE 'sqlite_%'
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 0) {
                throw drift("partial new-flow schema exists without its marker");
            }
        }
    }

    private static boolean markerExists(Connection connection)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM sqlite_schema
                WHERE type = 'table' AND name = ?
                """)) {
            statement.setString(1, MARKER);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private static void requireAutoCommit(Connection connection)
            throws SQLException
    {
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("new-flow bootstrap requires an unowned connection");
        }
    }

    private static void enableForeignKeys(Connection connection)
            throws SQLException
    {
        execute(connection, "PRAGMA foreign_keys = ON");
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            if (!result.next() || result.getInt(1) != 1 || result.next()) {
                throw new IllegalStateException("new-flow foreign keys are not enabled");
            }
        }
    }

    private static void validateDatabase(Connection connection)
            throws SQLException
    {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw drift("new-flow database violates a foreign key");
            }
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equals(result.getString(1)) || result.next()) {
                throw drift("new-flow database integrity check failed");
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

    private static void rollback(Connection connection, Throwable original)
    {
        try {
            execute(connection, "ROLLBACK");
        }
        catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static byte[] read(String path)
    {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        }
        catch (IOException failure) {
            throw new IllegalStateException("cannot read new-flow schema resource " + path, failure);
        }
    }

    private static MessageDigest sha256()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void frame(MessageDigest digest, String value)
    {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private static IllegalStateException drift(String message)
    {
        return new IllegalStateException(message);
    }
}
