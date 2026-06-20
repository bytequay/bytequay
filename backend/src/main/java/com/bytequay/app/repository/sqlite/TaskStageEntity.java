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

/** JPA row for a {@code task_stage} instance. */
@Entity
@Table(name = "task_stage")
class TaskStageEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "stage_type", nullable = false)
    private String stageType;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "opened_at_ms", nullable = false)
    private long openedAtMs;

    @Column(name = "closed_at_ms")
    private Long closedAtMs;

    @Column(name = "caller_stage_id")
    private String callerStageId;

    @Column(name = "summary_json")
    private String summaryJson;

    @Column(name = "metrics_json")
    private String metricsJson;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getStageType() { return stageType; }
    void setStageType(String stageType) { this.stageType = stageType; }

    String getState() { return state; }
    void setState(String state) { this.state = state; }

    long getOpenedAtMs() { return openedAtMs; }
    void setOpenedAtMs(long openedAtMs) { this.openedAtMs = openedAtMs; }

    Long getClosedAtMs() { return closedAtMs; }
    void setClosedAtMs(Long closedAtMs) { this.closedAtMs = closedAtMs; }

    String getCallerStageId() { return callerStageId; }
    void setCallerStageId(String callerStageId) { this.callerStageId = callerStageId; }

    String getSummaryJson() { return summaryJson; }
    void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }

    String getMetricsJson() { return metricsJson; }
    void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
}
