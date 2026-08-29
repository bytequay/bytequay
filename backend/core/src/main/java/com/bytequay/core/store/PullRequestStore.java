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

import com.bytequay.core.domain.AttentionReason;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.PullRequestRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface PullRequestStore
{
    PullRequestPage findPage(int page, int pageSize);

    PullRequestPage findPage(int page, int pageSize, String search);

    PullRequestPage findRepositoryPage(String repository, int page, int pageSize);

    PullRequestPage findRepositoryPage(String repository, int page, int pageSize, String search);

    Map<Long, Instant> findUpdatedAtByIds(Set<Long> ids);

    Set<Long> findIdsMissingEnrichment(Set<Long> ids);

    /** Stores a page of fetched pull requests and returns them as stored rows. */
    List<PullRequest> saveDashboardSyncPage(List<FetchedPullRequest> pullRequests, String syncMarker);

    Set<Long> completeDashboardSync(String syncMarker, String currentLogin);

    List<PullRequest> saveRepositorySyncPage(
            String repository, List<FetchedPullRequest> pullRequests, String syncMarker);

    List<PullRequest> findRepositoryRowsMissingSync(String repository, String syncMarker);

    /** Stores a pull request opened directly by the user until normal sync reconciliation runs. */
    /** Stores the pull request and returns the stored row, creating one if needed. */
    PullRequest saveObserved(FetchedPullRequest pullRequest);

    Set<Long> completeRepositorySync(String repository, Instant retentionCutoff);

    Optional<Long> findIdByRepoAndNumber(String repo, int number);

    Optional<PullRequestRef> findRefById(long prId);

    void updateState(long prId, String state, Instant updatedAt, Instant mergedAt);

    void updateEnrichment(
            long prId,
            PullRequestDetail.CiStatus ciStatus,
            int additions,
            int deletions,
            int commentCount,
            AttentionReason attentionReason,
            Boolean mergeable,
            String mergeableState,
            Instant headPushedAt,
            Map<String, String> reviewerVerdicts,
            String headRef);

    void updateCiStatus(long prId, PullRequestDetail.CiStatus ciStatus);
}
