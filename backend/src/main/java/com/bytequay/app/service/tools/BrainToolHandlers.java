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

import com.bytequay.app.domain.LocalCommitDetail;
import com.bytequay.app.domain.LocalCommitFile;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.LocalRepoService;
import com.bytequay.app.service.pr.PullRequestService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * The brain agent's read-only introspection tools. Every method is a pure
 * read against existing stores / caches — no mutations. They're registered
 * via {@code @AgentTool} like every other tool and bridged into the API
 * lane; the brain agent reaches exactly these because their names are the
 * {@code BRAIN_TOOL_ALLOWLIST}. Each takes an explicit {@code task_id} (the
 * agent is told its task in the system prompt) and, where remote data is
 * needed, resolves the repo from the task's linked-PR ref.
 */
@Component
public class BrainToolHandlers
{
    private static final Logger log = LoggerFactory.getLogger(BrainToolHandlers.class);

    private static final int COMMIT_BODY_MAX = 500;
    private static final int COMMIT_FILES_MAX = 5;
    private static final int PHASE_HISTORY_CAP = 50;

    /** Matches a JUnit/Jest-ish test case for the coverage heuristic. */
    private static final Pattern TEST_CASE = Pattern.compile(
            "@Test\\b|\\b(?:it|test)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final PullRequestService pullRequests;
    private final LocalRepoService localRepos;
    private final ObjectMapper mapper;

    public BrainToolHandlers(
            TaskStore taskStore,
            StageStore stageStore,
            PullRequestService pullRequests,
            LocalRepoService localRepos,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.localRepos = requireNonNull(localRepos, "localRepos is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    // ── count_operations ────────────────────────────────────────────────

    public record CountOperationsArgs(
            @ToolParam(description = "Task id.", required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "Optional operation type filter "
                    + "(code | validate | push | cleanup | ...).", required = false,
                    wireName = "operation_type")
            String operationType) {}

    @AgentTool(
            name = "count_operations",
            description = "Count completed operations recorded on this task's stages, "
                    + "optionally filtered to one operation type. Returns the total.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome countOperations(CountOperationsArgs args, ToolCall call)
    {
        if (blank(args.taskId())) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        long count = stageStore.findEventsByTask(args.taskId()).stream()
                .filter(e -> e.eventType() == StageEventType.OPERATION_COMPLETED)
                .filter(e -> args.operationType() == null
                        || args.operationType().equalsIgnoreCase(payloadField(e, "operation")))
                .count();
        return ok(new CountResult(args.taskId(), args.operationType(), count));
    }

    public record CountResult(String taskId, String operationType, long count) {}

    // ── read_commit_summary ─────────────────────────────────────────────

    public record ReadCommitArgs(
            @ToolParam(description = "Task id (used to resolve the repo).", required = true,
                    wireName = "task_id")
            String taskId,
            @ToolParam(description = "Commit SHA.", required = true)
            String sha) {}

    @AgentTool(
            name = "read_commit_summary",
            description = "Read a commit's subject, truncated body, and first few changed "
                    + "files with line counts, from the task's local clone.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readCommitSummary(ReadCommitArgs args, ToolCall call)
    {
        if (blank(args.taskId()) || blank(args.sha())) {
            return ToolOutcome.Completed.error("task_id and sha are required");
        }
        Optional<RepoRef> repo = repoFor(args.taskId());
        if (repo.isEmpty()) {
            return ToolOutcome.Completed.error("task has no linked repo to read commits from");
        }
        RepoRef r = repo.get();
        try {
            LocalCommitDetail detail = localRepos.commitDetail(r.owner(), r.repo(), args.sha());
            List<LocalCommitFile> files = localRepos.commitFiles(r.owner(), r.repo(), args.sha());
            List<CommitFile> top = files.stream()
                    .limit(COMMIT_FILES_MAX)
                    .map(f -> new CommitFile(f.path(), f.additions(), f.deletions()))
                    .toList();
            return ok(new CommitResult(
                    detail.sha(), detail.subject(), truncate(detail.body(), COMMIT_BODY_MAX),
                    files.size(), top));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolOutcome.Completed.error("interrupted reading commit " + args.sha());
        }
        catch (IOException | RuntimeException e) {
            return ToolOutcome.Completed.error("could not read commit " + args.sha() + ": " + e.getMessage());
        }
    }

    public record CommitResult(String sha, String subject, String body, int fileCount, List<CommitFile> files) {}

    public record CommitFile(String path, int additions, int deletions) {}

    // ── read_diff_summary ───────────────────────────────────────────────

    public record ReadDiffArgs(
            @ToolParam(description = "Task id.", required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "Optional file path to zoom into.", required = false)
            String file) {}

    @AgentTool(
            name = "read_diff_summary",
            description = "List the linked PR's changed files with line counts, or one "
                    + "file's counts when a file is given.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readDiffSummary(ReadDiffArgs args, ToolCall call)
    {
        if (blank(args.taskId())) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        Optional<PullRequestDetail> detail = prDetail(args.taskId());
        if (detail.isEmpty()) {
            return ToolOutcome.Completed.error("task has no linked PR diff to read");
        }
        List<CommitFile> files = detail.get().files().stream()
                .filter(f -> args.file() == null || args.file().equals(f.filename()))
                .map(f -> new CommitFile(f.filename(), f.additions(), f.deletions()))
                .toList();
        return ok(new DiffResult(detail.get().repo(), detail.get().number(), files));
    }

    public record DiffResult(String repo, int prNumber, List<CommitFile> files) {}

    // ── check_test_coverage ─────────────────────────────────────────────

    public record CheckCoverageArgs(
            @ToolParam(description = "Task id (used to resolve the worktree).", required = true,
                    wireName = "task_id")
            String taskId,
            @ToolParam(description = "Relative file paths to check.", required = true)
            List<String> files) {}

    @AgentTool(
            name = "check_test_coverage",
            description = "For each file, report whether a matching test file exists in the "
                    + "task's worktree and roughly how many test cases it contains.",
            security = SecurityType.CODE_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome checkTestCoverage(CheckCoverageArgs args, ToolCall call)
    {
        if (blank(args.taskId()) || args.files() == null || args.files().isEmpty()) {
            return ToolOutcome.Completed.error("task_id and at least one file are required");
        }
        Optional<Task> task = taskStore.findTaskById(args.taskId());
        if (task.isEmpty() || blank(task.get().workingDir())) {
            return ToolOutcome.Completed.error("task has no worktree to inspect");
        }
        Path root = Path.of(task.get().agentCwd());
        List<Coverage> out = new ArrayList<>();
        for (String file : args.files()) {
            out.add(coverageFor(root, file));
        }
        return ok(out);
    }

    public record Coverage(String file, boolean hasTest, String testFile, int testCases) {}

    // ── read_stage_metrics ──────────────────────────────────────────────

    public record StageMetricsArgs(
            @ToolParam(description = "Task id.", required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "Stage id, or 'all' for every stage.", required = false,
                    wireName = "stage_id")
            String stageId) {}

    @AgentTool(
            name = "read_stage_metrics",
            description = "Read the metrics JSON for one stage, or for all of the task's "
                    + "stages when stage_id is 'all' or omitted.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readStageMetrics(StageMetricsArgs args, ToolCall call)
    {
        if (blank(args.taskId())) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        boolean all = blank(args.stageId()) || "all".equalsIgnoreCase(args.stageId());
        List<StageMetricsRow> rows = stageStore.findStagesByTask(args.taskId()).stream()
                .filter(s -> all || s.id().toString().equals(args.stageId()))
                .map(s -> new StageMetricsRow(
                        s.id().toString(), s.type().name(), s.state().name(),
                        stageStore.findMetricsJson(s.id()).orElse("{}")))
                .toList();
        return ok(rows);
    }

    public record StageMetricsRow(String stageId, String type, String state, String metricsJson) {}

    // ── read_phase_history ──────────────────────────────────────────────

    public record PhaseHistoryArgs(
            @ToolParam(description = "Task id.", required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "Optional ISO-8601 lower bound; only transitions after "
                    + "it are returned.", required = false, wireName = "since_ts")
            String sinceTs) {}

    @AgentTool(
            name = "read_phase_history",
            description = "List the task's phase transitions oldest-first (capped at 50), "
                    + "optionally only those after a timestamp.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readPhaseHistory(PhaseHistoryArgs args, ToolCall call)
    {
        if (blank(args.taskId())) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        Instant since = parseInstant(args.sinceTs());
        List<PhaseRow> rows = taskStore.listPhaseEvents(args.taskId()).stream()
                .filter(e -> since == null || e.transitionedAt().isAfter(since))
                .limit(PHASE_HISTORY_CAP)
                .map(BrainToolHandlers::phaseRow)
                .toList();
        return ok(rows);
    }

    public record PhaseRow(String fromPhase, String toPhase, String transitionedAt, String reason) {}

    // ── read_review_panel_findings ──────────────────────────────────────

    public record PanelFindingsArgs(
            @ToolParam(description = "Task id.", required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "Review stage id, or 'all'.", required = false,
                    wireName = "review_stage_id")
            String reviewStageId) {}

    @AgentTool(
            name = "read_review_panel_findings",
            description = "List multi-agent review-panel findings for the task. Empty until "
                    + "review panels run.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readReviewPanelFindings(PanelFindingsArgs args, ToolCall call)
    {
        if (blank(args.taskId())) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        // Review-panel findings aren't produced yet; return an empty list so
        // the agent can answer "no panel reviews have run" truthfully.
        return ok(List.<PanelFinding>of());
    }

    public record PanelFinding(String reviewStageId, String severity, boolean agreed, String summary) {}

    // ── read_remote_pr_status ───────────────────────────────────────────

    public record PrStatusArgs(
            @ToolParam(description = "Task id.", required = true, wireName = "task_id")
            String taskId) {}

    @AgentTool(
            name = "read_remote_pr_status",
            description = "Read the linked PR's status, CI status, approvals, mergeability.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome readRemotePrStatus(PrStatusArgs args, ToolCall call)
    {
        if (blank(args.taskId())) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        Optional<PullRequestDetail> detail = prDetail(args.taskId());
        if (detail.isEmpty()) {
            return ToolOutcome.Completed.error("task has no linked PR");
        }
        PullRequestDetail d = detail.get();
        return ok(new PrStatus(
                d.state(), d.draft(), d.merged(),
                d.ciStatus() == null ? "UNKNOWN" : d.ciStatus().name(),
                d.approvalCount(), d.changesRequestedCount(),
                Boolean.TRUE.equals(d.mergeable()), d.mergeableState()));
    }

    public record PrStatus(
            String state, boolean draft, boolean merged, String ciStatus,
            int approvals, int changesRequested, boolean mergeable, String mergeableState) {}

    // ── list_unresolved_comments ────────────────────────────────────────

    public record UnresolvedCommentsArgs(
            @ToolParam(description = "Task id.", required = true, wireName = "task_id")
            String taskId,
            @ToolParam(description = "Optional source filter "
                    + "(LOCAL_USER | LOCAL_AGENT | REMOTE_REVIEWER).", required = false)
            String source) {}

    @AgentTool(
            name = "list_unresolved_comments",
            description = "List the task's unresolved review comments, optionally filtered "
                    + "to one source.",
            security = SecurityType.TASK_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome listUnresolvedComments(UnresolvedCommentsArgs args, ToolCall call)
    {
        if (blank(args.taskId())) {
            return ToolOutcome.Completed.error("task_id is required");
        }
        ReviewCommentSource source = parseSource(args.source());
        if (args.source() != null && source == null) {
            return ToolOutcome.Completed.error("unknown source: " + args.source());
        }
        List<CommentRow> rows = stageStore.findUnresolvedComments(args.taskId()).stream()
                .filter(c -> source == null || c.source() == source)
                .map(BrainToolHandlers::commentRow)
                .toList();
        return ok(rows);
    }

    public record CommentRow(String file, int line, String body, String source, String remoteLink) {}

    // ── helpers ─────────────────────────────────────────────────────────

    private Coverage coverageFor(Path root, String file)
    {
        Path target = root.resolve(file);
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        Path dir = target.getParent() == null ? root : target.getParent();
        List<Path> candidates = new ArrayList<>();
        if ("java".equals(ext)) {
            candidates.add(dir.resolve(base + "Test.java"));
            candidates.add(dir.resolve("Test" + base + ".java"));
        }
        else {
            candidates.add(dir.resolve(base + ".test." + ext));
            candidates.add(dir.resolve(base + ".spec." + ext));
            candidates.add(dir.resolve("__tests__").resolve(base + ".test." + ext));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return new Coverage(file, true, root.relativize(candidate).toString(),
                        countTestCases(candidate));
            }
        }
        return new Coverage(file, false, null, 0);
    }

    private int countTestCases(Path testFile)
    {
        try {
            String body = Files.readString(testFile);
            Matcher m = TEST_CASE.matcher(body);
            int n = 0;
            while (m.find()) {
                n++;
            }
            return n;
        }
        catch (IOException e) {
            return 0;
        }
    }

    private Optional<PullRequestDetail> prDetail(String taskId)
    {
        return repoFor(taskId).flatMap(r -> {
            try {
                return Optional.ofNullable(pullRequests.getPullRequestDetail(r.repoFullName(), r.number()));
            }
            catch (RuntimeException e) {
                log.warn("brain prDetail for task {} failed: {}", taskId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Resolve owner/repo + PR number from a task's {@code owner/repo#n} link ref. */
    private Optional<RepoRef> repoFor(String taskId)
    {
        return taskStore.findTaskById(taskId)
                .map(Task::linkedPrRef)
                .flatMap(BrainToolHandlers::parseRef);
    }

    private static Optional<RepoRef> parseRef(String ref)
    {
        if (ref == null) {
            return Optional.empty();
        }
        int hash = ref.lastIndexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) {
            return Optional.empty();
        }
        String repoFull = ref.substring(0, hash);
        int slash = repoFull.indexOf('/');
        if (slash <= 0 || slash == repoFull.length() - 1) {
            return Optional.empty();
        }
        int number;
        try {
            number = Integer.parseInt(ref.substring(hash + 1).trim());
        }
        catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(new RepoRef(
                repoFull.substring(0, slash), repoFull.substring(slash + 1), repoFull, number));
    }

    private record RepoRef(String owner, String repo, String repoFullName, int number) {}

    private String payloadField(StageEvent event, String field)
    {
        if (event.payloadJson() == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(event.payloadJson()).get(field);
            return node == null || node.isNull() ? null : node.asText();
        }
        catch (JsonProcessingException e) {
            return null;
        }
    }

    private static PhaseRow phaseRow(TaskPhaseEvent e)
    {
        return new PhaseRow(
                e.fromPhase() == null ? null : e.fromPhase().name(),
                e.toPhase().name(),
                e.transitionedAt().toString(),
                e.reason());
    }

    private static CommentRow commentRow(ReviewComment c)
    {
        return new CommentRow(c.file(), c.line(), c.body(), c.source().name(), c.remoteLink());
    }

    private static ReviewCommentSource parseSource(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ReviewCommentSource.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant parseInstant(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static String truncate(String text, int max)
    {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static boolean blank(String s)
    {
        return s == null || s.isBlank();
    }

    private ToolOutcome ok(Object payload)
    {
        try {
            return ToolOutcome.Completed.ok(mapper.writeValueAsString(payload));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise brain tool payload", e);
        }
    }
}
