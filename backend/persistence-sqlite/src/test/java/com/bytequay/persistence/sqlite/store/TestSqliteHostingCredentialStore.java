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

import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingCredential;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.persistence.sqlite.credential.SqliteHostingCredentialStore;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class TestSqliteHostingCredentialStore
        extends AbstractTestTransactionalSqliteStore
{
    @Autowired
    private SqliteHostingIdentityStore identities;

    @Autowired
    private SqliteHostingCredentialStore credentials;

    @Autowired
    private EntityManager entityManager;

    @Test
    void encryptsAndIsolatesAccountSecrets()
    {
        HostingAccount first = account("1", "alice");
        HostingAccount second = account("2", "bob");
        HostingCredential firstCredential = credentials.save(first.id(), "alice-token");
        credentials.save(second.id(), "bob-token");
        entityManager.flush();
        entityManager.clear();

        assertThat(firstCredential.accountId()).isEqualTo(first.id());
        assertThat(credentials.find(first.id()).orElseThrow().secret()).isEqualTo("alice-token");
        assertThat(credentials.find(second.id()).orElseThrow().secret()).isEqualTo("bob-token");
        assertThat(ciphertext(first.id())).isNotBlank().doesNotContain("alice-token");
    }

    @Test
    void replacesAndDeletesSecretsWithoutRemovingAccountIdentity()
    {
        HostingAccount account = account("1", "alice");
        credentials.save(account.id(), "first-token");
        credentials.save(account.id(), "second-token");
        entityManager.flush();
        entityManager.clear();
        assertThat(credentials.find(account.id()).orElseThrow().secret()).isEqualTo("second-token");

        credentials.delete(account.id());
        entityManager.flush();
        entityManager.clear();
        assertThat(credentials.find(account.id())).isEmpty();
        assertThat(identities.findAccount(account.id())).contains(account);
        assertThat(entityManager.createQuery("SELECT COUNT(credential) FROM HostingCredentialEntity credential", Long.class).getSingleResult()).isZero();
        assertThat(credentials.save(account.id(), "third-token").secret()).isEqualTo("third-token");
    }

    @Test
    void rejectsUnownedAndInvalidCredentials()
    {
        assertThatIllegalArgumentException().isThrownBy(() -> credentials.find(0));
        assertThatIllegalArgumentException().isThrownBy(() -> credentials.save(0, "token"));
        assertThatIllegalArgumentException().isThrownBy(() -> credentials.delete(-1));
        assertThatIllegalStateException().isThrownBy(() -> credentials.save(999, "unowned-token"));
        assertThat(entityManager.createQuery("SELECT COUNT(credential) FROM HostingCredentialEntity credential", Long.class).getSingleResult()).isZero();
    }

    private String ciphertext(long accountId)
    {
        return entityManager.createQuery("SELECT ciphertext FROM HostingCredentialEntity WHERE accountId = :accountId", String.class)
                .setParameter("accountId", accountId)
                .getSingleResult();
    }

    private HostingAccount account(String remoteId, String login)
    {
        var instance = identities.upsertInstance(new HostingServer(HostingProvider.GITLAB, URI.create("https://gitlab.com"), URI.create("https://gitlab.com/api/v4")));
        return identities.upsertAccount(instance.id(), new RemoteHostingAccount(remoteId, login));
    }
}
