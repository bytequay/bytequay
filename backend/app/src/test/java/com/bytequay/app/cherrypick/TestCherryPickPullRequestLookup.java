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
package com.bytequay.app.cherrypick;

import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class TestCherryPickPullRequestLookup
{
    private static final RepoRef REPOSITORY = RepoRef.of("trinodb", "trino");
    private static final String SHA = "a".repeat(40);

    @Test
    void groupsACommitUnderItsSingleMergedPullRequest()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url(SHA)))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                [{"number":23401,"title":"Fix Hive coercion",
                                  "html_url":"https://github.com/trinodb/trino/pull/23401",
                                  "merged_at":"2026-08-03T10:00:00Z","base":{"ref":"master"}}]
                                """));

        List<String> warnings = new ArrayList<>();
        PullRequestLookupResult result =
                lookup(builder).lookUpResult(REPOSITORY, ImmutableList.of(SHA), "master", warnings);

        assertThat(association(result, SHA).number()).isEqualTo(23401);
        assertThat(association(result, SHA).title()).isEqualTo("Fix Hive coercion");
        assertThat(association(result, SHA).mergedAt()).isEqualTo("2026-08-03T10:00:00Z");
        assertThat(warnings).isEmpty();
        server.verify();
    }

    @Test
    void breaksATieOnTheSourceBranchAndOtherwiseLeavesTheCommitUngrouped()
    {
        String tie = "b".repeat(40);
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo(url(SHA)))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                [{"number":1,"title":"Backport","html_url":"u1",
                                  "merged_at":"2026-08-01T00:00:00Z","base":{"ref":"release-477"}},
                                 {"number":2,"title":"Original","html_url":"u2",
                                  "merged_at":"2026-08-02T00:00:00Z","base":{"ref":"master"}}]
                                """));
        server.expect(requestTo(url(tie)))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                [{"number":3,"title":"One","html_url":"u3",
                                  "merged_at":"2026-08-01T00:00:00Z","base":{"ref":"release-477"}},
                                 {"number":4,"title":"Two","html_url":"u4",
                                  "merged_at":"2026-08-02T00:00:00Z","base":{"ref":"release-478"}}]
                                """));

        List<String> warnings = new ArrayList<>();
        PullRequestLookupResult result =
                lookup(builder).lookUpResult(REPOSITORY, ImmutableList.of(SHA, tie), "master", warnings);

        assertThat(association(result, SHA).number()).as("base ref breaks the tie").isEqualTo(2);
        assertThat(result.associations()).as("an ambiguous commit is never guessed")
                .noneMatch(value -> value.sha().equals(tie));
        assertThat(warnings).anyMatch(warning -> warning.contains("no single merged pull request"));
    }

    @Test
    void ignoresUnmergedPullRequestsAndCommitsWithNone()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url(SHA)))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                [{"number":9,"title":"Still open","html_url":"u9",
                                  "merged_at":null,"base":{"ref":"master"}}]
                                """));

        List<String> warnings = new ArrayList<>();
        assertThat(lookup(builder).lookUpResult(REPOSITORY, ImmutableList.of(SHA), "master", warnings)
                .associations()).isEmpty();
        assertThat(warnings).anyMatch(warning -> warning.contains("listed individually"));
    }

    @Test
    void keepsCommitsSelectableWhenGitHubFails()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(manyTimes(), requestTo(url(SHA))).andRespond(withServerError());

        List<String> warnings = new ArrayList<>();
        PullRequestLookupResult result =
                lookup(builder).lookUpResult(REPOSITORY, ImmutableList.of(SHA), "master", warnings);

        assertThat(result.associations()).isEmpty();
        assertThat(result.failedShas()).containsExactly(SHA);
        assertThat(warnings).anyMatch(warning -> warning.contains("could not be checked against GitHub"));
    }

    @Test
    void warnsInsteadOfFailingWhenNoCredentialExists()
    {
        CherryPickPullRequestLookup lookup = new CherryPickPullRequestLookup(
                offlineClient(),
                () -> {
                    throw new IllegalStateException("no GitHub token configured");
                });

        List<String> warnings = new ArrayList<>();
        assertThat(lookup.lookUpResult(REPOSITORY, ImmutableList.of(SHA), "master", warnings)
                .associations()).isEmpty();
        assertThat(warnings).anyMatch(warning -> warning.contains("without GitHub credentials"));
    }

    @Test
    void asksGitHubOncePerCommitEvenWhenLookedUpAgain()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url(SHA)))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                [{"number":5,"title":"Cached","html_url":"u5",
                                  "merged_at":"2026-08-04T00:00:00Z","base":{"ref":"master"}}]
                                """));

        CherryPickPullRequestLookup lookup = lookup(builder);
        List<String> warnings = new ArrayList<>();
        lookup.lookUpResult(REPOSITORY, ImmutableList.of(SHA), "master", warnings);
        assertThat(association(
                lookup.lookUpResult(REPOSITORY, ImmutableList.of(SHA), "master", warnings), SHA).number())
                .isEqualTo(5);
        server.verify();
    }

    /**
     * A client that cannot reach anything.
     *
     * <p>The lookup must return before it asks for a token it does not have, so
     * this fails the test loudly rather than letting a regression quietly send
     * a request to github.com.
     */
    static RestClient offlineClient()
    {
        return RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory((uri, httpMethod) -> {
                    throw new AssertionError("this test must not make an HTTP request: " + httpMethod + " " + uri);
                })
                .build();
    }

    private static CherryPickPullRequestLookup lookup(RestClient.Builder builder)
    {
        return new CherryPickPullRequestLookup(builder.build(), () -> "test-token");
    }

    private static PullRequestAssociation association(PullRequestLookupResult result, String sha)
    {
        return result.associations().stream()
                .filter(value -> value.sha().equals(sha))
                .findFirst()
                .orElseThrow()
                .pullRequest();
    }

    private static String url(String sha)
    {
        return "https://api.github.com/repos/trinodb/trino/commits/" + sha + "/pulls?per_page=100";
    }
}
