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
@Table(name = "worktree_leases")
class WorktreeLeaseEntity
{
    @Id
    @Column(name = "worktree_path", nullable = false)
    private String worktreePath;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "agent_kind", nullable = false)
    private String agentKind;

    @Column(name = "holder_pid")
    private Integer holderPid;

    @Column(name = "acquired_at_ms", nullable = false)
    private long acquiredAtMs;

    @Column(name = "expires_at_ms")
    private Long expiresAtMs;

    @Column(name = "workflow_version", nullable = false)
    private String workflowVersion = "LEGACY";

    String getWorktreePath() { return worktreePath; }
    void setWorktreePath(String worktreePath) { this.worktreePath = worktreePath; }

    String getTaskId() { return taskId; }
    void setTaskId(String taskId) { this.taskId = taskId; }

    String getAgentKind() { return agentKind; }
    void setAgentKind(String agentKind) { this.agentKind = agentKind; }

    Integer getHolderPid() { return holderPid; }
    void setHolderPid(Integer holderPid) { this.holderPid = holderPid; }

    long getAcquiredAtMs() { return acquiredAtMs; }
    void setAcquiredAtMs(long acquiredAtMs) { this.acquiredAtMs = acquiredAtMs; }

    Long getExpiresAtMs() { return expiresAtMs; }
    void setExpiresAtMs(Long expiresAtMs) { this.expiresAtMs = expiresAtMs; }

    String getWorkflowVersion() { return workflowVersion; }
    void setWorkflowVersion(String workflowVersion) { this.workflowVersion = workflowVersion; }
}
