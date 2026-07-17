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
@Table(name = "notifications")
class NotificationEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "thread_id")
    private String threadId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "read_at_ms")
    private Long readAtMs;

    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "public_type")
    private String publicType;

    @Column(name = "title")
    private String title;

    @Column(name = "summary")
    private String summary;

    @Column(name = "item_path")
    private String itemPath;

    @Column(name = "dedup_key")
    private String dedupKey;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getKind() { return kind; }
    void setKind(String kind) { this.kind = kind; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    String getPayloadJson() { return payloadJson; }
    void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getReadAtMs() { return readAtMs; }
    void setReadAtMs(Long readAtMs) { this.readAtMs = readAtMs; }

    String getWorkspaceId() { return workspaceId; }
    void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    String getPublicType() { return publicType; }
    void setPublicType(String publicType) { this.publicType = publicType; }

    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }

    String getSummary() { return summary; }
    void setSummary(String summary) { this.summary = summary; }

    String getItemPath() { return itemPath; }
    void setItemPath(String itemPath) { this.itemPath = itemPath; }

    String getDedupKey() { return dedupKey; }
    void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
}
