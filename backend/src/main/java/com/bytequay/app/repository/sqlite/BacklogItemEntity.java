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
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;

/** JPA row for a {@code backlog_item}. {@code tags} is stored as a JSON
 *  text column via {@link StringListConverter}. */
@Entity
@Table(name = "backlog_item")
class BacklogItemEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @Convert(converter = StringListConverter.class)
    @Column(name = "tags_json", nullable = false)
    private List<String> tags;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "started_at_ms")
    private Long startedAtMs;

    @Column(name = "linked_task_id")
    private String linkedTaskId;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    List<String> getTags() { return tags; }
    void setTags(List<String> tags) { this.tags = tags; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getStartedAtMs() { return startedAtMs; }
    void setStartedAtMs(Long startedAtMs) { this.startedAtMs = startedAtMs; }

    String getLinkedTaskId() { return linkedTaskId; }
    void setLinkedTaskId(String linkedTaskId) { this.linkedTaskId = linkedTaskId; }
}
