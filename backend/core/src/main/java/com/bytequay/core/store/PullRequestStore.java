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
package com.bytequay.core.store;

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestPage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface PullRequestStore
{
    PullRequestPage findPage(int page, int pageSize);

    List<PullRequest> findAttentionCandidates();

    Optional<PullRequest> findById(long prId);

    void setMonitored(long prId, boolean monitored);

    Map<Long, PrActivitySummary> findActivities(Set<Long> prIds);

    Optional<Instant> findDiscussionActivityAt(long prId);

    void observeActivity(long prId, Optional<PrActivitySummary> activity, Instant discussionActivityAt);

    /** When each pull request last had activity that ends an acknowledgment in Needs attention; absent until a refresh finds some. */
    Map<Long, Instant> findAttentionActivities(Set<Long> prIds);

    /** The latest discussion activity recorded for each of {@code prIds}; a pull request without one is absent. */
    Map<Long, Instant> findDiscussionActivities(Set<Long> prIds);

    /** Keeps the later of the stored and the observed attention activity. */
    void observeAttentionActivity(long prId, Instant at);

    PullRequestPage findRepositoryPage(String repository, int page, int pageSize);

    PullRequestPage findRepositoryPage(String repository, int page, int pageSize, String search);

    /** One repository's merged rows, newest merge first; a non-blank search matches the title or a #number. */
    List<PullRequest> findMergedRepositoryPullRequests(String repository, String search, int limit);

    /** Stores a page of fetched pull requests and returns them as stored rows. */
    List<PullRequest> saveDashboardSyncPage(List<FetchedPullRequest> pullRequests);

    List<PullRequest> saveRepositorySyncPage(String repository, List<FetchedPullRequest> pullRequests);

    /** Stores the pull request and returns the stored row, creating one if needed. */
    PullRequest saveObserved(FetchedPullRequest pullRequest);

    Optional<Long> findIdByRepoAndNumber(String repo, int number);

    /** Every stored pull request whose head is the given branch of the repository, newest number first. */
    List<PullRequest> findByHeadRef(String repo, String headRef);

    void updateDetails(long prId, PrRawDetail detail);

    void updateTitle(long prId, String title);

    void updateEnrichment(
            long prId,
            Instant sourceUpdatedAt,
            PullRequestDetail.CiStatus ciStatus,
            int additions,
            int deletions,
            int commentCount,
            Boolean mergeable,
            String mergeableState,
            Instant headPushedAt,
            String headRef,
            List<String> requestedReviewers,
            PullRequest.Origin origin,
            Optional<Instant> reviewRequestedAt,
            Optional<String> reviewDecision,
            Map<String, String> activityFingerprints);
}
