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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.core.localreview.LocalReviewStore;
import com.bytequay.core.localreview.LocalReviewThread;
import com.bytequay.persistence.sqlite.entity.LocalReviewThreadEntity;
import com.bytequay.persistence.sqlite.repository.LocalReviewThreadJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.store.StoreWrites.AGENT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqliteLocalReviewStore
        implements LocalReviewStore
{
    private final LocalReviewThreadJpaRepository repository;

    public SqliteLocalReviewStore(LocalReviewThreadJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    public Optional<LocalReviewThread> find(String id)
    {
        return repository.findById(id).map(LocalReviewThreadEntity::toDomain);
    }

    @Override
    public List<LocalReviewThread> listByTask(String taskId)
    {
        return repository.findByTaskIdOrderByIdAsc(taskId).stream().map(LocalReviewThreadEntity::toDomain).collect(toImmutableList());
    }

    @Override
    public List<LocalReviewThread> listByPullRequest(String repo, int number)
    {
        return repository.findByRepoAndNumberOrderByIdAsc(repo, number).stream().map(LocalReviewThreadEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public LocalReviewThread insert(LocalReviewThread thread)
    {
        return StoreWrites.insert(repository, thread.id(), "Local review", () -> LocalReviewThreadEntity.fromDomain(thread), AGENT).toDomain();
    }

    @Override
    @Transactional
    public LocalReviewThread update(LocalReviewThread thread)
    {
        return StoreWrites.update(repository, thread.id(), "Local review", thread.version(), LocalReviewThreadEntity::getVersion,
                entity -> entity.updateFrom(thread), () -> new AgentConflictException("Local review does not exist"), AGENT).toDomain();
    }
}
