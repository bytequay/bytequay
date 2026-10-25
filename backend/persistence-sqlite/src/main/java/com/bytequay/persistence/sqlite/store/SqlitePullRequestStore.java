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

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository.AttentionActivity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository.CardActivity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository.DiscussionActivity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Repository
public class SqlitePullRequestStore
        implements PullRequestStore
{
    private final PullRequestJpaRepository jpaRepository;
    private final PrViewStateStore viewStateStore;

    public SqlitePullRequestStore(PullRequestJpaRepository jpaRepository, PrViewStateStore viewStateStore)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullRequest> findAttentionCandidates()
    {
        List<PullRequestEntity> rows = jpaRepository.findAttentionCandidates();
        Map<Long, PrViewState> states = viewStateStore.findByPrIds(rows.stream().map(PullRequestEntity::getId).collect(toImmutableSet()));
        return rows.stream().map(row -> row.toDomain(states.get(row.getId()))).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PullRequest> findById(long prId)
    {
        PrViewState state = viewStateStore.findByPrIds(ImmutableSet.of(prId)).get(prId);
        return jpaRepository.findById(prId).map(row -> row.toDomain(state));
    }

    @Override
    @Transactional
    public void setMonitored(long prId, boolean monitored)
    {
        PullRequestEntity row = jpaRepository.findById(prId).orElseThrow();
        checkArgument(!monitored || !"merged".equalsIgnoreCase(canonicalState(row.getState(), row.getMergedAt())), "Merged pull requests cannot be monitored");
        if (monitored != (row.getMonitoredAt() != null)) {
            row.setMonitoredAt(monitored ? Instant.now() : null);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, PrActivitySummary> findActivities(Set<Long> prIds)
    {
        if (prIds.isEmpty()) {
            return ImmutableMap.of();
        }
        return jpaRepository.findCardActivities(prIds).stream().collect(toImmutableMap(CardActivity::getId, CardActivity::getCardActivity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> findDiscussionActivityAt(long prId)
    {
        // One column: attention sync asks this for every candidate each minute.
        return jpaRepository.findDiscussionActivityAt(prId);
    }

    @Override
    @Transactional
    public void observeActivity(long prId, Optional<PrActivitySummary> activity, Instant discussionActivityAt)
    {
        jpaRepository.findById(prId).ifPresent(row -> {
            if (row.getDiscussionActivityAt() == null || discussionActivityAt.isAfter(row.getDiscussionActivityAt())) {
                row.setDiscussionActivityAt(discussionActivityAt);
                if (row.getActivityAt() == null || discussionActivityAt.isAfter(row.getActivityAt())) {
                    row.setActivityAt(discussionActivityAt);
                }
            }
            activity.filter(value -> row.getCardActivity() == null || !value.timestamp().isBefore(row.getCardActivity().timestamp()))
                    .ifPresent(row::setCardActivity);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Instant> findAttentionActivities(Set<Long> prIds)
    {
        if (prIds.isEmpty()) {
            return ImmutableMap.of();
        }
        return jpaRepository.findAttentionActivities(prIds).stream()
                .collect(toImmutableMap(AttentionActivity::getId, AttentionActivity::getAttentionActivityAt));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Instant> findDiscussionActivities(Set<Long> prIds)
    {
        if (prIds.isEmpty()) {
            return ImmutableMap.of();
        }
        return jpaRepository.findDiscussionActivities(prIds).stream()
                .collect(toImmutableMap(DiscussionActivity::getId, DiscussionActivity::getDiscussionActivityAt));
    }

    @Override
    @Transactional
    public void observeAttentionActivity(long prId, Instant at)
    {
        requireNonNull(at, "at is null");
        jpaRepository.findById(prId)
                .filter(row -> row.getAttentionActivityAt() == null || at.isAfter(row.getAttentionActivityAt()))
                .ifPresent(row -> row.setAttentionActivityAt(at));
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
    @Transactional(readOnly = true)
    public List<PullRequest> findMergedRepositoryPullRequests(String repository, String search, int limit)
    {
        requireNonNull(repository, "repository is null");
        checkArgument(limit > 0 && limit <= 100, "limit must be between 1 and 100");
        String term = normalizeSearch(search);
        return jpaRepository.findMergedWorkspace(repository, term, pullRequestNumber(term), PageRequest.of(0, limit)).stream()
                .map(entity -> entity.toDomain(null))
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public List<PullRequest> saveDashboardSyncPage(List<FetchedPullRequest> pullRequests)
    {
        requireNonNull(pullRequests, "pullRequests is null");
        Instant now = Instant.now();
        ImmutableList.Builder<PullRequest> saved = ImmutableList.builder();
        for (FetchedPullRequest pullRequest : pullRequests) {
            saved.add(upsert(pullRequest, now, (entity, previous) -> {
                entity.setDashboardVisible(true);
                entity.setWorkspaceVisible(previous != null && workspaceVisible(previous));
            }));
        }
        return saved.build();
    }

    @Override
    @Transactional
    public List<PullRequest> saveRepositorySyncPage(String repository, List<FetchedPullRequest> pullRequests)
    {
        requireNonNull(repository, "repository is null");
        requireNonNull(pullRequests, "pullRequests is null");
        Instant now = Instant.now();
        ImmutableList.Builder<PullRequest> saved = ImmutableList.builder();
        for (FetchedPullRequest pullRequest : pullRequests) {
            saved.add(upsert(pullRequest, now, (entity, previous) -> {
                entity.setDashboardVisible(previous != null && dashboardVisible(previous));
                entity.setWorkspaceVisible(true);
            }));
        }
        return saved.build();
    }

    @Override
    @Transactional
    public PullRequest saveObserved(FetchedPullRequest pullRequest)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        return upsert(pullRequest, Instant.now(), (entity, previous) -> {
            entity.setDashboardVisible(true);
            entity.setWorkspaceVisible(previous != null && workspaceVisible(previous));
        });
    }

    /**
     * Shared save skeleton for every path that persists a fetched pull
     * request: looks up the stored row, carries its id forward, applies the
     * caller's visibility choices, and preserves enrichment
     * so a resync cannot silently drop it. {@code mark} receives the
     * previous row, which is null for a first sighting.
     */
    private PullRequest upsert(FetchedPullRequest pullRequest, Instant now, BiConsumer<PullRequestEntity, PullRequestEntity> mark)
    {
        PullRequestEntity previous = storedByRef(pullRequest).orElse(null);
        if (previous != null && previous.getUpdatedAt() != null && pullRequest.updatedAt() != null
                && pullRequest.updatedAt().isBefore(previous.getUpdatedAt())) {
            // A slower scan still saw this row, but must not undo a newer summary refresh.
            mark.accept(previous, previous);
            return jpaRepository.save(previous).toDomain(null);
        }
        PullRequestEntity entity = PullRequestEntity.from(pullRequest, now);
        entity.setId(previous == null ? null : previous.getId());
        mark.accept(entity, previous);
        if (previous != null) {
            // Syncs see the same rows again and again; a fresh time on each would make every one of them an UPDATE.
            entity.setSyncedAt(previous.getSyncedAt());
            // Search has no reviewer list or head ref; only exact detail can remove a known pending request.
            if (pullRequest.headRef() == null && pullRequest.origin() == PullRequest.Origin.OTHER
                    && PullRequest.Origin.REVIEW_REQUESTED.name().equals(previous.getOrigin())) {
                entity.setOrigin(previous.getOrigin());
            }
            entity.preserveEnrichmentFrom(previous);
            if (!Objects.equals(previous.getUpdatedAt(), entity.getUpdatedAt())
                    || !Objects.equals(previous.getTitle(), entity.getTitle())
                    || !Objects.equals(canonicalState(previous.getState(), previous.getMergedAt()), entity.getState())
                    || previous.isDraft() != entity.isDraft()
                    || !ImmutableSet.copyOf(previous.getLabels()).equals(ImmutableSet.copyOf(entity.getLabels()))) {
                entity.setActivityAt(now);
            }
        }
        if ("merged".equalsIgnoreCase(entity.getState())) {
            entity.setMonitoredAt(null);
        }
        return jpaRepository.save(entity).toDomain(null);
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
        return PageRequest.of(page, pageSize, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
    }

    private static String normalizeSearch(String search)
    {
        return search == null ? "" : search.trim();
    }

    private static Integer pullRequestNumber(String search)
    {
        String value = search.startsWith("#") ? search.substring(1) : search;
        return Ints.tryParse(value);
    }

    /** The stored row for a pull request, found on its natural key. */
    private Optional<PullRequestEntity> storedByRef(FetchedPullRequest pullRequest)
    {
        return jpaRepository.findByRepoIgnoreCaseAndNumber(pullRequest.repo(), pullRequest.number());
    }

    @Override
    public Optional<Long> findIdByRepoAndNumber(String repo, int number)
    {
        requireNonNull(repo, "repo is null");
        return jpaRepository.findByRepoIgnoreCaseAndNumber(repo, number).map(PullRequestEntity::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullRequest> findByHeadRef(String repo, String headRef)
    {
        requireNonNull(repo, "repo is null");
        requireNonNull(headRef, "headRef is null");
        return jpaRepository.findByRepoIgnoreCaseAndHeadRefOrderByNumberDesc(repo, headRef).stream()
                .map(entity -> entity.toDomain(null))
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void updateDetails(long prId, PrRawDetail detail)
    {
        requireNonNull(detail, "detail is null");
        String normalizedState = canonicalState(detail.state(), detail.mergedAt());
        jpaRepository.findById(prId).ifPresent(entity -> {
            if (detail.updatedAt() != null && entity.getUpdatedAt() != null && detail.updatedAt().isBefore(entity.getUpdatedAt())) {
                return;
            }
            if (!Objects.equals(canonicalState(entity.getState(), entity.getMergedAt()), normalizedState)
                    || (detail.updatedAt() != null && !Objects.equals(entity.getUpdatedAt(), detail.updatedAt()))
                    || !Objects.equals(entity.getMergedAt(), detail.mergedAt())
                    || (detail.title() != null && !Objects.equals(entity.getTitle(), detail.title()))
                    || entity.isDraft() != detail.draft()
                    || !ImmutableSet.copyOf(entity.getLabels()).equals(ImmutableSet.copyOf(detail.labels()))) {
                entity.setActivityAt(Instant.now());
            }
            entity.setState(normalizedState);
            if ("merged".equalsIgnoreCase(normalizedState)) {
                entity.setMonitoredAt(null);
            }
            if (detail.updatedAt() != null) {
                entity.setUpdatedAt(detail.updatedAt());
            }
            entity.setMergedAt(detail.mergedAt());
            if (detail.title() != null) {
                entity.setTitle(detail.title());
            }
            entity.setDraft(detail.draft());
            entity.setLabels(detail.labels());
            entity.setLabelColors(detail.labelColors());
        });
    }

    @Override
    @Transactional
    public void updateTitle(long prId, String title)
    {
        requireNonNull(title, "title is null");
        jpaRepository.findById(prId).ifPresent(entity -> {
            entity.setTitle(title);
            entity.setActivityAt(Instant.now());
        });
    }

    @Override
    @Transactional
    public void updateEnrichment(
            long prId,
            Instant sourceUpdatedAt,
            PullRequestDetail.CiStatus ciStatus,
            int additions,
            int deletions,
            int commentCount,
            Boolean mergeable,
            String mergeableState,
            Instant headPushedAt,
            String headRef,
            List<String> requestedReviewers,
            PullRequest.Origin origin,
            Optional<Instant> reviewRequestedAt,
            Optional<String> reviewDecision,
            Map<String, String> activityFingerprints)
    {
        jpaRepository.findById(prId).ifPresent(entity -> {
            if (sourceUpdatedAt != null && entity.getUpdatedAt() != null && sourceUpdatedAt.isBefore(entity.getUpdatedAt())) {
                return;
            }
            Map<String, String> previousFingerprints = Optional.ofNullable(entity.getActivityFingerprints()).orElse(ImmutableMap.of());
            // Summary rollups and detailed checks can disagree; replaying an unchanged source must not overwrite newer enrichment.
            if (!activityFingerprints.isEmpty() && activityFingerprints.entrySet().stream()
                    .allMatch(entry -> Objects.equals(previousFingerprints.get(entry.getKey()), entry.getValue()))) {
                // Both sources read the same mergeability, which moves without activity, so another source's brief answer
                // must not outlive this fresh read. An unknown one is GitHub still computing and keeps the stored answer.
                if (mergeable != null) {
                    entity.setMergeable(mergeable);
                    entity.setMergeableState(mergeableState);
                }
                return;
            }
            // Review decisions use different summary/detail semantics; their source fingerprints detect actual review changes.
            if (entity.getCiStatus() != ciStatus
                    || !Objects.equals(entity.getHeadPushedAt(), headPushedAt)
                    || activityFingerprints.entrySet().stream().anyMatch(entry -> previousFingerprints.containsKey(entry.getKey())
                    && !Objects.equals(previousFingerprints.get(entry.getKey()), entry.getValue()))) {
                entity.setActivityAt(Instant.now());
            }
            Map<String, String> fingerprints = new HashMap<>(previousFingerprints);
            fingerprints.putAll(activityFingerprints);
            entity.setActivityFingerprints(ImmutableMap.copyOf(fingerprints));
            if (origin == PullRequest.Origin.REVIEW_REQUESTED && !origin.name().equals(entity.getOrigin())) {
                entity.setReviewRequestedAt(Instant.now());
            }
            entity.setOrigin(origin.name());
            reviewRequestedAt.filter(timestamp -> entity.getReviewRequestedAt() == null || timestamp.isAfter(entity.getReviewRequestedAt()))
                    .ifPresent(entity::setReviewRequestedAt);
            entity.setReviewDecision(reviewDecision.orElse(null));
            entity.setRequestedReviewers(requestedReviewers);
            entity.setCiStatus(ciStatus);
            entity.setAdditions(additions);
            entity.setDeletions(deletions);
            entity.setCommentCount(commentCount);
            entity.setMergeable(mergeable);
            entity.setMergeableState(mergeableState);
            entity.setHeadPushedAt(headPushedAt);
            // Only overwrite head_ref when the detail sync actually
            // produced one. Detail can briefly miss it on first fetch
            // (GitHub still computing); keeping the previous value
            // beats blanking the IN_REVIEW lookup.
            if (headRef != null) {
                entity.setHeadRef(headRef);
            }
        });
    }

    private static String canonicalState(String state, Instant mergedAt)
    {
        return mergedAt != null ? "merged" : state;
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
