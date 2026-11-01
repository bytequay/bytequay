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
package com.bytequay.integration.gitlab.impl;

import com.bytequay.core.ci.CiDeliveryChecks;
import com.bytequay.core.ci.CiHeadSnapshot;
import com.bytequay.core.ci.CiJob;
import com.bytequay.core.ci.CiRerunResult;
import com.bytequay.core.ci.CiRun;
import com.bytequay.core.ci.RerunFailedJobsCommand;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.ci.CiRerunResult.Outcome.ACCEPTED;
import static com.bytequay.core.ci.CiRerunResult.Outcome.OBSOLETE;
import static com.bytequay.core.ci.CiRerunResult.Outcome.REJECTED;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TestGitLabPullRequestCi
{
    private static final String API = "/gitlab/api/v4/projects/";
    private static final String PREFIX = API + "42/merge_requests/7";
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String TOKEN = "secret-token-value";
    private final Map<String, String> responses = new HashMap<>();
    private final Map<String, Integer> statuses = new HashMap<>();
    private final List<String> received = new ArrayList<>();
    private HttpServer server;
    private HttpClient http;
    private HostingAccess access;
    private HostingRepository repository;
    private GitLabPullRequestCi ci;
    private GitLabPullRequestCi legacy;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().toASCIIString();
            received.add(key);
            String body = responses.get(key);
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses.getOrDefault(key, body == null ? 403 : 200), bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/gitlab";
        HostingInstance instance = new HostingInstance(1, GITLAB, URI.create(base), URI.create(base + "/api/v4"));
        access = new HostingAccess(instance, TOKEN);
        repository = new HostingRepository(2, 1, "42", "group/project", URI.create(base + "/group/project"),
                URI.create(base + "/group/project.git"), Optional.empty(), Optional.of("main"));
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        ci = new GitLabPullRequestCi(http, new ObjectMapper());
        legacy = new GitLabPullRequestCi(http, new ObjectMapper(), GitLabRelease.LEGACY);
    }

    @AfterEach
    void tearDown()
    {
        http.close();
        server.stop(0);
    }

    @Test
    void inspectsRetriedJobsAsAttemptsOfTheHeadPipeline()
    {
        mergeRequest(42, pipeline(42, "failed"));
        jobs(42, job(10, "test", "failed", false), job(11, "lint", "success", false), job(12, "test", "failed", false), job(13, "build", "failed", true));

        CiHeadSnapshot snapshot = ci.inspect(target(), 7);

        assertThat(snapshot.open()).isTrue();
        assertThat(snapshot.author()).isEqualTo("alice");
        assertThat(snapshot.headRepository().fullName()).isEqualTo("group/project");
        assertThat(snapshot.headRef()).isEqualTo("feature");
        assertThat(snapshot.headSha()).isEqualTo(HEAD);
        assertThat(snapshot.baseRef()).isEqualTo("main");
        assertThat(snapshot.baseSha()).isEqualTo(BASE);
        CiRun run = snapshot.runs().getFirst();
        assertThat(snapshot.runs()).hasSize(1);
        assertThat(run.id()).isEqualTo(9);
        assertThat(run.definitionId()).isEqualTo(42);
        assertThat(run.name()).isEqualTo("Pipeline #9");
        assertThat(run.attempt()).isEqualTo(2);
        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.conclusion()).hasValue("failure");
        assertThat(run.headSha()).isEqualTo(HEAD);
        assertThat(run.url()).isEqualTo("https://gitlab.example.com/group/project/-/pipelines/9");
        assertThat(run.jobs()).extracting(CiJob::id, CiJob::name, CiJob::attempt, job -> job.conclusion().orElseThrow())
                .containsExactly(tuple(13L, "build", 1, "neutral"), tuple(11L, "lint", 1, "success"), tuple(12L, "test", 2, "failure"));
        assertThat(snapshot.actionableFailures()).singleElement().satisfies(job -> {
            assertThat(job.id()).isEqualTo(12);
            assertThat(job.failureSummary()).isEqualTo("test failed");
        });
        assertThat(snapshot.pending()).isFalse();
        assertThat(snapshot.green()).isFalse();
    }

    @Test
    void readsAForkPipelineFromItsSourceProject()
    {
        mergeRequest(43, pipeline(43, "success"));
        jobs(43, job(12, "test", "success", false));

        CiHeadSnapshot snapshot = ci.inspect(target(), 7);

        assertThat(snapshot.headRepository().fullName()).isEqualTo("alice/project");
        assertThat(snapshot.runs()).singleElement().satisfies(run -> {
            assertThat(run.definitionId()).isEqualTo(42);
            assertThat(run.attempt()).isEqualTo(1);
        });
        assertThat(snapshot.green()).isTrue();
        assertThat(received).contains("GET " + API + "43/pipelines/9/jobs?include_retried=true&per_page=100&page=1");
    }

    @Test
    void requestWithoutHeadPipelineHasNoRuns()
    {
        mergeRequest(42, "null");

        CiHeadSnapshot snapshot = ci.inspect(target(), 7);

        assertThat(snapshot.runs()).isEmpty();
        assertThat(snapshot.pending()).isFalse();
        assertThat(snapshot.green()).isFalse();
    }

    @Test
    void mapsGitLabStatusesIntoTheSharedVocabulary()
    {
        mergeRequest(42, pipeline(42, "manual"));
        jobs(42, job(1, "created", "created", false), job(2, "pending", "pending", false), job(3, "waiting", "waiting_for_resource", false),
                job(4, "preparing", "preparing", false), job(5, "scheduled", "scheduled", false), job(6, "running", "running", false),
                job(7, "success", "success", false), job(8, "failed", "failed", false), job(9, "optional failure", "failed", true),
                job(10, "canceled", "canceled", false), job(11, "skipped", "skipped", false), job(12, "optional manual", "manual", true),
                job(13, "blocking manual", "manual", false));

        CiRun run = ci.inspect(target(), 7).runs().getFirst();

        assertThat(run.status()).isEqualTo("queued");
        assertThat(run.conclusion()).isEmpty();
        assertThat(run.jobs()).extracting(CiJob::name, CiJob::status, job -> job.conclusion().orElse(null)).containsExactlyInAnyOrder(
                tuple("created", "queued", null),
                tuple("pending", "queued", null),
                tuple("waiting", "queued", null),
                tuple("preparing", "queued", null),
                tuple("scheduled", "queued", null),
                tuple("running", "in_progress", null),
                tuple("success", "completed", "success"),
                tuple("failed", "completed", "failure"),
                tuple("optional failure", "completed", "neutral"),
                tuple("canceled", "completed", "cancelled"),
                tuple("skipped", "completed", "skipped"),
                tuple("optional manual", "completed", "skipped"),
                tuple("blocking manual", "queued", null));
    }

    @Test
    void downloadsPlainStepAndErrorLines(@TempDir Path directory)
            throws IOException
    {
        mergeRequest(42, pipeline(42, "failed"));
        jobs(42, job(12, "test", "failed", false));
        responses.put("GET " + API + "42/jobs/12/trace", """
                \u001B[0KRunning with gitlab-runner 17.0.0\u001B[0;m
                \u001B[0Ksection_start:1700000000:prepare_executor\r\u001B[0K\u001B[0K\u001B[36;1mPreparing the "docker" executor\u001B[0;m\u001B[0;m
                Using Docker executor
                \u001B[0Ksection_end:1700000000:prepare_executor\r\u001B[0K\u001B[0Ksection_start:1700000001:step_script[collapsed=true]\r\u001B[0K\
                \u001B[0K\u001B[36;1mExecuting "step_script" stage of the job script\u001B[0;m\u001B[0;m
                Downloading 10%\rDownloading 100%
                curl --header "PRIVATE-TOKEN: abc123" https://gitlab.example.com
                echo secret-token-value SAMPLE_PAT
                \u001B[0Ksection_end:1700000001:step_script\r\u001B[0K
                \u001B[0Ksection_start:1700000002:upload_artifacts_on_failure\r\u001B[0K
                \u001B[31;1mERROR: Job failed: exit code 1
                \u001B[0;m
                """.replace("SAMPLE_PAT", "glpat" + "-" + "x".repeat(20)));
        Path destination = directory.resolve("job.log");

        assertThat(ci.downloadLog(target(), 7, 12, destination)).isEmpty();

        assertThat(Files.readString(destination)).isEqualTo("""
                Running with gitlab-runner 17.0.0
                ##[group]Preparing the "docker" executor
                Using Docker executor
                ##[group]Executing "step_script" stage of the job script
                Downloading 100%
                curl --header "PRIVATE-TOKEN: [REDACTED] https://gitlab.example.com
                echo [REDACTED] [REDACTED]
                ##[group]upload_artifacts_on_failure
                ##[error]ERROR: Job failed: exit code 1

                """);
    }

    @Test
    void unavailableLogsExplainWhyWithoutWriting(@TempDir Path directory)
    {
        Path destination = directory.resolve("job.log");
        mergeRequest(42, "null");

        assertThat(ci.downloadLog(target(), 7, 12, destination)).hasValue("This request has no current pipeline");

        mergeRequest(42, pipeline(42, "failed"));
        jobs(42, job(12, "test", "failed", false));

        assertThat(ci.downloadLog(target(), 7, 99, destination)).hasValue("This job is not part of the request's current pipeline");

        statuses.put("GET " + API + "42/jobs/12/trace", 404);

        assertThat(ci.downloadLog(target(), 7, 12, destination)).hasValueSatisfying(reason -> assertThat(reason)
                .contains("HTTP 404", "Request: GET <api>/projects/{id}/jobs/{id}/trace").hasSizeLessThanOrEqualTo(500));
        assertThat(destination).doesNotExist();
    }

    @Test
    void failureNotesExplainOnlyFailuresTheLogCannotShow()
    {
        mergeRequest(43, pipeline(43, "failed"));
        responses.put("GET " + API + "43/jobs/12", "{\"id\":12,\"name\":\"test\",\"status\":\"failed\",\"failure_reason\":\"runner_system_failure\"}");
        responses.put("GET " + API + "43/jobs/13", "{\"id\":13,\"name\":\"lint\",\"status\":\"failed\",\"failure_reason\":\"script_failure\"}");

        assertThat(ci.failureNotes(target(), 7, 12)).containsExactly("The runner failed while running the job, outside the job's script");
        assertThat(ci.failureNotes(target(), 7, 13)).isEmpty();
        assertThat(ci.failureNotes(target(), 7, 14)).isEmpty();
    }

    @Test
    void currentReleasesRepairConfigurationErrorsGraphQlReports(@TempDir Path directory)
            throws IOException
    {
        mergeRequest(42, pipeline(42, "failed"));
        jobs(42);
        // REST leaves yaml_errors empty on current releases.
        responses.put("GET " + API + "42/pipelines/9", pipeline(42, "failed"));
        responses.put("POST /gitlab/api/graphql", """
                {"data":{"project":{"mergeRequest":{"headPipeline":{"id":"gid://gitlab/Ci::Pipeline/9",
                "failureReason":"%s",
                "errorMessages":{"nodes":[{"content":"jobs test config should implement the script:, run:, or trigger: keyword"}]}}}}}}
                """.formatted("The pipeline failed due to an error on the CI/CD configuration file."));

        CiHeadSnapshot snapshot = ci.inspect(target(), 7);

        assertThat(snapshot.actionableFailures()).singleElement().satisfies(job -> {
            assertThat(job.id()).isEqualTo(9);
            assertThat(job.name()).isEqualTo("CI configuration");
        });
        assertThat(snapshot.runs().getFirst().name()).isEqualTo("Pipeline #9");
        Path destination = directory.resolve("job.log");
        assertThat(ci.downloadLog(target(), 7, 9, destination)).isEmpty();
        assertThat(Files.readString(destination)).isEqualTo("##[error]jobs test config should implement the script:, run:, or trigger: keyword");
        assertThat(received).doesNotContain("GET " + API + "42/pipelines/9");
    }

    @Test
    void olderReleasesRepairConfigurationErrorsRestReports(@TempDir Path directory)
            throws IOException
    {
        mergeRequest(42, pipeline(42, "failed"));
        jobs(42);
        responses.put("GET " + API + "42/pipelines/9", configFailure(9, HEAD));

        CiHeadSnapshot snapshot = legacy.inspect(target(), 7);

        assertThat(snapshot.actionableFailures()).singleElement().satisfies(job -> {
            assertThat(job.id()).isEqualTo(9);
            assertThat(job.name()).isEqualTo("CI configuration");
        });
        Path destination = directory.resolve("job.log");
        assertThat(legacy.downloadLog(target(), 7, 9, destination)).isEmpty();
        assertThat(Files.readString(destination)).isEqualTo("##[error]jobs:test config contains unknown keys: scrpt");
        assertThat(legacy.failureNotes(target(), 7, 9)).isEmpty();
        assertThat(received).doesNotContain("GET " + API + "42/jobs/9", "POST /gitlab/api/graphql");
    }

    @Test
    void pipelineStoppedOutsideItsConfigurationCarriesGitLabsReason()
    {
        mergeRequest(42, pipeline(42, "failed"));
        jobs(42);
        responses.put("GET " + API + "42/pipelines/9", pipeline(42, "failed"));
        responses.put("POST /gitlab/api/graphql", """
                {"data":{"project":{"mergeRequest":{"headPipeline":{"id":"gid://gitlab/Ci::Pipeline/9",
                "failureReason":"The pipeline failed due to the user not being verified."}}}}}
                """);

        CiRun run = ci.inspect(target(), 7).runs().getFirst();

        assertThat(run.name()).isEqualTo("Pipeline #9: The pipeline failed due to the user not being verified.");
        assertThat(run.conclusion()).hasValue("failure");
        assertThat(run.jobs()).isEmpty();

        // Older releases have no failure reason to ask for.
        received.clear();

        assertThat(legacy.inspect(target(), 7).runs().getFirst().name()).isEqualTo("Pipeline #9");
        assertThat(received).doesNotContain("POST /gitlab/api/graphql");

        // The reason only adds context: without it the run keeps its plain name.
        responses.remove("POST /gitlab/api/graphql");

        assertThat(ci.inspect(target(), 7).runs().getFirst().name()).isEqualTo("Pipeline #9");
    }

    @Test
    void failedJobsOnBranchReadsTheLatestFinishedPushPipeline()
    {
        String latest = "GET " + API + "42/pipelines?ref=release%2F1.0&source=push&scope=finished&order_by=id&sort=desc&per_page=1";
        responses.put(latest, """
                [{"id":5,"iid":3,"project_id":42,"sha":"%s","status":"failed","web_url":"https://gitlab.example.com/group/project/-/pipelines/5"}]
                """.formatted(BASE));
        responses.put("GET " + API + "42/pipelines/5/jobs?include_retried=true&per_page=100&page=1", "[" + String.join(",",
                job(1, "build", "failed", false), job(2, "build", "success", false), job(3, "test", "failed", false), job(4, "lint", "failed", true)) + "]");

        assertThat(ci.failedJobsOnBranch(target(), 42, "release/1.0")).containsExactly("test");

        // A base branch whose configuration is broken too fails the same named job.
        responses.put("GET " + API + "42/pipelines/5/jobs?include_retried=true&per_page=100&page=1", "[]");
        responses.put("GET " + API + "42/pipelines/5", configFailure(5, BASE));

        assertThat(legacy.failedJobsOnBranch(target(), 42, "release/1.0")).containsExactly("CI configuration");

        // Current releases name a branch pipeline by its number within the project.
        responses.put("POST /gitlab/api/graphql", """
                {"data":{"project":{"pipeline":{"id":"gid://gitlab/Ci::Pipeline/5","failureReason":"%s",
                "errorMessages":{"nodes":[{"content":"jobs test config should implement the script:, run:, or trigger: keyword"}]}}}}}
                """.formatted("The pipeline failed due to an error on the CI/CD configuration file."));

        assertThat(ci.failedJobsOnBranch(target(), 42, "release/1.0")).containsExactly("CI configuration");

        responses.put(latest, "[]");

        assertThat(ci.failedJobsOnBranch(target(), 42, "release/1.0")).isEmpty();
    }

    @Test
    void rerunRetriesTheExactFailedAttempt()
    {
        mergeRequest(43, pipeline(43, "failed"));
        jobs(43, job(10, "test", "failed", false), job(11, "test", "failed", false), job(12, "lint", "success", false));
        responses.put("POST " + API + "43/pipelines/9/retry", "{\"id\":9}");

        CiRerunResult result = ci.rerunFailedJobs(target(), 7, new RerunFailedJobsCommand(9, HEAD, 2, true));

        assertThat(result.outcome()).isEqualTo(ACCEPTED);
        assertThat(received).containsOnlyOnce("POST " + API + "43/pipelines/9/retry");
    }

    @ParameterizedTest
    @CsvSource({
            "9, aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, 1, failed",
            "8, aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, 2, failed",
            "9, cccccccccccccccccccccccccccccccccccccccc, 2, failed",
            "9, aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, 2, running",
            "9, aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, 2, success"})
    void rerunOfAnotherAttemptIsObsolete(long runId, String headSha, int previousAttempt, String pipelineStatus)
    {
        mergeRequest(42, pipeline(42, pipelineStatus));
        jobs(42, job(10, "test", "failed", false), job(11, "test", "failed", false));

        CiRerunResult result = ci.rerunFailedJobs(target(), 7, new RerunFailedJobsCommand(runId, headSha, previousAttempt, true));

        assertThat(result.outcome()).isEqualTo(OBSOLETE);
        assertThat(received).noneMatch(request -> request.startsWith("POST"));
    }

    @Test
    void unauthorizedOrDeniedRerunIsRejected()
    {
        assertThat(ci.rerunFailedJobs(target(), 7, new RerunFailedJobsCommand(9, HEAD, 1, false)).outcome()).isEqualTo(REJECTED);
        assertThat(received).isEmpty();

        mergeRequest(42, pipeline(42, "failed"));
        jobs(42, job(10, "test", "failed", false));
        statuses.put("POST " + API + "42/pipelines/9/retry", 403);

        assertThat(ci.rerunFailedJobs(target(), 7, new RerunFailedJobsCommand(9, HEAD, 1, true)).outcome()).isEqualTo(REJECTED);

        statuses.put("POST " + API + "42/pipelines/9/retry", 404);

        assertThat(ci.rerunFailedJobs(target(), 7, new RerunFailedJobsCommand(9, HEAD, 1, true)).outcome()).isEqualTo(OBSOLETE);
    }

    @Test
    void deliveryChecksAreGreenWhenEveryHeadCheckPasses()
    {
        mergeRequest(42, pipeline(42, "success"));
        jobs(42, job(10, "test", "failed", false), job(11, "test", "success", false), job(12, "lint", "failed", true));
        bridges("[]");
        commitStatuses(status(11, "test", "success"), status(20, "external/scan", "success"));

        CiDeliveryChecks checks = ci.deliveryChecks(target(), 7);

        assertThat(checks.pullRequest().headSha()).isEqualTo(HEAD);
        assertThat(checks.pullRequest().author()).isEqualTo("alice");
        assertThat(checks.waiting()).isEmpty();
        assertThat(checks.failures()).isEmpty();
        assertThat(checks.green()).isTrue();
    }

    @Test
    void deliveryChecksWaitForUnfinishedChecks()
    {
        mergeRequest(42, pipeline(42, "running"));
        jobs(42, job(10, "test", "running", false), job(11, "deploy", "manual", false), job(12, "docs", "manual", true));
        bridges("[" + job(13, "downstream", "pending", false) + "]");
        commitStatuses(status(20, "external/scan", "pending"));

        CiDeliveryChecks checks = ci.deliveryChecks(target(), 7);

        assertThat(checks.waiting()).containsExactly("Waiting for CI pipeline #9 (running)", "Waiting for CI job test (running)", "Waiting for CI job deploy (manual)",
                "Waiting for CI job downstream (pending)", "Waiting for CI status external/scan (pending)");
        assertThat(checks.failures()).isEmpty();
    }

    @Test
    void deliveryChecksReportFailuresInsideAndOutsideThePipeline()
    {
        mergeRequest(42, pipeline(42, "failed"));
        jobs(42, job(10, "test", "failed", false), job(11, "lint", "canceled", false));
        bridges("[]");
        commitStatuses(status(10, "test", "failed"), status(20, "external/scan", "failed"));

        CiDeliveryChecks checks = ci.deliveryChecks(target(), 7);

        assertThat(checks.waiting()).isEmpty();
        assertThat(checks.failures()).containsExactly("CI pipeline #9 reported failed", "CI job test reported failed", "CI job lint reported canceled",
                "CI status external/scan reported failed; CI Autofix cannot repair this external check");

        mergeRequest(42, "null");
        commitStatuses();

        assertThat(ci.deliveryChecks(target(), 7).waiting()).containsExactly("No current-head CI checks have been reported");
    }

    private HostingTarget target()
    {
        return new HostingTarget(access, repository);
    }

    private void mergeRequest(long sourceProject, String headPipeline)
    {
        responses.put("GET " + PREFIX, """
                {"iid":7,"sha":"%s","state":"opened","draft":false,"author":{"id":3,"username":"alice"},"web_url":"%s/-/merge_requests/7",
                "source_project_id":%d,"target_project_id":42,"source_branch":"feature","target_branch":"main",
                "diff_refs":{"head_sha":"%s","start_sha":"%s"},"head_pipeline":%s}
                """.formatted(HEAD, repository.webUrl(), sourceProject, HEAD, BASE, headPipeline));
        responses.put("GET " + API + "42", "{\"id\":42,\"path_with_namespace\":\"group/project\"}");
        responses.put("GET " + API + "43", "{\"id\":43,\"path_with_namespace\":\"alice/project\"}");
    }

    private static String pipeline(long project, String status)
    {
        return "{\"id\":9,\"project_id\":%d,\"sha\":\"%s\",\"status\":\"%s\",\"web_url\":\"https://gitlab.example.com/group/project/-/pipelines/9\"}"
                .formatted(project, HEAD, status);
    }

    private static String configFailure(long id, String sha)
    {
        return ("{\"id\":%d,\"project_id\":42,\"sha\":\"%s\",\"status\":\"failed\",\"web_url\":\"https://gitlab.example.com/group/project/-/pipelines/%d\","
                + "\"yaml_errors\":\"jobs:test config contains unknown keys: scrpt\"}").formatted(id, sha, id);
    }

    private void jobs(long project, String... jobs)
    {
        responses.put("GET " + API + project + "/pipelines/9/jobs?include_retried=true&per_page=100&page=1", "[" + String.join(",", jobs) + "]");
    }

    private void bridges(String bridges)
    {
        responses.put("GET " + API + "42/pipelines/9/bridges?per_page=100&page=1", bridges);
    }

    private void commitStatuses(String... statuses)
    {
        responses.put("GET " + API + "42/repository/commits/" + HEAD + "/statuses?per_page=100&page=1", "[" + String.join(",", statuses) + "]");
    }

    private static String job(long id, String name, String status, boolean allowFailure)
    {
        return "{\"id\":%d,\"name\":\"%s\",\"status\":\"%s\",\"allow_failure\":%s,\"web_url\":\"https://gitlab.example.com/group/project/-/jobs/%d\"}"
                .formatted(id, name, status, allowFailure, id);
    }

    private static String status(long id, String name, String status)
    {
        return "{\"id\":%d,\"name\":\"%s\",\"status\":\"%s\",\"allow_failure\":false,\"target_url\":\"https://ci.example.com/%d\"}".formatted(id, name, status, id);
    }
}
