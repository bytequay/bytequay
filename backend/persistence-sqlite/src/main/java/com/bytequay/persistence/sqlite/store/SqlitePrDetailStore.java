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

import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.persistence.sqlite.entity.PrCheckRunEntity;
import com.bytequay.persistence.sqlite.entity.PrDetailEntity;
import com.bytequay.persistence.sqlite.entity.PrReviewEntity;
import com.bytequay.persistence.sqlite.entity.PrTimelineEntity;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PrCheckRunJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrDetailJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrReviewJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqlitePrDetailStore
        implements PrDetailStore
{
    private final PrDetailJpaRepository detailRepo;
    private final PrReviewJpaRepository reviewRepo;
    private final PrTimelineJpaRepository timelineRepo;
    private final PrCheckRunJpaRepository checkRunRepo;
    private final PullRequestJpaRepository pullRequestRepo;

    public SqlitePrDetailStore(
            PrDetailJpaRepository detailRepo,
            PrReviewJpaRepository reviewRepo,
            PrTimelineJpaRepository timelineRepo,
            PrCheckRunJpaRepository checkRunRepo,
            PullRequestJpaRepository pullRequestRepo)
    {
        this.detailRepo = requireNonNull(detailRepo, "detailRepo is null");
        this.reviewRepo = requireNonNull(reviewRepo, "reviewRepo is null");
        this.timelineRepo = requireNonNull(timelineRepo, "timelineRepo is null");
        this.checkRunRepo = requireNonNull(checkRunRepo, "checkRunRepo is null");
        this.pullRequestRepo = requireNonNull(pullRequestRepo, "pullRequestRepo is null");
    }

    @Override
    public Optional<Instant> findSyncedAt(String repo, int number)
    {
        return pullRequestRepo.findByRepoIgnoreCaseAndNumber(repo, number)
                .map(PullRequestEntity::getId)
                .flatMap(detailRepo::findSyncedAtByPrId);
    }

    @Override
    public Optional<StoredPrDetail> find(long prId)
    {
        return detailRepo.findById(prId).map(detail -> new StoredPrDetail(
                detail.toRawDetail(),
                reviewRepo.findByPrId(prId).stream().map(PrReviewEntity::toDomain).collect(toImmutableList()),
                timelineRepo.findByPrIdOrderByTimestampAscIdAsc(prId).stream()
                        .map(PrTimelineEntity::toDomain)
                        .collect(toImmutableList()),
                checkRunRepo.findByPrId(prId).stream().map(PrCheckRunEntity::toDomain).collect(toImmutableList()),
                detail.getUnavailableSections() != null ? detail.getUnavailableSections() : ImmutableList.of(),
                Boolean.TRUE.equals(detail.getViewerCanWrite())));
    }

    @Override
    public Optional<String> findEtag(long prId)
    {
        return detailRepo.findById(prId).map(PrDetailEntity::getEtag);
    }

    @Override
    public Optional<String> findCheckRunsEtag(long prId)
    {
        return detailRepo.findById(prId).map(PrDetailEntity::getCheckRunsEtag);
    }

    @Override
    public Optional<String> findCommitStatusesEtag(long prId)
    {
        return detailRepo.findById(prId).map(PrDetailEntity::getCommitStatusesEtag);
    }

    @Override
    @Transactional
    public void save(long prId, StoredPrDetail detail)
    {
        saveHeader(prId, detail);
        saveReviews(prId, detail.reviews());
        saveTimeline(prId, detail.timeline());
        saveCheckRuns(prId, detail.checkRuns());
    }

    @Override
    @Transactional
    public void saveStatus(long prId, StoredPrDetail detail)
    {
        saveHeader(prId, detail);
        saveCheckRuns(prId, detail.checkRuns());
    }

    @Override
    @Transactional
    public PrTimelineEvent recordLocalTimelineEvent(PullRequestRef pullRequest, PrTimelineEvent event)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(event, "event is null");
        String eventKey = event.localEventKey()
                .orElseThrow(() -> new IllegalArgumentException("local timeline event has no event key"));
        long prId = pullRequestRepo.findByRepoIgnoreCaseAndNumber(pullRequest.repoFullName(), pullRequest.number())
                .map(PullRequestEntity::getId)
                .orElseThrow(() -> new IllegalArgumentException("Pull request is not stored: " + pullRequest.fullName()));
        timelineRepo.insertLocalEvent(
                prId,
                event.event(),
                event.actor(),
                event.timestamp().toString(),
                event.body(),
                eventKey);
        return timelineRepo.findByPrIdAndLocalEventKey(prId, eventKey)
                .map(PrTimelineEntity::toDomain)
                .orElseThrow(() -> new IllegalStateException("Local timeline event was not stored"));
    }

    @Override
    @Transactional
    public void saveIncremental(long prId, StoredPrDetail detail)
    {
        saveHeader(prId, detail);
        saveReviews(prId, detail.reviews());
        saveCheckRuns(prId, detail.checkRuns());

        Set<Long> existingIds = new HashSet<>(timelineRepo.findGithubIdsByPrId(prId));
        timelineRepo.saveAll(detail.timeline().stream()
                .filter(event -> event.localEventKey().isEmpty())
                .filter(event -> event.githubId() == null || existingIds.add(event.githubId()))
                .map(event -> new PrTimelineEntity().updateFrom(prId, event))
                .collect(toImmutableList()));
    }

    @Override
    @Transactional
    public void updateEtag(long prId, Optional<String> etag)
    {
        requireNonNull(etag, "etag is null");
        detailRepo.findById(prId).ifPresent(detail -> {
            detail.setEtag(etag.orElse(null));
            detailRepo.save(detail);
        });
    }

    @Override
    @Transactional
    public void updateStatusEtags(long prId, Optional<String> checkRunsEtag, Optional<String> commitStatusesEtag)
    {
        requireNonNull(checkRunsEtag, "checkRunsEtag is null");
        requireNonNull(commitStatusesEtag, "commitStatusesEtag is null");
        detailRepo.findById(prId).ifPresent(detail -> {
            detail.setCheckRunsEtag(checkRunsEtag.orElse(null));
            detail.setCommitStatusesEtag(commitStatusesEtag.orElse(null));
            detailRepo.save(detail);
        });
    }

    @Override
    @Transactional
    public void deleteByPrIds(Set<Long> prIds)
    {
        if (prIds.isEmpty()) {
            return;
        }
        detailRepo.deleteByPrIdIn(prIds);
        for (long prId : prIds) {
            reviewRepo.deleteByPrId(prId);
            timelineRepo.deleteByPrId(prId);
            checkRunRepo.deleteByPrId(prId);
        }
    }

    private void saveHeader(long prId, StoredPrDetail detail)
    {
        PrRawDetail raw = detail.raw();
        PrDetailEntity entity = detailRepo.findById(prId).orElseGet(PrDetailEntity::new);
        if (!Objects.equals(entity.getHeadSha(), raw.headSha())) {
            entity.setCheckRunsEtag(null);
            entity.setCommitStatusesEtag(null);
        }
        entity.setPrId(prId);
        entity.setTitle(raw.title());
        entity.setHtmlUrl(raw.htmlUrl());
        entity.setState(raw.state());
        entity.setAuthor(raw.author());
        entity.setAuthorAvatarUrl(raw.authorAvatarUrl());
        entity.setCreatedAt(raw.createdAt());
        entity.setUpdatedAt(raw.updatedAt());
        entity.setMergedAt(raw.mergedAt());
        entity.setBody(raw.body());
        entity.setLabels(raw.labels() != null ? raw.labels() : ImmutableList.of());
        entity.setLabelColors(raw.labelColors() != null ? raw.labelColors() : ImmutableMap.of());
        entity.setAssignees(raw.assignees() != null ? raw.assignees() : ImmutableList.of());
        entity.setMilestone(raw.milestone());
        entity.setUnavailableSections(
                detail.unavailableSections() != null ? detail.unavailableSections() : ImmutableList.of());
        entity.setDraft(raw.draft());
        entity.setMergeable(raw.mergeable());
        entity.setMergeableState(raw.mergeableState());
        entity.setAdditions(raw.additions());
        entity.setDeletions(raw.deletions());
        entity.setChangedFiles(raw.changedFiles());
        entity.setRequestedReviewerCount(raw.requestedReviewerCount());
        entity.setRequestedReviewers(raw.requestedReviewers() != null ? raw.requestedReviewers() : ImmutableList.of());
        entity.setHeadSha(raw.headSha());
        entity.setHeadRef(raw.headRef());
        entity.setBaseRef(raw.baseRef());
        entity.setViewerCanWrite(detail.viewerCanWrite());
        entity.setSyncedAt(Instant.now());
        detailRepo.save(entity);
    }

    /**
     * Reconciles the stored reviews for a PR against the full list GitHub
     * returned: rows keep their identity across refreshes, rows GitHub no
     * longer reports are swept. Matching is on {@code github_id}; a row
     * without one cannot be matched and is replaced.
     */
    private void saveReviews(long prId, List<PrReviewState> reviews)
    {
        Map<Long, PrReviewEntity> stored = new HashMap<>();
        List<PrReviewEntity> unmatchable = new ArrayList<>();
        for (PrReviewEntity row : reviewRepo.findByPrId(prId)) {
            if (row.getGithubId() == null || stored.putIfAbsent(row.getGithubId(), row) != null) {
                unmatchable.add(row);
            }
        }
        reviewRepo.deleteAll(unmatchable);

        List<PrReviewEntity> merged = new ArrayList<>(reviews.size());
        for (PrReviewState review : reviews) {
            PrReviewEntity entity = review.githubId() == null ? null : stored.remove(review.githubId());
            merged.add((entity == null ? new PrReviewEntity() : entity).updateFrom(prId, review));
        }
        reviewRepo.saveAll(merged);
        reviewRepo.deleteAll(stored.values());
    }

    /**
     * Counterpart of {@link #saveReviews} for the timeline. Locally recorded
     * events are never part of what GitHub returns, so they are neither
     * rewritten nor swept.
     */
    private void saveTimeline(long prId, List<PrTimelineEvent> events)
    {
        Map<String, PrTimelineEntity> stored = new HashMap<>();
        List<PrTimelineEntity> unmatchable = new ArrayList<>();
        for (PrTimelineEntity row : timelineRepo.findByPrIdOrderByTimestampAscIdAsc(prId)) {
            if (row.getLocalEventKey() == null && stored.putIfAbsent(sourceKey(row.toDomain()), row) != null) {
                unmatchable.add(row);
            }
        }
        timelineRepo.deleteAll(unmatchable);

        List<PrTimelineEntity> merged = new ArrayList<>(events.size());
        for (PrTimelineEvent event : events) {
            if (event.localEventKey().isPresent()) {
                continue;
            }
            PrTimelineEntity entity = stored.remove(sourceKey(event));
            merged.add((entity == null ? new PrTimelineEntity() : entity).updateFrom(prId, event));
        }
        timelineRepo.saveAll(merged);
        timelineRepo.deleteAll(stored.values());
    }

    /**
     * Stable identity for a timeline event. Most carry a GitHub id, but
     * commits, cross-references and the synthetic reactions row do not, so
     * those fall back to the identifier their payload does carry.
     */
    private static String sourceKey(PrTimelineEvent event)
    {
        if (event.githubId() != null) {
            return "gh:" + event.githubId();
        }
        if (event.payload().commitSha() != null) {
            return "commit:" + event.payload().commitSha();
        }
        if (event.payload().sourceUrl() != null) {
            return "xref:" + event.payload().sourceUrl();
        }
        return "event:" + event.event();
    }

    /** Counterpart of {@link #saveReviews} for check runs. A re-run reports a
     *  new {@code github_id}, so superseded runs are swept here. */
    private void saveCheckRuns(long prId, List<PrCheckRunState> checkRuns)
    {
        Map<Long, PrCheckRunEntity> stored = new HashMap<>();
        List<PrCheckRunEntity> unmatchable = new ArrayList<>();
        for (PrCheckRunEntity row : checkRunRepo.findByPrId(prId)) {
            if (row.getGithubId() == null || stored.putIfAbsent(row.getGithubId(), row) != null) {
                unmatchable.add(row);
            }
        }
        checkRunRepo.deleteAll(unmatchable);

        List<PrCheckRunEntity> merged = new ArrayList<>(checkRuns.size());
        for (PrCheckRunState check : checkRuns) {
            PrCheckRunEntity entity = check.githubId() == null ? null : stored.remove(check.githubId());
            merged.add((entity == null ? new PrCheckRunEntity() : entity).updateFrom(prId, check));
        }
        checkRunRepo.saveAll(merged);
        checkRunRepo.deleteAll(stored.values());
    }
}
