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

import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrChangeSnapshot;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrCommitLine;
import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrStateSummary;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import com.bytequay.persistence.sqlite.entity.PrCheckRunEntity;
import com.bytequay.persistence.sqlite.entity.PrDetailEntity;
import com.bytequay.persistence.sqlite.entity.PrReviewEntity;
import com.bytequay.persistence.sqlite.entity.PrTimelineEntity;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PrCheckRunJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrDetailJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrDetailJpaRepository.AuthorAvatar;
import com.bytequay.persistence.sqlite.repository.PrDetailJpaRepository.RequestedReviewers;
import com.bytequay.persistence.sqlite.repository.PrDetailJpaRepository.StateSummary;
import com.bytequay.persistence.sqlite.repository.PrReviewJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository.ActivityCandidate;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository.ChangeEvent;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository.CommitCandidate;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository.QueueState;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Repository
public class SqlitePrDetailStore
        implements PrDetailStore
{
    /** Commits a list shows at most; a card names a handful and counts the rest. */
    private static final int MAX_COMMITS_PER_PULL_REQUEST = 20;

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
    public PrDetailFreshness findFreshness(long prId)
    {
        // Four timestamps, not the whole row: attention reads this once per failing pull request.
        return detailRepo.findFreshness(prId).map(SqlitePrDetailStore::freshness).orElseGet(PrDetailFreshness::unknown);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, PrDetailFreshness> findFreshness(Set<Long> prIds)
    {
        requireNonNull(prIds, "prIds is null");
        if (prIds.isEmpty()) {
            return ImmutableMap.of();
        }
        return detailRepo.findFreshness(prIds).stream().collect(toImmutableMap(PrDetailJpaRepository.PrFreshness::getPrId, SqlitePrDetailStore::freshness));
    }

    private static PrDetailFreshness freshness(PrDetailJpaRepository.Freshness row)
    {
        return new PrDetailFreshness(Optional.ofNullable(row.getMetadataValidatedAt()), Optional.ofNullable(row.getDiscussionValidatedAt()),
                Optional.ofNullable(row.getChecksValidatedAt()), Optional.ofNullable(row.getDiscussionSourceUpdatedAt()));
    }

    // Four queries across four tables: without a transaction each borrows the single
    // pooled connection separately, so a read queues behind writers four times over.
    @Override
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
    public Map<Long, PrStateSummary> findStates(Set<Long> prIds)
    {
        requireNonNull(prIds, "prIds is null");
        if (prIds.isEmpty()) {
            return ImmutableMap.of();
        }
        Map<Long, String> queueEvents = timelineRepo.findLatestQueueEvents(prIds).stream()
                .collect(toImmutableMap(QueueState::getPrId, QueueState::getEvent));
        return detailRepo.findStates(prIds).stream().collect(toImmutableMap(StateSummary::getPrId, row -> {
            String state = row.getMergedAt() == null ? row.getState() : "merged";
            return new PrStateSummary(state, row.isDraft(), "open".equals(state) && "added_to_merge_queue".equals(queueEvents.get(row.getPrId())));
        }));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, PrChangeSnapshot> findChangeSnapshots(Set<Long> prIds)
    {
        requireNonNull(prIds, "prIds is null");
        if (prIds.isEmpty()) {
            return ImmutableMap.of();
        }
        ImmutableListMultimap<Long, ChangeEvent> events = Multimaps.index(timelineRepo.findChangeEvents(prIds), ChangeEvent::getPrId);
        Map<Long, List<PrCheckRunState>> checks = findCheckRuns(prIds);
        return detailRepo.findRequestedReviewers(prIds).stream().collect(toImmutableMap(RequestedReviewers::getPrId, row -> new PrChangeSnapshot(
                row.getRequestedReviewers(),
                events.get(row.getPrId()).stream().map(event -> new PrTimelineEvent(null, event.getEvent(), event.getActor(), event.getTimestamp(), null, Optional.empty()))
                        .collect(toImmutableList()),
                checks.getOrDefault(row.getPrId(), ImmutableList.of()))));
    }

    @Override
    public Map<Long, String> findAuthorAvatarUrls(Set<Long> prIds)
    {
        requireNonNull(prIds, "prIds is null");
        if (prIds.isEmpty()) {
            return ImmutableMap.of();
        }
        return detailRepo.findAuthorAvatarUrls(prIds).stream()
                .collect(toImmutableMap(AuthorAvatar::getPrId, AuthorAvatar::getAuthorAvatarUrl));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, PrActivitySummary> findLatestActivities(Set<Long> prIds)
    {
        requireNonNull(prIds, "prIds is null");
        if (prIds.isEmpty()) {
            return ImmutableMap.of();
        }
        Map<Long, PrActivitySummary> latest = new HashMap<>();
        for (ActivityCandidate event : timelineRepo.findLatestActivityCandidates(prIds, ImmutableSet.of("mentioned", "subscribed", "unsubscribed"))) {
            PrTimelinePayload payload = Optional.ofNullable(event.getPayload()).orElseGet(PrTimelinePayload::empty);
            // An inline comment names the file and line it was left on; an outdated one keeps its original line.
            Optional<String> path = Optional.ofNullable(payload.path());
            OptionalInt line = Optional.ofNullable(payload.line()).or(() -> Optional.ofNullable(payload.originalLine()))
                    .map(OptionalInt::of).orElseGet(OptionalInt::empty);
            if (event.getTimestamp() != null) {
                mergeActivity(latest, event.getPrId(), new PrActivitySummary(
                        event.getEvent(), Optional.ofNullable(event.getActor()), event.getTimestamp(),
                        Optional.ofNullable(payload.state()), Optional.ofNullable(payload.label()), Optional.ofNullable(payload.requestedReviewer()),
                        PrActivitySummary.excerpt(event.getBody()), path, line));
            }
            if ("review_thread".equals(event.getEvent())) {
                payload.comments().stream().filter(comment -> comment.timestamp() != null).forEach(comment -> mergeActivity(
                        latest, event.getPrId(), new PrActivitySummary(
                                "review_thread", Optional.ofNullable(comment.actor()), comment.timestamp(), Optional.empty(), Optional.empty(), Optional.empty(),
                                PrActivitySummary.excerpt(comment.body()), path, line)));
            }
        }
        return ImmutableMap.copyOf(latest);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, List<PrCommitLine>> findCommitsAfter(Map<Long, Instant> since)
    {
        requireNonNull(since, "since is null");
        if (since.isEmpty()) {
            return ImmutableMap.of();
        }
        Instant earliest = since.values().stream().min(Instant::compareTo).orElseThrow();
        Map<Long, List<PrCommitLine>> commits = new HashMap<>();
        for (CommitCandidate commit : timelineRepo.findCommitCandidates(since.keySet(), earliest)) {
            PrTimelinePayload payload = Optional.ofNullable(commit.getPayload()).orElseGet(PrTimelinePayload::empty);
            List<PrCommitLine> lines = commits.computeIfAbsent(commit.getPrId(), ignored -> new ArrayList<>());
            if (payload.commitSha() == null || !commit.getTimestamp().isAfter(since.get(commit.getPrId())) || lines.size() >= MAX_COMMITS_PER_PULL_REQUEST) {
                continue;
            }
            String message = Optional.ofNullable(payload.commitMessage()).orElse("").lines().findFirst().orElse("");
            lines.add(new PrCommitLine(payload.commitSha(), message, Optional.ofNullable(commit.getActor()), commit.getTimestamp()));
        }
        return commits.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(toImmutableMap(Map.Entry::getKey, entry -> ImmutableList.copyOf(entry.getValue())));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, List<PrCheckRunState>> findCheckRuns(Set<Long> prIds)
    {
        requireNonNull(prIds, "prIds is null");
        if (prIds.isEmpty()) {
            return ImmutableMap.of();
        }
        Map<Long, List<PrCheckRunState>> checkRuns = new HashMap<>();
        for (PrCheckRunEntity checkRun : checkRunRepo.findByPrIdInOrderByIdAsc(prIds)) {
            checkRuns.computeIfAbsent(checkRun.getPrId(), ignored -> new ArrayList<>()).add(checkRun.toDomain());
        }
        return checkRuns.entrySet().stream().collect(toImmutableMap(Map.Entry::getKey, entry -> ImmutableList.copyOf(entry.getValue())));
    }

    private static void mergeActivity(Map<Long, PrActivitySummary> latest, long prId, PrActivitySummary activity)
    {
        latest.merge(prId, activity, (left, right) -> left.timestamp().isAfter(right.timestamp()) ? left : right);
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
    public Map<String, String> findDiscussionEtags(long prId)
    {
        return detailRepo.findById(prId).map(PrDetailEntity::getDiscussionEtags).map(ImmutableMap::copyOf).orElse(ImmutableMap.of());
    }

    @Override
    @Transactional
    public void saveCached(long prId, StoredPrDetail detail)
    {
        saveHeader(prId, detail);
        saveReviews(prId, detail.reviews(), detail.reviewsComplete());
        saveTimeline(prId, detail.timeline(), detail.timelineComplete());
        saveCheckRuns(prId, detail.checkRuns(), detail.checkRunsComplete());
    }

    @Override
    @Transactional
    public void save(long prId, StoredPrDetail detail)
    {
        saveCached(prId, detail);
        PrDetailEntity entity = detailRepo.findById(prId).orElseThrow();
        entity.setMetadataValidatedAt(Instant.now());
        markDiscussionValidated(entity, detail, detail.raw().updatedAt());
        if (detail.checkRunsComplete()) {
            entity.setChecksValidatedAt(Instant.now());
        }
    }

    @Override
    @Transactional
    public void saveMetadata(long prId, StoredPrDetail detail)
    {
        boolean headChanged = detailRepo.findById(prId).filter(entity -> !Objects.equals(entity.getHeadSha(), detail.raw().headSha())).isPresent();
        saveHeader(prId, detail);
        if (headChanged) {
            saveCheckRuns(prId, ImmutableList.of(), true);
        }
        detailRepo.findById(prId).orElseThrow().setMetadataValidatedAt(Instant.now());
    }

    @Override
    @Transactional
    public void saveDiscussion(long prId, StoredPrDetail detail, Instant sourceUpdatedAt)
    {
        saveHeader(prId, detail);
        saveReviews(prId, detail.reviews(), detail.reviewsComplete());
        saveTimeline(prId, detail.timeline(), detail.timelineComplete());
        markDiscussionValidated(detailRepo.findById(prId).orElseThrow(), detail, sourceUpdatedAt);
    }

    private static void markDiscussionValidated(PrDetailEntity entity, StoredPrDetail detail, Instant sourceUpdatedAt)
    {
        if (detail.reviewsComplete() && detail.timelineComplete()) {
            entity.setDiscussionValidatedAt(Instant.now());
            entity.setDiscussionSourceUpdatedAt(sourceUpdatedAt);
        }
    }

    @Override
    @Transactional
    public void saveStatus(long prId, StoredPrDetail detail)
    {
        saveHeader(prId, detail);
        saveCheckRuns(prId, detail.checkRuns(), detail.checkRunsComplete());
        if (detail.checkRunsComplete()) {
            detailRepo.findById(prId).orElseThrow().setChecksValidatedAt(Instant.now());
        }
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
        timelineRepo.insertLocalEvent(prId, event.event(), event.actor(), InstantToTextConverter.format(event.timestamp()), event.body(), eventKey);
        return timelineRepo.findByPrIdAndLocalEventKey(prId, eventKey)
                .map(PrTimelineEntity::toDomain)
                .orElseThrow(() -> new IllegalStateException("Local timeline event was not stored"));
    }

    @Override
    @Transactional
    public void updateEtag(long prId, Optional<String> etag)
    {
        requireNonNull(etag, "etag is null");
        detailRepo.findById(prId).ifPresent(detail -> {
            detail.setEtag(etag.orElse(null));
            if (etag.isEmpty()) {
                detail.setMetadataValidatedAt(null);
                detail.setDiscussionValidatedAt(null);
                detail.setDiscussionEtags(null);
            }
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
        });
    }

    @Override
    @Transactional
    public void updateDiscussionEtags(long prId, Map<String, String> etags)
    {
        detailRepo.findById(prId).ifPresent(detail -> detail.setDiscussionEtags(ImmutableMap.copyOf(etags)));
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
            entity.setChecksValidatedAt(null);
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
        entity.setUnavailableSections(detail.unavailableSections() != null ? detail.unavailableSections() : ImmutableList.of());
        entity.setDraft(raw.draft());
        entity.setMergeable(raw.mergeable());
        entity.setMergeableState(raw.mergeableState());
        entity.setAdditions(raw.additions());
        entity.setDeletions(raw.deletions());
        entity.setChangedFiles(raw.changedFiles());
        entity.setRequestedReviewers(raw.requestedReviewers() != null ? raw.requestedReviewers() : ImmutableList.of());
        entity.setHeadSha(raw.headSha());
        entity.setBaseSha(raw.baseSha());
        entity.setHeadRef(raw.headRef());
        entity.setBaseRef(raw.baseRef());
        entity.setViewerCanWrite(detail.viewerCanWrite());
        entity.setSyncedAt(Instant.now());
        detailRepo.save(entity);
    }

    /**
     * Reconciles the stored reviews for a PR against the full list GitHub
     * returned. Matching is on {@code github_id}.
     */
    private void saveReviews(long prId, List<PrReviewState> reviews, boolean complete)
    {
        reconcile(
                reviewRepo,
                reviewRepo.findByPrId(prId),
                PrReviewEntity::getGithubId,
                reviews,
                PrReviewState::githubId,
                (entity, review) -> (entity == null ? new PrReviewEntity() : entity).updateFrom(prId, review),
                complete);
    }

    /**
     * Counterpart of {@link #saveReviews} for the timeline, matched on
     * {@link #sourceKey}. Locally recorded events are never part of what
     * GitHub returns, so they are neither rewritten nor swept.
     */
    private void saveTimeline(long prId, List<PrTimelineEvent> events, boolean complete)
    {
        reconcile(
                timelineRepo,
                timelineRepo.findByPrIdOrderByTimestampAscIdAsc(prId).stream()
                        .filter(row -> row.getLocalEventKey() == null)
                        .collect(toImmutableList()),
                row -> sourceKey(row.toDomain()),
                events.stream()
                        .filter(event -> event.localEventKey().isEmpty())
                        .collect(toImmutableList()),
                SqlitePrDetailStore::sourceKey,
                (entity, event) -> (entity == null ? new PrTimelineEntity() : entity).updateFrom(prId, event),
                complete);
    }

    /**
     * Reconciles one PR-scoped detail table against the full list GitHub
     * returned: rows keep their identity across refreshes, and when the list
     * is {@code complete}, rows GitHub no longer reports are swept. A stored
     * row with a null or duplicate key cannot be matched and is replaced; an
     * incoming item with a null key always creates a new row.
     */
    private static <K, E, D> void reconcile(
            CrudRepository<E, ?> repo,
            List<E> stored,
            Function<E, K> storedKey,
            List<D> incoming,
            Function<D, K> incomingKey,
            BiFunction<E, D, E> merge,
            boolean complete)
    {
        Map<K, E> byKey = new HashMap<>();
        List<E> unmatchable = new ArrayList<>();
        for (E row : stored) {
            K key = storedKey.apply(row);
            if (key == null || byKey.putIfAbsent(key, row) != null) {
                unmatchable.add(row);
            }
        }
        repo.deleteAll(unmatchable);

        List<E> merged = new ArrayList<>(incoming.size());
        for (D item : incoming) {
            K key = incomingKey.apply(item);
            merged.add(merge.apply(key == null ? null : byKey.remove(key), item));
        }
        repo.saveAll(merged);
        if (complete) {
            repo.deleteAll(byKey.values());
        }
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
    private void saveCheckRuns(long prId, List<PrCheckRunState> checkRuns, boolean complete)
    {
        reconcile(
                checkRunRepo,
                checkRunRepo.findByPrId(prId),
                PrCheckRunEntity::getGithubId,
                checkRuns,
                PrCheckRunState::githubId,
                (entity, check) -> (entity == null ? new PrCheckRunEntity() : entity).updateFrom(prId, check),
                complete);
    }
}
