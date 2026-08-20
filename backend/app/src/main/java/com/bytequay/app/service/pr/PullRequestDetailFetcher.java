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

import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReaction;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.getDone;
import static com.google.common.util.concurrent.Futures.getUnchecked;
import static com.google.common.util.concurrent.Futures.submit;
import static com.google.common.util.concurrent.Futures.transformAsync;
import static com.google.common.util.concurrent.Futures.whenAllSucceed;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/**
 * Fetches everything the app stores about a single pull request: the PR itself,
 * reviews, timeline, issue comments, and check runs for the head commit. The
 * independent calls run concurrently on the IO executor; check runs wait only
 * for the PR detail they need.
 */
final class PullRequestDetailFetcher
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestDetailFetcher.class);

    private final GitHubPullRequestReadClient gitHub;
    private final GitHubActionsClient actions;
    private final PrDetailStore detailStore;
    private final Executor ioExecutor;

    PullRequestDetailFetcher(
            GitHubPullRequestReadClient gitHub,
            GitHubActionsClient actions,
            PrDetailStore detailStore,
            Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    StoredPrDetail fetch(String pat, PullRequestRef ref)
    {
        ListenableFuture<PrRawDetail> detail = fetchAsync("fetchPrDetail", ref, () -> gitHub.fetchPrDetail(pat, ref));
        ListenableFuture<SourceItems<PrReviewState>> reviews = fetchOptionalListAsync(
                "fetchPrReviews", "reviews", ref, () -> gitHub.fetchPrReviews(pat, ref));
        ListenableFuture<SourceItems<PrTimelineEvent>> timeline = fetchOptionalListAsync(
                "fetchPrTimeline", "timeline", ref, () -> gitHub.fetchPrTimeline(pat, ref, null));
        ListenableFuture<SourceItems<PrTimelineEvent>> comments = fetchOptionalListAsync(
                "fetchPrIssueComments", "comments", ref, () -> gitHub.fetchPrIssueComments(pat, ref, null));
        ListenableFuture<SourceItems<PrTimelineEvent>> commits = fetchOptionalListAsync(
                "fetchPrCommits", "commits", ref, () -> gitHub.fetchPrCommits(pat, ref));
        ListenableFuture<SourceItems<PrTimelineEvent>> threads = fetchOptionalListAsync(
                "fetchPrReviewThreads", "reviewThreads", ref, () -> gitHub.fetchPrReviewThreads(pat, ref));
        ListenableFuture<SourceItems<PrReaction>> descriptionReactions = fetchOptionalListAsync(
                "fetchPrDescriptionReactions", "reactions", ref, () -> gitHub.fetchPrDescriptionReactions(pat, ref));
        ListenableFuture<SourceItems<PrCheckRunState>> checks = transformAsync(
                detail, raw -> fetchCheckRunsAsync(pat, ref, raw), directExecutor());

        return await(whenAllSucceed(detail, reviews, timeline, comments, commits, threads, descriptionReactions, checks)
                .call(() -> new StoredPrDetail(
                                requireRaw(getDone(detail)),
                                getDone(reviews).items(),
                                gitHub.enrichTimelineReferences(pat, ref, mergeTimeline(
                                        getDone(timeline).items(),
                                        getDone(comments).items(),
                                        getDone(commits).items(),
                                        getDone(threads).items(),
                                        getDone(descriptionReactions).items())),
                                getDone(checks).items(),
                                unavailable(reviews, timeline, comments, commits, threads, descriptionReactions, checks)),
                        directExecutor()));
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
    private static <T> T await(ListenableFuture<T> future)
    {
        try {
            return getUnchecked(future);
        }
        catch (UncheckedExecutionException e) {
            throwIfUnchecked(e.getCause());
            throw e;
        }
    }

    private ListenableFuture<SourceItems<PrCheckRunState>> fetchCheckRunsAsync(
            String pat,
            PullRequestRef ref,
            PrRawDetail detail)
    {
        if (detail == null || detail.headSha() == null) {
            return com.google.common.util.concurrent.Futures.immediateFuture(
                    new SourceItems<>(ImmutableList.of(), null));
        }
        return fetchOptionalListAsync("fetchPrCheckRuns", "checks", ref,
                () -> actions.fetchPrCheckRuns(pat, ref.owner(), ref.repo(), detail.headSha()));
    }

    private <T> ListenableFuture<List<T>> fetchListAsync(String name, PullRequestRef ref, Supplier<List<T>> task)
    {
        return fetchAsync(name, ref, () -> requireNonNullElse(task.get(), ImmutableList.of()));
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
                log.warn("{}({}#{}) unavailable: {}", name, repoFullName(ref), ref.number(), e.toString());
                return new SourceItems<>(ImmutableList.of(), section);
            }
        }, ioExecutor);
    }

    private <T> ListenableFuture<T> fetchAsync(String name, PullRequestRef ref, Supplier<T> task)
    {
        return submit(() -> {
            try {
                return task.get();
            }
            catch (RuntimeException e) {
                log.warn("{}({}#{}) failed: {}", name, repoFullName(ref), ref.number(), e.toString());
                throw e;
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
                    PrTimelinePayload.withReactions(descriptionReactions),
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

    private static String repoFullName(PullRequestRef ref)
    {
        return ref.owner() + "/" + ref.repo();
    }

    private record SourceItems<T>(List<T> items, String unavailableSection) {}
}
