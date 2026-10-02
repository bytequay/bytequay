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

import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static com.bytequay.app.repository.github.GitHubApiSupport.get;
import static com.bytequay.app.repository.github.GitHubApiSupport.graphql;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Reads the closed pull requests a person wrote or reviewed, and one pull request's review discussion, for learning repository memory,
 * and a merged pull request's reviews and review threads, for replaying its review.
 */
@Component
public class GitHubHarvestClient
{
    private static final int SEARCH_RESULTS = 100;
    private static final String NOTE_FIELDS = "id body url authorAssociation author { __typename login }";
    private static final String QUERY = """
            query($owner:String!,$repo:String!,$number:Int!) {
              repository(owner:$owner,name:$repo) { pullRequest(number:$number) {
                number title url merged closedAt
                reviewThreads(first:100) { nodes { id isResolved isOutdated path comments(first:30) { nodes { %1$s createdAt } } } }
                reviews(first:100) { nodes { %1$s submittedAt } }
                comments(first:100) { nodes { %1$s createdAt } }
              } }
            }
            """.formatted(NOTE_FIELDS);
    private static final String REPLAY_QUERY = """
            query($owner:String!,$repo:String!,$number:Int!) {
              repository(owner:$owner,name:$repo) { pullRequest(number:$number) {
                number title url merged baseRefName author { __typename login } mergeCommit { oid }
                reviews(first:100) { nodes { author { __typename login } authorAssociation state submittedAt commit { oid } } }
                reviewThreads(first:100) { nodes { path diffSide line startLine originalLine originalStartLine
                  comments(first:1) { nodes { %1$s createdAt originalCommit { oid } } } } }
              } }
            }
            """.formatted(NOTE_FIELDS);

    private final RestClient gitHubRestClient;
    private final RestClient graphQlRestClient;

    public GitHubHarvestClient(RestClient gitHubRestClient, @Qualifier("gitHubGraphQLRestClient") RestClient graphQlRestClient)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.graphQlRestClient = requireNonNull(graphQlRestClient, "graphQlRestClient is null");
    }

    /** The repository's closed pull requests the person wrote or reviewed, newest close first, from GitHub search. */
    public List<ClosedPull> closedPulls(String pat, RepoRef repository, String login)
    {
        requirePat(pat);
        checkArgument(login.matches("[A-Za-z0-9-]{1,39}"), "Invalid GitHub login");
        Map<Integer, ClosedPull> pulls = new LinkedHashMap<>();
        for (String role : ImmutableList.of("author:", "reviewed-by:")) {
            String query = "repo:" + repository.fullName() + " is:pr is:closed " + role + login;
            GitHubSearchResponse response = translating(() -> requireBody(get(gitHubRestClient, pat, uri -> uri.path("/search/issues").queryParam("q", "{query}")
                    .queryParam("sort", "updated").queryParam("order", "desc").queryParam("per_page", SEARCH_RESULTS).build(query))
                    .retrieve().body(GitHubSearchResponse.class), "Missing search response from GitHub"));
            for (GitHubSearchResponse.Item item : Optional.ofNullable(response.items()).orElse(ImmutableList.of())) {
                if (item.pullRequest() != null && item.closedAt() != null) {
                    pulls.putIfAbsent(item.number(), new ClosedPull(item.number(), item.title(), item.closedAt(), item.pullRequest().mergedAt() != null));
                }
            }
        }
        return pulls.values().stream().sorted(Comparator.comparing(ClosedPull::closedAt).reversed()).collect(toImmutableList());
    }

    /** One closed pull request's review threads, reviews and comments; empty when it is open or gone. */
    public Optional<PullDiscussion> discussion(String pat, RepoRef repository, int number)
    {
        JsonNode pull = pullRequest(pat, repository, number, QUERY);
        if (!pull.isObject() || pull.path("closedAt").isNull() || pull.path("closedAt").isMissingNode()) {
            return Optional.empty();
        }
        List<ReviewThread> threads = nodes(pull.path("reviewThreads")).stream()
                .map(thread -> new ReviewThread(thread.path("id").asText(), thread.path("isResolved").asBoolean(), thread.path("isOutdated").asBoolean(),
                        Optional.ofNullable(thread.path("path").textValue()), nodes(thread.path("comments")).stream().map(comment -> note(comment, "createdAt"))
                        .collect(toImmutableList())))
                .collect(toImmutableList());
        return Optional.of(new PullDiscussion(pull.path("number").asInt(), pull.path("title").asText(), pull.path("url").asText(), pull.path("merged").asBoolean(),
                Instant.parse(pull.path("closedAt").asText()), threads,
                nodes(pull.path("reviews")).stream().map(review -> note(review, "submittedAt")).collect(toImmutableList()),
                nodes(pull.path("comments")).stream().map(comment -> note(comment, "createdAt")).collect(toImmutableList())));
    }

    /** A pull request's submitted reviews, with the head each was given on, and the first comment of each review thread; empty when it is gone. */
    public Optional<PullReplay> replay(String pat, RepoRef repository, int number)
    {
        JsonNode pull = pullRequest(pat, repository, number, REPLAY_QUERY);
        if (!pull.isObject()) {
            return Optional.empty();
        }
        JsonNode author = pull.path("author");
        List<SubmittedReview> reviews = nodes(pull.path("reviews")).stream().filter(review -> review.path("submittedAt").isTextual())
                .map(review -> new SubmittedReview(review.path("author").path("login").asText("ghost"), bot(review.path("author")),
                        review.path("authorAssociation").asText("NONE"), review.path("state").asText(""), Instant.parse(review.path("submittedAt").asText()),
                        Optional.ofNullable(review.path("commit").path("oid").textValue())))
                .collect(toImmutableList());
        List<ThreadStart> threads = nodes(pull.path("reviewThreads")).stream()
                .filter(thread -> thread.path("path").isTextual() && !nodes(thread.path("comments")).isEmpty())
                .map(thread -> {
                    JsonNode first = nodes(thread.path("comments")).getFirst();
                    return new ThreadStart(thread.path("path").asText(), thread.path("diffSide").asText("RIGHT"), line(thread, "line"), line(thread, "startLine"),
                            line(thread, "originalLine"), line(thread, "originalStartLine"), note(first, "createdAt"),
                            Optional.ofNullable(first.path("originalCommit").path("oid").textValue()));
                })
                .collect(toImmutableList());
        return Optional.of(new PullReplay(pull.path("number").asInt(), pull.path("title").asText(), pull.path("url").asText(), pull.path("merged").asBoolean(),
                author.path("login").asText("ghost"), pull.path("baseRefName").asText(), Optional.ofNullable(pull.path("mergeCommit").path("oid").textValue()),
                reviews, threads));
    }

    private JsonNode pullRequest(String pat, RepoRef repository, int number, String query)
    {
        requirePat(pat);
        checkArgument(number > 0, "Invalid pull request number");
        JsonNode response = requireBody(translating(() -> graphql(graphQlRestClient, pat, query,
                ImmutableMap.of("owner", repository.owner(), "repo", repository.repo(), "number", number)).body(JsonNode.class)), "GitHub returned no pull request");
        for (JsonNode error : response.path("errors")) {
            if ("RATE_LIMITED".equals(error.path("type").asText())) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "GitHub GraphQL rate limit reached");
            }
            if (!"NOT_FOUND".equals(error.path("type").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub could not read pull request #" + number);
            }
        }
        return response.path("data").path("repository").path("pullRequest");
    }

    private static Optional<Integer> line(JsonNode thread, String field)
    {
        return thread.path(field).isInt() ? Optional.of(thread.path(field).asInt()) : Optional.empty();
    }

    private static boolean bot(JsonNode author)
    {
        return "Bot".equals(author.path("__typename").asText()) || author.path("login").asText("").endsWith("[bot]");
    }

    private static boolean maintainer(String association)
    {
        return association.equals("OWNER") || association.equals("MEMBER") || association.equals("COLLABORATOR");
    }

    private static Note note(JsonNode node, String timeField)
    {
        JsonNode author = node.path("author");
        String login = author.path("login").asText("ghost");
        return new Note(node.path("id").asText(), login, bot(author),
                node.path("authorAssociation").asText("NONE"), node.path("body").asText(""), node.path("url").asText(""),
                Instant.parse(node.path(timeField).asText("1970-01-01T00:00:00Z")));
    }

    private static List<JsonNode> nodes(JsonNode connection)
    {
        return StreamSupport.stream(connection.path("nodes").spliterator(), false).filter(JsonNode::isObject).collect(toImmutableList());
    }

    public record ClosedPull(int number, String title, Instant closedAt, boolean merged)
    {
        public ClosedPull
        {
            requireNonNull(title, "title is null");
            requireNonNull(closedAt, "closedAt is null");
        }
    }

    public record PullDiscussion(int number, String title, String url, boolean merged, Instant closedAt, List<ReviewThread> threads, List<Note> reviews, List<Note> comments)
    {
        public PullDiscussion
        {
            threads = ImmutableList.copyOf(threads);
            reviews = ImmutableList.copyOf(reviews);
            comments = ImmutableList.copyOf(comments);
        }
    }

    /** A review thread on code; outdated when the code under it changed afterwards. */
    public record ReviewThread(String id, boolean resolved, boolean outdated, Optional<String> path, List<Note> comments)
    {
        public ReviewThread
        {
            comments = ImmutableList.copyOf(comments);
        }
    }

    /**
     * A comment, review body or reply as GitHub reports it.
     *
     * @param association GitHub's author association: OWNER, MEMBER and COLLABORATOR are the repository's maintainers
     */
    public record Note(String id, String author, boolean bot, String association, String body, String url, Instant at)
    {
        public boolean maintainer()
        {
            return GitHubHarvestClient.maintainer(association);
        }
    }

    /**
     * A pull request as its reviewers saw it, for replaying its review.
     *
     * @param mergeCommit the merge, squash or last rebased commit; its first parent is the base the change landed on
     */
    public record PullReplay(int number, String title, String url, boolean merged, String author, String baseRef, Optional<String> mergeCommit,
            List<SubmittedReview> reviews, List<ThreadStart> threads)
    {
        public PullReplay
        {
            requireNonNull(title, "title is null");
            requireNonNull(author, "author is null");
            requireNonNull(baseRef, "baseRef is null");
            requireNonNull(mergeCommit, "mergeCommit is null");
            reviews = ImmutableList.copyOf(reviews);
            threads = ImmutableList.copyOf(threads);
        }
    }

    /**
     * A submitted review and the head it was given on.
     *
     * @param commit the pull request's head at the review; GitHub drops it once a force-push lets that commit be collected
     */
    public record SubmittedReview(String author, boolean bot, String association, String state, Instant at, Optional<String> commit)
    {
        public SubmittedReview
        {
            requireNonNull(author, "author is null");
            requireNonNull(association, "association is null");
            requireNonNull(at, "at is null");
            requireNonNull(commit, "commit is null");
        }

        public boolean maintainer()
        {
            return GitHubHarvestClient.maintainer(association);
        }
    }

    /**
     * The first comment of a review thread and where it sits.
     *
     * @param side LEFT for the base side of the diff, RIGHT for the head side
     * @param line the line at the pull request's latest head, empty once the code under the thread changed
     * @param originalLine the line at the commit the comment was written on
     */
    public record ThreadStart(String path, String side, Optional<Integer> line, Optional<Integer> startLine, Optional<Integer> originalLine,
            Optional<Integer> originalStartLine, Note comment, Optional<String> originalCommit)
    {
        public ThreadStart
        {
            requireNonNull(path, "path is null");
            requireNonNull(side, "side is null");
            requireNonNull(comment, "comment is null");
            requireNonNull(originalCommit, "originalCommit is null");
        }
    }
}
