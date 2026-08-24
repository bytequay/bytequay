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

import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface PrDetailStore
{
    Optional<Instant> findSyncedAt(String repo, int number);

    Optional<StoredPrDetail> find(long prId);

    Optional<String> findEtag(long prId);

    void save(long prId, StoredPrDetail detail);

    void saveStatus(long prId, StoredPrDetail detail);

    void saveIncremental(long prId, StoredPrDetail detail);

    void updateEtag(long prId, Optional<String> etag);

    /** Records or returns one local timeline event identified by its stable local event key. */
    PrTimelineEvent recordLocalTimelineEvent(PullRequestRef pullRequest, PrTimelineEvent event);

    void deleteByPrIds(Set<Long> prIds);
}
