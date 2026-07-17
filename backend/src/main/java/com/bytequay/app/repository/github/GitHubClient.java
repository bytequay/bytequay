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
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.RecentEvent;
import com.bytequay.app.domain.RepoActivityItem;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.domain.UserCommitSummary;
import com.bytequay.app.domain.UserOrg;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.UserRepo;
import com.bytequay.app.repository.PullRequestRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.app.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubClient
        implements PullRequestRepository
{
    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private static final int PER_PAGE = 50;
    private static final String REPOS_SEGMENT = "/repos/";

    /** Notification reasons that mean "a pull request wants my attention":
     *  asked to review (incl. re-requests) or directly @-mentioned. */
    private static final Set<String> ATTENTION_REASONS = Set.of("review_requested", "mention");

    private final RestClient gitHubRestClient;
    private final RestClient graphqlRestClient;
    private final GitHubPaginator paginator;

    public GitHubClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient")
                    RestClient gitHubGraphQLRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphqlRestClient = requireNonNull(gitHubGraphQLRestClient, "gitHubGraphQLRestClient is null");
        this.paginator = new GitHubPaginator(gitHubRestClient);
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @Override
    public List<PullRequest> searchPullRequests(String pat, String query)
    {
        requirePat(pat);
        try {
            GitHubSearchResponse response = gitHubRestClient.get()
                    .uri(uri -> uri.path("/search/issues")
                            .queryParam("q", query)
                            .queryParam("per_page", PER_PAGE)
                            .build())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubSearchResponse.class);
            if (response == null || response.items() == null) {
                return ImmutableList.of();
            }
            PullRequest.Origin origin = query.contains("review-requested") ? REVIEW_REQUESTED : AUTHORED;
            return response.items().stream()
                    .map(item -> toPullRequest(item, origin))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public PullRequestHistoryPage searchPullRequestsPaged(
            String pat, String query, int page, int perPage, String sort, String order)
    {
        requirePat(pat);
        int safePage = Math.max(1, page);
        int safePerPage = Math.clamp(perPage, 1, 100);
        try {
            GitHubSearchResponse response = gitHubRestClient.get()
                    .uri(uri -> {
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
                return new PullRequestHistoryPage(ImmutableList.of(), safePage, safePerPage, 0, false);
            }
            PullRequest.Origin origin = query.contains("review-requested") ? REVIEW_REQUESTED : AUTHORED;
            List<PullRequest> items = response.items().stream()
                    .map(item -> toPullRequest(item, origin))
                    .collect(toImmutableList());
            // GitHub caps search at 1000 results, so a page that runs off
            // the end either by total or by the cap should report no more.
            int loadedSoFar = (safePage - 1) * safePerPage + items.size();
            boolean hasMore = items.size() == safePerPage
                    && loadedSoFar < response.totalCount()
                    && loadedSoFar < 1000;
            return new PullRequestHistoryPage(items, safePage, safePerPage, response.totalCount(), hasMore);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    // ── Pull request detail ───────────────────────────────────────────────────

    @Override
    public ProbeResult probeChangedSinceEtag(String pat, PullRequestRef pr, String etag)
    {
        try {
            ResponseEntity<Void> resp = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .headers(h -> {
                        if (etag != null && !etag.isBlank()) {
                            h.set("If-None-Match", etag);
                        }
                    })
                    // toBodilessEntity() lets us read headers (and the
                    // 304 status) without paying for body deserialization.
                    .retrieve()
                    .onStatus(s -> s.value() == 304, (req, res) -> { /* expected */ })
                    .toBodilessEntity();
            String newEtag = resp.getHeaders().getETag();
            // 304 → unchanged, echo the caller's etag (still valid).
            // 200 → new content, return the freshly-issued etag.
            boolean changed = resp.getStatusCode().value() != 304;
            return new ProbeResult(changed, newEtag != null ? newEtag : etag);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public PrRawDetail fetchPrDetail(String pat, PullRequestRef pr)
    {
        try {
            GitHubPullRequestDetailResponse r = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubPullRequestDetailResponse.class);
            if (r == null) {
                return null;
            }
            List<String> labels = Optional.ofNullable(r.labels()).orElse(ImmutableList.of()).stream()
                    .map(GitHubPullRequestDetailResponse.Label::name)
                    .collect(toImmutableList());
            List<String> reviewerLogins = Optional.ofNullable(r.requestedReviewers())
                    .orElse(ImmutableList.of()).stream()
                    .map(GitHubPullRequestDetailResponse.RequestedReviewer::login)
                    .filter(Objects::nonNull)
                    .collect(toImmutableList());
            int reviewerCount = reviewerLogins.size();
            String headSha = Optional.ofNullable(r.head()).map(GitHubPullRequestDetailResponse.Head::sha).orElse(null);
            String headRef = Optional.ofNullable(r.head()).map(GitHubPullRequestDetailResponse.Head::ref).orElse(null);
            String headRepo = Optional.ofNullable(r.head())
                    .map(GitHubPullRequestDetailResponse.Head::repo)
                    .map(GitHubPullRequestDetailResponse.Repo::fullName)
                    .orElse(null);
            String baseRef = Optional.ofNullable(r.base()).map(GitHubPullRequestDetailResponse.Base::ref).orElse(null);
            String baseRepo = Optional.ofNullable(r.base())
                    .map(GitHubPullRequestDetailResponse.Base::repo)
                    .map(GitHubPullRequestDetailResponse.Repo::fullName)
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
                    headRef,
                    headRepo,
                    baseRef,
                    baseRepo,
                    r.state(),
                    Boolean.TRUE.equals(r.merged()));
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public String fetchPrTitle(String pat, PullRequestRef pr)
    {
        try {
            GitHubPullRequestDetailResponse r = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubPullRequestDetailResponse.class);
            return r == null ? null : r.title();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<PrReviewState> fetchPrReviews(String pat, PullRequestRef pr)
    {
        try {
            List<GitHubReview> reviews = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (reviews == null) {
                return ImmutableList.of();
            }
            return reviews.stream()
                    .filter(r -> r.user() != null)
                    .map(r -> new PrReviewState(r.user().login(), r.state(), r.submittedAt()))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            return ImmutableList.of();
        }
    }

    @Override
    public List<PrCheckRunState> fetchPrCheckRuns(String pat, String owner, String repo, String sha)
    {
        // GitHub's /commits/{sha}/check-runs defaults to per_page=30 and
        // does NOT auto-aggregate across pages. Repos with large CI
        // matrices (Trino, Spark, etc.) routinely run 50+ check-runs
        // per commit, so a single un-paginated 30-row page silently
        // dropped failing checks from the merge bar. Walk pages at the
        // 100-row max until either the running total catches up to the
        // server-reported total_count or we hit a safety cap. Five
        // pages = 500 checks, far past anything we'd realistically see.
        ImmutableList.Builder<PrCheckRunState> out = ImmutableList.builder();
        int collected = 0;
        for (int page = 1; page <= 5; page++) {
            final int currentPage = page;
            try {
                GitHubCheckRunsResponse r = gitHubRestClient.get()
                        .uri(u -> u.path("/repos/{owner}/{repo}/commits/{sha}/check-runs")
                                .queryParam("per_page", 100)
                                .queryParam("page", currentPage)
                                .build(owner, repo, sha))
                        .header("Authorization", authorization(pat))
                        .retrieve()
                        .body(GitHubCheckRunsResponse.class);
                if (r == null || r.checkRuns() == null || r.checkRuns().isEmpty()) {
                    break;
                }
                for (GitHubCheckRunsResponse.CheckRun c : r.checkRuns()) {
                    out.add(new PrCheckRunState(
                            c.id(),
                            c.name(),
                            c.status(),
                            c.conclusion(),
                            c.htmlUrl(),
                            c.output() != null ? c.output().title() : null,
                            c.output() != null ? c.output().summary() : null));
                }
                collected += r.checkRuns().size();
                if (collected >= r.totalCount() || r.checkRuns().size() < 100) {
                    break;
                }
            }
            catch (RestClientResponseException e) {
                break;
            }
        }
        return out.build();
    }

    @Override
    public String fetchPrDiff(String pat, PullRequestRef pr)
    {
        try {
            String diff = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .header("Accept", "application/vnd.github.diff")
                    .retrieve()
                    .body(String.class);
            return diff != null ? diff : "";
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<PullRequestDetail.ChangedFile> fetchPrFiles(String pat, PullRequestRef pr)
    {
        try {
            List<GitHubPullRequestFile> files = gitHubRestClient.get()
                    .uri(builder -> builder.path("/repos/{owner}/{repo}/pulls/{number}/files")
                            .queryParam("per_page", 100)
                            .build(pr.owner(), pr.repo(), pr.number()))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (files == null) {
                return ImmutableList.of();
            }
            return files.stream()
                    .map(f -> new PullRequestDetail.ChangedFile(
                            f.filename(),
                            f.additions(),
                            f.deletions(),
                            f.status()))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            return ImmutableList.of();
        }
    }

    @Override
    public List<DiffFile> fetchPrDiffFiles(String pat, PullRequestRef pr)
    {
        try {
            List<GitHubPullRequestFileWithPatch> files = gitHubRestClient.get()
                    .uri(builder -> builder.path("/repos/{owner}/{repo}/pulls/{number}/files")
                            .queryParam("per_page", 100)
                            .build(pr.owner(), pr.repo(), pr.number()))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (files == null) {
                return ImmutableList.of();
            }
            return files.stream()
                    .map(f -> new DiffFile(
                            f.filename(),
                            f.status(),
                            f.additions(),
                            f.deletions(),
                            f.patch()))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<String> fetchFileBlobLines(String pat, RepoRef repo, String path, String sha)
    {
        try {
            GitHubFileContent content = gitHubRestClient.get()
                    .uri(builder -> builder.path("/repos/{owner}/{repo}/contents/{+path}")
                            .queryParam("ref", sha)
                            .build(repo.owner(), repo.repo(), path))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubFileContent.class);
            if (content == null || content.content() == null) {
                return ImmutableList.of();
            }
            String encoded = content.content();
            // GitHub's Contents API returns base64 with embedded newlines —
            // strip them before decoding so we don't choke MimeDecoder-style
            // strict implementations.
            String cleaned = encoded.replace("\n", "").replace("\r", "");
            byte[] decoded = Base64.getDecoder().decode(cleaned);
            return extractLinesFromBytes(decoded);
        }
        catch (RestClientResponseException e) {
            // 404 means the file doesn't exist at this ref (e.g. expanding
            // a deleted file's pre-image — caller can render an empty
            // gap rather than failing the whole diff view).
            if (e.getStatusCode().value() == 404) {
                return ImmutableList.of();
            }
            throw toReadableException(e);
        }
    }

    private static List<String> extractLinesFromBytes(byte[] decoded)
    {
        String text = new String(decoded, StandardCharsets.UTF_8);
        // Use -1 limit so trailing empty strings (when file ends with \n)
        // are preserved — caller wants exact line count.
        String[] lines = text.split("\n", -1);
        ImmutableList.Builder<String> out = ImmutableList.builder();
        // Drop the synthetic trailing empty line that split() produces
        // when the file ends with \n; otherwise the caller sees a
        // phantom blank line at the end.
        int len = lines.length;
        if (len > 0 && lines[len - 1].isEmpty()) {
            len -= 1;
        }
        for (int i = 0; i < len; i++) {
            out.add(lines[i]);
        }
        return out.build();
    }

    @Override
    public List<DiffFile> fetchCommitDiffFiles(String pat, PullRequestRef pr, String sha)
    {
        try {
            GitHubCommitDetail detail = gitHubRestClient.get()
                    .uri(builder -> builder.path("/repos/{owner}/{repo}/commits/{sha}")
                            .build(pr.owner(), pr.repo(), sha))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubCommitDetail.class);
            if (detail == null || detail.files() == null) {
                return ImmutableList.of();
            }
            return detail.files().stream()
                    .map(filename -> new DiffFile(
                            filename.filename(),
                            filename.status(),
                            filename.additions(),
                            filename.deletions(),
                            filename.patch()))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<PullRequestCommit> fetchPrCommits(String pat, PullRequestRef pr)
    {
        try {
            List<GitHubPullRequestCommit> commits = gitHubRestClient.get()
                    .uri(builder -> builder.path("/repos/{owner}/{repo}/pulls/{number}/commits")
                            .queryParam("per_page", 100)
                            .build(pr.owner(), pr.repo(), pr.number()))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (commits == null) {
                return ImmutableList.of();
            }
            return commits.stream()
                    .map(commit -> new PullRequestCommit(
                            commit.sha(),
                            Optional.ofNullable(commit.author()).map(GitHubPullRequestCommit.Author::login).orElse(null),
                            Optional.ofNullable(commit.commit())
                                    .map(GitHubPullRequestCommit.CommitInfo::author)
                                    .map(GitHubPullRequestCommit.GitSignature::name)
                                    .orElse(null),
                            Optional.ofNullable(commit.commit())
                                    .map(GitHubPullRequestCommit.CommitInfo::author)
                                    .map(GitHubPullRequestCommit.GitSignature::date)
                                    .orElse(null),
                            Optional.ofNullable(commit.commit())
                                    .map(GitHubPullRequestCommit.CommitInfo::message)
                                    .orElse(null)))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<PrTimelineEvent> fetchPrTimeline(String pat, PullRequestRef pr, Instant since)
    {
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
        return paginator.paginateSince(pat, "/repos/{owner}/{repo}/issues/{number}/timeline",
                new ParameterizedTypeReference<List<GitHubTimelineEvent>>() {},
                since,
                pr.owner(), pr.repo(), pr.number())
                .stream()
                .map(GitHubClient::toTimelineEvent)
                .collect(toImmutableList());
    }

    @Override
    public List<PrTimelineEvent> fetchPrIssueComments(String pat, PullRequestRef pr, Instant since)
    {
        return paginator.paginateSince(pat, "/repos/{owner}/{repo}/issues/{number}/comments",
                new ParameterizedTypeReference<List<GitHubIssueComment>>() {},
                since,
                pr.owner(), pr.repo(), pr.number())
                .stream()
                .map(GitHubClient::toCommentedTimelineEvent)
                .collect(toImmutableList());
    }

    private static PrTimelineEvent toCommentedTimelineEvent(GitHubIssueComment c)
    {
        return new PrTimelineEvent(
                c.id(),
                "commented",
                Optional.ofNullable(c.user()).map(GitHubIssueComment.User::login).orElse(null),
                null,
                c.createdAt(),
                c.body(),
                null,
                null,
                null,
                null,
                c.authorAssociation(),
                toIssueCommentReactions(c.reactions()));
    }

    private static Reactions toIssueCommentReactions(GitHubIssueComment.Reactions r)
    {
        if (r == null) {
            return Reactions.EMPTY;
        }
        return new Reactions(
                r.plusOne(), r.minusOne(), r.laugh(), r.hooray(),
                r.confused(), r.heart(), r.rocket(), r.eyes());
    }

    @Override
    public List<PrReviewThreadMessage> fetchPrReviewComments(String pat, PullRequestRef pr, Instant since)
    {
        return paginator.paginateSince(pat, "/repos/{owner}/{repo}/pulls/{number}/comments",
                new ParameterizedTypeReference<List<GitHubReviewComment>>() {},
                since,
                pr.owner(), pr.repo(), pr.number())
                .stream()
                .map(GitHubClient::toReviewThreadMessage)
                .collect(toImmutableList());
    }

    private static PrReviewThreadMessage toReviewThreadMessage(GitHubReviewComment c)
    {
        String author = Optional.ofNullable(c.user()).map(GitHubReviewComment.User::login).orElse(null);
        // GitHub returns position=null when the comment is anchored to a
        // line that no longer exists in the current diff (force-push
        // shifted things). That's the "Outdated" signal we surface on
        // the thread's badge.
        boolean outdated = c.position() == null;
        return new PrReviewThreadMessage(
                c.id(),
                c.inReplyToId(),
                c.pullRequestReviewId(),
                author,
                c.body(),
                c.path(),
                c.line(),
                c.side(),
                c.diffHunk(),
                c.commitId(),
                c.createdAt(),
                toReactions(c.reactions()),
                outdated,
                c.startLine(),
                c.startSide(),
                c.originalLine(),
                c.originalStartLine(),
                c.authorAssociation(),
                /* graphqlNodeId */ null,
                /* resolved */ null,
                /* resolvedBy */ null);
    }

    private static Reactions toReactions(GitHubReviewComment.Reactions r)
    {
        if (r == null) {
            return Reactions.EMPTY;
        }
        return new Reactions(
                r.plusOne(),
                r.minusOne(),
                r.laugh(),
                r.hooray(),
                r.confused(),
                r.heart(),
                r.rocket(),
                r.eyes());
    }

    @Override
    public Optional<PullRequestDetail.LinkedIssue> fetchIssue(String pat, RepoRef repo, int number)
    {
        try {
            GitHubIssueItem item = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/issues/{number}", repo.owner(), repo.repo(), number)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubIssueItem.class);
            if (item == null) {
                return Optional.empty();
            }
            return Optional.of(new PullRequestDetail.LinkedIssue(
                    item.number(), item.title(), item.state(), item.htmlUrl()));
        }
        catch (RestClientResponseException e) {
            // 404 (issue removed) and 410 (gone) are normal — leave the
            // closing reference unresolved rather than failing the page.
            return Optional.empty();
        }
    }

    private static PrTimelineEvent toTimelineEvent(GitHubTimelineEvent e)
    {
        String event = Optional.ofNullable(e.event()).orElse("");
        String actor = switch (event) {
            case "committed" -> Optional.ofNullable(e.author()).map(GitHubTimelineEvent.Author::name).orElse(null);
            case "reviewed" -> Optional.ofNullable(e.user()).map(GitHubTimelineEvent.User::login).orElse(null);
            default -> Optional.ofNullable(e.actor()).map(GitHubTimelineEvent.Actor::login)
                    .orElseGet(() -> Optional.ofNullable(e.user()).map(GitHubTimelineEvent.User::login).orElse(null));
        };
        Instant timestamp = switch (event) {
            case "committed" -> Optional.ofNullable(e.author()).map(GitHubTimelineEvent.Author::date).orElse(null);
            case "reviewed" -> e.submittedAt();
            default -> e.createdAt();
        };
        String beforeSha = Optional.ofNullable(e.beforeCommit()).map(GitHubTimelineEvent.Commit::sha).orElse(null);
        // For "committed" events the SHA lives at the top level; for
        // head_ref_force_pushed it's nested under after_commit. Fall back
        // to whichever is present so afterSha always carries "the commit
        // SHA most relevant to this event" — used by the UI to render
        // "pushed abc1234".
        String afterSha = Optional.ofNullable(e.afterCommit()).map(GitHubTimelineEvent.Commit::sha)
                .orElse(e.sha());
        String requestedReviewer = "review_requested".equals(event)
                ? Optional.ofNullable(e.requestedReviewer()).map(GitHubTimelineEvent.User::login).orElse(null)
                : null;
        Long reviewId = "reviewed".equals(event) ? e.id() : null;
        String labelName = Optional.ofNullable(e.label()).map(GitHubTimelineEvent.Label::name).orElse(null);
        String labelColor = Optional.ofNullable(e.label()).map(GitHubTimelineEvent.Label::color).orElse(null);
        String milestoneTitle = Optional.ofNullable(e.milestone())
                .map(GitHubTimelineEvent.Milestone::title).orElse(null);
        String assigneeLogin = Optional.ofNullable(e.assignee()).map(GitHubTimelineEvent.User::login).orElse(null);
        GitHubTimelineEvent.SourceIssue crossRef = Optional.ofNullable(e.source())
                .map(GitHubTimelineEvent.Source::issue).orElse(null);
        Integer crossRefNumber = crossRef == null ? null : crossRef.number();
        String crossRefTitle = crossRef == null ? null : crossRef.title();
        String crossRefUrl = crossRef == null ? null : crossRef.htmlUrl();
        boolean crossRefIsPullRequest = crossRef != null && crossRef.pullRequest() != null;
        // Timeline events don't carry reaction tallies in GitHub's
        // payload (reactions are on the issue-comment endpoint, fetched
        // separately). Use Reactions.EMPTY rather than null so the
        // store has a stable shape and the UI never NPEs.
        return new PrTimelineEvent(
                e.id(), e.event(), actor, e.state(), timestamp, e.body(), beforeSha, afterSha, requestedReviewer,
                reviewId, e.authorAssociation(), Reactions.EMPTY, labelName, labelColor, milestoneTitle,
                assigneeLogin, crossRefNumber, crossRefTitle, crossRefUrl, crossRefIsPullRequest);
    }

    // ── Pulls API ─────────────────────────────────────────────────────────────

    @Override
    public List<PullRequest> listPullRequests(String pat, RepoRef repo, ListPullRequestsQuery query)
    {
        String fullName = repo.fullName();
        try {
            List<GitHubRepoPullRequestItem> items = gitHubRestClient.get()
                    .uri(u -> u.path("/repos/{owner}/{repo}/pulls")
                            .queryParam("state", "open")
                            .queryParam("per_page", PER_PAGE)
                            .build(repo.owner(), repo.repo()))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (items == null) {
                return ImmutableList.of();
            }
            return items.stream()
                    .map(item -> toPullRequest(item, fullName))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /**
     * Opens a new pull request. Maps to POST /repos/{owner}/{repo}/pulls.
     * The {@link CreatePullRequestCommand#head} field carries the
     * cross-fork form ({@code "<owner>:<branch>"}) when the PR is
     * being opened from a fork; same-repo PRs use a bare branch name.
     */
    @Override
    public PullRequest createPullRequest(String pat, RepoRef repo, CreatePullRequestCommand command)
    {
        Map<String, Object> body = Maps.newHashMap();
        body.put("title", command.title());
        body.put("head", command.head());
        body.put("base", command.base());
        command.body().ifPresent(v -> body.put("body", v));
        command.draft().ifPresent(v -> body.put("draft", v));
        command.maintainerCanModify().ifPresent(v -> body.put("maintainer_can_modify", v));
        try {
            GitHubRepoPullRequestItem item = gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls", repo.owner(), repo.repo())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GitHubRepoPullRequestItem.class);
            if (item == null) {
                throw new IllegalStateException(
                        "GitHub returned an empty body for POST /repos/" + repo.fullName() + "/pulls");
            }
            return toPullRequest(item, repo.fullName());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /**
     * Single-PR fetch used by the deep-link fallback: when the user clicks
     * a PR link from the home-page activity feed and the PR isn't in the
     * (capped) list response, we look it up directly. Same JSON shape as
     * list items, so {@link #toPullRequest} reuses the same mapping.
     */
    @Override
    public PullRequest getPullRequest(String pat, PullRequestRef pr)
    {
        String fullName = pr.owner() + "/" + pr.repo();
        try {
            GitHubRepoPullRequestItem item = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}",
                            pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubRepoPullRequestItem.class);
            if (item == null) {
                throw new IllegalStateException("Empty response from GitHub for " + fullName + "#" + pr.number());
            }
            return toPullRequest(item, fullName);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /**
     * Pull requests the notifications feed flags for the user's attention —
     * the same signal that generates the review-request / mention email. Keeps
     * {@code review_requested} (asked to review, incl. re-requests) and
     * {@code mention} (directly @-mentioned); drops comments, pushes, CI, and
     * state changes. GitHub's search index occasionally omits a request
     * (observed: a team review request on an enterprise repo never appeared in
     * {@code user-review-requested:@me}), so the dashboard uses this to catch
     * what search drops. {@code all=true} so an item already read but not yet
     * acted on still comes back.
     */
    @Override
    public List<PullRequestRef> fetchAttentionPrRefs(String pat)
    {
        requirePat(pat);
        try {
            List<GitHubNotification> notifications = gitHubRestClient.get()
                    .uri(uri -> uri.path("/notifications")
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
                    .map(GitHubClient::attentionPrRef)
                    .flatMap(Optional::stream)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /** Notification → PR ref for the dashboard attention backstop: keeps review
     *  requests and direct @-mentions on pull requests, empty for every other
     *  reason or a non-PR subject. subject.url tail is the PR number and
     *  repository.full_name is {@code owner/repo}, so {@link PullRequestRef#parse}
     *  handles the split and validation. Package-private for direct unit test. */
    static Optional<PullRequestRef> attentionPrRef(GitHubNotification n)
    {
        if (n.subject() == null || !"PullRequest".equals(n.subject().type())
                || !ATTENTION_REASONS.contains(n.reason())
                || n.subject().url() == null
                || n.repository() == null || n.repository().fullName() == null) {
            return Optional.empty();
        }
        String url = n.subject().url();
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) {
            return Optional.empty();
        }
        return PullRequestRef.parse(n.repository().fullName() + "#" + url.substring(slash + 1));
    }

    private static PullRequest toPullRequest(GitHubRepoPullRequestItem item, String fullName)
    {
        List<GitHubRepoPullRequestItem.Label> rawLabels = Optional.ofNullable(item.labels())
                .orElse(ImmutableList.of());
        List<String> labels = rawLabels.stream()
                .map(GitHubRepoPullRequestItem.Label::name)
                .collect(toImmutableList());
        Map<String, String> labelColors = rawLabels.stream()
                .filter(l -> l.name() != null && l.color() != null)
                .collect(toImmutableMap(GitHubRepoPullRequestItem.Label::name, GitHubRepoPullRequestItem.Label::color, (a, b) -> a));
        List<String> reviewers = Optional.ofNullable(item.requestedReviewers()).orElse(ImmutableList.of()).stream()
                .map(GitHubRepoPullRequestItem.RequestedReviewer::login)
                .collect(toImmutableList());
        String author = Optional.ofNullable(item.user()).map(GitHubRepoPullRequestItem.User::login).orElse(null);
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
                // V26 kanban fields populated from the list response.
                // mergeable / mergeableState / headPushedAt / reviewerVerdicts
                // come from the detail fetch downstream and stay null here.
                item.state(),
                item.closedAt(),
                item.mergedAt(),
                null,
                null,
                null,
                null,
                // V37 snooze fields — local-state only, joined in by
                // SqlitePullRequestStore.toDomain via PrViewState.
                null,
                null,
                // V42: head ref captured from the list response so the
                // local-repo kanban can map a local branch to its open
                // PR without waiting for the per-PR detail fetch.
                Optional.ofNullable(item.head()).map(GitHubRepoPullRequestItem.Head::ref).orElse(null));
    }

    @Override
    public MergeResult mergePullRequest(String pat, PullRequestRef pr, MergePullRequestCommand command)
    {
        Map<String, Object> body = Maps.newHashMap();
        body.put("merge_method", command.mergeMethod());
        command.commitTitle().ifPresent(t -> body.put("commit_title", t));
        command.commitMessage().ifPresent(m -> body.put("commit_message", m));
        command.sha().ifPresent(s -> body.put("sha", s));
        try {
            return gitHubRestClient.put()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/merge",
                            pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MergeResult.class);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void deleteBranch(String pat, PullRequestRef pr, String branchName)
    {
        try {
            gitHubRestClient.delete()
                    .uri("/repos/{owner}/{repo}/git/refs/heads/{branch}", pr.owner(), pr.repo(), branchName)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            // Already gone (deleted elsewhere, or this call raced a retry) — idempotent success.
            if (e.getStatusCode().value() == 404) {
                return;
            }
            throw toReadableException(e);
        }
    }

    @Override
    public PullRequest updatePullRequest(String pat, PullRequestRef pr, UpdatePullRequestCommand command)
    {
        Map<String, Object> body = Maps.newHashMap();
        command.title().ifPresent(v -> body.put("title", v));
        command.body().ifPresent(v -> body.put("body", v));
        command.state().ifPresent(v -> body.put("state", v));
        command.base().ifPresent(v -> body.put("base", v));
        command.maintainerCanModify().ifPresent(v -> body.put("maintainer_can_modify", v));
        try {
            gitHubRestClient.patch()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            // Callers from our service use this fire-and-forget (we refresh
            // via the next sync); returning null keeps the signature simple
            // without parsing the full PR response we don't need here.
            return null;
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public PrTimelineEvent createIssueComment(String pat, PullRequestRef pr, String body)
    {
        try {
            GitHubIssueComment posted = gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/issues/{number}/comments",
                            pr.owner(), pr.repo(), pr.number())
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
            return toCommentedTimelineEvent(posted);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public PullRequest requestReviewers(String pat, PullRequestRef pr, RequestReviewersCommand command)
    {
        Map<String, Object> payload = Maps.newHashMap();
        payload.put("reviewers", Optional.ofNullable(command.reviewers()).orElse(ImmutableList.of()));
        payload.put("team_reviewers", Optional.ofNullable(command.teamReviewers()).orElse(ImmutableList.of()));
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/requested_reviewers",
                            pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /**
     * Re-run the failed jobs of every completed-but-unsuccessful workflow
     * run on {@code headSha} — GitHub's purpose-built fix for a flaky
     * failure. Returns the number of runs re-triggered (0 when nothing on
     * the head failed, so the caller can say "nothing to re-run").
     */
    @Override
    public int rerunFailedChecks(String pat, RepoRef repo, String headSha)
    {
        if (headSha == null || headSha.isBlank()) {
            return 0;
        }
        try {
            GitHubWorkflowRunsResponse runs = gitHubRestClient.get()
                    .uri(u -> u.path("/repos/{owner}/{repo}/actions/runs")
                            .queryParam("head_sha", headSha)
                            .queryParam("per_page", 30)
                            .build(repo.owner(), repo.repo()))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubWorkflowRunsResponse.class);
            if (runs == null || runs.workflowRuns() == null) {
                return 0;
            }
            int reRun = 0;
            for (GitHubWorkflowRunsResponse.Run run : runs.workflowRuns()) {
                if (!"completed".equals(run.status()) || !isFailedConclusion(run.conclusion())) {
                    continue;
                }
                gitHubRestClient.post()
                        .uri("/repos/{owner}/{repo}/actions/runs/{id}/rerun-failed-jobs",
                                repo.owner(), repo.repo(), run.id())
                        .header("Authorization", authorization(pat))
                        .retrieve()
                        .toBodilessEntity();
                reRun++;
            }
            return reRun;
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static boolean isFailedConclusion(String conclusion)
    {
        return "failure".equals(conclusion)
                || "cancelled".equals(conclusion)
                || "timed_out".equals(conclusion)
                || "startup_failure".equals(conclusion);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubWorkflowRunsResponse(@JsonProperty("workflow_runs") List<Run> workflowRuns)
    {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Run(long id, String status, String conclusion) {}
    }

    @Override
    public void removeRequestedReviewers(String pat, PullRequestRef pr, RequestReviewersCommand command)
    {
        Map<String, Object> payload = Maps.newHashMap();
        payload.put("reviewers", Optional.ofNullable(command.reviewers()).orElse(ImmutableList.of()));
        payload.put("team_reviewers", Optional.ofNullable(command.teamReviewers()).orElse(ImmutableList.of()));
        try {
            gitHubRestClient.method(HttpMethod.DELETE)
                    .uri("/repos/{owner}/{repo}/pulls/{number}/requested_reviewers",
                            pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void createInlineReviewComment(
            String pat,
            PullRequestRef pr,
            String body,
            String path,
            int line,
            String side,
            String commitId,
            Integer startLine,
            String startSide)
    {
        Map<String, Object> payload = Maps.newHashMap();
        payload.put("body", body);
        payload.put("path", path);
        payload.put("line", line);
        payload.put("side", side);
        payload.put("commit_id", commitId);
        // GitHub requires both start_line + start_side together. When
        // start_line equals line, it's still treated as a single-line
        // comment by GitHub — but we skip the start_* fields entirely
        // in that case so the payload looks identical to the legacy
        // single-line path.
        if (startLine != null && startSide != null && startLine != line) {
            payload.put("start_line", startLine);
            payload.put("start_side", startSide);
        }
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/comments",
                            pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public PrReviewThreadMessage replyToReviewComment(String pat, PullRequestRef pr, long rootCommentId, String body)
    {
        try {
            GitHubReviewComment created = gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/comments/{commentId}/replies",
                            pr.owner(), pr.repo(), pr.number(), rootCommentId)
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ImmutableMap.of("body", body))
                    .retrieve()
                    .body(GitHubReviewComment.class);
            if (created == null) {
                throw new IllegalStateException("GitHub returned no body from reply-to-review-comment");
            }
            return toReviewThreadMessage(created);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void editIssueComment(String pat, String owner, String repo, long commentId, String body)
    {
        try {
            gitHubRestClient.method(HttpMethod.PATCH)
                    .uri("/repos/{owner}/{repo}/issues/comments/{commentId}",
                            owner, repo, commentId)
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ImmutableMap.of("body", body))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void editReviewComment(String pat, String owner, String repo, long commentId, String body)
    {
        try {
            gitHubRestClient.method(HttpMethod.PATCH)
                    .uri("/repos/{owner}/{repo}/pulls/comments/{commentId}",
                            owner, repo, commentId)
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ImmutableMap.of("body", body))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void deleteIssueComment(String pat, String owner, String repo, long commentId)
    {
        try {
            gitHubRestClient.method(HttpMethod.DELETE)
                    .uri("/repos/{owner}/{repo}/issues/comments/{commentId}",
                            owner, repo, commentId)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void deleteReviewComment(String pat, String owner, String repo, long commentId)
    {
        try {
            gitHubRestClient.method(HttpMethod.DELETE)
                    .uri("/repos/{owner}/{repo}/pulls/comments/{commentId}",
                            owner, repo, commentId)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void addIssueCommentReaction(String pat, String owner, String repo, long commentId, String content)
    {
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/issues/comments/{commentId}/reactions",
                            owner, repo, commentId)
                    .header("Authorization", authorization(pat))
                    .header("Accept", "application/vnd.github.squirrel-girl-preview+json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ImmutableMap.of("content", content))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void addReviewCommentReaction(String pat, String owner, String repo, long commentId, String content)
    {
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/comments/{commentId}/reactions",
                            owner, repo, commentId)
                    .header("Authorization", authorization(pat))
                    // GitHub's reactions API requires the squirrel-girl
                    // preview header on older endpoints; modern API
                    // versions accept the default Accept header but the
                    // preview is harmless and explicit.
                    .header("Accept", "application/vnd.github.squirrel-girl-preview+json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ImmutableMap.of("content", content))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            // 200 OK returns the existing reaction if the user already
            // added it (idempotent); 201 Created on first add. Both are
            // .ok(). Anything else maps to our standard error.
            throw toReadableException(e);
        }
    }

    // ── GraphQL: review-thread resolution ────────────────────────────────

    /** GraphQL query for thread metadata, one page (100 threads, 50 comments
     *  per thread) at a time — {@link #fetchReviewThreadResolution} follows
     *  {@code pageInfo} to cover PRs with more than 100 threads. */
    private static final String REVIEW_THREAD_QUERY = """
            query($owner: String!, $name: String!, $number: Int!, $after: String) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $number) {
                  reviewThreads(first: 100, after: $after) {
                    nodes {
                      id
                      isResolved
                      resolvedBy {
                        login
                      }
                      comments(first: 50) {
                        nodes {
                          databaseId
                        }
                      }
                    }
                    pageInfo {
                      hasNextPage
                      endCursor
                    }
                  }
                }
              }
            }
            """;

    /** Hard cap on pages fetched per PR (100 threads/page, so 2000 threads
     *  total) — a runaway-loop backstop far above any real PR, in case
     *  GitHub ever returned a cursor that never terminates. */
    private static final int MAX_REVIEW_THREAD_PAGES = 20;

    @Override
    public List<ReviewThreadMeta> fetchReviewThreadResolution(String pat, PullRequestRef pr)
    {
        List<ReviewThreadMeta> out = Lists.newArrayList();
        String cursor = null;
        for (int page = 0; page < MAX_REVIEW_THREAD_PAGES; page++) {
            Map<String, Object> variables = new HashMap<>();
            variables.put("owner", pr.owner());
            variables.put("name", pr.repo());
            variables.put("number", pr.number());
            variables.put("after", cursor);
            Map<String, Object> body = ImmutableMap.of("query", REVIEW_THREAD_QUERY, "variables", variables);
            ReviewThreadGqlResponse response;
            try {
                response = graphqlRestClient.post()
                        .header("Authorization", authorization(pat))
                        .body(body)
                        .retrieve()
                        .body(ReviewThreadGqlResponse.class);
            }
            catch (RestClientResponseException e) {
                throw toReadableException(e);
            }
            if (response == null || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null) {
                break;
            }
            var threads = response.data().repository().pullRequest().reviewThreads();
            if (threads == null || threads.nodes() == null) {
                break;
            }
            for (var t : threads.nodes()) {
                if (t == null || t.id() == null) continue;
                if (t.comments() == null || t.comments().nodes() == null
                        || t.comments().nodes().isEmpty()) {
                    continue;
                }
                // The thread root comment is the FIRST in the list (GraphQL
                // returns them in chronological order). Use its databaseId
                // to join back to the REST root.
                Long rootDbId = t.comments().nodes().get(0).databaseId();
                if (rootDbId == null) continue;
                out.add(new ReviewThreadMeta(rootDbId, t.id(), Boolean.TRUE.equals(t.isResolved()),
                        t.resolvedBy() == null ? null : t.resolvedBy().login()));
            }
            if (threads.pageInfo() == null || !threads.pageInfo().hasNextPage()) {
                break;
            }
            cursor = threads.pageInfo().endCursor();
        }
        return ImmutableList.copyOf(out);
    }

    @Override
    public void resolveReviewThread(String pat, String threadNodeId)
    {
        runResolveMutation(pat, threadNodeId, true);
    }

    @Override
    public void unresolveReviewThread(String pat, String threadNodeId)
    {
        runResolveMutation(pat, threadNodeId, false);
    }

    private void runResolveMutation(String pat, String threadNodeId, boolean resolve)
    {
        // Both mutations have the same shape — { input: { threadId } }
        // — so we share the call site and pick the mutation name
        // based on the verb.
        String mutation = resolve
                ? "mutation($id: ID!) { resolveReviewThread(input: { threadId: $id }) { thread { id isResolved } } }"
                : "mutation($id: ID!) { unresolveReviewThread(input: { threadId: $id }) { thread { id isResolved } } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", mutation,
                "variables", ImmutableMap.of("id", threadNodeId));
        try {
            graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    // GraphQL response DTOs. Loose — the query above is small enough
    // that hand-rolled records are simpler than a generic walker.

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubNotification(String reason, Subject subject, NotificationRepo repository)
    {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Subject(String title, String url, String type) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record NotificationRepo(@JsonProperty("full_name") String fullName) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReviewThreadGqlResponse(GqlData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlData(GqlRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlRepo(GqlPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlPr(GqlReviewThreads reviewThreads) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlReviewThreads(List<GqlThread> nodes, GqlPageInfo pageInfo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlPageInfo(boolean hasNextPage, String endCursor) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlThread(String id, Boolean isResolved, GqlActor resolvedBy, GqlComments comments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlComments(List<GqlCommentNode> nodes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlCommentNode(Long databaseId) {}

    // ── GraphQL: merge-queue entry state ─────────────────────────────────

    /** GraphQL query for the PR's current merge-queue entry, if any.
     *  REST doesn't expose this per-PR — github.com itself uses this
     *  same GraphQL field on its PR detail page. Tiny query so the
     *  point-cost is negligible. */
    private static final String MERGE_QUEUE_STATE_QUERY = """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $number) {
                  mergeQueueEntry {
                    state
                  }
                  mergeQueue {
                    id
                  }
                }
              }
            }
            """;

    @Override
    public PullRequestRepository.MergeQueueInfo fetchMergeQueueInfo(String pat, PullRequestRef pr)
    {
        Map<String, Object> body = ImmutableMap.of(
                "query", MERGE_QUEUE_STATE_QUERY,
                "variables", ImmutableMap.of(
                        "owner", pr.owner(),
                        "name", pr.repo(),
                        "number", pr.number()));
        try {
            MergeQueueGqlResponse response = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .body(MergeQueueGqlResponse.class);
            if (response == null
                    || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null) {
                return new PullRequestRepository.MergeQueueInfo(false, null);
            }
            MergeQueueGqlPr pullRequest = response.data().repository().pullRequest();
            boolean queueConfigured = pullRequest.mergeQueue() != null
                    && pullRequest.mergeQueue().id() != null
                    && !pullRequest.mergeQueue().id().isBlank();
            String entryState = null;
            if (pullRequest.mergeQueueEntry() != null) {
                String state = pullRequest.mergeQueueEntry().state();
                entryState = state == null || state.isBlank() ? null : state;
            }
            return new PullRequestRepository.MergeQueueInfo(queueConfigured, entryState);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueGqlResponse(MergeQueueGqlData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueGqlData(MergeQueueGqlRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueGqlRepo(MergeQueueGqlPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueGqlPr(MergeQueueGqlEntry mergeQueueEntry, MergeQueueGqlQueue mergeQueue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueGqlEntry(String state) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueGqlQueue(String id) {}

    @Override
    public void setPullRequestDraft(String pat, PullRequestRef pr, boolean draft)
    {
        // Two-step GraphQL: REST exposes neither toggle, so first fetch
        // the PR's opaque GraphQL node id, then run the appropriate
        // mutation. Roundtrip cost is fine for a rare user-triggered
        // action; we don't pre-cache the node id since it's only used
        // here.
        String pullRequestNodeId;
        try {
            String idQuery = "query($owner: String!, $name: String!, $number: Int!) {"
                    + " repository(owner: $owner, name: $name) {"
                    + "   pullRequest(number: $number) { id }"
                    + " } }";
            Map<String, Object> idBody = ImmutableMap.of(
                    "query", idQuery,
                    "variables", ImmutableMap.of(
                            "owner", pr.owner(),
                            "name", pr.repo(),
                            "number", pr.number()));
            PullRequestIdGqlResponse idResponse = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(idBody)
                    .retrieve()
                    .body(PullRequestIdGqlResponse.class);
            if (idResponse == null
                    || idResponse.data() == null
                    || idResponse.data().repository() == null
                    || idResponse.data().repository().pullRequest() == null
                    || idResponse.data().repository().pullRequest().id() == null) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                        "PR " + pr.owner() + "/" + pr.repo() + "#" + pr.number() + " not found");
            }
            pullRequestNodeId = idResponse.data().repository().pullRequest().id();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }

        String mutation = draft
                ? "mutation($id: ID!) { convertPullRequestToDraft(input: { pullRequestId: $id }) { pullRequest { id isDraft } } }"
                : "mutation($id: ID!) { markPullRequestReadyForReview(input: { pullRequestId: $id }) { pullRequest { id isDraft } } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", mutation,
                "variables", ImmutableMap.of("id", pullRequestNodeId));
        try {
            graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestIdGqlResponse(PullRequestIdGqlData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestIdGqlData(PullRequestIdGqlRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestIdGqlRepo(PullRequestIdGqlPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestIdGqlPr(String id) {}

    // ── GraphQL: auto-merge (enable / disable / state) ────────────────────
    //
    // REST does not expose any of these — github.com itself uses the same
    // GraphQL mutations and the autoMergeRequest object on the PR. Each
    // mutation needs the PR's opaque GraphQL node id, so they all share the
    // node-id fetch below.

    private String fetchPullRequestNodeId(String pat, PullRequestRef pr)
    {
        String idQuery = "query($owner: String!, $name: String!, $number: Int!) {"
                + " repository(owner: $owner, name: $name) {"
                + "   pullRequest(number: $number) { id }"
                + " } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", idQuery,
                "variables", ImmutableMap.of(
                        "owner", pr.owner(),
                        "name", pr.repo(),
                        "number", pr.number()));
        try {
            PullRequestIdGqlResponse response = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .body(PullRequestIdGqlResponse.class);
            if (response == null
                    || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null
                    || response.data().repository().pullRequest().id() == null) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                        "PR " + pr.owner() + "/" + pr.repo() + "#" + pr.number() + " not found");
            }
            return response.data().repository().pullRequest().id();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void enableAutoMerge(String pat, PullRequestRef pr, String mergeMethod)
    {
        String nodeId = fetchPullRequestNodeId(pat, pr);
        String mutation = "mutation($id: ID!, $method: PullRequestMergeMethod!) {"
                + " enablePullRequestAutoMerge(input: { pullRequestId: $id, mergeMethod: $method }) {"
                + "   pullRequest { id }"
                + " } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", mutation,
                "variables", ImmutableMap.of("id", nodeId, "method", mergeMethod));
        try {
            graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void disableAutoMerge(String pat, PullRequestRef pr)
    {
        String nodeId = fetchPullRequestNodeId(pat, pr);
        String mutation = "mutation($id: ID!) {"
                + " disablePullRequestAutoMerge(input: { pullRequestId: $id }) {"
                + "   pullRequest { id }"
                + " } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", mutation,
                "variables", ImmutableMap.of("id", nodeId));
        try {
            graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public Optional<AutoMergeStatus> fetchAutoMergeStatus(String pat, PullRequestRef pr)
    {
        String query = "query($owner: String!, $name: String!, $number: Int!) {"
                + " repository(owner: $owner, name: $name) {"
                + "   pullRequest(number: $number) {"
                + "     autoMergeRequest { mergeMethod enabledBy { login } }"
                + "   }"
                + " } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", query,
                "variables", ImmutableMap.of(
                        "owner", pr.owner(),
                        "name", pr.repo(),
                        "number", pr.number()));
        try {
            AutoMergeGqlResponse response = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .body(AutoMergeGqlResponse.class);
            if (response == null
                    || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null
                    || response.data().repository().pullRequest().autoMergeRequest() == null) {
                return Optional.empty();
            }
            AutoMergeGqlRequest req = response.data().repository().pullRequest().autoMergeRequest();
            String login = req.enabledBy() == null ? null : req.enabledBy().login();
            return Optional.of(new AutoMergeStatus(req.mergeMethod(), login));
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AutoMergeGqlResponse(AutoMergeGqlData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AutoMergeGqlData(AutoMergeGqlRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AutoMergeGqlRepo(AutoMergeGqlPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AutoMergeGqlPr(AutoMergeGqlRequest autoMergeRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AutoMergeGqlRequest(String mergeMethod, AutoMergeGqlActor enabledBy) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AutoMergeGqlActor(String login) {}

    // ── GraphQL: merge queue probe + enqueue ─────────────────────────────

    @Override
    public Optional<MergeQueueProbe> probeMergeQueue(String pat, PullRequestRef pr)
    {
        String query = "query($owner: String!, $name: String!, $number: Int!) {"
                + " repository(owner: $owner, name: $name) {"
                + "   pullRequest(number: $number) { id mergeQueue { id } }"
                + " } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", query,
                "variables", ImmutableMap.of(
                        "owner", pr.owner(),
                        "name", pr.repo(),
                        "number", pr.number()));
        try {
            MergeQueueProbeGqlResponse response = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .body(MergeQueueProbeGqlResponse.class);
            if (response == null
                    || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null) {
                return Optional.empty();
            }
            MergeQueueProbeGqlPr probe = response.data().repository().pullRequest();
            // mergeQueue is non-null iff the PR's base branch has merge
            // queue enabled. We don't care about queue.id beyond presence —
            // the mutation takes the PR's node id, not the queue's.
            if (probe.mergeQueue() == null || probe.mergeQueue().id() == null) {
                return Optional.empty();
            }
            if (probe.id() == null) {
                return Optional.empty();
            }
            return Optional.of(new MergeQueueProbe(probe.id()));
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public Optional<String> pullRequestNodeId(String pat, PullRequestRef pr)
    {
        // Same shape as the merge-queue probe but without the mergeQueue
        // gate — used when a direct merge is rejected because a ruleset
        // requires the queue (GraphQL's pullRequest.mergeQueue is null for
        // ruleset-driven queues, so the probe can't see it).
        String query = "query($owner: String!, $name: String!, $number: Int!) {"
                + " repository(owner: $owner, name: $name) {"
                + "   pullRequest(number: $number) { id }"
                + " } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", query,
                "variables", ImmutableMap.of(
                        "owner", pr.owner(),
                        "name", pr.repo(),
                        "number", pr.number()));
        try {
            MergeQueueProbeGqlResponse response = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .body(MergeQueueProbeGqlResponse.class);
            if (response == null
                    || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(response.data().repository().pullRequest().id());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public MergeResult enqueuePullRequest(String pat, String pullRequestNodeId)
    {
        // enqueuePullRequest takes only the PR id — the queue's
        // configured merge method overrides any caller preference. Reading
        // back state lets us build a useful message ("queued at position
        // 3, awaiting checks") rather than a bare success.
        String mutation = "mutation($id: ID!) {"
                + " enqueuePullRequest(input: { pullRequestId: $id }) {"
                + "   mergeQueueEntry { id position state }"
                + " } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", mutation,
                "variables", ImmutableMap.of("id", pullRequestNodeId));
        try {
            EnqueueGqlResponse response = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .body(EnqueueGqlResponse.class);
            EnqueueGqlEntry entry = response == null
                    || response.data() == null
                    || response.data().enqueuePullRequest() == null
                            ? null
                            : response.data().enqueuePullRequest().mergeQueueEntry();
            String message;
            if (entry == null) {
                message = "Added to merge queue";
            }
            else if (entry.position() != null && entry.state() != null) {
                message = "Added to merge queue (position " + entry.position()
                        + ", " + entry.state().toLowerCase(Locale.ROOT).replace('_', ' ') + ")";
            }
            else if (entry.state() != null) {
                message = "Added to merge queue (" + entry.state().toLowerCase(Locale.ROOT).replace('_', ' ') + ")";
            }
            else {
                message = "Added to merge queue";
            }
            return MergeResult.enqueued(message);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueProbeGqlResponse(MergeQueueProbeGqlData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueProbeGqlData(MergeQueueProbeGqlRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueProbeGqlRepo(MergeQueueProbeGqlPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueProbeGqlPr(String id, MergeQueueProbeGqlQueue mergeQueue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueProbeGqlQueue(String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueGqlResponse(EnqueueGqlData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueGqlData(EnqueueGqlPayload enqueuePullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueGqlPayload(EnqueueGqlEntry mergeQueueEntry) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueGqlEntry(String id, Integer position, String state) {}

    @Override
    public void dequeuePullRequest(String pat, PullRequestRef pr)
    {
        // dequeuePullRequest takes the merge queue ENTRY id, not the PR id —
        // fetch it via a small GraphQL probe first. If the PR isn't currently
        // in a queue we no-op so a stale "Queued" cache state doesn't error
        // when the user clicks "Remove from queue" after GitHub already
        // merged or removed the PR.
        String entryQuery = "query($owner: String!, $name: String!, $number: Int!) {"
                + " repository(owner: $owner, name: $name) {"
                + "   pullRequest(number: $number) {"
                + "     mergeQueueEntry { id }"
                + "   }"
                + " } }";
        Map<String, Object> queryBody = ImmutableMap.of(
                "query", entryQuery,
                "variables", ImmutableMap.of(
                        "owner", pr.owner(),
                        "name", pr.repo(),
                        "number", pr.number()));
        String entryId;
        try {
            DequeueProbeGqlResponse probe = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(queryBody)
                    .retrieve()
                    .body(DequeueProbeGqlResponse.class);
            if (probe == null
                    || probe.data() == null
                    || probe.data().repository() == null
                    || probe.data().repository().pullRequest() == null
                    || probe.data().repository().pullRequest().mergeQueueEntry() == null) {
                // Not in a queue any more — treat as a successful no-op so the
                // UI doesn't surface a confusing error for a state the user
                // already left.
                return;
            }
            entryId = probe.data().repository().pullRequest().mergeQueueEntry().id();
            if (entryId == null) {
                return;
            }
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }

        String mutation = "mutation($id: ID!) {"
                + " dequeuePullRequest(input: { id: $id }) {"
                + "   mergeQueueEntry { id }"
                + " } }";
        Map<String, Object> body = ImmutableMap.of(
                "query", mutation,
                "variables", ImmutableMap.of("id", entryId));
        try {
            graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DequeueProbeGqlResponse(DequeueProbeGqlData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DequeueProbeGqlData(DequeueProbeGqlRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DequeueProbeGqlRepo(DequeueProbeGqlPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DequeueProbeGqlPr(DequeueProbeGqlEntry mergeQueueEntry) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DequeueProbeGqlEntry(String id) {}

    @Override
    public List<SuggestedReviewer> fetchSuggestedReviewers(String pat, PullRequestRef pr)
    {
        // GitHub caps suggestedReviewers at 5 today; first(10) is generous
        // headroom in case that ever changes. avatarUrl(size:40) keeps the
        // image small enough to render at 20×20 without retina blur.
        String query = "query($owner: String!, $repo: String!, $number: Int!) {"
                + "  repository(owner: $owner, name: $repo) {"
                + "    pullRequest(number: $number) {"
                + "      suggestedReviewers {"
                + "        isAuthor"
                + "        isCommenter"
                + "        reviewer { login name avatarUrl(size: 40) }"
                + "      }"
                + "    }"
                + "  }"
                + "}";
        Map<String, Object> body = ImmutableMap.of(
                "query", query,
                "variables", ImmutableMap.of(
                        "owner", pr.owner(),
                        "repo", pr.repo(),
                        "number", pr.number()));
        try {
            SuggestedReviewersGqlResponse response = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .body(SuggestedReviewersGqlResponse.class);
            if (response == null
                    || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null) {
                return ImmutableList.of();
            }
            List<GqlSuggestedReviewer> nodes = response.data().repository().pullRequest().suggestedReviewers();
            if (nodes == null) {
                return ImmutableList.of();
            }
            return nodes.stream()
                    .filter(n -> n.reviewer() != null && n.reviewer().login() != null)
                    .map(n -> new SuggestedReviewer(
                            n.reviewer().login(),
                            n.reviewer().avatarUrl(),
                            n.reviewer().name(),
                            Boolean.TRUE.equals(n.isAuthor()),
                            Boolean.TRUE.equals(n.isCommenter())))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            // Non-essential affordance — degrade silently rather than
            // failing the whole reviewer panel on a flaky GraphQL hop.
            return ImmutableList.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SuggestedReviewersGqlResponse(SuggestedData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SuggestedData(SuggestedRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SuggestedRepo(SuggestedPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SuggestedPr(List<GqlSuggestedReviewer> suggestedReviewers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlSuggestedReviewer(Boolean isAuthor, Boolean isCommenter, GqlActor reviewer) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GqlActor(String login, String name, String avatarUrl) {}

    @Override
    public PullRequestReview createReview(String pat, PullRequestRef pr, CreateReviewCommand command)
    {
        Map<String, Object> body = Maps.newHashMap();
        command.commitId().ifPresent(id -> body.put("commit_id", id));
        command.body().ifPresent(b -> body.put("body", b));
        body.put("event", command.event());
        if (!command.comments().isEmpty()) {
            // Build the comments[] array as plain maps so the JSON shape is
            // exactly what GitHub expects (snake_case + start_line/start_side
            // omitted for single-line comments). Reusing the record's
            // Jackson serialization tied us to camelCase / Optional quirks.
            List<Map<String, Object>> serialized = command.comments().stream()
                    .map(c -> {
                        Map<String, Object> m = Maps.newHashMap();
                        m.put("path", c.path());
                        c.line().ifPresent(line -> m.put("line", line));
                        c.position().ifPresent(pos -> m.put("position", pos));
                        m.put("side", c.side());
                        m.put("body", c.body());
                        // GitHub treats start_line == line as single-line,
                        // but rejects start_line/start_side appearing alone
                        // — only include the pair when it differs from line.
                        c.startLine().ifPresent(sl -> {
                            int line = c.line().orElse(sl);
                            if (sl != line) {
                                m.put("start_line", sl);
                                c.startSide().ifPresent(ss -> m.put("start_side", ss));
                            }
                        });
                        return m;
                    })
                    .collect(toImmutableList());
            body.put("comments", serialized);
        }
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/reviews",
                            pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return new PullRequestReview(0, "", "", command.event(), "", null, "");
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    // ── Repos and Users ───────────────────────────────────────────────────────

    @Override
    public List<RepoIssue> fetchRepoIssues(String pat, RepoRef repo, String state)
    {
        // GitHub's /repos/{owner}/{repo}/issues returns both issues and PRs
        // (PRs are a special subtype of issues), so we request the API maximum
        // (per_page=100) and filter PRs out client-side. We also sort by
        // updated desc so the freshest issues show up first regardless of the
        // 100-item cap.
        String stateParam = (state == null || state.isBlank()) ? "open" : state;
        try {
            List<GitHubIssueItem> items = gitHubRestClient.get()
                    .uri(u -> u.path("/repos/{owner}/{repo}/issues")
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
                    .map(GitHubClient::toRepoIssue)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public IssueDetail fetchIssueDetail(String pat, RepoRef repo, int number)
    {
        try {
            GitHubIssueItem item = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/issues/{number}",
                            repo.owner(), repo.repo(), number)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubIssueItem.class);
            if (item == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "Empty response from GitHub /issues/{n}");
            }
            // Comments are loaded in a follow-up call from the service so
            // GitHubClient stays one-fetch-per-method. Pass an empty list
            // here; RepoService merges in the real comments.
            return toIssueDetail(item, ImmutableList.of());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<IssueDetail.Comment> fetchIssueDetailComments(String pat, RepoRef repo, int number)
    {
        try {
            List<GitHubIssueComment> raw = paginator.paginate(pat,
                    "/repos/{owner}/{repo}/issues/{number}/comments",
                    new ParameterizedTypeReference<List<GitHubIssueComment>>() {},
                    repo.owner(), repo.repo(), number);
            return raw.stream()
                    .map(GitHubClient::toIssueDetailComment)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<IssueTimelineEvent> fetchIssueTimeline(String pat, RepoRef repo, int number)
    {
        try {
            List<GitHubTimelineEvent> raw = paginator.paginate(pat,
                    "/repos/{owner}/{repo}/issues/{number}/timeline",
                    new ParameterizedTypeReference<List<GitHubTimelineEvent>>() {},
                    repo.owner(), repo.repo(), number);
            return raw.stream()
                    // Skip "commented" rows — those ride on the comments
                    // endpoint already (and carry richer reaction data
                    // there). Activity tab renders structural events only.
                    .filter(e -> !"commented".equals(e.event()))
                    .map(GitHubClient::toIssueTimelineEvent)
                    .filter(Objects::nonNull)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static IssueTimelineEvent toIssueTimelineEvent(GitHubTimelineEvent e)
    {
        Instant when = e.createdAt() != null ? e.createdAt() : e.submittedAt();
        String actor = Optional.ofNullable(e.actor()).map(GitHubTimelineEvent.Actor::login)
                .orElseGet(() -> Optional.ofNullable(e.user()).map(GitHubTimelineEvent.User::login).orElse(null));
        IssueTimelineEvent.Label label = e.label() == null
                ? null
                : new IssueTimelineEvent.Label(e.label().name(), e.label().color());
        String assignee = e.assignee() == null ? null : e.assignee().login();
        String milestone = e.milestone() == null ? null : e.milestone().title();
        IssueTimelineEvent.Rename rename = e.rename() == null
                ? null
                : new IssueTimelineEvent.Rename(e.rename().from(), e.rename().to());
        IssueTimelineEvent.CrossReference crossRef = null;
        if (e.source() != null && e.source().issue() != null) {
            GitHubTimelineEvent.SourceIssue src = e.source().issue();
            String repoFullName = src.repository() == null ? null : src.repository().fullName();
            crossRef = new IssueTimelineEvent.CrossReference(
                    src.number(),
                    src.title(),
                    src.state(),
                    src.pullRequest() != null,
                    repoFullName,
                    src.htmlUrl());
        }
        return new IssueTimelineEvent(e.event(), actor, when, label, assignee, milestone, rename, crossRef);
    }

    @Override
    public boolean fetchIssueSubscription(String pat, RepoRef repo, int number)
    {
        try {
            SubscriptionResponse resp = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/issues/{number}/subscription",
                            repo.owner(), repo.repo(), number)
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
    public void setIssueSubscription(String pat, RepoRef repo, int number, boolean subscribe)
    {
        try {
            if (subscribe) {
                gitHubRestClient.put()
                        .uri("/repos/{owner}/{repo}/issues/{number}/subscription",
                                repo.owner(), repo.repo(), number)
                        .header("Authorization", authorization(pat))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ImmutableMap.of("subscribed", true))
                        .retrieve()
                        .toBodilessEntity();
            }
            else {
                gitHubRestClient.delete()
                        .uri("/repos/{owner}/{repo}/issues/{number}/subscription",
                                repo.owner(), repo.repo(), number)
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
    public IssueDetail setIssueState(String pat, RepoRef repo, int number, String state)
    {
        try {
            GitHubIssueItem item = gitHubRestClient.patch()
                    .uri("/repos/{owner}/{repo}/issues/{number}",
                            repo.owner(), repo.repo(), number)
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
    public IssueDetail.Comment postIssueComment(String pat, RepoRef repo, int number, String body)
    {
        try {
            GitHubIssueComment posted = gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/issues/{number}/comments",
                            repo.owner(), repo.repo(), number)
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

    private static IssueDetail toIssueDetail(GitHubIssueItem item, List<IssueDetail.Comment> comments)
    {
        List<IssueDetail.Label> labels = Optional.ofNullable(item.labels()).orElse(ImmutableList.of()).stream()
                .map(l -> new IssueDetail.Label(l.name(), l.color()))
                .collect(toImmutableList());
        List<IssueDetail.Assignee> assignees = Optional.ofNullable(item.assignees()).orElse(ImmutableList.of()).stream()
                .map(u -> new IssueDetail.Assignee(u.login(), u.avatarUrl()))
                .collect(toImmutableList());
        IssueDetail.Milestone milestone = item.milestone() == null
                ? null
                : new IssueDetail.Milestone(item.milestone().title(), item.milestone().state());
        String authorLogin = Optional.ofNullable(item.user()).map(GitHubIssueItem.User::login).orElse(null);
        String authorAvatar = Optional.ofNullable(item.user()).map(GitHubIssueItem.User::avatarUrl).orElse(null);
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

    private static IssueDetail.Comment toIssueDetailComment(GitHubIssueComment c)
    {
        String authorLogin = Optional.ofNullable(c.user()).map(GitHubIssueComment.User::login).orElse(null);
        String authorAvatar = Optional.ofNullable(c.user()).map(GitHubIssueComment.User::avatarUrl).orElse(null);
        return new IssueDetail.Comment(
                c.id(),
                authorLogin,
                authorAvatar,
                c.body(),
                c.createdAt(),
                toIssueCommentReactions(c.reactions()));
    }

    @Override
    public RepoMeta fetchRepoMeta(String pat, RepoRef repo)
    {
        try {
            GitHubRepoResponse repoResp = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}", repo.owner(), repo.repo())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubRepoResponse.class);
            if (repoResp == null) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub /repos");
            }

            // /languages is a flat name → byte-count map. We tolerate
            // failure here (rare empty repos / private repos with
            // restricted scopes) — falling back to an empty map keeps
            // the rest of the hero card rendering.
            Map<String, Long> languages;
            try {
                languages = gitHubRestClient.get()
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

            String licenseName = Optional.ofNullable(repoResp.license())
                    .map(GitHubRepoResponse.License::spdxId)
                    .orElse(Optional.ofNullable(repoResp.license())
                            .map(GitHubRepoResponse.License::name)
                            .orElse(null));

            GitHubRepoResponse.Parent parent = repoResp.parent();
            String parentOwner = parent == null
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
    public void createFork(String pat, RepoRef repo)
    {
        requirePat(pat);
        try {
            gitHubRestClient.post()
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
    public List<RepoActivityItem> fetchRepoActivity(String pat, RepoRef repo)
    {
        try {
            List<GitHubRepoEvent> events = gitHubRestClient.get()
                    .uri(u -> u.path("/repos/{owner}/{repo}/events")
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

    /** Translates one GitHub events-API entry into a normalized
     *  {@link RepoActivityItem}. Returns null for event types we
     *  don't care to surface (e.g. WatchEvent — adds noise without
     *  adding signal). */
    private static RepoActivityItem toRepoActivityItem(GitHubRepoEvent e, RepoRef repo)
    {
        String actor = Optional.ofNullable(e.actor()).map(GitHubRepoEvent.Actor::login).orElse(null);
        String htmlBase = "https://github.com/" + repo.owner() + "/" + repo.repo();
        String title;
        String url = htmlBase;
        switch (e.type()) {
            case "PushEvent" -> {
                int commits = Optional.ofNullable(e.payload())
                        .map(GitHubRepoEvent.Payload::commits)
                        .map(List::size)
                        .orElse(0);
                String ref = Optional.ofNullable(e.payload()).map(GitHubRepoEvent.Payload::ref).orElse("");
                String branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
                title = commits + (commits == 1 ? " commit" : " commits") + " pushed to " + (branch.isEmpty() ? "branch" : branch);
            }
            case "PullRequestEvent" -> {
                String action = Optional.ofNullable(e.payload()).map(GitHubRepoEvent.Payload::action).orElse("updated");
                Integer num = Optional.ofNullable(e.payload())
                        .map(GitHubRepoEvent.Payload::pullRequest)
                        .map(GitHubRepoEvent.PullRequestRefDto::number)
                        .orElse(null);
                String prTitle = Optional.ofNullable(e.payload())
                        .map(GitHubRepoEvent.Payload::pullRequest)
                        .map(GitHubRepoEvent.PullRequestRefDto::title)
                        .orElse("");
                if (num == null) return null;
                title = "#" + num + " " + prTitle + " " + action;
                url = htmlBase + "/pull/" + num;
            }
            case "PullRequestReviewEvent" -> {
                Integer num = Optional.ofNullable(e.payload())
                        .map(GitHubRepoEvent.Payload::pullRequest)
                        .map(GitHubRepoEvent.PullRequestRefDto::number)
                        .orElse(null);
                if (num == null) return null;
                title = "review on #" + num;
                url = htmlBase + "/pull/" + num;
            }
            case "IssuesEvent" -> {
                String action = Optional.ofNullable(e.payload()).map(GitHubRepoEvent.Payload::action).orElse("updated");
                Integer num = Optional.ofNullable(e.payload())
                        .map(GitHubRepoEvent.Payload::issue)
                        .map(GitHubRepoEvent.IssueRefDto::number)
                        .orElse(null);
                String issueTitle = Optional.ofNullable(e.payload())
                        .map(GitHubRepoEvent.Payload::issue)
                        .map(GitHubRepoEvent.IssueRefDto::title)
                        .orElse("");
                if (num == null) return null;
                title = "#" + num + " " + issueTitle + " " + action;
                url = htmlBase + "/issues/" + num;
            }
            case "IssueCommentEvent" -> {
                Integer num = Optional.ofNullable(e.payload())
                        .map(GitHubRepoEvent.Payload::issue)
                        .map(GitHubRepoEvent.IssueRefDto::number)
                        .orElse(null);
                if (num == null) return null;
                title = "comment on #" + num;
                url = htmlBase + "/issues/" + num;
            }
            case "ReleaseEvent" -> {
                String tag = Optional.ofNullable(e.payload())
                        .map(GitHubRepoEvent.Payload::release)
                        .map(GitHubRepoEvent.ReleaseRefDto::tagName)
                        .orElse("a release");
                title = tag + " released";
                url = htmlBase + "/releases/tag/" + tag;
            }
            case "CreateEvent" -> {
                String refType = Optional.ofNullable(e.payload()).map(GitHubRepoEvent.Payload::refType).orElse("ref");
                String ref = Optional.ofNullable(e.payload()).map(GitHubRepoEvent.Payload::ref).orElse("");
                title = refType + " " + ref + " created";
            }
            case "DeleteEvent" -> {
                String refType = Optional.ofNullable(e.payload()).map(GitHubRepoEvent.Payload::refType).orElse("ref");
                String ref = Optional.ofNullable(e.payload()).map(GitHubRepoEvent.Payload::ref).orElse("");
                title = refType + " " + ref + " deleted";
            }
            // Skip noisy event types.
            case "WatchEvent", "ForkEvent" -> { return null; }
            default -> title = e.type();
        }
        return new RepoActivityItem(e.type(), actor, title, url, e.createdAt());
    }

    private static RepoIssue toRepoIssue(GitHubIssueItem item)
    {
        List<String> labels = Optional.ofNullable(item.labels()).orElse(ImmutableList.of()).stream()
                .map(GitHubIssueItem.Label::name)
                .collect(toImmutableList());
        String author = Optional.ofNullable(item.user()).map(GitHubIssueItem.User::login).orElse(null);
        return new RepoIssue(
                item.id(),
                item.number(),
                item.title(),
                author,
                item.state(),
                item.htmlUrl(),
                item.updatedAt(),
                labels);
    }

    @Override
    public UserProfile fetchUserProfile(String pat)
    {
        try {
            GitHubUserProfileResponse response = gitHubRestClient.get()
                    .uri("/user")
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubUserProfileResponse.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub /user");
            }
            return toUserProfile(response);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public UserProfile updateUserProfile(String pat, String name, String bio, String location)
    {
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
            GitHubUserProfileResponse response = gitHubRestClient.patch()
                    .uri("/user")
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GitHubUserProfileResponse.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub PATCH /user");
            }
            return toUserProfile(response);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static UserProfile toUserProfile(GitHubUserProfileResponse response)
    {
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
    public ContributionCalendar fetchContributionCalendar(String pat, String login)
    {
        // contributionsCollection without from/to defaults to the trailing
        // 12 months — exactly what the home-page card claims in its
        // "Last 12 months" badge. The {color} field comes back as
        // GitHub's own palette hex, so the UI inherits whatever palette
        // GitHub ships without us having to bucket counts client-side.
        String query = "query($login: String!) {"
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
        Map<String, Object> body = ImmutableMap.of(
                "query", query,
                "variables", ImmutableMap.of("login", login));
        try {
            ContributionGqlResponse response = graphqlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .body(ContributionGqlResponse.class);
            if (response == null
                    || response.data() == null
                    || response.data().user() == null
                    || response.data().user().contributionsCollection() == null
                    || response.data().user().contributionsCollection().contributionCalendar() == null) {
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
            if (rawWeeks.isEmpty()) {
                throw new IllegalStateException("GitHub contribution calendar returned no weeks");
            }
            List<ContributionCalendar.Week> weeks = rawWeeks.stream()
                    .map(w -> new ContributionCalendar.Week(
                            (w.contributionDays() == null ? ImmutableList.<ContributionGqlDay>of() : w.contributionDays())
                                    .stream()
                                    .map(d -> new ContributionCalendar.Day(
                                            LocalDate.parse(d.date()),
                                            d.contributionCount() == null ? 0 : d.contributionCount(),
                                            d.color()))
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
    public List<UserCommitSummary> fetchUserCommitsOnDate(String pat, String login, String isoDate)
    {
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
            CommitSearchResponse response = gitHubRestClient.get()
                    .uri(u -> u.path("/search/commits")
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
                    .map(GitHubClient::toUserCommitSummary)
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

    private static UserCommitSummary toUserCommitSummary(CommitSearchItem item)
    {
        String repoFullName = item.repository() != null ? item.repository().fullName() : "";
        String rawMessage = item.commit() != null && item.commit().message() != null
                ? item.commit().message() : "";
        // Only the first line — the cube popover is one row per commit.
        int newline = rawMessage.indexOf('\n');
        String shortMessage = newline >= 0 ? rawMessage.substring(0, newline) : rawMessage;
        Instant authoredAt = null;
        if (item.commit() != null && item.commit().author() != null && item.commit().author().date() != null) {
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
            @JsonProperty("total_count") Integer totalCount,
            List<CommitSearchItem> items) {}

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
    public List<UserRepo> fetchUserRepos(String pat)
    {
        // Page through /user/repos until GitHub returns fewer than PER_PAGE
        // items (or we hit MAX_PAGES as a safety net). Users at big companies
        // commonly have several hundred repos via org_member affiliation.
        int perPage = 100;
        int maxPages = 10;
        ImmutableList.Builder<UserRepo> all = ImmutableList.builder();
        for (int page = 1; page <= maxPages; page++) {
            int currentPage = page;
            try {
                List<GitHubUserRepoItem> items = gitHubRestClient.get()
                        .uri(u -> u.path("/user/repos")
                                .queryParam("sort", "pushed")
                                // Include org repos the user has access to — without
                                // `organization_member`, company repos never appear in
                                // the list regardless of the token's scope.
                                .queryParam("affiliation", "owner,collaborator,organization_member")
                                .queryParam("per_page", perPage)
                                .queryParam("page", currentPage)
                                .build())
                        .header("Authorization", authorization(pat))
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                if (items == null || items.isEmpty()) {
                    break;
                }
                items.stream().map(GitHubClient::toUserRepo).forEach(all::add);
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

    private static UserRepo toUserRepo(GitHubUserRepoItem item)
    {
        String owner = Optional.ofNullable(item.owner()).map(GitHubUserRepoItem.Owner::login).orElse("");
        return new UserRepo(
                owner,
                item.name(),
                item.fullName(),
                item.description(),
                item.language(),
                item.stargazersCount());
    }

    @Override
    public boolean fetchHasSponsorsListing(String pat)
    {
        // The REST API doesn't expose Sponsors enrolment, so we fall back to
        // a single-field GraphQL query. Any error (network, scope, GitHub
        // unauthenticated) is swallowed so a flaky GraphQL endpoint can't
        // break the rest of the profile load — we just hide the row.
        try {
            String query = "{\"query\":\"query { viewer { hasSponsorsListing } }\"}";
            GitHubViewerSponsorsResponse response = gitHubRestClient.post()
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
    public boolean fetchViewerCanWrite(String pat, RepoRef repo)
    {
        // /repos/{owner}/{repo} returns a `permissions` block when called
        // with an authenticated PAT; `permissions.push` is true for users
        // with write/maintain/admin access. Swallowing errors keeps the
        // merge button greyed-out on any failure (404, rate-limit, scope) —
        // which is the safe default.
        try {
            GitHubRepoPermissionsResponse response = gitHubRestClient.get()
                    .uri(u -> u.path("/repos/{owner}/{repo}").build(repo.owner(), repo.repo()))
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
    public boolean fetchCollaboratorCanWrite(String pat, RepoRef repo, String login)
    {
        // /repos/{owner}/{repo}/collaborators/{login}/permission returns the
        // collaborator's permission ("admin" | "write" | "read" | "none";
        // maintain→write, triage→read in this legacy field). admin/write means
        // push access — the same test that lights GitHub's green approval mark.
        // Errors (404 for a non-collaborator, rate-limit) fall through to
        // false, so an unconfirmable reviewer simply doesn't count.
        try {
            GitHubCollaboratorPermissionResponse response = gitHubRestClient.get()
                    .uri(u -> u.path("/repos/{owner}/{repo}/collaborators/{login}/permission")
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
    record GitHubRepoPermissions(Boolean admin, Boolean maintain, Boolean push, Boolean triage, Boolean pull) {}

    /** Matches the job_id at the end of an Actions check_run's
     *  details_url, e.g. {@code https://github.com/o/r/actions/runs/123/job/456}.
     *  Used by {@link #fetchCheckRunLog} to bridge check_run.id →
     *  actions/jobs.id — the two are different resources and only the
     *  job_id is accepted by /actions/jobs/{id}/logs. */
    private static final Pattern ACTIONS_JOB_URL =
            Pattern.compile("/actions/runs/\\d+/job/(\\d+)(?:[/?#]|$)");

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubCheckRunDetail(
            @JsonProperty("id") long id,
            @JsonProperty("details_url") String detailsUrl,
            @JsonProperty("html_url") String htmlUrl) {}

    @Override
    public Optional<String> fetchCheckRunLog(String pat, RepoRef repo, long checkRunId)
    {
        // check_run.id is NOT the actions/jobs.id — they're separate
        // resources that happen to share names. /actions/jobs/{id}/logs
        // only accepts the job_id, so we resolve check_run → job_id by
        // looking up the check run's details_url (which for Actions
        // check_runs is a github.com/.../actions/runs/{run}/job/{job}
        // URL) and extracting the trailing job_id.
        //
        // External CI (Circle/Jenkins/etc.) sets details_url to its own
        // hosted UI — that won't match ACTIONS_JOB_URL, and we return
        // empty so the merge card shows "no log available".
        long jobId;
        try {
            GitHubCheckRunDetail detail = gitHubRestClient.get()
                    .uri(u -> u.path("/repos/{owner}/{repo}/check-runs/{id}")
                            .build(repo.owner(), repo.repo(), checkRunId))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubCheckRunDetail.class);
            if (detail == null) {
                log.info("[log-diag] check-run {} of {}/{}: GitHub returned null body",
                        checkRunId, repo.owner(), repo.repo());
                return Optional.empty();
            }
            log.info("[log-diag] check-run {} of {}/{}: details_url={} html_url={}",
                    checkRunId, repo.owner(), repo.repo(), detail.detailsUrl(), detail.htmlUrl());
            Long parsed = extractActionsJobId(detail.detailsUrl());
            if (parsed == null) {
                // Fall back to html_url — some Actions-backed check runs
                // populate the job ID there instead of details_url.
                parsed = extractActionsJobId(detail.htmlUrl());
                if (parsed != null) {
                    log.info("[log-diag] check-run {}: job_id={} extracted from html_url (details_url did not match)",
                            checkRunId, parsed);
                }
            }
            else {
                log.info("[log-diag] check-run {}: job_id={} extracted from details_url",
                        checkRunId, parsed);
            }
            if (parsed == null) {
                // External CI — details_url + html_url both point at a
                // non-Actions UI. Nothing we can fetch.
                log.info("[log-diag] check-run {}: neither URL matches ACTIONS_JOB_URL pattern — treating as external CI",
                        checkRunId);
                return Optional.empty();
            }
            jobId = parsed;
        }
        catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                // Check run vanished (rerun rotation?) — nothing to fetch.
                log.info("[log-diag] check-run {} of {}/{}: 404 on check-run lookup — gone (rerun rotation?)",
                        checkRunId, repo.owner(), repo.repo());
                return Optional.empty();
            }
            if (status == 403) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(403),
                        "GitHub denied access to this check run (403). Your PAT may be missing "
                                + "`repo` (classic) or `Checks: Read` (fine-grained) scope.",
                        e);
            }
            throw toReadableException(e);
        }

        // Step 2: fetch the actual log text for the resolved job_id.
        // /repos/{owner}/{repo}/actions/jobs/{job_id}/logs replies with
        // a 302 → presigned blob URL whose body is the raw text log;
        // Spring's RestClient follows redirects by default so one GET
        // retrieves the final text.
        try {
            String body = gitHubRestClient.get()
                    .uri(u -> u.path("/repos/{owner}/{repo}/actions/jobs/{id}/logs")
                            .build(repo.owner(), repo.repo(), jobId))
                    .header("Authorization", authorization(pat))
                    .header("Accept", "text/plain, */*")
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isEmpty()) {
                log.info("[log-diag] check-run {} (job_id={}): /actions/jobs/{}/logs returned {} body",
                        checkRunId, jobId, jobId, body == null ? "null" : "empty");
                return Optional.empty();
            }
            log.info("[log-diag] check-run {} (job_id={}): log fetched, {} bytes",
                    checkRunId, jobId, body.length());
            return Optional.of(body);
        }
        catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404 || status == 410) {
                // Job log expired (>90 days) or job already cleaned up —
                // silent fallback so the UI can show the empty hint.
                log.info("[log-diag] check-run {} (job_id={}): {} on /actions/jobs/{}/logs — expired or cleaned up",
                        checkRunId, jobId, status, jobId);
                return Optional.empty();
            }
            if (status == 403) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(403),
                        "GitHub denied the log fetch (403). Your PAT likely needs the `workflow` scope "
                                + "(classic PAT) or `Actions: Read` permission (fine-grained PAT).",
                        e);
            }
            throw toReadableException(e);
        }
    }

    private static Long extractActionsJobId(String url)
    {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Matcher m = ACTIONS_JOB_URL.matcher(url);
        if (!m.find()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(1));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<UserOrg> fetchUserOrgs(String pat)
    {
        try {
            List<GitHubUserOrgItem> items = gitHubRestClient.get()
                    .uri(u -> u.path("/user/orgs")
                            .queryParam("per_page", 100)
                            .build())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (items == null) {
                return ImmutableList.of();
            }
            return items.stream()
                    // /user/orgs doesn't ship html_url — synthesise from login.
                    .map(o -> new UserOrg(
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
    public List<UserRepo> searchRepositories(String pat, String query)
    {
        try {
            GitHubRepoSearchResponse response = gitHubRestClient.get()
                    .uri(u -> u.path("/search/repositories")
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
                    .map(GitHubClient::toUserRepoFromSearch)
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public Optional<UserRepo> fetchRepository(String pat, String owner, String repo)
    {
        try {
            GitHubUserRepoItem item = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(GitHubUserRepoItem.class);
            return Optional.ofNullable(item).map(GitHubClient::toUserRepoFromSearch);
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw toReadableException(e);
        }
    }

    private static UserRepo toUserRepoFromSearch(GitHubUserRepoItem item)
    {
        String owner = Optional.ofNullable(item.owner()).map(GitHubUserRepoItem.Owner::login).orElse("");
        return new UserRepo(
                owner,
                item.name(),
                item.fullName(),
                item.description(),
                item.language(),
                item.stargazersCount());
    }

    @Override
    public List<GitHubUserMatch> searchUsers(String pat, String query)
    {
        // `in:login` scopes the match to the login text rather than name /
        // bio / email; `type:user` filters out organisations so the team
        // roster doesn't accidentally pull in an org account. per_page is
        // small because the dropdown only renders ~10 rows.
        String q = query.trim() + " in:login type:user";
        try {
            GitHubUserSearchResponse response = gitHubRestClient.get()
                    .uri(u -> u.path("/search/users")
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
    public List<RecentEvent> fetchUserEvents(String pat, String login, int limit)
    {
        return fetchEventList("/users/{login}/events", pat, login, limit);
    }

    @Override
    public List<RecentEvent> fetchReceivedEvents(String pat, String login, int limit)
    {
        return fetchEventList("/users/{login}/received_events", pat, login, limit);
    }

    private List<RecentEvent> fetchEventList(String pathTemplate, String pat, String login, int limit)
    {
        try {
            List<GitHubEventItem> items = gitHubRestClient.get()
                    .uri(u -> u.path(pathTemplate)
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PullRequest toPullRequest(GitHubSearchResponse.Item item, PullRequest.Origin origin)
    {
        List<GitHubSearchResponse.Label> rawLabels = Optional.ofNullable(item.labels())
                .orElse(ImmutableList.of());
        List<String> labels = rawLabels.stream()
                .map(GitHubSearchResponse.Label::name)
                .collect(toImmutableList());
        Map<String, String> labelColors = rawLabels.stream()
                .filter(l -> l.name() != null && l.color() != null)
                .collect(toImmutableMap(GitHubSearchResponse.Label::name, GitHubSearchResponse.Label::color, (a, b) -> a));
        String author = Optional.ofNullable(item.user()).map(GitHubSearchResponse.User::login).orElse(null);
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
                ImmutableList.of(),
                null,
                0,
                0,
                0,
                null,
                // V26 kanban fields. Search results don't expose merged_at,
                // so it stays null here and is filled by the detail sync.
                item.state(),
                item.closedAt(),
                null,
                null,
                null,
                null,
                null,
                // V37 snooze fields — local-state only.
                null,
                null,
                // V42: search responses don't expose head.ref; the next
                // list-page sync will fill it in.
                null);
    }

    static String extractRepo(String repositoryUrl)
    {
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
