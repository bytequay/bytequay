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

import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PullRequestRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubMergeClient
{
    public record MergeQueueProbe(String pullRequestNodeId) {}

    private final RestClient gitHubRestClient;
    private final RestClient graphqlRestClient;

    public GitHubMergeClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient") RestClient gitHubGraphQLRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphqlRestClient = requireNonNull(gitHubGraphQLRestClient, "gitHubGraphQLRestClient is null");
    }

    public MergeResult mergePullRequest(
            String pat, PullRequestRef pr, MergePullRequestCommand command)
    {
        Map<String, Object> body = new HashMap<>();
        body.put("merge_method", command.mergeMethod());
        command.commitTitle().ifPresent(value -> body.put("commit_title", value));
        command.commitMessage().ifPresent(value -> body.put("commit_message", value));
        command.sha().ifPresent(value -> body.put("sha", value));
        try {
            return gitHubRestClient
                    .put()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/merge", pr.owner(), pr.repo(), pr.number())
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

    public Optional<MergeQueueProbe> probeMergeQueue(String pat, PullRequestRef pr)
    {
        ProbeResponse response = fetchProbe(pat, pr, true);
        ProbePullRequest pullRequest = requirePullRequest(response, "probeMergeQueue");
        if (pullRequest.mergeQueue() == null || pullRequest.mergeQueue().id() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(pullRequest.id()).map(MergeQueueProbe::new);
    }

    public Optional<String> pullRequestNodeId(String pat, PullRequestRef pr)
    {
        return Optional.ofNullable(requirePullRequest(fetchProbe(pat, pr, false), "pullRequestNodeId").id());
    }

    private ProbeResponse fetchProbe(String pat, PullRequestRef pr, boolean includeQueue)
    {
        String fields = includeQueue ? "id mergeQueue { id }" : "id";
        String query = "query($owner: String!, $name: String!, $number: Int!) {"
                + " repository(owner: $owner, name: $name) { pullRequest(number: $number) { "
                + fields
                + " } } }";
        Map<String, Object> body = Map.of(
                "query", query,
                "variables", Map.of("owner", pr.owner(), "name", pr.repo(), "number", pr.number()));
        try {
            return graphqlRestClient
                    .post()
                    .header("Authorization", authorization(pat))
                    .body(body)
                    .retrieve()
                    .body(ProbeResponse.class);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static ProbePullRequest requirePullRequest(ProbeResponse response, String operation)
    {
        if (response != null && response.errors() != null && !response.errors().isEmpty()) {
            GraphQlError error = response.errors().getFirst();
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502),
                    "GitHub " + operation + " returned GraphQL error " + error.type() + ": " + error.message());
        }
        if (response == null
                || response.data() == null
                || response.data().repository() == null
                || response.data().repository().pullRequest() == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502), "GitHub " + operation + " returned incomplete GraphQL data");
        }
        return response.data().repository().pullRequest();
    }

    public MergeResult enqueuePullRequest(String pat, String pullRequestNodeId)
    {
        String mutation = "mutation($id: ID!) {"
                + " enqueuePullRequest(input: { pullRequestId: $id }) {"
                + " mergeQueueEntry { position state } } }";
        try {
            EnqueueResponse response = graphqlRestClient
                    .post()
                    .header("Authorization", authorization(pat))
                    .body(Map.of(
                            "query", mutation,
                            "variables", Map.of("id", pullRequestNodeId)))
                    .retrieve()
                    .body(EnqueueResponse.class);
            if (response != null && response.errors() != null && !response.errors().isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(422),
                        "GitHub enqueuePullRequest failed: " + response.errors().getFirst().message());
            }
            QueueEntry entry = response == null
                    || response.data() == null
                    || response.data().enqueuePullRequest() == null
                    ? null
                    : response.data().enqueuePullRequest().mergeQueueEntry();
            if (entry == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502), "GitHub enqueuePullRequest returned no queue entry");
            }
            return MergeResult.enqueued(queueMessage(entry));
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static String queueMessage(QueueEntry entry)
    {
        String state = entry.state() == null
                ? null
                : entry.state().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (entry.position() != null && state != null) {
            return "Added to merge queue (position " + entry.position() + ", " + state + ")";
        }
        return state != null ? "Added to merge queue (" + state + ")" : "Added to merge queue";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbeResponse(ProbeData data, List<GraphQlError> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbeData(ProbeRepository repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbeRepository(ProbePullRequest pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbePullRequest(String id, ProbeQueue mergeQueue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbeQueue(String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GraphQlError(String type, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueResponse(EnqueueData data, List<GraphQlError> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueData(EnqueuePayload enqueuePullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueuePayload(QueueEntry mergeQueueEntry) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueueEntry(Integer position, String state) {}
}
