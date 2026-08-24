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
import java.util.HashSet;
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
        return pullRequestRepo.findIdByRepoAndNumber(repo, number)
                .flatMap(detailRepo::findSyncedAtByPrId);
    }

    @Override
    public Optional<StoredPrDetail> find(long prId)
    {
        return detailRepo.findById(prId).map(detail -> new StoredPrDetail(
                toRawDetail(detail),
                reviewRepo.findByPrId(prId).stream().map(SqlitePrDetailStore::toReview).collect(toImmutableList()),
                timelineRepo.findByPrIdOrderByTimestampAscIdAsc(prId).stream()
                        .map(SqlitePrDetailStore::toTimeline)
                        .collect(toImmutableList()),
                checkRunRepo.findByPrId(prId).stream().map(SqlitePrDetailStore::toCheckRun).collect(toImmutableList()),
                detail.getUnavailableSections() != null ? detail.getUnavailableSections() : ImmutableList.of(),
                Boolean.TRUE.equals(detail.getViewerCanWrite())));
    }

    @Override
    public Optional<String> findEtag(long prId)
    {
        return detailRepo.findById(prId).map(PrDetailEntity::getEtag);
    }

    @Override
    @Transactional
    public void save(long prId, StoredPrDetail detail)
    {
        saveHeader(prId, detail);

        reviewRepo.deleteByPrId(prId);
        reviewRepo.saveAll(detail.reviews().stream().map(review -> toEntity(prId, review)).collect(toImmutableList()));

        timelineRepo.deleteRemoteByPrId(prId);
        timelineRepo.saveAll(detail.timeline().stream().map(event -> toEntity(prId, event)).collect(toImmutableList()));

        checkRunRepo.deleteByPrId(prId);
        checkRunRepo.saveAll(detail.checkRuns().stream().map(check -> toEntity(prId, check)).collect(toImmutableList()));
    }

    @Override
    @Transactional
    public PrTimelineEvent recordLocalTimelineEvent(PullRequestRef pullRequest, PrTimelineEvent event)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(event, "event is null");
        String eventKey = event.localEventKey()
                .orElseThrow(() -> new IllegalArgumentException("local timeline event has no event key"));
        long prId = pullRequestRepo.findIdByRepoAndNumber(pullRequest.repoFullName(), pullRequest.number())
                .orElseThrow(() -> new IllegalArgumentException("Pull request is not stored: " + pullRequest.fullName()));
        timelineRepo.insertLocalEvent(
                prId,
                event.event(),
                event.actor(),
                event.timestamp().toString(),
                event.body(),
                eventKey);
        return timelineRepo.findByPrIdAndLocalEventKey(prId, eventKey)
                .map(SqlitePrDetailStore::toTimeline)
                .orElseThrow(() -> new IllegalStateException("Local timeline event was not stored"));
    }

    @Override
    @Transactional
    public void saveIncremental(long prId, StoredPrDetail detail)
    {
        saveHeader(prId, detail);

        reviewRepo.deleteByPrId(prId);
        reviewRepo.saveAll(detail.reviews().stream().map(review -> toEntity(prId, review)).collect(toImmutableList()));

        checkRunRepo.deleteByPrId(prId);
        checkRunRepo.saveAll(detail.checkRuns().stream().map(check -> toEntity(prId, check)).collect(toImmutableList()));

        Set<Long> existingIds = new HashSet<>(timelineRepo.findGithubIdsByPrId(prId));
        timelineRepo.saveAll(detail.timeline().stream()
                .filter(event -> event.githubId() == null || existingIds.add(event.githubId()))
                .map(event -> toEntity(prId, event))
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

    private static PrRawDetail toRawDetail(PrDetailEntity detail)
    {
        return new PrRawDetail(
                detail.getTitle(),
                detail.getHtmlUrl(),
                detail.getState(),
                detail.getAuthor(),
                detail.getAuthorAvatarUrl(),
                detail.getCreatedAt(),
                detail.getUpdatedAt(),
                detail.getMergedAt(),
                detail.getBody(),
                detail.getLabels(),
                detail.getLabelColors() != null ? detail.getLabelColors() : ImmutableMap.of(),
                detail.getAssignees() != null ? detail.getAssignees() : ImmutableList.of(),
                detail.getMilestone(),
                detail.isDraft(),
                detail.getMergeable(),
                detail.getMergeableState(),
                detail.getAdditions(),
                detail.getDeletions(),
                detail.getChangedFiles(),
                detail.getRequestedReviewerCount(),
                detail.getRequestedReviewers() != null ? detail.getRequestedReviewers() : ImmutableList.of(),
                detail.getHeadSha(),
                detail.getHeadRef(),
                detail.getBaseRef());
    }

    private static PrReviewState toReview(PrReviewEntity entity)
    {
        return new PrReviewState(
                entity.getGithubId(),
                entity.getLogin(),
                entity.getAvatarUrl(),
                entity.isBot(),
                entity.getState(),
                entity.getSubmittedAt(),
                entity.getBody(),
                entity.getHtmlUrl(),
                entity.getCommitId());
    }

    private static PrReviewEntity toEntity(long prId, PrReviewState review)
    {
        PrReviewEntity entity = new PrReviewEntity();
        entity.setPrId(prId);
        entity.setGithubId(review.githubId());
        entity.setLogin(review.login());
        entity.setAvatarUrl(review.avatarUrl());
        entity.setBot(review.bot());
        entity.setState(review.state());
        entity.setSubmittedAt(review.submittedAt());
        entity.setBody(review.body());
        entity.setHtmlUrl(review.htmlUrl());
        entity.setCommitId(review.commitId());
        return entity;
    }

    private static PrCheckRunState toCheckRun(PrCheckRunEntity entity)
    {
        return new PrCheckRunState(
                entity.getGithubId(),
                entity.getName(),
                entity.getStatus(),
                entity.getConclusion(),
                entity.getDetailsUrl(),
                entity.getSource());
    }

    private static PrCheckRunEntity toEntity(long prId, PrCheckRunState check)
    {
        PrCheckRunEntity entity = new PrCheckRunEntity();
        entity.setPrId(prId);
        entity.setGithubId(check.githubId());
        entity.setName(check.name());
        entity.setStatus(check.status());
        entity.setConclusion(check.conclusion());
        entity.setDetailsUrl(check.detailsUrl());
        entity.setSource(check.source());
        return entity;
    }

    private static PrTimelineEvent toTimeline(PrTimelineEntity entity)
    {
        return new PrTimelineEvent(
                entity.getGithubId(),
                entity.getEvent(),
                entity.getActor(),
                entity.getTimestamp(),
                entity.getBody(),
                entity.getPayload() == null ? com.bytequay.core.domain.PrTimelinePayload.empty() : entity.getPayload(),
                Optional.ofNullable(entity.getLocalEventKey()));
    }

    private static PrTimelineEntity toEntity(long prId, PrTimelineEvent event)
    {
        PrTimelineEntity entity = new PrTimelineEntity();
        entity.setPrId(prId);
        entity.setGithubId(event.githubId());
        entity.setEvent(event.event());
        entity.setActor(event.actor());
        entity.setTimestamp(event.timestamp());
        entity.setBody(event.body());
        entity.setPayload(event.payload());
        entity.setLocalEventKey(event.localEventKey().orElse(null));
        return entity;
    }
}
