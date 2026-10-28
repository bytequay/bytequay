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

import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.persistence.sqlite.entity.HostingAccountEntity;
import com.bytequay.persistence.sqlite.entity.HostingRepositoryEntity;
import com.bytequay.persistence.sqlite.entity.WorkspaceEntity;
import com.bytequay.persistence.sqlite.repository.HostingAccountJpaRepository;
import com.bytequay.persistence.sqlite.repository.HostingInstanceJpaRepository;
import com.bytequay.persistence.sqlite.repository.HostingRepositoryJpaRepository;
import com.bytequay.persistence.sqlite.repository.WorkspaceJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqliteWorkspaceStore
        implements WorkspaceStore
{
    private final WorkspaceJpaRepository repository;
    private final SqliteWorkspacePurge purge;
    private final HostingAccountJpaRepository hostingAccounts;
    private final HostingInstanceJpaRepository hostingInstances;
    private final HostingRepositoryJpaRepository hostingRepositories;

    public SqliteWorkspaceStore(WorkspaceJpaRepository repository, SqliteWorkspacePurge purge, HostingAccountJpaRepository hostingAccounts,
            HostingInstanceJpaRepository hostingInstances, HostingRepositoryJpaRepository hostingRepositories)
    {
        this.repository = requireNonNull(repository, "repository is null");
        this.purge = requireNonNull(purge, "purge is null");
        this.hostingAccounts = requireNonNull(hostingAccounts, "hostingAccounts is null");
        this.hostingInstances = requireNonNull(hostingInstances, "hostingInstances is null");
        this.hostingRepositories = requireNonNull(hostingRepositories, "hostingRepositories is null");
    }

    @Override
    public List<Workspace> findAll()
    {
        return repository.findAllByWatchedTrueOrderByDisplayOrderAscCreatedAtAsc().stream()
                .map(WorkspaceEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public List<Workspace> findAllRetained()
    {
        return repository.findAllByOrderByDisplayOrderAscCreatedAtAsc().stream()
                .map(WorkspaceEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void setWatched(String workspaceId, boolean watched)
    {
        WorkspaceEntity entity = repository.findById(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        entity.setWatched(watched);
        repository.saveAndFlush(entity);
    }

    @Override
    public boolean isWatched(String workspaceId)
    {
        return repository.existsByIdAndWatchedTrue(workspaceId);
    }

    @Override
    public boolean hasActiveWorkflows(String workspaceId)
    {
        return purge.hasActiveWorkflows(workspaceId);
    }

    @Override
    public Optional<Workspace> findById(String workspaceId)
    {
        return repository.findById(workspaceId).map(WorkspaceEntity::toDomain);
    }

    @Override
    public Optional<Workspace> findBySelectedRepositoryId(long selectedRepositoryId)
    {
        return repository.findBySelectedRepositoryId(selectedRepositoryId)
                .map(WorkspaceEntity::toDomain);
    }

    @Override
    public Optional<WorkspaceHostingBinding> findHostingBinding(String workspaceId)
    {
        return repository.findById(checkText(workspaceId, "workspaceId")).flatMap(WorkspaceEntity::hostingBinding);
    }

    @Override
    @Transactional
    public WorkspaceHostingBinding bindHosting(String workspaceId, long accountId, long selectedRepositoryId, Optional<Long> upstreamRepositoryId)
    {
        checkText(workspaceId, "workspaceId");
        checkArgument(accountId > 0, "accountId must be positive");
        checkArgument(selectedRepositoryId > 0, "selectedRepositoryId must be positive");
        requireNonNull(upstreamRepositoryId, "upstreamRepositoryId is null");
        upstreamRepositoryId.ifPresent(id -> checkArgument(id > 0, "upstreamRepositoryId must be positive"));
        WorkspaceEntity entity = repository.findById(workspaceId).orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        Workspace workspace = entity.toDomain();
        checkArgument(upstreamRepositoryId.isPresent() == workspace.upstreamRepositoryId().isPresent(), "Hosting upstream differs from workspace routing");
        HostingAccountEntity accountEntity = hostingAccounts.findById(accountId).orElseThrow(() -> new IllegalArgumentException("Hosting account not found"));
        HostingAccount account = accountEntity.toDomain();
        HostingInstance instance = hostingInstances.findById(account.instanceId()).orElseThrow().toDomain();
        checkArgument(instance.provider() == HostingProvider.GITHUB && instance.webBaseUrl().equals(URI.create("https://github.com"))
                && instance.apiBaseUrl().equals(URI.create("https://api.github.com")), "Existing workspaces require GitHub.com identities");
        HostingRepositoryEntity selected = bindingRepository(selectedRepositoryId, account.instanceId(), workspace.selectedRepositoryId());
        Optional<HostingRepositoryEntity> upstream = upstreamRepositoryId.map(id -> bindingRepository(id, account.instanceId(), workspace.upstreamRepositoryId().orElseThrow()));
        entity.bindHosting(accountEntity, selected, upstream);
        return repository.saveAndFlush(entity).hostingBinding().orElseThrow();
    }

    private HostingRepositoryEntity bindingRepository(long repositoryId, long instanceId, long remoteId)
    {
        HostingRepositoryEntity entity = hostingRepositories.findById(repositoryId).orElseThrow(() -> new IllegalArgumentException("Hosting repository not found"));
        HostingRepository repository = entity.toDomain();
        checkArgument(repository.instanceId() == instanceId, "Hosting repository belongs to another instance");
        checkArgument(repository.remoteId().equals(Long.toString(remoteId)), "Hosting repository differs from workspace repository identity");
        return entity;
    }

    @Override
    @Transactional
    public Workspace createOrGet(Workspace workspace)
    {
        requireNonNull(workspace, "workspace is null");
        Optional<Workspace> existing = findBySelectedRepositoryId(workspace.selectedRepositoryId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        checkArgument(!repository.existsById(workspace.id()), "Workspace already exists: %s", workspace.id());
        return repository.saveAndFlush(WorkspaceEntity.fromDomain(workspace)).toDomain();
    }

    @Override
    @Transactional
    public Workspace save(Workspace workspace)
    {
        WorkspaceEntity entity = repository.findById(workspace.id())
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspace.id()));
        entity.updateFrom(workspace);
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional
    public void delete(String workspaceId)
    {
        findById(workspaceId).ifPresent(purge::delete);
    }
}
