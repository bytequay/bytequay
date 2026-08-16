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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PullRequestDetailMapper
{
    private static final Set<String> INTERESTING_EVENTS = Set.of(
            "committed", "reviewed", "review_requested", "commented", "merged", "closed", "reopened",
            "head_ref_force_pushed", "added_to_merge_queue", "removed_from_merge_queue");
    private static final Set<String> FAILED_CHECK_CONCLUSIONS = Set.of(
            "failure", "timed_out", "cancelled", "action_required", "startup_failure");

    private PullRequestDetailMapper() {}

    static PullRequestDetail toPullRequestDetail(
            String repo, int number, StoredPrDetail stored, boolean viewerCanWrite)
    {
        PrRawDetail raw = stored.raw();
        return new PullRequestDetail(
                repo,
                number,
                raw.body(),
                raw.additions(),
                raw.deletions(),
                raw.changedFiles(),
                countApprovals(stored.reviews()),
                aggregateCiStatus(stored.checkRuns()),
                toActivityItems(stored.timeline()),
                toCheckRuns(stored.checkRuns()),
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
        return List.copyOf(latestByName.values());
    }

    static List<PullRequestDetail.CheckRun> toCheckRuns(List<PrCheckRunState> checkRuns)
    {
        return dedupeCheckRunsByName(checkRuns).stream()
                .map(checkRun -> new PullRequestDetail.CheckRun(
                        checkRun.githubId(), checkRun.name(), checkRun.status(), checkRun.conclusion()))
                .toList();
    }

    static int countApprovals(List<PrReviewState> reviews)
    {
        return (int) reviews.stream().filter(review -> GithubReviewState.APPROVED.equals(review.state())).count();
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
                .filter(event -> INTERESTING_EVENTS.contains(event.event()))
                .sorted((left, right) -> time(right).compareTo(time(left)))
                .map(event -> new PullRequestDetail.ActivityItem(
                        event.actor(), event.event(), event.timestamp(), event.body(), event.githubId()))
                .toList();
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

    static Map<String, String> rolledUpReviewerVerdicts(List<PrReviewState> reviews)
    {
        if (reviews == null || reviews.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (PrReviewState review : reviews) {
            if (review.login() == null || review.state() == null) {
                continue;
            }
            if (!out.containsKey(review.login()) || isStickyVerdict(review.state())) {
                out.put(review.login(), review.state());
            }
        }
        return Map.copyOf(out);
    }

    private static Instant time(PrTimelineEvent event)
    {
        return event.timestamp() != null ? event.timestamp() : Instant.EPOCH;
    }

    private static boolean isStickyVerdict(String state)
    {
        return GithubReviewState.APPROVED.equals(state)
                || GithubReviewState.CHANGES_REQUESTED.equals(state)
                || GithubReviewState.DISMISSED.equals(state);
    }
}
