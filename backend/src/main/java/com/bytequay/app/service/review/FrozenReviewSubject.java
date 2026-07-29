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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore.ReviewRoundSnapshot;

import java.nio.file.Path;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** Reconstructs a round subject exclusively from its immutable DB evidence. */
final class FrozenReviewSubject
{
    private FrozenReviewSubject() {}

    static InvestigationReviewContext.Snapshot snapshot(
            AgentReviewRow review, ReviewRoundSnapshot frozen)
    {
        requireNonNull(review, "review is null");
        requireNonNull(frozen, "frozen is null");
        PR pr = frozenPr(review, frozen);
        return new InvestigationReviewContext.Snapshot(
                pr, frozen.baseCommit(), frozen.headCommit(), frozen.diff(),
                frozen.files(), path(frozen.localRoot()),
                path(frozen.repositoryRoot()), frozen.capabilities(),
                frozen.fileContents());
    }

    private static PR frozenPr(
            AgentReviewRow review, ReviewRoundSnapshot frozen)
    {
        if ((frozen.repository() == null) != (frozen.remotePrNumber() == null)
                || frozen.remotePrNumber() != null
                    && frozen.remotePrNumber() <= 0) {
            throw new IllegalStateException(
                    "frozen review route is incomplete");
        }
        if (review.ownerTaskId() != null) {
            PR taskPr = PR.create(
                            review.prId(), review.ownerTaskId(), "",
                            frozen.baseBranch(), frozen.prTitle(),
                            frozen.prDescription(), Instant.EPOCH)
                    .withStatus(PR.STATUS_LOCAL_OPEN, Instant.EPOCH);
            if (frozen.repository() == null) {
                return taskPr;
            }
            return taskPr.withRemote(
                            frozen.repository(), frozen.remotePrNumber(), null,
                            Instant.EPOCH)
                    .withStatus(PR.STATUS_REMOTE_OPEN, Instant.EPOCH);
        }
        if (frozen.repository() == null) {
            throw new IllegalStateException(
                    "standalone review has no frozen PR route");
        }
        return PR.createExternal(
                review.prId(), frozen.repository(), frozen.remotePrNumber(),
                null, null, "", frozen.baseBranch(), frozen.prTitle(),
                frozen.prDescription(),
                PR.STATUS_REMOTE_OPEN, Instant.EPOCH, null, null);
    }

    private static Path path(String value)
    {
        return value == null ? null : Path.of(value);
    }
}
