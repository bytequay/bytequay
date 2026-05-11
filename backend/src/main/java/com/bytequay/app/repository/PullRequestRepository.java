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
package com.bytequay.app.repository;

import com.bytequay.app.domain.ContributionCalendar;
import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.IssueTimelineEvent;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestHistoryPage;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.RepoActivityItem;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.RequestedReviewers;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserRepo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Thin GitHub API client interface.  Each method maps to a single API call and returns raw data;
 * no business logic (deduplication, parallel orchestration, aggregation, filtering) lives here.
 */
public interface PullRequestRepository
{
    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Runs one GitHub search query and returns the matching pull requests.
     * Maps to: GET /search/issues?q={query}
     *
     * @param pat   GitHub Personal Access Token
     * @param query a GitHub search qualifier string, e.g. {@code "is:pr is:open author:@me"}
     */
    default List<PullRequest> searchPullRequests(String pat, String query)
    {
        throw new UnsupportedOperationException("searchPullRequests not implemented");
    }

    /**
     * Paged variant of {@link #searchPullRequests}. Used by the merge-history
     * page to walk through closed PRs without pulling everything at once.
     * Returns {@code items}, the server-reported {@code totalCount}, and a
     * derived {@code hasMore} flag.
     *
     * @param pat     GitHub PAT
     * @param query   GitHub search qualifier string
     * @param page    1-based page index
     * @param perPage results per page (GitHub caps this at 100)
     */
    default PullRequestHistoryPage searchPullRequestsPaged(String pat, String query, int page, int perPage)
    {
        throw new UnsupportedOperationException("searchPullRequestsPaged not implemented");
    }

    // ── Pull request detail (individual calls, orchestrated by the service) ──

    /**
     * Fetches core fields of a single pull request.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{number}
     */
    default PrRawDetail fetchPrDetail(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("fetchPrDetail not implemented");
    }

    /**
     * Fetches all submitted reviews for a pull request.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{number}/reviews
     */
    default List<PrReviewState> fetchPrReviews(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("fetchPrReviews not implemented");
    }

    /**
     * Fetches check-run results for a commit SHA.
     * Maps to: GET /repos/{owner}/{repo}/commits/{sha}/check-runs
     */
    default List<PrCheckRunState> fetchPrCheckRuns(String pat, String owner, String repo, String sha)
    {
        throw new UnsupportedOperationException("fetchPrCheckRuns not implemented");
    }

    /**
     * Fetches the unified diff for a pull request as a single text blob
     * (the same diff shown on the "Files changed" tab).
     * Maps to: GET /repos/{owner}/{repo}/pulls/{number} with Accept: application/vnd.github.diff
     */
    default String fetchPrDiff(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("fetchPrDiff not implemented");
    }

    /**
     * Fetches the list of files changed by a pull request.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{number}/files
     */
    default List<PullRequestDetail.ChangedFile> fetchPrFiles(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("fetchPrFiles not implemented");
    }

    /**
     * Fetches the same file list but with unified-diff patches attached — used
     * by the native diff viewer. Separate from {@link #fetchPrFiles} so the
     * preview path (which caches file metadata in SQLite) doesn't drag patch
     * blobs through storage.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{number}/files
     */
    default List<DiffFile> fetchPrDiffFiles(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("fetchPrDiffFiles not implemented");
    }

    /**
     * Fetches the list of commits in a pull request, oldest first.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{number}/commits
     */
    default List<PullRequestCommit> fetchPrCommits(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("fetchPrCommits not implemented");
    }

    /**
     * Fetches the diff for a single commit — the same DiffFile shape as
     * {@link #fetchPrDiffFiles} but scoped to one sha. Used by the commit
     * picker in the diff viewer so reviewers can drill into "what did THIS
     * commit add?" instead of staring at the cumulative PR diff.
     * Maps to: GET /repos/{owner}/{repo}/commits/{sha}
     */
    default List<DiffFile> fetchCommitDiffFiles(String pat, PullRequestRef pr, String sha)
    {
        throw new UnsupportedOperationException("fetchCommitDiffFiles not implemented");
    }

    /**
     * Fetches the full content of a file at a specific commit, returned as
     * a list of lines (no trailing newline on each entry). Powers the
     * "expand collapsed code" buttons in the diff viewer — the renderer
     * slices the line range it needs from this list. Capped at GitHub's
     * 1MB Contents-API limit; larger files yield a 404-like empty result.
     * Maps to: GET /repos/{owner}/{repo}/contents/{path}?ref={sha}
     */
    default List<String> fetchFileBlobLines(String pat, RepoRef repo, String path, String sha)
    {
        throw new UnsupportedOperationException("fetchFileBlobLines not implemented");
    }

    /**
     * Fetches the full timeline of a pull request. When {@code since}
     * is non-null, only events updated after that watermark are returned —
     * the incremental-sync hot path that lets a quiet PR settle for a
     * single GET-and-empty-response per cycle.
     * Maps to: GET /repos/{owner}/{repo}/issues/{number}/timeline
     */
    default List<PrTimelineEvent> fetchPrTimeline(String pat, PullRequestRef pr, Instant since)
    {
        throw new UnsupportedOperationException("fetchPrTimeline not implemented");
    }

    /**
     * Fetches every per-line review comment on the PR, threaded by replying
     * to the same root id. Powers the Conversation panel's threaded view.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{number}/comments
     */
    default List<PrReviewThreadMessage> fetchPrReviewComments(String pat, PullRequestRef pr, Instant since)
    {
        throw new UnsupportedOperationException("fetchPrReviewComments not implemented");
    }

    /**
     * Fetches every issue-level comment on the PR (the bottom-of-PR
     * "Add a comment" stream). Used as a body-fallback for the timeline:
     * GitHub's /issues/timeline endpoint occasionally omits the {@code body}
     * field on {@code commented} events for cross-repo or restricted-vis
     * PRs, leaving us with an actor+timestamp but no text. The dedicated
     * /comments endpoint always returns the full body.
     * Maps to: GET /repos/{owner}/{repo}/issues/{number}/comments
     */
    default List<PrTimelineEvent> fetchPrIssueComments(String pat, PullRequestRef pr, Instant since)
    {
        throw new UnsupportedOperationException("fetchPrIssueComments not implemented");
    }

    /**
     * Fetches a single issue's metadata (title + state + URL). Returns
     * empty when the issue doesn't exist or isn't visible to the PAT —
     * callers shouldn't blow up on broken {@code closes #N} references.
     * Maps to: GET /repos/{owner}/{repo}/issues/{number}
     */
    default Optional<PullRequestDetail.LinkedIssue> fetchIssue(String pat, RepoRef repo, int number)
    {
        throw new UnsupportedOperationException("fetchIssue not implemented");
    }

    /**
     * Posts a single per-line review comment ("Add single comment" in
     * GitHub's UI). Bypasses the draft-review flow — the comment lands
     * immediately on the PR. {@code commitId} should be the PR head SHA;
     * {@code side} is "LEFT" or "RIGHT".
     * Maps to: POST /repos/{owner}/{repo}/pulls/{number}/comments
     */
    default void createInlineReviewComment(
            String pat,
            PullRequestRef pr,
            String body,
            String path,
            int line,
            String side,
            String commitId,
            /** First line of a multi-line range (optional). When set,
             *  GitHub creates a multi-line comment spanning startLine
             *  through line, both inclusive. */
            Integer startLine,
            /** Side of startLine ("LEFT"/"RIGHT"); GitHub requires this
             *  whenever startLine is set. Pass null for single-line. */
            String startSide)
    {
        throw new UnsupportedOperationException("createInlineReviewComment not implemented");
    }

    // ── Pulls API ─────────────────────────────────────────────────────────────

    /**
     * Lists pull requests in a repository matching the given query.
     * Maps to: GET /repos/{owner}/{repo}/pulls
     */
    default List<PullRequest> listPullRequests(String pat, RepoRef repo, ListPullRequestsQuery query)
    {
        throw new UnsupportedOperationException("listPullRequests not implemented");
    }

    /**
     * Creates a new pull request in the given repository.
     * Maps to: POST /repos/{owner}/{repo}/pulls
     */
    default PullRequest createPullRequest(String pat, RepoRef repo, CreatePullRequestCommand command)
    {
        throw new UnsupportedOperationException("createPullRequest not implemented");
    }

    /**
     * Retrieves a single pull request by number.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{pull_number}
     */
    default PullRequest getPullRequest(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("getPullRequest not implemented");
    }

    /**
     * Updates an existing pull request.
     * Maps to: PATCH /repos/{owner}/{repo}/pulls/{pull_number}
     */
    default PullRequest updatePullRequest(String pat, PullRequestRef pr, UpdatePullRequestCommand command)
    {
        throw new UnsupportedOperationException("updatePullRequest not implemented");
    }

    /**
     * Posts a new issue comment on a pull request (the same endpoint that
     * powers GitHub's "Add a comment" box at the bottom of a PR page).
     * Maps to: POST /repos/{owner}/{repo}/issues/{number}/comments
     */
    default void createIssueComment(String pat, PullRequestRef pr, String body)
    {
        throw new UnsupportedOperationException("createIssueComment not implemented");
    }

    /**
     * Replies inline to an existing per-line review comment thread. Maps to:
     * POST /repos/{owner}/{repo}/pulls/{n}/comments/{commentId}/replies
     *
     * <p>Returns the new reply as a {@link PrReviewThreadMessage} with the
     * GitHub-assigned id, server timestamps, and {@code inReplyTo} set to
     * the thread root — so callers can patch a cached detail in place
     * without an extra refetch.
     */
    default PrReviewThreadMessage replyToReviewComment(String pat, PullRequestRef pr, long rootCommentId, String body)
    {
        throw new UnsupportedOperationException("replyToReviewComment not implemented");
    }

    /**
     * Edits the body of a top-level issue / PR comment authored by the
     * authenticated user. Maps to:
     * PATCH /repos/{owner}/{repo}/issues/comments/{id}
     */
    default void editIssueComment(String pat, String owner, String repo, long commentId, String body)
    {
        throw new UnsupportedOperationException("editIssueComment not implemented");
    }

    /**
     * Edits the body of a per-line review comment authored by the
     * authenticated user. Maps to:
     * PATCH /repos/{owner}/{repo}/pulls/comments/{id}
     */
    default void editReviewComment(String pat, String owner, String repo, long commentId, String body)
    {
        throw new UnsupportedOperationException("editReviewComment not implemented");
    }

    /**
     * Adds an emoji reaction to a per-line review comment.
     * Maps to: POST /repos/{owner}/{repo}/pulls/comments/{commentId}/reactions
     *
     * @param content one of GitHub's reaction-content strings:
     *                {@code "+1", "-1", "laugh", "confused", "heart",
     *                "hooray", "rocket", "eyes"}.
     */
    default void addReviewCommentReaction(String pat, String owner, String repo, long commentId, String content)
    {
        throw new UnsupportedOperationException("addReviewCommentReaction not implemented");
    }

    /**
     * Adds an emoji reaction to a top-level issue / PR comment.
     * Maps to: POST /repos/{owner}/{repo}/issues/comments/{id}/reactions
     */
    default void addIssueCommentReaction(String pat, String owner, String repo, long commentId, String content)
    {
        throw new UnsupportedOperationException("addIssueCommentReaction not implemented");
    }

    /**
     * Per-thread GraphQL metadata (node id + resolved flag) for one PR.
     * Joined back to REST root comment ids by databaseId so callers can
     * persist this alongside the REST-fetched data.
     */
    record ReviewThreadMeta(long rootCommentDatabaseId, String graphqlNodeId, boolean resolved) {}

    /**
     * Lists per-thread resolution metadata via GraphQL — the REST API
     * doesn't expose this. The query is bounded by the same first-100
     * page size as the REST review-comments fetcher; PRs with more
     * threads are extremely rare but surface as truncated metadata.
     */
    default List<ReviewThreadMeta> fetchReviewThreadResolution(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("fetchReviewThreadResolution not implemented");
    }

    /**
     * Marks a review thread resolved on GitHub (GraphQL mutation).
     * {@code threadNodeId} is the opaque base64 id from the GraphQL
     * fetcher above — REST root comment ids do NOT work here.
     */
    default void resolveReviewThread(String pat, String threadNodeId)
    {
        throw new UnsupportedOperationException("resolveReviewThread not implemented");
    }

    /** Reverse of {@link #resolveReviewThread}. */
    default void unresolveReviewThread(String pat, String threadNodeId)
    {
        throw new UnsupportedOperationException("unresolveReviewThread not implemented");
    }

    /**
     * GitHub's suggested reviewers for one PR — the same list that
     * powers the conversation-page reviewers picker on github.com.
     * GraphQL-only; there's no REST equivalent. Derived from blame on
     * the touched files plus the actor's review history. Empty list on
     * GraphQL errors so callers don't have to special-case auth/network
     * failures for what is a non-essential affordance.
     */
    default List<SuggestedReviewer> fetchSuggestedReviewers(String pat, PullRequestRef pr)
    {
        return List.of();
    }

    /**
     * Merges a pull request.
     * Maps to: PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge
     */
    default MergeResult mergePullRequest(String pat, PullRequestRef pr, MergePullRequestCommand command)
    {
        throw new UnsupportedOperationException("mergePullRequest not implemented");
    }

    /**
     * Checks if a pull request has been merged.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{pull_number}/merge
     */
    default boolean isPullRequestMerged(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("isPullRequestMerged not implemented");
    }

    /**
     * Updates the branch of a pull request to the latest commit of the base branch.
     * Maps to: PUT /repos/{owner}/{repo}/pulls/{pull_number}/update-branch
     */
    default void updatePullRequestBranch(String pat, PullRequestRef pr, String expectedHeadSha)
    {
        throw new UnsupportedOperationException("updatePullRequestBranch not implemented");
    }

    // ── Reviews API ───────────────────────────────────────────────────────────

    /**
     * Creates a review on a pull request.
     * Maps to: POST /repos/{owner}/{repo}/pulls/{pull_number}/reviews
     */
    default PullRequestReview createReview(String pat, PullRequestRef pr, CreateReviewCommand command)
    {
        throw new UnsupportedOperationException("createReview not implemented");
    }

    /**
     * Lists submitted reviews for a pull request as full review objects.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{pull_number}/reviews
     */
    default List<PullRequestReview> listReviews(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("listReviews not implemented");
    }

    /**
     * Dismisses a submitted review.
     * Maps to: PUT /repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}/dismissals
     */
    default PullRequestReview dismissReview(String pat, PullRequestRef pr, long reviewId, String message)
    {
        throw new UnsupportedOperationException("dismissReview not implemented");
    }

    // ── Review Requests API ───────────────────────────────────────────────────

    /**
     * Lists the users and teams requested to review a pull request.
     * Maps to: GET /repos/{owner}/{repo}/pulls/{pull_number}/requested_reviewers
     */
    default RequestedReviewers getRequestedReviewers(String pat, PullRequestRef pr)
    {
        throw new UnsupportedOperationException("getRequestedReviewers not implemented");
    }

    /**
     * Requests review from one or more users and/or teams.
     * Maps to: POST /repos/{owner}/{repo}/pulls/{pull_number}/requested_reviewers
     */
    default PullRequest requestReviewers(String pat, PullRequestRef pr, RequestReviewersCommand command)
    {
        throw new UnsupportedOperationException("requestReviewers not implemented");
    }

    /**
     * Removes review requests.
     * Maps to: DELETE /repos/{owner}/{repo}/pulls/{pull_number}/requested_reviewers
     */
    default void removeRequestedReviewers(String pat, PullRequestRef pr, RequestReviewersCommand command)
    {
        throw new UnsupportedOperationException("removeRequestedReviewers not implemented");
    }

    // ── Repos and Users ───────────────────────────────────────────────────────

    /**
     * Lists issues (excluding pull requests) for a repository. {@code state}
     * maps directly to GitHub's filter — typically "open" or "closed".
     * Maps to: GET /repos/{owner}/{repo}/issues?state={state}
     */
    default List<RepoIssue> fetchRepoIssues(String pat, RepoRef repo, String state)
    {
        throw new UnsupportedOperationException("fetchRepoIssues not implemented");
    }

    /**
     * Fetches a single issue's full payload for the in-app detail page.
     * Comments are loaded separately by {@link #fetchIssueDetailComments}
     * so each method maps cleanly to one upstream HTTP call.
     * Maps to: GET /repos/{owner}/{repo}/issues/{number}
     */
    default IssueDetail fetchIssueDetail(String pat, RepoRef repo, int number)
    {
        throw new UnsupportedOperationException("fetchIssueDetail not implemented");
    }

    /**
     * Loads the conversation comments on one issue. Returns an empty
     * list when the issue has no comments yet.
     * Maps to: GET /repos/{owner}/{repo}/issues/{number}/comments
     */
    default List<IssueDetail.Comment> fetchIssueDetailComments(String pat, RepoRef repo, int number)
    {
        throw new UnsupportedOperationException("fetchIssueDetailComments not implemented");
    }

    /**
     * Loads the structural events on one issue — labeled, assigned,
     * milestoned, closed, reopened, renamed, mentioned, cross-referenced,
     * etc. {@code commented} events are intentionally filtered out by
     * the implementation since they're surfaced via
     * {@link #fetchIssueDetailComments} instead.
     * Maps to: GET /repos/{owner}/{repo}/issues/{number}/timeline
     */
    default List<IssueTimelineEvent> fetchIssueTimeline(String pat, RepoRef repo, int number)
    {
        throw new UnsupportedOperationException("fetchIssueTimeline not implemented");
    }

    /**
     * Posts a new comment on an issue and returns the GitHub-side
     * payload as a normalised {@link IssueDetail.Comment} so the
     * caller can append it directly to its rendered timeline.
     * Maps to: POST /repos/{owner}/{repo}/issues/{number}/comments
     */
    default IssueDetail.Comment postIssueComment(String pat, RepoRef repo, int number, String body)
    {
        throw new UnsupportedOperationException("postIssueComment not implemented");
    }

    /**
     * Toggles an issue's state to "open" or "closed". GitHub's PATCH
     * endpoint also accepts a state_reason ("completed" /
     * "not_planned" / "reopened"); we leave it out so GitHub picks
     * the sensible default per direction. Returns the updated
     * detail so callers can refresh their local view without a
     * second fetch.
     * Maps to: PATCH /repos/{owner}/{repo}/issues/{number}
     */
    default IssueDetail setIssueState(String pat, RepoRef repo, int number, String state)
    {
        throw new UnsupportedOperationException("setIssueState not implemented");
    }

    /**
     * Fetches repo-level metadata (description, stars, license, topics,
     * language byte-counts, etc). Combines GitHub's
     * {@code /repos/{owner}/{repo}} and {@code /repos/{owner}/{repo}/languages}
     * so the frontend hero card can render in one round-trip.
     */
    default RepoMeta fetchRepoMeta(String pat, RepoRef repo)
    {
        throw new UnsupportedOperationException("fetchRepoMeta not implemented");
    }

    /**
     * Fetches the most recent ~30 events for the repository (push, PR
     * opened/merged, issue comment, release, etc). Powers the
     * "Recent activity" feed on the repo detail page.
     */
    default List<RepoActivityItem> fetchRepoActivity(String pat, RepoRef repo)
    {
        throw new UnsupportedOperationException("fetchRepoActivity not implemented");
    }

    /**
     * Fetches the authenticated user's profile.
     * Maps to: GET /user
     */
    default UserProfile fetchUserProfile(String pat)
    {
        throw new UnsupportedOperationException("fetchUserProfile not implemented");
    }

    /**
     * Fetches the rolling 12-month contribution calendar for {@code login}
     * via GraphQL {@code contributionsCollection.contributionCalendar}.
     * Powers the "Your year in code" home-page heatmap. Implementations
     * may return an empty calendar (zero total, no weeks) on auth /
     * network failure since this is a non-essential affordance.
     */
    default ContributionCalendar fetchContributionCalendar(String pat, String login)
    {
        throw new UnsupportedOperationException("fetchContributionCalendar not implemented");
    }

    /**
     * Updates the authenticated user's profile.
     * Maps to: PATCH /user
     */
    default UserProfile updateUserProfile(String pat, String name, String bio, String location)
    {
        throw new UnsupportedOperationException("updateUserProfile not implemented");
    }

    /**
     * Lists repos the authenticated user owns, collaborates on, or has access to
     * through organization membership.
     * Maps to: GET /user/repos?sort=pushed&affiliation=owner,collaborator,organization_member
     */
    default List<UserRepo> fetchUserRepos(String pat)
    {
        throw new UnsupportedOperationException("fetchUserRepos not implemented");
    }

    /**
     * Searches GitHub public repositories matching a query string.
     * Maps to: GET /search/repositories?q={query}
     */
    default List<UserRepo> searchRepositories(String pat, String query)
    {
        throw new UnsupportedOperationException("searchRepositories not implemented");
    }

    /**
     * Searches GitHub users by login prefix. Returns up to 10 matches —
     * ordered by GitHub's relevance ranking. Used by the team editor's
     * autocomplete so members can be picked instead of hand-typed.
     * Maps to: GET /search/users?q={query}+in:login&type:user
     */
    default List<GitHubUserMatch> searchUsers(String pat, String query)
    {
        throw new UnsupportedOperationException("searchUsers not implemented");
    }

    /**
     * Lists organisations the authenticated user is a member of.
     * Maps to: GET /user/orgs
     */
    default List<UserOrg> fetchUserOrgs(String pat)
    {
        throw new UnsupportedOperationException("fetchUserOrgs not implemented");
    }

    /**
     * Whether the authenticated user has set up GitHub Sponsors. Backed by
     * the GraphQL field {@code viewer.hasSponsorsListing} since the REST
     * API doesn't expose this flag. Returns {@code false} on any error so
     * a failed lookup quietly hides the Sponsors row instead of throwing.
     */
    default boolean fetchHasSponsorsListing(String pat)
    {
        return false;
    }

    /**
     * Whether the authenticated PAT has push (write) access to the given
     * repository, as reported by GitHub's {@code permissions.push} field on
     * {@code GET /repos/{owner}/{repo}}. Used to gate the merge button on
     * the PR detail page. Returns {@code false} on any error so a failed
     * lookup leaves the button greyed-out — safer than enabling something
     * GitHub will reject.
     */
    default boolean fetchViewerCanWrite(String pat, RepoRef repo)
    {
        return false;
    }

    /**
     * Fetches the raw log text for an Actions check-run job. Maps to
     * {@code GET /repos/{owner}/{repo}/actions/jobs/{checkRunId}/logs},
     * which returns a 302 to a presigned blob URL with the plain-text
     * log. Returns {@link Optional#empty()} when the check isn't an
     * Actions job (external CIs use the Checks API but don't expose
     * logs to GitHub) or when the log has expired / isn't accessible
     * with the supplied PAT.
     */
    default Optional<String> fetchCheckRunLog(String pat, RepoRef repo, long checkRunId)
    {
        return Optional.empty();
    }

    /**
     * Toggles a pull request between draft and ready-for-review. The
     * REST API doesn't expose this, so the implementation goes through
     * GraphQL ({@code markPullRequestReadyForReview} /
     * {@code convertPullRequestToDraft}). Caller passes {@code true}
     * to mark as draft, {@code false} to publish.
     */
    default void setPullRequestDraft(String pat, PullRequestRef pr, boolean draft)
    {
        throw new UnsupportedOperationException("setPullRequestDraft not implemented");
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /**
     * Fetches public events performed by a user, up to {@code limit} items.
     * No time-period filtering is applied here; callers decide what to keep.
     * Maps to: GET /users/{login}/events?per_page={limit}
     */
    default List<RecentEvent> fetchUserEvents(String pat, String login, int limit)
    {
        throw new UnsupportedOperationException("fetchUserEvents not implemented");
    }

    /**
     * Fetches events received by a user (from people they follow), up to {@code limit} items.
     * Maps to: GET /users/{login}/received_events?per_page={limit}
     */
    default List<RecentEvent> fetchReceivedEvents(String pat, String login, int limit)
    {
        throw new UnsupportedOperationException("fetchReceivedEvents not implemented");
    }
}
