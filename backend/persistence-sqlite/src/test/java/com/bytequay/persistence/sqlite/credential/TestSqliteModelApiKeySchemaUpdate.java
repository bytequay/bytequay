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
package com.bytequay.persistence.sqlite.credential;

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
class TestSqliteModelApiKeySchemaUpdate
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-model-key-schema-update-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteModelApiKeyStore keys;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createLegacyKeyTable();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Test
    void addsAndPersistsIdForAnExistingSavedKey()
    {
        var key = keys.findAll().getFirst();

        assertThat(key.alias()).isEqualTo("legacy-deepseek");
        assertThat(key.id()).isNotBlank();
        assertThat(keys.findById(key.id())).contains(key);
        assertThat(jdbc.queryForObject("SELECT key_id FROM model_api_keys WHERE alias = 'legacy-deepseek'", String.class)).isEqualTo(key.id());
        assertThat(jdbc.queryForList("SELECT name FROM pragma_table_info('model_api_keys')"))
                .extracting(row -> row.get("name"))
                .contains("key_id");
    }

    private static void createLegacyKeyTable()
    {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE model_api_keys (
                        alias varchar(255) NOT NULL,
                        provider varchar(255) NOT NULL,
                        ciphertext varchar(255) NOT NULL,
                        masked_hint varchar(255),
                        created_at timestamp NOT NULL,
                        PRIMARY KEY (alias))
                    """);
            statement.execute("""
                    INSERT INTO model_api_keys (alias, provider, ciphertext, masked_hint, created_at)
                    VALUES ('legacy-deepseek', 'DeepSeek', 'encrypted-placeholder', 'sk-le••••••gacy', '2026-08-23 10:00:00')
                    """);
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
