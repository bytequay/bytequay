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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.remote.ReviewBuildCommentOperationHandler.CommentAction;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Routes the two review-publication ledgers through one proven handler. */
@Component
public final class ReviewPublicationOperationStore
        implements ReviewBuildCommentOperationHandler.OperationStore
{
    private final SqliteReviewBuildCommentStore reviewBuilds;
    private final SqliteReviewPassPublicationStore reviewPasses;

    public ReviewPublicationOperationStore(
            SqliteReviewBuildCommentStore reviewBuilds,
            SqliteReviewPassPublicationStore reviewPasses)
    {
        this.reviewBuilds = requireNonNull(reviewBuilds, "reviewBuilds is null");
        this.reviewPasses = requireNonNull(reviewPasses, "reviewPasses is null");
    }

    @Override
    public CommentAction require(String operationId)
    {
        return reviewPasses.findByOperationId(operationId)
                .or(() -> reviewBuilds.findByOperationId(operationId))
                .orElseThrow(() -> new IllegalStateException(
                        "exact durable review publication is missing"));
    }

    @Override
    public CommentAction claim(
            String actionId,
            int expectedAttemptCount,
            ClaimMode mode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        return store(actionId).claim(
                actionId, expectedAttemptCount, mode, claimOwner,
                claimedAt, leaseUntil);
    }

    @Override
    public void finishSucceeded(
            String actionId,
            int attempt,
            String externalEffectId,
            String evidence,
            Instant completedAt)
    {
        store(actionId).finishSucceeded(
                actionId, attempt, externalEffectId, evidence, completedAt);
    }

    @Override
    public void finishFailed(
            String actionId, int attempt, String error, Instant completedAt)
    {
        store(actionId).finishFailed(actionId, attempt, error, completedAt);
    }

    @Override
    public void finishIndeterminate(
            String actionId, int attempt, String evidence, Instant completedAt)
    {
        store(actionId).finishIndeterminate(
                actionId, attempt, evidence, completedAt);
    }

    @Override
    public void finishCanceled(
            String actionId, int attempt, String error, Instant completedAt)
    {
        store(actionId).finishCanceled(actionId, attempt, error, completedAt);
    }

    @Override
    public void recordRecoveryBaseline(
            String actionId, int attempt, List<String> remoteEffectIds)
    {
        store(actionId).recordRecoveryBaseline(
                actionId, attempt, remoteEffectIds);
    }

    @Override
    public boolean deferProbe(
            String actionId,
            int attempt,
            Instant observedAt,
            Instant retryAt,
            String evidence)
    {
        return store(actionId).deferProbe(
                actionId, attempt, observedAt, retryAt, evidence);
    }

    private ReviewBuildCommentOperationHandler.OperationStore store(
            String actionId)
    {
        requireNonNull(actionId, "actionId is null");
        return actionId.startsWith(
                SqliteReviewPassPublicationStore.ACTION_PREFIX)
                ? reviewPasses : reviewBuilds;
    }
}
