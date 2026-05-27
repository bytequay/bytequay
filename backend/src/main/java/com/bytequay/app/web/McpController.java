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
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PullRequestStore;
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
import com.bytequay.app.service.tools.RuntimeToolInvocation;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.SkillTools;
import com.bytequay.app.service.tools.ToolContext;
import com.bytequay.app.service.tools.ToolParam;
import com.bytequay.app.service.tools.ToolSpec;
import com.bytequay.app.service.workspaces.WorkspaceService;
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

    /** Discovery tool name — returns the registry's catalog filtered
     *  to the caller's role. */
    private static final String LIST_TOOLS_TOOL = "list_tools";

    /** Skills-runtime tool name — returns the manifest projection for
     *  the caller's scope. */
    private static final String LIST_SKILLS_TOOL = "list_skills";

    /** Skills-runtime tool name — loads the body of one named skill. */
    private static final String LOAD_SKILL_TOOL = "load_skill";

    /** Read tool — returns one task row by id. */
    private static final String READ_TASK_TOOL = "read_task";

    /** Read tool — returns one PR's local-cache row by repo + number. */
    private static final String READ_PR_TOOL = "read_pr";

    /** Read tool — returns the active workspace's memory_md. */
    private static final String READ_WORKSPACE_MEMORY_TOOL = "read_workspace_memory";

    /** Orchestration tool — trunk-only; materialises the first task
     *  on a 0-task thread via the existing ThreadService entry point. */
    private static final String CREATE_TASK_TOOL = "create_task";

    /** Parked publisher — replies in an existing review thread on the
     *  active task's linked PR. The reply is captured into the parked
     *  notification; PublishService.approve posts it on the user's
     *  Approve click. */
    private static final String REPLY_REVIEW_THREAD_TOOL = "reply_review_thread";

    /** Parked publisher — submits an APPROVE review on the active
     *  task's linked PR. The optional body is the review summary
     *  shown alongside the green checkmark on GitHub. */
    private static final String APPROVE_PR_TOOL = "approve_pr";

    /** Parked publisher — merges the active task's linked PR with
     *  the user-selected strategy (squash by default). */
    private static final String MERGE_PR_TOOL = "merge_pr";

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
    private final SkillTools skillTools;
    private final PullRequestStore prStore;
    private final WorkspaceService workspaces;

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
            PermissionResolver permissions,
            SkillTools skillTools,
            PullRequestStore prStore,
            WorkspaceService workspaces)
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
        this.skillTools = requireNonNull(skillTools, "skillTools is null");
        this.prStore = requireNonNull(prStore, "prStore is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
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

    /** Args record for {@code list_tools} — no args. */
    public record ListToolsArgs() {}

    @AgentTool(
            name = LIST_TOOLS_TOOL,
            description = "List every tool available this turn, filtered to the "
                    + "caller's role. Returns a JSON array of {name, description, "
                    + "gating, security} entries — useful when picking the right "
                    + "verb for the next action.",
            security = SecurityType.TOOL_DISCOVER,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public void declareListTools(@SuppressWarnings("unused") ListToolsArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code list_skills}. */
    public record ListSkillsArgs(
            @ToolParam(description = "Optional scope filter — one of global, repo, thread. "
                    + "Omit to see all skills visible to this thread.") String scope,
            @ToolParam(description = "Optional substring match against the trigger description. "
                    + "Case-insensitive.") String query) {}

    @AgentTool(
            name = LIST_SKILLS_TOOL,
            description = "List the skills available for this turn. Returns a JSON array "
                    + "of {id, name, description, scope, repo, role_tag, kind} entries. "
                    + "Skills are model-triggered — read the \"loads when …\" description "
                    + "and decide whether to load the body via load_skill.",
            security = SecurityType.SKILL_USE,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public void declareListSkills(@SuppressWarnings("unused") ListSkillsArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code load_skill}. */
    public record LoadSkillArgs(
            @ToolParam(description = "Unique skill name from a prior list_skills entry.",
                    required = true) String name) {}

    @AgentTool(
            name = LOAD_SKILL_TOOL,
            description = "Load the body of one skill by name. Returns a JSON object "
                    + "{name, body}. Pair with list_skills: list to find the trigger "
                    + "that matches the task, load to fetch the instructions.",
            security = SecurityType.SKILL_USE,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public void declareLoadSkill(@SuppressWarnings("unused") LoadSkillArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code read_task}. */
    public record ReadTaskArgs(
            @ToolParam(description = "Task id to look up. Returns the task row as JSON or "
                    + "an error envelope when missing.",
                    required = true, wireName = "task_id") String taskId) {}

    @AgentTool(
            name = READ_TASK_TOOL,
            description = "Read one task row by id. Returns id, threadId, seq, status, "
                    + "branchName, worktreePath, baseBranch, workingDir, prNumber, "
                    + "linkedPrNumber, linkedIssueNumber, taskType, createdAt, endedAt, "
                    + "errorMessage, name. Pure read — no GitHub call.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public void declareReadTask(@SuppressWarnings("unused") ReadTaskArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code read_pr}. */
    public record ReadPrArgs(
            @ToolParam(description = "owner/name string of the repo.",
                    required = true) String repo,
            @ToolParam(description = "PR number.",
                    required = true) Integer number) {}

    @AgentTool(
            name = READ_PR_TOOL,
            description = "Read one pull request's row from the local cache. "
                    + "Returns id, repo, number, title, author, state, mergeable, "
                    + "headRef, baseRef, additions, deletions, commentCount, "
                    + "attentionReason, snoozedUntil, lastSyncedAt. Pure read against "
                    + "the local DB — no GitHub API call. Run the regular sync if "
                    + "you want a fresher snapshot.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public void declareReadPr(@SuppressWarnings("unused") ReadPrArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code read_workspace_memory} — no args; the
     *  workspace is derived from the thread's owning row. */
    public record ReadWorkspaceMemoryArgs() {}

    @AgentTool(
            name = READ_WORKSPACE_MEMORY_TOOL,
            description = "Read the active workspace's memory_md (the distilled brain — "
                    + "architecture decisions, conventions, blockers). Returns the raw "
                    + "markdown body so the agent can quote it or use it as context "
                    + "for the current turn.",
            security = SecurityType.MEMORY_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public void declareReadWorkspaceMemory(@SuppressWarnings("unused") ReadWorkspaceMemoryArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code create_task}. */
    public record CreateTaskArgs(
            @ToolParam(description = "owner/name of the watched repo the task should be cut from. "
                    + "Must already be a watched repo with a local clone path; the task's "
                    + "worktree is cut from that clone.",
                    required = true) String repo,
            @ToolParam(description = "Optional first user prompt to seed the new task's "
                    + "conversation. When set, the task starts running this turn immediately; "
                    + "when omitted, the task lands at PENDING and waits for the user.",
                    wireName = "initial_prompt") String initialPrompt,
            @ToolParam(description = "Task type — 'DEVELOP' (default), 'REVIEW', etc. "
                    + "Free-form so future task types don't need a schema bump.",
                    wireName = "task_type") String taskType,
            @ToolParam(description = "Optional GitHub PR number to link the task to "
                    + "(for review / fix-up tasks bound to an existing PR).",
                    wireName = "linked_pr_number") Integer linkedPrNumber,
            @ToolParam(description = "Optional GitHub issue number to link the task to.",
                    wireName = "linked_issue_number") Integer linkedIssueNumber) {}

    @AgentTool(
            name = CREATE_TASK_TOOL,
            description = "Cut a new task on this thread. Trunk-only — the trunk role "
                    + "plans + cuts tasks; task / reviewer roles can't reach this. "
                    + "Returns the new task's id, branch, worktree path, and seq. "
                    + "The first task on a 0-task thread runs through ThreadService's "
                    + "materialiseTask path; an attempt on a thread that already has "
                    + "tasks fails — use next_task / ship_task instead.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TRUNK)
    public void declareCreateTask(@SuppressWarnings("unused") CreateTaskArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code reply_review_thread}. */
    public record ReplyReviewThreadArgs(
            @ToolParam(description = "Id of the root review comment whose thread you're "
                    + "replying to. Find it via list_pr_review_threads / the diff view.",
                    required = true, wireName = "root_comment_id") Long rootCommentId,
            @ToolParam(description = "Markdown body of the reply. Shown to the user before "
                    + "sending; on Approve the server posts it via the per-repo PAT.",
                    required = true) String body) {}

    @AgentTool(
            name = REPLY_REVIEW_THREAD_TOOL,
            description = "Reply inside an existing review thread on the active task's "
                    + "linked PR. The body is parked at AWAITING_REVIEW; the user reviews "
                    + "it in the publish gate and the server posts via the GitHub reply-to-"
                    + "review-comment API on Approve.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public void declareReplyReviewThread(@SuppressWarnings("unused") ReplyReviewThreadArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code approve_pr}. */
    public record ApprovePrArgs(
            @ToolParam(description = "Optional review summary shown alongside the approval. "
                    + "Empty submits an approval with no body — GitHub allows this.")
            String body) {}

    @AgentTool(
            name = APPROVE_PR_TOOL,
            description = "Submit an APPROVE review on the active task's linked PR. "
                    + "The proposed approval (and any body) is parked at AWAITING_REVIEW; "
                    + "on Approve the server fires a GitHub review create with event=APPROVE.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public void declareApprovePr(@SuppressWarnings("unused") ApprovePrArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code merge_pr}. */
    public record MergePrArgs(
            @ToolParam(description = "Merge method — one of 'squash' (default), 'merge', or "
                    + "'rebase'. Repos that don't allow the chosen method will surface that "
                    + "as a publish failure on the Approve step.")
            String strategy) {}

    @AgentTool(
            name = MERGE_PR_TOOL,
            description = "Merge the active task's linked PR. The proposed merge is parked "
                    + "at AWAITING_REVIEW with the chosen strategy; on Approve the server "
                    + "fires the GitHub merge endpoint.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public void declareMergePr(@SuppressWarnings("unused") MergePrArgs args)
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
        if (LIST_TOOLS_TOOL.equals(name)) {
            handleListTools(threadId, id, deferred);
            return;
        }
        if (LIST_SKILLS_TOOL.equals(name)) {
            handleSkillToolsDispatch(threadId, id, LIST_SKILLS_TOOL, params.path("arguments"), deferred);
            return;
        }
        if (LOAD_SKILL_TOOL.equals(name)) {
            handleSkillToolsDispatch(threadId, id, LOAD_SKILL_TOOL, params.path("arguments"), deferred);
            return;
        }
        if (READ_TASK_TOOL.equals(name)) {
            handleReadTask(id, params.path("arguments"), deferred);
            return;
        }
        if (READ_PR_TOOL.equals(name)) {
            handleReadPr(id, params.path("arguments"), deferred);
            return;
        }
        if (READ_WORKSPACE_MEMORY_TOOL.equals(name)) {
            handleReadWorkspaceMemory(threadId, id, deferred);
            return;
        }
        if (CREATE_TASK_TOOL.equals(name)) {
            handleCreateTask(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (REPLY_REVIEW_THREAD_TOOL.equals(name)) {
            handleReplyReviewThread(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (APPROVE_PR_TOOL.equals(name)) {
            handleApprovePr(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (MERGE_PR_TOOL.equals(name)) {
            handleMergePr(threadId, id, params.path("arguments"), deferred);
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
     * Handles {@code list_tools}: walks the registry filtered by the
     * caller's role and returns a JSON array digest with the tool's
     * name, description, security type, and gating mode. Pure
     * metadata — no side effects.
     */
    private void handleListTools(String threadId, JsonNode id, DeferredResult<JsonNode> deferred)
    {
        AgentRole role = permissions.roleFor(threadId);
        ObjectNode result = mapper.createObjectNode();
        var arr = result.putArray("tools");
        for (ToolSpec spec : registry.visibleTo(role)) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("name", spec.name());
            entry.put("description", spec.description());
            entry.put("gating", spec.gating().name().toLowerCase(Locale.ROOT));
            entry.put("security", spec.security().name().toLowerCase(Locale.ROOT));
            arr.add(entry);
        }
        try {
            deferred.setResult(plainText(id, mapper.writeValueAsString(result.get("tools"))));
        }
        catch (JsonProcessingException e) {
            deferred.setResult(error(id, -32603, "failed to serialise tool catalog"));
        }
    }

    /**
     * Handles {@code list_skills} / {@code load_skill}: both route into
     * the shared {@link SkillTools} dispatcher with a {@link ToolContext}
     * built from the thread's resolved role. The dispatcher's JSON
     * result lands in the MCP plain-text envelope verbatim; an error
     * (e.g. unknown skill name) is surfaced as a deny envelope so the
     * model treats it as a tool failure.
     */
    private void handleSkillToolsDispatch(
            String threadId, JsonNode id, String toolName, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        AgentRole role = permissions.roleFor(threadId);
        ToolContext ctx = new ToolContext(
                Set.of(),
                Optional.of(threadId),
                Optional.of(role.name().toLowerCase(Locale.ROOT)));
        RuntimeToolInvocation out = skillTools.dispatch(toolName, args, ctx);
        if (out.isError()) {
            deferred.setResult(toolResponse(id, deny(out.result())));
            return;
        }
        deferred.setResult(plainText(id, out.result()));
    }

    /**
     * Handles {@code read_task}: looks up a task row by id and returns
     * the projection as plain-text JSON. Empty / unknown id surfaces
     * as a deny envelope so the model treats it as a tool failure
     * rather than a permission error.
     */
    private void handleReadTask(JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        String taskId = args.path("task_id").asText("");
        if (taskId.isBlank()) {
            deferred.setResult(toolResponse(id, deny("task_id is required")));
            return;
        }
        Optional<Task> match = taskStore.findTaskById(taskId);
        if (match.isEmpty()) {
            deferred.setResult(toolResponse(id, deny("task not found: " + taskId)));
            return;
        }
        Task task = match.get();
        ObjectNode out = mapper.createObjectNode();
        out.put("id", task.id());
        out.put("threadId", task.threadId());
        out.put("seq", task.seq());
        out.put("status", task.status() == null ? null : task.status().name());
        out.put("branchName", task.branchName());
        out.put("worktreePath", task.worktreePath());
        out.put("baseBranch", task.baseBranch());
        out.put("workingDir", task.workingDir());
        out.put("prNumber", task.prNumber());
        out.put("linkedPrNumber", task.linkedPrNumber());
        out.put("linkedIssueNumber", task.linkedIssueNumber());
        out.put("taskType", task.taskType());
        out.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
        out.put("endedAt", task.endedAt() == null ? null : task.endedAt().toString());
        out.put("errorMessage", task.errorMessage());
        out.put("name", task.name());
        deferred.setResult(plainText(id, toJsonString(out)));
    }

    /**
     * Handles {@code read_pr}: resolves the (repo, number) pair to a
     * local pull_requests row and emits its projection. Pure local
     * read — no PAT, no GitHub call.
     */
    private void handleReadPr(JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        String repo = args.path("repo").asText("");
        int number = args.path("number").asInt(0);
        if (repo.isBlank() || number <= 0) {
            deferred.setResult(toolResponse(id, deny("repo (owner/name) and number are required")));
            return;
        }
        Optional<Long> prId = prStore.findIdByRepoAndNumber(repo, number);
        if (prId.isEmpty()) {
            deferred.setResult(toolResponse(id, deny(
                    "PR not in local cache: " + repo + "#" + number
                            + " — run sync or add the repo to watched repos.")));
            return;
        }
        Optional<PullRequest> match = prStore.findById(prId.get());
        if (match.isEmpty()) {
            deferred.setResult(toolResponse(id, deny(
                    "PR row gone after id lookup: " + repo + "#" + number)));
            return;
        }
        PullRequest pr = match.get();
        ObjectNode out = mapper.createObjectNode();
        out.put("id", pr.id());
        out.put("repo", pr.repo());
        out.put("number", pr.number());
        out.put("title", pr.title());
        out.put("author", pr.author());
        out.put("state", pr.state());
        out.put("draft", pr.draft());
        out.put("mergeable", pr.mergeable());
        out.put("mergeableState", pr.mergeableState());
        out.put("headRef", pr.headRef());
        out.put("additions", pr.additions());
        out.put("deletions", pr.deletions());
        out.put("commentCount", pr.commentCount());
        out.put("attentionReason", pr.attentionReason() == null ? null : pr.attentionReason().name());
        out.put("createdAt", pr.createdAt() == null ? null : pr.createdAt().toString());
        out.put("updatedAt", pr.updatedAt() == null ? null : pr.updatedAt().toString());
        out.put("closedAt", pr.closedAt() == null ? null : pr.closedAt().toString());
        out.put("mergedAt", pr.mergedAt() == null ? null : pr.mergedAt().toString());
        out.put("snoozedUntil", pr.snoozedUntil() == null ? null : pr.snoozedUntil().toString());
        deferred.setResult(plainText(id, toJsonString(out)));
    }

    /**
     * Handles {@code read_workspace_memory}: derives the workspace id
     * from the thread row and returns its memory_md body. Returns an
     * empty string when the workspace has no brain yet (legitimate
     * pre-populated state).
     */
    private void handleReadWorkspaceMemory(String threadId, JsonNode id, DeferredResult<JsonNode> deferred)
    {
        Optional<com.bytequay.app.domain.Thread> threadOpt = threads.find(threadId);
        if (threadOpt.isEmpty()) {
            deferred.setResult(toolResponse(id, deny("thread not found: " + threadId)));
            return;
        }
        String workspaceId = threadOpt.get().workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            deferred.setResult(toolResponse(id, deny("thread has no workspace bound")));
            return;
        }
        try {
            String body = workspaces.getMemory(workspaceId);
            ObjectNode out = mapper.createObjectNode();
            out.put("workspaceId", workspaceId);
            out.put("memoryMd", body == null ? "" : body);
            deferred.setResult(plainText(id, toJsonString(out)));
        }
        catch (RuntimeException e) {
            deferred.setResult(toolResponse(id, deny(
                    "could not read memory for workspace " + workspaceId + ": " + e.getMessage())));
        }
    }

    /**
     * Handles {@code create_task}: cuts a new task on this thread via
     * the trunk-only path. Refuses when the thread already has tasks
     * (the agent should call next_task / ship_task instead), when the
     * named repo isn't watched, or when the watched repo has no local
     * clone path. On success returns id + branch + worktreePath so
     * the agent can immediately reason about where its work will land.
     */
    private void handleCreateTask(String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        String repo = args.path("repo").asText("");
        if (repo.isBlank()) {
            deferred.setResult(toolResponse(id, deny("repo (owner/name) is required")));
            return;
        }
        Optional<com.bytequay.app.domain.Thread> threadOpt = threads.find(threadId);
        if (threadOpt.isEmpty()) {
            deferred.setResult(toolResponse(id, deny("thread not found: " + threadId)));
            return;
        }
        com.bytequay.app.domain.Thread thread = threadOpt.get();
        if (!taskStore.listTasksByThread(threadId).isEmpty()) {
            deferred.setResult(toolResponse(id, deny(
                    "thread already has tasks — use next_task or ship_task to spawn a sibling. "
                            + "create_task is for 0-task threads only.")));
            return;
        }
        WatchedRepo watched = watchedRepos.findAll().stream()
                .filter(r -> repo.equals(r.fullName()))
                .findFirst()
                .orElse(null);
        if (watched == null) {
            deferred.setResult(toolResponse(id, deny(
                    "repo not in watched repos: " + repo
                            + " — add it under Repos before cutting a task.")));
            return;
        }
        if (watched.localClonePath() == null || watched.localClonePath().isBlank()) {
            deferred.setResult(toolResponse(id, deny(
                    "watched repo " + repo + " has no local clone path — set it under Repos.")));
            return;
        }
        String initialPrompt = args.path("initial_prompt").asText("");
        String taskType = args.path("task_type").asText("");
        Integer linkedPrNumber = args.path("linked_pr_number").isNumber()
                ? args.path("linked_pr_number").asInt()
                : null;
        Integer linkedIssueNumber = args.path("linked_issue_number").isNumber()
                ? args.path("linked_issue_number").asInt()
                : null;
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                thread.kind(),
                thread.provider(),
                thread.model(),
                /* title — reuse the thread title */ thread.title(),
                /* workingDir */ watched.localClonePath(),
                /* branchName — let worktree create derive it */ null,
                initialPrompt.isBlank() ? null : initialPrompt,
                /* initialGroupIds */ List.of(),
                taskType.isBlank() ? null : taskType,
                linkedPrNumber,
                linkedIssueNumber,
                thread.flow(),
                thread.workspaceId());
        try {
            Task created = threads.materialiseTask(threadId, request);
            ObjectNode out = mapper.createObjectNode();
            out.put("id", created.id());
            out.put("threadId", created.threadId());
            out.put("seq", created.seq());
            out.put("status", created.status() == null ? null : created.status().name());
            out.put("branchName", created.branchName());
            out.put("worktreePath", created.worktreePath());
            out.put("workingDir", created.workingDir());
            out.put("baseBranch", created.baseBranch());
            deferred.setResult(plainText(id, toJsonString(out)));
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            deferred.setResult(toolResponse(id, deny(
                    "create_task failed: " + e.getMessage())));
        }
    }

    /**
     * Handles {@code reply_review_thread}: parks the active task at
     * AWAITING_REVIEW with the proposed reply body + root comment id
     * captured in the notification payload. The actual GitHub call
     * lands in PublishService when the user clicks Approve — same
     * propose-then-confirm pattern as post_comment.
     */
    private void handleReplyReviewThread(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        long rootCommentId = args.path("root_comment_id").asLong(0L);
        String body = args.path("body").asText("");
        if (rootCommentId <= 0L) {
            deferred.setResult(plainText(id, "root_comment_id is required"));
            return;
        }
        if (body.isBlank()) {
            deferred.setResult(plainText(id, "body is required"));
            return;
        }
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to reply on"));
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
        payload.put("action", "reply_review_thread");
        payload.put("rootCommentId", rootCommentId);
        payload.put("body", body);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:reply_review_thread");
        emitAwaitingReviewNotification(threadId, active.get().id(), payload, "mcp:reply_review_thread");

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will review the reply and "
                        + "approve, edit, or discard from the thread."));
    }

    /**
     * Handles {@code approve_pr}: parks an APPROVE review on the
     * active task's linked PR. The user reviews the approval body
     * in the gate; the GitHub call happens in PublishService on
     * Approve.
     */
    private void handleApprovePr(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to approve"));
            return;
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no PR linked to the active task — set linked_pr_number first"));
            return;
        }
        String body = args.path("body").asText("");

        parkActiveTaskAtAwaitingReview(active.get());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "approve_pr");
        payload.put("body", body);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:approve_pr");
        emitAwaitingReviewNotification(threadId, active.get().id(), payload, "mcp:approve_pr");

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will review the approval and "
                        + "approve, edit, or discard from the thread."));
    }

    /**
     * Handles {@code merge_pr}: parks a merge request with the
     * chosen strategy. The user picks Approve / Edit (which can
     * change the body but not the strategy today) / Discard; on
     * Approve the server fires the GitHub merge endpoint.
     */
    private void handleMergePr(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to merge"));
            return;
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no PR linked to the active task — set linked_pr_number first"));
            return;
        }
        String strategy = normaliseMergeStrategy(args.path("strategy").asText(""));

        parkActiveTaskAtAwaitingReview(active.get());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "merge_pr");
        payload.put("strategy", strategy);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:merge_pr");
        emitAwaitingReviewNotification(threadId, active.get().id(), payload, "mcp:merge_pr");

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW (merge_pr, strategy=" + strategy + "). "
                        + "The user will approve, edit, or discard from the thread."));
    }

    private static String normaliseMergeStrategy(String raw)
    {
        if (raw == null) {
            return "squash";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "merge" -> "merge";
            case "rebase" -> "rebase";
            default -> "squash";
        };
    }

    /** Serialise a JSON node to its compact string form; falls back
     *  to an error envelope on the (vanishingly unlikely) Jackson
     *  failure so the deferred result always carries something. */
    private String toJsonString(ObjectNode out)
    {
        try {
            return mapper.writeValueAsString(out);
        }
        catch (JsonProcessingException e) {
            return "{\"error\":\"serialisation failed: " + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
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
