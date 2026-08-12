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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.repository.GitHubActionsRepository;
import com.bytequay.app.repository.GitHubMergeRepository;
import com.bytequay.app.repository.GitHubPullRequestReadRepository;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.github.GitHubOrgAccess;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

final class PullRequestDetailFetcher
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestDetailFetcher.class);

    private final GitHubPullRequestReadRepository gitHub;
    private final GitHubMergeRepository merges;
    private final GitHubActionsRepository actions;
    private final PrDetailStore detailStore;
    private final Executor ioExecutor;

    PullRequestDetailFetcher(
            GitHubPullRequestReadRepository gitHub,
            GitHubMergeRepository merges,
            GitHubActionsRepository actions,
            PrDetailStore detailStore,
            Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.merges = requireNonNull(merges, "merges is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    StoredPrDetail fetch(String pat, PullRequestRef ref)
    {
        long t0 = System.nanoTime();
        String repoFull = repoFullName(ref);
        log.debug("fetchDetailFromGitHub start: {}#{}", repoFull, ref.number());

        Instant watermark = detailFetchWatermark(repoFull, ref);
        if (watermark != null) {
            log.debug("fetchDetailFromGitHub incremental: {}#{} since={}", repoFull, ref.number(), watermark);
        }

        PrDetailFetchResult result = awaitDetailFetches(startDetailFetches(pat, ref, watermark));
        PrRawDetail raw = requireRawDetail(result.raw());
        List<PrReviewState> reviews = emptyIfNull(result.reviews());
        List<PullRequestDetail.ChangedFile> files = emptyIfNull(result.files());
        List<PrTimelineEvent> timeline = emptyIfNull(result.timeline());
        List<PrTimelineEvent> issueComments = emptyIfNull(result.issueComments());
        List<PrCheckRunState> checkRuns = emptyIfNull(result.checkRuns());
        List<PrReviewThreadMessage> reviewComments = attachReviewThreadResolution(
                emptyIfNull(result.reviewComments()),
                emptyIfNull(result.threadResolution()));
        List<PullRequestDetail.LinkedIssue> linkedIssues = emptyIfNull(result.linkedIssues());

        List<PrTimelineEvent> mergedTimeline = PullRequestTimelineUtil.mergeIssueComments(timeline, issueComments);
        GitHubMergeRepository.MergeQueueInfo info = result.mergeQueueInfo();
        if (info == null) {
            info = new GitHubMergeRepository.MergeQueueInfo(false, null);
        }
        logDetailFetchDone(ref, t0, timeline, reviewComments, files, checkRuns, issueComments);

        return new StoredPrDetail(
                raw,
                reviews,
                files,
                mergedTimeline,
                checkRuns,
                reviewComments,
                linkedIssues,
                info.entryState(),
                info.queueConfigured());
    }

    private Instant detailFetchWatermark(String repoFull, PullRequestRef ref)
    {
        // Timestamp of the last successful detail fetch. Endpoints that
        // support `since=` (timeline, issue comments, review comments)
        // only return rows updated after this point, so a quiet PR settles
        // for an empty single-page response per cycle. New PRs get null
        // and use the full-fetch path. The 30-second safety margin guards
        // against GitHub indexing a freshly-created comment just after our
        // previous wall-clock read.
        return detailStore.findSyncedAt(repoFull, ref.number())
                .map(timestamp -> timestamp.minusSeconds(30))
                .orElse(null);
    }

    private PrDetailFetches startDetailFetches(String pat, PullRequestRef ref, Instant watermark)
    {
        // The sub-fetches run on `ioExecutor` (virtual threads), not on
        // the bounded application executor. Parent sync jobs must not
        // compete with their own GitHub children.
        CompletableFuture<PrRawDetail> raw = timed("fetchPrDetail", ref, () -> gitHub.fetchPrDetail(pat, ref));

        return new PrDetailFetches(
                raw,
                timed("fetchPrReviews", ref, () -> gitHub.fetchPrReviews(pat, ref)),
                timed("fetchPrFiles", ref, () -> gitHub.fetchPrFiles(pat, ref)),
                timed("fetchPrTimeline", ref, () -> gitHub.fetchPrTimeline(pat, ref, watermark)),
                timed("fetchPrReviewComments", ref, () -> gitHub.fetchPrReviewComments(pat, ref, watermark)),
                timed("fetchPrIssueComments", ref, () -> gitHub.fetchPrIssueComments(pat, ref, watermark)),
                timed("fetchReviewThreadResolution", ref, () -> fetchReviewThreadResolutionBestEffort(pat, ref)),
                timed("fetchMergeQueueInfo", ref, () -> fetchMergeQueueInfoBestEffort(pat, ref)),
                fetchCheckRunsAfterRawDetail(pat, ref, raw),
                fetchLinkedIssuesAfterRawDetail(pat, ref, raw));
    }

    private CompletableFuture<List<PrCheckRunState>> fetchCheckRunsAfterRawDetail(
            String pat,
            PullRequestRef ref,
            CompletableFuture<PrRawDetail> raw)
    {
        return raw.thenCompose(detail -> {
            if (detail == null || detail.headSha() == null) {
                return CompletableFuture.completedFuture(ImmutableList.of());
            }
            return timed("fetchPrCheckRuns", ref, () -> actions.fetchPrCheckRuns(pat, ref.owner(), ref.repo(), detail.headSha()));
        });
    }

    private CompletableFuture<List<PullRequestDetail.LinkedIssue>> fetchLinkedIssuesAfterRawDetail(
            String pat,
            PullRequestRef ref,
            CompletableFuture<PrRawDetail> raw)
    {
        return raw.thenCompose(detail -> {
            if (detail == null) {
                return CompletableFuture.completedFuture(ImmutableList.of());
            }
            Set<Integer> issueNumbers = PullRequestTimelineUtil.extractClosingReferences(detail.body(), ref.owner(), ref.repo());
            if (issueNumbers.isEmpty()) {
                return CompletableFuture.completedFuture(ImmutableList.of());
            }
            return timed("resolveLinkedIssues", ref, () -> resolveLinkedIssues(pat, ref, issueNumbers));
        });
    }

    private List<GitHubPullRequestReadRepository.ReviewThreadMeta> fetchReviewThreadResolutionBestEffort(
            String pat,
            PullRequestRef ref)
    {
        // GraphQL fetch: REST does not expose review-thread resolution
        // state. Best effort keeps the REST detail usable when GraphQL is
        // rate-limited or unavailable.
        try {
            return gitHub.fetchReviewThreadResolution(pat, ref);
        }
        catch (RuntimeException e) {
            logBestEffortFailure("GraphQL review-thread resolution fetch failed", e);
            return ImmutableList.of();
        }
    }

    private GitHubMergeRepository.MergeQueueInfo fetchMergeQueueInfoBestEffort(String pat, PullRequestRef ref)
    {
        // GraphQL fetch: REST does not expose the per-PR merge-queue entry.
        try {
            return merges.fetchMergeQueueInfo(pat, ref);
        }
        catch (RuntimeException e) {
            logBestEffortFailure("GraphQL merge-queue info fetch failed", e);
            return new GitHubMergeRepository.MergeQueueInfo(false, null);
        }
    }

    /** Warns, except for the org-blocks-classic-PATs denial — that one is
     *  permanent and already reported once by {@link GitHubOrgAccess}. */
    private static void logBestEffortFailure(String what, RuntimeException e)
    {
        if (GitHubOrgAccess.isClassicPatDenial(e.getMessage())) {
            log.debug("{}: {}", what, e.getMessage());
        }
        else {
            log.warn("{}: {}", what, e.getMessage());
        }
    }

    private PrDetailFetchResult awaitDetailFetches(PrDetailFetches fetches)
    {
        return new PrDetailFetchResult(
                join(fetches.raw()),
                join(fetches.reviews()),
                join(fetches.files()),
                join(fetches.timeline()),
                join(fetches.reviewComments()),
                join(fetches.issueComments()),
                join(fetches.threadResolution()),
                join(fetches.mergeQueueInfo()),
                join(fetches.checkRuns()),
                join(fetches.linkedIssues()));
    }

    private static PrRawDetail requireRawDetail(PrRawDetail raw)
    {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub PR detail");
        }
        return raw;
    }

    private static List<PrReviewThreadMessage> attachReviewThreadResolution(
            List<PrReviewThreadMessage> reviewComments,
            List<GitHubPullRequestReadRepository.ReviewThreadMeta> threadResolution)
    {
        // Stitch the GraphQL metadata onto the REST messages. Only the
        // thread root (inReplyTo == null) carries graphqlNodeId +
        // resolved; replies stay null on those fields.
        if (reviewComments.isEmpty() || threadResolution.isEmpty()) {
            return reviewComments;
        }

        Map<Long, GitHubPullRequestReadRepository.ReviewThreadMeta> metaByRootId = new HashMap<>();
        for (GitHubPullRequestReadRepository.ReviewThreadMeta meta : threadResolution) {
            metaByRootId.put(meta.rootCommentDatabaseId(), meta);
        }
        return reviewComments.stream()
                .map(message -> attachReviewThreadResolution(message, metaByRootId))
                .collect(toImmutableList());
    }

    private static PrReviewThreadMessage attachReviewThreadResolution(
            PrReviewThreadMessage message,
            Map<Long, GitHubPullRequestReadRepository.ReviewThreadMeta> metaByRootId)
    {
        if (message.inReplyTo() != null) {
            return message;
        }
        GitHubPullRequestReadRepository.ReviewThreadMeta meta = metaByRootId.get(message.githubId());
        if (meta == null) {
            return message;
        }
        return new PrReviewThreadMessage(
                message.githubId(), message.inReplyTo(), message.reviewId(), message.author(),
                message.body(), message.filePath(), message.lineNumber(), message.side(),
                message.diffHunk(), message.commitId(), message.createdAt(), message.reactions(),
                message.outdated(), message.startLine(), message.startSide(),
                message.originalLine(), message.originalStartLine(),
                message.authorAssociation(),
                meta.graphqlNodeId(),
                meta.resolved(),
                meta.resolvedBy());
    }

    private void logDetailFetchDone(
            PullRequestRef ref,
            long startNanos,
            List<PrTimelineEvent> timeline,
            List<PrReviewThreadMessage> reviewComments,
            List<PullRequestDetail.ChangedFile> files,
            List<PrCheckRunState> checkRuns,
            List<PrTimelineEvent> issueComments)
    {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.debug("fetchDetailFromGitHub done: {}#{} in {}ms -- timeline={} threadMsgs={} files={} checks={} issueComments={}",
                repoFullName(ref), ref.number(), elapsedMs,
                timeline.size(),
                reviewComments.size(),
                files.size(),
                checkRuns.size(),
                issueComments.size());
    }

    private <T> CompletableFuture<T> timed(String name, PullRequestRef ref, Supplier<T> thread)
    {
        return CompletableFuture.supplyAsync(() -> {
            long t = System.nanoTime();
            try {
                T result = thread.get();
                long ms = (System.nanoTime() - t) / 1_000_000;
                log.debug("{}({}#{}) ok in {}ms", name, repoFullName(ref), ref.number(), ms);
                return result;
            }
            catch (RuntimeException e) {
                long ms = (System.nanoTime() - t) / 1_000_000;
                // An org that blocks classic PATs fails every sub-fetch of every
                // PR on every sync cycle; GitHubOrgAccess reports it once.
                if (GitHubOrgAccess.isClassicPatDenial(e.getMessage())) {
                    log.debug("{}({}#{}) denied in {}ms: {}", name, repoFullName(ref), ref.number(), ms, e.toString());
                }
                else {
                    log.warn("{}({}#{}) failed in {}ms: {}", name, repoFullName(ref), ref.number(), ms, e.toString());
                }
                throw e;
            }
        }, ioExecutor);
    }

    private List<PullRequestDetail.LinkedIssue> resolveLinkedIssues(String pat, PullRequestRef ref, Set<Integer> numbers)
    {
        if (numbers.isEmpty()) {
            return ImmutableList.of();
        }
        RepoRef repoRef = new RepoRef(ref.owner(), ref.repo());
        List<CompletableFuture<Optional<PullRequestDetail.LinkedIssue>>> futures = numbers.stream()
                .sorted()
                .map(number -> CompletableFuture.supplyAsync(() -> gitHub.fetchIssue(pat, repoRef, number), ioExecutor))
                .toList();
        List<PullRequestDetail.LinkedIssue> resolved = Lists.newArrayList();
        for (CompletableFuture<Optional<PullRequestDetail.LinkedIssue>> future : futures) {
            Optional<PullRequestDetail.LinkedIssue> value = join(future);
            if (value != null && value.isPresent()) {
                resolved.add(value.get());
            }
        }
        return ImmutableList.copyOf(resolved);
    }

    private static <T> List<T> emptyIfNull(List<T> values)
    {
        return values != null ? values : ImmutableList.of();
    }

    private static String repoFullName(PullRequestRef ref)
    {
        return ref.owner() + "/" + ref.repo();
    }

    private static <T> T join(CompletableFuture<T> future)
    {
        return future.join();
    }

    private record PrDetailFetches(
            CompletableFuture<PrRawDetail> raw,
            CompletableFuture<List<PrReviewState>> reviews,
            CompletableFuture<List<PullRequestDetail.ChangedFile>> files,
            CompletableFuture<List<PrTimelineEvent>> timeline,
            CompletableFuture<List<PrReviewThreadMessage>> reviewComments,
            CompletableFuture<List<PrTimelineEvent>> issueComments,
            CompletableFuture<List<GitHubPullRequestReadRepository.ReviewThreadMeta>> threadResolution,
            CompletableFuture<GitHubMergeRepository.MergeQueueInfo> mergeQueueInfo,
            CompletableFuture<List<PrCheckRunState>> checkRuns,
            CompletableFuture<List<PullRequestDetail.LinkedIssue>> linkedIssues)
    {
    }

    private record PrDetailFetchResult(
            PrRawDetail raw,
            List<PrReviewState> reviews,
            List<PullRequestDetail.ChangedFile> files,
            List<PrTimelineEvent> timeline,
            List<PrReviewThreadMessage> reviewComments,
            List<PrTimelineEvent> issueComments,
            List<GitHubPullRequestReadRepository.ReviewThreadMeta> threadResolution,
            GitHubMergeRepository.MergeQueueInfo mergeQueueInfo,
            List<PrCheckRunState> checkRuns,
            List<PullRequestDetail.LinkedIssue> linkedIssues)
    {
    }
}
