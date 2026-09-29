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
package com.bytequay.app.repository.github;

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PullRequestDetail.CiStatus;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Card and notification facts, deliberately independent of the full detail snapshot. */
public record GitHubPullRequestSummary(
        FetchedPullRequest pullRequest,
        CiStatus ciStatus,
        int additions,
        int deletions,
        int commentCount,
        String headSha,
        Optional<Instant> headCommittedAt,
        Optional<String> reviewDecision,
        Optional<PrReviewState> viewerReview,
        Optional<Instant> reviewRequestedAt,
        String activityVersion)
{
    public GitHubPullRequestSummary
    {
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(ciStatus, "ciStatus is null");
        checkArgument(additions >= 0 && deletions >= 0 && commentCount >= 0, "summary counts are negative");
        requireNonNull(headSha, "headSha is null");
        requireNonNull(headCommittedAt, "headCommittedAt is null");
        requireNonNull(reviewDecision, "reviewDecision is null");
        requireNonNull(viewerReview, "viewerReview is null");
        requireNonNull(reviewRequestedAt, "reviewRequestedAt is null");
        requireNonNull(activityVersion, "activityVersion is null");
    }
}
