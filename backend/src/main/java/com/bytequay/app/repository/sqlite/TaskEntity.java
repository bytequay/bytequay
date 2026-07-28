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

    @Column(name = "origin", nullable = false, updatable = false)
    private String origin = "user";

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

    @Column(name = "current_liveness_turn_id")
    private String currentLivenessTurnId;

    @Column(name = "paused_status")
    private String pausedStatus;

    @Column(name = "resume_requested_at_ms")
    private Long resumeRequestedAtMs;

    @Column(name = "recovery_phase")
    private String recoveryPhase;

    @Column(name = "recovery_context_json")
    private String recoveryContextJson;

    @Column(name = "recovery_request_id")
    private String recoveryRequestId;

    @Column(name = "recovery_requested_kind")
    private String recoveryRequestedKind;

    @Column(name = "recovery_request_payload_json")
    private String recoveryRequestPayloadJson;

    @Column(name = "recovery_requested_at_ms")
    private Long recoveryRequestedAtMs;

    @Column(name = "auto_approve", nullable = false)
    private boolean autoApprove;

    @Column(name = "auto_merge", nullable = false)
    private boolean autoMerge;

    @Column(name = "min_approvals", nullable = false)
    private int minApprovals;

    @Column(name = "pushed_at_ms")
    private Long pushedAtMs;

    // Defaulted so a freshly-inserted task (saveTask deliberately never
    // maps phase, to avoid clobbering it on a full-row update) satisfies
    // the NOT NULL column; the phase machine writes it via load-set-save.
    // Every task starts in PLANNING — the brain agent plans in the open
    // PlanStage and the DevelopmentStage only opens once the user approves.
    @Column(name = "phase", nullable = false)
    private String phase = "PLANNING";

    @Column(name = "agenda_json")
    private String agendaJson;

    @Column(name = "consecutive_auto_pushes", nullable = false)
    private int consecutiveAutoPushes;

    @Column(name = "linked_pr_ref")
    private String linkedPrRef;

    /** Opening-prompt accumulator for a queue-born task (V110). Nullable;
     *  written via the dedicated opening-prompt update path, never the
     *  full-row saveTask, so it survives a clobber. */
    @Column(name = "opening_prompt")
    private String openingPrompt;

    /** Ready-to-merge notify sentinel (V116 column). Set via atomic CAS the
     *  first time a monitor detects the ready state, cleared when a
     *  condition breaks. Entity-managed, never mapped by saveTask. */
    @Column(name = "merge_notification_sent_at_ms")
    private Long mergeNotificationSentAtMs;

    @Column(name = "ready_gate_sent_at_ms")
    private Long readyGateSentAtMs;

    /** When the user approved the "Approve &amp; merge" gate — standing consent
     *  to merge this PR, so the lifecycle re-enqueues automatically after a
     *  merge-queue bounce instead of re-prompting. Null until approved. */
    @Column(name = "merge_authorized_at_ms")
    private Long mergeAuthorizedAtMs;

    /** Count of silent auto re-enqueues after merge-queue bounces. */
    @Column(name = "merge_queue_retries")
    private int mergeQueueRetries;

    /** The brain's in-flight "summarize this task for the trunk" turn (V149),
     *  set when the task reaches COMPLETED and cleared once
     *  TaskCompletionAnnouncer picks up its finish event (or the
     *  stale-completion sweep gives up). Null the rest of the time. */
    @Column(name = "pending_completion_summary_turn_id")
    private String pendingCompletionSummaryTurnId;

    /** V2 aggregate fence. The database owns increments and creation's
     * default; legacy full-row saves must never overwrite it. */
    @Column(name = "epoch", nullable = false, insertable = false, updatable = false)
    private long epoch;

    @Column(name = "workflow_version", nullable = false, insertable = false, updatable = false)
    private String workflowVersion;

    String getId() { return id; }
    void setId(String id) { this.id = id; }

    long getEpoch() { return epoch; }

    String getWorkflowVersion() { return workflowVersion; }

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

    String getCurrentLivenessTurnId() { return currentLivenessTurnId; }
    void setCurrentLivenessTurnId(String currentLivenessTurnId)
    {
        this.currentLivenessTurnId = currentLivenessTurnId;
    }

    String getPausedStatus() { return pausedStatus; }
    void setPausedStatus(String pausedStatus) { this.pausedStatus = pausedStatus; }

    Long getResumeRequestedAtMs() { return resumeRequestedAtMs; }
    void setResumeRequestedAtMs(Long resumeRequestedAtMs)
    {
        this.resumeRequestedAtMs = resumeRequestedAtMs;
    }

    String getRecoveryPhase() { return recoveryPhase; }
    void setRecoveryPhase(String recoveryPhase) { this.recoveryPhase = recoveryPhase; }

    String getRecoveryContextJson() { return recoveryContextJson; }
    void setRecoveryContextJson(String recoveryContextJson)
    {
        this.recoveryContextJson = recoveryContextJson;
    }

    String getRecoveryRequestId() { return recoveryRequestId; }
    void setRecoveryRequestId(String recoveryRequestId)
    {
        this.recoveryRequestId = recoveryRequestId;
    }

    String getRecoveryRequestedKind() { return recoveryRequestedKind; }
    void setRecoveryRequestedKind(String recoveryRequestedKind)
    {
        this.recoveryRequestedKind = recoveryRequestedKind;
    }

    String getRecoveryRequestPayloadJson() { return recoveryRequestPayloadJson; }
    void setRecoveryRequestPayloadJson(String recoveryRequestPayloadJson)
    {
        this.recoveryRequestPayloadJson = recoveryRequestPayloadJson;
    }

    Long getRecoveryRequestedAtMs() { return recoveryRequestedAtMs; }
    void setRecoveryRequestedAtMs(Long recoveryRequestedAtMs)
    {
        this.recoveryRequestedAtMs = recoveryRequestedAtMs;
    }

    String getOrigin() { return origin; }
    void setOrigin(String origin) { this.origin = origin; }

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

    boolean isAutoApprove() { return autoApprove; }
    void setAutoApprove(boolean autoApprove) { this.autoApprove = autoApprove; }

    boolean isAutoMerge() { return autoMerge; }
    void setAutoMerge(boolean autoMerge) { this.autoMerge = autoMerge; }

    int getMinApprovals() { return minApprovals; }
    void setMinApprovals(int minApprovals) { this.minApprovals = minApprovals; }

    Long getPushedAtMs() { return pushedAtMs; }
    void setPushedAtMs(Long pushedAtMs) { this.pushedAtMs = pushedAtMs; }

    String getPhase() { return phase; }
    void setPhase(String phase) { this.phase = phase; }

    String getAgendaJson() { return agendaJson; }
    void setAgendaJson(String agendaJson) { this.agendaJson = agendaJson; }

    int getConsecutiveAutoPushes() { return consecutiveAutoPushes; }
    void setConsecutiveAutoPushes(int consecutiveAutoPushes) { this.consecutiveAutoPushes = consecutiveAutoPushes; }

    String getLinkedPrRef() { return linkedPrRef; }
    void setLinkedPrRef(String linkedPrRef) { this.linkedPrRef = linkedPrRef; }

    String getOpeningPrompt() { return openingPrompt; }
    void setOpeningPrompt(String openingPrompt) { this.openingPrompt = openingPrompt; }

    Long getMergeNotificationSentAtMs() { return mergeNotificationSentAtMs; }
    void setMergeNotificationSentAtMs(Long mergeNotificationSentAtMs) { this.mergeNotificationSentAtMs = mergeNotificationSentAtMs; }

    Long getMergeAuthorizedAtMs() { return mergeAuthorizedAtMs; }
    void setMergeAuthorizedAtMs(Long mergeAuthorizedAtMs) { this.mergeAuthorizedAtMs = mergeAuthorizedAtMs; }

    int getMergeQueueRetries() { return mergeQueueRetries; }
    void setMergeQueueRetries(int mergeQueueRetries) { this.mergeQueueRetries = mergeQueueRetries; }

    Long getReadyGateSentAtMs() { return readyGateSentAtMs; }
    void setReadyGateSentAtMs(Long readyGateSentAtMs) { this.readyGateSentAtMs = readyGateSentAtMs; }

    String getPendingCompletionSummaryTurnId() { return pendingCompletionSummaryTurnId; }
    void setPendingCompletionSummaryTurnId(String pendingCompletionSummaryTurnId) {
        this.pendingCompletionSummaryTurnId = pendingCompletionSummaryTurnId;
    }
}
