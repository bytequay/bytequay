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

import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewOperation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
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
import java.util.Map;
import java.util.Optional;

@Entity
@Table(name = "agent_review_run", indexes = @Index(name = "idx_agent_review_run_review", columnList = "review_id"))
public class AgentReviewRunEntity
        extends AssignedIdEntity<String>
{
    private static final TypeReference<List<ReviewCommit>> COMMITS = new TypeReference<>() {};
    private static final TypeReference<List<DiffFile>> DIFF = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, ReviewOperation>> OPERATIONS = new TypeReference<>() {};
    private static final TypeReference<Map<String, ReviewEvidence>> EVIDENCE = new TypeReference<>() {};

    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "review_id", nullable = false)
    private String reviewId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ReviewRun.Scope scope;

    @Column(name = "base_ref", nullable = false)
    private String baseRef;

    @Column(name = "base_sha", nullable = false)
    private String baseSha;

    @Column(name = "merge_base_sha", nullable = false)
    private String mergeBaseSha;

    @Column(name = "head_sha", nullable = false)
    private String headSha;

    @Column(name = "prior_head_sha")
    private String priorHeadSha;

    @Column(name = "commits_json", nullable = false, columnDefinition = "TEXT")
    private String commitsJson;

    @Column(name = "diff_json", nullable = false, columnDefinition = "TEXT")
    private String diffJson;

    @Column(name = "turn_ids_json", nullable = false, columnDefinition = "TEXT")
    private String turnIdsJson;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ReviewRun.State state;

    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    @Column(name = "coverage_json", columnDefinition = "TEXT")
    private String coverageJson;

    @Column(name = "completion_repair_used", nullable = false)
    private boolean completionRepairUsed;

    @Column(name = "operations_json", nullable = false, columnDefinition = "TEXT")
    private String operationsJson;

    @Column(name = "evidence_json", nullable = false, columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(columnDefinition = "TEXT")
    private String outcome;

    @Version
    @Getter
    private long version;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Convert(converter = InstantToTextConverter.class)
    private Instant endedAt;

    public AgentReviewRunEntity() {}

    public static AgentReviewRunEntity fromDomain(ReviewRun run)
    {
        AgentReviewRunEntity entity = new AgentReviewRunEntity();
        entity.id = run.id();
        entity.version = run.version();
        entity.updateFrom(run);
        return entity;
    }

    public void updateFrom(ReviewRun run)
    {
        reviewId = run.reviewId();
        ordinal = run.ordinal();
        scope = run.scope();
        baseRef = run.baseRef();
        baseSha = run.baseSha();
        mergeBaseSha = run.mergeBaseSha();
        headSha = run.headSha();
        priorHeadSha = run.priorHeadSha().orElse(null);
        commitsJson = AgentReviewJson.encode(run.commits());
        diffJson = AgentReviewJson.encode(run.diff());
        turnIdsJson = AgentReviewJson.encode(run.turnIds());
        state = run.state();
        summaryJson = run.summary().map(AgentReviewJson::encode).orElse(null);
        coverageJson = run.coverage().map(AgentReviewJson::encode).orElse(null);
        completionRepairUsed = run.completionRepairUsed();
        operationsJson = AgentReviewJson.encode(run.operations());
        evidenceJson = AgentReviewJson.encode(run.evidence());
        outcome = run.outcome().orElse(null);
        createdAt = run.createdAt();
        updatedAt = run.updatedAt();
        endedAt = run.endedAt().orElse(null);
    }

    public ReviewRun toDomain()
    {
        return new ReviewRun(
                id,
                reviewId,
                ordinal,
                scope,
                baseRef,
                baseSha,
                mergeBaseSha,
                headSha,
                Optional.ofNullable(priorHeadSha),
                AgentReviewJson.decode(commitsJson, COMMITS),
                AgentReviewJson.decode(diffJson, DIFF),
                AgentReviewJson.decode(turnIdsJson, STRINGS),
                state,
                Optional.ofNullable(summaryJson).map(json -> AgentReviewJson.decode(json, ReviewSummary.class)),
                Optional.ofNullable(coverageJson).map(json -> AgentReviewJson.decode(json, ReviewCoverage.class)),
                completionRepairUsed,
                AgentReviewJson.decode(operationsJson, OPERATIONS),
                AgentReviewJson.decode(evidenceJson, EVIDENCE),
                Optional.ofNullable(outcome),
                version,
                createdAt,
                updatedAt,
                Optional.ofNullable(endedAt));
    }
}
