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

import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.persistence.sqlite.entity.PullRequestTaskEntity;
import com.bytequay.persistence.sqlite.repository.PullRequestTaskJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqlitePullRequestTaskStore
        implements PullRequestTaskStore
{
    private final PullRequestTaskJpaRepository repository;

    public SqlitePullRequestTaskStore(PullRequestTaskJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PullRequestTask> find(String workspaceId, String repo, int number)
    {
        return repository.findLatest(workspaceId, repo.toLowerCase(Locale.ROOT), number, PageRequest.of(0, 1)).stream().findFirst().map(PullRequestTaskEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PullRequestTask> findByTaskId(String taskId)
    {
        return repository.findById(taskId).map(PullRequestTaskEntity::toDomain);
    }

    @Override
    @Transactional
    public void insert(PullRequestTask assignment)
    {
        repository.saveAndFlush(PullRequestTaskEntity.fromDomain(assignment));
    }
}
