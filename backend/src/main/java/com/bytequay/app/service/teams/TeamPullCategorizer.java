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
package com.bytequay.app.service.teams;

import com.bytequay.app.domain.GithubReviewState;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.MyPrColumn;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.google.common.collect.ImmutableList;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side mirror of the frontend's categorizeMyPr / sort functions
 * in prBuckets.ts. Runs on the team kanban path so the backend can
 * pre-bucket PRs into columns and paginate per column — the frontend
 * KanbanColumn's "+ N more" button no longer slices in-memory data; it
 * asks the backend for the next page.
 *
 * <p>Keep this in sync with prBuckets.ts. Tests cover both sides.
 */
final class TeamPullCategorizer
{
    private static final Duration RECENT_WINDOW = Duration.ofDays(7);

    private TeamPullCategorizer() {}

    /**
     * Returns the column the PR belongs in, or null when it doesn't
     * fit any column (e.g. closed > 7 days, or origin != AUTHORED —
     * the team kanban only renders authored PRs).
     */
    static MyPrColumn categorize(PullRequest pr, Instant now)
    {
        if (pr.origin() != PullRequest.Origin.AUTHORED) {
            return null;
        }

        // User-dismissed PRs go to the HANDLED bucket regardless of
        // whether they're still open on GitHub. Same predicate as the
        // frontend isHandled() — kept in lockstep.
        HandledAction handled = pr.handledAction();
        if (handled == HandledAction.MERGED
                || handled == HandledAction.DISMISSED
                || handled == HandledAction.MANUAL) {
            return MyPrColumn.HANDLED;
        }

        Instant merged = pr.mergedAt();
        Instant closed = pr.closedAt();
        if (merged != null && Duration.between(merged, now).compareTo(RECENT_WINDOW) <= 0) {
            return MyPrColumn.RECENTLY_MERGED;
        }
        if (closed != null
                && Duration.between(closed, now).compareTo(RECENT_WINDOW) <= 0
                && !"open".equals(pr.state())) {
            return MyPrColumn.RECENTLY_MERGED;
        }
        if ("closed".equals(pr.state()) || "merged".equals(pr.state())) {
            return null;
        }

        if (pr.draft()) {
            return MyPrColumn.DRAFTING;
        }

        Map<String, String> verdicts = pr.reviewerVerdicts() == null
                ? Map.of()
                : pr.reviewerVerdicts();
        boolean hasApproval = verdicts.containsValue(GithubReviewState.APPROVED);
        boolean hasChangesRequested = verdicts.containsValue(GithubReviewState.CHANGES_REQUESTED);

        if (hasApproval
                && !hasChangesRequested
                && pr.ciStatus() == PullRequestDetail.CiStatus.PASSING
                && Boolean.TRUE.equals(pr.mergeable())) {
            return MyPrColumn.READY_TO_MERGE;
        }

        if (hasChangesRequested) {
            return MyPrColumn.NEEDS_CHANGES;
        }

        // Failing CI is the author's problem too — mirror of the
        // frontend's prBuckets.ts categorizeMyPr.
        if (pr.ciStatus() == PullRequestDetail.CiStatus.FAILING) {
            return MyPrColumn.NEEDS_CHANGES;
        }

        return MyPrColumn.WAITING_ON_REVIEW;
    }

    /**
     * Buckets a list of PRs into columns and applies the per-column
     * sort that prBuckets.ts also uses (matches sort order on both
     * sides so the same PR is at the top regardless of where the page
     * is built).
     */
    static Map<MyPrColumn, List<PullRequest>> groupAndSort(Collection<PullRequest> prs, Instant now)
    {
        Map<MyPrColumn, List<PullRequest>> out = new EnumMap<>(MyPrColumn.class);
        for (MyPrColumn col : MyPrColumn.values()) {
            out.put(col, new ArrayList<>());
        }
        for (PullRequest pr : prs) {
            MyPrColumn col = categorize(pr, now);
            if (col != null) {
                out.get(col).add(pr);
            }
        }
        sortInPlace(out.get(MyPrColumn.DRAFTING), BY_UPDATED_AT_DESC);
        sortInPlace(out.get(MyPrColumn.WAITING_ON_REVIEW), BY_CREATED_AT_ASC);
        sortInPlace(out.get(MyPrColumn.NEEDS_CHANGES), BY_UPDATED_AT_DESC);
        sortInPlace(out.get(MyPrColumn.READY_TO_MERGE), BY_UPDATED_AT_DESC);
        sortInPlace(out.get(MyPrColumn.RECENTLY_MERGED), BY_MERGED_AT_DESC);
        // Handled PRs sort by reviewedAt desc (most recent dismissal at
        // the top). reviewedAt is when the user clicked "mark handled".
        sortInPlace(out.get(MyPrColumn.HANDLED), BY_REVIEWED_AT_DESC);
        // Wrap into immutable lists so callers can't mutate the cached value.
        Map<MyPrColumn, List<PullRequest>> immutable = new EnumMap<>(MyPrColumn.class);
        for (Map.Entry<MyPrColumn, List<PullRequest>> e : out.entrySet()) {
            immutable.put(e.getKey(), ImmutableList.copyOf(e.getValue()));
        }
        return immutable;
    }

    static Map<MyPrColumn, Integer> counts(Map<MyPrColumn, List<PullRequest>> grouped)
    {
        Map<MyPrColumn, Integer> out = new HashMap<>();
        for (Map.Entry<MyPrColumn, List<PullRequest>> e : grouped.entrySet()) {
            out.put(e.getKey(), e.getValue().size());
        }
        return out;
    }

    private static void sortInPlace(List<PullRequest> list, Comparator<PullRequest> cmp)
    {
        list.sort(cmp);
    }

    private static final Comparator<PullRequest> BY_CREATED_AT_ASC = Comparator.comparing(
            (PullRequest pr) -> pr.createdAt() != null ? pr.createdAt() : pr.updatedAt(),
            Comparator.nullsLast(Comparator.naturalOrder()));

    private static final Comparator<PullRequest> BY_UPDATED_AT_DESC = Comparator.comparing(
            PullRequest::updatedAt,
            Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<PullRequest> BY_MERGED_AT_DESC = Comparator.comparing(
            PullRequest::mergedAt,
            Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<PullRequest> BY_REVIEWED_AT_DESC = Comparator.comparing(
            PullRequest::reviewedAt,
            Comparator.nullsLast(Comparator.reverseOrder()));
}
