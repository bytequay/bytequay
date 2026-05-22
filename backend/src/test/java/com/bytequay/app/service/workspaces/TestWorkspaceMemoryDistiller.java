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

import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceMemoryProposal;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.service.threads.CheckpointSummariser;
import com.bytequay.app.service.threads.CheckpointSummaryResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestWorkspaceMemoryDistiller
{
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final WorkspaceMemoryProposalService proposals = mock(WorkspaceMemoryProposalService.class);
    private final ThreadCheckpointStore checkpoints = mock(ThreadCheckpointStore.class);
    private final CheckpointSummariser summariser = mock(CheckpointSummariser.class);
    private final WorkspaceMemoryDistiller distiller =
            new WorkspaceMemoryDistiller(workspaces, proposals, checkpoints, summariser);

    @Test
    void distilQueuesAProposalRatherThanWritingMemoryDirectly()
    {
        // Phase 3 acceptance: "distillation proposes (doesn't silently
        // overwrite)". The distiller must never call setMemory; it
        // hands the summariser output to the proposal service so the
        // user gets a confirm step.
        Workspace before = newWorkspace("ws-1", "Current memory text.", /* scratch */ false);
        when(workspaces.require("ws-1")).thenReturn(before);
        when(checkpoints.listAllActiveOveralls(anyInt())).thenReturn(List.of(
                overall("thread-a", "Built the upload pipeline."),
                overall("thread-b", "Resolved the rate-limit incident.")));
        CheckpointSummaryResult fresh = new CheckpointSummaryResult(
                "## Architecture\n…distilled blob…", List.of("Architecture", "Active work"),
                "claude-haiku-4-5", 1_000L, 400L, 3L);
        when(summariser.distilWorkspaceMemory(eq("Current memory text."), any()))
                .thenReturn(fresh);
        WorkspaceMemoryProposal queued = new WorkspaceMemoryProposal(
                "ws-1", before.memoryMd(), fresh.summaryMd(),
                "claude-haiku-4-5", 1_000L, 400L, 3L,
                Instant.parse("2026-05-22T12:00:00Z"));
        when(proposals.propose(eq("ws-1"), eq("Current memory text."), eq(fresh)))
                .thenReturn(Optional.of(queued));

        Optional<WorkspaceMemoryProposal> result = distiller.distill("ws-1");

        assertThat(result).contains(queued);
        verify(proposals).propose(eq("ws-1"), eq("Current memory text."), eq(fresh));
        // Critical guard against the silent-overwrite regression the
        // reviewer flagged: setMemory must never be called from
        // distill().
        verify(workspaces, never()).setMemory(anyString(), anyString());
    }

    @Test
    void distilIsANoOpForScratchWorkspaces()
    {
        when(workspaces.require("ws-scratch"))
                .thenReturn(newWorkspace("ws-scratch", "", /* scratch */ true));

        Optional<WorkspaceMemoryProposal> result = distiller.distill("ws-scratch");

        assertThat(result).isEmpty();
        verifyNoInteractions(checkpoints, summariser, proposals);
    }

    @Test
    void distilIsANoOpWhenNoThreadOverallsExist()
    {
        when(workspaces.require("ws-1"))
                .thenReturn(newWorkspace("ws-1", "Existing memory.", false));
        when(checkpoints.listAllActiveOveralls(anyInt())).thenReturn(List.of());

        Optional<WorkspaceMemoryProposal> result = distiller.distill("ws-1");

        assertThat(result).isEmpty();
        verify(summariser, never()).distilWorkspaceMemory(anyString(), any());
        verifyNoInteractions(proposals);
    }

    @Test
    void distilAllIteratesEveryWorkspaceAndSurvivesIndividualFailures()
    {
        Workspace one = newWorkspace("ws-1", "m", false);
        Workspace two = newWorkspace("ws-2", "m", false);
        when(workspaces.list()).thenReturn(List.of(one, two));
        when(workspaces.require("ws-1"))
                .thenThrow(new RuntimeException("workspace lookup blew up"));
        when(workspaces.require("ws-2")).thenReturn(two);
        when(checkpoints.listAllActiveOveralls(anyInt())).thenReturn(List.of(
                overall("thread-x", "Resolved a deploy regression.")));
        CheckpointSummaryResult ok = new CheckpointSummaryResult(
                "ok", List.of(), "claude-haiku-4-5", 0L, 0L, 0L);
        when(summariser.distilWorkspaceMemory(anyString(), any())).thenReturn(ok);

        distiller.distillAll();

        // First workspace blew up but the second still got its
        // distillation pass routed through the proposal queue.
        verify(proposals, times(1)).propose(eq("ws-2"), eq("m"), eq(ok));
    }

    @Test
    void distilPassesTheCurrentMemoryAndCorpusVerbatim()
    {
        Workspace before = newWorkspace("ws-1", "PREV", false);
        when(workspaces.require("ws-1")).thenReturn(before);
        ThreadCheckpoint a = overall("thread-a", "Body A");
        ThreadCheckpoint b = overall("thread-b", "Body B");
        when(checkpoints.listAllActiveOveralls(anyInt())).thenReturn(List.of(a, b));
        CheckpointSummaryResult next = new CheckpointSummaryResult(
                "NEXT", List.of(), "claude-haiku-4-5", 0L, 0L, 0L);
        when(summariser.distilWorkspaceMemory(anyString(), any())).thenReturn(next);

        distiller.distill("ws-1");

        ArgumentCaptor<String> mem = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ThreadCheckpoint>> corpus =
                ArgumentCaptor.forClass((Class<List<ThreadCheckpoint>>) (Class<?>) List.class);
        verify(summariser).distilWorkspaceMemory(mem.capture(), corpus.capture());
        assertThat(mem.getValue()).isEqualTo("PREV");
        assertThat(corpus.getValue()).containsExactly(a, b);
        verify(proposals).propose(eq("ws-1"), eq("PREV"), eq(next));
    }

    private static ThreadCheckpoint overall(String threadId, String summary)
    {
        return new ThreadCheckpoint(
                threadId + "-cp", threadId, /* seq */ 0L, /* isOverall */ true,
                /* firstMsgSeq */ 1L, /* lastMsgSeq */ 10L,
                /* tokensCovered */ 25_000L,
                summary, List.of(), "claude-haiku-4-5",
                1_000L, 200L, 1L,
                Instant.parse("2026-05-15T12:00:00Z"),
                /* supersededAt */ null,
                /* taskId — Overall always thread-scoped */ null);
    }

    private static Workspace newWorkspace(String id, String memory, boolean scratch)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Workspace(id, "ByteQuay", memory, scratch, now, now);
    }
}
