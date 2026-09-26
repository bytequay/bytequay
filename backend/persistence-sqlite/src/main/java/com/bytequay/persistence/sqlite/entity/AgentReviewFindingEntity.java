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

import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.REVIEW;

@Entity
@Table(name = "agent_review_finding", indexes = @Index(name = "idx_agent_review_finding_review", columnList = "review_id"))
public class AgentReviewFindingEntity
        extends AssignedIdEntity<String>
{
    private static final TypeReference<List<ReviewEvidence>> EVIDENCE = new TypeReference<>() {};

    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "review_id", nullable = false)
    private String reviewId;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    private String severity;

    @Column(name = "anchor_json", nullable = false, columnDefinition = "TEXT")
    private String anchorJson;

    @Column(name = "evidence_json", nullable = false, columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ReviewFinding.State state;

    @Column(name = "state_reason", columnDefinition = "TEXT")
    private String stateReason;

    @Column(name = "validated_head_sha", nullable = false)
    private String validatedHeadSha;

    @Column(nullable = false)
    private boolean outdated;

    @Version
    @Getter
    private long version;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    public AgentReviewFindingEntity() {}

    public static AgentReviewFindingEntity fromDomain(ReviewFinding finding)
    {
        AgentReviewFindingEntity entity = new AgentReviewFindingEntity();
        entity.id = finding.id();
        entity.version = finding.version();
        entity.updateFrom(finding);
        return entity;
    }

    public void updateFrom(ReviewFinding finding)
    {
        reviewId = finding.reviewId();
        runId = finding.runId();
        ordinal = finding.ordinal();
        title = finding.title();
        body = finding.body();
        severity = finding.severity().orElse(null);
        anchorJson = REVIEW.write(finding.anchor().orElse(null), "finding anchor");
        evidenceJson = REVIEW.write(finding.evidence(), "finding evidence");
        revision = finding.revision();
        state = finding.state();
        stateReason = finding.stateReason().orElse(null);
        validatedHeadSha = finding.validatedHeadSha();
        outdated = finding.outdated();
        createdAt = finding.createdAt();
        updatedAt = finding.updatedAt();
    }

    public ReviewFinding toDomain()
    {
        return new ReviewFinding(
                id,
                reviewId,
                runId,
                ordinal,
                title,
                body,
                Optional.ofNullable(severity),
                Optional.ofNullable(REVIEW.read(anchorJson, ReviewAnchor.class, "finding anchor")),
                REVIEW.read(evidenceJson, EVIDENCE, "finding evidence"),
                revision,
                state,
                Optional.ofNullable(stateReason),
                validatedHeadSha,
                outdated,
                version,
                createdAt,
                updatedAt);
    }
}
