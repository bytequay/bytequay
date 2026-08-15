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

import com.bytequay.app.domain.MergeResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientMergeQueue
{
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

    private static Fixture fixture()
    {
        RestClient.Builder restBuilder = RestClient.builder().baseUrl("https://api.github.test");
        RestClient.Builder graphqlBuilder = RestClient.builder().baseUrl("https://api.github.test/graphql");
        MockRestServiceServer graphql = MockRestServiceServer.bindTo(graphqlBuilder).build();
        return new Fixture(new GitHubMergeClient(restBuilder.build(), graphqlBuilder.build()), graphql);
    }

    private record Fixture(GitHubMergeClient client, MockRestServiceServer graphql) {}
}
