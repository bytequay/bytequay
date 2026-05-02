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

public record PullRequestDetail(
        String repo,
        int number,
        String body,
        List<String> labels,
        boolean draft,
        Boolean mergeable,
        String mergeableState,
        int additions,
        int deletions,
        int changedFiles,
        int approvalCount,
        int changesRequestedCount,
        int pendingReviewerCount,
        CiStatus ciStatus,
        List<ChangedFile> files,
        List<ActivityItem> recentActivity,
        List<CheckRun> checkRuns,
        /** Per-line threaded review comments, grouped — one ReviewThread per
         *  root comment with its replies in order. */
        List<ReviewThread> reviewThreads,
        /** Issues linked from this PR via closing keywords (closes/fixes/
         *  resolves #N) in the PR body. Resolved against the same repo only;
         *  cross-repo references like {@code closes owner/repo#42} are not
         *  matched yet — left as a follow-up for the GraphQL pass. */
        List<LinkedIssue> linkedIssues,
        /** True iff the authenticated PAT has push (write) access to the
         *  PR's repository, as reported by {@code GET /repos/{owner}/{repo}}'s
         *  {@code permissions.push} flag. Gates the merge button on the
         *  detail page so we don't surface a control GitHub will reject. */
        boolean viewerCanWrite)
{
    public enum CiStatus
    {
        PASSING,
        FAILING,
        PENDING,
        NONE
    }

    public record ChangedFile(String filename, int additions, int deletions, String status) {}

    /**
     * One row in the PR's conversation feed. {@code body} carries the
     * comment text for {@code commented} / {@code reviewed} events
     * (markdown, as GitHub stores it); null for structural events like
     * {@code review_requested} / {@code merged}. {@code state} carries
     * the review verdict for {@code reviewed} events
     * (APPROVED / CHANGES_REQUESTED / COMMENTED / DISMISSED).
     */
    public record ActivityItem(
            String actor,
            String eventType,
            Instant timestamp,
            String body,
            String state,
            /** before/after SHAs on head_ref_force_pushed events; null otherwise. */
            String beforeSha,
            String afterSha,
            /** For {@code review_requested} events, the login of the user
             *  being invited to review. Null for every other event type. */
            String requestedReviewer,
            /** For {@code reviewed} events, the GitHub review id used to
             *  match the event to its per-line review comments. Null
             *  otherwise. */
            Long reviewId,
            /** Author's relationship to the repo (MEMBER / CONTRIBUTOR /
             *  OWNER / NONE / FIRST_TIME_CONTRIBUTOR / …) for events that
             *  carry a comment ({@code commented} / {@code reviewed}).
             *  Null for structural events. */
            String authorAssociation,
            /** Stable GitHub event id. For {@code commented} events this
             *  is the issue-comment id required by the reactions
             *  endpoint; null on rare timeline events without an id. */
            Long githubId,
            /** Reactions tally on {@code commented} events. {@link Reactions#EMPTY}
             *  for events without reactions (force-push, merged, etc.). */
            Reactions reactions) {}

    /**
     * Per-check view suitable for the UI — name, conclusion, and a link to
     * the details page on GitHub. The frontend filters failing entries from
     * this list.
     */
    public record CheckRun(String name, String status, String conclusion, String htmlUrl) {}

    /**
     * One per-line review thread: the root comment plus its replies, all
     * anchored to the same {@code (filePath, line)}. {@code diffHunk} is the
     * surrounding diff snippet from the root comment so the UI can render
     * the code in question without re-loading the diff.
     */
    public record ReviewThread(
            long rootGithubId,
            String filePath,
            Integer line,
            String side,
            String diffHunk,
            List<ReviewMessage> messages,
            /** True iff GitHub considers this thread resolved (the
             *  "Resolved" badge in the conversation page). The REST API
             *  doesn't expose this — surfaced via GraphQL only — so this
             *  is null/false on the REST path until the GraphQL fetcher
             *  is wired up. The UI defaults resolved threads to folded. */
            Boolean resolved,
            /** True iff the thread is anchored to a line that no longer
             *  exists in the current diff (typically after a force-push).
             *  Derived from REST's `position` field on the root comment:
             *  position == null ⇒ outdated. */
            boolean outdated,
            /** First line of the multi-line range the thread anchors to.
             *  Null for single-line threads (the common case). When set,
             *  the pair (startLine, line) is the inclusive range. */
            Integer startLine,
            /** Side of {@link #startLine} ("LEFT"/"RIGHT"); usually the
             *  same as {@link #side}. */
            String startSide) {}

    public record ReviewMessage(
            long githubId,
            String author,
            String body,
            Instant createdAt,
            Reactions reactions,
            /** GitHub review id this message was submitted with. Lets the
             *  UI fold per-line comments under their parent review event. */
            Long reviewId,
            /** Author's relationship to the repo (MEMBER / CONTRIBUTOR /
             *  OWNER / NONE / …). Powers the role pill in the UI. */
            String authorAssociation) {}

    /**
     * Resolved metadata for an issue referenced from the PR body via a
     * closing keyword. {@code state} is "open" or "closed"; the title and
     * URL come from a single GET against the issues API.
     */
    public record LinkedIssue(int number, String title, String state, String htmlUrl) {}
}
