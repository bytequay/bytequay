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
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.persistence.sqlite.repository.HostingAccountJpaRepository;
import com.bytequay.persistence.sqlite.repository.HostingRepositoryJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TestSqliteHostingIdentityStore
        extends AbstractTestTransactionalSqliteStore
{
    @Autowired
    private SqliteHostingIdentityStore store;

    @Autowired
    private HostingRepositoryJpaRepository repositories;

    @Autowired
    private HostingAccountJpaRepository accounts;

    @Autowired
    private EntityManager entityManager;

    @Test
    void selectsTheSameInstanceAndRejectsSilentEndpointChanges()
    {
        HostingInstance instance = gitlab("https://git.example.com:8443/Platform");
        assertThat(store.upsertInstance(new HostingServer(HostingProvider.GITLAB,
                URI.create("HTTPS://GIT.EXAMPLE.COM:8443/Platform/"), URI.create("https://git.example.com:8443/Platform/api/v4/")))).isEqualTo(instance);
        assertThat(store.listInstances()).containsExactly(instance);
        assertThat(store.findInstance(instance.id())).contains(instance);
        assertThatIllegalArgumentException().isThrownBy(() -> store.upsertInstance(new HostingServer(HostingProvider.GITLAB,
                URI.create("https://git.example.com:8443/Platform"), URI.create("https://other.example.com/api/v4"))));
        assertThat(store.findInstance(instance.id())).contains(instance);
    }

    @Test
    void defaultPortsDoNotCreateSeparateInstancesOrConflictingEndpoints()
    {
        HostingInstance instance = gitlab("https://gitlab.com");
        assertThat(store.upsertInstance(new HostingServer(HostingProvider.GITLAB, URI.create("https://gitlab.com:443/"), URI.create("https://gitlab.com:443/api/v4/"))))
                .isEqualTo(instance);
        assertThat(store.listInstances()).containsExactly(instance);
        HostingInstance loopback = gitlab("http://localhost");
        assertThat(store.upsertInstance(new HostingServer(HostingProvider.GITLAB, URI.create("http://localhost:80"), URI.create("http://localhost:80/api/v4"))))
                .isEqualTo(loopback);
    }

    @Test
    void separatesRemoteIdsAcrossHostsAndKeepsIdsAcrossRenames()
    {
        HostingInstance first = gitlab("https://git.example.com");
        HostingInstance second = gitlab("https://gitlab.com");
        HostingAccount account = store.upsertAccount(first.id(), new RemoteHostingAccount("17", "alice"));
        HostingAccount otherAccount = store.upsertAccount(second.id(), new RemoteHostingAccount("17", "alice"));
        HostingRepository repository = repository(first, "99", "Group/Subgroup/Project");
        HostingRepository otherRepository = repository(second, "99", "Group/Subgroup/Project");

        HostingAccount renamedAccount = store.upsertAccount(first.id(), new RemoteHostingAccount("17", "alice-renamed"));
        HostingRepository renamedRepository = repository(first, "99", "NewGroup/NewProject");
        entityManager.flush();
        entityManager.clear();
        assertThat(account.id()).isNotEqualTo(otherAccount.id());
        assertThat(repository.id()).isNotEqualTo(otherRepository.id());
        assertThat(renamedAccount.id()).isEqualTo(account.id());
        assertThat(renamedRepository.id()).isEqualTo(repository.id());
        assertThat(renamedRepository.fullPath()).isEqualTo("NewGroup/NewProject");
        assertThat(renamedRepository.webUrl()).isEqualTo(URI.create("https://git.example.com/NewGroup/NewProject"));
        assertThat(store.findAccount(account.id())).contains(renamedAccount);
        assertThat(store.listAccounts(first.id())).containsExactly(renamedAccount);
        assertThat(store.findRepository(repository.id())).contains(renamedRepository);
        assertThat(store.findRepository(otherRepository.id())).contains(otherRepository);
        assertThat(repositories.count()).isEqualTo(2);
    }

    @Test
    void emptyRepositoryHasNoInventedDefaultBranch()
    {
        HostingInstance instance = gitlab("https://git.example.com/gitlab");
        HostingRepository repository = repository(instance, "100", "Group/Subgroup/Empty");
        assertThat(repository.defaultBranch()).isEmpty();
        assertThat(repository.httpCloneUrl()).isEqualTo(URI.create("https://git.example.com/gitlab/Group/Subgroup/Empty.git"));
    }

    @Test
    void rejectsUnknownInstancesAndCoordinatesOutsideTheirBase()
    {
        assertThatIllegalArgumentException().isThrownBy(() -> store.upsertAccount(999, new RemoteHostingAccount("17", "alice")));
        assertThatIllegalArgumentException().isThrownBy(() -> store.findInstance(0));
        assertThatIllegalArgumentException().isThrownBy(() -> store.upsertRepository(999, new RemoteHostingRepository("100", "Group/Project",
                URI.create("https://git.example.com/Group/Project"), URI.create("https://git.example.com/Group/Project.git"), Optional.empty(), Optional.empty())));
        HostingInstance instance = gitlab("https://git.example.com/gitlab");
        assertThatIllegalArgumentException().isThrownBy(() -> store.upsertRepository(instance.id(), new RemoteHostingRepository("100", "Group/Project",
                URI.create("https://git.example.com/gitlab/Group/Project"), URI.create("https://other.example.com/Group/Project.git"), Optional.empty(), Optional.empty())));
        assertThatIllegalArgumentException().isThrownBy(() -> store.upsertRepository(instance.id(), new RemoteHostingRepository("100", "Group/Project",
                URI.create("https://git.example.com/gitlab-other/Group/Project"), URI.create("https://git.example.com/gitlab/Group/Project.git"),
                Optional.empty(), Optional.empty())));
        assertThat(repositories.count()).isZero();
        assertThat(accounts.count()).isZero();
    }

    private HostingInstance gitlab(String base)
    {
        return store.upsertInstance(new HostingServer(HostingProvider.GITLAB, URI.create(base), URI.create(base + "/api/v4")));
    }

    private HostingRepository repository(HostingInstance instance, String remoteId, String path)
    {
        return store.upsertRepository(instance.id(), new RemoteHostingRepository(remoteId, path, URI.create(instance.webBaseUrl() + "/" + path),
                URI.create(instance.webBaseUrl() + "/" + path + ".git"), Optional.of("git@git.example.com:" + path + ".git"), Optional.empty()));
    }
}
