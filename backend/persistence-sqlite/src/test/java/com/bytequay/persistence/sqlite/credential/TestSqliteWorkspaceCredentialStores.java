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
import jakarta.persistence.EntityManager;
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
    private ModelApiKeyJpaRepository apiKeyRows;

    @Autowired
    private CredentialCipher cipher;

    @Autowired
    private EntityManager entityManager;

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
        ModelApiKey key = new ModelApiKey("personal-deepseek", "DeepSeek", "sk-se••••••cret", createdAt);
        apiKeys.save(key, "sk-secret");

        assertThat(apiKeys.findAll()).containsExactly(key);
        assertThat(apiKeys.findById(key.id())).contains(key);
        assertThat(apiKeys.findSecret("personal-deepseek")).contains("sk-secret");
        assertThat(apiKeys.findSecretById(key.id())).contains("sk-secret");
        assertThatThrownBy(() -> apiKeys.save(
                new ModelApiKey("personal-deepseek", "DeepSeek", "sk-ot••••••ther", createdAt), "sk-other"))
                .isInstanceOf(IllegalArgumentException.class);

        apiKeys.delete("personal-deepseek");
        assertThat(apiKeys.findAll()).isEmpty();
        assertThat(apiKeys.findById(key.id())).isEmpty();
        assertThat(apiKeys.findSecretById(key.id())).isEmpty();

        ModelApiKey replacement = new ModelApiKey("personal-deepseek", "DeepSeek", "sk-ot••••••ther", createdAt);
        apiKeys.save(replacement, "sk-other");
        assertThat(replacement.id()).isNotEqualTo(key.id());
        assertThat(apiKeys.findSecretById(key.id())).isEmpty();
        assertThat(apiKeys.findSecretById(replacement.id())).contains("sk-other");
    }

    @Test
    void existingApiKeyReceivesPermanentId()
    {
        apiKeyRows.save(new ModelApiKeyEntity(null, "legacy-deepseek", "DeepSeek", cipher.encrypt("sk-legacy"), "sk-le••••••gacy",
                Instant.parse("2026-08-23T10:00:00Z")));

        apiKeys.assignMissingIds();
        String id = apiKeys.findAll().stream()
                .filter(key -> key.alias().equals("legacy-deepseek"))
                .findFirst().orElseThrow().id();
        apiKeyRows.flush();
        entityManager.clear();

        assertThat(apiKeyRows.findById("legacy-deepseek").orElseThrow().getKeyId()).isEqualTo(id);
        assertThat(apiKeys.findSecretById(id)).contains("sk-legacy");
        apiKeys.assignMissingIds();
        assertThat(apiKeys.findById(id)).isPresent();
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
