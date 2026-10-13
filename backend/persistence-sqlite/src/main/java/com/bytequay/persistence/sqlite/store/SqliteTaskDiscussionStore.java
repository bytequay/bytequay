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
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.bytequay.persistence.sqlite.entity.TaskDiscussionEntity;
import com.bytequay.persistence.sqlite.repository.TaskDiscussionJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.store.StoreWrites.AGENT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteTaskDiscussionStore
        implements TaskDiscussionStore
{
    private final TaskDiscussionJpaRepository repository;

    public SqliteTaskDiscussionStore(TaskDiscussionJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskDiscussion> find(String discussionId)
    {
        return repository.findById(discussionId).map(TaskDiscussionEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskDiscussion> findByAnchor(String taskId, String turnId, String quoteHash)
    {
        return repository.findByTaskIdAndTurnIdAndQuoteHash(taskId, turnId, quoteHash).map(TaskDiscussionEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskDiscussion> listByTask(String taskId)
    {
        return repository.findByTaskIdOrderByCreatedAtAsc(taskId).stream().map(TaskDiscussionEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public TaskDiscussion insertOrGet(TaskDiscussion discussion)
    {
        return StoreWrites.createOrGet(
                repository,
                discussion.id(),
                "Discussion",
                () -> repository.findByTaskIdAndTurnIdAndQuoteHash(discussion.taskId(), discussion.anchor().turnId(), discussion.anchor().quoteHash()),
                () -> TaskDiscussionEntity.fromDomain(discussion),
                AGENT).toDomain();
    }

    @Override
    @Transactional
    public TaskDiscussion update(TaskDiscussion discussion)
    {
        return StoreWrites.update(repository, discussion.id(), "Discussion", discussion.version(), TaskDiscussionEntity::getVersion,
                entity -> entity.updateFrom(discussion), () -> new AgentConflictException("Discussion does not exist"), AGENT).toDomain();
    }
}
