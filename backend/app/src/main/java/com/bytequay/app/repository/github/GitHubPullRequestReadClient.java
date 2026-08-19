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

import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.bytequay.core.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.core.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestReadClient
{
    private final RestClient gitHubRestClient;
    private final GitHubPaginator paginator;

    public GitHubPullRequestReadClient(RestClient gitHubRestClient) {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.paginator = new GitHubPaginator(gitHubRestClient);
    }

    private static final int PER_PAGE = 50;
    private static final String REPOS_SEGMENT = "/repos/";
    private static final Set<String> ATTENTION_REASONS =
            Set.of("review_requested", "mention");

    public List<PullRequest> searchPullRequests(String pat, String query) {
        return searchPullRequestsPaged(pat, query, 1, PER_PAGE).items();
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
                        List.of(), safePage, safePerPage, 0, false);
            }
            PullRequest.Origin origin =
                    query.contains("review-requested") ? REVIEW_REQUESTED : AUTHORED;
            List<PullRequest> items =
                    response.items().stream()
                            .map(item -> toPullRequest(item, origin))
                            .toList();
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
                    Optional.ofNullable(r.labels()).orElse(List.of()).stream()
                            .map(GitHubPullRequestDetailResponse.Label::name)
                            .toList();
            List<String> reviewerLogins =
                    Optional.ofNullable(r.requestedReviewers()).orElse(List.of()).stream()
                            .map(GitHubPullRequestDetailResponse.RequestedReviewer::login)
                            .filter(Objects::nonNull)
                            .toList();
            int reviewerCount = reviewerLogins.size();
            String headSha =
                    Optional.ofNullable(r.head())
                            .map(GitHubPullRequestDetailResponse.Head::sha)
                            .orElse(null);
            String headRef =
                    Optional.ofNullable(r.head())
                            .map(GitHubPullRequestDetailResponse.Head::ref)
                            .orElse(null);
            return new PrRawDetail(
                    r.body(),
                    labels,
                    r.draft(),
                    r.mergeable(),
                    r.mergeableState(),
                    r.additions(),
                    r.deletions(),
                    r.changedFiles(),
                    reviewerCount,
                    reviewerLogins,
                    headSha,
                    headRef);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public List<PrReviewState> fetchPrReviews(String pat, PullRequestRef pr) {
        try {
            List<GitHubReview> reviews =
                    gitHubRestClient
                            .get()
                            .uri(
                                    "/repos/{owner}/{repo}/pulls/{number}/reviews",
                                    pr.owner(),
                                    pr.repo(),
                                    pr.number())
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (reviews == null) {
                return List.of();
            }
            return reviews.stream()
                    .filter(r -> r.user() != null)
                    .map(r -> new PrReviewState(r.user().login(), r.state(), r.submittedAt()))
                    .toList();
        }
        catch (RestClientResponseException e) {
            return List.of();
        }
    }

    public List<DiffFile> fetchPrDiffFiles(String pat, PullRequestRef pr) {
        try {
            List<GitHubPullRequestFileWithPatch> files =
                    gitHubRestClient
                            .get()
                            .uri(
                                    builder ->
                                            builder.path(
                                                            "/repos/{owner}/{repo}/pulls/{number}/files")
                                                    .queryParam("per_page", 100)
                                                    .build(pr.owner(), pr.repo(), pr.number()))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (files == null) {
                return List.of();
            }
            return files.stream()
                    .map(
                            f ->
                                    new DiffFile(
                                            f.filename(),
                                            f.status(),
                                            f.additions(),
                                            f.deletions(),
                                            f.patch()))
                    .toList();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
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
        return paginator
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
                .toList();
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
                .toList();
    }

    private static PrTimelineEvent toCommentedTimelineEvent(GitHubIssueComment c) {
        return new PrTimelineEvent(
                c.id(),
                "commented",
                Optional.ofNullable(c.user()).map(GitHubIssueComment.User::login).orElse(null),
                c.createdAt(),
                c.body(),
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
        return new PrTimelineEvent(
                event.id(),
                event.event(),
                actor,
                timestamp,
                event.body(),
                Optional.empty());
    }

    public PullRequest getPullRequest(String pat, PullRequestRef pr) {
        String fullName = pr.owner() + "/" + pr.repo();
        try {
            GitHubRepoPullRequestItem item =
                    gitHubRestClient
                            .get()
                            .uri(
                                    "/repos/{owner}/{repo}/pulls/{number}",
                                    pr.owner(),
                                    pr.repo(),
                                    pr.number())
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubRepoPullRequestItem.class);
            checkState(item != null, "Empty response from GitHub for " + fullName + "#" + pr.number());
            return toPullRequest(item, fullName);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
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
    public List<PullRequestRef> fetchAttentionPrRefs(String pat) {
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
                return List.of();
            }
            return notifications.stream()
                    .map(GitHubPullRequestReadClient::attentionPrRef)
                    .flatMap(Optional::stream)
                    .toList();
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
    static Optional<PullRequestRef> attentionPrRef(GitHubNotification n) {
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
        return PullRequestRef.parse(n.repository().fullName() + "#" + url.substring(slash + 1));
    }

    private static PullRequest toPullRequest(GitHubRepoPullRequestItem item, String fullName) {
        List<GitHubRepoPullRequestItem.Label> rawLabels =
                Optional.ofNullable(item.labels()).orElse(List.of());
        List<String> labels =
                rawLabels.stream()
                        .map(GitHubRepoPullRequestItem.Label::name)
                        .toList();
        Map<String, String> labelColors =
                rawLabels.stream()
                        .filter(l -> l.name() != null && l.color() != null)
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        GitHubRepoPullRequestItem.Label::name,
                                        GitHubRepoPullRequestItem.Label::color,
                                        (a, b) -> a));
        List<String> reviewers =
                Optional.ofNullable(item.requestedReviewers()).orElse(List.of()).stream()
                        .map(GitHubRepoPullRequestItem.RequestedReviewer::login)
                        .toList();
        String author =
                Optional.ofNullable(item.user())
                        .map(GitHubRepoPullRequestItem.User::login)
                        .orElse(null);
        return new PullRequest(
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
                null,
                null,
                null,
                reviewers,
                null,
                0,
                0,
                0,
                null,
                // List response fields; detail-only fields stay null here.
                item.state(),
                item.closedAt(),
                item.mergedAt(),
                null,
                null,
                null,
                null,
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

    // ── Helpers ───────────────────────────────────────────────────────────────
    static PullRequest toPullRequest(GitHubSearchResponse.Item item, PullRequest.Origin origin) {
        List<GitHubSearchResponse.Label> rawLabels =
                Optional.ofNullable(item.labels()).orElse(List.of());
        List<String> labels =
                rawLabels.stream().map(GitHubSearchResponse.Label::name).toList();
        Map<String, String> labelColors =
                rawLabels.stream()
                        .filter(l -> l.name() != null && l.color() != null)
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        GitHubSearchResponse.Label::name,
                                        GitHubSearchResponse.Label::color,
                                        (a, b) -> a));
        String author =
                Optional.ofNullable(item.user()).map(GitHubSearchResponse.User::login).orElse(null);
        return new PullRequest(
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
                null,
                null,
                null,
                List.of(),
                null,
                0,
                0,
                0,
                null,
                // Search reports a merged PR as
                // state=closed, so merged_at has to come along or every
                // renderer downstream reads it as plainly closed.
                item.state(),
                item.closedAt(),
                Optional.ofNullable(item.pullRequest())
                        .map(GitHubSearchResponse.PullRequestLink::mergedAt)
                        .orElse(null),
                null,
                null,
                null,
                null,
                // Search responses don't expose head.ref; the next
                // list-page sync will fill it in.
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
