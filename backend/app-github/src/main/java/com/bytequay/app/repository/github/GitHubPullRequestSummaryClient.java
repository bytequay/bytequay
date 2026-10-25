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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
    // GitHub computes mergeStateStatus only when one query asks for at most two pull requests; with more, it answers BLOCKED
    // or UNKNOWN for every one of them, which would keep approved pull requests out of Ready to merge.
    private static final int QUERY_SIZE = 2;

    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new Jdk8Module()).addModule(new JavaTimeModule())
            .defaultSetterInfo(JsonSetter.Value.forContentNulls(Nulls.FAIL))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .withCoercionConfig(LogicalType.Boolean, config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Textual, config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(Integer.TYPE, config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail))
            .build();
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
        for (int offset = 0; offset < unique.size(); offset += QUERY_SIZE) {
            List<PullRequestRef> batch = unique.subList(offset, Math.min(unique.size(), offset + QUERY_SIZE));
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
            query.append("){ rateLimit { cost } ");
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
                  rateLimit { cost }
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
            ThreadPage threads = read(required(required(required(data, "repository"), "pullRequest"), "reviewThreads"), ThreadPage.class);
            for (ReviewThread thread : threads.nodes()) {
                List<RootComment> roots = thread.root().nodes();
                if (roots.isEmpty() || !key.viewer().equalsIgnoreCase(roots.getFirst().author().flatMap(User::login).orElse(null))) {
                    continue;
                }
                if (threadHasAuthorReply(pat, key, text(thread.id().orElse(null)), thread.comments().orElseThrow(() -> incomplete("Missing thread comments")))) {
                    return true;
                }
            }
            NextPage page = threads.pageInfo();
            if (!page.hasNextPage()) {
                return false;
            }
            String cursor = text(page.endCursor().orElse(null));
            if (!cursors.add(cursor)) {
                throw incomplete("Review thread pagination did not advance");
            }
            variables.put("after", cursor);
        }
    }

    private boolean threadHasAuthorReply(String pat, ReplyKey key, String threadId, CommentPage initial)
    {
        String query = """
                query($id:ID!,$before:String!) {
                  rateLimit { cost }
                  node(id:$id) { ... on PullRequestReviewThread { comments(last:100,before:$before) { %s } } }
                }
                """.formatted(REPLY_FIELDS);
        CommentPage comments = initial;
        Set<String> cursors = new HashSet<>();
        while (true) {
            for (Reply comment : comments.nodes()) {
                if (comment.replyTo().isPresent()
                        && key.author().equalsIgnoreCase(comment.author().flatMap(User::login).orElse(null))
                        && comment.createdAt().isAfter(key.since())) {
                    return true;
                }
            }
            PreviousPage page = comments.pageInfo();
            if (!page.hasPreviousPage()) {
                return false;
            }
            String cursor = text(page.startCursor().orElse(null));
            if (!cursors.add(cursor)) {
                throw incomplete("Review comment pagination did not advance");
            }
            comments = read(required(required(strictGraphQl(pat, query, ImmutableMap.of("id", threadId, "before", cursor)), "node"), "comments"), CommentPage.class);
        }
    }

    private JsonNode strictGraphQl(String pat, String query, Map<String, Object> variables)
    {
        JsonNode response = requireBody(translating(() -> graphql(graphQlRestClient, pat, query, variables).body(JsonNode.class)), "GitHub returned no review reply metadata");
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

    private static GitHubPullRequestSummary summary(JsonNode value, PullRequestRef expected, String login)
    {
        Row row = read(value, Row.class);
        String repository = text(row.repository().nameWithOwner());
        if (row.number() != expected.number() || !repository.equalsIgnoreCase(expected.repoFullName())) {
            throw incomplete("Pull request identity differs from the requested reference");
        }
        List<Label> labels = completeNodes(row.labels(), "labels");
        List<String> reviewers = completeNodes(row.reviewRequests(), "reviewRequests").stream()
                .map(ReviewRequest::requestedReviewer)
                .filter(reviewer -> "User".equals(text(reviewer.type())))
                .map(reviewer -> text(reviewer.login().orElse(null)))
                .collect(toImmutableList());
        String state = text(row.state());
        if (!ImmutableSet.of("OPEN", "CLOSED", "MERGED").contains(state)) {
            throw incomplete("Unknown pull request state");
        }
        Boolean mergeable = switch (text(row.mergeable())) {
            case "MERGEABLE" -> true;
            case "CONFLICTING" -> false;
            case "UNKNOWN" -> null;
            default -> throw incomplete("Unknown mergeability");
        };
        ImmutableMap.Builder<String, String> colors = ImmutableMap.builder();
        labels.forEach(label -> colors.put(text(label.name()), text(label.color())));
        FetchedPullRequest pullRequest = new FetchedPullRequest(
                databaseId(row.databaseId()), repository, count(row.number()), text(row.title()), row.author().map(author -> text(author.login().orElse(null))).orElse(null),
                text(row.url()), row.createdAt(), row.updatedAt(), OTHER,
                labels.stream().map(label -> text(label.name())).collect(toImmutableList()), colors.buildOrThrow(), row.isDraft(),
                state.toLowerCase(Locale.ROOT), row.closedAt().orElse(null), row.mergedAt().orElse(null), reviewers,
                mergeable, text(row.mergeStateStatus()).toLowerCase(Locale.ROOT), text(row.headRefName()))
                .withViewerOrigin(login);
        List<CommitNode> commits = row.commits().nodes();
        Optional<Instant> headCommittedAt = commits.isEmpty() ? Optional.empty() : commits.getLast().commit().committedDate();
        List<Review> reviews = row.viewerReviews().nodes();
        Optional<PrReviewState> viewerReview = reviews.isEmpty() ? Optional.empty() : Optional.of(review(reviews.getLast(), login));
        // Retain the latest 20 request events; older request evidence is outside the card summary.
        Optional<Instant> requestedAt = row.reviewRequested().nodes().stream()
                .filter(event -> login.equalsIgnoreCase(event.requestedReviewer().flatMap(User::login).orElse(null)))
                .map(event -> event.createdAt().orElseThrow(() -> incomplete("Missing review request timestamp")))
                .max(Instant::compareTo);
        Optional<Rollup> rollup = row.statusCheckRollup();
        ActivityConnection activity = row.activity();
        Optional<String> decision = row.reviewDecision();
        if (decision.isPresent() && !ImmutableSet.of("APPROVED", "CHANGES_REQUESTED", "REVIEW_REQUIRED").contains(decision.orElseThrow())) {
            throw incomplete("Unknown review decision");
        }
        // Full-detail review projection represents no active verdict as an absent decision.
        decision = decision.filter(valueDecision -> !"REVIEW_REQUIRED".equals(valueDecision));
        String headSha = text(row.headRefOid());
        // Counts detect check progress; individual checks are fetched when the PR is opened.
        String activityVersion = Hashing.sha256().hashString(
                ImmutableList.of(pullRequest.updatedAt(), pullRequest.title(), pullRequest.state(), pullRequest.draft(),
                        labels.stream().map(Label::name).sorted().collect(toImmutableList()),
                        headSha, headCommittedAt, decision, viewerReview, requestedAt,
                        Optional.ofNullable(mergeable), pullRequest.mergeableState(), reviewers.stream().sorted().collect(toImmutableList()),
                        activity.updatedAt(), count(activity.totalCount()), checkVersion(rollup))
                        .toString(), StandardCharsets.UTF_8).toString();
        return new GitHubPullRequestSummary(pullRequest, ciStatus(rollup), count(row.additions()), count(row.deletions()),
                count(row.comments().totalCount()), headSha, headCommittedAt, decision, viewerReview, requestedAt, activityVersion,
                activity.updatedAt(), latestActivity(activity));
    }

    private static Optional<PrActivitySummary> latestActivity(ActivityConnection connection)
    {
        List<Activity> nodes = connection.nodes().orElse(ImmutableList.of());
        if (nodes.isEmpty()) {
            return Optional.empty();
        }
        Activity node = nodes.getLast();
        String event = switch (node.type().orElse("")) {
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
        return node.createdAt().map(at -> new PrActivitySummary(event,
                node.author().flatMap(User::login).or(() -> node.actor().flatMap(User::login)), at,
                node.state(), Optional.empty(), node.requestedReviewer().flatMap(User::login),
                PrActivitySummary.excerpt(node.body().orElse(null)), Optional.empty(), OptionalInt.empty()));
    }

    private static PrReviewState review(Review review, String login)
    {
        User author = review.author();
        if (!login.equalsIgnoreCase(text(author.login().orElse(null)))) {
            throw incomplete("Review author differs from the viewer");
        }
        String state = text(review.state());
        if (!ImmutableSet.of("APPROVED", "CHANGES_REQUESTED", "COMMENTED", "DISMISSED").contains(state)) {
            throw incomplete("Unknown viewer review state");
        }
        // GitHub nulls the commit once a force-push lets it be garbage-collected.
        return new PrReviewState(databaseId(review.databaseId()), login, text(author.avatarUrl().orElse(null)), false, state,
                review.submittedAt(), null, null, review.commit().map(commit -> text(commit.oid())).orElse(null));
    }

    private static CiStatus ciStatus(Optional<Rollup> rollup)
    {
        return rollup.map(value -> switch (text(value.state())) {
            case "ERROR", "FAILURE" -> CiStatus.FAILING;
            case "EXPECTED", "PENDING" -> CiStatus.PENDING;
            case "SUCCESS" -> CiStatus.PASSING;
            default -> throw incomplete("Unknown check status");
        }).orElse(CiStatus.NONE);
    }

    private static String checkVersion(Optional<Rollup> rollup)
    {
        if (rollup.isEmpty()) {
            return "none";
        }
        CheckCounts contexts = rollup.orElseThrow().contexts();
        List<String> states = new ArrayList<>();
        contexts.checkRunCountsByState().forEach(value -> states.add("checkRunCountsByState:" + text(value.state()) + ":" + count(value.count())));
        contexts.statusContextCountsByState().forEach(value -> states.add("statusContextCountsByState:" + text(value.state()) + ":" + count(value.count())));
        states.sort(String::compareTo);
        return text(rollup.orElseThrow().state()) + states;
    }

    private static <T> List<T> completeNodes(Connection<T> connection, String field)
    {
        if (connection.pageInfo().hasNextPage()) {
            throw incomplete("Incomplete " + field);
        }
        return connection.nodes();
    }

    private static long databaseId(long id)
    {
        if (id <= 0) {
            throw incomplete("Invalid database id");
        }
        return id;
    }

    private static int count(int value)
    {
        if (value < 0) {
            throw incomplete("Invalid count");
        }
        return value;
    }

    private static String text(String value)
    {
        if (value == null || value.isBlank()) {
            throw incomplete("Missing text");
        }
        return value;
    }

    private static <T> T read(JsonNode value, Class<T> type)
    {
        try {
            return MAPPER.treeToValue(value, type);
        }
        catch (JsonProcessingException failure) {
            throw incomplete("Invalid " + type.getSimpleName() + ": " + failure.getOriginalMessage());
        }
    }

    private static JsonNode required(JsonNode row, String field)
    {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw incomplete("Missing " + field);
        }
        return value;
    }

    private static ResponseStatusException incomplete(String reason)
    {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub pull request summary: " + reason);
    }

    private record Row(
            @JsonProperty(required = true) long databaseId, @JsonProperty(required = true) int number,
            @JsonProperty(required = true) String title, @JsonProperty(required = true) String url,
            @JsonProperty(required = true) Instant createdAt, @JsonProperty(required = true) Instant updatedAt,
            @JsonProperty(required = true) Optional<Instant> closedAt, @JsonProperty(required = true) Optional<Instant> mergedAt,
            @JsonProperty(required = true) String state, @JsonProperty(required = true) boolean isDraft,
            @JsonProperty(required = true) Optional<User> author, @JsonProperty(required = true) Repository repository,
            @JsonProperty(required = true) int additions, @JsonProperty(required = true) int deletions,
            @JsonProperty(required = true) Count comments, @JsonProperty(required = true) String headRefName,
            @JsonProperty(required = true) String headRefOid, @JsonProperty(required = true) String mergeable,
            @JsonProperty(required = true) String mergeStateStatus, @JsonProperty(required = true) Optional<String> reviewDecision,
            @JsonProperty(required = true) Connection<Label> labels, @JsonProperty(required = true) Connection<ReviewRequest> reviewRequests,
            @JsonProperty(required = true) Nodes<Review> viewerReviews, @JsonProperty(required = true) Nodes<CommitNode> commits,
            @JsonProperty(required = true) Optional<Rollup> statusCheckRollup, @JsonProperty(required = true) ActivityConnection activity,
            @JsonProperty(required = true) Nodes<Activity> reviewRequested) {}

    private record User(Optional<String> login, Optional<String> avatarUrl) {}

    private record Repository(@JsonProperty(required = true) String nameWithOwner) {}

    private record Count(@JsonProperty(required = true) int totalCount) {}

    private record Label(@JsonProperty(required = true) String name, @JsonProperty(required = true) String color) {}

    private record Reviewer(@JsonProperty(value = "__typename", required = true) String type, Optional<String> login) {}

    private record ReviewRequest(@JsonProperty(required = true) Reviewer requestedReviewer) {}

    private record Review(@JsonProperty(required = true) long databaseId, @JsonProperty(required = true) String state,
            @JsonProperty(required = true) Instant submittedAt, @JsonProperty(required = true) User author,
            @JsonProperty(required = true) Optional<Commit> commit) {}

    private record Commit(@JsonProperty(required = true) String oid) {}

    private record CommitDate(@JsonProperty(required = true) Optional<Instant> committedDate) {}

    private record CommitNode(@JsonProperty(required = true) CommitDate commit) {}

    private record Rollup(@JsonProperty(required = true) String state, @JsonProperty(required = true) CheckCounts contexts) {}

    private record CheckCounts(@JsonProperty(required = true) List<StateCount> checkRunCountsByState,
            @JsonProperty(required = true) List<StateCount> statusContextCountsByState) {}

    private record StateCount(@JsonProperty(required = true) String state, @JsonProperty(required = true) int count) {}

    private record ActivityConnection(@JsonProperty(required = true) Instant updatedAt, @JsonProperty(required = true) int totalCount,
            Optional<List<Activity>> nodes) {}

    private record Activity(@JsonProperty("__typename") Optional<String> type, Optional<Instant> createdAt,
            Optional<User> author, Optional<User> actor, Optional<String> body, Optional<String> state, Optional<User> requestedReviewer) {}

    private record Nodes<T>(@JsonProperty(required = true) List<T> nodes)
    {
        private Nodes
        {
            nodes = ImmutableList.copyOf(nodes);
        }
    }

    private record Connection<T>(@JsonProperty(required = true) List<T> nodes, @JsonProperty(required = true) NextPage pageInfo)
    {
        private Connection
        {
            nodes = ImmutableList.copyOf(nodes);
        }
    }

    private record NextPage(@JsonProperty(required = true) boolean hasNextPage, Optional<String> endCursor) {}

    private record PreviousPage(@JsonProperty(required = true) boolean hasPreviousPage, Optional<String> startCursor) {}

    private record ReviewThread(Optional<String> id, @JsonProperty(required = true) Nodes<RootComment> root,
            Optional<CommentPage> comments) {}

    private record ThreadPage(@JsonProperty(required = true) List<ReviewThread> nodes, @JsonProperty(required = true) NextPage pageInfo) {}

    private record RootComment(@JsonProperty(required = true) Optional<User> author) {}

    private record Reply(@JsonProperty(required = true) Instant createdAt, @JsonProperty(required = true) Optional<User> author,
            @JsonProperty(required = true) Optional<ReplyTo> replyTo) {}

    private record ReplyTo(@JsonProperty(required = true) String id) {}

    private record CommentPage(@JsonProperty(required = true) List<Reply> nodes, @JsonProperty(required = true) PreviousPage pageInfo) {}

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
