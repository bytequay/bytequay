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

import com.bytequay.app.domain.PullRequestRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientMergeQueue
{
    @Test
    void enqueueSendsExpectedHeadOid()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query", containsString("expectedHeadOid")))
                .andExpect(jsonPath("$.variables.id").value("PR_node"))
                .andExpect(jsonPath("$.variables.head").value("head-sha"))
                .andRespond(withSuccess("""
                        {"data":{"enqueuePullRequest":{"mergeQueueEntry":{
                          "id":"MQ_entry","position":2,"state":"AWAITING_CHECKS"
                        }}}}
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.client.enqueuePullRequest(
                "pat", "PR_node", "head-sha");

        assertThat(result.queued()).isTrue();
        assertThat(result.message()).contains("position 2");
        fixture.server.verify();
    }

    @Test
    void graphQlErrorsCannotBecomeAcceptedQueueEvidence()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":null,"errors":[{
                          "type":"UNPROCESSABLE",
                          "message":"Pull request head oid has changed"
                        }]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.enqueuePullRequest(
                "pat", "PR_node", "stale-head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("head oid has changed");
        fixture.server.verify();
    }

    @Test
    void graphQlErrorsCannotProveThatDirectMergeIsSafe()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":null,"errors":[{
                          "type":"RATE_LIMITED",
                          "message":"API rate limit exceeded"
                        }]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.fetchMergeQueueInfo(
                "pat", PullRequestRef.of("owner", "repo", 17)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RATE_LIMITED");
        fixture.server.verify();
    }

    private static Fixture fixture()
    {
        RestClient.Builder graphBuilder = RestClient.builder()
                .baseUrl("https://api.github.test/graphql");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(graphBuilder).build();
        GitHubClient client = new GitHubClient(
                RestClient.builder().baseUrl("https://api.github.test").build(),
                graphBuilder.build());
        return new Fixture(client, server);
    }

    private record Fixture(GitHubClient client, MockRestServiceServer server) {}
}
