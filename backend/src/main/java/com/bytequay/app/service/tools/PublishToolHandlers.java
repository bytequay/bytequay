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
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.threads.ParkedProposalService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Strings.nullToEmpty;
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

    /** Phases that mean "still implementing" — a ship/push proposal from one
     *  of these fast-forwards to AWAITING_PUSH so the stepper reflects the
     *  review/push stage instead of staying on "Implement". */
    private static final Set<TaskPhase> PRE_PUSH_PHASES = EnumSet.of(
            TaskPhase.IMPLEMENTING,
            TaskPhase.VALIDATING, TaskPhase.INTERNAL_REVIEW);

    private final TaskStore taskStore;
    private final WatchedRepoStore watchedRepos;
    private final ParkedProposalService parkedProposals;
    private final GitRunner git;
    private final ObjectMapper mapper;
    private final TaskPhaseMachine taskPhaseMachine;
    private final PullRequestService pullRequestService;
    private final PRService prService;

    public PublishToolHandlers(
            TaskStore taskStore,
            WatchedRepoStore watchedRepos,
            ParkedProposalService parkedProposals,
            GitRunner git,
            ObjectMapper mapper,
            TaskPhaseMachine taskPhaseMachine,
            PullRequestService pullRequestService,
            PRService prService)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.parkedProposals = requireNonNull(parkedProposals, "parkedProposals is null");
        this.git = requireNonNull(git, "git is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.taskPhaseMachine = requireNonNull(taskPhaseMachine, "taskPhaseMachine is null");
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
        this.prService = requireNonNull(prService, "prService is null");
    }

    /** Args record for {@code validate}. */
    public record ValidateArgs(
            @ToolParam(description = "Optional one-line note on what you're validating "
                    + "(tests, build, lint). Recorded as the phase-transition reason.")
            String summary) {}

    @AgentTool(
            name = "validate",
            description = "Mark the active task as VALIDATING so the flow stepper reflects the "
                    + "validation stage. This ONLY advances the dev-lifecycle phase — it does "
                    + "not run anything; run your tests / build / lint yourself, then call this "
                    + "to record that you're verifying. No-op if the task is already at or past "
                    + "validation. (Validation passing is implied once you request review or "
                    + "ship — review sits behind validate in the lifecycle.)",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome validate(ValidateArgs args, ToolCall call)
    {
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to validate");
        }
        Task task = active.get();
        if (hasPrivateLocalPr(task)) {
            return ToolOutcome.Completed.ok(
                    "Not advanced — record_local_review is the sole development handoff "
                            + "for a task with a Local PR.");
        }
        if (task.phase() != TaskPhase.IMPLEMENTING) {
            return ToolOutcome.Completed.ok(
                    "task is already at or past validation (phase " + task.phase() + "); no change");
        }
        String note = nullToEmpty(args.summary()).trim();
        String reason = note.isEmpty() ? "agent_validating" : "validating: " + note;
        taskPhaseMachine.transition(task.id(), TaskPhase.VALIDATING, reason, Actor.AGENT);
        return ToolOutcome.Completed.ok("Validating — phase advanced to VALIDATING.");
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
    @Concept(
            name = "request_review",
            kind = ConceptKind.VERB,
            definition = "Park the active task at AWAITING_REVIEW with a proposed diff + "
                    + "reply. The agent's way of saying \"I'm done — please look\" "
                    + "without itself pushing or commenting on GitHub.",
            examples = "An agent finishes a refactor and calls request_review with the "
                    + "summary + reply; the user sees a park card and decides.",
            relatedConcepts = {"task", "awaiting_review", "ship"})
    public ToolOutcome requestReview(RequestReviewArgs args, ToolCall call)
    {
        String summary = nullToEmpty(args.summary());
        String draftReply = nullToEmpty(args.draftReply());
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to request review for");
        }
        Task task = active.get();
        Optional<ToolOutcome> rejected = rejectLegacyPublishForLocalPr(task);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return ToolOutcome.Completed.ok("the active task has no worktree — no diff is available for review");
        }
        DiffBundle bundle = collectDiffBundle(Path.of(task.worktreePath()), task);
        return park(task, new ParkedProposal.RequestReview(
                        summary,
                        draftReply.isEmpty() ? null : draftReply,
                        task.branchName(),
                        task.baseBranch(),
                        task.worktreePath(),
                        bundle.diffBase(),
                        bundle.diff(),
                        bundle.diffError()),
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
        String body = nullToEmpty(args.body());
        if (body.isBlank()) {
            return ToolOutcome.Completed.ok("body is required");
        }
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to comment on");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        return park(active.get(),
                new ParkedProposal.PostComment(body, toPrRef(prRef.get())),
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to push");
        }
        Task task = active.get();
        Optional<ToolOutcome> rejected = rejectLegacyPublishForLocalPr(task);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return ToolOutcome.Completed.ok("the active task has no worktree — push needs an isolated branch");
        }
        DiffBundle bundle = collectDiffBundle(Path.of(task.worktreePath()), task);
        return park(task, new ParkedProposal.Push(
                        task.branchName(),
                        task.baseBranch(),
                        task.worktreePath(),
                        bundle.diffBase(),
                        bundle.diff(),
                        bundle.diffError()),
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
        String body = nullToEmpty(args.body());
        if (rootCommentId <= 0L) {
            return ToolOutcome.Completed.ok("root_comment_id is required");
        }
        if (body.isBlank()) {
            return ToolOutcome.Completed.ok("body is required");
        }
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to reply on");
        }
        if (hasTaskOriginPr(active.get())) {
            return ToolOutcome.Completed.ok(
                    "Not parked — review-round replies are drafted locally with record_round_reply "
                            + "and published atomically from the round gate.");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        return park(active.get(),
                new ParkedProposal.ReplyReviewThread(rootCommentId, body, toPrRef(prRef.get())),
                "Parked at AWAITING_REVIEW. The user will review the reply and "
                        + "approve, edit, or discard from the thread.");
    }

    /** Args record for {@code list_pr_review_threads}. */
    public record ListPrReviewThreadsArgs(
            @ToolParam(description = "owner/repo of the PR (e.g. \"trinodb/trino\"). "
                    + "Omit to use the active task's linked PR.") String repo,
            @ToolParam(description = "PR number. Omit to use the active task's linked PR.",
                    wireName = "pr_number") Integer prNumber) {}

    @AgentTool(
            name = "list_pr_review_threads",
            description = "Read the remote inline review threads + comments on a PR (the "
                    + "reviewer's per-line feedback), with each thread's resolved state and "
                    + "root comment id. Pass repo + pr_number, or omit both to use the active "
                    + "task's linked PR. Use a thread's root comment id with reply_review_thread "
                    + "or resolve_review_thread.",
            security = SecurityType.VCS_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome listPrReviewThreads(ListPrReviewThreadsArgs args, ToolCall call)
    {
        Optional<PullRequestRef> prRef = resolvePrRef(args.repo(), args.prNumber(), call);
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok(
                    "no PR to read — pass repo + pr_number, or link a PR to the active task");
        }
        PullRequestRef ref = prRef.get();
        try {
            var detail = pullRequestService.getPullRequestDetail(
                    ref.owner() + "/" + ref.repo(), ref.number());
            return ToolOutcome.Completed.ok(mapper.writeValueAsString(detail.reviewThreads()));
        }
        catch (JsonProcessingException e) {
            return ToolOutcome.Completed.error("failed to serialise review threads: " + e.getMessage());
        }
        catch (RuntimeException e) {
            return ToolOutcome.Completed.error("failed to read review threads for "
                    + ref.owner() + "/" + ref.repo() + "#" + ref.number() + ": " + e.getMessage());
        }
    }

    /** Args record for {@code resolve_review_thread}. */
    public record ResolveReviewThreadArgs(
            @ToolParam(description = "Id of the root review comment whose thread to resolve. "
                    + "Find it via list_pr_review_threads.",
                    required = true, wireName = "root_comment_id") Long rootCommentId,
            @ToolParam(description = "true to resolve the thread, false to re-open it. "
                    + "Defaults to true.") Boolean resolved,
            @ToolParam(description = "owner/repo of the PR. Omit to use the active task's linked PR.")
            String repo,
            @ToolParam(description = "PR number. Omit to use the active task's linked PR.",
                    wireName = "pr_number") Integer prNumber) {}

    @AgentTool(
            name = "resolve_review_thread",
            description = "Mark a review thread resolved (or re-open it) on a PR. Parked at "
                    + "AWAITING_REVIEW; the user approves in the publish gate and the server "
                    + "runs the GitHub GraphQL resolve on Approve. Pass repo + pr_number, or "
                    + "omit both to use the active task's linked PR.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = {AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome resolveReviewThread(ResolveReviewThreadArgs args, ToolCall call)
    {
        long rootCommentId = args.rootCommentId() == null ? 0L : args.rootCommentId();
        if (rootCommentId <= 0L) {
            return ToolOutcome.Completed.ok("root_comment_id is required");
        }
        boolean resolved = args.resolved() == null || args.resolved();
        Optional<PullRequestRef> prRef = resolvePrRef(args.repo(), args.prNumber(), call);
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok(
                    "no PR to act on — pass repo + pr_number, or link a PR to the active task");
        }
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to park the resolution on");
        }
        if (hasTaskOriginPr(active.get())) {
            return ToolOutcome.Completed.ok(
                    "Not parked — task-origin review threads are resolved through the round gate.");
        }
        return park(active.get(),
                new ParkedProposal.ResolveReviewThread(rootCommentId, resolved, toPrRef(prRef.get())),
                "Parked at AWAITING_REVIEW. The user will approve or discard the "
                        + (resolved ? "resolve" : "re-open") + " from the thread.");
    }

    /** Resolve a PR from explicit repo + number args, falling back to the
     *  turn's task's linked PR when the args are omitted. */
    private Optional<PullRequestRef> resolvePrRef(String repo, Integer prNumber, ToolCall call)
    {
        if (repo != null && !repo.isBlank() && prNumber != null && prNumber > 0) {
            int slash = repo.indexOf('/');
            if (slash <= 0 || slash >= repo.length() - 1) {
                return Optional.empty();
            }
            return Optional.of(new PullRequestRef(
                    repo.substring(0, slash), repo.substring(slash + 1), prNumber));
        }
        return resolveTaskForCall(call).flatMap(this::resolvePrRefFromTask);
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to approve");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        return park(active.get(),
                new ParkedProposal.ApprovePr(nullToEmpty(args.body()), toPrRef(prRef.get())),
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to merge");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String strategy = normaliseMergeStrategy(args.strategy());
        return park(active.get(),
                new ParkedProposal.MergePr(strategy, toPrRef(prRef.get())),
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to comment on");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String filePath = nullToEmpty(args.filePath());
        String body = nullToEmpty(args.body());
        String commitId = nullToEmpty(args.commitId());
        int line = args.line() == null ? 0 : args.line();
        if (filePath.isBlank() || body.isBlank() || commitId.isBlank() || line <= 0) {
            return ToolOutcome.Completed.ok("file_path, line, body, commit_id are required");
        }
        String side = args.side() == null || args.side().isBlank() ? "RIGHT" : args.side();
        String startSide = nullToEmpty(args.startSide());
        return park(active.get(),
                new ParkedProposal.CreateReviewComment(
                        body,
                        filePath,
                        line,
                        side,
                        commitId,
                        args.startLine(),
                        startSide.isBlank() ? null : startSide,
                        toPrRef(prRef.get())),
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to update");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String body = nullToEmpty(args.body());
        if (body.isBlank()) {
            return ToolOutcome.Completed.ok("body is required");
        }
        return park(active.get(),
                new ParkedProposal.UpdatePrBody(body, toPrRef(prRef.get())),
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to request review on");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String reviewer = nullToEmpty(args.reviewer()).trim();
        if (reviewer.isBlank()) {
            return ToolOutcome.Completed.ok("reviewer (GitHub login) is required");
        }
        return park(active.get(),
                new ParkedProposal.RequestReviewer(reviewer, toPrRef(prRef.get())),
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to comment on");
        }
        Optional<WatchedRepo> repo = resolveRepoFromTask(active.get());
        if (repo.isEmpty()) {
            return ToolOutcome.Completed.ok("active task's workingDir doesn't match any watched repo");
        }
        int issueNumber = args.issueNumber() == null ? 0 : args.issueNumber();
        String body = nullToEmpty(args.body());
        if (issueNumber <= 0) {
            return ToolOutcome.Completed.ok("issue_number is required");
        }
        if (body.isBlank()) {
            return ToolOutcome.Completed.ok("body is required");
        }
        return park(active.get(),
                new ParkedProposal.CommentOnIssue(body, toIssueRef(repo.get(), issueNumber)),
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to flip");
        }
        Optional<WatchedRepo> repo = resolveRepoFromTask(active.get());
        if (repo.isEmpty()) {
            return ToolOutcome.Completed.ok("active task's workingDir doesn't match any watched repo");
        }
        int issueNumber = args.issueNumber() == null ? 0 : args.issueNumber();
        String state = nullToEmpty(args.state()).trim().toLowerCase(Locale.ROOT);
        if (issueNumber <= 0) {
            return ToolOutcome.Completed.ok("issue_number is required");
        }
        if (!"open".equals(state) && !"closed".equals(state)) {
            return ToolOutcome.Completed.ok("state must be 'open' or 'closed'");
        }
        return park(active.get(),
                new ParkedProposal.SetIssueState(state, toIssueRef(repo.get(), issueNumber)),
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to open a PR for");
        }
        Optional<ToolOutcome> rejected = rejectLegacyPublishForLocalPr(active.get());
        if (rejected.isPresent()) {
            return rejected.get();
        }
        Optional<WatchedRepo> repo = resolveRepoFromTask(active.get());
        if (repo.isEmpty()) {
            return ToolOutcome.Completed.ok("active task's workingDir doesn't match any watched repo");
        }
        String title = nullToEmpty(args.title()).trim();
        if (title.isBlank()) {
            return ToolOutcome.Completed.ok("title is required");
        }
        String head = nullToEmpty(args.head()).trim();
        if (head.isBlank()) {
            head = active.get().branchName() == null ? "" : active.get().branchName();
        }
        String base = nullToEmpty(args.base()).trim();
        if (base.isBlank()) {
            base = active.get().baseBranch() == null ? "main" : active.get().baseBranch();
        }
        if (head.isBlank()) {
            return ToolOutcome.Completed.ok("head branch could not be resolved — pass head explicitly");
        }
        String body = nullToEmpty(args.body());
        boolean draft = args.draft() != null && args.draft();
        return park(active.get(),
                new ParkedProposal.OpenPr(
                        title,
                        head,
                        base,
                        body,
                        draft,
                        new ParkedProposal.RepoRef(repo.get().owner(), repo.get().repo())),
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to review");
        }
        if (hasTaskOriginPr(active.get())) {
            return ToolOutcome.Completed.ok(
                    "Not parked — task-origin feedback stays private in the canonical review-round gate.");
        }
        Optional<PullRequestRef> prRef = resolvePrRefFromTask(active.get());
        if (prRef.isEmpty()) {
            return ToolOutcome.Completed.ok("no PR linked to the active task — set linked_pr_number first");
        }
        String event = nullToEmpty(args.event()).trim().toUpperCase(Locale.ROOT);
        if (event.isEmpty()) {
            event = "COMMENT";
        }
        if (!event.equals("APPROVE") && !event.equals("REQUEST_CHANGES") && !event.equals("COMMENT")) {
            event = "COMMENT";
        }
        String body = nullToEmpty(args.body());
        JsonNode commentsRaw = args.comments();
        List<ParkedProposal.PublishReview.InlineComment> comments;
        if (commentsRaw == null || commentsRaw.isNull()) {
            comments = List.of();
        }
        else if (!commentsRaw.isArray()) {
            return ToolOutcome.Completed.ok("comments must be an array");
        }
        else {
            try {
                comments = mapper.convertValue(commentsRaw,
                        mapper.getTypeFactory().constructCollectionType(
                                List.class, ParkedProposal.PublishReview.InlineComment.class));
            }
            catch (IllegalArgumentException e) {
                return ToolOutcome.Completed.ok("comments has an invalid shape: " + e.getMessage());
            }
        }
        return park(active.get(),
                new ParkedProposal.PublishReview(event, body, comments, toPrRef(prRef.get())),
                "Parked at AWAITING_REVIEW (publish_review · " + event + " · "
                        + comments.size() + " inline comment(s)). The user will approve, "
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
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to advance");
        }
        Task task = active.get();
        Optional<ToolOutcome> rejected = rejectLegacyPublishForLocalPr(task);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return ToolOutcome.Completed.ok("the active task has no worktree — next task needs an isolated branch");
        }
        DiffBundle bundle = collectDiffBundle(Path.of(task.worktreePath()), task);
        return park(task, new ParkedProposal.NextTask(
                        call.threadId(),
                        task.id(),
                        task.branchName(),
                        task.baseBranch(),
                        task.worktreePath(),
                        nullToEmpty(args.nextTitle()).trim(),
                        normaliseBaseMode(args.baseMode()),
                        bundle.diffBase(),
                        bundle.diff(),
                        bundle.diffError()),
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
                    wireName = "base_mode") String baseMode,
            @ToolParam(description = "Proposed title for the draft PR this ship opens. "
                    + "Falls back to the thread title when omitted.",
                    wireName = "pr_title") String prTitle,
            @ToolParam(description = "Proposed PR description (markdown). If the repo ships a "
                    + "pull-request template (.github/PULL_REQUEST_TEMPLATE.md, a root/docs "
                    + "PULL_REQUEST_TEMPLATE, or a file under .github/PULL_REQUEST_TEMPLATE/), "
                    + "read it and follow it EXACTLY — fill in its sections and add no "
                    + "others. If there is NO template, keep it minimal and match the size "
                    + "of the change: a small / nit change gets ONE line saying what it does "
                    + "(e.g. \"Add a requireNonNull check for currentPredicate in "
                    + "DynamicFilterSnapshot\") — do NOT add Description / Changes / "
                    + "Validation headings, and do NOT list every edit or describe testing. "
                    + "Only a substantial change warrants a short summary paragraph. The user "
                    + "can edit it before approving.",
                    wireName = "pr_body") String prBody) {}

    @AgentTool(
            name = "ship_task",
            description = "Ship the active task: park the proposal at AWAITING_REVIEW so "
                    + "the user reviews the diff + PR state, then on Approve the server "
                    + "runs the full ship-and-continue flow (push, open or update PR, "
                    + "mark the task COMPLETED, cut the next task). Use when the unit of "
                    + "work is genuinely done and ready for human sign-off. When this opens "
                    + "the task's first PR, you MUST pass the final pr_title and complete pr_body "
                    + "previously recorded with record_pr_description. Existing remote PRs do "
                    + "not need those fields for another branch push.",
            security = SecurityType.VCS_PUBLISH,
            gating = Gating.PARKED,
            roles = AgentRole.TASK)
    public ToolOutcome shipTask(ShipTaskArgs args, ToolCall call)
    {
        Optional<Task> active = resolveTaskForCall(call);
        if (active.isEmpty()) {
            return ToolOutcome.Completed.ok("no active task on this thread — nothing to ship");
        }
        Task task = active.get();
        Optional<ToolOutcome> rejected = rejectLegacyPublishForLocalPr(task);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return ToolOutcome.Completed.ok("the active task has no worktree — ship needs an isolated branch");
        }
        Path worktree = Path.of(task.worktreePath());
        // The user reviews COMMITTED diffs before approving the push, so an
        // uncommitted worktree would park an empty branch. Don't commit for the
        // agent — bounce the turn back so it commits its own work (proper
        // message + authorship), then calls ship_task again. Repo-agnostic: this
        // holds regardless of what the target repo's conventions say about who
        // commits.
        if (worktreeIsDirty(worktree)) {
            return ToolOutcome.Completed.ok(
                    "Not shipped — the worktree still has uncommitted changes. The user reviews "
                    + "your committed diff before approving the push, so shipping now would park "
                    + "an empty branch. Commit ALL of your work first: stage every change and "
                    + "commit with a clear message describing it, then call ship_task again.");
        }
        String prTitle = nullToEmpty(args.prTitle()).trim();
        String prBody = nullToEmpty(args.prBody());
        boolean opensFirstPr = task.prNumber() == null && task.linkedPrNumber() == null;
        if (opensFirstPr && (prTitle.isEmpty() || prBody.isBlank())) {
            return ToolOutcome.Completed.ok(
                    "Not shipped — prepare the PR draft first. Inspect the complete branch and "
                    + "repository template, call record_pr_description, then call ship_task again "
                    + "with the same non-blank pr_title and pr_body.");
        }
        DiffBundle bundle = collectDiffBundle(worktree, task);
        return park(task, new ParkedProposal.ShipTask(
                        call.threadId(),
                        task.id(),
                        task.branchName(),
                        task.baseBranch(),
                        task.worktreePath(),
                        nullToEmpty(args.nextTitle()).trim(),
                        normaliseBaseMode(args.baseMode()),
                        bundle.diffBase(),
                        bundle.diff(),
                        bundle.diffError(),
                        prTitle.isEmpty() ? null : prTitle,
                        prBody.isEmpty() ? null : prBody),
                "Parked at AWAITING_REVIEW (ship_task). The user will review the "
                        + "proposed ship and approve, edit, or discard from the thread.");
    }

    // ── shared helpers ───────────────────────────────────────────────

    /**
     * Resolve the task a publish tool acts on from the running turn's
     * stamped task id. Resolving by the turn's task id is what lets a
     * shipped (IN_REVIEW) task still ship / push / comment: the turn is
     * stamped with the task it runs under, so we use that and never guess
     * the thread's "active task" (which excludes shipped tasks).
     */
    private Optional<Task> resolveTaskForCall(ToolCall call)
    {
        if (call.taskId() == null || call.taskId().isBlank()) {
            return Optional.empty();
        }
        return taskStore.findTaskById(call.taskId());
    }

    /** Canonical task-origin PRs advance through record_local_review,
     *  validation, Brain, and the Local Review gate. The legacy publish
     *  tools must not manufacture an earlier AWAITING_PUSH promotion. */
    private Optional<ToolOutcome> rejectLegacyPublishForLocalPr(Task task)
    {
        if (!hasPrivateLocalPr(task)) {
            return Optional.empty();
        }
        return Optional.of(ToolOutcome.Completed.ok(
                "Not parked — this task already has a Local PR. Finish development with "
                        + "record_local_review; validation, Brain review, and Local Review "
                        + "must complete before the app offers Push."));
    }

    private boolean hasPrivateLocalPr(Task task)
    {
        return prService.findByTask(task.id())
                .filter(pr -> PR.ORIGIN_TASK.equals(pr.origin()))
                .filter(pr -> PR.STATUS_LOCAL_DRAFTED.equals(pr.status())
                        || PR.STATUS_LOCAL_OPEN.equals(pr.status()))
                .isPresent();
    }

    private boolean hasTaskOriginPr(Task task)
    {
        return prService.findByTask(task.id())
                .filter(pr -> PR.ORIGIN_TASK.equals(pr.origin()))
                .isPresent();
    }

    /** Persist a parked proposal and return the synchronous text the
     *  agent sees. The park is transactional; on failure we return a
     *  retryable soft message (no partial state was left) rather than a
     *  hard tool error. */
    private ToolOutcome park(Task task, ParkedProposal proposal, String parkedText)
    {
        try {
            parkedProposals.park(task, proposal);
        }
        catch (RuntimeException e) {
            log.warn("failed to park task {} for review ({}): {}",
                    task.id(), proposal.action(), e.getMessage());
            return ToolOutcome.Completed.ok(
                    "Could not save the review notification (%s). The task was not parked — please retry."
                            .formatted(e.getMessage()));
        }
        // A ship / push / next proposal means the agent finished implementing
        // and is awaiting the user's approval to push — fast-forward the
        // dev-lifecycle phase so the flow stepper reads "Push", not "Implement"
        // (CLI agents jump straight here without walking the phases). Best-
        // effort: the park already committed, so a phase failure mustn't mask it.
        if (proposesPush(proposal) && PRE_PUSH_PHASES.contains(task.phase())) {
            try {
                taskPhaseMachine.observe(task.id(), TaskPhase.AWAITING_PUSH, "parked_for_publish");
            }
            catch (RuntimeException e) {
                log.warn("phase fast-forward on park of {} threw: {}", task.id(), e.getMessage());
            }
        }
        return ToolOutcome.Completed.ok(parkedText);
    }

    /** Whether a parked proposal represents the agent finishing the work and
     *  awaiting approval to push it (ship / push / roll-to-next), as opposed
     *  to acting on an already-published PR (comment, request review, …). */
    private static boolean proposesPush(ParkedProposal proposal)
    {
        return proposal instanceof ParkedProposal.ShipTask
                || proposal instanceof ParkedProposal.Push
                || proposal instanceof ParkedProposal.NextTask;
    }

    private static ParkedProposal.PrRef toPrRef(PullRequestRef ref)
    {
        return new ParkedProposal.PrRef(ref.owner(), ref.repo(), ref.number());
    }

    private static ParkedProposal.IssueRef toIssueRef(WatchedRepo repo, int number)
    {
        return new ParkedProposal.IssueRef(repo.owner(), repo.repo(), number);
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

    /** Captured unified-diff preview a {@code request_review} / {@code
     *  push} / {@code next_task} / {@code ship_task} proposal attaches to
     *  its parked notification. On success {@link #diffBase} + {@link
     *  #diff} are populated and {@link #diffError} is null; on failure
     *  the latter carries the cause and {@link #diff} is null. The
     *  proposal records embed these three fields directly so the wire
     *  shape stays flat. */
    private record DiffBundle(String diffBase, String diff, String diffError) {}

    /** Produce the diff preview for a proposal that wants one. Failures
     *  don't abort the park — the audit trail is still useful even
     *  without the preview, so we capture the error in {@link
     *  DiffBundle#diffError} instead of raising. */
    /** True when the worktree has staged, unstaged, or untracked changes. On
     *  any git failure return false — let the ship park rather than wedge the
     *  agent in a commit loop; the user still reviews before the push. */
    private boolean worktreeIsDirty(Path worktree)
    {
        try {
            return git.hasUncommittedChanges(worktree);
        }
        catch (IOException e) {
            log.warn("dirty-check for {} failed: {}", worktree, e.getMessage());
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private DiffBundle collectDiffBundle(Path worktree, Task task)
    {
        try {
            String base = chooseDiffBase(worktree, task);
            if (base == null) {
                return new DiffBundle(null, null,
                        "no base ref available to diff against; task.baseBranch is "
                                + (task.baseBranch() == null ? "null" : "not on origin yet"));
            }
            return new DiffBundle(base, git.diff(worktree, base, "HEAD", PUSH_DIFF_MAX_BYTES), null);
        }
        catch (IOException e) {
            log.warn("push diff for {} failed: {}", worktree, e.getMessage());
            return new DiffBundle(null, null, "git diff failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DiffBundle(null, null, "git diff interrupted");
        }
        catch (RuntimeException e) {
            log.warn("push diff preview for {} rejected: {}", worktree, e.getMessage());
            return new DiffBundle(null, null, "git diff preview rejected: " + e.getMessage());
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
                String upstreamBase = "upstream/" + task.baseBranch();
                if (git.refExists(worktree, upstreamBase)) {
                    return upstreamBase;
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
