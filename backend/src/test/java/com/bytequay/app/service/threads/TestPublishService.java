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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PrPushedEvent;
import com.bytequay.app.service.review.ReviewPassResolver;
import com.bytequay.app.service.threads.PublishService.PublishResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link PublishService} — the side-effect-running
 * half of the publish gate. McpController parks; this is where the
 * push and createIssueComment calls actually happen, the notification
 * is claimed exactly once, and the audit row lands. All dependencies
 * are mocked so the test is fast and deterministic (no real git, no
 * GitHub).
 */
class TestPublishService
{
    private NotificationService notifications;
    private TaskStore taskStore;
    private GitRunner git;
    private PullRequestRepository pullRequests;
    private PatResolver patResolver;
    private ObjectMapper mapper;
    private ParkedProposalService parkedProposals;
    private TaskService taskService;
    private TaskPhaseMachine phaseMachine;
    private StageStore stageStore;
    private PRService prService;
    private ApplicationEventPublisher eventPublisher;
    private PublishService service;

    @BeforeEach
    void setUp()
    {
        notifications = mock(NotificationService.class);
        taskStore = mock(TaskStore.class);
        git = mock(GitRunner.class);
        pullRequests = mock(PullRequestRepository.class);
        patResolver = mock(PatResolver.class);
        mapper = new ObjectMapper();
        parkedProposals = mock(ParkedProposalService.class);
        taskService = mock(TaskService.class);
        phaseMachine = mock(TaskPhaseMachine.class);
        stageStore = mock(StageStore.class);
        prService = mock(PRService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new PublishService(
                notifications, taskStore, git, pullRequests, patResolver, mapper, parkedProposals, taskService,
                mock(ReviewPassResolver.class), phaseMachine, stageStore, prService, eventPublisher);
        when(notifications.claimResolution(anyString())).thenReturn(true);
        when(stageStore.findUnresolvedComments(anyString())).thenReturn(List.of());
        when(prService.findByTask(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void approvePushRunsGitPushCompletesTaskDismissesParkedRowAndWritesApprovedAudit()
            throws Exception
    {
        Notification parked = parkedPush("notif-1", "task-1",
                "feature/x", "/tmp/wt/feature-x");
        when(notifications.find("notif-1")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-1"))
                .thenReturn(Optional.of(taskAt("task-1", TaskStatus.AWAITING_REVIEW)));

        PublishResult result = service.approve("notif-1", null, "push");

        assertThat(result.ok()).isTrue();
        assertThat(result.resolution()).isEqualTo("approved");
        assertThat(result.action()).isEqualTo("push");
        assertThat(result.message()).contains("Pushed feature/x");

        verify(git).pushForceWithLease(Path.of("/tmp/wt/feature-x"));
        verify(parkedProposals).finishApproved(parked, false);
        verify(notifications).claimResolution("notif-1");
        assertAuditRowWritten(parked, "approved", "push", "Pushed feature/x");
        // The approved push advances the task onto the remote spine.
        verify(phaseMachine).observe("task-1", TaskPhase.PUSHED_AWAITING_CI, "publish_approved");
    }

    @Test
    void approvePushOnATaskWithAnExistingPrPublishesAPrPushedEvent()
    {
        // A plain push gate never creates a PR itself — but if the task
        // already has one (e.g. pushing more commits after addressing
        // comments), the PR row must still learn about it, or the panel
        // keeps offering "ready to push" for a push that just landed.
        Notification parked = parkedPush("notif-existing-pr", "task-existing-pr",
                "feature/x", "/tmp/wt/feature-x");
        when(notifications.find("notif-existing-pr")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-existing-pr"))
                .thenReturn(Optional.of(taskWithLinkedPr("task-existing-pr", "acme/widget#42")));

        service.approve("notif-existing-pr", null, "push");

        verify(eventPublisher).publishEvent(
                new PrPushedEvent("task-existing-pr", "acme/widget", 42, "https://github.com/acme/widget/pull/42"));
    }

    @Test
    void approvePushOnATaskWithNoPrYetPublishesNoEvent()
    {
        Notification parked = parkedPush("notif-no-pr", "task-no-pr",
                "feature/x", "/tmp/wt/feature-x");
        when(notifications.find("notif-no-pr")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-no-pr"))
                .thenReturn(Optional.of(taskAt("task-no-pr", TaskStatus.AWAITING_REVIEW)));

        service.approve("notif-no-pr", null, "push");

        verify(eventPublisher, never()).publishEvent(any(PrPushedEvent.class));
    }

    @Test
    void approvePushReleasesTheGateForRetryWhenGitPushThrows()
            throws Exception
    {
        Notification parked = parkedPush("notif-2", "task-2",
                "feature/y", "/tmp/wt/feature-y");
        when(notifications.find("notif-2")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-2"))
                .thenReturn(Optional.of(taskAt("task-2", TaskStatus.AWAITING_REVIEW)));
        // A failed push means nothing reached the remote — IOException is what
        // GitRunner surfaces for a rejection / network blip.
        doThrow(new IOException("rejected: non-fast-forward"))
                .when(git).pushForceWithLease(any(Path.class));

        // The gate is released back to UNREAD (safe to retry) and the caller
        // gets a clear error — not a pinned RESOLVING claim.
        assertThatThrownBy(() -> service.approve("notif-2", null, "push"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("push failed");
        verify(notifications).releaseResolution("notif-2");
        verify(parkedProposals, never()).finishApproved(any(), anyBoolean());
    }

    @Test
    void approvePostCommentRunsCreateIssueCommentUsingEditedBodyWhenProvided()
            throws Exception
    {
        Notification parked = parkedPostComment("notif-3", "task-3",
                "acme", "widget", 42, "LGTM, ship it.");
        when(notifications.find("notif-3")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-3"))
                .thenReturn(Optional.of(taskAt("task-3", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");

        String edited = "LGTM, ship after CI is green.";
        PublishResult result = service.approve("notif-3", edited, "post_comment");

        assertThat(result.ok()).isTrue();
        assertThat(result.resolution()).isEqualTo("approved");
        assertThat(result.action()).isEqualTo("post_comment");
        verify(pullRequests).createIssueComment(
                eq("ghp_secret"), eq(new PullRequestRef("acme", "widget", 42)), eq(edited));
        verify(notifications).claimResolution("notif-3");
        verify(parkedProposals).finishApproved(parked, false);
    }

    @Test
    void approveMergeFallsBackToEnqueueWhenARulesetRequiresTheMergeQueue()
            throws Exception
    {
        Notification parked = parkedMergePr("notif-mq", "task-mq", "trinodb", "trino", 30070);
        when(notifications.find("notif-mq")).thenReturn(Optional.of(parked));
        when(patResolver.resolve("trinodb/trino")).thenReturn("ghp_secret");
        PullRequestRef ref = new PullRequestRef("trinodb", "trino", 30070);
        // No queue is visible to the probe (Mockito default Optional.empty()),
        // so the direct merge runs and GitHub 405s for the ruleset rule.
        when(pullRequests.mergePullRequest(eq("ghp_secret"), eq(ref), any()))
                .thenThrow(new ResponseStatusException(HttpStatusCode.valueOf(405),
                        "Repository rule violations found\n\nChanges must be made through the merge queue\n\n"));
        when(pullRequests.pullRequestNodeId("ghp_secret", ref)).thenReturn(Optional.of("PR_node_1"));
        when(pullRequests.enqueuePullRequest("ghp_secret", "PR_node_1"))
                .thenReturn(MergeResult.enqueued("Added to merge queue"));

        PublishResult result = service.approve("notif-mq", null, "merge_pr");

        assertThat(result.ok()).isTrue();
        assertThat(result.resolution()).isEqualTo("approved");
        assertThat(result.message()).contains("merge queue");
        verify(pullRequests).enqueuePullRequest("ghp_secret", "PR_node_1");
        verify(parkedProposals).finishApproved(parked, false);
    }

    private static Notification parkedMergePr(
            String notificationId, String taskId, String owner, String repo, int number)
    {
        String json = "{"
                + "\"action\":\"merge_pr\","
                + "\"pr\":{\"owner\":" + quote(owner) + ",\"repo\":" + quote(repo)
                + ",\"number\":" + number + "},"
                + "\"source\":\"mcp:merge_pr\""
                + "}";
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD, json, Instant.now(), null);
    }

    @Test
    void approveNextTaskAlsoSurfacesAsSuccessWhenAConcurrentResolverRacesAfterTheAdvanceLands()
    {
        // Same race as the post_comment case, but for an action whose
        // local finalization runs with taskAlreadyAdvanced=true. The
        // catch-side 409 → approved_concurrent branch must apply to
        // every action class, not just the body-payload ones.
        Notification parked = parkedNextTask("notif-next-race", "task-next-race");
        when(notifications.find("notif-next-race")).thenReturn(Optional.of(parked));
        Task target = taskAt("task-next-race", TaskStatus.AWAITING_REVIEW);
        when(taskStore.findTaskById("task-next-race")).thenReturn(Optional.of(target));
        Task successor = taskAt("task-next-race-2", TaskStatus.RUNNING);
        when(taskService.startNextFromApprovedParkedTask(
                eq("thread-task-next-race"), eq("task-next-race"), any()))
                .thenReturn(successor);
        doThrow(new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "notification already resolved: notif-next-race"))
                .when(parkedProposals).finishApproved(parked, /* taskAlreadyAdvanced */ true);

        PublishResult result = service.approve("notif-next-race", null, "next_task");

        // The advance succeeded (successor task was created), so the
        // user-visible result is approved even though the local finalize
        // raced a concurrent discard.
        assertThat(result.ok()).isTrue();
        assertThat(result.resolution()).isEqualTo("approved");
        verify(taskService).startNextFromApprovedParkedTask(
                eq("thread-task-next-race"), eq("task-next-race"), any());
        assertAuditRowWritten(parked, "approved_concurrent", "next_task",
                "another resolver finalized this row first");
    }

    @Test
    void approvePostCommentReturnsSuccessWhenAConcurrentResolverRacesAfterTheRemoteFires()
    {
        // Tab A approves while Tab B discards concurrently. Tab A's
        // remote call succeeds, but Tab B's finishResolution wins the
        // atomic RESOLVING→RESOLVED transition. parkedProposals.finishApproved
        // raises a 409 inside finishClaim. The approve path must surface
        // this as a clean success (the remote DID complete) with an
        // `approved_concurrent` audit row, not as an interrupted result
        // that would leave Tab A's UI stuck on "Finish locally".
        Notification parked = parkedPostComment("notif-race", "task-race",
                "acme", "widget", 99, "LGTM.");
        when(notifications.find("notif-race")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-race"))
                .thenReturn(Optional.of(taskAt("task-race", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        doThrow(new ResponseStatusException(
                HttpStatusCode.valueOf(409),
                "notification already resolved: notif-race"))
                .when(parkedProposals).finishApproved(parked, false);

        PublishResult result = service.approve("notif-race", null, "post_comment");

        // Remote action ran (the discard didn't roll back GitHub's
        // createIssueComment), so the PublishResult must read as
        // "approved" — not interrupted.
        assertThat(result.ok()).isTrue();
        assertThat(result.resolution()).isEqualTo("approved");
        verify(pullRequests).createIssueComment(
                eq("ghp_secret"), eq(new PullRequestRef("acme", "widget", 99)), anyString());
        // The audit row records the race so a reader can correlate
        // with the concurrent discard's audit chain.
        assertAuditRowWritten(parked, "approved_concurrent", "post_comment",
                "another resolver finalized this row first");
    }

    @Test
    void approvePostCommentFallsBackToParkedBodyWhenEditedBodyIsBlank()
            throws Exception
    {
        Notification parked = parkedPostComment("notif-4", "task-4",
                "acme", "widget", 7, "Looks good.");
        when(notifications.find("notif-4")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-4"))
                .thenReturn(Optional.of(taskAt("task-4", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");

        service.approve("notif-4", "   ", "post_comment");

        // Blank editedBody means "user clicked Approve without editing"
        // — the parked body is what gets posted.
        verify(pullRequests).createIssueComment(
                eq("ghp_secret"),
                eq(new PullRequestRef("acme", "widget", 7)),
                eq("Looks good."));
    }

    @Test
    void approveResolveReviewThreadMapsRootCommentIdToNodeIdAndRunsTheGraphqlResolve()
    {
        // Resolve runs over GraphQL keyed on the thread's opaque node id,
        // not the REST root comment id the agent parks. The approve path
        // must map root id → node id live, then fire resolveReviewThread.
        Notification parked = parkedResolveReviewThread("notif-res", "task-res",
                "acme", "widget", 42, 555L, true);
        when(notifications.find("notif-res")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-res"))
                .thenReturn(Optional.of(taskAt("task-res", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.fetchReviewThreadResolution("ghp_secret", new PullRequestRef("acme", "widget", 42)))
                .thenReturn(List.of(
                        new PullRequestRepository.ReviewThreadMeta(111L, "NODE_OTHER", false),
                        new PullRequestRepository.ReviewThreadMeta(555L, "NODE_TARGET", false)));

        PublishResult result = service.approve("notif-res", null, "resolve_review_thread");

        assertThat(result.ok()).isTrue();
        assertThat(result.action()).isEqualTo("resolve_review_thread");
        assertThat(result.message()).contains("Resolved").contains("acme/widget#42");
        verify(pullRequests).resolveReviewThread("ghp_secret", "NODE_TARGET");
        verify(pullRequests, never()).unresolveReviewThread(anyString(), anyString());
    }

    @Test
    void approveResolveReviewThreadCallsUnresolveWhenResolvedFlagIsFalse()
    {
        Notification parked = parkedResolveReviewThread("notif-unres", "task-unres",
                "acme", "widget", 7, 555L, false);
        when(notifications.find("notif-unres")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-unres"))
                .thenReturn(Optional.of(taskAt("task-unres", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.fetchReviewThreadResolution("ghp_secret", new PullRequestRef("acme", "widget", 7)))
                .thenReturn(List.of(new PullRequestRepository.ReviewThreadMeta(555L, "NODE_TARGET", true)));

        PublishResult result = service.approve("notif-unres", null, "resolve_review_thread");

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("Unresolved");
        verify(pullRequests).unresolveReviewThread("ghp_secret", "NODE_TARGET");
        verify(pullRequests, never()).resolveReviewThread(anyString(), anyString());
    }

    @Test
    void approveResolveReviewThreadRefusesWithNotFoundWhenNoThreadMatchesTheRootCommentId()
    {
        Notification parked = parkedResolveReviewThread("notif-miss", "task-miss",
                "acme", "widget", 7, 999L, true);
        when(notifications.find("notif-miss")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-miss"))
                .thenReturn(Optional.of(taskAt("task-miss", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.fetchReviewThreadResolution("ghp_secret", new PullRequestRef("acme", "widget", 7)))
                .thenReturn(List.of(new PullRequestRepository.ReviewThreadMeta(555L, "NODE_OTHER", false)));

        assertThatThrownBy(() -> service.approve("notif-miss", null, "resolve_review_thread"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no review thread with root comment id 999");

        verify(pullRequests, never()).resolveReviewThread(anyString(), anyString());
        verify(pullRequests, never()).unresolveReviewThread(anyString(), anyString());
        // A 4xx-class failure releases the claim so the row stays actionable.
        verify(notifications).releaseResolution("notif-miss");
    }

    @Test
    void approveRequestReviewCompletesTaskWithoutPublishingRemotely()
            throws Exception
    {
        Notification parked = parkedRequestReview("notif-review", "task-review");
        when(notifications.find("notif-review")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-review"))
                .thenReturn(Optional.of(taskAt("task-review", TaskStatus.AWAITING_REVIEW)));

        PublishResult result = service.approve("notif-review", null, "request_review");

        assertThat(result.action()).isEqualTo("request_review");
        assertThat(result.message()).contains("No remote changes");
        verify(git, never()).push(any());
        verify(pullRequests, never()).createIssueComment(anyString(), any(), anyString());
        verify(parkedProposals).finishApproved(parked, false);
        verify(notifications).claimResolution("notif-review");
    }

    @Test
    void approveAcceptsLegacyRequestReviewPayloadWithoutAnAction()
    {
        Notification parked = new Notification(
                "notif-legacy-review", NotificationKind.AWAITING_REVIEW, "thread-task-legacy", "task-legacy",
                NotificationStatus.UNREAD,
                "{\"summary\":\"Ready\",\"source\":\"mcp:request_review\"}",
                Instant.now(), null);
        when(notifications.find("notif-legacy-review")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-legacy"))
                .thenReturn(Optional.of(taskAt("task-legacy", TaskStatus.AWAITING_REVIEW)));

        PublishResult result = service.approve("notif-legacy-review", null, "request_review");

        assertThat(result.action()).isEqualTo("request_review");
        verify(notifications).claimResolution("notif-legacy-review");
    }

    @Test
    void approveNextTaskDelegatesAdvanceOnlyAfterApprovalAndKeepsPriorTaskParked()
    {
        Notification parked = parkedNextTask("notif-next", "task-next");
        when(notifications.find("notif-next")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-next"))
                .thenReturn(Optional.of(taskAt("task-next", TaskStatus.AWAITING_REVIEW)));
        when(taskService.startNextFromApprovedParkedTask(eq("thread-task-next"), eq("task-next"), any()))
                .thenReturn(taskAt("task-successor", TaskStatus.PENDING));

        PublishResult result = service.approve("notif-next", null, "next_task");

        assertThat(result.action()).isEqualTo("next_task");
        assertThat(result.message()).contains("task-successor");
        ArgumentCaptor<TaskService.ShipRequest> request =
                ArgumentCaptor.forClass(TaskService.ShipRequest.class);
        verify(taskService).startNextFromApprovedParkedTask(
                eq("thread-task-next"), eq("task-next"), request.capture());
        assertThat(request.getValue().nextTitle()).isEqualTo("Follow-up");
        assertThat(request.getValue().baseMode()).isEqualTo(TaskService.BaseMode.STACKED);
        verify(taskStore, never()).saveTask(any());
        verify(notifications).claimResolution("notif-next");
        verify(parkedProposals).finishApproved(parked, true);
    }

    @Test
    void approveShipTaskDelegatesTerminalAdvanceOnlyAfterApproval()
    {
        Notification parked = parkedShipTask("notif-ship", "task-ship");
        when(notifications.find("notif-ship")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-ship"))
                .thenReturn(Optional.of(taskAt("task-ship", TaskStatus.AWAITING_REVIEW)));
        when(taskService.shipApprovedParkedTask(eq("thread-task-ship"), eq("task-ship"), any()))
                .thenReturn(taskAt("task-after-ship", TaskStatus.PENDING));

        PublishResult result = service.approve("notif-ship", null, "ship_task");

        assertThat(result.action()).isEqualTo("ship_task");
        ArgumentCaptor<TaskService.ShipRequest> request =
                ArgumentCaptor.forClass(TaskService.ShipRequest.class);
        verify(taskService).shipApprovedParkedTask(
                eq("thread-task-ship"), eq("task-ship"), request.capture());
        assertThat(request.getValue().nextTitle()).isEqualTo("After ship");
        assertThat(request.getValue().baseMode()).isEqualTo(TaskService.BaseMode.MAIN);
        verify(notifications).claimResolution("notif-ship");
        verify(parkedProposals).finishApproved(parked, true);
    }

    @Test
    void approveShipTaskThreadsProposedPrTitleAndBodyIntoTheShipRequest()
    {
        Notification parked = parkedShipTaskWithPr(
                "notif-ship-pr", "task-ship-pr", "Add cache layer", "Caches reads.");
        when(notifications.find("notif-ship-pr")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-ship-pr"))
                .thenReturn(Optional.of(taskAt("task-ship-pr", TaskStatus.AWAITING_REVIEW)));
        when(taskService.shipApprovedParkedTask(eq("thread-task-ship-pr"), eq("task-ship-pr"), any()))
                .thenReturn(taskAt("task-after", TaskStatus.PENDING));

        service.approve("notif-ship-pr", null, "ship_task");

        ArgumentCaptor<TaskService.ShipRequest> request =
                ArgumentCaptor.forClass(TaskService.ShipRequest.class);
        verify(taskService).shipApprovedParkedTask(
                eq("thread-task-ship-pr"), eq("task-ship-pr"), request.capture());
        assertThat(request.getValue().prTitle()).isEqualTo("Add cache layer");
        assertThat(request.getValue().prBody()).isEqualTo("Caches reads.");
    }

    @Test
    void approveShipTaskRejectedWhenTaskHasUnresolvedLocalReviewComments()
    {
        Notification parked = parkedShipTask("notif-ship-gate", "task-ship-gate");
        when(notifications.find("notif-ship-gate")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-ship-gate"))
                .thenReturn(Optional.of(taskAt("task-ship-gate", TaskStatus.AWAITING_REVIEW)));
        when(stageStore.findUnresolvedComments("task-ship-gate"))
                .thenReturn(List.of(localComment("task-ship-gate")));

        assertThatThrownBy(() -> service.approve("notif-ship-gate", null, "ship_task"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("open review comment");

        // The gate runs in preflight, before any claim or remote side effect.
        verify(notifications, never()).claimResolution("notif-ship-gate");
        verify(taskService, never()).shipApprovedParkedTask(anyString(), anyString(), any());
    }

    @Test
    void approveShipTaskSucceedsOnceLocalReviewCommentsAreResolved()
    {
        Notification parked = parkedShipTask("notif-ship-ok", "task-ship-ok");
        when(notifications.find("notif-ship-ok")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-ship-ok"))
                .thenReturn(Optional.of(taskAt("task-ship-ok", TaskStatus.AWAITING_REVIEW)));
        when(stageStore.findUnresolvedComments("task-ship-ok")).thenReturn(List.of());
        when(taskService.shipApprovedParkedTask(eq("thread-task-ship-ok"), eq("task-ship-ok"), any()))
                .thenReturn(taskAt("task-after-ok", TaskStatus.PENDING));

        PublishResult result = service.approve("notif-ship-ok", null, "ship_task");

        assertThat(result.action()).isEqualTo("ship_task");
        verify(taskService).shipApprovedParkedTask(eq("thread-task-ship-ok"), eq("task-ship-ok"), any());
    }

    @Test
    void approvePostCommentNotGatedByUnresolvedReviewComments()
    {
        // The ship gate is for advance proposals only — a comment publish
        // must still work even with open review comments on the task.
        Notification parked = parkedPostComment(
                "notif-comment-gate", "task-comment-gate", "acme", "widget", 7, "Looks good.");
        when(notifications.find("notif-comment-gate")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-comment-gate"))
                .thenReturn(Optional.of(taskAt("task-comment-gate", TaskStatus.AWAITING_REVIEW)));
        when(stageStore.findUnresolvedComments("task-comment-gate"))
                .thenReturn(List.of(localComment("task-comment-gate")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");

        PublishResult result = service.approve("notif-comment-gate", null, "post_comment");

        assertThat(result.ok()).isTrue();
        verify(pullRequests).createIssueComment(eq("ghp_secret"), any(), eq("Looks good."));
    }

    @Test
    void updateShipDescriptionRewritesTheParkedPayloadsPrTitleAndBody()
    {
        Notification parked = parkedShipTask("notif-edit", "task-edit");
        when(notifications.find("notif-edit")).thenReturn(Optional.of(parked));
        Notification rewritten = new Notification(
                "notif-edit", NotificationKind.AWAITING_REVIEW, "thread-task-edit", "task-edit",
                NotificationStatus.UNREAD, "{}", Instant.now(), null);
        when(notifications.updatePayload(eq("notif-edit"), anyString())).thenReturn(rewritten);

        service.updateShipDescription("notif-edit", "Edited title", "Edited body");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notifications).updatePayload(eq("notif-edit"), payload.capture());
        JsonNode tree;
        try {
            tree = mapper.readTree(payload.getValue());
        }
        catch (Exception e) {
            throw new AssertionError("payload not valid JSON: " + payload.getValue(), e);
        }
        assertThat(tree.path("action").asText()).isEqualTo("ship_task");
        assertThat(tree.path("prTitle").asText()).isEqualTo("Edited title");
        assertThat(tree.path("prBody").asText()).isEqualTo("Edited body");
    }

    @Test
    void updateShipDescriptionRejectsNonShipProposal()
    {
        Notification parked = parkedNextTask("notif-not-ship", "task-not-ship");
        when(notifications.find("notif-not-ship")).thenReturn(Optional.of(parked));

        assertThatThrownBy(() -> service.updateShipDescription("notif-not-ship", "t", "b"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a ship proposal");

        verify(notifications, never()).updatePayload(anyString(), anyString());
    }

    @Test
    void failedNextTaskApprovalLeavesInterruptedClaimWithoutRetryingPublish()
    {
        Notification parked = parkedNextTask("notif-next-fail", "task-next-fail");
        when(notifications.find("notif-next-fail")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-next-fail"))
                .thenReturn(Optional.of(taskAt("task-next-fail", TaskStatus.AWAITING_REVIEW)));
        when(taskService.startNextFromApprovedParkedTask(eq("thread-task-next-fail"), eq("task-next-fail"), any()))
                .thenThrow(new IllegalStateException("push rejected"));

        PublishResult result = service.approve("notif-next-fail", null, "next_task");

        assertThat(result.ok()).isFalse();
        verify(taskStore, never()).saveTask(any());
    }

    @Test
    void failedAdvanceApprovalWith4xxStaysInterruptedAndNeverReleasesTheClaim()
    {
        // next_task / ship_task push the branch BEFORE the PR-create call
        // that can 4xx, so a 4xx from the advance must NOT release the
        // claim for retry (that could double-push). It stays interrupted
        // like any ambiguous failure — unlike single-remote-call actions,
        // where a 4xx means nothing ran and the claim is released.
        Notification parked = parkedNextTask("notif-next-4xx", "task-next-4xx");
        when(notifications.find("notif-next-4xx")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-next-4xx"))
                .thenReturn(Optional.of(taskAt("task-next-4xx", TaskStatus.AWAITING_REVIEW)));
        when(taskService.startNextFromApprovedParkedTask(
                eq("thread-task-next-4xx"), eq("task-next-4xx"), any()))
                .thenThrow(new ResponseStatusException(
                        HttpStatusCode.valueOf(409), "branch already has an open PR"));

        PublishResult result = service.approve("notif-next-4xx", null, "next_task");

        assertThat(result.ok()).isFalse();
        assertThat(result.resolution()).isEqualTo("interrupted");
        verify(notifications, never()).releaseResolution("notif-next-4xx");
        assertAuditRowWritten(parked, "interrupted_unconfirmed", "next_task",
                "may or may not have run");
    }

    @Test
    void approveCannotRunSideEffectWhenAnotherRequestAlreadyClaimedNotification()
            throws Exception
    {
        Notification parked = parkedNextTask("notif-race", "task-race");
        when(notifications.find("notif-race")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-race"))
                .thenReturn(Optional.of(taskAt("task-race", TaskStatus.AWAITING_REVIEW)));
        when(notifications.claimResolution("notif-race")).thenReturn(false);

        assertThatThrownBy(() -> service.approve("notif-race", null, "next_task"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already resolved");

        verify(taskService, never()).startNextFromApprovedParkedTask(anyString(), anyString(), any());
        verify(git, never()).push(any());
    }

    @Test
    void approveRejectsChangedActionBeforeClaimingNotification()
    {
        Notification parked = parkedNextTask("notif-action", "task-action");
        when(notifications.find("notif-action")).thenReturn(Optional.of(parked));

        assertThatThrownBy(() -> service.approve("notif-action", null, "ship_task"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("notification action changed");

        verify(notifications, never()).claimResolution("notif-action");
        verify(taskService, never()).startNextFromApprovedParkedTask(anyString(), anyString(), any());
    }

    @Test
    void approveRequiresRenderedActionDiscriminator()
    {
        Notification parked = parkedPush("notif-no-action", "task-no-action",
                "feature/x", "/tmp/wt/feature-x");
        when(notifications.find("notif-no-action")).thenReturn(Optional.of(parked));

        assertThatThrownBy(() -> service.approve("notif-no-action", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expectedAction is required");

        verify(notifications, never()).claimResolution("notif-no-action");
    }

    @Test
    void invalidAdvancePayloadIsRejectedBeforeTheNotificationIsClaimed()
    {
        Notification parked = new Notification(
                "notif-bad-mode", NotificationKind.AWAITING_REVIEW,
                "thread-task-bad-mode", "task-bad-mode",
                NotificationStatus.UNREAD,
                "{\"action\":\"next_task\",\"baseMode\":\"unsupported\"}",
                Instant.now(), null);
        when(notifications.find("notif-bad-mode")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-bad-mode"))
                .thenReturn(Optional.of(taskAt("task-bad-mode", TaskStatus.AWAITING_REVIEW)));

        assertThatThrownBy(() -> service.approve("notif-bad-mode", null, "next_task"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid baseMode");

        verify(notifications, never()).claimResolution("notif-bad-mode");
        verify(taskService, never()).startNextFromApprovedParkedTask(anyString(), anyString(), any());
    }

    @Test
    void resolvingApprovalFinishesLocallyWithoutRepeatingRemoteAction()
            throws Exception
    {
        Notification resolving = new Notification(
                "notif-resolving", NotificationKind.AWAITING_REVIEW, "thread-x", "task-x",
                NotificationStatus.RESOLVING,
                "{\"action\":\"push\",\"branch\":\"feature/x\",\"worktreePath\":\"/tmp/wt/x\"}",
                Instant.now(), null);
        when(notifications.find("notif-resolving")).thenReturn(Optional.of(resolving));

        PublishResult result = service.approve("notif-resolving", null, "push");

        assertThat(result.resolution()).isEqualTo("recovered");
        assertThat(result.message()).contains("without repeating");
        verify(git, never()).push(any());
        verify(parkedProposals).finishInterruptedApproval(resolving, "push");
        verify(notifications, never()).claimResolution("notif-resolving");
    }

    @Test
    void localFinalizationFailureKeepsSuccessfulRemoteAttemptInRecoveryState()
    {
        Notification parked = parkedPostComment("notif-finalize-fail", "task-finalize-fail",
                "acme", "widget", 42, "LGTM.");
        when(notifications.find("notif-finalize-fail")).thenReturn(Optional.of(parked));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        doThrow(new IllegalStateException("sqlite busy"))
                .when(parkedProposals).finishApproved(parked, false);

        PublishResult result = service.approve("notif-finalize-fail", null, "post_comment");

        assertThat(result.ok()).isFalse();
        assertThat(result.resolution()).isEqualTo("interrupted");
        verify(pullRequests).createIssueComment(
                eq("ghp_secret"), eq(new PullRequestRef("acme", "widget", 42)), eq("LGTM."));
    }

    @Test
    void approveRefusesWithNotFoundForUnknownNotification()
    {
        when(notifications.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve("missing", null, "push"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no notification: missing");
    }

    @Test
    void approveRefusesWithBadRequestWhenNotificationIsNotAwaitingReview()
    {
        Notification audit = new Notification(
                "audit-1", NotificationKind.AUTO_FIX_DONE, "thread-x", "task-x",
                NotificationStatus.UNREAD, "{}", Instant.now(), null);
        when(notifications.find("audit-1")).thenReturn(Optional.of(audit));

        assertThatThrownBy(() -> service.approve("audit-1", null, "push"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only AWAITING_REVIEW");
    }

    @Test
    void approveRefusesWithBadRequestForUnsupportedAction()
    {
        // "fly_drone" isn't in the sealed ParkedProposal hierarchy, so
        // Jackson polymorphism rejects the payload at parse time with
        // a 400 — same outcome as the prior switch's default branch,
        // surfaced through the typed deserialiser.
        Notification parked = new Notification(
                "notif-bad", NotificationKind.AWAITING_REVIEW, "thread-x", "task-x",
                NotificationStatus.UNREAD,
                "{\"action\":\"fly_drone\"}",
                Instant.now(), null);
        when(notifications.find("notif-bad")).thenReturn(Optional.of(parked));

        assertThatThrownBy(() -> service.approve("notif-bad", null, "fly_drone"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fly_drone")
                .hasMessageContaining("not a known parked proposal");

        verify(notifications, never()).claimResolution("notif-bad");
    }

    @Test
    void approvePushRefusesWithBadRequestWhenPayloadHasNoWorktreePath()
    {
        Notification parked = new Notification(
                "notif-no-wt", NotificationKind.AWAITING_REVIEW, "thread-x", "task-x",
                NotificationStatus.UNREAD,
                "{\"action\":\"push\"}",
                Instant.now(), null);
        when(notifications.find("notif-no-wt")).thenReturn(Optional.of(parked));

        assertThatThrownBy(() -> service.approve("notif-no-wt", null, "push"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no worktreePath");

        verify(notifications, never()).claimResolution("notif-no-wt");
    }

    @Test
    void discardOfUnattemptedPostCommentResumesTaskWithoutPostingAnywhere()
    {
        // Uninterrupted discard never closes the task — the remote
        // side effect was never attempted (Approve was never clicked),
        // so the agent's work-in-progress must resume rather than be
        // silently closed when the user declines a single proposal.
        Notification parked = parkedPostComment("notif-5", "task-5",
                "acme", "widget", 9, "Body the user decided not to send.");
        when(notifications.find("notif-5")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-5"))
                .thenReturn(Optional.of(taskAt("task-5", TaskStatus.AWAITING_REVIEW)));

        PublishResult result = service.discard("notif-5", "post_comment");

        assertThat(result.ok()).isTrue();
        assertThat(result.resolution()).isEqualTo("discarded");
        assertThat(result.action()).isEqualTo("post_comment");
        verify(notifications).claimResolution("notif-5");
        verify(parkedProposals).finishDiscarded(parked, true);
        // Side effect must not fire on discard.
        verify(pullRequests, never()).createIssueComment(anyString(), any(), anyString());
        assertAuditRowWritten(parked, "discarded", "post_comment",
                "user discarded the proposed post_comment");
    }

    @Test
    void discardNextTaskReturnsParkedTaskToIdleWithoutStartingSuccessor()
    {
        Notification parked = parkedNextTask("notif-next-discard", "task-next-discard");
        when(notifications.find("notif-next-discard")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-next-discard"))
                .thenReturn(Optional.of(taskAt("task-next-discard", TaskStatus.AWAITING_REVIEW)));

        PublishResult result = service.discard("notif-next-discard", "next_task");

        assertThat(result.action()).isEqualTo("next_task");
        verify(parkedProposals).finishDiscarded(parked, true);
        verify(taskService, never()).startNextFromApprovedParkedTask(anyString(), anyString(), any());
        verify(notifications).claimResolution("notif-next-discard");
    }

    @Test
    void discardInterruptedPushCompletesInsteadOfResumingPotentiallyPublishedWork()
    {
        Notification resolving = withStatus(
                parkedPush("notif-push-interrupted", "task-push-interrupted",
                        "feature/push-interrupted", "/tmp/wt/push-interrupted"),
                NotificationStatus.RESOLVING);
        when(notifications.find("notif-push-interrupted")).thenReturn(Optional.of(resolving));

        PublishResult result = service.discard("notif-push-interrupted", "push");

        assertThat(result.action()).isEqualTo("push");
        verify(notifications, never()).claimResolution("notif-push-interrupted");
        verify(parkedProposals).finishDiscarded(resolving, false);
        assertAuditRowWritten(resolving, "discarded_after_interrupt", "push",
                "see the prior interrupted audit row");
    }

    @Test
    void discardUnattemptedShipTaskReturnsTaskToLocalWork()
    {
        Notification parked = parkedShipTask("notif-ship-discard", "task-ship-discard");
        when(notifications.find("notif-ship-discard")).thenReturn(Optional.of(parked));

        PublishResult result = service.discard("notif-ship-discard", "ship_task");

        assertThat(result.action()).isEqualTo("ship_task");
        verify(notifications).claimResolution("notif-ship-discard");
        verify(parkedProposals).finishDiscarded(parked, true);
    }

    @Test
    void discardInterruptedShipTaskDoesNotReopenPotentiallyShippedWork()
    {
        Notification resolving = withStatus(
                parkedShipTask("notif-ship-interrupted", "task-ship-interrupted"),
                NotificationStatus.RESOLVING);
        when(notifications.find("notif-ship-interrupted")).thenReturn(Optional.of(resolving));

        service.discard("notif-ship-interrupted", "ship_task");

        verify(parkedProposals).finishDiscarded(resolving, false);
    }

    @Test
    void discardInterruptedNextTaskCompletesInsteadOfResumingPotentiallyShippedWork()
    {
        // An approved next_task pushes the branch and opens a PR inside
        // the advance, exactly like ship_task. If that approve was
        // interrupted, discarding must NOT resume the prior task: the
        // branch may already be on the remote, and (when the advance
        // produced a successor) reviving the prior task would leave two
        // active siblings on the thread. Complete locally instead.
        Notification resolving = withStatus(
                parkedNextTask("notif-next-interrupted", "task-next-interrupted"),
                NotificationStatus.RESOLVING);
        when(notifications.find("notif-next-interrupted")).thenReturn(Optional.of(resolving));

        service.discard("notif-next-interrupted", "next_task");

        verify(parkedProposals).finishDiscarded(resolving, false);
        verify(notifications, never()).claimResolution("notif-next-interrupted");
    }

    @Test
    void resolvingApprovalRecoversWithoutRequiringTheExpectedActionDiscriminator()
    {
        // Recovering an interrupted (RESOLVING) row runs no remote
        // action, so it must reach the local-recovery branch even when
        // the caller can't supply expectedAction — the fresh-approve
        // guard would otherwise 400 and strand the row.
        Notification resolving = new Notification(
                "notif-recover-noaction", NotificationKind.AWAITING_REVIEW, "thread-x", "task-x",
                NotificationStatus.RESOLVING,
                "{\"action\":\"push\",\"branch\":\"feature/x\",\"worktreePath\":\"/tmp/wt/x\"}",
                Instant.now(), null);
        when(notifications.find("notif-recover-noaction")).thenReturn(Optional.of(resolving));

        PublishResult result = service.approve("notif-recover-noaction", null, /* expectedAction */ null);

        assertThat(result.resolution()).isEqualTo("recovered");
        verify(parkedProposals).finishInterruptedApproval(resolving, "push");
    }

    @Test
    void discardOfInterruptedRowRecoversWithoutRequiringTheExpectedActionDiscriminator()
    {
        Notification resolving = withStatus(
                parkedPush("notif-discard-noaction", "task-discard-noaction",
                        "feature/x", "/tmp/wt/x"),
                NotificationStatus.RESOLVING);
        when(notifications.find("notif-discard-noaction")).thenReturn(Optional.of(resolving));

        PublishResult result = service.discard("notif-discard-noaction", /* expectedAction */ null);

        assertThat(result.resolution()).isEqualTo("discarded");
        verify(parkedProposals).finishDiscarded(resolving, false);
    }

    @Test
    void approveReleasesTheClaimAndSurfacesA4xxWhenTheRemoteRejectsTheAction()
    {
        // A 4xx after the claim means the action was rejected before it
        // changed remote state (a GitHub client rejection, or a local
        // validation the preflight didn't catch). The row must return to
        // the actionable feed via a claim release and the clean status
        // must reach the caller — never a misleading "outcome unknown"
        // audit that pins the row in RESOLVING.
        Notification parked = parkedPostComment("notif-4xx", "task-4xx",
                "acme", "widget", 7, "LGTM.");
        when(notifications.find("notif-4xx")).thenReturn(Optional.of(parked));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        doThrow(new ResponseStatusException(HttpStatusCode.valueOf(422), "already reviewed"))
                .when(pullRequests).createIssueComment(
                        eq("ghp_secret"), eq(new PullRequestRef("acme", "widget", 7)), eq("LGTM."));

        assertThatThrownBy(() -> service.approve("notif-4xx", null, "post_comment"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already reviewed");

        verify(notifications).releaseResolution("notif-4xx");
        verify(notifications, never()).notifyAutoFixDone(anyString(), anyString(), anyString());
    }

    @Test
    void discardAcknowledgesSuccessEvenWhenBestEffortAuditWriteFails()
    {
        Notification parked = parkedNextTask("notif-discard-audit", "task-discard-audit");
        when(notifications.find("notif-discard-audit")).thenReturn(Optional.of(parked));
        when(notifications.notifyAutoFixDone(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("audit write failed"));

        PublishResult result = service.discard("notif-discard-audit", "next_task");

        assertThat(result.ok()).isTrue();
        verify(parkedProposals).finishDiscarded(parked, true);
    }

    @Test
    void successfulApprovalDelegatesIdempotentLocalCompletion()
    {
        // Two notifications attached to the same task (e.g. a stray
        // post_comment row left over after the user approved the push).
        // Approving the second one should still write the audit row but
        // must not flip the task back from COMPLETED to anything else.
        Notification parked = parkedPostComment("notif-6", "task-6",
                "acme", "widget", 11, "Body.");
        when(notifications.find("notif-6")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-6"))
                .thenReturn(Optional.of(taskAt("task-6", TaskStatus.COMPLETED)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");

        service.approve("notif-6", null, "post_comment");

        verify(parkedProposals).finishApproved(parked, false);
    }

    @Test
    void approvePrSendsExplicitlyBlankBodyAsOptionalEmptyWhenTheUserClearedTheTextarea()
    {
        // approve_pr's body is optional. When the user clears the gate
        // textarea before clicking Approve, the frontend sends "" — and
        // the backend must honour that as "approve with no comment"
        // instead of silently substituting the agent's parked body.
        Notification parked = parkedApprovePr("notif-clear-approve", "task-clear-approve",
                "acme", "widget", 7, "Looks good — keep this hidden, user cleared it.");
        when(notifications.find("notif-clear-approve")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-clear-approve"))
                .thenReturn(Optional.of(taskAt("task-clear-approve", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");

        PublishResult result = service.approve("notif-clear-approve", "", "approve_pr");

        assertThat(result.ok()).isTrue();
        ArgumentCaptor<CreateReviewCommand> command = ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp_secret"), eq(new PullRequestRef("acme", "widget", 7)), command.capture());
        assertThat(command.getValue().event()).isEqualTo("APPROVE");
        assertThat(command.getValue().body()).isEmpty();
    }

    @Test
    void approvePrFallsBackToParkedBodyWhenEditedBodyIsNull()
    {
        // null = "no override from the user" (the bridge sends null for
        // actions whose UI doesn't expose an editor at all). Backend
        // must keep using the agent's parked body in that case.
        Notification parked = parkedApprovePr("notif-null-approve", "task-null-approve",
                "acme", "widget", 8, "Looks good.");
        when(notifications.find("notif-null-approve")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-null-approve"))
                .thenReturn(Optional.of(taskAt("task-null-approve", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");

        service.approve("notif-null-approve", null, "approve_pr");

        ArgumentCaptor<CreateReviewCommand> command = ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp_secret"), eq(new PullRequestRef("acme", "widget", 8)), command.capture());
        assertThat(command.getValue().body()).contains("Looks good.");
    }

    @Test
    void openPrSendsExplicitlyBlankBodyWhenTheUserClearedTheTextarea()
    {
        // Same shape as approve_pr: editedBody="" → Optional.empty()
        // on the GitHub command. The PR gets created with an empty
        // body, not the agent's parked description.
        Notification parked = parkedOpenPr("notif-clear-open", "task-clear-open",
                "acme", "widget",
                "Add cache layer", "feature/cache", "main",
                "Agent's draft description — user cleared this.");
        when(notifications.find("notif-clear-open")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-clear-open"))
                .thenReturn(Optional.of(taskAt("task-clear-open", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");

        service.approve("notif-clear-open", "", "open_pr");

        ArgumentCaptor<CreatePullRequestCommand> command =
                ArgumentCaptor.forClass(CreatePullRequestCommand.class);
        verify(pullRequests).createPullRequest(
                eq("ghp_secret"), eq(new RepoRef("acme", "widget")), command.capture());
        assertThat(command.getValue().title()).isEqualTo("Add cache layer");
        assertThat(command.getValue().body()).isEmpty();
    }

    @Test
    void openPrPersistsTheCreatedPrNumberAndMarksTheBranchPushed()
    {
        // One-step publish: approving open_pr records the returned PR
        // number + state on the task (the number used to be discarded)
        // and stamps the branch as on-remote so the task UI can show it.
        Notification parked = parkedOpenPr("notif-open-persist", "task-open-persist",
                "acme", "widget",
                "Add cache layer", "feature/cache", "main",
                "Draft body.");
        when(notifications.find("notif-open-persist")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-open-persist"))
                .thenReturn(Optional.of(taskAt("task-open-persist", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.createPullRequest(any(), any(), any()))
                .thenReturn(samplePr(42, true));

        PublishResult result = service.approve("notif-open-persist", "", "open_pr");

        verify(taskStore).linkPullRequest("task-open-persist", 42, "draft");
        verify(taskStore).markPushed(eq("task-open-persist"), any());
        assertThat(result.message()).contains("#42");
    }

    @Test
    void openPrPublishesAPrPushedEventSoTheStalePushPromptClears()
    {
        // The PR row is a separate tracking table from the task's own
        // phase — auto-approve resolving this gate must also carry it
        // forward, or PRActionBar keeps offering "Approve & push to
        // GitHub" for a push that already happened. PublishService's job is
        // just to publish the event; PRPublishService's listener (see
        // TestPRPublishService) is what actually advances the row.
        Notification parked = parkedOpenPr("notif-sync-local-pr", "task-sync-local-pr",
                "acme", "widget", "Add cache layer", "feature/cache", "main", "Draft body.");
        when(notifications.find("notif-sync-local-pr")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-sync-local-pr"))
                .thenReturn(Optional.of(taskAt("task-sync-local-pr", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.createPullRequest(any(), any(), any()))
                .thenReturn(samplePr(42, true));

        service.approve("notif-sync-local-pr", "", "open_pr");

        verify(eventPublisher).publishEvent(
                new PrPushedEvent("task-sync-local-pr", "acme/widget", 42, "https://github.com/acme/widget/pull/42"));
    }

    @Test
    void markReadyAdvancesAStuckPrRowToRemoteOpen()
    {
        Notification parked = parkedMarkReady("notif-mark-ready-sync", "task-mark-ready-sync",
                "acme", "widget", 42, "");
        when(notifications.find("notif-mark-ready-sync")).thenReturn(Optional.of(parked));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        PR remoteDrafted = PR.create(
                "local-pr-2", "task-mark-ready-sync", "feature/cache", "main",
                "Add cache layer", "", Instant.parse("2026-05-22T11:00:00Z"))
                .withStatus(PR.STATUS_LOCAL_OPEN, Instant.parse("2026-05-22T11:30:00Z"))
                .withStatus(PR.STATUS_REMOTE_DRAFTED, Instant.parse("2026-05-22T11:31:00Z"));
        when(prService.findByTask("task-mark-ready-sync")).thenReturn(Optional.of(remoteDrafted));

        service.approve("notif-mark-ready-sync", "", "mark_ready");

        verify(prService).transition("local-pr-2", PR.STATUS_REMOTE_OPEN, PRTimelineEntry.ACTOR_AGENT);
    }

    private static PullRequest samplePr(int number, boolean draft)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new PullRequest(
                1L, "acme/widget", number, "Add cache layer", "alice",
                "https://github.com/acme/widget/pull/" + number,
                now, now, PullRequest.Origin.AUTHORED,
                List.of(), Map.of(), draft,
                null, null, null, List.of(), null,
                0, 0, 0, null,
                "open", null, null, null, null, null,
                Map.of(), null, null, "feature/cache");
    }

    @Test
    void publishReviewSendsExplicitlyBlankBodyWhenTheUserClearedTheTextarea()
    {
        // Same semantic for the review-level body on publish_review.
        // APPROVE / REQUEST_CHANGES can land with no review-level body.
        Notification parked = parkedPublishReview("notif-clear-review", "task-clear-review",
                "acme", "widget", 99, "APPROVE",
                "Agent's review summary — user cleared this.");
        when(notifications.find("notif-clear-review")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-clear-review"))
                .thenReturn(Optional.of(taskAt("task-clear-review", TaskStatus.AWAITING_REVIEW)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");

        service.approve("notif-clear-review", "", "publish_review");

        ArgumentCaptor<CreateReviewCommand> command = ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp_secret"), eq(new PullRequestRef("acme", "widget", 99)), command.capture());
        assertThat(command.getValue().event()).isEqualTo("APPROVE");
        assertThat(command.getValue().body()).isEmpty();
    }

    private void assertAuditRowWritten(
            Notification original, String resolution, String action, String messageFragment)
    {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifyAutoFixDone(
                eq(original.threadId()), eq(original.taskId()), payloadCaptor.capture());
        JsonNode audit;
        try {
            audit = mapper.readTree(payloadCaptor.getValue());
        }
        catch (Exception e) {
            throw new AssertionError("audit payload was not valid JSON: " + payloadCaptor.getValue(), e);
        }
        assertThat(audit.path("publishResolution").asText()).isEqualTo(resolution);
        assertThat(audit.path("action").asText()).isEqualTo(action);
        assertThat(audit.path("originalNotificationId").asText()).isEqualTo(original.id());
        assertThat(audit.path("message").asText()).contains(messageFragment);
    }

    private static Notification parkedPush(
            String notificationId, String taskId, String branch, String worktreePath)
    {
        String json = "{"
                + "\"action\":\"push\","
                + "\"branch\":\"" + branch + "\","
                + "\"worktreePath\":\"" + worktreePath + "\","
                + "\"source\":\"mcp:push\""
                + "}";
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD, json, Instant.now(), null);
    }

    private static Notification parkedPostComment(
            String notificationId, String taskId,
            String owner, String repo, int number, String body)
    {
        String json = "{"
                + "\"action\":\"post_comment\","
                + "\"body\":" + quote(body) + ","
                + "\"pr\":{\"owner\":" + quote(owner) + ",\"repo\":" + quote(repo)
                + ",\"number\":" + number + "},"
                + "\"source\":\"mcp:post_comment\""
                + "}";
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD, json, Instant.now(), null);
    }

    private static Notification parkedResolveReviewThread(
            String notificationId, String taskId,
            String owner, String repo, int number, long rootCommentId, boolean resolved)
    {
        String json = "{"
                + "\"action\":\"resolve_review_thread\","
                + "\"rootCommentId\":" + rootCommentId + ","
                + "\"resolved\":" + resolved + ","
                + "\"pr\":{\"owner\":" + quote(owner) + ",\"repo\":" + quote(repo)
                + ",\"number\":" + number + "},"
                + "\"source\":\"mcp:resolve_review_thread\""
                + "}";
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD, json, Instant.now(), null);
    }

    private static Notification parkedRequestReview(String notificationId, String taskId)
    {
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD,
                "{\"action\":\"request_review\",\"summary\":\"Ready\"}",
                Instant.now(), null);
    }

    private static Notification parkedNextTask(String notificationId, String taskId)
    {
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD,
                "{\"action\":\"next_task\",\"nextTitle\":\"Follow-up\",\"baseMode\":\"stacked\"}",
                Instant.now(), null);
    }

    private static Notification parkedShipTask(String notificationId, String taskId)
    {
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD,
                "{\"action\":\"ship_task\",\"nextTitle\":\"After ship\",\"baseMode\":\"main\"}",
                Instant.now(), null);
    }

    private static Notification parkedShipTaskWithPr(
            String notificationId, String taskId, String prTitle, String prBody)
    {
        String json = "{"
                + "\"action\":\"ship_task\","
                + "\"nextTitle\":\"After ship\","
                + "\"baseMode\":\"main\","
                + "\"prTitle\":" + quote(prTitle) + ","
                + "\"prBody\":" + quote(prBody)
                + "}";
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD, json, Instant.now(), null);
    }

    private static ReviewComment localComment(String taskId)
    {
        return new ReviewComment(
                UUID.randomUUID(), taskId, "src/Foo.java", 12, "fix this",
                Instant.now(), ReviewCommentSource.LOCAL_USER, null, false, null, null, null, null,
                "RIGHT", null, null);
    }

    private static Notification parkedApprovePr(
            String notificationId, String taskId,
            String owner, String repo, int number, String body)
    {
        String json = "{"
                + "\"action\":\"approve_pr\","
                + "\"body\":" + quote(body) + ","
                + "\"pr\":{\"owner\":" + quote(owner) + ",\"repo\":" + quote(repo)
                + ",\"number\":" + number + "},"
                + "\"source\":\"mcp:approve_pr\""
                + "}";
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD, json, Instant.now(), null);
    }

    private static Notification parkedOpenPr(
            String notificationId, String taskId,
            String owner, String repo, String title, String head, String base, String body)
    {
        String json = "{"
                + "\"action\":\"open_pr\","
                + "\"title\":" + quote(title) + ","
                + "\"head\":" + quote(head) + ","
                + "\"base\":" + quote(base) + ","
                + "\"body\":" + quote(body) + ","
                + "\"draft\":false,"
                + "\"repo\":{\"owner\":" + quote(owner) + ",\"repo\":" + quote(repo) + "},"
                + "\"source\":\"mcp:open_pr\""
                + "}";
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD, json, Instant.now(), null);
    }

    private static Notification parkedMarkReady(
            String notificationId, String taskId, String owner, String repo, int number, String body)
    {
        String json = "{"
                + "\"action\":\"mark_ready\","
                + "\"pr\":{\"owner\":" + quote(owner) + ",\"repo\":" + quote(repo)
                + ",\"number\":" + number + "},"
                + "\"body\":" + quote(body) + ","
                + "\"source\":\"lifecycle:mark_ready\""
                + "}";
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD, json, Instant.now(), null);
    }

    private static Notification parkedPublishReview(
            String notificationId, String taskId,
            String owner, String repo, int number, String event, String body)
    {
        String json = "{"
                + "\"action\":\"publish_review\","
                + "\"event\":" + quote(event) + ","
                + "\"body\":" + quote(body) + ","
                + "\"comments\":[],"
                + "\"pr\":{\"owner\":" + quote(owner) + ",\"repo\":" + quote(repo)
                + ",\"number\":" + number + "},"
                + "\"source\":\"mcp:publish_review\""
                + "}";
        return new Notification(
                notificationId, NotificationKind.AWAITING_REVIEW, "thread-" + taskId, taskId,
                NotificationStatus.UNREAD, json, Instant.now(), null);
    }

    private static Notification withStatus(Notification notification, NotificationStatus status)
    {
        return new Notification(
                notification.id(), notification.kind(), notification.threadId(), notification.taskId(),
                status, notification.payloadJson(), notification.createdAt(), notification.readAt());
    }

    private static String quote(String s)
    {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Task taskAt(String id, TaskStatus status)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Task(
                id, "thread-" + id, 1L, status,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null,
                "DEVELOP", null, null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                now, null, null, null, null, null);
    }

    /** Same as {@link #taskAt} but with {@code linkedPrRef} set — a task
     *  that's already opened its PR through some other path, so a plain
     *  push here is a subsequent push to that existing PR. */
    private static Task taskWithLinkedPr(String id, String linkedPrRef)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Task(
                id, "thread-" + id, 1L, TaskStatus.AWAITING_REVIEW,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null,
                "DEVELOP", null, null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                now, null, null, null, null, null,
                /* pushedAt */ null, TaskPhase.IMPLEMENTING, /* agendaJson */ null,
                /* consecutiveAutoPushes */ 0, linkedPrRef, /* openingPrompt */ null);
    }
}
