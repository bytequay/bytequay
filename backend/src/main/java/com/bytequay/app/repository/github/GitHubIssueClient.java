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

import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.IssueOrigin;
import com.bytequay.app.domain.IssueTimelineEvent;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.RepoActivityItem;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoIssueIntakePage;
import com.bytequay.app.domain.RepoIssuePage;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.GitHubIssueRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubIssueClient
        implements GitHubIssueRepository {
    private final RestClient gitHubRestClient;
    private final GitHubPaginator paginator;

    public GitHubIssueClient(RestClient gitHubRestClient) {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.paginator = new GitHubPaginator(gitHubRestClient);
    }

    // ── Repos and Users ───────────────────────────────────────────────────────

    @Override
    public List<RepoIssue> fetchRepoIssues(String pat, RepoRef repo, String state) {
        // GitHub's /repos/{owner}/{repo}/issues returns both issues and PRs
        // (PRs are a special subtype of issues), so we request the API maximum
        // (per_page=100) and filter PRs out client-side. We also sort by
        // updated desc so the freshest issues show up first regardless of the
        // 100-item cap.
        String stateParam = (state == null || state.isBlank()) ? "open" : state;
        try {
            List<GitHubIssueItem> items =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/repos/{owner}/{repo}/issues")
                                                    .queryParam("state", stateParam)
                                                    .queryParam("sort", "updated")
                                                    .queryParam("direction", "desc")
                                                    .queryParam("per_page", 100)
                                                    .build(repo.owner(), repo.repo()))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (items == null) {
                return ImmutableList.of();
            }
            return items.stream()
                    .filter(item -> item.pullRequest() == null)
                    .map(GitHubIssueClient::toRepoIssue)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public RepoIssueIntakePage fetchRepoIssueIntakePage(
            String pat, RepoRef repo, int page, int perPage) {
        if (page < 1 || perPage < 1 || perPage > 100) {
            throw new IllegalArgumentException("invalid GitHub issue page request");
        }
        try {
            List<GitHubIssueItem> items =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/repos/{owner}/{repo}/issues")
                                                    .queryParam("state", "all")
                                                    .queryParam("sort", "created")
                                                    .queryParam("direction", "desc")
                                                    .queryParam("page", page)
                                                    .queryParam("per_page", perPage)
                                                    .build(repo.owner(), repo.repo()))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (items == null || items.isEmpty()) {
                return new RepoIssueIntakePage(List.of(), 0, 0, false);
            }
            List<RepoIssue> openIssues =
                    items.stream()
                            .filter(item -> item.pullRequest() == null)
                            .filter(item -> "open".equalsIgnoreCase(item.state()))
                            .map(GitHubIssueClient::toRepoIssue)
                            .toList();
            int newest = items.stream().mapToInt(GitHubIssueItem::number).max().orElse(0);
            int oldest = items.stream().mapToInt(GitHubIssueItem::number).min().orElse(0);
            return new RepoIssueIntakePage(openIssues, newest, oldest, items.size() == perPage);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public RepoIssuePage fetchRepoIssuePage(String pat, RepoRef repo, int page, int perPage) {
        if (page < 1 || perPage < 1 || perPage > 100) {
            throw new IllegalArgumentException("invalid GitHub issue page request");
        }
        try {
            List<GitHubIssueItem> items =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/repos/{owner}/{repo}/issues")
                                                    .queryParam("state", "all")
                                                    .queryParam("sort", "created")
                                                    .queryParam("direction", "desc")
                                                    .queryParam("page", page)
                                                    .queryParam("per_page", perPage)
                                                    .build(repo.owner(), repo.repo()))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (items == null || items.isEmpty()) {
                return new RepoIssuePage(List.of(), false);
            }
            return new RepoIssuePage(
                    items.stream()
                            .filter(item -> item.pullRequest() == null)
                            .map(GitHubIssueClient::toRepoIssue)
                            .toList(),
                    items.size() == perPage);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public RepoIssue createIssue(String pat, RepoRef repo, String title, String body) {
        try {
            GitHubIssueItem item =
                    gitHubRestClient
                            .post()
                            .uri("/repos/{owner}/{repo}/issues", repo.owner(), repo.repo())
                            .header("Authorization", authorization(pat))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(ImmutableMap.of("title", title, "body", body))
                            .retrieve()
                            .body(GitHubIssueItem.class);
            if (item == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Empty response from GitHub when creating issue");
            }
            return toRepoIssue(item);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public IssueDetail fetchIssueDetail(String pat, RepoRef repo, int number) {
        try {
            GitHubIssueItem item =
                    gitHubRestClient
                            .get()
                            .uri(
                                    "/repos/{owner}/{repo}/issues/{number}",
                                    repo.owner(),
                                    repo.repo(),
                                    number)
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubIssueItem.class);
            if (item == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "Empty response from GitHub /issues/{n}");
            }
            // Comments are loaded in a follow-up call from the service so
            // The API client stays one-fetch-per-method. Pass an empty list
            // here; RepoService merges in the real comments.
            return toIssueDetail(item, ImmutableList.of());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<IssueDetail.Comment> fetchIssueDetailComments(
            String pat, RepoRef repo, int number) {
        try {
            List<GitHubIssueComment> raw =
                    paginator.paginate(
                            pat,
                            "/repos/{owner}/{repo}/issues/{number}/comments",
                            new ParameterizedTypeReference<List<GitHubIssueComment>>() {},
                            repo.owner(),
                            repo.repo(),
                            number);
            return raw.stream()
                    .map(GitHubIssueClient::toIssueDetailComment)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<IssueTimelineEvent> fetchIssueTimeline(String pat, RepoRef repo, int number) {
        try {
            List<GitHubTimelineEvent> raw =
                    paginator.paginate(
                            pat,
                            "/repos/{owner}/{repo}/issues/{number}/timeline",
                            new ParameterizedTypeReference<List<GitHubTimelineEvent>>() {},
                            repo.owner(),
                            repo.repo(),
                            number);
            return raw.stream()
                    // Skip "commented" rows — those ride on the comments
                    // endpoint already (and carry richer reaction data
                    // there). Activity tab renders structural events only.
                    .filter(e -> !"commented".equals(e.event()))
                    .map(GitHubIssueClient::toIssueTimelineEvent)
                    .filter(Objects::nonNull)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static IssueTimelineEvent toIssueTimelineEvent(GitHubTimelineEvent e) {
        Instant when = e.createdAt() != null ? e.createdAt() : e.submittedAt();
        String actor =
                Optional.ofNullable(e.actor())
                        .map(GitHubTimelineEvent.Actor::login)
                        .orElseGet(
                                () ->
                                        Optional.ofNullable(e.user())
                                                .map(GitHubTimelineEvent.User::login)
                                                .orElse(null));
        IssueTimelineEvent.Label label =
                e.label() == null
                        ? null
                        : new IssueTimelineEvent.Label(e.label().name(), e.label().color());
        String assignee = e.assignee() == null ? null : e.assignee().login();
        String milestone = e.milestone() == null ? null : e.milestone().title();
        IssueTimelineEvent.Rename rename =
                e.rename() == null
                        ? null
                        : new IssueTimelineEvent.Rename(e.rename().from(), e.rename().to());
        IssueTimelineEvent.CrossReference crossRef = null;
        if (e.source() != null && e.source().issue() != null) {
            GitHubTimelineEvent.SourceIssue src = e.source().issue();
            String repoFullName = src.repository() == null ? null : src.repository().fullName();
            crossRef =
                    new IssueTimelineEvent.CrossReference(
                            src.number(),
                            src.title(),
                            src.state(),
                            src.pullRequest() != null,
                            repoFullName,
                            src.htmlUrl());
        }
        return new IssueTimelineEvent(
                e.event(), actor, when, label, assignee, milestone, rename, crossRef);
    }

    @Override
    public boolean fetchIssueSubscription(String pat, RepoRef repo, int number) {
        try {
            SubscriptionResponse resp =
                    gitHubRestClient
                            .get()
                            .uri(
                                    "/repos/{owner}/{repo}/issues/{number}/subscription",
                                    repo.owner(),
                                    repo.repo(),
                                    number)
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(SubscriptionResponse.class);
            // GitHub returns 200 with {subscribed: true|false, ignored: …}
            // when the viewer has an explicit choice on file; we treat
            // only an explicit subscribed=true as "subscribed". An
            // ignore (subscribed=false, ignored=true) reads as not
            // subscribed in v1.
            return resp != null && Boolean.TRUE.equals(resp.subscribed());
        }
        catch (RestClientResponseException e) {
            // 404 = default state, viewer has no explicit subscription.
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw toReadableException(e);
        }
    }

    @Override
    public void setIssueSubscription(String pat, RepoRef repo, int number, boolean subscribe) {
        try {
            if (subscribe) {
                gitHubRestClient
                        .put()
                        .uri(
                                "/repos/{owner}/{repo}/issues/{number}/subscription",
                                repo.owner(),
                                repo.repo(),
                                number)
                        .header("Authorization", authorization(pat))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ImmutableMap.of("subscribed", true))
                        .retrieve()
                        .toBodilessEntity();
            }
            else {
                gitHubRestClient
                        .delete()
                        .uri(
                                "/repos/{owner}/{repo}/issues/{number}/subscription",
                                repo.owner(),
                                repo.repo(),
                                number)
                        .header("Authorization", authorization(pat))
                        .retrieve()
                        .toBodilessEntity();
            }
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SubscriptionResponse(Boolean subscribed, Boolean ignored) {}

    @Override
    public IssueDetail setIssueState(String pat, RepoRef repo, int number, String state) {
        try {
            GitHubIssueItem item =
                    gitHubRestClient
                            .patch()
                            .uri(
                                    "/repos/{owner}/{repo}/issues/{number}",
                                    repo.owner(),
                                    repo.repo(),
                                    number)
                            .header("Authorization", authorization(pat))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(ImmutableMap.of("state", state))
                            .retrieve()
                            .body(GitHubIssueItem.class);
            if (item == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Empty response from GitHub when toggling issue state");
            }
            // Comments don't change as a side-effect of a state flip — the
            // caller already has them and merging back here would mean a
            // second fetch we don't need. Return with an empty list and
            // let the caller (RepoService) splice in whatever it has.
            return toIssueDetail(item, ImmutableList.of());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public IssueDetail.Comment postIssueComment(String pat, RepoRef repo, int number, String body) {
        try {
            GitHubIssueComment posted =
                    gitHubRestClient
                            .post()
                            .uri(
                                    "/repos/{owner}/{repo}/issues/{number}/comments",
                                    repo.owner(),
                                    repo.repo(),
                                    number)
                            .header("Authorization", authorization(pat))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(ImmutableMap.of("body", body))
                            .retrieve()
                            .body(GitHubIssueComment.class);
            if (posted == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Empty response from GitHub when posting issue comment");
            }
            return toIssueDetailComment(posted);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static IssueDetail toIssueDetail(
            GitHubIssueItem item, List<IssueDetail.Comment> comments) {
        List<IssueDetail.Label> labels =
                Optional.ofNullable(item.labels()).orElse(ImmutableList.of()).stream()
                        .map(l -> new IssueDetail.Label(l.name(), l.color()))
                        .collect(toImmutableList());
        List<IssueDetail.Assignee> assignees =
                Optional.ofNullable(item.assignees()).orElse(ImmutableList.of()).stream()
                        .map(u -> new IssueDetail.Assignee(u.login(), u.avatarUrl()))
                        .collect(toImmutableList());
        IssueDetail.Milestone milestone =
                item.milestone() == null
                        ? null
                        : new IssueDetail.Milestone(
                                item.milestone().title(), item.milestone().state());
        String authorLogin =
                Optional.ofNullable(item.user()).map(GitHubIssueItem.User::login).orElse(null);
        String authorAvatar =
                Optional.ofNullable(item.user()).map(GitHubIssueItem.User::avatarUrl).orElse(null);
        return new IssueDetail(
                item.id(),
                item.number(),
                item.title(),
                item.body(),
                authorLogin,
                authorAvatar,
                item.state(),
                item.htmlUrl(),
                item.createdAt(),
                item.updatedAt(),
                item.closedAt(),
                labels,
                assignees,
                milestone,
                comments,
                // Timeline is fetched separately on the service-side
                // hot path so this client-internal helper just returns
                // empty here; the service splices the timeline in.
                ImmutableList.of(),
                // Subscription state is also fetched separately by the
                // service so this helper defaults to false; the service
                // overwrites with the real GET /subscription result.
                false);
    }

    private static IssueDetail.Comment toIssueDetailComment(GitHubIssueComment c) {
        String authorLogin =
                Optional.ofNullable(c.user()).map(GitHubIssueComment.User::login).orElse(null);
        String authorAvatar =
                Optional.ofNullable(c.user()).map(GitHubIssueComment.User::avatarUrl).orElse(null);
        return new IssueDetail.Comment(
                c.id(),
                authorLogin,
                authorAvatar,
                c.body(),
                c.createdAt(),
                toIssueCommentReactions(c.reactions()));
    }

    private static Reactions toIssueCommentReactions(GitHubIssueComment.Reactions reactions) {
        if (reactions == null) {
            return Reactions.EMPTY;
        }
        return new Reactions(
                reactions.plusOne(),
                reactions.minusOne(),
                reactions.laugh(),
                reactions.hooray(),
                reactions.confused(),
                reactions.heart(),
                reactions.rocket(),
                reactions.eyes());
    }

    @Override
    public RepoMeta fetchRepoMeta(String pat, RepoRef repo) {
        try {
            GitHubRepoResponse repoResp =
                    gitHubRestClient
                            .get()
                            .uri("/repos/{owner}/{repo}", repo.owner(), repo.repo())
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubRepoResponse.class);
            if (repoResp == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "Empty response from GitHub /repos");
            }

            // /languages is a flat name → byte-count map. We tolerate
            // failure here (rare empty repos / private repos with
            // restricted scopes) — falling back to an empty map keeps
            // the rest of the hero card rendering.
            Map<String, Long> languages;
            try {
                languages =
                        gitHubRestClient
                                .get()
                                .uri("/repos/{owner}/{repo}/languages", repo.owner(), repo.repo())
                                .header("Authorization", authorization(pat))
                                .retrieve()
                                .body(new ParameterizedTypeReference<Map<String, Long>>() {});
                if (languages == null) {
                    languages = ImmutableMap.of();
                }
            }
            catch (RestClientResponseException ignored) {
                languages = ImmutableMap.of();
            }

            String licenseName =
                    Optional.ofNullable(repoResp.license())
                            .map(GitHubRepoResponse.License::spdxId)
                            .orElse(
                                    Optional.ofNullable(repoResp.license())
                                            .map(GitHubRepoResponse.License::name)
                                            .orElse(null));

            GitHubRepoResponse.Parent parent = repoResp.parent();
            String parentOwner =
                    parent == null
                            ? null
                            : Optional.ofNullable(parent.owner())
                                    .map(GitHubRepoResponse.Owner::login)
                                    .orElse(null);
            String parentName = parent == null ? null : parent.name();
            String parentDefaultBranch = parent == null ? null : parent.defaultBranch();

            return new RepoMeta(
                    repoResp.fullName(),
                    repoResp.htmlUrl(),
                    repoResp.description(),
                    repoResp.defaultBranch(),
                    licenseName,
                    repoResp.stargazersCount(),
                    repoResp.forksCount(),
                    repoResp.subscribersCount(),
                    repoResp.openIssuesCount(),
                    repoResp.size(),
                    repoResp.createdAt(),
                    repoResp.pushedAt(),
                    Optional.ofNullable(repoResp.topics()).orElse(ImmutableList.of()),
                    languages,
                    Optional.ofNullable(repoResp.owner())
                            .map(GitHubRepoResponse.Owner::avatarUrl)
                            .orElse(null),
                    parentOwner,
                    parentName,
                    parentDefaultBranch);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void createFork(String pat, RepoRef repo) {
        requirePat(pat);
        try {
            gitHubRestClient
                    .post()
                    .uri("/repos/{owner}/{repo}/forks", repo.owner(), repo.repo())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<RepoActivityItem> fetchRepoActivity(String pat, RepoRef repo) {
        try {
            List<GitHubRepoEvent> events =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/repos/{owner}/{repo}/events")
                                                    .queryParam("per_page", 30)
                                                    .build(repo.owner(), repo.repo()))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (events == null) {
                return ImmutableList.of();
            }
            return events.stream()
                    .map(e -> toRepoActivityItem(e, repo))
                    .filter(Objects::nonNull)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /**
     * Translates one GitHub events-API entry into a normalized {@link RepoActivityItem}. Returns
     * null for event types we don't care to surface (e.g. WatchEvent — adds noise without adding
     * signal).
     */
    private static RepoActivityItem toRepoActivityItem(GitHubRepoEvent e, RepoRef repo) {
        String actor =
                Optional.ofNullable(e.actor()).map(GitHubRepoEvent.Actor::login).orElse(null);
        String htmlBase = "https://github.com/" + repo.owner() + "/" + repo.repo();
        String title;
        String url = htmlBase;
        switch (e.type()) {
            case "PushEvent" -> {
                int commits =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::commits)
                                .map(List::size)
                                .orElse(0);
                String ref =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::ref)
                                .orElse("");
                String branch =
                        ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
                title =
                        commits
                                + (commits == 1 ? " commit" : " commits")
                                + " pushed to "
                                + (branch.isEmpty() ? "branch" : branch);
            }
            case "PullRequestEvent" -> {
                String action =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::action)
                                .orElse("updated");
                Integer num =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::pullRequest)
                                .map(GitHubRepoEvent.PullRequestRefDto::number)
                                .orElse(null);
                String prTitle =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::pullRequest)
                                .map(GitHubRepoEvent.PullRequestRefDto::title)
                                .orElse("");
                if (num == null) {
                    return null;
                }
                title = "#" + num + " " + prTitle + " " + action;
                url = htmlBase + "/pull/" + num;
            }
            case "PullRequestReviewEvent" -> {
                Integer num =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::pullRequest)
                                .map(GitHubRepoEvent.PullRequestRefDto::number)
                                .orElse(null);
                if (num == null) {
                    return null;
                }
                title = "review on #" + num;
                url = htmlBase + "/pull/" + num;
            }
            case "IssuesEvent" -> {
                String action =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::action)
                                .orElse("updated");
                Integer num =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::issue)
                                .map(GitHubRepoEvent.IssueRefDto::number)
                                .orElse(null);
                String issueTitle =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::issue)
                                .map(GitHubRepoEvent.IssueRefDto::title)
                                .orElse("");
                if (num == null) {
                    return null;
                }
                title = "#" + num + " " + issueTitle + " " + action;
                url = htmlBase + "/issues/" + num;
            }
            case "IssueCommentEvent" -> {
                Integer num =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::issue)
                                .map(GitHubRepoEvent.IssueRefDto::number)
                                .orElse(null);
                if (num == null) {
                    return null;
                }
                title = "comment on #" + num;
                url = htmlBase + "/issues/" + num;
            }
            case "ReleaseEvent" -> {
                String tag =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::release)
                                .map(GitHubRepoEvent.ReleaseRefDto::tagName)
                                .orElse("a release");
                title = tag + " released";
                url = htmlBase + "/releases/tag/" + tag;
            }
            case "CreateEvent" -> {
                String refType =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::refType)
                                .orElse("ref");
                String ref =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::ref)
                                .orElse("");
                title = refType + " " + ref + " created";
            }
            case "DeleteEvent" -> {
                String refType =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::refType)
                                .orElse("ref");
                String ref =
                        Optional.ofNullable(e.payload())
                                .map(GitHubRepoEvent.Payload::ref)
                                .orElse("");
                title = refType + " " + ref + " deleted";
            }
            // Skip noisy event types.
            case "WatchEvent", "ForkEvent" -> {
                return null;
            }
            default -> title = e.type();
        }
        return new RepoActivityItem(e.type(), actor, title, url, e.createdAt());
    }

    private static RepoIssue toRepoIssue(GitHubIssueItem item) {
        List<String> labels =
                Optional.ofNullable(item.labels()).orElse(ImmutableList.of()).stream()
                        .map(GitHubIssueItem.Label::name)
                        .collect(toImmutableList());
        String author =
                Optional.ofNullable(item.user()).map(GitHubIssueItem.User::login).orElse(null);
        return new RepoIssue(
                item.id(),
                item.number(),
                item.title(),
                author,
                item.state(),
                item.htmlUrl(),
                item.updatedAt(),
                labels,
                item.comments(),
                IssueOrigin.detect(item.body()));
    }
}
