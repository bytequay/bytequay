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

import com.bytequay.app.review.AgentReviewProjection;
import com.bytequay.app.review.AgentReviewProjection.TaskView;
import com.bytequay.app.review.AgentReviewProjection.TurnView;
import com.bytequay.app.review.AgentReviewProjection.WorktreeSyncView;
import com.bytequay.app.review.ReviewPublicationService;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewPublication;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** The review pane's HTTP shapes. Nothing here carries a token, a filesystem path, or provider JSON. */
final class ReviewHttpModels
{
    private ReviewHttpModels() {}

    static ReviewResponse review(AgentReviewProjection projection)
    {
        requireNonNull(projection, "projection is null");
        AgentReview review = projection.review();
        return new ReviewResponse(
                review.id(),
                review.workspaceId(),
                review.repo(),
                review.number(),
                review.sourceTaskId(),
                review.state().name(),
                review.followUpPaused(),
                review.observedHeadSha(),
                review.latestCompletedRunId(),
                review.version(),
                review.createdAt(),
                review.closedAt(),
                projection.runs().stream().map(ReviewHttpModels::run).collect(toImmutableList()),
                projection.findings().stream().map(ReviewHttpModels::finding).collect(toImmutableList()),
                projection.messages().stream().map(ReviewHttpModels::message).collect(toImmutableList()),
                projection.task(),
                projection.activeTurn(),
                projection.queuedInputIds(),
                projection.queuePause(),
                projection.pendingApprovals(),
                projection.byteQuayTurnIds(),
                projection.draft().map(ReviewHttpModels::draft),
                projection.pullRequest().map(pullRequest -> new PullRequestResponse(pullRequest.title(), pullRequest.state(), pullRequest.draft())),
                projection.worktreeSync(),
                review.replay());
    }

    private static RunResponse run(ReviewRun run)
    {
        return new RunResponse(
                run.id(),
                run.ordinal(),
                run.scope().name(),
                run.baseRef(),
                run.baseSha(),
                run.mergeBaseSha(),
                run.headSha(),
                run.worktreeTreeSha(),
                run.priorHeadSha(),
                run.commits(),
                run.diff().size(),
                run.turnIds(),
                run.state().name(),
                run.summary(),
                run.coverage(),
                run.investigation(),
                run.steps(),
                ImmutableList.copyOf(run.evidence().values()),
                run.outcome(),
                run.createdAt(),
                run.endedAt());
    }

    private static FindingResponse finding(ReviewFinding finding)
    {
        Optional<ReviewAnchor> anchor = finding.anchor();
        return new FindingResponse(
                finding.id(),
                finding.label(),
                finding.runId(),
                finding.title(),
                finding.body(),
                finding.severity(),
                anchor.map(ReviewAnchor::path),
                anchor.map(ReviewAnchor::side),
                anchor.map(ReviewAnchor::startLine),
                anchor.map(ReviewAnchor::endLine),
                anchor.map(ReviewAnchor::label),
                anchor.map(ReviewAnchor::excerpt).orElse(ImmutableList.of()),
                finding.evidence(),
                finding.revision(),
                finding.state().name(),
                finding.stateReason(),
                finding.outdated(),
                finding.validatedHeadSha(),
                finding.version(),
                finding.updatedAt(),
                finding.memories(),
                finding.origin().name(),
                finding.resolvedByTaskId());
    }

    private static MessageResponse message(AgentReviewProjection.MessageView view)
    {
        ReviewMessage message = view.message();
        return new MessageResponse(
                message.id(),
                message.findingId(),
                message.cardId(),
                message.runId(),
                message.author().name(),
                message.body(),
                message.targetRevision(),
                view.turnId(),
                message.queuedInputId(),
                view.messageEventId(),
                message.createdAt(),
                message.developerTaskId());
    }

    static DraftResponse draft(ReviewDraft draft)
    {
        return new DraftResponse(
                draft.repo(),
                draft.number(),
                draft.body(),
                draft.event(),
                draft.reviewPublication().map(ReviewHttpModels::publication),
                draft.items().stream().map(ReviewHttpModels::item).collect(toImmutableList()),
                draft.postedSummaryFindingIds(),
                draft.version(),
                draft.updatedAt());
    }

    private static PublicationResponse publication(ReviewPublication publication)
    {
        return new PublicationResponse(publication.state().name(), publication.remoteReviewId(), publication.remoteCommentId(), publication.url(), publication.at());
    }

    private static DraftItemResponse item(ReviewDraftItem item)
    {
        return new DraftItemResponse(
                item.id(),
                item.replyToCommentId(),
                item.source().name(),
                item.findingId(),
                item.findingRevision(),
                item.commitId(),
                item.path(),
                item.side(),
                item.line(),
                item.startLine(),
                item.body(),
                item.included(),
                item.published(),
                item.publication().map(ReviewHttpModels::publication));
    }

    static PublishResponse publish(ReviewPublicationService.Publication publication)
    {
        return new PublishResponse(draft(publication.draft()), publication.publishedItemIds(), publication.uncertainReason());
    }

    record ReviewResponse(
            String id,
            String workspaceId,
            String repo,
            OptionalInt number,
            Optional<String> sourceTaskId,
            String state,
            boolean followUpPaused,
            Optional<String> observedHeadSha,
            Optional<String> latestCompletedRunId,
            long version,
            Instant createdAt,
            Optional<Instant> closedAt,
            List<RunResponse> runs,
            List<FindingResponse> findings,
            List<MessageResponse> messages,
            TaskView task,
            Optional<TurnView> activeTurn,
            List<String> queuedInputIds,
            Optional<String> queuePause,
            int pendingApprovals,
            List<String> byteQuayTurnIds,
            Optional<DraftResponse> draft,
            Optional<PullRequestResponse> pullRequest,
            Optional<WorktreeSyncView> worktreeSync,
            Optional<AgentReview.Replay> replay) {}

    record RunResponse(
            String id,
            int ordinal,
            String scope,
            String baseRef,
            String baseSha,
            String mergeBaseSha,
            String headSha,
            Optional<String> worktreeTreeSha,
            Optional<String> priorHeadSha,
            List<ReviewCommit> commits,
            int changedFiles,
            List<String> turnIds,
            String state,
            Optional<ReviewSummary> summary,
            Optional<ReviewCoverage> coverage,
            Optional<ReviewInvestigation> investigation,
            List<ReviewStep> steps,
            List<ReviewEvidence> evidence,
            Optional<String> outcome,
            Instant createdAt,
            Optional<Instant> endedAt) {}

    record FindingResponse(
            String id,
            String label,
            String runId,
            String title,
            String body,
            Optional<String> severity,
            Optional<String> path,
            Optional<String> side,
            Optional<Integer> startLine,
            Optional<Integer> endLine,
            Optional<String> range,
            List<String> excerpt,
            List<ReviewEvidence> evidence,
            int revision,
            String state,
            Optional<String> stateReason,
            boolean outdated,
            String validatedHeadSha,
            long version,
            Instant updatedAt,
            List<ReviewFinding.MemoryCitation> memories,
            String origin,
            Optional<String> resolvedByTaskId) {}

    record MessageResponse(
            String id,
            Optional<String> findingId,
            Optional<String> cardId,
            String runId,
            String author,
            String body,
            int targetRevision,
            Optional<String> turnId,
            Optional<String> queuedInputId,
            Optional<String> messageEventId,
            Instant createdAt,
            Optional<String> developerTaskId) {}

    record ConfigureRequest(Long expectedVersion, String laneId, String model, String effort, String serviceTier) {}

    record PullRequestResponse(String title, String state, boolean draft) {}

    record DraftResponse(
            String repo,
            int number,
            String body,
            String event,
            Optional<PublicationResponse> reviewPublication,
            List<DraftItemResponse> items,
            List<String> postedSummaryFindingIds,
            long version,
            Instant updatedAt) {}

    record DraftItemResponse(
            String id,
            OptionalLong replyToCommentId,
            String source,
            Optional<String> findingId,
            OptionalInt findingRevision,
            Optional<String> commitId,
            String path,
            String side,
            int line,
            OptionalInt startLine,
            String body,
            boolean included,
            boolean published,
            Optional<PublicationResponse> publication) {}

    record PublicationResponse(
            String state,
            OptionalLong remoteReviewId,
            OptionalLong remoteCommentId,
            Optional<String> url,
            Instant at) {}

    record PublishResponse(DraftResponse draft, List<String> publishedItemIds, Optional<String> uncertainReason) {}

    record StartRequest(String workspaceId, String repo, Integer number) {}

    record StopRuntimeRequest(String generation) {}

    record RefreshExplanationRequest(String runId, Integer expectedRevision) {}

    /** A private question on the summary or one finding, with the revision the user was looking at. */
    record AskRequest(String findingId, String cardId, Integer targetRevision, String message) {}

    /** A free-form message to the review agent: TURN starts one, STEER joins the running one, QUEUE runs next. */
    /**
     * @param turnId the running turn a STEER is for, as the user saw it
     */
    record MessageRequest(String message, String delivery, String turnId) {}

    /**
     * @param remember also save the reason as the user's rule for the finding's file
     */
    record ResolveFindingRequest(Long expectedVersion, String reason, Boolean remember) {}

    record DraftItemRequest(
            Long expectedVersion,
            String path,
            String side,
            Integer line,
            Integer startLine,
            String body,
            Boolean included,
            String commitId,
            Long replyToCommentId) {}

    record PromoteFindingRequest(Long expectedVersion, String reviewId, String findingId) {}

    record DraftReviewRequest(Long expectedVersion, String body, String event) {}

    record DraftSubmitRequest(Long expectedVersion, String body, String event, String headSha) {}
}
