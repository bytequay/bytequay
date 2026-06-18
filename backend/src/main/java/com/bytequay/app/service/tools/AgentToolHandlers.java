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

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.QueuedTask;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadCheckpoint;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.local.TestRunnerDetector;
import com.bytequay.app.service.threads.TaskQueueMaterialiser;
import com.bytequay.app.service.threads.TaskQueueScheduler;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
    private final ThreadService threads;
    private final TaskQueueMaterialiser queueMaterialiser;
    private final TaskQueueScheduler queueScheduler;
    private final ObjectMapper mapper;

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
            TaskQueueMaterialiser queueMaterialiser,
            TaskQueueScheduler queueScheduler,
            ObjectMapper mapper)
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
        this.queueMaterialiser = requireNonNull(queueMaterialiser, "queueMaterialiser is null");
        this.queueScheduler = requireNonNull(queueScheduler, "queueScheduler is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
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

    /** Args record for {@code list_tools} — no args. */
    public record ListToolsArgs() {}

    @AgentTool(
            name = "list_tools",
            description = "List every tool available this turn, filtered to the "
                    + "caller's role. Returns a JSON array of {name, description, "
                    + "gating, security} entries — useful when picking the right "
                    + "verb for the next action.",
            security = SecurityType.TOOL_DISCOVER,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
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

    /** Args record for {@code list_skills}. */
    public record ListSkillsArgs(
            @ToolParam(description = "Optional scope filter — one of global, repo, thread. "
                    + "Omit to see all skills visible to this thread.") String scope,
            @ToolParam(description = "Optional substring match against the trigger description. "
                    + "Case-insensitive.") String query) {}

    @AgentTool(
            name = "list_skills",
            description = "List the skills available for this turn. Returns a JSON array "
                    + "of {id, name, description, scope, repo, role_tag, kind} entries. "
                    + "Skills are model-triggered — read the \"loads when …\" description "
                    + "and decide whether to load the body via load_skill.",
            security = SecurityType.SKILL_USE,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome listSkills(ListSkillsArgs args, ToolCall call)
    {
        return skillOutcome(skillTools.listSkills(args.scope(), args.query(), skillContext(call)));
    }

    /** Args record for {@code load_skill}. */
    public record LoadSkillArgs(
            @ToolParam(description = "Unique skill name from a prior list_skills entry.",
                    required = true) String name) {}

    @AgentTool(
            name = "load_skill",
            description = "Load the body of one skill by name. Returns a JSON object "
                    + "{name, body}. Pair with list_skills: list to find the trigger "
                    + "that matches the task, load to fetch the instructions.",
            security = SecurityType.SKILL_USE,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome loadSkill(LoadSkillArgs args, ToolCall call)
    {
        return skillOutcome(skillTools.loadSkill(args.name()));
    }

    private static ToolContext skillContext(ToolCall call)
    {
        return new ToolContext(
                Set.of(),
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
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty() || active.get().worktreePath() == null
                || active.get().worktreePath().isBlank()) {
            return ToolOutcome.Completed.error("run_checks requires an active task with a worktree");
        }
        Path worktree = Path.of(active.get().worktreePath());
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
            name = "create_task",
            description = "Cut a new task on this thread. Trunk-only — the trunk role "
                    + "plans + cuts tasks; task / reviewer roles can't reach this. "
                    + "Returns the new task's id, branch, worktree path, and seq. "
                    + "Valid whenever the thread has no active task: on a brand-new "
                    + "0-task thread (the bootstrap), or after the chain has run dry "
                    + "(prior tasks all COMPLETED — the trunk re-enters as creator). "
                    + "Fails when there's already an active task — use next_task / "
                    + "ship_task to propose a successor instead.",
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
        // Active-task check, not zero-task check: the trunk can re-enter the
        // creator role whenever the chain has no live task (bootstrap on a
        // brand-new thread, OR revival after the previous chain completed).
        // See workspace-thread-task-design.md §"Trunk re-enters when the
        // chain runs dry."
        if (taskStore.findActiveTaskForThread(threadId).isPresent()) {
            return ToolOutcome.Completed.error(
                    "thread has an active task — use next_task or ship_task to "
                            + "propose a successor. create_task only runs when the "
                            + "chain has no live task.");
        }
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
        // When the trunk has lined up a queue, cutting a task starts the
        // head of that queue (its planned title / opening prompt) through
        // the same dequeue-and-run path the scheduler uses — one execution
        // rule for queue_task, create_task, and on-completion advancement.
        // The caller's args are a one-off fallback only when the queue is
        // empty. create_task is trunk-only (no active task), so the slot is
        // free and the head starts immediately.
        Optional<QueuedTask> head = queueMaterialiser.pendingHead(thread);
        if (head.isPresent()) {
            try {
                Optional<Task> started =
                        queueScheduler.startNextIfIdle(threadId, watched.localClonePath());
                if (started.isEmpty()) {
                    return ToolOutcome.Completed.error(
                            "create_task: the queued head couldn't start now (slot busy or no "
                                    + "working dir); it will start when a slot frees.");
                }
                Task materialised = started.get();
                return toolOutcome(new CreatedTaskResult(
                        materialised.id(),
                        materialised.threadId(),
                        materialised.seq(),
                        materialised.status() == null ? null : materialised.status().name(),
                        materialised.branchName(),
                        materialised.worktreePath(),
                        materialised.workingDir(),
                        materialised.baseBranch()));
            }
            catch (IllegalArgumentException | IllegalStateException e) {
                return ToolOutcome.Completed.error("create_task failed: " + e.getMessage());
            }
        }
        String initialPrompt = args.initialPrompt() == null ? "" : args.initialPrompt();
        String taskType = args.taskType() == null ? "" : args.taskType();
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
                args.linkedPrNumber(),
                args.linkedIssueNumber(),
                thread.flow(),
                thread.workspaceId(),
                /* workModel — inherit thread's override */ thread.workModel());
        try {
            Task created = threads.materialiseTask(threadId, request);
            return toolOutcome(new CreatedTaskResult(
                    created.id(),
                    created.threadId(),
                    created.seq(),
                    created.status() == null ? null : created.status().name(),
                    created.branchName(),
                    created.worktreePath(),
                    created.workingDir(),
                    created.baseBranch()));
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return ToolOutcome.Completed.error("create_task failed: " + e.getMessage());
        }
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
            String baseBranch) {}

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
