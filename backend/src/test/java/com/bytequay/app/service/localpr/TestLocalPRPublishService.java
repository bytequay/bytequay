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

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRCheck;
import com.bytequay.app.domain.LocalPRComment;
import com.bytequay.app.domain.LocalPRTimelineEvent;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.review.BrainReviewService;
import org.junit.jupiter.api.Test;
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
 * {@link LocalPRService#recordPush} (which strips locals + flips status).
 */
class TestLocalPRPublishService
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final LocalPRService localPr = mock(LocalPRService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final PatResolver patResolver = mock(PatResolver.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final LocalPRPublishService service =
            new LocalPRPublishService(localPr, taskStore, git, pullRequests, patResolver, brainReview);

    private LocalPR localPr(String status)
    {
        return LocalPR.create("pr1", "task1", "feature/x", "main", "Add cache", "desc", NOW)
                .withStatus(status, NOW);
    }

    private LocalPR pushedPr(String status)
    {
        return LocalPR.create("pr1", "task1", "feature/x", "main", "Add cache", "desc", NOW)
                .withRemote(145, "https://github.com/acme/widget/pull/145", NOW)
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
        when(localPr.findById("pr1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(Path.of("/tmp/wt/feature-x"), "origin/feature/x")).thenReturn(false);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(eq("ghp"), eq(new RepoRef("acme", "widget")), any()))
                .thenReturn(opened(145));
        LocalPR flipped = localPr(LocalPR.STATUS_REMOTE_DRAFTED);
        when(localPr.recordPush("pr1", 145, "https://github.com/acme/widget/pull/145")).thenReturn(flipped);

        LocalPR result = service.push("pr1");

        verify(git).push(Path.of("/tmp/wt/feature-x"));
        verify(taskStore).markPushed(eq("task1"), any());
        verify(taskStore).linkPullRequest("task1", 145, "draft");
        verify(taskStore).linkTaskToPr("task1", "acme/widget#145");
        verify(localPr).recordPush("pr1", 145, "https://github.com/acme/widget/pull/145");
        assertThat(result).isSameAs(flipped);
    }

    @Test
    void pushSkipsGitPushWhenTheBranchIsAlreadyOnOrigin()
            throws Exception
    {
        when(localPr.findById("pr1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(Path.of("/tmp/wt/feature-x"), "origin/feature/x")).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(any(), any(), any())).thenReturn(opened(7));
        when(localPr.recordPush(any(), eq(7), any())).thenReturn(localPr(LocalPR.STATUS_REMOTE_DRAFTED));

        service.push("pr1");

        verify(git, never()).push(any());
        verify(pullRequests).createPullRequest(any(), any(), any());
    }

    @Test
    void pushRejectsAPrThatIsNotLocalOpen()
    {
        when(localPr.findById("pr1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_DRAFTED)));

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
        when(localPr.findByTask("task1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_OPEN)));

        service.onPushedElsewhere(new LocalPrPushedEvent("task1", 145, "https://github.com/acme/widget/pull/145"));

        verify(localPr).recordPush("pr1", 145, "https://github.com/acme/widget/pull/145");
        verify(brainReview, never()).reviewBeforeLocalOpen(any(), any());
    }

    @Test
    void onPushedElsewhereRunsTheBrainReviewFirstWhenStillLocalDrafted()
    {
        when(localPr.findByTask("task1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_DRAFTED)));
        when(brainReview.reviewBeforeLocalOpen("pr1", LocalPRTimelineEvent.ACTOR_AGENT))
                .thenReturn(localPr(LocalPR.STATUS_LOCAL_OPEN));

        service.onPushedElsewhere(new LocalPrPushedEvent("task1", 145, "https://github.com/acme/widget/pull/145"));

        verify(brainReview).reviewBeforeLocalOpen("pr1", LocalPRTimelineEvent.ACTOR_AGENT);
        verify(localPr).recordPush("pr1", 145, "https://github.com/acme/widget/pull/145");
    }

    @Test
    void onPushedElsewhereIsANoOpForATaskWithNoLocalPr()
    {
        when(localPr.findByTask("task-none")).thenReturn(Optional.empty());

        service.onPushedElsewhere(new LocalPrPushedEvent("task-none", 145, "https://github.com/x/y/pull/145"));

        verify(localPr, never()).recordPush(any(), anyInt(), any());
    }

    @Test
    void pushRejectsAnUnknownPr()
    {
        when(localPr.findById("pr1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.push("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no local PR");
    }

    @Test
    void pushRejectsAnOpenCommentThread()
    {
        when(localPr.findById("pr1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_OPEN)));
        when(localPr.comments("pr1")).thenReturn(List.of(comment(null, null)));

        assertThatThrownBy(() -> service.push("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("open comment thread");
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void pushAllowsResolvedAndDismissedThreads()
            throws Exception
    {
        when(localPr.findById("pr1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_OPEN)));
        when(localPr.comments("pr1")).thenReturn(List.of(comment(NOW, null), comment(null, NOW)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(any(), any())).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(any(), any(), any())).thenReturn(opened(7));
        when(localPr.recordPush(any(), eq(7), any())).thenReturn(localPr(LocalPR.STATUS_REMOTE_DRAFTED));

        service.push("pr1");

        verify(pullRequests).createPullRequest(any(), any(), any());
    }

    @Test
    void pushRejectsAFailingLocalTestRun()
    {
        when(localPr.findById("pr1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_OPEN)));
        when(localPr.checks("pr1")).thenReturn(List.of(
                check(Instant.parse("2026-07-01T00:00:00Z"), LocalPRCheck.STATUS_PASSED),
                check(Instant.parse("2026-07-01T00:05:00Z"), LocalPRCheck.STATUS_FAILED)));

        assertThatThrownBy(() -> service.push("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("failing local test run");
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void pushAllowsAFixedRunEvenAfterAnEarlierFailure()
            throws Exception
    {
        when(localPr.findById("pr1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_OPEN)));
        when(localPr.checks("pr1")).thenReturn(List.of(
                check(Instant.parse("2026-07-01T00:00:00Z"), LocalPRCheck.STATUS_FAILED),
                check(Instant.parse("2026-07-01T00:05:00Z"), LocalPRCheck.STATUS_PASSED)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(any(), any())).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(any(), any(), any())).thenReturn(opened(7));
        when(localPr.recordPush(any(), eq(7), any())).thenReturn(localPr(LocalPR.STATUS_REMOTE_DRAFTED));

        service.push("pr1");

        verify(pullRequests).createPullRequest(any(), any(), any());
    }

    @Test
    void pushAllowsAPrWithNoLocalChecksRecordedAtAll()
            throws Exception
    {
        // No recognised test runner for this repo — nothing to gate on.
        when(localPr.findById("pr1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(git.refExists(any(), any())).thenReturn(true);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.createPullRequest(any(), any(), any())).thenReturn(opened(7));
        when(localPr.recordPush(any(), eq(7), any())).thenReturn(localPr(LocalPR.STATUS_REMOTE_DRAFTED));

        service.push("pr1");

        verify(pullRequests).createPullRequest(any(), any(), any());
    }

    private static LocalPRComment comment(Instant resolvedAt, Instant dismissedAt)
    {
        return new LocalPRComment("cm1", "pr1", LocalPRComment.ORIGIN_LOCAL, LocalPRComment.SCOPE_PR,
                null, null, "you", "note", NOW, resolvedAt, dismissedAt, null, null);
    }

    private static LocalPRCheck check(Instant startedAt, String status)
    {
        return new LocalPRCheck("c1", "pr1", LocalPRCheck.KIND_LOCAL, "maven test", status,
                1000L, startedAt, startedAt, null);
    }

    @Test
    void mergeMergesTheRemotePrAndFlipsToMerged()
            throws Exception
    {
        when(localPr.findById("pr1")).thenReturn(Optional.of(pushedPr(LocalPR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.mergePullRequest(eq("ghp"), eq(new PullRequestRef("acme", "widget", 145)), any()))
                .thenReturn(new MergeResult("sha123", true, "Merged"));
        LocalPR merged = pushedPr(LocalPR.STATUS_MERGED);
        when(localPr.recordMerged("pr1")).thenReturn(merged);

        LocalPR result = service.merge("pr1", "squash");

        verify(pullRequests).mergePullRequest(
                eq("ghp"), eq(new PullRequestRef("acme", "widget", 145)), any());
        verify(localPr).recordMerged("pr1");
        assertThat(result).isSameAs(merged);
    }

    @Test
    void mergeRejectsAPrThatWasNeverPushed()
    {
        when(localPr.findById("pr1")).thenReturn(Optional.of(localPr(LocalPR.STATUS_LOCAL_OPEN)));

        assertThatThrownBy(() -> service.merge("pr1", "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("has not been pushed");
    }

    @Test
    void mergeSurfacesGitHubRefusalWithoutFlipping()
    {
        when(localPr.findById("pr1")).thenReturn(Optional.of(pushedPr(LocalPR.STATUS_REMOTE_OPEN)));
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
        verify(localPr, never()).recordMerged(any());
    }
}
