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

import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Everything the pull-request pane shows for one review, read from durable facts. Runs are sent
 * without their frozen patches; the diff the user sees is the Changes tab's own.
 *
 * @param byteQuayTurnIds the turns ByteQuay started with review requests, whose input the transcript attributes to ByteQuay
 * @param queuedInputIds queue rows still waiting, so a question that names one is shown as queued
 */
public record AgentReviewProjection(
        AgentReview review,
        List<RunView> runs,
        List<ReviewFinding> findings,
        List<ReviewMessage> messages,
        TaskView task,
        Optional<TurnView> activeTurn,
        List<String> queuedInputIds,
        int pendingApprovals,
        List<String> byteQuayTurnIds,
        Optional<ReviewDraft> draft)
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
            boolean completionRepairUsed,
            Optional<String> outcome,
            Instant createdAt,
            Instant updatedAt,
            Optional<Instant> endedAt)
    {
        public static RunView of(ReviewRun run)
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
                    run.completionRepairUsed(),
                    run.outcome(),
                    run.createdAt(),
                    run.updatedAt(),
                    run.endedAt());
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
