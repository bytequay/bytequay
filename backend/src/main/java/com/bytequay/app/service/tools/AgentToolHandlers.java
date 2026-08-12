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

import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadCheckpoint;
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
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.local.TestRunnerDetector;
import com.bytequay.app.service.threads.WorktreeService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
    private final WorktreeService worktreeService;
    private final ObjectMapper mapper;
    private final RepoService repoService;
    private final ActiveAgentContextRegistry activeContexts;

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
            WorktreeService worktreeService,
            ObjectMapper mapper,
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
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
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
                    + "the local clone path.",
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
