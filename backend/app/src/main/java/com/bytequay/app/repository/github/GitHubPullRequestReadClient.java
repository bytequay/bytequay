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

import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReaction;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.bytequay.core.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.core.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestReadClient
{
    private final RestClient gitHubRestClient;
    private final RestClient graphQlRestClient;
    private final GitHubPaginator paginator;

    public GitHubPullRequestReadClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient") RestClient graphQlRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphQlRestClient = requireNonNull(graphQlRestClient, "graphQlRestClient is null");
        this.paginator = new GitHubPaginator(gitHubRestClient);
    }

    private static final int PER_PAGE = 50;
    private static final String REPOS_SEGMENT = "/repos/";
    private static final Pattern ISSUE_REFERENCE = Pattern.compile(
            "https://github\\.com/[^/\\s]+/[^/\\s]+/(?:issues|pull)/\\d+");
    private static final Pattern SHORT_ISSUE_REFERENCE = Pattern.compile(
            "(?<![\\w/\\[])#(\\d+)\\b(?!\\]\\()");
    private static final Set<String> ATTENTION_REASONS =
            ImmutableSet.of("review_requested", "mention");

    public List<FetchedPullRequest> searchPullRequests(String pat, String query) {
        return searchPullRequestsPaged(pat, query, 1, PER_PAGE).items();
    }

    public List<RepoUser> fetchAssignableUsers(String pat, RepoRef repo)
    {
        return fetchAssignableRepositoryUsers(pat, repo).stream()
                .map(user -> new RepoUser(user.login(), user.avatarUrl(), user.bot()))
                .collect(toImmutableList());
    }

    public List<GitHubRepositoryUser> fetchAssignableRepositoryUsers(String pat, RepoRef repo)
    {
        requirePat(pat);
        return paginator.paginate(
                        pat,
                        "/repos/{owner}/{repo}/assignees",
                        new ParameterizedTypeReference<List<GitHubAssignableUser>>() {},
                        repo.owner(),
                        repo.repo()).stream()
                .filter(user -> user.id() > 0
                        && user.login() != null && !user.login().isBlank()
                        && user.avatarUrl() != null && !user.avatarUrl().isBlank())
                .map(user -> new GitHubRepositoryUser(
                        user.id(),
                        user.login(),
                        user.avatarUrl(),
                        "Bot".equalsIgnoreCase(user.type()) || user.login().endsWith("[bot]")))
                .collect(toImmutableList());
    }

    public PullRequestHistoryPage searchPullRequestsPaged(
            String pat, String query, int page, int perPage) {
        return searchPullRequestsPaged(pat, query, page, perPage, null, null);
    }

    public PullRequestHistoryPage searchPullRequestsPaged(
            String pat, String query, int page, int perPage, String sort, String order) {
        requirePat(pat);
        int safePage = Math.max(1, page);
        int safePerPage = Math.clamp(perPage, 1, 100);
        try {
            GitHubSearchResponse response =
                    gitHubRestClient
                            .get()
                            .uri(
                                    uri -> {
                                        uri.path("/search/issues")
                                                .queryParam("q", query)
                                                .queryParam("per_page", safePerPage)
                                                .queryParam("page", safePage);
                                        if (sort != null && !sort.isBlank()) {
                                            uri.queryParam("sort", sort);
                                        }
                                        if (order != null && !order.isBlank()) {
                                            uri.queryParam("order", order);
                                        }
                                        return uri.build();
                                    })
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubSearchResponse.class);
            if (response == null || response.items() == null) {
                return new PullRequestHistoryPage(
                        ImmutableList.of(), safePage, safePerPage, 0, false);
            }
            PullRequest.Origin origin =
                    query.contains("review-requested") ? REVIEW_REQUESTED : AUTHORED;
            List<FetchedPullRequest> items =
                    response.items().stream()
                            .map(item -> toPullRequest(item, origin))
                            .collect(toImmutableList());
            // GitHub caps search at 1000 results, so a page that runs off
            // the end either by total or by the cap should report no more.
            int loadedSoFar = (safePage - 1) * safePerPage + items.size();
            boolean hasMore =
                    items.size() == safePerPage
                            && loadedSoFar < response.totalCount()
                            && loadedSoFar < 1000;
            return new PullRequestHistoryPage(
                    items, safePage, safePerPage, response.totalCount(), hasMore);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    // ── Pull request detail ───────────────────────────────────────────────────

    public PrRawDetail fetchPrDetail(String pat, PullRequestRef pr) {
        try {
            GitHubPullRequestDetailResponse r =
                    gitHubRestClient
                            .get()
                            .uri(
                                    "/repos/{owner}/{repo}/pulls/{number}",
                                    pr.owner(),
                                    pr.repo(),
                                    pr.number())
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubPullRequestDetailResponse.class);
            if (r == null) {
                return null;
            }
            List<String> labels =
                    Optional.ofNullable(r.labels()).orElse(ImmutableList.of()).stream()
                            .map(GitHubPullRequestDetailResponse.Label::name)
                            .collect(toImmutableList());
            Map<String, String> labelColors = Optional.ofNullable(r.labels()).orElse(ImmutableList.of()).stream()
                    .filter(label -> label.name() != null && label.color() != null)
                    .collect(toImmutableMap(
                            GitHubPullRequestDetailResponse.Label::name,
                            GitHubPullRequestDetailResponse.Label::color,
                            (left, right) -> left));
            List<String> assignees = Optional.ofNullable(r.assignees()).orElse(ImmutableList.of()).stream()
                    .map(GitHubPullRequestDetailResponse.Assignee::login)
                    .filter(Objects::nonNull)
                    .collect(toImmutableList());
            List<String> reviewerLogins =
                    Optional.ofNullable(r.requestedReviewers()).orElse(ImmutableList.of()).stream()
                            .map(GitHubPullRequestDetailResponse.RequestedReviewer::login)
                            .filter(Objects::nonNull)
                            .collect(toImmutableList());
            int reviewerCount = reviewerLogins.size();
            String headSha =
                    Optional.ofNullable(r.head())
                            .map(GitHubPullRequestDetailResponse.Head::sha)
                            .orElse(null);
            String headRef =
                    Optional.ofNullable(r.head())
                            .map(GitHubPullRequestDetailResponse.Head::ref)
                            .orElse(null);
            String baseRef = Optional.ofNullable(r.base())
                    .map(GitHubPullRequestDetailResponse.Base::ref)
                    .orElse(null);
            String author = Optional.ofNullable(r.user())
                    .map(GitHubPullRequestDetailResponse.User::login)
                    .orElse(null);
            String authorAvatarUrl = Optional.ofNullable(r.user())
                    .map(GitHubPullRequestDetailResponse.User::avatarUrl)
                    .orElse(null);
            return new PrRawDetail(
                    r.title(),
                    r.htmlUrl(),
                    r.mergedAt() == null ? r.state() : "merged",
                    author,
                    authorAvatarUrl,
                    r.createdAt(),
                    r.updatedAt(),
                    r.mergedAt(),
                    enrichIssueReferences(pat, pr, r.body()),
                    labels,
                    labelColors,
                    assignees,
                    Optional.ofNullable(r.milestone())
                            .map(GitHubPullRequestDetailResponse.Milestone::title)
                            .orElse(null),
                    r.draft(),
                    r.mergeable(),
                    r.mergeableState(),
                    r.additions(),
                    r.deletions(),
                    r.changedFiles(),
                    reviewerCount,
                    reviewerLogins,
                    headSha,
                    headRef,
                    baseRef);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private String enrichIssueReferences(String pat, PullRequestRef pr, String body)
    {
        if (body == null || body.isBlank()) {
            return body;
        }
        return applyIssueReferenceReplacements(body, resolveIssueReferences(pat, pr, ImmutableList.of(body)));
    }

    public List<PrTimelineEvent> enrichTimelineReferences(
            String pat, PullRequestRef pr, List<PrTimelineEvent> timeline)
    {
        List<String> bodies = new ArrayList<>();
        for (PrTimelineEvent event : timeline) {
            bodies.add(event.body());
            event.payload().comments().forEach(comment -> bodies.add(comment.body()));
        }
        Map<String, String> replacements = resolveIssueReferences(pat, pr, bodies);
        if (replacements.isEmpty()) {
            return timeline;
        }
        return timeline.stream()
                .map(event -> withIssueReferenceReplacements(event, replacements))
                .collect(toImmutableList());
    }

    private Map<String, String> resolveIssueReferences(
            String pat, PullRequestRef pr, Iterable<String> bodies)
    {
        Map<String, String> references = new LinkedHashMap<>();
        for (String body : bodies) {
            if (body == null || body.isBlank() || references.size() >= 50) {
                continue;
            }
            Matcher matcher = ISSUE_REFERENCE.matcher(body);
            while (matcher.find() && references.size() < 50) {
                references.putIfAbsent(matcher.group(), matcher.group());
            }
            matcher = SHORT_ISSUE_REFERENCE.matcher(body);
            while (matcher.find() && references.size() < 50) {
                references.putIfAbsent(
                        matcher.group(),
                        "https://github.com/%s/%s/issues/%s".formatted(pr.owner(), pr.repo(), matcher.group(1)));
            }
        }
        if (references.isEmpty()) {
            return ImmutableMap.of();
        }

        StringBuilder query = new StringBuilder("query(");
        Map<String, Object> variables = new LinkedHashMap<>();
        int index = 0;
        for (String url : references.values()) {
            if (index > 0) {
                query.append(',');
            }
            query.append("$url").append(index).append(":URI!");
            variables.put("url" + index, url);
            index++;
        }
        query.append("){ ");
        for (index = 0; index < references.size(); index++) {
            query.append("ref").append(index)
                    .append(":resource(url:$url").append(index)
                    .append("){__typename ... on Issue{title number}... on PullRequest{title number}} ");
        }
        query.append('}');

        try {
            JsonNode response = graphQlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(ImmutableMap.of("query", query.toString(), "variables", variables))
                    .retrieve()
                    .body(JsonNode.class);
            Map<String, String> replacements = new LinkedHashMap<>();
            index = 0;
            for (Map.Entry<String, String> reference : references.entrySet()) {
                int referenceIndex = index++;
                JsonNode item = response == null
                        ? null
                        : response.path("data").path("ref" + referenceIndex);
                if (item == null || item.isMissingNode() || item.isNull() || item.path("title").isMissingNode()) {
                    continue;
                }
                String title = item.path("title").asText().replace('[', '(').replace(']', ')');
                int number = item.path("number").asInt();
                String url = reference.getKey().startsWith("#")
                        ? "https://github.com/%s/%s/%s/%s".formatted(
                                pr.owner(),
                                pr.repo(),
                                item.path("__typename").asText().equals("PullRequest") ? "pull" : "issues",
                                number)
                        : reference.getValue();
                replacements.put(reference.getKey(), "[" + title + " #" + number + "](" + url + ")");
            }
            return replacements;
        }
        catch (RestClientResponseException ignored) {
            return ImmutableMap.of();
        }
    }

    private static PrTimelineEvent withIssueReferenceReplacements(
            PrTimelineEvent event, Map<String, String> replacements)
    {
        String body = applyIssueReferenceReplacements(event.body(), replacements);
        PrTimelinePayload payload = event.payload();
        List<PrTimelineComment> comments = payload.comments().stream()
                .map(comment -> {
                    String commentBody = applyIssueReferenceReplacements(comment.body(), replacements);
                    if (Objects.equals(comment.body(), commentBody)) {
                        return comment;
                    }
                    return new PrTimelineComment(
                            comment.githubId(), comment.actor(), comment.actorAvatarUrl(), comment.actorBot(),
                            comment.timestamp(), commentBody, comment.htmlUrl(), comment.reviewId(),
                            comment.inReplyToId(), comment.reactions());
                })
                .collect(toImmutableList());
        if (Objects.equals(event.body(), body) && comments.equals(payload.comments())) {
            return event;
        }
        return new PrTimelineEvent(
                event.githubId(), event.event(), event.actor(), event.timestamp(), body,
                new PrTimelinePayload(
                        payload.actorAvatarUrl(), payload.actorBot(), payload.state(), payload.htmlUrl(),
                        payload.label(), payload.labelColor(), payload.renameFrom(), payload.renameTo(),
                        payload.commitSha(), payload.commitMessage(), payload.sourceTitle(), payload.sourceUrl(),
                        payload.sourceNumber(), payload.milestoneTitle(), payload.path(), payload.diffHunk(),
                        payload.line(), payload.originalLine(), payload.startLine(), payload.originalStartLine(),
                        payload.side(), payload.resolved(), payload.outdated(), payload.reactions(), comments),
                event.localEventKey());
    }

    private static String applyIssueReferenceReplacements(String body, Map<String, String> replacements)
    {
        if (body == null || body.isBlank() || replacements.isEmpty()) {
            return body;
        }
        String enriched = body;
        Map<String, String> placeholders = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            String placeholder = "\0bytequay-reference-" + index++ + "\0";
            enriched = enriched.replace(replacement.getKey(), placeholder);
            placeholders.put(placeholder, replacement.getValue());
        }
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            enriched = enriched.replace(placeholder.getKey(), placeholder.getValue());
        }
        return enriched;
    }

    public List<PrReaction> fetchPrDescriptionReactions(String pat, PullRequestRef pr)
    {
        GitHubIssueReactionsResponse response = gitHubRestClient.get()
                .uri("/repos/{owner}/{repo}/issues/{number}", pr.owner(), pr.repo(), pr.number())
                .header("Authorization", authorization(pat))
                .retrieve()
                .body(GitHubIssueReactionsResponse.class);
        return toReactions(response == null ? null : response.reactions());
    }

    public List<PrReviewState> fetchPrReviews(String pat, PullRequestRef pr) {
        try {
            List<GitHubReview> reviews = paginator.paginate(
                    pat,
                    "/repos/{owner}/{repo}/pulls/{number}/reviews",
                    new ParameterizedTypeReference<List<GitHubReview>>() {},
                    pr.owner(),
                    pr.repo(),
                    pr.number());
            return reviews.stream()
                    .filter(r -> r.user() != null)
                    .map(r -> new PrReviewState(
                            r.id(),
                            r.user().login(),
                            r.user().avatarUrl(),
                            "Bot".equalsIgnoreCase(r.user().type()),
                            r.state(),
                            r.submittedAt(),
                            r.body(),
                            r.htmlUrl(),
                            r.commitId()))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            return ImmutableList.of();
        }
    }

    public List<DiffFile> fetchPrDiffFiles(String pat, PullRequestRef pr)
    {
        return fetchPrDiffFiles(pat, pr, ImmutableList.of(), Optional.empty()).orElse(ImmutableList.of());
    }

    /**
     * Re-reads the default diff only when GitHub says it changed. {@code stored}
     * is the copy the caller already holds; its size decides whether the ETag
     * can be trusted, since the ETag only covers the first page.
     */
    public ConditionalList<DiffFile> fetchPrDiffFiles(
            String pat, PullRequestRef pr, List<DiffFile> stored, Optional<String> etag)
    {
        try {
            return paginator.paginateConditional(
                            pat,
                            "/repos/{owner}/{repo}/pulls/{number}/files",
                            new ParameterizedTypeReference<List<GitHubPullRequestFileWithPatch>>() {},
                            etagCoveringOnePage(stored, etag),
                            pr.owner(),
                            pr.repo(),
                            pr.number())
                    .map(GitHubPullRequestReadClient::toDiffFiles);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /**
     * A stored ETag comes from page one alone. Withhold it once the stored copy
     * needs a second page, or a change past page one leaves page one
     * byte-identical and the stale copy is kept forever.
     */
    private static Optional<String> etagCoveringOnePage(List<?> stored, Optional<String> etag)
    {
        return stored.size() <= GitHubPaginator.PAGE_SIZE ? etag : Optional.empty();
    }

    public List<DiffFile> fetchPrDiffFiles(String pat, PullRequestRef pr, String baseSha, String headSha)
    {
        requirePat(pat);
        if (baseSha == null || baseSha.isBlank() || headSha == null || headSha.isBlank()) {
            return fetchPrDiffFiles(pat, pr);
        }
        try {
            GitHubCompareResponse response = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/compare/{base}...{head}",
                            pr.owner(), pr.repo(), baseSha, headSha)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubCompareResponse.class);
            return response == null ? ImmutableList.of() : toDiffFiles(response.files());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public List<DiffCommit> fetchPrDiffCommits(String pat, PullRequestRef pr)
    {
        return fetchPrDiffCommits(pat, pr, ImmutableList.of(), Optional.empty()).orElse(ImmutableList.of());
    }

    public ConditionalList<DiffCommit> fetchPrDiffCommits(
            String pat, PullRequestRef pr, List<DiffCommit> stored, Optional<String> etag)
    {
        return paginator.paginateConditional(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/commits",
                        new ParameterizedTypeReference<List<GitHubPullRequestCommit>>() {},
                        etagCoveringOnePage(stored, etag),
                        pr.owner(),
                        pr.repo(),
                        pr.number())
                .map(GitHubPullRequestReadClient::toDiffCommits);
    }

    private static List<DiffCommit> toDiffCommits(List<GitHubPullRequestCommit> commits)
    {
        return commits.stream()
                .map(commit -> {
                    GitHubPullRequestCommit.Author author = Optional.ofNullable(commit.commit())
                            .map(GitHubPullRequestCommit.Commit::author)
                            .orElse(null);
                    return new DiffCommit(
                            commit.sha(),
                            Optional.ofNullable(commit.parents()).orElse(ImmutableList.of()).stream()
                                    .findFirst()
                                    .map(GitHubPullRequestCommit.Parent::sha)
                                    .orElse(null),
                            Optional.ofNullable(commit.commit()).map(GitHubPullRequestCommit.Commit::message).orElse(null),
                            Optional.ofNullable(commit.author()).map(GitHubPullRequestCommit.User::login)
                                    .orElseGet(() -> Optional.ofNullable(author)
                                            .map(GitHubPullRequestCommit.Author::name)
                                            .orElse(null)),
                            Optional.ofNullable(author).map(GitHubPullRequestCommit.Author::date).orElse(null));
                })
                .collect(toImmutableList());
    }

    public DiffFileContent fetchPrDiffFileContent(String pat, PullRequestRef pr, String path)
    {
        requirePat(pat);
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path must not be blank");
        }
        try {
            GitHubPullRequestDetailResponse detail = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubPullRequestDetailResponse.class);
            if (detail == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty pull request response from GitHub");
            }
            String baseSha = Optional.ofNullable(detail.base()).map(GitHubPullRequestDetailResponse.Base::sha).orElse(null);
            String headSha = Optional.ofNullable(detail.head()).map(GitHubPullRequestDetailResponse.Head::sha).orElse(null);
            return fetchPrDiffFileContent(pat, pr, path, baseSha, headSha);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public DiffFileContent fetchPrDiffFileContent(
            String pat, PullRequestRef pr, String path, String baseSha, String headSha)
    {
        requirePat(pat);
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path must not be blank");
        }
        return new DiffFileContent(
                fetchRepositoryFile(pat, pr, path, baseSha),
                fetchRepositoryFile(pat, pr, path, headSha));
    }

    private String fetchRepositoryFile(String pat, PullRequestRef pr, String path, String ref)
    {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        try {
            return gitHubRestClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/contents/")
                            .path(path)
                            .queryParam("ref", ref)
                            .build(pr.owner(), pr.repo()))
                    .header("Authorization", authorization(pat))
                    .header("Accept", "application/vnd.github.raw+json")
                    .retrieve()
                    .body(String.class);
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private static List<DiffFile> toDiffFiles(List<GitHubPullRequestFileWithPatch> files)
    {
        return Optional.ofNullable(files).orElse(ImmutableList.of()).stream()
                .map(f -> new DiffFile(f.filename(), f.status(), f.additions(), f.deletions(), f.patch()))
                .collect(toImmutableList());
    }

    public List<PrTimelineEvent> fetchPrTimeline(String pat, PullRequestRef pr, Instant since) {
        // GitHub returns timeline events oldest-first, capped at per_page=100
        // per request. Long-lived PRs blow past 100 events easily (force
        // pushes + reviews + comments add up), and without pagination the
        // newest activity gets silently dropped — see #27922 where the
        // first page only reaches 2026-03-25 even though replies continued
        // into late April. Walk pages until we get a short page or hit the
        // safety cap.
        //
        // When `since` is provided we narrow the request to events after
        // that watermark — this is the incremental-sync hot path, where
        // an unchanged PR returns an empty page in one round trip.
        List<PrTimelineEvent> timeline = paginator
                .paginateSince(
                        pat,
                        "/repos/{owner}/{repo}/issues/{number}/timeline",
                        new ParameterizedTypeReference<List<GitHubTimelineEvent>>() {},
                        since,
                        pr.owner(),
                        pr.repo(),
                        pr.number())
                .stream()
                .map(GitHubPullRequestReadClient::toTimelineEvent)
                .collect(toImmutableList());
        if (timeline.stream().noneMatch(event -> "reviewed".equals(event.event()))) {
            return timeline;
        }
        try {
            Map<Long, List<PrReaction>> reactions = fetchReviewReactions(pat, pr);
            return timeline.stream()
                    .map(event -> event.githubId() == null
                            ? event
                            : withReviewReactions(event, reactions.get(event.githubId())))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException ignored) {
            return timeline;
        }
    }

    private Map<Long, List<PrReaction>> fetchReviewReactions(String pat, PullRequestRef pr)
    {
        String query = """
                query($owner:String!,$repo:String!,$number:Int!,$after:String) {
                  repository(owner:$owner,name:$repo) {
                    pullRequest(number:$number) {
                      reviews(first:100,after:$after) {
                        nodes { databaseId reactionGroups { content users { totalCount } } }
                        pageInfo { hasNextPage endCursor }
                      }
                    }
                  }
                }
                """;
        Map<Long, List<PrReaction>> out = new LinkedHashMap<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("owner", pr.owner());
            variables.put("repo", pr.repo());
            variables.put("number", pr.number());
            variables.put("after", cursor);
            GitHubReviewReactionsResponse response = graphQlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(ImmutableMap.of("query", query, "variables", variables))
                    .retrieve()
                    .body(GitHubReviewReactionsResponse.class);
            GitHubReviewReactionsResponse.Reviews reviews = Optional.ofNullable(response)
                    .map(GitHubReviewReactionsResponse::data)
                    .map(GitHubReviewReactionsResponse.Data::repository)
                    .map(GitHubReviewReactionsResponse.Repository::pullRequest)
                    .map(GitHubReviewReactionsResponse.PullRequest::reviews)
                    .orElse(null);
            if (reviews == null) {
                break;
            }
            for (GitHubReviewReactionsResponse.Review review : Optional.ofNullable(reviews.nodes()).orElse(ImmutableList.of())) {
                if (review.databaseId() == null) {
                    continue;
                }
                List<PrReaction> counts = Optional.ofNullable(review.reactionGroups()).orElse(ImmutableList.of()).stream()
                        .filter(group -> group.users() != null && group.users().totalCount() > 0)
                        .map(group -> new PrReaction(reactionContent(group.content()), group.users().totalCount()))
                        .collect(toImmutableList());
                if (!counts.isEmpty()) {
                    out.put(review.databaseId(), counts);
                }
            }
            GitHubReviewThreadsResponse.PageInfo pageInfo = reviews.pageInfo();
            if (pageInfo == null || !pageInfo.hasNextPage() || pageInfo.endCursor() == null) {
                break;
            }
            cursor = pageInfo.endCursor();
        }
        return ImmutableMap.copyOf(out);
    }

    private static PrTimelineEvent withReviewReactions(PrTimelineEvent event, List<PrReaction> reactions)
    {
        if (reactions == null || reactions.isEmpty()) {
            return event;
        }
        PrTimelinePayload payload = event.payload();
        return new PrTimelineEvent(
                event.githubId(),
                event.event(),
                event.actor(),
                event.timestamp(),
                event.body(),
                new PrTimelinePayload(
                        payload.actorAvatarUrl(), payload.actorBot(), payload.state(), payload.htmlUrl(),
                        payload.label(), payload.labelColor(), payload.renameFrom(), payload.renameTo(),
                        payload.commitSha(), payload.commitMessage(), payload.sourceTitle(), payload.sourceUrl(),
                        payload.sourceNumber(), payload.milestoneTitle(), payload.path(), payload.diffHunk(),
                        payload.line(), payload.originalLine(), payload.startLine(), payload.originalStartLine(),
                        payload.side(), payload.resolved(), payload.outdated(), reactions, payload.comments()),
                event.localEventKey());
    }

    private static String reactionContent(String content)
    {
        return switch (Optional.ofNullable(content).orElse("")) {
            case "THUMBS_UP" -> "+1";
            case "THUMBS_DOWN" -> "-1";
            default -> content == null ? "" : content.toLowerCase(java.util.Locale.ROOT);
        };
    }

    public List<PrTimelineEvent> fetchPrIssueComments(
            String pat, PullRequestRef pr, Instant since) {
        return paginator
                .paginateSince(
                        pat,
                        "/repos/{owner}/{repo}/issues/{number}/comments",
                        new ParameterizedTypeReference<List<GitHubIssueComment>>() {},
                        since,
                        pr.owner(),
                        pr.repo(),
                        pr.number())
                .stream()
                .map(GitHubPullRequestReadClient::toCommentedTimelineEvent)
                .collect(toImmutableList());
    }

    static PrTimelineEvent toCommentedTimelineEvent(GitHubIssueComment c) {
        GitHubIssueComment.User user = c.user();
        return new PrTimelineEvent(
                c.id(),
                "commented",
                Optional.ofNullable(user).map(GitHubIssueComment.User::login).orElse(null),
                c.createdAt(),
                c.body(),
                new PrTimelinePayload(
                        Optional.ofNullable(user).map(GitHubIssueComment.User::avatarUrl).orElse(null),
                        user != null && "Bot".equalsIgnoreCase(user.type()),
                        null,
                        c.htmlUrl(),
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, false, false,
                        toReactions(c.reactions()),
                        ImmutableList.of()),
                Optional.empty());
    }

    private static PrTimelineEvent toTimelineEvent(GitHubTimelineEvent event)
    {
        String type = Optional.ofNullable(event.event()).orElse("");
        String actor = switch (type) {
            case "committed" -> Optional.ofNullable(event.author())
                    .map(GitHubTimelineEvent.Author::name)
                    .orElse(null);
            case "reviewed" -> Optional.ofNullable(event.user())
                    .map(GitHubTimelineEvent.User::login)
                    .orElse(null);
            default -> Optional.ofNullable(event.actor())
                    .map(GitHubTimelineEvent.Actor::login)
                    .orElseGet(() -> Optional.ofNullable(event.user())
                            .map(GitHubTimelineEvent.User::login)
                            .orElse(null));
        };
        Instant timestamp = switch (type) {
            case "committed" -> Optional.ofNullable(event.author())
                    .map(GitHubTimelineEvent.Author::date)
                    .orElse(null);
            case "reviewed" -> event.submittedAt();
            default -> event.createdAt();
        };
        String actorAvatarUrl = Optional.ofNullable(event.actor())
                .map(GitHubTimelineEvent.Actor::avatarUrl)
                .orElseGet(() -> Optional.ofNullable(event.user())
                        .map(GitHubTimelineEvent.User::avatarUrl)
                        .orElse(null));
        boolean actorBot = Optional.ofNullable(event.actor())
                .map(GitHubTimelineEvent.Actor::type)
                .or(() -> Optional.ofNullable(event.user()).map(GitHubTimelineEvent.User::type))
                .map("Bot"::equalsIgnoreCase)
                .orElse(false);
        GitHubTimelineEvent.Issue source = Optional.ofNullable(event.source())
                .map(GitHubTimelineEvent.Source::issue)
                .orElse(null);
        return new PrTimelineEvent(
                event.id(),
                event.event(),
                actor,
                timestamp,
                event.body(),
                new PrTimelinePayload(
                        actorAvatarUrl,
                        actorBot,
                        event.state(),
                        event.htmlUrl(),
                        Optional.ofNullable(event.label()).map(GitHubTimelineEvent.Label::name).orElse(null),
                        Optional.ofNullable(event.label()).map(GitHubTimelineEvent.Label::color).orElse(null),
                        Optional.ofNullable(event.rename()).map(GitHubTimelineEvent.Rename::from).orElse(null),
                        Optional.ofNullable(event.rename()).map(GitHubTimelineEvent.Rename::to).orElse(null),
                        event.sha(),
                        event.message(),
                        source == null ? null : source.title(),
                        source == null ? null : source.htmlUrl(),
                        source == null ? null : source.number(),
                        Optional.ofNullable(event.milestone()).map(GitHubTimelineEvent.Milestone::title).orElse(null),
                        null, null, null, null, null, null, null,
                        false,
                        false,
                        toReactions(event.reactions()),
                        ImmutableList.of()),
                Optional.empty());
    }

    public List<PrTimelineEvent> fetchPrCommits(String pat, PullRequestRef pr)
    {
        return paginator.paginate(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/commits",
                        new ParameterizedTypeReference<List<GitHubPullRequestCommit>>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number()).stream()
                .map(GitHubPullRequestReadClient::toCommittedTimelineEvent)
                .collect(toImmutableList());
    }

    private static PrTimelineEvent toCommittedTimelineEvent(GitHubPullRequestCommit commit)
    {
        GitHubPullRequestCommit.User user = commit.author();
        GitHubPullRequestCommit.Author author = Optional.ofNullable(commit.commit())
                .map(GitHubPullRequestCommit.Commit::author)
                .orElse(null);
        String actor = user == null
                ? Optional.ofNullable(author).map(GitHubPullRequestCommit.Author::name).orElse(null)
                : user.login();
        return new PrTimelineEvent(
                null,
                "committed",
                actor,
                Optional.ofNullable(author).map(GitHubPullRequestCommit.Author::date).orElse(null),
                null,
                new PrTimelinePayload(
                        user == null ? null : user.avatarUrl(),
                        user != null && "Bot".equalsIgnoreCase(user.type()),
                        null,
                        commit.htmlUrl(),
                        null, null, null, null,
                        commit.sha(),
                        Optional.ofNullable(commit.commit()).map(GitHubPullRequestCommit.Commit::message).orElse(null),
                        null, null, null, null, null, null, null, null, null, null, null,
                        false, false, ImmutableList.of(), ImmutableList.of()),
                Optional.empty());
    }

    public List<PrTimelineEvent> fetchPrReviewThreads(String pat, PullRequestRef pr)
    {
        List<GitHubReviewComment> comments = paginator.paginate(
                pat,
                "/repos/{owner}/{repo}/pulls/{number}/comments",
                new ParameterizedTypeReference<List<GitHubReviewComment>>() {},
                pr.owner(),
                pr.repo(),
                pr.number());
        if (comments.isEmpty()) {
            return ImmutableList.of();
        }
        Map<Long, ReviewThreadFacts> facts = fetchReviewThreadFacts(pat, pr);
        Map<Long, Long> roots = new LinkedHashMap<>();
        Map<Long, List<GitHubReviewComment>> grouped = new LinkedHashMap<>();
        comments.stream()
                .sorted(Comparator.comparing(comment -> Optional.ofNullable(comment.createdAt()).orElse(Instant.EPOCH)))
                .forEach(comment -> {
                    long rootId = comment.inReplyToId() == null
                            ? comment.id()
                            : roots.getOrDefault(comment.inReplyToId(), comment.inReplyToId());
                    roots.put(comment.id(), rootId);
                    grouped.computeIfAbsent(rootId, ignored -> new ArrayList<>()).add(comment);
                });
        return grouped.values().stream()
                .map(thread -> toReviewThreadEvent(thread, facts.get(thread.getFirst().id())))
                .sorted(Comparator.comparing(event -> Optional.ofNullable(event.timestamp()).orElse(Instant.EPOCH)))
                .collect(toImmutableList());
    }

    private Map<Long, ReviewThreadFacts> fetchReviewThreadFacts(String pat, PullRequestRef pr)
    {
        String query = """
                query($owner:String!,$repo:String!,$number:Int!,$after:String) {
                  repository(owner:$owner,name:$repo) {
                    pullRequest(number:$number) {
                      reviewThreads(first:100,after:$after) {
                        nodes { id isResolved isOutdated path line originalLine startLine originalStartLine diffSide
                          comments(first:1) { nodes { databaseId } }
                        }
                        pageInfo { hasNextPage endCursor }
                      }
                    }
                  }
                }
                """;
        Map<Long, ReviewThreadFacts> facts = new LinkedHashMap<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("owner", pr.owner());
            variables.put("repo", pr.repo());
            variables.put("number", pr.number());
            variables.put("after", cursor);
            try {
                GitHubReviewThreadsResponse response = graphQlRestClient.post()
                        .header("Authorization", authorization(pat))
                        .body(ImmutableMap.of("query", query, "variables", variables))
                        .retrieve()
                        .body(GitHubReviewThreadsResponse.class);
                GitHubReviewThreadsResponse.ReviewThreads threads = reviewThreads(response);
                if (threads == null) {
                    break;
                }
                for (GitHubReviewThreadsResponse.Thread thread : Optional.ofNullable(threads.nodes()).orElse(ImmutableList.of())) {
                    Long rootId = Optional.ofNullable(thread.comments())
                            .map(GitHubReviewThreadsResponse.Comments::nodes)
                            .orElse(ImmutableList.of()).stream()
                            .map(GitHubReviewThreadsResponse.Comment::databaseId)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                    if (rootId != null) {
                        facts.put(rootId, new ReviewThreadFacts(
                                thread.id(),
                                thread.isResolved(),
                                thread.isOutdated(),
                                thread.path(),
                                thread.line(),
                                thread.originalLine(),
                                thread.startLine(),
                                thread.originalStartLine(),
                                thread.diffSide()));
                    }
                }
                GitHubReviewThreadsResponse.PageInfo pageInfo = threads.pageInfo();
                if (pageInfo == null || !pageInfo.hasNextPage() || pageInfo.endCursor() == null) {
                    break;
                }
                cursor = pageInfo.endCursor();
            }
            catch (RestClientResponseException e) {
                throw toReadableException(e);
            }
        }
        return ImmutableMap.copyOf(facts);
    }

    public String findReviewThreadId(String pat, PullRequestRef pr, long rootCommentId)
    {
        ReviewThreadFacts facts = fetchReviewThreadFacts(pat, pr).get(rootCommentId);
        if (facts == null || facts.id() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "GitHub review thread was not found");
        }
        return facts.id();
    }

    private static GitHubReviewThreadsResponse.ReviewThreads reviewThreads(
            GitHubReviewThreadsResponse response)
    {
        return Optional.ofNullable(response)
                .map(GitHubReviewThreadsResponse::data)
                .map(GitHubReviewThreadsResponse.Data::repository)
                .map(GitHubReviewThreadsResponse.Repository::pullRequest)
                .map(GitHubReviewThreadsResponse.PullRequest::reviewThreads)
                .orElse(null);
    }

    private static PrTimelineEvent toReviewThreadEvent(
            List<GitHubReviewComment> thread,
            ReviewThreadFacts facts)
    {
        GitHubReviewComment root = thread.getFirst();
        GitHubReviewComment.User user = root.user();
        ReviewThreadFacts effective = facts == null
                ? new ReviewThreadFacts(
                        null,
                        false,
                        root.line() == null,
                        root.path(),
                        root.line(),
                        root.originalLine(),
                        root.startLine(),
                        root.originalStartLine(),
                        root.side())
                : facts;
        List<PrTimelineComment> replies = thread.stream()
                .map(GitHubPullRequestReadClient::toTimelineComment)
                .collect(toImmutableList());
        return new PrTimelineEvent(
                root.id(),
                "review_thread",
                user == null ? null : user.login(),
                root.createdAt(),
                null,
                new PrTimelinePayload(
                        user == null ? null : user.avatarUrl(),
                        user != null && "Bot".equalsIgnoreCase(user.type()),
                        null,
                        root.htmlUrl(),
                        null, null, null, null, null, null, null, null, null, null,
                        effective.path(),
                        root.diffHunk(),
                        effective.line(),
                        effective.originalLine(),
                        effective.startLine(),
                        effective.originalStartLine(),
                        effective.side(),
                        effective.resolved(),
                        effective.outdated(),
                        ImmutableList.of(),
                        replies),
                Optional.empty());
    }

    static PrTimelineEvent toReviewThreadEvent(GitHubReviewComment comment)
    {
        return toReviewThreadEvent(ImmutableList.of(comment), null);
    }

    private static PrTimelineComment toTimelineComment(GitHubReviewComment comment)
    {
        GitHubReviewComment.User user = comment.user();
        return new PrTimelineComment(
                comment.id(),
                user == null ? null : user.login(),
                user == null ? null : user.avatarUrl(),
                user != null && "Bot".equalsIgnoreCase(user.type()),
                comment.createdAt(),
                comment.body(),
                comment.htmlUrl(),
                comment.reviewId(),
                comment.inReplyToId(),
                toReactions(comment.reactions()));
    }

    private static List<PrReaction> toReactions(GitHubReactions reactions)
    {
        if (reactions == null) {
            return ImmutableList.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("+1", reactions.plusOne());
        counts.put("-1", reactions.minusOne());
        counts.put("laugh", reactions.laugh());
        counts.put("hooray", reactions.hooray());
        counts.put("confused", reactions.confused());
        counts.put("heart", reactions.heart());
        counts.put("rocket", reactions.rocket());
        counts.put("eyes", reactions.eyes());
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new PrReaction(entry.getKey(), entry.getValue()))
                .collect(toImmutableList());
    }

    private record ReviewThreadFacts(
            String id,
            boolean resolved,
            boolean outdated,
            String path,
            Integer line,
            Integer originalLine,
            Integer startLine,
            Integer originalStartLine,
            String side) {}

    public FetchedPullRequest getPullRequest(String pat, PullRequestRef pr) {
        return getPullRequest(pat, pr, Optional.empty()).pullRequest()
                .orElseThrow(() -> new IllegalStateException("Unconditional GitHub PR request returned no body"));
    }

    public PullRequestFetch getPullRequest(String pat, PullRequestRef pr, Optional<String> etag) {
        String fullName = pr.owner() + "/" + pr.repo();
        requireNonNull(etag, "etag is null");
        try {
            RestClient.RequestHeadersSpec<?> request = gitHubRestClient
                    .get()
                    .uri(
                            "/repos/{owner}/{repo}/pulls/{number}",
                            pr.owner(),
                            pr.repo(),
                            pr.number())
                    .header("Authorization", authorization(pat));
            etag.ifPresent(value -> request.header(HttpHeaders.IF_NONE_MATCH, value));
            ResponseEntity<GitHubRepoPullRequestItem> response = request
                    .retrieve()
                    .toEntity(GitHubRepoPullRequestItem.class);
            Optional<String> responseEtag = Optional.ofNullable(response.getHeaders().getETag()).or(() -> etag);
            if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                return new PullRequestFetch(Optional.empty(), responseEtag);
            }
            GitHubRepoPullRequestItem item = response.getBody();
            checkState(item != null, "Empty response from GitHub for " + fullName + "#" + pr.number());
            return new PullRequestFetch(Optional.of(toPullRequest(item, fullName)), responseEtag);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public record PullRequestFetch(Optional<FetchedPullRequest> pullRequest, Optional<String> etag)
    {
        public PullRequestFetch
        {
            requireNonNull(pullRequest, "pullRequest is null");
            requireNonNull(etag, "etag is null");
        }
    }

    /**
     * Pull requests the notifications feed flags for the user's attention — the same signal that
     * generates the review-request / mention email. Keeps {@code review_requested} (asked to
     * review, incl. re-requests) and {@code mention} (directly @-mentioned); drops comments,
     * pushes, CI, and state changes. GitHub's search index occasionally omits a request (observed:
     * a team review request on an enterprise repo never appeared in {@code
     * user-review-requested:@me}), so the dashboard uses this to catch what search drops. {@code
     * all=true} so an item already read but not yet acted on still comes back.
     */
    public List<AttentionPrRef> fetchAttentionPrRefs(String pat) {
        requirePat(pat);
        try {
            List<GitHubNotification> notifications =
                    gitHubRestClient
                            .get()
                            .uri(
                                    uri ->
                                            uri.path("/notifications")
                                                    .queryParam("participating", "true")
                                                    .queryParam("all", "true")
                                                    .queryParam("per_page", PER_PAGE)
                                                    .build())
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (notifications == null) {
                return ImmutableList.of();
            }
            return notifications.stream()
                    .map(GitHubPullRequestReadClient::attentionPrRef)
                    .flatMap(Optional::stream)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /**
     * Notification → PR ref for the dashboard attention backstop: keeps review requests and
     * direct @-mentions on pull requests, empty for every other reason or a non-PR subject.
     * subject.url tail is the PR number and repository.full_name is {@code owner/repo}, so {@link
     * PullRequestRef#parse} handles the split and validation. Package-private for direct unit test.
     */
    static Optional<AttentionPrRef> attentionPrRef(GitHubNotification n) {
        if (n.subject() == null
                || !"PullRequest".equals(n.subject().type())
                || !ATTENTION_REASONS.contains(n.reason())
                || n.subject().url() == null
                || n.repository() == null
                || n.repository().fullName() == null) {
            return Optional.empty();
        }
        String url = n.subject().url();
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) {
            return Optional.empty();
        }
        return PullRequestRef.parse(n.repository().fullName() + "#" + url.substring(slash + 1))
                .map(ref -> new AttentionPrRef(ref, "review_requested".equals(n.reason())));
    }

    public record AttentionPrRef(PullRequestRef ref, boolean reviewRequested) {}

    private static FetchedPullRequest toPullRequest(GitHubRepoPullRequestItem item, String fullName) {
        List<GitHubRepoPullRequestItem.Label> rawLabels =
                Optional.ofNullable(item.labels()).orElse(ImmutableList.of());
        List<String> labels =
                rawLabels.stream()
                        .map(GitHubRepoPullRequestItem.Label::name)
                        .collect(toImmutableList());
        Map<String, String> labelColors =
                rawLabels.stream()
                        .filter(l -> l.name() != null && l.color() != null)
                        .collect(
                                toImmutableMap(
                                        GitHubRepoPullRequestItem.Label::name,
                                        GitHubRepoPullRequestItem.Label::color,
                                        (a, b) -> a));
        List<String> reviewers =
                Optional.ofNullable(item.requestedReviewers()).orElse(ImmutableList.of()).stream()
                        .map(GitHubRepoPullRequestItem.RequestedReviewer::login)
                        .collect(toImmutableList());
        String author =
                Optional.ofNullable(item.user())
                        .map(GitHubRepoPullRequestItem.User::login)
                        .orElse(null);
        return new FetchedPullRequest(
                item.id(),
                fullName,
                item.number(),
                item.title(),
                author,
                item.htmlUrl(),
                item.createdAt(),
                item.updatedAt(),
                AUTHORED,
                labels,
                labelColors,
                item.draft(),
                item.state(),
                item.closedAt(),
                item.mergedAt(),
                reviewers,
                item.mergeable(),
                item.mergeableState(),
                // Head ref is available directly from the list response.
                Optional.ofNullable(item.head())
                        .map(GitHubRepoPullRequestItem.Head::ref)
                        .orElse(null));
    }

    // ── GraphQL: review-thread resolution ────────────────────────────────

    record GitHubNotification(String reason, Subject subject, NotificationRepo repository) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Subject(String url, String type) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record NotificationRepo(@JsonProperty("full_name") String fullName) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubAssignableUser(
            long id,
            String login,
            @JsonProperty("avatar_url") String avatarUrl,
            String type) {}

    // ── Helpers ───────────────────────────────────────────────────────────────
    static FetchedPullRequest toPullRequest(GitHubSearchResponse.Item item, PullRequest.Origin origin) {
        List<GitHubSearchResponse.Label> rawLabels =
                Optional.ofNullable(item.labels()).orElse(ImmutableList.of());
        List<String> labels =
                rawLabels.stream().map(GitHubSearchResponse.Label::name).collect(toImmutableList());
        Map<String, String> labelColors =
                rawLabels.stream()
                        .filter(l -> l.name() != null && l.color() != null)
                        .collect(
                                toImmutableMap(
                                        GitHubSearchResponse.Label::name,
                                        GitHubSearchResponse.Label::color,
                                        (a, b) -> a));
        String author =
                Optional.ofNullable(item.user()).map(GitHubSearchResponse.User::login).orElse(null);
        return new FetchedPullRequest(
                item.id(),
                extractRepo(item.repositoryUrl()),
                item.number(),
                item.title(),
                author,
                item.htmlUrl(),
                item.createdAt(),
                item.updatedAt(),
                origin,
                labels,
                labelColors,
                item.draft(),
                // Search reports a merged PR as
                // state=closed, so merged_at has to come along or every
                // renderer downstream reads it as plainly closed.
                item.state(),
                item.closedAt(),
                Optional.ofNullable(item.pullRequest())
                        .map(GitHubSearchResponse.PullRequestLink::mergedAt)
                        .orElse(null),
                // Search responses expose no reviewers, mergeability or head
                // ref; the next list-page sync fills them in.
                ImmutableList.of(),
                null,
                null,
                null);
    }

    static String extractRepo(String repositoryUrl) {
        if (repositoryUrl == null) {
            return "";
        }
        int index = repositoryUrl.indexOf(REPOS_SEGMENT);
        if (index < 0) {
            return repositoryUrl;
        }
        return repositoryUrl.substring(index + REPOS_SEGMENT.length());
    }
}
