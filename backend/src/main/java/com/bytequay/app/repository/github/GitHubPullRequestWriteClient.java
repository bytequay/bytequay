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

import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Reactions;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestWriteClient
{
    private final RestClient gitHubRestClient;

    public GitHubPullRequestWriteClient(RestClient gitHubRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
    }

    public PrTimelineEvent createIssueComment(String pat, PullRequestRef pr, String body)
    {
        try {
            GitHubIssueComment posted = gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/issues/{number}/comments", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("body", body))
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
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", pr.owner(), pr.repo(), pr.number())
                    .header("Authorization", authorization(pat))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("event", "APPROVE"))
                    .retrieve()
                    .toBodilessEntity();
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
                null,
                comment.createdAt(),
                comment.body(),
                null,
                null,
                null,
                null,
                comment.authorAssociation(),
                toReactions(comment.reactions()));
    }

    private static Reactions toReactions(GitHubIssueComment.Reactions reactions)
    {
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
}
