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
@Table(name = "tasks")
class TaskEntity
{
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "agent_session_id")
    private String agentSessionId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "working_dir", nullable = false)
    private String workingDir;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "cost_usd_milli", nullable = false)
    private long costUsdMilli;

    @Column(name = "tokens_in", nullable = false)
    private long tokensIn;

    @Column(name = "tokens_out", nullable = false)
    private long tokensOut;

    @Column(name = "process_pid")
    private Integer processPid;

    @Column(name = "log_path")
    private String logPath;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "updated_at_ms", nullable = false)
    private long updatedAtMs;

    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "metadata_json", nullable = false)
    private String metadataJson;

    @Column(name = "group_id")
    private String groupId;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getKind() { return kind; }
    void setKind(String kind) { this.kind = kind; }

    String getProvider() { return provider; }
    void setProvider(String provider) { this.provider = provider; }

    String getAgentSessionId() { return agentSessionId; }
    void setAgentSessionId(String agentSessionId) { this.agentSessionId = agentSessionId; }

    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    String getWorkingDir() { return workingDir; }
    void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

    String getBranchName() { return branchName; }
    void setBranchName(String branchName) { this.branchName = branchName; }

    String getModel() { return model; }
    void setModel(String model) { this.model = model; }

    long getCostUsdMilli() { return costUsdMilli; }
    void setCostUsdMilli(long costUsdMilli) { this.costUsdMilli = costUsdMilli; }

    long getTokensIn() { return tokensIn; }
    void setTokensIn(long tokensIn) { this.tokensIn = tokensIn; }

    long getTokensOut() { return tokensOut; }
    void setTokensOut(long tokensOut) { this.tokensOut = tokensOut; }

    Integer getProcessPid() { return processPid; }
    void setProcessPid(Integer processPid) { this.processPid = processPid; }

    String getLogPath() { return logPath; }
    void setLogPath(String logPath) { this.logPath = logPath; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    long getUpdatedAtMs() { return updatedAtMs; }
    void setUpdatedAtMs(long updatedAtMs) { this.updatedAtMs = updatedAtMs; }

    Long getEndedAtMs() { return endedAtMs; }
    void setEndedAtMs(Long endedAtMs) { this.endedAtMs = endedAtMs; }

    String getErrorMessage() { return errorMessage; }
    void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    String getMetadataJson() { return metadataJson; }
    void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    String getGroupId() { return groupId; }
    void setGroupId(String groupId) { this.groupId = groupId; }
}
