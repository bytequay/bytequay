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

import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.bytequay.app.repository.github.GitHubApiSupport.get;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/** One account inbox poll, shared by every surface. Checkpoints follow successful local ingestion. */
@Component
public class GitHubNotificationClient
{
    private static final Duration OVERLAP = Duration.ofMinutes(5);
    private final RestClient client;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Instant> pollAt = new HashMap<>();
    private final Map<String, String> credentials = new HashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();

    public GitHubNotificationClient(RestClient gitHubRestClient, AppSettingsStore settings, ObjectMapper mapper, Clock clock)
    {
        client = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public synchronized void poll(String token, String login, Consumer<GitHubNotificationRef> ingest)
    {
        String account = login.toLowerCase(Locale.ROOT);
        State previous = state(account);
        if (!token.equals(credentials.put(account, token))) {
            pollAt.remove(account);
            previous = new State(previous.completedAt(), Optional.empty(), previous.threads());
            states.put(account, previous);
        }
        Instant started = clock.instant();
        if (started.isBefore(pollAt.getOrDefault(account, Instant.MIN))) {
            return;
        }
        pollAt.put(account, started.plusSeconds(60));
        Map<String, GitHubNotificationRef> seen = new LinkedHashMap<>();
        previous.threads().forEach(thread -> seen.put(thread.id(), thread));
        Instant cutoff = previous.completedAt().orElse(started.minus(Duration.ofDays(90))).minus(OVERLAP);
        Optional<String> modified = previous.lastModified();
        Optional<RuntimeException> ingestionFailure = Optional.empty();
        try {
            for (int page = 1; page <= 200; page++) {
                int currentPage = page;
                RestClient.RequestHeadersSpec<?> request = get(client, token, uri -> uri.path("/notifications")
                        .queryParam("all", true).queryParam("participating", false).queryParam("per_page", 50).queryParam("page", currentPage).build());
                if (page == 1) {
                    modified.ifPresent(value -> request.header(HttpHeaders.IF_MODIFIED_SINCE, value));
                }
                ResponseEntity<JsonNode> response = translating(() -> request.retrieve().toEntity(JsonNode.class));
                pollAt.put(account, clock.instant().plusSeconds(pollSeconds(response.getHeaders())));
                if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                    if (page != 1 || modified.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unexpected GitHub notification 304");
                    }
                    failures.remove(account);
                    return;
                }
                if (page == 1) {
                    modified = Optional.ofNullable(response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED));
                }
                JsonNode body = requireBody(response.getBody(), "Missing GitHub notifications");
                if (!body.isArray()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid GitHub notifications");
                }
                boolean reachedCheckpoint = false;
                for (JsonNode row : body) {
                    if (Instant.parse(row.path("updated_at").asText()).isBefore(cutoff)) {
                        reachedCheckpoint = true;
                        continue;
                    }
                    Optional<GitHubNotificationRef> parsed = parse(row);
                    if (parsed.isEmpty()) {
                        continue;
                    }
                    GitHubNotificationRef thread = parsed.orElseThrow();
                    if (!thread.equals(seen.get(thread.id()))) {
                        try {
                            ingest.accept(thread);
                            seen.put(thread.id(), thread);
                        }
                        catch (RuntimeException failure) {
                            if (isRateLimit(failure)) {
                                throw failure;
                            }
                            if (ingestionFailure.isEmpty()) {
                                ingestionFailure = Optional.of(failure);
                            }
                        }
                    }
                }
                String links = response.getHeaders().getFirst(HttpHeaders.LINK);
                boolean hasNext = links != null && links.contains("rel=\"next\"");
                if (!hasNext || reachedCheckpoint) {
                    if (ingestionFailure.isPresent()) {
                        throw ingestionFailure.orElseThrow();
                    }
                    Instant retention = started.minus(Duration.ofDays(90));
                    State completed = new State(Optional.of(started), modified,
                            seen.values().stream().filter(thread -> !thread.updatedAt().isBefore(retention)).collect(ImmutableList.toImmutableList()));
                    settings.put(key(account), encode(completed));
                    states.put(account, completed);
                    failures.remove(account);
                    return;
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub notification catch-up exceeded 10000 threads; checkpoint retained");
        }
        catch (RuntimeException failure) {
            failures.put(account, failure.getMessage() == null ? "GitHub notifications unavailable" : failure.getMessage());
            State partial = new State(previous.completedAt(), previous.lastModified(), ImmutableList.copyOf(seen.values()));
            if (!partial.equals(previous)) {
                try {
                    // Keep successful local ingestion durable without acknowledging unfinished inbox pages.
                    settings.put(key(account), encode(partial));
                    states.put(account, partial);
                }
                catch (RuntimeException persistenceFailure) {
                    if (persistenceFailure != failure) {
                        failure.addSuppressed(persistenceFailure);
                    }
                }
            }
            if (ingestionFailure.isEmpty() && !isRateLimit(failure) && failure instanceof ResponseStatusException response
                    && (response.getStatusCode().value() == 401 || response.getStatusCode().value() == 403)) {
                pollAt.put(account, started.plus(Duration.ofMinutes(30)));
            }
            throw failure;
        }
    }

    /** GitHub's reason is sticky subscription history; this remains a fallback for team review requests, not proof of a current request. */
    public Set<PullRequestRef> reviewRequests(String login)
    {
        // Display requests read the last immutable snapshot without waiting for inbox network calls or ingestion.
        return state(login.toLowerCase(Locale.ROOT)).threads().stream().filter(GitHubNotificationRef::pullRequest)
                .filter(GitHubNotificationRef::reviewRequested).map(GitHubNotificationRef::ref).collect(toImmutableSet());
    }

    public Optional<String> failure(String login)
    {
        return Optional.ofNullable(failures.get(login.toLowerCase(Locale.ROOT)));
    }

    /** Fetches one comment, without its discussion history, patches, reactions or checks. */
    public Optional<PrActivitySummary> activity(String token, GitHubNotificationRef notification)
    {
        if (notification.commentUrl().isEmpty()) {
            return Optional.empty();
        }
        URI uri = URI.create(notification.commentUrl().orElseThrow());
        String prefix = "/repos/" + notification.ref().repoFullName() + "/";
        String path = uri.getPath();
        if (!"https".equals(uri.getScheme()) || !"api.github.com".equalsIgnoreCase(uri.getHost()) || !path.startsWith(prefix)
                || !path.substring(prefix.length()).matches("(?:issues|pulls)/comments/[0-9]+")) {
            return Optional.empty();
        }
        JsonNode comment = requireBody(translating(() -> get(client, token, path).retrieve().body(JsonNode.class)), "Missing GitHub comment");
        String date = comment.path("updated_at").asText(comment.path("created_at").asText());
        if (date.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PrActivitySummary("commented", text(comment.path("user"), "login"), Instant.parse(date), Optional.empty(),
                Optional.empty(), Optional.empty(), PrActivitySummary.excerpt(comment.path("body").asText()), text(comment, "path"),
                comment.path("line").canConvertToInt() ? OptionalInt.of(comment.path("line").intValue()) : OptionalInt.empty()));
    }

    private State state(String account)
    {
        return states.computeIfAbsent(account, ignored -> settings.get(key(account)).map(value -> {
            try {
                return mapper.readValue(value, State.class);
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("GitHub notification checkpoint could not be read", failure);
            }
        }).orElse(new State(Optional.empty(), Optional.empty(), ImmutableList.of())));
    }

    private String encode(State value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("GitHub notification checkpoint could not be saved", failure);
        }
    }

    private static String key(String account)
    {
        return "github.notifications." + account;
    }

    private static long pollSeconds(HttpHeaders headers)
    {
        try {
            return Math.max(60, Long.parseLong(headers.getFirst("X-Poll-Interval")));
        }
        catch (NumberFormatException ignored) {
            return 60;
        }
    }

    private static Optional<GitHubNotificationRef> parse(JsonNode row)
    {
        String type = row.path("subject").path("type").asText();
        if (!ImmutableSet.of("PullRequest", "Issue").contains(type)) {
            return Optional.empty();
        }
        String repository = row.path("repository").path("full_name").asText();
        String url = row.path("subject").path("url").asText();
        String number = url.substring(url.lastIndexOf('/') + 1);
        return PullRequestRef.parse(repository + "#" + number).map(ref -> new GitHubNotificationRef(
                row.path("id").asText(), ref, "PullRequest".equals(type), row.path("subject").path("title").asText(),
                row.path("reason").asText(), Instant.parse(row.path("updated_at").asText()), text(row.path("subject"), "latest_comment_url")));
    }

    private static Optional<String> text(JsonNode node, String field)
    {
        return Optional.ofNullable(node.path(field).textValue()).filter(value -> !value.isBlank());
    }

    private record State(Optional<Instant> completedAt, Optional<String> lastModified, List<GitHubNotificationRef> threads)
    {
        private State
        {
            threads = ImmutableList.copyOf(threads);
        }
    }
}
