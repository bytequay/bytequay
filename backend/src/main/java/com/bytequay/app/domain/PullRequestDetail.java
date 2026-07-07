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

/**
 * Aggregated pull-request detail payload for the review page.
 *
 * @param requestedReviewers logins still on GitHub's pending-review list.
 * @param reviewThreads per-line threaded review comments, grouped by root
 * comment.
 * @param linkedIssues issues linked from this PR via closing keywords in the PR
 * body.
 * @param viewerCanWrite true iff the authenticated PAT has push access to the
 * PR's repository.
 * @param headRef branch name on the head side, or null on legacy rows.
 * @param headRepo {@code "owner/repo"} of the head side.
 * @param baseRef target branch.
 * @param baseRepo {@code "owner/repo"} of the target side.
 * @param mergeQueueState GitHub merge-queue state for this PR, or null when it
 * has none.
 * @param mergeQueueEnabled true when the PR's base branch has a merge queue
 * configured (it's possible to add this PR to the queue); GraphQL-sourced.
 */
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
        int writeApprovalCount,
        int changesRequestedCount,
        int pendingReviewerCount,
        List<String> requestedReviewers,
        CiStatus ciStatus,
        List<ChangedFile> files,
        List<ActivityItem> recentActivity,
        List<CheckRun> checkRuns,
        List<ReviewThread> reviewThreads,
        List<LinkedIssue> linkedIssues,
        boolean viewerCanWrite,
        String headRef,
        String headRepo,
        String baseRef,
        String baseRepo,
        String mergeQueueState,
        String state,
        boolean merged,
        boolean mergeQueueEnabled)
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
     * comment text for "commented" and "reviewed" events
     * (markdown, as GitHub stores it); null for structural events like
     * {@code review_requested} / {@code merged}. {@code state} carries
     * the review verdict for "reviewed" events
     * (APPROVED / CHANGES_REQUESTED / COMMENTED / DISMISSED).
     *
     * @param beforeSha before SHA on head-ref force-pushed events.
     * @param requestedReviewer login invited to review on review-requested
     * events.
     * @param reviewId GitHub review id used to match review events to per-line
     * comments.
     * @param authorAssociation author's relationship to the repo for events
     * that carry a comment.
     * @param githubId stable GitHub event id.
     * @param reactions reactions tally on commented events.
     * @param labelName label name on labeled/unlabeled events.
     * @param labelColor label hex color (no leading {@code #}) on
     * labeled/unlabeled events.
     * @param milestoneTitle milestone title on milestoned/demilestoned events.
     * @param assigneeLogin login assigned/unassigned on assigned/unassigned
     * events.
     * @param crossRefNumber the other issue/PR's number on cross-referenced
     * events.
     * @param crossRefTitle the other issue/PR's title on cross-referenced
     * events.
     * @param crossRefUrl the other issue/PR's html_url on cross-referenced
     * events.
     * @param crossRefIsPullRequest true iff the cross-referencing source is a
     * pull request rather than an issue.
     */
    public record ActivityItem(
            String actor,
            String eventType,
            Instant timestamp,
            String body,
            String state,
            String beforeSha,
            String afterSha,
            String requestedReviewer,
            Long reviewId,
            String authorAssociation,
            Long githubId,
            Reactions reactions,
            String labelName,
            String labelColor,
            String milestoneTitle,
            String assigneeLogin,
            Integer crossRefNumber,
            String crossRefTitle,
            String crossRefUrl,
            boolean crossRefIsPullRequest)
    {
        /** Compact form for the many event types that never carry label/
         *  milestone/assignee/cross-ref data — every existing call site
         *  keeps working unchanged, defaulting the new fields to "none". */
        public ActivityItem(
                String actor, String eventType, Instant timestamp, String body, String state, String beforeSha,
                String afterSha, String requestedReviewer, Long reviewId, String authorAssociation, Long githubId,
                Reactions reactions)
        {
            this(actor, eventType, timestamp, body, state, beforeSha, afterSha, requestedReviewer, reviewId,
                    authorAssociation, githubId, reactions, null, null, null, null, null, null, null, false);
        }
    }

    /**
     * Per-check view suitable for the UI. {@code outputTitle} +
     * {@code outputSummary} carry GitHub's {@code output.title} /
     * {@code output.summary} blocks — usually the actual error message
     * for failing checks, surfaced inline so reviewers don't need to
     * click through to the run page on github.com. Both are nullable
     * (some runners don't publish an output block at all).
     *
     * @param githubId stable per-attempt id from GitHub, or null for legacy
     * cached rows.
     */
    public record CheckRun(
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
     *
     * @param resolved true iff GitHub considers this thread resolved.
     * @param outdated true iff the thread is anchored to a line that no longer
     * exists in the current diff.
     * @param startLine first line of the multi-line range the thread anchors
     * to, or null for single-line threads.
     * @param startSide side of {@link #startLine}; usually the same as
     * {@link #side}.
     * @param originalLine original line number matching {@link #diffHunk}.
     * @param originalStartLine original start line matching {@link #diffHunk}.
     */
    public record ReviewThread(
            long rootGithubId,
            String filePath,
            Integer line,
            String side,
            String diffHunk,
            List<ReviewMessage> messages,
            Boolean resolved,
            boolean outdated,
            Integer startLine,
            String startSide,
            Integer originalLine,
            Integer originalStartLine) {}

    /**
     * One message inside a review thread.
     *
     * @param reviewId GitHub review id this message was submitted with.
     * @param authorAssociation author's relationship to the repo.
     */
    public record ReviewMessage(
            long githubId,
            String author,
            String body,
            Instant createdAt,
            Reactions reactions,
            Long reviewId,
            String authorAssociation) {}

    /**
     * Resolved metadata for an issue referenced from the PR body via a
     * closing keyword. {@code state} is "open" or "closed"; the title and
     * URL come from a single GET against the issues API.
     */
    public record LinkedIssue(int number, String title, String state, String htmlUrl) {}
}
