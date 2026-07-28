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

import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.checks.RepoTestValidationCheck;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PRSyncService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
    @Test
    void bundleUsesTheReadOnlyRefreshPath()
    {
        PRService prs = mock(PRService.class);
        PRSyncService sync = mock(PRSyncService.class);
        PR pr = PR.create(
                "pr1", "task1", "feature/x", "main", "Title", "",
                Instant.parse("2026-07-24T00:00:00Z"));
        when(sync.syncPRForDisplay("pr1")).thenReturn(Optional.of(pr));
        when(prs.commits("pr1")).thenReturn(List.of());
        when(prs.timeline("pr1")).thenReturn(List.of());
        when(prs.checks("pr1")).thenReturn(List.of());
        when(prs.comments("pr1")).thenReturn(List.of());

        PRController controller = new PRController(
                prs, mock(PRPublishService.class), sync, mock(TaskStore.class),
                new ObjectMapper(), mock(RepoTestValidationCheck.class),
                mock(PullRequestService.class), mock(InvestigationReviewService.class));

        controller.bundle("pr1");

        verify(sync).syncPRForDisplay("pr1");
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
                new ObjectMapper(), mock(RepoTestValidationCheck.class),
                pullRequests, mock(InvestigationReviewService.class));

        controller.approve("pr1", "approve-command");
        controller.approve("pr1", "approve-command");

        verify(publish, times(2)).publishReview(
                "approve-command", "pr1", "APPROVE", List.of(), List.of(), "");
        verify(pullRequests, never()).submitApproval(anyString(), anyInt());
    }

    @Test
    void legacyApprovalKeepsTheExistingDirectBehavior()
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
                new ObjectMapper(), mock(RepoTestValidationCheck.class),
                pullRequests, mock(InvestigationReviewService.class));

        controller.approve("pr1", null);

        verify(pullRequests).submitApproval("acme/widget", 17);
        verify(prs).markHandled("pr1", HandledAction.APPROVED);
        verify(publish, never()).publishReview(
                anyString(), anyString(), anyList(), anyList(), anyString());
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
        when(sync.syncPR("pr1", 0)).thenReturn(Optional.of(pr));
        PRController controller = new PRController(
                prs, publish, sync, tasks, new ObjectMapper(),
                mock(RepoTestValidationCheck.class),
                mock(PullRequestService.class), investigations);

        controller.publishReview("pr1", "review-command", new PRController.PublishReviewRequest(
                "COMMENT", List.of("finding-1"), List.of("comment-1"),
                "summary"));

        verify(publish).publishReview(
                "review-command", "pr1", "COMMENT", List.of("finding-1"),
                List.of("comment-1"), "summary");
        verify(investigations, never()).recordPublished(
                anyString(), anyString(), anyList(), anyList());
    }
}
