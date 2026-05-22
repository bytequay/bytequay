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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceMemoryProposal;
import com.bytequay.app.repository.WorkspaceMemoryProposalStore;
import com.bytequay.app.service.threads.CheckpointSummaryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Owns the propose/confirm lifecycle for workspace-memory edits.
 * {@link WorkspaceMemoryDistiller} writes through {@link #propose}
 * instead of replacing {@code memory_md} directly, and the user
 * resolves the pending proposal by calling {@link #apply} (writes the
 * proposed body back through {@link WorkspaceService#setMemory}) or
 * {@link #discard} (drops the row, no side effect).
 *
 * <p>Apply is drift-checked: the proposal carries the {@code memory_md}
 * value that was live when the proposal was generated, and the apply
 * step refuses (409) when the workspace's current {@code memory_md}
 * has diverged. That's how a user hand-edit between proposal-time and
 * apply-time is protected — the distiller can't clobber edits it
 * couldn't see when Haiku ran.
 */
@Service
public class WorkspaceMemoryProposalService
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceMemoryProposalService.class);

    private final WorkspaceService workspaces;
    private final WorkspaceMemoryProposalStore store;

    public WorkspaceMemoryProposalService(
            WorkspaceService workspaces,
            WorkspaceMemoryProposalStore store)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.store = requireNonNull(store, "store is null");
    }

    public Optional<WorkspaceMemoryProposal> find(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return store.findByWorkspaceId(workspaceId);
    }

    /**
     * Record a fresh proposal for {@code workspaceId}, upserting any
     * previous pending row. Returns empty (and writes nothing) when
     * the Haiku output is identical to the current memory — there's
     * nothing for the user to confirm.
     */
    public Optional<WorkspaceMemoryProposal> propose(
            String workspaceId, String currentMd, CheckpointSummaryResult result)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(currentMd, "currentMd is null");
        requireNonNull(result, "result is null");
        String proposedMd = result.summaryMd();
        if (proposedMd == null || proposedMd.equals(currentMd)) {
            // Nothing actionable to surface; clear any stale proposal
            // so the user doesn't see a row whose proposed body is
            // identical to what's already live.
            store.deleteByWorkspaceId(workspaceId);
            return Optional.empty();
        }
        WorkspaceMemoryProposal proposal = new WorkspaceMemoryProposal(
                workspaceId,
                currentMd,
                proposedMd,
                result.modelUsed(),
                result.promptTokens(),
                result.completionTokens(),
                result.costUsdMilli(),
                Instant.now());
        store.save(proposal);
        return Optional.of(proposal);
    }

    /**
     * Confirm the pending proposal — write {@code proposedMd} back to
     * the workspace and clear the row. Refuses with 409 when the
     * workspace's current {@code memory_md} has changed since the
     * proposal was generated (a user hand-edit landed in between);
     * the user then re-distills against the fresh memory.
     */
    public Workspace apply(String workspaceId)
    {
        WorkspaceMemoryProposal proposal = require(workspaceId);
        Workspace current = workspaces.require(workspaceId);
        if (!proposal.currentMd().equals(current.memoryMd())) {
            log.info("Refusing to apply workspace memory proposal for {}: "
                            + "memory_md changed since the proposal was generated",
                    workspaceId);
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "workspace memory has changed since this proposal was generated; "
                            + "re-distill to refresh the proposal");
        }
        Workspace updated = workspaces.setMemory(workspaceId, proposal.proposedMd());
        store.deleteByWorkspaceId(workspaceId);
        log.info("Applied workspace memory proposal for {} ({} chars)",
                workspaceId, proposal.proposedMd().length());
        return updated;
    }

    /** Drop the pending proposal without writing anything to the
     *  workspace. 404 when there's nothing pending — the UI should
     *  only surface Discard when a proposal exists. */
    public void discard(String workspaceId)
    {
        require(workspaceId);
        store.deleteByWorkspaceId(workspaceId);
        log.info("Discarded workspace memory proposal for {}", workspaceId);
    }

    private WorkspaceMemoryProposal require(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return store.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "no pending memory proposal for workspace " + workspaceId));
    }
}
