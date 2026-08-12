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

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.repository.GitHubPullRequestWriteRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestWriteClient
        implements GitHubPullRequestWriteRepository {
    private final RestClient gitHubRestClient;
    private final RestClient graphqlRestClient;

    public GitHubPullRequestWriteClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient") RestClient gitHubGraphQLRestClient) {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphqlRestClient =
                requireNonNull(gitHubGraphQLRestClient, "gitHubGraphQLRestClient is null");
    }

    @Override
    public String commitFileText(
            String pat,
            RepoRef repo,
            String path,
            String branch,
            String blobSha,
            String text,
            String message) {
        try {
            GitHubContentCommitResponse response =
                    gitHubRestClient
                            .put()
                            .uri(
                                    builder ->
                                            builder.path("/repos/{owner}/{repo}/contents/{+path}")
                                                    .build(repo.owner(), repo.repo(), path))
                            .header("Authorization", authorization(pat))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(
                                    ImmutableMap.of(
                                            "message", message,
                                            "content",
                                                    Base64.getEncoder()
                                                            .encodeToString(
                                                                    text.getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)),
                                            "sha", blobSha,
                                            "branch", branch))
                            .retrieve()
                            .body(GitHubContentCommitResponse.class);
            if (response == null || response.commit() == null || response.commit().sha() == null) {
                throw new IllegalStateException(
                        "GitHub accepted the content write but returned no commit");
            }
            return response.commit().sha();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubContentCommitResponse(GitHubContentCommit commit) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record GitHubContentCommit(String sha) {}
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
    public PullRequest createPullRequest(
            String pat, RepoRef repo, CreatePullRequestCommand command) {
        Map<String, Object> body = Maps.newHashMap();
        body.put("title", command.title());
        body.put("head", command.head());
        body.put("base", command.base());
        command.body().ifPresent(v -> body.put("body", v));
        command.draft().ifPresent(v -> body.put("draft", v));
        command.maintainerCanModify().ifPresent(v -> body.put("maintainer_can_modify", v));
        try {
            GitHubRepoPullRequestItem item =
                    gitHubRestClient
                            .post()
                            .uri("/repos/{owner}/{repo}/pulls", repo.owner(), repo.repo())
                            .header("Authorization", authorization(pat))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(GitHubRepoPullRequestItem.class);
            if (item == null) {
                throw new IllegalStateException(
                        "GitHub returned an empty body for POST /repos/"
                                + repo.fullName()
                                + "/pulls");
            }
            return toPullRequest(item, repo.fullName());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /**
     * Single-PR fetch used by the deep-link fallback: when the user clicks a PR link from the
     * home-page activity feed and the PR isn't in the (capped) list response, we look it up
     * directly. Same JSON shape as list items, so {@link #toPullRequest} reuses the same mapping.
     */
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

    @Override
    public PullRequest updatePullRequest(
            String pat, PullRequestRef pr, UpdatePullRequestCommand command) {
        Map<String, Object> body = Maps.newHashMap();
        command.title().ifPresent(v -> body.put("title", v));
        command.body().ifPresent(v -> body.put("body", v));
        command.state().ifPresent(v -> body.put("state", v));
        command.base().ifPresent(v -> body.put("base", v));
        command.maintainerCanModify().ifPresent(v -> body.put("maintainer_can_modify", v));
        try {
            gitHubRestClient
                    .patch()
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
    public PrTimelineEvent createIssueComment(String pat, PullRequestRef pr, String body) {
        try {
            GitHubIssueComment posted =
                    gitHubRestClient
                            .post()
                            .uri(
                                    "/repos/{owner}/{repo}/issues/{number}/comments",
                                    pr.owner(),
                                    pr.repo(),
                                    pr.number())
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
    public PullRequest requestReviewers(
            String pat, PullRequestRef pr, RequestReviewersCommand command) {
        Map<String, Object> payload = Maps.newHashMap();
        payload.put(
                "reviewers", Optional.ofNullable(command.reviewers()).orElse(ImmutableList.of()));
        payload.put(
                "team_reviewers",
                Optional.ofNullable(command.teamReviewers()).orElse(ImmutableList.of()));
        try {
            gitHubRestClient
                    .post()
                    .uri(
                            "/repos/{owner}/{repo}/pulls/{number}/requested_reviewers",
                            pr.owner(),
                            pr.repo(),
                            pr.number())
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
     * Re-run the failed jobs of every completed-but-unsuccessful workflow run on {@code headSha} —
     * GitHub's purpose-built fix for a flaky failure. Returns the number of runs re-triggered (0
     * when nothing on the head failed, so the caller can say "nothing to re-run").
     */
    @Override
    public void removeRequestedReviewers(
            String pat, PullRequestRef pr, RequestReviewersCommand command) {
        Map<String, Object> payload = Maps.newHashMap();
        payload.put(
                "reviewers", Optional.ofNullable(command.reviewers()).orElse(ImmutableList.of()));
        payload.put(
                "team_reviewers",
                Optional.ofNullable(command.teamReviewers()).orElse(ImmutableList.of()));
        try {
            gitHubRestClient
                    .method(HttpMethod.DELETE)
                    .uri(
                            "/repos/{owner}/{repo}/pulls/{number}/requested_reviewers",
                            pr.owner(),
                            pr.repo(),
                            pr.number())
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
    public void setPullRequestAssignee(
            String pat, PullRequestRef pr, String login, boolean selected) {
        try {
            if (selected) {
                gitHubRestClient
                        .post()
                        .uri(
                                "/repos/{owner}/{repo}/issues/{number}/assignees",
                                pr.owner(),
                                pr.repo(),
                                pr.number())
                        .header("Authorization", authorization(pat))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ImmutableMap.of("assignees", ImmutableList.of(login)))
                        .retrieve()
                        .toBodilessEntity();
            }
            else {
                gitHubRestClient
                        .method(HttpMethod.DELETE)
                        .uri(
                                "/repos/{owner}/{repo}/issues/{number}/assignees",
                                pr.owner(),
                                pr.repo(),
                                pr.number())
                        .header("Authorization", authorization(pat))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ImmutableMap.of("assignees", ImmutableList.of(login)))
                        .retrieve()
                        .toBodilessEntity();
            }
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void setPullRequestLabel(String pat, PullRequestRef pr, String label, boolean selected) {
        try {
            if (selected) {
                gitHubRestClient
                        .post()
                        .uri(
                                "/repos/{owner}/{repo}/issues/{number}/labels",
                                pr.owner(),
                                pr.repo(),
                                pr.number())
                        .header("Authorization", authorization(pat))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ImmutableMap.of("labels", ImmutableList.of(label)))
                        .retrieve()
                        .toBodilessEntity();
            }
            else {
                gitHubRestClient
                        .method(HttpMethod.DELETE)
                        .uri(
                                "/repos/{owner}/{repo}/issues/{number}/labels/{label}",
                                pr.owner(),
                                pr.repo(),
                                pr.number(),
                                label)
                        .header("Authorization", authorization(pat))
                        .retrieve()
                        .toBodilessEntity();
            }
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
            String startSide) {
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
            gitHubRestClient
                    .post()
                    .uri(
                            "/repos/{owner}/{repo}/pulls/{number}/comments",
                            pr.owner(),
                            pr.repo(),
                            pr.number())
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
    public PrReviewThreadMessage replyToReviewComment(
            String pat, PullRequestRef pr, long rootCommentId, String body) {
        try {
            GitHubReviewComment created =
                    gitHubRestClient
                            .post()
                            .uri(
                                    "/repos/{owner}/{repo}/pulls/{number}/comments/{commentId}/replies",
                                    pr.owner(),
                                    pr.repo(),
                                    pr.number(),
                                    rootCommentId)
                            .header("Authorization", authorization(pat))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(ImmutableMap.of("body", body))
                            .retrieve()
                            .body(GitHubReviewComment.class);
            if (created == null) {
                throw new IllegalStateException(
                        "GitHub returned no body from reply-to-review-comment");
            }
            return GitHubPullRequestReadClient.toReviewThreadMessage(created);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void editIssueComment(
            String pat, String owner, String repo, long commentId, String body) {
        try {
            gitHubRestClient
                    .method(HttpMethod.PATCH)
                    .uri(
                            "/repos/{owner}/{repo}/issues/comments/{commentId}",
                            owner,
                            repo,
                            commentId)
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
    public void editReviewComment(
            String pat, String owner, String repo, long commentId, String body) {
        try {
            gitHubRestClient
                    .method(HttpMethod.PATCH)
                    .uri("/repos/{owner}/{repo}/pulls/comments/{commentId}", owner, repo, commentId)
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
    public void deleteIssueComment(String pat, String owner, String repo, long commentId) {
        try {
            gitHubRestClient
                    .method(HttpMethod.DELETE)
                    .uri(
                            "/repos/{owner}/{repo}/issues/comments/{commentId}",
                            owner,
                            repo,
                            commentId)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void deleteReviewComment(String pat, String owner, String repo, long commentId) {
        try {
            gitHubRestClient
                    .method(HttpMethod.DELETE)
                    .uri("/repos/{owner}/{repo}/pulls/comments/{commentId}", owner, repo, commentId)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public void addPullRequestReaction(String pat, PullRequestRef ref, String content) {
        try {
            gitHubRestClient
                    .post()
                    .uri(
                            "/repos/{owner}/{repo}/issues/{number}/reactions",
                            ref.owner(),
                            ref.repo(),
                            ref.number())
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
    public void addIssueCommentReaction(
            String pat, String owner, String repo, long commentId, String content) {
        try {
            gitHubRestClient
                    .post()
                    .uri(
                            "/repos/{owner}/{repo}/issues/comments/{commentId}/reactions",
                            owner,
                            repo,
                            commentId)
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
    public void addReviewCommentReaction(
            String pat, String owner, String repo, long commentId, String content) {
        try {
            gitHubRestClient
                    .post()
                    .uri(
                            "/repos/{owner}/{repo}/pulls/comments/{commentId}/reactions",
                            owner,
                            repo,
                            commentId)
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

    @Override
    public void resolveReviewThread(String pat, String threadNodeId) {
        runResolveMutation(pat, threadNodeId, true);
    }

    @Override
    public void unresolveReviewThread(String pat, String threadNodeId) {
        runResolveMutation(pat, threadNodeId, false);
    }

    private void runResolveMutation(String pat, String threadNodeId, boolean resolve) {
        // Both mutations have the same shape — { input: { threadId } }
        // — so we share the call site and pick the mutation name
        // based on the verb.
        String mutation =
                resolve
                        ? "mutation($id: ID!) { resolveReviewThread(input: { threadId: $id }) {"
                              + " thread { id isResolved } } }"
                        : "mutation($id: ID!) { unresolveReviewThread(input: { threadId: $id }) {"
                              + " thread { id isResolved } } }";
        Map<String, Object> body =
                ImmutableMap.of(
                        "query", mutation, "variables", ImmutableMap.of("id", threadNodeId));
        try {
            graphqlRestClient
                    .post()
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
    @Override
    public void setPullRequestDraft(String pat, PullRequestRef pr, boolean draft) {
        // Two-step GraphQL: REST exposes neither toggle, so first fetch
        // the PR's opaque GraphQL node id, then run the appropriate
        // mutation. Roundtrip cost is fine for a rare user-triggered
        // action; we don't pre-cache the node id since it's only used
        // here.
        String pullRequestNodeId;
        try {
            String idQuery =
                    "query($owner: String!, $name: String!, $number: Int!) {"
                            + " repository(owner: $owner, name: $name) {"
                            + "   pullRequest(number: $number) { id }"
                            + " } }";
            Map<String, Object> idBody =
                    ImmutableMap.of(
                            "query",
                            idQuery,
                            "variables",
                            ImmutableMap.of(
                                    "owner", pr.owner(),
                                    "name", pr.repo(),
                                    "number", pr.number()));
            PullRequestIdGqlResponse idResponse =
                    graphqlRestClient
                            .post()
                            .header("Authorization", authorization(pat))
                            .body(idBody)
                            .retrieve()
                            .body(PullRequestIdGqlResponse.class);
            if (idResponse == null
                    || idResponse.data() == null
                    || idResponse.data().repository() == null
                    || idResponse.data().repository().pullRequest() == null
                    || idResponse.data().repository().pullRequest().id() == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "PR " + pr.owner() + "/" + pr.repo() + "#" + pr.number() + " not found");
            }
            pullRequestNodeId = idResponse.data().repository().pullRequest().id();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }

        String mutation =
                draft
                        ? "mutation($id: ID!) { convertPullRequestToDraft(input: { pullRequestId:"
                              + " $id }) { pullRequest { id isDraft } } }"
                        : "mutation($id: ID!) { markPullRequestReadyForReview(input: {"
                              + " pullRequestId: $id }) { pullRequest { id isDraft } } }";
        Map<String, Object> body =
                ImmutableMap.of(
                        "query", mutation, "variables", ImmutableMap.of("id", pullRequestNodeId));
        try {
            graphqlRestClient
                    .post()
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

    private String fetchPullRequestNodeId(String pat, PullRequestRef pr) {
        String idQuery =
                "query($owner: String!, $name: String!, $number: Int!) {"
                        + " repository(owner: $owner, name: $name) {"
                        + "   pullRequest(number: $number) { id }"
                        + " } }";
        Map<String, Object> body =
                ImmutableMap.of(
                        "query",
                        idQuery,
                        "variables",
                        ImmutableMap.of(
                                "owner", pr.owner(),
                                "name", pr.repo(),
                                "number", pr.number()));
        try {
            PullRequestIdGqlResponse response =
                    graphqlRestClient
                            .post()
                            .header("Authorization", authorization(pat))
                            .body(body)
                            .retrieve()
                            .body(PullRequestIdGqlResponse.class);
            if (response == null
                    || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null
                    || response.data().repository().pullRequest().id() == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "PR " + pr.owner() + "/" + pr.repo() + "#" + pr.number() + " not found");
            }
            return response.data().repository().pullRequest().id();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public PullRequestReview createReview(
            String pat, PullRequestRef pr, CreateReviewCommand command) {
        Map<String, Object> body = Maps.newHashMap();
        command.commitId().ifPresent(id -> body.put("commit_id", id));
        command.body().ifPresent(b -> body.put("body", b));
        body.put("event", command.event());
        if (!command.comments().isEmpty()) {
            // Build the comments[] array as plain maps so the JSON shape is
            // exactly what GitHub expects (snake_case + start_line/start_side
            // omitted for single-line comments). Reusing the record's
            // Jackson serialization tied us to camelCase / Optional quirks.
            List<Map<String, Object>> serialized =
                    command.comments().stream()
                            .map(
                                    c -> {
                                        Map<String, Object> m = Maps.newHashMap();
                                        m.put("path", c.path());
                                        c.line().ifPresent(line -> m.put("line", line));
                                        c.position().ifPresent(pos -> m.put("position", pos));
                                        m.put("side", c.side());
                                        m.put("body", c.body());
                                        // GitHub treats start_line == line as single-line,
                                        // but rejects start_line/start_side appearing alone
                                        // — only include the pair when it differs from line.
                                        c.startLine()
                                                .ifPresent(
                                                        sl -> {
                                                            int line = c.line().orElse(sl);
                                                            if (sl != line) {
                                                                m.put("start_line", sl);
                                                                c.startSide()
                                                                        .ifPresent(
                                                                                ss ->
                                                                                        m.put(
                                                                                                "start_side",
                                                                                                ss));
                                                            }
                                                        });
                                        return m;
                                    })
                            .collect(toImmutableList());
            body.put("comments", serialized);
        }
        try {
            GitHubReview created =
                    gitHubRestClient
                            .post()
                            .uri(
                                    "/repos/{owner}/{repo}/pulls/{number}/reviews",
                                    pr.owner(),
                                    pr.repo(),
                                    pr.number())
                            .header("Authorization", authorization(pat))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(GitHubReview.class);
            if (created == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "GitHub create review returned no review");
            }
            return GitHubPullRequestReadClient.toPullRequestReview(created);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }
}
