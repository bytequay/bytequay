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

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrMergeQueueRemoval;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReaction;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.hosting.DiscussionProbe;
import com.bytequay.core.hosting.PullRequestFetch;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.bytequay.app.repository.github.GitHubApiSupport.get;
import static com.bytequay.app.repository.github.GitHubApiSupport.graphql;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubPullRequestReadClient
{
    private static final int MAX_CODE_SNIPPET_BYTES = 1024 * 1024;
    private final RestClient gitHubRestClient;
    private final RestClient graphQlRestClient;
    private final GitHubPaginator paginator;

    public GitHubPullRequestReadClient(RestClient gitHubRestClient, @Qualifier("gitHubGraphQLRestClient") RestClient graphQlRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphQlRestClient = requireNonNull(graphQlRestClient, "graphQlRestClient is null");
        this.paginator = new GitHubPaginator(gitHubRestClient);
    }

    private static final String REPOS_SEGMENT = "/repos/";
    private static final Set<String> NON_CI_RULES = ImmutableSet.of(
            "creation", "update", "deletion", "required_linear_history", "merge_queue", "required_deployments", "required_signatures", "pull_request", "non_fast_forward",
            "commit_message_pattern", "commit_author_email_pattern", "committer_email_pattern", "branch_name_pattern", "tag_name_pattern",
            "file_path_restriction", "max_file_path_length", "file_extension_restriction", "max_file_size");

    /** The REST reads behind a stored discussion; the GraphQL reads only add to what these return. */
    private static final Map<String, String> DISCUSSION_SOURCES = ImmutableMap.of(
            "reviews", "/repos/{owner}/{repo}/pulls/{number}/reviews",
            "timeline", "/repos/{owner}/{repo}/issues/{number}/timeline",
            "comments", "/repos/{owner}/{repo}/issues/{number}/comments",
            "reviewComments", "/repos/{owner}/{repo}/pulls/{number}/comments",
            "issue", "/repos/{owner}/{repo}/issues/{number}");

    /** The list endpoint supplies head refs immediately, without waiting for GitHub's search index. */
    public List<FetchedPullRequest> fetchPullRequestsByHead(String pat, RepoRef repository, String head)
    {
        requirePat(pat);
        requireNonNull(repository, "repository is null");
        requireNonNull(head, "head is null");
        ImmutableList.Builder<FetchedPullRequest> result = ImmutableList.builder();
        GitHubPaginator.forEachPage(50, page -> translating(() -> get(gitHubRestClient, pat, uri -> uri.path("/repos/{owner}/{repo}/pulls")
                .queryParam("state", "all")
                .queryParam("head", "{head}")
                .queryParam("per_page", GitHubPaginator.PAGE_SIZE)
                .queryParam("page", page)
                .build(repository.owner(), repository.repo(), head))
                .retrieve().body(new ParameterizedTypeReference<List<GitHubPullRequestDetailResponse>>() {})),
                rows -> rows.forEach(row -> result.add(toPullRequest(row, repository.fullName(),
                        Optional.ofNullable(row.requestedReviewers()).orElse(ImmutableList.of()).stream()
                                .map(GitHubUser::login).collect(toImmutableList())))));
        return result.build();
    }

    /** Reads all PR states for an exact fork or same-repository head, retaining different base branches. */
    public List<GitHubPullRequestIdentity> fetchPullRequestIdentities(String pat, RepoRef baseRepository, RepoRef headRepository, String headBranch)
    {
        requirePat(pat);
        requireNonNull(baseRepository, "baseRepository is null");
        requireNonNull(headRepository, "headRepository is null");
        requireNonNull(headBranch, "headBranch is null");
        ImmutableList.Builder<GitHubPullRequestIdentity> result = ImmutableList.builder();
        boolean complete = GitHubPaginator.forEachPage(50, page -> requireBody(translating(() -> get(gitHubRestClient, pat, uri -> uri.path("/repos/{owner}/{repo}/pulls")
                    .queryParam("state", "all")
                    .queryParam("head", "{head}")
                    .queryParam("per_page", GitHubPaginator.PAGE_SIZE)
                    .queryParam("page", page)
                    .build(baseRepository.owner(), baseRepository.repo(), headRepository.owner() + ":" + headBranch))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PublicationPullRequest>>() {})), "GitHub returned no pull-request identity response"), rows -> {
                for (PublicationPullRequest row : rows) {
                    GitHubPullRequestIdentity identity = publicationIdentity(row, baseRepository);
                    if (!identity.headRepository().fullName().equalsIgnoreCase(headRepository.fullName()) || !identity.headBranch().equals(headBranch)) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned another pull-request head");
                    }
                    result.add(identity);
                }
            });
        if (!complete) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Pull-request identity results exceeded the pagination limit");
        }
        return result.build();
    }

    public GitHubPullRequestIdentity fetchPullRequestIdentity(String pat, PullRequestRef pullRequest)
    {
        requirePat(pat);
        requireNonNull(pullRequest, "pullRequest is null");
        PublicationPullRequest response = readPublication(pat, pullRequest);
        GitHubPullRequestIdentity identity = publicationIdentity(response, RepoRef.of(pullRequest.owner(), pullRequest.repo()));
        checkState(identity.pullRequest().equals(pullRequest), "GitHub returned another pull request");
        return identity;
    }

    /** Fresh merge evidence and the complete original PR sequence, never cached commit-to-PR grouping. */
    public MergedPullRequest fetchMergedPullRequest(String pat, PullRequestRef pullRequest)
    {
        requirePat(pat);
        requireNonNull(pullRequest, "pullRequest is null");
        PublicationPullRequest before = readPublication(pat, pullRequest);
        GitHubPullRequestIdentity identity = publicationIdentity(before, RepoRef.of(pullRequest.owner(), pullRequest.repo()));
        checkState(identity.pullRequest().equals(pullRequest), "GitHub returned another pull request");
        checkState(identity.state() == GitHubPullRequestIdentity.State.MERGED && Boolean.TRUE.equals(before.merged()), "The pull request is not verified merged");
        checkState(before.mergeCommitSha() != null, "GitHub omitted the landed commit");
        checkSha(before.mergeCommitSha(), "GitHub merge commit");
        checkState(before.base().repo().id() > 0, "GitHub omitted the base repository ID");
        // GitHub's PR commits endpoint is capped at 250, including when pagination succeeds.
        checkState(before.commits() != null && before.commits() > 0 && before.commits() <= 250, "The complete pull-request commit list is unavailable (supported limit: 250)");
        ImmutableList.Builder<String> commits = ImmutableList.builder();
        for (int offset = 0; offset < before.commits(); offset += GitHubPaginator.PAGE_SIZE) {
            List<GitHubPullRequestCommit> page = paginator.fetchPage(pat, "/repos/{owner}/{repo}/pulls/{number}/commits",
                    new ParameterizedTypeReference<List<GitHubPullRequestCommit>>() {}, offset / GitHubPaginator.PAGE_SIZE + 1,
                    pullRequest.owner(), pullRequest.repo(), pullRequest.number());
            checkState(page.size() == Math.min(GitHubPaginator.PAGE_SIZE, before.commits() - offset), "GitHub returned an incomplete pull-request commit list");
            for (GitHubPullRequestCommit commit : page) {
                checkState(commit != null && commit.sha() != null, "GitHub omitted a pull-request commit");
                checkSha(commit.sha(), "GitHub pull-request commit");
                commits.add(commit.sha());
            }
        }
        List<String> sequence = commits.build();
        checkState(ImmutableSet.copyOf(sequence).size() == sequence.size(), "GitHub returned duplicate pull-request commits");
        checkState(sequence.getLast().equals(identity.headSha()), "GitHub's commit list does not end at the pull-request head");
        checkState(before.equals(readPublication(pat, pullRequest)), "The pull request changed while its merge evidence was being read");
        return new MergedPullRequest(identity, before.base().repo().id(), before.mergedAt(), before.mergeCommitSha(), sequence);
    }

    private PublicationPullRequest readPublication(String pat, PullRequestRef pullRequest)
    {
        return requireBody(translating(() -> get(gitHubRestClient, pat, "/repos/{owner}/{repo}/pulls/{number}",
                pullRequest.owner(), pullRequest.repo(), pullRequest.number()).retrieve().body(PublicationPullRequest.class)),
                "GitHub returned no pull-request merge evidence");
    }

    public record MergedPullRequest(GitHubPullRequestIdentity identity, long repositoryId, Instant mergedAt, String mergeSha, List<String> commits)
    {
        public MergedPullRequest
        {
            requireNonNull(identity, "identity is null");
            requireNonNull(mergedAt, "mergedAt is null");
            checkSha(mergeSha, "mergeSha");
            commits = ImmutableList.copyOf(commits);
        }
    }

    /** Reads complete current-head checks and both classic and ruleset requirements without a cached success fallback. */
    public GitHubDeliveryChecks fetchDeliveryChecks(String pat, PullRequestRef pullRequest)
    {
        requirePat(pat);
        requireNonNull(pullRequest, "pullRequest is null");
        JsonNode first = fetchDeliveryCheckPage(pat, pullRequest, Optional.empty());
        GitHubPullRequestIdentity identity = deliveryIdentity(first, pullRequest);
        String nodeId = deliveryText(first, "id");
        List<String> waiting = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            List<RequiredDeliveryCheck> required = new ArrayList<>();
            readClassicRequirements(first.path("baseRef"), required);
            readRulesetRequirements(pat, identity, required, waiting);
            inspectMergeChecks(first, waiting, failures);
            List<JsonNode> checks = new ArrayList<>();
            Map<String, JsonNode> unique = new LinkedHashMap<>();
            JsonNode page = first;
            Optional<String> cursor = Optional.empty();
            for (int pageNumber = 1; pageNumber <= 10; pageNumber++) {
                checkState(identity.equals(deliveryIdentity(page, pullRequest)), "The pull-request revision changed while checks were being read");
                checkState(first.path("baseRef").equals(page.path("baseRef")), "The base branch or its required checks changed while being read");
                checkState(first.path("potentialMergeCommit").equals(page.path("potentialMergeCommit")), "Test merge commit checks changed while being read");
                JsonNode rollup = page.path("statusCheckRollup");
                checkState(identity.headSha().equals(deliveryText(rollup.path("commit"), "oid")), "Current-head CI evidence is missing or belongs to another revision");
                JsonNode contexts = rollup.path("contexts");
                checkState(contexts.path("nodes").isArray() && contexts.path("totalCount").isIntegralNumber() && contexts.path("totalCount").canConvertToInt(),
                        "GitHub check results are incomplete");
                checkState(first.path("statusCheckRollup").path("state").equals(rollup.path("state")), "GitHub check results changed during pagination");
                for (JsonNode check : contexts.path("nodes")) {
                    checkState(unique.putIfAbsent(deliveryText(check, "id"), check) == null, "GitHub returned duplicate check results during pagination");
                    checks.add(check);
                }
                JsonNode pageInfo = contexts.path("pageInfo");
                checkState(pageInfo.path("hasNextPage").isBoolean(), "GitHub check pagination is incomplete");
                if (!pageInfo.path("hasNextPage").booleanValue()) {
                    checkState(checks.size() == contexts.path("totalCount").intValue(), "GitHub check results are incomplete");
                    evaluateDeliveryChecks(checks, required, waiting, failures);
                    String state = deliveryText(rollup, "state");
                    if (!"SUCCESS".equals(state)) {
                        waiting.add("GitHub's current-head check rollup is " + state);
                    }
                    return new GitHubDeliveryChecks(nodeId, identity, waiting, failures);
                }
                String nextCursor = deliveryText(pageInfo, "endCursor");
                checkState(!cursor.filter(nextCursor::equals).isPresent(), "GitHub check pagination did not advance");
                checkState(pageNumber < 10, "GitHub checks exceed the complete inspection limit");
                cursor = Optional.of(nextCursor);
                page = fetchDeliveryCheckPage(pat, pullRequest, cursor);
            }
        }
        catch (IllegalArgumentException | IllegalStateException invalid) {
            waiting.add(invalid.getMessage());
        }
        return new GitHubDeliveryChecks(nodeId, identity, waiting, failures);
    }

    private JsonNode fetchDeliveryCheckPage(String pat, PullRequestRef pullRequest, Optional<String> cursor)
    {
        String query = """
                query($owner:String!,$name:String!,$number:Int!,$cursor:String){
                  repository(owner:$owner,name:$name){pullRequest(number:$number){
                    id number url state isDraft author{login} headRefName headRefOid baseRefName baseRefOid
                    headRepository{nameWithOwner} baseRepository{nameWithOwner}
                    headRef{target{oid}}
                    baseRef{target{oid} branchProtectionRule{requiresStatusChecks requiredStatusChecks{context app{databaseId}}}}
                    potentialMergeCommit{oid statusCheckRollup{commit{oid} state contexts(first:1){totalCount}}}
                    statusCheckRollup{commit{oid} state contexts(first:100,after:$cursor){totalCount pageInfo{hasNextPage endCursor} nodes{
                      __typename ... on CheckRun{id name status conclusion isRequired(pullRequestNumber:$number) checkSuite{app{databaseId slug}}}
                      ... on StatusContext{id context state isRequired(pullRequestNumber:$number)}
                    }}}
                  }}
                }
                """;
        Map<String, Object> variables = new LinkedHashMap<>(ImmutableMap.of("owner", pullRequest.owner(), "name", pullRequest.repo(), "number", pullRequest.number()));
        cursor.ifPresent(value -> variables.put("cursor", value));
        JsonNode response = translating(() -> graphql(graphQlRestClient, pat, query, variables)
                .body(JsonNode.class));
        if (response == null || (response.has("errors") && (!response.path("errors").isArray() || !response.path("errors").isEmpty()))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub could not verify current PR checks and branch protection");
        }
        return response.path("data").path("repository").path("pullRequest");
    }

    private static GitHubPullRequestIdentity deliveryIdentity(JsonNode row, PullRequestRef pullRequest)
    {
        checkState(row.path("number").isIntegralNumber() && row.path("number").intValue() == pullRequest.number(), "GitHub returned another pull request");
        checkState(row.path("isDraft").isBoolean(), "GitHub omitted draft status");
        RepoRef headRepository = deliveryRepository(row.path("headRepository"));
        RepoRef baseRepository = deliveryRepository(row.path("baseRepository"));
        checkState(baseRepository.equals(RepoRef.of(pullRequest.owner(), pullRequest.repo())), "GitHub returned another base repository");
        String head = deliveryText(row, "headRefOid");
        String base = deliveryText(row, "baseRefOid");
        checkState(head.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})") && base.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"), "GitHub returned an invalid PR revision");
        checkState(head.equals(deliveryText(row.path("headRef").path("target"), "oid")), "The pull-request head branch changed or is missing");
        // GitHub records baseRefOid at the last push, so a base branch that moved on since then is normal and leaves head checks valid.
        checkState(row.path("baseRef").path("target").path("oid").isTextual(), "The pull-request base branch is missing");
        return new GitHubPullRequestIdentity(pullRequest, deliveryText(row, "url"), headRepository, deliveryText(row, "headRefName"), head,
                baseRepository, deliveryText(row, "baseRefName"), base, GitHubPullRequestIdentity.State.valueOf(deliveryText(row, "state")),
                row.path("isDraft").booleanValue(), deliveryText(row.path("author"), "login"));
    }

    private static RepoRef deliveryRepository(JsonNode repository)
    {
        RepositoryIdentity identity = RepositoryIdentity.valueOf(deliveryText(repository, "nameWithOwner"));
        return RepoRef.of(identity.owner(), identity.name());
    }

    private static String deliveryText(JsonNode node, String field)
    {
        checkState(node.path(field).isTextual() && !node.path(field).textValue().isBlank(), "GitHub omitted " + field + " evidence");
        return node.path(field).textValue();
    }

    private static void readClassicRequirements(JsonNode baseRef, List<RequiredDeliveryCheck> required)
    {
        checkState(baseRef.has("branchProtectionRule"), "GitHub omitted branch-protection evidence");
        JsonNode rule = baseRef.path("branchProtectionRule");
        if (rule.isNull()) {
            return;
        }
        checkState(rule.path("requiresStatusChecks").isBoolean(), "GitHub omitted required-check policy");
        if (!rule.path("requiresStatusChecks").booleanValue()) {
            return;
        }
        checkState(rule.path("requiredStatusChecks").isArray(), "GitHub omitted required checks");
        for (JsonNode check : rule.path("requiredStatusChecks")) {
            checkState(check.has("app"), "GitHub omitted required-check app identity");
            Optional<Long> app = check.path("app").isNull() ? Optional.empty() : Optional.of(deliveryAppId(check.path("app").path("databaseId")));
            required.add(new RequiredDeliveryCheck(deliveryText(check, "context"), app));
        }
    }

    private void readRulesetRequirements(String pat, GitHubPullRequestIdentity identity, List<RequiredDeliveryCheck> required, List<String> waiting)
    {
        boolean complete = GitHubPaginator.forEachPage(10, page -> {
            JsonNode rows = translating(() -> get(gitHubRestClient, pat,
                    uri -> uri.path("/repos/{owner}/{repo}/rules/branches/{branch}").queryParam("per_page", 100).queryParam("page", page)
                            .build(identity.baseRepository().owner(), identity.baseRepository().repo(), identity.baseBranch()))
                    .retrieve()
                    .body(JsonNode.class));
            checkState(rows != null && rows.isArray(), "GitHub omitted active branch rules");
            return ImmutableList.copyOf(rows);
        }, rows -> {
            for (JsonNode rule : rows) {
                String type = deliveryText(rule, "type");
                if ("required_status_checks".equals(type)) {
                    JsonNode checks = rule.path("parameters").path("required_status_checks");
                    checkState(checks.isArray(), "GitHub omitted ruleset required checks");
                    for (JsonNode check : checks) {
                        JsonNode integration = check.path("integration_id");
                        Optional<Long> app = integration.isMissingNode() || integration.isNull() ? Optional.empty() : Optional.of(deliveryAppId(integration));
                        required.add(new RequiredDeliveryCheck(deliveryText(check, "context"), app));
                    }
                }
                else if ("workflows".equals(type) || "code_scanning".equals(type)) {
                    waiting.add("Required " + type.replace('_', ' ') + " evidence is not supported by this CI check gate");
                }
                else if (!NON_CI_RULES.contains(type)) {
                    waiting.add("Unknown branch rule " + type + " prevents complete required-check verification");
                }
            }
        });
        checkState(complete, "GitHub active branch rules exceed the complete inspection limit");
    }

    private static long deliveryAppId(JsonNode value)
    {
        checkState(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0, "GitHub omitted required-check app identity");
        return value.longValue();
    }

    private static void inspectMergeChecks(JsonNode pullRequest, List<String> waiting, List<String> failures)
    {
        checkState(pullRequest.has("potentialMergeCommit"), "GitHub omitted test merge commit evidence");
        JsonNode merge = pullRequest.path("potentialMergeCommit");
        if (merge.isNull()) {
            waiting.add("Waiting for GitHub to produce the current test merge commit");
            return;
        }
        String mergeSha = deliveryText(merge, "oid");
        checkState(mergeSha.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"), "GitHub returned an invalid test merge commit");
        checkState(merge.has("statusCheckRollup"), "GitHub omitted test merge checks");
        JsonNode rollup = merge.path("statusCheckRollup");
        if (rollup.isNull()) {
            return;
        }
        checkState(mergeSha.equals(deliveryText(rollup.path("commit"), "oid")), "Test merge checks belong to another revision");
        JsonNode count = rollup.path("contexts").path("totalCount");
        checkState(count.isIntegralNumber() && count.canConvertToInt() && count.intValue() >= 0, "GitHub omitted the test merge check count");
        if (count.intValue() > 0) {
            String state = deliveryText(rollup, "state");
            // GitHub uses merge-commit requirements when that commit has checks; head checks cannot substitute for them.
            if ("FAILURE".equals(state) || "ERROR".equals(state)) {
                failures.add("Test merge commit checks reported " + state + "; Actions Autofix cannot repair this separate check result");
            }
            else {
                waiting.add("Test merge commit has separate checks (" + state + "); complete required-check verification for that commit is not supported");
            }
        }
        else {
            waiting.add("Test merge commit check evidence is empty");
        }
    }

    private static void evaluateDeliveryChecks(List<JsonNode> checks, List<RequiredDeliveryCheck> required, List<String> waiting, List<String> failures)
    {
        if (checks.isEmpty()) {
            waiting.add("No current-head CI checks have been reported; required workflows may not run while the PR is draft");
        }
        for (JsonNode check : checks) {
            boolean checkRun = "CheckRun".equals(deliveryText(check, "__typename"));
            checkState(checkRun || "StatusContext".equals(deliveryText(check, "__typename")), "GitHub returned an unknown check type");
            String name = deliveryText(check, checkRun ? "name" : "context");
            checkState(check.path("isRequired").isBoolean(), "GitHub omitted required-check applicability");
            String state = deliveryText(check, checkRun ? "status" : "state");
            String result = checkRun && "COMPLETED".equals(state) ? deliveryText(check, "conclusion") : state;
            boolean passing = checkRun ? "COMPLETED".equals(state) && ImmutableSet.of("SUCCESS", "NEUTRAL", "SKIPPED").contains(result) : "SUCCESS".equals(result);
            if (passing) {
                continue;
            }
            if (ImmutableSet.of("FAILURE", "ERROR", "ACTION_REQUIRED", "CANCELLED", "STALE", "STARTUP_FAILURE", "TIMED_OUT").contains(result)) {
                String origin = checkRun && "github-actions".equals(check.path("checkSuite").path("app").path("slug").asText()) ? "CI check" : "External CI check";
                failures.add(origin + " " + name + " reported " + result + ("External CI check".equals(origin) ? "; Actions Autofix cannot repair this external check" : ""));
            }
            else {
                waiting.add("Waiting for CI check " + name + " (" + result + ")");
            }
        }
        for (RequiredDeliveryCheck requirement : required) {
            boolean present = checks.stream().anyMatch(check -> {
                boolean checkRun = "CheckRun".equals(check.path("__typename").asText());
                return requirement.context().equals(check.path(checkRun ? "name" : "context").asText()) && check.path("isRequired").asBoolean(false)
                        && requirement.appId().map(app -> checkRun && check.path("checkSuite").path("app").path("databaseId").asLong(-1) == app).orElse(true);
            });
            if (!present) {
                waiting.add("Required CI check " + requirement.context() + " has not reported from its required source; it may not run while the PR is draft");
            }
        }
    }

    private record RequiredDeliveryCheck(String context, Optional<Long> appId) {}

    private static GitHubPullRequestIdentity publicationIdentity(PublicationPullRequest row, RepoRef expectedBase)
    {
        try {
            requireNonNull(row, "pull request is null");
            RepoRef headRepository = publicationRepository(row.head().repo());
            RepoRef baseRepository = publicationRepository(row.base().repo());
            checkState(baseRepository.fullName().equalsIgnoreCase(expectedBase.fullName()), "GitHub returned another base repository");
            checkState(row.number() > 0 && row.draft() != null, "GitHub omitted pull-request identity facts");
            checkState(row.mergedAt() == null || "closed".equals(row.state()), "GitHub returned inconsistent pull-request state");
            checkState(row.head().sha().matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
                    && row.base().sha().matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"), "GitHub returned an invalid pull-request revision");
            GitHubPullRequestIdentity.State state = switch (row.state()) {
                case "open" -> GitHubPullRequestIdentity.State.OPEN;
                case "closed" -> row.mergedAt() == null ? GitHubPullRequestIdentity.State.CLOSED : GitHubPullRequestIdentity.State.MERGED;
                default -> throw new IllegalStateException("GitHub returned an unknown pull-request state");
            };
            return new GitHubPullRequestIdentity(
                    PullRequestRef.of(baseRepository.owner(), baseRepository.repo(), row.number()), row.htmlUrl(),
                    headRepository, row.head().ref(), row.head().sha(), baseRepository, row.base().ref(), row.base().sha(),
                    state, row.draft(), row.user().login());
        }
        catch (IllegalArgumentException | IllegalStateException | NullPointerException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub pull-request identity could not be verified", invalid);
        }
    }

    private static RepoRef publicationRepository(PublicationRepository repository)
    {
        RepositoryIdentity identity = RepositoryIdentity.valueOf(repository.fullName());
        return RepoRef.of(identity.owner(), identity.name());
    }

    private record PublicationPullRequest(
            int number,
            @JsonProperty("html_url") String htmlUrl,
            String state,
            @JsonProperty("merged_at") Instant mergedAt,
            Boolean merged,
            @JsonProperty("merge_commit_sha") String mergeCommitSha,
            Integer commits,
            Boolean draft,
            PublicationBranch head,
            PublicationBranch base,
            PublicationUser user) {}

    private record PublicationBranch(String ref, String sha, PublicationRepository repo) {}

    private record PublicationRepository(long id, @JsonProperty("full_name") String fullName) {}

    private record PublicationUser(String login) {}

    public List<RepoUser> fetchAssignableUsers(String pat, RepoRef repo)
    {
        return fetchAssignableRepositoryUsers(pat, repo).stream()
                .map(user -> new RepoUser(user.login(), user.avatarUrl(), user.bot()))
                .collect(toImmutableList());
    }

    public List<GitHubRepositoryUser> fetchAssignableRepositoryUsers(String pat, RepoRef repo)
    {
        requirePat(pat);
        return paginator.paginate(pat, "/repos/{owner}/{repo}/assignees", new ParameterizedTypeReference<List<GitHubAssignableUser>>() {}, repo.owner(), repo.repo()).stream()
                .filter(user -> user.id() > 0
                        && user.login() != null && !user.login().isBlank()
                        && user.avatarUrl() != null && !user.avatarUrl().isBlank())
                .map(user -> new GitHubRepositoryUser(user.id(), user.login(), user.avatarUrl(), "Bot".equalsIgnoreCase(user.type()) || user.login().endsWith("[bot]")))
                .collect(toImmutableList());
    }

    /** A personal account has no teams; GitHub answers its teams list with 404. */
    public List<String> fetchOrganizationTeams(String pat, String organization)
    {
        requirePat(pat);
        try {
            return paginator.paginate(pat, "/orgs/{org}/teams", new ParameterizedTypeReference<List<GitHubPullRequestDetailResponse.Team>>() {}, organization).stream()
                    .map(GitHubPullRequestDetailResponse.Team::slug)
                    .filter(slug -> slug != null && !slug.isBlank())
                    .map(slug -> organization + "/" + slug)
                    .collect(toImmutableList());
        }
        catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return ImmutableList.of();
            }
            throw e;
        }
    }

    public PullRequestHistoryPage searchPullRequestsPaged(String pat, String query, int page, int perPage) {
        requirePat(pat);
        int safePage = Math.max(1, page);
        int safePerPage = Math.clamp(perPage, 1, 100);
        GitHubSearchResponse response = translating(() ->
                get(gitHubRestClient, pat, uri -> uri.path("/search/issues")
                        .queryParam("q", query)
                        .queryParam("per_page", safePerPage)
                        .queryParam("page", safePage)
                        .queryParam("sort", "updated")
                        .queryParam("order", "desc")
                        .build())
                        .retrieve()
                        .body(GitHubSearchResponse.class));
        if (response == null || response.items() == null || Boolean.TRUE.equals(response.incompleteResults())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned incomplete pull request search results");
        }
        PullRequest.Origin origin = query.contains("review-requested") ? REVIEW_REQUESTED : AUTHORED;
        List<FetchedPullRequest> items =
                response.items().stream()
                        .map(item -> toPullRequest(item, origin))
                        .collect(toImmutableList());
        // Report remaining results honestly; discovery handles GitHub's search ceiling without pruning omissions.
        int loadedSoFar = (safePage - 1) * safePerPage + items.size();
        boolean hasMore =
                items.size() == safePerPage
                        && loadedSoFar < response.totalCount();
        return new PullRequestHistoryPage(items, hasMore);
    }

    // ── Pull request detail ───────────────────────────────────────────────────

    public PrRawDetail fetchPrDetail(String pat, PullRequestRef pr) {
        GitHubPullRequestDetailResponse r = fetchPullRequestDetailResponse(pat, pr);
        return r == null ? null : toPrRawDetail(pr, r);
    }

    private PrRawDetail toPrRawDetail(PullRequestRef pr, GitHubPullRequestDetailResponse r)
    {
        requireNonNull(r, "response is null");
        List<String> labels =
                Optional.ofNullable(r.labels()).orElse(ImmutableList.of()).stream()
                        .map(GitHubPullRequestDetailResponse.Label::name)
                        .collect(toImmutableList());
        Map<String, String> labelColors = Optional.ofNullable(r.labels()).orElse(ImmutableList.of()).stream()
                .filter(label -> label.name() != null && label.color() != null)
                .collect(toImmutableMap(GitHubPullRequestDetailResponse.Label::name, GitHubPullRequestDetailResponse.Label::color, (left, right) -> left));
        List<String> assignees = Optional.ofNullable(r.assignees()).orElse(ImmutableList.of()).stream()
                .map(GitHubUser::login)
                .filter(Objects::nonNull)
                .collect(toImmutableList());
        // A requested team is named owner/slug, as the timeline names it; removing it addresses team_reviewers.
        List<String> reviewerLogins = Stream.concat(
                        Optional.ofNullable(r.requestedReviewers()).orElse(ImmutableList.of()).stream().map(GitHubUser::login),
                        Optional.ofNullable(r.requestedTeams()).orElse(ImmutableList.of()).stream().map(GitHubPullRequestDetailResponse.Team::slug)
                                .filter(Objects::nonNull).map(slug -> pr.owner() + "/" + slug))
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
        String baseRef = Optional.ofNullable(r.base())
                .map(GitHubPullRequestDetailResponse.Base::ref)
                .orElse(null);
        String author = Optional.ofNullable(r.user())
                .map(GitHubUser::login)
                .orElse(null);
        String authorAvatarUrl = Optional.ofNullable(r.user())
                .map(GitHubUser::avatarUrl)
                .orElse(null);
        return new PrRawDetail(
                r.title(),
                r.htmlUrl(),
                r.mergedAt() == null ? r.state() : "merged",
                author,
                authorAvatarUrl,
                r.createdAt(),
                r.updatedAt(),
                r.mergedAt(),
                r.body(),
                labels,
                labelColors,
                assignees,
                Optional.ofNullable(r.milestone())
                        .map(GitHubPullRequestDetailResponse.Milestone::title)
                        .orElse(null),
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
                baseRef,
                Optional.ofNullable(r.base()).map(GitHubPullRequestDetailResponse.Base::sha).orElse(null));
    }

    private GitHubPullRequestDetailResponse fetchPullRequestDetailResponse(String pat, PullRequestRef pr)
    {
        return translating(() -> get(gitHubRestClient, pat, "/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number())
                .retrieve()
                .body(GitHubPullRequestDetailResponse.class));
    }

    /**
     * Sends each discussion source's stored ETag with one request for its first page, so an unchanged discussion costs
     * only 304s. An ETag is kept only for a source with room left on that page: rows added to a full page land on the
     * next one and leave page one, and its ETag, unchanged.
     */
    public DiscussionProbe probeDiscussion(String pat, PullRequestRef pr, Map<String, String> etags)
    {
        ImmutableMap.Builder<String, String> fresh = ImmutableMap.builder();
        boolean unchanged = true;
        for (Map.Entry<String, String> source : DISCUSSION_SOURCES.entrySet()) {
            Optional<String> stored = Optional.ofNullable(etags.get(source.getKey()));
            RestClient.RequestHeadersSpec<?> request = get(gitHubRestClient, pat, uri -> uri.path(source.getValue())
                    .queryParam("per_page", GitHubPaginator.PAGE_SIZE)
                    .build(pr.owner(), pr.repo(), pr.number()));
            stored.ifPresent(etag -> request.header(HttpHeaders.IF_NONE_MATCH, etag));
            ResponseEntity<JsonNode> response = translating(() -> request.retrieve().toEntity(JsonNode.class));
            boolean notModified = stored.isPresent() && response.getStatusCode() == HttpStatus.NOT_MODIFIED;
            boolean roomLeft = notModified || Optional.ofNullable(response.getBody()).filter(body -> !body.isArray() || body.size() < GitHubPaginator.PAGE_SIZE).isPresent();
            unchanged &= notModified;
            Optional.ofNullable(response.getHeaders().getETag()).or(() -> notModified ? stored : Optional.empty())
                    .filter(etag -> roomLeft)
                    .ifPresent(etag -> fresh.put(source.getKey(), etag));
        }
        return new DiscussionProbe(unchanged, fresh.buildOrThrow());
    }

    public List<PrReaction> fetchPrDescriptionReactions(String pat, PullRequestRef pr)
    {
        GitHubIssueReactionsResponse response = translating(() -> get(gitHubRestClient, pat, "/repos/{owner}/{repo}/issues/{number}", pr.owner(), pr.repo(), pr.number())
                .retrieve()
                .body(GitHubIssueReactionsResponse.class));
        if (response == null || toReactions(response.reactions(), null).isEmpty()) {
            return ImmutableList.of();
        }
        Map<String, Set<String>> viewerReactions = fetchViewerReactions(pat, Optional.ofNullable(response.nodeId()).stream().toList());
        return toReactions(response.reactions(), viewerReactions.get(response.nodeId()));
    }

    public List<PrReviewState> fetchPrReviews(String pat, PullRequestRef pr) {
        return paginator.paginate(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/reviews",
                        new ParameterizedTypeReference<List<GitHubReview>>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number()).stream()
                .filter(r -> r.user() != null)
                .map(GitHubReview::toReviewState)
                .collect(toImmutableList());
    }

    /**
     * Re-reads the default diff only when GitHub says it changed. {@code stored}
     * is the copy the caller already holds; its size decides whether the ETag
     * can be trusted, since the ETag only covers the first page.
     */
    public ConditionalList<DiffFile> fetchPrDiffFiles(String pat, PullRequestRef pr, List<DiffFile> stored, Optional<String> etag)
    {
        return translating(() -> paginator.paginateConditional(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/files",
                        new ParameterizedTypeReference<List<GitHubPullRequestFileWithPatch>>() {},
                        etagCoveringOnePage(stored, etag),
                        pr.owner(),
                        pr.repo(),
                        pr.number())
                .map(GitHubPullRequestReadClient::toDiffFiles));
    }

    /**
     * A stored ETag comes from page one alone. Withhold it once the stored copy
     * needs a second page, or a change past page one leaves page one
     * byte-identical and the stale copy is kept forever.
     */
    private static Optional<String> etagCoveringOnePage(List<?> stored, Optional<String> etag)
    {
        return stored.size() <= GitHubPaginator.PAGE_SIZE ? etag : Optional.empty();
    }

    public List<DiffFile> fetchPrDiffFiles(String pat, PullRequestRef pr, String baseSha, String headSha)
    {
        requirePat(pat);
        if (baseSha == null || baseSha.isBlank() || headSha == null || headSha.isBlank()) {
            return fetchPrDiffFiles(pat, pr, ImmutableList.of(), Optional.empty()).orElse(ImmutableList.of());
        }
        GitHubCompareResponse response = translating(() -> get(gitHubRestClient, pat, "/repos/{owner}/{repo}/compare/{base}...{head}", pr.owner(), pr.repo(), baseSha, headSha)
                .retrieve()
                .body(GitHubCompareResponse.class));
        return response == null ? ImmutableList.of() : toDiffFiles(response.files());
    }

    public ConditionalList<DiffCommit> fetchPrDiffCommits(String pat, PullRequestRef pr, List<DiffCommit> stored, Optional<String> etag)
    {
        return paginator.paginateConditional(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/commits",
                        new ParameterizedTypeReference<List<GitHubPullRequestCommit>>() {},
                        etagCoveringOnePage(stored, etag),
                        pr.owner(),
                        pr.repo(),
                        pr.number())
                .map(GitHubPullRequestReadClient::toDiffCommits);
    }

    private static List<DiffCommit> toDiffCommits(List<GitHubPullRequestCommit> commits)
    {
        return commits.stream()
                .map(commit -> {
                    GitHubPullRequestCommit.Author author = Optional.ofNullable(commit.commit())
                            .map(GitHubPullRequestCommit.Commit::author)
                            .orElse(null);
                    return new DiffCommit(
                            commit.sha(),
                            Optional.ofNullable(commit.parents()).orElse(ImmutableList.of()).stream()
                                    .findFirst()
                                    .map(GitHubPullRequestCommit.Parent::sha)
                                    .orElse(null),
                            Optional.ofNullable(commit.commit()).map(GitHubPullRequestCommit.Commit::message).orElse(null),
                            Optional.ofNullable(commit.author()).map(GitHubUser::login)
                                    .orElseGet(() -> Optional.ofNullable(author)
                                            .map(GitHubPullRequestCommit.Author::name)
                                            .orElse(null)),
                            Optional.ofNullable(author).map(GitHubPullRequestCommit.Author::date).orElse(null));
                })
                .collect(toImmutableList());
    }

    public DiffFileContent fetchPrDiffFileContent(String pat, PullRequestRef pr, String path, String baseSha, String headSha)
    {
        requirePat(pat);
        checkText(path, "path");
        return new DiffFileContent(fetchRepositoryFile(pat, pr, path, baseSha), fetchRepositoryFile(pat, pr, path, headSha));
    }

    private String fetchRepositoryFile(String pat, PullRequestRef pr, String path, String ref)
    {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        try {
            return get(gitHubRestClient, pat, repositoryFileUri(RepoRef.of(pr.owner(), pr.repo()), path, ref))
                    .header("Accept", "application/vnd.github.raw+json")
                    .retrieve()
                    .body(String.class);
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw toReadableException(e);
        }
    }

    private static URI repositoryFileUri(RepoRef repository, String path, String ref)
    {
        return UriComponentsBuilder.newInstance()
                .pathSegment("repos", repository.owner(), repository.repo(), "contents")
                .pathSegment(path.split("/", -1))
                .queryParam("ref", ref)
                .build()
                .encode()
                .toUri();
    }

    public Optional<List<String>> fetchRepositoryFileLines(String pat, RepoRef repository, String path, String ref, int startLine, int endLine)
    {
        requirePat(pat);
        requireNonNull(repository, "repository is null");
        checkText(path, "path");
        checkText(ref, "ref");
        if (startLine <= 0 || endLine < startLine) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid code line range");
        }
        return get(gitHubRestClient, pat, repositoryFileUri(repository, path, ref))
                .header("Accept", "application/vnd.github.raw+json")
                .exchange((request, response) -> {
                    if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                        return Optional.empty();
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new ResponseStatusException(response.getStatusCode(), "GitHub repository file request failed");
                    }
                    return Optional.of(readCodeLines(response.getBody(), startLine, endLine));
                });
    }

    private static List<String> readCodeLines(InputStream input, int startLine, int endLine)
            throws IOException
    {
        byte[] bytes = input.readNBytes(MAX_CODE_SNIPPET_BYTES + 1);
        boolean truncated = bytes.length > MAX_CODE_SNIPPET_BYTES;
        String content = new String(bytes, 0, Math.min(bytes.length, MAX_CODE_SNIPPET_BYTES), StandardCharsets.UTF_8);
        if (content.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "repository file is not text");
        }
        if (truncated) {
            int finalLineBreak = Math.max(content.lastIndexOf('\n'), content.lastIndexOf('\r'));
            content = finalLineBreak < 0 ? "" : content.substring(0, finalLineBreak + 1);
        }
        long requestedLines = (long) endLine - startLine + 1;
        List<String> lines = content.lines()
                .skip((long) startLine - 1)
                .limit(requestedLines)
                .collect(toImmutableList());
        if (lines.size() == requestedLines) {
            return lines;
        }
        if (truncated) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "code line range exceeds the snippet read limit");
        }
        return ImmutableList.of();
    }

    private static List<DiffFile> toDiffFiles(List<GitHubPullRequestFileWithPatch> files)
    {
        return Optional.ofNullable(files).orElse(ImmutableList.of()).stream()
                .map(f -> new DiffFile(f.filename(), f.status(), f.additions(), f.deletions(), f.patch()))
                .collect(toImmutableList());
    }

    public List<PrTimelineEvent> fetchPrTimeline(String pat, PullRequestRef pr) {
        // GitHub returns timeline events oldest-first, capped at per_page=100
        // per request. Long-lived PRs blow past 100 events easily (force
        // pushes + reviews + comments add up), and without pagination the
        // newest activity gets silently dropped — see #27922 where replies
        // continued weeks beyond the first page. Walk pages until we get a
        // short page or hit the safety cap.
        List<GitHubTimelineEvent> events = paginator
                .paginate(pat, "/repos/{owner}/{repo}/issues/{number}/timeline", new ParameterizedTypeReference<List<GitHubTimelineEvent>>() {}, pr.owner(), pr.repo(), pr.number());
        Map<String, PrMergeQueueRemoval> removals = new GitHubMergeQueueTimeline(graphQlRestClient).read(pat, events.stream()
                .filter(event -> "removed_from_merge_queue".equals(event.event()))
                .map(GitHubTimelineEvent::nodeId).filter(Objects::nonNull).distinct().toList());
        List<PrTimelineEvent> timeline = events.stream()
                .map(event -> {
                    PrTimelineEvent mapped = toTimelineEvent(event, pr.owner());
                    if (!"removed_from_merge_queue".equals(event.event())) {
                        return mapped;
                    }
                    return new PrTimelineEvent(mapped.githubId(), mapped.event(), mapped.actor(), mapped.timestamp(), mapped.body(),
                            PrTimelinePayload.builder(mapped.payload()).mergeQueueRemoval(removals.get(event.nodeId())).build(), mapped.localEventKey());
                })
                .collect(toImmutableList());
        if (timeline.stream().noneMatch(event -> "reviewed".equals(event.event()))) {
            return timeline;
        }
        try {
            Map<Long, List<PrReaction>> reactions = fetchReviewReactions(pat, pr);
            return timeline.stream()
                    .map(event -> event.githubId() == null
                            ? event
                            : withReviewReactions(event, reactions.get(event.githubId())))
                    .collect(toImmutableList());
        }
        catch (RestClientResponseException ignored) {
            return timeline;
        }
    }

    private Map<Long, List<PrReaction>> fetchReviewReactions(String pat, PullRequestRef pr)
    {
        String query = """
                query($owner:String!,$repo:String!,$number:Int!,$after:String) {
                  rateLimit { cost }
                  repository(owner:$owner,name:$repo) {
                    pullRequest(number:$number) {
                      reviews(first:100,after:$after) {
                        nodes { databaseId reactionGroups { content users { totalCount } viewerHasReacted } }
                        pageInfo { hasNextPage endCursor }
                      }
                    }
                  }
                }
                """;
        Map<Long, List<PrReaction>> out = new LinkedHashMap<>();
        forEachGraphQlPage(pat, pr, query, GitHubReviewReactionsResponse.class, response -> {
            GitHubReviewReactionsResponse.Reviews reviews = Optional.ofNullable(response)
                    .map(GitHubReviewReactionsResponse::data)
                    .map(GitHubReviewReactionsResponse.Data::repository)
                    .map(GitHubReviewReactionsResponse.Repository::pullRequest)
                    .map(GitHubReviewReactionsResponse.PullRequest::reviews)
                    .orElse(null);
            if (reviews == null) {
                return null;
            }
            for (GitHubReviewReactionsResponse.Review review : Optional.ofNullable(reviews.nodes()).orElse(ImmutableList.of())) {
                if (review.databaseId() == null) {
                    continue;
                }
                List<PrReaction> counts = Optional.ofNullable(review.reactionGroups()).orElse(ImmutableList.of()).stream()
                        .filter(group -> group.users() != null && group.users().totalCount() > 0)
                        .map(group -> new PrReaction(reactionContent(group.content()), group.users().totalCount(), group.viewerHasReacted()))
                        .collect(toImmutableList());
                if (!counts.isEmpty()) {
                    out.put(review.databaseId(), counts);
                }
            }
            return reviews.pageInfo();
        });
        return ImmutableMap.copyOf(out);
    }

    /**
     * Walks a cursor-paginated GraphQL connection for one pull request:
     * posts {@code query} with {owner, repo, number, after} variables, hands
     * each response to {@code page}, and follows {@code endCursor} until the
     * last page or a 50-page safety cap. {@code page} returns the
     * connection's page info, or null to stop. GitHub errors propagate raw
     * so each caller keeps its own translation or fallback policy.
     */
    private <R> void forEachGraphQlPage(String pat, PullRequestRef pr, String query, Class<R> responseType, Function<R, GitHubReviewThreadsResponse.PageInfo> page)
    {
        String cursor = null;
        for (int i = 0; i < 50; i++) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("owner", pr.owner());
            variables.put("repo", pr.repo());
            variables.put("number", pr.number());
            variables.put("after", cursor);
            R response = graphql(graphQlRestClient, pat, query, variables)
                    .body(responseType);
            GitHubReviewThreadsResponse.PageInfo pageInfo = page.apply(response);
            if (pageInfo == null || !pageInfo.hasNextPage() || pageInfo.endCursor() == null) {
                break;
            }
            cursor = pageInfo.endCursor();
        }
    }

    private Map<String, Set<String>> fetchViewerReactions(String pat, List<String> nodeIds)
    {
        List<String> ids = nodeIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .collect(toImmutableList());
        if (ids.isEmpty()) {
            return ImmutableMap.of();
        }
        String query = """
                query($ids:[ID!]!) {
                  rateLimit { cost }
                  nodes(ids:$ids) {
                    id
                    ... on Reactable { reactionGroups { content viewerHasReacted } }
                  }
                }
                """;
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (int offset = 0; offset < ids.size(); offset += 100) {
            List<String> batch = ImmutableList.copyOf(ids.subList(offset, Math.min(offset + 100, ids.size())));
            ViewerReactionsResponse response;
            try {
                response = graphql(graphQlRestClient, pat, query, ImmutableMap.of("ids", batch))
                        .body(ViewerReactionsResponse.class);
            }
            catch (RestClientException ignored) {
                continue;
            }
            List<ViewerReactionNode> nodes = Optional.ofNullable(response)
                    .map(ViewerReactionsResponse::data)
                    .map(ViewerReactionsData::nodes)
                    .orElse(ImmutableList.of());
            for (ViewerReactionNode node : nodes) {
                if (node == null || node.id() == null || node.reactionGroups() == null
                        || node.reactionGroups().stream().anyMatch(group -> group == null || group.viewerHasReacted() == null)) {
                    continue;
                }
                Set<String> selected = node.reactionGroups().stream()
                        .filter(group -> Boolean.TRUE.equals(group.viewerHasReacted()))
                        .map(group -> reactionContent(group.content()))
                        .filter(content -> !content.isBlank())
                        .collect(ImmutableSet.toImmutableSet());
                out.put(node.id(), selected);
            }
        }
        return ImmutableMap.copyOf(out);
    }

    private static PrTimelineEvent withReviewReactions(PrTimelineEvent event, List<PrReaction> reactions)
    {
        if (reactions == null || reactions.isEmpty()) {
            return event;
        }
        return new PrTimelineEvent(
                event.githubId(),
                event.event(),
                event.actor(),
                event.timestamp(),
                event.body(),
                PrTimelinePayload.builder(event.payload()).reactions(reactions).build(),
                event.localEventKey());
    }

    private static String reactionContent(String content)
    {
        return switch (Optional.ofNullable(content).orElse("")) {
            case "THUMBS_UP" -> "+1";
            case "THUMBS_DOWN" -> "-1";
            default -> content == null ? "" : content.toLowerCase(java.util.Locale.ROOT);
        };
    }

    public List<PrTimelineEvent> fetchPrIssueComments(String pat, PullRequestRef pr) {
        List<GitHubIssueComment> comments = paginator.paginate(
                pat,
                "/repos/{owner}/{repo}/issues/{number}/comments",
                new ParameterizedTypeReference<List<GitHubIssueComment>>() {},
                pr.owner(),
                pr.repo(),
                pr.number());
        Map<String, Set<String>> viewerReactions = fetchViewerReactions(
                pat,
                comments.stream().filter(comment -> !toReactions(comment.reactions(), null).isEmpty()).map(GitHubIssueComment::nodeId).toList());
        return comments.stream()
                .map(comment -> toCommentedTimelineEvent(comment, viewerReactions.get(comment.nodeId())))
                .collect(toImmutableList());
    }

    static PrTimelineEvent toCommentedTimelineEvent(GitHubIssueComment c) {
        return toCommentedTimelineEvent(c, null);
    }

    private static PrTimelineEvent toCommentedTimelineEvent(GitHubIssueComment c, Set<String> viewerReactions)
    {
        GitHubUser user = c.user();
        return new PrTimelineEvent(
                c.id(),
                "commented",
                Optional.ofNullable(user).map(GitHubUser::login).orElse(null),
                c.createdAt(),
                c.body(),
                PrTimelinePayload.builder()
                        .actorAvatarUrl(Optional.ofNullable(user).map(GitHubUser::avatarUrl).orElse(null))
                        .actorBot(user != null && "Bot".equalsIgnoreCase(user.type()))
                        .htmlUrl(c.htmlUrl())
                        .reactions(toReactions(c.reactions(), viewerReactions))
                        .build(),
                Optional.empty());
    }

    private static PrTimelineEvent toTimelineEvent(GitHubTimelineEvent event, String owner)
    {
        String type = Optional.ofNullable(event.event()).orElse("");
        String actor = switch (type) {
            case "committed" -> Optional.ofNullable(event.author())
                    .map(GitHubTimelineEvent.Author::name)
                    .orElse(null);
            case "reviewed" -> Optional.ofNullable(event.user())
                    .map(GitHubUser::login)
                    .orElse(null);
            default -> Optional.ofNullable(event.actor())
                    .map(GitHubUser::login)
                    .orElseGet(() -> Optional.ofNullable(event.user())
                            .map(GitHubUser::login)
                            .orElse(null));
        };
        // GitHub's committed timeline row has no created_at; committer time keeps rewritten commits near their push.
        Instant timestamp = switch (type) {
            case "committed" -> Optional.ofNullable(event.committer())
                    .map(GitHubTimelineEvent.Author::date)
                    .or(() -> Optional.ofNullable(event.author()).map(GitHubTimelineEvent.Author::date))
                    .orElse(null);
            case "reviewed" -> event.submittedAt();
            default -> event.createdAt();
        };
        String actorAvatarUrl = Optional.ofNullable(event.actor())
                .map(GitHubUser::avatarUrl)
                .orElseGet(() -> Optional.ofNullable(event.user())
                        .map(GitHubUser::avatarUrl)
                        .orElse(null));
        boolean actorBot = Optional.ofNullable(event.actor())
                .map(GitHubUser::type)
                .or(() -> Optional.ofNullable(event.user()).map(GitHubUser::type))
                .map("Bot"::equalsIgnoreCase)
                .orElse(false);
        GitHubTimelineEvent.Issue source = Optional.ofNullable(event.source())
                .map(GitHubTimelineEvent.Source::issue)
                .orElse(null);
        return new PrTimelineEvent(
                event.id(),
                event.event(),
                actor,
                timestamp,
                event.body(),
                PrTimelinePayload.builder()
                        .actorAvatarUrl(actorAvatarUrl)
                        .actorBot(actorBot)
                        .state(event.state())
                        .htmlUrl(event.htmlUrl())
                        .requestedReviewer(Optional.ofNullable(event.requestedReviewer()).map(GitHubUser::login)
                                .or(() -> Optional.ofNullable(event.requestedTeam()).map(GitHubTimelineEvent.Team::slug).filter(slug -> !slug.isBlank()).map(slug -> owner + "/" + slug))
                                .orElse(null))
                        .assignee(Optional.ofNullable(event.assignee()).map(GitHubUser::login).orElse(null))
                        .label(Optional.ofNullable(event.label()).map(GitHubTimelineEvent.Label::name).orElse(null))
                        .labelColor(Optional.ofNullable(event.label()).map(GitHubTimelineEvent.Label::color).orElse(null))
                        .renameFrom(Optional.ofNullable(event.rename()).map(GitHubTimelineEvent.Rename::from).orElse(null))
                        .renameTo(Optional.ofNullable(event.rename()).map(GitHubTimelineEvent.Rename::to).orElse(null))
                        .commitSha(event.sha())
                        .commitMessage(event.message())
                        .sourceTitle(source == null ? null : source.title())
                        .sourceUrl(source == null ? null : source.htmlUrl())
                        .sourceNumber(source == null ? null : source.number())
                        .milestoneTitle(Optional.ofNullable(event.milestone()).map(GitHubTimelineEvent.Milestone::title).orElse(null))
                        .reactions(toReactions(event.reactions(), null))
                        .build(),
                Optional.empty());
    }

    public List<PrTimelineEvent> fetchPrCommits(String pat, PullRequestRef pr)
    {
        return paginator.paginate(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/commits",
                        new ParameterizedTypeReference<List<GitHubPullRequestCommit>>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number()).stream()
                .map(GitHubPullRequestReadClient::toCommittedTimelineEvent)
                .collect(toImmutableList());
    }

    private static PrTimelineEvent toCommittedTimelineEvent(GitHubPullRequestCommit commit)
    {
        GitHubUser user = commit.author();
        GitHubPullRequestCommit.Commit details = commit.commit();
        GitHubPullRequestCommit.Author author = Optional.ofNullable(details)
                .map(GitHubPullRequestCommit.Commit::author)
                .orElse(null);
        String actor = user == null ? Optional.ofNullable(author).map(GitHubPullRequestCommit.Author::name).orElse(null) : user.login();
        return new PrTimelineEvent(
                null,
                "committed",
                actor,
                Optional.ofNullable(details)
                        .map(GitHubPullRequestCommit.Commit::committer)
                        .map(GitHubPullRequestCommit.Author::date)
                        .or(() -> Optional.ofNullable(author).map(GitHubPullRequestCommit.Author::date))
                        .orElse(null),
                null,
                PrTimelinePayload.builder()
                        .actorAvatarUrl(user == null ? null : user.avatarUrl())
                        .actorBot(user != null && "Bot".equalsIgnoreCase(user.type()))
                        .htmlUrl(commit.htmlUrl())
                        .commitSha(commit.sha())
                        .commitMessage(Optional.ofNullable(details).map(GitHubPullRequestCommit.Commit::message).orElse(null))
                        .build(),
                Optional.empty());
    }

    public List<PrTimelineEvent> fetchPrReviewThreads(String pat, PullRequestRef pr)
    {
        List<GitHubReviewComment> comments = paginator.paginate(
                pat,
                "/repos/{owner}/{repo}/pulls/{number}/comments",
                new ParameterizedTypeReference<List<GitHubReviewComment>>() {},
                pr.owner(),
                pr.repo(),
                pr.number());
        if (comments.isEmpty()) {
            return ImmutableList.of();
        }
        Map<String, Set<String>> viewerReactions = fetchViewerReactions(
                pat,
                comments.stream().filter(comment -> !toReactions(comment.reactions(), null).isEmpty()).map(GitHubReviewComment::nodeId).toList());
        return toReviewThreadEvents(comments, fetchReviewThreadsByRoot(pat, pr), viewerReactions);
    }

    /**
     * The inline comments one review was submitted with. Submitting a review
     * returns the review alone, so the comments it carried have to be read
     * back. That REST endpoint can omit line and side fields, so thread details
     * supply the location and outdated status just as they do during refresh.
     */
    public List<PrTimelineEvent> fetchReviewComments(String pat, PullRequestRef pr, long reviewId)
    {
        return toReviewThreadEvents(
                paginator.paginate(
                        pat,
                        "/repos/{owner}/{repo}/pulls/{number}/reviews/{reviewId}/comments",
                        new ParameterizedTypeReference<List<GitHubReviewComment>>() {},
                        pr.owner(),
                        pr.repo(),
                        pr.number(),
                        reviewId),
                fetchReviewThreadsByRoot(pat, pr),
                ImmutableMap.of());
    }

    private static List<PrTimelineEvent> toReviewThreadEvents(
            List<GitHubReviewComment> comments,
            Map<Long, GitHubReviewThreadsResponse.Thread> threadsByRoot,
            Map<String, Set<String>> viewerReactions)
    {
        if (comments.isEmpty()) {
            return ImmutableList.of();
        }
        Map<Long, Long> roots = new LinkedHashMap<>();
        Map<Long, List<GitHubReviewComment>> grouped = new LinkedHashMap<>();
        comments.stream()
                .sorted(Comparator.comparing(comment -> Optional.ofNullable(comment.createdAt()).orElse(Instant.EPOCH)))
                .forEach(comment -> {
                    long rootId = comment.inReplyToId() == null ? comment.id() : roots.getOrDefault(comment.inReplyToId(), comment.inReplyToId());
                    roots.put(comment.id(), rootId);
                    grouped.computeIfAbsent(rootId, ignored -> new ArrayList<>()).add(comment);
                });
        return grouped.values().stream()
                .map(thread -> toReviewThreadEvent(thread, threadsByRoot.get(thread.getFirst().id()), viewerReactions))
                .sorted(Comparator.comparing(event -> Optional.ofNullable(event.timestamp()).orElse(Instant.EPOCH)))
                .collect(toImmutableList());
    }

    private Map<Long, GitHubReviewThreadsResponse.Thread> fetchReviewThreadsByRoot(String pat, PullRequestRef pr)
    {
        String query = """
                query($owner:String!,$repo:String!,$number:Int!,$after:String) {
                  rateLimit { cost }
                  repository(owner:$owner,name:$repo) {
                    pullRequest(number:$number) {
                      reviewThreads(first:100,after:$after) {
                        nodes { id isResolved isOutdated path line originalLine startLine originalStartLine diffSide
                          comments(first:1) { nodes { databaseId } }
                        }
                        pageInfo { hasNextPage endCursor }
                      }
                    }
                  }
                }
                """;
        return translating(() -> {
            Map<Long, GitHubReviewThreadsResponse.Thread> threadsByRoot = new LinkedHashMap<>();
            forEachGraphQlPage(pat, pr, query, GitHubReviewThreadsResponse.class, response -> {
                GitHubReviewThreadsResponse.ReviewThreads threads = Optional.ofNullable(response)
                        .map(GitHubReviewThreadsResponse::data)
                        .map(GitHubReviewThreadsResponse.Data::repository)
                        .map(GitHubReviewThreadsResponse.Repository::pullRequest)
                        .map(GitHubReviewThreadsResponse.PullRequest::reviewThreads)
                        .orElse(null);
                if (threads == null) {
                    return null;
                }
                for (GitHubReviewThreadsResponse.Thread thread : Optional.ofNullable(threads.nodes()).orElse(ImmutableList.of())) {
                    Long rootId = Optional.ofNullable(thread.comments())
                            .map(GitHubReviewThreadsResponse.Comments::nodes)
                            .orElse(ImmutableList.of()).stream()
                            .map(GitHubReviewThreadsResponse.Comment::databaseId)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                    if (rootId != null) {
                        threadsByRoot.put(rootId, thread);
                    }
                }
                return threads.pageInfo();
            });
            return ImmutableMap.copyOf(threadsByRoot);
        });
    }

    /** The latest comment of each review thread whose author can push to the repository. */
    public Set<Long> latestReviewCommentsByPushers(String pat, PullRequestRef pr)
    {
        String query = """
                query($owner:String!,$repo:String!,$number:Int!,$after:String) {
                  rateLimit { cost }
                  repository(owner:$owner,name:$repo) {
                    pullRequest(number:$number) {
                      reviewThreads(first:100,after:$after) {
                        nodes { comments(last:1) { nodes { databaseId pullRequestReview { authorCanPushToRepository } } } }
                        pageInfo { hasNextPage endCursor }
                      }
                    }
                  }
                }
                """;
        return translating(() -> {
            ImmutableSet.Builder<Long> latest = ImmutableSet.builder();
            forEachGraphQlPage(pat, pr, query, GitHubReviewThreadsResponse.class, response -> {
                GitHubReviewThreadsResponse.ReviewThreads threads = Optional.ofNullable(response)
                        .map(GitHubReviewThreadsResponse::data)
                        .map(GitHubReviewThreadsResponse.Data::repository)
                        .map(GitHubReviewThreadsResponse.Repository::pullRequest)
                        .map(GitHubReviewThreadsResponse.PullRequest::reviewThreads)
                        .orElse(null);
                if (threads == null) {
                    return null;
                }
                Optional.ofNullable(threads.nodes()).orElse(ImmutableList.of()).stream()
                        .flatMap(thread -> Optional.ofNullable(thread.comments()).map(GitHubReviewThreadsResponse.Comments::nodes).orElse(ImmutableList.of()).stream())
                        .filter(comment -> comment.databaseId() != null && comment.pullRequestReview() != null && comment.pullRequestReview().authorCanPushToRepository())
                        .forEach(comment -> latest.add(comment.databaseId()));
                return threads.pageInfo();
            });
            return latest.build();
        });
    }

    public String findReviewThreadId(String pat, PullRequestRef pr, long rootCommentId)
    {
        GitHubReviewThreadsResponse.Thread thread = fetchReviewThreadsByRoot(pat, pr).get(rootCommentId);
        if (thread == null || thread.id() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GitHub review thread was not found");
        }
        return thread.id();
    }

    private static PrTimelineEvent toReviewThreadEvent(List<GitHubReviewComment> thread, GitHubReviewThreadsResponse.Thread details, Map<String, Set<String>> viewerReactions)
    {
        GitHubReviewComment root = thread.getFirst();
        GitHubUser user = root.user();
        List<PrTimelineComment> replies = thread.stream()
                .map(comment -> toTimelineComment(comment, viewerReactions.get(comment.nodeId())))
                .collect(toImmutableList());
        // A returned GraphQL thread owns all location fields, including explicit nulls.
        return new PrTimelineEvent(
                root.id(),
                "review_thread",
                user == null ? null : user.login(),
                root.createdAt(),
                null,
                PrTimelinePayload.builder()
                        .actorAvatarUrl(user == null ? null : user.avatarUrl())
                        .actorBot(user != null && "Bot".equalsIgnoreCase(user.type()))
                        .htmlUrl(root.htmlUrl())
                        .path(details == null ? root.path() : details.path())
                        .diffHunk(root.diffHunk())
                        .line(details == null ? root.line() : details.line())
                        .originalLine(details == null ? root.originalLine() : details.originalLine())
                        .startLine(details == null ? root.startLine() : details.startLine())
                        .originalStartLine(details == null ? root.originalStartLine() : details.originalStartLine())
                        .side(details == null ? root.side() : details.diffSide())
                        .resolved(details != null && details.isResolved())
                        .outdated(details == null ? root.line() == null : details.isOutdated())
                        .comments(replies)
                        .build(),
                Optional.empty());
    }

    static PrTimelineEvent toReviewThreadEvent(GitHubReviewComment comment)
    {
        return toReviewThreadEvent(ImmutableList.of(comment), null, ImmutableMap.of());
    }

    private static PrTimelineComment toTimelineComment(GitHubReviewComment comment, Set<String> viewerReactions)
    {
        GitHubUser user = comment.user();
        return new PrTimelineComment(
                comment.id(),
                user == null ? null : user.login(),
                user == null ? null : user.avatarUrl(),
                user != null && "Bot".equalsIgnoreCase(user.type()),
                comment.createdAt(),
                comment.body(),
                comment.htmlUrl(),
                comment.reviewId(),
                comment.inReplyToId(),
                toReactions(comment.reactions(), viewerReactions));
    }

    private static List<PrReaction> toReactions(GitHubReactions reactions, Set<String> viewerReactions)
    {
        if (reactions == null) {
            return ImmutableList.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("+1", reactions.plusOne());
        counts.put("-1", reactions.minusOne());
        counts.put("laugh", reactions.laugh());
        counts.put("hooray", reactions.hooray());
        counts.put("confused", reactions.confused());
        counts.put("heart", reactions.heart());
        counts.put("rocket", reactions.rocket());
        counts.put("eyes", reactions.eyes());
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new PrReaction(entry.getKey(), entry.getValue(), viewerReactions == null ? null : viewerReactions.contains(entry.getKey())))
                .collect(toImmutableList());
    }

    private record ViewerReactionsResponse(ViewerReactionsData data) {}

    private record ViewerReactionsData(List<ViewerReactionNode> nodes) {}

    private record ViewerReactionNode(String id, List<ViewerReactionGroup> reactionGroups) {}

    private record ViewerReactionGroup(String content, Boolean viewerHasReacted) {}

    public FetchedPullRequest getPullRequest(String pat, PullRequestRef pr) {
        return getPullRequest(pat, pr, Optional.empty()).pullRequest()
                .orElseThrow(() -> new IllegalStateException("Unconditional GitHub PR request returned no body"));
    }

    public PullRequestFetch getPullRequest(String pat, PullRequestRef pr, Optional<String> etag) {
        String fullName = pr.repoFullName();
        requireNonNull(etag, "etag is null");
        RestClient.RequestHeadersSpec<?> request = get(gitHubRestClient, pat, "/repos/{owner}/{repo}/pulls/{number}", pr.owner(), pr.repo(), pr.number());
        etag.ifPresent(value -> request.header(HttpHeaders.IF_NONE_MATCH, value));
        ResponseEntity<GitHubPullRequestDetailResponse> response = translating(() -> request.retrieve().toEntity(GitHubPullRequestDetailResponse.class));
        Optional<String> responseEtag = Optional.ofNullable(response.getHeaders().getETag()).or(() -> etag);
        if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
            return new PullRequestFetch(Optional.empty(), responseEtag);
        }
        GitHubPullRequestDetailResponse item = response.getBody();
        checkState(item != null, "Empty response from GitHub for " + fullName + "#" + pr.number());
        List<String> reviewers = Optional.ofNullable(item.requestedReviewers()).orElse(ImmutableList.of()).stream()
                .map(GitHubUser::login).filter(Objects::nonNull).collect(toImmutableList());
        return new PullRequestFetch(Optional.of(toPullRequest(item, fullName, reviewers)), responseEtag, Optional.of(toPrRawDetail(pr, item)));
    }

    private static FetchedPullRequest toPullRequest(GitHubPullRequestDetailResponse item, String fullName, List<String> reviewers)
    {
        List<GitHubPullRequestDetailResponse.Label> labels = Optional.ofNullable(item.labels()).orElse(ImmutableList.of());
        return new FetchedPullRequest(
                item.id(), fullName, item.number(), item.title(), Optional.ofNullable(item.user()).map(GitHubUser::login).orElse(null),
                item.htmlUrl(), item.createdAt(), item.updatedAt(), OTHER, labels.stream().map(GitHubPullRequestDetailResponse.Label::name).collect(toImmutableList()),
                labels.stream().filter(label -> label.name() != null && label.color() != null)
                        .collect(toImmutableMap(GitHubPullRequestDetailResponse.Label::name, GitHubPullRequestDetailResponse.Label::color, (left, right) -> left)),
                item.draft(), item.state(), item.closedAt(), item.mergedAt(), reviewers, item.mergeable(), item.mergeableState(),
                Optional.ofNullable(item.head()).map(GitHubPullRequestDetailResponse.Head::ref).orElse(null));
    }

    // ── GraphQL: review-thread resolution ────────────────────────────────

    private record GitHubAssignableUser(
            long id,
            String login,
            @JsonProperty("avatar_url") String avatarUrl,
            String type) {}

    // ── Helpers ───────────────────────────────────────────────────────────────
    static FetchedPullRequest toPullRequest(GitHubSearchResponse.Item item, PullRequest.Origin origin) {
        List<GitHubSearchResponse.Label> rawLabels = Optional.ofNullable(item.labels()).orElse(ImmutableList.of());
        List<String> labels = rawLabels.stream().map(GitHubSearchResponse.Label::name).collect(toImmutableList());
        Map<String, String> labelColors =
                rawLabels.stream()
                        .filter(l -> l.name() != null && l.color() != null)
                        .collect(toImmutableMap(GitHubSearchResponse.Label::name, GitHubSearchResponse.Label::color, (a, b) -> a));
        String author = Optional.ofNullable(item.user()).map(GitHubUser::login).orElse(null);
        return new FetchedPullRequest(
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
                // Search reports a merged PR as
                // state=closed, so merged_at has to come along or every
                // renderer downstream reads it as plainly closed.
                item.state(),
                item.closedAt(),
                Optional.ofNullable(item.pullRequest())
                        .map(GitHubSearchResponse.PullRequestLink::mergedAt)
                        .orElse(null),
                // Search responses expose no reviewers, mergeability or head
                // ref; the next list-page sync fills them in.
                ImmutableList.of(),
                null,
                null,
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

    private record GitHubCompareResponse(List<GitHubPullRequestFileWithPatch> files) {}

    /** One changed file with its unified-diff patch. */
    private record GitHubPullRequestFileWithPatch(
            String filename,
            int additions,
            int deletions,
            String status,
            String patch) {}

    private record GitHubIssueReactionsResponse(
            @JsonProperty("node_id") String nodeId,
            GitHubReactions reactions) {}

    private record GitHubPullRequestCommit(String sha, @JsonProperty("html_url") String htmlUrl, GitHubUser author, Commit commit, List<Parent> parents)
    {
        public record Commit(String message, Author author, Author committer) {}

        public record Author(String name, Instant date) {}

        public record Parent(String sha) {}
    }

    private record GitHubReviewReactionsResponse(Data data)
    {
        public record Data(Repository repository) {}

        public record Repository(PullRequest pullRequest) {}

        public record PullRequest(Reviews reviews) {}

        public record Reviews(List<Review> nodes, GitHubReviewThreadsResponse.PageInfo pageInfo) {}

        public record Review(Long databaseId, List<ReactionGroup> reactionGroups) {}

        public record ReactionGroup(String content, Users users, Boolean viewerHasReacted) {}

        public record Users(int totalCount) {}
    }

    /** GitHub timeline fields consumed by ByteQuay. */
    private record GitHubTimelineEvent(
            Long id,
            @JsonProperty("node_id") String nodeId,
            String event,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("submitted_at") Instant submittedAt,
            GitHubUser actor,
            GitHubUser user,
            @JsonProperty("requested_reviewer") GitHubUser requestedReviewer,
            @JsonProperty("requested_team") Team requestedTeam,
            GitHubUser assignee,
            String body,
            String state,
            @JsonProperty("html_url") String htmlUrl,
            Author author,
            Author committer,
            Label label,
            Rename rename,
            Milestone milestone,
            Source source,
            String sha,
            String message,
            GitHubReactions reactions)
    {
        public record Team(String slug) {}

        public record Author(String name, Instant date) {}

        public record Label(String name, String color) {}

        public record Rename(String from, String to) {}

        public record Milestone(String title) {}

        public record Source(Issue issue) {}

        public record Issue(
                int number,
                String title,
                @JsonProperty("html_url") String htmlUrl) {}
    }
}
