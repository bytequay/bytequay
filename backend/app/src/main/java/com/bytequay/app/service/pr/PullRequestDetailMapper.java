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

import com.bytequay.core.domain.GithubReviewState;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.StoredPrDetail;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class PullRequestDetailMapper
{
    private static final java.util.Set<String> FAILED_CHECK_CONCLUSIONS = ImmutableSet.of(
            "failure", "timed_out", "cancelled", "action_required", "startup_failure");

    private PullRequestDetailMapper() {}

    static PullRequestDetail toPullRequestDetail(
            long id, String repo, int number, StoredPrDetail stored, boolean viewerCanWrite)
    {
        PrRawDetail raw = stored.raw();
        return new PullRequestDetail(
                id,
                repo,
                number,
                raw.title(),
                raw.htmlUrl(),
                raw.state(),
                raw.draft(),
                raw.mergeable(),
                raw.mergeableState(),
                raw.author(),
                raw.authorAvatarUrl(),
                raw.createdAt(),
                raw.updatedAt(),
                raw.mergedAt(),
                raw.baseRef(),
                raw.headRef(),
                raw.body(),
                raw.labels().stream()
                        .map(label -> new PullRequestDetail.Label(label, raw.labelColors().get(label)))
                        .collect(toImmutableList()),
                raw.assignees(),
                raw.milestone(),
                reviewers(raw, stored.reviews()),
                raw.additions(),
                raw.deletions(),
                raw.changedFiles(),
                countApprovals(stored.reviews()),
                aggregateCiStatus(stored.checkRuns()),
                toActivityItems(stored.timeline()),
                toCheckRuns(stored.checkRuns()),
                stored.unavailableSections(),
                viewerCanWrite);
    }

    static List<PrCheckRunState> dedupeCheckRunsByName(List<PrCheckRunState> checkRuns)
    {
        Map<String, PrCheckRunState> latestByName = new LinkedHashMap<>();
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
                        checkRun.detailsUrl(),
                        checkRun.source(),
                        checkCategory(checkRun),
                        checkRun.completedAt()))
                .collect(toImmutableList());
    }

    static int countApprovals(List<PrReviewState> reviews)
    {
        return (int) latestReviews(reviews).values().stream()
                .filter(review -> GithubReviewState.APPROVED.equals(review.state()))
                .count();
    }

    static PullRequestDetail.CiStatus aggregateCiStatus(List<PrCheckRunState> checkRuns)
    {
        List<PrCheckRunState> latest = dedupeCheckRunsByName(checkRuns);
        if (latest.isEmpty()) {
            return PullRequestDetail.CiStatus.NONE;
        }
        if (latest.stream().anyMatch(checkRun -> checkRun.conclusion() != null
                && FAILED_CHECK_CONCLUSIONS.contains(checkRun.conclusion()))) {
            return PullRequestDetail.CiStatus.FAILING;
        }
        if (latest.stream().anyMatch(checkRun ->
                "in_progress".equals(checkRun.status()) || "queued".equals(checkRun.status()))) {
            return PullRequestDetail.CiStatus.PENDING;
        }
        return PullRequestDetail.CiStatus.PASSING;
    }

    static List<PullRequestDetail.ActivityItem> toActivityItems(List<PrTimelineEvent> timeline)
    {
        return timeline.stream()
                .sorted(Comparator.comparing(PullRequestDetailMapper::time)
                        .thenComparing(event -> Optional.ofNullable(event.githubId()).orElse(Long.MAX_VALUE)))
                .map(event -> new PullRequestDetail.ActivityItem(
                        event.actor(),
                        event.event(),
                        event.timestamp(),
                        event.body(),
                        event.githubId(),
                        event.payload()))
                .collect(toImmutableList());
    }

    static Instant latestPushAt(List<PrTimelineEvent> timeline)
    {
        if (timeline == null) {
            return null;
        }
        return timeline.stream()
                .filter(event -> "committed".equals(event.event()) && event.timestamp() != null)
                .map(PrTimelineEvent::timestamp)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private static List<PullRequestDetail.Reviewer> reviewers(
            PrRawDetail raw,
            List<PrReviewState> reviews)
    {
        Map<String, PrReviewState> latest = latestReviews(reviews);
        Map<String, PullRequestDetail.Reviewer> out = new LinkedHashMap<>();
        for (String login : raw.requestedReviewers()) {
            out.put(login, new PullRequestDetail.Reviewer(login, null, "PENDING"));
        }
        latest.forEach((login, review) -> out.put(login, new PullRequestDetail.Reviewer(
                login, review.avatarUrl(), review.state())));
        out.remove(raw.author());
        return ImmutableList.copyOf(out.values());
    }

    private static Map<String, PrReviewState> latestReviews(List<PrReviewState> reviews)
    {
        Map<String, PrReviewState> latest = new LinkedHashMap<>();
        reviews.stream()
                .filter(review -> review.login() != null && review.state() != null)
                .sorted(Comparator.comparing(
                        review -> Optional.ofNullable(review.submittedAt()).orElse(Instant.EPOCH)))
                .forEach(review -> latest.put(review.login(), review));
        return latest;
    }

    private static String checkCategory(PrCheckRunState check)
    {
        if ("queued".equals(check.status()) || "in_progress".equals(check.status())) {
            return "pending";
        }
        return switch (Optional.ofNullable(check.conclusion()).orElse("")) {
            case "success" -> "successful";
            case "failure", "timed_out", "action_required", "startup_failure" -> "failing";
            case "cancelled" -> "cancelled";
            case "skipped" -> "skipped";
            case "neutral", "stale" -> "neutral";
            default -> "pending";
        };
    }

    private static Instant time(PrTimelineEvent event)
    {
        return event.timestamp() != null ? event.timestamp() : Instant.EPOCH;
    }
}
