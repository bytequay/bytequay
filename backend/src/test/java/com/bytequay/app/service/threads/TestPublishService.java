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

import com.bytequay.app.developmentflow.execution.quality.V2QualityIssuePublishRuntime;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.sqlite.SqliteStageStore;
import com.bytequay.app.service.IssueOriginService;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.ReviewPassResolver;
import com.bytequay.app.service.stage.ReadyToMergeService;
import com.bytequay.app.service.threads.PublishService.PublishResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPublishService
{
    private NotificationService notifications;
    private TaskStore tasks;
    private GitRunner git;
    private PullRequestRepository pullRequests;
    private ParkedProposalService parkedProposals;
    private V2QualityIssuePublishRuntime qualityPublishes;
    private PublishService service;

    @BeforeEach
    void setUp()
    {
        notifications = mock(NotificationService.class);
        tasks = mock(TaskStore.class);
        git = mock(GitRunner.class);
        pullRequests = mock(PullRequestRepository.class);
        parkedProposals = mock(ParkedProposalService.class);
        qualityPublishes = mock(V2QualityIssuePublishRuntime.class);
        service = new PublishService(
                notifications,
                tasks,
                git,
                pullRequests,
                mock(PatResolver.class),
                mock(IssueOriginService.class),
                new ObjectMapper(),
                parkedProposals,
                mock(TaskService.class),
                mock(ReviewPassResolver.class),
                mock(TaskPhaseMachine.class),
                mock(SqliteStageStore.class),
                mock(PRService.class),
                mock(PullRequestService.class),
                mock(ReadyToMergeService.class));
        service.setQualityIssuePublishes(qualityPublishes);
    }

    @Test
    void v2QualityIssueApprovalDelegatesWithoutRunningLegacyEffects()
    {
        Notification notification = qualityIssue("quality", "task-v2");
        when(notifications.find(notification.id())).thenReturn(Optional.of(notification));
        when(tasks.findWorkflowVersion("task-v2")).thenReturn(Optional.of("V2"));
        when(qualityPublishes.approve(any(), any(), eq("edited")))
                .thenReturn(new PublishResult(
                        true, "approved", "Issue publication queued.", "create_issue"));

        PublishResult result = service.approve(
                notification.id(), "edited", "create_issue");

        assertThat(result.message()).isEqualTo("Issue publication queued.");
        verify(qualityPublishes).approve(any(), any(), eq("edited"));
        verify(notifications, never()).claimResolution(anyString());
        verify(pullRequests, never()).createIssue(
                anyString(), any(), anyString(), anyString());
    }

    @Test
    void v2QualityIssueDiscardDelegatesWithoutLegacyFinalization()
    {
        Notification notification = qualityIssue("quality-discard", "task-v2");
        when(notifications.find(notification.id())).thenReturn(Optional.of(notification));
        when(tasks.findWorkflowVersion("task-v2")).thenReturn(Optional.of("V2"));
        when(qualityPublishes.discard(any(), any()))
                .thenReturn(new PublishResult(
                        true, "discarded", "Discarded.", "create_issue"));

        PublishResult result = service.discard(
                notification.id(), "create_issue");

        assertThat(result.resolution()).isEqualTo("discarded");
        verify(qualityPublishes).discard(any(), any());
        verify(notifications, never()).claimResolution(anyString());
        verify(parkedProposals, never()).finishDiscarded(any(), anyBoolean());
    }

    @Test
    void historicalQualityIssueApprovalIsReadOnly()
    {
        Notification notification = qualityIssue("legacy-quality", "task-legacy");
        when(notifications.find(notification.id())).thenReturn(Optional.of(notification));
        when(tasks.findWorkflowVersion("task-legacy"))
                .thenReturn(Optional.of("LEGACY"));

        assertRetired(() -> service.approve(
                notification.id(), "edited", "create_issue"));

        verify(qualityPublishes, never()).approve(any(), any(), any());
        verify(notifications, never()).claimResolution(anyString());
        verify(pullRequests, never()).createIssue(
                anyString(), any(), anyString(), anyString());
    }

    @Test
    void everyNonQualityParkedProposalIsReadOnlyEvenForAV2Task()
    {
        Notification notification = postComment("comment", "task-v2");
        when(notifications.find(notification.id())).thenReturn(Optional.of(notification));
        when(tasks.findWorkflowVersion("task-v2")).thenReturn(Optional.of("V2"));

        assertRetired(() -> service.approve(
                notification.id(), "edited", "post_comment"));
        assertRetired(() -> service.discard(
                notification.id(), "post_comment"));

        verify(notifications, never()).claimResolution(anyString());
        verify(pullRequests, never()).createIssueComment(anyString(), any(), anyString());
        verify(parkedProposals, never()).finishDiscarded(any(), anyBoolean());
    }

    @Test
    void historicalShipDescriptionCannotBeEdited()
            throws IOException, InterruptedException
    {
        Notification notification = ship("ship", "task-legacy");
        when(notifications.find(notification.id())).thenReturn(Optional.of(notification));

        assertRetired(() -> service.updateShipDescription(
                notification.id(), "title", "body"));

        verify(notifications, never()).updatePayload(anyString(), anyString());
        verify(git, never()).push(any());
        verify(git, never()).pushForceWithLease(any());
    }

    @Test
    void qualityIssueWithUnknownWorkflowFailsClosed()
    {
        Notification notification = qualityIssue("unknown", "task-missing");
        when(notifications.find(notification.id())).thenReturn(Optional.of(notification));
        when(tasks.findWorkflowVersion("task-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(
                notification.id(), null, "create_issue"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("workflow is unknown");

        verify(qualityPublishes, never()).approve(any(), any(), any());
        verify(notifications, never()).claimResolution(anyString());
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("is read-only")
                .hasMessageContaining("typed V2 owner");
    }

    private static Notification qualityIssue(String id, String taskId)
    {
        return notification(id, taskId, """
                {"action":"create_issue","title":"Finding","body":"Details",
                 "repo":{"owner":"acme","repo":"widget"},
                 "source":"automation:quality-scan"}
                """);
    }

    private static Notification postComment(String id, String taskId)
    {
        return notification(id, taskId, """
                {"action":"post_comment","body":"body",
                 "pr":{"owner":"acme","repo":"widget","number":42},
                 "source":"mcp:post_comment"}
                """);
    }

    private static Notification ship(String id, String taskId)
    {
        return notification(id, taskId, """
                {"action":"ship_task","nextTitle":"next","baseMode":"main"}
                """);
    }

    private static Notification notification(
            String id, String taskId, String payload)
    {
        return new Notification(
                id, NotificationKind.AWAITING_REVIEW, "thread-" + taskId,
                taskId, NotificationStatus.UNREAD, payload,
                Instant.parse("2026-07-29T00:00:00Z"), null);
    }
}
