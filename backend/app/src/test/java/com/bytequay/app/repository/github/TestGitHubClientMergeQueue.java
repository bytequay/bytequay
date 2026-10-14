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

import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.RepoRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientMergeQueue
{
    @Test
    void detectsMergeQueueForBranch()
    {
        Fixture fixture = fixture();
        fixture.graphql().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"mergeQueue":{"id":"MQ_1"}}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().hasMergeQueue("pat", RepoRef.of("owner", "repo"), "main")).isTrue();
    }

    @Test
    void enqueuesPullRequest()
    {
        Fixture fixture = fixture();
        fixture.graphql().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"enqueuePullRequest":{"mergeQueueEntry":{"position":2,"state":"QUEUED"}}}}
                        """, MediaType.APPLICATION_JSON));

        MergeResult result = fixture.client().enqueuePullRequest("pat", "PR_1");

        assertThat(result.queued()).isTrue();
        assertThat(result.message()).contains("position 2");
    }

    @Test
    void dequeuesPullRequestUsingItsNodeId()
    {
        Fixture fixture = fixture();
        fixture.graphql().expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().json("""
                        {"query":"mutation($id: ID!) { dequeuePullRequest(input: { id: $id }) { clientMutationId } }","variables":{"id":"PR_1"}}
                        """))
                .andRespond(withSuccess("""
                        {"data":{"dequeuePullRequest":{"clientMutationId":null}}}
                        """, MediaType.APPLICATION_JSON));

        fixture.client().dequeuePullRequest("pat", "PR_1");

        fixture.graphql().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":{}}", "{\"data\":{\"dequeuePullRequest\":null}}"})
    void rejectsIncompleteDequeueAcknowledgment(String response)
    {
        Fixture fixture = fixture();
        fixture.graphql().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().dequeuePullRequest("pat", "PR_1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete GraphQL data");
    }

    @Test
    void reportsDequeueRejectionEvenWithPartialData()
    {
        Fixture fixture = fixture();
        fixture.graphql().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"dequeuePullRequest":{"clientMutationId":null}},"errors":[{"message":"Write access is required"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().dequeuePullRequest("pat", "PR_1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Write access is required");
    }

    private static Fixture fixture()
    {
        RestClient.Builder restBuilder = RestClient.builder().baseUrl("https://api.github.test");
        RestClient.Builder graphqlBuilder = RestClient.builder().baseUrl("https://api.github.test/graphql");
        MockRestServiceServer graphql = MockRestServiceServer.bindTo(graphqlBuilder).build();
        return new Fixture(new GitHubPullRequestWriteClient(restBuilder.build(), graphqlBuilder.build()), graphql);
    }

    private record Fixture(GitHubPullRequestWriteClient client, MockRestServiceServer graphql) {}
}
