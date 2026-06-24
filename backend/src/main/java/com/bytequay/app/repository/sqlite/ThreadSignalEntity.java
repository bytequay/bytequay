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

/** JPA row for a {@code thread_signal} — a passive Notifications-feed entry. */
@Entity
@Table(name = "thread_signal")
class ThreadSignalEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "source_kind", nullable = false)
    private String sourceKind;

    @Column(name = "icon_kind", nullable = false)
    private String iconKind;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body")
    private String body;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "read_at_ms")
    private Long readAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getSourceKind() { return sourceKind; }
    void setSourceKind(String sourceKind) { this.sourceKind = sourceKind; }

    String getIconKind() { return iconKind; }
    void setIconKind(String iconKind) { this.iconKind = iconKind; }

    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    String getSourceUrl() { return sourceUrl; }
    void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getReadAtMs() { return readAtMs; }
    void setReadAtMs(Long readAtMs) { this.readAtMs = readAtMs; }
}
