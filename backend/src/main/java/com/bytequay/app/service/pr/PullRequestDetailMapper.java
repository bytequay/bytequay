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

import com.bytequay.app.domain.GithubReviewState;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.StoredPrDetail;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class PullRequestDetailMapper
{
    private static final Set<String> INTERESTING_EVENTS = ImmutableSet.of(
            "committed", "reviewed", "review_requested", "commented", "merged", "closed", "reopened",
            "head_ref_force_pushed", "added_to_merge_queue", "removed_from_merge_queue");

    private PullRequestDetailMapper() {}

    static PullRequestDetail toPullRequestDetail(String repo, int number, StoredPrDetail stored, boolean viewerCanWrite)
    {
        PrRawDetail raw = stored.raw();
        return new PullRequestDetail(
                repo,
                number,
                raw.body(),
                raw.labels(),
                raw.draft(),
                raw.mergeable(),
                raw.mergeableState(),
                raw.additions(),
                raw.deletions(),
                raw.changedFiles(),
                countApprovals(stored.reviews()),
                countChangesRequested(stored.reviews()),
                raw.requestedReviewerCount(),
                raw.requestedReviewers() != null ? raw.requestedReviewers() : ImmutableList.of(),
                aggregateCiStatus(stored.checkRuns()),
                stored.files(),
                toActivityItems(stored.timeline()),
                toCheckRuns(stored.checkRuns()),
                groupReviewThreads(stored.reviewComments()),
                stored.linkedIssues() != null ? stored.linkedIssues() : ImmutableList.of(),
                viewerCanWrite,
                raw.headRef(),
                raw.headRepo(),
                raw.baseRef(),
                raw.baseRepo(),
                stored.mergeQueueState(),
                raw.state(),
                raw.merged(),
                stored.mergeQueueEnabled());
    }

    /**
     * Groups a flat list of GitHub per-line review comments into threads.
     * Each top-level comment ({@code inReplyTo == null}) seeds a thread;
     * replies attach to the root identified by {@code inReplyTo}.
     */
    static List<PullRequestDetail.ReviewThread> groupReviewThreads(List<PrReviewThreadMessage> flat)
    {
        if (flat == null || flat.isEmpty()) {
            return ImmutableList.of();
        }

        LinkedHashMap<Long, PrReviewThreadMessage> rootById = Maps.newLinkedHashMap();
        Map<Long, List<PrReviewThreadMessage>> repliesByRoot = Maps.newHashMap();
        for (PrReviewThreadMessage message : flat) {
            if (message.inReplyTo() == null) {
                rootById.put(message.githubId(), message);
                repliesByRoot.computeIfAbsent(message.githubId(), key -> Lists.newArrayList());
            }
        }
        for (PrReviewThreadMessage message : flat) {
            if (message.inReplyTo() != null) {
                repliesByRoot.computeIfAbsent(message.inReplyTo(), key -> Lists.newArrayList()).add(message);
            }
        }

        List<PullRequestDetail.ReviewThread> threads = Lists.newArrayList();
        for (PrReviewThreadMessage root : rootById.values()) {
            List<PrReviewThreadMessage> replies = repliesByRoot.getOrDefault(root.githubId(), ImmutableList.of()).stream()
                    .sorted((left, right) -> {
                        Instant leftTime = left.createdAt() != null ? left.createdAt() : Instant.EPOCH;
                        Instant rightTime = right.createdAt() != null ? right.createdAt() : Instant.EPOCH;
                        return leftTime.compareTo(rightTime);
                    })
                    .toList();
            List<PullRequestDetail.ReviewMessage> messages = Lists.newArrayList();
            messages.add(new PullRequestDetail.ReviewMessage(
                    root.githubId(), root.author(), root.body(), root.createdAt(),
                    root.reactions() != null ? root.reactions() : Reactions.EMPTY,
                    root.reviewId(),
                    root.authorAssociation()));
            for (PrReviewThreadMessage reply : replies) {
                messages.add(new PullRequestDetail.ReviewMessage(
                        reply.githubId(), reply.author(), reply.body(), reply.createdAt(),
                        reply.reactions() != null ? reply.reactions() : Reactions.EMPTY,
                        reply.reviewId(),
                        reply.authorAssociation()));
            }
            threads.add(new PullRequestDetail.ReviewThread(
                    root.githubId(),
                    root.filePath(),
                    root.lineNumber(),
                    root.side(),
                    root.diffHunk(),
                    ImmutableList.copyOf(messages),
                    root.resolved(),
                    root.outdated(),
                    root.startLine(),
                    root.startSide(),
                    root.originalLine(),
                    root.originalStartLine()));
        }

        threads.sort((left, right) -> {
            PrReviewThreadMessage leftRoot = rootById.get(left.rootGithubId());
            PrReviewThreadMessage rightRoot = rootById.get(right.rootGithubId());
            Instant leftTime = leftRoot != null && leftRoot.createdAt() != null ? leftRoot.createdAt() : Instant.EPOCH;
            Instant rightTime = rightRoot != null && rightRoot.createdAt() != null ? rightRoot.createdAt() : Instant.EPOCH;
            return rightTime.compareTo(leftTime);
        });
        return ImmutableList.copyOf(threads);
    }

    /**
     * Keeps one row per check name. The first occurrence wins, matching
     * GitHub's most-recent-attempt ordering for re-runs and matrix retries.
     */
    static List<PrCheckRunState> dedupeCheckRunsByName(List<PrCheckRunState> checkRuns)
    {
        Map<String, PrCheckRunState> latestByName = Maps.newLinkedHashMap();
        for (int i = 0; i < checkRuns.size(); i++) {
            PrCheckRunState checkRun = checkRuns.get(i);
            String key = checkRun.name() == null || checkRun.name().isBlank() ? "__anonymous__" + i : checkRun.name();
            latestByName.putIfAbsent(key, checkRun);
        }
        return ImmutableList.copyOf(latestByName.values());
    }

    static List<PullRequestDetail.CheckRun> toCheckRuns(List<PrCheckRunState> checkRuns)
    {
        return dedupeCheckRunsByName(checkRuns).stream()
                .map(checkRun -> new PullRequestDetail.CheckRun(
                        checkRun.githubId(),
                        checkRun.name(),
                        checkRun.status(),
                        checkRun.conclusion(),
                        checkRun.htmlUrl(),
                        checkRun.outputTitle(),
                        checkRun.outputSummary()))
                .collect(toImmutableList());
    }

    static int countApprovals(List<PrReviewState> reviews)
    {
        return (int) reviews.stream().filter(review -> GithubReviewState.APPROVED.equals(review.state())).count();
    }

    static int countChangesRequested(List<PrReviewState> reviews)
    {
        return (int) reviews.stream().filter(review -> GithubReviewState.CHANGES_REQUESTED.equals(review.state())).count();
    }

    static PullRequestDetail.CiStatus aggregateCiStatus(List<PrCheckRunState> checkRuns)
    {
        // Aggregate over the deduped latest-per-name view so a check
        // that failed in attempt 1 but passed in a re-run doesn't keep
        // the whole PR in FAILING state.
        List<PrCheckRunState> latest = dedupeCheckRunsByName(checkRuns);
        if (latest.isEmpty()) {
            return PullRequestDetail.CiStatus.NONE;
        }
        boolean anyFailed = latest.stream()
                .anyMatch(checkRun -> "failure".equals(checkRun.conclusion()) || "cancelled".equals(checkRun.conclusion()));
        if (anyFailed) {
            return PullRequestDetail.CiStatus.FAILING;
        }
        boolean anyPending = latest.stream()
                .anyMatch(checkRun -> "in_progress".equals(checkRun.status()) || "queued".equals(checkRun.status()));
        if (anyPending) {
            return PullRequestDetail.CiStatus.PENDING;
        }
        return PullRequestDetail.CiStatus.PASSING;
    }

    static List<PullRequestDetail.ActivityItem> toActivityItems(List<PrTimelineEvent> timeline)
    {
        return timeline.stream()
                .filter(event -> INTERESTING_EVENTS.contains(event.event()))
                .sorted((left, right) -> {
                    Instant leftTime = left.timestamp() != null ? left.timestamp() : Instant.EPOCH;
                    Instant rightTime = right.timestamp() != null ? right.timestamp() : Instant.EPOCH;
                    return rightTime.compareTo(leftTime);
                })
                .map(event -> new PullRequestDetail.ActivityItem(
                        event.actor(),
                        event.event(),
                        event.timestamp(),
                        event.body(),
                        event.state() != null ? event.state().toUpperCase(Locale.ROOT) : null,
                        event.beforeSha(),
                        event.afterSha(),
                        event.requestedReviewer(),
                        event.reviewId(),
                        event.authorAssociation(),
                        event.githubId(),
                        event.reactions() != null ? event.reactions() : Reactions.EMPTY))
                .collect(toImmutableList());
    }

    static Instant latestPushAt(List<PrTimelineEvent> timeline)
    {
        if (timeline == null) {
            return null;
        }
        Instant latest = null;
        for (PrTimelineEvent event : timeline) {
            if (!"committed".equals(event.event()) || event.timestamp() == null) {
                continue;
            }
            if (latest == null || event.timestamp().isAfter(latest)) {
                latest = event.timestamp();
            }
        }
        return latest;
    }

    static Map<String, String> rolledUpReviewerVerdicts(List<PrReviewState> reviews)
    {
        if (reviews == null || reviews.isEmpty()) {
            return ImmutableMap.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (PrReviewState review : reviews) {
            if (review.login() == null || review.state() == null) {
                continue;
            }
            String state = review.state();
            if (out.containsKey(review.login()) && !isStickyVerdict(state)) {
                continue;
            }
            out.put(review.login(), state);
        }
        return ImmutableMap.copyOf(out);
    }

    private static boolean isStickyVerdict(String state)
    {
        return GithubReviewState.APPROVED.equals(state)
                || GithubReviewState.CHANGES_REQUESTED.equals(state)
                || GithubReviewState.DISMISSED.equals(state);
    }
}
