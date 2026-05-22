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

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.threads.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the {@code push} / {@code post_comment} MCP
 * tools' parking behaviour: fire the JSON-RPC call against
 * {@link McpController}, then assert the active task transitioned to
 * AWAITING_REVIEW, an AWAITING_REVIEW notification was written with
 * the right payload shape, and the response is the synchronous "parked"
 * text (no DeferredResult left hanging on a gate decision).
 *
 * <p>The design row in {@code workspace-thread-task-design.md} pins the
 * publish gate at AWAITING_REVIEW with a diff viewer + Approve / Edit
 * / Discard UI. The earlier inline allow/deny card was strictly weaker
 * — no diff capture, no edit affordance, no audit trail. These tests
 * pin the new behaviour so a refactor can't quietly regress to the
 * inline form.
 */
@SpringBootTest
class TestMcpPublishGate
{
    @Autowired
    private McpController controller;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private WatchedRepoStore watchedRepos;
    @Autowired
    private NotificationService notifications;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void pushParksActiveTaskAtAwaitingReviewAndEmitsNotification()
            throws Exception
    {
        String threadId = newThread("Push gate fixture");
        Task seeded = newTask(threadId, "feature/push-gate", "/tmp/bytequay-test-fake-worktree-push");
        tasks.saveTask(seeded);

        JsonNode response = invokePush(threadId);

        assertThat(textOf(response))
                .startsWith("Parked at AWAITING_REVIEW.");
        // AWAITING_REVIEW isn't in the "active" status set
        // (findActiveTaskForThread filters terminal-ish states), so the
        // post-park lookup goes by id rather than the active query.
        Task afterPark = tasks.findTaskById(seeded.id()).orElseThrow();
        assertThat(afterPark.status()).isEqualTo(TaskStatus.AWAITING_REVIEW);

        Notification latest = newestAwaitingReviewFor(threadId);
        JsonNode payload = mapper.readTree(latest.payloadJson());
        assertThat(payload.path("action").asText()).isEqualTo("push");
        assertThat(payload.path("branch").asText()).isEqualTo("feature/push-gate");
        assertThat(payload.path("worktreePath").asText())
                .isEqualTo("/tmp/bytequay-test-fake-worktree-push");
        assertThat(payload.path("source").asText()).isEqualTo("mcp:push");
        // No real git repo is wired up under the fake worktree path, so
        // diff computation will fail — but the park, the audit row, and
        // the diffError marker are all there. That's the contract: the
        // park always lands; the diff is best-effort.
        assertThat(payload.has("diff") || payload.has("diffError")).isTrue();
    }

    @Test
    void pushRefusesWhenThreadHasNoActiveTask()
            throws Exception
    {
        String threadId = newThread("Push fixture without a task");

        JsonNode response = invokePush(threadId);

        assertThat(textOf(response)).contains("no active task on this thread");
        // No task to flip, no notification to write — the refusal is a
        // straight error message and the data plane stays untouched.
        assertThat(notifications.listForThread(threadId)).isEmpty();
    }

    @Test
    void pushRefusesWhenActiveTaskHasNoWorktree()
            throws Exception
    {
        String threadId = newThread("Push fixture without a worktree");
        // Non-worktree tasks (logic-loop bootstrap or 0-Task threads
        // that got a Task without an isolated branch) have nothing for
        // push to act on — the gate must refuse without parking.
        Task seeded = newTask(threadId, /* branchName */ null, /* worktreePath */ null);
        tasks.saveTask(seeded);

        JsonNode response = invokePush(threadId);

        assertThat(textOf(response)).contains("the active task has no worktree");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.RUNNING);
        assertThat(notifications.listForThread(threadId)).isEmpty();
    }

    @Test
    void postCommentParksActiveTaskAtAwaitingReviewWithBodyAndPrInPayload()
            throws Exception
    {
        String workingDir = "/tmp/bytequay-test-fake-clone-post-comment";
        watchedRepos.add("acme", "widget");
        watchedRepos.setLocalClonePath("acme", "widget", workingDir);
        String threadId = newThread("Post-comment fixture");
        Task seeded = newTaskWithLinkedPr(threadId, workingDir, 42);
        tasks.saveTask(seeded);

        String body = "LGTM, ship it once CI is green.";
        JsonNode response = invokePostComment(threadId, body);

        assertThat(textOf(response)).startsWith("Parked at AWAITING_REVIEW.");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.AWAITING_REVIEW);

        Notification latest = newestAwaitingReviewFor(threadId);
        JsonNode payload = mapper.readTree(latest.payloadJson());
        assertThat(payload.path("action").asText()).isEqualTo("post_comment");
        assertThat(payload.path("body").asText()).isEqualTo(body);
        assertThat(payload.path("pr").path("owner").asText()).isEqualTo("acme");
        assertThat(payload.path("pr").path("repo").asText()).isEqualTo("widget");
        assertThat(payload.path("pr").path("number").asInt()).isEqualTo(42);
        assertThat(payload.path("source").asText()).isEqualTo("mcp:post_comment");
    }

    @Test
    void postCommentRefusesWhenBodyIsBlank()
            throws Exception
    {
        String threadId = newThread("Post-comment blank body");
        Task seeded = newTaskWithLinkedPr(threadId, "/tmp/whatever", 1);
        tasks.saveTask(seeded);

        JsonNode response = invokePostComment(threadId, "   ");

        assertThat(textOf(response)).isEqualTo("body is required");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.RUNNING);
        assertThat(notifications.listForThread(threadId)).isEmpty();
    }

    @Test
    void postCommentRefusesWhenActiveTaskHasNoLinkedPr()
            throws Exception
    {
        String threadId = newThread("Post-comment without a linked PR");
        // Task is present and has a workingDir, but linked_pr_number is
        // null — there's nothing to comment on, and the gate must say so
        // rather than fall through and emit an AWAITING_REVIEW row that
        // the user could never actually approve.
        Task seeded = newTask(threadId, "main", null);
        tasks.saveTask(seeded);

        JsonNode response = invokePostComment(threadId, "Hi");

        assertThat(textOf(response))
                .contains("no PR linked to the active task");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.RUNNING);
        assertThat(notifications.listForThread(threadId)).isEmpty();
    }

    @Test
    void requestReviewRefactorStillParksAtAwaitingReview()
            throws Exception
    {
        // The refactor that introduced the shared helper has no
        // dedicated test of the existing request_review path; anchor it
        // here so a future change can't quietly regress the parked
        // state or the notification.
        String threadId = newThread("Request-review refactor anchor");
        Task seeded = newTask(threadId, "feature/req-review", "/tmp/bytequay-test-req-review");
        tasks.saveTask(seeded);

        JsonNode response = invokeRequestReview(threadId,
                "Done, please review.", "Approved with one nit.");

        assertThat(textOf(response)).startsWith("Parked at AWAITING_REVIEW.");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.AWAITING_REVIEW);

        Notification latest = newestAwaitingReviewFor(threadId);
        JsonNode payload = mapper.readTree(latest.payloadJson());
        assertThat(payload.path("summary").asText()).isEqualTo("Done, please review.");
        assertThat(payload.path("draftReply").asText()).isEqualTo("Approved with one nit.");
        assertThat(payload.path("source").asText()).isEqualTo("mcp:request_review");
    }

    private JsonNode invokePush(String threadId)
            throws Exception
    {
        String rpc = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/call",
                  "params": { "name": "push", "arguments": {} }
                }
                """;
        return resolved(controller.handle(threadId, mapper.readTree(rpc)));
    }

    private JsonNode invokePostComment(String threadId, String body)
            throws Exception
    {
        String rpc = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/call",
                  "params": { "name": "post_comment", "arguments": { "body": %s } }
                }
                """.formatted(mapper.writeValueAsString(body));
        return resolved(controller.handle(threadId, mapper.readTree(rpc)));
    }

    private JsonNode invokeRequestReview(String threadId, String summary, String draftReply)
            throws Exception
    {
        String rpc = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/call",
                  "params": {
                    "name": "request_review",
                    "arguments": { "summary": %s, "draft_reply": %s }
                  }
                }
                """.formatted(mapper.writeValueAsString(summary), mapper.writeValueAsString(draftReply));
        return resolved(controller.handle(threadId, mapper.readTree(rpc)));
    }

    private static JsonNode resolved(DeferredResult<JsonNode> deferred)
    {
        Object got = deferred.getResult();
        assertThat(got)
                .as("MCP call should resolve synchronously — parked tools no longer block on a gate decision")
                .isInstanceOf(JsonNode.class);
        return (JsonNode) got;
    }

    private static String textOf(JsonNode rpcResponse)
    {
        JsonNode content = rpcResponse.path("result").path("content");
        assertThat(content.isArray()).isTrue();
        return content.get(0).path("text").asText();
    }

    private Notification newestAwaitingReviewFor(String threadId)
    {
        List<Notification> all = notifications.listForThread(threadId);
        return all.stream()
                .filter(n -> n.kind() == NotificationKind.AWAITING_REVIEW)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no AWAITING_REVIEW notification for thread " + threadId));
    }

    private String newThread(String title)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        Thread t = new Thread(
                UUID.randomUUID().toString(),
                ThreadKind.CLI_AGENT,
                "claude-code",
                /* agentSessionId */ null,
                title,
                ThreadStatus.RUNNING,
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD, null);
        threads.saveThread(t);
        return t.id();
    }

    private static Task newTask(String threadId, String branchName, String worktreePath)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Task(
                UUID.randomUUID().toString(), threadId, 1L, TaskStatus.RUNNING,
                branchName,
                worktreePath,
                /* baseBranch */ "main",
                /* workingDir */ worktreePath == null ? "/tmp/bytequay-test-fake" : worktreePath,
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* firstMsgSeq */ null, /* lastMsgSeq */ null,
                now, /* endedAt */ null, /* errorMessage */ null);
    }

    private static Task newTaskWithLinkedPr(String threadId, String workingDir, int linkedPrNumber)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Task(
                UUID.randomUUID().toString(), threadId, 1L, TaskStatus.RUNNING,
                /* branchName */ "main",
                /* worktreePath */ null,
                /* baseBranch */ "main",
                workingDir,
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                linkedPrNumber, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* firstMsgSeq */ null, /* lastMsgSeq */ null,
                now, /* endedAt */ null, /* errorMessage */ null);
    }
}
