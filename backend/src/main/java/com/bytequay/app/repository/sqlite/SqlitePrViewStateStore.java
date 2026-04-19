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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.repository.PrViewStateStore;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;

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

    @Override
    public Map<Long, PrViewState> findAll()
    {
        return jpaRepository.findAll().stream()
                .collect(toImmutableMap(
                        PrViewStateEntity::getPrId,
                        e -> new PrViewState(
                                e.getPrId(),
                                e.getViewedAt(),
                                e.getSnoozedUntil(),
                                e.getReviewedAt(),
                                e.getHandledAction())));
    }

    @Override
    public void markViewed(long prId)
    {
        PrViewStateEntity entity = jpaRepository.findById(prId).orElseGet(() -> newEntity(prId));
        if (entity.getViewedAt() == null) {
            entity.setViewedAt(Instant.now());
            jpaRepository.save(entity);
        }
    }

    @Override
    public void markReviewed(long prId, HandledAction action)
    {
        requireNonNull(action, "action is null");
        PrViewStateEntity entity = jpaRepository.findById(prId).orElseGet(() -> newEntity(prId));
        Instant now = Instant.now();
        if (entity.getViewedAt() == null) {
            entity.setViewedAt(now);
        }
        entity.setReviewedAt(now);
        entity.setHandledAction(action);
        jpaRepository.save(entity);
    }

    @Override
    public void reopen(long prId)
    {
        jpaRepository.findById(prId).ifPresent(entity -> {
            entity.setReviewedAt(null);
            entity.setHandledAction(null);
            jpaRepository.save(entity);
        });
    }

    private static PrViewStateEntity newEntity(long prId)
    {
        PrViewStateEntity entity = new PrViewStateEntity();
        entity.setPrId(prId);
        return entity;
    }
}
