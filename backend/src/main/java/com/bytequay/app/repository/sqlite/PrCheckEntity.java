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

/** JPA row for a {@code pr_check}. */
@Entity
@Table(name = "pr_check")
class PrCheckEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "pr_id", nullable = false)
    private String prId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "started_at_ms", nullable = false)
    private long startedAtMs;

    @Column(name = "finished_at_ms")
    private Long finishedAtMs;

    @Column(name = "run_id")
    private String runId;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getPrId() { return prId; }
    void setPrId(String prId) { this.prId = prId; }

    String getKind() { return kind; }
    void setKind(String kind) { this.kind = kind; }

    String getName() { return name; }
    void setName(String name) { this.name = name; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    Long getDurationMs() { return durationMs; }
    void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    long getStartedAtMs() { return startedAtMs; }
    void setStartedAtMs(long startedAtMs) { this.startedAtMs = startedAtMs; }

    Long getFinishedAtMs() { return finishedAtMs; }
    void setFinishedAtMs(Long finishedAtMs) { this.finishedAtMs = finishedAtMs; }

    String getRunId() { return runId; }
    void setRunId(String runId) { this.runId = runId; }
}
