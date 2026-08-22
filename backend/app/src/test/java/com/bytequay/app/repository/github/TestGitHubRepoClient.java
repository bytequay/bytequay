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
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubRepoClient
{
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repo");

    @Test
    void fetchesContributorLabelAndTopicProjections()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/contributors?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":1,"login":"alice","avatar_url":"avatar","type":"User"}]
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/labels?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":2,"name":"bug","color":"ff0000"}]
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo"))
                .andRespond(withSuccess("""
                        {"topics":["java","github"]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchContributors("pat", REPOSITORY))
                .extracting(RepositoryContributor::id)
                .containsExactly(1L);
        assertThat(fixture.client().fetchLabels("pat", REPOSITORY))
                .extracting(RepositoryLabel::id)
                .containsExactly(2L);
        assertThat(fixture.client().fetchTopics("pat", REPOSITORY)).containsExactly("java", "github");
        fixture.server().verify();
    }

    @Test
    void fetchesOpenIssuesAndExcludesPullRequestsFromGitHubIssuesEndpoint()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/issues?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":10,"number":7,"title":"Issue","body":"Details",
                          "user":{"login":"alice","avatar_url":"avatar"},
                          "html_url":"https://github.test/owner/repo/issues/7",
                          "labels":[{"name":"bug"}],"assignees":[{"login":"bob"}],
                          "comments":2,"created_at":"2026-08-20T00:00:00Z",
                          "updated_at":"2026-08-21T00:00:00Z"},
                         {"id":11,"number":8,"title":"PR","pull_request":{"url":"pull"},
                          "created_at":"2026-08-20T00:00:00Z","updated_at":"2026-08-21T00:00:00Z"}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchOpenIssues("pat", REPOSITORY))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.id()).isEqualTo(10);
                    assertThat(issue.labels()).containsExactly("bug");
                    assertThat(issue.assignees()).containsExactly("bob");
                });
        fixture.server().verify();
    }

    @Test
    void fetchesCommitAvatarsBySha()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits?sha=main&per_page=2&page=1"))
                .andRespond(withSuccess("""
                        [{"sha":"abc","author":{"avatar_url":"https://avatars/alice"}},
                         {"sha":"def","author":null}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchCommitAvatars("pat", REPOSITORY, "main", 2))
                .containsExactlyEntriesOf(ImmutableMap.of("abc", "https://avatars/alice"));
        fixture.server().verify();
    }

    private static Fixture fixture()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new GitHubRepoClient(builder.build()), server);
    }

    private record Fixture(GitHubRepoClient client, MockRestServiceServer server)
    {
        private Fixture
        {
            java.util.Objects.requireNonNull(client, "client is null");
            java.util.Objects.requireNonNull(server, "server is null");
        }
    }
}
