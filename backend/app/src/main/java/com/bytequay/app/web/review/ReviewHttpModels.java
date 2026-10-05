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
                task(projection.task()),
                projection.activeTurn().map(turn -> new TurnResponse(turn.id(), turn.state(), turn.startedAt(), turn.cancellationRequestedAt())),
                projection.queuedInputIds(),
                projection.queuePause(),
                projection.pendingApprovals(),
                projection.byteQuayTurnIds(),
                projection.draft().map(ReviewHttpModels::draft),
                projection.pullRequest().map(pullRequest -> new PullRequestResponse(pullRequest.title(), pullRequest.state(), pullRequest.draft())),
                projection.worktreeSync().map(sync -> new WorktreeSyncResponse(sync.state().name(), sync.headSha(), sync.targetHeadSha(), sync.dirty(), sync.reason())),
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
                run.priorHeadSha(),
                run.commits(),
                run.diff().size(),
                run.turnIds(),
                run.state().name(),
                run.summary().map(ReviewHttpModels::summary),
                run.coverage().map(ReviewHttpModels::coverage),
                run.investigation(),
                run.steps(),
                run.evidence().values().stream().map(ReviewHttpModels::evidence).collect(toImmutableList()),
                run.outcome(),
                run.createdAt(),
                run.endedAt());
    }

    private static SummaryResponse summary(ReviewSummary summary)
    {
        return new SummaryResponse(
                summary.revision(),
                summary.purpose(),
                summary.commits().stream().map(commit -> new CommitExplanationResponse(commit.sha(), commit.explanation())).collect(toImmutableList()),
                summary.combinedResult(),
                summary.evidence(),
                summary.criticalFindingIds());
    }

    private static CoverageResponse coverage(ReviewCoverage coverage)
    {
        return new CoverageResponse(coverage.complete(), coverage.omissions(), coverage.warnings(), coverage.recordedAt());
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
                finding.evidence().stream().map(ReviewHttpModels::evidence).collect(toImmutableList()),
                finding.revision(),
                finding.state().name(),
                finding.stateReason(),
                finding.outdated(),
                finding.validatedHeadSha(),
                finding.version(),
                finding.updatedAt(),
                finding.memories());
    }

    private static EvidenceResponse evidence(ReviewEvidence evidence)
    {
        return new EvidenceResponse(evidence.ref(), evidence.source(), evidence.provenance(), evidence.excerpt(), evidence.explanation());
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
                message.createdAt());
    }

    private static TaskResponse task(AgentReviewProjection.TaskView task)
    {
        return new TaskResponse(
                task.id(),
                task.workspaceId(),
                task.state(),
                task.version(),
                task.laneId(),
                task.laneLabel(),
                task.model(),
                task.mutationClaim(),
                task.steering(),
                task.nestedAgents());
    }

    static DraftResponse draft(ReviewDraft draft)
    {
        return new DraftResponse(
                draft.repo(),
                draft.number(),
                draft.body(),
                draft.event(),
                draft.items().stream().map(ReviewHttpModels::item).collect(toImmutableList()),
                draft.version(),
                draft.updatedAt());
    }

    private static DraftItemResponse item(ReviewDraftItem item)
    {
        return new DraftItemResponse(
                item.id(),
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
                item.publication().map(publication -> new PublicationResponse(
                        publication.state().name(),
                        publication.remoteReviewId(),
                        publication.remoteCommentId(),
                        publication.url(),
                        publication.at())));
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
            TaskResponse task,
            Optional<TurnResponse> activeTurn,
            List<String> queuedInputIds,
            Optional<String> queuePause,
            int pendingApprovals,
            List<String> byteQuayTurnIds,
            Optional<DraftResponse> draft,
            Optional<PullRequestResponse> pullRequest,
            Optional<WorktreeSyncResponse> worktreeSync,
            Optional<AgentReview.Replay> replay) {}

    record WorktreeSyncResponse(String state, Optional<String> headSha, Optional<String> targetHeadSha, boolean dirty, Optional<String> reason) {}

    record RunResponse(
            String id,
            int ordinal,
            String scope,
            String baseRef,
            String baseSha,
            String mergeBaseSha,
            String headSha,
            Optional<String> priorHeadSha,
            List<ReviewCommit> commits,
            int changedFiles,
            List<String> turnIds,
            String state,
            Optional<SummaryResponse> summary,
            Optional<CoverageResponse> coverage,
            Optional<ReviewInvestigation> investigation,
            List<ReviewStep> steps,
            List<EvidenceResponse> evidence,
            Optional<String> outcome,
            Instant createdAt,
            Optional<Instant> endedAt) {}

    record SummaryResponse(
            int revision,
            String purpose,
            List<CommitExplanationResponse> commits,
            String combinedResult,
            Optional<String> evidence,
            List<String> criticalFindingIds) {}

    record CommitExplanationResponse(String sha, String explanation) {}

    record CoverageResponse(boolean complete, List<String> omissions, List<String> warnings, Instant recordedAt) {}

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
            List<EvidenceResponse> evidence,
            int revision,
            String state,
            Optional<String> stateReason,
            boolean outdated,
            String validatedHeadSha,
            long version,
            Instant updatedAt,
            List<ReviewFinding.MemoryCitation> memories) {}

    record EvidenceResponse(String ref, String source, String provenance, String excerpt, String explanation) {}

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
            Instant createdAt) {}

    record TaskResponse(
            String id,
            String workspaceId,
            String state,
            long version,
            String laneId,
            String laneLabel,
            Optional<String> model,
            Optional<String> mutationClaim,
            boolean steering,
            boolean nestedAgents) {}

    record TurnResponse(String id, String state, Optional<Instant> startedAt, Optional<Instant> cancellationRequestedAt) {}

    record PullRequestResponse(String title, String state, boolean draft) {}

    record DraftResponse(
            String repo,
            int number,
            String body,
            String event,
            List<DraftItemResponse> items,
            long version,
            Instant updatedAt) {}

    record DraftItemResponse(
            String id,
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
            String commitId) {}

    record PromoteFindingRequest(Long expectedVersion, String reviewId, String findingId) {}

    record DraftReviewRequest(Long expectedVersion, String body, String event) {}
}
