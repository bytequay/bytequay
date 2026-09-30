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
package com.bytequay.persistence.sqlite.repository;

import com.bytequay.core.context.ContextGrade;
import com.bytequay.persistence.sqlite.entity.ContextGradeEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ContextGradeJpaRepository
        extends JpaRepository<ContextGradeEntity, String>
{
    @Query("select g from ContextGradeEntity g where g.workspaceId = :workspaceId and g.entryId = :entryId and g.entryVersion = :version "
            + "order by g.gradedAtMs desc, g.id desc")
    List<ContextGradeEntity> listVersion(String workspaceId, String entryId, long version, Pageable page);

    @Query("select g from ContextGradeEntity g where g.workspaceId = :workspaceId and g.grade in :grades order by g.gradedAtMs desc, g.id desc")
    List<ContextGradeEntity> listGrades(String workspaceId, Collection<ContextGrade.Grade> grades, Pageable page);

    @Query("select g.entryId as entryId, g.grade as grade, count(distinct g.taskId) as tasks from ContextGradeEntity g "
            + "where g.workspaceId = :workspaceId and g.gradedAtMs >= :since group by g.entryId, g.grade")
    List<CountRow> count(String workspaceId, long since);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ContextGradeEntity g WHERE g.taskId = :taskId")
    void deleteByTaskId(String taskId);

    interface CountRow
    {
        String getEntryId();

        ContextGrade.Grade getGrade();

        long getTasks();
    }
}
