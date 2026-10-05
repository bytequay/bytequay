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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.bytequay.app.repository.github.GitHubApiSupport.get;
import static com.bytequay.app.repository.github.GitHubApiSupport.graphql;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * Reads the closed pull requests a person wrote or reviewed, or names by number, link or title, and one pull request's review discussion,
 * for learning repository memory, and a merged pull request's reviews and review threads, for replaying its review.
 */
@Component
public class GitHubHarvestClient
{
    private static final int SEARCH_RESULTS = 100;
    private static final int FOUND_RESULTS = 10;
    // GitHub refuses search queries over 256 characters, so a long pasted title searches by its first words.
    private static final int SEARCH_WORDS = 8;
    private static final Pattern NUMBER = Pattern.compile("#?([1-9][0-9]{0,8})");
    private static final Pattern LINK = Pattern.compile("(?:https?://)?(?:www\\.)?github\\.com/([\\w.-]+)/([\\w.-]+)/pull/([1-9][0-9]{0,8})(?:[/?#].*)?",
            Pattern.CASE_INSENSITIVE);
    private static final String NOTE_FIELDS = "id body url author { __typename login }";
    private static final String QUERY = """
            query($owner:String!,$repo:String!,$number:Int!) {
              repository(owner:$owner,name:$repo) { pullRequest(number:$number) {
                number title url merged closedAt
                reviewThreads(first:100) { nodes { id isResolved isOutdated path
                  comments(first:30) { nodes { %1$s createdAt pullRequestReview { authorCanPushToRepository } } } } }
                reviews(first:100) { nodes { %1$s submittedAt authorCanPushToRepository } }
                comments(first:100) { nodes { %1$s createdAt } }
              } }
            }
            """.formatted(NOTE_FIELDS);
    private static final String REPLAY_QUERY = """
            query($owner:String!,$repo:String!,$number:Int!) {
              repository(owner:$owner,name:$repo) { pullRequest(number:$number) {
                number title url merged baseRefName author { __typename login } mergeCommit { oid }
                reviews(first:100) { nodes { author { __typename login } authorCanPushToRepository state submittedAt commit { oid } } }
                reviewThreads(first:100) { nodes { path diffSide line startLine originalLine originalStartLine
                  comments(first:1) { nodes { %1$s createdAt originalCommit { oid } pullRequestReview { authorCanPushToRepository } } } } }
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
                closed(item).ifPresent(pull -> pulls.putIfAbsent(pull.number(), pull));
            }
        }
        return pulls.values().stream().sorted(Comparator.comparing(ClosedPull::closedAt).reversed()).collect(toImmutableList());
    }

    /**
     * The repository's closed pull requests a person names: the one with the typed number or link, or those whose titles have every
     * typed word, most recently updated first. Characters GitHub search reads as syntax are dropped, so the repository and state stay fixed.
     */
    public List<ClosedPull> findClosedPulls(String pat, RepoRef repository, String typed)
    {
        requirePat(pat);
        Matcher link = LINK.matcher(typed.strip());
        if (link.matches()) {
            checkArgument((link.group(1) + "/" + link.group(2)).equalsIgnoreCase(repository.fullName()),
                    "Only a pull request of %s, the repository this workspace learns from, can be learned from", repository.fullName());
            return closedPull(pat, repository, Integer.parseInt(link.group(3)));
        }
        Matcher number = NUMBER.matcher(typed.strip());
        if (number.matches()) {
            return closedPull(pat, repository, Integer.parseInt(number.group(1)));
        }
        String words = Arrays.stream(typed.replaceAll("[^\\p{L}\\p{N}_./-]+", " ").strip().split(" +")).filter(word -> !word.isEmpty()).limit(SEARCH_WORDS)
                .map(word -> "\"" + word + "\"").collect(joining(" "));
        if (words.isEmpty()) {
            return ImmutableList.of();
        }
        String query = "repo:" + repository.fullName() + " is:pr is:closed in:title " + words;
        GitHubSearchResponse response = translating(() -> requireBody(get(gitHubRestClient, pat, uri -> uri.path("/search/issues").queryParam("q", "{query}")
                .queryParam("sort", "updated").queryParam("order", "desc").queryParam("per_page", FOUND_RESULTS).build(query))
                .retrieve().body(GitHubSearchResponse.class), "Missing search response from GitHub"));
        return Optional.ofNullable(response.items()).orElse(ImmutableList.of()).stream().map(GitHubHarvestClient::closed).flatMap(Optional::stream)
                .collect(toImmutableList());
    }

    /** The pull request with this number while it is closed; an open one, an issue or a missing number is none. */
    private List<ClosedPull> closedPull(String pat, RepoRef repository, int number)
    {
        try {
            GitHubSearchResponse.Item item = translating(() -> requireBody(get(gitHubRestClient, pat, "/repos/{owner}/{repo}/issues/{number}", repository.owner(),
                    repository.repo(), number).retrieve().body(GitHubSearchResponse.Item.class), "GitHub returned no pull request"));
            return closed(item).stream().collect(toImmutableList());
        }
        catch (ResponseStatusException missing) {
            if (missing.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return ImmutableList.of();
            }
            throw missing;
        }
    }

    /** A search hit or an issue read as a closed pull request; an issue or an open pull request is none. */
    private static Optional<ClosedPull> closed(GitHubSearchResponse.Item item)
    {
        return item.pullRequest() == null || item.closedAt() == null ? Optional.empty()
                : Optional.of(new ClosedPull(item.number(), item.title(), item.closedAt(), item.pullRequest().mergedAt() != null));
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
                        Optional.ofNullable(thread.path("path").textValue()), nodes(thread.path("comments")).stream()
                        .map(comment -> note(comment, "createdAt", canPush(comment.path("pullRequestReview")))).collect(toImmutableList())))
                .collect(toImmutableList());
        List<Note> reviews = nodes(pull.path("reviews")).stream().map(review -> note(review, "submittedAt", canPush(review))).collect(toImmutableList());
        // Only reviews say whether their author can push, so a conversation comment is a maintainer's when its author reviewed this pull request
        // with push access.
        Set<String> maintainers = Stream.concat(reviews.stream(), threads.stream().flatMap(thread -> thread.comments().stream()))
                .filter(Note::maintainer).map(Note::author).collect(toImmutableSet());
        return Optional.of(new PullDiscussion(pull.path("number").asInt(), pull.path("title").asText(), pull.path("url").asText(), pull.path("merged").asBoolean(),
                Instant.parse(pull.path("closedAt").asText()), threads, reviews,
                nodes(pull.path("comments")).stream().map(comment -> note(comment, "createdAt", maintainers.contains(login(comment)))).collect(toImmutableList())));
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
                .map(review -> new SubmittedReview(login(review), bot(review.path("author")), canPush(review), review.path("state").asText(""),
                        Instant.parse(review.path("submittedAt").asText()),
                        Optional.ofNullable(review.path("commit").path("oid").textValue())))
                .collect(toImmutableList());
        List<ThreadStart> threads = nodes(pull.path("reviewThreads")).stream()
                .filter(thread -> thread.path("path").isTextual() && !nodes(thread.path("comments")).isEmpty())
                .map(thread -> {
                    JsonNode first = nodes(thread.path("comments")).getFirst();
                    return new ThreadStart(thread.path("path").asText(), thread.path("diffSide").asText("RIGHT"), line(thread, "line"), line(thread, "startLine"),
                            line(thread, "originalLine"), line(thread, "originalStartLine"), note(first, "createdAt", canPush(first.path("pullRequestReview"))),
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

    /**
     * A maintainer is someone who can push to the repository. GitHub's author association is no proxy for that: MEMBER also covers
     * organization members who can only triage.
     */
    private static boolean canPush(JsonNode review)
    {
        return review.path("authorCanPushToRepository").asBoolean(false);
    }

    private static String login(JsonNode node)
    {
        return node.path("author").path("login").asText("ghost");
    }

    private static Note note(JsonNode node, String timeField, boolean maintainer)
    {
        return new Note(node.path("id").asText(), login(node), bot(node.path("author")), maintainer, node.path("body").asText(""), node.path("url").asText(""),
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
     * @param maintainer whether its author can push to the repository
     */
    public record Note(String id, String author, boolean bot, boolean maintainer, String body, String url, Instant at) {}

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
     * @param maintainer whether its author can push to the repository
     * @param commit the pull request's head at the review; GitHub drops it once a force-push lets that commit be collected
     */
    public record SubmittedReview(String author, boolean bot, boolean maintainer, String state, Instant at, Optional<String> commit)
    {
        public SubmittedReview
        {
            requireNonNull(author, "author is null");
            requireNonNull(at, "at is null");
            requireNonNull(commit, "commit is null");
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
