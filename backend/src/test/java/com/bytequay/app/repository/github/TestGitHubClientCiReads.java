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

import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientCiReads
{
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

        List<PullRequestRepository.CheckRunAnnotation> result =
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

    private static GitHubClient client(RestClient.Builder restBuilder)
    {
        return new GitHubClient(
                restBuilder.build(), RestClient.builder().baseUrl("https://graphql.test").build());
    }
}
