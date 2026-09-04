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
package com.bytequay.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TestSqliteLargeSchemaUpdate
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-large-schema-update-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createWideTable();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        // src/test/resources/application.properties shadows the production resource.
        registry.add(
                "spring.jpa.properties.hibernate.hbm2ddl.jdbc_metadata_extraction_strategy",
                () -> "individually");
    }

    @Test
    void updatesSchemaBeyondSqliteCompoundSelectLimit()
    {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name = 'pull_requests'",
                Integer.class)).isOne();
    }

    private static void createWideTable()
    {
        // A grouped sqlite-jdbc metadata read also includes sqlite_schema's five columns,
        // so 496 application columns reproduce SQLite's 500-arm compound SELECT ceiling.
        String columns = IntStream.range(0, 496)
                .mapToObj(index -> "column_" + index + " TEXT")
                .collect(joining(", "));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE wide_metadata (" + columns + ")");
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
