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

/** JPA row for a {@code dev_report}. */
@Entity
@Table(name = "dev_report")
class DevReportEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "task_id", nullable = false, unique = true)
    private String taskId;

    @Column(name = "summary", nullable = false)
    private String summary;

    @Column(name = "decisions_json")
    private String decisionsJson;

    @Column(name = "invariants_json")
    private String invariantsJson;

    @Column(name = "tricky_spots_json")
    private String trickySpotsJson;

    @Column(name = "test_map_json")
    private String testMapJson;

    @Column(name = "followups_json")
    private String followupsJson;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getSummary() { return summary; }
    void setSummary(String summary) { this.summary = summary; }

    String getDecisionsJson() { return decisionsJson; }
    void setDecisionsJson(String decisionsJson) { this.decisionsJson = decisionsJson; }

    String getInvariantsJson() { return invariantsJson; }
    void setInvariantsJson(String invariantsJson) { this.invariantsJson = invariantsJson; }

    String getTrickySpotsJson() { return trickySpotsJson; }
    void setTrickySpotsJson(String trickySpotsJson) { this.trickySpotsJson = trickySpotsJson; }

    String getTestMapJson() { return testMapJson; }
    void setTestMapJson(String testMapJson) { this.testMapJson = testMapJson; }

    String getFollowupsJson() { return followupsJson; }
    void setFollowupsJson(String followupsJson) { this.followupsJson = followupsJson; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }
}
