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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.sqlite.SqliteReviewRoundStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.BrainReviewServiceImpl;
import com.bytequay.app.service.runs.AgentRunServiceImpl;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.WorktreeService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Local-PR AUTO tool handlers — the {@code record_pr_*} writers a Task agent
 * calls to build the local PR artifact (design #44). They resolve the target
 * PR from the running turn's stamped task id ({@link ToolCall#taskId()}); a
 * trunk turn (null task) can't call them.
 *
 * <p>All gate at {@link AgentRole#TASK}: Dev, CI-Fix, and Comments agents all
 * run as {@code TASK}, so the role axis can't express "the Comments stage sees
 * only the comment tools" — that finer per-stage restriction is a stage-level
 * concern, not a role one, and is enforced where stages compose their tool set.
 *
 * <p>{@code push_pr} / {@code merge_pr} are deliberately NOT tools — a push or
 * merge is a user-gated action routed through {@code PRController}, never
 * something an agent triggers autonomously.
 */
@Component
public class PRRecordToolHandlers
{
    private static final int SHORT_SHA_LENGTH = 12;

    private final PRService prService;
    private final TaskStore taskStore;
    private final BrainReviewServiceImpl brainReview;
    private final SqliteReviewRoundStore roundStore;
    private final AgentRunServiceImpl agentRuns;
    private final TaskPhaseMachine phaseMachine;
    private final GitRunner git;

    public PRRecordToolHandlers(
            PRService prService, TaskStore taskStore, BrainReviewServiceImpl brainReview,
            SqliteReviewRoundStore roundStore, AgentRunServiceImpl agentRuns, TaskPhaseMachine phaseMachine,
            GitRunner git)
    {
        this.prService = requireNonNull(prService, "prService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.git = requireNonNull(git, "git is null");
    }

    /** Args for {@code record_pr_progress}. */
    public record RecordPrProgressArgs(
            @ToolParam(description = "PR preparation phase: starting | creating-draft.", required = true)
            String phase) {}

    @AgentTool(
            name = "record_pr_progress",
            description = "Record a durable PR-preparation milestone in the PR, Development, "
                    + "and Brain timelines. Call starting before inspecting the final branch and "
                    + "repository template, then creating-draft before writing the title/body. "
                    + "Repeated calls for the same phase are idempotent.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordPrProgress(RecordPrProgressArgs args, ToolCall call)
    {
        if (!PRTimelineEntry.PHASE_STARTING.equals(args.phase())
                && !PRTimelineEntry.PHASE_CREATING_DRAFT.equals(args.phase())) {
            return ToolOutcome.Completed.error("phase must be starting or creating-draft");
        }
        return withPr(call, pr -> {
            prService.recordProgress(pr.id(), args.phase());
            return ToolOutcome.Completed.ok("recorded PR progress: " + args.phase());
        });
    }

    /** Args for {@code record_pr_description}. */
    public record RecordPrDescriptionArgs(
            @ToolParam(description = "Optional new PR title (a short, imperative summary).")
            String title,
            @ToolParam(description = "The complete PR description as markdown. Follow the repository's "
                    + "pull-request template exactly when present; otherwise keep it proportional to the "
                    + "change (one line for a small change).",
                    required = true)
            String description) {}

    @AgentTool(
            name = "record_pr_description",
            description = "Write the local PR's final title + complete description after inspecting "
                    + "the whole branch, its commit history, final diff, and repository template. "
                    + "This edits only ByteQuay's local PR row; nothing is posted remotely.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordPrDescription(RecordPrDescriptionArgs args, ToolCall call)
    {
        return withPr(call, pr -> {
            // Also guarantees the milestone when an older/partial prompt omits
            // the explicit creating-draft progress call.
            prService.recordProgress(pr.id(), PRTimelineEntry.PHASE_CREATING_DRAFT);
            prService.updateDetails(pr.id(), args.title(), args.description());
            return ToolOutcome.Completed.ok("recorded PR description");
        });
    }

    /** Args for {@code record_pr_commit}. */
    public record RecordPrCommitArgs(
            @ToolParam(description = "The commit's short SHA.", required = true) String sha,
            @ToolParam(description = "The commit message.", required = true) String message,
            @ToolParam(description = "Lines added in this commit.") Integer additions,
            @ToolParam(description = "Lines deleted in this commit.") Integer deletions) {}

    @AgentTool(
            name = "record_pr_commit",
            description = "Append a commit to the local PR's timeline. Creates the local PR row "
                    + "on the first commit (idempotent). Local-only until the user approves a push.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordPrCommit(RecordPrCommitArgs args, ToolCall call)
    {
        if (args.sha() == null || args.sha().isBlank()) {
            return ToolOutcome.Completed.error("sha is required");
        }
        return withPr(call, pr -> {
            prService.recordCommit(
                    pr.id(),
                    shortSha(args.sha()),
                    args.message(),
                    args.additions() == null ? 0 : args.additions(),
                    args.deletions() == null ? 0 : args.deletions(),
                    PRTimelineEntry.ACTOR_AGENT);
            return ToolOutcome.Completed.ok("recorded commit " + shortSha(args.sha()));
        });
    }

    /** Args for {@code record_pr_check}. */
    public record RecordPrCheckArgs(
            @ToolParam(description = "Check origin: 'local' (validation scripts run every iteration) "
                    + "or 'remote' (GitHub Actions).", required = true) String kind,
            @ToolParam(description = "Check name, e.g. 'mvn verify' or 'backend / unit-tests'.",
                    required = true) String name,
            @ToolParam(description = "Result: pending | running | passed | failed | neutral.",
                    required = true) String status,
            @ToolParam(description = "Wall-clock duration in milliseconds.",
                    wireName = "duration_ms") Long durationMs) {}

    @AgentTool(
            name = "record_pr_check",
            description = "Record a check run (local validation or remote CI) on the local PR. "
                    + "A finished check adds a ci event to the PR timeline.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordPrCheck(RecordPrCheckArgs args, ToolCall call)
    {
        return withPr(call, pr -> {
            prService.recordCheck(pr.id(), args.kind(), args.name(), args.status(), args.durationMs());
            return ToolOutcome.Completed.ok("recorded check " + args.name() + " = " + args.status());
        });
    }

    /** Args for {@code record_local_review}. */
    public record RecordLocalReviewArgs(
            @ToolParam(description = "Set true to finish the Development handoff. Validation then "
                    + "starts Brain adversarial review; only its conclusion flips the PR local-open.",
                    wireName = "request_user_review", required = true) Boolean requestUserReview) {}

    @AgentTool(
            name = "record_local_review",
            description = "Declare Development done. Commit your work on the Task branch "
                    + "first, then the idle lifecycle runs validation "
                    + "and starts Brain adversarial review. Failed validation enters the bounded local "
                    + "fix loop; only Brain approval or bounded escalation hands the private local PR "
                    + "to the user. No GitHub interaction.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordLocalReview(RecordLocalReviewArgs args, ToolCall call)
    {
        if (args.requestUserReview() == null || !args.requestUserReview()) {
            return ToolOutcome.Completed.ok("no-op: request_user_review was not set");
        }
        if (taskStore.isV2Task(call.requireTaskId())) {
            return ToolOutcome.Completed.error(
                    "V2 Local Development completes through its exact StageTurn result");
        }
        return withPr(call, pr -> {
            return TaskPhaseMachine.withTaskLock(pr.taskId(), () -> {
                Task task = taskStore.findTaskById(pr.taskId()).orElse(null);
                if (task == null) {
                    return ToolOutcome.Completed.error("no task for local PR " + pr.id());
                }
                if (task.phase() == TaskPhase.IMPLEMENTING) {
                    String error = prepareHandoff(task, pr);
                    if (error != null) {
                        return ToolOutcome.Completed.error(error);
                    }
                    phaseMachine.transition(
                            task.id(), TaskPhase.VALIDATING, "development_handoff", Actor.AGENT);
                    return ToolOutcome.Completed.ok(
                            "development handoff recorded — local validation will start Brain review when ready");
                }
                if (task.phase() != TaskPhase.INTERNAL_REVIEW) {
                    return ToolOutcome.Completed.ok(
                            "development handoff already recorded — waiting for local validation");
                }
                PR after = brainReview.reviewBeforeLocalOpen(pr.id(), PRTimelineEntry.ACTOR_AGENT);
                return ToolOutcome.Completed.ok(PR.STATUS_LOCAL_OPEN.equals(after.status())
                        ? "flipped local PR to local-open for user review"
                        : "development done — the brain is reviewing the diff before handing it to the user");
            });
        });
    }

    private String prepareHandoff(Task task, PR pr)
    {
        if (task.worktreePath() == null || task.worktreePath().isBlank()
                || task.branchName() == null || task.branchName().isBlank()
                || task.baseBranch() == null || task.baseBranch().isBlank()) {
            return "development handoff requires a task worktree, branch, and base branch";
        }
        Path worktree = Path.of(task.worktreePath());
        try {
            if (git.hasUncommittedChanges(worktree)) {
                git.stageAll(worktree, List.of(WorktreeService.HOOK_DIR_REL));
                git.commit(worktree, checkpointMessage(pr));
            }
            Integer ahead = git.commitCountUniqueTo(worktree, task.branchName(), task.baseBranch());
            if (ahead == null || ahead <= 0) {
                return "development handoff rejected: create at least one commit ahead of the base branch first";
            }
            return null;
        }
        catch (IOException e) {
            return parkHandoffFailure(task,
                    "Development handoff could not checkpoint Git changes: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return parkHandoffFailure(task,
                    "Development handoff was interrupted while checkpointing Git changes");
        }
    }

    private String parkHandoffFailure(Task task, String error)
    {
        taskStore.saveTask(task.withErrorMessage(error));
        phaseMachine.transition(
                task.id(), TaskPhase.NEEDS_ATTENTION,
                "development_handoff_checkpoint_failed", Actor.AGENT);
        return error;
    }

    private static String checkpointMessage(PR pr)
    {
        return pr.title() == null || pr.title().isBlank()
                ? "Complete local development"
                : pr.title().strip();
    }

    /** Args for {@code record_pr_comment}. */
    public record RecordPrCommentArgs(
            @ToolParam(description = "Comment scope: 'pr' (PR-level) or 'file-line' (inline).",
                    required = true) String scope,
            @ToolParam(description = "File path — required for a file-line comment.",
                    wireName = "file_path") String filePath,
            @ToolParam(description = "Line number — required for a file-line comment.",
                    wireName = "line_number") Integer lineNumber,
            @ToolParam(description = "Comment body (markdown).", required = true) String body,
            @ToolParam(description = "Optional id of the local PR comment this replies to.",
                    wireName = "parent_comment_id") String parentCommentId) {}

    @AgentTool(
            name = "record_pr_comment",
            description = "Add an agent comment to the local PR (PR-level or inline). Local comments "
                    + "never migrate to GitHub — they are stripped when the PR is pushed.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordPrComment(RecordPrCommentArgs args, ToolCall call)
    {
        String author = isBrainReviewTurn(call) ? PRTimelineEntry.ACTOR_BRAIN : PRTimelineEntry.ACTOR_AGENT;
        return withPr(call, pr -> {
            prService.addComment(
                    pr.id(),
                    PRComment.ORIGIN_LOCAL,
                    args.scope(),
                    args.filePath(),
                    args.lineNumber(),
                    /* side */ null,
                    /* startLine */ null,
                    /* startSide */ null,
                    author,
                    args.body(),
                    parentCommentId(args.parentCommentId()));
            return ToolOutcome.Completed.ok("recorded PR comment");
        });
    }

    private static String parentCommentId(String value)
    {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** True while the calling turn is a brain adversarial-review pass (its
     *  stage is a live round in {@code triaging} — plan-rail-runs.md R21/R22)
     *  — attributes the comment to the brain rather than the dev agent. */
    private boolean isBrainReviewTurn(ToolCall call)
    {
        if (call.scope() != ThreadScope.STAGE) {
            return false;
        }
        String taskId = call.requireTaskId();
        String stageId = call.requireStageId();
        return roundStore.findLiveByTask(taskId)
                .filter(r -> ReviewRound.STATUS_TRIAGING.equals(r.status()))
                .filter(r -> r.runId() != null)
                .filter(r -> call.agentRunId() == null || r.runId().equals(call.agentRunId()))
                .flatMap(r -> agentRuns.findById(r.runId()))
                .map(run -> stageId.equals(run.stageId()))
                .orElse(false);
    }

    /** Args for {@code resolve_pr_comment}. */
    public record ResolvePrCommentArgs(
            @ToolParam(description = "The comment id to resolve.",
                    wireName = "comment_id", required = true) String commentId,
            @ToolParam(description = "How it was resolved: 'addressed' or 'dismissed'.")
            String resolution,
            @ToolParam(description = "A short reply, posted under the comment, describing how you "
                    + "addressed it. Required when addressing (mirrors replying on a github.com review "
                    + "thread before resolving it); omit only when dismissing.")
            String reply) {}

    @AgentTool(
            name = "resolve_pr_comment",
            description = "Reply under a local PR comment describing the fix, then mark it resolved — "
                    + "or dismiss it without a reply when you are not acting on it.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome resolvePrComment(ResolvePrCommentArgs args, ToolCall call)
    {
        if (args.commentId() == null || args.commentId().isBlank()) {
            return ToolOutcome.Completed.error("comment_id is required");
        }
        return withPr(call, pr -> {
            PRComment parent = prService.comments(pr.id()).stream()
                    .filter(comment -> args.commentId().equals(comment.id()))
                    .findFirst()
                    .orElse(null);
            if (parent == null) {
                return ToolOutcome.Completed.error(
                        "comment " + args.commentId() + " does not belong to this task's local PR");
            }
            if ("dismissed".equals(args.resolution())) {
                prService.dismissCommentForAgent(args.commentId());
                return ToolOutcome.Completed.ok("dismissed comment " + args.commentId());
            }
            if (args.reply() == null || args.reply().isBlank()) {
                return ToolOutcome.Completed.error(
                        "provide a 'reply' describing how you addressed comment " + args.commentId()
                                + " before resolving it (or set resolution='dismissed' to close it without a fix)");
            }
            // Reply under the comment before resolving so the reviewer sees how
            // it was addressed — the local analog of replying on a github.com
            // review thread and then resolving it.
            prService.addComment(
                    pr.id(), PRComment.ORIGIN_LOCAL, parent.scope(), parent.filePath(),
                    parent.lineNumber(), parent.side(), parent.startLine(), parent.startSide(),
                    PRTimelineEntry.ACTOR_AGENT, args.reply().strip(), args.commentId());
            prService.resolveCommentForAgent(args.commentId());
            return ToolOutcome.Completed.ok("replied and resolved comment " + args.commentId());
        });
    }

    /** Resolve the running turn's task-scoped local PR — creating the row from
     *  the task's branch if the agent hasn't recorded anything yet (idempotent)
     *  — and apply {@code action}. */
    private ToolOutcome withPr(ToolCall call, PrAction action)
    {
        if (call.scope() == ThreadScope.TRUNK) {
            return ToolOutcome.Completed.error("this tool needs a task-scoped turn");
        }
        String taskId = call.requireTaskId();
        Optional<Task> task = taskStore.findTaskById(taskId);
        if (task.isEmpty()) {
            return ToolOutcome.Completed.error("unknown task: " + taskId);
        }
        Task t = task.get();
        if (t.branchName() == null || t.branchName().isBlank()) {
            return ToolOutcome.Completed.error("task has no branch yet");
        }
        String base = t.baseBranch() == null || t.baseBranch().isBlank() ? "main" : t.baseBranch();
        String title = t.name() != null && !t.name().isBlank() ? t.name() : t.branchName();
        try {
            PR pr = prService.createForTask(taskId, t.branchName(), base, title, "");
            return action.apply(pr);
        }
        catch (IllegalArgumentException e) {
            return ToolOutcome.Completed.error(e.getMessage());
        }
    }

    private static String shortSha(String sha)
    {
        String trimmed = sha.strip();
        return trimmed.length() <= SHORT_SHA_LENGTH ? trimmed : trimmed.substring(0, SHORT_SHA_LENGTH);
    }

    @FunctionalInterface
    private interface PrAction
    {
        ToolOutcome apply(PR pr);
    }
}
