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
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.UserRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubAccountClientRepositoryRouting
        extends AbstractGitHubClientTest
{
    private final GitHubAccountClient client = new GitHubAccountClient(restClient, graphQlClient);

    @ParameterizedTest
    @CsvSource(value = {
            "true|{\"permissions\":{\"push\":true}}",
            "false|{\"permissions\":{\"push\":false}}",
            "false|{\"permissions\":{\"push\":null}}",
            "false|{\"permissions\":{}}",
            "false|{\"permissions\":null}",
            "false|{}",
    }, delimiter = '|')
    void repositoryPermissionsRequireExplicitPushAccess(boolean expected, String response)
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThat(client.fetchViewerCanWrite("pat", RepoRef.of("owner", "repo"))).isEqualTo(expected);
    }

    @Test
    void fetchesAnotherUsersPublicProfile()
    {
        server.expect(requestTo("https://api.github.test/users/octocat"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "login": "octocat",
                          "name": "The Octocat",
                          "avatar_url": "https://avatars.test/octocat",
                          "html_url": "https://github.com/octocat",
                          "public_repos": 8,
                          "followers": 42,
                          "following": 3,
                          "bio": "GitHub mascot",
                          "location": "San Francisco",
                          "company": "GitHub"
                        }
                        """, MediaType.APPLICATION_JSON));

        UserProfile profile = client.fetchUserProfile("pat", "octocat");

        assertThat(profile.login()).isEqualTo("octocat");
        assertThat(profile.name()).isEqualTo("The Octocat");
        assertThat(profile.followers()).isEqualTo(42);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repositoryReadUsesCanonicalSourceAndPermissionSummary(boolean byId)
    {
        server.expect(requestTo("https://api.github.test" + (byId ? "/repositories/42" : "/repos/contributor/project")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "id": 42,
                          "name": "project",
                          "full_name": "contributor/project",
                          "owner": {"login": "contributor"},
                          "description": "nested fork",
                          "fork": true,
                          "visibility": "private",
                          "permissions": {"push": true},
                          "default_branch": "fork-default",
                          "clone_url": "https://github.com/contributor/project.git",
                          "parent": {
                            "id": 41,
                            "name": "project",
                            "owner": {"login": "middle"},
                            "default_branch": "middle-default",
                            "clone_url": "https://github.com/middle/project.git"
                          },
                          "source": {
                            "id": 40,
                            "name": "project",
                            "owner": {"login": "canonical"},
                            "default_branch": "main",
                            "clone_url": "https://github.com/canonical/project.git"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        UserRepo repository = (byId ? client.fetchRepository("pat", 42) : client.fetchRepository("pat", "contributor", "project")).orElseThrow();

        assertThat(repository.visibility()).isEqualTo("private");
        assertThat(repository.viewerCanPush()).isTrue();
        assertThat(repository.upstream()).contains(new UserRepo.Upstream(40, "canonical", "project", "main", "https://github.com/canonical/project.git"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repositoryReadsPreserveMissingAndFailedResponses(boolean byId)
    {
        String url = "https://api.github.test" + (byId ? "/repositories/42" : "/repos/contributor/project");
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        Supplier<Optional<UserRepo>> fetch = () -> byId ? client.fetchRepository("pat", 42) : client.fetchRepository("pat", "contributor", "project");

        assertThat(fetch.get()).isEmpty();
        assertThatThrownBy(fetch::get).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(fetch::get).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
