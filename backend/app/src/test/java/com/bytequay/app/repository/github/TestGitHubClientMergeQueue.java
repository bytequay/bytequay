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
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientMergeQueue
        extends AbstractGitHubClientTest
{
    private final GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(restClient, graphQlClient);

    @Test
    void detectsMergeQueueForBranch()
    {
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"mergeQueue":{"id":"MQ_1"}}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.hasMergeQueue("pat", RepoRef.of("owner", "repo"), "main")).isTrue();
    }

    @Test
    void enqueuesPullRequest()
    {
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().json("""
                        {"query":"mutation($id: ID!, $head: GitObjectID!) { enqueuePullRequest(input: { pullRequestId: $id, expectedHeadOid: $head }) { mergeQueueEntry { position state } } }",
                         "variables":{"id":"PR_1","head":"displayed-head"}}
                        """))
                .andRespond(withSuccess("""
                        {"data":{"enqueuePullRequest":{"mergeQueueEntry":{"position":2,"state":"QUEUED"}}}}
                        """, MediaType.APPLICATION_JSON));

        MergeResult result = client.enqueuePullRequest("pat", "PR_1", "displayed-head");

        assertThat(result.queued()).isTrue();
        assertThat(result.message()).contains("position 2");
    }

    @Test
    void dequeuesPullRequestUsingItsNodeId()
    {
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().json("""
                        {"query":"mutation($id: ID!) { dequeuePullRequest(input: { id: $id }) { clientMutationId } }","variables":{"id":"PR_1"}}
                        """))
                .andRespond(withSuccess("""
                        {"data":{"dequeuePullRequest":{"clientMutationId":null}}}
                        """, MediaType.APPLICATION_JSON));

        client.dequeuePullRequest("pat", "PR_1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":{}}", "{\"data\":{\"dequeuePullRequest\":null}}"})
    void rejectsIncompleteDequeueAcknowledgment(String response)
    {
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.dequeuePullRequest("pat", "PR_1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete GraphQL data");
    }

    @Test
    void reportsDequeueRejectionEvenWithPartialData()
    {
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"dequeuePullRequest":{"clientMutationId":null}},"errors":[{"message":"Write access is required"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.dequeuePullRequest("pat", "PR_1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Write access is required");
    }
}
