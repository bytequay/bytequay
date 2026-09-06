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

import com.bytequay.core.domain.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubAccountClientForks
{
    private static final UserRepo UPSTREAM = new UserRepo(
            40, "canonical", "project", "canonical/project", Optional.empty(), false, "public", false,
            "main", "https://github.com/canonical/project.git", Optional.empty());

    private MockRestServiceServer server;
    private GitHubAccountClient client;

    @BeforeEach
    void setUp()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubAccountClient(builder.build(), RestClient.create());
    }

    @Test
    void findsRenamedNestedForkBySourceIdentity()
    {
        expectOwnedRepos(1, """
                [{"id": 41, "fork": true}, {"id": 42, "fork": true}]
                """);
        server.expect(requestTo("https://api.github.test/repositories/41"))
                .andRespond(withSuccess(forkJson(41, "project", 900), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repositories/42"))
                .andRespond(withSuccess(forkJson(42, "renamed-project", 40), MediaType.APPLICATION_JSON));

        UserRepo fork = client.findOwnedFork("pat", UPSTREAM, "CONTRIBUTOR").orElseThrow();

        assertThat(fork.id()).isEqualTo(42);
        assertThat(fork.fullName()).isEqualTo("contributor/renamed-project");
        assertThat(fork.upstream()).hasValueSatisfying(source -> assertThat(source.id()).isEqualTo(40));
        server.verify();
    }

    @Test
    void searchesBeyondTheRepositoryPickerPageLimit()
    {
        for (int page = 1; page <= 10; page++) {
            String repositories = IntStream.range(page * 100, (page + 1) * 100)
                    .mapToObj(id -> "{\"id\":" + id + ",\"fork\":false}")
                    .collect(joining(",", "[", "]"));
            expectOwnedRepos(page, repositories);
        }
        expectOwnedRepos(11, "[{\"id\":42,\"fork\":true}]");
        server.expect(requestTo("https://api.github.test/repositories/42"))
                .andRespond(withSuccess(forkJson(42, "renamed-project", 40), MediaType.APPLICATION_JSON));

        assertThat(client.findOwnedFork("pat", UPSTREAM, "contributor")).hasValueSatisfying(fork -> assertThat(fork.id()).isEqualTo(42));
        server.verify();
    }

    @Test
    void acceptsParentIdentityWhenSourceIsNotPresent()
    {
        expectOwnedRepos(1, "[{\"id\":42,\"fork\":true}]");
        server.expect(requestTo("https://api.github.test/repositories/42"))
                .andRespond(withSuccess("""
                        {
                          "id": 42, "name": "project", "owner": {"login": "contributor"},
                          "fork": true, "permissions": {"push": false}, "parent": {"id": 40}
                        }
                        """, MediaType.APPLICATION_JSON));

        UserRepo fork = client.findOwnedFork("pat", UPSTREAM, "contributor").orElseThrow();

        assertThat(fork.id()).isEqualTo(42);
        assertThat(fork.viewerCanPush()).isFalse();
        server.verify();
    }

    @Test
    void reportsAbsenceOnlyAfterACompleteListing()
    {
        expectOwnedRepos(1, "[]");

        assertThat(client.findOwnedFork("pat", UPSTREAM, "contributor")).isEmpty();
        server.verify();
    }

    @Test
    void propagatesFailureToInspectAnExistingFork()
    {
        expectOwnedRepos(1, "[{\"id\":42,\"fork\":true}]");
        server.expect(requestTo("https://api.github.test/repositories/42"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Fork access denied\"}"));

        assertThatThrownBy(() -> client.findOwnedFork("pat", UPSTREAM, "contributor"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Fork access denied");
        server.verify();
    }

    @Test
    void rejectsMissingListingAndForkTopology()
    {
        server.expect(requestTo(ownedReposUrl(1))).andRespond(withSuccess());

        assertThatThrownBy(() -> client.findOwnedFork("pat", UPSTREAM, "contributor"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Empty response");
        server.verify();
        server.reset();

        expectOwnedRepos(1, "[{\"id\":42,\"fork\":true}]");
        server.expect(requestTo("https://api.github.test/repositories/42"))
                .andRespond(withSuccess("{\"id\":42,\"fork\":true}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findOwnedFork("pat", UPSTREAM, "contributor"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("did not identify the source");
        server.verify();
    }

    @Test
    void createsForkForAuthenticatedUserAndMapsAcceptedMetadata()
    {
        server.expect(requestTo("https://api.github.test/repos/canonical/project/forks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("{\"default_branch_only\":false}"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(forkJson(42, "project", 40)));

        UserRepo fork = client.createFork("pat", UPSTREAM);

        assertThat(fork.id()).isEqualTo(42);
        assertThat(fork.owner()).isEqualTo("contributor");
        assertThat(fork.cloneUrl()).isEqualTo("https://github.com/contributor/project.git");
        assertThat(fork.viewerCanPush()).isTrue();
        server.verify();
    }

    @Test
    void rejectsForkCreationInAnUnrelatedNetwork()
    {
        server.expect(requestTo("https://api.github.test/repos/canonical/project/forks"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(forkJson(42, "project", 900)));

        assertThatThrownBy(() -> client.createFork("pat", UPSTREAM))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("did not return a fork of the selected upstream");
        server.verify();
    }

    private void expectOwnedRepos(int page, String body)
    {
        server.expect(requestTo(ownedReposUrl(page)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String ownedReposUrl(int page)
    {
        return "https://api.github.test/user/repos?affiliation=owner&sort=full_name&direction=asc&per_page=100&page=" + page;
    }

    private static String forkJson(long id, String name, long sourceId)
    {
        return """
                {
                  "id": %s, "name": "%s", "full_name": "contributor/%s",
                  "owner": {"login": "contributor"}, "fork": true, "visibility": "public",
                  "permissions": {"push": true}, "default_branch": "main",
                  "clone_url": "https://github.com/contributor/%s.git",
                  "parent": {"id": 33, "name": "project", "owner": {"login": "middle"}},
                  "source": {
                    "id": %s, "name": "project", "owner": {"login": "canonical"},
                    "default_branch": "main", "clone_url": "https://github.com/canonical/project.git"
                  }
                }
                """.formatted(id, name, name, name, sourceId);
    }
}
