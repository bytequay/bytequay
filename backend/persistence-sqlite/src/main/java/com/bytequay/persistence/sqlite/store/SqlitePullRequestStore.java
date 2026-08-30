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
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.PullRequestRef;
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
import java.util.function.BiConsumer;

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
    public PullRequestPage findPage(int page, int pageSize, String search)
    {
        String term = normalizeSearch(search);
        if (term.isEmpty()) {
            return findPage(page, pageSize);
        }
        Integer number = pullRequestNumber(term);
        Page<PullRequestEntity> rows = jpaRepository.searchDashboardPage(term, number, pageRequest(page, pageSize));
        return toPage(rows, page, pageSize, jpaRepository.countOpenDashboardSearch(term, number));
    }

    @Override
    public PullRequestPage findRepositoryPage(String repository, int page, int pageSize)
    {
        requireNonNull(repository, "repository is null");
        Page<PullRequestEntity> rows = jpaRepository.findWorkspacePage(repository, pageRequest(page, pageSize));
        return toPage(rows, page, pageSize, jpaRepository.countOpenWorkspace(repository));
    }

    @Override
    public PullRequestPage findRepositoryPage(String repository, int page, int pageSize, String search)
    {
        requireNonNull(repository, "repository is null");
        String term = normalizeSearch(search);
        if (term.isEmpty()) {
            return findRepositoryPage(repository, page, pageSize);
        }
        Integer number = pullRequestNumber(term);
        Page<PullRequestEntity> rows = jpaRepository.searchWorkspacePage(repository, term, number, pageRequest(page, pageSize));
        return toPage(rows, page, pageSize, jpaRepository.countOpenWorkspaceSearch(repository, term, number));
    }

    @Override
    public Map<Long, Instant> findUpdatedAtByIds(Set<Long> ids)
    {
        requireNonNull(ids, "ids is null");
        return jpaRepository.findAllById(ids).stream()
                .collect(toImmutableMap(PullRequestEntity::getId, PullRequestEntity::getUpdatedAt));
    }

    @Override
    @Transactional
    public List<PullRequest> saveDashboardSyncPage(List<FetchedPullRequest> pullRequests, String syncMarker)
    {
        requireNonNull(pullRequests, "pullRequests is null");
        requireNonNull(syncMarker, "syncMarker is null");
        Instant now = Instant.now();
        ImmutableList.Builder<PullRequest> saved = ImmutableList.builder();
        for (FetchedPullRequest pullRequest : pullRequests) {
            saved.add(upsert(pullRequest, now, (entity, previous) -> {
                entity.setDashboardVisible(true);
                entity.setWorkspaceVisible(previous != null && workspaceVisible(previous));
                entity.setDashboardSyncMarker(syncMarker);
                entity.setWorkspaceSyncMarker(previous == null ? null : previous.getWorkspaceSyncMarker());
                if (previous != null
                        && syncMarker.equals(previous.getDashboardSyncMarker())
                        && PullRequest.Origin.AUTHORED.name().equals(previous.getOrigin())) {
                    entity.setOrigin(PullRequest.Origin.AUTHORED.name());
                }
            }));
        }
        return saved.build();
    }

    @Override
    @Transactional
    public Set<Long> completeDashboardSync(String syncMarker, String currentLogin)
    {
        requireNonNull(syncMarker, "syncMarker is null");
        requireNonNull(currentLogin, "currentLogin is null");
        jpaRepository.demoteAuthoredByOthers(currentLogin);
        jpaRepository.hideStaleDashboardRows(
                syncMarker, Instant.now().minus(HANDLED_RETENTION_DAYS, ChronoUnit.DAYS));
        return deleteInvisibleRows();
    }

    @Override
    @Transactional
    public List<PullRequest> saveRepositorySyncPage(
            String repository, List<FetchedPullRequest> pullRequests, String syncMarker)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(pullRequests, "pullRequests is null");
        requireNonNull(syncMarker, "syncMarker is null");
        Instant now = Instant.now();
        ImmutableList.Builder<PullRequest> saved = ImmutableList.builder();
        for (FetchedPullRequest pullRequest : pullRequests) {
            saved.add(upsert(pullRequest, now, (entity, previous) -> {
                entity.setDashboardVisible(previous != null && dashboardVisible(previous));
                entity.setWorkspaceVisible(true);
                entity.setDashboardSyncMarker(previous == null ? null : previous.getDashboardSyncMarker());
                entity.setWorkspaceSyncMarker(syncMarker);
            }));
        }
        return saved.build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullRequest> findRepositoryRowsMissingSync(String repository, String syncMarker)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(syncMarker, "syncMarker is null");
        return jpaRepository.findWorkspaceRowsMissingSync(repository, syncMarker).stream()
                .map(entity -> entity.toDomain(null))
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public PullRequest saveObserved(FetchedPullRequest pullRequest)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        return upsert(pullRequest, Instant.now(), (entity, previous) -> {
            entity.setDashboardVisible(true);
            entity.setWorkspaceVisible(previous != null && workspaceVisible(previous));
            entity.setDashboardSyncMarker(previous == null ? null : previous.getDashboardSyncMarker());
            entity.setWorkspaceSyncMarker(previous == null ? null : previous.getWorkspaceSyncMarker());
        });
    }

    /**
     * Shared save skeleton for every path that persists a fetched pull
     * request: looks up the stored row, carries its id forward, applies the
     * caller's visibility and sync-marker choices, and preserves enrichment
     * so a resync cannot silently drop it. {@code mark} receives the
     * previous row, which is null for a first sighting.
     */
    private PullRequest upsert(
            FetchedPullRequest pullRequest,
            Instant now,
            BiConsumer<PullRequestEntity, PullRequestEntity> mark)
    {
        PullRequestEntity previous = storedByRef(pullRequest).orElse(null);
        PullRequestEntity entity = PullRequestEntity.from(pullRequest, now);
        entity.setId(previous == null ? null : previous.getId());
        mark.accept(entity, previous);
        if (previous != null) {
            entity.preserveEnrichmentFrom(previous);
        }
        return jpaRepository.save(entity).toDomain(null);
    }

    @Override
    @Transactional
    public Set<Long> completeRepositorySync(String repository, Instant retentionCutoff)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(retentionCutoff, "retentionCutoff is null");
        jpaRepository.hideExpiredWorkspaceRows(repository, retentionCutoff);
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
                        .map(entity -> entity.toDomain(stateByPrId.get(entity.getId())))
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

    private static String normalizeSearch(String search)
    {
        return search == null ? "" : search.trim();
    }

    private static Integer pullRequestNumber(String search)
    {
        String value = search.startsWith("#") ? search.substring(1) : search;
        try {
            return Integer.valueOf(value);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** The stored row for a pull request, found on its natural key. */
    private Optional<PullRequestEntity> storedByRef(FetchedPullRequest pullRequest)
    {
        return jpaRepository.findByRepoIgnoreCaseAndNumber(pullRequest.repo(), pullRequest.number());
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
        requireNonNull(repo, "repo is null");
        return jpaRepository.findByRepoIgnoreCaseAndNumber(repo, number).map(PullRequestEntity::getId);
    }

    @Override
    public Optional<PullRequestRef> findRefById(long prId)
    {
        return jpaRepository.findById(prId)
                .flatMap(entity -> PullRequestRef.parse(entity.getRepo() + "#" + entity.getNumber()));
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
        });
    }

    private static boolean dashboardVisible(PullRequestEntity entity)
    {
        return entity.getDashboardVisible() == null || entity.getDashboardVisible();
    }

    private static boolean workspaceVisible(PullRequestEntity entity)
    {
        return Boolean.TRUE.equals(entity.getWorkspaceVisible());
    }
}
