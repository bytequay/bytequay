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

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRComment;
import com.bytequay.app.domain.LocalPRTimelineEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.LocalPRService;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Local-PR AUTO tool handlers — the {@code record_pr_*} writers a stage agent
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
 * merge is a user-gated action routed through {@code LocalPRController}, never
 * something an agent triggers autonomously.
 */
@Component
public class LocalPRToolHandlers
{
    private static final int SHORT_SHA_LENGTH = 12;

    private final LocalPRService localPr;
    private final TaskStore taskStore;

    public LocalPRToolHandlers(LocalPRService localPr, TaskStore taskStore)
    {
        this.localPr = requireNonNull(localPr, "localPr is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    /** Args for {@code record_pr_description}. */
    public record RecordPrDescriptionArgs(
            @ToolParam(description = "Optional new PR title (a short, imperative summary).")
            String title,
            @ToolParam(description = "The PR description as markdown — what the change does and why.",
                    required = true)
            String description) {}

    @AgentTool(
            name = "record_pr_description",
            description = "Write the local PR's title + description. This edits the local PR row "
                    + "in ByteQuay — it is NOT a GitHub call and nothing is posted remotely.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordPrDescription(RecordPrDescriptionArgs args, ToolCall call)
    {
        return withPr(call, pr -> {
            localPr.updateDetails(pr.id(), args.title(), args.description());
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
            localPr.recordCommit(
                    pr.id(),
                    shortSha(args.sha()),
                    args.message(),
                    args.additions() == null ? 0 : args.additions(),
                    args.deletions() == null ? 0 : args.deletions(),
                    LocalPRTimelineEvent.ACTOR_AGENT);
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
            localPr.recordCheck(pr.id(), args.kind(), args.name(), args.status(), args.durationMs());
            return ToolOutcome.Completed.ok("recorded check " + args.name() + " = " + args.status());
        });
    }

    /** Args for {@code record_local_review}. */
    public record RecordLocalReviewArgs(
            @ToolParam(description = "Set true to flip the PR from local-drafted to local-open — "
                    + "'development done, ready for the user's private review'.",
                    wireName = "request_user_review", required = true) Boolean requestUserReview) {}

    @AgentTool(
            name = "record_local_review",
            description = "Declare development done and hand the local PR to the user for review "
                    + "(flips local-drafted -> local-open). No GitHub interaction.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordLocalReview(RecordLocalReviewArgs args, ToolCall call)
    {
        if (args.requestUserReview() == null || !args.requestUserReview()) {
            return ToolOutcome.Completed.ok("no-op: request_user_review was not set");
        }
        return withPr(call, pr -> {
            localPr.requestUserReview(pr.id(), LocalPRTimelineEvent.ACTOR_AGENT);
            return ToolOutcome.Completed.ok("flipped local PR to local-open for user review");
        });
    }

    /** Args for {@code record_pr_comment}. */
    public record RecordPrCommentArgs(
            @ToolParam(description = "Comment scope: 'pr' (PR-level) or 'file-line' (inline).",
                    required = true) String scope,
            @ToolParam(description = "File path — required for a file-line comment.",
                    wireName = "file_path") String filePath,
            @ToolParam(description = "Line number — required for a file-line comment.",
                    wireName = "line_number") Integer lineNumber,
            @ToolParam(description = "Comment body (markdown).", required = true) String body) {}

    @AgentTool(
            name = "record_pr_comment",
            description = "Add an agent comment to the local PR (PR-level or inline). Local comments "
                    + "never migrate to GitHub — they are stripped when the PR is pushed.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordPrComment(RecordPrCommentArgs args, ToolCall call)
    {
        return withPr(call, pr -> {
            localPr.addComment(
                    pr.id(),
                    LocalPRComment.ORIGIN_LOCAL,
                    args.scope(),
                    args.filePath(),
                    args.lineNumber(),
                    LocalPRTimelineEvent.ACTOR_AGENT,
                    args.body(),
                    /* parentCommentId */ null);
            return ToolOutcome.Completed.ok("recorded PR comment");
        });
    }

    /** Args for {@code resolve_pr_comment}. */
    public record ResolvePrCommentArgs(
            @ToolParam(description = "The comment id to resolve.",
                    wireName = "comment_id", required = true) String commentId,
            @ToolParam(description = "How it was resolved: 'addressed' or 'dismissed'.")
            String resolution) {}

    @AgentTool(
            name = "resolve_pr_comment",
            description = "Mark a local PR comment resolved after addressing or dismissing it.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome resolvePrComment(ResolvePrCommentArgs args, ToolCall call)
    {
        if (args.commentId() == null || args.commentId().isBlank()) {
            return ToolOutcome.Completed.error("comment_id is required");
        }
        try {
            localPr.resolveComment(args.commentId());
            return ToolOutcome.Completed.ok("resolved comment " + args.commentId());
        }
        catch (IllegalArgumentException e) {
            return ToolOutcome.Completed.error(e.getMessage());
        }
    }

    /** Resolve the running turn's task-scoped local PR — creating the row from
     *  the task's branch if the agent hasn't recorded anything yet (idempotent)
     *  — and apply {@code action}. */
    private ToolOutcome withPr(ToolCall call, PrAction action)
    {
        String taskId = call.taskId();
        if (taskId == null) {
            return ToolOutcome.Completed.error("this tool needs a task-scoped turn");
        }
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
            LocalPR pr = localPr.createForTask(taskId, t.branchName(), base, title, "");
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
        ToolOutcome apply(LocalPR pr);
    }
}
