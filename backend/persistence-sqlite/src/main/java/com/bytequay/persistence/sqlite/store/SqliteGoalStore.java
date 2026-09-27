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

import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.store.GoalStore;
import com.bytequay.persistence.sqlite.entity.GoalEntity;
import com.bytequay.persistence.sqlite.repository.GoalJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteGoalStore
        implements GoalStore
{
    private final GoalJpaRepository goals;

    public SqliteGoalStore(GoalJpaRepository goals)
    {
        this.goals = requireNonNull(goals, "goals is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Goal> list(String workspaceId)
    {
        return goals.findByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId).stream().map(GoalEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Goal> find(String goalId)
    {
        return goals.findById(goalId).map(GoalEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Goal> findBySource(GoalSource source)
    {
        return goals.findBySourceKindAndSourceKey(source.kind().name(), source.key()).stream().map(GoalEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(Goal goal)
    {
        // Updates the loaded row; only a goal with no row yet is persisted as new.
        GoalEntity entity = goals.findById(goal.id()).orElseGet(() -> new GoalEntity(goal));
        entity.update(goal);
        goals.saveAndFlush(entity);
    }

    @Override
    @Transactional
    public void delete(String goalId)
    {
        goals.deleteById(goalId);
    }
}
