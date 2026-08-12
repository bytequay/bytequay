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
package com.bytequay.app.repository.github;

import com.bytequay.app.domain.ContributionCalendar;
import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.UserCommitSummary;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserRepo;
import com.bytequay.app.repository.GitHubAccountRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubAccountClient
        implements GitHubAccountRepository {
    private static final Logger log = LoggerFactory.getLogger(GitHubAccountClient.class);

    private final RestClient gitHubRestClient;
    private final RestClient graphqlRestClient;

    public GitHubAccountClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient") RestClient gitHubGraphQLRestClient) {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphqlRestClient =
                requireNonNull(gitHubGraphQLRestClient, "gitHubGraphQLRestClient is null");
    }

    @Override
    public UserProfile fetchUserProfile(String pat) {
        try {
            GitHubUserProfileResponse response =
                    gitHubRestClient
                            .get()
                            .uri("/user")
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubUserProfileResponse.class);
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "Empty response from GitHub /user");
            }
            return toUserProfile(response);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public UserProfile updateUserProfile(String pat, String name, String bio, String location) {
        Map<String, Object> body = Maps.newHashMap();
        if (name != null) {
            body.put("name", name);
        }
        if (bio != null) {
            body.put("bio", bio);
        }
        if (location != null) {
            body.put("location", location);
        }
        try {
            GitHubUserProfileResponse response =
                    gitHubRestClient
                            .patch()
                            .uri("/user")
                            .header("Authorization", authorization(pat))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(GitHubUserProfileResponse.class);
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "Empty response from GitHub PATCH /user");
            }
            return toUserProfile(response);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static UserProfile toUserProfile(GitHubUserProfileResponse response) {
        // hasSponsors defaults to false here — RepoService overlays the value
        // from the separate GraphQL `viewer.hasSponsorsListing` call. Keeping
        // the GraphQL out of the REST mapper avoids dragging a separate
        // request through every code path that just wants the basics.
        return new UserProfile(
                response.login(),
                response.name(),
                response.avatarUrl(),
                response.htmlUrl(),
                response.publicRepos(),
                response.followers(),
                response.following(),
                response.bio(),
                response.location(),
                response.company(),
                response.email(),
                false);
    }

    @Override
    public ContributionCalendar fetchContributionCalendar(String pat, String login) {
        // contributionsCollection without from/to defaults to the trailing
        // 12 months — exactly what the home-page card claims in its
        // "Last 12 months" badge. The {color} field comes back as
        // GitHub's own palette hex, so the UI inherits whatever palette
        // GitHub ships without us having to bucket counts client-side.
        String query =
                "query($login: String!) {"
                        + "  user(login: $login) {"
                        + "    contributionsCollection {"
                        + "      contributionCalendar {"
                        + "        totalContributions"
                        + "        weeks {"
                        + "          contributionDays { date contributionCount color }"
                        + "        }"
                        + "      }"
                        + "    }"
                        + "  }"
                        + "}";
        Map<String, Object> body =
                ImmutableMap.of("query", query, "variables", ImmutableMap.of("login", login));
        try {
            ContributionGqlResponse response =
                    graphqlRestClient
                            .post()
                            .header("Authorization", authorization(pat))
                            .body(body)
                            .retrieve()
                            .body(ContributionGqlResponse.class);
            if (response == null
                    || response.data() == null
                    || response.data().user() == null
                    || response.data().user().contributionsCollection() == null
                    || response.data().user().contributionsCollection().contributionCalendar()
                            == null) {
                // GitHub's GraphQL hop signals failure with HTTP 200 +
                // {data:null, errors:[...]}, so RestClientResponseException
                // never fires here. The two we see in practice are
                // FORBIDDEN (classic PAT missing read:user, or a
                // fine-grained PAT — contributionsCollection isn't
                // exposed to fine-grained tokens at all) and
                // NOT_FOUND for renamed/deleted logins.
                List<ContributionGqlError> errors = response == null ? null : response.errors();
                if (errors != null && !errors.isEmpty()) {
                    throw new IllegalStateException(
                            "GitHub contribution calendar returned errors: " + errors);
                }
                throw new IllegalStateException("GitHub contribution calendar returned no data");
            }
            ContributionGqlCalendar cal =
                    response.data().user().contributionsCollection().contributionCalendar();
            List<ContributionGqlWeek> rawWeeks =
                    cal.weeks() != null ? cal.weeks() : ImmutableList.of();
            if (rawWeeks.isEmpty()) {
                throw new IllegalStateException("GitHub contribution calendar returned no weeks");
            }
            List<ContributionCalendar.Week> weeks =
                    rawWeeks.stream()
                            .map(
                                    w ->
                                            new ContributionCalendar.Week(
                                                    (w.contributionDays() == null
                                                                    ? ImmutableList
                                                                            .<ContributionGqlDay>
                                                                                    of()
                                                                    : w.contributionDays())
                                                            .stream()
                                                                    .map(
                                                                            d ->
                                                                                    new ContributionCalendar
                                                                                            .Day(
                                                                                            LocalDate
                                                                                                    .parse(
                                                                                                            d
                                                                                                                    .date()),
                                                                                            d
                                                                                                                    .contributionCount()
                                                                                                            == null
                                                                                                    ? 0
                                                                                                    : d
                                                                                                            .contributionCount(),
                                                                                            d
                                                                                                    .color()))
                                                                    .collect(toImmutableList())))
                            .collect(toImmutableList());
            int total = cal.totalContributions() == null ? 0 : cal.totalContributions();
            return new ContributionCalendar(total, weeks);
        }
        catch (RestClientResponseException e) {
            throw new IllegalStateException("GitHub contribution calendar request failed", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContributionGqlResponse(ContributionGqlData data, List<ContributionGqlError> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContributionGqlError(String type, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContributionGqlData(ContributionGqlUser user) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContributionGqlUser(ContributionGqlCollection contributionsCollection) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContributionGqlCollection(ContributionGqlCalendar contributionCalendar) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContributionGqlCalendar(Integer totalContributions, List<ContributionGqlWeek> weeks) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContributionGqlWeek(List<ContributionGqlDay> contributionDays) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContributionGqlDay(String date, Integer contributionCount, String color) {}

    @Override
    public List<UserCommitSummary> fetchUserCommitsOnDate(
            String pat, String login, String isoDate) {
        // GitHub's /search/commits backs the heatmap-cube popover: list
        // commits authored by {login} on a single UTC calendar day. We
        // ask for a tight window (author-date:DATE..DATE) and the
        // newest-first ordering so the popover reads as "what did I
        // ship today" rather than a sorted blob. per_page=30 is a
        // glance, not an audit log — anything past that, the user can
        // click through to GitHub.
        requirePat(pat);
        String query = "author:" + login + " author-date:" + isoDate;
        try {
            CommitSearchResponse response =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/search/commits")
                                                    .queryParam("q", query)
                                                    .queryParam("per_page", 30)
                                                    .queryParam("sort", "author-date")
                                                    .queryParam("order", "desc")
                                                    .build())
                            .header("Authorization", authorization(pat))
                            .header("Accept", "application/vnd.github+json")
                            .retrieve()
                            .body(CommitSearchResponse.class);
            if (response == null || response.items() == null) {
                return ImmutableList.of();
            }
            return response.items().stream()
                    .map(GitHubAccountClient::toUserCommitSummary)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            // Non-essential affordance — degrade silently rather than
            // breaking the home-page click; the popover renders an
            // empty list.
            log.warn("commit search for {} on {} failed: {}", login, isoDate, e.getMessage());
            return ImmutableList.of();
        }
    }

    private static UserCommitSummary toUserCommitSummary(CommitSearchItem item) {
        String repoFullName = item.repository() != null ? item.repository().fullName() : "";
        String rawMessage =
                item.commit() != null && item.commit().message() != null
                        ? item.commit().message()
                        : "";
        // Only the first line — the cube popover is one row per commit.
        int newline = rawMessage.indexOf('\n');
        String shortMessage = newline >= 0 ? rawMessage.substring(0, newline) : rawMessage;
        Instant authoredAt = null;
        if (item.commit() != null
                && item.commit().author() != null
                && item.commit().author().date() != null) {
            try {
                authoredAt = Instant.parse(item.commit().author().date());
            }
            catch (Exception ignored) {
                // Bad timestamp from GitHub is non-fatal — list still renders.
            }
        }
        return new UserCommitSummary(
                item.sha() == null ? "" : item.sha(),
                repoFullName,
                shortMessage,
                item.htmlUrl() == null ? "" : item.htmlUrl(),
                authoredAt);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitSearchResponse(
            @JsonProperty("total_count") Integer totalCount, List<CommitSearchItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitSearchItem(
            String sha,
            @JsonProperty("html_url") String htmlUrl,
            CommitSearchCommit commit,
            CommitSearchRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitSearchCommit(String message, CommitSearchAuthor author) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitSearchAuthor(String name, String email, String date) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommitSearchRepo(@JsonProperty("full_name") String fullName) {}

    @Override
    public List<UserRepo> fetchUserRepos(String pat) {
        // Page through /user/repos until GitHub returns fewer than PER_PAGE
        // items (or we hit MAX_PAGES as a safety net). Users at big companies
        // commonly have several hundred repos via org_member affiliation.
        int perPage = 100;
        int maxPages = 10;
        ImmutableList.Builder<UserRepo> all = ImmutableList.builder();
        for (int page = 1; page <= maxPages; page++) {
            int currentPage = page;
            try {
                List<GitHubUserRepoItem> items =
                        gitHubRestClient
                                .get()
                                .uri(
                                        u ->
                                                u.path("/user/repos")
                                                        .queryParam("sort", "pushed")
                                                        // Include org repos the user has access to
                                                        // — without
                                                        // `organization_member`, company repos
                                                        // never appear in
                                                        // the list regardless of the token's scope.
                                                        .queryParam(
                                                                "affiliation",
                                                                "owner,collaborator,organization_member")
                                                        .queryParam("per_page", perPage)
                                                        .queryParam("page", currentPage)
                                                        .build())
                                .header("Authorization", authorization(pat))
                                .retrieve()
                                .body(new ParameterizedTypeReference<>() {});
                if (items == null || items.isEmpty()) {
                    break;
                }
                items.stream().map(GitHubAccountClient::toUserRepo).forEach(all::add);
                if (items.size() < perPage) {
                    break;
                }
            }
            catch (RestClientResponseException e) {
                throw toReadableException(e);
            }
        }
        return all.build();
    }

    private static UserRepo toUserRepo(GitHubUserRepoItem item) {
        String owner =
                Optional.ofNullable(item.owner()).map(GitHubUserRepoItem.Owner::login).orElse("");
        return new UserRepo(
                owner,
                item.name(),
                item.fullName(),
                item.description(),
                item.language(),
                item.stargazersCount());
    }

    @Override
    public boolean fetchHasSponsorsListing(String pat) {
        // The REST API doesn't expose Sponsors enrolment, so we fall back to
        // a single-field GraphQL query. Any error (network, scope, GitHub
        // unauthenticated) is swallowed so a flaky GraphQL endpoint can't
        // break the rest of the profile load — we just hide the row.
        try {
            String query = "{\"query\":\"query { viewer { hasSponsorsListing } }\"}";
            GitHubViewerSponsorsResponse response =
                    gitHubRestClient
                            .post()
                            .uri("/graphql")
                            .header("Authorization", authorization(pat))
                            .header("Content-Type", "application/json")
                            .body(query)
                            .retrieve()
                            .body(GitHubViewerSponsorsResponse.class);
            return response != null
                    && response.data() != null
                    && response.data().viewer() != null
                    && response.data().viewer().hasSponsorsListing();
        }
        catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean fetchViewerCanWrite(String pat, RepoRef repo) {
        // /repos/{owner}/{repo} returns a `permissions` block when called
        // with an authenticated PAT; `permissions.push` is true for users
        // with write/maintain/admin access. Swallowing errors keeps the
        // merge button greyed-out on any failure (404, rate-limit, scope) —
        // which is the safe default.
        try {
            GitHubRepoPermissionsResponse response =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/repos/{owner}/{repo}")
                                                    .build(repo.owner(), repo.repo()))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubRepoPermissionsResponse.class);
            return response != null
                    && response.permissions() != null
                    && Boolean.TRUE.equals(response.permissions().push());
        }
        catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean fetchCollaboratorCanWrite(String pat, RepoRef repo, String login) {
        // /repos/{owner}/{repo}/collaborators/{login}/permission returns the
        // collaborator's permission ("admin" | "write" | "read" | "none";
        // maintain→write, triage→read in this legacy field). admin/write means
        // push access — the same test that lights GitHub's green approval mark.
        // Errors (404 for a non-collaborator, rate-limit) fall through to
        // false, so an unconfirmable reviewer simply doesn't count.
        try {
            GitHubCollaboratorPermissionResponse response =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path(
                                                            "/repos/{owner}/{repo}/collaborators/{login}/permission")
                                                    .build(repo.owner(), repo.repo(), login))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubCollaboratorPermissionResponse.class);
            if (response == null || response.permission() == null) {
                return false;
            }
            String permission = response.permission();
            return "admin".equals(permission) || "write".equals(permission);
        }
        catch (Exception e) {
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubCollaboratorPermissionResponse(String permission) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubRepoPermissionsResponse(GitHubRepoPermissions permissions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubRepoPermissions(
            Boolean admin, Boolean maintain, Boolean push, Boolean triage, Boolean pull) {}

    @Override
    public List<UserOrg> fetchUserOrgs(String pat) {
        try {
            List<GitHubUserOrgItem> items =
                    gitHubRestClient
                            .get()
                            .uri(u -> u.path("/user/orgs").queryParam("per_page", 100).build())
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (items == null) {
                return ImmutableList.of();
            }
            return items.stream()
                    // /user/orgs doesn't ship html_url — synthesise from login.
                    .map(
                            o ->
                                    new UserOrg(
                                            o.login(),
                                            o.avatarUrl(),
                                            "https://github.com/" + o.login(),
                                            o.description()))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<UserRepo> searchRepositories(String pat, String query) {
        try {
            GitHubRepoSearchResponse response =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/search/repositories")
                                                    .queryParam("q", query)
                                                    .queryParam("per_page", 30)
                                                    .build())
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubRepoSearchResponse.class);
            if (response == null || response.items() == null) {
                return ImmutableList.of();
            }
            return response.items().stream()
                    .map(GitHubAccountClient::toUserRepoFromSearch)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public Optional<UserRepo> fetchRepository(String pat, String owner, String repo) {
        try {
            GitHubUserRepoItem item =
                    gitHubRestClient
                            .get()
                            .uri("/repos/{owner}/{repo}", owner, repo)
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubUserRepoItem.class);
            return Optional.ofNullable(item).map(GitHubAccountClient::toUserRepoFromSearch);
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw toReadableException(e);
        }
    }

    private static UserRepo toUserRepoFromSearch(GitHubUserRepoItem item) {
        String owner =
                Optional.ofNullable(item.owner()).map(GitHubUserRepoItem.Owner::login).orElse("");
        return new UserRepo(
                owner,
                item.name(),
                item.fullName(),
                item.description(),
                item.language(),
                item.stargazersCount());
    }

    @Override
    public List<GitHubUserMatch> searchUsers(String pat, String query) {
        // `in:login` scopes the match to the login text rather than name /
        // bio / email; `type:user` filters out organisations so the team
        // roster doesn't accidentally pull in an org account. per_page is
        // small because the dropdown only renders ~10 rows.
        String q = query.trim() + " in:login type:user";
        try {
            GitHubUserSearchResponse response =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/search/users")
                                                    .queryParam("q", q)
                                                    .queryParam("per_page", 10)
                                                    .build())
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubUserSearchResponse.class);
            if (response == null || response.items() == null) {
                return ImmutableList.of();
            }
            return response.items().stream()
                    .filter(i -> i.login() != null)
                    // Skip organisation accounts even if the search returned
                    // them (the qualifier above usually filters but isn't
                    // 100% — defence in depth).
                    .filter(i -> !"Organization".equalsIgnoreCase(i.type()))
                    .map(i -> new GitHubUserMatch(i.login(), i.avatarUrl(), null))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @Override
    public List<RecentEvent> fetchUserEvents(String pat, String login, int limit) {
        return fetchEventList("/users/{login}/events", pat, login, limit);
    }

    @Override
    public List<RecentEvent> fetchReceivedEvents(String pat, String login, int limit) {
        return fetchEventList("/users/{login}/received_events", pat, login, limit);
    }

    private List<RecentEvent> fetchEventList(
            String pathTemplate, String pat, String login, int limit) {
        try {
            List<GitHubEventItem> items =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path(pathTemplate)
                                                    .queryParam("per_page", Math.min(limit, 100))
                                                    .build(login))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (items == null) {
                return ImmutableList.of();
            }
            return items.stream()
                    .filter(e -> e.createdAt() != null)
                    .limit(limit)
                    .map(GitHubEventMapper::toRecentEvent)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            return ImmutableList.of();
        }
    }
}
