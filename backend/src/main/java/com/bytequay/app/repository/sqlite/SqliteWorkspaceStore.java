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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.WorkspaceStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteWorkspaceStore
        implements WorkspaceStore
{
    private final WorkspaceJpaRepository workspaces;
    private final WorkspaceRepoJpaRepository repos;

    SqliteWorkspaceStore(WorkspaceJpaRepository workspaces, WorkspaceRepoJpaRepository repos)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.repos = requireNonNull(repos, "repos is null");
    }

    @Override
    @Transactional
    public void saveWorkspace(Workspace workspace)
    {
        WorkspaceEntity entity = workspaces.findById(workspace.id()).orElseGet(WorkspaceEntity::new);
        entity.setId(workspace.id());
        entity.setName(workspace.name());
        entity.setMemoryMd(workspace.memoryMd());
        entity.setIsScratch(workspace.isScratch() ? 1 : 0);
        entity.setCreatedAtMs(workspace.createdAt().toEpochMilli());
        entity.setUpdatedAtMs(workspace.updatedAt().toEpochMilli());
        workspaces.save(entity);
    }

    @Override
    public Optional<Workspace> findWorkspaceById(String id)
    {
        return workspaces.findById(id).map(SqliteWorkspaceStore::toWorkspace);
    }

    @Override
    public List<Workspace> listWorkspaces()
    {
        return workspaces.findAllByOrderByUpdatedAtMsDesc().stream()
                .map(SqliteWorkspaceStore::toWorkspace)
                .toList();
    }

    @Override
    @Transactional
    public void deleteWorkspace(String id)
    {
        workspaces.deleteById(id);
    }

    @Override
    @Transactional
    public void addRepo(WorkspaceRepo repo)
    {
        WorkspaceRepoEntity.WorkspaceRepoKey key =
                new WorkspaceRepoEntity.WorkspaceRepoKey(repo.workspaceId(), repo.repoFullName());
        WorkspaceRepoEntity entity = repos.findById(key).orElseGet(WorkspaceRepoEntity::new);
        entity.setId(key);
        entity.setDefaultBaseBranch(repo.defaultBaseBranch());
        entity.setAddedAtMs(repo.addedAt().toEpochMilli());
        repos.save(entity);
    }

    @Override
    @Transactional
    public void removeRepo(String workspaceId, String repoFullName)
    {
        WorkspaceRepoEntity.WorkspaceRepoKey key =
                new WorkspaceRepoEntity.WorkspaceRepoKey(workspaceId, repoFullName);
        if (repos.existsById(key)) {
            repos.deleteById(key);
        }
    }

    @Override
    public List<WorkspaceRepo> listRepos(String workspaceId)
    {
        return repos.findByIdWorkspaceIdOrderByAddedAtMsAsc(workspaceId).stream()
                .map(SqliteWorkspaceStore::toRepo)
                .toList();
    }

    @Override
    public Optional<WorkspaceRepo> findRepo(String workspaceId, String repoFullName)
    {
        WorkspaceRepoEntity.WorkspaceRepoKey key =
                new WorkspaceRepoEntity.WorkspaceRepoKey(workspaceId, repoFullName);
        return repos.findById(key).map(SqliteWorkspaceStore::toRepo);
    }

    @Override
    @Transactional
    public void setDefaultBaseBranch(String workspaceId, String repoFullName, String defaultBaseBranch)
    {
        WorkspaceRepoEntity.WorkspaceRepoKey key =
                new WorkspaceRepoEntity.WorkspaceRepoKey(workspaceId, repoFullName);
        repos.findById(key).ifPresent(entity -> {
            entity.setDefaultBaseBranch(defaultBaseBranch);
            repos.save(entity);
        });
    }

    private static Workspace toWorkspace(WorkspaceEntity e)
    {
        return new Workspace(
                e.getId(),
                e.getName(),
                e.getMemoryMd(),
                e.getIsScratch() != 0,
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()));
    }

    private static WorkspaceRepo toRepo(WorkspaceRepoEntity e)
    {
        return new WorkspaceRepo(
                e.getId().getWorkspaceId(),
                e.getId().getRepoFullName(),
                e.getDefaultBaseBranch(),
                Instant.ofEpochMilli(e.getAddedAtMs()));
    }
}
