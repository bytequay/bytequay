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
class TestSqliteRepositoryMetadataSchemaUpdate
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-repository-metadata-schema-update-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteRepositoryMetadataStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createLegacyRepositoryMetadataTable();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void addsMetadataColumnsToPopulatedLegacyTable()
    {
        var snapshot = store.find(100).orElseThrow();

        assertThat(snapshot.languages()).isEmpty();
        assertThat(snapshot.stargazersCount()).isZero();
        assertThat(snapshot.pickerUsers()).isEmpty();
        assertThat(snapshot.pickerUsersSyncedAt()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT languages FROM repository_metadata WHERE repository_id = 100", String.class)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT picker_users FROM repository_metadata WHERE repository_id = 100", String.class)).isEqualTo("[]");
        assertThat(jdbc.queryForObject("SELECT stargazers_count FROM repository_metadata WHERE repository_id = 100", Integer.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT \"notnull\" FROM pragma_table_info('repository_metadata') WHERE name = 'languages'"))
                .containsEntry("notnull", 1);
        assertThat(jdbc.queryForMap("SELECT \"notnull\" FROM pragma_table_info('repository_metadata') WHERE name = 'stargazers_count'"))
                .containsEntry("notnull", 1);
        assertThat(jdbc.queryForMap("SELECT \"notnull\" FROM pragma_table_info('repository_metadata') WHERE name = 'picker_users'"))
                .containsEntry("notnull", 1);
    }

    private static void createLegacyRepositoryMetadataTable()
    {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE repository_metadata (
                        repository_id bigint NOT NULL,
                        contributors varchar(255) NOT NULL,
                        contributors_synced_at varchar(255),
                        labels varchar(255) NOT NULL,
                        labels_topics_synced_at varchar(255),
                        owner varchar(255) NOT NULL,
                        repo varchar(255) NOT NULL,
                        topics varchar(255) NOT NULL,
                        PRIMARY KEY (repository_id))
                    """);
            statement.execute("""
                    INSERT INTO repository_metadata (
                        repository_id, contributors, labels, owner, repo, topics)
                    VALUES (100, '[]', '[]', 'owner', 'project', '[]')
                    """);
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
