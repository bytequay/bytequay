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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.backlog.BacklogServiceImpl;
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.local.TestRunnerDetector;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.threads.WorktreeService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Shared, lane-neutral home for tool handler logic. Each
 * {@link AgentTool}-annotated method here is both the tool's
 * declaration (the registry derives its schema from the args record)
 * and its real implementation: {@link AgentToolRegistry#invoke} binds
 * the args record from the call's JSON and dispatches here, returning
 * a {@link ToolOutcome} the calling lane adapts to its transport.
 *
 * <p>This is where AUTO tools live once they come off
 * {@code McpController}'s hand-coded dispatch — pure synchronous tools
 * whose result the lane echoes back. PARKED publishers (which propose
 * a change for the user to approve) and the gate-coupled tools
 * ({@code approval_prompt}, {@code run_shell}) keep their lane-specific
 * handling in the controller, since their flow isn't a plain
 * request/response.
 */
@Component
public class AgentToolHandlers
{
    /** Enough rows for the immediately preceding trunk exchange, without
     *  turning old roadmap discussion into current task intent. */
    private static final int BACKLOG_CONTEXT_MESSAGE_LIMIT = 24;

    private static final Pattern PHASE_IDENTIFIER =
            Pattern.compile("\\bphase\\s+(\\d+)\\b");

    private static final Pattern DIRECT_TASK_APPROVAL =
            Pattern.compile("\\b(cut|start|create)\\b.*\\b(task|backlog|phase\\s+\\d+)\\b");

    private static final String BACKLOG_VERIFICATION_UNAVAILABLE =
            "The supplied backlog item could not be verified against this trunk's "
                    + "in-progress backlog items.";

    /** Hard upper bound on recall_thread's {@code limit}. */
    private static final int RECALL_THREAD_MAX_LIMIT = 20;

    /** Default recall_thread {@code limit} when none is supplied. */
    private static final int RECALL_THREAD_DEFAULT_LIMIT = 5;

    /** Per-checkpoint summary excerpt cap in the recall digest. */
    private static final int RECALL_SUMMARY_EXCERPT_CHARS = 800;

    /** Wall-clock cap on the run_checks process — 5 minutes. */
    private static final long RUN_CHECKS_TIMEOUT_SECONDS = 300L;

    /** Output cap on run_checks — same 256 KB as run_shell. */
    private static final int RUN_CHECKS_OUTPUT_BYTES = ShellRunner.MAX_OUTPUT_BYTES;

    private final TaskStore taskStore;
    private final PullRequestStore prStore;
    private final ThreadStore threadStore;
    private final WorkspaceService workspaces;
    private final AgentToolRegistry registry;
    private final SkillTools skillTools;
    private final ThreadCheckpointStore checkpoints;
    private final TestRunnerDetector testRunnerDetector;
    private final ShellRunner shellRunner;
    private final WatchedRepoStore watchedRepos;
    private final ThreadService threads;
    private final WorktreeService worktreeService;
    private final ObjectMapper mapper;
    private final BacklogServiceImpl backlog;
    private final RepoService repoService;
    private final ActiveAgentContextRegistry activeContexts;

    private static final Logger log = LoggerFactory.getLogger(AgentToolHandlers.class);

    public AgentToolHandlers(
            TaskStore taskStore,
            PullRequestStore prStore,
            ThreadStore threadStore,
            WorkspaceService workspaces,
            AgentToolRegistry registry,
            SkillTools skillTools,
            ThreadCheckpointStore checkpoints,
            TestRunnerDetector testRunnerDetector,
            ShellRunner shellRunner,
            WatchedRepoStore watchedRepos,
            ThreadService threads,
            WorktreeService worktreeService,
            ObjectMapper mapper,
            BacklogServiceImpl backlog)
    {
        this(taskStore, prStore, threadStore, workspaces, registry, skillTools,
                checkpoints, testRunnerDetector, shellRunner, watchedRepos,
                threads, worktreeService, mapper, backlog, null,
                new ActiveAgentContextRegistry());
    }

    public AgentToolHandlers(
            TaskStore taskStore,
            PullRequestStore prStore,
            ThreadStore threadStore,
            WorkspaceService workspaces,
            AgentToolRegistry registry,
            SkillTools skillTools,
            ThreadCheckpointStore checkpoints,
            TestRunnerDetector testRunnerDetector,
            ShellRunner shellRunner,
            WatchedRepoStore watchedRepos,
            ThreadService threads,
            WorktreeService worktreeService,
            ObjectMapper mapper,
            BacklogServiceImpl backlog,
            RepoService repoService)
    {
        this(taskStore, prStore, threadStore, workspaces, registry, skillTools,
                checkpoints, testRunnerDetector, shellRunner, watchedRepos,
                threads, worktreeService, mapper, backlog, repoService,
                new ActiveAgentContextRegistry());
    }

    @Autowired
    public AgentToolHandlers(
            TaskStore taskStore,
            PullRequestStore prStore,
            ThreadStore threadStore,
            WorkspaceService workspaces,
            AgentToolRegistry registry,
            SkillTools skillTools,
            ThreadCheckpointStore checkpoints,
            TestRunnerDetector testRunnerDetector,
            ShellRunner shellRunner,
            WatchedRepoStore watchedRepos,
            ThreadService threads,
            WorktreeService worktreeService,
            ObjectMapper mapper,
            BacklogServiceImpl backlog,
            RepoService repoService,
            ActiveAgentContextRegistry activeContexts)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.prStore = requireNonNull(prStore, "prStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.skillTools = requireNonNull(skillTools, "skillTools is null");
        this.checkpoints = requireNonNull(checkpoints, "checkpoints is null");
        this.testRunnerDetector = requireNonNull(testRunnerDetector, "testRunnerDetector is null");
        this.shellRunner = requireNonNull(shellRunner, "shellRunner is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.backlog = requireNonNull(backlog, "backlog is null");
        this.repoService = repoService;
        this.activeContexts = requireNonNull(
                activeContexts, "activeContexts is null");
    }

    /** Args record for {@code read_task}. */
    public record ReadTaskArgs(
            @ToolParam(description = "Task id to look up. Returns the task row as JSON or "
                    + "an error envelope when missing.",
                    required = true, wireName = "task_id") String taskId) {}

    @AgentTool(
            name = "read_task",
            description = "Read one task row by id. Returns id, threadId, seq, status, "
                    + "branchName, worktreePath, baseBranch, workingDir, prNumber, "
                    + "linkedPrNumber, linkedIssueNumber, taskType, createdAt, endedAt, "
                    + "errorMessage, name. Pure read — no GitHub call.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readTask(ReadTaskArgs args, ToolCall call)
    {
        String taskId = args.taskId();
        if (taskId == null || taskId.isBlank()) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        Optional<Task> match = taskStore.findTaskById(taskId);
        if (match.isEmpty()) {
            return ToolOutcome.Completed.error("task not found: " + taskId);
        }
        return toolOutcome(ReadTaskResult.from(match.get()));
    }

    /** Wire shape for {@code read_task}'s result. */
    public record ReadTaskResult(
            String id,
            String threadId,
            long seq,
            String status,
            String branchName,
            String worktreePath,
            String baseBranch,
            String workingDir,
            Integer prNumber,
            Integer linkedPrNumber,
            Integer linkedIssueNumber,
            String taskType,
            String origin,
            String createdAt,
            String endedAt,
            String errorMessage,
            String name)
    {
        static ReadTaskResult from(Task t)
        {
            return new ReadTaskResult(
                    t.id(),
                    t.threadId(),
                    t.seq(),
                    t.status() == null ? null : t.status().name(),
                    t.branchName(),
                    t.worktreePath(),
                    t.baseBranch(),
                    t.workingDir(),
                    t.prNumber(),
                    t.linkedPrNumber(),
                    t.linkedIssueNumber(),
                    t.taskType(),
                    t.origin(),
                    t.createdAt() == null ? null : t.createdAt().toString(),
                    t.endedAt() == null ? null : t.endedAt().toString(),
                    t.errorMessage(),
                    t.name());
        }
    }

    /** Args record for {@code read_pr}. */
    public record ReadPrArgs(
            @ToolParam(description = "owner/name string of the repo.",
                    required = true) String repo,
            @ToolParam(description = "PR number.",
                    required = true) Integer number) {}

    @AgentTool(
            name = "read_pr",
            description = "Read one pull request's row from the local cache. "
                    + "Returns id, repo, number, title, author, state, mergeable, "
                    + "headRef, baseRef, additions, deletions, commentCount, "
                    + "attentionReason, snoozedUntil, lastSyncedAt. Pure read against "
                    + "the local DB — no GitHub API call. Run the regular sync if "
                    + "you want a fresher snapshot.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readPr(ReadPrArgs args, ToolCall call)
    {
        String repo = args.repo() == null ? "" : args.repo();
        int number = args.number() == null ? 0 : args.number();
        if (repo.isBlank() || number <= 0) {
            return ToolOutcome.Completed.error("repo (owner/name) and number are required");
        }
        Optional<Long> prId = prStore.findIdByRepoAndNumber(repo, number);
        if (prId.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "PR not in local cache: " + repo + "#" + number
                            + " — run sync or add the repo to watched repos.");
        }
        Optional<PullRequest> match = prStore.findById(prId.get());
        if (match.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "PR row gone after id lookup: " + repo + "#" + number);
        }
        return toolOutcome(ReadPrResult.from(match.get()));
    }

    /** Wire shape for {@code read_pr}'s result. */
    public record ReadPrResult(
            long id,
            String repo,
            int number,
            String title,
            String author,
            String state,
            boolean draft,
            Boolean mergeable,
            String mergeableState,
            String headRef,
            int additions,
            int deletions,
            int commentCount,
            String attentionReason,
            String createdAt,
            String updatedAt,
            String closedAt,
            String mergedAt,
            String snoozedUntil)
    {
        static ReadPrResult from(PullRequest pr)
        {
            return new ReadPrResult(
                    pr.id(),
                    pr.repo(),
                    pr.number(),
                    pr.title(),
                    pr.author(),
                    pr.state(),
                    pr.draft(),
                    pr.mergeable(),
                    pr.mergeableState(),
                    pr.headRef(),
                    pr.additions(),
                    pr.deletions(),
                    pr.commentCount(),
                    pr.attentionReason() == null ? null : pr.attentionReason().name(),
                    pr.createdAt() == null ? null : pr.createdAt().toString(),
                    pr.updatedAt() == null ? null : pr.updatedAt().toString(),
                    pr.closedAt() == null ? null : pr.closedAt().toString(),
                    pr.mergedAt() == null ? null : pr.mergedAt().toString(),
                    pr.snoozedUntil() == null ? null : pr.snoozedUntil().toString());
        }
    }

    /** Args for read_issue. Repository identity is intentionally omitted:
     *  it is always derived from the calling trunk's workspace. */
    public record ReadIssueArgs(
            @ToolParam(description = "Issue number in the calling workspace's repository.",
                    required = true) Integer number) {}

    @AgentTool(
            name = "read_issue",
            description = "Fetch one issue's fresh title, body, labels, assignees, milestone, "
                    + "and comments from the sole repository owned by the calling trunk's "
                    + "workspace. The issue text is returned as data and is never pasted into "
                    + "the launch prompt.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readIssue(ReadIssueArgs args, ToolCall call)
    {
        int number = args.number() == null ? 0 : args.number();
        if (number <= 0) {
            return ToolOutcome.Completed.error("number must be positive");
        }
        if (call.threadId() == null || call.threadId().isBlank()) {
            return ToolOutcome.Completed.error("calling thread is required");
        }
        Optional<Thread> thread = threadStore.findThreadById(call.threadId());
        if (thread.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "calling thread not found: " + call.threadId());
        }
        List<WorkspaceRepo> workspaceRepos =
                workspaces.listRepos(thread.get().workspaceId());
        if (workspaceRepos.size() != 1) {
            return ToolOutcome.Completed.error(
                    "calling workspace must own exactly one repository");
        }
        if (repoService == null) {
            return ToolOutcome.Completed.error("fresh issue reader is unavailable");
        }
        String fullName = workspaceRepos.getFirst().repoFullName();
        int slash = fullName.indexOf('/');
        if (slash < 1 || slash == fullName.length() - 1) {
            return ToolOutcome.Completed.error(
                    "invalid workspace repository: " + fullName);
        }
        IssueDetail issue = repoService.getIssueDetail(
                fullName.substring(0, slash),
                fullName.substring(slash + 1),
                number);
        return toolOutcome(issue);
    }

    /** Args record for {@code read_workspace_memory} — no args; the
     *  workspace is derived from the thread's owning row. */
    public record ReadWorkspaceMemoryArgs() {}

    @AgentTool(
            name = "read_workspace_memory",
            description = "Read the active workspace's memory_md (the distilled brain — "
                    + "architecture decisions, conventions, blockers). Returns the raw "
                    + "markdown body so the agent can quote it or use it as context "
                    + "for the current turn.",
            security = SecurityType.MEMORY_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readWorkspaceMemory(ReadWorkspaceMemoryArgs args, ToolCall call)
    {
        Optional<Thread> threadOpt = threadStore.findThreadById(call.threadId());
        if (threadOpt.isEmpty()) {
            return ToolOutcome.Completed.error("thread not found: " + call.threadId());
        }
        String workspaceId = threadOpt.get().workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            return ToolOutcome.Completed.error("thread has no workspace bound");
        }
        try {
            String body = workspaces.getMemory(workspaceId);
            return toolOutcome(new WorkspaceMemory(workspaceId, body == null ? "" : body));
        }
        catch (RuntimeException e) {
            return ToolOutcome.Completed.error(
                    "could not read memory for workspace " + workspaceId + ": " + e.getMessage());
        }
    }

    /** Wire shape for {@code read_workspace_memory}'s result. */
    public record WorkspaceMemory(String workspaceId, String memoryMd) {}

    /** Args record for {@code read_current_repository} — no args; the repo
     *  is derived from the calling thread's workspace. */
    public record ReadCurrentRepositoryArgs() {}

    @AgentTool(
            name = "read_current_repository",
            description = "Read the owner/name slug of the repository backing this trunk's "
                    + "current planning checkout. Returns only sanitized workspace metadata; "
                    + "it does not read or expose .git/config, remote URLs, credentials, or "
                    + "the local clone path. Use this before create_task instead of inspecting "
                    + "Git configuration.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = AgentRole.TRUNK)
    public ToolOutcome readCurrentRepository(ReadCurrentRepositoryArgs args, ToolCall call)
    {
        Optional<Thread> thread = threadStore.findThreadById(call.threadId());
        if (thread.isEmpty()) {
            return ToolOutcome.Completed.error("thread not found: " + call.threadId());
        }
        return resolveTrunkRepo(thread.get().workspaceId())
                .map(repo -> toolOutcome(new CurrentRepository(repo.fullName())))
                .orElseGet(() -> ToolOutcome.Completed.error(
                        "no watched repository with a local clone is linked to this workspace"));
    }

    /** Sanitized wire shape for {@code read_current_repository}. */
    public record CurrentRepository(String repo) {}

    /** Args record for {@code sync_repo} — no args; the workspace/clone is
     *  derived from the calling thread. */
    public record SyncRepoArgs() {}

    @AgentTool(
            name = "sync_repo",
            description = "Fetch the latest base branch and refresh the trunk's read-only "
                    + "planning worktree to it — upstream/master for a fork, origin's default "
                    + "branch for a direct clone — so your code search reflects the current "
                    + "base. Each turn already starts from the latest fetched base; call this "
                    + "only when you need to re-sync mid-turn (e.g. a PR just merged and you "
                    + "must see it before finishing this turn).",
            security = SecurityType.GIT_LOCAL,
            gating = Gating.AUTO,
            roles = AgentRole.TRUNK)
    public ToolOutcome syncRepo(SyncRepoArgs args, ToolCall call)
    {
        Optional<Thread> threadOpt = threadStore.findThreadById(call.threadId());
        if (threadOpt.isEmpty()) {
            return ToolOutcome.Completed.error("thread not found: " + call.threadId());
        }
        Optional<String> cloneRoot = resolveTrunkRepo(threadOpt.get().workspaceId())
                .map(WatchedRepo::localClonePath);
        if (cloneRoot.isEmpty()) {
            return ToolOutcome.Completed.ok(
                    "No local clone is linked to this workspace — nothing to sync.");
        }
        Path repoRoot = Path.of(cloneRoot.get()).toAbsolutePath().normalize();
        return worktreeService.refreshPlanningWorktree(repoRoot, call.threadId())
                .map(sync -> {
                    threadStore.setPlanningSnapshot(call.threadId(),
                            new ThreadStore.PlanningSnapshot(repoRoot.toString(), sync.baseSha()));
                    return ToolOutcome.Completed.ok(
                            "Synced the planning base to " + sync.baseRef()
                                    + " at " + sync.baseSha() + ".");
                })
                .orElseGet(() -> ToolOutcome.Completed.ok(
                        "Could not sync the base (git unavailable or no resolvable base ref); "
                                + "planning will use the last-known checkout."));
    }

    /** Repo the trunk plans in for {@code workspaceId}. Local tools are
     * deliberately unavailable when the workspace clone has gone missing. */
    private Optional<WatchedRepo> resolveTrunkRepo(String workspaceId)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            return Optional.empty();
        }
        List<String> repos = workspaces.listRepos(workspaceId).stream()
                .map(r -> r.repoFullName())
                .toList();
        if (repos.size() != 1) {
            return Optional.empty();
        }
        return watchedRepos.findAll().stream()
                .filter(wr -> repos.getFirst().equalsIgnoreCase(wr.fullName()))
                .filter(wr -> localCloneExists(wr.localClonePath()))
                .findFirst();
    }

    private static boolean localCloneExists(String path)
    {
        try {
            return path != null && !path.isBlank() && Files.isDirectory(Path.of(path));
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Args record for the diagnostic tool catalog query — no args. */
    public record ListToolsArgs() {}

    // Deliberately not an @AgentTool. ByteQuay resolves the bounded tool set
    // before a turn, so model-driven catalog discovery would bypass that
    // decision. The handler remains callable by diagnostics and tests.
    public ToolOutcome listTools(ListToolsArgs args, ToolCall call)
    {
        List<ToolCatalogEntry> entries = registry.visibleTo(call.role()).stream()
                .map(spec -> new ToolCatalogEntry(
                        spec.name(),
                        spec.description(),
                        spec.gating().name().toLowerCase(Locale.ROOT),
                        spec.security().name().toLowerCase(Locale.ROOT)))
                .toList();
        return toolOutcome(entries);
    }

    /** Wire shape for one {@code list_tools} catalog entry. */
    public record ToolCatalogEntry(String name, String description, String gating, String security) {}

    /** Args record for the diagnostic skill catalog query. */
    public record ListSkillsArgs(
            @ToolParam(description = "Optional scope filter — one of global, repo, thread. "
                    + "Omit to see all skills visible to this thread.") String scope,
            @ToolParam(description = "Optional substring match against the trigger description. "
                    + "Case-insensitive.") String query) {}

    // Deliberately not an @AgentTool; skill selection belongs to ByteQuay's
    // context compiler, not to the provider model.
    public ToolOutcome listSkills(ListSkillsArgs args, ToolCall call)
    {
        return skillOutcome(skillTools.listSkills(args.scope(), args.query(), skillContext(call)));
    }

    /** Args record for the diagnostic skill body query. */
    public record LoadSkillArgs(
            @ToolParam(description = "Unique skill name from a prior list_skills entry.",
                    required = true) String name) {}

    // Deliberately not an @AgentTool; retained for UI/diagnostic callers.
    public ToolOutcome loadSkill(LoadSkillArgs args, ToolCall call)
    {
        return skillOutcome(skillTools.loadSkill(args.name()));
    }

    private static ToolContext skillContext(ToolCall call)
    {
        return new ToolContext(
                ImmutableSet.of(),
                Optional.of(call.threadId()),
                Optional.of(call.role().name().toLowerCase(Locale.ROOT)));
    }

    /** Adapt a {@link RuntimeToolInvocation} (an Object payload + an
     *  error flag) to a {@link ToolOutcome.Completed} carrying the
     *  serialised JSON. This is the single place tool results are
     *  serialised — handlers stay free of JSON plumbing, and the bytes
     *  the model sees come from one mapper. */
    private ToolOutcome skillOutcome(RuntimeToolInvocation out)
    {
        String json;
        try {
            json = mapper.writeValueAsString(out.payload());
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to serialise skill tool payload: " + out.payload(), e);
        }
        return out.isError()
                ? ToolOutcome.Completed.error(json)
                : ToolOutcome.Completed.ok(json);
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
            name = "recall_thread",
            description = "Search prior threads' Overall summaries for prior context. "
                    + "Use this before answering an unfamiliar question to see if "
                    + "a previous thread already worked through the same problem. "
                    + "Returns title + summary excerpt + bullet titles for each "
                    + "matching thread; never mutates state.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome recallThread(RecallThreadArgs args, ToolCall call)
    {
        String query = args.query() == null ? "" : args.query().trim();
        int requestedLimit = args.limit() == null ? RECALL_THREAD_DEFAULT_LIMIT : args.limit();
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
            if (cp.threadId().equals(call.threadId())) {
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
        return ToolOutcome.Completed.ok(renderRecallResult(query, matches));
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
            String title = threadStore.findThreadById(cp.threadId())
                    .map(Thread::title)
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

    /** Args record for {@code run_checks} — no args today. */
    public record RunChecksArgs() {}

    @AgentTool(
            name = "run_checks",
            description = "Run the active task's test suite. Auto-detects the ecosystem "
                    + "from the worktree (maven / gradle / npm / cargo / go / pytest) and "
                    + "runs the canonical verify command. AUTO — no user prompt, since "
                    + "tests are read-only and the test runner is a workspace-level trust "
                    + "boundary. Returns {ran, ecosystem, command, exitCode, output, "
                    + "truncated}. 5-minute timeout, 256 KB output cap.",
            security = SecurityType.CODE_EXEC,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome runChecks(RunChecksArgs args, ToolCall call)
    {
        String agentKey = call.runtimeAgentKey();
        Optional<ActiveAgentContextRegistry.TypedOwner> typedOwner =
                activeContexts.findTypedOwner(call.threadId(), agentKey);
        Optional<String> typedWorktree = typedOwner
                .flatMap(ignored -> activeContexts.findWorktreePath(
                        call.threadId(), agentKey));
        Optional<String> legacyWorktree = typedOwner.isPresent()
                ? Optional.empty()
                : call.scope() == ThreadScope.TRUNK
                    ? Optional.empty()
                    : taskStore.findTaskById(call.requireTaskId())
                            .map(Task::worktreePath);
        String path = typedWorktree.or(() -> legacyWorktree)
                .filter(value -> !value.isBlank())
                .orElse(null);
        if (path == null) {
            return ToolOutcome.Completed.error("run_checks requires a task with a worktree");
        }
        Path worktree = Path.of(path);
        Optional<TestRunnerDetector.Detected> detected = testRunnerDetector.detect(worktree);
        if (detected.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "no recognised test runner in " + worktree
                            + " (looked for pom.xml / build.gradle / package.json / "
                            + "Cargo.toml / go.mod / pyproject.toml). Configure a "
                            + "workspace-level test command, or use run_shell.");
        }
        TestRunnerDetector.Detected runner = detected.get();
        try {
            ShellRunner.Result result = shellRunner.runArgv(
                    worktree, runner.argv(), RUN_CHECKS_TIMEOUT_SECONDS, RUN_CHECKS_OUTPUT_BYTES);
            return toolOutcome(new RunChecksResult(
                    result.ran(),
                    runner.ecosystem(),
                    String.join(" ", runner.argv()),
                    result.exitCode(),
                    result.truncated(),
                    result.output(),
                    result.error()));
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            return ToolOutcome.Completed.error("interrupted: " + e.getMessage());
        }
        catch (RuntimeException e) {
            return ToolOutcome.Completed.error("run_checks failed: " + e.getMessage());
        }
    }

    /** Wire shape for {@code run_checks}' result. Mirrors {@link
     *  ShellRunner.Result} plus the detected ecosystem + the command the
     *  detector resolved. {@code error} is null on a successful run; the
     *  field is emitted unconditionally so the wire shape is stable. */
    public record RunChecksResult(
            boolean ran,
            String ecosystem,
            String command,
            int exitCode,
            boolean truncated,
            String output,
            String error) {}

    /** Args record for {@code create_task}. */
    public record CreateTaskArgs(
            @ToolParam(description = "owner/name of the watched repo the task should be cut from. "
                    + "Must already be a watched repo with a local clone path; the task's "
                    + "worktree is cut from that clone. Use read_current_repository to obtain "
                    + "this value; do not inspect Git configuration.",
                    required = true) String repo,
            @ToolParam(description = "A short, purpose-written task title — what this task "
                    + "accomplishes, phrased like a PR title (e.g. \"Clean up backend exception "
                    + "handling\"). Imperative, at most 12 words, no code signatures / parens / "
                    + "method names and no trailing scope or justification fragments. It names "
                    + "the task row and the dev branch, so keep it a whole short clause that "
                    + "won't read truncated. Omit only if you have nothing better than the "
                    + "prompt's first line.") String title,
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
                    wireName = "linked_issue_number") Integer linkedIssueNumber,
            @ToolParam(description = "Optional trunk-supplied PlanResult (the plan you already "
                    + "worked out): an object with understanding (summary, affectedComponents, "
                    + "existingPatterns, constraints), intent (summary, numbered steps, "
                    + "validationStrategy, pushStrategy), and signals. Set status='finalized' if "
                    + "it's ready, or 'suggested' / include uncertainAreas to have the task's brain "
                    + "finalize it. When given, it seeds the task's plan so the brain validates or "
                    + "revises it instead of planning from scratch.",
                    wireName = "trunk_plan") JsonNode trunkPlan,
            @ToolParam(description = "The id of the backlog item this task implements, when known "
                    + "from a backlog kickoff or the current trunk discussion. Passing it resolves "
                    + "and links that item to the task; when omitted, the server requires either "
                    + "two agreeing context signals or user confirmation. Only an item already "
                    + "started from the Backlog view is eligible, and this id must match one of "
                    + "the current trunk's in-progress items.",
                    wireName = "backlog_item_id") String backlogItemId,
            @ToolParam(description = "Set true only after the user chooses to start this task "
                    + "without linking a suggested backlog item. This bypasses backlog inference "
                    + "and prevents another confirmation loop.",
                    wireName = "skip_backlog_link") boolean skipBacklogLink) {}

    @AgentTool(
            name = "create_task",
            description = "Cut a new task on this thread. Trunk-only. Returns the new task's "
                    + "id, branch, worktree path, seq, and the backlog item actually linked. "
                    + "A thread may run several tasks at "
                    + "once — each gets its own branch and worktree — so this cuts immediately "
                    + "whether or not other tasks are already live. This is the only way to "
                    + "start a task. Call it only after presenting the plan, asking the user to "
                    + "confirm, and receiving explicit approval in the immediately preceding turn. "
                    + "A backlog link may only target an item already started on this trunk. "
                    + "When backlog evidence is suggestive but not decisive, it returns "
                    + "confirmation_required and creates no task; ask the user, then retry with "
                    + "backlog_item_id or skip_backlog_link=true.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TRUNK)
    public ToolOutcome createTask(CreateTaskArgs args, ToolCall call)
    {
        String threadId = call.threadId();
        String repo = args.repo() == null ? "" : args.repo();
        if (repo.isBlank()) {
            return ToolOutcome.Completed.error("repo (owner/name) is required");
        }
        Optional<Thread> threadOpt = threadStore.findThreadById(threadId);
        if (threadOpt.isEmpty()) {
            return ToolOutcome.Completed.error("thread not found: " + threadId);
        }
        Thread thread = threadOpt.get();
        // A thread can run several tasks concurrently — each on its own branch
        // and worktree — so create_task always cuts immediately.
        // GitHub owner/name slugs are case-insensitive (trino/Trino,
        // spark/Spark resolve to the same repo), so match the same way —
        // otherwise an agent that fumbles the case gets a confusing "repo not
        // in watched repos" denial. The canonical-cased watched repo is used
        // for everything downstream (clone path, task repo).
        WatchedRepo watched = watchedRepos.findAll().stream()
                .filter(r -> repo.equalsIgnoreCase(r.fullName()))
                .findFirst()
                .orElse(null);
        if (watched == null) {
            return ToolOutcome.Completed.error(
                    "repo not in watched repos: " + repo
                            + " — add it under Repos before cutting a task.");
        }
        if (watched.localClonePath() == null || watched.localClonePath().isBlank()) {
            return ToolOutcome.Completed.error(
                    "watched repo " + repo + " has no local clone path — set it under Repos.");
        }
        String initialPrompt = args.initialPrompt() == null ? "" : args.initialPrompt();
        String taskType = args.taskType() == null ? "" : args.taskType();
        // Prefer the agent's purpose-written title; fall back to the first
        // sentence of the prompt so the name never reads truncated mid-thought.
        // Cap either at a short word boundary so a verbose title (the model
        // ignoring the "≤12 words" guidance) can't render clipped mid-token.
        String rawTitle = args.title() != null && !args.title().isBlank()
                ? args.title().strip()
                : createTaskTitle(initialPrompt, thread.title());
        String title = shortTitle(rawTitle);
        String backlogItemId = args.backlogItemId();
        String suppliedBacklogItemId = backlogItemId;
        boolean explicitBacklogItem = suppliedBacklogItemId != null
                && !suppliedBacklogItemId.isBlank();
        BacklogLinkDecision decision = BacklogLinkDecision.none();
        if (explicitBacklogItem || !args.skipBacklogLink()) {
            decision = inferBacklogLink(
                    threadId, rawTitle, initialPrompt, args.trunkPlan(), suppliedBacklogItemId);
        }
        if (explicitBacklogItem) {
            if (decision.itemId() != null
                    && !suppliedBacklogItemId.equals(decision.itemId())) {
                decision = BacklogLinkDecision.confirm(
                        "The supplied backlog item conflicts with the backlog item identified "
                                + "by the approved task context.",
                        decision.candidates());
            }
            else if (decision.itemId() != null) {
                // The id was independently verified by a unique in-progress
                // item, decisive approval, or two agreeing evidence channels.
                decision = BacklogLinkDecision.none();
            }
        }
        if (decision.requiresConfirmation()) {
            List<BacklogLinkCandidate> candidates = decision.candidates().stream()
                    .map(item -> new BacklogLinkCandidate(item.id(), item.title()))
                    .toList();
            if (candidates.isEmpty()) {
                String instruction = BACKLOG_VERIFICATION_UNAVAILABLE.equals(decision.reason())
                        ? "No task was created because the backlog could not be read. Retry "
                                + "create_task with the same backlog_item_id after the backlog "
                                + "is available; use skip_backlog_link=true only if the user "
                                + "chooses to start without a backlog link."
                        : "No task was created. No matching backlog item is currently in progress "
                                + "on this trunk. Start the intended item from the Backlog view, "
                                + "then retry create_task with the backlog_item_id from its kickoff "
                                + "message; use skip_backlog_link=true only if the user chooses to "
                                + "start without a backlog link.";
                return toolOutcome(new BacklogLinkConfirmationResult(
                        true,
                        decision.reason(),
                        candidates,
                        instruction));
            }
            String linkOptions = String.join("; ", candidates.stream()
                    .map(candidate -> "\"Start and link: " + candidate.title()
                            + "\" (retry with backlog_item_id=" + candidate.id() + ")")
                    .toList());
            return toolOutcome(new BacklogLinkConfirmationResult(
                    true,
                    decision.reason(),
                    candidates,
                    "No task was created. Call ask_user_question now with these options: "
                            + linkOptions + "; \"Start without backlog\" (retry create_task "
                            + "with skip_backlog_link=true)."));
        }
        if (!explicitBacklogItem) {
            backlogItemId = decision.itemId();
        }
        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                thread.kind(),
                thread.provider(),
                thread.model(),
                title,
                /* workingDir */ watched.localClonePath(),
                /* branchName — let worktree create derive it */ null,
                initialPrompt.isBlank() ? null : initialPrompt,
                /* initialGroupIds */ List.of(),
                taskType.isBlank() ? null : taskType,
                args.linkedPrNumber(),
                args.linkedIssueNumber(),
                thread.flow(),
                thread.workspaceId(),
                /* workModel — inherit thread's override */ thread.workModel(),
                /* trunkPlan — seeds the PlanStage when the trunk hands off a plan */
                args.trunkPlan()).withOrigin(Task.ORIGIN_AGENT);
        try {
            Task created = threads.materialiseTask(threadId, request);
            BacklogItem linkedBacklog = resolveBacklogItem(backlogItemId, created.id());
            return toolOutcome(new CreatedTaskResult(
                    created.id(),
                    created.threadId(),
                    created.seq(),
                    created.status() == null ? null : created.status().name(),
                    created.branchName(),
                    created.worktreePath(),
                    created.workingDir(),
                    created.baseBranch(),
                    linkedBacklog == null
                            ? null
                            : new LinkedBacklog(linkedBacklog.id(), linkedBacklog.title())));
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return ToolOutcome.Completed.error("create_task failed: " + e.getMessage());
        }
    }

    /** Best-effort: link the new task after it has been cut. A stale id or a
     *  transient backlog failure must not turn successful task creation into
     *  a tool failure. */
    private BacklogItem resolveBacklogItem(String backlogItemId, String taskId)
    {
        if (backlogItemId == null || backlogItemId.isBlank()) {
            return null;
        }
        try {
            return backlog.resolve(backlogItemId, taskId);
        }
        catch (RuntimeException e) {
            log.warn("create_task: failed to resolve backlog item {} for task {}: {}",
                    backlogItemId, taskId, e.getMessage());
            return null;
        }
    }

    /** Decide before materialising the task: a direct task-cut approval is
     *  decisive; otherwise automatic linking requires two independent
     *  evidence channels which agree on the same item. A lone suggestion or
     *  unresolved ambiguity is handed back to trunk for an explicit user
     *  choice. Only in-progress items are eligible: an explicit id must name
     *  one of them, and automatic inference cannot skip the Backlog view's
     *  start-development transition. A backlog read failure remains
     *  best-effort for tasks that do not explicitly request a link. */
    private BacklogLinkDecision inferBacklogLink(
            String threadId,
            String title,
            String initialPrompt,
            JsonNode trunkPlan,
            String explicitBacklogItemId)
    {
        try {
            List<BacklogItem> candidates = backlog.list(threadId).stream()
                    .filter(item -> BacklogItem.STATUS_IN_PROGRESS.equals(item.status()))
                    .toList();
            if (explicitBacklogItemId != null
                    && !explicitBacklogItemId.isBlank()
                    && candidates.stream()
                            .noneMatch(item -> explicitBacklogItemId.equals(item.id()))) {
                return BacklogLinkDecision.confirm(
                        "The supplied backlog item does not match an in-progress backlog item "
                                + "on this trunk.",
                        candidates);
            }
            if (explicitBacklogItemId != null
                    && !explicitBacklogItemId.isBlank()
                    && candidates.size() == 1) {
                return BacklogLinkDecision.link(candidates.getFirst());
            }
            List<EvidenceMatch> matches = new ArrayList<>();
            for (String evidence : new String[] {
                    title,
                    initialPrompt,
                    trunkPlan == null ? null : trunkPlan.toString()}) {
                addMatch(matches, matchBacklogEvidence(candidates, evidence));
            }
            ApprovalContext approvalContext = precedingTrunkApprovalContext(threadId);
            if (explicitBacklogItemId != null && !explicitBacklogItemId.isBlank()) {
                Optional<BacklogItem> userSelection = explicitBacklogSelection(
                        candidates, approvalContext.userReply());
                if (userSelection.isPresent()) {
                    BacklogItem selected = userSelection.get();
                    if (explicitBacklogItemId.equals(selected.id())) {
                        return BacklogLinkDecision.link(selected);
                    }
                    return BacklogLinkDecision.confirm(
                            "The supplied backlog item conflicts with the backlog item selected "
                                    + "by the user.",
                            List.of(selected));
                }
            }
            if (approvalContext.genericAffirmative()) {
                ApprovalMatch approval = matchApprovalEvidence(
                        candidates, approvalContext.evidence());
                if (approval.decisiveItemId() != null) {
                    return candidates.stream()
                            .filter(item -> item.id().equals(approval.decisiveItemId()))
                            .findFirst()
                            .map(BacklogLinkDecision::link)
                            .orElseGet(BacklogLinkDecision::none);
                }
                addMatch(matches, approval.match());
            }

            List<BacklogItem> matched = candidates.stream()
                    .filter(item -> matches.stream().anyMatch(match -> match.contains(item)))
                    .toList();
            if (matched.isEmpty()) {
                if (explicitBacklogItemId != null && !explicitBacklogItemId.isBlank()) {
                    return BacklogLinkDecision.confirm(
                            "Multiple backlog items are in progress and the task context does "
                                    + "not verify the supplied item.",
                            candidates);
                }
                return BacklogLinkDecision.none();
            }
            List<BacklogItem> strong = matched.stream()
                    .filter(item -> matches.stream()
                            .filter(EvidenceMatch::isUnique)
                            .filter(match -> match.contains(item))
                            .count() >= 2)
                    .toList();
            if (strong.size() == 1) {
                return BacklogLinkDecision.link(strong.getFirst());
            }
            boolean conflicting = matched.size() > 1
                    || matches.stream().anyMatch(match -> !match.isUnique());
            String reason = conflicting
                    ? "Backlog context contains conflicting or ambiguous matches."
                    : "Backlog context is not strong enough to link automatically.";
            return BacklogLinkDecision.confirm(reason, matched);
        }
        catch (RuntimeException e) {
            log.warn("create_task: failed to infer backlog link for thread {}: {}",
                    threadId, e.getMessage());
            if (explicitBacklogItemId != null && !explicitBacklogItemId.isBlank()) {
                return BacklogLinkDecision.confirm(
                        BACKLOG_VERIFICATION_UNAVAILABLE,
                        List.of());
            }
            return BacklogLinkDecision.none();
        }
    }

    private static void addMatch(List<EvidenceMatch> matches, EvidenceMatch match)
    {
        if (!match.candidates().isEmpty()) {
            matches.add(match);
        }
    }

    private static Optional<BacklogItem> explicitBacklogSelection(
            List<BacklogItem> candidates, String userReply)
    {
        String selection = normaliseBacklogText(userReply);
        List<BacklogItem> selected = candidates.stream()
                .filter(item -> selection.equals(normaliseBacklogText(
                        "Start and link: " + item.title())))
                .toList();
        return selected.size() == 1 ? Optional.of(selected.getFirst()) : Optional.empty();
    }

    /** The approval exchange is one evidence channel. Within it, prefer the
     *  first specific question over broader explanatory context so a deferred
     *  later phase does not erase the item the user was asked to approve. */
    private static ApprovalMatch matchApprovalEvidence(
            List<BacklogItem> candidates, List<String> evidence)
    {
        List<BacklogItem> ambiguous = new ArrayList<>();
        for (String value : evidence) {
            EvidenceMatch match = matchBacklogEvidence(candidates, value);
            if (match.isUnique()) {
                return new ApprovalMatch(
                        match,
                        isDirectTaskApproval(value)
                                ? match.candidates().getFirst().id()
                                : null);
            }
            for (BacklogItem item : match.candidates()) {
                if (ambiguous.stream().noneMatch(existing -> existing.id().equals(item.id()))) {
                    ambiguous.add(item);
                }
            }
        }
        return new ApprovalMatch(new EvidenceMatch(ambiguous), null);
    }

    private static boolean isDirectTaskApproval(String value)
    {
        if (value == null || !value.stripTrailing().endsWith("?")) {
            return false;
        }
        return DIRECT_TASK_APPROVAL.matcher(normaliseBacklogText(value)).find();
    }

    /** Exact title / summary / suffix matches are stronger than the compact
     *  "Phase N" fallback. */
    private static EvidenceMatch matchBacklogEvidence(
            List<BacklogItem> candidates, String evidence)
    {
        List<BacklogItem> exact = matchingBacklogItems(candidates, evidence);
        if (exact.size() == 1) {
            return new EvidenceMatch(exact);
        }
        if (exact.size() > 1) {
            List<BacklogItem> phase = matchingBacklogPhases(exact, evidence);
            return new EvidenceMatch(phase.isEmpty() ? exact : phase);
        }
        return new EvidenceMatch(matchingBacklogPhases(candidates, evidence));
    }

    /** Context for a generic approval such as "go ahead": only the trunk
     *  exchange immediately before the current user message. Task rows and
     *  older trunk rounds are deliberately excluded so stale plans cannot
     *  claim an unrelated task. */
    private ApprovalContext precedingTrunkApprovalContext(String threadId)
    {
        List<ThreadMessage> messages =
                threadStore.listRecentMessages(threadId, BACKLOG_CONTEXT_MESSAGE_LIMIT);
        int currentUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ThreadMessage message = messages.get(i);
            if (isTrunkMessage(message)
                    && "user".equals(message.role())
                    && "text".equals(message.type())) {
                currentUser = i;
                break;
            }
        }
        if (currentUser < 0) {
            return new ApprovalContext(false, "", List.of());
        }

        String userReply = textMessage(messages.get(currentUser));
        boolean genericAffirmative = isGenericAffirmative(userReply);
        List<String> evidence = new ArrayList<>();
        for (int i = currentUser - 1; i >= 0; i--) {
            ThreadMessage message = messages.get(i);
            if (!isTrunkMessage(message)) {
                continue;
            }
            if ("user".equals(message.role()) && "text".equals(message.type())) {
                addEvidence(evidence, textMessage(message));
                break;
            }
            if ("assistant".equals(message.role()) && "text".equals(message.type())) {
                String text = textMessage(message);
                addEvidence(evidence, trailingQuestion(text));
                addEvidence(evidence, text);
            }
            else if ("tool_call".equals(message.type())) {
                addQuestionToolEvidence(evidence, message);
            }
        }
        return new ApprovalContext(genericAffirmative, userReply, evidence);
    }

    private static boolean isGenericAffirmative(String value)
    {
        return switch (normaliseBacklogText(value)) {
            case "yes", "yes please", "go ahead", "go ahead with it", "proceed",
                    "please proceed", "do it", "start it", "cut it", "ok", "okay",
                    "sounds good", "sure" -> true;
            default -> false;
        };
    }

    private static boolean isTrunkMessage(ThreadMessage message)
    {
        return message.taskId() == null && message.stageId() == null;
    }

    private String textMessage(ThreadMessage message)
    {
        try {
            return mapper.readTree(message.contentJson()).path("text").asText("");
        }
        catch (JsonProcessingException | RuntimeException ignored) {
            return "";
        }
    }

    private void addQuestionToolEvidence(List<String> evidence, ThreadMessage message)
    {
        try {
            JsonNode call = mapper.readTree(message.contentJson());
            if (!"ask_user_question".equals(call.path("toolName").asText())) {
                return;
            }
            JsonNode input = call.path("input");
            // The confirmation itself is more specific than its explanatory
            // context (which commonly names later deferred phases).
            addEvidence(evidence, input.path("question").asText(""));
            addEvidence(evidence, input.path("context").asText(""));
        }
        catch (JsonProcessingException | RuntimeException ignored) {
            // Malformed history is not allowed to fail task creation.
        }
    }

    private static void addEvidence(List<String> evidence, String value)
    {
        if (value != null && !value.isBlank()) {
            evidence.add(value);
        }
    }

    private static String trailingQuestion(String text)
    {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.stripTrailing();
        if (!stripped.endsWith("?")) {
            return null;
        }
        int start = Math.max(stripped.lastIndexOf('\n'), stripped.lastIndexOf(". "));
        start = Math.max(start, stripped.lastIndexOf("! "));
        return stripped.substring(start < 0 ? 0 : start + 1).strip();
    }

    private static List<BacklogItem> matchingBacklogPhases(
            List<BacklogItem> candidates, String evidence)
    {
        Set<String> phases = phaseIdentifiers(evidence);
        if (phases.size() != 1) {
            return List.of();
        }
        String phase = phases.iterator().next();
        return candidates.stream()
                .filter(item -> phaseIdentifiers(item.title()).contains(phase))
                .toList();
    }

    private static Set<String> phaseIdentifiers(String value)
    {
        Set<String> phases = new HashSet<>();
        var matcher = PHASE_IDENTIFIER.matcher(normaliseBacklogText(value));
        while (matcher.find()) {
            phases.add(matcher.group(1));
        }
        return phases;
    }

    private record EvidenceMatch(List<BacklogItem> candidates)
    {
        boolean isUnique()
        {
            return candidates.size() == 1;
        }

        boolean contains(BacklogItem item)
        {
            return candidates.stream().anyMatch(candidate -> candidate.id().equals(item.id()));
        }
    }

    private record ApprovalMatch(EvidenceMatch match, String decisiveItemId) {}

    private record ApprovalContext(
            boolean genericAffirmative, String userReply, List<String> evidence) {}

    private record BacklogLinkDecision(
            String itemId, String reason, List<BacklogItem> candidates)
    {
        static BacklogLinkDecision none()
        {
            return new BacklogLinkDecision(null, null, List.of());
        }

        static BacklogLinkDecision link(BacklogItem item)
        {
            return new BacklogLinkDecision(item.id(), null, List.of(item));
        }

        static BacklogLinkDecision confirm(String reason, List<BacklogItem> candidates)
        {
            return new BacklogLinkDecision(null, reason, candidates);
        }

        boolean requiresConfirmation()
        {
            return reason != null;
        }
    }

    private static List<BacklogItem> matchingBacklogItems(
            List<BacklogItem> candidates, String evidence)
    {
        String context = normaliseBacklogText(evidence);
        if (context.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(item -> backlogTitleMatches(item, context))
                .toList();
    }

    private static boolean backlogTitleMatches(BacklogItem item, String normalisedContext)
    {
        List<String> phrases = new ArrayList<>();
        phrases.add(normaliseBacklogText(item.title()));
        phrases.add(normaliseBacklogText(item.summary()));
        String[] titleParts = item.title().split("\\s+[—–-]\\s+", 2);
        if (titleParts.length == 2) {
            phrases.add(normaliseBacklogText(titleParts[1]));
        }
        String boundedContext = " " + normalisedContext + " ";
        return phrases.stream()
                .filter(phrase -> phrase.split(" ").length >= 3)
                .distinct()
                .anyMatch(phrase -> boundedContext.contains(" " + phrase + " "));
    }

    private static String normaliseBacklogText(String value)
    {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }

    /** Display cap for a task title, in words — a task row / branch name reads
     *  as a whole short clause, not a mid-thought cut. */
    private static final int MAX_TITLE_WORDS = 12;

    /** Trim a title to {@link #MAX_TITLE_WORDS} words at a word boundary,
     *  marking the cut with an ellipsis — never a mid-token slice like
     *  "…recoverTurns(status,". Titles already within the cap pass through
     *  unchanged, so this only bites a verbose agent title. */
    static String shortTitle(String title)
    {
        String[] words = title.strip().split("\\s+");
        if (words.length <= MAX_TITLE_WORDS) {
            return title.strip();
        }
        return String.join(" ", Arrays.copyOfRange(words, 0, MAX_TITLE_WORDS)) + "…";
    }

    /** A task title from its opening prompt's first line (capped), so the
     *  worktree branch reads like the work — not the generic thread title.
     *  Falls back to the thread title when there's no prompt. */
    private static String createTaskTitle(String initialPrompt, String threadTitle)
    {
        if (initialPrompt == null || initialPrompt.isBlank()) {
            return threadTitle;
        }
        String firstLine = initialPrompt.strip().lines().findFirst().orElse(initialPrompt).strip();
        return firstSentence(firstLine);
    }

    /** First complete sentence of {@code text} so a derived name reads as a
     *  whole clause rather than a mid-thought cut (the old hard 72-char slice
     *  produced names like "Clean up X. Scope is"). Cuts at the first period
     *  that is followed by whitespace — ignoring dotted abbreviations /
     *  decimals ("e.g.", "v1.2") which have no following space. Falls back to
     *  a 72-char word-boundary trim when there's no early sentence break. */
    static String firstSentence(String text)
    {
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '.' && Character.isWhitespace(text.charAt(i + 1))) {
                if (i + 1 <= 72) {
                    return text.substring(0, i + 1).strip();
                }
                break;
            }
        }
        if (text.length() <= 72) {
            return text;
        }
        String head = text.substring(0, 72);
        int lastSpace = head.lastIndexOf(' ');
        return (lastSpace > 0 ? head.substring(0, lastSpace) : head).strip();
    }

    /** Wire shape for {@code create_task}'s result — the just-cut task's
     *  identifying fields. */
    public record CreatedTaskResult(
            String id,
            String threadId,
            long seq,
            String status,
            String branchName,
            String worktreePath,
            String workingDir,
            String baseBranch,
            LinkedBacklog linkedBacklog) {}

    public record LinkedBacklog(String id, String title) {}

    /** Wire shape returned instead of cutting a task when backlog evidence is
     *  suggestive but not safe to apply automatically. */
    public record BacklogLinkConfirmationResult(
            @JsonProperty("confirmation_required") boolean confirmationRequired,
            String reason,
            List<BacklogLinkCandidate> candidates,
            String instruction) {}

    public record BacklogLinkCandidate(String id, String title) {}

    /** Adapt a record (or any Jackson-serialisable value) to a {@link
     *  ToolOutcome.Completed} carrying the JSON bytes. This is the single
     *  serialisation point for tool results in this class — handlers stay
     *  free of JSON plumbing, and the bytes the model sees come from one
     *  mapper. A failure here is a programming bug (a non-serialisable
     *  payload type), not a tool error, so we raise rather than wrap. */
    private ToolOutcome toolOutcome(Object payload)
    {
        try {
            return ToolOutcome.Completed.ok(mapper.writeValueAsString(payload));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to serialise tool payload: " + payload, e);
        }
    }
}
