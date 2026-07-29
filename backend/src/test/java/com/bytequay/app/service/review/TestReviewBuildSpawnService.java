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
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workspaces.WorkspaceRelationService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewBuildSpawnService
{
    private ReviewStore reviewStore;
    private PullRequestStore pullRequests;
    private PrDetailStore prDetails;
    private WorkspaceService workspaceService;
    private WorkspaceRepositoryResolver repositories;
    private WorkspaceRelationService relations;
    private WatchedRepoStore watchedRepos;
    private ReviewBuildSpawnCommitter committer;
    private ReviewBuildSpawnService service;

    private static final String REPO = "acme/widget";
    private static final int PR = 42;

    @BeforeEach
    void setUp()
    {
        reviewStore = mock(ReviewStore.class);
        pullRequests = mock(PullRequestStore.class);
        prDetails = mock(PrDetailStore.class);
        workspaceService = mock(WorkspaceService.class);
        repositories = mock(WorkspaceRepositoryResolver.class);
        relations = mock(WorkspaceRelationService.class);
        watchedRepos = mock(WatchedRepoStore.class);
        committer = mock(ReviewBuildSpawnCommitter.class);
        service = new ReviewBuildSpawnService(reviewStore, pullRequests, prDetails,
                workspaceService, repositories, relations, watchedRepos,
                committer);

        when(pullRequests.findIdByRepoAndNumber(REPO, PR))
                .thenReturn(Optional.of(1L));
        when(pullRequests.findById(1L)).thenReturn(Optional.of(pr("alice")));
        when(prDetails.find(1L)).thenReturn(Optional.of(detail(rawDetail())));
        when(watchedRepos.find("acme", "widget"))
                .thenReturn(Optional.of(new WatchedRepo(1, "acme", "widget", 0, "/clones/widget", null, null)));
        when(repositories.resolve("ws-1")).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", REPO, "main"));
        when(relations.find("ws-1")).thenReturn(Optional.empty());
        ReviewBuildSpawnCommitter.CommittedSpawn committed = mock(
                ReviewBuildSpawnCommitter.CommittedSpawn.class);
        when(committed.thread()).thenReturn(buildThread("thread-new"));
        when(committer.commit(any(), any(), any(), any(), any()))
                .thenReturn(committed);
        when(workspaceService.listRepos("ws-1")).thenReturn(List.of(
                new WorkspaceRepo("ws-1", REPO, "main", false, Instant.EPOCH)));
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
    void replaysAnAlreadySpawnedSelectAllRequest()
    {
        when(reviewStore.findPassById("p")).thenReturn(Optional.of(pass(ReviewPhase.TERMINATE, "thread-old")));
        when(committer.findCommitted("p")).thenReturn(Optional.of(
                committed("thread-old",
                        ReviewBuildSelectionStore.SelectionPolicy.ALL_ELIGIBLE,
                        ReviewBuildSpawnService.MODE_AUTHOR, "f1")));

        ReviewBuildSpawnService.BuildSpawn replay =
                service.spawn("p", "ws-1", null);

        assertThat(replay.threadId()).isEqualTo("thread-old");
        assertThat(replay.mode()).isEqualTo(ReviewBuildSpawnService.MODE_AUTHOR);
        verify(committer, never()).commit(any(), any(), any(), any(), any());
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
        when(repositories.resolve("ws-2")).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", REPO, "main"));
        when(relations.find("ws-2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.spawn("p", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ambiguous_workspace_picker_required");
    }

    // ── happy paths ──────────────────────────────────────────────────

    @Test
    void authoredPrSpawnsBuildThreadFromTheFrozenLocalSnapshot()
    {
        eligiblePass();

        ReviewBuildSpawnService.BuildSpawn out = service.spawn("p", "ws-1", null);

        assertThat(out.mode()).isEqualTo(ReviewBuildSpawnService.MODE_AUTHOR);
        assertThat(out.threadId()).isEqualTo("thread-new");
        // The committed request is a BUILD Trunk with an exact route.
        ArgumentCaptor<ThreadService.NewTaskRequest> req =
                ArgumentCaptor.forClass(ThreadService.NewTaskRequest.class);
        ArgumentCaptor<ReviewBuildSelectionStore.SpawnInput> spawn =
                ArgumentCaptor.forClass(ReviewBuildSelectionStore.SpawnInput.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReviewFinding>> frozen = ArgumentCaptor.forClass(List.class);
        verify(committer).commit(
                req.capture(), any(), spawn.capture(), frozen.capture(),
                any(Instant.class));
        assertThat(req.getValue().flow()).isEqualTo(ThreadFlow.BUILD);
        assertThat(req.getValue().linkedPrNumber()).isEqualTo(PR);
        assertThat(spawn.getValue().mode())
                .isEqualTo(ReviewBuildSpawnService.MODE_AUTHOR);
        assertThat(spawn.getValue().baseRepositoryId()).isEqualTo(REPO);
        assertThat(spawn.getValue().headRepositoryId()).isEqualTo(REPO);
        assertThat(frozen.getValue())
                .extracting(ReviewFinding::id)
                .containsExactly("f1");
    }

    @Test
    void freezesOnlyExplicitlySelectedEligibleFindings()
    {
        when(reviewStore.findPassById("p"))
                .thenReturn(Optional.of(pass(ReviewPhase.TERMINATE, null)));
        when(reviewStore.listFindingsForPass("p")).thenReturn(List.of(
                finding("f-major", ReviewFindingSeverity.MAJOR,
                        ReviewFindingStatus.AGREED, "major", null),
                finding("f-blocker", ReviewFindingSeverity.BLOCKER,
                        ReviewFindingStatus.AGREED, "blocker", null)));

        service.spawn("p", "ws-1", null, List.of("f-major"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReviewFinding>> frozen = ArgumentCaptor.forClass(List.class);
        verify(committer).commit(
                any(), any(), any(), frozen.capture(), any(Instant.class));
        assertThat(frozen.getValue())
                .extracting(ReviewFinding::id)
                .containsExactly("f-major");
    }

    @Test
    void rejectsSpawnWhenReviewedHeadMoved()
    {
        eligiblePass();
        PrRawDetail current = rawDetail();
        when(prDetails.find(1L)).thenReturn(Optional.of(detail(new PrRawDetail(
                        current.body(), current.labels(),
                        current.draft(), current.mergeable(),
                        current.mergeableState(), current.additions(),
                        current.deletions(), current.changedFiles(),
                        current.requestedReviewerCount(),
                        current.requestedReviewers(), "moved-head",
                        current.headRef(), current.headRepo(), current.baseRef(),
                        current.baseRepo()))));

        assertThatThrownBy(() -> service.spawn("p", "ws-1", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("review_head_moved");
        verify(committer, never()).commit(any(), any(), any(), any(), any());
    }

    @Test
    void someoneElsesPrUsesSuggestedChangeMode()
    {
        eligiblePass();
        when(pullRequests.findById(1L)).thenReturn(Optional.of(pr("bob")));

        ReviewBuildSpawnService.BuildSpawn out = service.spawn("p", "ws-1", null);

        assertThat(out.mode()).isEqualTo(ReviewBuildSpawnService.MODE_SUGGESTED);
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
        assertThat(suggested)
                .contains("immutable frozen suggested-change comments for PR #7")
                .contains("explicitly approve or discard")
                .contains("never creates a Task or writable worktree")
                .doesNotContain("Split into separate Tasks");

        // Byte-identical reruns for the same inputs.
        assertThat(service.renderOpeningTurn(7, "My PR", findings,
                ReviewBuildSpawnService.MODE_AUTHOR, "feature/7")).isEqualTo(author);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void eligiblePass()
    {
        when(reviewStore.findPassById("p")).thenReturn(Optional.of(pass(ReviewPhase.TERMINATE, null)));
        when(reviewStore.listFindingsForPass("p")).thenReturn(List.of(
                finding("f1", ReviewFindingSeverity.MAJOR,
                        ReviewFindingStatus.ARBITRATED,
                        "[Claude] fix it", null)));
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
                Instant.EPOCH, Instant.EPOCH, "alice".equals(author)
                        ? PullRequest.Origin.AUTHORED
                        : PullRequest.Origin.REVIEW_REQUESTED,
                List.of(), Map.of(), false, null, null, null, List.of(), null,
                0, 0, 0, null, "open", null, null, null, null, null, Map.of(),
                null, null, "feature/" + PR);
    }

    private static PrRawDetail rawDetail()
    {
        return new PrRawDetail("body", List.of(), false, true, "clean", 0, 0, 0, 0, List.of(),
                "sha", "feature/" + PR, REPO, "main", REPO);
    }

    private static StoredPrDetail detail(PrRawDetail raw)
    {
        return new StoredPrDetail(
                raw, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());
    }

    private static Workspace ws(String id)
    {
        return new Workspace(id, "name", null, false, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static ReviewBuildSpawnCommitter.CommittedSpawn committed(
            String threadId,
            ReviewBuildSelectionStore.SelectionPolicy policy,
            String mode,
            String findingId)
    {
        ReviewBuildSelectionStore.Selection selection =
                new ReviewBuildSelectionStore.Selection(
                        threadId, "p", REPO, PR, "sha",
                        new ReviewBuildSelectionStore.SpawnInput(
                                "ws-1", "Fix review findings on PR #" + PR,
                                policy, mode, REPO, REPO, "main",
                                "feature/" + PR),
                        "selection-digest",
                        List.of(new ReviewBuildSelectionStore.Finding(
                                "p", findingId, 1, "{}", "digest")),
                        Instant.EPOCH);
        return new ReviewBuildSpawnCommitter.CommittedSpawn(
                buildThread(threadId), Optional.of(selection));
    }

    private static Thread buildThread(String id)
    {
        return new Thread(id, ThreadKind.CLI_AGENT, null, null, "title", ThreadStatus.PENDING,
                null, 0L, 0L, 0L, Instant.EPOCH, Instant.EPOCH, null, null, ThreadFlow.BUILD,
                "ws-1", null, null);
    }
}
