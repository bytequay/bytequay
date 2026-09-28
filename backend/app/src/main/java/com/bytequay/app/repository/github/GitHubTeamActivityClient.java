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

import com.bytequay.core.domain.TeamActivityEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.repository.github.GitHubApiSupport.get;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Bounded read-only observations; neither public events nor these repository pages are a complete audit log. */
@Component
public class GitHubTeamActivityClient
{
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9-]+(?:\\[bot\\])?");
    private final RestClient client;

    public GitHubTeamActivityClient(@Qualifier("gitHubRestClient") RestClient client)
    {
        this.client = requireNonNull(client, "client is null");
    }

    public Observation publicEvents(String token, String login, Identity identity)
    {
        requirePat(token);
        checkArgument(LOGIN.matcher(login).matches(), "Invalid GitHub login");
        Collector collector = new Collector(identity);
        // GitHub limits this public stream to 300 events from the last 30 days, including unrelated event kinds.
        for (int page = 1; page <= 3 && !collector.rateLimited; page++) {
            int currentPage = page;
            Optional<JsonNode> response = collector.read("public events for " + login, () -> array(token, "/users/" + login + "/events/public", currentPage, ""));
            if (response.isEmpty()) {
                break;
            }
            collector.each(response.get(), collector::publicEvent);
            if (response.get().size() < 100) {
                break;
            }
        }
        return collector.result();
    }

    public Observation repository(String token, String repository, Identity identity, int offset)
    {
        requirePat(token);
        checkArgument(REPOSITORY.matcher(repository).matches() && !repository.endsWith("/.") && !repository.endsWith("/.."), "Invalid GitHub repository");
        Collector collector = new Collector(identity);
        String prefix = "/repos/" + repository;
        Map<Integer, JsonNode> subjects = new LinkedHashMap<>();
        collector.read(repository + " issues/PRs", () -> array(token, prefix + "/issues", 1, "issues"))
                .ifPresent(rows -> collector.each(rows, item -> {
                    subjects.put(item.path("number").asInt(), item);
                    collector.subject(repository, item, "REPOSITORY");
                }));
        collector.read(repository + " comments", () -> array(token, prefix + "/issues/comments", 1, "comments"))
                .ifPresent(rows -> collector.each(rows, comment -> {
                    int number = urlNumber(comment.path("issue_url").asText());
                    JsonNode subject = subjects.get(number);
                    boolean pull = subject != null ? subject.has("pull_request") : comment.path("html_url").asText().contains("/pull/");
                    collector.comment(repository, number, comment, Optional.ofNullable(subject), pull ? "PR_COMMENT" : "ISSUE_COMMENT", "REPOSITORY");
                }));
        collector.read(repository + " review comments", () -> array(token, prefix + "/pulls/comments", 1, "comments"))
                .ifPresent(rows -> collector.each(rows, comment -> {
                    int number = urlNumber(comment.path("pull_request_url").asText());
                    collector.comment(repository, number, comment, Optional.ofNullable(subjects.get(number)), "PR_REVIEW_COMMENT", "REPOSITORY");
                }));
        List<JsonNode> pulls = subjects.values().stream().filter(subject -> subject.has("pull_request")).toList();
        // ponytail: five PR detail/review/timeline groups per repository pass; rotate through the newest page rather than unbounded fan-out.
        for (int index = 0; index < Math.min(5, pulls.size()) && !collector.rateLimited; index++) {
            JsonNode summary = pulls.get(Math.floorMod(offset + index, pulls.size()));
            int number = summary.path("number").asInt();
            collector.read(repository + " PR #" + number, () -> object(token, prefix + "/pulls/" + number))
                    .ifPresent(detail -> collector.accept(() -> collector.pull(repository, detail, "REPOSITORY")));
            collector.read(repository + " reviews #" + number, () -> array(token, prefix + "/pulls/" + number + "/reviews", 1, ""))
                    .ifPresent(reviews -> collector.each(reviews, review -> collector.review(repository, number, review, summary, "REPOSITORY")));
            timeline(token, prefix + "/issues/" + number + "/timeline", repository, number, summary, collector);
        }
        return collector.result();
    }

    /** Timeline pages are oldest-first; retain the first and last page without walking an unbounded history. */
    private void timeline(String token, String path, String repository, int number, JsonNode subject, Collector collector)
    {
        collector.read(repository + " timeline #" + number, () -> {
            ResponseEntity<JsonNode> response = translating(() -> get(client, token, uri -> uri.path(path).queryParam("per_page", 100).queryParam("page", 1).build())
                    .retrieve().toEntity(JsonNode.class));
            checkArgument(requireBody(response.getBody(), "Missing GitHub timeline").isArray(), "Invalid GitHub timeline");
            return response;
        }).ifPresent(response -> {
            collector.each(response.getBody(), event -> collector.timelineEvent(repository, number, subject, event));
            int last = lastPage(Optional.ofNullable(response.getHeaders().getFirst("Link")).orElse(""));
            if (last > 1) {
                collector.read(repository + " latest timeline #" + number, () -> array(token, path, last, ""))
                        .ifPresent(rows -> collector.each(rows, event -> collector.timelineEvent(repository, number, subject, event)));
            }
        });
    }

    static int lastPage(String links)
    {
        for (String link : links.split(",")) {
            if (link.contains("rel=\"last\"")) {
                Matcher page = Pattern.compile("[?&]page=([0-9]+)(?:[&>])").matcher(link);
                if (page.find()) {
                    try {
                        return Integer.parseInt(page.group(1));
                    }
                    catch (NumberFormatException ignored) {
                        return 1;
                    }
                }
            }
        }
        return 1;
    }

    private JsonNode object(String token, String path)
    {
        return requireBody(translating(() -> get(client, token, path).retrieve().body(JsonNode.class)), "GitHub returned an empty activity response");
    }

    private JsonNode array(String token, String path, int page, String mode)
    {
        JsonNode result = requireBody(translating(() -> get(client, token, uri -> {
            uri.path(path).queryParam("per_page", 100).queryParam("page", page);
            if (!mode.isEmpty()) {
                uri.queryParam("sort", "updated").queryParam("direction", "desc").queryParam("since", Instant.now().minusSeconds(30L * 86400));
            }
            if (mode.equals("issues")) {
                uri.queryParam("state", "all");
            }
            return uri.build();
        }).retrieve().body(JsonNode.class)), "GitHub returned an empty activity response");
        checkArgument(result.isArray(), "Invalid GitHub activity response");
        return result;
    }

    static int urlNumber(String url)
    {
        try {
            return Integer.parseInt(url.substring(url.lastIndexOf('/') + 1));
        }
        catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public record Identity(long viewerId, String viewerLogin, Map<Long, String> members)
    {
        public Identity
        {
            members = ImmutableMap.copyOf(members);
        }
    }

    public record Observation(List<TeamActivityEvent> events, List<String> errors, boolean rateLimited)
    {
        public Observation
        {
            events = ImmutableList.copyOf(events);
            errors = ImmutableList.copyOf(errors);
        }
    }

    static final class Collector
    {
        private final Identity identity;
        private final Map<String, TeamActivityEvent> events = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();
        private boolean rateLimited;

        Collector(Identity identity)
        {
            this.identity = identity;
        }

        <T> Optional<T> read(String label, Supplier<T> request)
        {
            if (rateLimited) {
                return Optional.empty();
            }
            try {
                return Optional.of(request.get());
            }
            catch (RuntimeException failure) {
                rateLimited = isRateLimit(failure);
                errors.add(label + (rateLimited ? ": GitHub rate limit; collection paused" : ": unavailable (" + failure.getClass().getSimpleName() + ")"));
                return Optional.empty();
            }
        }

        void each(JsonNode rows, Consumer<JsonNode> mapping)
        {
            rows.forEach(row -> accept(() -> mapping.accept(row)));
        }

        void accept(Runnable mapping)
        {
            try {
                mapping.run();
            }
            catch (IllegalArgumentException | DateTimeParseException failure) {
                String message = "A malformed GitHub activity record was skipped";
                if (!errors.contains(message)) {
                    errors.add(message);
                }
            }
        }

        void timelineEvent(String repository, int number, JsonNode subject, JsonNode event)
        {
            if (event.path("event").asText().equals("review_requested")) {
                reviewRequest(repository, number, event.path("actor"), subject, event.path("requested_reviewer"), event.path("created_at"), "REPOSITORY");
            }
        }

        private void reviewRequest(String repository, int number, JsonNode actor, JsonNode subject, JsonNode reviewer, JsonNode time, String source)
        {
            long actorId = actor.path("id").asLong();
            long reviewerId = reviewer.path("id").asLong();
            if (actorId <= 0 || reviewerId <= 0 || number <= 0 || time.asText().isBlank()) {
                return;
            }
            Instant requestedAt = Instant.parse(time.asText());
            String key = "review-request:" + repository.toLowerCase(Locale.ROOT) + "#" + number + ":" + actorId + ":" + reviewerId + ":" + requestedAt;
            List<Long> direct = new ArrayList<>();
            if (actorId != reviewerId) {
                if (actorId == identity.viewerId() && identity.members().containsKey(reviewerId)) {
                    direct.add(reviewerId);
                }
                else if (reviewerId == identity.viewerId() && identity.members().containsKey(actorId)) {
                    direct.add(actorId);
                }
            }
            add(key, "REVIEW_REQUESTED", repository, number, actor, subject, time, "", source, direct);
        }

        void publicEvent(JsonNode event)
        {
            String repository = event.path("repo").path("name").asText();
            if (!REPOSITORY.matcher(repository).matches() || repository.endsWith("/.") || repository.endsWith("/..")) {
                return;
            }
            JsonNode payload = event.path("payload");
            JsonNode actor = event.path("actor");
            String action = payload.path("action").asText();
            JsonNode subject = payload.has("pull_request") ? payload.path("pull_request") : payload.path("issue");
            int number = subject.path("number").asInt(payload.path("number").asInt());
            String type = event.path("type").asText();
            if (type.equals("IssueCommentEvent") && action.equals("created")) {
                comment(repository, number, payload.path("comment"), Optional.of(subject), subject.has("pull_request") ? "PR_COMMENT" : "ISSUE_COMMENT", "PUBLIC_EVENTS");
            }
            else if (type.equals("PullRequestReviewCommentEvent") && action.equals("created")) {
                comment(repository, number, payload.path("comment"), Optional.of(subject), "PR_REVIEW_COMMENT", "PUBLIC_EVENTS");
            }
            else if (type.equals("PullRequestReviewEvent")) {
                review(repository, number, payload.path("review"), subject, "PUBLIC_EVENTS");
            }
            else if (type.equals("PullRequestEvent")) {
                String kind = action.equals("opened") ? "PR_OPENED" : action.equals("closed") && subject.path("merged").asBoolean() ? "PR_MERGED"
                        : action.equals("review_requested") ? "REVIEW_REQUESTED" : "";
                if (kind.equals("REVIEW_REQUESTED")) {
                    reviewRequest(repository, number, actor, subject, payload.path("requested_reviewer"), event.path("created_at"), "PUBLIC_EVENTS");
                }
                else if (!kind.isEmpty()) {
                    add(subjectKey(repository, number, kind), kind, repository, number, actor, subject, event.path("created_at"), "", "PUBLIC_EVENTS");
                }
            }
            else if (type.equals("IssuesEvent") && (action.equals("opened") || action.equals("closed"))) {
                String kind = action.equals("opened") ? "ISSUE_OPENED" : "ISSUE_CLOSED";
                String key = subjectKey(repository, number, kind) + (action.equals("closed") ? ":" + event.path("created_at").asText() : "");
                add(key, kind, repository, number, actor, subject, event.path("created_at"), "", "PUBLIC_EVENTS");
            }
        }

        void subject(String repository, JsonNode subject, String source)
        {
            int number = subject.path("number").asInt();
            String kind = subject.has("pull_request") ? "PR_OPENED" : "ISSUE_OPENED";
            add(subjectKey(repository, number, kind), kind, repository, number, subject.path("user"), subject, subject.path("created_at"), "", source);
            if (subject.has("pull_request")) {
                add(subjectKey(repository, number, "PR_MERGED"), "PR_MERGED", repository, number, subject.path("merged_by"), subject,
                        subject.path("pull_request").path("merged_at"), "", source);
            }
            else if (subject.path("closed_by").path("id").asLong() > 0) {
                add(subjectKey(repository, number, "ISSUE_CLOSED") + ":" + subject.path("closed_at").asText(), "ISSUE_CLOSED", repository, number,
                        subject.path("closed_by"), subject, subject.path("closed_at"), "", source);
            }
        }

        void pull(String repository, JsonNode subject, String source)
        {
            int number = subject.path("number").asInt();
            add(subjectKey(repository, number, "PR_OPENED"), "PR_OPENED", repository, number, subject.path("user"), subject, subject.path("created_at"), "", source);
            add(subjectKey(repository, number, "PR_MERGED"), "PR_MERGED", repository, number, subject.path("merged_by"), subject, subject.path("merged_at"), "", source);
        }

        void comment(String repository, int number, JsonNode comment, Optional<JsonNode> subject, String kind, String source)
        {
            long id = comment.path("id").asLong();
            if (id <= 0) {
                return;
            }
            String key = (kind.equals("PR_REVIEW_COMMENT") ? "review-comment:" : "issue-comment:") + id;
            add(key, kind, repository, number, comment.path("user"), subject.orElse(MissingNode.getInstance()), comment.path("created_at"), comment.path("body").asText(), source);
        }

        void review(String repository, int number, JsonNode review, JsonNode subject, String source)
        {
            if (review.path("id").asLong() > 0 && !review.path("state").asText().equalsIgnoreCase("pending")) {
                add("review:" + review.path("id").asLong(), "PR_REVIEWED", repository, number, review.path("user"), subject,
                        review.path("submitted_at"), review.path("body").asText(), source);
            }
        }

        private void add(String key, String kind, String repository, int number, JsonNode actor, JsonNode subject, JsonNode time, String body, String source)
        {
            add(key, kind, repository, number, actor, subject, time, body, source, ImmutableList.of());
        }

        private void add(String key, String kind, String repository, int number, JsonNode actor, JsonNode subject, JsonNode time, String body, String source,
                List<Long> explicitInteractions)
        {
            if (number <= 0 || time.asText().isBlank()) {
                return;
            }
            Instant occurredAt;
            try {
                occurredAt = Instant.parse(time.asText());
            }
            catch (DateTimeParseException ignored) {
                return;
            }
            if (occurredAt.isBefore(Instant.now().minusSeconds(90L * 86400)) || occurredAt.isAfter(Instant.now().plusSeconds(300))) {
                return;
            }
            long actorId = actor.path("id").asLong();
            long authorId = subject.path("user").path("id").asLong();
            if (actorId <= 0 && (!kind.equals("PR_MERGED") || authorId <= 0)) {
                return;
            }
            List<Long> direct = new ArrayList<>(explicitInteractions);
            boolean review = kind.equals("PR_REVIEWED");
            identity.members().forEach((memberId, login) -> {
                if (memberId != identity.viewerId() && ((actorId == memberId && (mentions(body, identity.viewerLogin()) || review && authorId == identity.viewerId()))
                        || actorId == identity.viewerId() && (mentions(body, login) || review && authorId == memberId))) {
                    direct.add(memberId);
                }
            });
            if (!identity.members().containsKey(actorId) && !identity.members().containsKey(authorId) && direct.isEmpty() && actorId != identity.viewerId()) {
                return;
            }
            String fallback = repository + "#" + number;
            String title = subject.path("title").asText(fallback);
            String url = "https://github.com/" + repository + (kind.startsWith("ISSUE_") ? "/issues/" : "/pull/") + number;
            TeamActivityEvent fact = new TeamActivityEvent(key, kind, actorId, actor.path("login").asText("unknown"), repository.toLowerCase(Locale.ROOT), number,
                    title.length() > 500 ? title.substring(0, 500) : title, url, occurredAt,
                    authorId > 0 ? Optional.of(authorId) : Optional.empty(),
                    Optional.of(subject.path("user").path("login").asText()).filter(value -> !value.isBlank()), source, direct);
            events.merge(key, fact, TeamActivityEvent::merge);
        }

        Observation result()
        {
            return new Observation(ImmutableList.copyOf(events.values()), errors, rateLimited);
        }
    }

    public static boolean mentions(String body, String login)
    {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_@])@" + Pattern.quote(login) + "(?![A-Za-z0-9_-])").matcher(body).find();
    }

    private static String subjectKey(String repository, int number, String kind)
    {
        return repository.toLowerCase(Locale.ROOT) + "#" + number + ":" + kind;
    }
}
