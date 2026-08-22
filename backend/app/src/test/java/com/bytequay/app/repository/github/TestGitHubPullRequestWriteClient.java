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

import com.bytequay.core.domain.RepoRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubPullRequestWriteClient
{
    @Test
    void createsPullRequestThroughExistingWriteClient()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(builder.build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/pulls"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("""
                        {
                          "title":"PR title",
                          "body":"PR body",
                          "head":"contributor:bump-branch",
                          "base":"main"
                        }
                        """))
                .andRespond(withSuccess("""
                        {"id":900,"number":12,"html_url":"https://github.com/upstream/project/pull/12"}
                        """, MediaType.APPLICATION_JSON));

        CreatedPullRequest created = client.createPullRequest(
                "pat",
                RepoRef.of("upstream", "project"),
                "contributor:bump-branch",
                "main",
                "PR title",
                "PR body");

        assertThat(created).isEqualTo(new CreatedPullRequest(
                900, 12, "https://github.com/upstream/project/pull/12"));
        server.verify();
    }
}
