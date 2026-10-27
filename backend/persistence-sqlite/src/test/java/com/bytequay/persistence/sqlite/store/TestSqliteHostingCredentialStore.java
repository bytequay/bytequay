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
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        assertThat(firstCredential.generation()).isEqualTo(1);
        assertThat(firstCredential.accountId()).isEqualTo(first.id());
        assertThat(credentials.find(first.id()).orElseThrow().secret()).isEqualTo("alice-token");
        assertThat(credentials.find(second.id()).orElseThrow().secret()).isEqualTo("bob-token");
        assertThat(ciphertext(first.id())).isNotBlank().doesNotContain("alice-token");
    }

    @Test
    void advancesDurableGenerationOnReplacementDeletionAndReconnect()
    {
        HostingAccount account = account("1", "alice");
        assertThat(credentials.generation(account.id())).isZero();
        HostingCredential original = credentials.save(account.id(), "first-token");
        HostingCredential replaced = credentials.save(account.id(), "second-token");
        assertThat(replaced.generation()).isEqualTo(original.generation() + 1);
        assertThat(credentials.generation(account.id())).isEqualTo(replaced.generation());
        credentials.delete(account.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(credentials.find(account.id())).isEmpty();
        assertThat(credentials.generation(account.id())).isEqualTo(3);
        assertThat(ciphertext(account.id())).isNull();
        assertThat(identities.findAccount(account.id())).contains(account);
        HostingCredential reconnected = credentials.save(account.id(), "third-token");
        assertThat(reconnected.generation()).isEqualTo(4);
        assertThat(credentials.generation(account.id())).isEqualTo(reconnected.generation());
        assertThat(reconnected.secret()).isEqualTo("third-token");
    }

    @Test
    void deletingBeforeFirstSaveStillCreatesADurableGenerationBoundary()
    {
        HostingAccount account = account("1", "alice");
        credentials.delete(account.id());
        credentials.delete(account.id());

        assertThat(credentials.find(account.id())).isEmpty();
        assertThat(credentials.generation(account.id())).isEqualTo(2);
        assertThat(credentials.save(account.id(), "connected-token").generation()).isEqualTo(3);
    }

    @Test
    void rejectsUnownedCredentialsAndGenerationOverflow()
    {
        assertThat(credentials.generation(999)).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> credentials.generation(0));
        assertThatIllegalArgumentException().isThrownBy(() -> credentials.generation(-1));
        assertThatIllegalStateException().isThrownBy(() -> credentials.save(999, "unowned-token"));
        assertThatIllegalStateException().isThrownBy(() -> credentials.delete(999));
        assertThat(entityManager.createQuery("SELECT COUNT(credential) FROM HostingCredentialEntity credential", Long.class).getSingleResult()).isZero();

        HostingAccount account = account("1", "alice");
        credentials.save(account.id(), "original-token");
        entityManager.flush();
        entityManager.createQuery("UPDATE HostingCredentialEntity SET generation = :generation WHERE accountId = :accountId")
                .setParameter("generation", Long.MAX_VALUE)
                .setParameter("accountId", account.id())
                .executeUpdate();
        entityManager.clear();
        assertThatIllegalStateException().isThrownBy(() -> credentials.save(account.id(), "replacement-token"));
        assertThat(credentials.find(account.id()).orElseThrow().generation()).isEqualTo(Long.MAX_VALUE);
        assertThat(credentials.find(account.id()).orElseThrow().secret()).isEqualTo("original-token");
    }

    @Test
    void rejectsAStaleCredentialEntityAfterAnotherUpdate()
    {
        HostingAccount account = account("1", "alice");
        credentials.save(account.id(), "original-token");
        entityManager.flush();
        Object stale = entityManager.createQuery("FROM HostingCredentialEntity WHERE accountId = :accountId", Object.class)
                .setParameter("accountId", account.id())
                .getSingleResult();
        entityManager.detach(stale);

        credentials.save(account.id(), "replacement-token");
        entityManager.flush();

        assertThatThrownBy(() -> entityManager.merge(stale)).isInstanceOf(OptimisticLockException.class);
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
