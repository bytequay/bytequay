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

import com.bytequay.core.domain.JiraIssueTask;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.persistence.sqlite.entity.JiraIssueTaskEntity;
import com.bytequay.persistence.sqlite.repository.JiraIssueTaskJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteJiraIssueTaskStore
        implements JiraIssueTaskStore
{
    private final JiraIssueTaskJpaRepository tasks;

    public SqliteJiraIssueTaskStore(JiraIssueTaskJpaRepository tasks)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JiraIssueTask> findBySource(String cloudId, String issueId)
    {
        return tasks.findByCloudIdAndIssueId(cloudId, issueId).map(JiraIssueTaskEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JiraIssueTask> findByTaskId(String taskId)
    {
        return tasks.findById(taskId).map(JiraIssueTaskEntity::toDomain);
    }

    @Override
    @Transactional
    public void insert(JiraIssueTask task)
    {
        tasks.saveAndFlush(new JiraIssueTaskEntity(task));
    }
}
