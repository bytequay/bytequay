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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.service.agents.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The endpoint's value is in what it refuses, so that is most of what is here:
 * a run that is not open, a tool outside the manifest, and a second
 * registration for a run that already has one.
 */
final class TestNewFlowAgentToolBridge
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN = "run-1";

    private final NewFlowAgentToolBridge bridge =
            new NewFlowAgentToolBridge(MAPPER);
    private final List<String> executed = new ArrayList<>();

    @Test
    void aClosedRunIsNotThisEndpointAnyMore()
    {
        try (NewFlowAgentToolBridge.Registration registration = open()) {
            assertThat(bridge.handle(RUN, request("tools/list", null)))
                    .isPresent();
            assertThat(registration.path())
                    .isEqualTo("/api/new-flow/runs/run-1/mcp");
        }

        // Empty, not an RPC error. A subprocess that outlived its turn must find
        // nothing here rather than a live worktree; the process group makes that
        // rare, and this makes it harmless.
        assertThat(bridge.handle(RUN, request("tools/list", null))).isEmpty();
        assertThat(bridge.handle("never-opened", request("tools/list", null)))
                .isEmpty();
    }

    @Test
    void aToolOutsideTheManifestNeverReachesTheExecutor()
    {
        try (NewFlowAgentToolBridge.Registration ignored = open()) {
            ObjectNode params = MAPPER.createObjectNode().put("name", "run_checks");

            JsonNode response = bridge.handle(RUN, request("tools/call", params))
                    .orElseThrow();

            assertThat(response.path("error").path("message").asText())
                    .isEqualTo("tool is not available");
            // The manifest is the program's decision about this role. It must
            // hold without the executor having to agree.
            assertThat(executed).isEmpty();
        }
    }

    @Test
    void anAllowedToolReachesTheExecutorAndItsFailureIsAToolFailure()
    {
        try (NewFlowAgentToolBridge.Registration ignored = open()) {
            ObjectNode arguments = MAPPER.createObjectNode().put("path", "pom.xml");
            ObjectNode params = MAPPER.createObjectNode().put("name", "read_file");
            params.set("arguments", arguments);

            JsonNode ok = bridge.handle(RUN, request("tools/call", params))
                    .orElseThrow();

            assertThat(executed).containsExactly("read_file");
            assertThat(ok.path("result").path("isError").asBoolean()).isFalse();
            assertThat(ok.path("result").path("content").get(0).path("text")
                    .asText()).isEqualTo("read pom.xml");

            ObjectNode failing = MAPPER.createObjectNode().put("name", "write_file");
            JsonNode failed = bridge.handle(RUN, request("tools/call", failing))
                    .orElseThrow();

            // Reported as a failed tool, not a failed request: the agent is
            // meant to read it and try something else.
            assertThat(failed.path("result").path("isError").asBoolean()).isTrue();
            assertThat(failed.path("error").isMissingNode()).isTrue();
        }
    }

    @Test
    void aSecondRegistrationForOneRunIsRefused()
    {
        try (NewFlowAgentToolBridge.Registration ignored = open()) {
            // A redelivered launch must not serve tools against a stale
            // executor while the first one is still running.
            assertThatThrownBy(this::open)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already open for run run-1");
        }
        // Reusable once closed, so an ordinary retry is not poisoned.
        open().close();
    }

    @Test
    void listsExactlyTheManifestAndRefusesUnknownMethods()
    {
        try (NewFlowAgentToolBridge.Registration ignored = open()) {
            JsonNode listed = bridge.handle(RUN, request("tools/list", null))
                    .orElseThrow();
            List<String> names = new ArrayList<>();
            listed.path("result").path("tools")
                    .forEach(tool -> names.add(tool.path("name").asText()));
            assertThat(names).containsExactly("read_file", "write_file");

            assertThat(bridge.handle(RUN, request("initialize", null))
                    .orElseThrow().path("result").path("protocolVersion").asText())
                    .isNotBlank();
            assertThat(bridge.handle(RUN, request("resources/list", null))
                    .orElseThrow().path("error").path("code").asInt())
                    .isEqualTo(-32601);
        }
    }

    @Test
    void aLoopbackCallExecutesOnTheTurnOwnerThread()
            throws Exception
    {
        Thread owner = Thread.currentThread();
        AtomicReference<Thread> executionThread = new AtomicReference<>();
        try (NewFlowAgentToolBridge.Registration registration = bridge.open(
                RUN, manifest(), call -> {
                    executionThread.set(Thread.currentThread());
                    return ToolExecutor.ToolCallResult.ok("owned");
                })) {
            ObjectNode params = MAPPER.createObjectNode()
                    .put("name", "read_file");
            CompletableFuture<Optional<JsonNode>> response =
                    CompletableFuture.supplyAsync(() -> bridge.handle(
                            RUN, request("tools/call", params)));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!response.isDone() && System.nanoTime() < deadline) {
                registration.executePendingCalls();
                Thread.onSpinWait();
            }

            assertThat(response.get(1, TimeUnit.SECONDS)).isPresent();
            assertThat(executionThread).hasValue(owner);
        }
    }

    private NewFlowAgentToolBridge.Registration open()
    {
        return bridge.open(RUN, manifest(), executor());
    }

    private ArrayNode manifest()
    {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.addObject().put("name", "read_file")
                .putObject("inputSchema").put("type", "object");
        tools.addObject().put("name", "write_file")
                .putObject("inputSchema").put("type", "object");
        return tools;
    }

    private ToolExecutor executor()
    {
        return call -> {
            executed.add(call.name());
            return Optional.of(call)
                    .filter(seen -> "read_file".equals(seen.name()))
                    .map(seen -> ToolExecutor.ToolCallResult.ok(
                            "read " + seen.input().path("path").asText()))
                    .orElseGet(() -> ToolExecutor.ToolCallResult.error(
                            "refused"));
        };
    }

    private static JsonNode request(String method, JsonNode params)
    {
        ObjectNode request = MAPPER.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", method);
        request.put("id", 7);
        if (params != null) {
            request.set("params", params);
        }
        return request;
    }
}
