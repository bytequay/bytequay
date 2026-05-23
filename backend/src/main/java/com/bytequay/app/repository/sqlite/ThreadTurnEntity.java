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
@Table(name = "thread_turns")
class ThreadTurnEntity
{
    @Id
    private String id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "lane", nullable = false)
    private String lane;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "input", nullable = false)
    private String input;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "updated_at_ms", nullable = false)
    private long updatedAtMs;

    @Column(name = "started_at_ms")
    private Long startedAtMs;

    @Column(name = "finished_at_ms")
    private Long finishedAtMs;

    @Column(name = "error_message")
    private String errorMessage;

    String getId()
    {
        return id;
    }

    void setId(String id)
    {
        this.id = id;
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

    String getLane()
    {
        return lane;
    }

    void setLane(String lane)
    {
        this.lane = lane;
    }

    String getStatus()
    {
        return status;
    }

    void setStatus(String status)
    {
        this.status = status;
    }

    String getInput()
    {
        return input;
    }

    void setInput(String input)
    {
        this.input = input;
    }

    long getCreatedAtMs()
    {
        return createdAtMs;
    }

    void setCreatedAtMs(long createdAtMs)
    {
        this.createdAtMs = createdAtMs;
    }

    long getUpdatedAtMs()
    {
        return updatedAtMs;
    }

    void setUpdatedAtMs(long updatedAtMs)
    {
        this.updatedAtMs = updatedAtMs;
    }

    Long getStartedAtMs()
    {
        return startedAtMs;
    }

    void setStartedAtMs(Long startedAtMs)
    {
        this.startedAtMs = startedAtMs;
    }

    Long getFinishedAtMs()
    {
        return finishedAtMs;
    }

    void setFinishedAtMs(Long finishedAtMs)
    {
        this.finishedAtMs = finishedAtMs;
    }

    String getErrorMessage()
    {
        return errorMessage;
    }

    void setErrorMessage(String errorMessage)
    {
        this.errorMessage = errorMessage;
    }
}
