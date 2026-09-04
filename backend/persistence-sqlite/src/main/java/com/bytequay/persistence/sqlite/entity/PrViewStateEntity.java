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
import com.bytequay.core.domain.PrViewState;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "pr_view_state")
@Getter
@Setter
public class PrViewStateEntity
{
    @Id
    private Long prId;

    @Convert(converter = InstantToTextConverter.class)
    private Instant viewedAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant reviewedAt;

    @Column(name = "handled_action")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private String handledAction;

    @Column(name = "reviewed_head_sha")
    private String reviewedHeadSha;

    public PrViewStateEntity() {}

    public HandledAction getHandledAction() { return handledAction == null ? null : HandledAction.valueOf(handledAction); }
    public void setHandledAction(HandledAction handledAction) { this.handledAction = handledAction == null ? null : handledAction.name(); }

    public PrViewState toDomain()
    {
        return new PrViewState(getPrId(), getViewedAt(), getReviewedAt(), getHandledAction(), getReviewedHeadSha());
    }
}
