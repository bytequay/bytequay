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

import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.repository.PrDetailStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqlitePrDetailStore
        implements PrDetailStore
{
    private final PrDetailJpaRepository detailRepo;
    private final PrReviewJpaRepository reviewRepo;
    private final PrFileJpaRepository fileRepo;
    private final PrTimelineJpaRepository timelineRepo;
    private final PrCheckRunJpaRepository checkRunRepo;
    private final PrReviewThreadMessageJpaRepository reviewCommentRepo;
    private final PrLinkedIssueJpaRepository linkedIssueRepo;
    private final PullRequestJpaRepository pullRequestRepo;

    public SqlitePrDetailStore(
            PrDetailJpaRepository detailRepo,
            PrReviewJpaRepository reviewRepo,
            PrFileJpaRepository fileRepo,
            PrTimelineJpaRepository timelineRepo,
            PrCheckRunJpaRepository checkRunRepo,
            PrReviewThreadMessageJpaRepository reviewCommentRepo,
            PrLinkedIssueJpaRepository linkedIssueRepo,
            PullRequestJpaRepository pullRequestRepo)
    {
        this.detailRepo = requireNonNull(detailRepo, "detailRepo is null");
        this.reviewRepo = requireNonNull(reviewRepo, "reviewRepo is null");
        this.fileRepo = requireNonNull(fileRepo, "fileRepo is null");
        this.timelineRepo = requireNonNull(timelineRepo, "timelineRepo is null");
        this.checkRunRepo = requireNonNull(checkRunRepo, "checkRunRepo is null");
        this.reviewCommentRepo = requireNonNull(reviewCommentRepo, "reviewCommentRepo is null");
        this.linkedIssueRepo = requireNonNull(linkedIssueRepo, "linkedIssueRepo is null");
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
        Optional<PrDetailEntity> entityOpt = detailRepo.findById(prId);
        if (entityOpt.isEmpty()) {
            return Optional.empty();
        }
        PrDetailEntity d = entityOpt.get();
        PrRawDetail raw = new PrRawDetail(
                d.getBody(),
                d.getLabels(),
                d.isDraft(),
                d.getMergeable(),
                d.getMergeableState(),
                d.getAdditions(),
                d.getDeletions(),
                d.getChangedFiles(),
                d.getRequestedReviewerCount(),
                d.getRequestedReviewers() != null ? d.getRequestedReviewers() : ImmutableList.of(),
                d.getHeadSha(),
                d.getHeadRef(),
                d.getHeadRepo(),
                d.getBaseRef(),
                d.getBaseRepo());

        List<PrReviewState> reviews = reviewRepo.findByPrId(prId).stream()
                .map(r -> new PrReviewState(r.getLogin(), r.getState(), r.getSubmittedAt()))
                .collect(toImmutableList());

        List<PullRequestDetail.ChangedFile> files = fileRepo.findByPrId(prId).stream()
                .map(f -> new PullRequestDetail.ChangedFile(
                        f.getFilename(), f.getAdditions(), f.getDeletions(), f.getStatus()))
                .collect(toImmutableList());

        List<PrTimelineEvent> timeline = timelineRepo.findByPrId(prId).stream()
                .map(t -> new PrTimelineEvent(
                        t.getGithubId(),
                        t.getEvent(),
                        t.getActor(),
                        t.getState(),
                        t.getTimestamp(),
                        t.getBody(),
                        t.getBeforeSha(),
                        t.getAfterSha(),
                        t.getRequestedReviewer(),
                        t.getReviewId(),
                        t.getAuthorAssociation(),
                        new Reactions(
                                t.getReactionsPlusOne(),
                                t.getReactionsMinusOne(),
                                t.getReactionsLaugh(),
                                t.getReactionsHooray(),
                                t.getReactionsConfused(),
                                t.getReactionsHeart(),
                                t.getReactionsRocket(),
                                t.getReactionsEyes())))
                .collect(toImmutableList());

        List<PrCheckRunState> checkRuns = checkRunRepo.findByPrId(prId).stream()
                .map(c -> new PrCheckRunState(
                        c.getGithubId(),
                        c.getName(),
                        c.getStatus(),
                        c.getConclusion(),
                        c.getHtmlUrl(),
                        c.getOutputTitle(),
                        c.getOutputSummary()))
                .collect(toImmutableList());

        List<PrReviewThreadMessage> reviewComments = reviewCommentRepo.findByPrIdOrderByCreatedAtAsc(prId).stream()
                .map(m -> new PrReviewThreadMessage(
                        m.getGithubId(),
                        m.getInReplyTo(),
                        m.getReviewId(),
                        m.getAuthor(),
                        m.getBody(),
                        m.getFilePath(),
                        m.getLineNumber(),
                        m.getSide(),
                        m.getDiffHunk(),
                        m.getCommitId(),
                        m.getCreatedAt(),
                        new Reactions(
                                m.getReactionsPlusOne(),
                                m.getReactionsMinusOne(),
                                m.getReactionsLaugh(),
                                m.getReactionsHooray(),
                                m.getReactionsConfused(),
                                m.getReactionsHeart(),
                                m.getReactionsRocket(),
                                m.getReactionsEyes()),
                        m.isOutdated(),
                        m.getStartLine(),
                        m.getStartSide(),
                        m.getOriginalLine(),
                        m.getOriginalStartLine(),
                        m.getAuthorAssociation(),
                        m.getGraphqlNodeId(),
                        m.getResolved()))
                .collect(toImmutableList());

        List<PullRequestDetail.LinkedIssue> linkedIssues = linkedIssueRepo.findByPrIdOrderByIssueNumberAsc(prId).stream()
                .map(li -> new PullRequestDetail.LinkedIssue(
                        li.getIssueNumber(), li.getTitle(), li.getState(), li.getHtmlUrl()))
                .collect(toImmutableList());

        return Optional.of(new StoredPrDetail(
                raw, reviews, files, timeline, checkRuns, reviewComments, linkedIssues,
                entityOpt.get().getMergeQueueState()));
    }

    @Override
    @Transactional
    public void save(long prId, StoredPrDetail detail)
    {
        Instant now = Instant.now();
        detailRepo.save(populateDetail(
                detailRepo.findById(prId).orElseGet(PrDetailEntity::new),
                prId, detail.raw(), detail.mergeQueueState(), now));

        reviewRepo.deleteByPrId(prId);
        reviewRepo.saveAll(detail.reviews().stream()
                .map(r -> toReview(prId, r))
                .collect(toImmutableList()));

        fileRepo.deleteByPrId(prId);
        fileRepo.saveAll(detail.files().stream()
                .map(f -> toFile(prId, f))
                .collect(toImmutableList()));

        timelineRepo.deleteByPrId(prId);
        timelineRepo.saveAll(detail.timeline().stream()
                .map(t -> toTimeline(prId, t))
                .collect(toImmutableList()));

        checkRunRepo.deleteByPrId(prId);
        checkRunRepo.saveAll(detail.checkRuns().stream()
                .map(c -> toCheckRun(prId, c))
                .collect(toImmutableList()));

        // Defensive dedupe: GitHub's /pulls/:n/comments occasionally
        // returns the same comment id on adjacent pages when comments
        // are being created concurrently with our walk. Without this,
        // the unique (pr_id, github_id) constraint would blow up the
        // entire detail save and the renderer hangs waiting for a
        // response that never comes.
        reviewCommentRepo.deleteByPrId(prId);
        Set<Long> seenGithubIds = new HashSet<>();
        reviewCommentRepo.saveAll(detail.reviewComments().stream()
                .filter(m -> seenGithubIds.add(m.githubId()))
                .map(m -> toReviewComment(prId, m))
                .collect(toImmutableList()));

        linkedIssueRepo.deleteByPrId(prId);
        List<PullRequestDetail.LinkedIssue> linkedIssues = detail.linkedIssues() != null
                ? detail.linkedIssues() : ImmutableList.of();
        linkedIssueRepo.saveAll(linkedIssues.stream()
                .map(li -> toLinkedIssue(prId, li))
                .collect(toImmutableList()));
    }

    /**
     * Append-only save for incremental syncs. The fan-out fetcher passes
     * the previous {@code synced_at} as GitHub's `since=` so the response
     * carries only NEW timeline events / review-thread messages. We then
     * insert just the rows whose {@code github_id} isn't already present.
     * Reviews / files / check-runs / linked-issues fall back to a full
     * replace because GitHub doesn't expose an incremental endpoint for
     * them.
     */
    @Override
    @Transactional
    public void saveIncremental(long prId, StoredPrDetail detail)
    {
        // 1. Refresh the wholesale-replace bits (raw, reviews, files,
        //    check-runs, linked-issues). These are small lists where a
        //    full re-write is faster than diff logic.
        Instant now = Instant.now();
        detailRepo.save(populateDetail(
                detailRepo.findById(prId).orElseGet(PrDetailEntity::new),
                prId, detail.raw(), detail.mergeQueueState(), now));

        reviewRepo.deleteByPrId(prId);
        reviewRepo.saveAll(detail.reviews().stream()
                .map(r -> toReview(prId, r))
                .collect(toImmutableList()));

        fileRepo.deleteByPrId(prId);
        fileRepo.saveAll(detail.files().stream()
                .map(f -> toFile(prId, f))
                .collect(toImmutableList()));

        checkRunRepo.deleteByPrId(prId);
        checkRunRepo.saveAll(detail.checkRuns().stream()
                .map(c -> toCheckRun(prId, c))
                .collect(toImmutableList()));

        linkedIssueRepo.deleteByPrId(prId);
        List<PullRequestDetail.LinkedIssue> linkedIssues = detail.linkedIssues() != null
                ? detail.linkedIssues() : ImmutableList.of();
        linkedIssueRepo.saveAll(linkedIssues.stream()
                .map(li -> toLinkedIssue(prId, li))
                .collect(toImmutableList()));

        // 2. Append-only for timeline events. Read existing github_ids,
        //    filter the fresh list to genuinely-new rows, insert.
        Set<Long> existingTimelineIds =
                new HashSet<>(timelineRepo.findGithubIdsByPrId(prId));
        timelineRepo.saveAll(detail.timeline().stream()
                .filter(t -> t.githubId() == null || !existingTimelineIds.contains(t.githubId()))
                .map(t -> toTimeline(prId, t))
                .collect(toImmutableList()));

        // 3. Append-only for review-thread messages.
        Set<Long> existingThreadIds =
                new HashSet<>(reviewCommentRepo.findGithubIdsByPrId(prId));
        Set<Long> seenInBatch = new HashSet<>();
        reviewCommentRepo.saveAll(detail.reviewComments().stream()
                .filter(m -> seenInBatch.add(m.githubId()))
                .filter(m -> !existingThreadIds.contains(m.githubId()))
                .map(m -> toReviewComment(prId, m))
                .collect(toImmutableList()));
    }

    // ── Domain → Entity mappers ────────────────────────────────────────
    // Centralised here so {@link #save} (full replace) and
    // {@link #saveIncremental} (append-only for timeline / messages,
    // wholesale for the rest) share one source of truth for entity
    // population. Adding a column means touching one helper, not two
    // write paths.

    private static PrDetailEntity populateDetail(
            PrDetailEntity entity, long prId, PrRawDetail raw, String mergeQueueState, Instant syncedAt)
    {
        entity.setPrId(prId);
        entity.setBody(raw.body());
        entity.setLabels(raw.labels() != null ? raw.labels() : ImmutableList.of());
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
        entity.setHeadRepo(raw.headRepo());
        entity.setBaseRef(raw.baseRef());
        entity.setBaseRepo(raw.baseRepo());
        entity.setMergeQueueState(mergeQueueState);
        entity.setSyncedAt(syncedAt);
        return entity;
    }

    private static PrReviewEntity toReview(long prId, PrReviewState r)
    {
        PrReviewEntity e = new PrReviewEntity();
        e.setPrId(prId);
        e.setLogin(r.login());
        e.setState(r.state());
        e.setSubmittedAt(r.submittedAt());
        return e;
    }

    private static PrFileEntity toFile(long prId, PullRequestDetail.ChangedFile f)
    {
        PrFileEntity e = new PrFileEntity();
        e.setPrId(prId);
        e.setFilename(f.filename());
        e.setAdditions(f.additions());
        e.setDeletions(f.deletions());
        e.setStatus(f.status());
        return e;
    }

    private static PrCheckRunEntity toCheckRun(long prId, PrCheckRunState c)
    {
        PrCheckRunEntity e = new PrCheckRunEntity();
        e.setPrId(prId);
        e.setGithubId(c.githubId());
        e.setName(c.name());
        e.setStatus(c.status());
        e.setConclusion(c.conclusion());
        e.setHtmlUrl(c.htmlUrl());
        e.setOutputTitle(c.outputTitle());
        e.setOutputSummary(c.outputSummary());
        return e;
    }

    private static PrLinkedIssueEntity toLinkedIssue(long prId, PullRequestDetail.LinkedIssue li)
    {
        PrLinkedIssueEntity e = new PrLinkedIssueEntity();
        e.setPrId(prId);
        e.setIssueNumber(li.number());
        e.setTitle(li.title());
        e.setState(li.state());
        e.setHtmlUrl(li.htmlUrl());
        return e;
    }

    private static PrTimelineEntity toTimeline(long prId, PrTimelineEvent t)
    {
        PrTimelineEntity e = new PrTimelineEntity();
        e.setPrId(prId);
        e.setGithubId(t.githubId());
        e.setEvent(t.event());
        e.setActor(t.actor());
        e.setState(t.state());
        e.setTimestamp(t.timestamp());
        e.setBody(t.body());
        e.setBeforeSha(t.beforeSha());
        e.setAfterSha(t.afterSha());
        e.setRequestedReviewer(t.requestedReviewer());
        e.setReviewId(t.reviewId());
        e.setAuthorAssociation(t.authorAssociation());
        Reactions r = t.reactions() != null ? t.reactions() : Reactions.EMPTY;
        e.setReactionsPlusOne(r.plusOne());
        e.setReactionsMinusOne(r.minusOne());
        e.setReactionsLaugh(r.laugh());
        e.setReactionsHooray(r.hooray());
        e.setReactionsConfused(r.confused());
        e.setReactionsHeart(r.heart());
        e.setReactionsRocket(r.rocket());
        e.setReactionsEyes(r.eyes());
        return e;
    }

    private static PrReviewThreadMessageEntity toReviewComment(long prId, PrReviewThreadMessage m)
    {
        PrReviewThreadMessageEntity e = new PrReviewThreadMessageEntity();
        e.setPrId(prId);
        e.setGithubId(m.githubId());
        e.setInReplyTo(m.inReplyTo());
        e.setReviewId(m.reviewId());
        e.setAuthor(m.author());
        e.setBody(m.body());
        e.setFilePath(m.filePath());
        e.setLineNumber(m.lineNumber());
        e.setSide(m.side());
        e.setStartLine(m.startLine());
        e.setStartSide(m.startSide());
        e.setOriginalLine(m.originalLine());
        e.setOriginalStartLine(m.originalStartLine());
        e.setDiffHunk(m.diffHunk());
        e.setCommitId(m.commitId());
        e.setCreatedAt(m.createdAt());
        Reactions r = m.reactions() != null ? m.reactions() : Reactions.EMPTY;
        e.setReactionsPlusOne(r.plusOne());
        e.setReactionsMinusOne(r.minusOne());
        e.setReactionsLaugh(r.laugh());
        e.setReactionsHooray(r.hooray());
        e.setReactionsConfused(r.confused());
        e.setReactionsHeart(r.heart());
        e.setReactionsRocket(r.rocket());
        e.setReactionsEyes(r.eyes());
        e.setOutdated(m.outdated());
        e.setAuthorAssociation(m.authorAssociation());
        e.setGraphqlNodeId(m.graphqlNodeId());
        e.setResolved(m.resolved());
        return e;
    }

    @Override
    @Transactional
    public void deleteByPrIds(Set<Long> prIds)
    {
        if (prIds.isEmpty()) {
            return;
        }
        detailRepo.deleteByPrIdIn(prIds);
        for (Long prId : prIds) {
            reviewRepo.deleteByPrId(prId);
            fileRepo.deleteByPrId(prId);
            timelineRepo.deleteByPrId(prId);
            checkRunRepo.deleteByPrId(prId);
            reviewCommentRepo.deleteByPrId(prId);
            linkedIssueRepo.deleteByPrId(prId);
        }
    }

    @Override
    public Map<String, Integer> openPrNumbersByHeadRef(String repo)
    {
        // Last-write-wins on collisions (same head_ref appearing on
        // multiple open PRs). The kanban only needs one number per
        // branch — for the typical "user has one fork per watched
        // repo" case this is unambiguous.
        return detailRepo.findOpenHeadRefsForRepo(repo).stream()
                .collect(Collectors.toMap(
                        HeadRefRow::headRef,
                        HeadRefRow::number,
                        (a, b) -> a));
    }

    @Override
    public Optional<Long> findPrIdByIssueCommentId(long commentId)
    {
        return timelineRepo.findPrIdsByCommentedEventGithubId(commentId).stream().findFirst();
    }

    @Override
    public Optional<Long> findPrIdByReviewCommentId(long commentId)
    {
        return reviewCommentRepo.findPrIdsByGithubId(commentId).stream().findFirst();
    }

    @Override
    public List<Long> findPrIdsMissingReviewTimestamps(int limit)
    {
        if (limit <= 0) {
            return List.of();
        }
        return reviewRepo.findDistinctPrIdsWithNullSubmittedAt(firstPage(limit));
    }
}
