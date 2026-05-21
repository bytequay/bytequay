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
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.ThreadService;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
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

    /** How long the agent will wait for the user before we give up
     *  and tell Claude the request was denied. Two minutes is enough
     *  to switch tabs, read the call site, and decide; longer would
     *  leak DeferredResults if the browser tab dies. */
    private static final long DECISION_TIMEOUT_MS = 2L * 60L * 1000L;

    private final ThreadService threads;
    private final TaskStore taskStore;
    private final McpPermissionGate gate;
    private final NotificationService notifications;
    private final WatchedRepoStore watchedRepos;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;
    private final GitRunner git;
    private final ObjectMapper mapper;

    public McpController(
            ThreadService threads,
            TaskStore taskStore,
            McpPermissionGate gate,
            NotificationService notifications,
            WatchedRepoStore watchedRepos,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            GitRunner git,
            ObjectMapper mapper)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.git = requireNonNull(git, "git is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
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
                case "tools/list" -> deferred.setResult(listTools(id));
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

    private JsonNode listTools(JsonNode id)
    {
        ObjectNode result = mapper.createObjectNode();
        var tools = result.putArray("tools");

        // approval_prompt — drives Claude's --permission-prompt-tool.
        ObjectNode approval = mapper.createObjectNode();
        approval.put("name", TOOL_NAME);
        approval.put("description", "Asks the user to allow or deny a tool call. "
                + "Returns a JSON envelope with behavior=allow|deny.");
        ObjectNode approvalSchema = approval.putObject("inputSchema");
        approvalSchema.put("type", "object");
        ObjectNode approvalProperties = approvalSchema.putObject("properties");
        approvalProperties.putObject("tool_name").put("type", "string");
        approvalProperties.putObject("input").put("type", "object");
        approvalProperties.putObject("tool_use_id").put("type", "string");
        approvalSchema.putArray("required").add("tool_name").add("input").add("tool_use_id");
        tools.add(approval);

        // request_review — self-park gate. The CLI agent calls this
        // when it finishes with a proposed diff that needs the human's
        // eyes before publishing. No user prompt; the side-effect is
        // a status transition + notification.
        ObjectNode review = mapper.createObjectNode();
        review.put("name", REQUEST_REVIEW_TOOL);
        review.put("description",
                "Park the current task at AWAITING_REVIEW with a proposed diff + reply. "
                        + "Use this when you've finished a unit of work and want the human "
                        + "to review before anything is pushed or commented on GitHub.");
        ObjectNode reviewSchema = review.putObject("inputSchema");
        reviewSchema.put("type", "object");
        ObjectNode reviewProperties = reviewSchema.putObject("properties");
        reviewProperties.putObject("summary")
                .put("type", "string")
                .put("description", "One- or two-sentence summary of what's ready for review.");
        reviewProperties.putObject("draft_reply")
                .put("type", "string")
                .put("description", "Optional reply the human can publish as-is or edit.");
        reviewSchema.putArray("required").add("summary");
        tools.add(review);

        // post_comment — gated publish. Posts an issue-style comment
        // (the timeline-level kind, not a per-line review comment) to
        // the active task's linked PR, but only after the user clicks
        // Allow on a permission card showing the body. Resolves the
        // (owner, repo, number) tuple from the task's workingDir +
        // linkedPrNumber so the agent only has to write the body.
        ObjectNode postComment = mapper.createObjectNode();
        postComment.put("name", POST_COMMENT_TOOL);
        postComment.put("description",
                "Ask the user to post a comment on the active task's linked PR. "
                        + "Body is shown to the user before sending; on Approve the "
                        + "server makes the GitHub API call with the per-repo PAT.");
        ObjectNode postSchema = postComment.putObject("inputSchema");
        postSchema.put("type", "object");
        ObjectNode postProperties = postSchema.putObject("properties");
        postProperties.putObject("body")
                .put("type", "string")
                .put("description", "Markdown-formatted body of the comment.");
        postSchema.putArray("required").add("body");
        tools.add(postComment);

        // push — gated git push. Pushes the active task's worktree
        // branch to its upstream (sets -u origin <branch> on first
        // push, per GitRunner). Same user-gate pattern as post_comment;
        // no silent publish.
        ObjectNode push = mapper.createObjectNode();
        push.put("name", PUSH_TOOL);
        push.put("description",
                "Push the active task's branch upstream. The user must approve "
                        + "before the push runs; on Approve the server invokes "
                        + "`git push` from the task's worktree.");
        ObjectNode pushSchema = push.putObject("inputSchema");
        pushSchema.put("type", "object");
        pushSchema.putObject("properties");
        tools.add(push);

        return ok(id, result);
    }

    private void handleToolCall(String threadId, JsonNode id, JsonNode request, DeferredResult<JsonNode> deferred)
    {
        JsonNode params = request.path("params");
        String name = params.path("name").asText();
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
        if (!TOOL_NAME.equals(name)) {
            deferred.setResult(error(id, -32602, "unknown tool: " + name));
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
        if (active.isPresent()) {
            Task t = active.get();
            taskStore.saveTask(new Task(
                    t.id(), t.threadId(), t.seq(), TaskStatus.AWAITING_REVIEW,
                    t.branchName(), t.worktreePath(), t.baseBranch(), t.workingDir(),
                    t.processPid(), t.logPath(),
                    t.prNumber(), t.prState(), t.ciState(),
                    t.taskType(), t.linkedPrNumber(), t.linkedIssueNumber(),
                    t.costUsdMilli(), t.tokensIn(), t.tokensOut(),
                    t.firstMsgSeq(), t.lastMsgSeq(),
                    t.createdAt(), t.endedAt(), t.errorMessage()));
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", summary);
            if (!draftReply.isEmpty()) {
                payload.put("draftReply", draftReply);
            }
            payload.put("source", "mcp:request_review");
            String payloadJson = mapper.writeValueAsString(payload);
            notifications.notifyAwaitingReview(
                    threadId, active.map(Task::id).orElse(null), payloadJson);
        }
        catch (JsonProcessingException | RuntimeException e) {
            log.warn("notification emit on request_review failed for thread {}: {}",
                    threadId, e.getMessage());
        }
        ObjectNode result = mapper.createObjectNode();
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "text");
        item.put("text", "Parked at AWAITING_REVIEW. The user will see a notification "
                + "and can approve, edit, or discard from the thread.");
        result.putArray("content").add(item);
        deferred.setResult(ok(id, result));
    }

    /**
     * Handles {@code post_comment}: surfaces a permission card with
     * the proposed body and, on Approve, POSTs the comment to the
     * active task's linked PR via the per-repo PAT. Resolves the
     * (owner, repo, number) tuple from the task's workingDir +
     * linkedPrNumber so the agent only has to write the body.
     */
    private void handlePostComment(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        String body = args.path("body").asText("");
        if (body.isBlank()) {
            deferred.setResult(plainText(id, "body is required"));
            return;
        }
        Optional<PullRequestRef> prRef = resolvePrRef(threadId);
        if (prRef.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no PR linked to the active task — set linked_pr_number first"));
            return;
        }

        // Run the same permission flow approval_prompt uses, just with
        // post_comment as the gate's tool name. The frontend's
        // PermissionCard already special-cases the toolName for nicer
        // copy when one lands; arbitrary tools render a generic card.
        String callId = "post_comment-" + UUID.randomUUID();
        CompletableFuture<PermissionDecision> decisionFuture = gate.register(callId, POST_COMMENT_TOOL);
        try {
            threads.notifyPermissionRequested(threadId, callId, POST_COMMENT_TOOL,
                    "Post comment on " + prRef.get().owner() + "/" + prRef.get().repo()
                            + "#" + prRef.get().number());
        }
        catch (RuntimeException e) {
            log.warn("Failed to surface post_comment permission card for thread {}: {}",
                    threadId, e.getMessage());
        }

        decisionFuture.whenComplete((decision, ex) -> {
            if (ex != null) {
                deferred.setResult(plainText(id, "interrupted: " + ex.getMessage()));
                return;
            }
            if (decision != PermissionDecision.ALLOW) {
                deferred.setResult(plainText(id, "user denied"));
                return;
            }
            try {
                String repoFullName = prRef.get().owner() + "/" + prRef.get().repo();
                String pat = patResolver.resolve(repoFullName);
                pullRequests.createIssueComment(pat, prRef.get(), body);
                deferred.setResult(plainText(id,
                        "Posted comment on " + repoFullName + "#" + prRef.get().number() + "."));
            }
            catch (RuntimeException e) {
                log.warn("post_comment GitHub call failed for thread {}: {}",
                        threadId, e.getMessage());
                deferred.setResult(plainText(id,
                        "GitHub rejected the comment: " + e.getMessage()));
            }
        });
    }

    /**
     * Handles {@code push}: surfaces a permission card and, on
     * Approve, runs {@code git push} from the active task's worktree
     * path. Returns plain text — the agent isn't asking permission
     * for a future tool, it's asking for one side-effect to happen
     * now (same as {@code post_comment}).
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

        String callId = "push-" + UUID.randomUUID();
        CompletableFuture<PermissionDecision> decisionFuture = gate.register(callId, PUSH_TOOL);
        try {
            threads.notifyPermissionRequested(threadId, callId, PUSH_TOOL,
                    "Push " + (task.branchName() == null ? "branch" : task.branchName())
                            + " from worktree " + worktree);
        }
        catch (RuntimeException e) {
            log.warn("Failed to surface push permission card for thread {}: {}",
                    threadId, e.getMessage());
        }

        decisionFuture.whenComplete((decision, ex) -> {
            if (ex != null) {
                deferred.setResult(plainText(id, "interrupted: " + ex.getMessage()));
                return;
            }
            if (decision != PermissionDecision.ALLOW) {
                deferred.setResult(plainText(id, "user denied"));
                return;
            }
            try {
                git.push(worktree);
                deferred.setResult(plainText(id,
                        "Pushed " + (task.branchName() == null ? "branch" : task.branchName())
                                + " from " + worktree + "."));
            }
            catch (IOException e) {
                log.warn("push from {} failed for thread {}: {}",
                        worktree, threadId, e.getMessage());
                deferred.setResult(plainText(id, "push failed: " + e.getMessage()));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                deferred.setResult(plainText(id, "push interrupted"));
            }
            catch (RuntimeException e) {
                log.warn("push from {} rejected for thread {}: {}",
                        worktree, threadId, e.getMessage());
                deferred.setResult(plainText(id, "push rejected: " + e.getMessage()));
            }
        });
    }

    /** Resolves the active task's linked PR into a PullRequestRef by
     *  matching the task's workingDir against the watched-repos list. */
    private Optional<PullRequestRef> resolvePrRef(String threadId)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Task task = active.get();
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
