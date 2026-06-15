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

/** JPA row for the {@code validation_pass} audit log. */
@Entity
@Table(name = "validation_pass")
class ValidationPassEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "started_at_ms", nullable = false)
    private long startedAtMs;

    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    @Column(name = "passed")
    private Boolean passed;

    @Column(name = "fix_rounds", nullable = false)
    private int fixRounds;

    @Column(name = "failures_json")
    private String failuresJson;

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    long getStartedAtMs() { return startedAtMs; }
    void setStartedAtMs(long startedAtMs) { this.startedAtMs = startedAtMs; }

    Long getEndedAtMs() { return endedAtMs; }
    void setEndedAtMs(Long endedAtMs) { this.endedAtMs = endedAtMs; }

    Boolean getPassed() { return passed; }
    void setPassed(Boolean passed) { this.passed = passed; }

    int getFixRounds() { return fixRounds; }
    void setFixRounds(int fixRounds) { this.fixRounds = fixRounds; }

    String getFailuresJson() { return failuresJson; }
    void setFailuresJson(String failuresJson) { this.failuresJson = failuresJson; }
}
