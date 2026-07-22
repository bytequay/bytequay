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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

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

    private static GitHubClient client(RestClient.Builder restBuilder)
    {
        return new GitHubClient(
                restBuilder.build(), RestClient.builder().baseUrl("https://graphql.test").build());
    }
}
