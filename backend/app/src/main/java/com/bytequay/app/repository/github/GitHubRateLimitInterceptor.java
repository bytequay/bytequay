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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** One transport budget for background scans, focused reads, and explicit writes. */
@Component
public final class GitHubRateLimitInterceptor
        implements ClientHttpRequestInterceptor
{
    private static final Logger log = LoggerFactory.getLogger(GitHubRateLimitInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CONCURRENT_REQUESTS = 4;
    private static final String REMAINING = "X-RateLimit-Remaining";
    private static final String RESET = "X-RateLimit-Reset";
    private static final String RESOURCE = "X-RateLimit-Resource";
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    // Any other segment becomes {}, so an owner, branch, or file path never reaches the snapshot.
    private static final Set<String> PATH_WORDS = ImmutableSet.of("actions", "assignees", "branches", "check-runs", "check-suites", "code-snippet", "comments",
            "commits", "compare", "contents", "contributors", "events", "files", "forks", "git", "graphql", "heads", "issue-types", "issues", "jobs", "labels",
            "languages", "logs", "merge", "notifications", "orgs", "preview", "public", "pulls", "reactions", "received_events", "ref", "refs", "replies", "repos",
            "repositories", "requested_reviewers", "rerun-failed-jobs", "reviews", "rules", "runs", "search", "status", "statuses", "sub_issues", "teams", "threads", "timeline",
            "user", "users", "workflows");

    private final Clock clock;
    private final Semaphore permits;
    private final int maximumConcurrent;
    private final Map<String, AccountBudget> credentials = new HashMap<>();
    private final Map<String, AccountBudget> accounts = new HashMap<>();

    @Autowired
    public GitHubRateLimitInterceptor(Clock clock)
    {
        this(clock, MAX_CONCURRENT_REQUESTS);
    }

    GitHubRateLimitInterceptor(Clock clock, int maximumConcurrent)
    {
        this.clock = requireNonNull(clock, "clock is null");
        checkArgument(maximumConcurrent > 0, "maximumConcurrent must be positive");
        this.maximumConcurrent = maximumConcurrent;
        this.permits = new Semaphore(maximumConcurrent, true);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException
    {
        String credential = credential(request.getURI().getAuthority(), request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        String resource = resource(request.getURI().getPath());
        String family = family(request.getURI().getPath(), body);
        String endpoint = endpoint(request.getURI().getPath());
        rejectWhileBlocked(credential, resource);
        try {
            permits.acquire();
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for GitHub request capacity");
        }
        long started = System.nanoTime();
        ClientHttpResponse response;
        try {
            // A sibling may have established a cooldown while this request waited for capacity.
            rejectWhileBlocked(credential, resource);
            response = execution.execute(request, body);
        }
        catch (IOException | RuntimeException | Error failure) {
            permits.release();
            if (!(failure instanceof GitHubRateLimitException)) {
                completed(credential, family, endpoint, resource, 0, started, Optional.empty());
            }
            throw failure;
        }
        TrackedResponse tracked = new TrackedResponse(response, credential, family, endpoint, resource, started);
        try {
            int status = response.getStatusCode().value();
            tracked.status = status;
            boolean profile = "/user".equals(request.getURI().getPath()) && status == 200;
            Optional<JsonNode> json = Optional.empty();
            if (resource.equals("graphql") || status == 403 || status == 429 || profile) {
                tracked.buffer();
                json = parse(tracked.buffered.orElseThrow());
            }
            if (profile) {
                json.filter(value -> value.path("id").isIntegralNumber() && value.path("id").longValue() > 0)
                        .ifPresent(value -> bindAccount(credential, request.getURI().getAuthority(), value.path("id").longValue()));
            }
            if (resource.equals("graphql")) {
                tracked.cost = json.flatMap(value -> number(value.path("data").path("rateLimit").path("cost").asText()));
            }
            observe(credential, family, resource, status, response.getHeaders(), json);
            return tracked;
        }
        catch (IOException | RuntimeException | Error failure) {
            tracked.close();
            throw failure;
        }
    }

    private synchronized void rejectWhileBlocked(String credential, String resource)
    {
        AccountBudget budget = budget(credential);
        long now = clock.instant().getEpochSecond();
        long primary = budget.primary.getOrDefault(resource, 0L);
        long until = Math.max(primary, budget.secondaryUntil);
        if (until > now) {
            budget.deferred++;
            throw new GitHubRateLimitException(budget.secondaryUntil >= primary ? "secondary" : resource, until);
        }
    }

    private synchronized void observe(String credential, String family, String requestResource, int status, HttpHeaders headers, Optional<JsonNode> json)
    {
        AccountBudget budget = budget(credential);
        String resource = Optional.ofNullable(headers.getFirst(RESOURCE)).map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> value.equals("core") || value.equals("search") || value.equals("graphql")).orElse(requestResource);
        long now = clock.instant().getEpochSecond();
        Optional<Long> remaining = number(headers.getFirst(REMAINING));
        Optional<Long> reset = number(headers.getFirst(RESET)).filter(GitHubRateLimitInterceptor::validEpoch);
        if (remaining.isPresent() || reset.isPresent()) {
            budget.quotas.put(resource, new Quota(resource, number(headers.getFirst("X-RateLimit-Limit")), remaining,
                    number(headers.getFirst("X-RateLimit-Used")), reset.map(Instant::ofEpochSecond)));
        }
        boolean exhausted = remaining.filter(value -> value == 0).isPresent();
        boolean bodyLimited = json.map(GitHubRateLimitInterceptor::rateLimited).orElse(false);
        boolean bodySecondary = json.map(GitHubRateLimitInterceptor::secondaryLimited).orElse(false);
        Optional<Long> retryAfter = retryAt(headers.getFirst(HttpHeaders.RETRY_AFTER), now);
        boolean secondary = bodySecondary || !exhausted && status == 429 || (status == 403 || status == 429 || bodyLimited) && retryAfter.isPresent();
        boolean primary = exhausted || bodyLimited && !secondary;
        if (primary) {
            long until = reset.filter(value -> value > now).orElse(now + 60);
            long previous = budget.primary.getOrDefault(resource, 0L);
            budget.primary.put(resource, Math.max(previous, until));
            if (until > previous) {
                log.warn("GitHub {} API rate limit reached during {}; pausing requests until {}", resource, family, Instant.ofEpochSecond(until));
            }
        }
        if (secondary) {
            long delay = Math.min(3600L, 60L << Math.min(budget.secondaryFailures++, 6));
            long until = retryAfter.orElse(now + delay);
            if (until > budget.secondaryUntil) {
                budget.secondaryUntil = until;
                log.warn("GitHub secondary API rate limit reached during {}; pausing requests until {}", family, Instant.ofEpochSecond(until));
            }
        }
        boolean rejected = bodyLimited || secondary || primary && status >= 400;
        if (rejected) {
            budget.rateLimited++;
            long primaryUntil = budget.primary.getOrDefault(resource, 0L);
            long until = Math.max(primaryUntil, budget.secondaryUntil);
            throw new GitHubRateLimitException(budget.secondaryUntil >= primaryUntil ? "secondary" : resource, until);
        }
        if (status >= 200 && status < 400 && budget.secondaryUntil <= now) {
            budget.secondaryFailures = 0;
        }
    }

    private static boolean rateLimited(JsonNode json)
    {
        if (limitedMessage(json.path("message").asText()) || secondaryLimited(json)) {
            return true;
        }
        for (JsonNode error : json.path("errors")) {
            if ("RATE_LIMITED".equals(error.path("type").asText()) || limitedMessage(error.path("message").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean limitedMessage(String value)
    {
        String message = value.toLowerCase(Locale.ROOT);
        return message.contains("rate limit exceeded") || message.contains("rate limit has been exceeded");
    }

    private static boolean secondaryLimited(JsonNode json)
    {
        if (secondaryMessage(json.path("message").asText())) {
            return true;
        }
        for (JsonNode error : json.path("errors")) {
            if (secondaryMessage(error.path("message").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean secondaryMessage(String value)
    {
        String message = value.toLowerCase(Locale.ROOT);
        return message.contains("secondary rate limit") || message.contains("abuse detection") || message.contains("abuse rate limit");
    }

    private static Optional<Long> retryAt(String value, long now)
    {
        if (value == null) {
            return Optional.empty();
        }
        Optional<Long> seconds = number(value);
        if (seconds.isPresent()) {
            try {
                return Optional.of(Math.addExact(now, Math.max(1, seconds.orElseThrow()))).filter(GitHubRateLimitInterceptor::validEpoch);
            }
            catch (ArithmeticException ignored) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(Math.max(now + 1, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()));
        }
        catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Long> number(String value)
    {
        try {
            return Optional.ofNullable(value).map(Long::parseLong).filter(number -> number >= 0);
        }
        catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static boolean validEpoch(long value)
    {
        try {
            Instant.ofEpochSecond(value);
            return true;
        }
        catch (DateTimeException ignored) {
            return false;
        }
    }

    private static Optional<JsonNode> parse(byte[] body)
    {
        try {
            return Optional.ofNullable(MAPPER.readTree(body));
        }
        catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private synchronized void bindAccount(String credential, String host, long id)
    {
        AccountBudget previous = budget(credential);
        AccountBudget known = accounts.putIfAbsent(host.toLowerCase(Locale.ROOT) + ":" + id, previous);
        if (known != null && known != previous) {
            previous.primary.forEach((resource, until) -> known.primary.merge(resource, until, Math::max));
            previous.quotas.forEach(known.quotas::putIfAbsent);
            previous.requests.forEach((key, totals) -> known.requests.computeIfAbsent(key, ignored -> new Totals())
                    .add(totals.count, totals.nanos, totals.reportedCost, totals.costedRequests));
            known.secondaryUntil = Math.max(known.secondaryUntil, previous.secondaryUntil);
            known.secondaryFailures = Math.max(known.secondaryFailures, previous.secondaryFailures);
            known.deferred += previous.deferred;
            known.rateLimited += previous.rateLimited;
            credentials.replaceAll((key, value) -> value == previous ? known : value);
        }
    }

    private AccountBudget budget(String credential)
    {
        return credentials.computeIfAbsent(credential, ignored -> new AccountBudget());
    }

    private synchronized void completed(String credential, String family, String endpoint, String resource, int status, long started, Optional<Long> cost)
    {
        budget(credential).requests.computeIfAbsent(new RequestKey(family, endpoint, resource, status), ignored -> new Totals())
                .add(1, System.nanoTime() - started, cost.orElse(0L), cost.isPresent() ? 1 : 0);
    }

    /** Local diagnostics only: no request, credential, URL, or response content is returned. */
    public synchronized Snapshot snapshot(String pat)
    {
        AccountBudget budget = budget(credential("api.github.com", GitHubApiSupport.authorization(pat)));
        List<RequestCount> requests = budget.requests.entrySet().stream()
                .map(entry -> new RequestCount(entry.getKey().family(), entry.getKey().endpoint(), entry.getKey().resource(), entry.getKey().status(),
                        entry.getValue().count, entry.getValue().nanos / 1_000_000, entry.getValue().reportedCost, entry.getValue().costedRequests))
                .collect(toImmutableList());
        Map<String, Instant> cooldowns = new HashMap<>();
        budget.primary.forEach((resource, until) -> {
            if (until > clock.instant().getEpochSecond()) {
                cooldowns.put(resource, Instant.ofEpochSecond(until));
            }
        });
        if (budget.secondaryUntil > clock.instant().getEpochSecond()) {
            cooldowns.put("secondary", Instant.ofEpochSecond(budget.secondaryUntil));
        }
        return new Snapshot(requests, ImmutableList.copyOf(budget.quotas.values()), cooldowns,
                budget.deferred, budget.rateLimited, maximumConcurrent - permits.availablePermits(), maximumConcurrent);
    }

    private static String credential(String host, String authorization)
    {
        return host.toLowerCase(Locale.ROOT) + ":" + Hashing.sha256().hashString(authorization == null ? "" : authorization, StandardCharsets.UTF_8);
    }

    private static String resource(String path)
    {
        if (path.equals("/graphql")) {
            return "graphql";
        }
        return path.startsWith("/search/") ? "search" : "core";
    }

    /** The request path with owner, repository, number, and SHA segments replaced, e.g. /repos/{o}/{r}/pulls/{n}/reviews. */
    static String endpoint(String path)
    {
        List<String> segments = Splitter.on('/').splitToList(path);
        StringBuilder template = new StringBuilder();
        for (int index = 1; index < segments.size(); index++) {
            template.append('/').append(template(segments.get(1), index, segments.get(index)));
        }
        return template.toString();
    }

    private static String template(String root, int index, String segment)
    {
        if (root.equals("repos") && (index == 2 || index == 3)) {
            return index == 2 ? "{o}" : "{r}";
        }
        if (PATH_WORDS.contains(segment)) {
            return segment;
        }
        if (NUMBER.matcher(segment).matches()) {
            return "{n}";
        }
        return SHA.matcher(segment).matches() ? "{sha}" : "{}";
    }

    private static String family(String path, byte[] body)
    {
        if (path.equals("/graphql")) {
            String query = parse(body).map(value -> value.path("query").asText()).orElse("");
            if (query.contains("fragment Summary on PullRequest")) {
                return "pr-summary";
            }
            if (query.contains("reactionGroups")) {
                return query.contains("viewerHasReacted") && !query.contains("users") ? "viewer-reactions" : "review-reactions";
            }
            return query.contains("reviewThreads") || query.contains("PullRequestReviewThread") ? "review-threads" : "graphql";
        }
        if (path.startsWith("/search/")) {
            return "discovery";
        }
        if (path.startsWith("/notifications")) {
            return "notifications";
        }
        if (path.contains("/check-runs") || path.endsWith("/status") || path.contains("/actions/")) {
            return "checks";
        }
        if (path.endsWith("/files") || path.contains("/compare/")) {
            return "diffs";
        }
        if (path.contains("/pulls")) {
            return "pr-details";
        }
        if (path.contains("/issues")) {
            return "issues-and-pr-activity";
        }
        return path.contains("/events") ? "activity" : "account-and-repository";
    }

    /** Cost is reported only by instrumented GraphQL queries; costedRequests distinguishes missing data from zero cost. */
    public record RequestCount(String family, String endpoint, String resource, int status, long count, long totalDurationMillis, long reportedCost, long costedRequests) {}

    public record Quota(String resource, Optional<Long> limit, Optional<Long> remaining, Optional<Long> used, Optional<Instant> resetAt) {}

    public record Snapshot(List<RequestCount> requests, List<Quota> quotas, Map<String, Instant> cooldowns,
            long deferredRequests, long rateLimitedResponses, int inFlight, int maximumConcurrent)
    {
        public Snapshot
        {
            requests = ImmutableList.copyOf(requests);
            quotas = ImmutableList.copyOf(quotas);
            cooldowns = ImmutableMap.copyOf(cooldowns);
        }
    }

    private record RequestKey(String family, String endpoint, String resource, int status) {}

    private static final class Totals
    {
        private long count;
        private long nanos;
        private long reportedCost;
        private long costedRequests;

        private void add(long count, long nanos, long reportedCost, long costedRequests)
        {
            this.count += count;
            this.nanos += nanos;
            this.reportedCost += reportedCost;
            this.costedRequests += costedRequests;
        }
    }

    private static final class AccountBudget
    {
        private final Map<String, Long> primary = new HashMap<>();
        private final Map<String, Quota> quotas = new HashMap<>();
        private final Map<RequestKey, Totals> requests = new HashMap<>();
        private long secondaryUntil;
        private int secondaryFailures;
        private long deferred;
        private long rateLimited;
    }

    private final class TrackedResponse
            implements ClientHttpResponse
    {
        private final ClientHttpResponse delegate;
        private final String credential;
        private final String family;
        private final String endpoint;
        private final String resource;
        private final long started;
        private final AtomicBoolean closed = new AtomicBoolean();
        private int status;
        private Optional<Long> cost = Optional.empty();
        private Optional<byte[]> buffered = Optional.empty();

        private TrackedResponse(ClientHttpResponse delegate, String credential, String family, String endpoint, String resource, long started)
        {
            this.delegate = delegate;
            this.credential = credential;
            this.family = family;
            this.endpoint = endpoint;
            this.resource = resource;
            this.started = started;
        }

        private void buffer()
                throws IOException
        {
            buffered = Optional.of(delegate.getBody().readAllBytes());
        }

        @Override
        public HttpStatusCode getStatusCode()
                throws IOException
        {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText()
                throws IOException
        {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders()
        {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody()
                throws IOException
        {
            return buffered.isPresent() ? new ByteArrayInputStream(buffered.orElseThrow()) : delegate.getBody();
        }

        @Override
        public void close()
        {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                delegate.close();
            }
            finally {
                completed(credential, family, endpoint, resource, status, started, cost);
                permits.release();
            }
        }
    }
}
