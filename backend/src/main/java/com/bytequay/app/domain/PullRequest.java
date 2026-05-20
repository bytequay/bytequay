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
package com.bytequay.app.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The list-level shape of a PR. Most fields come straight from GitHub's
 * search/list endpoints; the trailing block (ciStatus, additions, deletions,
 * commentCount, attentionReason) is populated by the sync job from the
 * accompanying detail fetch so the Kanban can render cards without an
 * extra round-trip.
 *
 * @param createdAt GitHub's {@code created_at}, or null on legacy rows.
 * @param labelColors side-table for label name to hex color.
 * @param state GitHub PR state: {@code "open"}, {@code "closed"}, or
 * {@code "merged"}.
 * @param mergeable GitHub's mergeable flag, or null while GitHub is still
 * computing it.
 * @param mergeableState GitHub's mergeable_state value.
 * @param headPushedAt timestamp of the most recent commit on the PR's head
 * branch.
 * @param reviewerVerdicts per-reviewer verdict map keyed by login.
 * @param snoozedUntil when the PR is snoozed until, or null when not snoozed.
 * @param snoozeWakeReason populated when an auto-wake fires.
 * @param headRef PR head branch name from GitHub's list response.
 */
public record PullRequest(
        long id,
        String repo,
        int number,
        String title,
        String author,
        String htmlUrl,
        Instant createdAt,
        Instant updatedAt,
        Origin origin,
        List<String> labels,
        Map<String, String> labelColors,
        boolean draft,
        Instant viewedAt,
        Instant reviewedAt,
        HandledAction handledAction,
        List<String> requestedReviewers,
        PullRequestDetail.CiStatus ciStatus,
        int additions,
        int deletions,
        int commentCount,
        AttentionReason attentionReason,
        // --- Phase 1 kanban-refactor fields (V26). Optional — null on rows
        //     that haven't been re-synced yet, so renderers must tolerate
        //     missing values. See docs/design/kanban-refactor.md. ---
        String state,
        Instant closedAt,
        Instant mergedAt,
        Boolean mergeable,
        String mergeableState,
        Instant headPushedAt,
        Map<String, String> reviewerVerdicts,
        // --- Snooze (V37) — local-state, never sent to GitHub ---
        Instant snoozedUntil,
        String snoozeWakeReason,
        String headRef)
{
    public enum Origin
    {
        AUTHORED,
        REVIEW_REQUESTED
    }
}
