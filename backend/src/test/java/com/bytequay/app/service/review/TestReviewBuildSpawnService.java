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

import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewBuildSpawnService
{
    private ReviewStore reviewStore;
    private PullRequestRepository pullRequests;
    private PatResolver patResolver;
    private ThreadStore threadStore;
    private ThreadService threadService;
    private WorkspaceService workspaceService;
    private WatchedRepoStore watchedRepos;
    private GitRunner git;
    private ReviewBuildSpawnService service;

    private static final String REPO = "acme/widget";
    private static final int PR = 42;

    @BeforeEach
    void setUp()
    {
        reviewStore = mock(ReviewStore.class);
        pullRequests = mock(PullRequestRepository.class);
        patResolver = mock(PatResolver.class);
        threadStore = mock(ThreadStore.class);
        threadService = mock(ThreadService.class);
        workspaceService = mock(WorkspaceService.class);
        watchedRepos = mock(WatchedRepoStore.class);
        git = mock(GitRunner.class);
        service = new ReviewBuildSpawnService(reviewStore, pullRequests, patResolver,
                threadStore, threadService, workspaceService, watchedRepos, git);

        when(patResolver.resolve(REPO)).thenReturn("pat");
        when(pullRequests.fetchPrDetail(eq("pat"), any(PullRequestRef.class))).thenReturn(rawDetail());
        when(pullRequests.fetchUserProfile("pat")).thenReturn(profile("alice"));
        when(watchedRepos.find("acme", "widget"))
                .thenReturn(Optional.of(new WatchedRepo(1, "acme", "widget", 0, "/clones/widget", null, null)));
        when(threadService.create(any())).thenReturn(buildThread("thread-new"));
        when(workspaceService.listRepos("ws-1")).thenReturn(List.of(
                new WorkspaceRepo("ws-1", REPO, "main", false, Instant.EPOCH)));
        when(pullRequests.getPullRequest(eq("pat"), any(PullRequestRef.class))).thenReturn(pr("alice"));
    }

    // ── gating ───────────────────────────────────────────────────────

    @Test
    void rejectsWhenPassIsNotTerminate()
    {
        when(reviewStore.findPassById("p")).thenReturn(Optional.of(pass(ReviewPhase.ARBITRATE, null)));
        assertThatThrownBy(() -> service.spawn("p", "ws-1", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not TERMINATE");
    }

    @Test
    void rejectsWhenAlreadySpawned()
    {
        when(reviewStore.findPassById("p")).thenReturn(Optional.of(pass(ReviewPhase.TERMINATE, "thread-old")));
        assertThatThrownBy(() -> service.spawn("p", "ws-1", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already spawned");
    }

    @Test
    void rejectsWhenNoEligibleAgreedMajorFinding()
    {
        when(reviewStore.findPassById("p")).thenReturn(Optional.of(pass(ReviewPhase.TERMINATE, null)));
        // An AGREED nit and a DISPUTED blocker — neither is AGREED + >= MAJOR.
        when(reviewStore.listFindingsForPass("p")).thenReturn(List.of(
                finding("f1", ReviewFindingSeverity.NIT, ReviewFindingStatus.AGREED, "nit", null),
                finding("f2", ReviewFindingSeverity.BLOCKER, ReviewFindingStatus.DISPUTED, "[Claude] big", null)));
        assertThatThrownBy(() -> service.spawn("p", "ws-1", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no_eligible_findings");
    }

    @Test
    void rejectsWhenNoWorkspaceWatchesTheRepo()
    {
        eligiblePass();
        when(workspaceService.list()).thenReturn(List.of());
        assertThatThrownBy(() -> service.spawn("p", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no_workspace_for_repo");
    }

    @Test
    void rejectsWhenMultipleWorkspacesAreAmbiguous()
    {
        eligiblePass();
        when(workspaceService.list()).thenReturn(List.of(ws("ws-1"), ws("ws-2")));
        when(workspaceService.listRepos("ws-2")).thenReturn(List.of(
                new WorkspaceRepo("ws-2", REPO, "main", false, Instant.EPOCH)));
        assertThatThrownBy(() -> service.spawn("p", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ambiguous_workspace_picker_required");
    }

    // ── happy paths ──────────────────────────────────────────────────

    @Test
    void authorIsReviewerSpawnsBuildThreadFetchesHeadAndSetsBackLinks()
            throws Exception
    {
        eligiblePass();
        when(pullRequests.getPullRequest(eq("pat"), any(PullRequestRef.class))).thenReturn(pr("alice"));

        ReviewBuildSpawnService.BuildSpawn out = service.spawn("p", "ws-1", null);

        assertThat(out.mode()).isEqualTo(ReviewBuildSpawnService.MODE_AUTHOR);
        assertThat(out.threadId()).isEqualTo("thread-new");
        // author mode pre-fetches pr.head locally.
        verify(git).fetchPrRefs(any(), eq(PR), anyString());
        // The created thread is a BUILD thread.
        ArgumentCaptor<ThreadService.NewTaskRequest> req =
                ArgumentCaptor.forClass(ThreadService.NewTaskRequest.class);
        verify(threadService).create(req.capture());
        assertThat(req.getValue().flow()).isEqualTo(ThreadFlow.BUILD);
        assertThat(req.getValue().linkedPrNumber()).isEqualTo(PR);
        // Back-links: thread -> pass, pass -> thread.
        ArgumentCaptor<Thread> savedThread = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(savedThread.capture());
        assertThat(savedThread.getValue().parentReviewPassId()).isEqualTo("p");
        ArgumentCaptor<ReviewPass> savedPass = ArgumentCaptor.forClass(ReviewPass.class);
        verify(reviewStore).savePass(savedPass.capture());
        assertThat(savedPass.getValue().spawnedBuildThreadId()).isEqualTo("thread-new");
    }

    @Test
    void someoneElsesPrUsesSuggestedChangeModeAndDoesNotFetchHead()
            throws Exception
    {
        eligiblePass();
        when(pullRequests.getPullRequest(eq("pat"), any(PullRequestRef.class))).thenReturn(pr("bob"));

        ReviewBuildSpawnService.BuildSpawn out = service.spawn("p", "ws-1", null);

        assertThat(out.mode()).isEqualTo(ReviewBuildSpawnService.MODE_SUGGESTED);
        // suggested-change mode is comment-only: no PR-head checkout.
        verify(git, never()).fetchPrRefs(any(), eq(PR), anyString());
    }

    // ── opening turn (Step 3) ────────────────────────────────────────

    @Test
    void openingTurnRendersEligibleFindingsOrderedWithIdsAttributionsAndModeSuffix()
    {
        List<ReviewFinding> findings = List.of(
                finding("f-major", ReviewFindingSeverity.MAJOR, ReviewFindingStatus.AGREED,
                        "[Claude] tighten the loop", "converged"),
                finding("f-blocker", ReviewFindingSeverity.BLOCKER, ReviewFindingStatus.AGREED,
                        "null deref", null));

        String author = service.renderOpeningTurn(7, "My PR", findings,
                ReviewBuildSpawnService.MODE_AUTHOR, "feature/7");

        // Severity desc → blocker first; finding ids present; converged note;
        // panel attribution for the unprefixed body.
        assertThat(author.indexOf("[BLOCKER]")).isLessThan(author.indexOf("[MAJOR]"));
        assertThat(author).contains("#finding-f-blocker", "#finding-f-major");
        assertThat(author).contains("Source: @Claude (debate converged)");
        assertThat(author).contains("Source: @panel");
        assertThat(author).contains("Address them on `feature/7`");
        // author mode has no suggested-change suffix.
        assertThat(author).doesNotContain("suggested-change comments");

        String suggested = service.renderOpeningTurn(7, "My PR", findings,
                ReviewBuildSpawnService.MODE_SUGGESTED, "feature/7");
        assertThat(suggested).contains("or as suggested-change comments on PR #7");

        // Byte-identical reruns for the same inputs.
        assertThat(service.renderOpeningTurn(7, "My PR", findings,
                ReviewBuildSpawnService.MODE_AUTHOR, "feature/7")).isEqualTo(author);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void eligiblePass()
    {
        when(reviewStore.findPassById("p")).thenReturn(Optional.of(pass(ReviewPhase.TERMINATE, null)));
        when(reviewStore.listFindingsForPass("p")).thenReturn(List.of(
                finding("f1", ReviewFindingSeverity.MAJOR, ReviewFindingStatus.AGREED, "[Claude] fix it", null)));
    }

    private static ReviewPass pass(ReviewPhase phase, String spawnedThreadId)
    {
        return new ReviewPass("p", "t", REPO, PR, "sha", phase, 0, 3, 500L, 0L, null,
                Instant.EPOCH, phase == ReviewPhase.TERMINATE ? Instant.EPOCH : null, spawnedThreadId);
    }

    private static ReviewFinding finding(
            String id, ReviewFindingSeverity sev, ReviewFindingStatus status, String body, String debateStatus)
    {
        return new ReviewFinding(id, "p", "src/a.ts", 1, sev, status, body, null, null,
                Instant.EPOCH, debateStatus, 0);
    }

    private static PullRequest pr(String author)
    {
        return new PullRequest(
                1L, REPO, PR, "My PR", author, "https://github.com/" + REPO + "/pull/" + PR,
                Instant.EPOCH, Instant.EPOCH, PullRequest.Origin.REVIEW_REQUESTED,
                List.of(), Map.of(), false, null, null, null, List.of(), null,
                0, 0, 0, null, "open", null, null, null, null, null, Map.of(),
                null, null, "feature/" + PR);
    }

    private static PrRawDetail rawDetail()
    {
        return new PrRawDetail("body", List.of(), false, true, "clean", 0, 0, 0, 0, List.of(),
                "sha", "feature/" + PR, REPO, "main", REPO);
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, "Name", null, null, 0, 0, 0, null, null, null, null, false);
    }

    private static Workspace ws(String id)
    {
        return new Workspace(id, "name", null, false, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static Thread buildThread(String id)
    {
        return new Thread(id, ThreadKind.CLI_AGENT, null, null, "title", ThreadStatus.PENDING,
                null, 0L, 0L, 0L, Instant.EPOCH, Instant.EPOCH, null, null, ThreadFlow.BUILD,
                "ws-1", null, null);
    }
}
