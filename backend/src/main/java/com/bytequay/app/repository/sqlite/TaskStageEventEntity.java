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

/** JPA row for the {@code task_stage_event} stage lifecycle log. */
@Entity
@Table(name = "task_stage_event")
class TaskStageEventEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "stage_id", nullable = false)
    private String stageId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_at_ms", nullable = false)
    private long eventAtMs;

    @Column(name = "payload_json")
    private String payloadJson;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getStageId() { return stageId; }
    void setStageId(String stageId) { this.stageId = stageId; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getEventType() { return eventType; }
    void setEventType(String eventType) { this.eventType = eventType; }

    long getEventAtMs() { return eventAtMs; }
    void setEventAtMs(long eventAtMs) { this.eventAtMs = eventAtMs; }

    String getPayloadJson() { return payloadJson; }
    void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
}
