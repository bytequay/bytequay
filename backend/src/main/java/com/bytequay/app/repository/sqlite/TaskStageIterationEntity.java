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

/** JPA row for a {@code task_stage_iteration}. */
@Entity
@Table(name = "task_stage_iteration")
class TaskStageIterationEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "stage_id", nullable = false)
    private String stageId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "turn_id", nullable = false)
    private String turnId;

    @Column(name = "iteration_number", nullable = false)
    private int iterationNumber;

    @Column(name = "trigger", nullable = false)
    private String trigger;

    @Column(name = "started_at_ms", nullable = false)
    private long startedAtMs;

    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    @Column(name = "ended_reason")
    private String endedReason;

    @Column(name = "summary_text")
    private String summaryText;

    @Column(name = "summarized_at_ms")
    private Long summarizedAtMs;

    @Column(name = "summary_request_turn_id")
    private String summaryRequestTurnId;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getStageId() { return stageId; }
    void setStageId(String stageId) { this.stageId = stageId; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getTurnId() { return turnId; }
    void setTurnId(String turnId) { this.turnId = turnId; }

    int getIterationNumber() { return iterationNumber; }
    void setIterationNumber(int iterationNumber) { this.iterationNumber = iterationNumber; }

    String getTrigger() { return trigger; }
    void setTrigger(String trigger) { this.trigger = trigger; }

    long getStartedAtMs() { return startedAtMs; }
    void setStartedAtMs(long startedAtMs) { this.startedAtMs = startedAtMs; }

    Long getEndedAtMs() { return endedAtMs; }
    void setEndedAtMs(Long endedAtMs) { this.endedAtMs = endedAtMs; }

    String getEndedReason() { return endedReason; }
    void setEndedReason(String endedReason) { this.endedReason = endedReason; }

    String getSummaryText() { return summaryText; }
    void setSummaryText(String summaryText) { this.summaryText = summaryText; }

    Long getSummarizedAtMs() { return summarizedAtMs; }
    void setSummarizedAtMs(Long summarizedAtMs) { this.summarizedAtMs = summarizedAtMs; }

    String getSummaryRequestTurnId() { return summaryRequestTurnId; }
    void setSummaryRequestTurnId(String summaryRequestTurnId) { this.summaryRequestTurnId = summaryRequestTurnId; }
}
