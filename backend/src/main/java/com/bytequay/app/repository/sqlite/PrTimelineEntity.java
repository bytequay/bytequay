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
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pr_timeline")
class PrTimelineEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long prId;

    private String event;

    private String actor;

    private String state;

    @Convert(converter = InstantToTextConverter.class)
    private Instant timestamp;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "before_sha")
    private String beforeSha;

    @Column(name = "after_sha")
    private String afterSha;

    @Column(name = "requested_reviewer")
    private String requestedReviewer;

    @Column(name = "review_id")
    private Long reviewId;

    /** Stable GitHub event id; lets the upsert-on-insert pattern dedupe
     *  rows across incremental sync windows. Null only on legacy rows
     *  written before V25. */
    @Column(name = "github_id")
    private Long githubId;

    /** GitHub author_association — see V30 migration. */
    @Column(name = "author_association")
    private String authorAssociation;

    /** Reactions on issue/PR comments (V32). All 0 for non-comment
     *  timeline events. */
    @Column(name = "reactions_plus_one", nullable = false)
    private int reactionsPlusOne;
    @Column(name = "reactions_minus_one", nullable = false)
    private int reactionsMinusOne;
    @Column(name = "reactions_laugh", nullable = false)
    private int reactionsLaugh;
    @Column(name = "reactions_hooray", nullable = false)
    private int reactionsHooray;
    @Column(name = "reactions_confused", nullable = false)
    private int reactionsConfused;
    @Column(name = "reactions_heart", nullable = false)
    private int reactionsHeart;
    @Column(name = "reactions_rocket", nullable = false)
    private int reactionsRocket;
    @Column(name = "reactions_eyes", nullable = false)
    private int reactionsEyes;

    protected PrTimelineEntity() {}

    Long getId() { return id; }

    Long getPrId() { return prId; }
    void setPrId(Long prId) { this.prId = prId; }

    String getEvent() { return event; }
    void setEvent(String event) { this.event = event; }

    String getActor() { return actor; }
    void setActor(String actor) { this.actor = actor; }

    String getState() { return state; }
    void setState(String state) { this.state = state; }

    Instant getTimestamp() { return timestamp; }
    void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    String getBody() { return body; }
    void setBody(String body) { this.body = body; }

    String getBeforeSha() { return beforeSha; }
    void setBeforeSha(String beforeSha) { this.beforeSha = beforeSha; }

    String getAfterSha() { return afterSha; }
    void setAfterSha(String afterSha) { this.afterSha = afterSha; }

    String getRequestedReviewer() { return requestedReviewer; }
    void setRequestedReviewer(String requestedReviewer) { this.requestedReviewer = requestedReviewer; }

    Long getReviewId() { return reviewId; }
    void setReviewId(Long reviewId) { this.reviewId = reviewId; }

    Long getGithubId() { return githubId; }
    void setGithubId(Long githubId) { this.githubId = githubId; }

    String getAuthorAssociation() { return authorAssociation; }
    void setAuthorAssociation(String authorAssociation) { this.authorAssociation = authorAssociation; }

    int getReactionsPlusOne() { return reactionsPlusOne; }
    void setReactionsPlusOne(int v) { this.reactionsPlusOne = v; }
    int getReactionsMinusOne() { return reactionsMinusOne; }
    void setReactionsMinusOne(int v) { this.reactionsMinusOne = v; }
    int getReactionsLaugh() { return reactionsLaugh; }
    void setReactionsLaugh(int v) { this.reactionsLaugh = v; }
    int getReactionsHooray() { return reactionsHooray; }
    void setReactionsHooray(int v) { this.reactionsHooray = v; }
    int getReactionsConfused() { return reactionsConfused; }
    void setReactionsConfused(int v) { this.reactionsConfused = v; }
    int getReactionsHeart() { return reactionsHeart; }
    void setReactionsHeart(int v) { this.reactionsHeart = v; }
    int getReactionsRocket() { return reactionsRocket; }
    void setReactionsRocket(int v) { this.reactionsRocket = v; }
    int getReactionsEyes() { return reactionsEyes; }
    void setReactionsEyes(int v) { this.reactionsEyes = v; }
}
