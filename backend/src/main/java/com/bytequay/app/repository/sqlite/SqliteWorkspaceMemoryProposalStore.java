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

import com.bytequay.app.domain.WorkspaceMemoryProposal;
import com.bytequay.app.repository.WorkspaceMemoryProposalStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteWorkspaceMemoryProposalStore
        implements WorkspaceMemoryProposalStore
{
    private final WorkspaceMemoryProposalJpaRepository proposals;

    SqliteWorkspaceMemoryProposalStore(WorkspaceMemoryProposalJpaRepository proposals)
    {
        this.proposals = requireNonNull(proposals, "proposals is null");
    }

    @Override
    @Transactional
    public void save(WorkspaceMemoryProposal proposal)
    {
        WorkspaceMemoryProposalEntity entity = proposals.findById(proposal.workspaceId())
                .orElseGet(WorkspaceMemoryProposalEntity::new);
        entity.setWorkspaceId(proposal.workspaceId());
        entity.setCurrentMd(proposal.currentMd());
        entity.setProposedMd(proposal.proposedMd());
        entity.setSummariserModel(proposal.summariserModel());
        entity.setPromptTokens(proposal.promptTokens());
        entity.setCompletionTokens(proposal.completionTokens());
        entity.setCostUsdMilli(proposal.costUsdMilli());
        entity.setCreatedAtMs(proposal.createdAt().toEpochMilli());
        proposals.save(entity);
    }

    @Override
    public Optional<WorkspaceMemoryProposal> findByWorkspaceId(String workspaceId)
    {
        return proposals.findById(workspaceId).map(SqliteWorkspaceMemoryProposalStore::toDomain);
    }

    @Override
    @Transactional
    public void deleteByWorkspaceId(String workspaceId)
    {
        if (proposals.existsById(workspaceId)) {
            proposals.deleteById(workspaceId);
        }
    }

    private static WorkspaceMemoryProposal toDomain(WorkspaceMemoryProposalEntity e)
    {
        return new WorkspaceMemoryProposal(
                e.getWorkspaceId(),
                e.getCurrentMd(),
                e.getProposedMd(),
                e.getSummariserModel(),
                e.getPromptTokens(),
                e.getCompletionTokens(),
                e.getCostUsdMilli(),
                Instant.ofEpochMilli(e.getCreatedAtMs()));
    }
}
