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

import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.IssueTimelineEvent;
import com.bytequay.app.domain.RepoActivityItem;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoIssueIntakePage;
import com.bytequay.app.domain.RepoIssuePage;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/** GitHub issue and repository metadata API operations. */
public interface GitHubIssueRepository {
    // ── Repos and Users ───────────────────────────────────────────────────────

    /**
     * Lists issues (excluding pull requests) for a repository. {@code state} maps directly to
     * GitHub's filter — typically "open" or "closed". Maps to: GET
     * /repos/{owner}/{repo}/issues?state={state}
     */
    default List<RepoIssue> fetchRepoIssues(String pat, RepoRef repo, String state) {
        throw new UnsupportedOperationException("fetchRepoIssues not implemented");
    }

    /**
     * Lists a creation-ordered page for the workspace issue-intake cursor. Page boundaries include
     * PRs and closed issues, while {@code openIssues} excludes both. Maps to one GitHub issues-list
     * request.
     */
    default RepoIssueIntakePage fetchRepoIssueIntakePage(
            String pat, RepoRef repo, int page, int perPage) {
        throw new UnsupportedOperationException("fetchRepoIssueIntakePage not implemented");
    }

    /** Lists one all-state issue page, excluding pull requests. */
    default RepoIssuePage fetchRepoIssuePage(String pat, RepoRef repo, int page, int perPage) {
        throw new UnsupportedOperationException("fetchRepoIssuePage not implemented");
    }

    /**
     * Creates an issue and returns its normalised list-row payload. Maps to: POST
     * /repos/{owner}/{repo}/issues
     */
    default RepoIssue createIssue(String pat, RepoRef repo, String title, String body) {
        throw new UnsupportedOperationException("createIssue not implemented");
    }

    /**
     * Fetches a single issue's full payload for the in-app detail page. Comments are loaded
     * separately by {@link #fetchIssueDetailComments} so each method maps cleanly to one upstream
     * HTTP call. Maps to: GET /repos/{owner}/{repo}/issues/{number}
     */
    default IssueDetail fetchIssueDetail(String pat, RepoRef repo, int number) {
        throw new UnsupportedOperationException("fetchIssueDetail not implemented");
    }

    /**
     * Loads the conversation comments on one issue. Returns an empty list when the issue has no
     * comments yet. Maps to: GET /repos/{owner}/{repo}/issues/{number}/comments
     */
    default List<IssueDetail.Comment> fetchIssueDetailComments(
            String pat, RepoRef repo, int number) {
        throw new UnsupportedOperationException("fetchIssueDetailComments not implemented");
    }

    /**
     * Loads the structural events on one issue — labeled, assigned, milestoned, closed, reopened,
     * renamed, mentioned, cross-referenced, etc. {@code commented} events are intentionally
     * filtered out by the implementation since they're surfaced via {@link
     * #fetchIssueDetailComments} instead. Maps to: GET
     * /repos/{owner}/{repo}/issues/{number}/timeline
     */
    default List<IssueTimelineEvent> fetchIssueTimeline(String pat, RepoRef repo, int number) {
        throw new UnsupportedOperationException("fetchIssueTimeline not implemented");
    }

    /**
     * Returns the viewer's subscription state on one issue. True iff GitHub returns 200 with {@code
     * subscribed=true}; false on 404 (default state) or on an explicit ignore. Maps to: GET
     * /repos/{owner}/{repo}/issues/{number}/subscription
     */
    default boolean fetchIssueSubscription(String pat, RepoRef repo, int number) {
        throw new UnsupportedOperationException("fetchIssueSubscription not implemented");
    }

    /**
     * Toggles the viewer's subscription on one issue. {@code subscribe=true} PUTs {@code
     * {subscribed: true}}; {@code subscribe=false} DELETEs to return to the default state.
     */
    default void setIssueSubscription(String pat, RepoRef repo, int number, boolean subscribe) {
        throw new UnsupportedOperationException("setIssueSubscription not implemented");
    }

    /**
     * Posts a new comment on an issue and returns the GitHub-side payload as a normalised {@link
     * IssueDetail.Comment} so the caller can append it directly to its rendered timeline. Maps to:
     * POST /repos/{owner}/{repo}/issues/{number}/comments
     */
    default IssueDetail.Comment postIssueComment(
            String pat, RepoRef repo, int number, String body) {
        throw new UnsupportedOperationException("postIssueComment not implemented");
    }

    /**
     * Toggles an issue's state to "open" or "closed". GitHub's PATCH endpoint also accepts a
     * state_reason ("completed" / "not_planned" / "reopened"); we leave it out so GitHub picks the
     * sensible default per direction. Returns the updated detail so callers can refresh their local
     * view without a second fetch. Maps to: PATCH /repos/{owner}/{repo}/issues/{number}
     */
    default IssueDetail setIssueState(String pat, RepoRef repo, int number, String state) {
        throw new UnsupportedOperationException("setIssueState not implemented");
    }

    /**
     * Fetches repo-level metadata (description, stars, license, topics, language byte-counts, etc).
     * Combines GitHub's {@code /repos/{owner}/{repo}} and {@code /repos/{owner}/{repo}/languages}
     * so the frontend hero card can render in one round-trip.
     */
    default RepoMeta fetchRepoMeta(String pat, RepoRef repo) {
        throw new UnsupportedOperationException("fetchRepoMeta not implemented");
    }

    /**
     * Optional wrapper for {@link #fetchRepoMeta}. Kept here so callers that probe for an existing
     * fork don't have to know how the GitHub client maps 404s.
     */
    default Optional<RepoMeta> findRepoMeta(String pat, RepoRef repo) {
        try {
            return Optional.ofNullable(fetchRepoMeta(pat, repo));
        }
        catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Creates a fork for the authenticated user. Maps to: POST /repos/{owner}/{repo}/forks. */
    default void createFork(String pat, RepoRef repo) {
        throw new UnsupportedOperationException("createFork not implemented");
    }

    /**
     * Fetches the most recent ~30 events for the repository (push, PR opened/merged, issue comment,
     * release, etc). Powers the "Recent activity" feed on the repo detail page.
     */
    default List<RepoActivityItem> fetchRepoActivity(String pat, RepoRef repo) {
        throw new UnsupportedOperationException("fetchRepoActivity not implemented");
    }
}
