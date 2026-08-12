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
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.GitHubActionsRepository;
import com.bytequay.app.repository.GitHubActionsRepository.ActionsJobLogCapture;
import com.bytequay.app.repository.GitHubActionsRepository.ActionsJobLogStatus;
import com.bytequay.app.repository.GitHubActionsRepository.ActionsWorkflowJobSetEvidence;
import com.bytequay.app.repository.GitHubActionsRepository.CheckRunAnnotationEvidence;
import com.bytequay.app.repository.PullRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientCiReads
{
    @Test
    void strictCheckReadRetainsExactGitHubLineage()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits/head/check-runs"
                                + "?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        {"total_count":1,"check_runs":[{
                          "id":99,"head_sha":"merge-sha","external_id":"job-1",
                          "name":"build","status":"completed","conclusion":"failure",
                          "html_url":"https://github.com/owner/repo/actions/runs/7/job/9",
                          "details_url":"https://github.com/owner/repo/actions/runs/7/job/9",
                          "check_suite":{"id":31},
                          "app":{"id":15368,"slug":"github-actions"},
                          "pull_requests":[{"number":41,
                            "head":{"sha":"head"},"base":{"sha":"base"}}],
                          "output":{"title":null,"summary":null,"annotations_count":2}
                        }]}
                        """, MediaType.APPLICATION_JSON));

        PrCheckRunState check = client.fetchPrCheckRunsStrict(
                "pat", "owner", "repo", "head").getFirst();

        assertThat(check.githubMetadata().testedSha()).isEqualTo("merge-sha");
        assertThat(check.githubMetadata().detailsUrl()).contains("actions/runs/7");
        assertThat(check.githubMetadata().checkSuiteId()).isEqualTo(31L);
        assertThat(check.githubMetadata().appSlug()).isEqualTo("github-actions");
        assertThat(check.githubMetadata().annotationCount()).isEqualTo(2);
        assertThat(check.githubMetadata().pullRequests()).singleElement()
                .satisfies(subject -> {
                    assertThat(subject.pullRequestNumber()).isEqualTo(41);
                    assertThat(subject.headSha()).isEqualTo("head");
                    assertThat(subject.baseSha()).isEqualTo("base");
                });
        server.verify();
    }

    @Test
    void strictCheckReadRejectsTheFivePageSafetyCapBeforeTheReportedTotal()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        for (int page = 1; page <= 5; page++) {
            server.expect(requestTo(
                            "https://api.github.test/repos/owner/repo/commits/head/check-runs"
                                    + "?per_page=100&page=" + page))
                    .andRespond(withSuccess(
                            checkRunsPage(501, 100),
                            MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> client.fetchPrCheckRunsStrict(
                "pat", "owner", "repo", "head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("collected 500 of 501");

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 25})
    void strictCheckReadRejectsAPrematureEmptyOrPartialPage(int finalPageSize)
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits/head/check-runs"
                                + "?per_page=100&page=1"))
                .andRespond(withSuccess(
                        checkRunsPage(150, 100),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits/head/check-runs"
                                + "?per_page=100&page=2"))
                .andRespond(withSuccess(
                        checkRunsPage(150, finalPageSize),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchPrCheckRunsStrict(
                "pat", "owner", "repo", "head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("of 150");

        server.verify();
    }

    @Test
    void strictCheckReadRejectsAnUnstableServerTotal()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits/head/check-runs"
                                + "?per_page=100&page=1"))
                .andRespond(withSuccess(
                        checkRunsPage(150, 100),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits/head/check-runs"
                                + "?per_page=100&page=2"))
                .andRespond(withSuccess(
                        checkRunsPage(149, 49),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchPrCheckRunsStrict(
                "pat", "owner", "repo", "head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("collected 100 of 150");

        server.verify();
    }

    @Test
    void ordinaryCheckReadKeepsPartialResultsWhenPaginationEndsEarly()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits/head/check-runs"
                                + "?per_page=100&page=1"))
                .andRespond(withSuccess(
                        checkRunsPage(150, 100),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits/head/check-runs"
                                + "?per_page=100&page=2"))
                .andRespond(withSuccess(
                        checkRunsPage(150, 0),
                        MediaType.APPLICATION_JSON));

        assertThat(client.fetchPrCheckRuns(
                "pat", "owner", "repo", "head")).hasSize(100);

        server.verify();
    }

    @Test
    void actionsWorkflowReadRetainsTheTestedSubjectAndProfile()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/actions/runs/7"))
                .andRespond(withSuccess("""
                        {"id":7,"workflow_id":17,
                         "path":".github/workflows/ci.yml@main",
                         "event":"pull_request","head_sha":"merge-sha",
                         "check_suite_id":31,"run_attempt":2,
                         "status":"completed","conclusion":"failure"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchActionsWorkflowRun(
                "pat", RepoRef.of("owner", "repo"), 7L))
                .get()
                .satisfies(run -> {
                    assertThat(run.workflowId()).isEqualTo(17L);
                    assertThat(run.workflowPath())
                            .isEqualTo(".github/workflows/ci.yml@main");
                    assertThat(run.headSha()).isEqualTo("merge-sha");
                    assertThat(run.checkSuiteId()).isEqualTo(31L);
                    assertThat(run.runAttempt()).isEqualTo(2);
                    assertThat(run.status()).isEqualTo("completed");
                    assertThat(run.conclusion()).isEqualTo("failure");
                });
        server.verify();
    }

    @Test
    void strictActionsAttemptReadRetainsExactTerminalIdentity()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/actions/runs/7"
                                + "/attempts/2"))
                .andRespond(withSuccess("""
                        {"id":7,"workflow_id":17,
                         "path":".github/workflows/ci.yml@main",
                         "event":"pull_request","head_sha":"merge-sha",
                         "check_suite_id":31,"run_attempt":2,
                         "status":"completed","conclusion":"failure"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchActionsWorkflowRunAttemptStrict(
                "pat", RepoRef.of("owner", "repo"), 7L, 2))
                .satisfies(run -> {
                    assertThat(run.runId()).isEqualTo(7L);
                    assertThat(run.runAttempt()).isEqualTo(2);
                    assertThat(run.headSha()).isEqualTo("merge-sha");
                    assertThat(run.status()).isEqualTo("completed");
                    assertThat(run.conclusion()).isEqualTo("failure");
                });
        server.verify();
    }

    @Test
    void strictActionsAttemptReadRejectsAnAttemptMismatch()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/actions/runs/7"
                                + "/attempts/2"))
                .andRespond(withSuccess("""
                        {"id":7,"workflow_id":17,
                         "path":".github/workflows/ci.yml@main",
                         "event":"pull_request","head_sha":"merge-sha",
                         "check_suite_id":31,"run_attempt":3,
                         "status":"completed","conclusion":"failure"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchActionsWorkflowRunAttemptStrict(
                "pat", RepoRef.of("owner", "repo"), 7L, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("attempt 2");
        server.verify();
    }

    @Test
    void strictActionsAttemptJobsRetainEveryPageAndImmutableSteps()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(attemptJobsUrl(1)))
                .andRespond(withSuccess(
                        actionsJobsPage(101, 1, 100, 7, 2),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(attemptJobsUrl(2)))
                .andRespond(withSuccess(
                        actionsJobsPage(101, 101, 1, 7, 2),
                        MediaType.APPLICATION_JSON));

        ActionsWorkflowJobSetEvidence evidence =
                client.fetchActionsWorkflowAttemptJobsStrict(
                        "pat", RepoRef.of("owner", "repo"), 7L, 2);

        assertThat(evidence.complete()).isTrue();
        assertThat(evidence.observedJobCount()).isEqualTo(101);
        assertThat(evidence.expectedJobCount()).isEqualTo(101);
        assertThat(evidence.jobs()).hasSize(101);
        assertThat(evidence.jobs().getFirst()).satisfies(job -> {
            assertThat(job.jobId()).isEqualTo(1001L);
            assertThat(job.checkRunId()).isEqualTo(2001L);
            assertThat(job.runId()).isEqualTo(7L);
            assertThat(job.runAttempt()).isEqualTo(2);
            assertThat(job.headSha()).isEqualTo("merge-sha");
            assertThat(job.steps()).singleElement().satisfies(step -> {
                assertThat(step.number()).isEqualTo(1);
                assertThat(step.name()).isEqualTo("Check results");
                assertThat(step.conclusion()).isEqualTo("failure");
            });
        });
        assertThatThrownBy(() -> evidence.jobs().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evidence.jobs().getFirst().steps().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 25})
    void strictActionsAttemptJobsRejectAPrematureEmptyOrShortPage(int pageSize)
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(attemptJobsUrl(1)))
                .andRespond(withSuccess(
                        actionsJobsPage(150, 1, pageSize, 7, 2),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                client.fetchActionsWorkflowAttemptJobsStrict(
                        "pat", RepoRef.of("owner", "repo"), 7L, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete");
        server.verify();
    }

    @Test
    void strictActionsAttemptJobsRejectAChangingTotal()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(attemptJobsUrl(1)))
                .andRespond(withSuccess(
                        actionsJobsPage(150, 1, 100, 7, 2),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(attemptJobsUrl(2)))
                .andRespond(withSuccess(
                        actionsJobsPage(149, 101, 49, 7, 2),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                client.fetchActionsWorkflowAttemptJobsStrict(
                        "pat", RepoRef.of("owner", "repo"), 7L, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("total changed");
        server.verify();
    }

    @Test
    void strictActionsAttemptJobsRejectThePageCapBeforeTheReportedTotal()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        for (int page = 1; page <= 5; page++) {
            server.expect(requestTo(attemptJobsUrl(page)))
                    .andRespond(withSuccess(
                            actionsJobsPage(
                                    501, (page - 1) * 100 + 1, 100, 7, 2),
                            MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() ->
                client.fetchActionsWorkflowAttemptJobsStrict(
                        "pat", RepoRef.of("owner", "repo"), 7L, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("500 of 501")
                .hasMessageContaining("page cap");
        server.verify();
    }

    @Test
    void strictActionsAttemptJobsRejectMalformedJobIdentity()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(attemptJobsUrl(1)))
                .andRespond(withSuccess("""
                        {"total_count":1,"jobs":[{
                          "id":1001,"run_id":8,"run_attempt":2,
                          "head_sha":"merge-sha",
                          "check_run_url":"https://api.github.test/repos/owner/repo/check-runs/2001",
                          "name":"CI success","status":"completed",
                          "conclusion":"failure","steps":[]
                        }]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                client.fetchActionsWorkflowAttemptJobsStrict(
                        "pat", RepoRef.of("owner", "repo"), 7L, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("identity was malformed or mismatched");
        server.verify();
    }

    @Test
    void strictActionsAttemptJobsRejectANullResponse()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo(attemptJobsUrl(1)))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                client.fetchActionsWorkflowAttemptJobsStrict(
                        "pat", RepoRef.of("owner", "repo"), 7L, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("null or empty");
        server.verify();
    }

    @Test
    void ordinaryPrEnrichmentKeepsItsBestEffortChecksFallback()
    {
        RestClient.Builder restBuilder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo("https://api.github.test/repos/owner/repo/commits/sha/check-runs"
                        + "?per_page=100&page=1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(client.fetchPrCheckRuns("pat", "owner", "repo", "sha")).isEmpty();

        server.verify();
    }

    @Test
    void checkRunFailuresAreNotReportedAsAnEmptyCiSnapshot()
    {
        RestClient.Builder restBuilder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        server.expect(requestTo("https://api.github.test/repos/owner/repo/commits/sha/check-runs"
                        + "?per_page=100&page=1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"checks unavailable\"}"));

        assertThatThrownBy(() -> client.fetchPrCheckRunsStrict("pat", "owner", "repo", "sha"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("checks unavailable");

        server.verify();
    }

    @Test
    void checkRunLogsAreFetchedAgainOnEveryCall()
    {
        RestClient.Builder restBuilder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        String detailUrl = "https://api.github.test/repos/owner/repo/check-runs/99";
        String logUrl = "https://api.github.test/repos/owner/repo/actions/jobs/101/logs";
        String detail = """
                {"id":99,"details_url":"https://github.com/owner/repo/actions/runs/1/job/101"}
                """;
        server.expect(requestTo(detailUrl))
                .andRespond(withSuccess(detail, MediaType.APPLICATION_JSON));
        server.expect(requestTo(logUrl))
                .andRespond(withSuccess("first log", MediaType.TEXT_PLAIN));
        server.expect(requestTo(detailUrl))
                .andRespond(withSuccess(detail, MediaType.APPLICATION_JSON));
        server.expect(requestTo(logUrl))
                .andRespond(withSuccess("second log", MediaType.TEXT_PLAIN));

        assertThat(client.fetchCheckRunLog("pat", RepoRef.of("owner", "repo"), 99L))
                .contains("first log");
        assertThat(client.fetchCheckRunLog("pat", RepoRef.of("owner", "repo"), 99L))
                .contains("second log");

        server.verify();
    }

    @Test
    void exactActionsJobLogCaptureReadsStrictUtf8ToEofAndFreezesItsDigest()
            throws Exception
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        byte[] bytes = "compiler failed: λ\nsecond line\n"
                .getBytes(StandardCharsets.UTF_8);
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/actions/jobs/101/logs"))
                .andRespond(withSuccess(bytes, MediaType.TEXT_PLAIN));

        ActionsJobLogCapture capture = client.fetchActionsJobLogStrict(
                "pat", RepoRef.of("owner", "repo"), 101L);

        assertThat(capture.status()).isEqualTo(ActionsJobLogStatus.COMPLETE);
        assertThat(capture.rawText()).isEqualTo(
                "compiler failed: λ\nsecond line\n");
        assertThat(capture.rawByteLength()).isEqualTo((long) bytes.length);
        assertThat(capture.sha256Digest()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(capture.detail()).isNull();
        server.verify();
    }

    @Test
    void exactActionsJobLogCaptureRejectsOversizeAndInvalidUtf8Evidence()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        String url = "https://api.github.test/repos/owner/repo/actions/jobs/101/logs";
        server.expect(requestTo(url)).andRespond(withSuccess(
                new byte[8 * 1024 * 1024 + 1], MediaType.TEXT_PLAIN));
        server.expect(requestTo(url)).andRespond(withSuccess(
                new byte[] {(byte) 0xC3, (byte) 0x28}, MediaType.TEXT_PLAIN));

        assertThat(client.fetchActionsJobLogStrict(
                "pat", RepoRef.of("owner", "repo"), 101L))
                .satisfies(capture -> {
                    assertThat(capture.status())
                            .isEqualTo(ActionsJobLogStatus.INCOMPLETE);
                    assertThat(capture.detail()).contains("8 MiB");
                    assertThat(capture.rawText()).isNull();
                });
        assertThat(client.fetchActionsJobLogStrict(
                "pat", RepoRef.of("owner", "repo"), 101L))
                .satisfies(capture -> {
                    assertThat(capture.status())
                            .isEqualTo(ActionsJobLogStatus.INCOMPLETE);
                    assertThat(capture.detail()).contains("strict UTF-8");
                    assertThat(capture.sha256Digest()).isNull();
                });
        server.verify();
    }

    @Test
    void exactActionsJobLogCaptureSeparatesUnavailableFromTransientFailure()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        String url = "https://api.github.test/repos/owner/repo/actions/jobs/101/logs";
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.GONE));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(url))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.fetchActionsJobLogStrict(
                "pat", RepoRef.of("owner", "repo"), 101L).status())
                .isEqualTo(ActionsJobLogStatus.UNAVAILABLE);
        assertThat(client.fetchActionsJobLogStrict(
                "pat", RepoRef.of("owner", "repo"), 101L))
                .satisfies(capture -> {
                    assertThat(capture.status())
                            .isEqualTo(ActionsJobLogStatus.UNAVAILABLE);
                    assertThat(capture.detail()).contains("denied");
                });
        assertThatThrownBy(() -> client.fetchActionsJobLogStrict(
                "pat", RepoRef.of("owner", "repo"), 101L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(
                        ((ResponseStatusException) failure).getStatusCode().value())
                        .isEqualTo(503));
        server.verify();
    }

    @Test
    void checkRunAnnotationsDropWarningsAndTheContentlessExitCodeEntry()
    {
        RestClient.Builder restBuilder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        // Shape GitHub actually returns for a failing Actions job: the generic
        // exit-code annotation, deprecation warnings, and — last — the
        // assertion the user actually wants to read.
        // The exit-code annotation's path is a bare ".github" with a line
        // number pointing into the workflow, so it looks source-located until
        // you check the path — verbatim shape from trinodb/trino#30504.
        String annotations = """
                [
                  {"annotation_level":"failure","title":"",
                   "message":"Process completed with exit code 1.",
                   "path":".github","start_line":278},
                  {"annotation_level":"warning","title":"Maven Install",
                   "message":"Listeners in io.trino.testng.services has been deprecated",
                   "path":"testing/trino-testing-services/src/main/java/Report.java","start_line":28},
                  {"annotation_level":"failure","title":"Upload test results",
                   "message":"Expecting actual: 1L to be less than: 1L",
                   "path":"plugin/trino-redshift/src/test/java/io/trino/plugin/redshift/TestRedshiftUnload.java",
                   "start_line":169}
                ]
                """;
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/check-runs/90465481459/annotations?per_page=100"))
                .andRespond(withSuccess(annotations, MediaType.APPLICATION_JSON));

        List<GitHubActionsRepository.CheckRunAnnotation> result =
                client.fetchCheckRunAnnotations("pat", RepoRef.of("owner", "repo"), 90465481459L);

        // Only the assertion survives: the warning is the wrong level, and the
        // exit-code entry says nothing the log won't say better.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Upload test results");
        assertThat(result.get(0).message()).isEqualTo("Expecting actual: 1L to be less than: 1L");
        assertThat(result.get(0).path())
                .isEqualTo("plugin/trino-redshift/src/test/java/io/trino/plugin/redshift/TestRedshiftUnload.java");
        assertThat(result.get(0).startLine()).isEqualTo(169);

        server.verify();
    }

    @Test
    void strictAnnotationsWalkEveryPageAndRetainGenericFailureEvidence()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = client(restBuilder);
        StringBuilder firstPage = new StringBuilder("[");
        for (int index = 0; index < 100; index++) {
            if (index > 0) {
                firstPage.append(',');
            }
            firstPage.append("{\"annotation_level\":\"warning\","
                    + "\"message\":\"warning\",\"path\":\"src/A.java\"}");
        }
        firstPage.append(']');
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/check-runs/99/annotations"
                                + "?per_page=100&page=1"))
                .andRespond(withSuccess(
                        firstPage.toString(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/check-runs/99/annotations"
                                + "?per_page=100&page=2"))
                .andRespond(withSuccess("""
                        [{"annotation_level":"failure","title":"",
                          "message":"Process completed with exit code 1.",
                          "path":".github","start_line":20}]
                        """, MediaType.APPLICATION_JSON));

        CheckRunAnnotationEvidence evidence =
                client.fetchCheckRunAnnotationsStrict(
                        "pat", RepoRef.of("owner", "repo"), 99L, 101);

        assertThat(evidence.complete()).isTrue();
        assertThat(evidence.observedAnnotationCount()).isEqualTo(101);
        assertThat(evidence.failureAnnotations()).singleElement()
                .extracting(GitHubActionsRepository.CheckRunAnnotation::message)
                .isEqualTo("Process completed with exit code 1.");
        server.verify();
    }

    private static String attemptJobsUrl(int page)
    {
        return "https://api.github.test/repos/owner/repo/actions/runs/7"
                + "/attempts/2/jobs?per_page=100&page=" + page;
    }

    private static String actionsJobsPage(
            int totalCount,
            int firstIdentity,
            int pageSize,
            long runId,
            int runAttempt)
    {
        StringBuilder json = new StringBuilder(
                "{\"total_count\":" + totalCount + ",\"jobs\":[");
        for (int index = 0; index < pageSize; index++) {
            if (index > 0) {
                json.append(',');
            }
            int identity = firstIdentity + index;
            json.append("{\"id\":")
                    .append(1000 + identity)
                    .append(",\"run_id\":")
                    .append(runId)
                    .append(",\"run_attempt\":")
                    .append(runAttempt)
                    .append(",\"head_sha\":\"merge-sha\",")
                    .append("\"check_run_url\":\"https://api.github.test")
                    .append("/repos/owner/repo/check-runs/")
                    .append(2000 + identity)
                    .append("\",\"name\":\"job-")
                    .append(identity)
                    .append("\",\"status\":\"completed\",")
                    .append("\"conclusion\":\"failure\",\"steps\":[{")
                    .append("\"number\":1,\"name\":\"Check results\",")
                    .append("\"status\":\"completed\",")
                    .append("\"conclusion\":\"failure\"}]}");
        }
        return json.append("]}").toString();
    }

    private static String checkRunsPage(int totalCount, int pageSize)
    {
        StringBuilder json = new StringBuilder(
                "{\"total_count\":" + totalCount + ",\"check_runs\":[");
        for (int index = 0; index < pageSize; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":")
                    .append(index + 1)
                    .append(",\"name\":\"check-")
                    .append(index + 1)
                    .append("\",\"status\":\"completed\",")
                    .append("\"conclusion\":\"success\"}");
        }
        return json.append("]}").toString();
    }

    private static GitHubClient client(RestClient.Builder restBuilder)
    {
        return new GitHubClient(restBuilder.build());
    }

    private static final class GitHubClient implements PullRequestRepository
    {
        private final GitHubActionsClient actions;

        private GitHubClient(RestClient restClient)
        {
            this.actions = new GitHubActionsClient(restClient);
        }

        @Override
        public List<PrCheckRunState> fetchPrCheckRuns(
                String pat, String owner, String repo, String sha)
        {
            return actions.fetchPrCheckRuns(pat, owner, repo, sha);
        }

        @Override
        public List<PrCheckRunState> fetchPrCheckRunsStrict(
                String pat, String owner, String repo, String sha)
        {
            return actions.fetchPrCheckRunsStrict(pat, owner, repo, sha);
        }

        @Override
        public Optional<GitHubActionsRepository.ActionsWorkflowRun> fetchActionsWorkflowRun(
                String pat, RepoRef repo, long runId)
        {
            return actions.fetchActionsWorkflowRun(pat, repo, runId);
        }

        @Override
        public GitHubActionsRepository.ActionsWorkflowRun fetchActionsWorkflowRunAttemptStrict(
                String pat, RepoRef repo, long runId, int runAttempt)
        {
            return actions.fetchActionsWorkflowRunAttemptStrict(pat, repo, runId, runAttempt);
        }

        @Override
        public ActionsWorkflowJobSetEvidence fetchActionsWorkflowAttemptJobsStrict(
                String pat, RepoRef repo, long runId, int runAttempt)
        {
            return actions.fetchActionsWorkflowAttemptJobsStrict(pat, repo, runId, runAttempt);
        }

        @Override
        public Optional<String> fetchCheckRunLog(String pat, RepoRef repo, long checkRunId)
        {
            return actions.fetchCheckRunLog(pat, repo, checkRunId);
        }

        @Override
        public ActionsJobLogCapture fetchActionsJobLogStrict(
                String pat, RepoRef repo, long jobId)
        {
            return actions.fetchActionsJobLogStrict(pat, repo, jobId);
        }

        @Override
        public List<GitHubActionsRepository.CheckRunAnnotation> fetchCheckRunAnnotations(
                String pat, RepoRef repo, long checkRunId)
        {
            return actions.fetchCheckRunAnnotations(pat, repo, checkRunId);
        }

        @Override
        public CheckRunAnnotationEvidence fetchCheckRunAnnotationsStrict(
                String pat, RepoRef repo, long checkRunId, int expectedAnnotationCount)
        {
            return actions.fetchCheckRunAnnotationsStrict(
                    pat, repo, checkRunId, expectedAnnotationCount);
        }
    }
}
