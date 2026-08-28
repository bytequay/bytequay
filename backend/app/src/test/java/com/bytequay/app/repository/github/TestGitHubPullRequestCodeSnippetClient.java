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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubPullRequestCodeSnippetClient
{
    private static final String COMMIT = "4bfd15e6e1cd4b4a5c838c6065d22151d7d044aa";

    @Test
    void encodesRepositoryPathsAndReturnsOnlyRequestedLines()
    {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(
                        "https://api.github.test/repos/trinodb/trino/contents/src/%7Bgenerated%7D.ts?ref=" + COMMIT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer pat"))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json"))
                .andRespond(withSuccess("first\r\nsecond\nthird\nfourth\n", MediaType.TEXT_PLAIN));

        assertThat(fixture.client.fetchRepositoryFileLines(
                "pat", RepoRef.of("trinodb", "trino"), "src/{generated}.ts", COMMIT, 2, 3))
                .hasValue(java.util.List.of("second", "third"));
        fixture.server.verify();
    }

    @Test
    void returnsCompleteEarlyLinesWithoutReadingAnOversizedRemainder()
    {
        Fixture fixture = fixture();
        expectSource(fixture, "first\n" + "x".repeat(1024 * 1024 + 1));

        assertThat(fixture.client.fetchRepositoryFileLines(
                "pat", RepoRef.of("trinodb", "trino"), "src/Main.java", COMMIT, 1, 1))
                .hasValue(java.util.List.of("first"));
        fixture.server.verify();
    }

    @Test
    void rejectsARequestedLineBeyondTheBoundedRead()
    {
        Fixture fixture = fixture();
        expectSource(fixture, "x".repeat(1024 * 1024 + 1));

        assertThatThrownBy(() -> fixture.client.fetchRepositoryFileLines(
                "pat", RepoRef.of("trinodb", "trino"), "src/Main.java", COMMIT, 1, 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("snippet read limit");
        fixture.server.verify();
    }

    private static void expectSource(Fixture fixture, String source)
    {
        fixture.server.expect(requestTo(
                        "https://api.github.test/repos/trinodb/trino/contents/src/Main.java?ref=" + COMMIT))
                .andRespond(withSuccess(source, MediaType.TEXT_PLAIN));
    }

    private static Fixture fixture()
    {
        RestClient.Builder api = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(api).build();
        GitHubPullRequestReadClient client = new GitHubPullRequestReadClient(
                api.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        return new Fixture(client, server);
    }

    private record Fixture(GitHubPullRequestReadClient client, MockRestServiceServer server) {}
}
