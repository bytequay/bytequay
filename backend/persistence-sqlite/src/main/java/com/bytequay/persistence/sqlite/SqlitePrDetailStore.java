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
package com.bytequay.persistence.sqlite;

import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
                reviewRepo.findByPrId(prId).stream().map(SqlitePrDetailStore::toReview).toList(),
                timelineRepo.findByPrId(prId).stream().map(SqlitePrDetailStore::toTimeline).toList(),
                checkRunRepo.findByPrId(prId).stream().map(SqlitePrDetailStore::toCheckRun).toList()));
    }

    @Override
    @Transactional
    public void save(long prId, StoredPrDetail detail)
    {
        saveHeader(prId, detail.raw());

        reviewRepo.deleteByPrId(prId);
        reviewRepo.saveAll(detail.reviews().stream().map(review -> toEntity(prId, review)).toList());

        timelineRepo.deleteByPrId(prId);
        timelineRepo.saveAll(detail.timeline().stream().map(event -> toEntity(prId, event)).toList());

        checkRunRepo.deleteByPrId(prId);
        checkRunRepo.saveAll(detail.checkRuns().stream().map(check -> toEntity(prId, check)).toList());
    }

    @Override
    @Transactional
    public void saveIncremental(long prId, StoredPrDetail detail)
    {
        saveHeader(prId, detail.raw());

        reviewRepo.deleteByPrId(prId);
        reviewRepo.saveAll(detail.reviews().stream().map(review -> toEntity(prId, review)).toList());

        checkRunRepo.deleteByPrId(prId);
        checkRunRepo.saveAll(detail.checkRuns().stream().map(check -> toEntity(prId, check)).toList());

        Set<Long> existingIds = new HashSet<>(timelineRepo.findGithubIdsByPrId(prId));
        timelineRepo.saveAll(detail.timeline().stream()
                .filter(event -> event.githubId() == null || existingIds.add(event.githubId()))
                .map(event -> toEntity(prId, event))
                .toList());
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

    private void saveHeader(long prId, PrRawDetail raw)
    {
        PrDetailEntity entity = detailRepo.findById(prId).orElseGet(PrDetailEntity::new);
        entity.setPrId(prId);
        entity.setBody(raw.body());
        entity.setLabels(raw.labels() != null ? raw.labels() : List.of());
        entity.setDraft(raw.draft());
        entity.setMergeable(raw.mergeable());
        entity.setMergeableState(raw.mergeableState());
        entity.setAdditions(raw.additions());
        entity.setDeletions(raw.deletions());
        entity.setChangedFiles(raw.changedFiles());
        entity.setRequestedReviewerCount(raw.requestedReviewerCount());
        entity.setRequestedReviewers(raw.requestedReviewers() != null ? raw.requestedReviewers() : List.of());
        entity.setHeadSha(raw.headSha());
        entity.setHeadRef(raw.headRef());
        entity.setSyncedAt(Instant.now());
        detailRepo.save(entity);
    }

    private static PrRawDetail toRawDetail(PrDetailEntity detail)
    {
        return new PrRawDetail(
                detail.getBody(),
                detail.getLabels(),
                detail.isDraft(),
                detail.getMergeable(),
                detail.getMergeableState(),
                detail.getAdditions(),
                detail.getDeletions(),
                detail.getChangedFiles(),
                detail.getRequestedReviewerCount(),
                detail.getRequestedReviewers() != null ? detail.getRequestedReviewers() : List.of(),
                detail.getHeadSha(),
                detail.getHeadRef());
    }

    private static PrReviewState toReview(PrReviewEntity entity)
    {
        return new PrReviewState(entity.getLogin(), entity.getState(), entity.getSubmittedAt());
    }

    private static PrReviewEntity toEntity(long prId, PrReviewState review)
    {
        PrReviewEntity entity = new PrReviewEntity();
        entity.setPrId(prId);
        entity.setLogin(review.login());
        entity.setState(review.state());
        entity.setSubmittedAt(review.submittedAt());
        return entity;
    }

    private static PrCheckRunState toCheckRun(PrCheckRunEntity entity)
    {
        return new PrCheckRunState(
                entity.getGithubId(),
                entity.getName(),
                entity.getStatus(),
                entity.getConclusion());
    }

    private static PrCheckRunEntity toEntity(long prId, PrCheckRunState check)
    {
        PrCheckRunEntity entity = new PrCheckRunEntity();
        entity.setPrId(prId);
        entity.setGithubId(check.githubId());
        entity.setName(check.name());
        entity.setStatus(check.status());
        entity.setConclusion(check.conclusion());
        return entity;
    }

    private static PrTimelineEvent toTimeline(PrTimelineEntity entity)
    {
        return new PrTimelineEvent(
                entity.getGithubId(),
                entity.getEvent(),
                entity.getActor(),
                entity.getTimestamp(),
                entity.getBody());
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
        return entity;
    }
}
