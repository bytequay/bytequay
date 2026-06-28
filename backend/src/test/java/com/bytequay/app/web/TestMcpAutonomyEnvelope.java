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
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
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
 * Coverage for the autonomy envelope at the approval gate. When a turn
 * runs unattended (the CI auto-fix coordinator's turns) there is no
 * human to answer a built-in-tool permission prompt, so the gate
 * decides by capability: an in-bounds tool (its capability is in the
 * thread's grants) auto-allows under that standing policy, while an
 * out-of-bounds tool escalates to a needs-attention notification and
 * denies. Attended turns keep the unchanged prompt flow.
 */
@SpringBootTest
class TestMcpAutonomyEnvelope
{
    @Autowired
    private McpController controller;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private ThreadTurnStore turns;
    @Autowired
    private NotificationService notifications;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void unattendedTurnAutoAllowsAnInBoundsBuiltinTool()
            throws Exception
    {
        // A task thread (TASK role) grants CODE_EXEC, so Bash is
        // in-bounds and runs without a prompt — the call resolves
        // synchronously to an allow envelope.
        String threadId = newTaskThread();
        saveRunningTurn(threadId, TurnInitiator.unattended("auto-fix-ci-fail"));

        JsonNode response = resolved(requestApprovalPrompt(threadId, "Bash", "call-bash"));

        JsonNode envelope = parseEnvelope(response);
        assertThat(envelope.path("behavior").asText()).isEqualTo("allow");
    }

    @Test
    void unattendedTurnEscalatesAndDeniesAnOutOfBoundsBuiltinTool()
            throws Exception
    {
        // WebFetch maps to no capability, so it is out-of-bounds — the
        // gate escalates with a needs-attention notification and denies
        // rather than registering a prompt no one would answer.
        String threadId = newTaskThread();
        saveRunningTurn(threadId, TurnInitiator.unattended("auto-fix-ci-fail"));

        JsonNode response = resolved(requestApprovalPrompt(threadId, "WebFetch", "call-web"));

        JsonNode envelope = parseEnvelope(response);
        assertThat(envelope.path("behavior").asText()).isEqualTo("deny");
        assertThat(envelope.path("message").asText()).contains("autonomy envelope");

        List<Notification> escalations = notifications.listForThread(threadId).stream()
                .filter(n -> n.kind() == NotificationKind.NEEDS_ATTENTION)
                .toList();
        assertThat(escalations).hasSize(1);
        assertThat(escalations.get(0).payloadJson()).contains("WebFetch");
    }

    @Test
    void attendedTurnStillRegistersAPromptForTheSameTool()
            throws Exception
    {
        // The exact tool (WebFetch) the unattended turn escalates: an
        // attended turn must instead surface a prompt and wait — the
        // envelope is unattended-only, so the call stays pending.
        String threadId = newTaskThread();
        saveRunningTurn(threadId, TurnInitiator.user());

        DeferredResult<JsonNode> pending = requestApprovalPrompt(threadId, "WebFetch", "call-attended");

        assertThat(pending.hasResult()).isFalse();
        assertThat(notifications.listForThread(threadId).stream()
                .anyMatch(n -> n.kind() == NotificationKind.NEEDS_ATTENTION))
                .isFalse();
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

    private JsonNode parseEnvelope(JsonNode rpcResponse)
            throws Exception
    {
        JsonNode content = rpcResponse.path("result").path("content");
        assertThat(content.isArray()).isTrue();
        return mapper.readTree(content.get(0).path("text").asText());
    }

    private static JsonNode resolved(DeferredResult<JsonNode> deferred)
    {
        Object got = deferred.getResult();
        assertThat(got).isInstanceOf(JsonNode.class);
        return (JsonNode) got;
    }

    private String newTaskThread()
    {
        Instant now = Instant.parse("2026-05-28T12:00:00Z");
        String threadId = UUID.randomUUID().toString();
        threads.saveThread(new Thread(
                threadId, ThreadKind.CLI_AGENT, "claude-code", null,
                "Autonomy-envelope fixture", ThreadStatus.RUNNING, "test",
                0L, 0L, 0L, now, now, null, null,
                ThreadFlow.BUILD, "ws-default", null, null));
        // A RUNNING task at dev altitude makes the thread TASK-role (grants
        // CODE_*) and keeps the park-guard happy (an active, unparked task).
        // A PLANNING task (saveTask's default phase) stays TRUNK, so move it
        // to IMPLEMENTING. The worktree path is unique per thread so the
        // shared worktree lease doesn't collide across tests in this class.
        String worktree = "/tmp/bytequay-test-envelope-" + threadId;
        String taskId = UUID.randomUUID().toString();
        tasks.saveTask(new Task(
                taskId, threadId, 1L, TaskStatus.RUNNING,
                "feature/auto", worktree, "main", worktree,
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        tasks.updatePhase(taskId, TaskPhase.IMPLEMENTING);
        return threadId;
    }

    private void saveRunningTurn(String threadId, TurnInitiator initiator)
    {
        Instant now = Instant.parse("2026-05-28T12:00:00Z");
        // Task-scope the turn so the resolver derives the TASK role from it
        // (the role now comes from the running turn's scope, not the thread's
        // task projection).
        String taskId = tasks.findActiveTaskForThread(threadId).map(Task::id).orElse(null);
        turns.saveTurn(new ThreadTurn(
                UUID.randomUUID().toString(), threadId, taskId,
                ThreadResourceLane.CLI, ThreadTurnStatus.RUNNING, "input",
                now, now, now, null, null, initiator));
    }
}
