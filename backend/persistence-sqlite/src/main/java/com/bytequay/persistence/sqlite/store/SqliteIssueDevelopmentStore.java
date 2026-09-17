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

import com.bytequay.core.domain.IssueGoal;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.persistence.sqlite.entity.IssueGoalEntity;
import com.bytequay.persistence.sqlite.entity.IssueTaskEntity;
import com.bytequay.persistence.sqlite.repository.IssueGoalJpaRepository;
import com.bytequay.persistence.sqlite.repository.IssueTaskJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteIssueDevelopmentStore
        implements IssueDevelopmentStore
{
    private final IssueGoalJpaRepository goals;
    private final IssueTaskJpaRepository tasks;

    public SqliteIssueDevelopmentStore(IssueGoalJpaRepository goals, IssueTaskJpaRepository tasks)
    {
        this.goals = requireNonNull(goals, "goals is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IssueGoal> findGoal(String host, long issueId)
    {
        return goals.findByHostAndIssueId(host.toLowerCase(Locale.ROOT), issueId).map(IssueGoalEntity::toDomain);
    }

    @Override
    @Transactional
    public void saveGoal(IssueGoal goal)
    {
        IssueGoalEntity entity = goals.findByHostAndIssueId(goal.host(), goal.issueId()).orElseGet(() -> new IssueGoalEntity(goal));
        entity.update(goal);
        goals.saveAndFlush(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IssueTask> findTask(String host, long issueId)
    {
        return tasks.findByHostAndIssueId(host.toLowerCase(Locale.ROOT), issueId).map(IssueTaskEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IssueTask> findByTaskId(String taskId)
    {
        return tasks.findById(taskId).map(IssueTaskEntity::toDomain);
    }

    @Override
    @Transactional
    public void insertTask(IssueTask task)
    {
        tasks.saveAndFlush(new IssueTaskEntity(task));
    }
}
