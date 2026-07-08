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

import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration coverage for the user-gated push: it pushes the branch, opens
 * a Draft PR, mirrors the push onto the task, and hands off to
 * {@link PRService#recordPush} (which strips locals + flips status).
 */
class TestPRPublishService
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final PRService prService = mock(PRService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final PatResolver patResolver = mock(PatResolver.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final PRPublishService service =
            new PRPublishService(prService, taskStore, git, pullRequests, patResolver, brainReview, phaseMachine);

    private PR pr(String status)
    {
        return PR.create("pr1", "task1", "feature/x", "main", "Add cache", "desc", NOW)
                .withStatus(status, NOW);
    }

    private PR pushedPr(String status)
    {
        return PR.create("pr1", "task1", "feature/x", "main", "Add cache", "desc", NOW)
                .withRemote("acme/widget", 145, "https://github.com/acme/widget/pull/145", NOW)
                .withStatus(status, NOW);
    }

    private Task task()
    {
        return new Task(
                "task1", "thread-1", 1L, TaskStatus.AWAITING_REVIEW,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null);
    }

    private static PullRequest opened(int number)
    {
        return new PullRequest(
                1L, "acme/widget", number, "Add cache", "you",
                "https://github.com/acme/widget/pull/" + number,
                NOW, NOW, PullRequest.Origin.AUTHORED, List.of(), Map.of(), /* draft */ true,
                null, null, null, List.of(), null, 0, 0, 0, null,
                "open", null, null, null, null, null, Map.of(), null, null, "feature/x");
    }

    @Test
    void pushPushesBranchOpensDraftPrAndRecordsThePush()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(Path.of("/tmp/wt/feature-x"), "origin/feature/x")).thenReturn(false);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(eq("ghp"), eq(new RepoRef("acme", "widget")), any()))
                .thenReturn(opened(145));
        PR flipped = pr(PR.STATUS_REMOTE_DRAFTED);
        when(prService.recordPush("pr1", "acme/widget", 145, "https://github.com/acme/widget/pull/145")).thenReturn(flipped);

        PR result = service.push("pr1");

        verify(git).push(Path.of("/tmp/wt/feature-x"));
        verify(taskStore).markPushed(eq("task1"), any());
        verify(taskStore).linkPullRequest("task1", 145, "draft");
        verify(taskStore).linkTaskToPr("task1", "acme/widget#145");
        verify(prService).recordPush("pr1", "acme/widget", 145, "https://github.com/acme/widget/pull/145");
        verify(phaseMachine).observe("task1", TaskPhase.PUSHED_AWAITING_CI, "local_pr_pushed");
        assertThat(result).isSameAs(flipped);
    }

    @Test
    void pushSkipsGitPushWhenTheBranchIsAlreadyOnOrigin()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(Path.of("/tmp/wt/feature-x"), "origin/feature/x")).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(any(), any(), any())).thenReturn(opened(7));
        when(prService.recordPush(any(), any(), eq(7), any())).thenReturn(pr(PR.STATUS_REMOTE_DRAFTED));

        service.push("pr1");

        verify(git, never()).push(any());
        verify(pullRequests).createPullRequest(any(), any(), any());
    }

    @Test
    void pushRejectsAPrThatIsNotLocalOpen()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_DRAFTED)));

        assertThatThrownBy(() -> service.push("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not ready to push");
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void onPushedElsewhereAdvancesALocalOpenRowToRemoteDrafted()
    {
        // Mirrors a push/open_pr gate or the ship/next tool flow — none of
        // which call this service's own push(), so the row would otherwise
        // stay stuck offering "ready to push" for a push that already
        // happened.
        when(prService.findByTask("task1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));

        service.onPushedElsewhere(new PrPushedEvent("task1", "acme/widget", 145, "https://github.com/acme/widget/pull/145"));

        verify(prService).recordPush("pr1", "acme/widget", 145, "https://github.com/acme/widget/pull/145");
        verify(brainReview, never()).reviewBeforeLocalOpen(any(), any());
    }

    @Test
    void onPushedElsewhereRunsTheBrainReviewFirstWhenStillLocalDrafted()
    {
        when(prService.findByTask("task1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_DRAFTED)));
        when(brainReview.reviewBeforeLocalOpen("pr1", PRTimelineEntry.ACTOR_AGENT))
                .thenReturn(pr(PR.STATUS_LOCAL_OPEN));

        service.onPushedElsewhere(new PrPushedEvent("task1", "acme/widget", 145, "https://github.com/acme/widget/pull/145"));

        verify(brainReview).reviewBeforeLocalOpen("pr1", PRTimelineEntry.ACTOR_AGENT);
        verify(prService).recordPush("pr1", "acme/widget", 145, "https://github.com/acme/widget/pull/145");
    }

    @Test
    void onPushedElsewhereIsANoOpForATaskWithNoPr()
    {
        when(prService.findByTask("task-none")).thenReturn(Optional.empty());

        service.onPushedElsewhere(new PrPushedEvent("task-none", "x/y", 145, "https://github.com/x/y/pull/145"));

        verify(prService, never()).recordPush(any(), any(), anyInt(), any());
    }

    @Test
    void pushRejectsAnUnknownPr()
    {
        when(prService.findById("pr1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.push("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no local PR");
    }

    @Test
    void pushRejectsAnOpenCommentThread()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));
        when(prService.comments("pr1")).thenReturn(List.of(comment(null, null)));

        assertThatThrownBy(() -> service.push("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("open comment thread");
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void pushAllowsResolvedAndDismissedThreads()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));
        when(prService.comments("pr1")).thenReturn(List.of(comment(NOW, null), comment(null, NOW)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(any(), any())).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(any(), any(), any())).thenReturn(opened(7));
        when(prService.recordPush(any(), any(), eq(7), any())).thenReturn(pr(PR.STATUS_REMOTE_DRAFTED));

        service.push("pr1");

        verify(pullRequests).createPullRequest(any(), any(), any());
    }

    @Test
    void pushRejectsAFailingLocalTestRun()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));
        when(prService.checks("pr1")).thenReturn(List.of(
                check(Instant.parse("2026-07-01T00:00:00Z"), PRCheck.STATUS_PASSED),
                check(Instant.parse("2026-07-01T00:05:00Z"), PRCheck.STATUS_FAILED)));

        assertThatThrownBy(() -> service.push("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("failing local test run");
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void pushAllowsAFixedRunEvenAfterAnEarlierFailure()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));
        when(prService.checks("pr1")).thenReturn(List.of(
                check(Instant.parse("2026-07-01T00:00:00Z"), PRCheck.STATUS_FAILED),
                check(Instant.parse("2026-07-01T00:05:00Z"), PRCheck.STATUS_PASSED)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(any(), any())).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(any(), any(), any())).thenReturn(opened(7));
        when(prService.recordPush(any(), any(), eq(7), any())).thenReturn(pr(PR.STATUS_REMOTE_DRAFTED));

        service.push("pr1");

        verify(pullRequests).createPullRequest(any(), any(), any());
    }

    @Test
    void pushAllowsAPrWithNoLocalChecksRecordedAtAll()
            throws Exception
    {
        // No recognised test runner for this repo — nothing to gate on.
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(any(), any())).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(any(), any(), any())).thenReturn(opened(7));
        when(prService.recordPush(any(), any(), eq(7), any())).thenReturn(pr(PR.STATUS_REMOTE_DRAFTED));

        service.push("pr1");

        verify(pullRequests).createPullRequest(any(), any(), any());
    }

    private static PRComment comment(Instant resolvedAt, Instant dismissedAt)
    {
        return new PRComment("cm1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, "you", "note", NOW, resolvedAt, dismissedAt, null, null, null,
                "RIGHT", null, null);
    }

    private static PRCheck check(Instant startedAt, String status)
    {
        return new PRCheck("c1", "pr1", PRCheck.KIND_LOCAL, "maven test", status,
                1000L, startedAt, startedAt, null);
    }

    @Test
    void mergeMergesTheRemotePrAndFlipsToMerged()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.mergePullRequest(eq("ghp"), eq(new PullRequestRef("acme", "widget", 145)), any()))
                .thenReturn(new MergeResult("sha123", true, "Merged"));
        PR merged = pushedPr(PR.STATUS_MERGED);
        when(prService.recordMerged("pr1")).thenReturn(merged);

        PR result = service.merge("pr1", "squash");

        verify(pullRequests).mergePullRequest(
                eq("ghp"), eq(new PullRequestRef("acme", "widget", 145)), any());
        verify(prService).recordMerged("pr1");
        assertThat(result).isSameAs(merged);
    }

    @Test
    void mergeRejectsAPrThatWasNeverPushed()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));

        assertThatThrownBy(() -> service.merge("pr1", "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("has not been pushed");
    }

    @Test
    void mergeSurfacesGitHubRefusalWithoutFlipping()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        try {
            when(git.remoteSlug(Path.of("/tmp/repo"), "origin"))
                    .thenReturn(Optional.of(new RepoRef("acme", "widget")));
            when(pullRequests.mergePullRequest(any(), any(), any()))
                    .thenReturn(new MergeResult(null, false, "not mergeable"));
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        assertThatThrownBy(() -> service.merge("pr1", "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("did not merge");
        verify(prService, never()).recordMerged(any());
    }

    @Test
    void mergeEnqueuesInsteadOfMergingWhenTheBranchHasAMergeQueue()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        PullRequestRef ref = new PullRequestRef("acme", "widget", 145);
        when(pullRequests.probeMergeQueue("ghp", ref))
                .thenReturn(Optional.of(new PullRequestRepository.MergeQueueProbe("PR_nodeid123")));
        when(pullRequests.enqueuePullRequest("ghp", "PR_nodeid123"))
                .thenReturn(MergeResult.enqueued("Queued"));

        PR result = service.merge("pr1", "squash");

        verify(pullRequests, never()).mergePullRequest(any(), any(), any());
        verify(prService, never()).recordMerged(any());
        assertThat(result.status()).isEqualTo(PR.STATUS_REMOTE_OPEN);
    }

    @Test
    void mergeFallsBackToEnqueueOnA405RequiringTheMergeQueue()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        PullRequestRef ref = new PullRequestRef("acme", "widget", 145);
        // Probe sees no queue (a ruleset-driven queue GraphQL can't see), so a
        // direct merge is attempted first and bounces with GitHub's 405.
        when(pullRequests.mergePullRequest(eq("ghp"), eq(ref), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "requires merge queue"));
        when(pullRequests.pullRequestNodeId("ghp", ref)).thenReturn(Optional.of("PR_nodeid456"));
        when(pullRequests.enqueuePullRequest("ghp", "PR_nodeid456"))
                .thenReturn(MergeResult.enqueued("Queued"));

        PR result = service.merge("pr1", "squash");

        verify(pullRequests).enqueuePullRequest("ghp", "PR_nodeid456");
        verify(prService, never()).recordMerged(any());
        assertThat(result.status()).isEqualTo(PR.STATUS_REMOTE_OPEN);
    }

    @Test
    void dequeueRemovesThePrFromTheMergeQueue()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.dequeue("pr1");

        verify(pullRequests).dequeuePullRequest("ghp", new PullRequestRef("acme", "widget", 145));
    }

    @Test
    void deleteBranchDeletesOnGitHubAndStampsBranchDeletedAt()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_MERGED)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        PR deleted = pushedPr(PR.STATUS_MERGED);
        when(prService.recordBranchDeleted("pr1")).thenReturn(deleted);

        PR result = service.deleteBranch("pr1");

        verify(pullRequests).deleteBranch("ghp", new PullRequestRef("acme", "widget", 145), "feature/x");
        assertThat(result).isSameAs(deleted);
    }

    @Test
    void deleteBranchRejectsAPrThatIsNotMerged()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));

        assertThatThrownBy(() -> service.deleteBranch("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("is not merged");
        verify(prService, never()).recordBranchDeleted(any());
    }

    @Test
    void mergeResolvesTheRemoteDirectlyForAnExternalPrWithoutTouchingAnyTask()
            throws Exception
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        PullRequestRef ref = new PullRequestRef("acme", "widget", 99);
        when(pullRequests.mergePullRequest(eq("ghp"), eq(ref), any()))
                .thenReturn(new MergeResult("sha123", true, "Merged"));
        when(prService.recordMerged("pr-ext")).thenReturn(externalPr());

        service.merge("pr-ext", "squash");

        verify(pullRequests).mergePullRequest(eq("ghp"), eq(ref), any());
        verify(taskStore, never()).findTaskById(any());
    }

    @Test
    void dequeueResolvesTheRemoteDirectlyForAnExternalPr()
            throws Exception
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.dequeue("pr-ext");

        verify(pullRequests).dequeuePullRequest("ghp", new PullRequestRef("acme", "widget", 99));
        verify(taskStore, never()).findTaskById(any());
    }

    @Test
    void deleteBranchResolvesTheRemoteDirectlyForAnExternalPr()
            throws Exception
    {
        PR merged = new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "", PR.STATUS_MERGED, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, NOW, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
        when(prService.findById("pr-ext")).thenReturn(Optional.of(merged));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(prService.recordBranchDeleted("pr-ext")).thenReturn(merged);

        service.deleteBranch("pr-ext");

        verify(pullRequests).deleteBranch("ghp", new PullRequestRef("acme", "widget", 99), "feature/y");
        verify(taskStore, never()).findTaskById(any());
    }

    private PR externalPr()
    {
        return new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
    }

    private static PRComment draft(String id, String scope, String filePath, Integer lineNumber, String body)
    {
        return new PRComment(id, "pr-ext", PRComment.ORIGIN_LOCAL, scope,
                filePath, lineNumber, "you", body, NOW, null, null, null, null, null,
                "RIGHT", null, null);
    }

    @Test
    void publishReviewBatchesDraftsIntoOneGitHubReviewThenMarksThemPublished()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        PRComment prLevel = draft("cm1", PRComment.SCOPE_PR, null, null, "Nice work overall.");
        PRComment lineLevel = draft("cm2", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "Fix this.");
        when(prService.comments("pr-ext")).thenReturn(List.of(prLevel, lineLevel));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr-ext");

        verify(pullRequests).createReview(eq("ghp"), eq(new PullRequestRef("acme", "widget", 99)), any());
        verify(prService).markPublished(eq("cm1"), any());
        verify(prService).markPublished(eq("cm2"), any());
    }

    @Test
    void publishReviewRejectsATaskOriginPr()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));

        assertThatThrownBy(() -> service.publishReview("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only applies to external PRs");
    }

    @Test
    void publishReviewRejectsWhenThereAreNoDraftsToPublish()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        when(prService.comments("pr-ext")).thenReturn(List.of());

        assertThatThrownBy(() -> service.publishReview("pr-ext"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no draft comments");
        verify(pullRequests, never()).createReview(any(), any(), any());
    }
}
