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

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PullRequestDetail.CiStatus;
import com.bytequay.core.domain.PullRequestRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static com.bytequay.app.repository.github.GitHubApiSupport.graphql;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Batches card facts and one activity preview without downloading full detail histories. */
@Component
public class GitHubPullRequestSummaryClient
{
    // Thirty nested summaries approach GitHub's ten-second execution deadline.
    public static final int BATCH_SIZE = 5;

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestSummaryClient.class);
    private static final String REPLY_FIELDS = """
            nodes { createdAt author { login } replyTo { id } }
            pageInfo { hasPreviousPage startCursor }
            """;
    private static final String FIELDS = """
            databaseId:fullDatabaseId number title url createdAt updatedAt closedAt mergedAt state isDraft
            author { login } repository { nameWithOwner }
            additions deletions comments { totalCount }
            headRefName headRefOid mergeable mergeStateStatus reviewDecision
            labels(first:100) { nodes { name color } pageInfo { hasNextPage } }
            reviewRequests(first:100) { nodes { requestedReviewer { __typename ... on User { login } } } pageInfo { hasNextPage } }
            viewerReviews:reviews(last:1,author:$login,states:[APPROVED,CHANGES_REQUESTED,COMMENTED,DISMISSED]) {
              nodes { databaseId:fullDatabaseId state submittedAt author { login avatarUrl } commit { oid } }
            }
            commits(last:1) { nodes { commit { committedDate } } }
            statusCheckRollup { state contexts(first:1) { checkRunCountsByState { state count } statusContextCountsByState { state count } } }
            activity:timelineItems(last:1) { updatedAt totalCount nodes {
              __typename
              ... on IssueComment { createdAt author { login } body }
              ... on PullRequestReview { createdAt author { login } body state }
              ... on ReviewRequestedEvent { createdAt actor { login } requestedReviewer { ... on User { login } } }
              ... on MergedEvent { createdAt actor { login } }
              ... on ClosedEvent { createdAt actor { login } }
              ... on ReopenedEvent { createdAt actor { login } }
              ... on HeadRefForcePushedEvent { createdAt actor { login } }
            } }
            reviewRequested:timelineItems(last:20,itemTypes:[REVIEW_REQUESTED_EVENT]) {
              nodes { ... on ReviewRequestedEvent { createdAt requestedReviewer { __typename ... on User { login } } } }
            }
            """;

    private final RestClient graphQlRestClient;
    private final Cache<ReplyKey, Boolean> authorReplies = CacheBuilder.newBuilder().maximumSize(1000).build();

    public GitHubPullRequestSummaryClient(@Qualifier("gitHubGraphQLRestClient") RestClient graphQlRestClient)
    {
        this.graphQlRestClient = requireNonNull(graphQlRestClient, "graphQlRestClient is null");
    }

    /** Missing or partially failed entries are omitted, so their existing local facts remain intact. */
    public List<GitHubPullRequestSummary> fetchSummaries(String pat, List<PullRequestRef> refs, String login)
    {
        requirePat(pat);
        requireNonNull(login, "login is null");
        checkArgument(!login.isBlank(), "login is blank");
        List<PullRequestRef> unique = ImmutableSet.copyOf(refs).asList();
        ImmutableList.Builder<GitHubPullRequestSummary> summaries = ImmutableList.builder();
        for (int offset = 0; offset < unique.size(); offset += BATCH_SIZE) {
            List<PullRequestRef> batch = unique.subList(offset, Math.min(unique.size(), offset + BATCH_SIZE));
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("login", login);
            StringBuilder query = new StringBuilder("query($login:String!");
            for (int index = 0; index < batch.size(); index++) {
                query.append(",$owner").append(index).append(":String!,$repo").append(index).append(":String!,$number").append(index).append(":Int!");
                PullRequestRef ref = batch.get(index);
                variables.put("owner" + index, ref.owner());
                variables.put("repo" + index, ref.repo());
                variables.put("number" + index, ref.number());
            }
            query.append("){ ");
            for (int index = 0; index < batch.size(); index++) {
                query.append("r").append(index).append(":repository(owner:$owner").append(index).append(",name:$repo").append(index)
                        .append("){ pullRequest(number:$number").append(index).append("){ ...Summary } } ");
            }
            query.append("} fragment Summary on PullRequest { ").append(FIELDS).append(" }");
            JsonNode response = requireBody(translating(() -> graphql(graphQlRestClient, pat, query.toString(), variables).body(JsonNode.class)),
                    "GitHub returned no pull request summaries");
            Set<String> failed = failedAliases(response);
            JsonNode data = required(response, "data");
            for (int index = 0; index < batch.size(); index++) {
                String alias = "r" + index;
                if (failed.contains(alias) || data.path(alias).isNull()) {
                    continue;
                }
                try {
                    JsonNode pullRequest = required(required(data, alias), "pullRequest");
                    summaries.add(summary(pullRequest, batch.get(index), login));
                }
                catch (ResponseStatusException failure) {
                    log.warn("Skipped incomplete GitHub PR summary for {}: {}", batch.get(index).fullName(), failure.getReason());
                }
            }
        }
        return summaries.build();
    }

    /** Reads reply metadata only when a previously handled review has new source activity. */
    public boolean hasAuthorReplyAfter(String pat, PullRequestRef ref, String viewer, String author, Instant since, String activityVersion)
    {
        requirePat(pat);
        requireNonNull(viewer, "viewer is null");
        requireNonNull(author, "author is null");
        ReplyKey key = new ReplyKey(ref, viewer.toLowerCase(Locale.ROOT), author.toLowerCase(Locale.ROOT), since, activityVersion);
        Boolean known = authorReplies.getIfPresent(key);
        if (known != null) {
            return known;
        }
        boolean found = findAuthorReply(pat, key);
        authorReplies.put(key, found);
        return found;
    }

    private boolean findAuthorReply(String pat, ReplyKey key)
    {
        String query = """
                query($owner:String!,$repo:String!,$number:Int!,$after:String) {
                  repository(owner:$owner,name:$repo) { pullRequest(number:$number) {
                    reviewThreads(first:100,after:$after) {
                      nodes { id root:comments(first:1) { nodes { author { login } } }
                        comments(last:100) { %s }
                      }
                      pageInfo { hasNextPage endCursor }
                    }
                  } }
                }
                """.formatted(REPLY_FIELDS);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("owner", key.ref().owner());
        variables.put("repo", key.ref().repo());
        variables.put("number", key.ref().number());
        variables.put("after", null);
        Set<String> cursors = new HashSet<>();
        while (true) {
            JsonNode data = strictGraphQl(pat, query, variables);
            JsonNode threads = required(required(required(data, "repository"), "pullRequest"), "reviewThreads");
            for (JsonNode thread : nodes(threads)) {
                List<JsonNode> roots = nodes(required(thread, "root"));
                if (roots.isEmpty() || !key.viewer().equalsIgnoreCase(optionalText(requiredNullable(roots.getFirst(), "author"), "login").orElse(null))) {
                    continue;
                }
                if (threadHasAuthorReply(pat, key, text(thread, "id"), required(thread, "comments"))) {
                    return true;
                }
            }
            JsonNode page = required(threads, "pageInfo");
            if (!bool(page, "hasNextPage")) {
                return false;
            }
            String cursor = text(page, "endCursor");
            if (!cursors.add(cursor)) {
                throw incomplete("Review thread pagination did not advance");
            }
            variables.put("after", cursor);
        }
    }

    private boolean threadHasAuthorReply(String pat, ReplyKey key, String threadId, JsonNode initial)
    {
        String query = """
                query($id:ID!,$before:String!) {
                  node(id:$id) { ... on PullRequestReviewThread { comments(last:100,before:$before) { %s } } }
                }
                """.formatted(REPLY_FIELDS);
        JsonNode comments = initial;
        Set<String> cursors = new HashSet<>();
        while (true) {
            for (JsonNode comment : nodes(comments)) {
                if (!requiredNullable(comment, "replyTo").isNull()
                        && key.author().equalsIgnoreCase(optionalText(requiredNullable(comment, "author"), "login").orElse(null))
                        && instant(comment, "createdAt").orElseThrow(() -> incomplete("Missing reply timestamp")).isAfter(key.since())) {
                    return true;
                }
            }
            JsonNode page = required(comments, "pageInfo");
            if (!bool(page, "hasPreviousPage")) {
                return false;
            }
            String cursor = text(page, "startCursor");
            if (!cursors.add(cursor)) {
                throw incomplete("Review comment pagination did not advance");
            }
            comments = required(required(strictGraphQl(pat, query, ImmutableMap.of("id", threadId, "before", cursor)), "node"), "comments");
        }
    }

    private JsonNode strictGraphQl(String pat, String query, Map<String, Object> variables)
    {
        JsonNode response = requireBody(translating(() -> graphql(graphQlRestClient, pat, query, variables).body(JsonNode.class)),
                "GitHub returned no review reply metadata");
        if (response.path("errors").size() > 0) {
            throw incomplete("Review reply metadata is unavailable");
        }
        return required(response, "data");
    }

    private static Set<String> failedAliases(JsonNode response)
    {
        Set<String> failed = new HashSet<>();
        for (JsonNode error : response.path("errors")) {
            if ("RATE_LIMITED".equals(error.path("type").asText())) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "GitHub GraphQL rate limit reached");
            }
            String alias = error.path("path").path(0).textValue();
            if (alias == null) {
                throw incomplete("GraphQL request failed");
            }
            failed.add(alias);
        }
        return failed;
    }

    private static GitHubPullRequestSummary summary(JsonNode row, PullRequestRef expected, String login)
    {
        int number = count(row, "number");
        String repository = text(required(row, "repository"), "nameWithOwner");
        if (number != expected.number() || !repository.equalsIgnoreCase(expected.repoFullName())) {
            throw incomplete("Pull request identity differs from the requested reference");
        }
        List<JsonNode> labels = completeNodes(row, "labels");
        List<String> reviewers = completeNodes(row, "reviewRequests").stream()
                .map(request -> required(request, "requestedReviewer"))
                .filter(reviewer -> "User".equals(text(reviewer, "__typename")))
                .map(reviewer -> text(reviewer, "login"))
                .collect(toImmutableList());
        String state = text(row, "state");
        if (!ImmutableSet.of("OPEN", "CLOSED", "MERGED").contains(state)) {
            throw incomplete("Unknown pull request state");
        }
        Boolean mergeable = switch (text(row, "mergeable")) {
            case "MERGEABLE" -> true;
            case "CONFLICTING" -> false;
            case "UNKNOWN" -> null;
            default -> throw incomplete("Unknown mergeability");
        };
        ImmutableMap.Builder<String, String> colors = ImmutableMap.builder();
        labels.forEach(label -> colors.put(text(label, "name"), text(label, "color")));
        FetchedPullRequest pullRequest = new FetchedPullRequest(
                databaseId(row), repository, number, text(row, "title"), optionalText(requiredNullable(row, "author"), "login").orElse(null),
                text(row, "url"), instant(row, "createdAt").orElseThrow(() -> incomplete("Missing createdAt")),
                instant(row, "updatedAt").orElseThrow(() -> incomplete("Missing updatedAt")), OTHER,
                labels.stream().map(label -> text(label, "name")).collect(toImmutableList()), colors.buildOrThrow(), bool(row, "isDraft"),
                state.toLowerCase(Locale.ROOT), instant(row, "closedAt").orElse(null), instant(row, "mergedAt").orElse(null), reviewers,
                mergeable, text(row, "mergeStateStatus").toLowerCase(Locale.ROOT), text(row, "headRefName"))
                .withViewerOrigin(login);
        List<JsonNode> commits = nodes(required(row, "commits"));
        Optional<Instant> headCommittedAt = commits.isEmpty() ? Optional.empty() : instant(required(commits.getLast(), "commit"), "committedDate");
        List<JsonNode> reviews = nodes(required(row, "viewerReviews"));
        Optional<PrReviewState> viewerReview = reviews.isEmpty() ? Optional.empty() : Optional.of(review(reviews.getLast(), login));
        // ponytail: retain the latest 20 review-request events; paginate only if older request evidence becomes a product requirement.
        Optional<Instant> requestedAt = nodes(required(row, "reviewRequested")).stream()
                .filter(event -> login.equalsIgnoreCase(event.path("requestedReviewer").path("login").textValue()))
                .map(event -> instant(event, "createdAt").orElseThrow(() -> incomplete("Missing review request timestamp")))
                .max(Instant::compareTo);
        JsonNode rollup = requiredNullable(row, "statusCheckRollup");
        JsonNode activity = required(row, "activity");
        Optional<String> decision = optionalText(row, "reviewDecision");
        if (decision.isPresent() && !ImmutableSet.of("APPROVED", "CHANGES_REQUESTED", "REVIEW_REQUIRED").contains(decision.orElseThrow())) {
            throw incomplete("Unknown review decision");
        }
        // Full-detail review projection represents no active verdict as an absent decision.
        decision = decision.filter(value -> !"REVIEW_REQUIRED".equals(value));
        String headSha = text(row, "headRefOid");
        // ponytail: state counts detect check progress, but not checks swapping states; fetch individual checks only when the PR is opened.
        String activityVersion = Hashing.sha256().hashString(
                ImmutableList.of(pullRequest.updatedAt(), pullRequest.title(), pullRequest.state(), pullRequest.draft(),
                        labels.stream().map(label -> text(label, "name")).sorted().collect(toImmutableList()),
                        headSha, headCommittedAt, decision, viewerReview, requestedAt,
                        Optional.ofNullable(mergeable), pullRequest.mergeableState(), reviewers.stream().sorted().collect(toImmutableList()),
                        instant(activity, "updatedAt").orElseThrow(() -> incomplete("Missing activity timestamp")), count(activity, "totalCount"), checkVersion(rollup))
                        .toString(), StandardCharsets.UTF_8).toString();
        return new GitHubPullRequestSummary(pullRequest, ciStatus(rollup), count(row, "additions"), count(row, "deletions"),
                count(required(row, "comments"), "totalCount"), headSha, headCommittedAt, decision, viewerReview, requestedAt, activityVersion,
                instant(activity, "updatedAt").orElseThrow(), latestActivity(activity));
    }

    private static Optional<PrActivitySummary> latestActivity(JsonNode connection)
    {
        JsonNode nodes = connection.path("nodes");
        if (!nodes.isArray() || nodes.isEmpty()) {
            return Optional.empty();
        }
        JsonNode node = nodes.get(nodes.size() - 1);
        String event = switch (node.path("__typename").asText()) {
            case "IssueComment" -> "commented";
            case "PullRequestReview" -> "reviewed";
            case "ReviewRequestedEvent" -> "review_requested";
            case "MergedEvent" -> "merged";
            case "ClosedEvent" -> "closed";
            case "ReopenedEvent" -> "reopened";
            case "HeadRefForcePushedEvent" -> "head_ref_force_pushed";
            default -> null;
        };
        if (event == null) {
            return Optional.empty();
        }
        return instant(node, "createdAt").map(at -> new PrActivitySummary(event,
                activityText(node.path("author"), "login").or(() -> activityText(node.path("actor"), "login")), at,
                activityText(node, "state"), Optional.empty(), activityText(node.path("requestedReviewer"), "login"),
                PrActivitySummary.excerpt(node.path("body").asText(null)), Optional.empty(), OptionalInt.empty()));
    }

    private static Optional<String> activityText(JsonNode node, String field)
    {
        return Optional.ofNullable(node.path(field).textValue()).filter(value -> !value.isBlank());
    }

    private static PrReviewState review(JsonNode review, String login)
    {
        JsonNode author = required(review, "author");
        if (!login.equalsIgnoreCase(text(author, "login"))) {
            throw incomplete("Review author differs from the viewer");
        }
        String state = text(review, "state");
        if (!ImmutableSet.of("APPROVED", "CHANGES_REQUESTED", "COMMENTED", "DISMISSED").contains(state)) {
            throw incomplete("Unknown viewer review state");
        }
        return new PrReviewState(databaseId(review), login, text(author, "avatarUrl"), false, state,
                instant(review, "submittedAt").orElseThrow(() -> incomplete("Missing review timestamp")), null, null,
                text(required(review, "commit"), "oid"));
    }

    private static CiStatus ciStatus(JsonNode rollup)
    {
        if (rollup.isNull()) {
            return CiStatus.NONE;
        }
        return switch (text(rollup, "state")) {
            case "ERROR", "FAILURE" -> CiStatus.FAILING;
            case "EXPECTED", "PENDING" -> CiStatus.PENDING;
            case "SUCCESS" -> CiStatus.PASSING;
            default -> throw incomplete("Unknown check status");
        };
    }

    private static String checkVersion(JsonNode rollup)
    {
        if (rollup.isNull()) {
            return "none";
        }
        JsonNode contexts = required(rollup, "contexts");
        List<String> states = new ArrayList<>();
        for (String source : ImmutableList.of("checkRunCountsByState", "statusContextCountsByState")) {
            JsonNode counts = required(contexts, source);
            if (!counts.isArray()) {
                throw incomplete("Invalid check counts");
            }
            counts.forEach(value -> states.add(source + ":" + text(value, "state") + ":" + count(value, "count")));
        }
        states.sort(String::compareTo);
        return text(rollup, "state") + states;
    }

    private static List<JsonNode> completeNodes(JsonNode row, String field)
    {
        JsonNode connection = required(row, field);
        if (bool(required(connection, "pageInfo"), "hasNextPage")) {
            throw incomplete("Incomplete " + field);
        }
        return nodes(connection);
    }

    private static List<JsonNode> nodes(JsonNode connection)
    {
        JsonNode values = required(connection, "nodes");
        if (!values.isArray()) {
            throw incomplete("Invalid connection nodes");
        }
        ImmutableList.Builder<JsonNode> nodes = ImmutableList.builder();
        values.forEach(value -> {
            if (!value.isObject()) {
                throw incomplete("Invalid connection node");
            }
            nodes.add(value);
        });
        return nodes.build();
    }

    private static long databaseId(JsonNode row)
    {
        JsonNode value = required(row, "databaseId");
        try {
            long id = Long.parseLong(value.asText());
            if (id <= 0) {
                throw incomplete("Invalid database id");
            }
            return id;
        }
        catch (NumberFormatException ignored) {
            throw incomplete("Invalid database id");
        }
    }

    private static int count(JsonNode row, String field)
    {
        JsonNode value = required(row, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw incomplete("Invalid " + field);
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode row, String field)
    {
        JsonNode value = required(row, field);
        if (!value.isBoolean()) {
            throw incomplete("Invalid " + field);
        }
        return value.booleanValue();
    }

    private static Optional<Instant> instant(JsonNode row, String field)
    {
        try {
            return optionalText(row, field).map(Instant::parse);
        }
        catch (DateTimeParseException ignored) {
            throw incomplete("Invalid " + field);
        }
    }

    private static Optional<String> optionalText(JsonNode row, String field)
    {
        if (row.isNull()) {
            return Optional.empty();
        }
        return requiredNullable(row, field).isNull() ? Optional.empty() : Optional.of(text(row, field));
    }

    private static String text(JsonNode row, String field)
    {
        JsonNode value = required(row, field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw incomplete("Invalid " + field);
        }
        return value.textValue();
    }

    private static JsonNode required(JsonNode row, String field)
    {
        JsonNode value = requiredNullable(row, field);
        if (value.isNull()) {
            throw incomplete("Missing " + field);
        }
        return value;
    }

    private static JsonNode requiredNullable(JsonNode row, String field)
    {
        JsonNode value = row.path(field);
        if (value.isMissingNode()) {
            throw incomplete("Missing " + field);
        }
        return value;
    }

    private static ResponseStatusException incomplete(String reason)
    {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub pull request summary: " + reason);
    }

    private record ReplyKey(PullRequestRef ref, String viewer, String author, Instant since, String activityVersion)
    {
        private ReplyKey
        {
            requireNonNull(ref, "ref is null");
            requireNonNull(viewer, "viewer is null");
            requireNonNull(author, "author is null");
            requireNonNull(since, "since is null");
            requireNonNull(activityVersion, "activityVersion is null");
        }
    }
}
