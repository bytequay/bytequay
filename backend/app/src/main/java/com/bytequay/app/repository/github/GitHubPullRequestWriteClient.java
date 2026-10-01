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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.graphql;
import static com.bytequay.app.repository.github.GitHubApiSupport.issueTypeName;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.send;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestWriteClient
{
    private static final String ISSUE_TYPES_API_VERSION = "2026-03-10";

    private final RestClient gitHubRestClient;
    private final RestClient graphQlRestClient;

    public GitHubPullRequestWriteClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient") RestClient graphQlRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphQlRestClient = requireNonNull(graphQlRestClient, "graphQlRestClient is null");
    }

    public PrTimelineEvent createIssueComment(String pat, PullRequestRef pr, String body)
    {
        return translating(() -> {
            GitHubIssueComment posted = requireBody(send(gitHubRestClient, HttpMethod.POST, pat, ImmutableMap.of("body", body),
                    "/repos/{owner}/{repo}/issues/{number}/comments", pr.owner(), pr.repo(), pr.number())
                    .body(GitHubIssueComment.class), "Empty response from GitHub when posting issue comment");
            return GitHubPullRequestReadClient.toCommentedTimelineEvent(posted);
        });
    }

    public boolean togglePullRequestReaction(
            String pat,
            PullRequestRef pr,
            String content,
            Boolean viewerHasReacted)
    {
        return setReaction(
                pat,
                "/repos/{owner}/{repo}/issues/{number}/reactions",
                content,
                viewerHasReacted,
                pr.owner(),
                pr.repo(),
                pr.number());
    }

    public boolean toggleIssueCommentReaction(
            String pat,
            PullRequestRef pr,
            long commentId,
            String content,
            Boolean viewerHasReacted)
    {
        return setReaction(
                pat,
                "/repos/{owner}/{repo}/issues/comments/{commentId}/reactions",
                content,
                viewerHasReacted,
                pr.owner(),
                pr.repo(),
                commentId);
    }

    public boolean toggleReviewCommentReaction(
            String pat,
            PullRequestRef pr,
            long commentId,
            String content,
            Boolean viewerHasReacted)
    {
        return setReaction(
                pat,
                "/repos/{owner}/{repo}/pulls/comments/{commentId}/reactions",
                content,
                viewerHasReacted,
                pr.owner(),
                pr.repo(),
                commentId);
    }

    private boolean setReaction(
            String pat,
            String uri,
            String content,
            Boolean viewerHasReacted,
            Object... uriVariables)
    {
        ResponseEntity<GitHubReaction> response = addReaction(pat, uri, content, uriVariables);
        if (response.getStatusCode() != HttpStatus.OK && response.getStatusCode() != HttpStatus.CREATED) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unexpected response from GitHub when setting reaction");
        }
        boolean alreadyReacted = response.getStatusCode() == HttpStatus.OK;
        boolean remove = Boolean.FALSE.equals(viewerHasReacted)
                || (viewerHasReacted == null && alreadyReacted);
        if (remove) {
            GitHubReaction reaction = requireBody(response.getBody(), "Empty response from GitHub when setting reaction");
            deleteReaction(pat, uri, reaction.id(), uriVariables);
            return false;
        }
        return true;
    }

    private ResponseEntity<GitHubReaction> addReaction(String pat, String uri, String content, Object... uriVariables)
    {
        return translating(() -> send(gitHubRestClient, HttpMethod.POST, pat, ImmutableMap.of("content", content), uri, uriVariables)
                .toEntity(GitHubReaction.class));
    }

    private void deleteReaction(String pat, String uri, long reactionId, Object... uriVariables)
    {
        translating(() -> gitHubRestClient.delete()
                .uri(uri + "/" + reactionId, uriVariables)
                .header("Authorization", authorization(pat))
                .retrieve()
                .toBodilessEntity());
    }

    public void closePullRequest(String pat, PullRequestRef pr)
    {
        translating(() -> send(gitHubRestClient, HttpMethod.PATCH, pat, ImmutableMap.of("state", "closed"),
                "/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                .toBodilessEntity());
    }

    public void updatePullRequestTitle(String pat, PullRequestRef pr, String title)
    {
        translating(() -> send(gitHubRestClient, HttpMethod.PATCH, pat, ImmutableMap.of("title", requireNonNull(title, "title is null")),
                "/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                .toBodilessEntity());
    }

    public void updatePullRequestDescription(String pat, PullRequestRef pr, String description)
    {
        translating(() -> send(gitHubRestClient, HttpMethod.PATCH, pat, ImmutableMap.of("body", requireNonNull(description, "description is null")),
                "/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                .toBodilessEntity());
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
        return translating(() -> {
            UpdatedIssue updated = requireBody(gitHubRestClient.patch()
                    .uri("/repos/{owner}/{repo}/issues/{number}", issue.owner(), issue.repo(), issue.number())
                    .header("Authorization", authorization(pat))
                    .headers(headers -> headers.set("X-GitHub-Api-Version", ISSUE_TYPES_API_VERSION))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(UpdatedIssue.class), "Empty response from GitHub when updating issue");
            return updated;
        });
    }

    public void updateReviewerRequest(String pat, PullRequestRef pullRequest, String login, boolean requested)
    {
        checkText(login, "reviewer login");
        translating(() -> send(gitHubRestClient, requested ? HttpMethod.POST : HttpMethod.DELETE, pat, ImmutableMap.of("reviewers", ImmutableList.of(login)),
                "/repos/{owner}/{repo}/pulls/{number}/requested_reviewers",
                        pullRequest.owner(), pullRequest.repo(), pullRequest.number())
                .toBodilessEntity());
    }

    public PrReviewState submitReview(
            String pat,
            PullRequestRef pr,
            String event,
            String body,
            List<InlineReviewComment> comments)
    {
        return translating(() -> {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("event", event);
            if (body != null && !body.isBlank()) {
                request.put("body", body);
            }
            if (comments != null && !comments.isEmpty()) {
                // One review is anchored at one commit; callers guarantee every comment names the same one.
                comments.stream().map(InlineReviewComment::commitId).filter(commitId -> commitId != null && !commitId.isBlank()).findFirst()
                        .ifPresent(commitId -> request.put("commit_id", commitId));
                request.put("comments", comments.stream()
                        .map(GitHubPullRequestWriteClient::toReviewCommentRequest)
                        .toList());
            }
            return postReview(pat, pr, request);
        });
    }

    /** Posts a review and returns what GitHub created, so callers need not re-read it. */
    private PrReviewState postReview(String pat, PullRequestRef pr, Map<String, ?> request)
    {
        return translating(() -> {
            GitHubReview created = requireBody(send(gitHubRestClient, HttpMethod.POST, pat, request,
                    "/repos/{owner}/{repo}/pulls/{number}/reviews", pr.owner(), pr.repo(), pr.number())
                    .body(GitHubReview.class), "Empty response from GitHub when submitting review");
            return created.toReviewState();
        });
    }

    public PrTimelineEvent createReviewComment(String pat, PullRequestRef pr, InlineReviewComment comment)
    {
        return translating(() -> {
            Map<String, Object> request = toReviewCommentRequest(comment);
            request.put("commit_id", comment.commitId());
            GitHubReviewComment posted = requireBody(send(gitHubRestClient, HttpMethod.POST, pat, request,
                    "/repos/{owner}/{repo}/pulls/{number}/comments", pr.owner(), pr.repo(), pr.number())
                    .body(GitHubReviewComment.class), "Empty response from GitHub when posting review comment");
            return GitHubPullRequestReadClient.toReviewThreadEvent(posted);
        });
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
        translating(() -> send(gitHubRestClient, HttpMethod.POST, pat, ImmutableMap.of("body", body),
                "/repos/{owner}/{repo}/pulls/{number}/comments/{commentId}/replies",
                        pr.owner(), pr.repo(), pr.number(), commentId)
                .toBodilessEntity());
    }

    public void updateIssueComment(String pat, PullRequestRef pr, long commentId, String body)
    {
        translating(() -> send(gitHubRestClient, HttpMethod.PATCH, pat, ImmutableMap.of("body", body),
                "/repos/{owner}/{repo}/issues/comments/{commentId}", pr.owner(), pr.repo(), commentId)
                .toBodilessEntity());
    }

    public void updateReviewComment(String pat, PullRequestRef pr, long commentId, String body)
    {
        translating(() -> send(gitHubRestClient, HttpMethod.PATCH, pat, ImmutableMap.of("body", body),
                "/repos/{owner}/{repo}/pulls/comments/{commentId}", pr.owner(), pr.repo(), commentId)
                .toBodilessEntity());
    }

    public void updateReviewBody(String pat, PullRequestRef pr, long reviewId, String body)
    {
        translating(() -> send(gitHubRestClient, HttpMethod.PUT, pat, ImmutableMap.of("body", body),
                "/repos/{owner}/{repo}/pulls/{number}/reviews/{reviewId}", pr.owner(), pr.repo(), pr.number(), reviewId)
                .toBodilessEntity());
    }

    public void setReviewThreadResolved(String pat, String threadId, boolean resolved)
    {
        String mutation = resolved
                ? "mutation($threadId:ID!){resolveReviewThread(input:{threadId:$threadId}){thread{id isResolved}}}"
                : "mutation($threadId:ID!){unresolveReviewThread(input:{threadId:$threadId}){thread{id isResolved}}}";
        ReviewThreadMutationResponse response = requireBody(translating(() -> graphql(graphQlRestClient, pat, mutation, ImmutableMap.of("threadId", threadId))
                .body(ReviewThreadMutationResponse.class)), "Empty response from GitHub when updating review thread");
        if (response.errors() != null && !response.errors().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502),
                    "GitHub review thread update failed: " + response.errors().getFirst().message());
        }
    }

    /** One mutation only; automated delivery also verifies its authorized PR revision before and after this call. */
    public void markReadyForReview(String pat, String nodeId)
    {
        requirePat(pat);
        checkText(nodeId, "nodeId", 255);
        String mutation = "mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id}){pullRequest{id isDraft}}}";
        translating(() -> {
            JsonNode response = graphql(graphQlRestClient, pat, mutation, ImmutableMap.of("id", nodeId))
                    .body(JsonNode.class);
            if (response == null || (response.has("errors") && (!response.path("errors").isArray() || !response.path("errors").isEmpty()))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ready-for-review response is uncertain; the mutation will not be retried");
            }
            JsonNode result = response.path("data").path("markPullRequestReadyForReview").path("pullRequest");
            if (!nodeId.equals(result.path("id").asText()) || !result.path("isDraft").isBoolean() || result.path("isDraft").booleanValue()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ready-for-review result could not be verified; the mutation will not be retried");
            }
            return result;
        });
    }

    /** Creates a pull request using the same authenticated write client as existing PR actions. */
    public CreatedPullRequest createPullRequest(
            String pat,
            RepoRef baseRepository,
            String head,
            String base,
            String title,
            String body,
            boolean draft)
    {
        requireNonNull(baseRepository, "baseRepository is null");
        checkText(head, "head");
        checkText(base, "base");
        checkText(title, "title");
        requireNonNull(body, "body is null");
        return translating(() -> requireBody(send(gitHubRestClient, HttpMethod.POST, pat,
                ImmutableMap.of("title", title, "body", body, "head", head, "base", base, "draft", draft),
                "/repos/{owner}/{repo}/pulls", baseRepository.owner(), baseRepository.repo())
                .body(CreatedPullRequest.class), "Empty response from GitHub when creating pull request"));
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
    private record GitHubReaction(long id) {}

    private record ReviewThreadMutationResponse(List<GitHubReviewThreadsResponse.Error> errors) {}
}
