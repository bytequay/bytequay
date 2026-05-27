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
import com.bytequay.app.domain.ThreadStatus;
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
 * Coverage for the three meta tools — {@code list_tools},
 * {@code list_skills}, {@code load_skill} — that the MCP server
 * inherits from the {@link com.bytequay.app.service.tools.SkillTools}
 * runtime. None of these mutate state; the tests assert response
 * shapes and that the role filter keeps the catalog honest.
 */
@SpringBootTest
class TestMcpMetaTools
{
    @Autowired
    private McpController controller;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void listToolsReturnsCatalogFilteredByRole()
            throws Exception
    {
        String threadId = newTrunkThread();

        JsonNode response = await(controller.handle(threadId,
                jsonRpc("tools/call", mapper.createObjectNode()
                        .put("name", "list_tools")
                        .set("arguments", mapper.createObjectNode()))));

        // The plain-text content is itself a JSON array of catalog
        // entries.
        String text = response.path("result").path("content").get(0).path("text").asText();
        JsonNode catalog = mapper.readTree(text);
        assertThat(catalog.isArray()).isTrue();
        List<String> names = toNames(catalog);
        assertThat(names).contains(
                "approval_prompt", "recall_thread", "list_tools",
                "list_skills", "load_skill");
        // Trunk role excludes the task-only publishers.
        assertThat(names).doesNotContain("push", "post_comment", "request_review");
        // Each entry carries gating + security alongside name + description.
        JsonNode listToolsEntry = catalog.findValue("name");
        assertThat(listToolsEntry).isNotNull();
        boolean foundGatingField = false;
        for (JsonNode entry : catalog) {
            if ("list_tools".equals(entry.path("name").asText())) {
                assertThat(entry.path("gating").asText()).isEqualTo("auto");
                assertThat(entry.path("security").asText()).isEqualTo("tool_discover");
                foundGatingField = true;
                break;
            }
        }
        assertThat(foundGatingField).isTrue();
    }

    @Test
    void listSkillsReturnsTheManifestForTheThread()
            throws Exception
    {
        String threadId = newTrunkThread();

        JsonNode response = await(controller.handle(threadId,
                jsonRpc("tools/call", mapper.createObjectNode()
                        .put("name", "list_skills")
                        .set("arguments", mapper.createObjectNode()))));

        String text = response.path("result").path("content").get(0).path("text").asText();
        JsonNode parsed = mapper.readTree(text);
        // The manifest is always an array — empty when no skills
        // apply, populated when the test DB has rows (e.g. the
        // migrated 'Trino code style' row from V87).
        assertThat(parsed.isArray()).isTrue();
    }

    @Test
    void loadSkillWithUnknownNameSurfacesADenyEnvelope()
            throws Exception
    {
        String threadId = newTrunkThread();

        JsonNode response = await(controller.handle(threadId,
                jsonRpc("tools/call", mapper.createObjectNode()
                        .put("name", "load_skill")
                        .set("arguments", mapper.createObjectNode()
                                .put("name", "no-such-skill-exists-here")))));

        String text = response.path("result").path("content").get(0).path("text").asText();
        JsonNode envelope = mapper.readTree(text);
        assertThat(envelope.path("behavior").asText()).isEqualTo("deny");
        // The dispatcher's own error payload rides inside the deny
        // envelope so the model treats it as a recoverable failure
        // rather than a permission rejection.
        assertThat(envelope.path("message").asText())
                .contains("skill not found")
                .contains("no-such-skill-exists-here");
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
                /* activeTask */ null);
        threads.saveThread(thread);
        return id;
    }
}
