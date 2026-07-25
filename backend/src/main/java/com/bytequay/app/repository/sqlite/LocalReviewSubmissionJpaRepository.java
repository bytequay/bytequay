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
package com.bytequay.app.repository.sqlite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface LocalReviewSubmissionJpaRepository
        extends JpaRepository<LocalReviewSubmissionEntity, String>
{
    @Query("SELECT COALESCE(MAX(s.submissionSeq), 0) FROM LocalReviewSubmissionEntity s "
            + "WHERE s.taskId = :taskId")
    long maxSeq(@Param("taskId") String taskId);

    @Query("SELECT s FROM LocalReviewSubmissionEntity s WHERE s.taskId = :taskId "
            + "AND s.completedAtMs IS NULL AND s.canceledAtMs IS NULL "
            + "ORDER BY s.submissionSeq ASC")
    List<LocalReviewSubmissionEntity> listOpenByTask(@Param("taskId") String taskId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LocalReviewSubmissionEntity s SET s.agentRunId = :runId, "
            + "s.activatedAtMs = :atMs WHERE s.id = :id")
    int bindRun(@Param("id") String id, @Param("runId") String runId, @Param("atMs") long atMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LocalReviewSubmissionEntity s SET s.completedAtMs = :atMs "
            + "WHERE s.id = :id AND s.completedAtMs IS NULL AND s.canceledAtMs IS NULL")
    int markCompleted(@Param("id") String id, @Param("atMs") long atMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LocalReviewSubmissionEntity s SET s.canceledAtMs = :atMs, "
            + "s.cancelReason = :reason WHERE s.taskId = :taskId "
            + "AND s.completedAtMs IS NULL AND s.canceledAtMs IS NULL")
    int cancelOpenForTask(
            @Param("taskId") String taskId, @Param("reason") String reason, @Param("atMs") long atMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LocalReviewSubmissionEntity s SET s.failures = s.failures + 1 "
            + "WHERE s.id = :id")
    int incrementFailures(@Param("id") String id);
}
