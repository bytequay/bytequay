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
import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.UserCommitSummary;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserRepo;

import java.util.List;
import java.util.Optional;

/** GitHub account, organization, and user activity API operations. */
public interface GitHubAccountRepository {
    /** Fetches the authenticated user's profile. Maps to: GET /user */
    default UserProfile fetchUserProfile(String pat) {
        throw new UnsupportedOperationException("fetchUserProfile not implemented");
    }

    /**
     * Fetches the rolling 12-month contribution calendar for {@code login} via GraphQL {@code
     * contributionsCollection.contributionCalendar}. Powers the "Your year in code" home-page
     * heatmap. Implementations may return an empty calendar (zero total, no weeks) on auth /
     * network failure since this is a non-essential affordance.
     */
    default ContributionCalendar fetchContributionCalendar(String pat, String login) {
        throw new UnsupportedOperationException("fetchContributionCalendar not implemented");
    }

    /**
     * Lists commits authored by {@code login} on a specific calendar day (UTC ISO {@code
     * yyyy-MM-dd}). Powers the click-through from the home-page heatmap to the day's actual
     * commits. Maps to the REST {@code /search/commits?q=author:LOGIN+author-date:DATE} endpoint;
     * implementations cap at one page (~30 rows) — the cube popover is meant to be a glance, not a
     * full transcript.
     */
    default List<UserCommitSummary> fetchUserCommitsOnDate(
            String pat, String login, String isoDate) {
        throw new UnsupportedOperationException("fetchUserCommitsOnDate not implemented");
    }

    /** Updates the authenticated user's profile. Maps to: PATCH /user */
    default UserProfile updateUserProfile(String pat, String name, String bio, String location) {
        throw new UnsupportedOperationException("updateUserProfile not implemented");
    }

    /**
     * Lists repos the authenticated user owns, collaborates on, or has access to through
     * organization membership. Maps to: GET
     * /user/repos?sort=pushed&affiliation=owner,collaborator,organization_member
     */
    default List<UserRepo> fetchUserRepos(String pat) {
        throw new UnsupportedOperationException("fetchUserRepos not implemented");
    }

    /**
     * Searches GitHub public repositories matching a query string. Maps to: GET
     * /search/repositories?q={query}
     */
    default List<UserRepo> searchRepositories(String pat, String query) {
        throw new UnsupportedOperationException("searchRepositories not implemented");
    }

    /**
     * Exact repository lookup by owner + name. Maps to: GET /repos/{owner}/{repo}. Returns empty
     * when the repo doesn't exist or the PAT can't see it. Used so a user who types the precise
     * {@code owner/name} finds it directly — GitHub's repository search doesn't parse the slash, so
     * a search would miss it.
     */
    default Optional<UserRepo> fetchRepository(String pat, String owner, String repo) {
        throw new UnsupportedOperationException("fetchRepository not implemented");
    }

    /**
     * Searches GitHub users by login prefix. Returns up to 10 matches — ordered by GitHub's
     * relevance ranking. Used by the team editor's autocomplete so members can be picked instead of
     * hand-typed. Maps to: GET /search/users?q={query}+in:login&type:user
     */
    default List<GitHubUserMatch> searchUsers(String pat, String query) {
        throw new UnsupportedOperationException("searchUsers not implemented");
    }

    /** Lists organisations the authenticated user is a member of. Maps to: GET /user/orgs */
    default List<UserOrg> fetchUserOrgs(String pat) {
        throw new UnsupportedOperationException("fetchUserOrgs not implemented");
    }

    /**
     * Whether the authenticated user has set up GitHub Sponsors. Backed by the GraphQL field {@code
     * viewer.hasSponsorsListing} since the REST API doesn't expose this flag. Returns {@code false}
     * on any error so a failed lookup quietly hides the Sponsors row instead of throwing.
     */
    default boolean fetchHasSponsorsListing(String pat) {
        return false;
    }

    /**
     * Whether the authenticated PAT has push (write) access to the given repository, as reported by
     * GitHub's {@code permissions.push} field on {@code GET /repos/{owner}/{repo}}. Used to gate
     * the merge button on the PR detail page. Returns {@code false} on any error so a failed lookup
     * leaves the button greyed-out — safer than enabling something GitHub will reject.
     */
    default boolean fetchViewerCanWrite(String pat, RepoRef repo) {
        return false;
    }

    /**
     * Whether {@code login} has write (push) permission on the repo, from {@code GET
     * /repos/{owner}/{repo}/collaborators/{login}/permission}. Used to decide which approvals count
     * toward a task's minimum-approvals gate (GitHub's green vs. grey approval marks). Returns
     * {@code false} on any error — a reviewer we can't confirm simply doesn't count.
     */
    default boolean fetchCollaboratorCanWrite(String pat, RepoRef repo, String login) {
        return false;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /**
     * Fetches public events performed by a user, up to {@code limit} items. No time-period
     * filtering is applied here; callers decide what to keep. Maps to: GET
     * /users/{login}/events?per_page={limit}
     */
    default List<RecentEvent> fetchUserEvents(String pat, String login, int limit) {
        throw new UnsupportedOperationException("fetchUserEvents not implemented");
    }

    /**
     * Fetches events received by a user (from people they follow), up to {@code limit} items. Maps
     * to: GET /users/{login}/received_events?per_page={limit}
     */
    default List<RecentEvent> fetchReceivedEvents(String pat, String login, int limit) {
        throw new UnsupportedOperationException("fetchReceivedEvents not implemented");
    }
}
