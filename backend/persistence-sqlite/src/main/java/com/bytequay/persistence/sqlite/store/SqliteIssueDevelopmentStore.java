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

import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.persistence.sqlite.entity.IssueEpicEntity;
import com.bytequay.persistence.sqlite.entity.IssueTaskEntity;
import com.bytequay.persistence.sqlite.repository.IssueEpicJpaRepository;
import com.bytequay.persistence.sqlite.repository.IssueTaskJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqliteIssueDevelopmentStore
        implements IssueDevelopmentStore
{
    private final IssueEpicJpaRepository epics;
    private final IssueTaskJpaRepository tasks;

    public SqliteIssueDevelopmentStore(IssueEpicJpaRepository epics, IssueTaskJpaRepository tasks)
    {
        this.epics = requireNonNull(epics, "epics is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @Override
    public Optional<IssueEpic> findEpic(String host, long issueId)
    {
        return epics.findByHostAndIssueId(host.toLowerCase(Locale.ROOT), issueId).map(IssueEpicEntity::toDomain);
    }

    @Override
    @Transactional
    public void saveEpic(IssueEpic epic)
    {
        IssueEpicEntity entity = epics.findByHostAndIssueId(epic.host(), epic.issueId()).orElseGet(() -> new IssueEpicEntity(epic));
        entity.update(epic);
        epics.saveAndFlush(entity);
    }

    @Override
    public Optional<IssueTask> findTask(String host, long issueId)
    {
        return tasks.findBySourceSlot(IssueTaskEntity.slot(host.toLowerCase(Locale.ROOT), issueId)).map(IssueTaskEntity::toDomain);
    }

    @Override
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

    @Override
    @Transactional
    public void retireTask(String taskId)
    {
        tasks.findById(taskId).ifPresent(entity -> {
            entity.retire();
            tasks.saveAndFlush(entity);
        });
    }
}
