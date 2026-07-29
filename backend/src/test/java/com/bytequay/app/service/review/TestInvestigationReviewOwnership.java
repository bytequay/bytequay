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

import com.bytequay.app.domain.InvestigationReviewData;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    void standaloneFullReviewRequiresAWorkspaceRepositoryBinding()
    {
        PR pr = externalPr();
        when(store.findActiveReviewByPr(pr.id())).thenReturn(Optional.empty());
        when(prs.findById(pr.id())).thenReturn(Optional.of(pr));
        when(workspaces.listRepos(WORKSPACE_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.start(
                pr.id(), new InvestigationReviewService.StartOptions(null, null, WORKSPACE_ID)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).contains("must own this PR repository");
                });

        verify(store, never()).insertReview(any(), any());
        verify(threads, never()).saveThread(any());
    }

    @Test
    void standaloneFullReviewRequestsAFrozenReviewSessionSnapshot()
    {
        PR pr = externalPr();
        AgentReviewRow review = new AgentReviewRow(
                "review-1", pr.repo(), pr.id(), "base-sha", "head-sha",
                "ACTIVE", WORKSPACE_ID, null, null);
        ReviewSessionSnapshotRuntime snapshots = mock(
                ReviewSessionSnapshotRuntime.class);
        when(store.findActiveReviewByPr(pr.id())).thenReturn(Optional.empty());
        when(prs.findById(pr.id())).thenReturn(Optional.of(pr));
        when(workspaces.listRepos(WORKSPACE_ID)).thenReturn(List.of(
                new WorkspaceRepo(
                        WORKSPACE_ID, pr.repo(), "main", false, Instant.EPOCH)));
        when(snapshots.requestNew(
                eq(pr), eq(WORKSPACE_ID),
                eq(ReviewSessionSnapshotRuntime.Scope.FULL), any()))
                .thenReturn(review);
        service.setReviewAssignmentTurnRuntime(
                mock(ReviewAssignmentTurnRuntime.class));
        service.setReviewSessionSnapshots(snapshots);

        InvestigationReviewData result = service.start(
                pr.id(), new InvestigationReviewService.StartOptions(null, null, WORKSPACE_ID));

        assertThat(result.review()).isEqualTo(review);
        verify(snapshots).requestNew(
                eq(pr), eq(WORKSPACE_ID),
                eq(ReviewSessionSnapshotRuntime.Scope.FULL), any());
        verifyNoInteractions(contexts, runner);
        verify(threads, never()).saveThread(any());
        verify(threads, never()).findReviewTrunk(any(), any());
    }

    @Test
    void workspacePurgeCancelsStandaloneTypedRound()
    {
        AgentReviewRow review = new AgentReviewRow(
                "review-1", "acme/widget", "pr-1", "base-sha", "head-sha",
                "ACTIVE", WORKSPACE_ID, null, null);
        ReviewRoundRow round = new ReviewRoundRow(
                "round-1", review.id(), "run-1", "initial", "full",
                "head-sha", null, "RUNNING", new RoundBudget(50, 10), 0);
        ReviewAssignmentTurnRuntime typed = mock(ReviewAssignmentTurnRuntime.class);
        when(store.reviewsByWorkspace(WORKSPACE_ID)).thenReturn(List.of(review));
        when(store.rounds(review.id())).thenReturn(List.of(round));
        when(typed.ownsRound(round.id())).thenReturn(true);
        service.setReviewAssignmentTurnRuntime(typed);

        service.purgeByWorkspace(WORKSPACE_ID);

        verify(typed).cancelRound(round.id());
        verify(store).deleteReview(review.id());
    }

    private static PR externalPr()
    {
        return PR.createExternal(
                "pr-1", "acme/widget", 42, "https://example.test/acme/widget/pull/42",
                "octocat", "feature", "main", "Review this change", "",
                PR.STATUS_REMOTE_OPEN, Instant.EPOCH, null, null);
    }
}
