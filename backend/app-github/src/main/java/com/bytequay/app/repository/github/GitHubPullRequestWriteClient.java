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
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestWriteClient
{
    private static final String ISSUE_TYPES_API_VERSION = "2026-03-10";

    private final RestClient gitHubRestClient;
    private final RestClient graphQlRestClient;

    public GitHubPullRequestWriteClient(RestClient gitHubRestClient, @Qualifier("gitHubGraphQLRestClient") RestClient graphQlRestClient)
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

    public boolean togglePullRequestReaction(String pat, PullRequestRef pr, String content, Boolean viewerHasReacted)
    {
        return setReaction(pat, "/repos/{owner}/{repo}/issues/{number}/reactions", content, viewerHasReacted, pr.owner(), pr.repo(), pr.number());
    }

    public boolean toggleIssueCommentReaction(String pat, PullRequestRef pr, long commentId, String content, Boolean viewerHasReacted)
    {
        return setReaction(pat, "/repos/{owner}/{repo}/issues/comments/{commentId}/reactions", content, viewerHasReacted, pr.owner(), pr.repo(), commentId);
    }

    public boolean toggleReviewCommentReaction(String pat, PullRequestRef pr, long commentId, String content, Boolean viewerHasReacted)
    {
        return setReaction(pat, "/repos/{owner}/{repo}/pulls/comments/{commentId}/reactions", content, viewerHasReacted, pr.owner(), pr.repo(), commentId);
    }

    private boolean setReaction(String pat, String uri, String content, Boolean viewerHasReacted, Object... uriVariables)
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

    public IssueMetadata updateIssueMetadata(String pat, PullRequestRef issue, List<String> assignees, List<String> labels)
    {
        checkArgument(assignees != null || labels != null, "assignees or labels must be provided");
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
                        .map(GitHubUser::login)
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
        // owner/slug names a team; GitHub requires the reviewers field even when removing only a team.
        int slash = login.indexOf('/');
        Map<String, Object> request = ImmutableMap.of(
                "reviewers", slash < 0 ? ImmutableList.of(login) : ImmutableList.of(),
                "team_reviewers", slash < 0 ? ImmutableList.of() : ImmutableList.of(login.substring(slash + 1)));
        translating(() -> send(gitHubRestClient, requested ? HttpMethod.POST : HttpMethod.DELETE, pat, request,
                "/repos/{owner}/{repo}/pulls/{number}/requested_reviewers",
                        pullRequest.owner(), pullRequest.repo(), pullRequest.number())
                .toBodilessEntity());
    }

    public PrReviewState submitReview(String pat, PullRequestRef pr, String event, String body, List<InlineReviewComment> comments, String commitId)
    {
        return translating(() -> {
            Map<String, Object> request = reviewRequest(comments == null ? ImmutableList.of() : comments, commitId);
            request.put("event", event);
            if (body != null && !body.isBlank()) {
                request.put("body", body);
            }
            return requireBody(send(gitHubRestClient, HttpMethod.POST, pat, request,
                    "/repos/{owner}/{repo}/pulls/{number}/reviews", pr.owner(), pr.repo(), pr.number())
                    .body(GitHubReview.class), "Empty response from GitHub when submitting review").toReviewState();
        });
    }

    /** Replies belong to existing threads, so attach them to one pending review before publishing it. */
    public PrReviewState submitReviewWithReplies(String pat, PullRequestRef pr, String event, String body, List<InlineReviewComment> comments, Map<Long, String> replyThreads,
            String commitId)
    {
        return translating(() -> {
            Map<String, Object> request = reviewRequest(comments.stream().filter(comment -> comment.replyToCommentId().isEmpty()).toList(), commitId);
            PendingReview pending = requireBody(send(gitHubRestClient, HttpMethod.POST, pat, request,
                    "/repos/{owner}/{repo}/pulls/{number}/reviews", pr.owner(), pr.repo(), pr.number())
                    .body(PendingReview.class), "Empty response from GitHub when preparing review");
            if (pending.id() == null || pending.nodeId() == null || !"PENDING".equals(pending.state())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not identify the pending review; check the pull request on GitHub before retrying");
            }
            try {
                for (InlineReviewComment comment : comments) {
                    if (comment.replyToCommentId().isEmpty()) {
                        continue;
                    }
                    String mutation = """
                            mutation($review:ID!,$thread:ID!,$body:String!){
                              addPullRequestReviewThreadReply(input:{pullRequestReviewId:$review,pullRequestReviewThreadId:$thread,body:$body}){comment{id}}
                            }
                            """;
                    JsonNode data = graphqlData(pat, mutation, ImmutableMap.of(
                            "review", pending.nodeId(), "thread", replyThreads.get(comment.replyToCommentId().getAsLong()), "body", comment.body()),
                            HttpStatus.BAD_GATEWAY, "GitHub did not confirm the pending reply");
                    if (!data.path("addPullRequestReviewThreadReply").path("comment").path("id").isTextual()) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub did not confirm the pending reply");
                    }
                }
                return requireBody(send(gitHubRestClient, HttpMethod.POST, pat, ImmutableMap.of("event", event, "body", body),
                        "/repos/{owner}/{repo}/pulls/{number}/reviews/{reviewId}/events", pr.owner(), pr.repo(), pr.number(), pending.id())
                        .body(GitHubReview.class), "Empty response from GitHub when submitting review").toReviewState();
            }
            catch (RuntimeException failure) {
                // An uncertain submit may already be public; never retry or delete it automatically.
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Review submission was not confirmed. Check the pending or submitted review on GitHub before retrying.", failure);
            }
        });
    }

    private record PendingReview(Long id, @JsonProperty("node_id") String nodeId, String state) {}

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

    private static Map<String, Object> reviewRequest(List<InlineReviewComment> comments, String commitId)
    {
        checkText(commitId, "review commit");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commit_id", commitId);
        if (!comments.isEmpty()) {
            request.put("comments", comments.stream().map(GitHubPullRequestWriteClient::toReviewCommentRequest).toList());
        }
        return request;
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
        graphqlData(pat, mutation, ImmutableMap.of("threadId", threadId), HttpStatus.BAD_GATEWAY, "GitHub review thread update failed");
    }

    /** One mutation only; automated delivery also verifies its authorized PR revision before and after this call. */
    public void markReadyForReview(String pat, String nodeId)
    {
        requirePat(pat);
        checkText(nodeId, "nodeId", 255);
        String mutation = "mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id}){pullRequest{id isDraft}}}";
        JsonNode result = graphqlData(pat, mutation, ImmutableMap.of("id", nodeId), HttpStatus.BAD_GATEWAY,
                "Ready-for-review response is uncertain; the mutation will not be retried").path("markPullRequestReadyForReview").path("pullRequest");
        if (!nodeId.equals(result.path("id").asText()) || !result.path("isDraft").isBoolean() || result.path("isDraft").booleanValue()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ready-for-review result could not be verified; the mutation will not be retried");
        }
    }

    /** Creates a pull request using the same authenticated write client as existing PR actions. */
    public CreatedPullRequest createPullRequest(String pat, RepoRef baseRepository, String head, String base, String title, String body, boolean draft)
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

    public record IssueMetadata(List<String> assignees, List<String> labels, Instant updatedAt)
    {
        public IssueMetadata
        {
            assignees = ImmutableList.copyOf(requireNonNull(assignees, "assignees is null"));
            labels = ImmutableList.copyOf(requireNonNull(labels, "labels is null"));
        }
    }

    private record UpdatedIssue(
            JsonNode type,
            List<UpdatedLabel> labels,
            List<GitHubUser> assignees,
            @JsonProperty("updated_at") Instant updatedAt) {}

    private record UpdatedLabel(String name) {}

    private record GitHubReaction(long id) {}

    public boolean hasMergeQueue(String pat, RepoRef repo, String branch)
    {
        String query = "query($owner: String!, $name: String!, $branch: String!) {" + " repository(owner: $owner, name: $name) { mergeQueue(branch: $branch) { id } } }";
        JsonNode repository = graphqlData(pat, query, ImmutableMap.of("owner", repo.owner(), "name", repo.repo(), "branch", branch),
                HttpStatus.BAD_GATEWAY, "GitHub merge queue lookup failed").path("repository");
        if (!repository.isObject() || (repository.hasNonNull("mergeQueue") && !repository.path("mergeQueue").isObject())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub merge queue lookup returned incomplete GraphQL data");
        }
        return repository.hasNonNull("mergeQueue");
    }

    public MergeResult mergePullRequest(String pat, PullRequestRef pr, MergePullRequestCommand command)
    {
        Map<String, Object> body = new HashMap<>();
        body.put("merge_method", command.mergeMethod());
        command.commitTitle().ifPresent(value -> body.put("commit_title", value));
        command.commitMessage().ifPresent(value -> body.put("commit_message", value));
        command.sha().ifPresent(value -> body.put("sha", value));
        return translating(() -> {
            MergeResponse response = send(gitHubRestClient, HttpMethod.PUT, pat, body, "/repos/{owner}/{repo}/pulls/{number}/merge", pr.owner(), pr.repo(), pr.number())
                    .body(MergeResponse.class);
            return response == null ? null : response.toDomain();
        });
    }

    public Optional<String> pullRequestNodeId(String pat, PullRequestRef pr)
    {
        String query = "query($owner: String!, $name: String!, $number: Int!) {" + " repository(owner: $owner, name: $name) { pullRequest(number: $number) { id } } }";
        JsonNode pullRequest = graphqlData(pat, query, ImmutableMap.of("owner", pr.owner(), "name", pr.repo(), "number", pr.number()),
                HttpStatus.BAD_GATEWAY, "GitHub pullRequestNodeId failed").path("repository").path("pullRequest");
        if (!pullRequest.isObject() || (pullRequest.hasNonNull("id") && !pullRequest.path("id").isTextual())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub pullRequestNodeId returned incomplete GraphQL data");
        }
        return Optional.ofNullable(pullRequest.path("id").textValue());
    }

    public MergeResult enqueuePullRequest(String pat, String pullRequestNodeId, String headSha)
    {
        String mutation = "mutation($id: ID!, $head: GitObjectID!) { enqueuePullRequest(input: { pullRequestId: $id, expectedHeadOid: $head }) {"
                + " mergeQueueEntry { position state } } }";
        JsonNode entry = graphqlData(pat, mutation, ImmutableMap.of("id", pullRequestNodeId, "head", headSha),
                HttpStatus.UNPROCESSABLE_ENTITY, "GitHub enqueuePullRequest failed").path("enqueuePullRequest").path("mergeQueueEntry");
        if (!entry.isObject()
                || (entry.hasNonNull("position") && (!entry.path("position").isIntegralNumber() || !entry.path("position").canConvertToInt()))
                || (entry.hasNonNull("state") && !entry.path("state").isTextual())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub enqueuePullRequest returned no queue entry");
        }
        return MergeResult.enqueued(queueMessage(entry));
    }

    public void dequeuePullRequest(String pat, String pullRequestNodeId)
    {
        String mutation = "mutation($id: ID!) { dequeuePullRequest(input: { id: $id }) { clientMutationId } }";
        JsonNode result = graphqlData(pat, mutation, ImmutableMap.of("id", pullRequestNodeId),
                HttpStatus.UNPROCESSABLE_ENTITY, "GitHub dequeuePullRequest failed").path("dequeuePullRequest");
        if (!result.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub dequeuePullRequest returned incomplete GraphQL data");
        }
    }

    private JsonNode graphqlData(String pat, String query, Map<String, Object> variables, HttpStatus errorStatus, String failureMessage)
    {
        JsonNode response = requireBody(translating(() -> graphql(graphQlRestClient, pat, query, variables).body(JsonNode.class)), failureMessage);
        if (!response.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, failureMessage);
        }
        JsonNode errors = response.path("errors");
        if (!errors.isMissingNode() && (!errors.isArray() || !errors.isEmpty())) {
            JsonNode error = errors.path(0);
            String type = error.path("type").isTextual() ? error.path("type").textValue() + ": " : "";
            throw new ResponseStatusException(errorStatus, failureMessage + ": " + type + error.path("message").asText("Invalid GraphQL errors"));
        }
        return response.path("data");
    }

    private static String queueMessage(JsonNode entry)
    {
        String state = Optional.ofNullable(entry.path("state").textValue()).map(value -> value.toLowerCase(Locale.ROOT).replace('_', ' ')).orElse(null);
        if (entry.hasNonNull("position") && state != null) {
            return "Added to merge queue (position " + entry.path("position").asText() + ", " + state + ")";
        }
        return state != null ? "Added to merge queue (" + state + ")" : "Added to merge queue";
    }

    record MergeResponse(String sha, boolean merged, String message)
    {
        MergeResult toDomain()
        {
            return new MergeResult(sha, merged, message);
        }
    }
}
