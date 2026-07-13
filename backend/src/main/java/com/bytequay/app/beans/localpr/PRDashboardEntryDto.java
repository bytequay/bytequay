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
package com.bytequay.app.beans.localpr;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRDashboardEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire shape for one dashboard row — deliberately flat and field-compatible
 * with the legacy {@code PullRequestDto} (same names, same enum spellings)
 * so the frontend's existing categorization logic ({@code prBuckets.ts})
 * needs only a type-source swap, not a rewrite. {@code state} is translated
 * from the unified {@code PR.status} into the legacy open/closed/merged
 * vocabulary the categorizer already understands. Timestamps are ISO-8601
 * strings (not epoch-millis, unlike {@code PRDto}) — matching the legacy
 * {@code PullRequestDto} exactly, since {@code prBuckets.ts}'s large existing
 * test suite constructs fixtures with ISO string literals throughout;
 * {@code id} is the one field that genuinely can't stay compatible (the
 * legacy numeric id was never stable across GitHub's two id namespaces —
 * see unified-pr-view.md's dashboard migration).
 */
public record PRDashboardEntryDto(
        String id,
        String repo,
        int number,
        String title,
        String author,
        String htmlUrl,
        String createdAt,
        String updatedAt,
        String origin,
        List<String> labels,
        Map<String, String> labelColors,
        boolean draft,
        String viewedAt,
        String reviewedAt,
        String handledAction,
        List<String> requestedReviewers,
        String ciStatus,
        int additions,
        int deletions,
        int commentCount,
        String attentionReason,
        String state,
        String closedAt,
        String mergedAt,
        Boolean mergeable,
        String mergeableState,
        String headPushedAt,
        Map<String, String> reviewerVerdicts,
        String snoozedUntil,
        String snoozeWakeReason,
        String reviewState)
{
    public static PRDashboardEntryDto from(PRDashboardEntry entry)
    {
        return from(entry, "none");
    }

    public static PRDashboardEntryDto from(PRDashboardEntry entry, String reviewState)
    {
        PR pr = entry.pr();
        PR.PRSyncSnapshot sync = pr.githubSync();
        return new PRDashboardEntryDto(
                pr.id(),
                pr.repo(),
                pr.remotePrNumber() == null ? 0 : pr.remotePrNumber(),
                pr.title(),
                pr.author(),
                pr.remotePrUrl(),
                isoOrNull(pr.createdAt()),
                sync == null ? null : isoOrNull(sync.ghUpdatedAt()),
                sync == null || sync.watchReason() == null ? null : sync.watchReason().name(),
                sync == null ? List.of() : sync.labels(),
                sync == null ? Map.of() : sync.labelColors(),
                sync != null && sync.draft(),
                isoOrNull(entry.triage().viewedAt()),
                isoOrNull(entry.triage().reviewedAt()),
                entry.triage().handledAction() == null ? null : entry.triage().handledAction().name(),
                sync == null ? List.of() : sync.requestedReviewers(),
                sync == null || sync.ciStatus() == null ? null : sync.ciStatus().name(),
                sync == null ? 0 : sync.additions(),
                sync == null ? 0 : sync.deletions(),
                sync == null ? 0 : sync.commentCount(),
                sync == null || sync.attentionReason() == null ? null : sync.attentionReason().name(),
                legacyState(pr.status()),
                isoOrNull(pr.closedAt()),
                isoOrNull(pr.mergedAt()),
                sync == null ? null : sync.mergeable(),
                sync == null ? null : sync.mergeableState(),
                sync == null ? null : isoOrNull(sync.headPushedAt()),
                sync == null ? Map.of() : sync.reviewerVerdicts(),
                isoOrNull(entry.triage().snoozedUntil()),
                entry.triage().snoozeWakeReason(),
                reviewState);
    }

    private static String legacyState(String status)
    {
        if (PR.STATUS_MERGED.equals(status)) {
            return "merged";
        }
        if (PR.STATUS_CLOSED.equals(status)) {
            return "closed";
        }
        return "open";
    }

    private static String isoOrNull(Instant instant)
    {
        return instant == null ? null : instant.toString();
    }
}
