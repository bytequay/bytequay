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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.userwait.V2UserWaitService;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.mcp.approval.ApprovalStep;
import com.bytequay.app.service.mcp.approval.AutoApproveStep;
import com.bytequay.app.service.mcp.approval.BudgetStep;
import com.bytequay.app.service.mcp.approval.TypedPolicyGuardStep;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.PermissionResolver;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.ToolCall;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTypedApprovalPrompt
{
    private static final String TRUNK = "trunk-1";
    private static final String TASK = "task-1";
    private static final String TURN = "stage-turn-1";
    private static final String OPERATION = "operation-1";
    private static final String AGENT = "v2-stage-turn:stage-turn-1:operation-1";

    private final ObjectMapper json = new ObjectMapper();
    private final McpResponses responses = new McpResponses(json);
    private final AgentToolRegistry registry = mock(AgentToolRegistry.class);
    private final PermissionResolver permissions = mock(PermissionResolver.class);
    private final ThreadStore threads = mock(ThreadStore.class);
    private final ThreadService legacyPermissions = mock(ThreadService.class);
    private final TaskStore legacyTasks = mock(TaskStore.class);
    private final TaskManager.Store v2Tasks = mock(TaskManager.Store.class);
    private final V2UserWaitService waits = mock(V2UserWaitService.class);
    private final ActiveAgentContextRegistry activeContexts =
            new ActiveAgentContextRegistry();
    private McpServiceImpl service;

    @BeforeEach
    void setUp()
    {
        ToolSpec approvalSpec = mock(ToolSpec.class);
        when(approvalSpec.security()).thenReturn(SecurityType.MCP);
        when(approvalSpec.availableTo(AgentRole.TASK)).thenReturn(true);
        when(approvalSpec.availableToKind(ThreadKind.CLI_AGENT)).thenReturn(true);
        when(registry.byName("approval_prompt"))
                .thenReturn(Optional.of(approvalSpec));
        when(registry.invoke(eq("approval_prompt"), any(ToolCall.class)))
                .thenReturn(Optional.empty());
        when(permissions.roleFor(TRUNK, AGENT)).thenReturn(AgentRole.TASK);
        when(permissions.grants(TRUNK, AGENT)).thenReturn(Set.of(
                SecurityType.MCP, SecurityType.CODE_EXEC,
                SecurityType.CODE_WRITE));
        when(permissions.runningScope(TRUNK, AGENT)).thenReturn(
                new PermissionResolver.RunningScope(
                        ThreadScope.STAGE, TASK, "stage-1", TURN));
        Thread trunk = mock(Thread.class);
        when(trunk.kind()).thenReturn(ThreadKind.CLI_AGENT);
        when(threads.findThreadById(TRUNK)).thenReturn(Optional.of(trunk));
        activeContexts.put(
                TRUNK,
                AGENT,
                new ResolvedAgentContext(
                        ByteQuayRole.TASK, "1", AgentRole.TASK,
                        StageType.DEVELOPMENT_STAGE,
                        Set.of(SecurityType.MCP, SecurityType.CODE_EXEC,
                                SecurityType.CODE_WRITE),
                        List.of(), Set.of(), Set.of("approval_prompt")),
                new PermissionResolver.RunningScope(
                        ThreadScope.STAGE, TASK, "stage-1", TURN),
                new ActiveAgentContextRegistry.TypedOwner(
                        DispatchTicket.OwnerKind.STAGE_TURN, TURN, OPERATION));

        List<ApprovalStep> policy = List.of(
                new TypedPolicyGuardStep(v2Tasks, responses),
                new BudgetStep(legacyPermissions, responses),
                new AutoApproveStep(legacyTasks, v2Tasks, responses));
        ApprovalPromptHandler approval = new ApprovalPromptHandler(
                policy, legacyPermissions, mock(McpPermissionGate.class),
                responses);
        service = new McpServiceImpl(
                registry, permissions, responses, threads,
                List.of(approval), activeContexts, waits);
    }

    @Test
    void typedAutoApproveUsesV2PolicyWithoutTouchingLegacyPermissionState()
            throws Exception
    {
        when(v2Tasks.findPolicy(TASK))
                .thenReturn(Optional.of(policy(true)));
        when(v2Tasks.effectiveAutoApprove(TASK)).thenReturn(true);
        when(legacyTasks.isAutoApprove(TASK)).thenReturn(false);
        when(legacyPermissions.tryConsumeToolBudget(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException(
                        "legacy permission path called"));

        JsonNode response = result(service.handle(
                TRUNK, AGENT, approvalRequest("touch local-stage-output")));

        assertThat(response.has("error")).isFalse();
        JsonNode envelope = envelope(response);
        assertThat(envelope.path("behavior").asText()).isEqualTo("allow");
        assertThat(envelope.path("updatedInput").path("command").asText())
                .isEqualTo("touch local-stage-output");
        verify(legacyPermissions, never()).tryConsumeToolBudget(
                anyString(), anyString(), anyString());
        verify(legacyTasks, never()).isAutoApprove(TASK);
        verify(waits, never()).requestPermission(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString());
    }

    @Test
    void typedNonAutoApproveCreatesAnExactV2WaitInsteadOfCallingLegacy()
            throws Exception
    {
        when(v2Tasks.findPolicy(TASK))
                .thenReturn(Optional.of(policy(false)));
        when(v2Tasks.effectiveAutoApprove(TASK)).thenReturn(false);
        when(legacyTasks.isAutoApprove(TASK)).thenReturn(true);
        when(waits.requestPermission(
                eq(TRUNK), eq(AGENT), eq("bash-call-1"),
                eq(SecurityType.CODE_EXEC.name()), eq("Bash"),
                any(), anyString()))
                .thenReturn(V2UserWaitService.PermissionPrompt.waiting("wait-1"));

        JsonNode response = result(service.handle(
                TRUNK, AGENT, approvalRequest("touch local-stage-output")));

        assertThat(response.has("error")).isFalse();
        assertThat(envelope(response).path("behavior").asText())
                .isEqualTo("deny");
        verify(waits).requestPermission(
                eq(TRUNK), eq(AGENT), eq("bash-call-1"),
                eq(SecurityType.CODE_EXEC.name()), eq("Bash"),
                any(), anyString());
        verify(legacyPermissions, never()).tryConsumeToolBudget(
                anyString(), anyString(), anyString());
        verify(legacyTasks, never()).isAutoApprove(TASK);
    }

    @Test
    void missingTypedPolicyFailsClosedBeforeAnyAllowOrUserWait()
            throws Exception
    {
        when(v2Tasks.findPolicy(TASK)).thenReturn(Optional.empty());

        JsonNode response = result(service.handle(
                TRUNK, AGENT, approvalRequest("touch local-stage-output")));

        assertThat(response.has("error")).isFalse();
        JsonNode envelope = envelope(response);
        assertThat(envelope.path("behavior").asText()).isEqualTo("deny");
        assertThat(envelope.path("message").asText())
                .contains("V2 Task policy is unavailable");
        verify(waits, never()).requestPermission(
                anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString());
        verify(legacyPermissions, never()).tryConsumeToolBudget(
                anyString(), anyString(), anyString());
        verify(legacyTasks, never()).isAutoApprove(TASK);
    }

    private JsonNode approvalRequest(String command)
    {
        var arguments = json.createObjectNode();
        arguments.put("tool_name", "Bash");
        arguments.put("tool_use_id", "bash-call-1");
        arguments.set("input", json.createObjectNode().put("command", command));
        var params = json.createObjectNode();
        params.put("name", "approval_prompt");
        params.set("arguments", arguments);
        var request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);
        return request;
    }

    private JsonNode envelope(JsonNode response)
            throws Exception
    {
        return json.readTree(response.path("result").path("content")
                .get(0).path("text").asText());
    }

    private static JsonNode result(DeferredResult<JsonNode> deferred)
    {
        assertThat(deferred.hasResult()).isTrue();
        return (JsonNode) deferred.getResult();
    }

    private static TaskManager.PolicyRevision policy(boolean autoApprove)
    {
        return new TaskManager.PolicyRevision(
                "policy-1", TASK, TRUNK, 1,
                autoApprove, false, 0, 3, 3, true, "permission-policy-1");
    }
}
