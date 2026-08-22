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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.AttentionReason;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Repository
public class SqlitePullRequestStore
        implements PullRequestStore
{
    // How long a recently-handled PR stays in the list after it falls out of
    // the GitHub search results. GitHub removes a PR from the
    // `review-requested:@me` search the moment you submit a review, so
    // without this retention window, a PR disappears from our Handled view
    // seconds after you approve it.
    private static final int HANDLED_RETENTION_DAYS = 30;

    private final PullRequestJpaRepository jpaRepository;
    private final PrViewStateStore viewStateStore;

    public SqlitePullRequestStore(PullRequestJpaRepository jpaRepository, PrViewStateStore viewStateStore)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
    }

    @Override
    public List<PullRequest> findAll()
    {
        Map<Long, PrViewState> stateByPrId = viewStateStore.findAll();
        return jpaRepository.findAll().stream()
                .filter(SqlitePullRequestStore::dashboardVisible)
                .map(e -> toDomain(e, stateByPrId.get(e.getId())))
                .collect(toImmutableList());
    }

    @Override
    public List<PullRequest> findByRepository(String repository)
    {
        requireNonNull(repository, "repository is null");
        Map<Long, PrViewState> stateByPrId = viewStateStore.findAll();
        return jpaRepository.findAllByRepoIgnoreCase(repository).stream()
                .map(entity -> toDomain(entity, stateByPrId.get(entity.getId())))
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void replaceAll(List<PullRequest> pullRequests)
    {
        Instant now = Instant.now();
        Set<Long> protectedIds = handledPrIdsWithinRetention(now);

        Set<Long> freshIds = pullRequests.stream()
                .map(PullRequest::id)
                .collect(toImmutableSet());

        // Preserve detail-derived enrichment columns from the previous
        // sync. The search-API responses fed in here don't include
        // reviewer_verdicts / mergeable / etc., so toEntity below would
        // overwrite a populated row with nulls between syncs. The detail-sync pass downstream
        // only re-runs for PRs whose updatedAt changed (or whose
        // enrichment is missing) — for unchanged PRs we want their
        // last-known verdict map to survive this replace.
        Map<Long, PullRequestEntity> existingById = jpaRepository.findAll().stream()
                .collect(toImmutableMap(PullRequestEntity::getId, e -> e));

        List<PullRequestEntity> entities = pullRequests.stream()
                .map(pr -> {
                    PullRequestEntity entity = toEntity(pr, now);
                    PullRequestEntity prev = existingById.get(pr.id());
                    entity.setDashboardVisible(true);
                    entity.setWorkspaceVisible(prev != null && workspaceVisible(prev));
                    if (prev != null) {
                        preserveEnrichment(entity, prev);
                    }
                    return entity;
                })
                .collect(toImmutableList());

        jpaRepository.saveAll(entities);
        existingById.values().stream()
                .filter(SqlitePullRequestStore::dashboardVisible)
                .filter(entity -> !freshIds.contains(entity.getId()))
                .filter(entity -> !protectedIds.contains(entity.getId()))
                .forEach(entity -> {
                    if (workspaceVisible(entity)) {
                        entity.setDashboardVisible(false);
                        jpaRepository.save(entity);
                    }
                    else {
                        jpaRepository.delete(entity);
                    }
                });
    }

    @Override
    @Transactional
    public void replaceRepository(String repository, List<PullRequest> pullRequests)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(pullRequests, "pullRequests is null");
        Instant now = Instant.now();
        Map<Long, PullRequestEntity> existingById = jpaRepository.findAllByRepoIgnoreCase(repository).stream()
                .collect(toImmutableMap(PullRequestEntity::getId, entity -> entity));
        Set<Long> freshIds = pullRequests.stream()
                .map(PullRequest::id)
                .collect(toImmutableSet());

        List<PullRequestEntity> fresh = pullRequests.stream()
                .map(pullRequest -> {
                    PullRequestEntity entity = toEntity(pullRequest, now);
                    PullRequestEntity previous = existingById.get(pullRequest.id());
                    entity.setDashboardVisible(previous == null ? false : dashboardVisible(previous));
                    entity.setWorkspaceVisible(true);
                    if (previous != null) {
                        preserveEnrichment(entity, previous);
                    }
                    return entity;
                })
                .collect(toImmutableList());
        jpaRepository.saveAll(fresh);

        existingById.values().stream()
                .filter(entity -> !freshIds.contains(entity.getId()))
                .filter(SqlitePullRequestStore::workspaceVisible)
                .forEach(entity -> {
                    if (dashboardVisible(entity)) {
                        entity.setWorkspaceVisible(false);
                        jpaRepository.save(entity);
                    }
                    else {
                        jpaRepository.delete(entity);
                    }
                });
    }

    /**
     * PRs the user has handled (reviewed, approved, merged, dismissed…) in the
     * last {@value HANDLED_RETENTION_DAYS} days. These are preserved in the
     * pr table even after they drop out of the GitHub search results, so the
     * Handled view keeps showing them until the retention window elapses.
     */
    private Set<Long> handledPrIdsWithinRetention(Instant now)
    {
        Instant cutoff = now.minus(HANDLED_RETENTION_DAYS, ChronoUnit.DAYS);
        return viewStateStore.findAll().values().stream()
                .filter(s -> s.reviewedAt() != null && s.reviewedAt().isAfter(cutoff))
                .map(PrViewState::prId)
                .collect(toImmutableSet());
    }

    @Override
    public Map<Long, Instant> findUpdatedAtMap()
    {
        return jpaRepository.findAll().stream()
                .filter(SqlitePullRequestStore::dashboardVisible)
                .collect(toImmutableMap(PullRequestEntity::getId, PullRequestEntity::getUpdatedAt));
    }

    @Override
    public Set<Long> findIdsMissingEnrichment()
    {
        // Treat both null AND an empty map as "not yet synced". The empty
        // map is what the previous sync stored when the PR genuinely had
        // no reviews at the moment — but it's indistinguishable from
        // "stale empty after a reviewer later requested changes", which
        // is exactly the trino #29289 case. Re-syncing on every cycle
        // is cheap relative to silently stale reviewer state.
        // Null head_ref also forces a re-sync for older local rows.
        return jpaRepository.findAll().stream()
                .filter(SqlitePullRequestStore::dashboardVisible)
                .filter(e -> {
                    Map<String, String> verdicts = e.getReviewerVerdicts();
                    if (verdicts == null || verdicts.isEmpty()) {
                        return true;
                    }
                    return e.getHeadRef() == null;
                })
                .map(PullRequestEntity::getId)
                .collect(toImmutableSet());
    }

    @Override
    public Optional<Long> findIdByRepoAndNumber(String repo, int number)
    {
        return jpaRepository.findIdByRepoAndNumber(repo, number);
    }

    @Override
    @Transactional
    public void updateState(long prId, String state, Instant updatedAt, Instant mergedAt)
    {
        jpaRepository.findById(prId).ifPresent(entity -> {
            entity.setState(state);
            if (updatedAt != null) {
                entity.setUpdatedAt(updatedAt);
            }
            entity.setMergedAt(mergedAt);
            jpaRepository.save(entity);
        });
    }

    @Override
    @Transactional
    public void updateEnrichment(
            long prId,
            PullRequestDetail.CiStatus ciStatus,
            int additions,
            int deletions,
            int commentCount,
            AttentionReason attentionReason,
            Boolean mergeable,
            String mergeableState,
            Instant headPushedAt,
            Map<String, String> reviewerVerdicts,
            String headRef)
    {
        jpaRepository.findById(prId).ifPresent(entity -> {
            entity.setCiStatus(ciStatus);
            entity.setAdditions(additions);
            entity.setDeletions(deletions);
            entity.setCommentCount(commentCount);
            entity.setAttentionReason(attentionReason);
            entity.setMergeable(mergeable);
            entity.setMergeableState(mergeableState);
            entity.setHeadPushedAt(headPushedAt);
            entity.setReviewerVerdicts(reviewerVerdicts);
            // Only overwrite head_ref when the detail sync actually
            // produced one. Detail can briefly miss it on first fetch
            // (GitHub still computing); keeping the previous value
            // beats blanking the IN_REVIEW lookup.
            if (headRef != null) {
                entity.setHeadRef(headRef);
            }
            jpaRepository.save(entity);
        });
    }

    @Override
    @Transactional
    public void updateCiStatus(long prId, PullRequestDetail.CiStatus ciStatus)
    {
        jpaRepository.findById(prId).ifPresent(entity -> {
            entity.setCiStatus(ciStatus);
            jpaRepository.save(entity);
        });
    }

    private static PullRequestEntity toEntity(PullRequest pr, Instant syncedAt)
    {
        PullRequestEntity entity = new PullRequestEntity();
        entity.setId(pr.id());
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
        // Detail-derived enrichment is set by the sync job after the per-PR
        // detail fetch, not from the search response — leave the defaults
        // here unless the caller already populated them.
        entity.setCiStatus(pr.ciStatus());
        entity.setCommentCount(pr.commentCount());
        entity.setAdditions(pr.additions());
        entity.setDeletions(pr.deletions());
        entity.setAttentionReason(pr.attentionReason());
        // V26 list-level fields. state/closedAt/mergedAt come straight from
        // the GitHub list response; the rest are filled by updateEnrichment
        // after the next detail sync.
        entity.setState(pr.state());
        entity.setClosedAt(pr.closedAt());
        entity.setMergedAt(pr.mergedAt());
        entity.setMergeable(pr.mergeable());
        entity.setMergeableState(pr.mergeableState());
        entity.setHeadPushedAt(pr.headPushedAt());
        entity.setReviewerVerdicts(pr.reviewerVerdicts());
        entity.setHeadRef(pr.headRef());
        return entity;
    }

    private static void preserveEnrichment(PullRequestEntity entity, PullRequestEntity previous)
    {
        if (entity.getReviewerVerdicts() == null) {
            entity.setReviewerVerdicts(previous.getReviewerVerdicts());
        }
        if (entity.getMergeable() == null) {
            entity.setMergeable(previous.getMergeable());
        }
        if (entity.getMergeableState() == null) {
            entity.setMergeableState(previous.getMergeableState());
        }
        if (entity.getHeadPushedAt() == null) {
            entity.setHeadPushedAt(previous.getHeadPushedAt());
        }
        if (entity.getHeadRef() == null) {
            entity.setHeadRef(previous.getHeadRef());
        }
    }

    private static boolean dashboardVisible(PullRequestEntity entity)
    {
        return entity.getDashboardVisible() == null || entity.getDashboardVisible();
    }

    private static boolean workspaceVisible(PullRequestEntity entity)
    {
        return Boolean.TRUE.equals(entity.getWorkspaceVisible());
    }

    private static PullRequest toDomain(PullRequestEntity entity, PrViewState state)
    {
        // GitHub's REST API reports merged PRs as state="closed" with
        // merged_at set; synthesize "merged" here so every renderer
        // (status pills on rows and titles) sees
        // one canonical value instead of having to special-case the
        // closed-but-merged combination.
        String synthesizedState = entity.getMergedAt() != null && "closed".equals(entity.getState())
                ? "merged"
                : entity.getState();
        return new PullRequest(
                entity.getId(),
                entity.getRepo(),
                entity.getNumber(),
                entity.getTitle(),
                entity.getAuthor(),
                entity.getHtmlUrl(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                PullRequest.Origin.valueOf(entity.getOrigin()),
                entity.getLabels(),
                entity.getLabelColors(),
                entity.isDraft(),
                Optional.ofNullable(state).map(PrViewState::viewedAt).orElse(null),
                Optional.ofNullable(state).map(PrViewState::reviewedAt).orElse(null),
                Optional.ofNullable(state).map(PrViewState::handledAction).orElse(null),
                ImmutableList.of(),
                entity.getCiStatus(),
                entity.getAdditions(),
                entity.getDeletions(),
                entity.getCommentCount(),
                entity.getAttentionReason(),
                synthesizedState,
                entity.getClosedAt(),
                entity.getMergedAt(),
                entity.getMergeable(),
                entity.getMergeableState(),
                entity.getHeadPushedAt(),
                entity.getReviewerVerdicts(),
                entity.getHeadRef());
    }
}
