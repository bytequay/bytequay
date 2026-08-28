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

import com.bytequay.core.domain.PrReviewState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "pr_reviews",
        indexes = @Index(
                name = "idx_pr_reviews_pr_github",
                columnList = "pr_id, github_id"))
@Getter
@Setter
public class PrReviewEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    private Long prId;

    @Column(name = "github_id")
    private Long githubId;

    @Column(nullable = false)
    private String login;

    @Column(nullable = false)
    private String state;

    private String avatarUrl;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean bot;

    @Column(columnDefinition = "TEXT")
    private String body;

    private String htmlUrl;

    private String commitId;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    public PrReviewEntity() {}

    public boolean isBot() { return Boolean.TRUE.equals(bot); }
    public void setBot(boolean bot) { this.bot = bot; }

    public PrReviewState toDomain()
    {
        return new PrReviewState(
                getGithubId(),
                getLogin(),
                getAvatarUrl(),
                isBot(),
                getState(),
                getSubmittedAt(),
                getBody(),
                getHtmlUrl(),
                getCommitId());
    }

    public PrReviewEntity updateFrom(long prId, PrReviewState review)
    {
        setPrId(prId);
        setGithubId(review.githubId());
        setLogin(review.login());
        setAvatarUrl(review.avatarUrl());
        setBot(review.bot());
        setState(review.state());
        setSubmittedAt(review.submittedAt());
        setBody(review.body());
        setHtmlUrl(review.htmlUrl());
        setCommitId(review.commitId());
        return this;
    }
}
