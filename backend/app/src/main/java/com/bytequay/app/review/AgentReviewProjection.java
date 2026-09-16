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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentTurn;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Everything the pull-request pane shows for one review, read from durable facts. Runs are sent
 * without their frozen patches; the diff the user sees is the Changes tab's own.
 *
 * @param byteQuayTurnIds the turns ByteQuay started with review requests, whose input the transcript attributes to ByteQuay
 * @param queuedInputIds queue rows still waiting, so a question that names one is shown as queued
 * @param pullRequest the stored row for the reviewed pull request, which names it in lists; absent until ByteQuay has stored it
 */
public record AgentReviewProjection(
        AgentReview review,
        List<RunView> runs,
        List<ReviewFinding> findings,
        List<MessageView> messages,
        TaskView task,
        Optional<TurnView> activeTurn,
        List<String> queuedInputIds,
        int pendingApprovals,
        List<String> byteQuayTurnIds,
        Optional<ReviewDraft> draft,
        Optional<PullRequest> pullRequest)
{
    public AgentReviewProjection
    {
        requireNonNull(review, "review is null");
        runs = ImmutableList.copyOf(runs);
        findings = ImmutableList.copyOf(findings);
        messages = ImmutableList.copyOf(messages);
        requireNonNull(task, "task is null");
        requireNonNull(activeTurn, "activeTurn is null");
        queuedInputIds = ImmutableList.copyOf(queuedInputIds);
        byteQuayTurnIds = ImmutableList.copyOf(byteQuayTurnIds);
        requireNonNull(draft, "draft is null");
        requireNonNull(pullRequest, "pullRequest is null");
    }

    /** Saved discussion content with the current delivery identities from its accepted input. */
    public record MessageView(ReviewMessage message, Optional<String> turnId, Optional<String> messageEventId)
    {
        public MessageView
        {
            requireNonNull(message, "message is null");
            requireNonNull(turnId, "turnId is null");
            requireNonNull(messageEventId, "messageEventId is null");
        }
    }

    public record RunView(
            String id,
            int ordinal,
            ReviewRun.Scope scope,
            String baseRef,
            String baseSha,
            String mergeBaseSha,
            String headSha,
            Optional<String> priorHeadSha,
            List<ReviewCommit> commits,
            int changedFiles,
            List<String> turnIds,
            ReviewRun.State state,
            Optional<ReviewSummary> summary,
            Optional<ReviewCoverage> coverage,
            Optional<ReviewInvestigation> investigation,
            List<ReviewStep> steps,
            List<ReviewEvidence> evidence,
            Optional<ReviewContextSnapshot> context,
            Map<String, ContextDeliveryState> contextDeliveryStates,
            boolean completionRepairUsed,
            Optional<String> outcome,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> endedAt)
    {
        public RunView
        {
            contextDeliveryStates = ImmutableMap.copyOf(contextDeliveryStates);
        }

        public static RunView of(ReviewRun run, Map<String, ContextDeliveryState> deliveryStates)
        {
            return new RunView(
                    run.id(),
                    run.ordinal(),
                    run.scope(),
                    run.baseRef(),
                    run.baseSha(),
                    run.mergeBaseSha(),
                    run.headSha(),
                    run.priorHeadSha(),
                    run.commits(),
                    run.diff().size(),
                    run.turnIds(),
                    run.state(),
                    run.summary(),
                    run.coverage(),
                    run.investigation(),
                    run.steps(),
                    ImmutableList.copyOf(run.evidence().values()),
                    run.context(),
                    deliveryStates,
                    run.completionRepairUsed(),
                    run.outcome(),
                    run.createdAt(),
                    run.updatedAt(),
                    run.endedAt());
        }
    }

    /** Delivery labels derive only from durable provider receipts; runtime cleanup never establishes acceptance. */
    public record ContextDeliveryState(Outcome outcome, Optional<String> detail)
    {
        public ContextDeliveryState
        {
            requireNonNull(outcome, "outcome is null");
            requireNonNull(detail, "detail is null");
        }

        public static ContextDeliveryState of(Optional<AgentTurn> turn)
        {
            if (turn.isEmpty()) {
                return new ContextDeliveryState(Outcome.UNCERTAIN, Optional.of("The provider receipt is unavailable."));
            }
            AgentTurn value = turn.orElseThrow();
            if (value.providerTurnReference().isPresent()) {
                return new ContextDeliveryState(Outcome.CONFIRMED, Optional.empty());
            }
            if (value.knownUnsent() || value.startedAt().isEmpty()) {
                return new ContextDeliveryState(Outcome.PREPARED, Optional.of(value.state().terminal()
                        ? "Not sent: the turn ended before provider submission." : "Prepared before provider submission."));
            }
            return value.state().active()
                    ? new ContextDeliveryState(Outcome.SUBMITTED, Optional.of("Submission started; provider acceptance is not yet recorded."))
                    : new ContextDeliveryState(Outcome.UNCERTAIN, Optional.of("Submission may have reached the provider; acceptance could not be confirmed."));
        }

        public enum Outcome
        {
            PREPARED,
            SUBMITTED,
            CONFIRMED,
            UNCERTAIN
        }
    }

    /** The task facts the pane derives Running, Waiting for approval, Stopped and Ready from. */
    public record TaskView(
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

    public record TurnView(String id, String state, Optional<Instant> startedAt, Optional<Instant> cancellationRequestedAt) {}
}
