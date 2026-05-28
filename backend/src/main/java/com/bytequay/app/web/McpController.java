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
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.local.TestRunnerDetector;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.ParkedProposalService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentTool;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.tools.PermissionResolver;
import com.bytequay.app.service.tools.RuntimeToolInvocation;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.SkillTools;
import com.bytequay.app.service.tools.ToolCall;
import com.bytequay.app.service.tools.ToolContext;
import com.bytequay.app.service.tools.ToolOutcome;
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

    /** Parked publisher — posts a line-anchored review comment on
     *  the active task's linked PR. */
    private static final String CREATE_REVIEW_COMMENT_TOOL = "create_review_comment";

    /** Parked publisher — rewrites the active task's linked PR
     *  description (title remains unchanged). */
    private static final String UPDATE_PR_BODY_TOOL = "update_pr_body";

    /** Parked publisher — requests a reviewer on the active task's
     *  linked PR. */
    private static final String REQUEST_REVIEWER_TOOL = "request_reviewer";

    /** Parked publisher — posts a comment on an issue in the active
     *  task's repo. */
    private static final String COMMENT_ON_ISSUE_TOOL = "comment_on_issue";

    /** Parked publisher — flips an issue between 'open' and 'closed'
     *  in the active task's repo. */
    private static final String SET_ISSUE_STATE_TOOL = "set_issue_state";

    /** Parked publisher — creates a new pull request in the active
     *  task's repo from a head branch into a base branch. */
    private static final String OPEN_PR_TOOL = "open_pr";

    /** Parked publisher — submits a full review on the active task's
     *  linked PR with summary body + inline comments at once. */
    private static final String PUBLISH_REVIEW_TOOL = "publish_review";

    /** Orchestration tool — parks the active task at AWAITING_REVIEW
     *  and cuts a sibling task on the same thread. Same flow the
     *  user's "Next →" button drives. */
    private static final String NEXT_TASK_TOOL = "next_task";

    /** Orchestration tool — ships the active task. Parked at
     *  AWAITING_REVIEW first so the user reviews the proposed ship
     *  before the push + PR open + close fires. */
    private static final String SHIP_TASK_TOOL = "ship_task";

    /** Escape-hatch tool — runs a bounded shell command in the
     *  active task's worktreePath, gated on each call via the user-
     *  approval prompt. See {@link ShellRunner} for the policy. */
    private static final String RUN_SHELL_TOOL = "run_shell";

    /** Test-runner tool — auto-detects the worktree's ecosystem
     *  (maven / gradle / npm / cargo / go / pytest) and runs the
     *  canonical "verify" command. Read-only AUTO. */
    private static final String RUN_CHECKS_TOOL = "run_checks";

    /** Wall-clock cap on the run_checks process — 5 minutes. Most
     *  unit / spot-check suites fit comfortably; longer end-to-end
     *  suites belong to the workspace work-model when it lands. */
    private static final long RUN_CHECKS_TIMEOUT_SECONDS = 300L;

    /** Output cap on run_checks — same 256 KB as run_shell so the
     *  result stays inside a sensible model context budget. */
    private static final int RUN_CHECKS_OUTPUT_BYTES = ShellRunner.MAX_OUTPUT_BYTES;

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
    private final ParkedProposalService parkedProposals;
    private final WatchedRepoStore watchedRepos;
    private final GitRunner git;
    private final ObjectMapper mapper;
    private final AgentToolRegistry registry;
    private final PermissionResolver permissions;
    private final SkillTools skillTools;
    private final ShellRunner shellRunner;
    private final TestRunnerDetector testRunnerDetector;

    public McpController(
            ThreadService threads,
            TaskStore taskStore,
            ThreadCheckpointStore checkpoints,
            McpPermissionGate gate,
            ParkedProposalService parkedProposals,
            WatchedRepoStore watchedRepos,
            GitRunner git,
            ObjectMapper mapper,
            AgentToolRegistry registry,
            PermissionResolver permissions,
            SkillTools skillTools,
            ShellRunner shellRunner,
            TestRunnerDetector testRunnerDetector)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.checkpoints = requireNonNull(checkpoints, "checkpoints is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.parkedProposals = requireNonNull(parkedProposals, "parkedProposals is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.git = requireNonNull(git, "git is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.permissions = requireNonNull(permissions, "permissions is null");
        this.skillTools = requireNonNull(skillTools, "skillTools is null");
        this.shellRunner = requireNonNull(shellRunner, "shellRunner is null");
        this.testRunnerDetector = requireNonNull(testRunnerDetector, "testRunnerDetector is null");
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
    @SuppressWarnings("unused")
    public void declareApprovalPrompt(ApprovalPromptArgs args)
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
    @SuppressWarnings("unused")
    public void declareRequestReview(RequestReviewArgs args)
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
    @SuppressWarnings("unused")
    public void declarePostComment(PostCommentArgs args)
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
    @SuppressWarnings("unused")
    public void declarePush(PushArgs args)
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
    @SuppressWarnings("unused")
    public void declareRecallThread(RecallThreadArgs args)
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
    @SuppressWarnings("unused")
    public void declareListTools(ListToolsArgs args)
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
    @SuppressWarnings("unused")
    public void declareListSkills(ListSkillsArgs args)
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
    @SuppressWarnings("unused")
    public void declareLoadSkill(LoadSkillArgs args)
    {
        // Dispatched via handleToolCall.
    }

    // read_task / read_pr / read_workspace_memory now declare + handle
    // themselves on AgentToolHandlers; they dispatch through
    // registry.invoke in handleToolCall rather than a stub + branch here.

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
    @SuppressWarnings("unused")
    public void declareCreateTask(CreateTaskArgs args)
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
    @SuppressWarnings("unused")
    public void declareReplyReviewThread(ReplyReviewThreadArgs args)
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
    @SuppressWarnings("unused")
    public void declareApprovePr(ApprovePrArgs args)
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
    @SuppressWarnings("unused")
    public void declareMergePr(MergePrArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code create_review_comment}. */
    public record CreateReviewCommentArgs(
            @ToolParam(description = "Repo-relative file path the comment anchors to.",
                    required = true, wireName = "file_path") String filePath,
            @ToolParam(description = "Line in the file the comment anchors to (right side, "
                    + "added lines).",
                    required = true) Integer line,
            @ToolParam(description = "Markdown body of the comment.",
                    required = true) String body,
            @ToolParam(description = "Head commit SHA the comment anchors to. The agent can "
                    + "read it from the PR detail or the diff view.",
                    required = true, wireName = "commit_id") String commitId,
            @ToolParam(description = "'RIGHT' (added side, default) or 'LEFT' (deleted side).")
            String side,
            @ToolParam(description = "Optional first line of a multi-line range. When set, "
                    + "the comment spans start_line through line.",
                    wireName = "start_line") Integer startLine,
            @ToolParam(description = "Side of start_line — 'LEFT' or 'RIGHT'. Required when "
                    + "start_line is set.",
                    wireName = "start_side") String startSide) {}

    @AgentTool(
            name = CREATE_REVIEW_COMMENT_TOOL,
            description = "Post a line-anchored review comment on the active task's linked "
                    + "PR. The body + anchor are parked at AWAITING_REVIEW; on Approve the "
                    + "server fires the GitHub inline-review-comment API.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareCreateReviewComment(CreateReviewCommentArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code update_pr_body}. */
    public record UpdatePrBodyArgs(
            @ToolParam(description = "New PR description (markdown). Replaces the existing "
                    + "body wholesale — set it to the full final text, not a diff.",
                    required = true) String body) {}

    @AgentTool(
            name = UPDATE_PR_BODY_TOOL,
            description = "Rewrite the active task's linked PR description. The new body is "
                    + "parked at AWAITING_REVIEW; on Approve the server PATCHes the PR via "
                    + "the GitHub update-pull endpoint. Title is left alone.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareUpdatePrBody(UpdatePrBodyArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code request_reviewer}. */
    public record RequestReviewerArgs(
            @ToolParam(description = "GitHub login (no '@') of the user to request a review "
                    + "from. Org teams aren't supported through this tool today.",
                    required = true) String reviewer) {}

    @AgentTool(
            name = REQUEST_REVIEWER_TOOL,
            description = "Request a reviewer on the active task's linked PR. The request "
                    + "is parked at AWAITING_REVIEW; on Approve the server adds the login "
                    + "via the GitHub request-review endpoint.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareRequestReviewer(RequestReviewerArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code comment_on_issue}. */
    public record CommentOnIssueArgs(
            @ToolParam(description = "Issue number to comment on. The repo is the active "
                    + "task's repo (the same one the task's worktree was cut from).",
                    required = true, wireName = "issue_number") Integer issueNumber,
            @ToolParam(description = "Markdown body of the comment.",
                    required = true) String body) {}

    @AgentTool(
            name = COMMENT_ON_ISSUE_TOOL,
            description = "Post a comment on an issue in the active task's repo. The body "
                    + "is parked at AWAITING_REVIEW; on Approve the server posts via the "
                    + "GitHub issue-comment endpoint.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareCommentOnIssue(CommentOnIssueArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code set_issue_state}. */
    public record SetIssueStateArgs(
            @ToolParam(description = "Issue number to flip. The repo is the active task's repo.",
                    required = true, wireName = "issue_number") Integer issueNumber,
            @ToolParam(description = "Target state — 'open' or 'closed'.",
                    required = true) String state) {}

    @AgentTool(
            name = SET_ISSUE_STATE_TOOL,
            description = "Flip an issue between 'open' and 'closed' in the active task's "
                    + "repo. The proposed flip is parked at AWAITING_REVIEW; on Approve the "
                    + "server PATCHes via the GitHub issue endpoint.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareSetIssueState(SetIssueStateArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code open_pr}. */
    public record OpenPrArgs(
            @ToolParam(description = "Pull request title (≤ 256 chars).",
                    required = true) String title,
            @ToolParam(description = "Head branch — the one carrying the changes. "
                    + "Defaults to the active task's branch when omitted.")
            String head,
            @ToolParam(description = "Base branch the PR targets — e.g. 'main'. "
                    + "Defaults to the active task's baseBranch when omitted.")
            String base,
            @ToolParam(description = "Markdown PR description.")
            String body,
            @ToolParam(description = "When true, opens the PR as a draft.")
            Boolean draft) {}

    @AgentTool(
            name = OPEN_PR_TOOL,
            description = "Open a new pull request in the active task's repo. The proposed "
                    + "PR is parked at AWAITING_REVIEW with title + body + head/base + draft "
                    + "flag captured in the notification; on Approve the server fires the "
                    + "GitHub create-pull-request API. Use after push has landed the head "
                    + "branch on the remote.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareOpenPr(OpenPrArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code publish_review}. */
    public record PublishReviewArgs(
            @ToolParam(description = "Review event — 'APPROVE', 'REQUEST_CHANGES', or "
                    + "'COMMENT'. Defaults to 'COMMENT'.")
            String event,
            @ToolParam(description = "Optional review summary (markdown). Shown alongside "
                    + "the inline comments on GitHub.")
            String body,
            @ToolParam(description = "Array of inline comments to attach to the review. "
                    + "Each: {file_path, line, body, side?, start_line?, start_side?}.")
            JsonNode comments) {}

    @AgentTool(
            name = PUBLISH_REVIEW_TOOL,
            description = "Submit a full review on the active task's linked PR — summary "
                    + "body + a batch of line-anchored inline comments + an event "
                    + "(APPROVE / REQUEST_CHANGES / COMMENT). The whole bundle parks at "
                    + "AWAITING_REVIEW; on Approve the server fires one createReview call "
                    + "that posts the body + every inline comment + the verdict in a "
                    + "single GitHub round-trip.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declarePublishReview(PublishReviewArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code next_task}. */
    public record NextTaskArgs(
            @ToolParam(description = "Title for the new task. Optional — falls back to "
                    + "'task N+1' when omitted.",
                    wireName = "next_title") String nextTitle,
            @ToolParam(description = "Base mode for the new task's branch — 'main' (cut "
                    + "from the per-repo merge target, default) or 'stacked' (chain on "
                    + "the current task's branch).",
                    wireName = "base_mode") String baseMode) {}

    @AgentTool(
            name = NEXT_TASK_TOOL,
            description = "Propose advancing from the current task. The user reviews the "
                    + "current diff and must approve before the server pushes the branch, "
                    + "opens or finds the PR, parks this task, and creates the next worktree.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareNextTask(NextTaskArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code ship_task}. Same shape as next_task —
     *  the difference is the user's approval gate. */
    public record ShipTaskArgs(
            @ToolParam(description = "Title for the next task. Optional — the new task is "
                    + "created on Approve and takes 'task N+1' when omitted.",
                    wireName = "next_title") String nextTitle,
            @ToolParam(description = "Base mode for the next task's branch — 'main' "
                    + "(default) or 'stacked' to chain on this task's branch.",
                    wireName = "base_mode") String baseMode) {}

    @AgentTool(
            name = SHIP_TASK_TOOL,
            description = "Ship the active task: park the proposal at AWAITING_REVIEW so "
                    + "the user reviews the diff + PR state, then on Approve the server "
                    + "runs the full ship-and-continue flow (push, open or update PR, "
                    + "mark the task COMPLETED, cut the next task). Use when the unit of "
                    + "work is genuinely done and ready for human sign-off.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareShipTask(ShipTaskArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code run_shell}. */
    public record RunShellArgs(
            @ToolParam(description = "Plain argv command line — no pipes, redirects, "
                    + "command substitution, or background forks. The runner refuses any "
                    + "of | & ; > < ` $(. Use task tools (request_review / ship_task) for "
                    + "anything bigger than a quick probe.",
                    required = true) String command) {}

    @AgentTool(
            name = RUN_SHELL_TOOL,
            description = "Run a bounded shell command in the active task's worktree. "
                    + "Each call surfaces a permission prompt to the user — no command "
                    + "runs without an explicit click. Policy: 60-second timeout, 256 KB "
                    + "output cap, plain argv only, no shell operators. Use as an escape "
                    + "hatch for ad-hoc probes; prefer the test runner / ship_task / "
                    + "request_review for longer flows.",
            security = SecurityType.CODE_EXEC,
            gating = Gating.GATED,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareRunShell(RunShellArgs args)
    {
        // Dispatched via handleToolCall.
    }

    /** Args record for {@code run_checks} — no args today. */
    public record RunChecksArgs() {}

    @AgentTool(
            name = RUN_CHECKS_TOOL,
            description = "Run the active task's test suite. Auto-detects the ecosystem "
                    + "from the worktree (maven / gradle / npm / cargo / go / pytest) and "
                    + "runs the canonical verify command. AUTO — no user prompt, since "
                    + "tests are read-only and the test runner is a workspace-level trust "
                    + "boundary. Returns {ran, ecosystem, command, exitCode, output, "
                    + "truncated}. 5-minute timeout, 256 KB output cap.",
            security = SecurityType.CODE_EXEC,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    @SuppressWarnings("unused")
    public void declareRunChecks(RunChecksArgs args)
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
        // Tools migrated onto the registry-dispatch path bind their
        // args and run through the shared handler, returning a lane-
        // neutral outcome we adapt to the MCP wire. Tools still on the
        // hand-coded branches below return an empty Optional and fall
        // through. Permission / role gating already happened above — the
        // registry trusts the call is authorised.
        Optional<ToolOutcome> outcome = registry.invoke(
                name, new ToolCall(threadId, params.path("arguments"), role));
        if (outcome.isPresent()) {
            deferred.setResult(adaptOutcome(id, outcome.get()));
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
        if (CREATE_REVIEW_COMMENT_TOOL.equals(name)) {
            handleCreateReviewComment(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (UPDATE_PR_BODY_TOOL.equals(name)) {
            handleUpdatePrBody(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (REQUEST_REVIEWER_TOOL.equals(name)) {
            handleRequestReviewer(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (COMMENT_ON_ISSUE_TOOL.equals(name)) {
            handleCommentOnIssue(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (SET_ISSUE_STATE_TOOL.equals(name)) {
            handleSetIssueState(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (OPEN_PR_TOOL.equals(name)) {
            handleOpenPr(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (PUBLISH_REVIEW_TOOL.equals(name)) {
            handlePublishReview(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (NEXT_TASK_TOOL.equals(name)) {
            handleNextTask(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (SHIP_TASK_TOOL.equals(name)) {
            handleShipTask(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (RUN_SHELL_TOOL.equals(name)) {
            handleRunShell(threadId, id, params.path("arguments"), deferred);
            return;
        }
        if (RUN_CHECKS_TOOL.equals(name)) {
            handleRunChecks(threadId, id, deferred);
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
     *   * the thread's active task and its AWAITING_REVIEW
     *     notification are written atomically,
     *   * the response is plain text — no allow/deny envelope, since
     *     the agent isn't asking for permission, it's announcing it
     *     is done.
     */
    /**
     * Persists a parked proposal, returning {@code true} on success.
     * The park is transactional: if the notification can't be written
     * the task is rolled back too (never left at AWAITING_REVIEW without
     * its actionable row). On failure we resolve the agent's tool call
     * with a retryable soft message rather than letting the write error
     * bubble into the central handler as a {@code -32603} protocol error
     * that the agent reads as a hard tool failure. A retry is safe
     * precisely because the failed transaction left no partial state.
     */
    private boolean tryPark(Task task, Map<String, Object> payload, JsonNode id,
            DeferredResult<JsonNode> deferred)
    {
        try {
            parkedProposals.park(task, payload);
            return true;
        }
        catch (RuntimeException e) {
            log.warn("failed to park task {} for review ({}): {}",
                    task.id(), payload.get("action"), e.getMessage());
            deferred.setResult(plainText(id,
                    "Could not save the review notification (" + e.getMessage()
                            + "). The task was not parked — please retry."));
            return false;
        }
    }

    private void handleRequestReview(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        String summary = args.path("summary").asText("");
        String draftReply = args.path("draft_reply").asText("");
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to request review for"));
            return;
        }
        Task task = active.get();
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            deferred.setResult(plainText(id,
                    "the active task has no worktree — no diff is available for review"));
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "request_review");
        payload.put("summary", summary);
        if (!draftReply.isEmpty()) {
            payload.put("draftReply", draftReply);
        }
        payload.put("branch", task.branchName());
        payload.put("baseBranch", task.baseBranch());
        payload.put("worktreePath", task.worktreePath());
        attachPushDiffToPayload(payload, Path.of(task.worktreePath()), task);
        payload.put("source", "mcp:request_review");
        if (!tryPark(task, payload, id, deferred)) {
            return;
        }
        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will see a notification "
                        + "and can accept or discard it from the thread."));
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "post_comment");
        payload.put("body", body);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:post_comment");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "push");
        payload.put("branch", task.branchName());
        payload.put("baseBranch", task.baseBranch());
        payload.put("worktreePath", task.worktreePath());
        attachPushDiffToPayload(payload, worktree, task);
        payload.put("source", "mcp:push");
        if (!tryPark(task, payload, id, deferred)) {
            return;
        }

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
        try {
            String base = chooseDiffBase(worktree, task);
            if (base == null) {
                payload.put("diff", null);
                payload.put("diffError", "no base ref available to diff against; "
                        + "task.baseBranch is " + (task.baseBranch() == null ? "null" : "not on origin yet"));
                return;
            }
            payload.put("diffBase", base);
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
            log.warn("push diff preview for {} rejected: {}", worktree, e.getMessage());
            payload.put("diff", null);
            payload.put("diffError", "git diff preview rejected: " + e.getMessage());
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
        catch (IOException | InterruptedException | RuntimeException e) {
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
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "approve_pr");
        payload.put("body", body);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:approve_pr");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "merge_pr");
        payload.put("strategy", strategy);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:merge_pr");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW (merge_pr, strategy=" + strategy + "). "
                        + "The user will approve, edit, or discard from the thread."));
    }

    /**
     * Handles {@code create_review_comment}: parks a line-anchored
     * review comment on the active task's linked PR. The publisher
     * branch fires createInlineReviewComment on Approve.
     */
    private void handleCreateReviewComment(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
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
        String filePath = args.path("file_path").asText("");
        String body = args.path("body").asText("");
        String commitId = args.path("commit_id").asText("");
        int line = args.path("line").asInt(0);
        if (filePath.isBlank() || body.isBlank() || commitId.isBlank() || line <= 0) {
            deferred.setResult(plainText(id,
                    "file_path, line, body, commit_id are required"));
            return;
        }
        String side = args.path("side").asText("RIGHT");
        Integer startLine = args.path("start_line").isNumber() ? args.path("start_line").asInt() : null;
        String startSide = args.path("start_side").asText("");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "create_review_comment");
        payload.put("body", body);
        payload.put("filePath", filePath);
        payload.put("line", line);
        payload.put("side", side);
        payload.put("commitId", commitId);
        if (startLine != null) {
            payload.put("startLine", startLine);
        }
        if (!startSide.isBlank()) {
            payload.put("startSide", startSide);
        }
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:create_review_comment");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will review the inline comment and "
                        + "approve, edit, or discard from the thread."));
    }

    /**
     * Handles {@code update_pr_body}: parks a body-rewrite on the
     * active task's linked PR. PublishService PATCHes the PR on
     * Approve.
     */
    private void handleUpdatePrBody(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to update"));
            return;
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no PR linked to the active task — set linked_pr_number first"));
            return;
        }
        String body = args.path("body").asText("");
        if (body.isBlank()) {
            deferred.setResult(plainText(id, "body is required"));
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "update_pr_body");
        payload.put("body", body);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:update_pr_body");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will review the new PR body and "
                        + "approve, edit, or discard from the thread."));
    }

    /**
     * Handles {@code request_reviewer}: parks a reviewer request on
     * the active task's linked PR. PublishService fires the GitHub
     * request-reviewers endpoint on Approve.
     */
    private void handleRequestReviewer(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to request review on"));
            return;
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no PR linked to the active task — set linked_pr_number first"));
            return;
        }
        String reviewer = args.path("reviewer").asText("").trim();
        if (reviewer.isBlank()) {
            deferred.setResult(plainText(id, "reviewer (GitHub login) is required"));
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "request_reviewer");
        payload.put("reviewer", reviewer);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:request_reviewer");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will approve or discard the reviewer "
                        + "request from the thread."));
    }

    /**
     * Handles {@code comment_on_issue}: parks a comment on an issue
     * in the active task's repo. PublishService fires the GitHub
     * issue-comment endpoint on Approve.
     */
    private void handleCommentOnIssue(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to comment on"));
            return;
        }
        Optional<WatchedRepo> repo = resolveRepoFromTask(active.get());
        if (repo.isEmpty()) {
            deferred.setResult(plainText(id,
                    "active task's workingDir doesn't match any watched repo"));
            return;
        }
        int issueNumber = args.path("issue_number").asInt(0);
        String body = args.path("body").asText("");
        if (issueNumber <= 0) {
            deferred.setResult(plainText(id, "issue_number is required"));
            return;
        }
        if (body.isBlank()) {
            deferred.setResult(plainText(id, "body is required"));
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "comment_on_issue");
        payload.put("body", body);
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("owner", repo.get().owner());
        issue.put("repo", repo.get().repo());
        issue.put("number", issueNumber);
        payload.put("issue", issue);
        payload.put("source", "mcp:comment_on_issue");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW. The user will review the issue comment and "
                        + "approve, edit, or discard from the thread."));
    }

    /**
     * Handles {@code set_issue_state}: parks an open/closed flip on
     * an issue in the active task's repo.
     */
    private void handleSetIssueState(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to flip"));
            return;
        }
        Optional<WatchedRepo> repo = resolveRepoFromTask(active.get());
        if (repo.isEmpty()) {
            deferred.setResult(plainText(id,
                    "active task's workingDir doesn't match any watched repo"));
            return;
        }
        int issueNumber = args.path("issue_number").asInt(0);
        String state = args.path("state").asText("").trim().toLowerCase(Locale.ROOT);
        if (issueNumber <= 0) {
            deferred.setResult(plainText(id, "issue_number is required"));
            return;
        }
        if (!"open".equals(state) && !"closed".equals(state)) {
            deferred.setResult(plainText(id, "state must be 'open' or 'closed'"));
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "set_issue_state");
        payload.put("state", state);
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("owner", repo.get().owner());
        issue.put("repo", repo.get().repo());
        issue.put("number", issueNumber);
        payload.put("issue", issue);
        payload.put("source", "mcp:set_issue_state");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW (set_issue_state, " + state + "). "
                        + "The user will approve or discard from the thread."));
    }

    /**
     * Handles {@code open_pr}: parks a new pull request request. The
     * publisher branch fires createPullRequest on Approve. Head and
     * base default to the active task's branch fields when omitted.
     */
    private void handleOpenPr(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to open a PR for"));
            return;
        }
        Optional<WatchedRepo> repo = resolveRepoFromTask(active.get());
        if (repo.isEmpty()) {
            deferred.setResult(plainText(id,
                    "active task's workingDir doesn't match any watched repo"));
            return;
        }
        String title = args.path("title").asText("").trim();
        if (title.isBlank()) {
            deferred.setResult(plainText(id, "title is required"));
            return;
        }
        String head = args.path("head").asText("").trim();
        if (head.isBlank()) {
            head = active.get().branchName() == null ? "" : active.get().branchName();
        }
        String base = args.path("base").asText("").trim();
        if (base.isBlank()) {
            base = active.get().baseBranch() == null ? "main" : active.get().baseBranch();
        }
        if (head.isBlank()) {
            deferred.setResult(plainText(id,
                    "head branch could not be resolved — pass head explicitly"));
            return;
        }
        String body = args.path("body").asText("");
        boolean draft = args.path("draft").asBoolean(false);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "open_pr");
        payload.put("title", title);
        payload.put("head", head);
        payload.put("base", base);
        payload.put("body", body);
        payload.put("draft", draft);
        Map<String, Object> repoRef = new LinkedHashMap<>();
        repoRef.put("owner", repo.get().owner());
        repoRef.put("repo", repo.get().repo());
        payload.put("repo", repoRef);
        payload.put("source", "mcp:open_pr");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW (open_pr · " + head + " → " + base + "). "
                        + "The user will review the PR title/body and approve or discard "
                        + "from the thread."));
    }

    /**
     * Handles {@code publish_review}: parks a multi-comment review.
     * The payload captures event + body + comments[]; PublishService
     * marshals these into a single createReview call on Approve.
     */
    private void handlePublishReview(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to review"));
            return;
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no PR linked to the active task — set linked_pr_number first"));
            return;
        }
        String event = args.path("event").asText("COMMENT").trim().toUpperCase(Locale.ROOT);
        if (!event.equals("APPROVE") && !event.equals("REQUEST_CHANGES") && !event.equals("COMMENT")) {
            event = "COMMENT";
        }
        String body = args.path("body").asText("");
        JsonNode comments = args.path("comments");
        if (!comments.isMissingNode() && !comments.isNull() && !comments.isArray()) {
            deferred.setResult(plainText(id, "comments must be an array"));
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "publish_review");
        payload.put("event", event);
        payload.put("body", body);
        // Re-serialise comments through Jackson so the stored array
        // has a stable shape regardless of what raw JSON the agent
        // sent. Missing / empty → empty array.
        payload.put("comments", comments.isMissingNode() || comments.isNull()
                ? mapper.createArrayNode()
                : comments);
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", prRef.get().owner());
        pr.put("repo", prRef.get().repo());
        pr.put("number", prRef.get().number());
        payload.put("pr", pr);
        payload.put("source", "mcp:publish_review");
        if (!tryPark(active.get(), payload, id, deferred)) {
            return;
        }

        int commentCount = comments.isMissingNode() ? 0 : comments.size();
        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW (publish_review · " + event + " · "
                        + commentCount + " inline comment(s)). The user will approve, "
                        + "edit, or discard from the thread."));
    }

    /**
     * Handles {@code next_task}: parks a proposal for the user rather
     * than directly pushing and opening a PR. PublishService runs the
     * deferred advance on Approve.
     */
    private void handleNextTask(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to advance"));
            return;
        }
        Task task = active.get();
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            deferred.setResult(plainText(id,
                    "the active task has no worktree — next task needs an isolated branch"));
            return;
        }
        String nextTitle = args.path("next_title").asText("").trim();
        String baseMode = args.path("base_mode").asText("main").trim().toLowerCase(Locale.ROOT);
        if (!"main".equals(baseMode) && !"stacked".equals(baseMode)) {
            baseMode = "main";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "next_task");
        payload.put("threadId", threadId);
        payload.put("taskId", task.id());
        payload.put("branch", task.branchName());
        payload.put("baseBranch", task.baseBranch());
        payload.put("worktreePath", task.worktreePath());
        payload.put("nextTitle", nextTitle);
        payload.put("baseMode", baseMode);
        attachPushDiffToPayload(payload, Path.of(task.worktreePath()), task);
        payload.put("source", "mcp:next_task");
        if (!tryPark(task, payload, id, deferred)) {
            return;
        }

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW (next_task). The user will review the "
                        + "diff and approve or discard advancing to the next task."));
    }

    /**
     * Handles {@code ship_task}: parks a ship proposal on the active
     * task. PublishService runs the deferred shipAndContinue on
     * Approve.
     */
    private void handleShipTask(
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty()) {
            deferred.setResult(plainText(id,
                    "no active task on this thread — nothing to ship"));
            return;
        }
        Task task = active.get();
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            deferred.setResult(plainText(id,
                    "the active task has no worktree — ship needs an isolated branch"));
            return;
        }
        String nextTitle = args.path("next_title").asText("").trim();
        String baseMode = args.path("base_mode").asText("main").trim().toLowerCase(Locale.ROOT);
        if (!"main".equals(baseMode) && !"stacked".equals(baseMode)) {
            baseMode = "main";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "ship_task");
        payload.put("threadId", threadId);
        payload.put("taskId", task.id());
        payload.put("branch", task.branchName());
        payload.put("baseBranch", task.baseBranch());
        payload.put("worktreePath", task.worktreePath());
        payload.put("nextTitle", nextTitle);
        payload.put("baseMode", baseMode);
        attachPushDiffToPayload(payload, Path.of(task.worktreePath()), task);
        payload.put("source", "mcp:ship_task");
        if (!tryPark(task, payload, id, deferred)) {
            return;
        }

        deferred.setResult(plainText(id,
                "Parked at AWAITING_REVIEW (ship_task). The user will review the "
                        + "proposed ship and approve, edit, or discard from the thread."));
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
            String threadId, JsonNode id, JsonNode args, DeferredResult<JsonNode> deferred)
    {
        String command = args.path("command").asText("").trim();
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
                ObjectNode out = mapper.createObjectNode();
                out.put("ran", result.ran());
                out.put("exitCode", result.exitCode());
                out.put("truncated", result.truncated());
                out.put("output", result.output());
                if (result.error() != null) {
                    out.put("error", result.error());
                }
                deferred.setResult(plainText(id, toJsonString(out)));
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

    /**
     * Handles {@code run_checks}: auto-detects the test runner for
     * the active task's worktree and runs the canonical verify
     * command. AUTO — the test runner is a workspace-level trust
     * boundary the user already opted into when they cloned the
     * repo, so we don't gate each call.
     */
    private void handleRunChecks(String threadId, JsonNode id, DeferredResult<JsonNode> deferred)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(threadId);
        if (active.isEmpty() || active.get().worktreePath() == null
                || active.get().worktreePath().isBlank()) {
            deferred.setResult(toolResponse(id,
                    deny("run_checks requires an active task with a worktree")));
            return;
        }
        Path worktree = Path.of(active.get().worktreePath());
        Optional<TestRunnerDetector.Detected> detected = testRunnerDetector.detect(worktree);
        if (detected.isEmpty()) {
            deferred.setResult(toolResponse(id, deny(
                    "no recognised test runner in " + worktree
                            + " (looked for pom.xml / build.gradle / package.json / "
                            + "Cargo.toml / go.mod / pyproject.toml). Configure a "
                            + "workspace-level test command, or use run_shell.")));
            return;
        }
        TestRunnerDetector.Detected runner = detected.get();
        try {
            ShellRunner.Result result = shellRunner.runArgv(
                    worktree, runner.argv(), RUN_CHECKS_TIMEOUT_SECONDS, RUN_CHECKS_OUTPUT_BYTES);
            ObjectNode out = mapper.createObjectNode();
            out.put("ran", result.ran());
            out.put("ecosystem", runner.ecosystem());
            out.put("command", String.join(" ", runner.argv()));
            out.put("exitCode", result.exitCode());
            out.put("truncated", result.truncated());
            out.put("output", result.output());
            if (result.error() != null) {
                out.put("error", result.error());
            }
            deferred.setResult(plainText(id, toJsonString(out)));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deferred.setResult(toolResponse(id, deny("interrupted: " + e.getMessage())));
        }
        catch (RuntimeException e) {
            deferred.setResult(toolResponse(id, deny("run_checks failed: " + e.getMessage())));
        }
    }

    /** Resolves a watched repo from the task's workingDir by matching
     *  localClonePath. Returns empty when the task's directory isn't
     *  a known clone. */
    private Optional<WatchedRepo> resolveRepoFromTask(Task task)
    {
        if (task.workingDir() == null || task.workingDir().isBlank()) {
            return Optional.empty();
        }
        Path needle = Path.of(task.workingDir());
        for (WatchedRepo r : watchedRepos.findAll()) {
            if (r.localClonePath() != null
                    && !r.localClonePath().isBlank()
                    && Path.of(r.localClonePath()).equals(needle)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
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
