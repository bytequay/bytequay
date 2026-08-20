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
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface PullRequestStore
{
    List<PullRequest> findAll();

    void replaceAll(List<PullRequest> pullRequests);

    Map<Long, Instant> findUpdatedAtMap();

    Set<Long> findIdsMissingEnrichment();

    Optional<Long> findIdByRepoAndNumber(String repo, int number);

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
