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

import com.bytequay.core.domain.PullRequestRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubIssueLinks
{
    private static final PullRequestRef ISSUE = PullRequestRef.of("trinodb", "trino", 31252);

    private final RestClient.Builder graph = RestClient.builder().baseUrl("https://api.github.test/graphql");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(graph).build();
    private final GitHubPullRequestReadClient client = new GitHubPullRequestReadClient(RestClient.builder().baseUrl("https://api.github.test").build(), graph.build());

    @Test
    void readsTheIssueStateAndThePullRequestsLinkedToCloseIt()
    {
        server.expect(requestTo("https://api.github.test/graphql")).andExpect(content().string(containsString("closedByPullRequestsReferences")))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"issue":{"state":"OPEN","closedByPullRequestsReferences":{"nodes":[
                          {"number":31260,"state":"OPEN"},{"number":31100,"state":"CLOSED"}]}}}}}
                        """, MediaType.APPLICATION_JSON));
        GitHubPullRequestReadClient.IssueLinks links = client.fetchIssueLinks("token", ISSUE);
        assertThat(links.open()).isTrue();
        assertThat(links.pulls()).containsExactly(new GitHubPullRequestReadClient.LinkedPull(31260, "OPEN"), new GitHubPullRequestReadClient.LinkedPull(31100, "CLOSED"));
        server.verify();
    }

    @Test
    void aClosedIssueReadsClosedAndAnErrorFailsTheRead()
    {
        server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("{\"data\":{\"repository\":{\"issue\":{\"state\":\"CLOSED\",\"closedByPullRequestsReferences\":{\"nodes\":[]}}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("{\"data\":{\"repository\":{\"issue\":null}},\"errors\":[{\"type\":\"NOT_FOUND\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.fetchIssueLinks("token", ISSUE).open()).isFalse();
        assertThatThrownBy(() -> client.fetchIssueLinks("token", ISSUE)).isInstanceOf(ResponseStatusException.class);
        server.verify();
    }
}
