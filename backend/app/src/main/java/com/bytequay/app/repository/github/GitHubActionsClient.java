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

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.app.ci.BoundedCiLog;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiJob;
import com.bytequay.app.ci.CiLogExcerpt;
import com.bytequay.app.ci.CiRawLogStore;
import com.bytequay.app.ci.CiRerunResult;
import com.bytequay.app.ci.CiStep;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.ReadCiLogQuery;
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.app.repository.github.GitHubPaginator.Page;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Ascii;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.bytequay.app.ci.CiRerunResult.Outcome.ACCEPTED;
import static com.bytequay.app.ci.CiRerunResult.Outcome.OBSOLETE;
import static com.bytequay.app.ci.CiRerunResult.Outcome.REJECTED;
import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.extractGitHubErrorMessage;
import static com.bytequay.app.repository.github.GitHubApiSupport.get;
import static com.bytequay.app.repository.github.GitHubApiSupport.requireBody;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.bytequay.app.repository.github.GitHubPaginator.forEachCompletePage;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubActionsClient
        implements com.bytequay.app.ci.GitHubActionsClient
{
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final int MAX_TERMINAL_ERRORS = 20;
    private static final int MAX_ERROR_LINE = 500;
    private static final int MAX_ANNOTATIONS = 10;
    /** The job log already ends with this line. */
    private static final Pattern EXIT_STATUS_ANNOTATION = Pattern.compile("Process completed with exit code \\d+\\.?");
    private static final String TAIL_NOTE = "No diagnostic keywords or stack trace matched. Showing only the log tail (at most 32 KiB); "
            + "request the cached raw log for complete evidence.\n";
    private static final String LOG_MEDIA_TYPE = "text/plain; charset=utf-8";
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])");
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\s+");
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile("(?i)(authorization\\s*[:=]\\s*(?:bearer|token)\\s+)[^\\s]+");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\b(?:github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9]+)\\b");

    private final RestClient gitHubRestClient;
    private final RestClient logRestClient;
    private final Supplier<String> tokenSupplier;
    private final LogBlobWriter blobWriter;
    private final CiRawLogStore rawLogs;
    /** CI polls reread mostly unchanged evidence, and GitHub does not count a 304 against the rate limit. */
    private final Cache<String, ConditionalRead> conditionalReads = CacheBuilder.newBuilder().maximumSize(256).build();

    @Autowired
    public GitHubActionsClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubLogRestClient") RestClient logRestClient,
            CredentialService credentials,
            TaskLogStore taskLogStore,
            CiRawLogStore rawLogs)
    {
        this(gitHubRestClient, logRestClient, credentials::resolveGitHubToken, taskLogStore::writeBlob, rawLogs);
    }

    GitHubActionsClient(RestClient gitHubRestClient, RestClient logRestClient, Supplier<String> tokenSupplier, LogBlobWriter blobWriter, CiRawLogStore rawLogs)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.logRestClient = requireNonNull(logRestClient, "logRestClient is null");
        this.tokenSupplier = requireNonNull(tokenSupplier, "tokenSupplier is null");
        this.blobWriter = requireNonNull(blobWriter, "blobWriter is null");
        this.rawLogs = requireNonNull(rawLogs, "rawLogs is null");
    }

    public PrCheckRunsFetch refreshPrCheckRuns(
            String pat,
            String owner,
            String repo,
            String sha,
            List<PrCheckRunState> existing,
            Optional<String> checkRunsEtag,
            Optional<String> commitStatusesEtag)
    {
        requireNonNull(existing, "existing is null");
        requireNonNull(checkRunsEtag, "checkRunsEtag is null");
        requireNonNull(commitStatusesEtag, "commitStatusesEtag is null");
        // The stored ETag is captured from the first page alone, so it can only
        // vouch for one page. On a commit whose check runs need a second page, a
        // change past page one leaves page one byte-identical, and honouring the
        // ETag would report the stale result forever.
        Optional<String> safeCheckRunsEtag = fitsOnOnePage(existing) ? checkRunsEtag : Optional.empty();
        ConditionalList<PrCheckRunState> checkRuns = fetchCheckRuns(pat, owner, repo, sha, safeCheckRunsEtag);
        ConditionalList<PrCheckRunState> commitStatuses = fetchCommitStatuses(pat, owner, repo, sha, commitStatusesEtag);
        List<PrCheckRunState> result = new ArrayList<>();
        result.addAll(checkRuns.values().orElseGet(() -> checksFrom(existing, "check_run")));
        result.addAll(commitStatuses.values().orElseGet(() -> checksFrom(existing, "status")));
        return new PrCheckRunsFetch(result, checkRuns.etag(), commitStatuses.etag());
    }

    private static boolean fitsOnOnePage(List<PrCheckRunState> checkRuns)
    {
        return checkRuns.stream().filter(check -> "check_run".equals(check.source())).count() <= PAGE_SIZE;
    }

    private ConditionalList<PrCheckRunState> fetchCheckRuns(String pat, String owner, String repo, String sha, Optional<String> etag)
    {
        ConditionalList<GitHubCheckRunsResponse.CheckRun> checks = new GitHubPaginator(gitHubRestClient).paginateConditional(MAX_PAGES, pat,
                "/repos/{owner}/{repo}/commits/{sha}/check-runs", new ParameterizedTypeReference<GitHubCheckRunsResponse>() {}, etag,
                GitHubCheckRunsResponse::checkRuns, GitHubCheckRunsResponse::totalCount, owner, repo, sha);
        return new ConditionalList<>(checks.values().map(rows -> rows.stream().map(check -> new PrCheckRunState(
                check.id(), check.name(), check.status(), check.conclusion(), check.detailsUrl(), "check_run", check.startedAt(), check.completedAt()))
                .collect(toImmutableList())), checks.etag());
    }

    private ConditionalList<PrCheckRunState> fetchCommitStatuses(String pat, String owner, String repo, String sha, Optional<String> etag)
    {
        return translating(() -> {
            RestClient.RequestHeadersSpec<?> request = get(gitHubRestClient, pat, "/repos/{owner}/{repo}/commits/{sha}/status", owner, repo, sha);
            etag.ifPresent(value -> request.header(HttpHeaders.IF_NONE_MATCH, value));
            ResponseEntity<GitHubCombinedStatusResponse> entity = request.retrieve().toEntity(GitHubCombinedStatusResponse.class);
            Optional<String> responseEtag = Optional.ofNullable(entity.getHeaders().getETag()).or(() -> etag);
            if (entity.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                return new ConditionalList<>(Optional.empty(), responseEtag);
            }
            List<PrCheckRunState> statuses = Optional.ofNullable(entity.getBody())
                    .map(GitHubCombinedStatusResponse::statuses)
                    .orElse(ImmutableList.of()).stream()
                    .map(status -> new PrCheckRunState(
                            status.id(),
                            status.context(),
                            "pending".equals(status.state()) ? "in_progress" : "completed",
                            switch (Optional.ofNullable(status.state()).orElse("")) {
                                case "success" -> "success";
                                case "failure", "error" -> "failure";
                                default -> null;
                            },
                            status.targetUrl(),
                            "status",
                            // ponytail: statuses are immutable, so a pending one was posted when its context started;
                            // a CI that re-posts pending progress moves this forward
                            "pending".equals(status.state()) ? status.updatedAt() : null,
                            "pending".equals(status.state()) ? null : status.updatedAt()))
                    .collect(toImmutableList());
            return new ConditionalList<>(Optional.of(statuses), responseEtag);
        });
    }

    private static List<PrCheckRunState> checksFrom(List<PrCheckRunState> checks, String source)
    {
        return checks.stream().filter(check -> source.equals(check.source())).collect(toImmutableList());
    }

    public record PrCheckRunsFetch(List<PrCheckRunState> checkRuns, Optional<String> checkRunsEtag, Optional<String> commitStatusesEtag)
    {
        public PrCheckRunsFetch
        {
            checkRuns = ImmutableList.copyOf(requireNonNull(checkRuns, "checkRuns is null"));
            requireNonNull(checkRunsEtag, "checkRunsEtag is null");
            requireNonNull(commitStatusesEtag, "commitStatusesEtag is null");
        }
    }

    @Override
    public String readDescription(PullRequestRef pullRequest)
    {
        return Optional.ofNullable(fetchPullRequest(token(), pullRequest).body()).orElse("");
    }

    @Override
    public CiHeadSnapshot inspect(PullRequestRef pullRequest)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        String pat = token();
        PullRequestResponse response = fetchPullRequest(pat, pullRequest);
        if (response.user() == null || response.head() == null || response.base() == null
                || response.user().login() == null || response.head().sha() == null
                || response.head().ref() == null || response.base().sha() == null
                || response.base().ref() == null || response.htmlUrl() == null
                || response.head().repo() == null || response.head().repo().fullName() == null) {
            throw badGateway("GitHub returned incomplete pull-request head facts");
        }
        RepositoryIdentity headRepository;
        try {
            headRepository = RepositoryIdentity.valueOf(response.head().repo().fullName());
        }
        catch (IllegalArgumentException invalid) {
            throw badGateway("GitHub returned an invalid pull-request head repository");
        }
        List<CiWorkflowRun> runs = fetchWorkflowRuns(pat, pullRequest, response.head().sha());
        return new CiHeadSnapshot(
                pullRequest,
                "open".equalsIgnoreCase(response.state()),
                response.user().login(),
                RepoRef.of(headRepository.owner(), headRepository.name()),
                response.head().ref(),
                response.head().sha(),
                response.base().ref(),
                response.base().sha(),
                response.htmlUrl(),
                runs);
    }

    @Override
    public BoundedCiLog readLog(ReadCiLogQuery query)
    {
        requireNonNull(query, "query is null");
        Optional<String> unavailable = rawLogs.cache(query, path -> retrieveLog(token(), query.pullRequest(), query.job().id(), path));
        if (unavailable.isPresent()) {
            return BoundedCiLog.unavailable(unavailable.orElseThrow());
        }
        String logId = CiRawLogStore.id(query);
        String text = rawLogs.read(query.taskId(), logId);
        String rawNote = "Raw log: read_ci_raw_log({\"logId\":\"" + logId + "\"}).\n";
        int budget = query.maximumBytes() - rawNote.length() - TAIL_NOTE.length();
        if (budget <= 0) {
            return BoundedCiLog.unavailable("CI log byte limit is too small to include log retrieval details");
        }
        CiLogExcerpt excerpt = CiLogExcerpt.from(text, Math.min(budget, 32 * 1024), budget);
        byte[] stored = (rawNote + (excerpt.diagnosticsMatched() ? "" : TAIL_NOTE) + excerpt.text()).getBytes(StandardCharsets.UTF_8);
        BlobRef blob = blobWriter.write(query.taskId(), ByteBuffer.wrap(stored), LOG_MEDIA_TYPE);
        return new BoundedCiLog(Optional.of(blob), excerpt.compacted(), terminalErrors(text), Optional.empty(), Optional.of(logId), excerpt.diagnosticsMatched());
    }

    /** Uses the same full download and excerpt selection without creating task-owned storage. */
    public String readJobLog(PullRequestRef pullRequest, long jobId)
    {
        try {
            Path temporary = Files.createTempFile("bytequay-ci-", ".log");
            try {
                Optional<String> unavailable = retrieveLog(token(), pullRequest, jobId, temporary);
                if (unavailable.isPresent()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, unavailable.orElseThrow());
                }
                CiLogExcerpt excerpt = CiLogExcerpt.from(Files.readString(temporary), 32 * 1024, 1024 * 1024);
                return (excerpt.diagnosticsMatched() ? "" : "No diagnostic keywords or stack trace matched. Showing only the log tail (at most 32 KiB).\n") + excerpt.text();
            }
            finally {
                Files.deleteIfExists(temporary);
            }
        }
        catch (IOException failure) {
            throw new UncheckedIOException("Cannot read CI log", failure);
        }
    }

    @Override
    public List<String> readFailureAnnotations(PullRequestRef pullRequest, long jobId)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        String pat = token();
        List<AnnotationResponse> annotations;
        try {
            // An Actions job is its own check run.
            annotations = get(gitHubRestClient, pat, "/repos/{owner}/{repo}/check-runs/{jobId}/annotations?per_page=100", pullRequest.owner(), pullRequest.repo(), jobId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<AnnotationResponse>>() {});
        }
        catch (RestClientException failure) {
            // Annotations only add context; a token without check-run access still gets the log.
            return ImmutableList.of();
        }
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("GITHUB_TOKEN", pat));
        return Optional.ofNullable(annotations).orElse(ImmutableList.of()).stream()
                .filter(annotation -> "failure".equals(annotation.level()) && annotation.message() != null)
                .map(annotation -> Optional.ofNullable(annotation.title()).filter(title -> !title.isBlank()).map(title -> title + ": ").orElse("") + annotation.message())
                .map(message -> normalizeLogLine(redact(message, redactor)))
                .filter(message -> !message.isBlank() && !EXIT_STATUS_ANNOTATION.matcher(message).matches())
                .distinct()
                .limit(MAX_ANNOTATIONS)
                .collect(toImmutableList());
    }

    private Optional<String> retrieveLog(String pat, PullRequestRef pullRequest, long jobId, Path destination)
    {
        String unavailableReason = "GitHub Actions log could not be downloaded";
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("GitHub Actions log retrieval was interrupted");
            }
            try {
                // Each retry obtains a new short-lived storage URL.
                LogHop first = requestLogRedirect(pat, pullRequest, jobId, destination);
                return first.redirect().isEmpty() ? first.unavailableReason() : downloadRedirect(first.redirect().orElseThrow(), pat, destination);
            }
            catch (ResponseStatusException failure) {
                int status = failure.getStatusCode().value();
                if (Thread.currentThread().isInterrupted() || (status != 408 && status != 429 && !failure.getStatusCode().is5xxServerError())) {
                    throw failure;
                }
                if (status == 429) {
                    return Optional.of("GitHub Actions log retrieval is rate limited; no immediate retry was attempted");
                }
                unavailableReason = "GitHub Actions log retrieval failed after 2 attempts (HTTP " + status + ")";
            }
            catch (ResourceAccessException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
                unavailableReason = "GitHub Actions log retrieval failed after 2 attempts because of a network or read timeout error";
            }
        }
        return Optional.of(unavailableReason);
    }

    @Override
    public Set<String> failedJobsOnBranch(PullRequestRef pullRequest, long workflowId, String branch)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        String pat = token();
        // Push runs only: a fork's pull request may come from a branch with the same name.
        WorkflowRunsResponse runs = translating(() -> requireBody(conditional(
                "branch-runs/" + pullRequest.owner() + "/" + pullRequest.repo() + "/" + workflowId + "/" + branch,
                get(gitHubRestClient, pat, uri -> uri.path("/repos/{owner}/{repo}/actions/workflows/{workflowId}/runs")
                        .queryParam("branch", branch)
                        .queryParam("event", "push")
                        .queryParam("status", "completed")
                        .queryParam("per_page", 1)
                        .build(pullRequest.owner(), pullRequest.repo(), workflowId)),
                WorkflowRunsResponse.class), "GitHub returned empty base-branch workflow runs"));
        return Optional.ofNullable(runs.rows()).orElse(ImmutableList.of()).stream().findFirst()
                .map(run -> fetchJobs(pat, pullRequest, run.id(), positiveOrOne(run.runAttempt()), run.htmlUrl()).stream()
                        .filter(CiJob::actionableFailure)
                        .map(CiJob::name)
                        .collect(toImmutableSet()))
                .orElse(ImmutableSet.of());
    }

    @Override
    public CiRerunResult rerunFailedJobs(RerunFailedJobsCommand command)
    {
        requireNonNull(command, "command is null");
        if (!command.authorized()) {
            return new CiRerunResult(REJECTED, "Failed-job rerun was not authorized");
        }
        String pat = token();
        WorkflowRunResponse run = fetchWorkflowRun(pat, command.pullRequest(), command.workflowRunId());
        Optional<String> conclusion = Optional.ofNullable(run.conclusion())
                .map(value -> value.toLowerCase(Locale.ROOT));
        if (!command.headSha().equals(run.headSha())
                || positiveOrOne(run.runAttempt()) != command.previousAttempt()
                || !"completed".equalsIgnoreCase(run.status())
                || !actionable(conclusion)) {
            return new CiRerunResult(OBSOLETE, "Workflow run no longer matches the journaled failed attempt");
        }
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/actions/runs/{runId}/rerun-failed-jobs", command.pullRequest().owner(), command.pullRequest().repo(), command.workflowRunId())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .toBodilessEntity();
            return new CiRerunResult(ACCEPTED, "GitHub accepted the failed-job rerun");
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 403) {
                return new CiRerunResult(REJECTED, boundedReason(e, "GitHub denied the failed-job rerun"));
            }
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 409
                    || e.getStatusCode().value() == 422) {
                return new CiRerunResult(OBSOLETE, boundedReason(e, "Workflow run is no longer rerunnable"));
            }
            throw toReadableException(e);
        }
    }

    /**
     * Sends the ETag of the previous read of the same page and reuses that body on a 304. Every page is still
     * requested, so a 304 vouches only for its own page.
     */
    private <T> T conditional(String key, RestClient.RequestHeadersSpec<?> request, Class<T> type)
    {
        ConditionalRead cached = conditionalReads.getIfPresent(key);
        if (cached != null) {
            request.header(HttpHeaders.IF_NONE_MATCH, cached.etag());
        }
        ResponseEntity<T> entity = request.retrieve().toEntity(type);
        if (cached != null && entity.getStatusCode() == HttpStatus.NOT_MODIFIED) {
            return type.cast(cached.body());
        }
        T body = entity.getBody();
        String etag = entity.getHeaders().getETag();
        if (body == null || etag == null) {
            conditionalReads.invalidate(key);
        }
        else {
            conditionalReads.put(key, new ConditionalRead(etag, body));
        }
        return body;
    }

    private record ConditionalRead(String etag, Object body) {}

    private PullRequestResponse fetchPullRequest(String pat, PullRequestRef pullRequest)
    {
        return translating(() -> requireBody(conditional(
                "pulls/" + pullRequest.owner() + "/" + pullRequest.repo() + "/" + pullRequest.number(),
                get(gitHubRestClient, pat, "/repos/{owner}/{repo}/pulls/{number}", pullRequest.owner(), pullRequest.repo(), pullRequest.number()),
                PullRequestResponse.class), "GitHub returned an empty pull request"));
    }

    private List<CiWorkflowRun> fetchWorkflowRuns(String pat, PullRequestRef pullRequest, String headSha)
    {
        Map<Long, WorkflowRunResponse> latest = new LinkedHashMap<>();
        Set<String> observedAttempts = new HashSet<>();
        forEachCompletePage(MAX_PAGES, page -> fetchWorkflowRunPage(pat, pullRequest, headSha, page), row -> {
            if (row == null || row.id() <= 0 || row.workflowId() <= 0 || !headSha.equals(row.headSha())) {
                throw badGateway("GitHub returned an invalid current-head workflow run");
            }
            if (!observedAttempts.add(row.id() + ":" + positiveOrOne(row.runAttempt()))) {
                throw badGateway("GitHub returned duplicate workflow attempts during pagination");
            }
            latest.merge(row.id(), row, GitHubActionsClient::newerRun);
        }, "run");
        Map<WorkflowKey, WorkflowRunResponse> workflows = new LinkedHashMap<>();
        // A description edit replaces its PR-event run, but must not hide another trigger's failure.
        latest.values().forEach(run -> workflows.merge(new WorkflowKey(run.workflowId(), Optional.ofNullable(run.event())), run,
                (first, second) -> first.id() > second.id() ? first : second));
        return workflows.values().stream()
                .map(run -> toWorkflowRun(pat, pullRequest, run))
                .sorted(Comparator.comparing(CiWorkflowRun::name).thenComparingLong(CiWorkflowRun::id))
                .collect(toImmutableList());
    }

    private WorkflowRunsResponse fetchWorkflowRunPage(String pat, PullRequestRef pullRequest, String headSha, int page)
    {
        return translating(() -> conditional(
                "runs/" + pullRequest.owner() + "/" + pullRequest.repo() + "/" + headSha + "/" + page,
                get(gitHubRestClient, pat, uri -> uri.path("/repos/{owner}/{repo}/actions/runs")
                        .queryParam("head_sha", headSha)
                        .queryParam("per_page", PAGE_SIZE)
                        .queryParam("page", page)
                        .build(pullRequest.owner(), pullRequest.repo())),
                WorkflowRunsResponse.class));
    }

    private CiWorkflowRun toWorkflowRun(String pat, PullRequestRef pullRequest, WorkflowRunResponse run)
    {
        int attempt = positiveOrOne(run.runAttempt());
        return new CiWorkflowRun(
                run.id(),
                run.workflowId(),
                firstNonBlank(run.name(), run.displayTitle(), "Workflow " + run.workflowId()),
                attempt,
                firstNonBlank(run.status(), "queued"),
                Optional.ofNullable(run.conclusion()),
                run.headSha(),
                firstNonBlank(run.htmlUrl(), "https://github.com/"),
                fetchJobs(pat, pullRequest, run.id(), attempt, run.htmlUrl()));
    }

    private List<CiJob> fetchJobs(String pat, PullRequestRef pullRequest, long workflowRunId, int workflowAttempt, String workflowUrl)
    {
        Map<String, JobResponse> latest = new LinkedHashMap<>();
        Set<Long> observedJobs = new HashSet<>();
        forEachCompletePage(MAX_PAGES, page -> fetchJobPage(pat, pullRequest, workflowRunId, page), row -> {
            if (row == null || row.id() <= 0 || row.name() == null) {
                throw badGateway("GitHub returned an invalid workflow job");
            }
            if (!observedJobs.add(row.id())) {
                throw badGateway("GitHub returned duplicate workflow jobs during pagination");
            }
            latest.merge(row.name(), row, GitHubActionsClient::newerJob);
        }, "job");
        return latest.values().stream()
                .map(job -> toJob(workflowRunId, workflowAttempt, workflowUrl, job))
                .sorted(Comparator.comparing(CiJob::name).thenComparingLong(CiJob::id))
                .collect(toImmutableList());
    }

    private JobsResponse fetchJobPage(String pat, PullRequestRef pullRequest, long workflowRunId, int page)
    {
        return translating(() -> conditional(
                "jobs/" + pullRequest.owner() + "/" + pullRequest.repo() + "/" + workflowRunId + "/" + page,
                get(gitHubRestClient, pat, uri -> uri.path("/repos/{owner}/{repo}/actions/runs/{runId}/jobs")
                        .queryParam("filter", "all")
                        .queryParam("per_page", PAGE_SIZE)
                        .queryParam("page", page)
                        .build(pullRequest.owner(), pullRequest.repo(), workflowRunId)),
                JobsResponse.class));
    }

    /** A CI result is usable only after all reported rows have been read and individually validated. */
    private static CiJob toJob(long workflowRunId, int workflowAttempt, String workflowUrl, JobResponse job)
    {
        int attempt = job.runAttempt() <= 0 ? workflowAttempt : job.runAttempt();
        List<CiStep> steps = Optional.ofNullable(job.steps()).orElse(ImmutableList.of()).stream()
                .filter(step -> step.number() > 0 && step.name() != null && step.status() != null)
                .map(step -> new CiStep(step.number(), step.name(), step.status(), Optional.ofNullable(step.conclusion())))
                .collect(toImmutableList());
        return new CiJob(
                job.id(),
                workflowRunId,
                job.name(),
                attempt,
                firstNonBlank(job.status(), "queued"),
                Optional.ofNullable(job.conclusion()),
                firstNonBlank(job.htmlUrl(), workflowUrl, "https://github.com/"),
                steps);
    }

    private WorkflowRunResponse fetchWorkflowRun(String pat, PullRequestRef pullRequest, long runId)
    {
        return translating(() -> {
            WorkflowRunResponse response = requireBody(get(gitHubRestClient, pat, "/repos/{owner}/{repo}/actions/runs/{runId}", pullRequest.owner(), pullRequest.repo(), runId)
                    .retrieve()
                    .body(WorkflowRunResponse.class), "GitHub returned an empty workflow run");
            return response;
        });
    }

    private LogHop requestLogRedirect(String pat, PullRequestRef pullRequest, long jobId, Path destination)
    {
        return get(gitHubRestClient, pat, "/repos/{owner}/{repo}/actions/jobs/{jobId}/logs", pullRequest.owner(), pullRequest.repo(), jobId)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 200) {
                        writeRedacted(response.getBody(), destination, pat);
                        return new LogHop(Optional.empty(), Optional.empty());
                    }
                    if (status == 302 || status == 303 || status == 307 || status == 308) {
                        URI location = response.getHeaders().getLocation();
                        if (location == null || !safeLogLocation(location)) {
                            return LogHop.unavailable("GitHub returned an unsafe or missing log location");
                        }
                        return new LogHop(Optional.of(location), Optional.empty());
                    }
                    if (status == 404 || status == 410) {
                        return LogHop.unavailable("GitHub Actions log is missing or expired");
                    }
                    if (status == 206 || status == 416) {
                        return LogHop.unavailable("GitHub did not return a complete raw log");
                    }
                    throw new ResponseStatusException(HttpStatusCode.valueOf(status), "GitHub Actions log request failed with status " + status);
                });
    }

    private Optional<String> downloadRedirect(URI location, String pat, Path destination)
    {
        return logRestClient.get().uri(location).header(HttpHeaders.ACCEPT_ENCODING, "identity").exchange((request, response) -> {
            int status = response.getStatusCode().value();
            if (status == 404 || status == 410) {
                return Optional.of("GitHub Actions log is missing or expired");
            }
            if (status == 206 || status == 416) {
                return Optional.of("GitHub did not return a complete raw log");
            }
            if (status != 200) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(status), "GitHub Actions log download failed with status " + status);
            }
            writeRedacted(response.getBody(), destination, pat);
            return Optional.empty();
        });
    }

    private static void writeRedacted(InputStream input, Path destination, String pat)
            throws IOException
    {
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("GITHUB_TOKEN", pat));
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                var writer = rawLogWriter(destination)) {
            char[] buffer = new char[8192];
            StringBuilder line = new StringBuilder();
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("CI log download interrupted");
                }
                for (int index = 0; index < count; index++) {
                    line.append(buffer[index]);
                    if (buffer[index] == '\n') {
                        writer.write(redact(line.toString(), redactor));
                        line.setLength(0);
                    }
                }
            }
            if (!line.isEmpty()) {
                writer.write(redact(line.toString(), redactor));
            }
            if (writer.checkError()) {
                throw new UncheckedIOException("Cannot store raw CI log", new IOException("Raw log file write failed"));
            }
        }
    }

    private static PrintWriter rawLogWriter(Path destination)
    {
        try {
            // Keep local write failures distinct from retriable HTTP stream failures.
            return new PrintWriter(Files.newBufferedWriter(destination, StandardCharsets.UTF_8));
        }
        catch (IOException failure) {
            throw new UncheckedIOException("Cannot store raw CI log", failure);
        }
    }

    private static String redact(String text, SecretRedactor redactor)
    {
        text = redactor.redact(text);
        text = AUTHORIZATION_VALUE.matcher(text).replaceAll("$1[REDACTED]");
        return GITHUB_TOKEN.matcher(text).replaceAll("[REDACTED]");
    }

    private static List<String> terminalErrors(String log)
    {
        List<String> errors = log.lines()
                .map(GitHubActionsClient::normalizeLogLine)
                .filter(line -> {
                    String lower = line.toLowerCase(Locale.ROOT);
                    return lower.contains("error")
                            || lower.contains("exception")
                            || lower.contains("fail")
                            || lower.contains("fatal");
                })
                .filter(line -> !line.isBlank())
                .collect(toImmutableList());
        int first = Math.max(0, errors.size() - MAX_TERMINAL_ERRORS);
        return ImmutableList.copyOf(errors.subList(first, errors.size()));
    }

    private static String normalizeLogLine(String value)
    {
        String normalized = ANSI_ESCAPE.matcher(value).replaceAll("");
        normalized = TIMESTAMP_PREFIX.matcher(normalized).replaceFirst("");
        normalized = normalized.strip().replaceAll("\\s+", " ");
        return Ascii.truncate(normalized, MAX_ERROR_LINE, "");
    }

    private String token()
    {
        String pat = tokenSupplier.get();
        requirePat(pat);
        return pat;
    }

    private static boolean safeLogLocation(URI location)
    {
        return "https".equalsIgnoreCase(location.getScheme())
                && location.getHost() != null
                && location.getUserInfo() == null;
    }

    private static WorkflowRunResponse newerRun(WorkflowRunResponse first, WorkflowRunResponse second)
    {
        return positiveOrOne(second.runAttempt()) >= positiveOrOne(first.runAttempt()) ? second : first;
    }

    private static JobResponse newerJob(JobResponse first, JobResponse second)
    {
        int firstAttempt = positiveOrOne(first.runAttempt());
        int secondAttempt = positiveOrOne(second.runAttempt());
        if (secondAttempt != firstAttempt) {
            return secondAttempt > firstAttempt ? second : first;
        }
        return second.id() >= first.id() ? second : first;
    }

    private static int positiveOrOne(int value)
    {
        return Math.max(1, value);
    }

    private static boolean actionable(Optional<String> conclusion)
    {
        return conclusion.filter(value -> switch (value) {
            case "action_required", "cancelled", "failure", "stale", "startup_failure", "timed_out" -> true;
            default -> false;
        }).isPresent();
    }

    private static String boundedReason(RestClientResponseException failure, String fallback)
    {
        String reason = Optional.ofNullable(extractGitHubErrorMessage(failure.getResponseBodyAsString()))
                .filter(value -> !value.isBlank())
                .orElse(fallback);
        return Ascii.truncate(reason, 500, "");
    }

    private static String firstNonBlank(String... values)
    {
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseThrow();
    }

    private static ResponseStatusException badGateway(String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(502), message);
    }

    @FunctionalInterface
    interface LogBlobWriter
    {
        BlobRef write(TaskId taskId, ByteBuffer content, String mediaType);
    }

    private record LogHop(Optional<URI> redirect, Optional<String> unavailableReason)
    {
        private static LogHop unavailable(String reason)
        {
            return new LogHop(Optional.empty(), Optional.of(reason));
        }
    }

    private record PullRequestResponse(
            String state,
            String body,
            GitHubUser user,
            BranchResponse head,
            BranchResponse base,
            @JsonProperty("html_url") String htmlUrl) {}

    private record BranchResponse(String ref, String sha, RepositoryResponse repo) {}

    private record RepositoryResponse(@JsonProperty("full_name") String fullName) {}

    private record WorkflowRunsResponse(
            @JsonProperty("total_count") Integer totalCount,
            @JsonProperty("workflow_runs") List<WorkflowRunResponse> rows)
            implements Page<WorkflowRunResponse> {}

    private record WorkflowKey(long workflowId, Optional<String> event) {}

    private record WorkflowRunResponse(
            long id,
            @JsonProperty("workflow_id") long workflowId,
            String event,
            String name,
            @JsonProperty("display_title") String displayTitle,
            @JsonProperty("run_attempt") int runAttempt,
            String status,
            String conclusion,
            @JsonProperty("head_sha") String headSha,
            @JsonProperty("html_url") String htmlUrl) {}

    private record JobsResponse(
            @JsonProperty("total_count") Integer totalCount,
            @JsonProperty("jobs") List<JobResponse> rows)
            implements Page<JobResponse> {}

    private record JobResponse(
            long id,
            String name,
            @JsonProperty("run_attempt") int runAttempt,
            String status,
            String conclusion,
            @JsonProperty("html_url") String htmlUrl,
            List<StepResponse> steps) {}

    private record AnnotationResponse(@JsonProperty("annotation_level") String level, String title, String message) {}

    private record StepResponse(
            int number,
            String name,
            String status,
            String conclusion) {}

    private record GitHubCheckRunsResponse(@JsonProperty("total_count") int totalCount, @JsonProperty("check_runs") List<CheckRun> checkRuns)
    {
        public record CheckRun(
                Long id,
                String name,
                String status,
                String conclusion,
                @JsonProperty("details_url") String detailsUrl,
                @JsonProperty("started_at") Instant startedAt,
                @JsonProperty("completed_at") Instant completedAt) {}
    }

    private record GitHubCombinedStatusResponse(List<Status> statuses)
    {
        public record Status(
                Long id,
                String context,
                String state,
                @JsonProperty("target_url") String targetUrl,
                @JsonProperty("updated_at") Instant updatedAt) {}
    }
}
