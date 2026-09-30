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

import com.bytequay.core.context.ContextGrade;
import com.bytequay.core.context.ContextGradeStore;
import com.bytequay.persistence.sqlite.entity.ContextGradeEntity;
import com.bytequay.persistence.sqlite.repository.ContextGradeJpaRepository;
import com.google.common.collect.ImmutableSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteContextGradeStore
        implements ContextGradeStore
{
    private static final int MAXIMUM_PAGE = 1000;

    private final ContextGradeJpaRepository grades;

    public SqliteContextGradeStore(ContextGradeJpaRepository grades)
    {
        this.grades = requireNonNull(grades, "grades is null");
    }

    @Override
    @Transactional
    public void save(ContextGrade grade)
    {
        grades.saveAndFlush(new ContextGradeEntity(grade));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContextGrade> find(String taskId, ContextGrade.Grader grader, String entryId, long version)
    {
        return grades.findById(ContextGradeEntity.key(taskId, grader, entryId, version)).map(ContextGradeEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContextGrade> listVersion(String workspaceId, String entryId, long version, int limit)
    {
        checkArgument(limit >= 1 && limit <= MAXIMUM_PAGE, "Grade page size is out of range");
        return grades.listVersion(workspaceId, entryId, version, PageRequest.of(0, limit)).stream().map(ContextGradeEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContextGrade> listGrades(String workspaceId, Set<ContextGrade.Grade> kinds, int limit)
    {
        checkArgument(limit >= 1 && limit <= MAXIMUM_PAGE, "Grade page size is out of range");
        return grades.listGrades(workspaceId, ImmutableSet.copyOf(kinds), PageRequest.of(0, limit)).stream()
                .map(ContextGradeEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Count> count(String workspaceId, Instant since)
    {
        return grades.count(workspaceId, since.toEpochMilli()).stream()
                .map(row -> new Count(row.getEntryId(), row.getGrade(), row.getTasks())).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void deleteTask(String taskId)
    {
        grades.deleteByTaskId(taskId);
    }
}
