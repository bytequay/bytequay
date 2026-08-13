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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Objects.requireNonNull;

/**
 * The user-approval gate for CLI agent tool use. A flow turn launches with
 * everything it plausibly needs pre-approved; when the agent reaches for
 * something outside that set, Claude routes the request here instead of
 * prompting a terminal nobody is watching. The request becomes a card on the
 * run's page, the turn waits on the answer, and no decision within the window
 * is a deny — the agent is told to continue another way, never left hanging.
 *
 * <p>In-memory on purpose: a pending approval only makes sense while its turn
 * is alive in this process, and a turn does not survive the process.
 */
public final class NewFlowAgentPermissions
{
    /** The MCP tool name Claude calls for a permission decision. */
    public static final String TOOL_NAME = "request_tool_permission";

    private static final Duration DECISION_WINDOW = Duration.ofMinutes(30);

    public record PendingApproval(
            String approvalId,
            String runId,
            String toolName,
            String inputJson,
            long requestedAtEpochMilli)
    {
        public PendingApproval
        {
            requireNonNull(approvalId, "approvalId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(toolName, "toolName is null");
            requireNonNull(inputJson, "inputJson is null");
        }
    }

    private record Entry(
            PendingApproval request, CompletableFuture<Boolean> answer) {}

    private final ObjectMapper mapper;
    private final ConcurrentMap<String, Entry> pending =
            new ConcurrentHashMap<>();

    public NewFlowAgentPermissions(ObjectMapper mapper)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Blocks the calling turn until the user decides or the window closes,
     * and returns the JSON decision Claude's permission protocol expects.
     */
    public String ask(String runId, JsonNode arguments)
    {
        requireNonNull(runId, "runId is null");
        requireNonNull(arguments, "arguments is null");
        String toolName = arguments.path("tool_name").asText("unknown");
        JsonNode input = arguments.path("input");
        String approvalId = UUID.randomUUID().toString();
        Entry entry = new Entry(
                new PendingApproval(
                        approvalId, runId, toolName, input.toString(),
                        System.currentTimeMillis()),
                new CompletableFuture<>());
        pending.put(approvalId, entry);
        try {
            boolean allowed = entry.answer().get(
                    DECISION_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
            return allowed
                    ? allow(input)
                    : deny("the user declined this tool use; continue with"
                            + " the recommended bytequay tools instead");
        }
        catch (TimeoutException noAnswer) {
            return deny("no user decision arrived in time; continue with"
                    + " the recommended bytequay tools instead");
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return deny("the turn is stopping");
        }
        catch (ExecutionException impossible) {
            return deny("the approval could not be recorded");
        }
        finally {
            pending.remove(approvalId);
        }
    }

    /** Open questions for one run, oldest first, for the run page's card. */
    public List<PendingApproval> pending(String runId)
    {
        requireNonNull(runId, "runId is null");
        return pending.values().stream()
                .map(Entry::request)
                .filter(request -> request.runId().equals(runId))
                .sorted((left, right) -> Long.compare(
                        left.requestedAtEpochMilli(),
                        right.requestedAtEpochMilli()))
                .toList();
    }

    /** Records the user's decision; false when the question already closed. */
    public boolean answer(String approvalId, boolean allow)
    {
        requireNonNull(approvalId, "approvalId is null");
        Entry entry = pending.get(approvalId);
        if (entry == null) {
            return false;
        }
        return entry.answer().complete(allow);
    }

    private String allow(JsonNode input)
    {
        ObjectNode decision = mapper.createObjectNode()
                .put("behavior", "allow");
        decision.set(
                "updatedInput",
                input.isObject() ? input : mapper.createObjectNode());
        return decision.toString();
    }

    private String deny(String message)
    {
        return mapper.createObjectNode()
                .put("behavior", "deny")
                .put("message", message)
                .toString();
    }
}
