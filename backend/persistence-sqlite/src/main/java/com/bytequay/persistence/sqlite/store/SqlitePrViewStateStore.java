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

import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.persistence.sqlite.entity.PrViewStateEntity;
import com.bytequay.persistence.sqlite.repository.PrViewStateJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Repository
public class SqlitePrViewStateStore
        implements PrViewStateStore
{
    private final PrViewStateJpaRepository jpaRepository;

    public SqlitePrViewStateStore(PrViewStateJpaRepository jpaRepository)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
    }

    /** Returns only the requested view-state rows, keyed by PR id. */
    @Override
    public Map<Long, PrViewState> findByPrIds(Set<Long> prIds)
    {
        requireNonNull(prIds, "prIds is null");
        return toDomain(jpaRepository.findAllById(prIds).stream());
    }

    private static Map<Long, PrViewState> toDomain(java.util.stream.Stream<PrViewStateEntity> entities)
    {
        return entities.collect(toImmutableMap(PrViewStateEntity::getPrId, PrViewStateEntity::toDomain));
    }

    /** Records that the user handled this PR with the given action. */
    @Override
    public void markReviewed(long prId, HandledAction action, String headSha)
    {
        requireNonNull(action, "action is null");
        PrViewStateEntity entity = jpaRepository.findById(prId).orElseGet(() -> newEntity(prId));
        Instant now = Instant.now();
        if (entity.getViewedAt() == null) {
            entity.setViewedAt(now);
        }
        entity.setReviewedAt(now);
        entity.setHandledAction(action);
        entity.setReviewedHeadSha(headSha);
        jpaRepository.save(entity);
    }

    @Override
    public void markNeedsReview(long prId)
    {
        PrViewStateEntity entity = jpaRepository.findById(prId).orElse(null);
        if (entity == null) {
            return;
        }
        entity.setHandledAction(null);
        jpaRepository.save(entity);
    }

    private static PrViewStateEntity newEntity(long prId)
    {
        PrViewStateEntity entity = new PrViewStateEntity();
        entity.setPrId(prId);
        return entity;
    }
}
