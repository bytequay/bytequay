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

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.impl.FileAgentResourceService;
import com.bytequay.app.ci.BoundedCiLog;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiJob;
import com.bytequay.app.ci.CiRawLogStore;
import com.bytequay.app.ci.CiRerunResult;
import com.bytequay.app.ci.CiStep;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.ReadCiLogQuery;
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static com.bytequay.app.ci.CiRerunResult.Outcome.ACCEPTED;
import static com.bytequay.app.ci.CiRerunResult.Outcome.OBSOLETE;
import static com.bytequay.app.ci.CiRerunResult.Outcome.REJECTED;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubActionsClient
{
    @TempDir
    private static Path temporaryDirectory;

    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of("owner", "repo", 7);
    private static final String PAT = "github_pat_secret_123456789";
    private static final String HEAD_SHA = "1111111111111111111111111111111111111111";
    private static final String BASE_SHA = "2222222222222222222222222222222222222222";

    @Test
    void descriptionEditReplacesTheOlderFailedRunOnTheSameHead()
    {
        Fixture fixture = fixture();
        expectPullRequest(fixture);
        fixture.apiServer.expect(requestTo(runsUrl(1))).andRespond(withSuccess("""
                {"total_count":2,"workflow_runs":[
                  {"id":900,"workflow_id":80,"name":"Description","run_attempt":4,
                   "status":"completed","conclusion":"failure","head_sha":"%s"},
                  {"id":901,"workflow_id":80,"name":"Description","run_attempt":1,
                   "status":"completed","conclusion":"success","head_sha":"%s"}]}
                """.formatted(HEAD_SHA, HEAD_SHA), MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo(jobsUrl(901, 1))).andRespond(withSuccess("""
                {"total_count":1,"jobs":[{"id":902,"name":"check-description","run_attempt":1,
                  "status":"completed","conclusion":"success","steps":[]}]}
                """, MediaType.APPLICATION_JSON));
        CiHeadSnapshot snapshot = fixture.client.inspect(PULL_REQUEST);
        assertThat(snapshot.workflowRuns()).extracting(CiWorkflowRun::id).containsExactly(901L);
        assertThat(snapshot.green()).isTrue();
        fixture.verify();
    }

    @Test
    void rereadsUnchangedEvidenceWithItsEtag()
    {
        Fixture fixture = fixture();
        String pullRequest = """
                {"state":"open","user":{"login":"author"},
                 "head":{"ref":"feature","sha":"%s","repo":{"full_name":"owner/repo"}},
                 "base":{"ref":"main","sha":"%s","repo":{"full_name":"owner/repo"}},
                 "html_url":"https://github.com/owner/repo/pull/7"}
                """.formatted(HEAD_SHA, BASE_SHA);
        String runs = """
                {"total_count":1,"workflow_runs":[{"id":900,"workflow_id":80,"name":"Build","run_attempt":1,
                  "status":"in_progress","conclusion":null,"head_sha":"%s"}]}
                """.formatted(HEAD_SHA);
        String pullRequestUrl = "https://api.github.test/repos/owner/repo/pulls/7";
        fixture.apiServer.expect(requestTo(pullRequestUrl)).andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
                .andRespond(withSuccess(pullRequest, MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"pr-1\""));
        fixture.apiServer.expect(requestTo(runsUrl(1))).andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
                .andRespond(withSuccess(runs, MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"runs-1\""));
        fixture.apiServer.expect(requestTo(jobsUrl(900, 1))).andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
                .andRespond(withSuccess("""
                        {"total_count":1,"jobs":[{"id":902,"name":"Compile","run_attempt":1,"status":"in_progress","conclusion":null,"steps":[]}]}
                        """, MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"jobs-1\""));
        fixture.apiServer.expect(requestTo(pullRequestUrl)).andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"pr-1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, "\"pr-1\""));
        fixture.apiServer.expect(requestTo(runsUrl(1))).andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"runs-1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, "\"runs-1\""));
        fixture.apiServer.expect(requestTo(jobsUrl(900, 1))).andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"jobs-1\""))
                .andRespond(withSuccess("""
                        {"total_count":1,"jobs":[{"id":902,"name":"Compile","run_attempt":1,"status":"completed","conclusion":"success","steps":[]}]}
                        """, MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"jobs-2\""));

        CiHeadSnapshot first = fixture.client.inspect(PULL_REQUEST);
        CiHeadSnapshot second = fixture.client.inspect(PULL_REQUEST);

        assertThat(second.headSha()).isEqualTo(first.headSha()).isEqualTo(HEAD_SHA);
        assertThat(second.workflowRuns()).extracting(CiWorkflowRun::status).containsExactly("in_progress");
        assertThat(second.workflowRuns().getFirst().jobs()).extracting(CiJob::status).containsExactly("completed");
        fixture.verify();
    }

    @Test
    void newerPullRequestRunDoesNotHideAFailedPushRunOfTheSameWorkflow()
    {
        Fixture fixture = fixture();
        expectPullRequest(fixture);
        fixture.apiServer.expect(requestTo(runsUrl(1))).andRespond(withSuccess("""
                {"total_count":2,"workflow_runs":[
                  {"id":900,"workflow_id":80,"event":"push","name":"Build","run_attempt":1,
                   "status":"completed","conclusion":"failure","head_sha":"%s"},
                  {"id":901,"workflow_id":80,"event":"pull_request","name":"Build","run_attempt":1,
                   "status":"completed","conclusion":"success","head_sha":"%s"}]}
                """.formatted(HEAD_SHA, HEAD_SHA), MediaType.APPLICATION_JSON));
        for (int runId = 900; runId <= 901; runId++) {
            fixture.apiServer.expect(requestTo(jobsUrl(runId, 1))).andRespond(withSuccess("""
                    {"total_count":1,"jobs":[{"id":%d,"name":"Build","run_attempt":1,
                      "status":"completed","conclusion":"%s","steps":[]}]}
                    """.formatted(runId + 10, runId == 900 ? "failure" : "success"), MediaType.APPLICATION_JSON));
        }
        CiHeadSnapshot snapshot = fixture.client.inspect(PULL_REQUEST);
        assertThat(snapshot.workflowRuns()).hasSize(2);
        assertThat(snapshot.green()).isFalse();
        fixture.verify();
    }

    @Test
    void readsTheFullDescriptionIncludingWhitespace()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/7"))
                .andRespond(withSuccess("{\"body\":\"Fixes #1.\\n\\nRelease notes\"}", MediaType.APPLICATION_JSON));
        assertThat(fixture.client.readDescription(PULL_REQUEST)).isEqualTo("Fixes #1.\n\nRelease notes");
        fixture.verify();
    }

    @Test
    void readsLatestAttemptsAcrossPagesAndReturnsEarlyFailure()
    {
        Fixture fixture = fixture();
        expectPullRequest(fixture);
        fixture.apiServer.expect(requestTo(runsUrl(1)))
                .andRespond(withSuccess("""
                        {"total_count":2,"workflow_runs":[{
                          "id":900,"workflow_id":80,"name":"Build","run_attempt":2,
                          "status":"in_progress","conclusion":null,"head_sha":"%s",
                          "html_url":"https://github.com/owner/repo/actions/runs/900"
                        }]}
                        """.formatted(HEAD_SHA), MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo(runsUrl(2)))
                .andRespond(withSuccess("""
                        {"total_count":2,"workflow_runs":[{
                          "id":900,"workflow_id":80,"name":"Build","run_attempt":1,
                          "status":"completed","conclusion":"success","head_sha":"%s",
                          "html_url":"https://github.com/owner/repo/actions/runs/900"
                        }]}
                        """.formatted(HEAD_SHA), MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo(jobsUrl(900, 1)))
                .andRespond(withSuccess("""
                        {"total_count":3,"jobs":[{
                          "id":902,"name":"Compile","run_attempt":2,"status":"completed",
                          "conclusion":"failure","html_url":"https://github.com/job/902",
                          "steps":[{"number":3,"name":"Compile Java","status":"completed",
                            "conclusion":"failure"}]
                        }]}
                        """, MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo(jobsUrl(900, 2)))
                .andRespond(withSuccess("""
                        {"total_count":3,"jobs":[
                          {"id":901,"name":"Compile","run_attempt":1,"status":"completed",
                            "conclusion":"success","html_url":"https://github.com/job/901","steps":[]},
                          {"id":903,"name":"Slow integration","run_attempt":2,"status":"in_progress",
                            "conclusion":null,"html_url":"https://github.com/job/903","steps":[]}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        CiHeadSnapshot snapshot = fixture.client.inspect(PULL_REQUEST);

        assertThat(snapshot.open()).isTrue();
        assertThat(snapshot.author()).isEqualTo("author");
        assertThat(snapshot.headRepository()).isEqualTo(RepoRef.of("owner", "repo"));
        assertThat(snapshot.headRef()).isEqualTo("feature");
        assertThat(snapshot.baseRef()).isEqualTo("main");
        assertThat(snapshot.workflowRuns()).singleElement().satisfies(run -> {
            assertThat(run.attempt()).isEqualTo(2);
            assertThat(run.status()).isEqualTo("in_progress");
        });
        assertThat(snapshot.jobs()).extracting(CiJob::id).containsExactly(902L, 903L);
        assertThat(snapshot.actionableFailures()).extracting(CiJob::id).containsExactly(902L);
        assertThat(snapshot.pending()).isTrue();
        assertThat(snapshot.green()).isFalse();
        fixture.verify();
    }

    @Test
    void readsForkIdentityAndChecksFromTheBaseRepository()
    {
        Fixture fixture = fixture();
        expectPullRequest(fixture, "author/fork");
        fixture.apiServer.expect(requestTo(runsUrl(1)))
                .andRespond(withSuccess("{\"total_count\":0,\"workflow_runs\":[]}", MediaType.APPLICATION_JSON));

        CiHeadSnapshot snapshot = fixture.client.inspect(PULL_REQUEST);

        assertThat(snapshot.pullRequest()).isEqualTo(PULL_REQUEST);
        assertThat(snapshot.headRepository()).isEqualTo(RepoRef.of("author", "fork"));
        assertThat(snapshot.green()).isFalse();
        fixture.verify();
    }

    @Test
    void refusesDeletedForkWithoutReadingWorkflowRuns()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/7"))
                .andRespond(withSuccess("""
                        {"state":"open","user":{"login":"author"},
                         "head":{"ref":"feature","sha":"%s","repo":null},
                         "base":{"ref":"main","sha":"%s","repo":{"full_name":"owner/repo"}},
                         "html_url":"https://github.com/owner/repo/pull/7"}
                        """.formatted(HEAD_SHA, BASE_SHA), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.inspect(PULL_REQUEST))
                .hasMessageContaining("incomplete pull-request head facts");
        fixture.verify();
    }

    @Test
    void identifiesAllPendingHead()
    {
        Fixture fixture = fixture();
        expectSingleHead(fixture, "in_progress", null, "queued", null);

        CiHeadSnapshot snapshot = fixture.client.inspect(PULL_REQUEST);

        assertThat(snapshot.actionableFailures()).isEmpty();
        assertThat(snapshot.pending()).isTrue();
        assertThat(snapshot.green()).isFalse();
        fixture.verify();
    }

    @Test
    void identifiesAllGreenHead()
    {
        Fixture fixture = fixture();
        expectSingleHead(fixture, "completed", "success", "completed", "success");

        CiHeadSnapshot snapshot = fixture.client.inspect(PULL_REQUEST);

        assertThat(snapshot.actionableFailures()).isEmpty();
        assertThat(snapshot.pending()).isFalse();
        assertThat(snapshot.green()).isTrue();
        fixture.verify();
    }

    @Test
    void refusesIncompleteWorkflowPagesInsteadOfReturningPartialGreen()
    {
        Fixture fixture = fixture();
        expectPullRequest(fixture);
        fixture.apiServer.expect(requestTo(runsUrl(1))).andRespond(withSuccess("{\"total_count\":1,\"workflow_runs\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.inspect(PULL_REQUEST)).hasMessageContaining("incomplete workflow-run pages");
        fixture.verify();
    }

    @Test
    void duplicateWorkflowAttemptsCannotFillTheReportedCountAndHideAnotherRun()
    {
        Fixture fixture = fixture();
        expectPullRequest(fixture);
        String response = """
                {"total_count":2,"workflow_runs":[{
                  "id":900,"workflow_id":80,"name":"Build","run_attempt":1,
                  "status":"completed","conclusion":"success","head_sha":"%s",
                  "html_url":"https://github.com/owner/repo/actions/runs/900"
                }]}
                """.formatted(HEAD_SHA);
        fixture.apiServer.expect(requestTo(runsUrl(1))).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo(runsUrl(2))).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.inspect(PULL_REQUEST)).hasMessageContaining("duplicate workflow attempts");
        fixture.verify();
    }

    @Test
    void duplicateJobIdsCannotFillTheReportedCountAndHideAnotherJob()
    {
        Fixture fixture = fixture();
        expectPullRequest(fixture);
        fixture.apiServer.expect(requestTo(runsUrl(1))).andRespond(withSuccess("""
                {"total_count":1,"workflow_runs":[{
                  "id":900,"workflow_id":80,"name":"Build","run_attempt":1,
                  "status":"completed","conclusion":"success","head_sha":"%s",
                  "html_url":"https://github.com/owner/repo/actions/runs/900"
                }]}
                """.formatted(HEAD_SHA), MediaType.APPLICATION_JSON));
        String response = """
                {"total_count":2,"jobs":[{
                  "id":902,"name":"Compile","run_attempt":1,"status":"completed",
                  "conclusion":"success","html_url":"https://github.com/job/902","steps":[]
                }]}
                """;
        fixture.apiServer.expect(requestTo(jobsUrl(900, 1))).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo(jobsUrl(900, 2))).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.inspect(PULL_REQUEST)).hasMessageContaining("duplicate workflow jobs");
        fixture.verify();
    }

    @Test
    void refusesMissingWorkflowEvidenceInsteadOfAssumingNoOtherRuns()
    {
        for (String response : ImmutableList.of("{}", "{\"total_count\":0}", "{\"workflow_runs\":[]}")) {
            Fixture fixture = fixture();
            expectPullRequest(fixture);
            fixture.apiServer.expect(requestTo(runsUrl(1))).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> fixture.client.inspect(PULL_REQUEST)).hasMessageContaining("incomplete workflow-run evidence");
            fixture.verify();
        }
    }

    @Test
    void readsInlineDiagnosticLogWithoutCreatingATaskBlob()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT))
                .andRespond(withSuccess("2026-01-01T00:00:00Z error: failed with " + PAT + "\n", MediaType.TEXT_PLAIN));

        assertThat(fixture.client.readJobLog(PULL_REQUEST, 902)).isEqualTo("2026-01-01T00:00:00Z error: failed with [REDACTED]\n");
        assertThat(fixture.stored.get()).isNull();
        fixture.verify();
    }

    @Test
    void reportsMissingInlineLogs()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> fixture.client.readJobLog(PULL_REQUEST, 902))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("missing or expired");
        assertThat(fixture.stored.get()).isNull();
        fixture.verify();
    }

    @Test
    void followsLogRedirectWithoutCredentialsAndStoresBoundedRedactedBlob()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + PAT))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "https://logs.github.test/job-902"));
        String rawLog = """
                Authorization: Bearer %s
                2026-08-17T01:02:03Z \u001B[31merror: compilation failed with ghp_anotherSecret123\u001B[0m
                %s
                """.formatted(PAT, "x".repeat(400));
        fixture.logServer.expect(request -> {
            assertThat(request.getURI()).hasToString("https://logs.github.test/job-902");
            assertThat(request.getHeaders()).doesNotContainKey(HttpHeaders.AUTHORIZATION);
            assertThat(request.getHeaders()).doesNotContainKey(HttpHeaders.RANGE);
            assertThat(request.getHeaders().getFirst(HttpHeaders.ACCEPT_ENCODING)).isEqualTo("identity");
        }).andRespond(withSuccess(rawLog, MediaType.TEXT_PLAIN));

        BoundedCiLog log = fixture.client.readLog(logQuery(1024));
        String stored = new String(fixture.stored.get(), StandardCharsets.UTF_8);

        assertThat(log.blob()).isPresent();
        assertThat(log.byteCount()).isEqualTo(fixture.stored.get().length);
        assertThat(log.truncated()).isFalse();
        assertThat(log.omittedBeginning()).isFalse();
        assertThat(log.terminalErrorLines()).containsExactly("error: compilation failed with [REDACTED]");
        assertThat(stored).doesNotContain(PAT, "ghp_anotherSecret123");
        assertThat(stored).contains("error: compilation failed with [REDACTED]");
        assertThat(log.rawLogId()).isPresent();
        fixture.verify();
    }

    @Test
    void compactsCompleteLogsAfterRedactionWithoutClaimingDownloadTruncation()
    {
        Fixture fixture = fixture();
        String rawLog = "Downloaded build dependency successfully\n".repeat(2000)
                + "Compilation output:\n[ERROR] Compilation failed with " + PAT + " and ghp_anotherSecret123\n"
                + "src/main/java/Example.java:12: cannot find symbol\nBUILD FAILURE\n";
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andRespond(withSuccess(rawLog, MediaType.TEXT_PLAIN));

        BoundedCiLog log = fixture.client.readLog(logQuery(1024 * 1024));
        String stored = new String(fixture.stored.get(), StandardCharsets.UTF_8);

        assertThat(log.truncated()).isFalse();
        assertThat(log.omittedBeginning()).isFalse();
        assertThat(log.compacted()).isTrue();
        assertThat(log.byteCount()).isEqualTo(fixture.stored.get().length).isLessThanOrEqualTo(32 * 1024);
        assertThat(stored).contains("Compilation failed with [REDACTED] and [REDACTED]", "Example.java:12", "BUILD FAILURE");
        assertThat(stored).doesNotContain(PAT, "ghp_anotherSecret123");
        assertThat(log.terminalErrorLines()).contains("[ERROR] Compilation failed with [REDACTED] and [REDACTED]");
        fixture.verify();
    }

    @Test
    void inlineAndAutofixLogsPreserveTheSameOversizedRedactedStack()
    {
        Fixture fixture = fixture();
        String failure = "[ERROR] ExampleTest.testQuery <<< ERROR!\n"
                + "java.lang.IllegalStateException: authentication failed with " + PAT + "\n"
                + "    at Worker.repeat(Worker.java:42)\n".repeat(1400)
                + "Caused by: java.net.ConnectException: refused\n"
                + "    at Client.connect(Client.java:55)\n";
        String rawLog = "[INFO] downloaded dependency\n".repeat(4000) + failure + "[INFO] finished\n";
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andRespond(withSuccess(rawLog, MediaType.TEXT_PLAIN));
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andRespond(withSuccess(rawLog, MediaType.TEXT_PLAIN));

        BoundedCiLog log = fixture.client.readLog(logQuery(1024 * 1024));
        String stored = new String(fixture.stored.get(), StandardCharsets.UTF_8);
        String inline = fixture.client.readJobLog(PULL_REQUEST, 902);

        assertThat(stored).endsWith(inline);
        assertThat(inline).contains(failure.replace(PAT, "[REDACTED]")).doesNotContain(PAT, "line shortened", "previous line repeated");
        assertThat(log.byteCount()).isGreaterThan(32 * 1024).isLessThanOrEqualTo(1024 * 1024);
        assertThat(log.compacted()).isTrue();
        assertThat(log.truncated()).isFalse();
        fixture.verify();
    }

    @Test
    void cachesTheCompleteLogBeyondTheExcerptLimitAndReturnsAnExplicitTailWhenNoDiagnosticsMatch()
    {
        Fixture fixture = fixture();
        String source = "original beginning\r\n" + "x".repeat(8180) + PAT + "\r\n"
                + "downloaded 日志🙂 dependency\r\n".repeat(50000) + "##[error]Process completed with exit code 1.\n";
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andRespond(withSuccess(source, MediaType.TEXT_PLAIN));
        ReadCiLogQuery query = logQuery(1024 * 1024);

        BoundedCiLog log = fixture.client.readLog(query);
        String stored = new String(fixture.stored.get(), StandardCharsets.UTF_8);
        assertThat(log.diagnosticsMatched()).isFalse();
        assertThat(log.compacted()).isTrue();
        assertThat(log.truncated()).isFalse();
        assertThat(stored).contains("No diagnostic keywords", "only the log tail", "32 KiB", "read_ci_raw_log", "exit code 1");
        assertThat(stored).doesNotContain("original beginning", PAT);
        assertThat(stored.length()).isLessThan(33 * 1024);
        String id = log.rawLogId().orElseThrow();
        assertThat(fixture.rawLogs.read(query.taskId(), id)).isEqualTo(source.replace(PAT, "[REDACTED]"));
        assertThat(fixture.rawLogs.page(query.taskId(), id, 0, 32768).text()).startsWith("original beginning\r\n");
        assertThat(fixture.client.readLog(query).rawLogId()).contains(id);
        fixture.verify();
    }

    @Test
    void reportsMissingOrExpiredLogWithoutWritingBlob()
    {
        for (HttpStatus status : ImmutableList.of(HttpStatus.NOT_FOUND, HttpStatus.GONE)) {
            Fixture fixture = fixture();
            fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                    .andRespond(withStatus(status));

            BoundedCiLog log = fixture.client.readLog(logQuery(1024));

            assertThat(log.blob()).isEmpty();
            assertThat(log.unavailableReason()).contains("GitHub Actions log is missing or expired");
            assertThat(fixture.stored.get()).isNull();
            fixture.verify();
        }
    }

    @Test
    void reportsMissingOrExpiredRedirectedLogWithoutWritingBlob()
    {
        for (HttpStatus status : ImmutableList.of(HttpStatus.NOT_FOUND, HttpStatus.GONE)) {
            Fixture fixture = fixture();
            fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                    .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://logs.github.test/job-902"));
            fixture.logServer.expect(requestTo("https://logs.github.test/job-902"))
                    .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                    .andRespond(withStatus(status));

            BoundedCiLog log = fixture.client.readLog(logQuery(1024));

            assertThat(log.blob()).isEmpty();
            assertThat(log.unavailableReason()).contains("GitHub Actions log is missing or expired");
            assertThat(fixture.stored.get()).isNull();
            fixture.verify();
        }
    }

    @Test
    void reportsUnavailableRangeWithoutAnotherDownload()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://logs.github.test/job-902"));
        fixture.logServer.expect(requestTo("https://logs.github.test/job-902"))
                .andRespond(withStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE));

        BoundedCiLog log = fixture.client.readLog(logQuery(1024));

        assertThat(log.blob()).isEmpty();
        assertThat(log.unavailableReason()).contains("GitHub did not return a complete raw log");
        assertThat(fixture.stored.get()).isNull();
        fixture.verify();
    }

    @Test
    void retriesTransientApiErrorsOnceBeforeReturningLog()
    {
        for (HttpStatus status : ImmutableList.of(HttpStatus.REQUEST_TIMEOUT, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE)) {
            Fixture fixture = fixture();
            fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs")).andRespond(withStatus(status));
            fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                    .andRespond(withSuccess("error: failed\n", MediaType.TEXT_PLAIN));

            assertThat(fixture.client.readLog(logQuery(1024)).terminalErrorLines()).containsExactly("error: failed");
            fixture.verify();
        }
    }

    @Test
    void refreshesRedirectBeforeRetryingHttpOrTransportDownloadFailure()
    {
        for (boolean transportFailure : ImmutableList.of(false, true)) {
            Fixture fixture = fixture();
            fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                    .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://logs.github.test/job-902"));
            fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                    .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://logs.github.test/job-902?sig=fresh"));
            fixture.logServer.expect(requestTo("https://logs.github.test/job-902"))
                    .andRespond(transportFailure ? withException(new IOException("connection reset")) : withStatus(HttpStatus.BAD_GATEWAY));
            fixture.logServer.expect(requestTo("https://logs.github.test/job-902?sig=fresh"))
                    .andExpect(method(HttpMethod.GET)).andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                    .andRespond(withSuccess("error: failed\n", MediaType.TEXT_PLAIN));

            assertThat(fixture.client.readLog(logQuery(1024)).terminalErrorLines()).containsExactly("error: failed");
            fixture.verify();
        }
    }

    @Test
    void reportsExhaustedTransientApiAndRedirectFailuresWithoutWritingBlob()
    {
        for (HttpStatus status : ImmutableList.of(HttpStatus.REQUEST_TIMEOUT, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE)) {
            for (boolean redirected : ImmutableList.of(false, true)) {
                Fixture fixture = fixture();
                for (int attempt = 0; attempt < 2; attempt++) {
                    if (redirected) {
                        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://logs.github.test/job-902"));
                        fixture.logServer.expect(requestTo("https://logs.github.test/job-902")).andRespond(withStatus(status).body(PAT));
                    }
                    else {
                        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs")).andRespond(withStatus(status).body(PAT));
                    }
                }

                BoundedCiLog log = fixture.client.readLog(logQuery(1024));

                assertThat(log.blob()).isEmpty();
                assertThat(log.unavailableReason()).contains("GitHub Actions log retrieval failed after 2 attempts (HTTP " + status.value() + ")");
                assertThat(fixture.stored.get()).isNull();
                fixture.verify();
            }
        }
    }

    @Test
    void reportsExhaustedApiAndRedirectIoErrorsWithoutExposingRequestDetails()
    {
        for (IOException failure : ImmutableList.of(new IOException(PAT), new SocketTimeoutException("https://logs.github.test/?sig=private"))) {
            for (boolean redirected : ImmutableList.of(false, true)) {
                Fixture fixture = fixture();
                for (int attempt = 0; attempt < 2; attempt++) {
                    if (redirected) {
                        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://logs.github.test/job-902"));
                        fixture.logServer.expect(requestTo("https://logs.github.test/job-902")).andRespond(withException(failure));
                    }
                    else {
                        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs")).andRespond(withException(failure));
                    }
                }

                BoundedCiLog log = fixture.client.readLog(logQuery(1024));

                assertThat(log.blob()).isEmpty();
                assertThat(log.unavailableReason()).contains("GitHub Actions log retrieval failed after 2 attempts because of a network or read timeout error");
                assertThat(fixture.stored.get()).isNull();
                fixture.verify();
            }
        }
    }

    @Test
    void doesNotRetryAuthorizationErrorsOrRateLimits()
    {
        for (HttpStatus status : ImmutableList.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS)) {
            for (boolean redirected : ImmutableList.of(false, true)) {
                Fixture fixture = fixture();
                if (redirected) {
                    fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                            .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://logs.github.test/job-902"));
                    fixture.logServer.expect(requestTo("https://logs.github.test/job-902")).andRespond(withStatus(status).header(HttpHeaders.RETRY_AFTER, "120"));
                }
                else {
                    fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                            .andRespond(withStatus(status).header(HttpHeaders.RETRY_AFTER, "120"));
                }

                if (status == HttpStatus.TOO_MANY_REQUESTS) {
                    assertThat(fixture.client.readLog(logQuery(1024)).unavailableReason())
                            .contains("GitHub Actions log retrieval is rate limited; no immediate retry was attempted");
                }
                else {
                    assertThatThrownBy(() -> fixture.client.readLog(logQuery(1024))).isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode()).isEqualTo(status));
                }
                assertThat(fixture.stored.get()).isNull();
                fixture.verify();
            }
        }
    }

    @Test
    void propagatesInterruptionDuringApiOrDownloadWithoutRetrying()
    {
        for (String stage : ImmutableList.of("api", "download")) {
            Fixture fixture = fixture();
            MockRestServiceServer server = fixture.apiServer;
            String url = "https://api.github.test/repos/owner/repo/actions/jobs/902/logs";
            if (!stage.equals("api")) {
                fixture.apiServer.expect(requestTo(url)).andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://logs.github.test/job-902"));
                server = fixture.logServer;
                url = "https://logs.github.test/job-902";
            }
            server.expect(requestTo(url)).andRespond(request -> {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            });

            try {
                assertThatThrownBy(() -> fixture.client.readLog(logQuery(1024))).isInstanceOf(ResourceAccessException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            }
            finally {
                Thread.interrupted();
            }
            assertThat(fixture.stored.get()).isNull();
            fixture.verify();
        }
    }

    @Test
    void doesNotHideCancellationOrProgrammingErrors()
    {
        for (RuntimeException failure : ImmutableList.of(new CancellationException("stopped"), new IllegalStateException("unexpected response"))) {
            Fixture fixture = fixture();
            fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs")).andRespond(request -> {
                throw failure;
            });

            assertThatThrownBy(() -> fixture.client.readLog(logQuery(1024))).isSameAs(failure);
            assertThat(fixture.stored.get()).isNull();
            fixture.verify();
        }
    }

    @Test
    void doesNotRetryOrHideBlobStorageFailure()
    {
        Fixture fixture = fixture(PAT, true);
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andRespond(withSuccess("error: failed\n", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> fixture.client.readLog(logQuery(1024))).isInstanceOf(AgentStorageException.class).hasMessage("disk unavailable");
        assertThat(fixture.stored.get()).isNull();
        fixture.verify();
    }

    @Test
    void rejectsUnsafeLogRedirectWithoutDownloadingOrWriting()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "http://logs.github.test/job-902"));

        BoundedCiLog log = fixture.client.readLog(logQuery(1024));

        assertThat(log.blob()).isEmpty();
        assertThat(log.unavailableReason()).contains("GitHub returned an unsafe or missing log location");
        assertThat(fixture.stored.get()).isNull();
        fixture.verify();
    }

    @Test
    void acceptsExactFailedJobRerun()
    {
        Fixture fixture = fixture();
        expectFailedWorkflow(fixture);
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/runs/900/rerun-failed-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED));

        CiRerunResult result = fixture.client.rerunFailedJobs(rerunCommand(true));

        assertThat(result.outcome()).isEqualTo(ACCEPTED);
        fixture.verify();
    }

    @Test
    void returnsPermissionRejectionWithoutClaimingCiResult()
    {
        Fixture fixture = fixture();
        expectFailedWorkflow(fixture);
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/runs/900/rerun-failed-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Actions write permission is required\"}"));

        CiRerunResult result = fixture.client.rerunFailedJobs(rerunCommand(true));

        assertThat(result.outcome()).isEqualTo(REJECTED);
        assertThat(result.reason()).isEqualTo("Actions write permission is required");
        fixture.verify();
    }

    @Test
    void rejectsUnapprovedRerunWithoutCallingGitHub()
    {
        Fixture fixture = fixture();

        CiRerunResult result = fixture.client.rerunFailedJobs(rerunCommand(false));

        assertThat(result.outcome()).isEqualTo(REJECTED);
        fixture.verify();
    }

    @Test
    void treatsChangedWorkflowAttemptAsObsoleteWithoutRerunning()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/runs/900"))
                .andRespond(withSuccess("""
                        {"id":900,"workflow_id":80,"name":"Build","run_attempt":3,
                         "status":"completed","conclusion":"failure","head_sha":"%s",
                         "html_url":"https://github.com/owner/repo/actions/runs/900"}
                        """.formatted(HEAD_SHA), MediaType.APPLICATION_JSON));

        CiRerunResult result = fixture.client.rerunFailedJobs(rerunCommand(true));

        assertThat(result.outcome()).isEqualTo(OBSOLETE);
        fixture.verify();
    }

    @Test
    void readsExistingPrCheckRuns()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/check-runs?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        {"total_count":1,"check_runs":[{
                          "id":99,"name":"build","status":"completed","conclusion":"failure",
                          "started_at":"2026-08-25T12:30:00Z",
                          "completed_at":"2026-08-25T12:34:56Z",
                          "html_url":"https://github.com/owner/repo/actions/runs/7/job/9",
                          "output":{"title":"Tests failed","summary":"One failure"}
                        }]}
                        """, MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/status"))
                .andRespond(withSuccess("""
                        {"statuses":[
                          {"id":100,"context":"license","state":"success",
                           "target_url":"https://github.com/owner/repo/status/100",
                           "updated_at":"2026-08-25T12:35:00Z"},
                          {"id":101,"context":"deploy","state":"pending",
                           "target_url":"https://github.com/owner/repo/status/101",
                           "updated_at":"2026-08-25T12:36:00Z"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<PrCheckRunState> checks = fixture.client.refreshPrCheckRuns("pat", "owner", "repo", "head", ImmutableList.of(), Optional.empty(), Optional.empty()).checkRuns();
        PrCheckRunState check = checks.getFirst();

        assertThat(check.githubId()).isEqualTo(99L);
        assertThat(check.name()).isEqualTo("build");
        assertThat(check.conclusion()).isEqualTo("failure");
        assertThat(check.startedAt()).isEqualTo(Instant.parse("2026-08-25T12:30:00Z"));
        assertThat(check.completedAt()).isEqualTo(Instant.parse("2026-08-25T12:34:56Z"));
        assertThat(checks).hasSize(3);
        assertThat(checks.get(1).source()).isEqualTo("status");
        assertThat(checks.get(1).startedAt()).isNull();
        assertThat(checks.get(1).completedAt()).isEqualTo(Instant.parse("2026-08-25T12:35:00Z"));
        assertThat(checks.get(2).status()).isEqualTo("in_progress");
        assertThat(checks.get(2).startedAt()).isEqualTo(Instant.parse("2026-08-25T12:36:00Z"));
        assertThat(checks.get(2).completedAt()).isNull();
        fixture.verify();
    }

    @Test
    void readsEveryPrCheckRunPage()
    {
        Fixture fixture = fixture();
        String firstPage = IntStream.range(0, 100)
                .mapToObj(index -> """
                        {"id":%d,"name":"check-%d","status":"completed","conclusion":"success"}
                        """.formatted(index + 1, index + 1))
                .collect(joining(",", "{\"total_count\":101,\"check_runs\":[", "]}"));
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/check-runs?per_page=100&page=1"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/check-runs?per_page=100&page=2"))
                .andRespond(withSuccess("""
                        {"total_count":101,"check_runs":[
                          {"id":101,"name":"check-101","status":"completed","conclusion":"success"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/status"))
                .andRespond(withSuccess("{\"statuses\":[]}", MediaType.APPLICATION_JSON));

        List<PrCheckRunState> checks = fixture.client.refreshPrCheckRuns("pat", "owner", "repo", "head", ImmutableList.of(), Optional.empty(), Optional.empty()).checkRuns();

        assertThat(checks).hasSize(101);
        assertThat(checks.getLast().name()).isEqualTo("check-101");
        fixture.verify();
    }

    @Test
    void conditionalPrChecksReuseStoredValuesOnNotModified()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/check-runs?per_page=100&page=1"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"checks-1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, "\"checks-1\""));
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/status"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"statuses-1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, "\"statuses-1\""));
        List<PrCheckRunState> existing = ImmutableList.of(
                new PrCheckRunState(1L, "build", "completed", "success", null, "check_run", null, null),
                new PrCheckRunState(2L, "license", "completed", "success", null, "status", null, null));

        GitHubActionsClient.PrCheckRunsFetch result = fixture.client.refreshPrCheckRuns(
                "pat",
                "owner",
                "repo",
                "head",
                existing,
                Optional.of("\"checks-1\""),
                Optional.of("\"statuses-1\""));

        assertThat(result.checkRuns()).containsExactlyElementsOf(existing);
        assertThat(result.checkRunsEtag()).contains("\"checks-1\"");
        assertThat(result.commitStatusesEtag()).contains("\"statuses-1\"");
        fixture.verify();
    }

    @Test
    void conditionalPrChecksUseTheStoredEtagAtExactlyOnePageOfCheckRuns()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/check-runs?per_page=100&page=1"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"checks-1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, "\"checks-1\""));
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/status"))
                .andRespond(withSuccess("{\"statuses\":[]}", MediaType.APPLICATION_JSON));

        GitHubActionsClient.PrCheckRunsFetch result = fixture.client.refreshPrCheckRuns(
                "pat", "owner", "repo", "head", storedCheckRuns(100),
                Optional.of("\"checks-1\""), Optional.empty());

        assertThat(result.checkRuns()).hasSize(100);
        fixture.verify();
    }

    @Test
    void conditionalPrChecksIgnoreTheStoredEtagBeyondOnePageOfCheckRuns()
    {
        Fixture fixture = fixture();
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/check-runs?per_page=100&page=1"))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
                .andRespond(withSuccess("""
                        {"total_count":1,"check_runs":[
                          {"id":1,"name":"build","status":"completed","conclusion":"success"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/commits/head/status"))
                .andRespond(withSuccess("{\"statuses\":[]}", MediaType.APPLICATION_JSON));

        GitHubActionsClient.PrCheckRunsFetch result = fixture.client.refreshPrCheckRuns(
                "pat", "owner", "repo", "head", storedCheckRuns(101),
                Optional.of("\"checks-1\""), Optional.empty());

        assertThat(result.checkRuns()).extracting(PrCheckRunState::name).containsExactly("build");
        fixture.verify();
    }

    private static List<PrCheckRunState> storedCheckRuns(int count)
    {
        return IntStream.rangeClosed(1, count)
                .mapToObj(id -> new PrCheckRunState((long) id, "check-" + id, "completed", "success", null, "check_run", null, null))
                .toList();
    }

    private static void expectSingleHead(
            Fixture fixture,
            String runStatus,
            String runConclusion,
            String jobStatus,
            String jobConclusion)
    {
        expectPullRequest(fixture);
        fixture.apiServer.expect(requestTo(runsUrl(1)))
                .andRespond(withSuccess("""
                        {"total_count":1,"workflow_runs":[{
                          "id":900,"workflow_id":80,"name":"Build","run_attempt":1,
                          "status":"%s","conclusion":%s,"head_sha":"%s",
                          "html_url":"https://github.com/owner/repo/actions/runs/900"
                        }]}
                        """.formatted(runStatus, jsonString(runConclusion), HEAD_SHA), MediaType.APPLICATION_JSON));
        fixture.apiServer.expect(requestTo(jobsUrl(900, 1)))
                .andRespond(withSuccess("""
                        {"total_count":1,"jobs":[{
                          "id":902,"name":"Compile","run_attempt":1,"status":"%s",
                          "conclusion":%s,"html_url":"https://github.com/job/902","steps":[]
                        }]}
                        """.formatted(jobStatus, jsonString(jobConclusion)), MediaType.APPLICATION_JSON));
    }

    private static void expectPullRequest(Fixture fixture)
    {
        expectPullRequest(fixture, "owner/repo");
    }

    private static void expectPullRequest(Fixture fixture, String headRepository)
    {
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/7"))
                .andRespond(withSuccess("""
                        {"state":"open","user":{"login":"author"},
                         "head":{"ref":"feature","sha":"%s","repo":{"full_name":"%s"}},
                         "base":{"ref":"main","sha":"%s","repo":{"full_name":"owner/repo"}},
                         "html_url":"https://github.com/owner/repo/pull/7"}
                        """.formatted(HEAD_SHA, headRepository, BASE_SHA), MediaType.APPLICATION_JSON));
    }

    private static void expectFailedWorkflow(Fixture fixture)
    {
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/actions/runs/900"))
                .andRespond(withSuccess("""
                        {"id":900,"workflow_id":80,"name":"Build","run_attempt":2,
                         "status":"completed","conclusion":"failure","head_sha":"%s",
                         "html_url":"https://github.com/owner/repo/actions/runs/900"}
                        """.formatted(HEAD_SHA), MediaType.APPLICATION_JSON));
    }

    private static ReadCiLogQuery logQuery(int maximumBytes)
    {
        CiJob job = failedJob();
        CiWorkflowRun workflow = new CiWorkflowRun(
                900,
                80,
                "Build",
                2,
                "completed",
                Optional.of("failure"),
                HEAD_SHA,
                "https://github.com/owner/repo/actions/runs/900",
                ImmutableList.of(job));
        return new ReadCiLogQuery(TaskId.random(), PULL_REQUEST, workflow, job, maximumBytes);
    }

    private static CiJob failedJob()
    {
        return new CiJob(
                902,
                900,
                "Compile",
                2,
                "completed",
                Optional.of("failure"),
                "https://github.com/job/902",
                ImmutableList.of(new CiStep(3, "Compile Java", "completed", Optional.of("failure"))));
    }

    private static RerunFailedJobsCommand rerunCommand(boolean authorized)
    {
        return new RerunFailedJobsCommand(PULL_REQUEST, 900, HEAD_SHA, 2, authorized);
    }

    private static String runsUrl(int page)
    {
        return "https://api.github.test/repos/owner/repo/actions/runs?head_sha="
                + HEAD_SHA + "&per_page=100&page=" + page;
    }

    private static String jobsUrl(long runId, int page)
    {
        return "https://api.github.test/repos/owner/repo/actions/runs/" + runId
                + "/jobs?filter=all&per_page=100&page=" + page;
    }

    private static String jsonString(String value)
    {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static Fixture fixture()
    {
        return fixture(PAT);
    }

    private static Fixture fixture(String token)
    {
        return fixture(token, false);
    }

    private static Fixture fixture(String token, boolean failBlobWrite)
    {
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer apiServer = MockRestServiceServer.bindTo(apiBuilder).build();
        RestClient.Builder logBuilder = RestClient.builder();
        MockRestServiceServer logServer = MockRestServiceServer.bindTo(logBuilder).build();
        AtomicReference<byte[]> stored = new AtomicReference<>();
        FileAgentResourceService files = new FileAgentResourceService(temporaryDirectory.resolve(UUID.randomUUID().toString()));
        AgentResourceService resources = mock(AgentResourceService.class);
        when(resources.require(any())).thenAnswer(call -> files.allocate(call.getArgument(0)));
        CiRawLogStore rawLogs = new CiRawLogStore(resources);
        GitHubActionsClient client = new GitHubActionsClient(
                apiBuilder.build(),
                logBuilder.build(),
                () -> token,
                (taskId, content, mediaType) -> {
                    if (failBlobWrite) {
                        throw new AgentStorageException("disk unavailable");
                    }
                    return storeBlob(stored, content, mediaType);
                }, rawLogs);
        return new Fixture(client, apiServer, logServer, stored, rawLogs);
    }

    private static BlobRef storeBlob(AtomicReference<byte[]> stored, ByteBuffer content, String mediaType)
    {
        byte[] bytes = new byte[content.remaining()];
        content.get(bytes);
        stored.set(bytes);
        return new BlobRef("blobs/ci-log", mediaType, bytes.length);
    }

    private record Fixture(
            GitHubActionsClient client,
            MockRestServiceServer apiServer,
            MockRestServiceServer logServer,
            AtomicReference<byte[]> stored,
            CiRawLogStore rawLogs)
    {
        private void verify()
        {
            apiServer.verify();
            logServer.verify();
        }
    }
}
