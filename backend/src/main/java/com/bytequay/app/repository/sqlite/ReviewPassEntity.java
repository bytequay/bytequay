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
@Table(name = "review_passes")
class ReviewPassEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "repo_full_name", nullable = false)
    private String repoFullName;

    @Column(name = "pr_number", nullable = false)
    private int prNumber;

    @Column(name = "head_sha")
    private String headSha;

    @Column(name = "phase", nullable = false)
    private String phase;

    @Column(name = "round", nullable = false)
    private int round;

    @Column(name = "round_cap", nullable = false)
    private int roundCap;

    @Column(name = "cost_cap_milli", nullable = false)
    private long costCapMilli;

    @Column(name = "cost_usd_milli", nullable = false)
    private long costUsdMilli;

    @Column(name = "verdict")
    private String verdict;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    @Column(name = "spawned_build_thread_id")
    private String spawnedBuildThreadId;

    @Column(name = "agenda_json")
    private String agendaJson;

    // Defaulted so inserts satisfy the NOT NULL columns; savePass never
    // maps host (set once via setPassHost) so an update can't clobber it.
    @Column(name = "host_kind", nullable = false)
    private String hostKind = "THREAD";

    @Column(name = "host_id", nullable = false)
    private String hostId = "";

    @Column(name = "kind", nullable = false)
    private String kind = "FRESH";

    /** REVIEW_STAGE link (V123); null for standalone passes. Written once
     *  via setPassTaskStage, never by savePass — same as host. */
    @Column(name = "task_stage_id")
    private String taskStageId;

    @Column(name = "base_repository_id")
    private String baseRepositoryId;

    @Column(name = "head_repository_id")
    private String headRepositoryId;

    @Column(name = "head_ref")
    private String headRef;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    String getRepoFullName() { return repoFullName; }
    void setRepoFullName(String repoFullName) { this.repoFullName = repoFullName; }

    int getPrNumber() { return prNumber; }
    void setPrNumber(int prNumber) { this.prNumber = prNumber; }

    String getHeadSha() { return headSha; }
    void setHeadSha(String headSha) { this.headSha = headSha; }

    String getPhase() { return phase; }
    void setPhase(String phase) { this.phase = phase; }

    int getRound() { return round; }
    void setRound(int round) { this.round = round; }

    int getRoundCap() { return roundCap; }
    void setRoundCap(int roundCap) { this.roundCap = roundCap; }

    long getCostCapMilli() { return costCapMilli; }
    void setCostCapMilli(long costCapMilli) { this.costCapMilli = costCapMilli; }

    long getCostUsdMilli() { return costUsdMilli; }
    void setCostUsdMilli(long costUsdMilli) { this.costUsdMilli = costUsdMilli; }

    String getVerdict() { return verdict; }
    void setVerdict(String verdict) { this.verdict = verdict; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getEndedAtMs() { return endedAtMs; }
    void setEndedAtMs(Long endedAtMs) { this.endedAtMs = endedAtMs; }

    String getAgendaJson() { return agendaJson; }

    void setAgendaJson(String agendaJson) { this.agendaJson = agendaJson; }

    String getSpawnedBuildThreadId() { return spawnedBuildThreadId; }
    void setSpawnedBuildThreadId(String spawnedBuildThreadId) { this.spawnedBuildThreadId = spawnedBuildThreadId; }

    String getHostKind() { return hostKind; }
    void setHostKind(String hostKind) { this.hostKind = hostKind; }

    String getHostId() { return hostId; }
    void setHostId(String hostId) { this.hostId = hostId; }

    String getKind() { return kind; }
    void setKind(String kind) { this.kind = kind; }

    String getTaskStageId() { return taskStageId; }
    void setTaskStageId(String taskStageId) { this.taskStageId = taskStageId; }

    String getBaseRepositoryId() { return baseRepositoryId; }
    void setBaseRepositoryId(String baseRepositoryId) { this.baseRepositoryId = baseRepositoryId; }

    String getHeadRepositoryId() { return headRepositoryId; }
    void setHeadRepositoryId(String headRepositoryId) { this.headRepositoryId = headRepositoryId; }

    String getHeadRef() { return headRef; }
    void setHeadRef(String headRef) { this.headRef = headRef; }
}
