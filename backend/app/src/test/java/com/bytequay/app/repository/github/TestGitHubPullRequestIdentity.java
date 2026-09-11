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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubPullRequestIdentity
{
    private static final RepoRef BASE = RepoRef.of("acme", "project");
    private static final RepoRef HEAD = RepoRef.of("alice", "project");
    private static final String BRANCH = "bytequay/task/test";
    private static final String SHA = "a".repeat(40);
    private static final String FIRST_PAGE = "https://api.github.test/repos/acme/project/pulls?state=all&head=alice%3Abytequay%2Ftask%2Ftest&per_page=100&page=1";

    @Test
    void retainsOpenClosedMergedAndWrongBaseFactsWithExactForkIdentity()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(FIRST_PAGE)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andRespond(withSuccess("[" + row(1, "open", "null", "main") + "," + row(2, "closed", "null", "release") + ","
                        + row(3, "closed", "\"2026-09-01T00:00:00Z\"", "main") + "]", MediaType.APPLICATION_JSON));

        var identities = fixture.client().fetchPullRequestIdentities("token", BASE, HEAD, BRANCH);

        assertThat(identities).extracting(GitHubPullRequestIdentity::state)
                .containsExactly(GitHubPullRequestIdentity.State.OPEN, GitHubPullRequestIdentity.State.CLOSED, GitHubPullRequestIdentity.State.MERGED);
        assertThat(identities).allSatisfy(identity -> {
            assertThat(identity.headRepository()).isEqualTo(HEAD);
            assertThat(identity.baseRepository()).isEqualTo(BASE);
            assertThat(identity.headSha()).isEqualTo(SHA);
            assertThat(identity.baseSha()).isEqualTo(SHA);
            assertThat(identity.draft()).isTrue();
        });
        assertThat(identities.get(1).baseBranch()).isEqualTo("release");
        fixture.server().verify();
    }

    @Test
    void readsEveryPageInsteadOfChoosingTheNewestOpenMatch()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(FIRST_PAGE)).andRespond(withSuccess(
                IntStream.rangeClosed(1, 100).mapToObj(number -> row(number, "open", "null", "main")).collect(joining(",", "[", "]")), MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo(FIRST_PAGE.replace("&page=1", "&page=2")))
                .andRespond(withSuccess("[" + row(101, "closed", "null", "main") + "]", MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchPullRequestIdentities("token", BASE, HEAD, BRANCH)).hasSize(101);
        fixture.server().verify();
    }

    @Test
    void rejectsDifferentHeadRepositoryInsteadOfTreatingItAsAbsent()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(FIRST_PAGE)).andRespond(withSuccess(
                "[" + row(1, "open", "null", "main").replace("alice/project", "alice/another") + "]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().fetchPullRequestIdentities("token", BASE, HEAD, BRANCH))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("another pull-request head");
        fixture.server().verify();
    }

    @Test
    void rejectsMissingIdentityFacts()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(FIRST_PAGE)).andRespond(withSuccess(
                "[" + row(1, "open", "null", "main").replace("\"draft\":true,", "") + "]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().fetchPullRequestIdentities("token", BASE, HEAD, BRANCH))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("could not be verified");
        fixture.server().verify();
    }

    @Test
    void rejectsMissingResponseInsteadOfTreatingItAsNoPullRequest()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(FIRST_PAGE)).andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().fetchPullRequestIdentities("token", BASE, HEAD, BRANCH))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no pull-request identity response");
        fixture.server().verify();
    }

    private static String row(int number, String state, String mergedAt, String baseBranch)
    {
        return """
                {"number":%d,"html_url":"https://github.com/acme/project/pull/%d","state":"%s","merged_at":%s,"draft":true,
                 "user":{"login":"alice"},"head":{"ref":"%s","sha":"%s","repo":{"full_name":"alice/project"}},
                 "base":{"ref":"%s","sha":"%s","repo":{"full_name":"acme/project"}}}
                """.formatted(number, number, state, mergedAt, BRANCH, SHA, baseBranch, SHA);
    }

    private static Fixture fixture()
    {
        RestClient.Builder api = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(api).build();
        return new Fixture(new GitHubPullRequestReadClient(api.build(), RestClient.create("https://api.github.test/graphql")), server);
    }

    private record Fixture(GitHubPullRequestReadClient client, MockRestServiceServer server) {}
}
