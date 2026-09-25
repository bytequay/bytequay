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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.discussion.TaskDiscussion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.AGENT;

/**
 * One discussion row. The whole aggregate lives in the JSON payload; the real columns exist only
 * to find and order rows. The unique constraint carries the product rule that a passage has one
 * discussion, and removes the duplicate-submission race without an idempotency table.
 */
@Entity
@Table(name = "task_discussion",
        indexes = @Index(name = "idx_task_discussion_task", columnList = "task_id"),
        uniqueConstraints = @UniqueConstraint(name = "uq_task_discussion_anchor",
                columnNames = {"task_id", "turn_id", "quote_hash"}))
public class TaskDiscussionEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "turn_id", nullable = false)
    private String turnId;

    @Column(name = "quote_hash", nullable = false)
    private String quoteHash;

    // A real column because SQLite cannot order by a field inside the payload.
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Version
    @Getter
    private long version;

    public TaskDiscussionEntity() {}

    public static TaskDiscussionEntity fromDomain(TaskDiscussion discussion)
    {
        TaskDiscussionEntity entity = new TaskDiscussionEntity();
        entity.id = discussion.id();
        entity.version = discussion.version();
        entity.updateFrom(discussion);
        return entity;
    }

    public void updateFrom(TaskDiscussion discussion)
    {
        taskId = discussion.taskId();
        turnId = discussion.anchor().turnId();
        quoteHash = discussion.anchor().quoteHash();
        createdAt = discussion.createdAt();
        payload = AGENT.write(discussion, "discussion");
    }

    public TaskDiscussion toDomain()
    {
        return AGENT.read(payload, TaskDiscussion.class, "discussion").withVersion(version);
    }
}
