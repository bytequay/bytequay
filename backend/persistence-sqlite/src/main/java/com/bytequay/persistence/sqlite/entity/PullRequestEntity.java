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
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.converter.StringListConverter;
import com.bytequay.persistence.sqlite.converter.StringMapConverter;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Entity
@Table(
        name = "pull_requests",
        indexes = @Index(
                name = "idx_pull_requests_repo_number",
                columnList = "repo, number"))
public class PullRequestEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "github_id")
    private Long githubId;

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

    // Nullable so Hibernate can add these columns to existing SQLite databases.
    // A legacy null means the row belonged to the original global dashboard.
    @Column(name = "dashboard_visible")
    private Boolean dashboardVisible;

    @Column(name = "workspace_visible")
    private Boolean workspaceVisible;

    @Column(name = "dashboard_sync_marker")
    private String dashboardSyncMarker;

    @Column(name = "workspace_sync_marker")
    private String workspaceSyncMarker;

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
        this.repo = repo.toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        this.internalCreatedAt = now;
        this.internalUpdatedAt = now;
    }

    @PreUpdate
    public void preUpdate()
    {
        this.repo = repo.toLowerCase(Locale.ROOT);
        this.internalUpdatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getGithubId() { return githubId; }

    public void setGithubId(Long githubId) { this.githubId = githubId; }

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

    public Boolean getDashboardVisible() { return dashboardVisible; }
    public void setDashboardVisible(Boolean dashboardVisible) { this.dashboardVisible = dashboardVisible; }

    public Boolean getWorkspaceVisible() { return workspaceVisible; }
    public void setWorkspaceVisible(Boolean workspaceVisible) { this.workspaceVisible = workspaceVisible; }

    public String getDashboardSyncMarker() { return dashboardSyncMarker; }
    public void setDashboardSyncMarker(String dashboardSyncMarker) { this.dashboardSyncMarker = dashboardSyncMarker; }

    public String getWorkspaceSyncMarker() { return workspaceSyncMarker; }
    public void setWorkspaceSyncMarker(String workspaceSyncMarker) { this.workspaceSyncMarker = workspaceSyncMarker; }

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

    /** The row for a pull request GitHub reported. */
    public static PullRequestEntity from(FetchedPullRequest pr, Instant syncedAt)
    {
        PullRequestEntity entity = new PullRequestEntity();
        entity.setGithubId(pr.githubId());
        entity.setRepo(pr.repo());
        entity.setNumber(pr.number());
        entity.setTitle(pr.title());
        entity.setAuthor(pr.author());
        entity.setHtmlUrl(pr.htmlUrl());
        entity.setCreatedAt(pr.createdAt());
        entity.setUpdatedAt(pr.updatedAt());
        entity.setOrigin(pr.origin().name());
        entity.setLabels(pr.labels());
        entity.setLabelColors(pr.labelColors());
        entity.setDraft(pr.draft());
        entity.setSyncedAt(syncedAt);
        // state/closedAt/mergedAt come straight from the GitHub response; the
        // enrichment a fetch cannot report is carried over by preserveEnrichment.
        entity.setState(pr.state());
        entity.setClosedAt(pr.closedAt());
        entity.setMergedAt(pr.mergedAt());
        entity.setMergeable(pr.mergeable());
        entity.setMergeableState(pr.mergeableState());
        entity.setHeadRef(pr.headRef());
        return entity;
    }

    /** This row as the domain pull request, carrying the given view state. */
    public PullRequest toDomain(PrViewState state)
    {
        // GitHub's REST API reports merged PRs as state="closed" with
        // merged_at set; synthesize "merged" here so every renderer
        // (status pills on rows and titles) sees
        // one canonical value instead of having to special-case the
        // closed-but-merged combination.
        String synthesizedState = getMergedAt() != null && "closed".equals(getState())
                ? "merged"
                : getState();
        return new PullRequest(
                getId(),
                getRepo(),
                getNumber(),
                getTitle(),
                getAuthor(),
                getHtmlUrl(),
                getCreatedAt(),
                getUpdatedAt(),
                PullRequest.Origin.valueOf(getOrigin()),
                getLabels(),
                getLabelColors(),
                isDraft(),
                Optional.ofNullable(state).map(PrViewState::viewedAt).orElse(null),
                Optional.ofNullable(state).map(PrViewState::reviewedAt).orElse(null),
                Optional.ofNullable(state).map(PrViewState::handledAction).orElse(null),
                ImmutableList.of(),
                getCiStatus(),
                getAdditions(),
                getDeletions(),
                getCommentCount(),
                getAttentionReason(),
                synthesizedState,
                getClosedAt(),
                getMergedAt(),
                getMergeable(),
                getMergeableState(),
                getHeadPushedAt(),
                getReviewerVerdicts(),
                getHeadRef(),
                getGithubId());
    }

    /**
     * Carries over what a fetched pull request cannot report. No list or search
     * response includes the counts, CI status or attention reason, so a sync
     * page would otherwise overwrite them with the entity defaults.
     */
    public void preserveEnrichmentFrom(PullRequestEntity previous)
    {
        setCiStatus(previous.getCiStatus());
        setAdditions(previous.getAdditions());
        setDeletions(previous.getDeletions());
        setCommentCount(previous.getCommentCount());
        setAttentionReason(previous.getAttentionReason());
        setHeadPushedAt(previous.getHeadPushedAt());
        setReviewerVerdicts(previous.getReviewerVerdicts());
        // Reported by the pulls endpoint but not by search.
        if (getMergeable() == null) {
            setMergeable(previous.getMergeable());
        }
        if (getMergeableState() == null) {
            setMergeableState(previous.getMergeableState());
        }
        if (getHeadRef() == null) {
            setHeadRef(previous.getHeadRef());
        }
    }
}
