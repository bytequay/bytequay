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
 */
public record PullRequest(
        long id,
        String repo,
        int number,
        String title,
        String author,
        String htmlUrl,
        /** GitHub's PR.created_at — when the PR was first opened. Null on
         *  legacy rows that pre-date V19; the UI falls back to updatedAt. */
        Instant createdAt,
        Instant updatedAt,
        Origin origin,
        List<String> labels,
        /** Side-table for label name → hex color (e.g. "d4c5f9"). Same names
         *  as {@link #labels}; missing entries fall back to a neutral chip
         *  on the UI side. Null on legacy rows. */
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
        /** GitHub PR state: "open", "closed", or "merged" (= closed with
         *  merged_at set). Null on legacy rows. */
        String state,
        Instant closedAt,
        Instant mergedAt,
        /** GitHub's mergeable flag. Null while GitHub is still computing it
         *  or while the row hasn't been refreshed since V26. */
        Boolean mergeable,
        /** GitHub's mergeable_state — "clean", "dirty", "blocked", "behind",
         *  "unstable", "unknown". Used to colour the "Ready to merge" rule. */
        String mergeableState,
        /** Timestamp of the most recent commit on the PR's head branch —
         *  derived from the latest "committed" timeline event. Drives the
         *  "last push Xd ago" card meta line. */
        Instant headPushedAt,
        /** Per-reviewer verdict map: login -> state name (APPROVED,
         *  CHANGES_REQUESTED, COMMENTED, DISMISSED). Empty until the PR's
         *  detail has been synced at least once after V26. */
        Map<String, String> reviewerVerdicts,
        // --- Snooze (V37) — local-state, never sent to GitHub ---
        /** When the PR is snoozed until. Null when not snoozed. The
         *  page header's Snoozed tab filters to rows with this set;
         *  the auto-wake check fires when current time passes it. */
        Instant snoozedUntil,
        /** Populated when an auto-wake fires; cleared once the user
         *  acknowledges the just-woke alert. Drives the green
         *  "PR woke up" banner at the top of the Inbox. */
        String snoozeWakeReason,
        /** PR head branch name from GitHub's list response (head.ref).
         *  Captured by the list sync so the local-repo kanban can map
         *  a local branch to its open PR without waiting for the
         *  per-PR detail fetch. Null on legacy rows. */
        String headRef)
{
    public enum Origin
    {
        AUTHORED,
        REVIEW_REQUESTED
    }
}
