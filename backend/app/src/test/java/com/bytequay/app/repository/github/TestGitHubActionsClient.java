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

import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.TaskId;
import com.bytequay.app.ci.BoundedCiLog;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiJob;
import com.bytequay.app.ci.CiRerunResult;
import com.bytequay.app.ci.CiStep;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.ci.ReadCiLogQuery;
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PullRequestRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.ci.CiRerunResult.Outcome.ACCEPTED;
import static com.bytequay.app.ci.CiRerunResult.Outcome.OBSOLETE;
import static com.bytequay.app.ci.CiRerunResult.Outcome.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubActionsClient
{
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of("owner", "repo", 7);
    private static final String PAT = "github_pat_secret_123456789";
    private static final String HEAD_SHA = "1111111111111111111111111111111111111111";
    private static final String BASE_SHA = "2222222222222222222222222222222222222222";

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

        CiHeadSnapshot snapshot = fixture.client.inspect(new InspectCiHeadQuery(PULL_REQUEST));

        assertThat(snapshot.open()).isTrue();
        assertThat(snapshot.author()).isEqualTo("author");
        assertThat(snapshot.sameRepository()).isTrue();
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
    void identifiesAllPendingHead()
    {
        Fixture fixture = fixture();
        expectSingleHead(fixture, "in_progress", null, "queued", null);

        CiHeadSnapshot snapshot = fixture.client.inspect(new InspectCiHeadQuery(PULL_REQUEST));

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

        CiHeadSnapshot snapshot = fixture.client.inspect(new InspectCiHeadQuery(PULL_REQUEST));

        assertThat(snapshot.actionableFailures()).isEmpty();
        assertThat(snapshot.pending()).isFalse();
        assertThat(snapshot.green()).isTrue();
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
        }).andRespond(withSuccess(rawLog, MediaType.TEXT_PLAIN));

        BoundedCiLog log = fixture.client.readLog(logQuery(256));
        String stored = new String(fixture.stored.get(), StandardCharsets.UTF_8);

        assertThat(log.blob()).isPresent();
        assertThat(log.byteCount()).isEqualTo(fixture.stored.get().length);
        assertThat(log.truncated()).isTrue();
        assertThat(log.terminalErrorLines()).containsExactly("error: compilation failed with [REDACTED]");
        assertThat(stored).doesNotContain(PAT, "ghp_anotherSecret123");
        assertThat(stored).contains("Authorization: Bearer [REDACTED]");
        fixture.verify();
    }

    @Test
    void reportsMissingOrExpiredLogWithoutWritingBlob()
    {
        for (HttpStatus status : List.of(HttpStatus.NOT_FOUND, HttpStatus.GONE)) {
            Fixture fixture = fixture();
            fixture.apiServer.expect(requestTo(
                            "https://api.github.test/repos/owner/repo/actions/jobs/902/logs"))
                    .andRespond(withStatus(status));

            BoundedCiLog log = fixture.client.readLog(logQuery(1024));

            assertThat(log.blob()).isEmpty();
            assertThat(log.unavailableReason()).contains("GitHub Actions log is missing or expired");
            assertThat(fixture.stored.get()).isNull();
            fixture.verify();
        }
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
        fixture.apiServer.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/actions/runs/900/rerun-failed-jobs"))
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
        fixture.apiServer.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/actions/runs/900/rerun-failed-jobs"))
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
        fixture.apiServer.expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits/head/check-runs?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        {"total_count":1,"check_runs":[{
                          "id":99,"name":"build","status":"completed","conclusion":"failure",
                          "html_url":"https://github.com/owner/repo/actions/runs/7/job/9",
                          "output":{"title":"Tests failed","summary":"One failure"}
                        }]}
                        """, MediaType.APPLICATION_JSON));

        PrCheckRunState check = fixture.client.fetchPrCheckRuns("pat", "owner", "repo", "head").getFirst();

        assertThat(check.githubId()).isEqualTo(99L);
        assertThat(check.name()).isEqualTo("build");
        assertThat(check.conclusion()).isEqualTo("failure");
        fixture.verify();
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
        fixture.apiServer.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/7"))
                .andRespond(withSuccess("""
                        {"state":"open","user":{"login":"author"},
                         "head":{"ref":"feature","sha":"%s","repo":{"full_name":"owner/repo"}},
                         "base":{"ref":"main","sha":"%s","repo":{"full_name":"owner/repo"}},
                         "html_url":"https://github.com/owner/repo/pull/7"}
                        """.formatted(HEAD_SHA, BASE_SHA), MediaType.APPLICATION_JSON));
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
                List.of(job));
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
                List.of(new CiStep(3, "Compile Java", "completed", Optional.of("failure"))));
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
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer apiServer = MockRestServiceServer.bindTo(apiBuilder).build();
        RestClient.Builder logBuilder = RestClient.builder();
        MockRestServiceServer logServer = MockRestServiceServer.bindTo(logBuilder).build();
        AtomicReference<byte[]> stored = new AtomicReference<>();
        GitHubActionsClient client = new GitHubActionsClient(
                apiBuilder.build(),
                logBuilder.build(),
                () -> PAT,
                (taskId, content, mediaType) -> storeBlob(stored, content, mediaType));
        return new Fixture(client, apiServer, logServer, stored);
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
            AtomicReference<byte[]> stored)
    {
        private void verify()
        {
            apiServer.verify();
            logServer.verify();
        }
    }
}
