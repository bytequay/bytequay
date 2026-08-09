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
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.PermissionResolver;
import com.bytequay.app.service.tools.RoleCapabilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generic MCP route may still expose read-only historical state, but it
 * must never recreate the retired permission-session runtime. Exact V2 Turn
 * endpoints own all new approval prompts.
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
    private ActiveAgentContextRegistry activeContexts;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void legacyApprovalPromptFailsClosedInsteadOfCreatingAPermissionSession()
            throws Exception
    {
        String threadId = newTaskThread();
        JsonNode response = resolved(requestApprovalPrompt(
                threadId, "WebFetch", "call-attended"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(response.path("error").path("message").asText())
                .contains("Legacy permission sessions are retired")
                .contains("typed V2 execution endpoint");
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
        String taskId = tasks.activeTasksForThread(threadId).stream()
                .findFirst().map(Task::id).orElseThrow();
        activeContexts.put(threadId, taskId, new ResolvedAgentContext(
                ByteQuayRole.TASK, "1", AgentRole.TASK, null,
                RoleCapabilities.forRole(AgentRole.TASK), List.of(), ImmutableSet.of(),
                ImmutableSet.of("approval_prompt")),
                new PermissionResolver.RunningScope(
                        ThreadScope.TASK, taskId, null, null));
        return controller.handle(threadId, taskId, mapper.readTree(rpc));
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

}
