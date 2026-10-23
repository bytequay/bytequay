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

import com.bytequay.core.domain.ContributionCalendar;
import com.bytequay.core.domain.RecentEvent;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserCommitSummary;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.UserRepo;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.get;
import static com.bytequay.app.repository.github.GitHubApiSupport.graphql;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubAccountClient
{
    private static final Logger log = LoggerFactory.getLogger(GitHubAccountClient.class);

    private final RestClient gitHubRestClient;
    private final RestClient graphqlRestClient;

    public GitHubAccountClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient") RestClient gitHubGraphQLRestClient) {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphqlRestClient = requireNonNull(gitHubGraphQLRestClient, "gitHubGraphQLRestClient is null");
    }

    public UserProfile fetchUserProfile(String pat) {
        return translating(
                () -> {
                    GitHubUserProfileResponse response =
                            requireBody(get(gitHubRestClient, pat, "/user")
                                    .retrieve()
                                    .body(GitHubUserProfileResponse.class), "Empty response from GitHub /user");
                    return toUserProfile(response);
                });
    }

    public UserProfile fetchUserProfile(String pat, String login) {
        return translating(
                () -> {
                    GitHubUserProfileResponse response =
                            requireBody(get(gitHubRestClient, pat, "/users/{login}", login)
                                    .retrieve()
                                    .body(GitHubUserProfileResponse.class), "Empty response from GitHub /users/{login}");
                    return toUserProfile(response);
                });
    }

    /** Stable identity for private local circles; absence selects the authenticated account. */
    public UserIdentity fetchUserIdentity(String pat, Optional<String> login)
    {
        requireNonNull(login, "login is null");
        UserIdentityResponse response = translating(() -> requireBody(
                (login.isPresent() ? get(gitHubRestClient, pat, "/users/{login}", login.orElseThrow()) : get(gitHubRestClient, pat, "/user"))
                        .retrieve().body(UserIdentityResponse.class), "GitHub returned no user identity"));
        checkState(response.id() > 0 && response.login() != null && !response.login().isBlank(), "GitHub returned an invalid user identity");
        if (!"User".equals(response.type()) && !"Bot".equals(response.type())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "Choose a GitHub person, not an organization");
        }
        return new UserIdentity(response.id(), response.login(), Optional.ofNullable(response.name()), Optional.ofNullable(response.avatarUrl()));
    }

    public record UserIdentity(long id, String login, Optional<String> name, Optional<String> avatarUrl)
    {
        public UserIdentity
        {
            checkState(id > 0, "GitHub user ID must be positive");
            requireNonNull(login, "login is null");
            requireNonNull(name, "name is null");
            requireNonNull(avatarUrl, "avatarUrl is null");
        }
    }

    private record UserIdentityResponse(long id, String login, String name, @JsonProperty("avatar_url") String avatarUrl, String type) {}

    private static UserProfile toUserProfile(GitHubUserProfileResponse response) {
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
                response.email());
    }

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
        try {
            ContributionGqlResponse response = graphql(graphqlRestClient, pat, query, ImmutableMap.of("login", login))
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
                    throw new IllegalStateException("GitHub contribution calendar returned errors: " + errors);
                }
                throw new IllegalStateException("GitHub contribution calendar returned no data");
            }
            ContributionGqlCalendar cal = response.data().user().contributionsCollection().contributionCalendar();
            List<ContributionGqlWeek> rawWeeks = cal.weeks() != null ? cal.weeks() : ImmutableList.of();
            checkState(!rawWeeks.isEmpty(), "GitHub contribution calendar returned no weeks");
            List<ContributionCalendar.Week> weeks =
                    rawWeeks.stream()
                            .map(
                                    w ->
                                            new ContributionCalendar.Week(
                                                    (w.contributionDays() == null
                                                                    ? List.<ContributionGqlDay>of()
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

    record ContributionGqlResponse(ContributionGqlData data, List<ContributionGqlError> errors) {}

    record ContributionGqlError(String type, String message) {}

    record ContributionGqlData(ContributionGqlUser user) {}

    record ContributionGqlUser(ContributionGqlCollection contributionsCollection) {}

    record ContributionGqlCollection(ContributionGqlCalendar contributionCalendar) {}

    record ContributionGqlCalendar(Integer totalContributions, List<ContributionGqlWeek> weeks) {}

    record ContributionGqlWeek(List<ContributionGqlDay> contributionDays) {}

    record ContributionGqlDay(String date, Integer contributionCount, String color) {}

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
                    get(gitHubRestClient, pat, u ->
                                    u.path("/search/commits")
                                            .queryParam("q", query)
                                            .queryParam("per_page", 30)
                                            .queryParam("sort", "author-date")
                                            .queryParam("order", "desc")
                                            .build())
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
        String rawMessage = item.commit() != null && item.commit().message() != null ? item.commit().message() : "";
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
        return new UserCommitSummary(item.sha() == null ? "" : item.sha(), repoFullName, shortMessage, item.htmlUrl() == null ? "" : item.htmlUrl(), authoredAt);
    }

    record CommitSearchResponse(List<CommitSearchItem> items) {}

    record CommitSearchItem(
            String sha,
            @JsonProperty("html_url") String htmlUrl,
            CommitSearchCommit commit,
            CommitSearchRepo repository) {}

    record CommitSearchCommit(String message, CommitSearchAuthor author) {}

    record CommitSearchAuthor(String date) {}

    record CommitSearchRepo(@JsonProperty("full_name") String fullName) {}

    public List<UserRepo> fetchUserRepos(String pat) {
        ImmutableList.Builder<UserRepo> all = ImmutableList.builder();
        GitHubPaginator.forEachPage(10, page -> translating(() -> get(gitHubRestClient, pat, uri -> uri.path("/user/repos")
                .queryParam("sort", "pushed")
                // Include repositories reached through organization membership as well as direct access.
                .queryParam("affiliation", "owner,collaborator,organization_member")
                .queryParam("per_page", GitHubPaginator.PAGE_SIZE)
                .queryParam("page", page)
                .build()).retrieve().body(new ParameterizedTypeReference<List<GitHubUserRepoItem>>() {})),
                items -> items.stream().map(GitHubAccountClient::toUserRepoFromSearch).forEach(all::add));
        return all.build();
    }

    public boolean fetchViewerCanWrite(String pat, RepoRef repo) {
        // Swallowing errors keeps the merge button greyed-out on any failure (404, rate-limit, scope), which is the safe default.
        try {
            return fetchRepository(pat, repo.owner(), repo.repo()).map(UserRepo::viewerCanPush).orElse(false);
        }
        catch (Exception e) {
            return false;
        }
    }

    public List<UserRepo> searchRepositories(String pat, String query) {
        return translating(
                () -> {
                    GitHubRepoSearchResponse response =
                            get(gitHubRestClient, pat, u ->
                                            u.path("/search/repositories")
                                                    .queryParam("q", query)
                                                    .queryParam("per_page", 30)
                                                    .build())
                                    .retrieve()
                                    .body(GitHubRepoSearchResponse.class);
                    if (response == null || response.items() == null) {
                        return ImmutableList.of();
                    }
                    return response.items().stream()
                            .map(GitHubAccountClient::toUserRepoFromSearch)
                            .collect(toImmutableList());
                });
    }

    public Optional<UserRepo> fetchRepository(String pat, String owner, String repo) {
        return readRepository(pat, "/repos/{owner}/{repo}", owner, repo);
    }

    public Optional<UserRepo> fetchRepository(String pat, long repositoryId) {
        return readRepository(pat, "/repositories/{repositoryId}", repositoryId);
    }

    private Optional<UserRepo> readRepository(String pat, String path, Object... variables)
    {
        try {
            GitHubUserRepoItem item = get(gitHubRestClient, pat, path, variables).retrieve().body(GitHubUserRepoItem.class);
            return Optional.ofNullable(item).map(GitHubAccountClient::toUserRepoFromSearch);
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw toReadableException(e);
        }
    }

    public Optional<UserRepo> findOwnedFork(String pat, UserRepo upstream, String login)
    {
        requirePat(pat);
        requireNonNull(upstream, "upstream is null");
        requireNonNull(login, "login is null");
        int perPage = GitHubPaginator.PAGE_SIZE;
        AtomicReference<UserRepo> found = new AtomicReference<>();
        // Forks may be renamed, so inspect every owned fork instead of guessing login/upstream.name.
        GitHubPaginator.forEachPage(Integer.MAX_VALUE, page -> {
            if (found.get() != null) {
                return ImmutableList.<GitHubUserRepoItem>of();
            }
            return requireBody(translating(() -> get(gitHubRestClient, pat, uri -> uri.path("/user/repos")
                    .queryParam("affiliation", "owner")
                    .queryParam("sort", "full_name")
                    .queryParam("direction", "asc")
                    .queryParam("per_page", perPage)
                    .queryParam("page", page)
                    .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GitHubUserRepoItem>>() {})), "Empty response while finding your GitHub fork");
        }, items -> {
            for (GitHubUserRepoItem item : items) {
                if (!item.fork()) {
                    continue;
                }
                UserRepo candidate = fetchRepository(pat, item.id())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(409), "Could not inspect your GitHub fork. Retry watching the repository."));
                if (candidate.fork() && candidate.upstream().filter(source -> source.id() > 0).isEmpty()) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(502), "GitHub did not identify the source of your fork " + candidate.fullName());
                }
                if (candidate.owner().equalsIgnoreCase(login) && isForkOf(candidate, upstream)) {
                    found.set(candidate);
                    return;
                }
            }
        });
        return Optional.ofNullable(found.get());
    }

    public UserRepo createFork(String pat, UserRepo upstream)
    {
        requirePat(pat);
        requireNonNull(upstream, "upstream is null");
        GitHubUserRepoItem item = requireBody(translating(() -> gitHubRestClient
                .post()
                .uri("/repos/{owner}/{repo}/forks", upstream.owner(), upstream.name())
                .header("Authorization", authorization(pat))
                .body(ImmutableMap.of("default_branch_only", false))
                .retrieve()
                .body(GitHubUserRepoItem.class)), "GitHub returned no repository after accepting the fork request. Retry watching to find it.");
        UserRepo fork = toUserRepoFromSearch(item);
        if (!isForkOf(fork, upstream)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "GitHub did not return a fork of the selected upstream repository");
        }
        // GitHub returns 202 before the fork's Git objects are necessarily available to clone.
        return fork;
    }

    private static boolean isForkOf(UserRepo repository, UserRepo upstream)
    {
        return repository.fork()
                && repository.id() > 0
                && repository.id() != upstream.id()
                && repository.upstream().map(source -> source.id() == upstream.id()).orElse(false);
    }

    private static UserRepo toUserRepoFromSearch(GitHubUserRepoItem item) {
        String owner = Optional.ofNullable(item.owner()).map(GitHubUser::login).orElse("");
        Optional<UserRepo.Upstream> upstream = Optional.ofNullable(item.source())
                .or(() -> Optional.ofNullable(item.parent()))
                .map(repository -> new UserRepo.Upstream(
                        repository.id(),
                        Optional.ofNullable(repository.owner())
                                .map(GitHubUser::login)
                                .orElse(""),
                        Optional.ofNullable(repository.name()).orElse(""),
                        Optional.ofNullable(repository.defaultBranch()).orElse(""),
                        Optional.ofNullable(repository.cloneUrl()).orElse("")));
        return new UserRepo(
                item.id(),
                owner,
                Optional.ofNullable(item.name()).orElse(""),
                Optional.ofNullable(item.fullName()).orElse(""),
                Optional.ofNullable(item.description()),
                item.fork(),
                Optional.ofNullable(item.visibility()).orElse("unknown"),
                Optional.ofNullable(item.permissions())
                        .map(GitHubUserRepoItem.Permissions::push)
                        .map(Boolean.TRUE::equals)
                        .orElse(false),
                Optional.ofNullable(item.defaultBranch()).orElse(""),
                Optional.ofNullable(item.cloneUrl()).orElse(""),
                upstream);
    }

    // ── Events ────────────────────────────────────────────────────────────────

    public List<RecentEvent> fetchUserEvents(String pat, String login, int limit) {
        return fetchEventList("/users/{login}/events", pat, login, limit);
    }

    public List<RecentEvent> fetchReceivedEvents(String pat, String login, int limit) {
        return fetchEventList("/users/{login}/received_events", pat, login, limit);
    }

    private List<RecentEvent> fetchEventList(
            String pathTemplate, String pat, String login, int limit) {
        try {
            List<GitHubEventItem> items =
                    get(gitHubRestClient, pat, u ->
                                    u.path(pathTemplate)
                                            .queryParam("per_page", Math.min(limit, 100))
                                            .build(login))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (items == null) {
                return ImmutableList.of();
            }
            return items.stream()
                    .filter(e -> e.createdAt() != null)
                    .sorted(comparing(GitHubEventItem::createdAt).reversed())
                    .limit(limit)
                    .map(GitHubAccountClient::toRecentEvent)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            return ImmutableList.of();
        }
    }

    static RecentEvent toRecentEvent(GitHubEventItem e)
    {
        GitHubEventItem.Payload payload = e.payload();
        int commitCount = payload != null && payload.size() != null ? payload.size() : 0;
        String action = payload != null ? payload.action() : null;
        GitHubEventItem.PrPayload pullRequest = payload != null ? payload.pullRequest() : null;
        GitHubEventItem.IssuePayload issue = payload != null ? payload.issue() : null;
        String prTitle = pullRequest != null ? pullRequest.title() : issue != null ? issue.title() : null;
        int prNumber = pullRequest != null ? pullRequest.number() : issue != null ? issue.number() : 0;
        String refType = payload != null ? payload.refType() : null;
        String actorLogin = e.actor() != null ? e.actor().login() : null;
        String actorAvatarUrl = e.actor() != null ? e.actor().avatarUrl() : null;
        String detail = payload == null ? null : eventDetail(payload.commits(), commitCount);
        String ref = payload != null ? payload.ref() : null;
        String reviewState = payload != null && payload.review() != null ? payload.review().state() : null;
        return new RecentEvent(
                e.type(),
                e.repo() != null ? e.repo().name() : "",
                e.createdAt(),
                commitCount,
                action,
                prTitle,
                prNumber,
                refType,
                actorLogin,
                actorAvatarUrl,
                detail,
                ref,
                reviewState);
    }

    private static String eventDetail(List<GitHubEventItem.CommitPayload> commits, int commitCount)
    {
        if (commits == null || commits.isEmpty()) {
            return null;
        }
        String first = commits.stream()
                .map(GitHubEventItem.CommitPayload::message)
                .filter(message -> message != null && !message.isBlank())
                .findFirst()
                .orElse(null);
        if (first == null) {
            return null;
        }
        String headline = first.lines().findFirst().orElse(first);
        int remaining = Math.max(commitCount, commits.size()) - 1;
        return remaining == 0 ? headline : headline + " · +" + remaining + " more";
    }

    private record GitHubRepoSearchResponse(List<GitHubUserRepoItem> items) {}

    private record GitHubUserProfileResponse(
            String login,
            String name,
            @JsonProperty("avatar_url") String avatarUrl,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("public_repos") int publicRepos,
            int followers,
            int following,
            String bio,
            String location,
            String company,
            String email)
    {}

    record GitHubEventItem(String type, GitHubUser actor, Repo repo, Payload payload, @JsonProperty("created_at") Instant createdAt)
    {
        record Repo(String name) {}

        record Payload(
                Integer size,
                String action,
                @JsonProperty("ref_type") String refType,
                String ref,
                @JsonProperty("pull_request") PrPayload pullRequest,
                IssuePayload issue,
                ReviewPayload review,
                List<CommitPayload> commits) {}

        record PrPayload(int number, String title) {}

        record IssuePayload(int number, String title) {}

        record ReviewPayload(String state) {}

        record CommitPayload(String message) {}
    }
}
