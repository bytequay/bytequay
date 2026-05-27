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

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.PublishService.PublishResult;
import com.bytequay.app.web.PatResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
 * push and createIssueComment calls actually happen, the task flips
 * to COMPLETED, and the audit row lands. All dependencies are mocked
 * so the test is fast and deterministic (no real git, no GitHub).
 */
class TestPublishService
{
    private NotificationService notifications;
    private TaskStore taskStore;
    private GitRunner git;
    private PullRequestRepository pullRequests;
    private PatResolver patResolver;
    private ObjectMapper mapper;
    private TaskService taskService;
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
        taskService = mock(TaskService.class);
        service = new PublishService(notifications, taskStore, git, pullRequests, patResolver, mapper, taskService);
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

        PublishResult result = service.approve("notif-1", null);

        assertThat(result.ok()).isTrue();
        assertThat(result.resolution()).isEqualTo("approved");
        assertThat(result.action()).isEqualTo("push");
        assertThat(result.message()).contains("Pushed feature/x");

        verify(git).push(Path.of("/tmp/wt/feature-x"));
        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).saveTask(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.COMPLETED);
        verify(notifications).dismiss("notif-1");
        assertAuditRowWritten(parked, "approved", "push", "Pushed feature/x");
    }

    @Test
    void approvePushKeepsParkedRowAndWritesFailedAuditWhenGitPushThrows()
            throws Exception
    {
        Notification parked = parkedPush("notif-2", "task-2",
                "feature/y", "/tmp/wt/feature-y");
        when(notifications.find("notif-2")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-2"))
                .thenReturn(Optional.of(taskAt("task-2", TaskStatus.AWAITING_REVIEW)));
        // Mimic a real remote-rejection / network blip — IOException is
        // what GitRunner.push surfaces for those failures.
        doThrow(new IOException("rejected: non-fast-forward"))
                .when(git).push(any(Path.class));

        PublishResult result = service.approve("notif-2", null);

        assertThat(result.ok()).isFalse();
        assertThat(result.resolution()).isEqualTo("failed");
        assertThat(result.message()).contains("rejected: non-fast-forward");
        // The task stays AWAITING_REVIEW (no saveTask) and the parked
        // row stays UNREAD (no dismiss) so the user can hit Approve a
        // second time once the underlying problem is resolved.
        verify(taskStore, never()).saveTask(any());
        verify(notifications, never()).dismiss(anyString());
        assertAuditRowWritten(parked, "failed", "push", "rejected: non-fast-forward");
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
        PublishResult result = service.approve("notif-3", edited);

        assertThat(result.ok()).isTrue();
        assertThat(result.resolution()).isEqualTo("approved");
        assertThat(result.action()).isEqualTo("post_comment");
        verify(pullRequests).createIssueComment(
                eq("ghp_secret"), eq(new PullRequestRef("acme", "widget", 42)), eq(edited));
        verify(notifications).dismiss("notif-3");
        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).saveTask(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.COMPLETED);
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

        service.approve("notif-4", "   ");

        // Blank editedBody means "user clicked Approve without editing"
        // — the parked body is what gets posted.
        verify(pullRequests).createIssueComment(
                eq("ghp_secret"),
                eq(new PullRequestRef("acme", "widget", 7)),
                eq("Looks good."));
    }

    @Test
    void approveRefusesWithNotFoundForUnknownNotification()
    {
        when(notifications.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve("missing", null))
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

        assertThatThrownBy(() -> service.approve("audit-1", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only AWAITING_REVIEW");
    }

    @Test
    void approveRefusesWithBadRequestForUnsupportedAction()
    {
        // "fly_drone" is not in the dispatch switch — any unknown
        // action name produces the 400. (merge_pr / approve_pr have
        // since landed as real cases; pick a name that no future
        // catalog entry is likely to claim.)
        Notification parked = new Notification(
                "notif-bad", NotificationKind.AWAITING_REVIEW, "thread-x", "task-x",
                NotificationStatus.UNREAD,
                "{\"action\":\"fly_drone\"}",
                Instant.now(), null);
        when(notifications.find("notif-bad")).thenReturn(Optional.of(parked));

        assertThatThrownBy(() -> service.approve("notif-bad", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unsupported action: fly_drone");
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

        assertThatThrownBy(() -> service.approve("notif-no-wt", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no worktreePath");
    }

    @Test
    void discardMarksNotificationDismissedCompletesTaskAndWritesDiscardedAudit()
    {
        Notification parked = parkedPostComment("notif-5", "task-5",
                "acme", "widget", 9, "Body the user decided not to send.");
        when(notifications.find("notif-5")).thenReturn(Optional.of(parked));
        when(taskStore.findTaskById("task-5"))
                .thenReturn(Optional.of(taskAt("task-5", TaskStatus.AWAITING_REVIEW)));

        PublishResult result = service.discard("notif-5");

        assertThat(result.ok()).isTrue();
        assertThat(result.resolution()).isEqualTo("discarded");
        assertThat(result.action()).isEqualTo("post_comment");
        verify(notifications).dismiss("notif-5");
        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).saveTask(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.COMPLETED);
        // Side effect must not fire on discard.
        verify(pullRequests, never()).createIssueComment(anyString(), any(), anyString());
        assertAuditRowWritten(parked, "discarded", "post_comment",
                "user discarded the proposed post_comment");
    }

    @Test
    void completeTaskIsIdempotentWhenTaskHasAlreadyMovedOffAwaitingReview()
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

        service.approve("notif-6", null);

        verify(taskStore, never()).saveTask(any());
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
                now, null, null, null, null);
    }
}
