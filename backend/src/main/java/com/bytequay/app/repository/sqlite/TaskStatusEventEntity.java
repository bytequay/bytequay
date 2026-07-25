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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA row for the {@code task_status_event} audit trail. */
@Entity
@Table(name = "task_status_event")
class TaskStatusEventEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "from_status", nullable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "reason")
    private String reason;

    @Column(name = "occurred_at_ms", nullable = false)
    private long occurredAtMs;

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getFromStatus() { return fromStatus; }
    void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }

    String getToStatus() { return toStatus; }
    void setToStatus(String toStatus) { this.toStatus = toStatus; }

    String getActor() { return actor; }
    void setActor(String actor) { this.actor = actor; }

    String getReason() { return reason; }
    void setReason(String reason) { this.reason = reason; }

    long getOccurredAtMs() { return occurredAtMs; }
    void setOccurredAtMs(long occurredAtMs) { this.occurredAtMs = occurredAtMs; }
}
