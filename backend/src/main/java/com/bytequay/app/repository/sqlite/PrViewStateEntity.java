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
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pr_view_state")
class PrViewStateEntity
{
    @Id
    private Long prId;

    @Convert(converter = InstantToTextConverter.class)
    private Instant viewedAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant snoozedUntil;

    @Column(name = "snoozed_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant snoozedAt;

    @Column(name = "snooze_wake_reason")
    private String snoozeWakeReason;

    @Convert(converter = InstantToTextConverter.class)
    private Instant reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "handled_action")
    private HandledAction handledAction;

    @Column(nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalCreatedAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalUpdatedAt;

    protected PrViewStateEntity() {}

    @PrePersist
    void prePersist()
    {
        Instant now = Instant.now();
        this.internalCreatedAt = now;
        this.internalUpdatedAt = now;
    }

    @PreUpdate
    void preUpdate()
    {
        this.internalUpdatedAt = Instant.now();
    }

    Long getPrId() { return prId; }
    void setPrId(Long prId) { this.prId = prId; }

    Instant getViewedAt() { return viewedAt; }
    void setViewedAt(Instant viewedAt) { this.viewedAt = viewedAt; }

    Instant getSnoozedUntil() { return snoozedUntil; }
    void setSnoozedUntil(Instant snoozedUntil) { this.snoozedUntil = snoozedUntil; }

    Instant getSnoozedAt() { return snoozedAt; }
    void setSnoozedAt(Instant snoozedAt) { this.snoozedAt = snoozedAt; }

    String getSnoozeWakeReason() { return snoozeWakeReason; }
    void setSnoozeWakeReason(String snoozeWakeReason) { this.snoozeWakeReason = snoozeWakeReason; }

    Instant getReviewedAt() { return reviewedAt; }
    void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    HandledAction getHandledAction() { return handledAction; }
    void setHandledAction(HandledAction handledAction) { this.handledAction = handledAction; }
}
