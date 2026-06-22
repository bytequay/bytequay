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

import com.bytequay.app.beans.mcp.ApprovalPromptArgs;
import com.bytequay.app.beans.mcp.Capabilities;
import com.bytequay.app.beans.mcp.InitializeResult;
import com.bytequay.app.beans.mcp.JsonRpcRequest;
import com.bytequay.app.beans.mcp.ListToolsResult;
import com.bytequay.app.beans.mcp.RunShellArgs;
import com.bytequay.app.beans.mcp.ServerInfo;
import com.bytequay.app.beans.mcp.ToolCallParams;
import com.bytequay.app.beans.mcp.ToolDescriptor;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.LogicLoopThreadAgent;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.PermissionResolver;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.ToolCall;
import com.bytequay.app.service.tools.ToolOutcome;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Concrete MCP server: dispatches JSON-RPC requests, applies the
 * shared role / capability gates, and delegates each
 * {@code tools/call} either to the registry's default invoker
 * (read-only tools that return a {@link ToolOutcome}) or to one
 * {@link ToolHandler} per private-flow tool (currently
 * {@link ApprovalPromptHandler} and {@link RunShellHandler}).
 *
 * <p>The dispatcher itself stays small — every tool-specific
 * concern (parked-state, autonomy envelope, pre-approved budget,
 * the shell-runner policy) lives in its handler.
 */
@Service
public class McpServiceImpl
        implements McpService
{
    private static final Logger log = LoggerFactory.getLogger(McpServiceImpl.class);

    /** Bumped if we ever break wire-compat. Matches the version
     *  Claude Code negotiated against in its current MCP client. */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** How long the agent will wait for the user before we give up
     *  and tell Claude the request was denied. Two minutes is enough
     *  to switch tabs, read the call site, and decide; longer would
     *  leak DeferredResults if the browser tab dies. */
    private static final long DECISION_TIMEOUT_MS = 2L * 60L * 1000L;

    private final AgentToolRegistry registry;
    private final PermissionResolver permissions;
    private final McpResponses responses;
    private final ThreadStore threadStore;
    private final Map<String, ToolHandler> handlersByName;

    public McpServiceImpl(
            AgentToolRegistry registry,
            PermissionResolver permissions,
            McpResponses responses,
            ThreadStore threadStore,
            List<ToolHandler> handlers)
    {
        this.registry = requireNonNull(registry, "registry is null");
        this.permissions = requireNonNull(permissions, "permissions is null");
        this.responses = requireNonNull(responses, "responses is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        // Build the strategy map at construction. Spring injects
        // every ToolHandler bean; failing fast on a duplicate name
        // catches an accidental two-bean-registers-the-same-tool
        // wiring bug at startup instead of letting one silently win.
        Map<String, ToolHandler> map = new HashMap<>();
        for (ToolHandler handler : handlers) {
            ToolHandler prior = map.put(handler.toolName(), handler);
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate ToolHandler for '" + handler.toolName()
                                + "': " + prior + " and " + handler);
            }
        }
        this.handlersByName = Map.copyOf(map);
    }

    @Override
    public DeferredResult<JsonNode> handle(String threadId, JsonNode request)
    {
        // Defensive guards for the cross-service reuse path — the
        // controller already returned 400 on a blank threadId or a
        // missing/non-object request body. If another internal service
        // ever calls handle(...) directly with null, fail loudly so the
        // NPE doesn't surface deep inside the dispatch.
        requireNonNull(threadId, "threadId is null");
        requireNonNull(request, "request is null");
        DeferredResult<JsonNode> deferred = new DeferredResult<>(DECISION_TIMEOUT_MS);
        // Diagnostic: confirm the CLI's MCP request actually reaches us
        // and whether it resolves or stalls to the decision timeout.
        deferred.onTimeout(() -> log.warn(
                "MCP request timed out after {}ms: thread={}", DECISION_TIMEOUT_MS, threadId));
        deferred.onError(t -> log.warn(
                "MCP request errored: thread={}: {}", threadId, t.toString()));
        // Hold the raw "id" path for the failure paths below — if
        // binding the envelope itself fails the typed record never
        // exists, so we fall back to a JsonNode read for the response id.
        JsonNode rawId = request.path("id");
        try {
            JsonRpcRequest rpc = responses.mapper().treeToValue(request, JsonRpcRequest.class);
            String method = rpc.method() == null ? "" : rpc.method();
            JsonNode id = rpc.id();
            JsonNode paramsNode = rpc.params();
            String callTool = "tools/call".equals(method) && paramsNode != null
                    ? paramsNode.path("name").asText("")
                    : "";
            log.info("MCP request received: thread={} method={}{}", threadId, method,
                    callTool.isEmpty() ? "" : " tool=" + callTool);
            switch (method) {
                case "initialize" -> deferred.setResult(initialize(id, paramsNode));
                case "tools/list" -> deferred.setResult(listTools(threadId, id));
                case "tools/call" -> handleToolCall(threadId, id, paramsNode, deferred);
                case "notifications/initialized", "notifications/cancelled" ->
                        // Notifications carry no id and need no response — Spring
                        // returns an empty body when the result is null.
                        deferred.setResult(null);
                default -> deferred.setResult(responses.error(id, -32601, "method not found: " + method));
            }
        }
        catch (JsonProcessingException e) {
            log.warn("MCP request invalid for thread {}: {}", threadId, e.getMessage());
            deferred.setResult(responses.error(rawId, -32700, "parse error: " + e.getMessage()));
        }
        catch (RuntimeException e) {
            log.warn("MCP request failed for thread {}: {}", threadId, e.getMessage());
            deferred.setResult(responses.error(rawId, -32603, e.getMessage()));
        }
        return deferred;
    }

    private JsonNode initialize(JsonNode id, JsonNode params)
    {
        // Echo the protocol version the client asked for when it sent one —
        // a strict client (e.g. Codex's MCP client) may decline to use a
        // server that answers with a different version than it requested.
        // Fall back to our baseline when the request omits it.
        String requested = params == null ? null : params.path("protocolVersion").asText(null);
        String version = requested == null || requested.isBlank() ? PROTOCOL_VERSION : requested;
        return responses.ok(id, new InitializeResult(
                version,
                Capabilities.empty(),
                new ServerInfo("bytequay", "1.0.0")));
    }

    /** The connecting thread's kind, or null when it can't be resolved
     *  (an unknown / blank thread id) — {@link ToolSpec#availableToKind}
     *  treats null as "fails any kind-restricted tool". */
    private ThreadKind kindFor(String threadId)
    {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        return threadStore.findThreadById(threadId).map(Thread::kind).orElse(null);
    }

    private JsonNode listTools(String threadId, JsonNode id)
    {
        // Tools are declared via @AgentTool on the stub methods below;
        // the registry scans them at startup, sorts by name, and emits
        // a deterministic spec list. The MCP envelope just wraps each
        // spec into the wire shape, filtered to the caller's role so
        // a trunk agent doesn't even see task-only tools.
        AgentRole role = permissions.roleFor(threadId);
        ThreadKind kind = kindFor(threadId);
        List<ToolDescriptor> tools = new ArrayList<>();
        for (ToolSpec spec : registry.visibleTo(role)) {
            // Beyond role, some tools are gated to a thread kind (e.g.
            // record_plan to the brain) — hide those the caller's kind
            // can't reach.
            if (!spec.availableToKind(kind)) {
                continue;
            }
            // A brain connection is further scoped to the read-only brain
            // allowlist (+ record_plan), matching the in-JVM brain — so a
            // claude-code brain can't reach create_task / publish tools.
            // approval_prompt is exempt: it's not a capability but the
            // CLI's --permission-prompt-tool target, which claude-code
            // validates exists in the advertised list at startup — strip it
            // and the brain subprocess exits before it can plan.
            if (kind == ThreadKind.BRAIN_AGENT
                    && !ApprovalPromptHandler.NAME.equals(spec.name())
                    && !LogicLoopThreadAgent.BRAIN_TOOL_ALLOWLIST.contains(spec.name())) {
                continue;
            }
            JsonNode schema;
            try {
                schema = responses.mapper().readTree(spec.inputSchema());
            }
            catch (JsonProcessingException e) {
                // Generated by the registry from a record schema —
                // a parse failure here is a bug in the generator, not
                // the wire. Fail loudly so the next call surfaces it.
                throw new IllegalStateException(
                        "registry produced invalid JSON schema for tool " + spec.name(), e);
            }
            tools.add(new ToolDescriptor(spec.name(), spec.description(), schema));
        }
        return responses.ok(id, new ListToolsResult(tools));
    }

    // ── @AgentTool stub overrides ──────────────────────────────────────
    // The tool catalog entries (name, description, args record,
    // security, gating, roles) all live on {@link McpService} so the
    // contract is readable in one place. The registry's startup scan
    // walks each impl method's interface declarations via Spring's
    // AnnotatedElementUtils, so the empty overrides below are all that's
    // needed here — calling them directly is meaningless; dispatch
    // always flows through {@link #handleToolCall}.

    @Override
    @SuppressWarnings("unused")
    public void declareApprovalPrompt(ApprovalPromptArgs args) {}

    @Override
    @SuppressWarnings("unused")
    public void declareRunShell(RunShellArgs args) {}

    private void handleToolCall(String threadId, JsonNode id, JsonNode paramsNode, DeferredResult<JsonNode> deferred)
    {
        ToolCallParams params;
        try {
            params = responses.bindArgs(paramsNode, ToolCallParams.class);
        }
        catch (JsonProcessingException e) {
            deferred.setResult(responses.error(id, -32602, "invalid tools/call params: " + e.getMessage()));
            return;
        }
        String name = params.name() == null ? "" : params.name();
        // Look the tool up in the registry first — that's the single
        // source of truth for what exists, what role may discover it,
        // and what capability it exercises. An unknown name fails the
        // call the same way the legacy "unknown tool" branch did; a
        // known name whose security isn't in the caller's grants
        // returns a clean deny envelope so the model ends the turn
        // gracefully rather than retrying.
        ToolSpec spec = registry.byName(name).orElse(null);
        if (spec == null) {
            deferred.setResult(responses.error(id, -32602, "unknown tool: " + name));
            return;
        }
        AgentRole role = permissions.roleFor(threadId);
        if (!spec.availableTo(role)) {
            // The roles array on @AgentTool is both a discovery filter
            // (tools/list hides tools the role can't see) and a call-
            // time guard (so a hand-crafted RPC can't reach a tool that
            // the catalog wouldn't have offered to this role).
            deferred.setResult(responses.toolResponse(id, responses.deny(
                    "tool '" + name + "' is not available to the current role ("
                            + role + ")")));
            return;
        }
        ThreadKind kind = kindFor(threadId);
        if (!spec.availableToKind(kind)
                || (kind == ThreadKind.BRAIN_AGENT
                        && !ApprovalPromptHandler.NAME.equals(name)
                        && !LogicLoopThreadAgent.BRAIN_TOOL_ALLOWLIST.contains(name))) {
            // Kind gate (e.g. record_plan is brain-only) + the brain allowlist
            // scoping — same dual role as the role check: hidden in tools/list
            // and refused at call time.
            deferred.setResult(responses.toolResponse(id, responses.deny(
                    "tool '" + name + "' is not available to the current thread kind ("
                            + kind + ")")));
            return;
        }
        Set<SecurityType> grants = permissions.grants(threadId);
        if (!grants.contains(spec.security())) {
            deferred.setResult(responses.toolResponse(id, responses.deny(
                    "tool '" + name + "' requires capability " + spec.security()
                            + " which is not granted to the current role ("
                            + role + ")")));
            return;
        }
        // Registry default path: tools whose @AgentTool method returns
        // a ToolOutcome bind their args inside the registry and run
        // there. Stubs (void return type) fall through to the strategy
        // map so the per-tool flow takes over.
        Optional<ToolOutcome> outcome = registry.invoke(
                name, new ToolCall(threadId, params.arguments(), role));
        if (outcome.isPresent()) {
            deferred.setResult(adaptOutcome(id, outcome.get()));
            return;
        }
        ToolHandler handler = handlersByName.get(name);
        if (handler == null) {
            // Registry knew the tool but no strategy handler is wired
            // for it — a registry-only stub without a per-name handler
            // bean is a wiring bug, not a user-facing condition.
            deferred.setResult(responses.error(id, -32602, "no handler for tool: " + name));
            return;
        }
        handler.handle(new ToolDispatchContext(threadId, id, params, role, grants, spec), deferred);
    }

    /** Adapt a registry handler's lane-neutral {@link ToolOutcome} to
     *  the MCP wire. A successful Completed echoes its text verbatim;
     *  an error Completed is wrapped as a deny envelope so the model
     *  reads it as a recoverable tool failure (matching the old hand-
     *  coded read handlers). */
    private JsonNode adaptOutcome(JsonNode id, ToolOutcome outcome)
    {
        if (outcome instanceof ToolOutcome.Completed(String text, boolean isError)) {
            return isError ? responses.toolResponse(id, responses.deny(text)) : responses.plainText(id, text);
        }
        throw new IllegalStateException("unhandled tool outcome: " + outcome);
    }
}
