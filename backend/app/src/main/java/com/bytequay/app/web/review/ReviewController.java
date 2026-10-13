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
package com.bytequay.app.web.review;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.SteerAgentTurnCommand;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.app.agent.AgentStartupRecovery;
import com.bytequay.app.memory.MemoryService;
import com.bytequay.app.review.AgentReviewProjection;
import com.bytequay.app.review.AgentReviewService;
import com.bytequay.app.review.ReviewPublicationService;
import com.bytequay.app.utils.PullRequestRefUtil;
import com.bytequay.app.web.agent.AgentHttpModels;
import com.bytequay.app.web.agent.AgentStreamRegistry;
import com.bytequay.app.web.agent.ResolveApprovalRequest;
import com.bytequay.core.memory.MemorySource;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * The Agent review pane's routes.
 *
 * <p>Task operations resolve their review first, so a review's task can never be reached through this
 * controller by task id, and the interactive task routes stay closed to a workflow-owned task.
 */
@RestController
@RequestMapping("/api/agent-review")
public class ReviewController
{
    private final AgentReviewService reviews;
    private final ReviewPublicationService publications;
    private final AgentTurnService turns;
    private final AgentEventService events;
    private final AgentApprovalService approvals;
    private final AgentStartupRecovery startupRecovery;
    private final AgentStreamRegistry streams;
    private final MemoryService memories;

    public ReviewController(
            AgentReviewService reviews,
            ReviewPublicationService publications,
            AgentTurnService turns,
            AgentEventService events,
            AgentApprovalService approvals,
            AgentStartupRecovery startupRecovery,
            AgentStreamRegistry streams,
            MemoryService memories)
    {
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.publications = requireNonNull(publications, "publications is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.events = requireNonNull(events, "events is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.startupRecovery = requireNonNull(startupRecovery, "startupRecovery is null");
        this.streams = requireNonNull(streams, "streams is null");
        this.memories = requireNonNull(memories, "memories is null");
    }

    @GetMapping(params = {"!repo", "!number"})
    public List<ReviewHttpModels.ReviewResponse> list(@RequestParam String workspaceId)
    {
        requireNotBlank(workspaceId, "workspaceId must not be blank");
        return reviews.list(workspaceId).stream().map(ReviewHttpModels::review).collect(toImmutableList());
    }

    @GetMapping(params = {"repo", "number"})
    public ResponseEntity<ReviewHttpModels.ReviewResponse> find(@RequestParam String workspaceId, @RequestParam String repo, @RequestParam int number)
    {
        PullRequestRefUtil.parseRef(repo, number);
        return reviews.find(workspaceId, repo, number)
                .map(projection -> ResponseEntity.ok(ReviewHttpModels.review(projection)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    public ReviewHttpModels.ReviewResponse start(@RequestBody ReviewHttpModels.StartRequest request)
    {
        requireNotBlank(request.workspaceId(), "workspaceId must not be blank");
        requireNotBlank(request.repo(), "repo must not be blank");
        int number = required(request.number(), "number");
        PullRequestRefUtil.parseRef(request.repo(), number);
        return ReviewHttpModels.review(reviews.start(request.workspaceId(), request.repo(), number));
    }

    @PostMapping("/worktree")
    public ReviewHttpModels.ReviewResponse prepare(@RequestBody ReviewHttpModels.StartRequest request)
    {
        requireNotBlank(request.workspaceId(), "workspaceId must not be blank");
        requireNotBlank(request.repo(), "repo must not be blank");
        int number = required(request.number(), "number");
        PullRequestRefUtil.parseRef(request.repo(), number);
        return ReviewHttpModels.review(reviews.prepare(request.workspaceId(), request.repo(), number));
    }

    @GetMapping("/tasks/{sourceTaskId}")
    public ResponseEntity<ReviewHttpModels.ReviewResponse> findForTask(@PathVariable String sourceTaskId, @RequestParam String workspaceId)
    {
        requireNotBlank(workspaceId, "workspaceId must not be blank");
        return reviews.findForTask(workspaceId, sourceTaskId)
                .map(projection -> ResponseEntity.ok(ReviewHttpModels.review(projection)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/tasks/{sourceTaskId}")
    public ReviewHttpModels.ReviewResponse startForTask(@PathVariable String sourceTaskId, @RequestParam String workspaceId)
    {
        requireNotBlank(workspaceId, "workspaceId must not be blank");
        return ReviewHttpModels.review(reviews.startForTask(workspaceId, sourceTaskId));
    }

    @GetMapping("/{reviewId}")
    public ReviewHttpModels.ReviewResponse get(@PathVariable String reviewId)
    {
        return ReviewHttpModels.review(reviews.get(reviewId));
    }

    @PostMapping("/{reviewId}/stop")
    public ReviewHttpModels.ReviewResponse stop(@PathVariable String reviewId)
    {
        return ReviewHttpModels.review(reviews.stop(reviewId));
    }

    @GetMapping("/{reviewId}/runtime/stop-impact")
    public AgentHttpModels.RuntimeStopImpactResponse runtimeStopImpact(@PathVariable String reviewId)
    {
        return AgentHttpModels.stopImpact(turns.stopImpact(reviews.task(reviewId).id()));
    }

    /** Retries provider cleanup without ending the review or replaying its interrupted input. */
    @PostMapping("/{reviewId}/runtime/stop")
    public ReviewHttpModels.ReviewResponse stopRuntime(@PathVariable String reviewId, @RequestBody ReviewHttpModels.StopRuntimeRequest request)
    {
        requireNotBlank(request.generation(), "generation must not be blank");
        AgentTask task = reviews.task(reviewId);
        if (task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY).isPresent()) {
            startupRecovery.stopRecoveredRuntime(task.id(), request.generation());
        }
        else {
            turns.stopRuntime(task.id(), request.generation());
        }
        return ReviewHttpModels.review(reviews.get(reviewId));
    }

    @PostMapping("/{reviewId}/resume")
    public ReviewHttpModels.ReviewResponse resume(@PathVariable String reviewId)
    {
        return ReviewHttpModels.review(reviews.resume(reviewId));
    }

    @PostMapping("/{reviewId}/settings")
    public ReviewHttpModels.ReviewResponse configure(@PathVariable String reviewId, @RequestBody ReviewHttpModels.ConfigureRequest request)
    {
        requireNotBlank(request.laneId(), "laneId must not be blank");
        requireNotBlank(request.model(), "model must not be blank");
        return ReviewHttpModels.review(reviews.configure(reviewId, required(request.expectedVersion(), "expectedVersion"),
                new AgentLaneId(request.laneId()), request.model(), Optional.ofNullable(request.effort()), Optional.ofNullable(request.serviceTier())));
    }

    @PostMapping("/{reviewId}/update")
    public ReviewHttpModels.ReviewResponse update(@PathVariable String reviewId)
    {
        return ReviewHttpModels.review(reviews.update(reviewId));
    }

    @PostMapping("/{reviewId}/explanation/refresh")
    public ReviewHttpModels.ReviewResponse refreshExplanation(@PathVariable String reviewId, @RequestBody ReviewHttpModels.RefreshExplanationRequest request)
    {
        requireNotBlank(request.runId(), "runId must not be blank");
        return ReviewHttpModels.review(reviews.refreshExplanation(reviewId, request.runId(), required(request.expectedRevision(), "expectedRevision")));
    }

    @PostMapping("/{reviewId}/end")
    public ReviewHttpModels.ReviewResponse end(@PathVariable String reviewId)
    {
        return ReviewHttpModels.review(reviews.end(reviewId));
    }

    @PostMapping("/{reviewId}/questions")
    public ReviewHttpModels.ReviewResponse ask(@PathVariable String reviewId, @RequestBody ReviewHttpModels.AskRequest request)
    {
        requireNotBlank(request.message(), "message must not be blank");
        reviews.ask(
                reviewId,
                Optional.ofNullable(request.findingId()).filter(id -> !id.isBlank()),
                Optional.ofNullable(request.cardId()).filter(id -> !id.isBlank()),
                required(request.targetRevision(), "targetRevision"),
                request.message());
        return ReviewHttpModels.review(reviews.get(reviewId));
    }

    /**
     * A free-form message to the review agent from the Activity composer. Steering is refused by
     * core when the lane does not support it, so an unsupported control is never simulated here.
     */
    @PostMapping("/{reviewId}/messages")
    public ReviewHttpModels.ReviewResponse message(
            @PathVariable String reviewId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ReviewHttpModels.MessageRequest request)
    {
        requireNotBlank(idempotencyKey, "Idempotency-Key must not be blank");
        requireNotBlank(request.message(), "message must not be blank");
        AgentTask task = reviews.task(reviewId);
        String delivery = Optional.ofNullable(request.delivery()).orElse("TURN").toUpperCase(Locale.ROOT);
        switch (delivery) {
            case "TURN" -> reviews.sendMessage(reviewId, task.version(), request.message(), idempotencyKey, false);
            case "STEER" -> turns.steer(new SteerAgentTurnCommand(steeredTurn(task, request.turnId()), request.message(), ImmutableList.of(), idempotencyKey));
            case "QUEUE" -> reviews.sendMessage(reviewId, task.version(), request.message(), idempotencyKey, true);
            default -> throw new ReviewInvalidRequestException("delivery must be TURN, STEER or QUEUE");
        }
        return ReviewHttpModels.review(reviews.get(reviewId));
    }

    @PostMapping("/{reviewId}/queue/resume")
    public ReviewHttpModels.ReviewResponse resumeQueue(@PathVariable String reviewId)
    {
        turns.resumeQueued(reviews.task(reviewId).id());
        return ReviewHttpModels.review(reviews.get(reviewId));
    }

    @PostMapping("/{reviewId}/queue/{queuedInputId}/steer")
    public ReviewHttpModels.ReviewResponse steerQueued(@PathVariable String reviewId, @PathVariable String queuedInputId, @RequestParam String turnId)
    {
        turns.steerQueued(reviews.task(reviewId).id(), TurnId.valueOf(turnId), QueuedInputId.valueOf(queuedInputId));
        return ReviewHttpModels.review(reviews.get(reviewId));
    }

    @DeleteMapping("/{reviewId}/queue/{queuedInputId}")
    public ReviewHttpModels.ReviewResponse removeQueued(@PathVariable String reviewId, @PathVariable String queuedInputId)
    {
        turns.removeQueued(reviews.task(reviewId).id(), QueuedInputId.valueOf(queuedInputId));
        return ReviewHttpModels.review(reviews.get(reviewId));
    }

    @PostMapping("/{reviewId}/findings/{findingId}/resolve")
    public ReviewHttpModels.ReviewResponse resolveFinding(
            @PathVariable String reviewId,
            @PathVariable String findingId,
            @RequestBody ReviewHttpModels.ResolveFindingRequest request)
    {
        String reason = Optional.ofNullable(request.reason()).orElse("");
        // Checked before resolving, so a reason that cannot be remembered leaves the finding open.
        Optional<ReviewFinding> remembered = Boolean.TRUE.equals(request.remember()) ? Optional.of(rememberedFinding(reviewId, findingId, reason)) : Optional.empty();
        reviews.resolveFinding(reviewId, findingId, required(request.expectedVersion(), "expectedVersion"), reason);
        AgentReviewProjection resolved = reviews.get(reviewId);
        remembered.ifPresent(finding -> memories.rememberReason(resolved.review().workspaceId(), finding.title(), reason, finding.anchor().orElseThrow().path(),
                new MemorySource.UserPart(MemorySource.Where.RESOLVE, reviewId, findingId)));
        return ReviewHttpModels.review(resolved);
    }

    /** The finding whose reason is remembered, titled by it and for its file; a finding about the whole change has no file. */
    private ReviewFinding rememberedFinding(String reviewId, String findingId, String reason)
    {
        MemoryService.checkReason(reason);
        return reviews.get(reviewId).findings().stream().filter(finding -> finding.id().equals(findingId) && finding.anchor().isPresent()).findFirst()
                .orElseThrow(() -> new ReviewInvalidRequestException("A finding about the whole change has no file to remember its reason for"));
    }

    @PostMapping("/{reviewId}/findings/{findingId}/reopen")
    public ReviewHttpModels.ReviewResponse reopenFinding(
            @PathVariable String reviewId,
            @PathVariable String findingId,
            @RequestBody(required = false) ReviewHttpModels.ResolveFindingRequest request)
    {
        reviews.reopenFinding(reviewId, findingId, required(request == null ? null : request.expectedVersion(), "expectedVersion"));
        return ReviewHttpModels.review(reviews.get(reviewId));
    }

    // ---- activity, scoped to the review's own task ----

    @GetMapping("/{reviewId}/history")
    public AgentHttpModels.HistoryResponse history(@PathVariable String reviewId, @RequestParam(required = false) Long before, @RequestParam(required = false) Long after)
    {
        return AgentHttpModels.history(events.readHistory(
                reviews.task(reviewId).id(),
                before == null ? OptionalLong.empty() : OptionalLong.of(before),
                after == null ? OptionalLong.empty() : OptionalLong.of(after)));
    }

    @GetMapping(path = "/{reviewId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String reviewId, @RequestParam(defaultValue = "0") long after)
            throws IOException
    {
        return streams.open(events.subscribe(reviews.task(reviewId).id(), after));
    }

    @GetMapping("/{reviewId}/pending-approvals")
    public List<AgentHttpModels.PendingApprovalResponse> pendingApprovals(@PathVariable String reviewId)
    {
        AgentTask task = reviews.task(reviewId);
        return task.worktreeMutationClaim()
                .filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.TURN)
                .map(claim -> approvals.listPending(TurnId.valueOf(claim.ownerId())).stream()
                        .map(AgentHttpModels::pendingApproval)
                        .collect(toImmutableList()))
                .orElse(ImmutableList.of());
    }

    /** Resolves one approval, only when it belongs to the turn this review's task is running. */
    @PostMapping("/{reviewId}/approvals/{approvalId}")
    public AgentHttpModels.ApprovalResponse resolveApproval(@PathVariable String reviewId, @PathVariable String approvalId, @RequestBody ResolveApprovalRequest request)
    {
        AgentTask task = reviews.task(reviewId);
        TurnId turnId = task.worktreeMutationClaim()
                .filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.TURN)
                .map(claim -> TurnId.valueOf(claim.ownerId()))
                .orElseThrow(() -> new ReviewNotFoundException("This review has no turn awaiting approval"));
        if (approvals.listPending(turnId).stream().noneMatch(pending -> pending.id().toString().equals(approvalId))) {
            throw new ReviewNotFoundException("Approval not found for this review: " + approvalId);
        }
        return AgentHttpModels.approval(approvals.resolve(new ResolveApprovalCommand(
                com.bytequay.agent.core.ApprovalId.valueOf(approvalId),
                request.decision(),
                Optional.ofNullable(request.scopedPermission()),
                Boolean.TRUE.equals(request.forTask()),
                request.answers() == null ? ImmutableMap.of() : ImmutableMap.copyOf(request.answers()))));
    }

    // ---- the user's local pending review ----

    @GetMapping("/drafts")
    public ReviewHttpModels.DraftResponse draft(@RequestParam String repo, @RequestParam int number)
    {
        PullRequestRefUtil.parseRef(repo, number);
        return ReviewHttpModels.draft(publications.draft(repo, number));
    }

    @PostMapping("/drafts/items")
    public ReviewHttpModels.DraftResponse addManual(@RequestParam String repo, @RequestParam int number, @RequestBody ReviewHttpModels.DraftItemRequest request)
    {
        PullRequestRefUtil.parseRef(repo, number);
        requireNotBlank(request.path(), "path must not be blank");
        requireNotBlank(request.body(), "body must not be blank");
        ReviewDraftItem item = new ReviewDraftItem(
                "new",
                ReviewDraftItem.Source.MANUAL,
                Optional.empty(),
                OptionalInt.empty(),
                request.path(),
                side(request.side()),
                required(request.line(), "line"),
                request.startLine() == null ? OptionalInt.empty() : OptionalInt.of(request.startLine()),
                request.body(),
                true,
                Optional.empty(),
                Optional.ofNullable(request.commitId()), request.replyToCommentId() == null ? OptionalLong.empty() : OptionalLong.of(request.replyToCommentId()));
        return ReviewHttpModels.draft(publications.addManual(repo, number, required(request.expectedVersion(), "expectedVersion"), item));
    }

    @PostMapping("/drafts/findings")
    public ReviewHttpModels.DraftResponse addFinding(@RequestParam String repo, @RequestParam int number, @RequestBody ReviewHttpModels.PromoteFindingRequest request)
    {
        PullRequestRefUtil.parseRef(repo, number);
        requireNotBlank(request.reviewId(), "reviewId must not be blank");
        requireNotBlank(request.findingId(), "findingId must not be blank");
        return ReviewHttpModels.draft(publications.addFinding(repo, number, required(request.expectedVersion(), "expectedVersion"), request.reviewId(), request.findingId()));
    }

    @PutMapping("/drafts/items/{itemId}")
    public ReviewHttpModels.DraftResponse editItem(
            @RequestParam String repo,
            @RequestParam int number,
            @PathVariable String itemId,
            @RequestBody ReviewHttpModels.DraftItemRequest request)
    {
        PullRequestRefUtil.parseRef(repo, number);
        requireNotBlank(request.body(), "body must not be blank");
        return ReviewHttpModels.draft(publications.editItem(
                repo,
                number,
                required(request.expectedVersion(), "expectedVersion"),
                itemId,
                request.body(),
                request.included() == null || request.included()));
    }

    @DeleteMapping("/drafts/items/{itemId}")
    public ReviewHttpModels.DraftResponse removeItem(@RequestParam String repo, @RequestParam int number, @RequestParam long expectedVersion, @PathVariable String itemId)
    {
        PullRequestRefUtil.parseRef(repo, number);
        return ReviewHttpModels.draft(publications.removeItem(repo, number, expectedVersion, itemId));
    }

    @PostMapping("/drafts/items/{itemId}/confirm-not-posted")
    public ReviewHttpModels.DraftResponse confirmNotPosted(@RequestParam String repo, @RequestParam int number, @RequestParam long expectedVersion, @PathVariable String itemId)
    {
        PullRequestRefUtil.parseRef(repo, number);
        return ReviewHttpModels.draft(publications.confirmNotPosted(repo, number, expectedVersion, itemId));
    }

    @PutMapping("/drafts/review")
    public ReviewHttpModels.DraftResponse saveReview(@RequestParam String repo, @RequestParam int number, @RequestBody ReviewHttpModels.DraftReviewRequest request)
    {
        PullRequestRefUtil.parseRef(repo, number);
        return ReviewHttpModels.draft(publications.saveReview(
                repo,
                number,
                required(request.expectedVersion(), "expectedVersion"),
                request.body(),
                Optional.ofNullable(request.event()).orElse("COMMENT")));
    }

    @PostMapping("/drafts/items/{itemId}/publish")
    public ReviewHttpModels.PublishResponse publishItem(@RequestParam String repo, @RequestParam int number, @RequestParam long expectedVersion, @PathVariable String itemId)
    {
        PullRequestRefUtil.parseRef(repo, number);
        return ReviewHttpModels.publish(publications.publishItem(repo, number, expectedVersion, itemId));
    }

    @PostMapping("/drafts/submit")
    public ReviewHttpModels.PublishResponse submit(@RequestParam String repo, @RequestParam int number, @RequestBody ReviewHttpModels.DraftReviewRequest request)
    {
        PullRequestRefUtil.parseRef(repo, number);
        return ReviewHttpModels.publish(publications.submit(
                repo,
                number,
                required(request.expectedVersion(), "expectedVersion"),
                request.body(),
                Optional.ofNullable(request.event()).orElse("COMMENT")));
    }

    @GetMapping("/drafts/ineligible")
    public List<ReviewPublicationService.Ineligible> ineligible(@RequestParam String repo, @RequestParam int number)
    {
        PullRequestRefUtil.parseRef(repo, number);
        return publications.ineligible(repo, number);
    }

    /** The turn the user saw running; core refuses it once it has ended rather than steering whatever runs next. */
    private TurnId steeredTurn(AgentTask task, String turnId)
    {
        if (turnId == null || turnId.isBlank()) {
            throw new ReviewInvalidRequestException("turnId is required to steer");
        }
        AgentTurn turn = turns.get(TurnId.valueOf(turnId));
        if (!turn.taskId().equals(task.id())) {
            throw new ReviewInvalidRequestException("The turn does not belong to this review");
        }
        return turn.id();
    }

    private static String side(String side)
    {
        String value = Optional.ofNullable(side).orElse("RIGHT").toUpperCase(Locale.ROOT);
        if (!value.equals("LEFT") && !value.equals("RIGHT")) {
            throw new ReviewInvalidRequestException("side must be LEFT or RIGHT");
        }
        return value;
    }

    private static <T extends Number> T required(T value, String field)
    {
        if (value == null) {
            throw new ReviewInvalidRequestException(field + " is required");
        }
        return value;
    }
}
