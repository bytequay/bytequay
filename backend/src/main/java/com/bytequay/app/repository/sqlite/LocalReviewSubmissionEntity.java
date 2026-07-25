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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "local_review_submission")
class LocalReviewSubmissionEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "timeline_event_id")
    private String timelineEventId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "pr_id", nullable = false)
    private String prId;

    @Column(name = "agent_run_id")
    private String agentRunId;

    @Column(name = "submission_seq", nullable = false)
    private long submissionSeq;

    @Column(name = "root_ids_json", nullable = false)
    private String rootIdsJson;

    @Column(name = "root_snapshot_json", nullable = false)
    private String rootSnapshotJson;

    @Column(name = "submitted_through_ms", nullable = false)
    private long submittedThroughMs;

    @Column(name = "addressed_through_ms")
    private Long addressedThroughMs;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "failures", nullable = false)
    private int failures;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "activated_at_ms")
    private Long activatedAtMs;

    @Column(name = "completed_at_ms")
    private Long completedAtMs;

    @Column(name = "canceled_at_ms")
    private Long canceledAtMs;

    @Column(name = "cancel_reason")
    private String cancelReason;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getTimelineEventId() { return timelineEventId; }
    void setTimelineEventId(String timelineEventId) { this.timelineEventId = timelineEventId; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getPrId() { return prId; }
    void setPrId(String prId) { this.prId = prId; }

    String getAgentRunId() { return agentRunId; }
    void setAgentRunId(String agentRunId) { this.agentRunId = agentRunId; }

    long getSubmissionSeq() { return submissionSeq; }
    void setSubmissionSeq(long submissionSeq) { this.submissionSeq = submissionSeq; }

    String getRootIdsJson() { return rootIdsJson; }
    void setRootIdsJson(String rootIdsJson) { this.rootIdsJson = rootIdsJson; }

    String getRootSnapshotJson() { return rootSnapshotJson; }
    void setRootSnapshotJson(String rootSnapshotJson) { this.rootSnapshotJson = rootSnapshotJson; }

    long getSubmittedThroughMs() { return submittedThroughMs; }
    void setSubmittedThroughMs(long submittedThroughMs) { this.submittedThroughMs = submittedThroughMs; }

    Long getAddressedThroughMs() { return addressedThroughMs; }
    void setAddressedThroughMs(Long addressedThroughMs) { this.addressedThroughMs = addressedThroughMs; }

    int getAttempt() { return attempt; }
    void setAttempt(int attempt) { this.attempt = attempt; }

    int getFailures() { return failures; }
    void setFailures(int failures) { this.failures = failures; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getActivatedAtMs() { return activatedAtMs; }
    void setActivatedAtMs(Long activatedAtMs) { this.activatedAtMs = activatedAtMs; }

    Long getCompletedAtMs() { return completedAtMs; }
    void setCompletedAtMs(Long completedAtMs) { this.completedAtMs = completedAtMs; }

    Long getCanceledAtMs() { return canceledAtMs; }
    void setCanceledAtMs(Long canceledAtMs) { this.canceledAtMs = canceledAtMs; }

    String getCancelReason() { return cancelReason; }
    void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
}
