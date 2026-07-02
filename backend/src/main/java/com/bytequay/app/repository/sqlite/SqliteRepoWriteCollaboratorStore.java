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

import com.bytequay.app.repository.RepoWriteCollaboratorStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteRepoWriteCollaboratorStore
        implements RepoWriteCollaboratorStore
{
    private final RepoWriteCollaboratorJpaRepository repo;

    SqliteRepoWriteCollaboratorStore(RepoWriteCollaboratorJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Boolean> find(String repoFullName, String login, Instant freshAfter)
    {
        return repo.findById(RepoWriteCollaboratorEntity.idOf(repoFullName, login))
                .filter(entity -> !entity.getFetchedAt().isBefore(freshAfter))
                .map(RepoWriteCollaboratorEntity::isCanWrite);
    }

    @Override
    @Transactional
    public void save(String repoFullName, String login, boolean canWrite, Instant fetchedAt)
    {
        RepoWriteCollaboratorEntity entity = new RepoWriteCollaboratorEntity();
        entity.setId(RepoWriteCollaboratorEntity.idOf(repoFullName, login));
        entity.setRepoFullName(repoFullName);
        entity.setLogin(login);
        entity.setCanWrite(canWrite);
        entity.setFetchedAt(fetchedAt);
        repo.save(entity);
    }
}
