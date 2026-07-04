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

/** JPA row for a {@code branch_guard}. */
@Entity
@Table(name = "branch_guard")
class BranchGuardEntity
{
    @Id
    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "schedule", nullable = false)
    private String schedule;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "last_run_id")
    private String lastRunId;

    @Column(name = "last_checked_at_ms")
    private Long lastCheckedAtMs;

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    boolean isEnabled() { return enabled; }
    void setEnabled(boolean enabled) { this.enabled = enabled; }

    String getSchedule() { return schedule; }
    void setSchedule(String schedule) { this.schedule = schedule; }

    String getState() { return state; }
    void setState(String state) { this.state = state; }

    String getLastRunId() { return lastRunId; }
    void setLastRunId(String lastRunId) { this.lastRunId = lastRunId; }

    Long getLastCheckedAtMs() { return lastCheckedAtMs; }
    void setLastCheckedAtMs(Long lastCheckedAtMs) { this.lastCheckedAtMs = lastCheckedAtMs; }
}
