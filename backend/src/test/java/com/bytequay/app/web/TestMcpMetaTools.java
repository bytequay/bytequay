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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies model-driven skill and tool catalog discovery is not exposed by
 * MCP. ByteQuay resolves the bounded set before the provider starts a turn.
 */
@SpringBootTest
class TestMcpMetaTools
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
    void toolsListOmitsModelDrivenCatalogOperations()
            throws InterruptedException
    {
        String threadId = newTrunkThread();
        activeContexts.put(threadId, "trunk", new ResolvedAgentContext(
                ByteQuayRole.TRUNK, "1", AgentRole.TRUNK, null,
                RoleCapabilities.forRole(AgentRole.TRUNK), List.of(), ImmutableSet.of(),
                ImmutableSet.of("approval_prompt", "recall_thread")));

        JsonNode response = await(controller.handle(threadId, "trunk",
                jsonRpc("tools/list", mapper.createObjectNode())));

        List<String> names = toNames(response.path("result").path("tools"));
        assertThat(names).contains("approval_prompt", "recall_thread");
        assertThat(names).doesNotContain(
                "list_tools", "list_skills", "load_skill",
                "push", "post_comment", "request_review");
    }

    @Test
    void removedCatalogOperationsAreUnknownEvenWhenCalledDirectly()
            throws InterruptedException
    {
        String threadId = newTrunkThread();

        for (String name : List.of("list_tools", "list_skills", "load_skill")) {
            JsonNode response = await(controller.handle(threadId, "trunk",
                    jsonRpc("tools/call", mapper.createObjectNode()
                            .put("name", name)
                            .set("arguments", mapper.createObjectNode()))));

            assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
            assertThat(response.path("error").path("message").asText())
                    .isEqualTo("unknown tool: " + name);
        }
    }

    @Test
    void activeTurnContextHidesAndRefusesUnselectedTools()
            throws Exception
    {
        String threadId = newTrunkThread();
        ResolvedAgentContext context = new ResolvedAgentContext(
                ByteQuayRole.TRUNK, "1", AgentRole.TRUNK, null,
                RoleCapabilities.forRole(AgentRole.TRUNK), List.of("codegraph-first"),
                ImmutableSet.of(), ImmutableSet.of("recall_thread"));
        activeContexts.put(threadId, "trunk", context);
        try {
            JsonNode listed = await(controller.handle(threadId, "trunk",
                    jsonRpc("tools/list", mapper.createObjectNode())));
            assertThat(toNames(listed.path("result").path("tools")))
                    .containsExactly("recall_thread");

            JsonNode called = await(controller.handle(threadId, "trunk",
                    jsonRpc("tools/call", mapper.createObjectNode()
                            .put("name", "read_pr")
                            .set("arguments", mapper.createObjectNode()))));
            String text = called.path("result").path("content").get(0).path("text").asText();
            assertThat(mapper.readTree(text).path("message").asText())
                    .contains("is not active for role trunk@1");
        }
        finally {
            activeContexts.remove(threadId, "trunk");
        }

        JsonNode betweenTurns = await(controller.handle(threadId, "trunk",
                jsonRpc("tools/list", mapper.createObjectNode())));
        assertThat(toNames(betweenTurns.path("result").path("tools"))).isEmpty();
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
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!deferred.hasResult() && System.currentTimeMillis() < deadline) {
            java.lang.Thread.sleep(10);
        }
        if (!deferred.hasResult()) {
            throw new IllegalStateException("DeferredResult did not complete in time");
        }
        return (JsonNode) deferred.getResult();
    }

    private static List<String> toNames(JsonNode catalog)
    {
        List<String> names = new ArrayList<>();
        for (JsonNode entry : catalog) {
            names.add(entry.path("name").asText());
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
                "Meta-tools fixture",
                ThreadStatus.IDLE,
                /* model */ "test",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD,
                /* workspaceId */ "ws-default",
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
