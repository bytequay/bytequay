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
import com.bytequay.app.repository.GitHubMergeRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubMergeClient
        implements GitHubMergeRepository {
    private final RestClient gitHubRestClient;
    private final RestClient graphqlRestClient;

    public GitHubMergeClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubGraphQLRestClient") RestClient gitHubGraphQLRestClient) {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphqlRestClient =
                requireNonNull(gitHubGraphQLRestClient, "gitHubGraphQLRestClient is null");
    }

    private static final int BRANCH_RULES_PAGE_SIZE = 100;
    private static final int BRANCH_RULES_MAX_PAGES = 10;
    private static final Pattern LINK_HEADER =
            Pattern.compile("\\s*<([^<>]+)>\\s*;\\s*rel=\"(next|prev|first|last)\"\\s*");

    @Override
    public MergeResult mergePullRequest(
            String pat, PullRequestRef pr, MergePullRequestCommand command) {
        Map<String, Object> body = Maps.newHashMap();
        body.put("merge_method", command.mergeMethod());
        command.commitTitle().ifPresent(t -> body.put("commit_title", t));
        command.commitMessage().ifPresent(m -> body.put("commit_message", m));
        command.sha().ifPresent(s -> body.put("sha", s));
        try {
            return gitHubRestClient
                    .put()
                    .uri(
                            "/repos/{owner}/{repo}/pulls/{number}/merge",
                            pr.owner(),
                            pr.repo(),
                            pr.number())
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
    public void deleteBranch(String pat, PullRequestRef pr, String branchName) {
        try {
            gitHubRestClient
                    .delete()
                    .uri(
                            "/repos/{owner}/{repo}/git/refs/heads/{branch}",
                            pr.owner(),
                            pr.repo(),
                            branchName)
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
    public Optional<String> fetchBranchHeadSha(
            String pat, PullRequestRef repository, String branchName) {
        try {
            GitRefResponse response =
                    gitHubRestClient
                            .get()
                            .uri(
                                    "/repos/{owner}/{repo}/git/ref/heads/{branch}",
                                    repository.owner(),
                                    repository.repo(),
                                    branchName)
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitRefResponse.class);
            return Optional.ofNullable(response)
                    .map(GitRefResponse::object)
                    .map(GitRefObject::sha)
                    .filter(sha -> !sha.isBlank());
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitRefResponse(GitRefObject object) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitRefObject(String sha) {}

    // ── GraphQL: merge-queue entry state ─────────────────────────────────

    /**
     * GraphQL query for the PR's current merge-queue entry, if any. REST doesn't expose this per-PR
     * — github.com itself uses this same GraphQL field on its PR detail page. Tiny query so the
     * point-cost is negligible.
     */
    private static final String MERGE_QUEUE_STATE_QUERY =
            """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $number) {
                  baseRefName
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
    public MergeQueueInfo fetchMergeQueueInfo(String pat, PullRequestRef pr) {
        Map<String, Object> body =
                ImmutableMap.of(
                        "query",
                        MERGE_QUEUE_STATE_QUERY,
                        "variables",
                        ImmutableMap.of(
                                "owner", pr.owner(),
                                "name", pr.repo(),
                                "number", pr.number()));
        try {
            MergeQueueGqlResponse response =
                    graphqlRestClient
                            .post()
                            .header("Authorization", authorization(pat))
                            .body(body)
                            .retrieve()
                            .body(MergeQueueGqlResponse.class);
            requireMergeQueueResponse(
                    response == null ? null : response.errors(), "fetchMergeQueueInfo");
            if (response == null) {
                throw incompleteMergeQueueResponse("fetchMergeQueueInfo");
            }
            JsonNode pullRequest = requireMergeQueuePullRequest(response.data());
            String baseRefName = requireNonBlankText(pullRequest, "baseRefName");
            MergeQueueInfo info = mergeQueueInfo(pullRequest);
            if (info.queueConfigured()) {
                return info;
            }
            return new MergeQueueInfo(hasMergeQueueRule(pat, pr, baseRefName), null);
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueGqlResponse(JsonNode data, List<MergeQueueGqlError> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueGqlError(String type, String message) {}

    private static JsonNode requireMergeQueuePullRequest(JsonNode data) {
        JsonNode repository = data == null ? null : data.get("repository");
        JsonNode pullRequest = repository == null ? null : repository.get("pullRequest");
        if (data == null
                || !data.isObject()
                || repository == null
                || !repository.isObject()
                || pullRequest == null
                || !pullRequest.isObject()
                || !pullRequest.has("mergeQueue")
                || !pullRequest.has("mergeQueueEntry")) {
            throw incompleteMergeQueueResponse("fetchMergeQueueInfo");
        }
        return pullRequest;
    }

    private static MergeQueueInfo mergeQueueInfo(JsonNode pullRequest) {
        JsonNode queue = pullRequest.get("mergeQueue");
        JsonNode entry = pullRequest.get("mergeQueueEntry");
        boolean queueConfigured = !queue.isNull();
        if (queueConfigured) {
            requireNonBlankText(queue, "id");
        }
        String entryState = null;
        if (!entry.isNull()) {
            entryState = requireNonBlankText(entry, "state");
            queueConfigured = true;
        }
        return new MergeQueueInfo(queueConfigured, entryState);
    }

    private boolean hasMergeQueueRule(String pat, PullRequestRef pr, String baseRefName) {
        try {
            boolean mergeQueueConfigured = false;
            int page = 1;
            while (true) {
                int currentPage = page;
                ResponseEntity<JsonNode> response =
                        gitHubRestClient
                                .get()
                                .uri(
                                        uri ->
                                                uri.path(
                                                                "/repos/{owner}/{repo}/rules/branches/{branch}")
                                                        .queryParam(
                                                                "per_page", BRANCH_RULES_PAGE_SIZE)
                                                        .queryParam("page", currentPage)
                                                        .build(pr.owner(), pr.repo(), baseRefName))
                                .header("Authorization", authorization(pat))
                                .retrieve()
                                .toEntity(JsonNode.class);
                JsonNode rules = response.getBody();
                if (rules == null || !rules.isArray()) {
                    throw incompleteMergeQueueRulesResponse();
                }
                for (JsonNode rule : rules) {
                    JsonNode type = rule.isObject() ? rule.get("type") : null;
                    if (type == null || !type.isTextual() || type.textValue().isBlank()) {
                        throw incompleteMergeQueueRulesResponse();
                    }
                    mergeQueueConfigured |= type.textValue().equals("merge_queue");
                }
                Integer nextPage =
                        nextBranchRulesPage(response.getHeaders(), pr, baseRefName, currentPage);
                if (nextPage == null) {
                    return mergeQueueConfigured;
                }
                if (currentPage >= BRANCH_RULES_MAX_PAGES) {
                    throw incompleteMergeQueueRulesResponse();
                }
                page = nextPage;
            }
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static Integer nextBranchRulesPage(
            HttpHeaders headers, PullRequestRef pr, String baseRefName, int currentPage) {
        List<String> values = headers.get(HttpHeaders.LINK);
        if (values == null || values.isEmpty()) {
            return null;
        }
        Map<String, Integer> pages = new HashMap<>();
        String expectedPath =
                "/repos/" + pr.owner() + "/" + pr.repo() + "/rules/branches/" + baseRefName;
        for (String value : values) {
            for (String part : value.split(",")) {
                Matcher matcher = LINK_HEADER.matcher(part);
                if (!matcher.matches()) {
                    throw incompleteMergeQueueRulesResponse();
                }
                URI uri;
                try {
                    uri = URI.create(matcher.group(1));
                }
                catch (IllegalArgumentException e) {
                    throw incompleteMergeQueueRulesResponse();
                }
                var query = UriComponentsBuilder.fromUri(uri).build(true).getQueryParams();
                List<String> pageValues = query.get("page");
                List<String> pageSizeValues = query.get("per_page");
                if (!expectedPath.equals(uri.getPath())
                        || pageValues == null
                        || pageValues.size() != 1
                        || pageSizeValues == null
                        || pageSizeValues.size() != 1
                        || !pageSizeValues
                                .getFirst()
                                .equals(String.valueOf(BRANCH_RULES_PAGE_SIZE))) {
                    throw incompleteMergeQueueRulesResponse();
                }
                int linkedPage;
                try {
                    linkedPage = Integer.parseInt(pageValues.getFirst());
                }
                catch (NumberFormatException e) {
                    throw incompleteMergeQueueRulesResponse();
                }
                if (linkedPage < 1 || pages.putIfAbsent(matcher.group(2), linkedPage) != null) {
                    throw incompleteMergeQueueRulesResponse();
                }
            }
        }
        Integer next = pages.get("next");
        Integer previous = pages.get("prev");
        Integer first = pages.get("first");
        Integer last = pages.get("last");
        if ((next != null && next != currentPage + 1)
                || (previous != null && previous != currentPage - 1)
                || (first != null && first != 1)
                || (last != null && last < currentPage)
                || (next == null && last != null && last > currentPage)
                || (next != null && last != null && last < next)) {
            throw incompleteMergeQueueRulesResponse();
        }
        return next;
    }

    private static ResponseStatusException incompleteMergeQueueRulesResponse() {
        return new ResponseStatusException(
                HttpStatusCode.valueOf(502),
                "GitHub fetchMergeQueueInfo returned incomplete branch rules data");
    }

    private static String requireNonBlankText(JsonNode object, String field) {
        JsonNode value = object.isObject() ? object.get(field) : null;
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw incompleteMergeQueueResponse("fetchMergeQueueInfo");
        }
        return value.textValue();
    }

    // ── GraphQL: auto-merge (enable / disable / state) ────────────────────
    //
    // REST does not expose any of these — github.com itself uses the same
    // GraphQL mutations and the autoMergeRequest object on the PR. Each
    // mutation needs the PR's opaque GraphQL node id, so they all share the
    // node-id fetch below.

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestIdGqlResponse(PullRequestIdGqlData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestIdGqlData(PullRequestIdGqlRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestIdGqlRepo(PullRequestIdGqlPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullRequestIdGqlPr(String id) {}

    @Override
    public void enableAutoMerge(String pat, PullRequestRef pr, String mergeMethod) {
        String nodeId = fetchPullRequestNodeId(pat, pr);
        String mutation =
                "mutation($id: ID!, $method: PullRequestMergeMethod!) {"
                    + " enablePullRequestAutoMerge(input: { pullRequestId: $id, mergeMethod:"
                    + " $method }) {   pullRequest { id } } }";
        Map<String, Object> body =
                ImmutableMap.of(
                        "query",
                        mutation,
                        "variables",
                        ImmutableMap.of("id", nodeId, "method", mergeMethod));
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

    @Override
    public void disableAutoMerge(String pat, PullRequestRef pr) {
        String nodeId = fetchPullRequestNodeId(pat, pr);
        String mutation =
                "mutation($id: ID!) {"
                        + " disablePullRequestAutoMerge(input: { pullRequestId: $id }) {"
                        + "   pullRequest { id }"
                        + " } }";
        Map<String, Object> body =
                ImmutableMap.of("query", mutation, "variables", ImmutableMap.of("id", nodeId));
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

    @Override
    public Optional<AutoMergeStatus> fetchAutoMergeStatus(String pat, PullRequestRef pr) {
        String query =
                "query($owner: String!, $name: String!, $number: Int!) {"
                        + " repository(owner: $owner, name: $name) {"
                        + "   pullRequest(number: $number) {"
                        + "     autoMergeRequest { mergeMethod enabledBy { login } }"
                        + "   }"
                        + " } }";
        Map<String, Object> body =
                ImmutableMap.of(
                        "query",
                        query,
                        "variables",
                        ImmutableMap.of(
                                "owner", pr.owner(),
                                "name", pr.repo(),
                                "number", pr.number()));
        try {
            AutoMergeGqlResponse response =
                    graphqlRestClient
                            .post()
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
    public Optional<MergeQueueProbe> probeMergeQueue(String pat, PullRequestRef pr) {
        String query =
                "query($owner: String!, $name: String!, $number: Int!) {"
                        + " repository(owner: $owner, name: $name) {"
                        + "   pullRequest(number: $number) { id mergeQueue { id } }"
                        + " } }";
        Map<String, Object> body =
                ImmutableMap.of(
                        "query",
                        query,
                        "variables",
                        ImmutableMap.of(
                                "owner", pr.owner(),
                                "name", pr.repo(),
                                "number", pr.number()));
        try {
            MergeQueueProbeGqlResponse response =
                    graphqlRestClient
                            .post()
                            .header("Authorization", authorization(pat))
                            .body(body)
                            .retrieve()
                            .body(MergeQueueProbeGqlResponse.class);
            requireMergeQueueResponse(
                    response == null ? null : response.errors(), "probeMergeQueue");
            if (response == null
                    || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null) {
                throw incompleteMergeQueueResponse("probeMergeQueue");
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
    public Optional<String> pullRequestNodeId(String pat, PullRequestRef pr) {
        // Same shape as the merge-queue probe but without the mergeQueue
        // gate — used when a direct merge is rejected because a ruleset
        // requires the queue (GraphQL's pullRequest.mergeQueue is null for
        // ruleset-driven queues, so the probe can't see it).
        String query =
                "query($owner: String!, $name: String!, $number: Int!) {"
                        + " repository(owner: $owner, name: $name) {"
                        + "   pullRequest(number: $number) { id }"
                        + " } }";
        Map<String, Object> body =
                ImmutableMap.of(
                        "query",
                        query,
                        "variables",
                        ImmutableMap.of(
                                "owner", pr.owner(),
                                "name", pr.repo(),
                                "number", pr.number()));
        try {
            MergeQueueProbeGqlResponse response =
                    graphqlRestClient
                            .post()
                            .header("Authorization", authorization(pat))
                            .body(body)
                            .retrieve()
                            .body(MergeQueueProbeGqlResponse.class);
            requireMergeQueueResponse(
                    response == null ? null : response.errors(), "pullRequestNodeId");
            if (response == null
                    || response.data() == null
                    || response.data().repository() == null
                    || response.data().repository().pullRequest() == null) {
                throw incompleteMergeQueueResponse("pullRequestNodeId");
            }
            return Optional.ofNullable(response.data().repository().pullRequest().id());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public MergeResult enqueuePullRequest(String pat, String pullRequestNodeId) {
        String mutation =
                "mutation($id: ID!) {"
                        + " enqueuePullRequest(input: { pullRequestId: $id }) {"
                        + "   mergeQueueEntry { id position state }"
                        + " } }";
        Map<String, Object> body =
                ImmutableMap.of(
                        "query", mutation, "variables", ImmutableMap.of("id", pullRequestNodeId));
        return enqueuePullRequest(pat, body);
    }

    @Override
    public MergeResult enqueuePullRequest(
            String pat, String pullRequestNodeId, String expectedHeadOid) {
        if (expectedHeadOid == null || expectedHeadOid.isBlank()) {
            throw new IllegalArgumentException("expectedHeadOid must not be blank");
        }
        String mutation =
                "mutation($id: ID!, $head: GitObjectID!) { enqueuePullRequest(input: {"
                    + " pullRequestId: $id, expectedHeadOid: $head }) {   mergeQueueEntry { id"
                    + " position state } } }";
        Map<String, Object> body =
                ImmutableMap.of(
                        "query",
                        mutation,
                        "variables",
                        ImmutableMap.of("id", pullRequestNodeId, "head", expectedHeadOid));
        return enqueuePullRequest(pat, body);
    }

    private MergeResult enqueuePullRequest(String pat, Map<String, Object> body) {
        // The queue's configured merge method overrides caller preference.
        // Reading back state makes the result useful to older callers too.
        try {
            EnqueueGqlResponse response =
                    graphqlRestClient
                            .post()
                            .header("Authorization", authorization(pat))
                            .body(body)
                            .retrieve()
                            .body(EnqueueGqlResponse.class);
            if (response != null && response.errors() != null && !response.errors().isEmpty()) {
                EnqueueGqlError error = response.errors().getFirst();
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(422),
                        "GitHub enqueuePullRequest failed: " + error.message());
            }
            EnqueueGqlEntry entry =
                    response == null
                                    || response.data() == null
                                    || response.data().enqueuePullRequest() == null
                            ? null
                            : response.data().enqueuePullRequest().mergeQueueEntry();
            if (entry == null) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(502),
                        "GitHub enqueuePullRequest returned no queue entry");
            }
            String message;
            if (entry.position() != null && entry.state() != null) {
                message =
                        "Added to merge queue (position "
                                + entry.position()
                                + ", "
                                + entry.state().toLowerCase(Locale.ROOT).replace('_', ' ')
                                + ")";
            }
            else if (entry.state() != null) {
                message =
                        "Added to merge queue ("
                                + entry.state().toLowerCase(Locale.ROOT).replace('_', ' ')
                                + ")";
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
    record MergeQueueProbeGqlResponse(
            MergeQueueProbeGqlData data, List<MergeQueueGqlError> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueProbeGqlData(MergeQueueProbeGqlRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueProbeGqlRepo(MergeQueueProbeGqlPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueProbeGqlPr(String id, MergeQueueProbeGqlQueue mergeQueue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergeQueueProbeGqlQueue(String id) {}

    private static void requireMergeQueueResponse(
            List<MergeQueueGqlError> errors, String operation) {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        MergeQueueGqlError error = errors.getFirst();
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(502),
                "GitHub "
                        + operation
                        + " returned GraphQL error "
                        + error.type()
                        + ": "
                        + error.message());
    }

    private static ResponseStatusException incompleteMergeQueueResponse(String operation) {
        return new ResponseStatusException(
                HttpStatusCode.valueOf(502),
                "GitHub " + operation + " returned incomplete GraphQL data");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueGqlResponse(EnqueueGqlData data, List<EnqueueGqlError> errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueGqlError(String type, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueGqlData(EnqueueGqlPayload enqueuePullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueGqlPayload(EnqueueGqlEntry mergeQueueEntry) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnqueueGqlEntry(String id, Integer position, String state) {}

    @Override
    public void dequeuePullRequest(String pat, PullRequestRef pr) {
        // dequeuePullRequest takes the merge queue ENTRY id, not the PR id —
        // fetch it via a small GraphQL probe first. If the PR isn't currently
        // in a queue we no-op so a stale "Queued" cache state doesn't error
        // when the user clicks "Remove from queue" after GitHub already
        // merged or removed the PR.
        String entryQuery =
                "query($owner: String!, $name: String!, $number: Int!) {"
                        + " repository(owner: $owner, name: $name) {"
                        + "   pullRequest(number: $number) {"
                        + "     mergeQueueEntry { id }"
                        + "   }"
                        + " } }";
        Map<String, Object> queryBody =
                ImmutableMap.of(
                        "query",
                        entryQuery,
                        "variables",
                        ImmutableMap.of(
                                "owner", pr.owner(),
                                "name", pr.repo(),
                                "number", pr.number()));
        String entryId;
        try {
            DequeueProbeGqlResponse probe =
                    graphqlRestClient
                            .post()
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

        String mutation =
                "mutation($id: ID!) {"
                        + " dequeuePullRequest(input: { id: $id }) {"
                        + "   mergeQueueEntry { id }"
                        + " } }";
        Map<String, Object> body =
                ImmutableMap.of("query", mutation, "variables", ImmutableMap.of("id", entryId));
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
    record DequeueProbeGqlResponse(DequeueProbeGqlData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DequeueProbeGqlData(DequeueProbeGqlRepo repository) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DequeueProbeGqlRepo(DequeueProbeGqlPr pullRequest) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DequeueProbeGqlPr(DequeueProbeGqlEntry mergeQueueEntry) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DequeueProbeGqlEntry(String id) {}
}
