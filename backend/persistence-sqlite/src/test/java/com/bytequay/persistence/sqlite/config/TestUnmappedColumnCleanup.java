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
package com.bytequay.persistence.sqlite.config;

import com.bytequay.core.domain.RemoteSource;
import com.bytequay.persistence.sqlite.store.SqliteRemoteSourceStore;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestUnmappedColumnCleanup
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-unmapped-column-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteRemoteSourceStore remoteSources;

    @Autowired
    private EntityManager entities;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createTableWithRemovedFields();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "com.bytequay.persistence.sqlite.config.SqliteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void dropsUnmappedRequiredColumnSoInsertsSucceed()
    {
        remoteSources.save(new RemoteSource("ws-1", "origin", "https://example.com/owner/repo.git"));
        entities.flush();

        assertThat(jdbc.queryForList("SELECT name FROM pragma_table_info('workspace_remote_sources')", String.class))
                .contains("url", "removed_note")
                .doesNotContain("removed_flag");
    }

    private static void createTableWithRemovedFields()
    {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE workspace_remote_sources (id varchar(255) NOT NULL PRIMARY KEY, removed_flag boolean NOT NULL, removed_note varchar(255))");
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
