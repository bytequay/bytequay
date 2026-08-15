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
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubOrgAccess;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.sqlite.SqlitePrDetailStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

final class PullRequestDetailFetcher
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestDetailFetcher.class);

    private final GitHubPullRequestReadClient gitHub;
    private final GitHubActionsClient actions;
    private final SqlitePrDetailStore detailStore;
    private final Executor ioExecutor;

    PullRequestDetailFetcher(
            GitHubPullRequestReadClient gitHub,
            GitHubActionsClient actions,
            SqlitePrDetailStore detailStore,
            Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
    }

    StoredPrDetail fetch(String pat, PullRequestRef ref)
    {
        Instant watermark = detailStore.findSyncedAt(repoFullName(ref), ref.number())
                .map(timestamp -> timestamp.minusSeconds(30))
                .orElse(null);
        CompletableFuture<PrRawDetail> raw = timed("fetchPrDetail", ref, () -> gitHub.fetchPrDetail(pat, ref));
        CompletableFuture<List<PrReviewState>> reviews = timed(
                "fetchPrReviews", ref, () -> gitHub.fetchPrReviews(pat, ref));
        CompletableFuture<List<PrTimelineEvent>> timeline = timed(
                "fetchPrTimeline", ref, () -> gitHub.fetchPrTimeline(pat, ref, watermark));
        CompletableFuture<List<PrTimelineEvent>> comments = timed(
                "fetchPrIssueComments", ref, () -> gitHub.fetchPrIssueComments(pat, ref, watermark));
        CompletableFuture<List<PrCheckRunState>> checks = raw.thenCompose(detail -> {
            if (detail == null || detail.headSha() == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            return timed("fetchPrCheckRuns", ref,
                    () -> actions.fetchPrCheckRuns(pat, ref.owner(), ref.repo(), detail.headSha()));
        });

        PrRawDetail rawDetail = requireRaw(raw.join());
        return new StoredPrDetail(
                rawDetail,
                emptyIfNull(reviews.join()),
                mergeIssueComments(
                        emptyIfNull(timeline.join()), emptyIfNull(comments.join())),
                emptyIfNull(checks.join()));
    }

    private <T> CompletableFuture<T> timed(String name, PullRequestRef ref, Supplier<T> task)
    {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.get();
            }
            catch (RuntimeException e) {
                if (GitHubOrgAccess.isClassicPatDenial(e.getMessage())) {
                    log.debug("{}({}#{}) denied: {}", name, repoFullName(ref), ref.number(), e.toString());
                }
                else {
                    log.warn("{}({}#{}) failed: {}", name, repoFullName(ref), ref.number(), e.toString());
                }
                throw e;
            }
        }, ioExecutor);
    }

    private static PrRawDetail requireRaw(PrRawDetail raw)
    {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub PR detail");
        }
        return raw;
    }

    private static <T> List<T> emptyIfNull(List<T> values)
    {
        return values != null ? values : List.of();
    }

    private static List<PrTimelineEvent> mergeIssueComments(
            List<PrTimelineEvent> timeline, List<PrTimelineEvent> comments)
    {
        Set<String> commentKeys = comments.stream()
                .map(PullRequestDetailFetcher::commentKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        return Stream.concat(
                        timeline.stream().filter(event -> !"commented".equals(event.event())
                                || !commentKeys.contains(commentKey(event))),
                        comments.stream())
                .toList();
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
}
