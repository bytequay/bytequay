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
import com.bytequay.app.ci.CiLogExcerpt;
import com.bytequay.app.ci.CiRerunResult;
import com.bytequay.app.ci.CiStep;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.ci.ReadCiLogQuery;
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
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
import org.springframework.web.client.RestClientException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
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
    private static final Pattern LOG_CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
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
            RestClient.RequestHeadersSpec<?> request = get(gitHubRestClient, pat, uri -> uri.path("/repos/{owner}/{repo}/commits/{sha}/check-runs")
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", currentPage)
                    .build(owner, repo, sha));
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
                        check.startedAt(),
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
            RestClient.RequestHeadersSpec<?> request = get(gitHubRestClient, pat, "/repos/{owner}/{repo}/commits/{sha}/status", owner, repo, sha);
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
                            // ponytail: statuses are immutable, so a pending one was posted when its context started;
                            // a CI that re-posts pending progress moves this forward
                            "pending".equals(status.state()) ? status.updatedAt() : null,
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
                || response.base().ref() == null || response.htmlUrl() == null
                || response.head().repo() == null || response.head().repo().fullName() == null) {
            throw badGateway("GitHub returned incomplete pull-request head facts");
        }
        String[] headRepository = response.head().repo().fullName().split("/", -1);
        if (headRepository.length != 2 || headRepository[0].isBlank() || headRepository[1].isBlank()) {
            throw badGateway("GitHub returned an invalid pull-request head repository");
        }
        List<CiWorkflowRun> runs = fetchWorkflowRuns(pat, pullRequest, response.head().sha());
        return new CiHeadSnapshot(
                pullRequest,
                "open".equalsIgnoreCase(response.state()),
                response.user().login(),
                RepoRef.of(headRepository[0], headRepository[1]),
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
        LogHop result = first.redirect() == null ? first : downloadRedirect(first.redirect(), query.maximumBytes());
        if (result.unavailableReason() != null) {
            return BoundedCiLog.unavailable(result.unavailableReason());
        }
        RawLog raw = result.raw();
        byte[] fullyRedacted = redact(raw.bytes(), pat);
        byte[] redacted = fullyRedacted.length <= query.maximumBytes()
                ? fullyRedacted
                : Arrays.copyOf(fullyRedacted, query.maximumBytes());
        boolean truncated = raw.truncated() || redacted.length < fullyRedacted.length;
        String text = new String(redacted, StandardCharsets.UTF_8);
        String captureNote = (raw.omittedBeginning() ? "[Beginning of log omitted; downloaded tail.]\n" : "")
                + (truncated ? "[Log byte limit reached; end of log omitted.]\n" : "");
        int maximumExcerptBytes = Math.min(query.maximumBytes(), 32 * 1024);
        if (captureNote.length() >= maximumExcerptBytes) {
            return BoundedCiLog.unavailable("CI log byte limit is too small to include omitted-content details");
        }
        CiLogExcerpt excerpt = CiLogExcerpt.from(text, maximumExcerptBytes - captureNote.length());
        byte[] stored = (captureNote + excerpt.text()).getBytes(StandardCharsets.UTF_8);
        BlobRef blob = blobWriter.write(query.taskId(), ByteBuffer.wrap(stored), LOG_MEDIA_TYPE);
        return new BoundedCiLog(
                Optional.of(blob),
                stored.length,
                truncated,
                raw.omittedBeginning(),
                excerpt.compacted(),
                terminalErrors(text),
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
            PullRequestResponse response = requireBody(get(gitHubRestClient, pat,
                    "/repos/{owner}/{repo}/pulls/{number}",
                            pullRequest.owner(),
                            pullRequest.repo(),
                            pullRequest.number())
                    .retrieve()
                    .body(PullRequestResponse.class), "GitHub returned an empty pull request");
            return response;
        });
    }

    private List<CiWorkflowRun> fetchWorkflowRuns(String pat, PullRequestRef pullRequest, String headSha)
    {
        Map<Long, WorkflowRunResponse> latest = new LinkedHashMap<>();
        Set<String> observedAttempts = new HashSet<>();
        int collected = 0;
        boolean complete = false;
        for (int page = 1; page <= MAX_PAGES; page++) {
            WorkflowRunsResponse response = fetchWorkflowRunPage(pat, pullRequest, headSha, page);
            List<WorkflowRunResponse> rows = response.workflowRuns();
            for (WorkflowRunResponse row : rows) {
                if (row == null || row.id() <= 0 || !headSha.equals(row.headSha())) {
                    throw badGateway("GitHub returned an invalid current-head workflow run");
                }
                if (!observedAttempts.add(row.id() + ":" + positiveOrOne(row.runAttempt()))) {
                    throw badGateway("GitHub returned duplicate workflow attempts during pagination");
                }
                latest.merge(row.id(), row, GitHubActionsClient::newerRun);
            }
            collected += rows.size();
            if (collected == response.totalCount()) {
                complete = true;
                break;
            }
            if (rows.isEmpty() || collected > response.totalCount()) {
                throw badGateway("GitHub returned incomplete workflow-run pages");
            }
        }
        if (!complete) {
            throw badGateway("GitHub workflow runs exceed the complete inspection limit");
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
            WorkflowRunsResponse response = get(gitHubRestClient, pat, uri -> uri.path("/repos/{owner}/{repo}/actions/runs")
                    .queryParam("head_sha", headSha)
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", page)
                    .build(pullRequest.owner(), pullRequest.repo()))
                    .retrieve()
                    .body(WorkflowRunsResponse.class);
            if (response == null || response.totalCount() == null || response.totalCount() < 0 || response.workflowRuns() == null) {
                throw badGateway("GitHub returned incomplete workflow-run evidence");
            }
            return response;
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
        Set<Long> observedJobs = new HashSet<>();
        int collected = 0;
        boolean complete = false;
        for (int page = 1; page <= MAX_PAGES; page++) {
            JobsResponse response = fetchJobPage(pat, pullRequest, workflowRunId, page);
            List<JobResponse> rows = response.jobs();
            for (JobResponse row : rows) {
                if (row == null || row.id() <= 0 || row.name() == null) {
                    throw badGateway("GitHub returned an invalid workflow job");
                }
                if (!observedJobs.add(row.id())) {
                    throw badGateway("GitHub returned duplicate workflow jobs during pagination");
                }
                latest.merge(row.name(), row, GitHubActionsClient::newerJob);
            }
            collected += rows.size();
            if (collected == response.totalCount()) {
                complete = true;
                break;
            }
            if (rows.isEmpty() || collected > response.totalCount()) {
                throw badGateway("GitHub returned incomplete workflow-job pages");
            }
        }
        if (!complete) {
            throw badGateway("GitHub workflow jobs exceed the complete inspection limit");
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
            JobsResponse response = get(gitHubRestClient, pat, uri -> uri.path("/repos/{owner}/{repo}/actions/runs/{runId}/jobs")
                    .queryParam("filter", "all")
                    .queryParam("per_page", PAGE_SIZE)
                    .queryParam("page", page)
                    .build(pullRequest.owner(), pullRequest.repo(), workflowRunId))
                    .retrieve()
                    .body(JobsResponse.class);
            if (response == null || response.totalCount() == null || response.totalCount() < 0 || response.jobs() == null) {
                throw badGateway("GitHub returned incomplete workflow-job evidence");
            }
            return response;
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
            WorkflowRunResponse response = requireBody(get(gitHubRestClient, pat, "/repos/{owner}/{repo}/actions/runs/{runId}", pullRequest.owner(), pullRequest.repo(), runId)
                    .retrieve()
                    .body(WorkflowRunResponse.class), "GitHub returned an empty workflow run");
            return response;
        });
    }

    private LogHop requestLogRedirect(
            String pat,
            PullRequestRef pullRequest,
            long jobId,
            int maximumBytes)
    {
        return get(gitHubRestClient, pat, "/repos/{owner}/{repo}/actions/jobs/{jobId}/logs", pullRequest.owner(), pullRequest.repo(), jobId)
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

    private LogHop downloadRedirect(URI location, int maximumBytes)
    {
        Optional<Long> length = logLength(location).filter(value -> value > maximumBytes);
        RestClient.RequestHeadersSpec<?> request = logRestClient.get()
                .uri(location)
                .header(HttpHeaders.ACCEPT_ENCODING, "identity");
        length.ifPresent(value -> request.header(HttpHeaders.RANGE, "bytes=" + (value - maximumBytes) + "-" + (value - 1)));
        return request.exchange((logRequest, response) -> {
            int status = response.getStatusCode().value();
            if (status == 404 || status == 410) {
                return LogHop.unavailable("GitHub Actions log is missing or expired");
            }
            if (status == 416) {
                return LogHop.unavailable("GitHub Actions log is empty or the requested byte range is unavailable");
            }
            if (status == 206) {
                if (length.isEmpty()) {
                    return LogHop.unavailable("GitHub returned an unsolicited ranged log response");
                }
                return readLogRange(response.getBody(), response.getHeaders(), maximumBytes, length.orElseThrow());
            }
            if (status != 200) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(status),
                        "GitHub Actions log download failed with status " + status);
            }
            return new LogHop(null, readBounded(response.getBody(), maximumBytes), null);
        });
    }

    private Optional<Long> logLength(URI location)
    {
        try {
            return logRestClient.head().uri(location).header(HttpHeaders.ACCEPT_ENCODING, "identity").exchange((request, response) -> {
                if (response.getStatusCode().value() != 200
                        || !"identity".equalsIgnoreCase(Optional.ofNullable(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).orElse("identity"))) {
                    return Optional.empty();
                }
                return Optional.of(response.getHeaders().getContentLength()).filter(value -> value >= 0);
            });
        }
        catch (RestClientException | IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static LogHop readLogRange(InputStream input, HttpHeaders headers, int maximumBytes, long expectedLength)
            throws IOException
    {
        Matcher range = LOG_CONTENT_RANGE.matcher(Optional.ofNullable(headers.getFirst(HttpHeaders.CONTENT_RANGE)).orElse(""));
        if (!range.matches() || !"identity".equalsIgnoreCase(Optional.ofNullable(headers.getFirst(HttpHeaders.CONTENT_ENCODING)).orElse("identity"))) {
            return LogHop.unavailable("GitHub returned an invalid ranged log response");
        }
        try {
            long first = Long.parseLong(range.group(1));
            long last = Long.parseLong(range.group(2));
            long total = Long.parseLong(range.group(3));
            if (total != expectedLength || first != total - maximumBytes || last != total - 1) {
                return LogHop.unavailable("GitHub returned an invalid ranged log response");
            }
            int expectedBytes = (int) (last - first + 1);
            byte[] bytes = input.readNBytes(expectedBytes + 1);
            if (bytes.length != expectedBytes) {
                return LogHop.unavailable("GitHub returned an incomplete ranged log response");
            }
            if (first > 0) {
                // A suffix can start inside a secret, before its recognizable token prefix.
                int start = 0;
                while (start < bytes.length && bytes[start] != '\n') {
                    start++;
                }
                bytes = Arrays.copyOfRange(bytes, Math.min(bytes.length, start + 1), bytes.length);
            }
            return new LogHop(null, new RawLog(bytes, false, first > 0), null);
        }
        catch (NumberFormatException ignored) {
            return LogHop.unavailable("GitHub returned an invalid ranged log response");
        }
    }

    private static RawLog readBounded(InputStream input, int maximumBytes)
            throws IOException
    {
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        boolean truncated = bytes.length > maximumBytes;
        if (truncated) {
            // A cut-off credential no longer matches full-value secret redaction.
            int end = maximumBytes;
            while (end > 0 && bytes[end - 1] != '\n') {
                end--;
            }
            bytes = Arrays.copyOf(bytes, end);
        }
        return new RawLog(bytes, truncated, false);
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

    private record RawLog(byte[] bytes, boolean truncated, boolean omittedBeginning) {}

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
            @JsonProperty("total_count") Integer totalCount,
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
            @JsonProperty("total_count") Integer totalCount,
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
