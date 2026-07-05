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

/** JPA row for a {@code review_round}. */
@Entity
@Table(name = "review_round")
class ReviewRoundEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "idx", nullable = false)
    private int idx;

    @Column(name = "reviewers_json")
    private String reviewersJson;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "stats_json")
    private String statsJson;

    @Column(name = "run_id")
    private String runId;

    @Column(name = "opened_at_ms", nullable = false)
    private long openedAtMs;

    @Column(name = "gated_at_ms")
    private Long gatedAtMs;

    @Column(name = "posted_at_ms")
    private Long postedAtMs;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "brain_verdict")
    private String brainVerdict;

    @Column(name = "iteration", nullable = false)
    private int iteration;

    @Column(name = "budget", nullable = false)
    private int budget;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    int getIdx() { return idx; }
    void setIdx(int idx) { this.idx = idx; }

    String getReviewersJson() { return reviewersJson; }
    void setReviewersJson(String reviewersJson) { this.reviewersJson = reviewersJson; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    String getStatsJson() { return statsJson; }
    void setStatsJson(String statsJson) { this.statsJson = statsJson; }

    String getRunId() { return runId; }
    void setRunId(String runId) { this.runId = runId; }

    long getOpenedAtMs() { return openedAtMs; }
    void setOpenedAtMs(long openedAtMs) { this.openedAtMs = openedAtMs; }

    Long getGatedAtMs() { return gatedAtMs; }
    void setGatedAtMs(Long gatedAtMs) { this.gatedAtMs = gatedAtMs; }

    Long getPostedAtMs() { return postedAtMs; }
    void setPostedAtMs(Long postedAtMs) { this.postedAtMs = postedAtMs; }

    String getOrigin() { return origin; }
    void setOrigin(String origin) { this.origin = origin; }

    String getBrainVerdict() { return brainVerdict; }
    void setBrainVerdict(String brainVerdict) { this.brainVerdict = brainVerdict; }

    int getIteration() { return iteration; }
    void setIteration(int iteration) { this.iteration = iteration; }

    int getBudget() { return budget; }
    void setBudget(int budget) { this.budget = budget; }
}
