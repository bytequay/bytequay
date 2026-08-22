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

import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

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
            return toTimelineEvent(posted);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    public void approve(String pat, PullRequestRef pr)
    {
        submitReview(pat, pr, "APPROVE", "");
    }

    public void submitReview(String pat, PullRequestRef pr, String event, String body)
    {
        try {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("event", event);
            if (body != null && !body.isBlank()) {
                request.put("body", body);
            }
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
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

    private static PrTimelineEvent toTimelineEvent(GitHubIssueComment comment)
    {
        return new PrTimelineEvent(
                comment.id(),
                "commented",
                Optional.ofNullable(comment.user()).map(GitHubIssueComment.User::login).orElse(null),
                comment.createdAt(),
                comment.body(),
                Optional.empty());
    }

    private record ReviewThreadMutationResponse(List<GitHubReviewThreadsResponse.Error> errors) {}
}
