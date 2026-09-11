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

import com.bytequay.core.review.ReviewMessage;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "agent_review_message", indexes = @Index(name = "idx_agent_review_message_review", columnList = "review_id"))
public class AgentReviewMessageEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "review_id", nullable = false)
    private String reviewId;

    @Column(name = "finding_id")
    private String findingId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ReviewMessage.Author author;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "target_revision", nullable = false)
    private int targetRevision;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "turn_id")
    private String turnId;

    @Column(name = "queued_input_id")
    private String queuedInputId;

    @Version
    @Getter
    private long version;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    public AgentReviewMessageEntity() {}

    public static AgentReviewMessageEntity fromDomain(ReviewMessage message)
    {
        AgentReviewMessageEntity entity = new AgentReviewMessageEntity();
        entity.id = message.id();
        entity.version = message.version();
        entity.updateFrom(message);
        return entity;
    }

    public void updateFrom(ReviewMessage message)
    {
        reviewId = message.reviewId();
        findingId = message.findingId().orElse(null);
        author = message.author();
        body = message.body();
        targetRevision = message.targetRevision();
        runId = message.runId();
        turnId = message.turnId().orElse(null);
        queuedInputId = message.queuedInputId().orElse(null);
        createdAt = message.createdAt();
    }

    public ReviewMessage toDomain()
    {
        return new ReviewMessage(
                id,
                reviewId,
                Optional.ofNullable(findingId),
                author,
                body,
                targetRevision,
                runId,
                Optional.ofNullable(turnId),
                Optional.ofNullable(queuedInputId),
                version,
                createdAt);
    }
}
