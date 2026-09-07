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

import com.bytequay.core.review.AgentReview;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

@Entity
@Table(name = "agent_review", indexes = @Index(name = "idx_agent_review_pr", columnList = "workspace_id, repo, number"))
public class AgentReviewEntity
        implements Persistable<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private int number;

    @Column(name = "pull_request_id")
    private Long pullRequestId;

    @Column(name = "task_id", nullable = false, unique = true)
    private String taskId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AgentReview.State state;

    @Column(name = "follow_up_paused", nullable = false)
    private boolean followUpPaused;

    @Column(name = "latest_completed_run_id")
    private String latestCompletedRunId;

    @Column(name = "observed_head_sha")
    private String observedHeadSha;

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
    private Instant closedAt;

    @Transient
    private boolean newEntity = true;

    public AgentReviewEntity() {}

    public static AgentReviewEntity fromDomain(AgentReview review)
    {
        AgentReviewEntity entity = new AgentReviewEntity();
        entity.id = review.id();
        entity.version = review.version();
        entity.updateFrom(review);
        return entity;
    }

    public void updateFrom(AgentReview review)
    {
        workspaceId = review.workspaceId();
        repo = review.repo();
        number = review.number();
        pullRequestId = review.pullRequestId().isPresent() ? review.pullRequestId().getAsLong() : null;
        taskId = review.taskId();
        state = review.state();
        followUpPaused = review.followUpPaused();
        latestCompletedRunId = review.latestCompletedRunId().orElse(null);
        observedHeadSha = review.observedHeadSha().orElse(null);
        createdAt = review.createdAt();
        updatedAt = review.updatedAt();
        closedAt = review.closedAt().orElse(null);
    }

    public AgentReview toDomain()
    {
        return new AgentReview(
                id,
                workspaceId,
                repo,
                number,
                pullRequestId == null ? OptionalLong.empty() : OptionalLong.of(pullRequestId),
                taskId,
                state,
                followUpPaused,
                Optional.ofNullable(latestCompletedRunId),
                Optional.ofNullable(observedHeadSha),
                version,
                createdAt,
                updatedAt,
                Optional.ofNullable(closedAt));
    }

    @Override
    public boolean isNew()
    {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    private void markPersisted()
    {
        newEntity = false;
    }
}
