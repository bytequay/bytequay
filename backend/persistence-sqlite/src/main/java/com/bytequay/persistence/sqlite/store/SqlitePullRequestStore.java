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
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository.AttentionActivity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository.CardActivity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository.DiscussionActivity;
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
    @Transactional(readOnly = true)
    public Optional<PullRequest> findByHostingRepository(long repositoryId, int number)
    {
        return jpaRepository.findByHostingRepositoryIdAndNumber(repositoryId, number)
                .filter(SqlitePullRequestStore::hasSummary)
                .map(row -> row.toDomain(viewStateStore.findByPrIds(ImmutableSet.of(row.getId())).get(row.getId())));
    }

    @Override
    @Transactional
    public PullRequest saveHosted(long repositoryId, FetchedPullRequest pullRequest)
    {
        PullRequestEntity row = jpaRepository.findByHostingRepositoryIdAndNumber(repositoryId, pullRequest.number()).orElseThrow();
        if (!hasSummary(row)) {
            PullRequestEntity updated = PullRequestEntity.from(pullRequest, row.getSyncedAt());
            updated.preserveEnrichmentFrom(row);
            row.updateSummary(pullRequest, row.getSyncedAt());
            row.preserveEnrichmentFrom(updated);
        }
        return row.toDomain(viewStateStore.findByPrIds(ImmutableSet.of(row.getId())).get(row.getId()));
    }

    private static boolean hasSummary(PullRequestEntity row)
    {
        return row.getAuthor() != null && row.getCreatedAt() != null && row.getUpdatedAt() != null && row.getState() != null;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HostingPullRequest> findHostingIdentity(long prId)
    {
        return jpaRepository.findById(prId).map(PullRequestEntity::hostingIdentity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullRequest> findByHostingHeadRef(long repositoryId, String headRef)
    {
        return jpaRepository.findByHostingRepositoryIdAndHeadRefOrderByNumberDesc(repositoryId, headRef).stream()
                .map(row -> row.toDomain(null)).collect(toImmutableList());
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
    @Transactional
    public void setDashboardVisible(long prId, boolean visible)
    {
        PullRequestEntity row = jpaRepository.findById(prId).orElseThrow();
        // An unchanged mark must not count as a write, or every discovery pass would refresh the attention list.
        if (row.isDashboardVisible() != visible) {
            row.setDashboardVisible(visible);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullRequest> findDashboardVisible()
    {
        List<PullRequestEntity> rows = jpaRepository.findDashboardVisible();
        Map<Long, PrViewState> states = viewStateStore.findByPrIds(rows.stream().map(PullRequestEntity::getId).collect(toImmutableSet()));
        return rows.stream().map(row -> row.toDomain(states.get(row.getId()))).collect(toImmutableList());
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
    @Transactional(readOnly = true)
    public List<PullRequest> findMergedRepositoryPullRequests(String repository, String search, int limit)
    {
        requireNonNull(repository, "repository is null");
        checkArgument(limit > 0 && limit <= 100, "limit must be between 1 and 100");
        String term = normalizeSearch(search);
        return jpaRepository.findMerged(repository, term, pullRequestNumber(term), PageRequest.of(0, limit)).stream()
                .map(entity -> entity.toDomain(null))
                .collect(toImmutableList());
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

    @Override
    public Optional<Long> findIdByRepoAndNumber(String repo, int number)
    {
        requireNonNull(repo, "repo is null");
        return jpaRepository.findByPath(repo, number).stream().findFirst().map(PullRequestEntity::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullRequest> findByHeadRef(String repo, String headRef)
    {
        requireNonNull(repo, "repo is null");
        requireNonNull(headRef, "headRef is null");
        return jpaRepository.findByPathAndHeadRef(repo, headRef).stream()
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
            entity.observeMergeability(mergeable, mergeableState);
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
}
