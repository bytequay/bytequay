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
 * vocabulary the categorizer already understands.
 */
public record PRDashboardEntryDto(
        String id,
        String repo,
        int number,
        String title,
        String author,
        String htmlUrl,
        Long createdAt,
        Long updatedAt,
        String origin,
        List<String> labels,
        Map<String, String> labelColors,
        boolean draft,
        Long viewedAt,
        Long reviewedAt,
        String handledAction,
        List<String> requestedReviewers,
        String ciStatus,
        int additions,
        int deletions,
        int commentCount,
        String attentionReason,
        String state,
        Long closedAt,
        Long mergedAt,
        Boolean mergeable,
        String mergeableState,
        Long headPushedAt,
        Map<String, String> reviewerVerdicts,
        Long snoozedUntil,
        String snoozeWakeReason)
{
    public static PRDashboardEntryDto from(PRDashboardEntry entry)
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
                epochOrNull(pr.createdAt()),
                sync == null ? null : epochOrNull(sync.ghUpdatedAt()),
                sync == null || sync.watchReason() == null ? null : sync.watchReason().name(),
                sync == null ? List.of() : sync.labels(),
                sync == null ? Map.of() : sync.labelColors(),
                sync != null && sync.draft(),
                epochOrNull(entry.triage().viewedAt()),
                epochOrNull(entry.triage().reviewedAt()),
                entry.triage().handledAction() == null ? null : entry.triage().handledAction().name(),
                sync == null ? List.of() : sync.requestedReviewers(),
                sync == null || sync.ciStatus() == null ? null : sync.ciStatus().name(),
                sync == null ? 0 : sync.additions(),
                sync == null ? 0 : sync.deletions(),
                sync == null ? 0 : sync.commentCount(),
                sync == null || sync.attentionReason() == null ? null : sync.attentionReason().name(),
                legacyState(pr.status()),
                epochOrNull(pr.closedAt()),
                epochOrNull(pr.mergedAt()),
                sync == null ? null : sync.mergeable(),
                sync == null ? null : sync.mergeableState(),
                sync == null ? null : epochOrNull(sync.headPushedAt()),
                sync == null ? Map.of() : sync.reviewerVerdicts(),
                epochOrNull(entry.triage().snoozedUntil()),
                entry.triage().snoozeWakeReason());
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

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }
}
