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

import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.bytequay.app.utils.StringInputUtil.requireText;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestWriteClient
{
    private static final String ISSUE_TYPES_API_VERSION = "2026-03-10";

    private final RestClient gitHubRestClient;
    private final RestClient graphQlRestClient;
    private final GitHubPaginator paginator;

    public GitHubPullRequestWriteClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient") RestClient graphQlRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphQlRestClient = requireNonNull(graphQlRestClient, "graphQlRestClient is null");
        this.paginator = new GitHubPaginator(gitHubRestClient);
    }

    public PrTimelineEvent createIssueComment(String pat, PullRequestRef pr, String body)
    {
        try {
            GitHubIssueComment posted = gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/issues/{number}/comments", pr.owner(), pr.repo(), pr.number())
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
            return GitHubPullRequestReadClient.toCommentedTimelineEvent(posted);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public void togglePullRequestReaction(String pat, PullRequestRef pr, String content, String viewerLogin)
    {
        toggleReaction(
                pat,
                "/repos/{owner}/{repo}/issues/{number}/reactions",
                content,
                viewerLogin,
                pr.owner(),
                pr.repo(),
                pr.number());
    }

    public void toggleIssueCommentReaction(String pat, PullRequestRef pr, long commentId, String content, String viewerLogin)
    {
        toggleReaction(
                pat,
                "/repos/{owner}/{repo}/issues/comments/{commentId}/reactions",
                content,
                viewerLogin,
                pr.owner(),
                pr.repo(),
                commentId);
    }

    public void toggleReviewCommentReaction(String pat, PullRequestRef pr, long commentId, String content, String viewerLogin)
    {
        toggleReaction(
                pat,
                "/repos/{owner}/{repo}/pulls/comments/{commentId}/reactions",
                content,
                viewerLogin,
                pr.owner(),
                pr.repo(),
                commentId);
    }

    private void toggleReaction(String pat, String uri, String content, String viewerLogin, Object... uriVariables)
    {
        Optional<GitHubReaction> existing = paginator.paginate(
                        pat,
                        uri,
                        new ParameterizedTypeReference<List<GitHubReaction>>() {},
                        uriVariables)
                .stream()
                .filter(reaction -> content.equals(reaction.content()))
                .filter(reaction -> reaction.user() != null && viewerLogin.equalsIgnoreCase(reaction.user().login()))
                .findFirst();
        existing.ifPresentOrElse(
                reaction -> deleteReaction(pat, uri, reaction.id(), uriVariables),
                () -> addReaction(pat, uri, content, uriVariables));
    }

    private void addReaction(String pat, String uri, String content, Object... uriVariables)
    {
        try {
            gitHubRestClient.post()
                    .uri(uri, uriVariables)
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ImmutableMap.of("content", content))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private void deleteReaction(String pat, String uri, long reactionId, Object... uriVariables)
    {
        try {
            gitHubRestClient.delete()
                    .uri(uri + "/" + reactionId, uriVariables)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public void closePullRequest(String pat, PullRequestRef pr)
    {
        try {
            gitHubRestClient.patch()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ImmutableMap.of("state", "closed"))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public void closeIssue(String pat, PullRequestRef issue)
    {
        updateIssue(pat, issue, ImmutableMap.of("state", "closed"));
    }

    public String updateIssueType(String pat, PullRequestRef issue, String type)
    {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", type);
        return issueTypeName(updateIssue(pat, issue, request).type());
    }

    public IssueMetadata updateIssueMetadata(
            String pat,
            PullRequestRef issue,
            List<String> assignees,
            List<String> labels)
    {
        if (assignees == null && labels == null) {
            throw new IllegalArgumentException("assignees or labels must be provided");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        if (assignees != null) {
            request.put("assignees", ImmutableList.copyOf(assignees));
        }
        if (labels != null) {
            request.put("labels", ImmutableList.copyOf(labels));
        }
        UpdatedIssue updated = updateIssue(pat, issue, request);
        return new IssueMetadata(
                Optional.ofNullable(updated.assignees()).orElse(ImmutableList.of()).stream()
                        .map(UpdatedUser::login)
                        .filter(login -> login != null && !login.isBlank())
                        .collect(ImmutableList.toImmutableList()),
                Optional.ofNullable(updated.labels()).orElse(ImmutableList.of()).stream()
                        .map(UpdatedLabel::name)
                        .filter(name -> name != null && !name.isBlank())
                        .collect(ImmutableList.toImmutableList()),
                updated.updatedAt());
    }

    private UpdatedIssue updateIssue(String pat, PullRequestRef issue, Map<String, Object> request)
    {
        try {
            UpdatedIssue updated = gitHubRestClient.patch()
                    .uri("/repos/{owner}/{repo}/issues/{number}", issue.owner(), issue.repo(), issue.number())
                    .header("Authorization", authorization(pat))
                    .headers(headers -> headers.set("X-GitHub-Api-Version", ISSUE_TYPES_API_VERSION))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(UpdatedIssue.class);
            if (updated == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "Empty response from GitHub when updating issue");
            }
            return updated;
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public void updateReviewerRequest(String pat, PullRequestRef pullRequest, String login, boolean requested)
    {
        requireText(login, "reviewer login");
        try {
            gitHubRestClient.method(requested ? HttpMethod.POST : HttpMethod.DELETE)
                    .uri("/repos/{owner}/{repo}/pulls/{number}/requested_reviewers",
                            pullRequest.owner(), pullRequest.repo(), pullRequest.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ImmutableMap.of("reviewers", ImmutableList.of(login)))
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static String issueTypeName(JsonNode type)
    {
        if (type == null || type.isNull()) {
            return null;
        }
        return type.isTextual() ? type.asText() : type.path("name").textValue();
    }

    public PrReviewState approve(String pat, PullRequestRef pr)
    {
        return submitReview(pat, pr, "APPROVE", "");
    }

    public PrReviewState submitReview(String pat, PullRequestRef pr, String event, String body)
    {
        Map<String, String> request = new LinkedHashMap<>();
        request.put("event", event);
        if (body != null && !body.isBlank()) {
            request.put("body", body);
        }
        return postReview(pat, pr, request);
    }

    public PrReviewState submitReview(
            String pat,
            PullRequestRef pr,
            String event,
            String body,
            List<InlineReviewComment> comments)
    {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("event", event);
            if (body != null && !body.isBlank()) {
                request.put("body", body);
            }
            if (comments != null && !comments.isEmpty()) {
                Optional.ofNullable(comments.getFirst().commitId())
                        .filter(commitId -> !commitId.isBlank())
                        .ifPresent(commitId -> request.put("commit_id", commitId));
                request.put("comments", comments.stream()
                        .map(GitHubPullRequestWriteClient::toReviewCommentRequest)
                        .toList());
            }
            return postReview(pat, pr, request);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /** Posts a review and returns what GitHub created, so callers need not re-read it. */
    private PrReviewState postReview(String pat, PullRequestRef pr, Map<String, ?> request)
    {
        try {
            GitHubReview created = gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GitHubReview.class);
            if (created == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Empty response from GitHub when submitting review");
            }
            return created.toReviewState();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public PrTimelineEvent createReviewComment(String pat, PullRequestRef pr, InlineReviewComment comment)
    {
        try {
            Map<String, Object> request = toReviewCommentRequest(comment);
            request.put("commit_id", comment.commitId());
            GitHubReviewComment posted = gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/comments", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GitHubReviewComment.class);
            if (posted == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Empty response from GitHub when posting review comment");
            }
            return GitHubPullRequestReadClient.toReviewThreadEvent(posted);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static Map<String, Object> toReviewCommentRequest(InlineReviewComment comment)
    {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("path", comment.path());
        request.put("body", comment.body());
        request.put("line", comment.line());
        request.put("side", comment.side());
        if (comment.startLine() != null) {
            request.put("start_line", comment.startLine());
            request.put("start_side", comment.startSide());
        }
        return request;
    }

    public void replyToReviewComment(String pat, PullRequestRef pr, long commentId, String body)
    {
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/comments/{commentId}/replies",
                            pr.owner(), pr.repo(), pr.number(), commentId)
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

    public void setReviewThreadResolved(String pat, String threadId, boolean resolved)
    {
        String mutation = resolved
                ? "mutation($threadId:ID!){resolveReviewThread(input:{threadId:$threadId}){thread{id isResolved}}}"
                : "mutation($threadId:ID!){unresolveReviewThread(input:{threadId:$threadId}){thread{id isResolved}}}";
        try {
            ReviewThreadMutationResponse response = graphQlRestClient.post()
                    .header("Authorization", authorization(pat))
                    .body(ImmutableMap.of(
                            "query", mutation,
                            "variables", ImmutableMap.of("threadId", threadId)))
                    .retrieve()
                    .body(ReviewThreadMutationResponse.class);
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "Empty response from GitHub when updating review thread");
            }
            if (response.errors() != null && !response.errors().isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "GitHub review thread update failed: " + response.errors().getFirst().message());
            }
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    /** Creates a pull request using the same authenticated write client as existing PR actions. */
    public CreatedPullRequest createPullRequest(
            String pat,
            RepoRef baseRepository,
            String head,
            String base,
            String title,
            String body)
    {
        requireNonNull(baseRepository, "baseRepository is null");
        requireText(head, "head");
        requireText(base, "base");
        requireText(title, "title");
        requireNonNull(body, "body is null");
        try {
            GitHubCreatedPullRequest created = gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls", baseRepository.owner(), baseRepository.repo())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ImmutableMap.of("title", title, "body", body, "head", head, "base", base))
                    .retrieve()
                    .body(GitHubCreatedPullRequest.class);
            if (created == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "Empty response from GitHub when creating pull request");
            }
            return new CreatedPullRequest(created.id(), created.number(), created.htmlUrl());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IssueMetadata(List<String> assignees, List<String> labels, Instant updatedAt)
    {
        public IssueMetadata
        {
            assignees = ImmutableList.copyOf(requireNonNull(assignees, "assignees is null"));
            labels = ImmutableList.copyOf(requireNonNull(labels, "labels is null"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UpdatedIssue(
            JsonNode type,
            List<UpdatedLabel> labels,
            List<UpdatedUser> assignees,
            @JsonProperty("updated_at") Instant updatedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UpdatedLabel(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UpdatedUser(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubReaction(long id, String content, GitHubReactionUser user) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubReactionUser(String login) {}

    private record ReviewThreadMutationResponse(List<GitHubReviewThreadsResponse.Error> errors) {}
}
