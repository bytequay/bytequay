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

/** JPA row for a {@code distillation_signal}. */
@Entity
@Table(name = "distillation_signal")
class DistillationSignalEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "user_decision", nullable = false)
    private String userDecision;

    @Column(name = "reason")
    private String reason;

    @Column(name = "context_snapshot_json", nullable = false)
    private String contextSnapshotJson;

    @Column(name = "thread_id")
    private String threadId;

    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getEventType() { return eventType; }
    void setEventType(String eventType) { this.eventType = eventType; }

    String getSourceId() { return sourceId; }
    void setSourceId(String sourceId) { this.sourceId = sourceId; }

    String getUserDecision() { return userDecision; }
    void setUserDecision(String userDecision) { this.userDecision = userDecision; }

    String getReason() { return reason; }
    void setReason(String reason) { this.reason = reason; }

    String getContextSnapshotJson() { return contextSnapshotJson; }
    void setContextSnapshotJson(String contextSnapshotJson) { this.contextSnapshotJson = contextSnapshotJson; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    String getWorkspaceId() { return workspaceId; }
    void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }
}
