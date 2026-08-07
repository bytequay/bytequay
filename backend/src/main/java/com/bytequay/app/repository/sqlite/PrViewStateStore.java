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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
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
                .collect(toImmutableMap(
                        PrViewStateEntity::getPrId,
                        e -> new PrViewState(
                                e.getPrId(),
                                e.getViewedAt(),
                                e.getSnoozedUntil(),
                                e.getSnoozedAt(),
                                e.getSnoozeWakeReason(),
                                e.getReviewedAt(),
                                e.getHandledAction())));
    }

    /** Park the PR until {@code until}. Replaces any existing snooze and
    *  clears any pending wake reason. */
    public void snooze(long prId, Instant until)
    {
        requireNonNull(until, "until is null");
        PrViewStateEntity entity = jpaRepository.findById(prId).orElseGet(() -> newEntity(prId));
        entity.setSnoozedUntil(until);
        entity.setSnoozedAt(Instant.now());
        // A fresh snooze clears any prior auto-wake reason — the user
        // is parking the PR again on purpose.
        entity.setSnoozeWakeReason(null);
        jpaRepository.save(entity);
    }

    /** Wake a snoozed PR. {@code wakeReason} is recorded so the UI can
    *  surface the just-woke alert; pass null on user-initiated wake
    *  ("Wake now") to skip the alert. */
    public void unsnooze(long prId, String wakeReason)
    {
        jpaRepository.findById(prId).ifPresent(entity -> {
            entity.setSnoozedUntil(null);
            entity.setSnoozedAt(null);
            entity.setSnoozeWakeReason(wakeReason);
            jpaRepository.save(entity);
        });
    }

    /** Drop a stored wake reason once the user has acknowledged it. */
    public void clearWakeReason(long prId)
    {
        jpaRepository.findById(prId).ifPresent(entity -> {
            if (entity.getSnoozeWakeReason() != null) {
                entity.setSnoozeWakeReason(null);
                jpaRepository.save(entity);
            }
        });
    }

    /** Records that the user viewed this PR for the first time. Idempotent. */
    public void markViewed(long prId)
    {
        PrViewStateEntity entity = jpaRepository.findById(prId).orElseGet(() -> newEntity(prId));
        if (entity.getViewedAt() == null) {
            entity.setViewedAt(Instant.now());
            jpaRepository.save(entity);
        }
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

    /** Clears the reviewed timestamp and action so the PR returns to the Inbox. */
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
