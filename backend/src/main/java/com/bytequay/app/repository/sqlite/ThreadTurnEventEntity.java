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
@Table(name = "thread_turn_events")
class ThreadTurnEventEntity
{
    @Id
    private String id;

    @Column(name = "turn_id", nullable = false)
    private String turnId;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "event", nullable = false)
    private String event;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "message")
    private String message;

    @Column(name = "is_summary", nullable = false)
    private boolean summary;

    @Column(name = "stage_id")
    private String stageId;

    String getId()
    {
        return id;
    }

    void setId(String id)
    {
        this.id = id;
    }

    String getTurnId()
    {
        return turnId;
    }

    void setTurnId(String turnId)
    {
        this.turnId = turnId;
    }

    String getThreadId()
    {
        return threadId;
    }

    void setThreadId(String threadId)
    {
        this.threadId = threadId;
    }

    String getTaskId()
    {
        return taskId;
    }

    void setTaskId(String taskId)
    {
        this.taskId = taskId;
    }

    String getEvent()
    {
        return event;
    }

    void setEvent(String event)
    {
        this.event = event;
    }

    long getCreatedAtMs()
    {
        return createdAtMs;
    }

    void setCreatedAtMs(long createdAtMs)
    {
        this.createdAtMs = createdAtMs;
    }

    String getMessage()
    {
        return message;
    }

    void setMessage(String message)
    {
        this.message = message;
    }

    boolean isSummary()
    {
        return summary;
    }

    void setSummary(boolean summary)
    {
        this.summary = summary;
    }

    String getStageId()
    {
        return stageId;
    }

    void setStageId(String stageId)
    {
        this.stageId = stageId;
    }
}
