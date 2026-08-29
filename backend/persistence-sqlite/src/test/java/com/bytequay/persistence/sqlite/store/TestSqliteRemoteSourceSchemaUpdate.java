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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
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
class TestSqliteRemoteSourceSchemaUpdate
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-remote-source-schema-update-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteRemoteSourceStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createLegacyRemoteSourceTable();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void addsNullableProviderIdentityColumnsToPopulatedLegacyTable()
    {
        var legacy = store.findByWorkspace("ws-1").getFirst();

        assertThat(legacy.repositoryId()).isEmpty();
        assertThat(legacy.repository()).isEmpty();
        assertThat(jdbc.queryForList("SELECT name FROM pragma_table_info('workspace_remote_sources')"))
                .extracting(row -> row.get("name"))
                .contains("provider_repository_id", "provider_owner", "provider_repo");
        assertThat(jdbc.queryForList(
                "SELECT name, \"notnull\" FROM pragma_table_info('workspace_remote_sources') "
                        + "WHERE name LIKE 'provider_%'"))
                .allSatisfy(row -> assertThat(row.get("notnull")).isEqualTo(0));

        var identified = legacy.withRepositoryIdentity(166_515_022L, RepoRef.of("trinodb", "trino"));
        store.save(identified);

        assertThat(store.findByWorkspace("ws-1")).containsExactly(identified);
    }

    private static void createLegacyRemoteSourceTable()
    {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE workspace_remote_sources (
                        id varchar(255) NOT NULL,
                        workspace_id varchar(255) NOT NULL,
                        name varchar(255) NOT NULL,
                        url varchar(255) NOT NULL,
                        PRIMARY KEY (id))
                    """);
            statement.execute("""
                    INSERT INTO workspace_remote_sources (id, workspace_id, name, url)
                    VALUES ('ws-1:oss', 'ws-1', 'oss', 'https://github.com/trinodb/trino.git')
                    """);
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
