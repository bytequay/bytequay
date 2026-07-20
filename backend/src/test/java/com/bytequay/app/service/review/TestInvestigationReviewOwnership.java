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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewCapabilities;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewerDefRow;
import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.InvestigationReviewRunner.ProviderChoice;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestInvestigationReviewOwnership
{
    private static final String WORKSPACE_ID = "workspace-1";

    private final InvestigationReviewStore store = mock(InvestigationReviewStore.class);
    private final InvestigationReviewContext contexts = mock(InvestigationReviewContext.class);
    private final InvestigationReviewRunner runner = mock(InvestigationReviewRunner.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final PRService prs = mock(PRService.class);
    private final TaskStore tasks = mock(TaskStore.class);
    private final ThreadStore threads = mock(ThreadStore.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final InvestigationReviewService service = new InvestigationReviewService(
            store, contexts, runner, runs, prs, tasks, threads, mapper, workspaces);

    @Test
    void standaloneFullReviewRequiresAWorkspace()
    {
        PR pr = externalPr();
        when(store.findActiveReviewByPr(pr.id())).thenReturn(Optional.empty());
        when(prs.findById(pr.id())).thenReturn(Optional.of(pr));

        assertThatThrownBy(() -> service.start(
                pr.id(), new InvestigationReviewService.StartOptions(null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).contains("watched repository workspace");
                });

        verifyNoInteractions(contexts);
        verify(threads, never()).saveThread(any());
    }

    @Test
    void standaloneFullReviewRequiresTheWatchedCloneToContainTheReviewedCommit()
    {
        PR pr = externalPr();
        when(store.findActiveReviewByPr(pr.id())).thenReturn(Optional.empty());
        when(prs.findById(pr.id())).thenReturn(Optional.of(pr));
        when(workspaces.ownsVerifiedLocalRepo(WORKSPACE_ID, pr.repo())).thenReturn(true);
        when(contexts.load(pr, true)).thenReturn(new InvestigationReviewContext.Snapshot(
                pr, "base-sha", "head-sha", "", List.of(), null, null,
                ReviewCapabilities.remoteOnly()));

        assertThatThrownBy(() -> service.start(
                pr.id(), new InvestigationReviewService.StartOptions(null, null, WORKSPACE_ID)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).contains("reviewed commit");
                });

        verify(store, never()).insertReview(any(), any());
        verify(threads, never()).saveThread(any());
    }

    @Test
    void standaloneFullReviewOwnsTheRunThroughTheWorkspaceWithoutAReviewThread()
    {
        PR pr = externalPr();
        Path repositoryRoot = Path.of("/tmp/bytequay-review-workspace");
        InvestigationReviewContext.Snapshot snapshot = new InvestigationReviewContext.Snapshot(
                pr, "base-sha", "head-sha", "", List.of(), repositoryRoot, repositoryRoot,
                ReviewCapabilities.localSource());
        ReviewerDefRow reviewer = new ReviewerDefRow(
                "general-api", "General API", "General reviewer", "api",
                mapper.createObjectNode().put("provider", "auto"), null,
                List.of("trivial"), true);
        AgentRun detached = new AgentRun(
                "run-1", null, AgentRun.KIND_PANEL_REVIEW, null,
                null, "round-1", null, AgentRun.STATUS_RUNNING,
                0, 50, null, null, Instant.EPOCH, null);

        when(store.findActiveReviewByPr(pr.id())).thenReturn(Optional.empty());
        when(prs.findById(pr.id())).thenReturn(Optional.of(pr));
        when(workspaces.ownsVerifiedLocalRepo(WORKSPACE_ID, pr.repo())).thenReturn(true);
        when(contexts.load(pr, true)).thenReturn(snapshot);
        when(store.reviewerDefs()).thenReturn(List.of(reviewer));
        when(runner.choose(eq("api"), isNull()))
                .thenReturn(new ProviderChoice("openai", "api", "openai"));
        when(runs.openDetached(
                eq(AgentRun.KIND_PANEL_REVIEW), isNull(), any(), eq(50)))
                .thenReturn(detached);
        when(runs.attachOwnership(
                detached.id(), WORKSPACE_ID, null,
                "agent-review", "agent-review", "Review acme/widget#42"))
                .thenReturn(detached.withOwnership(
                        WORKSPACE_ID, null, "agent-review", "agent-review",
                        "Review acme/widget#42"));
        when(store.insertLiveRound(any(ReviewRoundRow.class), any(Instant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.start(
                pr.id(), new InvestigationReviewService.StartOptions(null, null, WORKSPACE_ID));

        ArgumentCaptor<AgentReviewRow> owner = ArgumentCaptor.forClass(AgentReviewRow.class);
        verify(store).insertReview(owner.capture(), any(Instant.class));
        assertThat(owner.getValue().workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(owner.getValue().ownerThreadId()).isNull();
        assertThat(owner.getValue().ownerTaskId()).isNull();
        verify(threads, never()).saveThread(any());
        verify(threads, never()).findReviewTrunk(any(), any());
        verify(runs).attachOwnership(
                detached.id(), WORKSPACE_ID, null,
                "agent-review", "agent-review", "Review acme/widget#42");
    }

    private static PR externalPr()
    {
        return PR.createExternal(
                "pr-1", "acme/widget", 42, "https://example.test/acme/widget/pull/42",
                "octocat", "feature", "main", "Review this change", "",
                PR.STATUS_REMOTE_OPEN, Instant.EPOCH, null, null);
    }
}
