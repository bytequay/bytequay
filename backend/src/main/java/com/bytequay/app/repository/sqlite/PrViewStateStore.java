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
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@Repository
public class PrViewStateStore
{
    private final PrViewStateJpaRepository jpaRepository;

    public PrViewStateStore(PrViewStateJpaRepository jpaRepository)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
    }

    /** Returns all view-state rows, keyed by PR id. */
    public Map<Long, PrViewState> findAll()
    {
        return jpaRepository.findAll().stream()
                .collect(Collectors.toUnmodifiableMap(
                        PrViewStateEntity::getPrId,
                        e -> new PrViewState(
                                e.getPrId(),
                                e.getViewedAt(),
                                e.getReviewedAt(),
                                e.getHandledAction())));
    }

    /** Records that the user handled this PR with the given action. */
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

    private static PrViewStateEntity newEntity(long prId)
    {
        PrViewStateEntity entity = new PrViewStateEntity();
        entity.setPrId(prId);
        return entity;
    }
}
