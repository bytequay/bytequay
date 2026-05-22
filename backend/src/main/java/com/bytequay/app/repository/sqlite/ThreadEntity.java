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
@Table(name = "threads")
class ThreadEntity
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

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "cost_usd_milli", nullable = false)
    private long costUsdMilli;

    @Column(name = "tokens_in", nullable = false)
    private long tokensIn;

    @Column(name = "tokens_out", nullable = false)
    private long tokensOut;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    @Column(name = "updated_at_ms", nullable = false)
    private long updatedAtMs;

    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    @Column(name = "error_message")
    private String errorMessage;

    // FK on workspaces.id, added in V73. Every existing thread was
    // backfilled to the ambient "ws-default" workspace by that
    // migration; SqliteThreadStore defaults it to the same value on
    // INSERT so new rows don't drift to NULL. Multi-workspace
    // creation will route this through the create request later.
    @Column(name = "workspace_id")
    private String workspaceId;

    // Structural discriminator: 'build' or 'review'. V74 carries the
    // column in NOT NULL DEFAULT 'build'; the store sets this only on
    // INSERT so the saveThread update path can't silently flip flow
    // on an existing row.
    @Column(name = "flow", nullable = false)
    private String flow;

    // Dropped in V72 (moved to the tasks table):
    //   working_dir, branch_name, local_branch, worktree_path,
    //   process_pid, log_path, task_type, linked_pr_number,
    //   linked_issue_number, metadata_json.
    // The legacy `threads.group_id` column still exists in the schema
    // (V57) but is no longer mapped — membership moved to the
    // `thread_group_members` join table in V59.

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

    String getModel() { return model; }
    void setModel(String model) { this.model = model; }

    long getCostUsdMilli() { return costUsdMilli; }
    void setCostUsdMilli(long costUsdMilli) { this.costUsdMilli = costUsdMilli; }

    long getTokensIn() { return tokensIn; }
    void setTokensIn(long tokensIn) { this.tokensIn = tokensIn; }

    long getTokensOut() { return tokensOut; }
    void setTokensOut(long tokensOut) { this.tokensOut = tokensOut; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }

    long getUpdatedAtMs() { return updatedAtMs; }
    void setUpdatedAtMs(long updatedAtMs) { this.updatedAtMs = updatedAtMs; }

    Long getEndedAtMs() { return endedAtMs; }
    void setEndedAtMs(Long endedAtMs) { this.endedAtMs = endedAtMs; }

    String getErrorMessage() { return errorMessage; }
    void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    String getWorkspaceId() { return workspaceId; }
    void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    String getFlow() { return flow; }
    void setFlow(String flow) { this.flow = flow; }
}
