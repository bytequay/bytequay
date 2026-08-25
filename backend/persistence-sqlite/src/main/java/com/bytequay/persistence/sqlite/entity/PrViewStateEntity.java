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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.domain.HandledAction;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pr_view_state")
public class PrViewStateEntity
{
    @Id
    private Long prId;

    @Convert(converter = InstantToTextConverter.class)
    private Instant viewedAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant reviewedAt;

    @Column(name = "handled_action")
    private String handledAction;

    @Column(name = "reviewed_head_sha")
    private String reviewedHeadSha;

    @Column(nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalCreatedAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalUpdatedAt;

    public PrViewStateEntity() {}

    @PrePersist
    public void prePersist()
    {
        Instant now = Instant.now();
        this.internalCreatedAt = now;
        this.internalUpdatedAt = now;
    }

    @PreUpdate
    public void preUpdate()
    {
        this.internalUpdatedAt = Instant.now();
    }

    public Long getPrId() { return prId; }
    public void setPrId(Long prId) { this.prId = prId; }

    public Instant getViewedAt() { return viewedAt; }
    public void setViewedAt(Instant viewedAt) { this.viewedAt = viewedAt; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public HandledAction getHandledAction() { return handledAction == null ? null : HandledAction.valueOf(handledAction); }
    public void setHandledAction(HandledAction handledAction) { this.handledAction = handledAction == null ? null : handledAction.name(); }

    public String getReviewedHeadSha() { return reviewedHeadSha; }
    public void setReviewedHeadSha(String reviewedHeadSha) { this.reviewedHeadSha = reviewedHeadSha; }
}
