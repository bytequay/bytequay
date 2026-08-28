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
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.app.ci.BoundedCiLog;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiJob;
import com.bytequay.app.ci.CiRerunResult;
import com.bytequay.app.ci.CiStep;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.ci.ReadCiLogQuery;
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PullRequestRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.bytequay.app.ci.CiRerunResult.Outcome.ACCEPTED;
import static com.bytequay.app.ci.CiRerunResult.Outcome.OBSOLETE;
import static com.bytequay.app.ci.CiRerunResult.Outcome.REJECTED;
import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.extractGitHubErrorMessage;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.bytequay.app.repository.github.GitHubApiSupport.translating;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubActionsClient
        implements com.bytequay.app.ci.GitHubActionsClient
{
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final int MAX_TERMINAL_ERRORS = 20;
    private static final int MAX_ERROR_LINE = 500;
    private static final String LOG_MEDIA_TYPE = "text/plain; charset=utf-8";
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1B(?:[@-Z\\\\-_]|\\[[0-?]*[ -/]*[@-~])");
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\s+");
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*(?:bearer|token)\\s+)[^\\s]+");
    private static final Pattern GITHUB_TOKEN = Pattern.compile(
            "\\b(?:github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9]+)\\b");

    private final RestClient gitHubRestClient;
    private final RestClient logRestClient;
    private final Supplier<String> tokenSupplier;
    private final LogBlobWriter blobWriter;

    @Autowired
    public GitHubActionsClient(
            RestClient gitHubRestClient,
            @Qualifier("gitHubLogRestClient") RestClient logRestClient,
            CredentialService credentials,
            TaskLogStore taskLogStore)
    {
        this(
                gitHubRestClient,
                logRestClient,
                credentials::resolveGitHubToken,
                taskLogStore::writeBlob);
    }

    GitHubActionsClient(
            RestClient gitHubRestClient,
            RestClient logRestClient,
            Supplier<String> tokenSupplier,
            LogBlobWriter blobWriter)
    {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
        this.logRestClient = requireNonNull(logRestClient, "logRestClient is null");
        this.tokenSupplier = requireNonNull(tokenSupplier, "tokenSupplier is null");
        this.blobWriter = requireNonNull(blobWriter, "blobWriter is null");
    }

    public List<PrCheckRunState> fetchPrCheckRuns(String pat, String owner, String repo, String sha)
    {
        return refreshPrCheckRuns(
                pat,
                owner,
                repo,
                sha,
                ImmutableList.of(),
                Optional.empty(),
                Optional.empty()).checkRuns();
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
        ConditionalChecks checkRuns = fetchCheckRuns(pat, owner, repo, sha, safeCheckRunsEtag);
        ConditionalChecks commitStatuses = fetchCommitStatuses(pat, owner, repo, sha, commitStatusesEtag);
        List<PrCheckRunState> result = new ArrayList<>();
        result.addAll(checkRuns.values().orElseGet(() -> checksFrom(existing, "check_run")));
        result.addAll(commitStatuses.values().orElseGet(() -> checksFrom(existing, "status")));
        return new PrCheckRunsFetch(result, checkRuns.etag(), commitStatuses.etag());
    }

    private static boolean fitsOnOnePage(List<PrCheckRunState> checkRuns)
    {
        return checkRuns.stream().filter(check -> "check_run".equals(check.source())).count() <= PAGE_SIZE;
    }

    private ConditionalChecks fetchCheckRuns(
            String pat,
            String owner,
            String repo,
            String sha,
            Optional<String> etag)
    {
        List<PrCheckRunState> result = new ArrayList<>();
        int collected = 0;
        Optional<String> responseEtag = etag;
        for (int page = 1; page <= MAX_PAGES; page++) {
            int currentPage = page;
            RestClient.RequestHeadersSpec<?> request = gitHubRestClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/commits/{sha}/check-runs")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", currentPage)
                            .build(owner, repo, sha))
                    .header("Authorization", authorization(pat));
            if (currentPage == 1) {
                etag.ifPresent(value -> request.header(HttpHeaders.IF_NONE_MATCH, value));
            }
            ResponseEntity<GitHubCheckRunsResponse> entity = translating(() -> request.retrieve().toEntity(GitHubCheckRunsResponse.class));
            if (currentPage == 1) {
                responseEtag = Optional.ofNullable(entity.getHeaders().getETag()).or(() -> etag);
                if (entity.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                    return new ConditionalChecks(Optional.empty(), responseEtag);
                }
            }
            GitHubCheckRunsResponse response = entity.getBody();
            if (response == null || response.checkRuns() == null) {
                break;
            }
            for (GitHubCheckRunsResponse.CheckRun check : response.checkRuns()) {
                result.add(new PrCheckRunState(
                        check.id(),
                        check.name(),
                        check.status(),
                        check.conclusion(),
                        check.detailsUrl(),
                        "check_run",
                        check.completedAt()));
            }
            collected += response.checkRuns().size();
            if (collected >= response.totalCount() || response.checkRuns().size() < PAGE_SIZE) {
                break;
            }
        }
        return new ConditionalChecks(Optional.of(ImmutableList.copyOf(result)), responseEtag);
    }

    private ConditionalChecks fetchCommitStatuses(
            String pat,
            String owner,
            String repo,
            String sha,
            Optional<String> etag)
    {
        return translating(() -> {
            RestClient.RequestHeadersSpec<?> request = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/commits/{sha}/status", owner, repo, sha)
                    .header("Authorization", authorization(pat));
            etag.ifPresent(value -> request.header(HttpHeaders.IF_NONE_MATCH, value));
            ResponseEntity<GitHubCombinedStatusResponse> entity = request.retrieve().toEntity(GitHubCombinedStatusResponse.class);
            Optional<String> responseEtag = Optional.ofNullable(entity.getHeaders().getETag()).or(() -> etag);
            if (entity.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                return new ConditionalChecks(Optional.empty(), responseEtag);
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
                            "pending".equals(status.state()) ? null : status.updatedAt()))
                    .collect(toImmutableList());
            return new ConditionalChecks(Optional.of(statuses), responseEtag);
        });
    }

    private static List<PrCheckRunState> checksFrom(List<PrCheckRunState> checks, String source)
    {
        return checks.stream().filter(check -> source.equals(check.source())).collect(toImmutableList());
    }

    public record PrCheckRunsFetch(
            List<PrCheckRunState> checkRuns,
            Optional<String> checkRunsEtag,
            Optional<String> commitStatusesEtag)
    {
        public PrCheckRunsFetch
        {
            checkRuns = ImmutableList.copyOf(requireNonNull(checkRuns, "checkRuns is null"));
            requireNonNull(checkRunsEtag, "checkRunsEtag is null");
            requireNonNull(commitStatusesEtag, "commitStatusesEtag is null");
        }
    }

    private record ConditionalChecks(Optional<List<PrCheckRunState>> values, Optional<String> etag)
    {
        private ConditionalChecks
        {
            requireNonNull(values, "values is null");
            requireNonNull(etag, "etag is null");
        }
    }

    @Override
    public CiHeadSnapshot inspect(InspectCiHeadQuery query)
    {
        requireNonNull(query, "query is null");
        String pat = token();
        PullRequestRef pullRequest = query.pullRequest();
        PullRequestResponse response = fetchPullRequest(pat, pullRequest);
        if (response.user() == null || response.head() == null || response.base() == null
                || response.user().login() == null || response.head().sha() == null
                || response.head().ref() == null || response.base().sha() == null
                || response.base().ref() == null || response.htmlUrl() == null) {
            throw badGateway("GitHub returned incomplete pull-request head facts");
        }
        boolean sameRepository = response.head().repo() != null
                && response.head().repo().fullName() != null
                && pullRequest.repoFullName().equalsIgnoreCase(response.head().repo().fullName());
        List<CiWorkflowRun> runs = fetchWorkflowRuns(pat, pullRequest, response.head().sha());
        return new CiHeadSnapshot(
                pullRequest,
                "open".equalsIgnoreCase(response.state()),
                response.user().login(),
                sameRepository,
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
        String pat = token();
        PullRequestRef pullRequest = query.pullRequest();
        LogHop first = requestLogRedirect(pat, pullRequest, query.job().id(), query.maximumBytes());
        if (first.unavailableReason() != null) {
            return BoundedCiLog.unavailable(first.unavailableReason());
        }
        RawLog raw = first.raw() != null
                ? first.raw()
                : downloadRedirect(first.redirect(), query.maximumBytes());
        byte[] fullyRedacted = redact(raw.bytes(), pat);
        byte[] redacted = fullyRedacted.length <= query.maximumBytes()
                ? fullyRedacted
                : Arrays.copyOf(fullyRedacted, query.maximumBytes());
        boolean truncated = raw.truncated() || redacted.length < fullyRedacted.length;
        BlobRef blob = blobWriter.write(query.taskId(), ByteBuffer.wrap(redacted), LOG_MEDIA_TYPE);
        return new BoundedCiLog(
                Optional.of(blob),
                redacted.length,
                truncated,
                terminalErrors(new String(redacted, StandardCharsets.UTF_8)),
                Optional.empty());
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
                    .uri(
                            "/repos/{owner}/{repo}/actions/runs/{runId}/rerun-failed-jobs",
                            command.pullRequest().owner(),
                            command.pullRequest().repo(),
                            command.workflowRunId())
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

    private PullRequestResponse fetchPullRequest(String pat, PullRequestRef pullRequest)
    {
        return translating(() -> {
            PullRequestResponse response = gitHubRestClient.get()
                    .uri(
                            "/repos/{owner}/{repo}/pulls/{number}",
                            pullRequest.owner(),
                            pullRequest.repo(),
                            pullRequest.number())
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(PullRequestResponse.class);
            if (response == null) {
                throw badGateway("GitHub returned an empty pull request");
            }
            return response;
        });
    }

    private List<CiWorkflowRun> fetchWorkflowRuns(String pat, PullRequestRef pullRequest, String headSha)
    {
        Map<Long, WorkflowRunResponse> latest = new LinkedHashMap<>();
        int collected = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            WorkflowRunsResponse response = fetchWorkflowRunPage(pat, pullRequest, headSha, page);
            List<WorkflowRunResponse> rows = Optional.ofNullable(response.workflowRuns()).orElse(ImmutableList.of());
            for (WorkflowRunResponse row : rows) {
                if (row.id() > 0 && headSha.equals(row.headSha())) {
                    latest.merge(row.id(), row, GitHubActionsClient::newerRun);
                }
            }
            collected += rows.size();
            if (rows.isEmpty() || collected >= response.totalCount()) {
                break;
            }
        }
        return latest.values().stream()
                .map(run -> toWorkflowRun(pat, pullRequest, run))
                .sorted(Comparator.comparing(CiWorkflowRun::name).thenComparingLong(CiWorkflowRun::id))
                .collect(toImmutableList());
    }

    private WorkflowRunsResponse fetchWorkflowRunPage(
            String pat,
            PullRequestRef pullRequest,
            String headSha,
            int page)
    {
        return translating(() -> {
            WorkflowRunsResponse response = gitHubRestClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/actions/runs")
                            .queryParam("head_sha", headSha)
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", page)
                            .build(pullRequest.owner(), pullRequest.repo()))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(WorkflowRunsResponse.class);
            return response == null ? new WorkflowRunsResponse(0, ImmutableList.of()) : response;
        });
    }

    private CiWorkflowRun toWorkflowRun(
            String pat,
            PullRequestRef pullRequest,
            WorkflowRunResponse run)
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

    private List<CiJob> fetchJobs(
            String pat,
            PullRequestRef pullRequest,
            long workflowRunId,
            int workflowAttempt,
            String workflowUrl)
    {
        Map<String, JobResponse> latest = new LinkedHashMap<>();
        int collected = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            JobsResponse response = fetchJobPage(pat, pullRequest, workflowRunId, page);
            List<JobResponse> rows = Optional.ofNullable(response.jobs()).orElse(ImmutableList.of());
            for (JobResponse row : rows) {
                if (row.id() > 0 && row.name() != null) {
                    latest.merge(row.name(), row, GitHubActionsClient::newerJob);
                }
            }
            collected += rows.size();
            if (rows.isEmpty() || collected >= response.totalCount()) {
                break;
            }
        }
        return latest.values().stream()
                .map(job -> toJob(workflowRunId, workflowAttempt, workflowUrl, job))
                .sorted(Comparator.comparing(CiJob::name).thenComparingLong(CiJob::id))
                .collect(toImmutableList());
    }

    private JobsResponse fetchJobPage(
            String pat,
            PullRequestRef pullRequest,
            long workflowRunId,
            int page)
    {
        return translating(() -> {
            JobsResponse response = gitHubRestClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/actions/runs/{runId}/jobs")
                            .queryParam("filter", "all")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", page)
                            .build(pullRequest.owner(), pullRequest.repo(), workflowRunId))
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(JobsResponse.class);
            return response == null ? new JobsResponse(0, ImmutableList.of()) : response;
        });
    }

    private static CiJob toJob(
            long workflowRunId,
            int workflowAttempt,
            String workflowUrl,
            JobResponse job)
    {
        int attempt = job.runAttempt() <= 0 ? workflowAttempt : job.runAttempt();
        List<CiStep> steps = Optional.ofNullable(job.steps()).orElse(ImmutableList.of()).stream()
                .filter(step -> step.number() > 0 && step.name() != null && step.status() != null)
                .map(step -> new CiStep(
                        step.number(),
                        step.name(),
                        step.status(),
                        Optional.ofNullable(step.conclusion())))
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
            WorkflowRunResponse response = gitHubRestClient.get()
                    .uri(
                            "/repos/{owner}/{repo}/actions/runs/{runId}",
                            pullRequest.owner(),
                            pullRequest.repo(),
                            runId)
                    .header("Authorization", authorization(pat))
                    .retrieve()
                    .body(WorkflowRunResponse.class);
            if (response == null) {
                throw badGateway("GitHub returned an empty workflow run");
            }
            return response;
        });
    }

    private LogHop requestLogRedirect(
            String pat,
            PullRequestRef pullRequest,
            long jobId,
            int maximumBytes)
    {
        return gitHubRestClient.get()
                .uri(
                        "/repos/{owner}/{repo}/actions/jobs/{jobId}/logs",
                        pullRequest.owner(),
                        pullRequest.repo(),
                        jobId)
                .header("Authorization", authorization(pat))
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 200) {
                        return new LogHop(null, readBounded(response.getBody(), maximumBytes), null);
                    }
                    if (status == 302 || status == 303 || status == 307 || status == 308) {
                        URI location = response.getHeaders().getLocation();
                        if (location == null || !safeLogLocation(location)) {
                            return LogHop.unavailable("GitHub returned an unsafe or missing log location");
                        }
                        return new LogHop(location, null, null);
                    }
                    if (status == 404 || status == 410) {
                        return LogHop.unavailable("GitHub Actions log is missing or expired");
                    }
                    throw new ResponseStatusException(
                            HttpStatusCode.valueOf(status),
                            "GitHub Actions log request failed with status " + status);
                });
    }

    private RawLog downloadRedirect(URI location, int maximumBytes)
    {
        return logRestClient.get()
                .uri(location)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status != 200) {
                        throw new ResponseStatusException(
                                HttpStatusCode.valueOf(status),
                                "GitHub Actions log download failed with status " + status);
                    }
                    return readBounded(response.getBody(), maximumBytes);
                });
    }

    private static RawLog readBounded(InputStream input, int maximumBytes)
            throws IOException
    {
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        boolean truncated = bytes.length > maximumBytes;
        return new RawLog(truncated ? Arrays.copyOf(bytes, maximumBytes) : bytes, truncated);
    }

    private static byte[] redact(byte[] bytes, String pat)
    {
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("GITHUB_TOKEN", pat));
        String text = redactor.redact(new String(bytes, StandardCharsets.UTF_8));
        text = AUTHORIZATION_VALUE.matcher(text).replaceAll("$1[REDACTED]");
        text = GITHUB_TOKEN.matcher(text).replaceAll("[REDACTED]");
        return text.getBytes(StandardCharsets.UTF_8);
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
        return normalized.length() <= MAX_ERROR_LINE
                ? normalized
                : normalized.substring(0, MAX_ERROR_LINE);
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
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
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

    private record RawLog(byte[] bytes, boolean truncated) {}

    private record LogHop(
            URI redirect,
            RawLog raw,
            String unavailableReason)
    {
        private static LogHop unavailable(String reason)
        {
            return new LogHop(null, null, reason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PullRequestResponse(
            String state,
            UserResponse user,
            BranchResponse head,
            BranchResponse base,
            @JsonProperty("html_url") String htmlUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserResponse(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BranchResponse(String ref, String sha, RepositoryResponse repo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RepositoryResponse(@JsonProperty("full_name") String fullName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkflowRunsResponse(
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("workflow_runs") List<WorkflowRunResponse> workflowRuns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkflowRunResponse(
            long id,
            @JsonProperty("workflow_id") long workflowId,
            String name,
            @JsonProperty("display_title") String displayTitle,
            @JsonProperty("run_attempt") int runAttempt,
            String status,
            String conclusion,
            @JsonProperty("head_sha") String headSha,
            @JsonProperty("html_url") String htmlUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobsResponse(
            @JsonProperty("total_count") int totalCount,
            List<JobResponse> jobs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JobResponse(
            long id,
            String name,
            @JsonProperty("run_attempt") int runAttempt,
            String status,
            String conclusion,
            @JsonProperty("html_url") String htmlUrl,
            List<StepResponse> steps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StepResponse(
            int number,
            String name,
            String status,
            String conclusion) {}
}
