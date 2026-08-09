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

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.tools.AgentRole;
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
 * Coverage for the read tools — {@code read_task},
 * {@code read_workspace_memory} — now that they declare and handle
 * themselves on {@code AgentToolHandlers} and dispatch through
 * {@code AgentToolRegistry.invoke}. These tests drive the full seam:
 * the MCP envelope, the registry's wire-name arg binding, the shared
 * handler, and the controller's outcome adapter — so a regression in
 * any link surfaces here.
 */
@SpringBootTest
class TestMcpReadTools
{
    @Autowired
    private McpController controller;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private ThreadTurnStore turns;
    @Autowired
    private ActiveAgentContextRegistry activeContexts;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void readWorkspaceMemoryReturnsTheBoundWorkspacesBody()
            throws Exception
    {
        // ws-default is seeded by the workspace migration with an
        // empty memory body, so the success path returns it verbatim.
        String threadId = newThread("ws-default");
        activateReadTools(threadId);

        JsonNode response = await(controller.handle(threadId, "trunk",
                jsonRpc("read_workspace_memory", mapper.createObjectNode())));

        String text = response.path("result").path("content").get(0).path("text").asText();
        JsonNode payload = mapper.readTree(text);
        // A success rides as plain JSON, not a deny envelope.
        assertThat(payload.has("behavior")).isFalse();
        assertThat(payload.path("workspaceId").asText()).isEqualTo("ws-default");
        assertThat(payload.has("memoryMd")).isTrue();
    }

    @Test
    void readTaskWithoutAnIdReturnsACorrectableToolError()
            throws Exception
    {
        // A handler rejection is a tool-execution error (isError on a
        // successful response), not the permission deny envelope that ends
        // the turn — the model has to be able to read this and retry with
        // the missing argument.
        String threadId = newThread("ws-default");
        activateReadTools(threadId);

        JsonNode response = await(controller.handle(threadId, "trunk",
                jsonRpc("read_task", mapper.createObjectNode())));

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(toolErrorText(response)).contains("task_id is required");
        assertThat(toolErrorText(response)).doesNotContain("behavior");
    }

    @Test
    void readTaskBindsTheSnakeCaseWireNameAndReportsAMiss()
            throws Exception
    {
        // The wire field is task_id (snake_case) while the record
        // component is taskId — proves the registry's wire-name remap
        // reaches the handler: a value supplied under task_id must
        // surface as the looked-up id in the not-found message.
        String threadId = newThread("ws-default");
        activateReadTools(threadId);

        JsonNode response = await(controller.handle(threadId, "trunk",
                jsonRpc("read_task", mapper.createObjectNode()
                        .put("task_id", "task-does-not-exist"))));

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(toolErrorText(response))
                .contains("task not found")
                .contains("task-does-not-exist");
    }

    private JsonNode jsonRpc(String toolName, JsonNode arguments)
    {
        var params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        var node = mapper.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", UUID.randomUUID().toString());
        node.put("method", "tools/call");
        node.set("params", params);
        return node;
    }

    private void activateReadTools(String threadId)
    {
        activeContexts.put(threadId, "trunk", new ResolvedAgentContext(
                ByteQuayRole.TRUNK, "1", AgentRole.TRUNK, null,
                RoleCapabilities.forRole(AgentRole.TRUNK), List.of(), ImmutableSet.of(),
                ImmutableSet.of("read_task", "read_workspace_memory")));
    }

    /** The message an errored tool call hands back to the model. Plain text,
     *  not a JSON envelope — the reason has to be readable as-is. */
    private String toolErrorText(JsonNode response)
    {
        return response.path("result").path("content").get(0).path("text").asText();
    }

    private static JsonNode await(DeferredResult<JsonNode> deferred)
            throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!deferred.hasResult() && System.currentTimeMillis() < deadline) {
            java.lang.Thread.sleep(10);
        }
        if (!deferred.hasResult()) {
            throw new IllegalStateException("DeferredResult did not complete in time");
        }
        return (JsonNode) deferred.getResult();
    }

    private String newThread(String workspaceId)
    {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Thread thread = new Thread(
                id,
                ThreadKind.CLI_AGENT,
                /* provider */ "claude-code",
                /* agentSessionId */ null,
                "Read-tools fixture",
                ThreadStatus.IDLE,
                /* model */ "test",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD,
                workspaceId,
                /* workModel */ null,
                /* activeTask */ null);
        threads.saveThread(thread);
        turns.saveTurn(new ThreadTurn(
                UUID.randomUUID().toString(), id, null,
                ThreadResourceLane.CLI, ThreadTurnStatus.RUNNING, "input",
                now, now, now, null, null, TurnInitiator.user(), null, ThreadScope.TRUNK));
        return id;
    }
}
