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
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    public PullRequestPage findPage(int page, int pageSize)
    {
        Page<PullRequestEntity> rows = jpaRepository.findDashboardPage(pageRequest(page, pageSize));
        return toPage(rows, page, pageSize, jpaRepository.countOpenDashboard());
    }

    @Override
    public PullRequestPage findRepositoryPage(String repository, int page, int pageSize)
    {
        requireNonNull(repository, "repository is null");
        Page<PullRequestEntity> rows = jpaRepository.findWorkspacePage(repository, pageRequest(page, pageSize));
        return toPage(rows, page, pageSize, jpaRepository.countOpenWorkspace(repository));
    }

    @Override
    public Map<Long, Instant> findUpdatedAtByIds(Set<Long> ids)
    {
        requireNonNull(ids, "ids is null");
        return jpaRepository.findAllById(ids).stream()
                .collect(toImmutableMap(PullRequestEntity::getId, PullRequestEntity::getUpdatedAt));
    }

    @Override
    public Set<Long> findIdsMissingEnrichment(Set<Long> ids)
    {
        requireNonNull(ids, "ids is null");
        return jpaRepository.findAllById(ids).stream()
                .filter(e -> {
                    Map<String, String> verdicts = e.getReviewerVerdicts();
                    return verdicts == null || verdicts.isEmpty() || e.getHeadRef() == null;
                })
                .map(PullRequestEntity::getId)
                .collect(toImmutableSet());
    }

    @Override
    @Transactional
    public void saveDashboardSyncPage(List<PullRequest> pullRequests, String syncMarker)
    {
        requireNonNull(pullRequests, "pullRequests is null");
        requireNonNull(syncMarker, "syncMarker is null");
        Instant now = Instant.now();
        Map<Long, PullRequestEntity> existingById = existingById(pullRequests);
        jpaRepository.saveAll(pullRequests.stream()
                .map(pullRequest -> {
                    PullRequestEntity previous = existingById.get(pullRequest.id());
                    PullRequestEntity entity = toEntity(pullRequest, now);
                    entity.setDashboardVisible(true);
                    entity.setWorkspaceVisible(previous != null && workspaceVisible(previous));
                    entity.setDashboardSyncMarker(syncMarker);
                    entity.setWorkspaceSyncMarker(previous == null ? null : previous.getWorkspaceSyncMarker());
                    if (previous != null) {
                        if (syncMarker.equals(previous.getDashboardSyncMarker())
                                && PullRequest.Origin.AUTHORED.name().equals(previous.getOrigin())) {
                            entity.setOrigin(PullRequest.Origin.AUTHORED.name());
                        }
                        preserveEnrichment(entity, previous);
                    }
                    return entity;
                })
                .collect(toImmutableList()));
    }

    @Override
    @Transactional
    public Set<Long> completeDashboardSync(String syncMarker)
    {
        requireNonNull(syncMarker, "syncMarker is null");
        jpaRepository.hideStaleDashboardRows(
                syncMarker, Instant.now().minus(HANDLED_RETENTION_DAYS, ChronoUnit.DAYS));
        return deleteInvisibleRows();
    }

    @Override
    @Transactional
    public void saveRepositorySyncPage(String repository, List<PullRequest> pullRequests, String syncMarker)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(pullRequests, "pullRequests is null");
        requireNonNull(syncMarker, "syncMarker is null");
        Instant now = Instant.now();
        Map<Long, PullRequestEntity> existingById = existingById(pullRequests);
        jpaRepository.saveAll(pullRequests.stream()
                .map(pullRequest -> {
                    PullRequestEntity previous = existingById.get(pullRequest.id());
                    PullRequestEntity entity = toEntity(pullRequest, now);
                    entity.setDashboardVisible(previous != null && dashboardVisible(previous));
                    entity.setWorkspaceVisible(true);
                    entity.setDashboardSyncMarker(previous == null ? null : previous.getDashboardSyncMarker());
                    entity.setWorkspaceSyncMarker(syncMarker);
                    if (previous != null) {
                        preserveEnrichment(entity, previous);
                    }
                    return entity;
                })
                .collect(toImmutableList()));
    }

    @Override
    @Transactional
    public void saveObserved(PullRequest pullRequest)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        PullRequestEntity previous = jpaRepository.findById(pullRequest.id()).orElse(null);
        PullRequestEntity entity = toEntity(pullRequest, Instant.now());
        entity.setDashboardVisible(true);
        entity.setWorkspaceVisible(previous != null && workspaceVisible(previous));
        entity.setDashboardSyncMarker(previous == null ? null : previous.getDashboardSyncMarker());
        entity.setWorkspaceSyncMarker(previous == null ? null : previous.getWorkspaceSyncMarker());
        if (previous != null) {
            preserveEnrichment(entity, previous);
        }
        jpaRepository.save(entity);
    }

    @Override
    @Transactional
    public Set<Long> completeRepositorySync(String repository, String syncMarker)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(syncMarker, "syncMarker is null");
        jpaRepository.hideStaleWorkspaceRows(repository, syncMarker);
        return deleteInvisibleRows();
    }

    private PullRequestPage toPage(Page<PullRequestEntity> rows, int page, int pageSize, long openPullRequests)
    {
        Set<Long> ids = rows.stream()
                .map(PullRequestEntity::getId)
                .collect(toImmutableSet());
        Map<Long, PrViewState> stateByPrId = viewStateStore.findByPrIds(ids);
        return new PullRequestPage(
                rows.stream()
                        .map(entity -> toDomain(entity, stateByPrId.get(entity.getId())))
                        .collect(toImmutableList()),
                page,
                pageSize,
                rows.getTotalElements(),
                openPullRequests,
                rows.hasNext());
    }

    private static PageRequest pageRequest(int page, int pageSize)
    {
        return PageRequest.of(page, pageSize, Sort.by(
                Sort.Order.desc("updatedAt"),
                Sort.Order.desc("id")));
    }

    private Map<Long, PullRequestEntity> existingById(List<PullRequest> pullRequests)
    {
        return jpaRepository.findAllById(pullRequests.stream()
                        .map(PullRequest::id)
                        .collect(toImmutableSet())).stream()
                .collect(toImmutableMap(PullRequestEntity::getId, entity -> entity));
    }

    private Set<Long> deleteInvisibleRows()
    {
        Set<Long> removed = ImmutableSet.copyOf(jpaRepository.findInvisibleIds());
        if (!removed.isEmpty()) {
            jpaRepository.deleteInvisibleRows();
        }
        return removed;
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
