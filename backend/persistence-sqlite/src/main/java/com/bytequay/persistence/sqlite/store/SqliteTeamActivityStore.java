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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.TeamActivityEvent;
import com.bytequay.core.store.TeamActivityStore;
import com.bytequay.persistence.sqlite.entity.TeamActivityEntity;
import com.bytequay.persistence.sqlite.repository.TeamActivityJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteTeamActivityStore
        implements TeamActivityStore
{
    private final TeamActivityJpaRepository events;

    public SqliteTeamActivityStore(TeamActivityJpaRepository events)
    {
        this.events = requireNonNull(events, "events is null");
    }

    @Override
    @Transactional
    public void save(long accountId, List<TeamActivityEvent> observations)
    {
        for (TeamActivityEvent observation : observations) {
            TeamActivityEntity entity = events.findById(TeamActivityEntity.key(accountId, observation.id()))
                    .orElseGet(() -> TeamActivityEntity.of(accountId, observation));
            entity.update(entity.toDomain().merge(observation));
            events.save(entity);
        }
    }

    @Override
    public List<TeamActivityEvent> findSince(long accountId, Instant since)
    {
        return events.findByAccountIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(accountId, since).stream()
                .map(TeamActivityEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void deleteBefore(Instant cutoff)
    {
        events.deleteBefore(cutoff);
    }
}
