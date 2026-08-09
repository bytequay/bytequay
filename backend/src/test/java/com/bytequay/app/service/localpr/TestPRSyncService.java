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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRDashboardEntry;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PRTriageState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.ActivityItem;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Coverage for observer-only PR refresh after the V2 ownership cutover. */
@SuppressWarnings("StringConcatToTextBlock")
class TestPRSyncService
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final PRService prService = mock(PRService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    /** Direct executor, so background syncs run inline and assertions stay
     *  deterministic. */
    private final PRSyncService service =
            new PRSyncService(prService, taskStore, git, pullRequests, Runnable::run);

    private Task task(TaskPhase phase)
    {
        return task(phase, null);
    }

    private Task task(TaskPhase phase, String linkedPrRef)
    {
        return new Task(
                "task1", "thread-1", 1L, TaskStatus.RUNNING,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, "T", null, null, null, phase, null, 0, linkedPrRef);
    }

    private PR draftPr()
    {
        return PR.create("pr1", "task1", "feature/x", "main", "T", "", NOW);
    }

    private PR pushedPr()
    {
        return draftPr().withRemote("acme/widget", 42, "https://github.com/acme/widget/pull/42", NOW)
                .withStatus(PR.STATUS_REMOTE_DRAFTED, NOW);
    }

    private static PR syncedAt(PR pr, Instant when)
    {
        return new PR(
                pr.id(), pr.taskId(), pr.branchName(), pr.baseBranch(), pr.title(), pr.description(),
                pr.status(), pr.createdAt(), pr.pushedAt(), pr.remotePrNumber(), pr.remotePrUrl(),
                pr.mergedAt(), pr.closedAt(), pr.localAddressedThroughAt(), pr.origin(), pr.repo(),
                pr.author(), when, pr.githubSync(), pr.branchDeletedAt());
    }

    /**
     * A PR pane can't mount until this resolver hands back the row's id, so a
     * PR we already hold must not wait on GitHub for it.
     */
    @Test
    void resolvingAKnownExternalPrNeverTouchesGitHub()
    {
        List<Runnable> queued = new ArrayList<>();
        PRSyncService deferred = new PRSyncService(
                prService, taskStore, git, pullRequests, queued::add);
        when(prService.findTaskByRepoAndNumber("acme/widget", 42)).thenReturn(Optional.empty());
        when(prService.findByRepoAndNumber("acme/widget", 42)).thenReturn(Optional.of(pushedPr()));
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr()));

        assertThat(deferred.resolveExternalPR("acme/widget", 42)).contains(pushedPr());

        verify(pullRequests, never()).lookupPullRequest(anyString(), anyInt());
        verify(pullRequests, never()).refreshPullRequestDetail(anyString(), anyInt(), anyInt());
        // The refresh still happens, just not while the caller is waiting.
        assertThat(queued).hasSize(1);
    }

    /**
     * The bundle GET reports {@code isSyncing} so the pane can poll for the
     * background pass's result. That flag has to converge: if every poll
     * started another pass the PR would sync forever at the fast cadence.
     */
    @Test
    void backgroundSyncRunsOnceThenLetsPollingSettle()
    {
        List<Runnable> queued = new ArrayList<>();
        PRSyncService deferred = new PRSyncService(
                prService, taskStore, git, pullRequests, queued::add);
        when(prService.findById("pr1")).thenReturn(Optional.of(draftPr()));

        deferred.syncInBackground("pr1");
        assertThat(deferred.isSyncing("pr1")).isTrue();
        assertThat(queued).hasSize(1);

        // A poll landing mid-pass must not stack a second one.
        deferred.syncInBackground("pr1");
        assertThat(queued).hasSize(1);

        queued.get(0).run();
        assertThat(deferred.isSyncing("pr1")).isFalse();

        // The pass just marked the PR synced, so the next poll is a no-op and
        // the pane drops back to its normal cadence.
        when(prService.findById("pr1")).thenReturn(Optional.of(syncedAt(draftPr(), Instant.now())));
        deferred.syncInBackground("pr1");
        assertThat(queued).hasSize(1);
        assertThat(deferred.isSyncing("pr1")).isFalse();
    }

    private static ActivityItem commented(long githubId, String actor, String body)
    {
        return new ActivityItem(actor, "commented", NOW, body, null, null, null, null, null, "MEMBER", githubId, null);
    }

    private static ActivityItem reviewed(long githubId, String actor, String state)
    {
        return reviewed(githubId, actor, state, null);
    }

    private static ActivityItem reviewed(long githubId, String actor, String state, String body)
    {
        return new ActivityItem(actor, "reviewed", NOW, body, state, null, null, null, null, "MEMBER", githubId, null);
    }

    private static PullRequestDetail detailWithActivity(List<ActivityItem> activity)
    {
        PullRequestDetail detail = mock(PullRequestDetail.class);
        when(detail.recentActivity()).thenReturn(activity);
        return detail;
    }

    @Test
    void passiveDisplayRefreshNeverStartsBrainReview()
            throws Exception
    {
        PR draft = draftPr();
        when(prService.findById("pr1")).thenReturn(Optional.of(draft));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.AWAITING_PUSH)));
        when(prService.commits("pr1")).thenReturn(List.of());
        when(git.resolveCommitBase(any(), any())).thenReturn("main");
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());

        assertThat(service.syncPRForDisplay("pr1")).contains(draft);
    }

    @Test
    void scheduledDashboardSyncNeverStartsTaskLifecycleReview()
            throws Exception
    {
        PR pushed = pushedPr();
        when(pullRequests.searchRelevantForDashboard()).thenReturn(List.of(
                ghPr(42, "T", "octocat", PullRequest.Origin.AUTHORED)));
        when(pullRequests.resolveCurrentDashboardLogin()).thenReturn("octocat");
        when(prService.findTaskByRepoAndNumber("acme/widget", 42))
                .thenReturn(Optional.of(pushed));
        when(prService.dashboardEntries()).thenReturn(List.of());

        service.syncList();

        verify(prService, never()).updateAuthor(any(), any());
        verify(prService, never()).updateSyncSnapshot(any(), any());
        verify(pullRequests, never()).refreshPullRequestDetail(any(), anyInt(), anyInt());
        verify(git, never()).listCommitsAhead(any(), any(), anyInt());
    }

    private PR externalPr()
    {
        return new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
    }

    @Test
    void syncPrSyncsAnExternalOriginPrDirectlyFromItsOwnRepoAndNumberNoTaskOrGitInvolved()
            throws Exception
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        PullRequestDetail detail = detailWithActivity(
                List.of(commented(5001L, "octocat", "Can you also handle nulls?")));
        when(pullRequests.refreshPullRequestDetail("acme/widget", 99, 20)).thenReturn(detail);
        when(prService.hasRemoteEvent(eq("pr-ext"), anyLong())).thenReturn(false);

        service.syncPR("pr-ext");

        verify(prService).addRemoteComment("pr-ext", "@octocat", "Can you also handle nulls?", NOW, 5001L);
        verify(taskStore, never()).findTaskById(any());
        verify(git, never()).remoteSlug(any(), any());
    }

    @Test
    void explicitZeroMaxAgeAlwaysProbesEvenForAJustSyncedExternalPr()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        PullRequestDetail detail = detailWithActivity(List.of());
        when(pullRequests.refreshPullRequestDetail("acme/widget", 99, 0)).thenReturn(detail);

        service.syncPR("pr-ext", 0);

        verify(pullRequests).refreshPullRequestDetail("acme/widget", 99, 0);
        verify(pullRequests, never()).refreshPullRequestDetail(any(), anyInt(), eq(20));
    }

    @Test
    void syncPrReturnsEmptyWhenThePrDoesNotExist()
    {
        when(prService.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.syncPR("missing")).isEmpty();
    }

    private static PullRequest lightPr(String title, String author, String state, boolean draft, Instant mergedAt)
    {
        PullRequest pr = mock(PullRequest.class);
        when(pr.title()).thenReturn(title);
        when(pr.author()).thenReturn(author);
        when(pr.htmlUrl()).thenReturn("https://github.com/acme/widget/pull/99");
        when(pr.createdAt()).thenReturn(NOW);
        when(pr.state()).thenReturn(state);
        when(pr.draft()).thenReturn(draft);
        when(pr.mergedAt()).thenReturn(mergedAt);
        when(pr.closedAt()).thenReturn(null);
        return pr;
    }

    private static PullRequestDetail detail(String headRef, String baseRef, String body, boolean merged, String state, boolean draft)
    {
        PullRequestDetail detail = mock(PullRequestDetail.class);
        when(detail.headRef()).thenReturn(headRef);
        when(detail.baseRef()).thenReturn(baseRef);
        when(detail.body()).thenReturn(body);
        when(detail.merged()).thenReturn(merged);
        when(detail.state()).thenReturn(state);
        when(detail.draft()).thenReturn(draft);
        when(detail.recentActivity()).thenReturn(List.of());
        return detail;
    }

    @Test
    void syncExternalPrCreatesTheRowOnFirstSightThenDelegatesToSyncPr()
    {
        PullRequest light = lightPr("Fix flaky test", "octocat", "open", false, null);
        PullRequestDetail fetchedDetail = detail("feature/y", "main", "body text", false, "open", false);
        PullRequestDetail refreshedDetail = detail("feature/y", "main", "body text", false, "open", false);
        when(prService.findByRepoAndNumber("acme/widget", 99)).thenReturn(Optional.empty());
        when(pullRequests.lookupPullRequest("acme/widget", 99)).thenReturn(light);
        when(pullRequests.getPullRequestDetail("acme/widget", 99)).thenReturn(fetchedDetail);
        PR created = new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "body text", PR.STATUS_REMOTE_OPEN, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
        when(prService.createExternal(
                eq("acme/widget"), eq(99), any(), eq("@octocat"), eq("feature/y"), eq("main"),
                eq("Fix flaky test"), eq("body text"), eq(PR.STATUS_REMOTE_OPEN), eq(NOW), any(), any()))
                .thenReturn(created);
        when(prService.findById("pr-ext")).thenReturn(Optional.of(created));
        when(pullRequests.refreshPullRequestDetail(eq("acme/widget"), eq(99), anyInt())).thenReturn(refreshedDetail);

        Optional<PR> result = service.syncExternalPR("acme/widget", 99);

        assertThat(result).isPresent();
        verify(prService).createExternal(
                eq("acme/widget"), eq(99), any(), eq("@octocat"), eq("feature/y"), eq("main"),
                eq("Fix flaky test"), eq("body text"), eq(PR.STATUS_REMOTE_OPEN), eq(NOW), any(), any());
    }

    @Test
    void syncExternalPrIsIdempotentOnceTheRowExists()
    {
        PR existing = new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
        PullRequestDetail refreshedDetail = detail("feature/y", "main", "", false, "open", false);
        when(prService.findByRepoAndNumber("acme/widget", 99)).thenReturn(Optional.of(existing));
        when(prService.findById("pr-ext")).thenReturn(Optional.of(existing));
        when(pullRequests.refreshPullRequestDetail(eq("acme/widget"), eq(99), anyInt())).thenReturn(refreshedDetail);

        service.syncExternalPR("acme/widget", 99);

        verify(pullRequests, never()).lookupPullRequest(any(), anyInt());
        verify(prService, never()).createExternal(
                any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void syncExternalPrRejectsAV2TaskAliasBeforeGitHubOrStoreMutation()
    {
        // A ByteQuay task opened this PR, so its task-origin row already carries
        // remote #99. The dashboard resolver must resolve to that row, not mint
        // a separate external twin. See pr-record-unification-design.md.
        PR taskRow = new PR(
                "pr-task", "task-1", "dev/x", "main", "Add cache", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_TASK, "acme/widget", "@octocat", null, null, null);
        when(prService.findTaskByRepoAndNumber("acme/widget", 99)).thenReturn(Optional.of(taskRow));
        when(taskStore.findWorkflowVersion("task-1")).thenReturn(Optional.of("V2"));

        assertThatThrownBy(() -> service.syncExternalPR("acme/widget", 99))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("use the Task review surface");

        verify(pullRequests, never()).lookupPullRequest(any(), anyInt());
        verify(pullRequests, never()).refreshPullRequestDetail(any(), anyInt(), anyInt());
        verify(prService, never()).createExternal(
                any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void syncFlipsAnExternalPrToMergedOnceGitHubReportsIt()
    {
        PR pr = new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
        PullRequestDetail refreshedDetail = detail("feature/y", "main", "", true, "closed", false);
        when(prService.findById("pr-ext")).thenReturn(Optional.of(pr));
        when(pullRequests.refreshPullRequestDetail("acme/widget", 99, 20)).thenReturn(refreshedDetail);

        service.syncPR("pr-ext");

        verify(prService).transition("pr-ext", PR.STATUS_MERGED, PRTimelineEntry.ACTOR_AGENT);
    }

    /** Built via the real constructor, not {@code mock(PullRequest.class)} —
     *  records don't mock cleanly with this project's Mockito setup (see
     *  the {@code lightPr}/{@code detail} fix earlier in this file). */
    private static PullRequest ghPr(int number, String title, String author, PullRequest.Origin origin)
    {
        return new PullRequest(
                1L, "acme/widget", number, title, author,
                "https://github.com/acme/widget/pull/" + number,
                NOW, NOW, origin, List.of(), Map.of(), /* draft */ false,
                null, null, null, List.of(), null, 0, 0, 0, null,
                "open", null, null, null, null, null, Map.of(), null, null, "feature/z");
    }

    @Test
    void syncListUpsertsAnExistingWatchedPrWithoutDuplicatingIt()
    {
        PR.PRSyncSnapshot existingSnapshot = new PR.PRSyncSnapshot(
                PullRequest.Origin.AUTHORED, NOW, List.of(), Map.of(), false,
                PullRequestDetail.CiStatus.PASSING, 5, 1, 0, null, null, null, null, Map.of(), List.of(),
                false, null);
        PR existing = new PR(
                "pr-101", null, "feature/z", "main", "Fix flaky test", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 101, "https://github.com/acme/widget/pull/101", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, existingSnapshot, null);
        when(pullRequests.searchRelevantForDashboard())
                .thenReturn(List.of(ghPr(101, "Fix flaky test", "octocat", PullRequest.Origin.AUTHORED)));
        when(pullRequests.resolveCurrentDashboardLogin()).thenReturn("octocat");
        when(prService.findByRepoAndNumber("acme/widget", 101)).thenReturn(Optional.of(existing));
        when(prService.updateAuthor("pr-101", "@octocat")).thenReturn(existing);
        when(prService.updateSyncSnapshot(eq("pr-101"), any())).thenReturn(existing);
        when(prService.dashboardEntries()).thenReturn(List.of());

        service.syncList();

        verify(prService, never()).createExternal(
                any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(prService).updateSyncSnapshot(eq("pr-101"), argThat(
                snap -> snap.watchReason() == PullRequest.Origin.AUTHORED && !snap.draft()));
    }

    @Test
    void syncListUnwatchesAPrThatFellOutOfTheSearchAndWasNeverReviewed()
    {
        when(pullRequests.searchRelevantForDashboard()).thenReturn(List.of());
        when(pullRequests.resolveCurrentDashboardLogin()).thenReturn("octocat");
        PR fellOut = new PR(
                "pr-old", null, "feature/y", "main", "Old PR", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 55, "https://github.com/acme/widget/pull/55", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
        when(prService.dashboardEntries()).thenReturn(
                List.of(new PRDashboardEntry(fellOut, PRTriageState.empty("pr-old"))));

        service.syncList();

        verify(prService).setWatchReason("pr-old", null);
    }

    @Test
    void syncListKeepsWatchingARecentlyReviewedPrThatFellOutOfTheSearch()
    {
        when(pullRequests.searchRelevantForDashboard()).thenReturn(List.of());
        when(pullRequests.resolveCurrentDashboardLogin()).thenReturn("octocat");
        PR fellOut = new PR(
                "pr-old", null, "feature/y", "main", "Old PR", "", PR.STATUS_MERGED, NOW,
                null, 55, "https://github.com/acme/widget/pull/55", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
        // Relative to the real clock, not the frozen NOW: syncList measures
        // retention against Instant.now(), so a fixed timestamp silently ages
        // out of the window and fails the day it crosses HANDLED_RETENTION_DAYS.
        PRTriageState reviewedYesterday = new PRTriageState(
                "pr-old", null, Instant.now().minus(1, ChronoUnit.DAYS), HandledAction.APPROVED, null, null, null);
        when(prService.dashboardEntries()).thenReturn(
                List.of(new PRDashboardEntry(fellOut, reviewedYesterday)));

        service.syncList();

        verify(prService, never()).setWatchReason(eq("pr-old"), any());
    }

    @Test
    void syncBackfillsTheDescriptionFromGitHubForAnExternalPr()
    {
        PR pr = new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
        PullRequestDetail refreshedDetail = detail("feature/y", "main", "The real PR body.", false, "open", false);
        when(prService.findById("pr-ext")).thenReturn(Optional.of(pr));
        when(pullRequests.refreshPullRequestDetail("acme/widget", 99, 20)).thenReturn(refreshedDetail);

        service.syncPR("pr-ext");

        verify(prService).updateDetails("pr-ext", null, "The real PR body.");
    }

    private PR externalPr(PR.PRSyncSnapshot snapshot)
    {
        return new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, snapshot, null);
    }

    @Test
    void syncPrSyncsGitHubCommitsForAnExternalPrDedupingKnownShas()
    {
        PR pr = externalPr(null);
        PullRequestDetail refreshedDetail = detail("feature/y", "main", "", false, "open", false);
        when(prService.findById("pr-ext")).thenReturn(Optional.of(pr));
        when(pullRequests.refreshPullRequestDetail("acme/widget", 99, 20)).thenReturn(refreshedDetail);
        when(prService.commits("pr-ext")).thenReturn(
                List.of(new PRCommit("c1", "pr-ext", "aaa", "first", 0, 0, NOW, null)));
        when(pullRequests.getPullRequestCommits("acme/widget", 99)).thenReturn(List.of(
                new PullRequestCommit("aaa", "octocat", "Octo Cat",
                        NOW.minusSeconds(60), NOW.minusSeconds(60), "first"),
                new PullRequestCommit("bbb", "octocat", "Octo Cat",
                        NOW.minusSeconds(30), NOW.minusSeconds(30), "second")));

        service.syncPR("pr-ext");

        verify(prService, never()).recordSyncedCommit(eq("pr-ext"), eq("aaa"), any(), any(), any());
        verify(prService).recordSyncedCommit(
                eq("pr-ext"), eq("bbb"), eq("second"), eq(NOW.minusSeconds(30)), eq("@octocat"));
    }

    @Test
    void syncPrSyncsGitHubCheckRunsForAnExternalPrSkippingRunsWithNoStableId()
    {
        PR pr = externalPr(null);
        PullRequestDetail refreshedDetail = detail("feature/y", "main", "", false, "open", false);
        when(refreshedDetail.checkRuns()).thenReturn(List.of(
                new PullRequestDetail.CheckRun(555L, "build", "completed", "success", "https://x", null, null),
                new PullRequestDetail.CheckRun(null, "legacy", "completed", "success", null, null, null)));
        when(prService.findById("pr-ext")).thenReturn(Optional.of(pr));
        when(pullRequests.refreshPullRequestDetail("acme/widget", 99, 20)).thenReturn(refreshedDetail);
        when(prService.commits("pr-ext")).thenReturn(List.of());
        when(pullRequests.getPullRequestCommits("acme/widget", 99)).thenReturn(List.of());

        service.syncPR("pr-ext");

        verify(prService).recordSyncedCheck(
                eq("pr-ext"), eq("555"), eq("build"), eq(PRCheck.STATUS_PASSED), any(), any());
        verify(prService, times(1)).recordSyncedCheck(any(), any(), any(), any(), any(), any());
        verify(prService).retainSyncedChecks("pr-ext", ImmutableSet.of("555"));
    }

    @Test
    void syncPrRefreshesDiffAndCiSnapshotFromDetailPreservingDashboardOnlyFields()
    {
        PR.PRSyncSnapshot baseline = new PR.PRSyncSnapshot(
                PullRequest.Origin.AUTHORED, NOW, List.of("bug"), Map.of("bug", "red"), true,
                PullRequestDetail.CiStatus.PENDING, 0, 0, 3, AttentionReason.MINE, null, null, null,
                Map.of(), List.of(), false, null);
        PR pr = externalPr(baseline);
        PullRequestDetail refreshedDetail = detail("feature/y", "main", "", false, "open", false);
        when(refreshedDetail.additions()).thenReturn(891);
        when(refreshedDetail.deletions()).thenReturn(407);
        when(refreshedDetail.ciStatus()).thenReturn(PullRequestDetail.CiStatus.PASSING);
        when(refreshedDetail.mergeable()).thenReturn(true);
        when(refreshedDetail.mergeableState()).thenReturn("clean");
        when(prService.findById("pr-ext")).thenReturn(Optional.of(pr));
        when(pullRequests.refreshPullRequestDetail("acme/widget", 99, 20)).thenReturn(refreshedDetail);
        when(prService.commits("pr-ext")).thenReturn(List.of());
        when(pullRequests.getPullRequestCommits("acme/widget", 99)).thenReturn(List.of());

        service.syncPR("pr-ext");

        verify(prService).updateSyncSnapshot(eq("pr-ext"), argThat(snap ->
                snap.additions() == 891 && snap.deletions() == 407
                        && snap.ciStatus() == PullRequestDetail.CiStatus.PASSING
                        && Boolean.TRUE.equals(snap.mergeable())
                        && "clean".equals(snap.mergeableState())
                        && snap.watchReason() == PullRequest.Origin.AUTHORED
                        && snap.labels().equals(List.of("bug"))
                        && snap.commentCount() == 3));
    }

    @Test
    void syncPrThreadsMergeQueueInfoFromTheAlreadyFetchedDetailIntoTheSnapshot()
    {
        PR pr = externalPr();
        PullRequestDetail refreshedDetail = detail("feature/y", "main", "", false, "open", false);
        when(refreshedDetail.mergeQueueEnabled()).thenReturn(true);
        when(refreshedDetail.mergeQueueState()).thenReturn("QUEUED");
        when(prService.findById("pr-ext")).thenReturn(Optional.of(pr));
        when(pullRequests.refreshPullRequestDetail("acme/widget", 99, 20)).thenReturn(refreshedDetail);

        service.syncPR("pr-ext");

        verify(prService).updateSyncSnapshot(eq("pr-ext"), argThat(snap ->
                snap.mergeQueueEnabled() && "QUEUED".equals(snap.mergeQueueState())));
    }

    @Test
    void aFixupLandsBesideItsPickRatherThanAfterEveryPick()
            throws Exception
    {
        // A cherry-pick keeps upstream's author date, so a branch of picks and
        // their fixups reads as three old commits then three new ones when it is
        // ordered by author. The committer date is when each landed on the
        // branch, which is the order GitHub itself shows.
        Instant old = NOW.minusSeconds(86_400);
        assertThat(PRSyncService.timelineOrderOf(new PullRequestCommit(
                "pick", "dependabot", "dependabot", old, NOW.minusSeconds(300), "Bump x")))
                .isEqualTo(NOW.minusSeconds(300));
        // Nothing to fall back on for rows synced before we read the committer.
        assertThat(PRSyncService.timelineOrderOf(new PullRequestCommit(
                "legacy", "dependabot", "dependabot", old, null, "Bump y")))
                .isEqualTo(old);
    }
}
