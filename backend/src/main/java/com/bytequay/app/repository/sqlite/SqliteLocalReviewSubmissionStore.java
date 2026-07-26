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

import com.bytequay.app.domain.LocalReviewSubmission;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.repository.sqlite.Timestamps.epochMilli;
import static java.util.Objects.requireNonNull;

@Repository
class SqliteLocalReviewSubmissionStore
        implements LocalReviewSubmissionStore
{
    private final LocalReviewSubmissionJpaRepository rows;

    SqliteLocalReviewSubmissionStore(LocalReviewSubmissionJpaRepository rows)
    {
        this.rows = requireNonNull(rows, "rows is null");
    }

    @Override
    @Transactional
    public void insert(LocalReviewSubmission submission)
    {
        LocalReviewSubmissionEntity entity = new LocalReviewSubmissionEntity();
        entity.setId(submission.id());
        entity.setTimelineEventId(submission.timelineEventId());
        entity.setTaskId(submission.taskId());
        entity.setPrId(submission.prId());
        entity.setAgentRunId(submission.agentRunId());
        entity.setSubmissionSeq(submission.submissionSeq());
        entity.setRootIdsJson(submission.rootIdsJson());
        entity.setRootSnapshotJson(submission.rootSnapshotJson());
        entity.setSubmittedThroughMs(submission.submittedThroughAt().toEpochMilli());
        entity.setAddressedThroughMs(epochMilli(submission.addressedThroughAt()));
        entity.setAttempt(submission.attempt());
        entity.setFailures(submission.failures());
        entity.setCreatedAtMs(submission.createdAt().toEpochMilli());
        entity.setActivatedAtMs(epochMilli(submission.activatedAt()));
        entity.setCompletedAtMs(epochMilli(submission.completedAt()));
        entity.setCanceledAtMs(epochMilli(submission.canceledAt()));
        entity.setCancelReason(submission.cancelReason());
        rows.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public long nextSeq(String taskId)
    {
        return rows.maxSeq(taskId) + 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalReviewSubmission> findById(String id)
    {
        return rows.findById(id).map(SqliteLocalReviewSubmissionStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocalReviewSubmission> listOpenByTask(String taskId)
    {
        return rows.listOpenByTask(taskId).stream()
                .map(SqliteLocalReviewSubmissionStore::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void bindRun(String id, String agentRunId, Instant activatedAt)
    {
        rows.bindRun(id, agentRunId, activatedAt.toEpochMilli());
    }

    @Override
    @Transactional
    public void bindRunThrough(
            String taskId, long throughSequence, String agentRunId, Instant activatedAt)
    {
        rows.bindRunThrough(
                taskId, throughSequence, agentRunId, activatedAt.toEpochMilli());
    }

    @Override
    @Transactional
    public void markCompleted(String id, Instant at)
    {
        rows.markCompleted(id, at.toEpochMilli());
    }

    @Override
    @Transactional
    public void cancelOpenForTask(String taskId, String reason, Instant at)
    {
        rows.cancelOpenForTask(taskId, reason, at.toEpochMilli());
    }

    @Override
    @Transactional
    public void incrementFailures(String id)
    {
        rows.incrementFailures(id);
    }

    private static LocalReviewSubmission toDomain(LocalReviewSubmissionEntity e)
    {
        return new LocalReviewSubmission(
                e.getId(),
                e.getTimelineEventId(),
                e.getTaskId(),
                e.getPrId(),
                e.getAgentRunId(),
                e.getSubmissionSeq(),
                e.getRootIdsJson(),
                e.getRootSnapshotJson(),
                Instant.ofEpochMilli(e.getSubmittedThroughMs()),
                e.getAddressedThroughMs() == null ? null : Instant.ofEpochMilli(e.getAddressedThroughMs()),
                e.getAttempt(),
                e.getFailures(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getActivatedAtMs() == null ? null : Instant.ofEpochMilli(e.getActivatedAtMs()),
                e.getCompletedAtMs() == null ? null : Instant.ofEpochMilli(e.getCompletedAtMs()),
                e.getCanceledAtMs() == null ? null : Instant.ofEpochMilli(e.getCanceledAtMs()),
                e.getCancelReason());
    }
}
