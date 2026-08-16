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

import com.bytequay.core.domain.AttentionReason;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.StringListConverter;
import com.bytequay.persistence.sqlite.converter.StringMapConverter;
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
public class PullRequestEntity
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

    // Detail-enrichment fields. Nullable until a row has been synced.

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

    /** PR head branch name (GitHub's head.ref on the list response). */
    @Column(name = "head_ref")
    private String headRef;

    @Column(nullable = false, updatable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalCreatedAt;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant internalUpdatedAt;

    public PullRequestEntity() {}

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }

    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getHtmlUrl() { return htmlUrl; }
    public void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels; }

    public Map<String, String> getLabelColors() { return labelColors; }
    public void setLabelColors(Map<String, String> labelColors) { this.labelColors = labelColors; }

    public boolean isDraft() { return draft; }
    public void setDraft(boolean draft) { this.draft = draft; }

    public Instant getSyncedAt() { return syncedAt; }
    public void setSyncedAt(Instant syncedAt) { this.syncedAt = syncedAt; }

    public PullRequestDetail.CiStatus getCiStatus() { return ciStatus; }
    public void setCiStatus(PullRequestDetail.CiStatus ciStatus) { this.ciStatus = ciStatus; }

    public int getCommentCount() { return commentCount; }
    public void setCommentCount(int commentCount) { this.commentCount = commentCount; }

    public int getAdditions() { return additions; }
    public void setAdditions(int additions) { this.additions = additions; }

    public int getDeletions() { return deletions; }
    public void setDeletions(int deletions) { this.deletions = deletions; }

    public AttentionReason getAttentionReason() { return attentionReason; }
    public void setAttentionReason(AttentionReason attentionReason) { this.attentionReason = attentionReason; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public Instant getMergedAt() { return mergedAt; }
    public void setMergedAt(Instant mergedAt) { this.mergedAt = mergedAt; }

    public Boolean getMergeable() { return mergeable; }
    public void setMergeable(Boolean mergeable) { this.mergeable = mergeable; }

    public String getMergeableState() { return mergeableState; }
    public void setMergeableState(String mergeableState) { this.mergeableState = mergeableState; }

    public Instant getHeadPushedAt() { return headPushedAt; }
    public void setHeadPushedAt(Instant headPushedAt) { this.headPushedAt = headPushedAt; }

    public Map<String, String> getReviewerVerdicts() { return reviewerVerdicts; }
    public void setReviewerVerdicts(Map<String, String> reviewerVerdicts) { this.reviewerVerdicts = reviewerVerdicts; }

    public String getHeadRef() { return headRef; }
    public void setHeadRef(String headRef) { this.headRef = headRef; }
}
