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

/** JPA row for an {@code agent_run}. */
@Entity
@Table(name = "agent_run")
class AgentRunEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "source")
    private String source;

    @Column(name = "parent_stage_id")
    private String parentStageId;

    @Column(name = "review_round_id")
    private String reviewRoundId;

    @Column(name = "stage_id")
    private String stageId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "iterations", nullable = false)
    private int iterations;

    @Column(name = "budget")
    private Integer budget;

    @Column(name = "headline")
    private String headline;

    @Column(name = "metrics_json")
    private String metricsJson;

    @Column(name = "started_at_ms", nullable = false)
    private long startedAtMs;

    @Column(name = "finished_at_ms")
    private Long finishedAtMs;

    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(name = "thread_id")
    private String threadId;

    @Column(name = "provider")
    private String provider;

    @Column(name = "model")
    private String model;

    @Column(name = "cost_usd_milli", nullable = false)
    private long costUsdMilli;

    @Column(name = "tokens_in", nullable = false)
    private long tokensIn;

    @Column(name = "tokens_out", nullable = false)
    private long tokensOut;

    @Column(name = "step_cursor", nullable = false)
    private int stepCursor;

    @Column(name = "launch_input")
    private String launchInput;

    @Column(name = "pause_reason")
    private String pauseReason;

    @Column(name = "outcome")
    private String outcome;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getKind() { return kind; }
    void setKind(String kind) { this.kind = kind; }

    String getSource() { return source; }
    void setSource(String source) { this.source = source; }

    String getParentStageId() { return parentStageId; }
    void setParentStageId(String parentStageId) { this.parentStageId = parentStageId; }

    String getReviewRoundId() { return reviewRoundId; }
    void setReviewRoundId(String reviewRoundId) { this.reviewRoundId = reviewRoundId; }

    String getStageId() { return stageId; }
    void setStageId(String stageId) { this.stageId = stageId; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    int getIterations() { return iterations; }
    void setIterations(int iterations) { this.iterations = iterations; }

    Integer getBudget() { return budget; }
    void setBudget(Integer budget) { this.budget = budget; }

    String getHeadline() { return headline; }
    void setHeadline(String headline) { this.headline = headline; }

    String getMetricsJson() { return metricsJson; }
    void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }

    long getStartedAtMs() { return startedAtMs; }
    void setStartedAtMs(long startedAtMs) { this.startedAtMs = startedAtMs; }

    Long getFinishedAtMs() { return finishedAtMs; }
    void setFinishedAtMs(Long finishedAtMs) { this.finishedAtMs = finishedAtMs; }

    String getWorkspaceId() { return workspaceId; }
    void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    String getProvider() { return provider; }
    void setProvider(String provider) { this.provider = provider; }

    String getModel() { return model; }
    void setModel(String model) { this.model = model; }

    long getCostUsdMilli() { return costUsdMilli; }
    void setCostUsdMilli(long costUsdMilli) { this.costUsdMilli = costUsdMilli; }

    long getTokensIn() { return tokensIn; }
    void setTokensIn(long tokensIn) { this.tokensIn = tokensIn; }

    long getTokensOut() { return tokensOut; }
    void setTokensOut(long tokensOut) { this.tokensOut = tokensOut; }

    int getStepCursor() { return stepCursor; }
    void setStepCursor(int stepCursor) { this.stepCursor = stepCursor; }

    String getLaunchInput() { return launchInput; }
    void setLaunchInput(String launchInput) { this.launchInput = launchInput; }

    String getPauseReason() { return pauseReason; }
    void setPauseReason(String pauseReason) { this.pauseReason = pauseReason; }

    String getOutcome() { return outcome; }
    void setOutcome(String outcome) { this.outcome = outcome; }
}
