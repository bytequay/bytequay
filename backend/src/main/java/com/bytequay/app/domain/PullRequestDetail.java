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
        /** Logins still on GitHub's pending-review list — populated
         *  from the same /pulls/{n} response that {@link #pendingReviewerCount}
         *  comes from. The frontend renders one row per login in the
         *  reviewer sidebar (with a re-request button); the count
         *  alone wasn't enough to surface them. Always non-null;
         *  empty when no reviewer is pending. */
        List<String> requestedReviewers,
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
        boolean viewerCanWrite,
        /** Branch name on the head side (e.g. "feat/foo"). Null on legacy
         *  rows whose detail predates V36. */
        String headRef,
        /** "owner/repo" of the head side; differs from baseRepo on fork PRs. */
        String headRepo,
        /** Target branch (almost always the default branch). */
        String baseRef,
        /** "owner/repo" of the target side; same as the PR's repo for
         *  in-repo PRs. */
        String baseRepo,
        /** GitHub merge-queue state for this PR — "QUEUED",
         *  "MERGEABLE", "UNMERGEABLE", etc. when the PR currently has
         *  an entry in the repo's merge queue; null when it has none
         *  or the repo doesn't use a merge queue. REST doesn't expose
         *  this per-PR (github.com itself uses the GraphQL
         *  {@code pullRequest.mergeQueueEntry} field for the same
         *  pill in their UI). */
        String mergeQueueState)
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
    /**
     * Per-check view suitable for the UI. {@code outputTitle} +
     * {@code outputSummary} carry GitHub's {@code output.title} /
     * {@code output.summary} blocks — usually the actual error message
     * for failing checks, surfaced inline so reviewers don't need to
     * click through to the run page on github.com. Both are nullable
     * (some runners don't publish an output block at all).
     */
    public record CheckRun(
            /** Stable per-attempt id from GitHub. For Actions-backed
             *  checks it is the same id used by
             *  {@code GET /actions/jobs/{id}/logs}, which the frontend
             *  uses to fetch the full log inline without leaving the
             *  page. Nullable for legacy cached rows pre-V34 migration. */
            Long githubId,
            String name,
            String status,
            String conclusion,
            String htmlUrl,
            String outputTitle,
            String outputSummary) {}

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
            String startSide,
            /** Original line numbers matching {@link #diffHunk}. The
             *  diff_hunk reflects the file *at the time the comment
             *  landed*; line / startLine shift after edits. Used to
             *  slice the hunk client-side to the actual commented
             *  lines. Null on legacy rows synced before V38; frontend
             *  falls back to {@link #line} / {@link #startLine}. */
            Integer originalLine,
            Integer originalStartLine) {}

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
