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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorkspaceMemoryProposalService
{
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final InMemoryProposalStore store = new InMemoryProposalStore();
    private final WorkspaceMemoryProposalService service =
            new WorkspaceMemoryProposalService(workspaces, store);

    @Test
    void proposeStoresTheUpsertWhenHaikuOutputDiffersFromCurrentMemory()
    {
        CheckpointSummaryResult result = summaryResult("## Architecture\n…fresh…");

        Optional<WorkspaceMemoryProposal> queued = service.propose(
                "ws-1", "Current memory text.", result);

        assertThat(queued).isPresent();
        WorkspaceMemoryProposal saved = store.rows.get("ws-1");
        assertThat(saved).isNotNull();
        assertThat(saved.currentMd()).isEqualTo("Current memory text.");
        assertThat(saved.proposedMd()).isEqualTo("## Architecture\n…fresh…");
        assertThat(saved.summariserModel()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void proposeReplacesAnyPreviousProposal()
    {
        service.propose("ws-1", "Current.", summaryResult("Proposed v1"));
        service.propose("ws-1", "Current.", summaryResult("Proposed v2"));

        assertThat(store.rows).hasSize(1);
        assertThat(store.rows.get("ws-1").proposedMd()).isEqualTo("Proposed v2");
    }

    @Test
    void proposeClearsAnyStaleProposalWhenHaikuOutputMatchesCurrentMemory()
    {
        // Seed an existing pending proposal so we can prove the
        // no-change case wipes it (otherwise the user would see an
        // out-of-date row whose proposal already matched live memory).
        store.rows.put("ws-1", proposal("ws-1", "old current", "stale proposal"));

        Optional<WorkspaceMemoryProposal> queued = service.propose(
                "ws-1", "Current.", summaryResult("Current."));

        assertThat(queued).isEmpty();
        assertThat(store.rows).doesNotContainKey("ws-1");
    }

    @Test
    void applyWritesProposedMdAndClearsTheRowWhenCurrentMemoryMatches()
    {
        store.rows.put("ws-1", proposal("ws-1", "Current.", "Approved memory."));
        when(workspaces.require("ws-1")).thenReturn(workspace("ws-1", "Current."));
        Workspace updated = workspace("ws-1", "Approved memory.");
        when(workspaces.setMemory(eq("ws-1"), eq("Approved memory."))).thenReturn(updated);

        Workspace applied = service.apply("ws-1");

        assertThat(applied).isEqualTo(updated);
        assertThat(store.rows).doesNotContainKey("ws-1");
        verify(workspaces).setMemory(eq("ws-1"), eq("Approved memory."));
    }

    @Test
    void applyRefusesWhenMemoryHasDriftedSinceTheProposalWasGenerated()
    {
        // This is the hand-edit guard. The user opened
        // WorkspaceMemoryPage, typed something, and saved
        // ("Hand-edited.") between the distiller queuing the proposal
        // and the user clicking Apply. Without the check the proposal
        // would clobber the edit — which is exactly the silent-
        // overwrite the design forbade.
        store.rows.put("ws-1", proposal("ws-1", "Current.", "Approved memory."));
        when(workspaces.require("ws-1")).thenReturn(workspace("ws-1", "Hand-edited."));

        assertThatThrownBy(() -> service.apply("ws-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("memory has changed since this proposal");
        // The proposal stays around so the user can re-distill /
        // re-review against the fresh memory.
        assertThat(store.rows).containsKey("ws-1");
        verify(workspaces, never()).setMemory(eq("ws-1"), eq("Approved memory."));
    }

    @Test
    void applyThrows404WhenNoProposalExists()
    {
        assertThatThrownBy(() -> service.apply("ws-empty"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no pending memory proposal");
        verify(workspaces, never()).setMemory(eq("ws-empty"), eq("anything"));
    }

    @Test
    void discardDropsTheProposalWithoutTouchingMemory()
    {
        store.rows.put("ws-1", proposal("ws-1", "Current.", "Discarded body."));

        service.discard("ws-1");

        assertThat(store.rows).doesNotContainKey("ws-1");
        verify(workspaces, never()).setMemory(eq("ws-1"), eq("anything"));
    }

    @Test
    void discardThrows404WhenNoProposalExists()
    {
        assertThatThrownBy(() -> service.discard("ws-empty"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void findReturnsTheStoredProposalWhenOnePending()
    {
        WorkspaceMemoryProposal queued = proposal("ws-1", "Current.", "Pending body.");
        store.rows.put("ws-1", queued);

        ArgumentCaptor<String> nothing = ArgumentCaptor.forClass(String.class);
        assertThat(service.find("ws-1")).contains(queued);
        verify(workspaces, never()).require(nothing.capture());
    }

    private static CheckpointSummaryResult summaryResult(String summaryMd)
    {
        return new CheckpointSummaryResult(
                summaryMd, List.of(), "claude-haiku-4-5", 1_000L, 400L, 3L);
    }

    private static WorkspaceMemoryProposal proposal(
            String workspaceId, String currentMd, String proposedMd)
    {
        return new WorkspaceMemoryProposal(
                workspaceId, currentMd, proposedMd,
                "claude-haiku-4-5", 1_000L, 400L, 3L,
                Instant.parse("2026-05-22T12:00:00Z"));
    }

    private static Workspace workspace(String id, String memoryMd)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Workspace(id, "ByteQuay", memoryMd, /* isScratch */ false, now, now);
    }

    private static final class InMemoryProposalStore
            implements WorkspaceMemoryProposalStore
    {
        private final Map<String, WorkspaceMemoryProposal> rows = new HashMap<>();

        @Override
        public void save(WorkspaceMemoryProposal proposal)
        {
            rows.put(proposal.workspaceId(), proposal);
        }

        @Override
        public Optional<WorkspaceMemoryProposal> findByWorkspaceId(String workspaceId)
        {
            return Optional.ofNullable(rows.get(workspaceId));
        }

        @Override
        public void deleteByWorkspaceId(String workspaceId)
        {
            rows.remove(workspaceId);
        }
    }
}
