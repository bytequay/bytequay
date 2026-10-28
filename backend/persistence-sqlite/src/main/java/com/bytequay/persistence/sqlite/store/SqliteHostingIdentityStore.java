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
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.HostingUrls;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.store.HostingIdentityStore;
import com.bytequay.persistence.sqlite.entity.HostingAccountEntity;
import com.bytequay.persistence.sqlite.entity.HostingInstanceEntity;
import com.bytequay.persistence.sqlite.entity.HostingRepositoryEntity;
import com.bytequay.persistence.sqlite.repository.HostingAccountJpaRepository;
import com.bytequay.persistence.sqlite.repository.HostingInstanceJpaRepository;
import com.bytequay.persistence.sqlite.repository.HostingRepositoryJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqliteHostingIdentityStore
        implements HostingIdentityStore
{
    private final HostingInstanceJpaRepository instances;
    private final HostingAccountJpaRepository accounts;
    private final HostingRepositoryJpaRepository repositories;

    public SqliteHostingIdentityStore(HostingInstanceJpaRepository instances, HostingAccountJpaRepository accounts, HostingRepositoryJpaRepository repositories)
    {
        this.instances = requireNonNull(instances, "instances is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
    }

    @Override
    @Transactional
    public HostingInstance upsertInstance(HostingServer server)
    {
        requireNonNull(server, "server is null");
        HostingInstanceEntity entity = instances.findByProviderAndWebBaseUrl(server.provider(), server.webBaseUrl().toASCIIString())
                .orElseGet(() -> instances.save(new HostingInstanceEntity(server.provider(), server.webBaseUrl(), server.apiBaseUrl())));
        HostingInstance instance = entity.toDomain();
        checkArgument(instance.apiBaseUrl().equals(server.apiBaseUrl()), "An existing instance cannot change its API endpoint");
        return instance;
    }

    @Override
    public Optional<HostingInstance> findInstance(long id)
    {
        checkArgument(id > 0, "id must be positive");
        return instances.findById(id).map(HostingInstanceEntity::toDomain);
    }

    @Override
    public List<HostingInstance> listInstances()
    {
        return instances.findAllByOrderById().stream().map(HostingInstanceEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public HostingAccount upsertAccount(long instanceId, RemoteHostingAccount remote)
    {
        checkArgument(instanceId > 0, "instanceId must be positive");
        requireNonNull(remote, "remote is null");
        HostingInstanceEntity instance = instances.findById(instanceId).orElseThrow(() -> new IllegalArgumentException("Hosting instance does not exist"));
        HostingAccountEntity entity = accounts.findByInstanceIdAndRemoteUserId(instanceId, remote.remoteId())
                .orElseGet(() -> new HostingAccountEntity(instance, remote.remoteId(), remote.login()));
        entity.updateLogin(remote.login());
        return accounts.save(entity).toDomain();
    }

    @Override
    public Optional<HostingAccount> findAccount(long id)
    {
        checkArgument(id > 0, "id must be positive");
        return accounts.findById(id).map(HostingAccountEntity::toDomain);
    }

    @Override
    public List<HostingAccount> listAccounts(long instanceId)
    {
        checkArgument(instanceId > 0, "instanceId must be positive");
        return accounts.findAllByInstanceIdOrderById(instanceId).stream().map(HostingAccountEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public HostingRepository upsertRepository(long instanceId, RemoteHostingRepository remote)
    {
        checkArgument(instanceId > 0, "instanceId must be positive");
        requireNonNull(remote, "remote is null");
        HostingInstanceEntity instance = instances.findById(instanceId).orElseThrow(() -> new IllegalArgumentException("Hosting instance does not exist"));
        HostingUrls.checkRepositoryPath(remote.fullPath());
        URI webBase = instance.toDomain().webBaseUrl();
        URI checkedWebUrl = HostingUrls.requireWithinBase(remote.webUrl(), webBase);
        URI checkedCloneUrl = HostingUrls.requireWithinBase(remote.httpCloneUrl(), webBase);
        checkOptionalText(remote.sshCloneUrl(), "sshCloneUrl").ifPresent(HostingUrls::checkSshCloneUrl);
        checkOptionalText(remote.defaultBranch(), "defaultBranch");
        HostingRepositoryEntity entity = repositories.findByInstanceIdAndRemoteId(instanceId, remote.remoteId())
                .orElseGet(() -> new HostingRepositoryEntity(instance, remote.remoteId()));
        entity.update(remote.fullPath(), checkedWebUrl, checkedCloneUrl, remote.sshCloneUrl(), remote.defaultBranch());
        return repositories.save(entity).toDomain();
    }

    @Override
    public Optional<HostingRepository> findRepository(long id)
    {
        checkArgument(id > 0, "id must be positive");
        return repositories.findById(id).map(HostingRepositoryEntity::toDomain);
    }

    @Override
    public List<HostingRepository> findRepositories(HostingServer server, String fullPath)
    {
        requireNonNull(server, "server is null");
        HostingUrls.checkRepositoryPath(fullPath);
        return instances.findByProviderAndWebBaseUrl(server.provider(), server.webBaseUrl().toASCIIString())
                .filter(instance -> instance.toDomain().apiBaseUrl().equals(server.apiBaseUrl()))
                .stream().flatMap(instance -> (server.provider() == GITHUB
                        ? repositories.findByInstanceIdAndFullPathIgnoreCase(instance.getId(), fullPath)
                        : repositories.findByInstanceIdAndFullPath(instance.getId(), fullPath)).stream())
                .map(HostingRepositoryEntity::toDomain).collect(toImmutableList());
    }
}
