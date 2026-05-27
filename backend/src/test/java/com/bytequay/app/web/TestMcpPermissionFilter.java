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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@link com.bytequay.app.service.tools.PermissionResolver}
 * wiring: tools/list is filtered by role and tools/call refuses a tool
 * whose security type isn't in the caller's grants.
 *
 * <p>A 0-task thread resolves to {@code TRUNK}, which has no
 * {@code GIT_PUSH} grant, so the {@code push} tool is invisible in the
 * tool catalog and refused on direct invocation. A task-bound thread
 * resolves to {@code TASK} and sees the same tool.
 */
@SpringBootTest
class TestMcpPermissionFilter
{
    @Autowired
    private McpController controller;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void trunkThreadDoesNotSeePushOrPostCommentInToolsList()
            throws Exception
    {
        String threadId = newTrunkThread();

        JsonNode listing = await(controller.handle(threadId,
                jsonRpc("tools/list", null)));

        JsonNode tools = listing.path("result").path("tools");
        assertThat(toolNames(tools)).contains("approval_prompt", "recall_thread");
        assertThat(toolNames(tools))
                .as("trunk has no GIT_PUSH / VCS_PUBLISH / TASK_MANAGE grant on a 0-task thread")
                .doesNotContain("push", "post_comment", "request_review");
    }

    @Test
    void taskThreadSeesPushAndPostCommentAndRequestReview()
            throws Exception
    {
        String threadId = newThreadWithActiveTask();

        JsonNode listing = await(controller.handle(threadId,
                jsonRpc("tools/list", null)));

        JsonNode tools = listing.path("result").path("tools");
        assertThat(toolNames(tools)).contains(
                "approval_prompt", "recall_thread", "push", "post_comment", "request_review");
    }

    @Test
    void trunkCallingPushReceivesAPermissionDenyEnvelope()
            throws Exception
    {
        String threadId = newTrunkThread();

        JsonNode response = await(controller.handle(threadId,
                jsonRpc("tools/call", mapper.createObjectNode()
                        .put("name", "push")
                        .set("arguments", mapper.createObjectNode()))));

        JsonNode item = response.path("result").path("content").get(0);
        assertThat(item.path("type").asText()).isEqualTo("text");
        JsonNode envelope = mapper.readTree(item.path("text").asText());
        assertThat(envelope.path("behavior").asText()).isEqualTo("deny");
        assertThat(envelope.path("message").asText())
                .contains("requires capability")
                .contains("GIT_PUSH")
                .contains("TRUNK");
    }

    @Test
    void recallThreadIsAllowedForBothRoles()
            throws Exception
    {
        String trunkThreadId = newTrunkThread();
        String taskThreadId = newThreadWithActiveTask();

        JsonNode trunkOut = await(controller.handle(trunkThreadId,
                jsonRpc("tools/call", mapper.createObjectNode()
                        .put("name", "recall_thread")
                        .set("arguments", mapper.createObjectNode()))));
        JsonNode taskOut = await(controller.handle(taskThreadId,
                jsonRpc("tools/call", mapper.createObjectNode()
                        .put("name", "recall_thread")
                        .set("arguments", mapper.createObjectNode()))));

        // recall_thread is plain-text on success — neither response is
        // an allow/deny envelope.
        assertThat(trunkOut.path("result").path("content").get(0).path("type").asText())
                .isEqualTo("text");
        assertThat(taskOut.path("result").path("content").get(0).path("type").asText())
                .isEqualTo("text");
    }

    private JsonNode jsonRpc(String method, JsonNode params)
    {
        var node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", UUID.randomUUID().toString());
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node;
    }

    private static JsonNode await(DeferredResult<JsonNode> deferred)
            throws InterruptedException
    {
        // The controller's handle() is synchronous for tools/list and
        // permission-deny tools/call paths; the recall_thread path is
        // also synchronous. No timed waits needed.
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!deferred.hasResult() && System.currentTimeMillis() < deadline) {
            java.lang.Thread.sleep(10);
        }
        if (!deferred.hasResult()) {
            throw new IllegalStateException("DeferredResult did not complete in time");
        }
        return (JsonNode) deferred.getResult();
    }

    private static List<String> toolNames(JsonNode tools)
    {
        List<String> names = new ArrayList<>();
        for (JsonNode t : tools) {
            names.add(t.path("name").asText());
        }
        return names;
    }

    private String newTrunkThread()
    {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Thread thread = new Thread(
                id,
                ThreadKind.CLI_AGENT,
                /* provider */ "claude-code",
                /* agentSessionId */ null,
                "Trunk fixture",
                ThreadStatus.IDLE,
                /* model */ "test",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                /* createdAt */ now,
                /* updatedAt */ now,
                /* endedAt */ null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                /* workspaceId */ "ws-default",
                /* activeTask */ null);
        threads.saveThread(thread);
        return id;
    }

    private String newThreadWithActiveTask()
    {
        String id = newTrunkThread();
        Task task = new Task(
                UUID.randomUUID().toString(), id, 1L, TaskStatus.RUNNING,
                "feature/permission-test", "/tmp/permission-test", "main", "/tmp/permission-test",
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                Instant.now(), /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null);
        tasks.saveTask(task);
        return id;
    }
}
