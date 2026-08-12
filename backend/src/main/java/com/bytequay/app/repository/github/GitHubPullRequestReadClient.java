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

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.ListPullRequestsQuery;
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
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.repository.GitHubPullRequestReadRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.app.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestReadClient
        implements GitHubPullRequestReadRepository {
    private final RestClient gitHubRestClient;
    private final RestClient graphqlRestClient;
    private final GitHubPaginator paginator;

    public GitHubPullRequestReadClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient") RestClient gitHubGraphQLRestClient) {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphqlRestClient =
                requireNonNull(gitHubGraphQLRestClient, "gitHubGraphQLRestClient is null");
        this.paginator = new GitHubPaginator(gitHubRestClient);
    }

    private static final int PER_PAGE = 50;
    private static final String REPOS_SEGMENT = "/repos/";
    private static final Set<String> ATTENTION_REASONS =
            ImmutableSet.of("review_requested", "mention");

    @Override
    public List<PullRequest> searchPullRequests(String pat, String query) {
        requirePat(pat);
        try {
            GitHubSearchResponse response =
                    gitHubRestClient
                            .get()
                            .uri(
                                    uri ->
                                            uri.path("/search/issues")
                                                    .queryParam("q", query)
                                                    .queryParam("per_page", PER_PAGE)
                                                    .build())
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubSearchResponse.class);
            if (response == null || response.items() == null) {
                return ImmutableList.of();
            }
            PullRequest.Origin origin =
                    query.contains("review-requested") ? REVIEW_REQUESTED : AUTHORED;
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
            List<PullRequest> items =
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

    @Override
    public ProbeResult probeChangedSinceEtag(String pat, PullRequestRef pr, String etag) {
        try {
            ResponseEntity<Void> resp =
                    gitHubRestClient
                            .get()
                            .uri(
                                    "/repos/{owner}/{repo}/pulls/{number}",
                                    pr.owner(),
                                    pr.repo(),
                                    pr.number())
                            .header("Authorization", authorization(pat))
                            .headers(
                                    h -> {
                                        if (etag != null && !etag.isBlank()) {
                                            h.set("If-None-Match", etag);
                                        }
                                    })
                            // toBodilessEntity() lets us read headers (and the
                            // 304 status) without paying for body deserialization.
                            .retrieve()
                            .onStatus(
                                    s -> s.value() == 304,
                                    (req, res) -> {
                                        /* expected */
                                    })
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
            String headRepo =
                    Optional.ofNullable(r.head())
                            .map(GitHubPullRequestDetailResponse.Head::repo)
                            .map(GitHubPullRequestDetailResponse.Repo::fullName)
                            .orElse(null);
            String baseRef =
                    Optional.ofNullable(r.base())
                            .map(GitHubPullRequestDetailResponse.Base::ref)
                            .orElse(null);
            String baseSha =
                    Optional.ofNullable(r.base())
                            .map(GitHubPullRequestDetailResponse.Base::sha)
                            .orElse(null);
            String baseRepo =
                    Optional.ofNullable(r.base())
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
                    Boolean.TRUE.equals(r.merged()),
                    baseSha,
                    r.mergeCommitSha());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public String fetchPrTitle(String pat, PullRequestRef pr) {
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
            return r == null ? null : r.title();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
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

    // ── Exhaustive paged evidence (archival, learning-owned) ────────────────

    /** Page cap for archival walks: 20 * 100 = 2,000 rows, far past any real PR. */
    private static final int EVIDENCE_MAX_PAGES = 20;

    /**
     * Walks a paginated GitHub list endpoint to exhaustion, reporting whether the walk reached the
     * natural end. A short/empty page means complete; a mid-walk error or hitting {@link
     * #EVIDENCE_MAX_PAGES} with full pages the whole way means the tail was dropped, so the result
     * is marked partial.
     */
    private <R> Paged<R> walkEvidencePages(
            String pat,
            String pathTemplate,
            ParameterizedTypeReference<List<R>> typeRef,
            Object... uriVars) {
        ImmutableList.Builder<R> out = ImmutableList.builder();
        for (int page = 1; page <= EVIDENCE_MAX_PAGES; page++) {
            final int current = page;
            List<R> rows;
            try {
                rows =
                        gitHubRestClient
                                .get()
                                .uri(
                                        u ->
                                                u.path(pathTemplate)
                                                        .queryParam("per_page", 100)
                                                        .queryParam("page", current)
                                                        .build(uriVars))
                                .header("Authorization", authorization(pat))
                                .retrieve()
                                .body(typeRef);
            }
            catch (RestClientResponseException e) {
                return Paged.partial(out.build());
            }
            if (rows == null || rows.isEmpty()) {
                return Paged.complete(out.build());
            }
            out.addAll(rows);
            if (rows.size() < 100) {
                return Paged.complete(out.build());
            }
        }
        // Every fetched page was full up to the cap — more rows exist unread.
        return Paged.partial(out.build());
    }

    @Override
    public Paged<PrReviewState> fetchAllPrReviews(String pat, PullRequestRef pr) {
        Paged<GitHubReview> raw =
                walkEvidencePages(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/reviews",
                        new ParameterizedTypeReference<>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number());
        List<PrReviewState> items =
                raw.items().stream()
                        .filter(r -> r.user() != null)
                        .map(r -> new PrReviewState(r.user().login(), r.state(), r.submittedAt()))
                        .collect(toImmutableList());
        return new Paged<>(items, raw.complete());
    }

    @Override
    public Paged<PullRequestDetail.ChangedFile> fetchAllPrFiles(String pat, PullRequestRef pr) {
        Paged<GitHubPullRequestFile> raw =
                walkEvidencePages(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/files",
                        new ParameterizedTypeReference<>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number());
        List<PullRequestDetail.ChangedFile> items =
                raw.items().stream()
                        .map(
                                f ->
                                        new PullRequestDetail.ChangedFile(
                                                f.filename(),
                                                f.additions(),
                                                f.deletions(),
                                                f.status()))
                        .collect(toImmutableList());
        return new Paged<>(items, raw.complete());
    }

    @Override
    public Paged<PullRequestCommit> fetchAllPrCommits(String pat, PullRequestRef pr) {
        Paged<GitHubPullRequestCommit> raw =
                walkEvidencePages(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/commits",
                        new ParameterizedTypeReference<>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number());
        List<PullRequestCommit> items =
                raw.items().stream()
                        .map(GitHubPullRequestReadClient::toPullRequestCommit)
                        .collect(toImmutableList());
        return new Paged<>(items, raw.complete());
    }

    @Override
    public Paged<PrReviewThreadMessage> fetchAllPrReviewComments(String pat, PullRequestRef pr) {
        Paged<GitHubReviewComment> raw =
                walkEvidencePages(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/comments",
                        new ParameterizedTypeReference<>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number());
        List<PrReviewThreadMessage> items =
                raw.items().stream()
                        .map(GitHubPullRequestReadClient::toReviewThreadMessage)
                        .collect(toImmutableList());
        return new Paged<>(items, raw.complete());
    }

    @Override
    public Paged<PrTimelineEvent> fetchAllPrTimeline(String pat, PullRequestRef pr) {
        Paged<GitHubTimelineEvent> raw =
                walkEvidencePages(
                        pat,
                        "/repos/{owner}/{repo}/issues/{number}/timeline",
                        new ParameterizedTypeReference<>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number());
        List<PrTimelineEvent> items =
                raw.items().stream()
                        .map(GitHubPullRequestReadClient::toTimelineEvent)
                        .collect(toImmutableList());
        return new Paged<>(items, raw.complete());
    }

    private static PullRequestCommit toPullRequestCommit(GitHubPullRequestCommit commit) {
        return new PullRequestCommit(
                commit.sha(),
                Optional.ofNullable(commit.author())
                        .map(GitHubPullRequestCommit.Author::login)
                        .orElse(null),
                Optional.ofNullable(commit.commit())
                        .map(GitHubPullRequestCommit.CommitInfo::author)
                        .map(GitHubPullRequestCommit.GitSignature::name)
                        .orElse(null),
                Optional.ofNullable(commit.commit())
                        .map(GitHubPullRequestCommit.CommitInfo::author)
                        .map(GitHubPullRequestCommit.GitSignature::date)
                        .orElse(null),
                Optional.ofNullable(commit.commit())
                        .map(GitHubPullRequestCommit.CommitInfo::committer)
                        .map(GitHubPullRequestCommit.GitSignature::date)
                        .orElse(null),
                Optional.ofNullable(commit.commit())
                        .map(GitHubPullRequestCommit.CommitInfo::message)
                        .orElse(null));
    }

    @Override
    public String fetchPrDiff(String pat, PullRequestRef pr) {
        try {
            String diff =
                    gitHubRestClient
                            .get()
                            .uri(
                                    "/repos/{owner}/{repo}/pulls/{number}",
                                    pr.owner(),
                                    pr.repo(),
                                    pr.number())
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
    public List<PullRequestDetail.ChangedFile> fetchPrFiles(String pat, PullRequestRef pr) {
        try {
            List<GitHubPullRequestFile> files =
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
                return ImmutableList.of();
            }
            return files.stream()
                    .map(
                            f ->
                                    new PullRequestDetail.ChangedFile(
                                            f.filename(), f.additions(), f.deletions(), f.status()))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            return ImmutableList.of();
        }
    }

    @Override
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
                return ImmutableList.of();
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
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public List<String> fetchFileBlobLines(String pat, RepoRef repo, String path, String sha) {
        try {
            GitHubFileContent content =
                    gitHubRestClient
                            .get()
                            .uri(
                                    builder ->
                                            builder.path("/repos/{owner}/{repo}/contents/{+path}")
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

    @Override
    public Optional<FileBlob> fetchFileBlob(String pat, RepoRef repo, String path, String ref) {
        try {
            GitHubFileContent content =
                    gitHubRestClient
                            .get()
                            .uri(
                                    builder ->
                                            builder.path("/repos/{owner}/{repo}/contents/{+path}")
                                                    .queryParam("ref", ref)
                                                    .build(repo.owner(), repo.repo(), path))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubFileContent.class);
            if (content == null || content.content() == null || content.sha() == null) {
                return Optional.empty();
            }
            String cleaned = content.content().replace("\n", "").replace("\r", "");
            byte[] decoded = Base64.getDecoder().decode(cleaned);
            return Optional.of(
                    new FileBlob(content.sha(), new String(decoded, StandardCharsets.UTF_8)));
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw toReadableException(e);
        }
    }

    record GitHubContentCommitResponse(GitHubContentCommit commit) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record GitHubContentCommit(String sha) {}
    }

    private static List<String> extractLinesFromBytes(byte[] decoded) {
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
    public List<DiffFile> fetchCommitDiffFiles(String pat, PullRequestRef pr, String sha) {
        try {
            GitHubCommitDetail detail =
                    gitHubRestClient
                            .get()
                            .uri(
                                    builder ->
                                            builder.path("/repos/{owner}/{repo}/commits/{sha}")
                                                    .build(pr.owner(), pr.repo(), sha))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubCommitDetail.class);
            if (detail == null || detail.files() == null) {
                return ImmutableList.of();
            }
            return detail.files().stream()
                    .map(
                            filename ->
                                    new DiffFile(
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
    public List<PullRequestCommit> fetchPrCommits(String pat, PullRequestRef pr) {
        try {
            List<GitHubPullRequestCommit> commits =
                    gitHubRestClient
                            .get()
                            .uri(
                                    builder ->
                                            builder.path(
                                                            "/repos/{owner}/{repo}/pulls/{number}/commits")
                                                    .queryParam("per_page", 100)
                                                    .build(pr.owner(), pr.repo(), pr.number()))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
            if (commits == null) {
                return ImmutableList.of();
            }
            return commits.stream()
                    .map(
                            commit ->
                                    new PullRequestCommit(
                                            commit.sha(),
                                            Optional.ofNullable(commit.author())
                                                    .map(GitHubPullRequestCommit.Author::login)
                                                    .orElse(null),
                                            Optional.ofNullable(commit.commit())
                                                    .map(GitHubPullRequestCommit.CommitInfo::author)
                                                    .map(GitHubPullRequestCommit.GitSignature::name)
                                                    .orElse(null),
                                            Optional.ofNullable(commit.commit())
                                                    .map(GitHubPullRequestCommit.CommitInfo::author)
                                                    .map(GitHubPullRequestCommit.GitSignature::date)
                                                    .orElse(null),
                                            Optional.ofNullable(commit.commit())
                                                    .map(
                                                            GitHubPullRequestCommit.CommitInfo
                                                                    ::committer)
                                                    .map(GitHubPullRequestCommit.GitSignature::date)
                                                    .orElse(null),
                                            Optional.ofNullable(commit.commit())
                                                    .map(
                                                            GitHubPullRequestCommit.CommitInfo
                                                                    ::message)
                                                    .orElse(null)))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
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
                .collect(toImmutableList());
    }

    @Override
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

    private static PrTimelineEvent toCommentedTimelineEvent(GitHubIssueComment c) {
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

    private static Reactions toIssueCommentReactions(GitHubIssueComment.Reactions r) {
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
    public List<PrReviewThreadMessage> fetchPrReviewComments(
            String pat, PullRequestRef pr, Instant since) {
        return paginator
                .paginateSince(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/comments",
                        new ParameterizedTypeReference<List<GitHubReviewComment>>() {},
                        since,
                        pr.owner(),
                        pr.repo(),
                        pr.number())
                .stream()
                .map(GitHubPullRequestReadClient::toReviewThreadMessage)
                .collect(toImmutableList());
    }

    static PrReviewThreadMessage toReviewThreadMessage(GitHubReviewComment c) {
        String author =
                Optional.ofNullable(c.user()).map(GitHubReviewComment.User::login).orElse(null);
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

    private static Reactions toReactions(GitHubReviewComment.Reactions r) {
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
    public Optional<PullRequestDetail.LinkedIssue> fetchIssue(
            String pat, RepoRef repo, int number) {
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
                return Optional.empty();
            }
            return Optional.of(
                    new PullRequestDetail.LinkedIssue(
                            item.number(), item.title(), item.state(), item.htmlUrl()));
        }
        catch (RestClientResponseException e) {
            // 404 (issue removed) and 410 (gone) are normal — leave the
            // closing reference unresolved rather than failing the page.
            return Optional.empty();
        }
    }

    private static PrTimelineEvent toTimelineEvent(GitHubTimelineEvent e) {
        String event = Optional.ofNullable(e.event()).orElse("");
        String actor =
                switch (event) {
                    case "committed" ->
                            Optional.ofNullable(e.author())
                                    .map(GitHubTimelineEvent.Author::name)
                                    .orElse(null);
                    case "reviewed" ->
                            Optional.ofNullable(e.user())
                                    .map(GitHubTimelineEvent.User::login)
                                    .orElse(null);
                    default ->
                            Optional.ofNullable(e.actor())
                                    .map(GitHubTimelineEvent.Actor::login)
                                    .orElseGet(
                                            () ->
                                                    Optional.ofNullable(e.user())
                                                            .map(GitHubTimelineEvent.User::login)
                                                            .orElse(null));
                };
        Instant timestamp =
                switch (event) {
                    case "committed" ->
                            Optional.ofNullable(e.author())
                                    .map(GitHubTimelineEvent.Author::date)
                                    .orElse(null);
                    case "reviewed" -> e.submittedAt();
                    default -> e.createdAt();
                };
        String beforeSha =
                Optional.ofNullable(e.beforeCommit())
                        .map(GitHubTimelineEvent.Commit::sha)
                        .orElse(null);
        // For "committed" events the SHA lives at the top level; for
        // head_ref_force_pushed it's nested under after_commit. Fall back
        // to whichever is present so afterSha always carries "the commit
        // SHA most relevant to this event" — used by the UI to render
        // "pushed abc1234".
        String afterSha =
                Optional.ofNullable(e.afterCommit())
                        .map(GitHubTimelineEvent.Commit::sha)
                        .orElse(e.sha());
        String requestedReviewer =
                "review_requested".equals(event)
                        ? Optional.ofNullable(e.requestedReviewer())
                                .map(GitHubTimelineEvent.User::login)
                                .orElse(null)
                        : null;
        Long reviewId = "reviewed".equals(event) ? e.id() : null;
        String labelName =
                Optional.ofNullable(e.label()).map(GitHubTimelineEvent.Label::name).orElse(null);
        String labelColor =
                Optional.ofNullable(e.label()).map(GitHubTimelineEvent.Label::color).orElse(null);
        String milestoneTitle =
                Optional.ofNullable(e.milestone())
                        .map(GitHubTimelineEvent.Milestone::title)
                        .orElse(null);
        String assigneeLogin =
                Optional.ofNullable(e.assignee()).map(GitHubTimelineEvent.User::login).orElse(null);
        GitHubTimelineEvent.SourceIssue crossRef =
                Optional.ofNullable(e.source()).map(GitHubTimelineEvent.Source::issue).orElse(null);
        Integer crossRefNumber = crossRef == null ? null : crossRef.number();
        String crossRefTitle = crossRef == null ? null : crossRef.title();
        String crossRefUrl = crossRef == null ? null : crossRef.htmlUrl();
        boolean crossRefIsPullRequest = crossRef != null && crossRef.pullRequest() != null;
        // Timeline events don't carry reaction tallies in GitHub's
        // payload (reactions are on the issue-comment endpoint, fetched
        // separately). Use Reactions.EMPTY rather than null so the
        // store has a stable shape and the UI never NPEs.
        return new PrTimelineEvent(
                e.id(),
                e.event(),
                actor,
                e.state(),
                timestamp,
                e.body(),
                beforeSha,
                afterSha,
                requestedReviewer,
                reviewId,
                e.authorAssociation(),
                Reactions.EMPTY,
                labelName,
                labelColor,
                milestoneTitle,
                assigneeLogin,
                crossRefNumber,
                crossRefTitle,
                crossRefUrl,
                crossRefIsPullRequest);
    }

    // ── Pulls API ─────────────────────────────────────────────────────────────

    @Override
    public List<PullRequest> listPullRequests(
            String pat, RepoRef repo, ListPullRequestsQuery query) {
        String fullName = repo.fullName();
        try {
            List<GitHubRepoPullRequestItem> items =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/repos/{owner}/{repo}/pulls")
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

    @Override
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
            if (item == null) {
                throw new IllegalStateException(
                        "Empty response from GitHub for " + fullName + "#" + pr.number());
            }
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
    @Override
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
                Optional.ofNullable(item.head())
                        .map(GitHubRepoPullRequestItem.Head::ref)
                        .orElse(null));
    }

    // ── GraphQL: review-thread resolution ────────────────────────────────

    /**
     * GraphQL query for thread metadata, one page (100 threads, 50 comments per thread) at a time —
     * {@link #fetchReviewThreadResolution} follows {@code pageInfo} to cover PRs with more than 100
     * threads.
     */
    private static final String REVIEW_THREAD_QUERY =
            """
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

    /**
     * Hard cap on pages fetched per PR (100 threads/page, so 2000 threads total) — a runaway-loop
     * backstop far above any real PR, in case GitHub ever returned a cursor that never terminates.
     */
    private static final int MAX_REVIEW_THREAD_PAGES = 20;

    @Override
    public List<ReviewThreadMeta> fetchReviewThreadResolution(String pat, PullRequestRef pr) {
        List<ReviewThreadMeta> out = Lists.newArrayList();
        String cursor = null;
        for (int page = 0; page < MAX_REVIEW_THREAD_PAGES; page++) {
            Map<String, Object> variables = new HashMap<>();
            variables.put("owner", pr.owner());
            variables.put("name", pr.repo());
            variables.put("number", pr.number());
            variables.put("after", cursor);
            Map<String, Object> body =
                    ImmutableMap.of("query", REVIEW_THREAD_QUERY, "variables", variables);
            ReviewThreadGqlResponse response;
            try {
                response =
                        graphqlRestClient
                                .post()
                                .header("Authorization", authorization(pat))
                                .body(body)
                                .retrieve()
                                .body(ReviewThreadGqlResponse.class);
            }
            catch (RestClientResponseException e) {
                throw toReadableException(e);
            }
            if (response == null
                    || response.data() == null
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
                if (t.comments() == null
                        || t.comments().nodes() == null
                        || t.comments().nodes().isEmpty()) {
                    continue;
                }
                // The thread root comment is the FIRST in the list (GraphQL
                // returns them in chronological order). Use its databaseId
                // to join back to the REST root.
                Long rootDbId = t.comments().nodes().get(0).databaseId();
                if (rootDbId == null) continue;
                out.add(
                        new ReviewThreadMeta(
                                rootDbId,
                                t.id(),
                                Boolean.TRUE.equals(t.isResolved()),
                                t.resolvedBy() == null ? null : t.resolvedBy().login()));
            }
            if (threads.pageInfo() == null || !threads.pageInfo().hasNextPage()) {
                break;
            }
            cursor = threads.pageInfo().endCursor();
        }
        return ImmutableList.copyOf(out);
    }

    record GitHubNotification(String reason, Subject subject, NotificationRepo repository) {
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

    @Override
    public List<SuggestedReviewer> fetchSuggestedReviewers(String pat, PullRequestRef pr) {
        // GitHub caps suggestedReviewers at 5 today; first(10) is generous
        // headroom in case that ever changes. avatarUrl(size:40) keeps the
        // image small enough to render at 20×20 without retina blur.
        String query =
                "query($owner: String!, $repo: String!, $number: Int!) {"
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
        Map<String, Object> body =
                ImmutableMap.of(
                        "query",
                        query,
                        "variables",
                        ImmutableMap.of(
                                "owner", pr.owner(),
                                "repo", pr.repo(),
                                "number", pr.number()));
        try {
            SuggestedReviewersGqlResponse response =
                    graphqlRestClient
                            .post()
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
            List<GqlSuggestedReviewer> nodes =
                    response.data().repository().pullRequest().suggestedReviewers();
            if (nodes == null) {
                return ImmutableList.of();
            }
            return nodes.stream()
                    .filter(n -> n.reviewer() != null && n.reviewer().login() != null)
                    .map(
                            n ->
                                    new SuggestedReviewer(
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
    public List<PullRequestReview> listReviews(String pat, PullRequestRef pr) {
        Paged<GitHubReview> reviews =
                walkEvidencePages(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/reviews",
                        new ParameterizedTypeReference<>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number());
        if (!reviews.complete()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502),
                    "GitHub review history could not be read exhaustively");
        }
        return reviews.items().stream()
                .map(GitHubPullRequestReadClient::toPullRequestReview)
                .toList();
    }

    static PullRequestReview toPullRequestReview(GitHubReview review) {
        String author = review.user() == null ? null : review.user().login();
        return new PullRequestReview(
                review.id(),
                author,
                review.body(),
                review.state(),
                review.commitId(),
                review.submittedAt(),
                review.htmlUrl());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Override
    public List<GitHubUserMatch> fetchAssignableUsers(String pat, RepoRef repo) {
        return paginator
                .paginate(
                        pat,
                        "/repos/{owner}/{repo}/assignees",
                        new ParameterizedTypeReference<List<GitHubUserSearchResponse.Item>>() {},
                        repo.owner(),
                        repo.repo())
                .stream()
                .filter(user -> user.login() != null)
                .map(user -> new GitHubUserMatch(user.login(), user.avatarUrl(), null))
                .collect(toImmutableList());
    }

    @Override
    public List<IssueDetail.Label> fetchRepoLabels(String pat, RepoRef repo) {
        return paginator
                .paginate(
                        pat,
                        "/repos/{owner}/{repo}/labels",
                        new ParameterizedTypeReference<
                                List<GitHubPullRequestDetailResponse.Label>>() {},
                        repo.owner(),
                        repo.repo())
                .stream()
                .filter(label -> label.name() != null)
                .map(label -> new IssueDetail.Label(label.name(), label.color()))
                .collect(toImmutableList());
    }

    static PullRequest toPullRequest(GitHubSearchResponse.Item item, PullRequest.Origin origin) {
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
                // V26 kanban fields. Search reports a merged PR as
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
                // V37 snooze fields — local-state only.
                null,
                null,
                // V42: search responses don't expose head.ref; the next
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
