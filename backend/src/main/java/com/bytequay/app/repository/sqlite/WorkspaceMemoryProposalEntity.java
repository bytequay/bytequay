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
@Table(name = "workspace_memory_proposals")
class WorkspaceMemoryProposalEntity
{
    @Id
    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "current_md", nullable = false)
    private String currentMd;

    @Column(name = "proposed_md", nullable = false)
    private String proposedMd;

    @Column(name = "summariser_model", nullable = false)
    private String summariserModel;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "cost_usd_milli", nullable = false)
    private long costUsdMilli;

    @Column(name = "created_at_ms", nullable = false)
    private long createdAtMs;

    String getWorkspaceId() { return workspaceId; }
    void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    String getCurrentMd() { return currentMd; }
    void setCurrentMd(String currentMd) { this.currentMd = currentMd; }

    String getProposedMd() { return proposedMd; }
    void setProposedMd(String proposedMd) { this.proposedMd = proposedMd; }

    String getSummariserModel() { return summariserModel; }
    void setSummariserModel(String summariserModel) { this.summariserModel = summariserModel; }

    long getPromptTokens() { return promptTokens; }
    void setPromptTokens(long promptTokens) { this.promptTokens = promptTokens; }

    long getCompletionTokens() { return completionTokens; }
    void setCompletionTokens(long completionTokens) { this.completionTokens = completionTokens; }

    long getCostUsdMilli() { return costUsdMilli; }
    void setCostUsdMilli(long costUsdMilli) { this.costUsdMilli = costUsdMilli; }

    long getCreatedAtMs() { return createdAtMs; }
    void setCreatedAtMs(long createdAtMs) { this.createdAtMs = createdAtMs; }
}
