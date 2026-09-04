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
import lombok.Getter;
import lombok.Setter;

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

    /** PR head branch name (GitHub's head.ref on the list response). */
    @Column(name = "head_ref")
    private String headRef;

    public PullRequestEntity() {}

    @PrePersist
    public void prePersist()
    {
        this.repo = repo.toLowerCase(Locale.ROOT);
    }

    @PreUpdate
    public void preUpdate()
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
                getHeadRef());
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
