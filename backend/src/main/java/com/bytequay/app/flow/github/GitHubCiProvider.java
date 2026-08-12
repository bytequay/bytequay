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
package com.bytequay.app.flow.github;

import com.bytequay.app.flow.ci.CiAutofixRecords.GitHubCheckSelector;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiObservationCoordinator.CiObservationActivation;
import com.bytequay.app.flow.runtime.FlowRuntime.CiObservationSubject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** Concrete bounded GitHub-only reader for receipt-owned CI watches. */
final class GitHubCiProvider
{
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final int MAX_SUITES = 10;
    private static final int MAX_RUNS = 1000;
    private static final int MAX_REQUESTS = 50;
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final Duration POLL_DEADLINE = Duration.ofMinutes(2);
    private static final int JSON_LIMIT = 1024 * 1024;
    private static final int LOG_LIMIT = 4 * 1024 * 1024;
    private static final Pattern ACTIONS_JOB = Pattern.compile(
            "^https://github\\.com/([^/]+)/([^/]+)/actions/runs/\\d+/job/(\\d+)(?:[/?#].*)?$");

    enum Failure
    {
        INVALID,
        UNSUPPORTED,
        UNAVAILABLE
    }

    record PollResult(
            GitHubCiObservationProof proof,
            Failure failure,
            Instant retryNotBefore)
    {
        PollResult(GitHubCiObservationProof proof, Failure failure)
        {
            this(proof, failure, null);
        }

        PollResult
        {
            if ((proof == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "poll result must contain proof or failure");
            }
        }
    }

    interface CiHttp
    {
        CiHttpResponse get(
                URI uri, char[] token, int responseLimit);
    }

    record CiHttpResponse(
            boolean complete,
            int statusCode,
            byte[] body,
            String location,
            String retryAfter,
            String rateLimitReset)
    {
        CiHttpResponse
        {
            body = body.clone();
        }

        @Override
        public byte[] body()
        {
            return body.clone();
        }
    }

    static final class CompleteBatch
            implements GitHubCiObservationProof
    {
        private final CiObservationActivation activation;
        private final List<NormalizedCheck> checks;
        private final Map<String, byte[]> logs;
        private final String batchDigest;

        private CompleteBatch(
                CiObservationActivation activation,
                List<NormalizedCheck> checks,
                Map<String, byte[]> logs,
                String batchDigest)
        {
            this.activation = requireNonNull(
                    activation, "activation is null");
            this.checks = List.copyOf(checks);
            Map<String, byte[]> copied = new LinkedHashMap<>();
            logs.forEach((key, value) -> copied.put(key, value.clone()));
            this.logs = Map.copyOf(copied);
            this.batchDigest = requireNonNull(
                    batchDigest, "batchDigest is null");
        }

        @Override
        public boolean matchesActivation(CiObservationActivation candidate)
        {
            return candidate != null
                    && activation.claim().equals(candidate.claim())
                    && activation.subject().equals(candidate.subject())
                    && activation.policy().equals(candidate.policy());
        }

        @Override
        public List<NormalizedCheck> checks()
        {
            return checks;
        }

        @Override
        public Map<String, byte[]> failedLogsByProviderCheckId()
        {
            Map<String, byte[]> copied = new LinkedHashMap<>();
            logs.forEach((key, value) -> copied.put(key, value.clone()));
            return Map.copyOf(copied);
        }

        @Override
        public String batchDigest()
        {
            return batchDigest;
        }
    }

    private record PrIdentity(
            int prNumber,
            String state,
            String nodeId,
            long baseRepositoryId,
            String baseOwner,
            String baseName,
            String baseRef,
            String baseSha,
            long headRepositoryId,
            String headOwner,
            String headName,
            String headRef,
            String headSha) {}

    private record Suite(long id, long appId, String headSha) {}

    private record Run(
            long id,
            long suiteId,
            long appId,
            String appSlug,
            String headSha,
            String name,
            String status,
            String conclusion,
            Instant startedAt,
            Instant completedAt,
            String detailsUrl) {}

    private record Collected(
            List<Suite> suites,
            List<Run> runs,
            String digest) {}

    private record SelectorIdentity(long appId, String name) {}

    private record RunSelection(Run run, boolean ambiguous) {}

    private static final class Budget
    {
        private static final Duration MIN_RATE_DELAY = Duration.ofMinutes(1);
        private static final Duration DEFAULT_RATE_DELAY = Duration.ofHours(1);
        private static final Duration MAX_RATE_DELAY = Duration.ofDays(1);

        private int requests;
        private int responseBytes;
        private final Clock clock;
        private final long deadline = System.nanoTime()
                + POLL_DEADLINE.toNanos();
        private Instant retryNotBefore;

        private Budget(Clock clock)
        {
            this.clock = clock;
        }

        private void request()
        {
            if (++requests > MAX_REQUESTS
                    || System.nanoTime() > deadline) {
                exceed();
            }
        }

        private void response(CiHttpResponse response)
        {
            responseBytes = Math.addExact(
                    responseBytes, response.body().length);
            if (response.statusCode() == 429
                    || response.statusCode() == 403) {
                retryNotBefore = later(
                        retryNotBefore, boundedRetry(response));
            }
            if (responseBytes > MAX_RESPONSE_BYTES
                    || System.nanoTime() > deadline) {
                exceed();
            }
        }

        private void exceed()
        {
            retryNotBefore = later(
                    retryNotBefore,
                    clock.instant().plus(Duration.ofMinutes(15)));
            throw new BudgetExceededException();
        }

        private Instant boundedRetry(CiHttpResponse response)
        {
            Instant now = clock.instant();
            Instant requested = later(
                    retryAfter(response.retryAfter(), now),
                    rateLimitReset(response.rateLimitReset()));
            if (requested == null) {
                requested = now.plus(DEFAULT_RATE_DELAY);
            }
            Instant minimum = now.plus(MIN_RATE_DELAY);
            Instant maximum = now.plus(MAX_RATE_DELAY);
            if (requested.isBefore(minimum)) {
                return minimum;
            }
            return requested.isAfter(maximum) ? maximum : requested;
        }

        private static Instant retryAfter(String value, Instant now)
        {
            try {
                return value == null ? null
                        : now.plusSeconds(Long.parseLong(value.trim()));
            }
            catch (RuntimeException malformed) {
                return null;
            }
        }

        private static Instant rateLimitReset(String value)
        {
            try {
                return value == null ? null
                        : Instant.ofEpochSecond(Long.parseLong(value.trim()));
            }
            catch (RuntimeException malformed) {
                return null;
            }
        }

        private static Instant later(Instant first, Instant second)
        {
            if (first == null) {
                return second;
            }
            return second != null && second.isAfter(first) ? second : first;
        }
    }

    private final GitHubProvider.SecretSource secrets;
    private final CiHttp http;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    GitHubCiProvider(
            GitHubProvider.SecretSource secrets,
            Clock clock)
    {
        this(secrets, new DirectCiHttp(), clock);
    }

    GitHubCiProvider(
            GitHubProvider.SecretSource secrets,
            CiHttp http,
            Clock clock)
    {
        this.secrets = requireNonNull(secrets, "secrets is null");
        this.http = requireNonNull(http, "http is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    PollResult poll(CiObservationActivation activation)
    {
        requireNonNull(activation, "activation is null");
        CiObservationSubject subject = activation.subject();
        List<GitHubCheckSelector> selectors;
        try {
            selectors = activation.policy().requiredCheckSelectors().stream()
                    .map(GitHubCiProvider::parseSelector)
                    .toList();
        }
        catch (IllegalArgumentException unsupported) {
            return new PollResult(null, Failure.INVALID);
        }
        GitHubProvider.RepositoryCredential credential = secrets.credential(
                subject.repositoryExternalId(),
                subject.repositoryOwner(), subject.repositoryName());
        if (credential == null || credential.token().length == 0
                || !credential.repositoryExternalId().equals(
                        subject.repositoryExternalId())) {
            if (credential != null) {
                Arrays.fill(credential.token(), '\0');
            }
            return new PollResult(null, Failure.UNAVAILABLE);
        }
        char[] token = credential.token();
        Budget budget = new Budget(clock);
        try {
            PrIdentity before = readPr(subject, token, budget);
            if (before == null) {
                return unavailable(budget);
            }
            if (!matchesSubject(subject, before)) {
                return new PollResult(null, Failure.INVALID);
            }
            Collected first = collect(subject, token, budget);
            Collected second = collect(subject, token, budget);
            if (first == null || second == null
                    || !first.digest().equals(second.digest())
                    || !first.suites().equals(second.suites())
                    || !first.runs().equals(second.runs())) {
                return unavailable(budget);
            }
            Map<SelectorIdentity, GitHubCheckSelector> required =
                    new HashMap<>();
            for (GitHubCheckSelector selector : selectors) {
                required.put(
                        new SelectorIdentity(
                                selector.appId(), selector.name()),
                        selector);
            }
            Instant observedAt = clock.instant();
            List<NormalizedCheck> checks = new ArrayList<>();
            Map<String, byte[]> logs = new LinkedHashMap<>();
            for (Run run : second.runs()) {
                GitHubCheckSelector selector = required.get(
                        new SelectorIdentity(run.appId(), run.name()));
                if (selector == null) {
                    continue;
                }
                String revision = digest(
                        "github-check-state:v1",
                        Long.toString(run.id()),
                        Long.toString(run.suiteId()),
                        Long.toString(run.appId()),
                        run.appSlug(),
                        run.headSha(), run.name(), run.status(),
                        Objects.toString(run.conclusion(), ""),
                        Objects.toString(run.startedAt(), ""),
                        Objects.toString(run.completedAt(), ""),
                        Objects.toString(run.detailsUrl(), ""));
                NormalizedCheck check = new NormalizedCheck(
                        run.headSha(), selector.key(),
                        Long.toString(run.id()),
                        Long.toString(run.suiteId()),
                        1, revision, run.name(), run.status(),
                        run.conclusion(), run.startedAt(), run.completedAt(),
                        observedAt,
                        "github-check-run:" + run.id() + ":" + revision);
                checks.add(check);
            }
            Set<String> accepted = activation.policy()
                    .acceptedConclusions().stream()
                    .map(GitHubCiProvider::normalize)
                    .collect(Collectors.toUnmodifiableSet());
            Map<SelectorIdentity, RunSelection> selections = new HashMap<>();
            boolean collecting = false;
            boolean failed = false;
            boolean needsAttention = false;
            for (SelectorIdentity selector : required.keySet()) {
                RunSelection selection = selectLatestRun(second.runs().stream()
                        .filter(run -> selector.equals(new SelectorIdentity(
                                run.appId(), run.name())))
                        .toList());
                selections.put(selector, selection);
                if (selection.ambiguous()) {
                    needsAttention = true;
                    continue;
                }
                Run selected = selection.run();
                if (selected == null || !terminal(selected.status())) {
                    collecting = true;
                    continue;
                }
                String conclusion = normalize(selected.conclusion());
                if (!accepted.contains(conclusion)) {
                    if ("FAILURE".equals(conclusion)) {
                        failed = true;
                    }
                    else {
                        needsAttention = true;
                    }
                }
            }
            if (!collecting && !needsAttention && failed) {
                for (RunSelection selection : selections.values()) {
                    Run selected = selection.run();
                    if (selected == null || !terminal(selected.status())) {
                        continue;
                    }
                    if (!"FAILURE".equals(normalize(selected.conclusion()))
                            || accepted.contains("FAILURE")) {
                        continue;
                    }
                    byte[] log = readActionsLog(
                            subject, selected, token, budget);
                    if (log == null) {
                        return unavailable(budget);
                    }
                    logs.put(Long.toString(selected.id()), log);
                }
            }
            PrIdentity after = readPr(subject, token, budget);
            if (after == null) {
                return unavailable(budget);
            }
            if (!matchesSubject(subject, after)) {
                return new PollResult(null, Failure.INVALID);
            }
            if (!before.equals(after)) {
                return unavailable(budget);
            }
            checks.sort(Comparator
                    .comparing(NormalizedCheck::selectorKey)
                    .thenComparing(NormalizedCheck::providerRunId)
                    .thenComparing(NormalizedCheck::providerCheckId));
            String batchDigest = batchDigest(
                    subject, activation, second, checks, logs);
            return new PollResult(
                    new CompleteBatch(
                            activation, checks, logs, batchDigest), null);
        }
        catch (StableObservationException unsupported) {
            return new PollResult(null, Failure.UNSUPPORTED);
        }
        catch (RuntimeException unavailable) {
            return unavailable(budget);
        }
        finally {
            Arrays.fill(token, '\0');
        }
    }

    private static PollResult unavailable(Budget budget)
    {
        return new PollResult(
                null, Failure.UNAVAILABLE, budget.retryNotBefore);
    }

    private PrIdentity readPr(
            CiObservationSubject subject, char[] token, Budget budget)
    {
        JsonNode root = getJson(
                api("/repos/" + subject.repositoryOwner() + "/"
                        + subject.repositoryName() + "/pulls/"
                        + subject.prNumber()), token, budget);
        if (root == null || !root.isObject()
                || !root.path("node_id").isTextual()
                || !root.path("number").canConvertToInt()
                || !root.path("state").isTextual()) {
            return null;
        }
        JsonNode base = root.path("base");
        JsonNode head = root.path("head");
        JsonNode baseRepo = base.path("repo");
        JsonNode headRepo = head.path("repo");
        if (!baseRepo.path("id").canConvertToLong()
                || !headRepo.path("id").canConvertToLong()
                || !base.path("ref").isTextual()
                || !base.path("sha").isTextual()
                || !head.path("ref").isTextual()
                || !head.path("sha").isTextual()
                || !baseRepo.path("owner").path("login").isTextual()
                || !baseRepo.path("name").isTextual()
                || !headRepo.path("owner").path("login").isTextual()
                || !headRepo.path("name").isTextual()) {
            return null;
        }
        return new PrIdentity(
                root.path("number").intValue(),
                root.path("state").textValue(),
                root.path("node_id").textValue(),
                baseRepo.path("id").longValue(),
                baseRepo.path("owner").path("login").textValue(),
                baseRepo.path("name").textValue(),
                base.path("ref").textValue(), base.path("sha").textValue(),
                headRepo.path("id").longValue(),
                headRepo.path("owner").path("login").textValue(),
                headRepo.path("name").textValue(),
                head.path("ref").textValue(), head.path("sha").textValue());
    }

    private Collected collect(
            CiObservationSubject subject, char[] token, Budget budget)
    {
        List<Suite> suites = new ArrayList<>();
        Set<Long> suiteIds = new HashSet<>();
        Integer suiteTotal = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode root = getJson(api("/repos/"
                    + subject.repositoryOwner() + "/"
                    + subject.repositoryName() + "/commits/"
                    + subject.proposedHead()
                    + "/check-suites?per_page=" + PAGE_SIZE
                    + "&page=" + page), token, budget);
            if (root == null || !root.path("total_count").canConvertToInt()
                    || !root.path("check_suites").isArray()) {
                return null;
            }
            int total = root.path("total_count").intValue();
            if (total < 0 || suiteTotal != null && suiteTotal != total) {
                return null;
            }
            suiteTotal = total;
            JsonNode values = root.path("check_suites");
            if (values.size() > PAGE_SIZE) {
                return null;
            }
            for (JsonNode value : values) {
                if (!value.path("id").canConvertToLong()
                        || !value.path("app").path("id").canConvertToLong()
                        || !value.path("head_sha").isTextual()) {
                    return null;
                }
                Suite suite = new Suite(
                        value.path("id").longValue(),
                        value.path("app").path("id").longValue(),
                        value.path("head_sha").textValue());
                if (suite.id() < 1 || suite.appId() < 1
                        || !suite.headSha().equals(subject.proposedHead())
                        || !suiteIds.add(suite.id())) {
                    return null;
                }
                suites.add(suite);
                if (suites.size() > MAX_SUITES) {
                    budget.exceed();
                }
            }
            if (suites.size() == suiteTotal) {
                break;
            }
            if (values.size() < PAGE_SIZE
                    || suites.size() > suiteTotal
                    || page == MAX_PAGES) {
                return null;
            }
        }
        if (suiteTotal == null || suites.size() != suiteTotal) {
            return null;
        }
        suites.sort(Comparator.comparingLong(Suite::id));
        List<Run> runs = new ArrayList<>();
        Set<Long> runIds = new HashSet<>();
        for (Suite suite : suites) {
            Integer totalRuns = null;
            int collected = 0;
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonNode root = getJson(api("/repos/"
                        + subject.repositoryOwner() + "/"
                        + subject.repositoryName() + "/check-suites/"
                        + suite.id() + "/check-runs?filter=all&per_page="
                        + PAGE_SIZE + "&page=" + page), token, budget);
                if (root == null
                        || !root.path("total_count").canConvertToInt()
                        || !root.path("check_runs").isArray()) {
                    return null;
                }
                int total = root.path("total_count").intValue();
                if (total < 0 || totalRuns != null && totalRuns != total) {
                    return null;
                }
                totalRuns = total;
                JsonNode values = root.path("check_runs");
                if (values.size() > PAGE_SIZE) {
                    return null;
                }
                for (JsonNode value : values) {
                    Run run = readRun(value, suite, subject.proposedHead());
                    if (run == null || !runIds.add(run.id())) {
                        return null;
                    }
                    runs.add(run);
                    if (runs.size() > MAX_RUNS) {
                        budget.exceed();
                    }
                }
                collected += values.size();
                if (collected == totalRuns) {
                    break;
                }
                if (values.size() < PAGE_SIZE || collected > totalRuns
                        || page == MAX_PAGES) {
                    return null;
                }
            }
            if (totalRuns == null || collected != totalRuns) {
                return null;
            }
        }
        runs.sort(Comparator.comparingLong(Run::id));
        String digest = enumerationDigest(suites, runs);
        return new Collected(List.copyOf(suites), List.copyOf(runs), digest);
    }

    private static Run readRun(
            JsonNode value, Suite suite, String headSha)
    {
        if (!value.path("id").canConvertToLong()
                || !value.path("check_suite").path("id").canConvertToLong()
                || !value.path("app").path("id").canConvertToLong()
                || !value.path("app").path("slug").isTextual()
                || !value.path("head_sha").isTextual()
                || !value.path("name").isTextual()
                || !value.path("status").isTextual()) {
            return null;
        }
        long suiteId = value.path("check_suite").path("id").longValue();
        long appId = value.path("app").path("id").longValue();
        if (suiteId != suite.id() || appId != suite.appId()
                || !headSha.equals(value.path("head_sha").textValue())) {
            return null;
        }
        return new Run(
                value.path("id").longValue(), suiteId, appId,
                value.path("app").path("slug").textValue(), headSha,
                value.path("name").textValue(),
                value.path("status").textValue(),
                textOrNull(value.path("conclusion")),
                instantOrNull(value.path("started_at")),
                instantOrNull(value.path("completed_at")),
                textOrNull(value.path("details_url")));
    }

    private byte[] readActionsLog(
            CiObservationSubject subject,
            Run run,
            char[] token,
            Budget budget)
    {
        if (!"github-actions".equals(run.appSlug())) {
            throw new StableObservationException();
        }
        if (run.detailsUrl() == null) {
            return null;
        }
        Matcher matcher = ACTIONS_JOB.matcher(run.detailsUrl());
        if (!matcher.matches()) {
            throw new StableObservationException();
        }
        if (!matcher.group(1).equals(subject.repositoryOwner())
                || !matcher.group(2).equals(subject.repositoryName())) {
            throw new StableObservationException();
        }
        long jobId;
        try {
            jobId = Long.parseLong(matcher.group(3));
        }
        catch (NumberFormatException invalid) {
            return null;
        }
        budget.request();
        CiHttpResponse first = http.get(api("/repos/"
                + subject.repositoryOwner() + "/" + subject.repositoryName()
                + "/actions/jobs/" + jobId + "/logs"), token, LOG_LIMIT);
        budget.response(first);
        byte[] bytes;
        if (first.complete() && first.statusCode() == 200) {
            bytes = first.body();
        }
        else if (first.complete() && first.statusCode() == 302
                && first.location() != null) {
            URI location;
            try {
                location = URI.create(first.location());
            }
            catch (IllegalArgumentException invalid) {
                return null;
            }
            if (!"https".equals(location.getScheme())
                    || location.getHost() == null
                    || location.getUserInfo() != null) {
                return null;
            }
            budget.request();
            CiHttpResponse redirected = http.get(
                    location, null, LOG_LIMIT);
            budget.response(redirected);
            if (!redirected.complete()
                    || redirected.statusCode() != 200) {
                return null;
            }
            bytes = redirected.body();
        }
        else {
            return null;
        }
        if (bytes.length == 0 || bytes.length > LOG_LIMIT
                || !strictUtf8(bytes)) {
            return null;
        }
        String tokenValue = new String(token);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.contains(tokenValue)) {
            return ("GitHub Actions log omitted because it contained "
                    + "the repository credential.\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
        return bytes;
    }

    private JsonNode getJson(URI uri, char[] token, Budget budget)
    {
        budget.request();
        CiHttpResponse response = http.get(uri, token, JSON_LIMIT);
        budget.response(response);
        if (!response.complete() || response.statusCode() != 200
                || response.body().length == 0) {
            return null;
        }
        try {
            return json.readTree(response.body());
        }
        catch (IOException malformed) {
            return null;
        }
    }

    private static boolean matchesSubject(
            CiObservationSubject subject, PrIdentity identity)
    {
        return identity.prNumber() == subject.prNumber()
                && identity.state().equals("open")
                && identity.nodeId().equals(subject.prNodeId())
                && Long.toString(identity.baseRepositoryId()).equals(
                        subject.repositoryExternalId())
                && identity.baseOwner().equals(subject.repositoryOwner())
                && identity.baseName().equals(subject.repositoryName())
                && identity.baseRef().equals(subject.targetBaseRef())
                && Long.toString(identity.headRepositoryId()).equals(
                        subject.headRepositoryExternalId())
                && identity.headOwner().equals(
                        subject.headRepositoryOwner())
                && identity.headName().equals(
                        subject.headRepositoryName())
                && identity.headRef().equals(subject.branchName())
                && identity.headSha().equals(subject.proposedHead());
    }

    private static GitHubCheckSelector parseSelector(String value)
    {
        return GitHubCheckSelector.parse(value);
    }

    private static String textOrNull(JsonNode value)
    {
        return value.isTextual() ? value.textValue() : null;
    }

    private static Instant instantOrNull(JsonNode value)
    {
        if (!value.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(value.textValue());
        }
        catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean terminal(String status)
    {
        return "COMPLETED".equals(normalize(status));
    }

    private static RunSelection selectLatestRun(List<Run> runs)
    {
        if (runs.size() == 1) {
            return new RunSelection(runs.getFirst(), false);
        }
        if (runs.isEmpty()) {
            return new RunSelection(null, false);
        }
        if (runs.stream().anyMatch(run -> run.startedAt() == null)) {
            return new RunSelection(null, true);
        }
        Instant latest = runs.stream()
                .map(Run::startedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        List<Run> selected = runs.stream()
                .filter(run -> run.startedAt().equals(latest))
                .toList();
        return selected.size() == 1
                ? new RunSelection(selected.getFirst(), false)
                : new RunSelection(null, true);
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean strictUtf8(byte[] bytes)
    {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        }
        catch (CharacterCodingException invalid) {
            return false;
        }
    }

    private static URI api(String pathAndQuery)
    {
        return URI.create("https://api.github.com" + pathAndQuery);
    }

    private static String enumerationDigest(
            List<Suite> suites, List<Run> runs)
    {
        List<String> fields = new ArrayList<>();
        fields.add("github-ci-enumeration:v1");
        fields.add(Integer.toString(suites.size()));
        for (Suite suite : suites) {
            fields.add("suite");
            fields.add(Long.toString(suite.id()));
            fields.add(Long.toString(suite.appId()));
            fields.add(suite.headSha());
        }
        fields.add(Integer.toString(runs.size()));
        for (Run run : runs) {
            fields.add("run");
            fields.add(Long.toString(run.id()));
            fields.add(Long.toString(run.suiteId()));
            fields.add(Long.toString(run.appId()));
            fields.add(run.appSlug());
            fields.add(run.headSha());
            fields.add(run.name());
            fields.add(run.status());
            fields.add(Objects.toString(run.conclusion(), ""));
            fields.add(Objects.toString(run.startedAt(), ""));
            fields.add(Objects.toString(run.completedAt(), ""));
            fields.add(Objects.toString(run.detailsUrl(), ""));
        }
        return digest(fields.toArray(String[]::new));
    }

    private static String batchDigest(
            CiObservationSubject subject,
            CiObservationActivation activation,
            Collected collected,
            List<NormalizedCheck> checks,
            Map<String, byte[]> logs)
    {
        List<String> fields = new ArrayList<>();
        fields.add("github-ci-batch:v1");
        fields.add(subject.receiptId());
        fields.add(activation.policy().policyRevisionId());
        fields.add(collected.digest());
        fields.add(Integer.toString(checks.size()));
        for (NormalizedCheck check : checks) {
            fields.add("check");
            fields.add(check.selectorKey());
            fields.add(check.providerCheckId());
            fields.add(check.providerRunId());
            fields.add(Long.toString(check.attempt()));
            fields.add(check.providerStateRevision());
        }
        fields.add(Integer.toString(logs.size()));
        logs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    fields.add("log");
                    fields.add(entry.getKey());
                    fields.add(digestBytes(entry.getValue()));
                });
        return digest(fields.toArray(String[]::new));
    }

    private static String digest(String... fields)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length)
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String digestBytes(byte[] value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static final class DirectCiHttp
            implements CiHttp
    {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new NoProxySelector())
                .build();

        @Override
        public CiHttpResponse get(
                URI uri, char[] token, int responseLimit)
        {
            requireNonNull(uri, "uri is null");
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || responseLimit < 1 || responseLimit > LOG_LIMIT) {
                return new CiHttpResponse(
                        false, -1, new byte[0], null, null, null);
            }
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .GET();
                if (token != null) {
                    request.header(
                            "Authorization", "Bearer " + new String(token));
                }
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                AtomicBoolean overflow = new AtomicBoolean();
                HttpResponse<Void> response = client.send(
                        request.build(),
                        HttpResponse.BodyHandlers.ofByteArrayConsumer(
                                bytes -> bytes.ifPresent(chunk -> {
                                    if (body.size() + chunk.length
                                            > responseLimit) {
                                        overflow.set(true);
                                        throw new ResponseTooLargeException();
                                    }
                                    body.writeBytes(chunk);
                                })));
                if (overflow.get()) {
                    return new CiHttpResponse(
                            false, response.statusCode(), new byte[0], null,
                            response.headers().firstValue("Retry-After")
                                    .orElse(null),
                            response.headers().firstValue("X-RateLimit-Reset")
                                    .orElse(null));
                }
                return new CiHttpResponse(
                        true, response.statusCode(), body.toByteArray(),
                        response.headers().firstValue("Location").orElse(null),
                        response.headers().firstValue("Retry-After").orElse(null),
                        response.headers().firstValue("X-RateLimit-Reset")
                                .orElse(null));
            }
            catch (IOException | ResponseTooLargeException failure) {
                return new CiHttpResponse(
                        false, -1, new byte[0], null, null, null);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new CiHttpResponse(
                        false, -1, new byte[0], null, null, null);
            }
        }

        List<Proxy> proxies(URI uri)
        {
            return client.proxy().orElseThrow().select(uri);
        }
    }

    private static final class NoProxySelector
            extends ProxySelector
    {
        @Override
        public List<Proxy> select(URI uri)
        {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(
                URI uri, SocketAddress address, IOException failure) {}
    }

    private static final class ResponseTooLargeException
            extends RuntimeException {}

    private static final class BudgetExceededException
            extends RuntimeException {}

    private static final class StableObservationException
            extends RuntimeException {}
}
