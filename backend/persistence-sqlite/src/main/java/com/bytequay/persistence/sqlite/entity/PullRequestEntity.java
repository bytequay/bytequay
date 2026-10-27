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

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.Column;
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
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
@Getter
@Setter
public class PullRequestEntity
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false)
    private String title;

    private String author;

    private String htmlUrl;

    private Instant updatedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> labels;

    @Column(name = "label_colors")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> labelColors;

    @Column(nullable = false)
    private boolean draft;

    @Column(nullable = false)
    private Instant syncedAt;

    // Nullable so Hibernate can add these columns to existing SQLite databases.
    // A legacy null means the row belonged to the original global dashboard.
    @Column(name = "dashboard_visible")
    private Boolean dashboardVisible;

    @Column(name = "workspace_visible")
    private Boolean workspaceVisible;

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

    // Detail-enrichment fields. Nullable until a row has been synced.

    /** GitHub PR state: "open", "closed", "merged" (the latter is closed+merged_at). */
    @Column(name = "state")
    private String state;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "merged_at")
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
    private Instant headPushedAt;

    /** PR head branch name (GitHub's head.ref on the list response). */
    @Column(name = "head_ref")
    private String headRef;

    private Instant monitoredAt;

    private Instant activityAt;

    private Instant discussionActivityAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private PrActivitySummary cardActivity;

    private Instant attentionActivityAt;

    private Instant reviewRequestedAt;

    private String reviewDecision;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> activityFingerprints;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> requestedReviewers;

    public PullRequestEntity() {}

    @PrePersist
    @PreUpdate
    public void normalizeRepo()
    {
        this.repo = repo.toLowerCase(Locale.ROOT);
    }

    /** The row for a pull request GitHub reported. */
    public static PullRequestEntity from(FetchedPullRequest pr, Instant syncedAt)
    {
        PullRequestEntity entity = new PullRequestEntity();
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
        entity.setState(pr.mergedAt() != null ? "merged" : pr.state());
        entity.setClosedAt(pr.closedAt());
        entity.setMergedAt(pr.mergedAt());
        entity.setMergeable(pr.mergeable());
        entity.setMergeableState(pr.mergeableState());
        entity.setHeadRef(pr.headRef());
        entity.setRequestedReviewers(pr.requestedReviewers());
        entity.setActivityAt(pr.updatedAt());
        if (pr.origin() == PullRequest.Origin.REVIEW_REQUESTED) {
            entity.setReviewRequestedAt(syncedAt);
        }
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
        String synthesizedState = getMergedAt() != null && "closed".equals(getState()) ? "merged" : getState();
        return new PullRequest(
                getId(), getRepo(), getNumber(), getTitle(), getAuthor(), getHtmlUrl(), getCreatedAt(), getUpdatedAt(), PullRequest.Origin.valueOf(getOrigin()), getLabels(),
                Optional.ofNullable(getLabelColors()).orElse(ImmutableMap.of()), isDraft(), Optional.ofNullable(state).map(PrViewState::viewedAt).orElse(null),
                Optional.ofNullable(state).map(PrViewState::reviewedAt).orElse(null), Optional.ofNullable(state).map(PrViewState::handledAction).orElse(null),
                Optional.ofNullable(getRequestedReviewers()).orElse(ImmutableList.of()), getCiStatus(), getAdditions(), getDeletions(), getCommentCount(), synthesizedState,
                getClosedAt(), getMergedAt(), getMergeable(), getMergeableState(), getHeadPushedAt(), getHeadRef(), getMonitoredAt(), getActivityAt(), getReviewRequestedAt(),
                getReviewDecision());
    }

    /**
     * Carries over what a fetched pull request cannot report. No list or search
     * response includes the counts or CI status, so a sync
     * page would otherwise overwrite them with the entity defaults.
     */
    public void preserveEnrichmentFrom(PullRequestEntity previous)
    {
        setMonitoredAt(previous.getMonitoredAt());
        setActivityAt(previous.getActivityAt());
        setDiscussionActivityAt(previous.getDiscussionActivityAt());
        setCardActivity(previous.getCardActivity());
        setAttentionActivityAt(previous.getAttentionActivityAt());
        setActivityFingerprints(previous.getActivityFingerprints());
        setReviewDecision(previous.getReviewDecision());
        if (getReviewRequestedAt() == null || PullRequest.Origin.REVIEW_REQUESTED.name().equals(previous.getOrigin())) {
            setReviewRequestedAt(previous.getReviewRequestedAt());
        }
        if (getRequestedReviewers().isEmpty()) {
            setRequestedReviewers(previous.getRequestedReviewers());
        }
        setCiStatus(previous.getCiStatus());
        setAdditions(previous.getAdditions());
        setDeletions(previous.getDeletions());
        setCommentCount(previous.getCommentCount());
        setHeadPushedAt(previous.getHeadPushedAt());
        Boolean observedMergeable = getMergeable();
        String observedMergeableState = getMergeableState();
        setMergeable(previous.getMergeable());
        setMergeableState(previous.getMergeableState());
        observeMergeability(observedMergeable, observedMergeableState);
        if (getHeadRef() == null) {
            setHeadRef(previous.getHeadRef());
        }
    }

    /**
     * Keeps the last known mergeability while GitHub reports none: search omits it, and GitHub answers null and "unknown" until it
     * has computed it again, which it redoes after every push to the base branch.
     */
    public void observeMergeability(Boolean observedMergeable, String observedMergeableState)
    {
        if (observedMergeable != null) {
            setMergeable(observedMergeable);
        }
        if (observedMergeableState != null && !"unknown".equals(observedMergeableState)) {
            setMergeableState(observedMergeableState);
        }
    }
}
