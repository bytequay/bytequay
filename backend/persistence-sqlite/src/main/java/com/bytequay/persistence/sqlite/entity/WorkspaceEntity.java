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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(
        name = "watched_repo_workspaces",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_watched_repo_workspaces_selected_repo_id",
                columnNames = "selected_repository_id"))
public class WorkspaceEntity
{
    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "selected_repository_id", nullable = false, unique = true)
    private long selectedRepositoryId;

    @Column(nullable = false)
    private String selectedOwner;

    @Column(nullable = false)
    private String selectedRepo;

    @Column(name = "upstream_repository_id")
    private Long upstreamRepositoryId;

    private String upstreamOwner;

    private String upstreamRepo;

    @Column(name = "monitored_repository_id", nullable = false)
    private long monitoredRepositoryId;

    @Column(nullable = false)
    private String monitoredOwner;

    @Column(nullable = false)
    private String monitoredRepo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Topology topology;

    @Column(nullable = false)
    private String defaultBranch;

    @Column(nullable = false)
    private String originCloneUrl;

    private String upstreamDefaultBranch;

    private String upstreamCloneUrl;

    @Column(nullable = false)
    private String localRepositoryPath;

    private Instant lastCodeSyncAt;

    private Instant lastMetadataSyncAt;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public WorkspaceEntity() {}

    public static WorkspaceEntity fromDomain(Workspace workspace)
    {
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.updateFrom(workspace);
        return entity;
    }

    public void updateFrom(Workspace workspace)
    {
        id = workspace.id();
        name = workspace.name();
        selectedRepositoryId = workspace.selectedRepositoryId();
        selectedOwner = workspace.selectedRepository().owner();
        selectedRepo = workspace.selectedRepository().repo();
        upstreamRepositoryId = workspace.upstreamRepositoryId().orElse(null);
        upstreamOwner = workspace.upstreamRepository().map(RepoRef::owner).orElse(null);
        upstreamRepo = workspace.upstreamRepository().map(RepoRef::repo).orElse(null);
        monitoredRepositoryId = workspace.monitoredRepositoryId();
        monitoredOwner = workspace.monitoredRepository().owner();
        monitoredRepo = workspace.monitoredRepository().repo();
        topology = workspace.topology();
        defaultBranch = workspace.defaultBranch();
        originCloneUrl = workspace.originCloneUrl();
        upstreamDefaultBranch = workspace.upstreamDefaultBranch().orElse(null);
        upstreamCloneUrl = workspace.upstreamCloneUrl().orElse(null);
        localRepositoryPath = workspace.localRepositoryPath();
        lastCodeSyncAt = workspace.lastCodeSyncAt().orElse(null);
        lastMetadataSyncAt = workspace.lastMetadataSyncAt().orElse(null);
        displayOrder = workspace.displayOrder();
        createdAt = workspace.createdAt();
        updatedAt = workspace.updatedAt();
    }

    public Workspace toDomain()
    {
        RepoRef selected = RepoRef.of(selectedOwner, selectedRepo);
        Optional<RepoRef> persistedUpstream = Optional.ofNullable(upstreamOwner)
                .flatMap(owner -> Optional.ofNullable(upstreamRepo)
                        .map(repo -> RepoRef.of(owner, repo)));
        Optional<Long> upstreamId = Optional.ofNullable(upstreamRepositoryId);
        Optional<RepoRef> upstream = upstreamId.isPresent() ? persistedUpstream : Optional.empty();
        return new Workspace(
                id,
                name,
                selectedRepositoryId,
                selected,
                upstreamId,
                upstream,
                upstreamId.isPresent() ? monitoredRepositoryId : selectedRepositoryId,
                upstreamId.isPresent() ? RepoRef.of(monitoredOwner, monitoredRepo) : selected,
                topology,
                defaultBranch,
                originCloneUrl,
                upstreamId.isPresent() ? Optional.ofNullable(upstreamDefaultBranch) : Optional.empty(),
                upstreamId.isPresent() ? Optional.ofNullable(upstreamCloneUrl) : Optional.empty(),
                localRepositoryPath,
                Optional.ofNullable(lastCodeSyncAt),
                Optional.ofNullable(lastMetadataSyncAt),
                displayOrder,
                createdAt,
                updatedAt);
    }

    public String getId() { return id; }

    public long getSelectedRepositoryId() { return selectedRepositoryId; }

    public int getDisplayOrder() { return displayOrder; }
}
