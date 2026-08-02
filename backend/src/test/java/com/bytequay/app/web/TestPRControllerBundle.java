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
package com.bytequay.app.web;

import com.bytequay.app.beans.localpr.PRBundleDto;
import com.bytequay.app.developmentflow.compatibility.V2PrTimelineProjection;
import com.bytequay.app.developmentflow.stage.ManualPrValidationRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.Operation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.Status;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PRSyncService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.pr.PullRequestService.CachedTerminalState;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPRControllerBundle
{
    private static final V2PrTimelineProjection IDENTITY_TIMELINE =
            new V2PrTimelineProjection()
            {
                @Override
                public List<PRTimelineEntry> project(
                        PR pr, List<PRTimelineEntry> stored)
                {
                    return stored;
                }

                @Override
                public List<PRCheck> remoteChecks(PR pr)
                {
                    return List.of();
                }
            };

    @Test
    void manualValidationReturnsAcceptedWhileTheDurableOperationIsPending()
    {
        PRService prs = mock(PRService.class);
        ManualPrValidationRuntime validation = mock(ManualPrValidationRuntime.class);
        Operation requested = new Operation(
                "operation-1", "command-1", "pr1", "task1", 1,
                Status.REQUESTED, null, null);
        when(validation.runAndWait("command-1", "pr1")).thenReturn(requested);
        PRController controller = new PRController(
                prs, mock(PRPublishService.class), mock(PRSyncService.class),
                mock(TaskStore.class), new ObjectMapper(), validation,
                mock(PullRequestService.class), mock(InvestigationReviewService.class),
                IDENTITY_TIMELINE);

        var response = controller.runTests("pr1", "command-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        verify(prs, never()).checks(anyString());
    }

    @Test
    void taskOwnedBundleUsesStoredProjectionWithoutSync()
    {
        PRService prs = mock(PRService.class);
        PRSyncService sync = mock(PRSyncService.class);
        PR pr = PR.create(
                "pr1", "task1", "feature/x", "main", "Title", "",
                Instant.parse("2026-07-24T00:00:00Z"));
        when(prs.findById("pr1")).thenReturn(Optional.of(pr));
        when(prs.commits("pr1")).thenReturn(List.of());
        when(prs.timeline("pr1")).thenReturn(List.of());
        when(prs.checks("pr1")).thenReturn(List.of());
        when(prs.comments("pr1")).thenReturn(List.of());

        PRController controller = new PRController(
                prs, mock(PRPublishService.class), sync, mock(TaskStore.class),
                new ObjectMapper(), mock(ManualPrValidationRuntime.class),
                mock(PullRequestService.class), mock(InvestigationReviewService.class),
                IDENTITY_TIMELINE);

        controller.bundle("pr1");

        verify(sync, never()).syncPRForDisplay("pr1");
    }

    @Test
    void taskOwnedBundleProjectsCachedRemoteCloseWithoutMutatingOrSyncing()
    {
        PRService prs = mock(PRService.class);
        PRSyncService sync = mock(PRSyncService.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        Instant pushedAt = Instant.parse("2026-07-24T00:01:00Z");
        Instant closedAt = Instant.parse("2026-07-24T00:02:00Z");
        PR pr = PR.create(
                        "pr1", "task1", "feature/x", "main", "Title", "",
                        Instant.parse("2026-07-24T00:00:00Z"))
                .withStatus(PR.STATUS_LOCAL_OPEN, pushedAt)
                .withRemote("acme/widget", 17,
                        "https://github.com/acme/widget/pull/17", pushedAt)
                .withStatus(PR.STATUS_REMOTE_DRAFTED, pushedAt);
        when(prs.findById("pr1")).thenReturn(Optional.of(pr));
        when(prs.commits("pr1")).thenReturn(List.of());
        when(prs.timeline("pr1")).thenReturn(List.of());
        when(prs.checks("pr1")).thenReturn(List.of());
        when(prs.comments("pr1")).thenReturn(List.of());
        when(pullRequests.findCachedTerminalState("acme/widget", 17))
                .thenReturn(Optional.of(new CachedTerminalState(
                        PR.STATUS_CLOSED, null, closedAt)));
        PRController controller = new PRController(
                prs, mock(PRPublishService.class), sync, mock(TaskStore.class),
                new ObjectMapper(), mock(ManualPrValidationRuntime.class),
                pullRequests, mock(InvestigationReviewService.class),
                IDENTITY_TIMELINE);

        PRBundleDto bundle = controller.bundle("pr1");

        assertThat(bundle.pr().status()).isEqualTo(PR.STATUS_CLOSED);
        assertThat(bundle.pr().closedAt()).isEqualTo(closedAt.toEpochMilli());
        assertThat(pr.status()).isEqualTo(PR.STATUS_REMOTE_DRAFTED);
        verify(sync, never()).syncInBackground("pr1");
        verify(sync, never()).syncPRForDisplay("pr1");
    }

    /** A standalone PR still refreshes, but never on the request thread — the
     *  read is milliseconds of SQLite, the refresh is seconds of GitHub. */
    @Test
    void standaloneBundleRefreshesInTheBackground()
    {
        PRService prs = mock(PRService.class);
        PRSyncService sync = mock(PRSyncService.class);
        PR stored = new PR(
                "pr1", null, "feature/x", "main", "Title", "",
                PR.STATUS_REMOTE_OPEN, Instant.parse("2026-07-24T00:00:00Z"),
                null, 17, "https://github.com/acme/widget/pull/17",
                null, null, null, PR.ORIGIN_EXTERNAL, "acme/widget",
                "@octocat", null, null, null);
        when(prs.findById("pr1")).thenReturn(Optional.of(stored));
        when(sync.isSyncing("pr1")).thenReturn(true);
        when(prs.commits("pr1")).thenReturn(List.of());
        when(prs.timeline("pr1")).thenReturn(List.of());
        when(prs.checks("pr1")).thenReturn(List.of());
        when(prs.comments("pr1")).thenReturn(List.of());
        PRController controller = controller(prs, mock(PRPublishService.class),
                sync, mock(TaskStore.class), mock(InvestigationReviewService.class));

        PRBundleDto bundle = controller.bundle("pr1");

        verify(sync, never()).syncPRForDisplay("pr1");
        verify(sync).syncInBackground("pr1");
        assertThat(bundle.syncing()).isTrue();
    }

    @Test
    void taskPrReadIsPure()
    {
        PRService prs = mock(PRService.class);
        PR pr = PR.create(
                "pr1", "task1", "feature/x", "main", "Title", "",
                Instant.parse("2026-07-24T00:00:00Z"));
        when(prs.findByTask("task1")).thenReturn(Optional.of(pr));
        PRSyncService sync = mock(PRSyncService.class);
        PRController controller = controller(prs, mock(PRPublishService.class),
                sync, mock(TaskStore.class), mock(InvestigationReviewService.class));

        assertThat(controller.getForTask("task1").id()).isEqualTo("pr1");

        verify(sync, never()).syncFromTask(anyString());
    }

    @Test
    void taskPrCreateAndDirectSyncAreRetiredBeforeMutation()
    {
        PRService prs = mock(PRService.class);
        TaskStore tasks = mock(TaskStore.class);
        PRSyncService sync = mock(PRSyncService.class);
        Task task = mock(Task.class);
        PR pr = PR.create(
                "pr1", "task1", "feature/x", "main", "Title", "",
                Instant.parse("2026-07-24T00:00:00Z"));
        when(task.id()).thenReturn("task1");
        when(tasks.findTaskById("task1")).thenReturn(Optional.of(task));
        when(tasks.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));
        when(prs.findById("pr1")).thenReturn(Optional.of(pr));
        PRController controller = controller(prs, mock(PRPublishService.class),
                sync, tasks, mock(InvestigationReviewService.class));

        assertThatThrownBy(() -> controller.create("task1", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.syncPr("pr1"))
                .isInstanceOf(ResponseStatusException.class);

        verify(prs, never()).createForTask(
                anyString(), anyString(), anyString(), anyString(), anyString());
        verify(sync, never()).syncPR(anyString(), anyInt());
    }

    @Test
    void v2ApprovalRetryReusesTheCallerCommandAfterALostResponse()
    {
        PRService prs = mock(PRService.class);
        TaskStore tasks = mock(TaskStore.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        PRPublishService publish = mock(PRPublishService.class);
        PR pr = PR.create(
                "pr1", "task1", "feature/x", "main", "Title", "",
                Instant.parse("2026-07-24T00:00:00Z"));
        when(prs.findById("pr1")).thenReturn(Optional.of(pr));
        when(tasks.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));
        PRController controller = new PRController(
                prs, publish, mock(PRSyncService.class), tasks,
                new ObjectMapper(), mock(ManualPrValidationRuntime.class),
                pullRequests, mock(InvestigationReviewService.class),
                IDENTITY_TIMELINE);

        controller.approve("pr1", "approve-command");
        controller.approve("pr1", "approve-command");

        verify(publish, times(2)).publishReview(
                "approve-command", "pr1", "APPROVE", List.of(), List.of(), "");
        verify(pullRequests, never()).submitApproval(anyString(), anyInt());
    }

    @Test
    void legacyTaskApprovalRejectsBeforeGitHubWrite()
    {
        PRService prs = mock(PRService.class);
        TaskStore tasks = mock(TaskStore.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        PRPublishService publish = mock(PRPublishService.class);
        PR pr = mock(PR.class);
        when(pr.taskId()).thenReturn("task1");
        when(pr.repo()).thenReturn("acme/widget");
        when(pr.remotePrNumber()).thenReturn(17);
        when(prs.findById("pr1")).thenReturn(Optional.of(pr));
        when(tasks.findWorkflowVersion("task1")).thenReturn(Optional.of("LEGACY"));
        PRController controller = new PRController(
                prs, publish, mock(PRSyncService.class), tasks,
                new ObjectMapper(), mock(ManualPrValidationRuntime.class),
                pullRequests, mock(InvestigationReviewService.class),
                IDENTITY_TIMELINE);

        assertThatThrownBy(() -> controller.approve("pr1", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));

        verify(pullRequests, never()).submitApproval(anyString(), anyInt());
        verify(prs, never()).markHandled("pr1", HandledAction.APPROVED);
        verify(publish, never()).publishReview(
                anyString(), anyString(), anyList(), anyList(), anyString());
    }

    @Test
    void standaloneApprovalUsesTheDurableCommandProtocol()
    {
        PRService prs = mock(PRService.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        PRPublishService publish = mock(PRPublishService.class);
        PR pr = mock(PR.class);
        when(pr.repo()).thenReturn("acme/widget");
        when(pr.remotePrNumber()).thenReturn(17);
        when(prs.findById("pr1")).thenReturn(Optional.of(pr));
        PRController controller = new PRController(
                prs, publish, mock(PRSyncService.class),
                mock(TaskStore.class), new ObjectMapper(),
                mock(ManualPrValidationRuntime.class), pullRequests,
                mock(InvestigationReviewService.class), IDENTITY_TIMELINE);

        controller.approve("pr1", "approve-command");

        verify(publish).publishReview(
                "approve-command", "pr1", "APPROVE", List.of(), List.of(), "");
        verify(pullRequests, never()).submitApproval(anyString(), anyInt());
        verify(prs, never()).markHandled("pr1", HandledAction.APPROVED);
    }

    @Test
    void v2ReviewDefersInvestigationPublicationUntilRemoteEvidence()
    {
        PRService prs = mock(PRService.class);
        TaskStore tasks = mock(TaskStore.class);
        PRPublishService publish = mock(PRPublishService.class);
        PRSyncService sync = mock(PRSyncService.class);
        InvestigationReviewService investigations = mock(
                InvestigationReviewService.class);
        PR pr = PR.create(
                "pr1", "task1", "feature/x", "main", "Title", "",
                Instant.parse("2026-07-24T00:00:00Z"));
        when(publish.publishReview(
                "review-command", "pr1", "COMMENT", List.of("finding-1"),
                List.of("comment-1"), "summary")).thenReturn(pr);
        when(tasks.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));
        PRController controller = new PRController(
                prs, publish, sync, tasks, new ObjectMapper(),
                mock(ManualPrValidationRuntime.class),
                mock(PullRequestService.class), investigations,
                IDENTITY_TIMELINE);

        controller.publishReview("pr1", "review-command", new PRController.PublishReviewRequest(
                "COMMENT", List.of("finding-1"), List.of("comment-1"),
                "summary"));

        verify(publish).publishReview(
                "review-command", "pr1", "COMMENT", List.of("finding-1"),
                List.of("comment-1"), "summary");
        verify(investigations, never()).recordPublished(
                anyString(), anyString(), anyList(), anyList());
        verify(sync, never()).syncPR(anyString(), anyInt());
    }

    @Test
    void v2RemoteCommentReturnsTypedProjectionWithoutDirectSync()
    {
        PRService prs = mock(PRService.class);
        TaskStore tasks = mock(TaskStore.class);
        PRPublishService publish = mock(PRPublishService.class);
        PRSyncService sync = mock(PRSyncService.class);
        PR pr = PR.create(
                "pr1", "task1", "feature/x", "main", "Title", "",
                Instant.parse("2026-07-24T00:00:00Z"));
        when(publish.postComment("comment-command", "pr1", "hello"))
                .thenReturn(pr);
        when(tasks.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));
        PRController controller = controller(
                prs, publish, sync, tasks, mock(InvestigationReviewService.class));

        controller.postRemoteComment(
                "pr1", "comment-command",
                new PRController.PostRemoteCommentRequest("hello"));

        verify(sync, never()).syncPR(anyString(), anyInt());
    }

    private static PRController controller(
            PRService prs,
            PRPublishService publish,
            PRSyncService sync,
            TaskStore tasks,
            InvestigationReviewService investigations)
    {
        return new PRController(
                prs, publish, sync, tasks, new ObjectMapper(),
                mock(ManualPrValidationRuntime.class),
                mock(PullRequestService.class), investigations,
                IDENTITY_TIMELINE);
    }
}
