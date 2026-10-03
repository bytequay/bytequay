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

import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.store.GoalTopicStore;
import com.bytequay.persistence.sqlite.entity.GoalTopicEntity;
import com.bytequay.persistence.sqlite.repository.GoalTopicJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteGoalTopicStore
        implements GoalTopicStore
{
    private final GoalTopicJpaRepository topics;

    public SqliteGoalTopicStore(GoalTopicJpaRepository topics)
    {
        this.topics = requireNonNull(topics, "topics is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GoalTopic> find(String topicId)
    {
        return topics.findById(topicId).map(GoalTopicEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GoalTopic> list(String goalId)
    {
        return topics.findByGoalIdOrderByIdAsc(goalId).stream().map(GoalTopicEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(GoalTopic topic)
    {
        GoalTopicEntity entity = topics.findById(topic.id()).orElseGet(() -> new GoalTopicEntity(topic));
        entity.update(topic);
        topics.saveAndFlush(entity);
    }
}
