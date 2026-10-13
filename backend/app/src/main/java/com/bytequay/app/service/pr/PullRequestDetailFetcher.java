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

import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReaction;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.getDone;
import static com.google.common.util.concurrent.Futures.getUnchecked;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.submit;
import static com.google.common.util.concurrent.Futures.whenAllComplete;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/**
 * Fetches pull-request discussion sources concurrently on the IO executor,
 * preserving the metadata and checks refreshed separately by the caller.
 */
@Component
class PullRequestDetailFetcher
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestDetailFetcher.class);

    private final GitHubPullRequestReadClient gitHub;
    private final Executor ioExecutor;

    PullRequestDetailFetcher(
            GitHubPullRequestReadClient gitHub,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    PrRawDetail raw(String pat, PullRequestRef ref, PullRequestFetch fetched)
    {
        return requireRaw(fetched.detailResponse().map(response -> gitHub.toPrRawDetail(pat, ref, response)).orElseGet(() -> gitHub.fetchPrDetail(pat, ref)));
    }

    ListenableFuture<StoredPrDetail> fetchDiscussion(String pat, PullRequestRef ref, StoredPrDetail existing)
    {
        PrRawDetail raw = existing.raw();
        ListenableFuture<SourceItems<PrReviewState>> reviews = fetchOptionalListAsync(
                "fetchPrReviews", StoredPrDetail.REVIEWS, ref, () -> gitHub.fetchPrReviews(pat, ref));
        ListenableFuture<SourceItems<PrTimelineEvent>> timeline = fetchOptionalListAsync(
                "fetchPrTimeline", StoredPrDetail.TIMELINE, ref, () -> gitHub.fetchPrTimeline(pat, ref));
        ListenableFuture<SourceItems<PrTimelineEvent>> comments = fetchOptionalListAsync(
                "fetchPrIssueComments", StoredPrDetail.COMMENTS, ref, () -> gitHub.fetchPrIssueComments(pat, ref));
        // ponytail: Initial hydration also fetches diff commits for their parent SHAs; subsequent discussions reuse these immutable events.
        ListenableFuture<SourceItems<PrTimelineEvent>> commits = !existing.unavailableSections().contains(StoredPrDetail.COMMITS)
                ? immediateFuture(new SourceItems<>(existing.timeline().stream().filter(event -> "committed".equals(event.event())).collect(toImmutableList()), null))
                : fetchOptionalListAsync("fetchPrCommits", StoredPrDetail.COMMITS, ref, () -> gitHub.fetchPrCommits(pat, ref));
        ListenableFuture<SourceItems<PrTimelineEvent>> threads = fetchOptionalListAsync(
                "fetchPrReviewThreads", StoredPrDetail.REVIEW_THREADS, ref, () -> gitHub.fetchPrReviewThreads(pat, ref));
        ListenableFuture<SourceItems<PrReaction>> descriptionReactions = fetchOptionalListAsync(
                "fetchPrDescriptionReactions", StoredPrDetail.REACTIONS, ref, () -> gitHub.fetchPrDescriptionReactions(pat, ref));
        ListenableFuture<SourceItems<PrCheckRunState>> checks = immediateFuture(
                new SourceItems<>(existing.checkRuns(), existing.checkRunsComplete() ? null : StoredPrDetail.CHECKS));

        // Not whenAllSucceed: during a rate-limit pause every source fails alike, and it logs each later failure as an error with a stack trace.
        return whenAllComplete(reviews, timeline, comments, commits, threads, descriptionReactions, checks)
                .call(() -> new StoredPrDetail(
                                raw,
                                getDone(reviews).items(),
                                gitHub.enrichTimelineReferences(pat, ref, mergeTimeline(
                                        getDone(timeline).items(),
                                        getDone(comments).items(),
                                        getDone(commits).items(),
                                        getDone(threads).items(),
                                        getDone(descriptionReactions).items())),
                                getDone(checks).items(),
                                unavailable(reviews, timeline, comments, commits, threads, descriptionReactions, checks),
                                existing.viewerCanWrite()),
                        directExecutor());
    }

    private static PrRawDetail requireRaw(PrRawDetail raw)
    {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub PR detail");
        }
        return raw;
    }

    // Unwrap so a ResponseStatusException from a fetch keeps its HTTP status
    // instead of surfacing as UncheckedExecutionException and mapping to 500.
    static <T> T await(ListenableFuture<T> future)
    {
        try {
            return getUnchecked(future);
        }
        catch (UncheckedExecutionException e) {
            throwIfUnchecked(e.getCause());
            throw e;
        }
    }

    private <T> ListenableFuture<SourceItems<T>> fetchOptionalListAsync(
            String name,
            String section,
            PullRequestRef ref,
            Supplier<List<T>> task)
    {
        return submit(() -> {
            try {
                return new SourceItems<>(requireNonNullElse(task.get(), ImmutableList.of()), null);
            }
            catch (RuntimeException e) {
                if (isRateLimit(e)) {
                    throw e;
                }
                log.warn("{}({}#{}) unavailable: {}", name, ref.repoFullName(), ref.number(), e.toString());
                return new SourceItems<>(ImmutableList.of(), section);
            }
        }, ioExecutor);
    }

    /**
     * The timeline and issue-comment endpoints both report issue comments;
     * where both returned the same comment, keep the issue-comment copy.
     */
    private static List<PrTimelineEvent> mergeTimeline(
            List<PrTimelineEvent> timeline,
            List<PrTimelineEvent> comments,
            List<PrTimelineEvent> commits,
            List<PrTimelineEvent> threads,
            List<PrReaction> descriptionReactions)
    {
        Set<String> commentKeys = comments.stream()
                .map(PullRequestDetailFetcher::commentKey)
                .filter(Objects::nonNull)
                .collect(toImmutableSet());
        List<PrTimelineEvent> base = Stream.concat(
                        timeline.stream().filter(event -> !"commented".equals(event.event()) || !commentKeys.contains(commentKey(event))),
                        comments.stream())
                .collect(toImmutableList());
        Set<String> commitShas = base.stream()
                .map(event -> event.payload().commitSha())
                .filter(Objects::nonNull)
                .collect(toImmutableSet());
        Stream<PrTimelineEvent> events = Stream.of(
                        base.stream(),
                        commits.stream().filter(event -> !commitShas.contains(event.payload().commitSha())),
                        threads.stream())
                .flatMap(stream -> stream);
        if (!descriptionReactions.isEmpty()) {
            events = Stream.concat(events, Stream.of(new PrTimelineEvent(
                    null,
                    "description_reactions",
                    null,
                    null,
                    null,
                    PrTimelinePayload.builder().reactions(descriptionReactions).build(),
                    java.util.Optional.empty())));
        }
        return events
                .collect(toImmutableList());
    }

    @SafeVarargs
    private static List<String> unavailable(ListenableFuture<? extends SourceItems<?>>... futures)
            throws Exception
    {
        List<String> sections = new ArrayList<>();
        for (ListenableFuture<? extends SourceItems<?>> future : futures) {
            String section = getDone(future).unavailableSection();
            if (section != null) {
                sections.add(section);
            }
        }
        return ImmutableList.copyOf(sections);
    }

    private static String commentKey(PrTimelineEvent event)
    {
        return event.actor() == null || event.timestamp() == null
                ? null
                : event.actor() + "@" + event.timestamp();
    }

    private record SourceItems<T>(List<T> items, String unavailableSection) {}
}
