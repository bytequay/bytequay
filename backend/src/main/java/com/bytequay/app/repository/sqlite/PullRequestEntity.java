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

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.PullRequestDetail;
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
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "pull_requests")
class PullRequestEntity
{
    @Id
    private Long id;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false)
    private String title;

    private String author;

    private String htmlUrl;

    @Convert(converter = InstantToTextConverter.class)
    private Instant updatedAt;

    @Column(name = "created_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant createdAt;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    @Convert(converter = StringListConverter.class)
    private List<String> labels;

    @Column(name = "label_colors")
    @Convert(converter = StringMapConverter.class)
    private Map<String, String> labelColors;

    @Column(nullable = false)
    private boolean draft;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant syncedAt;

    /** Set by the sync job from the accompanying detail fetch. NULL when not yet enriched. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ci_status")
    private PullRequestDetail.CiStatus ciStatus;

    @Column(name = "comment_count", nullable = false)
    private int commentCount;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;

    /** NULL means the PR isn't promoted to "Needs attention". */
    @Enumerated(EnumType.STRING)
    @Column(name = "attention_reason")
    private AttentionReason attentionReason;

    // --- Phase 1 kanban-refactor fields (V26). All nullable so existing rows
    //     survive without re-sync; populated lazily as PRs are next touched. ---

    /** GitHub PR state: "open", "closed", "merged" (the latter is closed+merged_at). */
    @Column(name = "state")
    private String state;

    @Column(name = "closed_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant closedAt;

    @Column(name = "merged_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant mergedAt;

    /** GitHub's mergeable flag — null while GitHub is still computing it. */
    @Column(name = "mergeable")
    private Boolean mergeable;

    /** GitHub's mergeable_state ("clean", "dirty", "blocked", "behind"…). */
    @Column(name = "mergeable_state")
    private String mergeableState;

    /** Timestamp of the latest commit on the PR head — derived from the most
     *  recent "committed" timeline event we already persist. */
    @Column(name = "head_pushed_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant headPushedAt;

    /** Per-reviewer verdict at the moment of the last detail sync, persisted
     *  as a JSON map {login -> state} via StringMapConverter. Empty when no
     *  reviewer has weighed in yet. */
    @Column(name = "reviewer_verdicts")
    @Convert(converter = StringMapConverter.class)
    private Map<String, String> reviewerVerdicts;

    @Column(nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalCreatedAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalUpdatedAt;

    protected PullRequestEntity() {}

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

    Long getId() { return id; }
    void setId(Long id) { this.id = id; }

    String getRepo() { return repo; }
    void setRepo(String repo) { this.repo = repo; }

    int getNumber() { return number; }
    void setNumber(int number) { this.number = number; }

    String getTitle() { return title; }
    void setTitle(String title) { this.title = title; }

    String getAuthor() { return author; }
    void setAuthor(String author) { this.author = author; }

    String getHtmlUrl() { return htmlUrl; }
    void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }

    Instant getUpdatedAt() { return updatedAt; }
    void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    Instant getCreatedAt() { return createdAt; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    String getOrigin() { return origin; }
    void setOrigin(String origin) { this.origin = origin; }

    List<String> getLabels() { return labels; }
    void setLabels(List<String> labels) { this.labels = labels; }

    Map<String, String> getLabelColors() { return labelColors; }
    void setLabelColors(Map<String, String> labelColors) { this.labelColors = labelColors; }

    boolean isDraft() { return draft; }
    void setDraft(boolean draft) { this.draft = draft; }

    Instant getSyncedAt() { return syncedAt; }
    void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }

    PullRequestDetail.CiStatus getCiStatus() { return ciStatus; }
    void setCiStatus(PullRequestDetail.CiStatus ciStatus) { this.ciStatus = ciStatus; }

    int getCommentCount() { return commentCount; }
    void setCommentCount(int commentCount) { this.commentCount = commentCount; }

    int getAdditions() { return additions; }
    void setAdditions(int additions) { this.additions = additions; }

    int getDeletions() { return deletions; }
    void setDeletions(int deletions) { this.deletions = deletions; }

    AttentionReason getAttentionReason() { return attentionReason; }
    void setAttentionReason(AttentionReason attentionReason) { this.attentionReason = attentionReason; }

    String getState() { return state; }
    void setState(String state) { this.state = state; }

    Instant getClosedAt() { return closedAt; }
    void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    Instant getMergedAt() { return mergedAt; }
    void setMergedAt(Instant mergedAt) { this.mergedAt = mergedAt; }

    Boolean getMergeable() { return mergeable; }
    void setMergeable(Boolean mergeable) { this.mergeable = mergeable; }

    String getMergeableState() { return mergeableState; }
    void setMergeableState(String mergeableState) { this.mergeableState = mergeableState; }

    Instant getHeadPushedAt() { return headPushedAt; }
    void setHeadPushedAt(Instant headPushedAt) { this.headPushedAt = headPushedAt; }

    Map<String, String> getReviewerVerdicts() { return reviewerVerdicts; }
    void setReviewerVerdicts(Map<String, String> reviewerVerdicts) { this.reviewerVerdicts = reviewerVerdicts; }
}
