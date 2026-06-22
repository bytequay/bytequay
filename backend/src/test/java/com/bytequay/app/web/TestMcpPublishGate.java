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
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.stage.PlanStageService;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.PublishService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private StageStore stageStore;
    @Autowired
    private PlanStageService planStageService;
    @Autowired
    private WatchedRepoStore watchedRepos;
    @Autowired
    private NotificationService notifications;
    @Autowired
    private PublishService publishes;
    @Autowired
    private NotificationController notificationController;
    @Autowired
    private McpPermissionGate gate;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void pushParksActiveTaskAtAwaitingReviewAndEmitsNotification()
            throws Exception
    {
        String threadId = newThread("Push gate fixture");
        Task seeded = newTask(threadId, "feature/push-gate", "/tmp/bytequay-test-fake-worktree-push");
        saveActiveTask(seeded);

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

        // A 0-task thread resolves to the TRUNK role; the push tool's
        // roles array doesn't include TRUNK, so the registry-driven
        // dispatch denies before the per-tool handler runs. Either
        // wording (the roles-filter "not available to the current
        // role" or the capability "not granted") is a legitimate
        // refusal — both end the call without writing a notification,
        // which is the invariant the original test pinned.
        assertThat(textOf(response)).contains("TRUNK");
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
        saveActiveTask(seeded);

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
        saveActiveTask(seeded);

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
        saveActiveTask(seeded);

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
        saveActiveTask(seeded);

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
        saveActiveTask(seeded);

        JsonNode response = invokeRequestReview(threadId,
                "Done, please review.", "Approved with one nit.");

        assertThat(textOf(response)).startsWith("Parked at AWAITING_REVIEW.");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.AWAITING_REVIEW);

        Notification latest = newestAwaitingReviewFor(threadId);
        JsonNode payload = mapper.readTree(latest.payloadJson());
        assertThat(payload.path("action").asText()).isEqualTo("request_review");
        assertThat(payload.path("summary").asText()).isEqualTo("Done, please review.");
        assertThat(payload.path("draftReply").asText()).isEqualTo("Approved with one nit.");
        assertThat(payload.path("source").asText()).isEqualTo("mcp:request_review");
        assertThatThrownBy(() -> notificationController.dismiss(latest.id()))
                .hasMessageContaining("must be resolved from its review flow");

        PublishService.PublishResult approved = publishes.approve(latest.id(), null, "request_review");
        assertThat(approved.action()).isEqualTo("request_review");
        assertThat(approved.message()).contains("No remote changes");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
        assertThat(notifications.find(latest.id()).orElseThrow().status())
                .isEqualTo(NotificationStatus.RESOLVED);
        assertThatThrownBy(() -> publishes.approve(latest.id(), null, "request_review"))
                .hasMessageContaining("already resolved");
    }

    @Test
    void requestReviewRefusesWhenActiveTaskHasNoReviewableWorktree()
            throws Exception
    {
        String threadId = newThread("Request-review without worktree");
        Task seeded = newTask(threadId, /* branchName */ null, /* worktreePath */ null);
        saveActiveTask(seeded);

        JsonNode response = invokeRequestReview(threadId, "Done.", "");

        assertThat(textOf(response)).contains("no diff is available for review");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.RUNNING);
        assertThat(notifications.listForThread(threadId)).isEmpty();
    }

    @Test
    void approvalWithJsonNullActionReportsMissingDiscriminator()
            throws Exception
    {
        String threadId = newThread("Approval null expected action");
        Task seeded = newTask(threadId, "feature/null-action", "/tmp/bytequay-test-null-action");
        saveActiveTask(seeded);
        invokeRequestReview(threadId, "Ready.", "");
        Notification latest = newestAwaitingReviewFor(threadId);

        assertThatThrownBy(() -> notificationController.approve(
                latest.id(), mapper.readTree("{\"expectedAction\":null}")))
                .hasMessageContaining("expectedAction is required");
        assertThat(notifications.find(latest.id()).orElseThrow().status())
                .isEqualTo(NotificationStatus.UNREAD);
    }

    @Test
    void nextTaskParksAProposalWithoutAdvancingUntilApproval()
            throws Exception
    {
        String threadId = newThread("Next task gate fixture");
        Task seeded = newTask(threadId, "feature/current", "/tmp/bytequay-test-next-current");
        saveActiveTask(seeded);

        JsonNode response = invokeNextTask(threadId, "Follow-up", "stacked");

        assertThat(textOf(response)).startsWith("Parked at AWAITING_REVIEW (next_task).");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.AWAITING_REVIEW);
        assertThat(tasks.listTasksByThread(threadId)).extracting(Task::id)
                .containsExactly(seeded.id());

        JsonNode payload = mapper.readTree(newestAwaitingReviewFor(threadId).payloadJson());
        assertThat(payload.path("action").asText()).isEqualTo("next_task");
        assertThat(payload.path("nextTitle").asText()).isEqualTo("Follow-up");
        assertThat(payload.path("baseMode").asText()).isEqualTo("stacked");
        assertThat(payload.path("source").asText()).isEqualTo("mcp:next_task");
    }

    @Test
    void shipTaskParksAReviewableProposalWithDiffMetadata()
            throws Exception
    {
        String threadId = newThread("Ship task gate fixture");
        Task seeded = newTask(threadId, "feature/finished", "/tmp/bytequay-test-ship-current");
        tasks.saveTask(seeded);
        approvePlan(seeded.id());

        JsonNode response = invokeShipTask(threadId, "After ship", "main");

        assertThat(textOf(response)).startsWith("Parked at AWAITING_REVIEW (ship_task).");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.AWAITING_REVIEW);
        // The agent finished implementing and proposed a ship, so the
        // dev-lifecycle phase fast-forwards from IMPLEMENTING to AWAITING_PUSH
        // — the flow stepper reads "Push", not a stale "Implement".
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().phase())
                .isEqualTo(TaskPhase.AWAITING_PUSH);
        JsonNode payload = mapper.readTree(newestAwaitingReviewFor(threadId).payloadJson());
        assertThat(payload.path("action").asText()).isEqualTo("ship_task");
        assertThat(payload.path("branch").asText()).isEqualTo("feature/finished");
        assertThat(payload.path("worktreePath").asText())
                .isEqualTo("/tmp/bytequay-test-ship-current");
        assertThat(payload.path("nextTitle").asText()).isEqualTo("After ship");
        assertThat(payload.path("baseMode").asText()).isEqualTo("main");
        assertThat(payload.has("diff") || payload.has("diffError")).isTrue();
        assertThat(payload.path("source").asText()).isEqualTo("mcp:ship_task");
    }

    @Test
    void validateAdvancesAnImplementingTaskToValidating()
            throws Exception
    {
        String threadId = newThread("Validate fixture");
        Task seeded = newTask(threadId, "feature/x", "/tmp/bytequay-test-validate");
        tasks.saveTask(seeded);
        approvePlan(seeded.id());

        JsonNode response = invokeValidate(threadId, "ran the unit tests");

        assertThat(textOf(response)).contains("VALIDATING");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().phase())
                .isEqualTo(TaskPhase.VALIDATING);
    }

    @Test
    void validateIsANoOpOnceTheTaskIsPastValidation()
            throws Exception
    {
        // Review sits behind validate in the lifecycle, so a task already at
        // AWAITING_PUSH has validated — validate must not rewind it.
        String threadId = newThread("Validate no-op fixture");
        Task seeded = newTask(threadId, "feature/y", "/tmp/bytequay-test-validate-noop");
        tasks.saveTask(seeded);
        tasks.updatePhase(seeded.id(), TaskPhase.AWAITING_PUSH);

        JsonNode response = invokeValidate(threadId, null);

        assertThat(textOf(response)).contains("already at or past validation");
        assertThat(tasks.findTaskById(seeded.id()).orElseThrow().phase())
                .isEqualTo(TaskPhase.AWAITING_PUSH);
    }

    @Test
    void approvalPromptIsDeniedOnceTheThreadHasAnAwaitingReviewTask()
            throws Exception
    {
        // Once request_review (or push / post_comment) parks the
        // active task, the agent's turn is logically over. A
        // misbehaving agent that follows up with Bash / Edit / Write
        // must be refused structurally — the generic permission gate
        // doesn't otherwise look at task status and would happily
        // surface a prompt as if work were still in progress.
        String threadId = newThread("Parked-thread guard fixture");
        tasks.saveTask(parkedTask(threadId, TaskStatus.AWAITING_REVIEW));

        JsonNode response = invokeApprovalPrompt(threadId, "Bash", "call-bash-1");

        JsonNode envelope = parseToolEnvelope(response);
        assertThat(envelope.path("behavior").asText()).isEqualTo("deny");
        assertThat(envelope.path("message").asText())
                .contains("parked at the publish gate");
    }

    @Test
    void approvalPromptIsDeniedOnceTheThreadHasANeedsAttentionTask()
            throws Exception
    {
        // NEEDS_ATTENTION isn't written by any code today, but it's a
        // parked state per the design and the guard treats it the
        // same. Cheap future-proofing against the second parked state
        // landing in a later change.
        String threadId = newThread("Parked NEEDS_ATTENTION fixture");
        tasks.saveTask(parkedTask(threadId, TaskStatus.NEEDS_ATTENTION));

        JsonNode response = invokeApprovalPrompt(threadId, "Edit", "call-edit-1");

        JsonNode envelope = parseToolEnvelope(response);
        assertThat(envelope.path("behavior").asText()).isEqualTo("deny");
        assertThat(envelope.path("message").asText())
                .contains("parked at the publish gate");
    }

    @Test
    void parkedPriorTaskDoesNotBlockApprovalPromptFromAnActiveSuccessor()
            throws Exception
    {
        String threadId = newThread("Successor prompt fixture");
        tasks.saveTask(parkedTask(threadId, TaskStatus.AWAITING_REVIEW));
        tasks.saveTask(newTask(threadId, 2L, "feature/next", "/tmp/bytequay-test-successor"));

        DeferredResult<JsonNode> pending = requestApprovalPrompt(threadId, "Bash", "call-successor");

        assertThat(pending.hasResult()).isFalse();
        gate.decide("call-successor", PermissionDecision.ALLOW);
        JsonNode envelope = parseToolEnvelope(resolved(pending));
        assertThat(envelope.path("behavior").asText()).isEqualTo("allow");
    }

    @Test
    void needsAttentionPriorTaskStillBlocksApprovalPromptFromActiveSuccessor()
            throws Exception
    {
        String threadId = newThread("Needs-attention successor fixture");
        tasks.saveTask(parkedTask(threadId, TaskStatus.NEEDS_ATTENTION));
        tasks.saveTask(newTask(threadId, 2L, "feature/next", "/tmp/bytequay-test-attention-successor"));

        JsonNode response = invokeApprovalPrompt(threadId, "Bash", "call-attention-successor");

        JsonNode envelope = parseToolEnvelope(response);
        assertThat(envelope.path("behavior").asText()).isEqualTo("deny");
        assertThat(envelope.path("message").asText())
                .contains("parked at the publish gate");
    }

    @Test
    void recallThreadStillWorksWhenTheThreadIsParked()
            throws Exception
    {
        // recall_thread is read-only — the design row puts it explicitly
        // outside the gate. It must still resolve so the agent can pull
        // cross-thread context even while a parked proposal is open.
        String threadId = newThread("Parked thread recall");
        tasks.saveTask(parkedTask(threadId, TaskStatus.AWAITING_REVIEW));

        JsonNode response = invokeRecallThread(threadId, "anything");

        // recall_thread returns text content; the parked-guard deny
        // shape (tool envelope with behavior=deny) is conspicuously
        // absent.
        assertThat(textOf(response)).isNotBlank();
        assertThat(textOf(response)).doesNotContain("parked at the publish gate");
    }

    @Test
    void requestReviewRefusesWhenTheThreadIsAlreadyParked()
            throws Exception
    {
        String threadId = newThread("Double-park request_review");
        tasks.saveTask(parkedTask(threadId, TaskStatus.AWAITING_REVIEW));

        JsonNode response = invokeRequestReview(threadId, "Already parked.", "");

        // A parked task (AWAITING_REVIEW) is not an *active* task, so the
        // thread resolves to the TRUNK role — request_review is TASK-only
        // and is refused at the role gate before reaching the handler.
        // Either way it's refused and no notification is parked.
        assertThat(textOf(response)).contains("not available to the current role");
        assertThat(notifications.listForThread(threadId)).isEmpty();
    }

    @Test
    void markReadStillFlipsAnInformationalAuditRow()
    {
        // AUTO_FIX_DONE rows are informational; reading one clears it
        // from the bell as before.
        String threadId = newThread("Mark-read audit fixture");
        Task task = newTask(threadId, "feature/audit", "/tmp/bytequay-test-audit");
        tasks.saveTask(task);
        Notification audit = notifications.notifyAutoFixDone(threadId, task.id(), "{}");

        Notification next = notificationController.markRead(audit.id());

        assertThat(next.status()).isEqualTo(NotificationStatus.READ);
        assertThat(next.readAt()).isNotNull();
    }

    @Test
    void needsAttentionRowStaysUndismissibleWhileItsTaskIsBlockingTheThread()
    {
        // Dismissing a NEEDS_ATTENTION bell row whose task is still
        // NEEDS_ATTENTION would clear the only affordance pointing at a
        // stuck task while isThreadParked keeps gating the agent. Block
        // it until the task is resolved.
        String threadId = newThread("Needs-attention dismiss guard");
        Task stuck = parkedTask(threadId, TaskStatus.NEEDS_ATTENTION);
        tasks.saveTask(stuck);
        Notification attention = notifications.notifyNeedsAttention(threadId, stuck.id(), "{}");

        assertThatThrownBy(() -> notificationController.dismiss(attention.id()))
                .hasMessageContaining("still needs attention");
        assertThat(notifications.find(attention.id()).orElseThrow().status())
                .isEqualTo(NotificationStatus.UNREAD);
    }

    @Test
    void needsAttentionRowIsDismissibleOnceItsTaskNoLongerNeedsAttention()
    {
        // A CI-failure NEEDS_ATTENTION row on an already-shipped task is
        // purely informational — it must stay dismissible so such rows
        // don't accumulate in the bell forever.
        String threadId = newThread("Needs-attention dismiss allowed");
        Task shipped = parkedTask(threadId, TaskStatus.COMPLETED);
        tasks.saveTask(shipped);
        Notification attention = notifications.notifyNeedsAttention(threadId, shipped.id(), "{}");

        Notification dismissed = notificationController.dismiss(attention.id());

        assertThat(dismissed.status()).isEqualTo(NotificationStatus.DISMISSED);
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

    private JsonNode invokeApprovalPrompt(String threadId, String toolName, String callId)
            throws Exception
    {
        return resolved(requestApprovalPrompt(threadId, toolName, callId));
    }

    private DeferredResult<JsonNode> requestApprovalPrompt(String threadId, String toolName, String callId)
            throws Exception
    {
        String rpc = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/call",
                  "params": {
                    "name": "approval_prompt",
                    "arguments": {
                      "tool_name": %s,
                      "tool_use_id": %s,
                      "input": {}
                    }
                  }
                }
                """.formatted(
                        mapper.writeValueAsString(toolName),
                        mapper.writeValueAsString(callId));
        return controller.handle(threadId, mapper.readTree(rpc));
    }

    private JsonNode invokeRecallThread(String threadId, String query)
            throws Exception
    {
        String rpc = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/call",
                  "params": {
                    "name": "recall_thread",
                    "arguments": { "query": %s, "limit": 5 }
                  }
                }
                """.formatted(mapper.writeValueAsString(query));
        return resolved(controller.handle(threadId, mapper.readTree(rpc)));
    }

    /** The approval_prompt path returns the gate envelope (allow/deny)
     *  wrapped as JSON text inside the MCP content array — unwrap so
     *  the test can assert on {@code behavior} / {@code message}. */
    private JsonNode parseToolEnvelope(JsonNode rpcResponse)
            throws Exception
    {
        JsonNode content = rpcResponse.path("result").path("content");
        assertThat(content.isArray()).isTrue();
        String envelopeJson = content.get(0).path("text").asText();
        return mapper.readTree(envelopeJson);
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

    private JsonNode invokeNextTask(String threadId, String nextTitle, String baseMode)
            throws Exception
    {
        String rpc = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/call",
                  "params": {
                    "name": "next_task",
                    "arguments": { "next_title": %s, "base_mode": %s }
                  }
                }
                """.formatted(mapper.writeValueAsString(nextTitle), mapper.writeValueAsString(baseMode));
        return resolved(controller.handle(threadId, mapper.readTree(rpc)));
    }

    private JsonNode invokeValidate(String threadId, String summary)
            throws Exception
    {
        String args = summary == null
                ? "{}"
                : "{ \"summary\": " + mapper.writeValueAsString(summary) + " }";
        String rpc = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/call",
                  "params": { "name": "validate", "arguments": %s }
                }
                """.formatted(args);
        return resolved(controller.handle(threadId, mapper.readTree(rpc)));
    }

    private JsonNode invokeShipTask(String threadId, String nextTitle, String baseMode)
            throws Exception
    {
        String rpc = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/call",
                  "params": {
                    "name": "ship_task",
                    "arguments": { "next_title": %s, "base_mode": %s }
                  }
                }
                """.formatted(mapper.writeValueAsString(nextTitle), mapper.writeValueAsString(baseMode));
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

    /** Approve a plan so the task leaves PLANNING for IMPLEMENTING with an
     *  open DevelopmentStage — the precondition the publish gates assume. */
    private void approvePlan(String taskId)
    {
        stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        planStageService.approve(taskId, "rev-1");
    }

    /** Seed a task that's already at dev altitude. The publish gates
     *  (push / post_comment / request_review / next_task) act on an
     *  in-flight, plan-approved task — one still PLANNING resolves to the
     *  TRUNK role and never reaches the per-tool handler. {@code saveTask}
     *  doesn't map the phase column, so move it to IMPLEMENTING explicitly. */
    private void saveActiveTask(Task seeded)
    {
        tasks.saveTask(seeded);
        tasks.updatePhase(seeded.id(), TaskPhase.IMPLEMENTING);
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
                ThreadFlow.BUILD, "ws-default", null, null);
        threads.saveThread(t);
        return t.id();
    }

    private static Task newTask(String threadId, String branchName, String worktreePath)
    {
        return newTask(threadId, 1L, branchName, worktreePath);
    }

    private static Task newTask(String threadId, long seq, String branchName, String worktreePath)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Task(
                UUID.randomUUID().toString(), threadId, seq, TaskStatus.RUNNING,
                branchName,
                worktreePath,
                /* baseBranch */ "main",
                /* workingDir */ worktreePath == null ? "/tmp/bytequay-test-fake" : worktreePath,
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                now, /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null, /* workModel */ null);
    }

    /** Builds a task pre-parked at the given status so the parked-
     *  thread guard tests don't have to drive a full request_review /
     *  push flow to get there. */
    private static Task parkedTask(String threadId, TaskStatus status)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Task(
                UUID.randomUUID().toString(), threadId, 1L, status,
                "feature/parked", "/tmp/bytequay-test-parked", "main",
                "/tmp/bytequay-test-parked",
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                now, /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null, /* workModel */ null);
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
                /* agentSessionId */ null,
                now, /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null, /* workModel */ null);
    }
}
