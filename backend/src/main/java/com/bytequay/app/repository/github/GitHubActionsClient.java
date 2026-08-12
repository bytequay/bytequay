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

import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrCheckRunState.GitHubMetadata;
import com.bytequay.app.domain.PrCheckRunState.PullRequestSubject;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.GitHubActionsRepository;
import com.bytequay.app.repository.GitHubActionsRepository.ActionsJobLogCapture;
import com.bytequay.app.repository.GitHubActionsRepository.ActionsJobLogStatus;
import com.bytequay.app.repository.GitHubActionsRepository.ActionsWorkflowJob;
import com.bytequay.app.repository.GitHubActionsRepository.ActionsWorkflowJobSetEvidence;
import com.bytequay.app.repository.GitHubActionsRepository.ActionsWorkflowJobStep;
import com.bytequay.app.repository.GitHubActionsRepository.ActionsWorkflowRun;
import com.bytequay.app.repository.GitHubActionsRepository.CheckRunAnnotation;
import com.bytequay.app.repository.GitHubActionsRepository.CheckRunAnnotationEvidence;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.repository.github.GitHubApiSupport.authorization;
import static com.bytequay.app.repository.github.GitHubApiSupport.requirePat;
import static com.bytequay.app.repository.github.GitHubApiSupport.toReadableException;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public class GitHubActionsClient
        implements GitHubActionsRepository {
    private static final Logger log = LoggerFactory.getLogger(GitHubActionsClient.class);

    private final RestClient gitHubRestClient;

    public GitHubActionsClient(RestClient gitHubRestClient) {
        this.gitHubRestClient = requireNonNull(gitHubRestClient, "gitHubRestClient is null");
    }

    @Override
    public List<PrCheckRunState> fetchPrCheckRuns(
            String pat, String owner, String repo, String sha) {
        return fetchPrCheckRuns(pat, owner, repo, sha, false);
    }

    @Override
    public List<PrCheckRunState> fetchPrCheckRunsStrict(
            String pat, String owner, String repo, String sha) {
        return fetchPrCheckRuns(pat, owner, repo, sha, true);
    }

    private List<PrCheckRunState> fetchPrCheckRuns(
            String pat, String owner, String repo, String sha, boolean strict) {
        ImmutableList.Builder<PrCheckRunState> out = ImmutableList.builder();
        int collected = 0;
        Integer strictTotal = null;
        boolean strictComplete = false;
        for (int page = 1; page <= 5; page++) {
            final int currentPage = page;
            try {
                GitHubCheckRunsResponse response =
                        gitHubRestClient
                                .get()
                                .uri(
                                        uri ->
                                                uri.path(
                                                                "/repos/{owner}/{repo}/commits/{sha}/check-runs")
                                                        .queryParam("per_page", 100)
                                                        .queryParam("page", currentPage)
                                                        .build(owner, repo, sha))
                                .header("Authorization", authorization(pat))
                                .retrieve()
                                .body(GitHubCheckRunsResponse.class);
                if (strict && (response == null || response.checkRuns() == null)) {
                    throw incompleteCheckRunsResponse(collected, strictTotal);
                }
                if (response == null || response.checkRuns() == null) {
                    break;
                }
                if (strict) {
                    if (strictTotal == null) {
                        strictTotal = response.totalCount();
                    }
                    else if (strictTotal != response.totalCount()) {
                        throw incompleteCheckRunsResponse(collected, strictTotal);
                    }
                }
                if (response.checkRuns().isEmpty()) {
                    strictComplete = strict && collected == strictTotal;
                    if (strict && !strictComplete) {
                        throw incompleteCheckRunsResponse(collected, strictTotal);
                    }
                    break;
                }
                for (GitHubCheckRunsResponse.CheckRun check : response.checkRuns()) {
                    out.add(
                            new PrCheckRunState(
                                    check.id(),
                                    check.name(),
                                    check.status(),
                                    check.conclusion(),
                                    check.htmlUrl(),
                                    check.output() == null ? null : check.output().title(),
                                    check.output() == null ? null : check.output().summary(),
                                    new GitHubMetadata(
                                            check.headSha(),
                                            check.externalId(),
                                            check.detailsUrl(),
                                            check.checkSuite() == null
                                                    ? null
                                                    : check.checkSuite().id(),
                                            check.app() == null ? null : check.app().id(),
                                            check.app() == null ? null : check.app().slug(),
                                            check.output() == null
                                                    ? null
                                                    : check.output().annotationsCount(),
                                            check.pullRequests() == null
                                                    ? List.of()
                                                    : check.pullRequests().stream()
                                                            .map(
                                                                    subject ->
                                                                            new PullRequestSubject(
                                                                                    subject
                                                                                            .number(),
                                                                                    subject.head()
                                                                                                    == null
                                                                                            ? null
                                                                                            : subject.head()
                                                                                                    .sha(),
                                                                                    subject.base()
                                                                                                    == null
                                                                                            ? null
                                                                                            : subject.base()
                                                                                                    .sha()))
                                                            .toList())));
                }
                collected += response.checkRuns().size();
                if (strict) {
                    strictComplete = collected == strictTotal;
                    if (strictComplete) {
                        break;
                    }
                    if (collected > strictTotal || response.checkRuns().size() < 100) {
                        throw incompleteCheckRunsResponse(collected, strictTotal);
                    }
                }
                else if (collected >= response.totalCount()
                        || response.checkRuns().size() < 100) {
                    break;
                }
            }
            catch (RestClientResponseException e) {
                if (strict) {
                    throw toReadableException(e);
                }
                break;
            }
        }
        if (strict && !strictComplete) {
            throw incompleteCheckRunsResponse(collected, strictTotal);
        }
        return out.build();
    }

    private static ResponseStatusException incompleteCheckRunsResponse(
            int collected, Integer total) {
        return new ResponseStatusException(
                HttpStatusCode.valueOf(502),
                "GitHub check-runs pagination was incomplete: collected "
                        + collected
                        + " of "
                        + total);
    }

    @Override
    public int rerunFailedChecks(String pat, RepoRef repo, String headSha) {
        if (headSha == null || headSha.isBlank()) {
            return 0;
        }
        try {
            GitHubWorkflowRunsResponse runs =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/repos/{owner}/{repo}/actions/runs")
                                                    .queryParam("head_sha", headSha)
                                                    .queryParam("per_page", 30)
                                                    .build(repo.owner(), repo.repo()))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubWorkflowRunsResponse.class);
            if (runs == null || runs.workflowRuns() == null) {
                return 0;
            }
            int reRun = 0;
            for (GitHubWorkflowRunsResponse.Run run : runs.workflowRuns()) {
                if (!"completed".equals(run.status()) || !isFailedConclusion(run.conclusion())) {
                    continue;
                }
                gitHubRestClient
                        .post()
                        .uri(
                                "/repos/{owner}/{repo}/actions/runs/{id}/rerun-failed-jobs",
                                repo.owner(),
                                repo.repo(),
                                run.id())
                        .header("Authorization", authorization(pat))
                        .retrieve()
                        .toBodilessEntity();
                reRun++;
            }
            return reRun;
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    private static boolean isFailedConclusion(String conclusion) {
        return "failure".equals(conclusion)
                || "cancelled".equals(conclusion)
                || "timed_out".equals(conclusion)
                || "startup_failure".equals(conclusion);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubWorkflowRunsResponse(@JsonProperty("workflow_runs") List<Run> workflowRuns) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Run(long id, String status, String conclusion) {}
    }

    /**
     * Matches the job_id at the end of an Actions check_run's details_url, e.g. {@code
     * https://github.com/o/r/actions/runs/123/job/456}. Used by {@link #fetchCheckRunLog} to bridge
     * check_run.id → actions/jobs.id — the two are different resources and only the job_id is
     * accepted by /actions/jobs/{id}/logs.
     */
    private static final Pattern ACTIONS_JOB_URL =
            Pattern.compile("/actions/runs/\\d+/job/(\\d+)(?:[/?#]|$)");

    private static final Pattern CHECK_RUN_API_URL =
            Pattern.compile("/repos/([^/]+)/([^/]+)/check-runs/(\\d+)(?:[/?#]|$)");
    private static final int ACTIONS_ATTEMPT_JOB_MAX_PAGES = 5;
    private static final int ACTIONS_JOB_LOG_MAX_BYTES = 8 * 1024 * 1024;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubCheckRunDetail(
            @JsonProperty("id") long id,
            @JsonProperty("details_url") String detailsUrl,
            @JsonProperty("html_url") String htmlUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubActionsWorkflowRun(
            long id,
            @JsonProperty("workflow_id") Long workflowId,
            String path,
            String event,
            @JsonProperty("head_sha") String headSha,
            @JsonProperty("check_suite_id") Long checkSuiteId,
            @JsonProperty("run_attempt") Integer runAttempt,
            String status,
            String conclusion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubActionsWorkflowJobs(
            @JsonProperty("total_count") Integer totalCount, List<GitHubActionsWorkflowJob> jobs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubActionsWorkflowJob(
            Long id,
            @JsonProperty("run_id") Long runId,
            @JsonProperty("run_attempt") Integer runAttempt,
            @JsonProperty("head_sha") String headSha,
            @JsonProperty("check_run_url") String checkRunUrl,
            String name,
            String status,
            String conclusion,
            List<GitHubActionsWorkflowJobStep> steps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubActionsWorkflowJobStep(
            Integer number, String name, String status, String conclusion) {}

    @Override
    public Optional<ActionsWorkflowRun> fetchActionsWorkflowRun(
            String pat, RepoRef repo, long runId) {
        try {
            GitHubActionsWorkflowRun run =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/repos/{owner}/{repo}/actions/runs/{id}")
                                                    .build(repo.owner(), repo.repo(), runId))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubActionsWorkflowRun.class);
            if (run == null) {
                return Optional.empty();
            }
            return Optional.of(
                    new ActionsWorkflowRun(
                            run.id(),
                            run.workflowId(),
                            run.path(),
                            run.event(),
                            run.headSha(),
                            run.checkSuiteId(),
                            run.runAttempt(),
                            run.status(),
                            run.conclusion()));
        }
        catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 403 || status == 404 || status == 410) {
                return Optional.empty();
            }
            throw toReadableException(e);
        }
    }

    @Override
    public ActionsWorkflowRun fetchActionsWorkflowRunAttemptStrict(
            String pat, RepoRef repo, long runId, int runAttempt) {
        requirePositive(runId, "runId");
        requirePositive(runAttempt, "runAttempt");
        try {
            GitHubActionsWorkflowRun run =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path(
                                                            "/repos/{owner}/{repo}/actions/runs/{id}/attempts/{attempt}")
                                                    .build(
                                                            repo.owner(),
                                                            repo.repo(),
                                                            runId,
                                                            runAttempt))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubActionsWorkflowRun.class);
            if (!validWorkflowRun(run, runId, runAttempt)) {
                throw incompleteActionsEvidence(
                        "workflow run identity did not match run "
                                + runId
                                + " attempt "
                                + runAttempt);
            }
            return new ActionsWorkflowRun(
                    run.id(),
                    run.workflowId(),
                    run.path(),
                    run.event(),
                    run.headSha(),
                    run.checkSuiteId(),
                    run.runAttempt(),
                    run.status(),
                    run.conclusion());
        }
        catch (RestClientResponseException e) {
            throw toReadableException(e);
        }
    }

    @Override
    public ActionsWorkflowJobSetEvidence fetchActionsWorkflowAttemptJobsStrict(
            String pat, RepoRef repo, long runId, int runAttempt) {
        requirePositive(runId, "runId");
        requirePositive(runAttempt, "runAttempt");
        ImmutableList.Builder<ActionsWorkflowJob> jobs = ImmutableList.builder();
        Set<Long> jobIds = new HashSet<>();
        Set<Long> checkRunIds = new HashSet<>();
        int collected = 0;
        Integer expected = null;
        for (int page = 1; page <= ACTIONS_ATTEMPT_JOB_MAX_PAGES; page++) {
            int currentPage = page;
            GitHubActionsWorkflowJobs response;
            try {
                response =
                        gitHubRestClient
                                .get()
                                .uri(
                                        u ->
                                                u.path(
                                                                "/repos/{owner}/{repo}/actions/runs/{id}"
                                                                    + "/attempts/{attempt}/jobs")
                                                        .queryParam("per_page", 100)
                                                        .queryParam("page", currentPage)
                                                        .build(
                                                                repo.owner(),
                                                                repo.repo(),
                                                                runId,
                                                                runAttempt))
                                .header("Authorization", authorization(pat))
                                .retrieve()
                                .body(GitHubActionsWorkflowJobs.class);
            }
            catch (RestClientResponseException e) {
                throw toReadableException(e);
            }
            if (response == null
                    || response.totalCount() == null
                    || response.jobs() == null
                    || response.jobs().isEmpty()) {
                throw incompleteActionsEvidence("null or empty jobs page " + currentPage);
            }
            if (response.totalCount() < 1) {
                throw incompleteActionsEvidence("workflow attempt reported no jobs");
            }
            if (expected == null) {
                expected = response.totalCount();
            }
            else if (!expected.equals(response.totalCount())) {
                throw incompleteActionsEvidence(
                        "job total changed from " + expected + " to " + response.totalCount());
            }
            if (response.jobs().size() > 100) {
                throw incompleteActionsEvidence("jobs page exceeded the requested page size");
            }
            for (GitHubActionsWorkflowJob job : response.jobs()) {
                ActionsWorkflowJob exact = exactJob(repo, runId, runAttempt, job);
                if (!jobIds.add(exact.jobId()) || !checkRunIds.add(exact.checkRunId())) {
                    throw incompleteActionsEvidence("workflow attempt repeated a job identity");
                }
                jobs.add(exact);
            }
            collected += response.jobs().size();
            if (collected == expected) {
                return new ActionsWorkflowJobSetEvidence(
                        runId, runAttempt, jobs.build(), collected, expected, true);
            }
            if (collected > expected || response.jobs().size() < 100) {
                throw incompleteActionsEvidence(
                        "collected " + collected + " of " + expected + " jobs");
            }
        }
        throw incompleteActionsEvidence(
                "collected " + collected + " of " + expected + " jobs before the page cap");
    }

    private static ActionsWorkflowJob exactJob(
            RepoRef repo, long runId, int runAttempt, GitHubActionsWorkflowJob job) {
        if (job == null
                || job.id() == null
                || job.id() < 1
                || job.runId() == null
                || job.runId() != runId
                || (job.runAttempt() != null && job.runAttempt() != runAttempt)
                || !hasText(job.headSha())
                || !hasText(job.name())
                || !hasText(job.status())
                || job.steps() == null) {
            throw incompleteActionsEvidence("workflow job identity was malformed or mismatched");
        }
        Long checkRunId = checkRunId(job.checkRunUrl(), repo);
        if (checkRunId == null) {
            throw incompleteActionsEvidence("workflow job check-run identity was malformed");
        }
        ImmutableList.Builder<ActionsWorkflowJobStep> steps = ImmutableList.builder();
        Set<Integer> numbers = new HashSet<>();
        for (GitHubActionsWorkflowJobStep step : job.steps()) {
            if (step == null
                    || step.number() == null
                    || step.number() < 1
                    || !numbers.add(step.number())
                    || !hasText(step.name())
                    || !hasText(step.status())) {
                throw incompleteActionsEvidence("workflow job step identity was malformed");
            }
            steps.add(
                    new ActionsWorkflowJobStep(
                            step.number(), step.name(), step.status(), step.conclusion()));
        }
        return new ActionsWorkflowJob(
                job.id(),
                checkRunId,
                runId,
                runAttempt,
                job.headSha(),
                job.name(),
                job.status(),
                job.conclusion(),
                steps.build());
    }

    private static boolean validWorkflowRun(
            GitHubActionsWorkflowRun run, long runId, int runAttempt) {
        return run != null
                && run.id() == runId
                && run.workflowId() != null
                && run.workflowId() > 0
                && hasText(run.path())
                && hasText(run.event())
                && hasText(run.headSha())
                && run.checkSuiteId() != null
                && run.checkSuiteId() > 0
                && run.runAttempt() != null
                && run.runAttempt() == runAttempt
                && hasText(run.status());
    }

    private static Long checkRunId(String url, RepoRef repo) {
        if (url == null) {
            return null;
        }
        Matcher matcher = CHECK_RUN_API_URL.matcher(url);
        if (!matcher.find()
                || !matcher.group(1).equalsIgnoreCase(repo.owner())
                || !matcher.group(2).equalsIgnoreCase(repo.repo())) {
            return null;
        }
        try {
            long id = Long.parseLong(matcher.group(3));
            return id > 0 ? id : null;
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ResponseStatusException incompleteActionsEvidence(String reason) {
        return new ResponseStatusException(
                HttpStatusCode.valueOf(502),
                "GitHub Actions attempt evidence was incomplete: " + reason);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @Override
    public Optional<String> fetchCheckRunLog(String pat, RepoRef repo, long checkRunId) {
        // check_run.id is NOT the actions/jobs.id — they're separate
        // resources that happen to share names. /actions/jobs/{id}/logs
        // only accepts the job_id, so we resolve check_run → job_id by
        // looking up the check run's details_url (which for Actions
        // check_runs is a github.com/.../actions/runs/{run}/job/{job}
        // URL) and extracting the trailing job_id.
        //
        // External CI (Circle/Jenkins/etc.) sets details_url to its own
        // hosted UI — that won't match ACTIONS_JOB_URL, and we return
        // empty so the merge card shows "no log available".
        long jobId;
        try {
            GitHubCheckRunDetail detail =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path("/repos/{owner}/{repo}/check-runs/{id}")
                                                    .build(repo.owner(), repo.repo(), checkRunId))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(GitHubCheckRunDetail.class);
            if (detail == null) {
                log.info(
                        "[log-diag] check-run {} of {}/{}: GitHub returned null body",
                        checkRunId,
                        repo.owner(),
                        repo.repo());
                return Optional.empty();
            }
            log.info(
                    "[log-diag] check-run {} of {}/{}: details_url={} html_url={}",
                    checkRunId,
                    repo.owner(),
                    repo.repo(),
                    detail.detailsUrl(),
                    detail.htmlUrl());
            Long parsed = extractActionsJobId(detail.detailsUrl());
            if (parsed == null) {
                // Fall back to html_url — some Actions-backed check runs
                // populate the job ID there instead of details_url.
                parsed = extractActionsJobId(detail.htmlUrl());
                if (parsed != null) {
                    log.info(
                            "[log-diag] check-run {}: job_id={} extracted from html_url"
                                + " (details_url did not match)",
                            checkRunId,
                            parsed);
                }
            }
            else {
                log.info(
                        "[log-diag] check-run {}: job_id={} extracted from details_url",
                        checkRunId,
                        parsed);
            }
            if (parsed == null) {
                // External CI — details_url + html_url both point at a
                // non-Actions UI. Nothing we can fetch.
                log.info(
                        "[log-diag] check-run {}: neither URL matches ACTIONS_JOB_URL pattern —"
                            + " treating as external CI",
                        checkRunId);
                return Optional.empty();
            }
            jobId = parsed;
        }
        catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                // Check run vanished (rerun rotation?) — nothing to fetch.
                log.info(
                        "[log-diag] check-run {} of {}/{}: 404 on check-run lookup — gone (rerun"
                            + " rotation?)",
                        checkRunId,
                        repo.owner(),
                        repo.repo());
                return Optional.empty();
            }
            if (status == 403) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(403),
                        "GitHub denied access to this check run (403). Your PAT may be missing "
                                + "`repo` (classic) or `Checks: Read` (fine-grained) scope.",
                        e);
            }
            throw toReadableException(e);
        }

        ActionsJobLogCapture capture = fetchActionsJobLogStrict(pat, repo, jobId);
        if (capture.status() != ActionsJobLogStatus.COMPLETE) {
            log.info(
                    "[log-diag] check-run {} (job_id={}): {} ({})",
                    checkRunId,
                    jobId,
                    capture.status(),
                    capture.detail());
            return Optional.empty();
        }
        log.info(
                "[log-diag] check-run {} (job_id={}): log fetched, {} bytes",
                checkRunId,
                jobId,
                capture.rawByteLength());
        return Optional.of(capture.rawText());
    }

    @Override
    public ActionsJobLogCapture fetchActionsJobLogStrict(String pat, RepoRef repo, long jobId) {
        requirePositive(jobId, "jobId");
        requirePat(pat);
        return gitHubRestClient
                .get()
                .uri(
                        u ->
                                u.path("/repos/{owner}/{repo}/actions/jobs/{id}/logs")
                                        .build(repo.owner(), repo.repo(), jobId))
                .header("Authorization", authorization(pat))
                .header("Accept", "text/plain, */*")
                .exchange(
                        (request, response) -> {
                            int status = response.getStatusCode().value();
                            if (status == 401 || status == 403 || status == 404 || status == 410) {
                                return ActionsJobLogCapture.unavailable(
                                        jobId,
                                        status == 401 || status == 403
                                                ? "GitHub denied access to the Actions job log"
                                                : "GitHub Actions job log is expired or"
                                                      + " unavailable");
                            }
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                throw actionsJobLogRequestFailure(response.getStatusCode());
                            }
                            long advertisedLength = response.getHeaders().getContentLength();
                            if (advertisedLength > ACTIONS_JOB_LOG_MAX_BYTES) {
                                return ActionsJobLogCapture.incomplete(
                                        jobId, "GitHub Actions job log exceeds the 8 MiB limit");
                            }
                            byte[] bytes =
                                    response.getBody().readNBytes(ACTIONS_JOB_LOG_MAX_BYTES + 1);
                            if (bytes.length > ACTIONS_JOB_LOG_MAX_BYTES) {
                                return ActionsJobLogCapture.incomplete(
                                        jobId, "GitHub Actions job log exceeds the 8 MiB limit");
                            }
                            if (advertisedLength >= 0 && advertisedLength != bytes.length) {
                                return ActionsJobLogCapture.incomplete(
                                        jobId,
                                        "GitHub Actions job log ended before its advertised"
                                            + " length");
                            }
                            if (bytes.length == 0) {
                                return ActionsJobLogCapture.incomplete(
                                        jobId, "GitHub Actions job log was empty");
                            }
                            String rawText;
                            try {
                                rawText =
                                        StandardCharsets.UTF_8
                                                .newDecoder()
                                                .onMalformedInput(CodingErrorAction.REPORT)
                                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                                .decode(ByteBuffer.wrap(bytes))
                                                .toString();
                            }
                            catch (CharacterCodingException malformed) {
                                return ActionsJobLogCapture.incomplete(
                                        jobId, "GitHub Actions job log was not strict UTF-8");
                            }
                            return ActionsJobLogCapture.complete(
                                    jobId, rawText, bytes.length, sha256(bytes));
                        });
    }

    private static ResponseStatusException actionsJobLogRequestFailure(HttpStatusCode status) {
        return new ResponseStatusException(
                status,
                "GitHub Actions job log request failed with status " + status.value() + ".");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitHubCheckRunAnnotation(
            @JsonProperty("path") String path,
            @JsonProperty("start_line") Integer startLine,
            @JsonProperty("annotation_level") String annotationLevel,
            @JsonProperty("title") String title,
            @JsonProperty("message") String message) {}

    /**
     * Annotations GitHub attaches to the workflow file instead of to source carry no information:
     * "Process completed with exit code 1." is the canonical example, emitted by every failing
     * Actions job. They're dropped rather than ranked last — a check whose only annotation is that
     * boilerplate is treated as having none, so the caller falls back to the log, where the real
     * error actually lives.
     */
    private static boolean hasSourceLocation(CheckRunAnnotation annotation) {
        // GitHub reports the workflow-file location as a bare ".github" as
        // well as ".github/workflows/…", and pairs it with a line number that
        // points into the workflow — so the line alone can't be trusted.
        return annotation.startLine() != null
                && annotation.path() != null
                && !annotation.path().isBlank()
                && !annotation.path().equals(".github")
                && !annotation.path().startsWith(".github/");
    }

    @Override
    public List<CheckRunAnnotation> fetchCheckRunAnnotations(
            String pat, RepoRef repo, long checkRunId) {
        List<GitHubCheckRunAnnotation> body;
        try {
            body =
                    gitHubRestClient
                            .get()
                            .uri(
                                    u ->
                                            u.path(
                                                            "/repos/{owner}/{repo}/check-runs/{id}/annotations")
                                                    .queryParam("per_page", 100)
                                                    .build(repo.owner(), repo.repo(), checkRunId))
                            .header("Authorization", authorization(pat))
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                // Check run vanished (rerun rotation?) — nothing to show.
                return ImmutableList.of();
            }
            throw toReadableException(e);
        }
        if (body == null) {
            return ImmutableList.of();
        }
        return body.stream()
                .filter(annotation -> "failure".equals(annotation.annotationLevel()))
                .map(GitHubActionsClient::toCheckRunAnnotation)
                .filter(GitHubActionsClient::hasSourceLocation)
                .collect(toImmutableList());
    }

    @Override
    public CheckRunAnnotationEvidence fetchCheckRunAnnotationsStrict(
            String pat, RepoRef repo, long checkRunId, int expectedAnnotationCount) {
        if (expectedAnnotationCount < 0) {
            return new CheckRunAnnotationEvidence(List.of(), 0, expectedAnnotationCount, false);
        }
        ImmutableList.Builder<CheckRunAnnotation> failures = ImmutableList.builder();
        int observed = 0;
        for (int page = 1; page <= 10 && observed < expectedAnnotationCount; page++) {
            List<GitHubCheckRunAnnotation> body;
            try {
                int currentPage = page;
                body =
                        gitHubRestClient
                                .get()
                                .uri(
                                        u ->
                                                u.path(
                                                                "/repos/{owner}/{repo}/check-runs/{id}/annotations")
                                                        .queryParam("per_page", 100)
                                                        .queryParam("page", currentPage)
                                                        .build(
                                                                repo.owner(),
                                                                repo.repo(),
                                                                checkRunId))
                                .header("Authorization", authorization(pat))
                                .retrieve()
                                .body(new ParameterizedTypeReference<>() {});
            }
            catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 403 || status == 404 || status == 410) {
                    return new CheckRunAnnotationEvidence(
                            failures.build(), observed, expectedAnnotationCount, false);
                }
                throw toReadableException(e);
            }
            if (body == null) {
                return new CheckRunAnnotationEvidence(
                        failures.build(), observed, expectedAnnotationCount, false);
            }
            observed += body.size();
            body.stream()
                    .filter(annotation -> "failure".equals(annotation.annotationLevel()))
                    .map(GitHubActionsClient::toCheckRunAnnotation)
                    .forEach(failures::add);
            if (body.size() < 100) {
                break;
            }
        }
        return new CheckRunAnnotationEvidence(
                failures.build(),
                observed,
                expectedAnnotationCount,
                observed == expectedAnnotationCount);
    }

    private static CheckRunAnnotation toCheckRunAnnotation(GitHubCheckRunAnnotation annotation) {
        return new CheckRunAnnotation(
                annotation.title(),
                annotation.message(),
                annotation.path(),
                annotation.startLine());
    }

    private static Long extractActionsJobId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Matcher m = ACTIONS_JOB_URL.matcher(url);
        if (!m.find()) {
            return null;
        }
        try {
            return Long.parseLong(m.group(1));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }
}
