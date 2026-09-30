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
package com.bytequay.core.context;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Task-owned grades and reports of exact note versions; deleting a task deletes its grades. */
public interface ContextGradeStore
{
    /** Saves a grade, replacing the same task's earlier grade by that grader for that note version. */
    void save(ContextGrade grade);

    /** The same task's grade by that grader for that note version, if any. */
    Optional<ContextGrade> find(String taskId, ContextGrade.Grader grader, String entryId, long version);

    /** Grades of one exact note version, newest first. */
    List<ContextGrade> listVersion(String workspaceId, String entryId, long version, int limit);

    /** A workspace's grades of the given kinds, newest first. */
    List<ContextGrade> listGrades(String workspaceId, Set<ContextGrade.Grade> grades, int limit);

    /** Distinct grading tasks per note and grade since {@code since}, across every version of each note. */
    List<Count> count(String workspaceId, Instant since);

    void deleteTask(String taskId);

    record Count(String entryId, ContextGrade.Grade grade, long tasks) {}
}
