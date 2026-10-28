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

import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrChangeSnapshot;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrCommitLine;
import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PrStateSummary;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface PrDetailStore
{
    Optional<Instant> findSyncedAt(String repo, int number);

    Optional<StoredPrDetail> find(long prId);

    Optional<String> findCredentialScope(long prId);

    /** Stamps an existing detail row after its remote data is fetched under the current credential. */
    void setCredentialScope(long prId, String scope);

    /** Clears remote cached groups while preserving locally recorded timeline events. */
    void clearRemote(long prId);

    /** Reads only cached list badges; missing details have no entry. */
    Map<Long, PrStateSummary> findStates(Set<Long> prIds);

    /** Batched inputs for changed-since-read indicators, without discussion bodies or review hydration. */
    Map<Long, PrChangeSnapshot> findChangeSnapshots(Set<Long> prIds);

    PrDetailFreshness findFreshness(long prId);

    /** The freshness of each stored detail among {@code prIds}; a pull request with no stored detail is absent. */
    Map<Long, PrDetailFreshness> findFreshness(Set<Long> prIds);

    /** Saves only metadata and its successful validation time. */
    void saveMetadata(long prId, StoredPrDetail detail);

    /** Reconciles discussion sources without marking cached checks fresh. */
    void saveDiscussion(long prId, StoredPrDetail detail, Instant sourceUpdatedAt);

    /** Reads only cached author avatar URLs; missing details or avatars have no entry. */
    Map<Long, String> findAuthorAvatarUrls(Set<Long> prIds);

    /** Reads the latest cached GitHub timeline action for each requested PR, including inline replies. */
    Map<Long, PrActivitySummary> findLatestActivities(Set<Long> prIds);

    /** Reads the cached commits each PR received after its instant, newest first; a PR without any has no entry. */
    Map<Long, List<PrCommitLine>> findCommitsAfter(Map<Long, Instant> since);

    /** Reads the cached check runs of each requested PR in stored order; a PR without any has no entry. */
    Map<Long, List<PrCheckRunState>> findCheckRuns(Set<Long> prIds);

    Optional<String> findEtag(long prId);

    Optional<String> findCheckRunsEtag(long prId);

    Optional<String> findCommitStatusesEtag(long prId);

    /** The ETag each REST discussion source last answered with, keyed by the source name the GitHub client chose. */
    Map<String, String> findDiscussionEtags(long prId);

    void save(long prId, StoredPrDetail detail);

    /** Applies locally known changes without claiming any remote group was validated. */
    void saveCached(long prId, StoredPrDetail detail);

    void saveStatus(long prId, StoredPrDetail detail);

    void updateEtag(long prId, Optional<String> etag);

    void updateStatusEtags(long prId, Optional<String> checkRunsEtag, Optional<String> commitStatusesEtag);

    void updateDiscussionEtags(long prId, Map<String, String> etags);

    /** Records or returns one local timeline event identified by its stable local event key. */
    PrTimelineEvent recordLocalTimelineEvent(PullRequestRef pullRequest, PrTimelineEvent event);

    void deleteByPrIds(Set<Long> prIds);
}
