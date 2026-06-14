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

    @Column(name = "thread_id", nullable = false)
    private String threadId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "worktree_path")
    private String worktreePath;

    @Column(name = "base_branch")
    private String baseBranch;

    @Column(name = "working_dir")
    private String workingDir;

    @Column(name = "process_pid")
    private Integer processPid;

    @Column(name = "log_path")
    private String logPath;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_state")
    private String prState;

    @Column(name = "ci_state")
    private String ciState;

    @Column(name = "task_type", nullable = false)
    private String taskType;

    @Column(name = "linked_pr_number")
    private Integer linkedPrNumber;

    @Column(name = "linked_issue_number")
    private Integer linkedIssueNumber;

    @Column(name = "cost_usd_milli", nullable = false)
    private long costUsdMilli;

    @Column(name = "tokens_in", nullable = false)
    private long tokensIn;

    @Column(name = "tokens_out", nullable = false)
    private long tokensOut;

    @Column(name = "agent_session_id")
    private String agentSessionId;

    @Column(name = "name")
    private String name;

    @Column(name = "role_skill", columnDefinition = "TEXT")
    private String roleSkill;

    /** Raw JSON for the task's per-task work-model override. Nullable
     *  — the resolver treats absent as "fall back to the thread pick".
     *  See V96 + {@link com.bytequay.app.domain.WorkModel}. */
    @Column(name = "work_model_json")
    private String workModelJson;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "accept_edits", nullable = false)
    private boolean acceptEdits;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    String getThreadId() { return threadId; }
    void setThreadId(String threadId) { this.threadId = threadId; }

    long getSeq() { return seq; }
    void setSeq(long seq) { this.seq = seq; }

    String getStatus() { return status; }
    void setStatus(String status) { this.status = status; }

    String getBranchName() { return branchName; }
    void setBranchName(String branchName) { this.branchName = branchName; }

    String getWorktreePath() { return worktreePath; }
    void setWorktreePath(String worktreePath) { this.worktreePath = worktreePath; }

    String getBaseBranch() { return baseBranch; }
    void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }

    String getWorkingDir() { return workingDir; }
    void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

    Integer getProcessPid() { return processPid; }
    void setProcessPid(Integer processPid) { this.processPid = processPid; }

    String getLogPath() { return logPath; }
    void setLogPath(String logPath) { this.logPath = logPath; }

    Integer getPrNumber() { return prNumber; }
    void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }

    String getPrState() { return prState; }
    void setPrState(String prState) { this.prState = prState; }

    String getCiState() { return ciState; }
    void setCiState(String ciState) { this.ciState = ciState; }

    String getTaskType() { return taskType; }
    void setTaskType(String taskType) { this.taskType = taskType; }

    Integer getLinkedPrNumber() { return linkedPrNumber; }
    void setLinkedPrNumber(Integer linkedPrNumber) { this.linkedPrNumber = linkedPrNumber; }

    Integer getLinkedIssueNumber() { return linkedIssueNumber; }
    void setLinkedIssueNumber(Integer linkedIssueNumber) { this.linkedIssueNumber = linkedIssueNumber; }

    long getCostUsdMilli() { return costUsdMilli; }
    void setCostUsdMilli(long costUsdMilli) { this.costUsdMilli = costUsdMilli; }

    long getTokensIn() { return tokensIn; }
    void setTokensIn(long tokensIn) { this.tokensIn = tokensIn; }

    long getTokensOut() { return tokensOut; }
    void setTokensOut(long tokensOut) { this.tokensOut = tokensOut; }

    String getAgentSessionId() { return agentSessionId; }
    void setAgentSessionId(String agentSessionId) { this.agentSessionId = agentSessionId; }

    String getName() { return name; }
    void setName(String name) { this.name = name; }

    String getRoleSkill() { return roleSkill; }
    void setRoleSkill(String roleSkill) { this.roleSkill = roleSkill; }

    String getWorkModelJson() { return workModelJson; }
    void setWorkModelJson(String workModelJson) { this.workModelJson = workModelJson; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    Long getEndedAtMs() { return endedAtMs; }
    void setEndedAtMs(Long endedAtMs) { this.endedAtMs = endedAtMs; }

    String getErrorMessage() { return errorMessage; }
    void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    boolean isAcceptEdits() { return acceptEdits; }
    void setAcceptEdits(boolean acceptEdits) { this.acceptEdits = acceptEdits; }
}
