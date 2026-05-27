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

import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentTool;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.tools.PermissionResolver;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.ToolParam;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

/**
 * Minimal MCP server exposed over HTTP, one URL per thread. Claude
 * Code is configured via {@code --mcp-config} to talk to this
 * endpoint, and via {@code --permission-prompt-tool} to route tool
 * approvals through our single {@code approval_prompt} tool.
 *
 * <p>Only three JSON-RPC methods are implemented — {@code initialize},
 * {@code tools/list}, {@code tools/call} — because that is all
 * {@code --permission-prompt-tool} actually invokes. Other MCP
 * surfaces (resources, prompts, sampling) are not used and would
 * just be dead code.
 *
 * <p>The {@code tools/call} handler does not block its Tomcat worker
 * thread — it returns a {@link DeferredResult} that Spring resumes
 * once the user clicks Allow / Deny in the conversation pane.
 */
@RestController
@RequestMapping("/api/threads/{threadId}/mcp")
public class McpController
{
    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    /** Bumped if we ever break wire-compat. Matches the version
     *  Claude Code negotiated against in its current MCP client. */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** {@code mcp__bytequay__approval_prompt} from Claude's perspective
     *  — the leading {@code mcp__bytequay__} is added by Claude based
     *  on the server name in {@code --mcp-config}. */
    private static final String TOOL_NAME = "approval_prompt";

    /** Self-park tool: the CLI agent calls this when it finishes
     *  with a proposed diff that wants the human's eyes before
     *  publishing. Transitions the thread's active task to
     *  AWAITING_REVIEW and writes a notification. */
    private static final String REQUEST_REVIEW_TOOL = "request_review";

    /** Gated publish tool: the CLI agent asks the user before posting
     *  an issue comment to the active task's linked PR. The user sees
     *  a permission card showing the body; on Allow we POST via the
     *  per-repo PAT. */
    private static final String POST_COMMENT_TOOL = "post_comment";

    /** Gated publish tool: pushes the active task's worktree branch
     *  upstream via {@code git push}. Same user-gate pattern as
     *  {@link #POST_COMMENT_TOOL} — no silent publish. */
    private static final String PUSH_TOOL = "push";

    /** Read-only cross-thread context lookup. The agent calls this
     *  with a free-text query (or no query at all) and the server
     *  returns a digest of matching active Overall checkpoints from
     *  other threads — title, summary excerpt, bullets. No user gate;
     *  no GitHub mutation. */
    private static final String RECALL_THREAD_TOOL = "recall_thread";

    /** Hard upper bound on the {@code limit} arg of {@code recall_thread}
     *  — the result is inlined into the agent's context, so we cap it
     *  so a too-eager caller can't blow up a single turn. */
    private static final int RECALL_THREAD_MAX_LIMIT = 20;

    /** Default {@code limit} when the agent doesn't supply one — small
     *  enough that a typical recall pulls in just the most-relevant
     *  threads, big enough that fuzzy queries return useful diversity. */
    private static final int RECALL_THREAD_DEFAULT_LIMIT = 5;

    /** Per-checkpoint summary excerpt cap. Keeps the response readable
     *  when the matching threads have long Overall summaries. */
    private static final int RECALL_SUMMARY_EXCERPT_CHARS = 800;

    /** How long the agent will wait for the user before we give up
     *  and tell Claude the request was denied. Two minutes is enough
     *  to switch tabs, read the call site, and decide; longer would
     *  leak DeferredResults if the browser tab dies. */
    private static final long DECISION_TIMEOUT_MS = 2L * 60L * 1000L;

    /** Hard cap on the unified-diff payload we attach to the
     *  AWAITING_REVIEW notification for a parked push. Notifications
     *  land in a SQLite TEXT column and the frontend renders the diff
     *  inline, so we truncate aggressively rather than store a megabyte
     *  per parked push. The truncation marker comes from
     *  {@link GitRunner#diff} itself so a reader can tell what was cut. */
    private static final int PUSH_DIFF_MAX_BYTES = 500_000;

    private final ThreadService threads;
    private final TaskStore taskStore;
    private final ThreadCheckpointStore checkpoints;
    private final McpPermissionGate gate;
    private final NotificationService notifications;
    private final WatchedRepoStore watchedRepos;
    private final GitRunner git;
    private final ObjectMapper mapper;
    private final AgentToolRegistry registry;
    private final PermissionResolver permissions;

    public McpController(
            ThreadService threads,
            TaskStore taskStore,
            ThreadCheckpointStore checkpoints,
            McpPermissionGate gate,
            NotificationService notifications,
            WatchedRepoStore watchedRepos,
            GitRunner git,
            ObjectMapper mapper,
            AgentToolRegistry registry,
            PermissionResolver permissions)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.checkpoints = requireNonNull(checkpoints, "checkpoints is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.git = requireNonNull(git, "git is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.permissions = requireNonNull(permissions, "permissions is null");
    }

    @PostMapping
    public DeferredResult<JsonNode> handle(
            @PathVariable String threadId,
            @RequestBody JsonNode request)
    {
        DeferredResult<JsonNode> deferred = new DeferredResult<>(DECISION_TIMEOUT_MS);
        try {
            String method = request.path("method").asText();
            JsonNode id = request.path("id");
            switch (method) {
                case "initialize" -> deferred.setResult(initialize(id));
                case "tools/list" -> deferred.setResult(listTools(threadId, id));
                case "tools/call" -> handleToolCall(threadId, id, request, deferred);
                case "notifications/initialized", "notifications/cancelled" ->
                        // Notifications carry no id and need no response — Spring
                        // returns an empty body when the result is null.
                        deferred.setResult(null);
                default -> deferred.setResult(error(id, -32601, "method not found: " + method));
            }
        }
        catch (RuntimeException e) {
            log.warn("MCP request failed for thread {}: {}", threadId, e.getMessage());
            deferred.setResult(error(request.path("id"), -32603, e.getMessage()));
        }
        return deferred;
    }

    private JsonNode initialize(JsonNode id)
    {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "bytequay");
        info.put("version", "1.0.0");
        return ok(id, result);
    }

    private JsonNode listTools(String threadId, JsonNode id)
    {
        // Tools are declared via @AgentTool on the stub methods below;
        // the registry scans them at startup, sorts by name, and emits
        // a deterministic spec list. The MCP envelope just wraps each
        // spec into the wire shape, filtered to the caller's role so
        // a trunk agent doesn't even see task-only tools.
        AgentRole role = permissions.roleFor(threadId);
        ObjectNode result = mapper.createObjectNode();
        var tools = result.putArray("tools");
        for (ToolSpec spec : registry.visibleTo(role)) {
            ObjectNode tool = mapper.createObjectNode();
            tool.put("name", spec.name());
            tool.put("description", spec.description());
            try {
                tool.set("inputSchema", mapper.readTree(spec.inputSchema()));
            }
            catch (JsonProcessingException e) {
                // Generated by the registry from a record schema —
                // a parse failure here is a bug in the generator, not
                // the wire. Fail loudly so the next call surfaces it.
                throw new IllegalStateException(
                        "registry produced invalid JSON schema for tool " + spec.name(), e);
            }
            tools.add(tool);
        }
        return ok(id, result);
    }

    // ── @AgentTool declarations ─────────────────────────────────────────
    // Each annotated method below exists so {@link AgentToolRegistry}
    // can scan it for the tool's metadata and derived JSON schema.
    // Dispatch in Phase A still flows through {@link #handleToolCall}
    // and the hand-written per-tool branches; Phase B unifies the
    // dispatch through the registry's invoke entry point.
    //
    // These methods are deliberately no-ops on direct invocation —
    // anything calling them straight is bypassing the gating /
    // approval / park-guard wired in handleToolCall, which is the
    // safety surface this class is responsible for preserving.

    /** Args record for the {@code approval_prompt} tool — Claude's
     *  {@code --permission-prompt-tool} target. */
    public record ApprovalPromptArgs(
            @ToolParam(description = "The tool the agent is asking permission for.",
                    required = true, wireName = "tool_name") String toolName,
            @ToolParam(description = "JSON object of the arguments the agent wants to invoke the tool with.",
                    required = true) JsonNode input,
            @ToolParam(description = "Opaque correlation id the CLI uses to match the response back to the pending call.",
                    required = true, wireName = "tool_use_id") String toolUseId) {}

    @AgentTool(
            name = TOOL_NAME,
            description = "Asks the user to allow or deny a tool call. "
                    + "Returns a JSON envelope with behavior=allow|deny.",
            security = SecurityType.MCP,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public void declareApprovalPrompt(@SuppressWarnings("unused") ApprovalPromptArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code request_review}. */
    public record RequestReviewArgs(
            @ToolParam(description = "One- or two-sentence summary of what's ready for review.",
                    required = true) String summary,
            @ToolParam(description = "Optional reply the human can publish as-is or edit.",
                    wireName = "draft_reply") String draftReply) {}

    @AgentTool(
            name = REQUEST_REVIEW_TOOL,
            description = "Park the current task at AWAITING_REVIEW with a proposed diff + reply. "
                    + "Use this when you've finished a unit of work and want the human "
                    + "to review before anything is pushed or commented on GitHub.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public void declareRequestReview(@SuppressWarnings("unused") RequestReviewArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code post_comment}. */
    public record PostCommentArgs(
            @ToolParam(description = "Markdown-formatted body of the comment.",
                    required = true) String body) {}

    @AgentTool(
            name = POST_COMMENT_TOOL,
            description = "Ask the user to post a comment on the active task's linked PR. "
                    + "Body is shown to the user before sending; on Approve the "
                    + "server makes the GitHub API call with the per-repo PAT.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public void declarePostComment(@SuppressWarnings("unused") PostCommentArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code push} — currently no-args. */
    public record PushArgs() {}

    @AgentTool(
            name = PUSH_TOOL,
            description = "Push the active task's branch upstream. The user must approve "
                    + "before the push runs; on Approve the server invokes "
                    + "`git push` from the task's worktree.",
            security = SecurityType.GIT_PUSH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public void declarePush(@SuppressWarnings("unused") PushArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code recall_thread}. */
    public record RecallThreadArgs(
            @ToolParam(description = "Optional free-text filter. Matched case-insensitively against "
                    + "Overall summary text and bullet titles. Omit to get the "
                    + "most recent threads regardless of content.") String query,
            @ToolParam(description = "Max threads to return (default "
                    + RECALL_THREAD_DEFAULT_LIMIT + ", capped at "
                    + RECALL_THREAD_MAX_LIMIT + ").") Integer limit) {}

    @AgentTool(
            name = RECALL_THREAD_TOOL,
            description = "Search prior threads' Overall summaries for prior context. "
                    + "Use this before answering an unfamiliar question to see if "
                    + "a previous thread already worked through the same problem. "
                    + "Returns title + summary excerpt + bullet titles for each "
                    + "matching thread; never mutates state.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public void declareRecallThread(@SuppressWarnings("unused") RecallThreadArgs args)
    {
        // Dispatched via handleToolCall.
    }

    private void handleToolCall(String threadId, JsonNode id, JsonNode request, DeferredResult<JsonNode> deferred)
    {
        JsonNode params = request.path("params");
        String name = params.path("name").asText();
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
        Set<SecurityType> grants = permissions.grants(threadId);
        if (!grants.contains(spec.security())) {
            deferred.setResult(toolResponse(id, deny(
                    "tool '" + name + "' requires capability " + spec.security()
                            + " which is not granted to the current role ("
                            + permissions.roleFor(threadId) + ")")));
            return;
        }
        if (REQUEST_REVIEW_TOOL.equals(name)) {
            handleRequestReview(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (POST_COMMENT_TOOL.equals(name)) {
            handlePostComment(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (PUSH_TOOL.equals(name)) {
            handlePush(threadId, id, deferred);
            return;
        }
        if (RECALL_THREAD_TOOL.equals(name)) {
            handleRecallThread(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (!TOOL_NAME.equals(name)) {
            // Registry knew the tool but this controller doesn't have a
            // hand-coded handler for it yet (the gating dispatcher
            // lands in a later commit). Today the only registered tool
            // without a per-name branch above is approval_prompt
            // (TOOL_NAME) — anything else is a registry-only stub.
            deferred.setResult(error(id, -32602, "no handler for tool: " + name));
            return;
        }
        JsonNode args = params.path("arguments");
        String toolName = args.path("tool_name").asText();
        String callId = args.path("tool_use_id").asText();
        JsonNode toolInput = args.path("input");
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
        // prose, duplicating the card. (Phase 2 tracked as thread #106:
        // an off-page notification queue.)
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

        // If the user has pre-approved this tool ("Allow next 5",
        // "Always for this tool"), drain one slot and resolve without
        // ever showing a prompt. We surface a permission_auto_allowed
        // notice next to the tool call so the user can see which slot
        // was burned and how many are left.
        OptionalInt remaining = threads.tryConsumeToolBudget(threadId, toolName);
        if (remaining.isPresent()) {
            try {
                threads.notifyPermissionAutoAllowed(threadId, callId, toolName, remaining.getAsInt());
            }
            catch (RuntimeException e) {
                log.warn("Failed to record auto-approval notice for thread {}: {}", threadId, e.getMessage());
            }
            deferred.setResult(toolResponse(id, allow(toolInput)));
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
            try {
                threads.notifyPermissionAutoAllowed(threadId, callId, toolName, remaining.getAsInt());
            }
            catch (RuntimeException e) {
                log.warn("Failed to record auto-approval notice for thread {}: {}", threadId, e.getMessage());
            }
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

    private static String summarize(String toolName, JsonNode input)
    {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return toolName;
        }
        String s = input.toString();
        return s.length() > 240 ? s.substring(0, 237) + "…" : s;
    }

    /**
     * Handles the {@code request_review} MCP call. The CLI agent
     * uses this to self-park the current task at AWAITING_REVIEW
     * once it has a proposed diff + reply ready. Side effects:
     *
     *   * the thread's active task transitions to AWAITING_REVIEW
     *     (no-op when the thread is 0-Task — the notification is
     *     still emitted so the user sees something happened),
     *   * an AWAITING_REVIEW notification is written so the bell /
     *     auto* filter / thread strip light up,
     *   * the response is plain text — no allow/deny envelope, since
     *     the agent isn't asking for permission, it's announcing it
     *     is done.
     */
    private void handleRequestReview(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        String summary = args.path("summary").asText("");
        String draftReply = args.path("draft_reply").asText("");
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        active.ifPresent(this::parkActiveTaskAtAwaitingReview);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        if (!draftReply.isEmpty()) {
            payload.put("draftReply", draftReply);
        }
        payload.put("source", "mcp:request_review");
        emitAwaitingReviewNotification(threadId, active.map(Task::id).orElse(null), payload,
                "mcp:request_review");
        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will see a notification "
                        + "and can approve, edit, or discard from the thread."));
    }

    /** Save {@code task} with its status flipped to AWAITING_REVIEW.
     *  Mirrors the shape used by {@link #handleRequestReview} so the
     *  publish gates (push / post_comment) land at the same parked
     *  state with the same row update. */
    private void parkActiveTaskAtAwaitingReview(Task task)
    {
        taskStore.saveTask(new Task(
                task.id(), task.threadId(), task.seq(), TaskStatus.AWAITING_REVIEW,
                task.branchName(), task.worktreePath(), task.baseBranch(), task.workingDir(),
                task.processPid(), task.logPath(),
                task.prNumber(), task.prState(), task.ciState(),
                task.taskType(), task.linkedPrNumber(), task.linkedIssueNumber(),
                task.costUsdMilli(), task.tokensIn(), task.tokensOut(),
                task.agentSessionId(),
                task.createdAt(), task.endedAt(), task.errorMessage(),
                task.name(), task.roleSkill()));
    }

    /** Serialises {@code payload} and writes an AWAITING_REVIEW
     *  notification. Failures only get logged — the notification is the
     *  audit trail, but if it can't be written we still want the MCP
     *  call to return a parked result so the agent ends its turn
     *  cleanly. */
    private void emitAwaitingReviewNotification(
            String threadId, String taskId, Map<String, Object> payload, String source)
    {
        try {
            String payloadJson = mapper.writeValueAsString(payload);
            notifications.notifyAwaitingReview(threadId, taskId, payloadJson);
        }
        catch (JsonProcessingException | RuntimeException e) {
            log.warn("notification emit on {} failed for thread {}: {}",
                    source, threadId, e.getMessage());
        }
    }

    /**
     * Handles {@code post_comment}: parks the active task at
     * AWAITING_REVIEW with the proposed body + linked-PR ref captured
     * in the notification payload, and returns immediately. The
     * actual GitHub call doesn't fire here — the user's Approve click
     * in the AWAITING_REVIEW pane drives a separate publish endpoint
     * (the design contract is "diff viewer + Approve / Edit / Discard",
     * not an inline allow/deny card).
     */
    private void handlePostComment(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        String body = args.path("body").asText("");
        if (body.isBlank()) {
            deferred.setResult(plainText(id, "body is required"));
            return;
        }
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to comment on"));
            return;
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no PR linked to the active task — set linked_pr_number first"));
            return;
        }

        parkActiveTaskAtAwaitingReview(active.get());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "post_comment");
        payload.put("body", body);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:post_comment");
        emitAwaitingReviewNotification(threadId, active.get().id(), payload, "mcp:post_comment");

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will review the comment body and "
                        + "approve, edit, or discard from the thread."));
    }

    /**
     * Handles {@code push}: parks the active task at AWAITING_REVIEW
     * with the proposed unified diff captured in the notification
     * payload, so the user can review what would be pushed before any
     * branch hits the remote. The {@code git push} itself doesn't fire
     * here — it's deferred to a publish endpoint the Approve action
     * drives.
     *
     * <p>Diff base: prefer {@code origin/<branch>} when the branch has
     * been pushed before (so the user sees "what's new since the last
     * push"), else fall back to {@code origin/<baseBranch>} or the
     * local base branch, matching the three-dot diff GitHub renders.
     */
    private void handlePush(String threadId, JsonNode id, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to push"));
            return;
        }
        Task task = active.get();
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            deferred.setResult(plainText(id,
                    "the active task has no worktree — push needs an isolated branch"));
            return;
        }
        Path worktree = Path.of(task.worktreePath());

        parkActiveTaskAtAwaitingReview(task);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "push");
        payload.put("branch", task.branchName());
        payload.put("baseBranch", task.baseBranch());
        payload.put("worktreePath", task.worktreePath());
        attachPushDiffToPayload(payload, worktree, task);
        payload.put("source", "mcp:push");
        emitAwaitingReviewNotification(threadId, task.id(), payload, "mcp:push");

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will review the diff and "
                        + "approve or discard from the thread."));
    }

    /** Adds {@code diff} + companion fields to {@code payload}: the
     *  unified diff string when computable, the chosen base ref, and a
     *  human-readable error string when git can't produce the diff
     *  (missing baseBranch, fetch needed, etc.). Failures don't abort
     *  the park — the audit trail is still useful even without the
     *  preview. */
    private void attachPushDiffToPayload(Map<String, Object> payload, Path worktree, Task task)
    {
        String base = chooseDiffBase(worktree, task);
        if (base == null) {
            payload.put("diff", null);
            payload.put("diffError", "no base ref available to diff against; "
                    + "task.baseBranch is " + (task.baseBranch() == null ? "null" : "not on origin yet"));
            return;
        }
        payload.put("diffBase", base);
        try {
            String diff = git.diff(worktree, base, "HEAD", PUSH_DIFF_MAX_BYTES);
            payload.put("diff", diff);
        }
        catch (IOException e) {
            log.warn("push diff for {} failed: {}", worktree, e.getMessage());
            payload.put("diff", null);
            payload.put("diffError", "git diff failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            payload.put("diff", null);
            payload.put("diffError", "git diff interrupted");
        }
        catch (RuntimeException e) {
            log.warn("push diff for {} rejected: {}", worktree, e.getMessage());
            payload.put("diff", null);
            payload.put("diffError", "git diff rejected: " + e.getMessage());
        }
    }

    /** Picks the most useful base ref for the push diff: the remote
     *  branch tip if the branch has been pushed (so the user sees only
     *  what's new), else the remote base branch, else the local base
     *  branch. Returns null when nothing resolves so the caller can
     *  record a {@code diffError} instead of guessing. */
    private String chooseDiffBase(Path worktree, Task task)
    {
        try {
            if (task.branchName() != null && !task.branchName().isBlank()) {
                String remoteBranch = "origin/" + task.branchName();
                if (git.refExists(worktree, remoteBranch)) {
                    return remoteBranch;
                }
            }
            if (task.baseBranch() != null && !task.baseBranch().isBlank()) {
                String remoteBase = "origin/" + task.baseBranch();
                if (git.refExists(worktree, remoteBase)) {
                    return remoteBase;
                }
                if (git.refExists(worktree, task.baseBranch())) {
                    return task.baseBranch();
                }
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("ref probe in {} failed while choosing diff base: {}",
                    worktree, e.getMessage());
        }
        return null;
    }

    /**
     * Handles {@code recall_thread}: searches active Overall checkpoints
     * across the database for prior threads whose Overall summary or
     * bullet titles match the agent's query, then returns a digest as a
     * single text block.
     *
     * <p>Read-only — no user gate. The current thread is excluded so
     * the agent doesn't read back its own in-flight Overall as if it
     * were a separate hit. Filtering is plain case-insensitive substring
     * matching today; a future commit can swap in BM25 or embeddings
     * once we have a corpus to tune against.
     */
    private void handleRecallThread(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        String query = args.path("query").asText("").trim();
        int requestedLimit = args.path("limit").asInt(RECALL_THREAD_DEFAULT_LIMIT);
        if (requestedLimit <= 0) {
            requestedLimit = RECALL_THREAD_DEFAULT_LIMIT;
        }
        int limit = Math.min(requestedLimit, RECALL_THREAD_MAX_LIMIT);

        // Pull a generous candidate window — we filter in-memory and
        // the table is local, so an extra factor here costs little but
        // helps when the query is selective.
        int scanLimit = Math.min(RECALL_THREAD_MAX_LIMIT * 4, limit * 8);
        List<ThreadCheckpoint> candidates = checkpoints.listAllActiveOveralls(scanLimit);
        String needle = query.isEmpty() ? null : query.toLowerCase(Locale.ROOT);

        List<ThreadCheckpoint> matches = new ArrayList<>();
        for (ThreadCheckpoint cp : candidates) {
            if (cp.threadId().equals(threadId)) {
                continue;
            }
            if (needle != null && !checkpointMatches(cp, needle)) {
                continue;
            }
            matches.add(cp);
            if (matches.size() >= limit) {
                break;
            }
        }

        deferred.setResult(plainText(id, renderRecallResult(query, matches)));
    }

    private static boolean checkpointMatches(ThreadCheckpoint cp, String needle)
    {
        String summary = cp.summaryMd();
        if (summary != null && summary.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (String bullet : cp.bulletTitles()) {
            if (bullet != null && bullet.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String renderRecallResult(String query, List<ThreadCheckpoint> matches)
    {
        if (matches.isEmpty()) {
            return query.isEmpty()
                    ? "No prior threads with an Overall summary yet."
                    : "No prior threads matched: " + query;
        }
        StringBuilder out = new StringBuilder();
        out.append(matches.size())
                .append(query.isEmpty() ? " recent thread(s):\n" : " match(es) for \"")
                .append(query.isEmpty() ? "" : query)
                .append(query.isEmpty() ? "" : "\":\n");
        for (ThreadCheckpoint cp : matches) {
            String title = threads.find(cp.threadId())
                    .map(com.bytequay.app.domain.Thread::title)
                    .filter(s -> !s.isBlank())
                    .orElse("(untitled)");
            out.append("\n— thread ").append(cp.threadId())
                    .append(" · ").append(title).append('\n');
            for (String bullet : cp.bulletTitles()) {
                if (bullet != null && !bullet.isBlank()) {
                    out.append("  • ").append(bullet).append('\n');
                }
            }
            String summary = cp.summaryMd();
            if (summary != null && !summary.isBlank()) {
                String excerpt = summary.length() <= RECALL_SUMMARY_EXCERPT_CHARS
                        ? summary
                        : summary.substring(0, RECALL_SUMMARY_EXCERPT_CHARS) + "…";
                out.append(excerpt);
                if (!excerpt.endsWith("\n")) {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    /** True when any task on this thread is currently parked
     *  ({@code AWAITING_REVIEW} or {@code NEEDS_ATTENTION}). The
     *  publish-gate flow only transitions out of those states on
     *  user approve/discard, so while a parked task exists the
     *  agent's turn is logically over and the gate refuses further
     *  built-in tool calls. {@code NEEDS_ATTENTION} isn't written by
     *  any code today; the second check is cheap future-proofing
     *  against the second parked state landing later. */
    private boolean isThreadParked(String threadId)
    {
        return taskStore.listTasksByThread(threadId).stream()
                .anyMatch(t -> t.status() == TaskStatus.AWAITING_REVIEW
                        || t.status() == TaskStatus.NEEDS_ATTENTION);
    }

    /** Resolves a task's linked PR into a PullRequestRef by matching
     *  the task's workingDir against the watched-repos list. Returns
     *  empty when the task has no PR linked or the workingDir doesn't
     *  match any known clone, so callers can surface a useful error
     *  rather than mint a half-formed ref. */
    private Optional<PullRequestRef> resolvePrRefFromTask(Task task)
    {
        if (task.linkedPrNumber() == null || task.workingDir() == null) {
            return Optional.empty();
        }
        Path needle = Path.of(task.workingDir());
        for (WatchedRepo r : watchedRepos.findAll()) {
            if (r.localClonePath() != null
                    && !r.localClonePath().isBlank()
                    && Path.of(r.localClonePath()).equals(needle)) {
                return Optional.of(new PullRequestRef(r.owner(), r.repo(), task.linkedPrNumber()));
            }
        }
        return Optional.empty();
    }

    /** Plain-text MCP tool response — no allow/deny envelope. */
    private JsonNode plainText(JsonNode id, String text)
    {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "text");
        item.put("text", text);
        result.putArray("content").add(item);
        return ok(id, result);
    }

    private ObjectNode allow(JsonNode updatedInput)
    {
        ObjectNode env = mapper.createObjectNode();
        env.put("behavior", "allow");
        env.set("updatedInput", updatedInput == null || updatedInput.isMissingNode()
                ? mapper.createObjectNode()
                : updatedInput);
        return env;
    }

    private ObjectNode deny(String message)
    {
        ObjectNode env = mapper.createObjectNode();
        env.put("behavior", "deny");
        env.put("message", message);
        return env;
    }

    private JsonNode toolResponse(JsonNode id, ObjectNode envelope)
    {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "text");
        item.put("text", envelope.toString());
        result.putArray("content").add(item);
        return ok(id, result);
    }

    private JsonNode ok(JsonNode id, JsonNode result)
    {
        ObjectNode env = mapper.createObjectNode();
        env.put("jsonrpc", "2.0");
        if (id != null && !id.isMissingNode()) {
            env.set("id", id);
        }
        env.set("result", result);
        return env;
    }

    private JsonNode error(JsonNode id, int code, String message)
    {
        ObjectNode env = mapper.createObjectNode();
        env.put("jsonrpc", "2.0");
        if (id != null && !id.isMissingNode()) {
            env.set("id", id);
        }
        ObjectNode err = env.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return env;
    }
}
