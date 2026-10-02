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

import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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
    void readsTheExactPullRequestAgainAfterReadyMutation()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/acme/project/pulls/7"))
                .andRespond(withSuccess(row(7, "open", "null", "main").replace("\"draft\":true", "\"draft\":false"), MediaType.APPLICATION_JSON));
        GitHubPullRequestIdentity identity = fixture.client().fetchPullRequestIdentity("token", PullRequestRef.of("acme", "project", 7));
        assertThat(identity.draft()).isFalse();
        assertThat(identity.headSha()).isEqualTo(SHA);
        assertThat(identity.baseRepository()).isEqualTo(BASE);
        fixture.server().verify();
    }

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

    @Test
    void readsCompleteMergeEvidenceAcrossPagesAndRechecksIt()
    {
        Fixture fixture = fixture();
        List<String> commits = IntStream.rangeClosed(1, 101).mapToObj(value -> "%040x".formatted(value)).toList();
        String detail = mergedRow(101).replace(SHA, commits.getLast());
        String url = "https://api.github.test/repos/acme/project/pulls/7";
        fixture.server().expect(requestTo(url)).andRespond(withSuccess(detail, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo(url + "/commits?per_page=100&page=1"))
                .andRespond(withSuccess(commitRows(commits.subList(0, 100)), MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo(url + "/commits?per_page=100&page=2"))
                .andRespond(withSuccess(commitRows(commits.subList(100, 101)), MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo(url)).andRespond(withSuccess(detail, MediaType.APPLICATION_JSON));

        var merged = fixture.client().fetchMergedPullRequest("token", PullRequestRef.of("acme", "project", 7));
        assertThat(merged.commits()).containsExactlyElementsOf(commits);
        assertThat(merged.repositoryId()).isEqualTo(42);
        assertThat(merged.mergeSha()).isEqualTo("b".repeat(40));
        fixture.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unmerged", "missing-merge", "too-many", "missing-count", "missing-repository"})
    void refusesMissingOrUnsupportedMergeFacts(String problem)
    {
        Fixture fixture = fixture();
        String detail = switch (problem) {
            case "unmerged" -> mergedRow(1).replace("\"merged\":true", "\"merged\":false");
            case "missing-merge" -> mergedRow(1).replace("\"merge_commit_sha\":\"" + "b".repeat(40) + "\",", "");
            case "too-many" -> mergedRow(251);
            case "missing-count" -> mergedRow(1).replace("\"commits\":1,", "");
            case "missing-repository" -> mergedRow(1).replace("\"id\":42,", "");
            default -> throw new AssertionError(problem);
        };
        fixture.server().expect(requestTo("https://api.github.test/repos/acme/project/pulls/7"))
                .andRespond(withSuccess(detail, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> fixture.client().fetchMergedPullRequest("token", PullRequestRef.of("acme", "project", 7)))
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        fixture.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short-list", "duplicate", "wrong-head", "changed-evidence"})
    void refusesIncompleteOrMovingCommitEvidence(String problem)
    {
        Fixture fixture = fixture();
        String detail = mergedRow(2);
        String rows = switch (problem) {
            case "short-list" -> commitRows(ImmutableList.of(SHA));
            case "duplicate" -> commitRows(ImmutableList.of(SHA, SHA));
            case "wrong-head" -> commitRows(ImmutableList.of(SHA, "c".repeat(40)));
            default -> commitRows(ImmutableList.of("c".repeat(40), SHA));
        };
        String url = "https://api.github.test/repos/acme/project/pulls/7";
        fixture.server().expect(requestTo(url)).andRespond(withSuccess(detail, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo(url + "/commits?per_page=100&page=1")).andRespond(withSuccess(rows, MediaType.APPLICATION_JSON));
        if (problem.equals("changed-evidence")) {
            fixture.server().expect(requestTo(url)).andRespond(withSuccess(detail.replace("b".repeat(40), "d".repeat(40)), MediaType.APPLICATION_JSON));
        }
        assertThatThrownBy(() -> fixture.client().fetchMergedPullRequest("token", PullRequestRef.of("acme", "project", 7)))
                .isInstanceOf(IllegalStateException.class);
        fixture.server().verify();
    }

    private static String mergedRow(int count)
    {
        return row(7, "closed", "\"2026-09-01T00:00:00Z\"", "main")
                .replace("\"number\":7,", "\"number\":7,\"merged\":true,\"merge_commit_sha\":\"" + "b".repeat(40) + "\",\"commits\":" + count + ",")
                .replace("\"full_name\":\"acme/project\"", "\"id\":42,\"full_name\":\"acme/project\"");
    }

    private static String commitRows(List<String> commits)
    {
        return commits.stream().map(sha -> "{\"sha\":\"" + sha + "\"}").collect(joining(",", "[", "]"));
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
