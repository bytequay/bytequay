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

import com.bytequay.core.domain.ModelApiKey;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteWorkspaceCredentialStores
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-credential-store-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqliteWorkspaceCredentialStore workspaceCredentials;

    @Autowired
    private SqliteModelApiKeyStore apiKeys;

    @Autowired
    private SqliteCredentialStore credentials;

    @Autowired
    private WorkspaceCredentialJpaRepository workspaceCredentialRows;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Test
    void workspaceTokenIsEncryptedAtRestAndRoundTrips()
    {
        workspaceCredentials.save("ws-1", "github_pat_secretvalue", "jack-work");

        assertThat(workspaceCredentials.getSecret("ws-1")).contains("github_pat_secretvalue");
        assertThat(workspaceCredentials.getLogin("ws-1")).contains("jack-work");
        assertThat(workspaceCredentialRows.findById("ws-1").orElseThrow().getCiphertext())
                .doesNotContain("secretvalue");

        workspaceCredentials.save("ws-1", "github_pat_rotated", "jack-work-2");
        assertThat(workspaceCredentials.getSecret("ws-1")).contains("github_pat_rotated");

        workspaceCredentials.delete("ws-1");
        assertThat(workspaceCredentials.getSecret("ws-1")).isEmpty();
    }

    @Test
    void apiKeysListWithoutSecretsAndRejectDuplicates()
    {
        Instant createdAt = Instant.parse("2026-08-23T10:00:00Z");
        apiKeys.save(new ModelApiKey("personal-deepseek", "DeepSeek", createdAt), "sk-secret");

        assertThat(apiKeys.findAll())
                .containsExactly(new ModelApiKey("personal-deepseek", "DeepSeek", createdAt));
        assertThat(apiKeys.findSecret("personal-deepseek")).contains("sk-secret");
        assertThatThrownBy(() -> apiKeys.save(
                new ModelApiKey("personal-deepseek", "DeepSeek", createdAt), "sk-other"))
                .isInstanceOf(IllegalArgumentException.class);

        apiKeys.delete("personal-deepseek");
        assertThat(apiKeys.findAll()).isEmpty();
    }

    @Test
    void accountTokenDeleteClearsTheStoredCredential()
    {
        credentials.save("ghp_token", null);
        assertThat(credentials.exists()).isTrue();

        credentials.delete();

        assertThat(credentials.exists()).isFalse();
        assertThat(credentials.getSecret()).isEmpty();
    }
}
