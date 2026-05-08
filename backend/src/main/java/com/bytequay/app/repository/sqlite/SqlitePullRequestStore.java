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
import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.repository.PrViewStateStore;
import com.bytequay.app.repository.PullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
                .map(e -> toDomain(e, stateByPrId.get(e.getId())))
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void replaceAll(List<PullRequest> pullRequests)
    {
        Instant now = Instant.now();
        Set<Long> protectedIds = handledPrIdsWithinRetention(now);

        if (pullRequests.isEmpty()) {
            if (protectedIds.isEmpty()) {
                jpaRepository.deleteAll();
            }
            else {
                jpaRepository.deleteAllByIdNotIn(protectedIds);
            }
            return;
        }

        Set<Long> freshIds = pullRequests.stream()
                .map(PullRequest::id)
                .collect(toImmutableSet());

        // Preserve detail-derived enrichment columns from the previous
        // sync. The search-API responses fed in here don't include
        // reviewer_verdicts / mergeable / etc., so toEntity below would
        // overwrite a populated row with nulls and wipe the kanban
        // categorization between syncs. The detail-sync pass downstream
        // only re-runs for PRs whose updatedAt changed (or whose
        // enrichment is missing) — for unchanged PRs we want their
        // last-known verdict map to survive this replace.
        Map<Long, PullRequestEntity> existingById = jpaRepository
                .findAllById(freshIds)
                .stream()
                .collect(Collectors.toMap(PullRequestEntity::getId, e -> e));

        List<PullRequestEntity> entities = pullRequests.stream()
                .map(pr -> {
                    PullRequestEntity entity = toEntity(pr, now);
                    PullRequestEntity prev = existingById.get(pr.id());
                    if (prev != null) {
                        if (entity.getReviewerVerdicts() == null) {
                            entity.setReviewerVerdicts(prev.getReviewerVerdicts());
                        }
                        if (entity.getMergeable() == null) {
                            entity.setMergeable(prev.getMergeable());
                        }
                        if (entity.getMergeableState() == null) {
                            entity.setMergeableState(prev.getMergeableState());
                        }
                        if (entity.getHeadPushedAt() == null) {
                            entity.setHeadPushedAt(prev.getHeadPushedAt());
                        }
                        if (entity.getHeadRef() == null) {
                            entity.setHeadRef(prev.getHeadRef());
                        }
                    }
                    return entity;
                })
                .collect(toImmutableList());

        jpaRepository.saveAll(entities);

        Set<Long> keep = Sets.newHashSet(freshIds);
        keep.addAll(protectedIds);
        jpaRepository.deleteAllByIdNotIn(keep);
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
    public Optional<Instant> lastSyncedAt()
    {
        return jpaRepository.findMaxSyncedAt();
    }

    @Override
    public Map<Long, Instant> findUpdatedAtMap()
    {
        return jpaRepository.findAll().stream()
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
        // is cheap relative to the kanban being silently wrong.
        // Null head_ref also forces a re-sync — the V42 column was
        // added after these rows were stored and only the detail
        // fetch fills it; without this clause the local-repo IN_REVIEW
        // column stays empty for every legacy PR.
        return jpaRepository.findAll().stream()
                .filter(e -> {
                    Map<String, String> verdicts = e.getReviewerVerdicts();
                    if (verdicts == null || verdicts.isEmpty()) {
                        return true;
                    }
                    return e.getHeadRef() == null;
                })
                .map(PullRequestEntity::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Optional<Long> findIdByRepoAndNumber(String repo, int number)
    {
        return jpaRepository.findIdByRepoAndNumber(repo, number);
    }

    @Override
    public Optional<PullRequest> findById(long prId)
    {
        Map<Long, PrViewState> stateByPrId = viewStateStore.findAll();
        return jpaRepository.findById(prId)
                .map(entity -> toDomain(entity, stateByPrId.get(entity.getId())));
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

    private static PullRequest toDomain(PullRequestEntity entity, PrViewState state)
    {
        // GitHub's REST API reports merged PRs as state="closed" with
        // merged_at set; synthesize "merged" here so every renderer
        // (status pills on cards + title, kanban categorization) sees
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
                Optional.ofNullable(state).map(PrViewState::snoozedUntil).orElse(null),
                Optional.ofNullable(state).map(PrViewState::snoozeWakeReason).orElse(null),
                entity.getHeadRef());
    }
}
