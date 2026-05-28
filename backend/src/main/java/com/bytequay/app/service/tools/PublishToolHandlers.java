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

import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.ParkedProposalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The PARKED publishers: tools that don't act on GitHub directly but
 * <em>propose</em> a change, parking the active task at AWAITING_REVIEW
 * so the human can approve, edit, or discard it. The actual GitHub /
 * git side effect fires later from PublishService when the user clicks
 * Approve.
 *
 * <p>Each handler resolves the active task, validates, builds the
 * notification payload, parks it, and returns the synchronous "parked"
 * text as a {@link ToolOutcome.Completed}. Because the park is a plain
 * write (no async user-gate), the result is immediate — the lane just
 * echoes the text. The gate-coupled escape hatch ({@code run_shell})
 * and the gate itself ({@code approval_prompt}) keep their async
 * handling in the MCP controller.
 */
@Component
public class PublishToolHandlers
{
    private static final Logger log = LoggerFactory.getLogger(PublishToolHandlers.class);

    /** Hard cap on the unified-diff payload attached to a parked
     *  push / review proposal — notifications live in a SQLite TEXT
     *  column and render inline, so truncate rather than store a
     *  megabyte per park. */
    private static final int PUSH_DIFF_MAX_BYTES = 500_000;

    private final TaskStore taskStore;
    private final WatchedRepoStore watchedRepos;
    private final ParkedProposalService parkedProposals;
    private final GitRunner git;
    private final ObjectMapper mapper;

    public PublishToolHandlers(
            TaskStore taskStore,
            WatchedRepoStore watchedRepos,
            ParkedProposalService parkedProposals,
            GitRunner git,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.parkedProposals = requireNonNull(parkedProposals, "parkedProposals is null");
        this.git = requireNonNull(git, "git is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Args record for {@code request_review}. */
    public record RequestReviewArgs(
            @ToolParam(description = "One- or two-sentence summary of what's ready for review.",
                    required = true) String summary,
            @ToolParam(description = "Optional reply the human can publish as-is or edit.",
                    wireName = "draft_reply") String draftReply) {}

    @AgentTool(
            name = "request_review",
            description = "Park the current task at AWAITING_REVIEW with a proposed diff + reply. "
                    + "Use this when you've finished a unit of work and want the human "
                    + "to review before anything is pushed or commented on GitHub.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome requestReview(RequestReviewArgs args, ToolCall call)
    {
        String summary = orEmpty(args.summary());
        String draftReply = orEmpty(args.draftReply());
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to request review for");
        }
        Task task = active.get();
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return ToolOutcome.Completed.ok("the active task has no worktree — no diff is available for review");
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
        return park(task, payload,
                "Parked at AWAITING_REVIEW. The user will see a notification "
                        + "and can accept or discard it from the thread.");
    }

    /** Args record for {@code post_comment}. */
    public record PostCommentArgs(
            @ToolParam(description = "Markdown-formatted body of the comment.",
                    required = true) String body) {}

    @AgentTool(
            name = "post_comment",
            description = "Ask the user to post a comment on the active task's linked PR. "
                    + "Body is shown to the user before sending; on Approve the "
                    + "server makes the GitHub API call with the per-repo PAT.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome postComment(PostCommentArgs args, ToolCall call)
    {
        String body = orEmpty(args.body());
        if (body.isBlank()) {
            return ToolOutcome.Completed.ok("body is required");
        }
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to comment on");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "post_comment");
        payload.put("body", body);
        payload.put("pr", prMap(prRef.get()));
        payload.put("source", "mcp:post_comment");
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW. The user will review the comment body and "
                        + "approve, edit, or discard from the thread.");
    }

    /** Args record for {@code push} — no args. */
    public record PushArgs() {}

    @AgentTool(
            name = "push",
            description = "Push the active task's branch upstream. The user must approve "
                    + "before the push runs; on Approve the server invokes "
                    + "`git push` from the task's worktree.",
            security = SecurityType.GIT_PUSH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome push(PushArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to push");
        }
        Task task = active.get();
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return ToolOutcome.Completed.ok("the active task has no worktree — push needs an isolated branch");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "push");
        payload.put("branch", task.branchName());
        payload.put("baseBranch", task.baseBranch());
        payload.put("worktreePath", task.worktreePath());
        attachPushDiffToPayload(payload, Path.of(task.worktreePath()), task);
        payload.put("source", "mcp:push");
        return park(task, payload,
                "Parked at AWAITING_REVIEW. The user will review the diff and "
                        + "approve or discard from the thread.");
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
            name = "reply_review_thread",
            description = "Reply inside an existing review thread on the active task's "
                    + "linked PR. The body is parked at AWAITING_REVIEW; the user reviews "
                    + "it in the publish gate and the server posts via the GitHub reply-to-"
                    + "review-comment API on Approve.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome replyReviewThread(ReplyReviewThreadArgs args, ToolCall call)
    {
        long rootCommentId = args.rootCommentId() == null ? 0L : args.rootCommentId();
        String body = orEmpty(args.body());
        if (rootCommentId <= 0L) {
            return ToolOutcome.Completed.ok("root_comment_id is required");
        }
        if (body.isBlank()) {
            return ToolOutcome.Completed.ok("body is required");
        }
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to reply on");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "reply_review_thread");
        payload.put("rootCommentId", rootCommentId);
        payload.put("body", body);
        payload.put("pr", prMap(prRef.get()));
        payload.put("source", "mcp:reply_review_thread");
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW. The user will review the reply and "
                        + "approve, edit, or discard from the thread.");
    }

    /** Args record for {@code approve_pr}. */
    public record ApprovePrArgs(
            @ToolParam(description = "Optional review summary shown alongside the approval. "
                    + "Empty submits an approval with no body — GitHub allows this.")
            String body) {}

    @AgentTool(
            name = "approve_pr",
            description = "Submit an APPROVE review on the active task's linked PR. "
                    + "The proposed approval (and any body) is parked at AWAITING_REVIEW; "
                    + "on Approve the server fires a GitHub review create with event=APPROVE.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome approvePr(ApprovePrArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to approve");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "approve_pr");
        payload.put("body", orEmpty(args.body()));
        payload.put("pr", prMap(prRef.get()));
        payload.put("source", "mcp:approve_pr");
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW. The user will review the approval and "
                        + "approve, edit, or discard from the thread.");
    }

    /** Args record for {@code merge_pr}. */
    public record MergePrArgs(
            @ToolParam(description = "Merge method — one of 'squash' (default), 'merge', or "
                    + "'rebase'. Repos that don't allow the chosen method will surface that "
                    + "as a publish failure on the Approve step.")
            String strategy) {}

    @AgentTool(
            name = "merge_pr",
            description = "Merge the active task's linked PR. The proposed merge is parked "
                    + "at AWAITING_REVIEW with the chosen strategy; on Approve the server "
                    + "fires the GitHub merge endpoint.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome mergePr(MergePrArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to merge");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String strategy = normaliseMergeStrategy(args.strategy());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "merge_pr");
        payload.put("strategy", strategy);
        payload.put("pr", prMap(prRef.get()));
        payload.put("source", "mcp:merge_pr");
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW (merge_pr, strategy=" + strategy + "). "
                        + "The user will approve, edit, or discard from the thread.");
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
            name = "create_review_comment",
            description = "Post a line-anchored review comment on the active task's linked "
                    + "PR. The body + anchor are parked at AWAITING_REVIEW; on Approve the "
                    + "server fires the GitHub inline-review-comment API.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome createReviewComment(CreateReviewCommentArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to comment on");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String filePath = orEmpty(args.filePath());
        String body = orEmpty(args.body());
        String commitId = orEmpty(args.commitId());
        int line = args.line() == null ? 0 : args.line();
        if (filePath.isBlank() || body.isBlank() || commitId.isBlank() || line <= 0) {
            return ToolOutcome.Completed.ok("file_path, line, body, commit_id are required");
        }
        String side = args.side() == null || args.side().isBlank() ? "RIGHT" : args.side();
        Integer startLine = args.startLine();
        String startSide = orEmpty(args.startSide());
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
        payload.put("pr", prMap(prRef.get()));
        payload.put("source", "mcp:create_review_comment");
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW. The user will review the inline comment and "
                        + "approve, edit, or discard from the thread.");
    }

    /** Args record for {@code update_pr_body}. */
    public record UpdatePrBodyArgs(
            @ToolParam(description = "New PR description (markdown). Replaces the existing "
                    + "body wholesale — set it to the full final text, not a diff.",
                    required = true) String body) {}

    @AgentTool(
            name = "update_pr_body",
            description = "Rewrite the active task's linked PR description. The new body is "
                    + "parked at AWAITING_REVIEW; on Approve the server PATCHes the PR via "
                    + "the GitHub update-pull endpoint. Title is left alone.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome updatePrBody(UpdatePrBodyArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to update");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String body = orEmpty(args.body());
        if (body.isBlank()) {
            return ToolOutcome.Completed.ok("body is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "update_pr_body");
        payload.put("body", body);
        payload.put("pr", prMap(prRef.get()));
        payload.put("source", "mcp:update_pr_body");
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW. The user will review the new PR body and "
                        + "approve, edit, or discard from the thread.");
    }

    /** Args record for {@code request_reviewer}. */
    public record RequestReviewerArgs(
            @ToolParam(description = "GitHub login (no '@') of the user to request a review "
                    + "from. Org teams aren't supported through this tool today.",
                    required = true) String reviewer) {}

    @AgentTool(
            name = "request_reviewer",
            description = "Request a reviewer on the active task's linked PR. The request "
                    + "is parked at AWAITING_REVIEW; on Approve the server adds the login "
                    + "via the GitHub request-review endpoint.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome requestReviewer(RequestReviewerArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to request review on");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String reviewer = orEmpty(args.reviewer()).trim();
        if (reviewer.isBlank()) {
            return ToolOutcome.Completed.ok("reviewer (GitHub login) is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "request_reviewer");
        payload.put("reviewer", reviewer);
        payload.put("pr", prMap(prRef.get()));
        payload.put("source", "mcp:request_reviewer");
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW. The user will approve or discard the reviewer "
                        + "request from the thread.");
    }

    /** Args record for {@code comment_on_issue}. */
    public record CommentOnIssueArgs(
            @ToolParam(description = "Issue number to comment on. The repo is the active "
                    + "task's repo (the same one the task's worktree was cut from).",
                    required = true, wireName = "issue_number") Integer issueNumber,
            @ToolParam(description = "Markdown body of the comment.",
                    required = true) String body) {}

    @AgentTool(
            name = "comment_on_issue",
            description = "Post a comment on an issue in the active task's repo. The body "
                    + "is parked at AWAITING_REVIEW; on Approve the server posts via the "
                    + "GitHub issue-comment endpoint.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome commentOnIssue(CommentOnIssueArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to comment on");
        }
        Optional<WatchedRepo> repo = resolveRepoFromTask(active.get());
        if (repo.isEmpty()) {
            return ToolOutcome.Completed.ok("active task's workingDir doesn't match any watched repo");
        }
        int issueNumber = args.issueNumber() == null ? 0 : args.issueNumber();
        String body = orEmpty(args.body());
        if (issueNumber <= 0) {
            return ToolOutcome.Completed.ok("issue_number is required");
        }
        if (body.isBlank()) {
            return ToolOutcome.Completed.ok("body is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "comment_on_issue");
        payload.put("body", body);
        payload.put("issue", issueMap(repo.get(), issueNumber));
        payload.put("source", "mcp:comment_on_issue");
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW. The user will review the issue comment and "
                        + "approve, edit, or discard from the thread.");
    }

    /** Args record for {@code set_issue_state}. */
    public record SetIssueStateArgs(
            @ToolParam(description = "Issue number to flip. The repo is the active task's repo.",
                    required = true, wireName = "issue_number") Integer issueNumber,
            @ToolParam(description = "Target state — 'open' or 'closed'.",
                    required = true) String state) {}

    @AgentTool(
            name = "set_issue_state",
            description = "Flip an issue between 'open' and 'closed' in the active task's "
                    + "repo. The proposed flip is parked at AWAITING_REVIEW; on Approve the "
                    + "server PATCHes via the GitHub issue endpoint.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome setIssueState(SetIssueStateArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to flip");
        }
        Optional<WatchedRepo> repo = resolveRepoFromTask(active.get());
        if (repo.isEmpty()) {
            return ToolOutcome.Completed.ok("active task's workingDir doesn't match any watched repo");
        }
        int issueNumber = args.issueNumber() == null ? 0 : args.issueNumber();
        String state = orEmpty(args.state()).trim().toLowerCase(Locale.ROOT);
        if (issueNumber <= 0) {
            return ToolOutcome.Completed.ok("issue_number is required");
        }
        if (!"open".equals(state) && !"closed".equals(state)) {
            return ToolOutcome.Completed.ok("state must be 'open' or 'closed'");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "set_issue_state");
        payload.put("state", state);
        payload.put("issue", issueMap(repo.get(), issueNumber));
        payload.put("source", "mcp:set_issue_state");
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW (set_issue_state, " + state + "). "
                        + "The user will approve or discard from the thread.");
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
            name = "open_pr",
            description = "Open a new pull request in the active task's repo. The proposed "
                    + "PR is parked at AWAITING_REVIEW with title + body + head/base + draft "
                    + "flag captured in the notification; on Approve the server fires the "
                    + "GitHub create-pull-request API. Use after push has landed the head "
                    + "branch on the remote.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome openPr(OpenPrArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to open a PR for");
        }
        Optional<WatchedRepo> repo = resolveRepoFromTask(active.get());
        if (repo.isEmpty()) {
            return ToolOutcome.Completed.ok("active task's workingDir doesn't match any watched repo");
        }
        String title = orEmpty(args.title()).trim();
        if (title.isBlank()) {
            return ToolOutcome.Completed.ok("title is required");
        }
        String head = orEmpty(args.head()).trim();
        if (head.isBlank()) {
            head = active.get().branchName() == null ? "" : active.get().branchName();
        }
        String base = orEmpty(args.base()).trim();
        if (base.isBlank()) {
            base = active.get().baseBranch() == null ? "main" : active.get().baseBranch();
        }
        if (head.isBlank()) {
            return ToolOutcome.Completed.ok("head branch could not be resolved — pass head explicitly");
        }
        String body = orEmpty(args.body());
        boolean draft = args.draft() != null && args.draft();
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
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW (open_pr · " + head + " → " + base + "). "
                        + "The user will review the PR title/body and approve or discard "
                        + "from the thread.");
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
            name = "publish_review",
            description = "Submit a full review on the active task's linked PR — summary "
                    + "body + a batch of line-anchored inline comments + an event "
                    + "(APPROVE / REQUEST_CHANGES / COMMENT). The whole bundle parks at "
                    + "AWAITING_REVIEW; on Approve the server fires one createReview call "
                    + "that posts the body + every inline comment + the verdict in a "
                    + "single GitHub round-trip.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome publishReview(PublishReviewArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to review");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String event = orEmpty(args.event()).trim().toUpperCase(Locale.ROOT);
        if (event.isEmpty()) {
            event = "COMMENT";
        }
        if (!event.equals("APPROVE") && !event.equals("REQUEST_CHANGES") && !event.equals("COMMENT")) {
            event = "COMMENT";
        }
        String body = orEmpty(args.body());
        JsonNode comments = args.comments();
        if (comments != null && !comments.isNull() && !comments.isArray()) {
            return ToolOutcome.Completed.ok("comments must be an array");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "publish_review");
        payload.put("event", event);
        payload.put("body", body);
        // Re-serialise comments through Jackson so the stored array has a
        // stable shape regardless of what raw JSON the agent sent.
        payload.put("comments", comments == null || comments.isNull()
                ? mapper.createArrayNode()
                : comments);
        payload.put("pr", prMap(prRef.get()));
        payload.put("source", "mcp:publish_review");
        int commentCount = comments == null ? 0 : comments.size();
        return park(active.get(), payload,
                "Parked at AWAITING_REVIEW (publish_review · " + event + " · "
                        + commentCount + " inline comment(s)). The user will approve, "
                        + "edit, or discard from the thread.");
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
            name = "next_task",
            description = "Propose advancing from the current task. The user reviews the "
                    + "current diff and must approve before the server pushes the branch, "
                    + "opens or finds the PR, parks this task, and creates the next worktree.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome nextTask(NextTaskArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to advance");
        }
        Task task = active.get();
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return ToolOutcome.Completed.ok("the active task has no worktree — next task needs an isolated branch");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "next_task");
        payload.put("threadId", call.threadId());
        payload.put("taskId", task.id());
        payload.put("branch", task.branchName());
        payload.put("baseBranch", task.baseBranch());
        payload.put("worktreePath", task.worktreePath());
        payload.put("nextTitle", orEmpty(args.nextTitle()).trim());
        payload.put("baseMode", normaliseBaseMode(args.baseMode()));
        attachPushDiffToPayload(payload, Path.of(task.worktreePath()), task);
        payload.put("source", "mcp:next_task");
        return park(task, payload,
                "Parked at AWAITING_REVIEW (next_task). The user will review the "
                        + "diff and approve or discard advancing to the next task.");
    }

    /** Args record for {@code ship_task}. */
    public record ShipTaskArgs(
            @ToolParam(description = "Title for the next task. Optional — the new task is "
                    + "created on Approve and takes 'task N+1' when omitted.",
                    wireName = "next_title") String nextTitle,
            @ToolParam(description = "Base mode for the next task's branch — 'main' "
                    + "(default) or 'stacked' to chain on this task's branch.",
                    wireName = "base_mode") String baseMode) {}

    @AgentTool(
            name = "ship_task",
            description = "Ship the active task: park the proposal at AWAITING_REVIEW so "
                    + "the user reviews the diff + PR state, then on Approve the server "
                    + "runs the full ship-and-continue flow (push, open or update PR, "
                    + "mark the task COMPLETED, cut the next task). Use when the unit of "
                    + "work is genuinely done and ready for human sign-off.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome shipTask(ShipTaskArgs args, ToolCall call)
    {
        Optional<Task> active = taskStore.findActiveTaskForThread(call.threadId());
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to ship");
        }
        Task task = active.get();
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return ToolOutcome.Completed.ok("the active task has no worktree — ship needs an isolated branch");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "ship_task");
        payload.put("threadId", call.threadId());
        payload.put("taskId", task.id());
        payload.put("branch", task.branchName());
        payload.put("baseBranch", task.baseBranch());
        payload.put("worktreePath", task.worktreePath());
        payload.put("nextTitle", orEmpty(args.nextTitle()).trim());
        payload.put("baseMode", normaliseBaseMode(args.baseMode()));
        attachPushDiffToPayload(payload, Path.of(task.worktreePath()), task);
        payload.put("source", "mcp:ship_task");
        return park(task, payload,
                "Parked at AWAITING_REVIEW (ship_task). The user will review the "
                        + "proposed ship and approve, edit, or discard from the thread.");
    }

    // ── shared helpers ───────────────────────────────────────────────

    /** Persist a parked proposal and return the synchronous text the
     *  agent sees. The park is transactional; on failure we return a
     *  retryable soft message (no partial state was left) rather than a
     *  hard tool error. */
    private ToolOutcome park(Task task, Map<String, Object> payload, String parkedText)
    {
        try {
            parkedProposals.park(task, payload);
            return ToolOutcome.Completed.ok(parkedText);
        }
        catch (RuntimeException e) {
            log.warn("failed to park task {} for review ({}): {}",
                    task.id(), payload.get("action"), e.getMessage());
            return ToolOutcome.Completed.ok(
                    "Could not save the review notification (" + e.getMessage()
                            + "). The task was not parked — please retry.");
        }
    }

    private static Map<String, Object> prMap(PullRequestRef ref)
    {
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("owner", ref.owner());
        pr.put("repo", ref.repo());
        pr.put("number", ref.number());
        return pr;
    }

    private static Map<String, Object> issueMap(WatchedRepo repo, int number)
    {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("owner", repo.owner());
        issue.put("repo", repo.repo());
        issue.put("number", number);
        return issue;
    }

    private static String orEmpty(String s)
    {
        return s == null ? "" : s;
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

    private static String normaliseBaseMode(String raw)
    {
        String mode = raw == null ? "main" : raw.trim().toLowerCase(Locale.ROOT);
        return "stacked".equals(mode) ? "stacked" : "main";
    }

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

    /** Adds the unified diff + companion fields to {@code payload}.
     *  Failures don't abort the park — the audit trail is still useful
     *  even without the preview. */
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
            payload.put("diff", git.diff(worktree, base, "HEAD", PUSH_DIFF_MAX_BYTES));
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
     *  branch tip if pushed, else the remote base branch, else the
     *  local base branch; null when nothing resolves. */
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
            log.warn("ref probe in {} failed while choosing diff base: {}", worktree, e.getMessage());
        }
        return null;
    }
}
