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
package com.bytequay.app.service.mcp;

import com.bytequay.app.beans.mcp.ToolCallParams;
import com.bytequay.app.service.mcp.approval.ApprovalStep;
import com.bytequay.app.service.mcp.approval.ApprovalStepResult;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.ThreadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestApprovalPromptHandler
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpResponses responses = new McpResponses(mapper);
    private final ThreadService threads = mock(ThreadService.class);
    private final McpPermissionGate gate = mock(McpPermissionGate.class);

    @Test
    void theFirstResolvingStepShortCircuitsAndLaterStepsDontRun()
    {
        AtomicBoolean laterRan = new AtomicBoolean(false);
        ApprovalStep resolver = c -> ApprovalStepResult.resolve(
                responses.toolResponse(c.id(), responses.allow(c.toolInput())));
        ApprovalStep later = c -> {
            laterRan.set(true);
            return ApprovalStepResult.cont();
        };
        ApprovalPromptHandler handler = new ApprovalPromptHandler(
                List.of(resolver, later), threads, gate, responses);

        DeferredResult<JsonNode> deferred = new DeferredResult<>();
        handler.handle(ctx("Bash", "call-1"), deferred);

        assertThat(deferred.getResult()).isNotNull();
        assertThat(laterRan).isFalse();
        verify(gate, never()).register(anyString(), anyString());
    }

    @Test
    void whenEveryStepContinuesTheCallIsRegisteredForTheUserPrompt()
    {
        ApprovalStep cont = c -> ApprovalStepResult.cont();
        when(gate.register("call-1", "Bash")).thenReturn(new CompletableFuture<>());
        when(threads.tryConsumeToolBudget("thread-1", "Bash")).thenReturn(OptionalInt.empty());
        ApprovalPromptHandler handler = new ApprovalPromptHandler(
                List.of(cont), threads, gate, responses);

        DeferredResult<JsonNode> deferred = new DeferredResult<>();
        handler.handle(ctx("Bash", "call-1"), deferred);

        // Fell through the chain → blocks on the user's decision via the gate.
        verify(gate).register("call-1", "Bash");
        assertThat(deferred.getResult()).isNull();
    }

    @Test
    void aMissingToolUseIdIsRejectedBeforeTheChainRuns()
    {
        AtomicBoolean stepRan = new AtomicBoolean(false);
        ApprovalStep spy = c -> {
            stepRan.set(true);
            return ApprovalStepResult.cont();
        };
        ApprovalPromptHandler handler = new ApprovalPromptHandler(
                List.of(spy), threads, gate, responses);

        DeferredResult<JsonNode> deferred = new DeferredResult<>();
        handler.handle(ctx("Bash", ""), deferred);

        assertThat(deferred.getResult()).isNotNull();
        assertThat(deferred.getResult().toString()).contains("tool_use_id");
        assertThat(stepRan).isFalse();
        verify(gate, never()).register(anyString(), anyString());
    }

    private ToolDispatchContext ctx(String toolName, String callId)
    {
        ObjectNode args = mapper.createObjectNode();
        args.put("tool_name", toolName);
        args.set("input", mapper.createObjectNode());
        args.put("tool_use_id", callId);
        return new ToolDispatchContext(
                "thread-1", JsonNodeFactory.instance.numberNode(1),
                new ToolCallParams("approval_prompt", args),
                /* role */ null, Set.of(), /* spec */ null);
    }
}
