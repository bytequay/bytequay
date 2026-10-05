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

import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
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
 * @param queuePause why the queue stopped after a turn that did not succeed; it waits for the user to resume it
 * @param pullRequest the stored row for the reviewed pull request, which names it in lists; absent until ByteQuay has stored it
 */
public record AgentReviewProjection(
        AgentReview review,
        List<ReviewRun> runs,
        List<ReviewFinding> findings,
        List<MessageView> messages,
        TaskView task,
        Optional<TurnView> activeTurn,
        List<String> queuedInputIds,
        Optional<String> queuePause,
        int pendingApprovals,
        List<String> byteQuayTurnIds,
        Optional<ReviewDraft> draft,
        Optional<PullRequest> pullRequest,
        Optional<WorktreeSyncView> worktreeSync)
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
        requireNonNull(queuePause, "queuePause is null");
        byteQuayTurnIds = ImmutableList.copyOf(byteQuayTurnIds);
        requireNonNull(draft, "draft is null");
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(worktreeSync, "worktreeSync is null");
    }

    /** Live checkout facts; catching up does not change the reviewed run or its findings. */
    public record WorktreeSyncView(State state, Optional<String> headSha, Optional<String> targetHeadSha, boolean dirty, Optional<String> reason)
    {
        public WorktreeSyncView
        {
            requireNonNull(state, "state is null");
            requireNonNull(headSha, "headSha is null");
            requireNonNull(targetHeadSha, "targetHeadSha is null");
            requireNonNull(reason, "reason is null");
        }

        public enum State
        {
            CURRENT,
            PENDING,
            BLOCKED,
            UNAVAILABLE
        }
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
