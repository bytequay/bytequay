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
import com.bytequay.core.domain.PullRequestRef;
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
        expectMergeQueue(true);
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().json("""
                        {"query":"mutation($id: ID!, $head: GitObjectID!) { enablePullRequestAutoMerge(input: { pullRequestId: $id, expectedHeadOid: $head }) { pullRequest { mergeQueueEntry { position state } autoMergeRequest { enabledAt } } } }",
                         "variables":{"id":"PR_1","head":"displayed-head"}}
                        """))
                .andRespond(withSuccess("""
                        {"data":{"enablePullRequestAutoMerge":{"pullRequest":{"mergeQueueEntry":{"position":2,"state":"QUEUED"},"autoMergeRequest":null}}}}
                        """, MediaType.APPLICATION_JSON));

        MergeResult result = client.mergeWhenReady("pat", PullRequestRef.of("owner", "repo", 7), "displayed-head");

        assertThat(result.queued()).isTrue();
        assertThat(result.merged()).isFalse();
        assertThat(result.message()).contains("position 2");
    }

    @Test
    void schedulesQueueEntryWhileRequirementsArePending()
    {
        expectMergeQueue(true);
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().json("""
                        {"query":"mutation($id: ID!, $head: GitObjectID!) { enablePullRequestAutoMerge(input: { pullRequestId: $id, expectedHeadOid: $head }) { pullRequest { mergeQueueEntry { position state } autoMergeRequest { enabledAt } } } }",
                         "variables":{"id":"PR_1","head":"displayed-head"}}
                        """))
                .andRespond(withSuccess("""
                        {"data":{"enablePullRequestAutoMerge":{"pullRequest":{"mergeQueueEntry":null,"autoMergeRequest":{"enabledAt":"2026-01-01T00:00:00Z"}}}}}
                        """, MediaType.APPLICATION_JSON));

        MergeResult result = client.mergeWhenReady("pat", PullRequestRef.of("owner", "repo", 7), "displayed-head");

        assertThat(result.queued()).isFalse();
        assertThat(result.merged()).isFalse();
        assertThat(result.message()).isEqualTo("Will join the merge queue when all requirements pass");
    }

    @Test
    void doesNotEnableAutoMergeWhenTheBranchNoLongerHasAQueue()
    {
        expectMergeQueue(false);

        assertThatThrownBy(() -> client.mergeWhenReady("pat", PullRequestRef.of("owner", "repo", 7), "displayed-head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no longer has a merge queue");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":\"PR_1\"}", "{\"isMergeQueueEnabled\":true}"})
    void doesNotEnableAutoMergeWithoutCompleteQueueMetadata(String pullRequest)
    {
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":%s}}}
                        """.formatted(pullRequest), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.mergeWhenReady("pat", PullRequestRef.of("owner", "repo", 7), "displayed-head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("incomplete GraphQL data");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"data\":{}}",
            "{\"data\":{\"enablePullRequestAutoMerge\":null}}",
            "{\"data\":{\"enablePullRequestAutoMerge\":{\"pullRequest\":null}}}",
            "{\"data\":{\"enablePullRequestAutoMerge\":{\"pullRequest\":{}}}}",
    })
    void rejectsIncompleteMergeWhenReadyAcknowledgment(String response)
    {
        expectMergeQueue(true);
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.mergeWhenReady("pat", PullRequestRef.of("owner", "repo", 7), "displayed-head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no queue entry or auto-merge request");
    }

    @Test
    void reportsMergeWhenReadyRejectionEvenWithPartialData()
    {
        expectMergeQueue(true);
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"enablePullRequestAutoMerge":{"pullRequest":{"mergeQueueEntry":{"position":2,"state":"QUEUED"}}}},
                         "errors":[{"message":"Head changed"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.mergeWhenReady("pat", PullRequestRef.of("owner", "repo", 7), "displayed-head"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Head changed");
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

    private void expectMergeQueue(boolean enabled)
    {
        graphServer.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().json("""
                        {"query":"query($owner: String!, $name: String!, $number: Int!) { repository(owner: $owner, name: $name) { pullRequest(number: $number) { id isMergeQueueEnabled } } }",
                         "variables":{"owner":"owner","name":"repo","number":7}}
                        """))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{"id":"PR_1","isMergeQueueEnabled":%s}}}}
                        """.formatted(enabled), MediaType.APPLICATION_JSON));
    }
}
