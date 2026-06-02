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

import com.bytequay.app.beans.mcp.AllowEnvelope;
import com.bytequay.app.beans.mcp.ApprovalPromptArgs;
import com.bytequay.app.beans.mcp.Capabilities;
import com.bytequay.app.beans.mcp.DenyEnvelope;
import com.bytequay.app.beans.mcp.InitializeResult;
import com.bytequay.app.beans.mcp.JsonRpcError;
import com.bytequay.app.beans.mcp.JsonRpcRequest;
import com.bytequay.app.beans.mcp.JsonRpcSuccess;
import com.bytequay.app.beans.mcp.ListToolsResult;
import com.bytequay.app.beans.mcp.RunShellArgs;
import com.bytequay.app.beans.mcp.RunShellResult;
import com.bytequay.app.beans.mcp.ServerInfo;
import com.bytequay.app.beans.mcp.ToolCallParams;
import com.bytequay.app.beans.mcp.ToolCallResult;
import com.bytequay.app.beans.mcp.ToolDescriptor;
import com.bytequay.app.beans.mcp.UnattendedGatePayload;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.tools.PermissionResolver;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.ToolCall;
import com.bytequay.app.service.tools.ToolOutcome;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

/**
 * Concrete MCP server: dispatches JSON-RPC requests, manages the
 * permission-prompt gate, escalates unattended out-of-bounds tool
 * calls, and runs the {@code run_shell} escape hatch. The controller
 * is a one-method delegator; everything that needs other backend
 * services lives here.
 */
@Service
public class McpServiceImpl
        implements McpService
{
    private static final Logger log = LoggerFactory.getLogger(McpServiceImpl.class);

    /** Bumped if we ever break wire-compat. Matches the version
     *  Claude Code negotiated against in its current MCP client. */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** {@code mcp__bytequay__approval_prompt} from Claude's perspective
     *  — the leading {@code mcp__bytequay__} is added by Claude based
     *  on the server name in {@code --mcp-config}. */
    private static final String TOOL_NAME = "approval_prompt";

    /** Escape-hatch tool — runs a bounded shell command in the
     *  active task's worktreePath, gated on each call via the user-
     *  approval prompt. See {@link ShellRunner} for the policy. */
    private static final String RUN_SHELL_TOOL = "run_shell";

    /** Claude Code prefixes MCP tool names with {@code mcp__<server>__}
     *  when passing them to the permission-prompt tool. Stripping it
     *  lets us look the target tool up in the registry by its short
     *  name. Built-in Claude tools (Bash / Edit / Read) come through
     *  unprefixed and miss the registry lookup, which is the safe
     *  fall-through to the normal prompt path. */
    private static final String MCP_TOOL_PREFIX = "mcp__bytequay__";

    /** How long the agent will wait for the user before we give up
     *  and tell Claude the request was denied. Two minutes is enough
     *  to switch tabs, read the call site, and decide; longer would
     *  leak DeferredResults if the browser tab dies. */
    private static final long DECISION_TIMEOUT_MS = 2L * 60L * 1000L;

    private final ThreadService threads;
    private final TaskStore taskStore;
    private final McpPermissionGate gate;
    private final ObjectMapper mapper;
    private final AgentToolRegistry registry;
    private final PermissionResolver permissions;
    private final ShellRunner shellRunner;
    private final ThreadTurnStore turnStore;
    private final NotificationService notifications;

    public McpServiceImpl(
            ThreadService threads,
            TaskStore taskStore,
            McpPermissionGate gate,
            ObjectMapper mapper,
            AgentToolRegistry registry,
            PermissionResolver permissions,
            ShellRunner shellRunner,
            ThreadTurnStore turnStore,
            NotificationService notifications)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.permissions = requireNonNull(permissions, "permissions is null");
        this.shellRunner = requireNonNull(shellRunner, "shellRunner is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
    }

    @Override
    public DeferredResult<JsonNode> handle(String threadId, JsonNode request)
    {
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
            JsonRpcRequest rpc = mapper.treeToValue(request, JsonRpcRequest.class);
            String method = rpc.method() == null ? "" : rpc.method();
            JsonNode id = rpc.id();
            JsonNode paramsNode = rpc.params();
            String callTool = "tools/call".equals(method) && paramsNode != null
                    ? paramsNode.path("name").asText("")
                    : "";
            log.info("MCP request received: thread={} method={}{}", threadId, method,
                    callTool.isEmpty() ? "" : " tool=" + callTool);
            switch (method) {
                case "initialize" -> deferred.setResult(initialize(id));
                case "tools/list" -> deferred.setResult(listTools(threadId, id));
                case "tools/call" -> handleToolCall(threadId, id, paramsNode, deferred);
                case "notifications/initialized", "notifications/cancelled" ->
                        // Notifications carry no id and need no response — Spring
                        // returns an empty body when the result is null.
                        deferred.setResult(null);
                default -> deferred.setResult(error(id, -32601, "method not found: " + method));
            }
        }
        catch (JsonProcessingException e) {
            log.warn("MCP request invalid for thread {}: {}", threadId, e.getMessage());
            deferred.setResult(error(rawId, -32700, "parse error: " + e.getMessage()));
        }
        catch (RuntimeException e) {
            log.warn("MCP request failed for thread {}: {}", threadId, e.getMessage());
            deferred.setResult(error(rawId, -32603, e.getMessage()));
        }
        return deferred;
    }

    private JsonNode initialize(JsonNode id)
    {
        return ok(id, new InitializeResult(
                PROTOCOL_VERSION,
                Capabilities.empty(),
                new ServerInfo("bytequay", "1.0.0")));
    }

    private JsonNode listTools(String threadId, JsonNode id)
    {
        // Tools are declared via @AgentTool on the stub methods below;
        // the registry scans them at startup, sorts by name, and emits
        // a deterministic spec list. The MCP envelope just wraps each
        // spec into the wire shape, filtered to the caller's role so
        // a trunk agent doesn't even see task-only tools.
        AgentRole role = permissions.roleFor(threadId);
        List<ToolDescriptor> tools = new ArrayList<>();
        for (ToolSpec spec : registry.visibleTo(role)) {
            JsonNode schema;
            try {
                schema = mapper.readTree(spec.inputSchema());
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
        return ok(id, new ListToolsResult(tools));
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
            params = mapper.treeToValue(
                    paramsNode == null || paramsNode.isMissingNode() ? mapper.createObjectNode() : paramsNode,
                    ToolCallParams.class);
        }
        catch (JsonProcessingException e) {
            deferred.setResult(error(id, -32602, "invalid tools/call params: " + e.getMessage()));
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
            deferred.setResult(error(id, -32602, "unknown tool: " + name));
            return;
        }
        AgentRole role = permissions.roleFor(threadId);
        if (!spec.availableTo(role)) {
            // The roles array on @AgentTool is both a discovery filter
            // (tools/list hides tools the role can't see) and a call-
            // time guard (so a hand-crafted RPC can't reach a tool that
            // the catalog wouldn't have offered to this role).
            deferred.setResult(toolResponse(id, deny(
                    "tool '" + name + "' is not available to the current role ("
                            + role + ")")));
            return;
        }
        Set<SecurityType> grants = permissions.grants(threadId);
        if (!grants.contains(spec.security())) {
            deferred.setResult(toolResponse(id, deny(
                    "tool '" + name + "' requires capability " + spec.security()
                            + " which is not granted to the current role ("
                            + role + ")")));
            return;
        }
        // Tools migrated onto the registry-dispatch path bind their
        // args and run through the shared handler, returning a lane-
        // neutral outcome we adapt to the MCP wire. Tools still on the
        // hand-coded branches below return an empty Optional and fall
        // through. Permission / role gating already happened above — the
        // registry trusts the call is authorised.
        Optional<ToolOutcome> outcome = registry.invoke(
                name, new ToolCall(threadId, params.arguments(), role));
        if (outcome.isPresent()) {
            deferred.setResult(adaptOutcome(id, outcome.get()));
            return;
        }
        if (RUN_SHELL_TOOL.equals(name)) {
            handleRunShell(threadId, id, params.arguments(), deferred);
            return;
        }
        if (!TOOL_NAME.equals(name)) {
            // Registry knew the tool but this controller doesn't have a
            // hand-coded handler for it yet. Today the only registered
            // tool without a per-name branch above is approval_prompt
            // (TOOL_NAME) — anything else is a registry-only stub.
            deferred.setResult(error(id, -32602, "no handler for tool: " + name));
            return;
        }
        ApprovalPromptArgs args;
        try {
            JsonNode rawArgs = params.arguments();
            args = mapper.treeToValue(
                    rawArgs == null || rawArgs.isMissingNode() ? mapper.createObjectNode() : rawArgs,
                    ApprovalPromptArgs.class);
        }
        catch (JsonProcessingException e) {
            deferred.setResult(error(id, -32602, "invalid approval_prompt args: " + e.getMessage()));
            return;
        }
        String toolName = args.toolName() == null ? "" : args.toolName();
        String callId = args.toolUseId() == null ? "" : args.toolUseId();
        JsonNode toolInput = args.input();
        if (callId.isEmpty()) {
            deferred.setResult(error(id, -32602, "tool_use_id is required"));
            return;
        }

        // Structural park-guard. Once a task on this thread is at
        // AWAITING_REVIEW or NEEDS_ATTENTION the agent has finished
        // its turn from the user's perspective — further built-in
        // tool calls (Edit, Write, Bash, …) must not silently fire a
        // permission prompt as if work were still in progress, and a
        // pre-approved budget must not let one slip through either.
        // The MCP-native tools dispatched above (request_review /
        // push / post_comment / recall_thread) have their own
        // handling and aren't reached here.
        if (isThreadParked(threadId)) {
            deferred.setResult(toolResponse(id, deny(
                    "This thread is parked at the publish gate. The user must "
                            + "approve or discard the proposed change before further "
                            + "tool calls are accepted. STOP NOW: end the turn "
                            + "immediately, do not attempt further tools, do not "
                            + "apologize.")));
            return;
        }

        // AskUserQuestion is Claude asking the user something. The CLI
        // runs in non-interactive mode, so the built-in tool returns
        // an empty answer immediately. We render the question as a
        // rich card in our conversation view (the frontend special-
        // cases this tool name on the tool_call message), then deny
        // here so Claude ends the turn and waits — the user's reply
        // arrives as the next chat message. The deny message is
        // deliberately blunt: without it Claude tends to apologize
        // about the failure and re-ask the same question in plain
        // prose, duplicating the card.
        if ("AskUserQuestion".equals(toolName)) {
            deferred.setResult(toolResponse(id, deny(
                    "SUCCESS — your question has been rendered to the user as "
                            + "a rich card showing every option. STOP NOW: do not "
                            + "write any further assistant text in this turn, do not "
                            + "re-ask the question in prose, do not explain or "
                            + "apologize, do not summarize the options. End the turn "
                            + "immediately. The user will type their reply into the "
                            + "chat input and you will see it as the next user "
                            + "message.")));
            return;
        }

        // Interim "gating dispatcher": honor the target tool's declared
        // Gating.AUTO so safe read-only tools (list_skills, list_tools,
        // load_skill, read_*, recall_thread, …) never spin on a prompt
        // the user has no reason to answer.
        ToolSpec gatingTarget = registry.byName(stripMcpServerPrefix(toolName)).orElse(null);
        if (gatingTarget != null && gatingTarget.gating() == Gating.AUTO) {
            deferred.setResult(toolResponse(id, allow(toolInput)));
            return;
        }

        // If the user has pre-approved this tool ("Allow next 5",
        // "Always for this tool"), drain one slot and resolve without
        // ever showing a prompt. We surface a permission_auto_allowed
        // notice next to the tool call so the user can see which slot
        // was burned and how many are left.
        OptionalInt remaining = threads.tryConsumeToolBudget(threadId, toolName);
        if (remaining.isPresent()) {
            notePermissionAutoAllowed(threadId, callId, toolName, remaining.getAsInt());
            deferred.setResult(toolResponse(id, allow(toolInput)));
            return;
        }

        // Autonomy envelope. An unattended turn (e.g. the CI auto-fix
        // coordinator) has no human to answer a prompt. Past the
        // standing budget checked above, decide by capability: if the
        // built-in tool maps to a capability the thread's grants allow,
        // it is in-bounds and runs under that standing policy without a
        // prompt; otherwise it is out-of-bounds, so we escalate to a
        // needs-attention notification and deny rather than register a
        // prompt that would only time out.
        if (isUnattended(threadId)) {
            SecurityType capability = capabilityForBuiltinTool(toolName);
            if (capability != null && grants.contains(capability)) {
                notePermissionAutoAllowed(threadId, callId, toolName, -1);
                deferred.setResult(toolResponse(id, allow(toolInput)));
                return;
            }
            escalateUnattendedGate(threadId, toolName, toolInput);
            deferred.setResult(toolResponse(id, deny(
                    "This turn is running unattended and '" + toolName + "' is outside its "
                            + "autonomy envelope. The request has been escalated to the user. "
                            + "STOP NOW: end the turn immediately, do not retry, do not apologize.")));
            return;
        }

        // Pass the tool name so a later `Allow next N` grant on the
        // same tool can drain still-pending callIds in one click
        // instead of leaving the user with a backlog of prompts.
        CompletableFuture<PermissionDecision> decisionFuture = gate.register(callId, toolName);
        CompletableFuture<PermissionDecision> responseFuture = decisionFuture.whenComplete((decision, ex) -> {
            if (ex != null) {
                deferred.setResult(toolResponse(id, deny("interrupted: " + ex.getMessage())));
            }
            else if (decision == PermissionDecision.ALLOW) {
                deferred.setResult(toolResponse(id, allow(toolInput)));
            }
            else {
                deferred.setResult(toolResponse(id, deny("user denied")));
            }
        });

        // Close the race where another prompt grants a budget after
        // our first budget check but before this call is visible in
        // the gate. Register first, then re-check before showing the
        // prompt; a hit completes through the same response future.
        remaining = threads.tryConsumeToolBudget(threadId, toolName);
        if (remaining.isPresent()) {
            notePermissionAutoAllowed(threadId, callId, toolName, remaining.getAsInt());
            gate.decide(callId, PermissionDecision.ALLOW);
            return;
        }
        if (decisionFuture.isDone()) {
            return;
        }

        // Surface the prompt in the conversation pane after the call
        // is registered so a concurrent `Allow next N` can drain it.
        try {
            threads.notifyPermissionRequested(threadId, callId, toolName, summarize(toolName, toolInput));
        }
        catch (RuntimeException e) {
            log.warn("Failed to surface permission prompt for thread {}: {}", threadId, e.getMessage());
        }
        deferred.onTimeout(() -> {
            gate.cancel(callId);
            deferred.setResult(toolResponse(id, deny("timed out waiting for the user")));
        });
        deferred.onCompletion(() -> {
            responseFuture.cancel(false);
            gate.cancel(callId);
        });
    }

    /** Best-effort: record the auto-approval notice without letting a
     *  notification failure tank the tool call. */
    private void notePermissionAutoAllowed(String threadId, String callId, String toolName, int remaining)
    {
        try {
            threads.notifyPermissionAutoAllowed(threadId, callId, toolName, remaining);
        }
        catch (RuntimeException e) {
            log.warn("Failed to record auto-approval notice for thread {}: {}", threadId, e.getMessage());
        }
    }

    private static String summarize(String toolName, JsonNode input)
    {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return toolName;
        }
        String s = input.toString();
        return s.length() > 240 ? s.substring(0, 237) + "…" : s;
    }

    /** True when the thread's in-flight turn was started by an
     *  automated trigger rather than a person. Absent a running turn we
     *  treat the call as attended — the safe default keeps the existing
     *  prompt flow rather than auto-allowing or escalating on a guess. */
    private boolean isUnattended(String threadId)
    {
        return runningTurn(threadId)
                .map(ThreadTurn::initiator)
                .map(initiator -> !initiator.attended())
                .orElse(false);
    }

    private Optional<ThreadTurn> runningTurn(String threadId)
    {
        return turnStore.listTurnsByTaskIdAndStatus(threadId, ThreadTurnStatus.RUNNING, 1)
                .stream()
                .findFirst();
    }

    /** Drop the {@code mcp__bytequay__} prefix Claude Code prepends to
     *  MCP tool names so the registry lookup matches the short name a
     *  tool is registered under. Pass-through for built-in tool names
     *  (Bash / Edit / Read), which don't carry the prefix and aren't in
     *  the registry — those miss the lookup and fall through cleanly. */
    private static String stripMcpServerPrefix(String toolName)
    {
        return toolName != null && toolName.startsWith(MCP_TOOL_PREFIX)
                ? toolName.substring(MCP_TOOL_PREFIX.length())
                : toolName;
    }

    /** Map a Claude built-in tool to the capability it exercises, so an
     *  unattended turn's grants can decide whether it is in-bounds.
     *  Returns {@code null} for tools with no capability mapping (web
     *  access, sub-agents, …) — those are out-of-bounds for an
     *  unattended turn and escalate. */
    private static SecurityType capabilityForBuiltinTool(String toolName)
    {
        return switch (toolName) {
            case "Edit", "Write", "MultiEdit", "NotebookEdit" -> SecurityType.CODE_WRITE;
            case "Read", "Glob", "Grep", "LS" -> SecurityType.CODE_READ;
            case "Bash", "BashOutput", "KillShell" -> SecurityType.CODE_EXEC;
            default -> null;
        };
    }

    /** Escalate an out-of-bounds tool request on an unattended turn to a
     *  needs-attention notification so the human can take over. Best
     *  effort — a failure to persist the notice must not turn into a
     *  protocol error on the agent's deny response. */
    private void escalateUnattendedGate(String threadId, String toolName, JsonNode toolInput)
    {
        try {
            String taskId = runningTurn(threadId).map(ThreadTurn::taskId).orElse(null);
            UnattendedGatePayload payload = new UnattendedGatePayload(toolName, summarize(toolName, toolInput));
            notifications.notifyNeedsAttention(threadId, taskId, mapper.writeValueAsString(payload));
        }
        catch (RuntimeException | JsonProcessingException e) {
            log.warn("Failed to escalate unattended gate for thread {}: {}", threadId, e.getMessage());
        }
    }

    /**
     * Handles {@code run_shell}: the escape hatch. Routes the call
     * through the same permission gate the CLI's built-in tools use
     * for approval_prompt — a per-call user click. On Allow the
     * runner spawns the process in the active task's worktreePath
     * under the policy enumerated in {@link ShellRunner}; on Deny
     * the agent gets a deny envelope.
     */
    private void handleRunShell(
            String threadId, JsonNode id, JsonNode argsNode, DeferredResult<JsonNode> deferred)
    {
        RunShellArgs args;
        try {
            args = mapper.treeToValue(
                    argsNode == null || argsNode.isMissingNode() ? mapper.createObjectNode() : argsNode,
                    RunShellArgs.class);
        }
        catch (JsonProcessingException e) {
            deferred.setResult(error(id, -32602, "invalid run_shell args: " + e.getMessage()));
            return;
        }
        String command = (args.command() == null ? "" : args.command()).trim();
        if (command.isEmpty()) {
            deferred.setResult(toolResponse(id, deny("command is required")));
            return;
        }
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty() || active.get().worktreePath() == null
                || active.get().worktreePath().isBlank()) {
            deferred.setResult(toolResponse(id,
                    deny("run_shell requires an active task with a worktree")));
            return;
        }
        Path worktree = Path.of(active.get().worktreePath());
        String callId = UUID.randomUUID().toString();

        // Surface a permission card in the conversation pane so the
        // user sees the exact cmdline before deciding. Same shape the
        // approval_prompt path uses for built-in Bash / Edit prompts.
        CompletableFuture<PermissionDecision> decisionFuture = gate.register(callId, RUN_SHELL_TOOL);
        decisionFuture.whenComplete((decision, ex) -> {
            if (ex != null) {
                deferred.setResult(toolResponse(id, deny("interrupted: " + ex.getMessage())));
                return;
            }
            if (decision != PermissionDecision.ALLOW) {
                deferred.setResult(toolResponse(id, deny("user denied")));
                return;
            }
            try {
                ShellRunner.Result result = shellRunner.run(worktree, command);
                RunShellResult out = new RunShellResult(
                        result.ran(),
                        result.exitCode(),
                        result.truncated(),
                        result.output(),
                        result.error());
                deferred.setResult(plainText(id, mapper.writeValueAsString(out)));
            }
            catch (JsonProcessingException je) {
                throw new IllegalStateException("failed to serialise run_shell result", je);
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                deferred.setResult(toolResponse(id, deny("interrupted: " + ie.getMessage())));
            }
            catch (RuntimeException e) {
                deferred.setResult(toolResponse(id, deny("run_shell failed: " + e.getMessage())));
            }
        });
        try {
            threads.notifyPermissionRequested(threadId, callId, RUN_SHELL_TOOL,
                    "cmd: " + (command.length() > 200 ? command.substring(0, 197) + "…" : command));
        }
        catch (RuntimeException e) {
            log.warn("Failed to surface run_shell prompt for thread {}: {}", threadId, e.getMessage());
        }
        deferred.onTimeout(() -> {
            gate.cancel(callId);
            deferred.setResult(toolResponse(id, deny("timed out waiting for the user")));
        });
        deferred.onCompletion(() -> gate.cancel(callId));
    }

    /** True when this thread has an unresolved blocking parked state.
     *  A successfully approved {@code next_task} deliberately leaves
     *  its prior sibling in {@code AWAITING_REVIEW} while work
     *  continues in the newly active task, so a historical parked row
     *  must not block prompts from that successor. In contrast,
     *  NEEDS_ATTENTION remains blocking until the user resolves it. */
    private boolean isThreadParked(String threadId)
    {
        List<Task> tasks = taskStore.listTasksByThread(threadId);
        if (tasks.stream().anyMatch(t -> t.status() == TaskStatus.NEEDS_ATTENTION)) {
            return true;
        }
        return taskStore.findActiveTaskForThread(threadId).isEmpty()
                && tasks.stream().anyMatch(t -> t.status() == TaskStatus.AWAITING_REVIEW);
    }

    /** Adapt a registry handler's lane-neutral {@link ToolOutcome} to
     *  the MCP wire. A successful Completed echoes its text verbatim;
     *  an error Completed is wrapped as a deny envelope so the model
     *  reads it as a recoverable tool failure (matching the old hand-
     *  coded read handlers). */
    private JsonNode adaptOutcome(JsonNode id, ToolOutcome outcome)
    {
        if (outcome instanceof ToolOutcome.Completed(String text, boolean isError)) {
            return isError ? toolResponse(id, deny(text)) : plainText(id, text);
        }
        throw new IllegalStateException("unhandled tool outcome: " + outcome);
    }

    // ── Response builders ─────────────────────────────────────────────
    // Each builder returns a JsonNode (the wire form) but does so by
    // assembling typed records that serialise once at the boundary.

    /** Plain-text MCP tool response — no allow/deny envelope. */
    private JsonNode plainText(JsonNode id, String text)
    {
        return ok(id, ToolCallResult.text(text));
    }

    private AllowEnvelope allow(JsonNode updatedInput)
    {
        return AllowEnvelope.of(mapper, updatedInput);
    }

    private static DenyEnvelope deny(String message)
    {
        return DenyEnvelope.of(message);
    }

    /** Wrap an allow/deny envelope (or any Jackson-serialisable value)
     *  as a {@code tools/call} result. MCP returns tool results as a
     *  content array whose entries are typed text; here the text is the
     *  envelope serialised to JSON. */
    private JsonNode toolResponse(JsonNode id, Object envelope)
    {
        String envelopeJson;
        try {
            envelopeJson = mapper.writeValueAsString(envelope);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise tool envelope: " + envelope, e);
        }
        return ok(id, ToolCallResult.text(envelopeJson));
    }

    private JsonNode ok(JsonNode id, Object result)
    {
        return mapper.valueToTree(JsonRpcSuccess.of(id, result));
    }

    private JsonNode error(JsonNode id, int code, String message)
    {
        return mapper.valueToTree(JsonRpcError.of(id, code, message));
    }
}
