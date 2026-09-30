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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextGrade;
import com.bytequay.core.context.ContextGradeStore;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Keeps one grade per task, grader and note version, newest last, like the SQLite store. */
public final class InMemoryContextGradeStore
        implements ContextGradeStore
{
    private final Map<String, ContextGrade> saved = new LinkedHashMap<>();

    @Override
    public void save(ContextGrade grade)
    {
        String key = key(grade.taskId(), grade.grader(), grade.note().id(), grade.note().version());
        saved.remove(key);
        saved.put(key, grade);
    }

    @Override
    public Optional<ContextGrade> find(String taskId, ContextGrade.Grader grader, String entryId, long version)
    {
        return Optional.ofNullable(saved.get(key(taskId, grader, entryId, version)));
    }

    @Override
    public List<ContextGrade> listVersion(String workspaceId, String entryId, long version, int limit)
    {
        return ImmutableList.copyOf(saved.values()).reverse().stream()
                .filter(grade -> grade.workspaceId().equals(workspaceId) && grade.note().id().equals(entryId) && grade.note().version() == version)
                .limit(limit).collect(ImmutableList.toImmutableList());
    }

    @Override
    public List<ContextGrade> listGrades(String workspaceId, Set<ContextGrade.Grade> kinds, int limit)
    {
        return ImmutableList.copyOf(saved.values()).reverse().stream().filter(grade -> grade.workspaceId().equals(workspaceId) && kinds.contains(grade.grade()))
                .limit(limit).collect(ImmutableList.toImmutableList());
    }

    @Override
    public List<Count> count(String workspaceId, Instant since)
    {
        return ImmutableList.of();
    }

    @Override
    public void deleteTask(String taskId)
    {
        saved.values().removeIf(grade -> grade.taskId().equals(taskId));
    }

    private static String key(String taskId, ContextGrade.Grader grader, String entryId, long version)
    {
        return taskId + ":" + grader + ":" + version + ":" + entryId;
    }
}
