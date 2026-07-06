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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA row for {@code pr_triage} — local dashboard-triage state, keyed 1:1 by PR id. */
@Entity
@Table(name = "pr_triage")
class PrTriageEntity
{
    @Id
    @Column(name = "pr_id", nullable = false)
    private String prId;

    @Column(name = "viewed_at_ms")
    private Long viewedAtMs;

    @Column(name = "reviewed_at_ms")
    private Long reviewedAtMs;

    @Column(name = "handled_action")
    private String handledAction;

    @Column(name = "snoozed_until_ms")
    private Long snoozedUntilMs;

    @Column(name = "snoozed_at_ms")
    private Long snoozedAtMs;

    @Column(name = "snooze_wake_reason")
    private String snoozeWakeReason;

    String getPrId() { return prId; }
    void setPrId(String prId) { this.prId = prId; }

    Long getViewedAtMs() { return viewedAtMs; }
    void setViewedAtMs(Long viewedAtMs) { this.viewedAtMs = viewedAtMs; }

    Long getReviewedAtMs() { return reviewedAtMs; }
    void setReviewedAtMs(Long reviewedAtMs) { this.reviewedAtMs = reviewedAtMs; }

    String getHandledAction() { return handledAction; }
    void setHandledAction(String handledAction) { this.handledAction = handledAction; }

    Long getSnoozedUntilMs() { return snoozedUntilMs; }
    void setSnoozedUntilMs(Long snoozedUntilMs) { this.snoozedUntilMs = snoozedUntilMs; }

    Long getSnoozedAtMs() { return snoozedAtMs; }
    void setSnoozedAtMs(Long snoozedAtMs) { this.snoozedAtMs = snoozedAtMs; }

    String getSnoozeWakeReason() { return snoozeWakeReason; }
    void setSnoozeWakeReason(String snoozeWakeReason) { this.snoozeWakeReason = snoozeWakeReason; }
}
