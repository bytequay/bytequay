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
import com.bytequay.core.hosting.PullRequestAssociation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubCommitPullRequests
        extends AbstractGitHubClientTest
{
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repository");
    private static final String SHA = "a".repeat(40);
    private static final String URL = "https://api.github.test/repos/owner/repository/commits/" + SHA + "/pulls?per_page=100";

    private final GitHubPullRequestReadClient client = new GitHubPullRequestReadClient(restClient, graphQlClient);

    @Test
    void readsOnlyTheMergedPullRequestsThatContainTheCommit()
    {
        server.expect(requestTo(URL)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token")).andRespond(withSuccess("""
                [{"number":23401,"title":"Fix Hive coercion","html_url":"https://github.com/owner/repository/pull/23401",
                  "merged_at":"2026-08-03T10:00:00Z","base":{"ref":"master"}},
                 {"number":23402,"title":null,"html_url":null,"merged_at":"2026-08-04T10:00:00Z","base":{"ref":"release"}},
                 {"number":9,"title":"Still open","html_url":"u9","merged_at":null,"base":{"ref":"master"}}]
                """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchMergedPullRequestsWithCommit("token", REPOSITORY, SHA)).containsExactly(
                new PullRequestAssociation(23401, "Fix Hive coercion", "https://github.com/owner/repository/pull/23401", "master", "2026-08-03T10:00:00Z"),
                new PullRequestAssociation(23402, "", "", "release", "2026-08-04T10:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-body", "full-negative-page", "full-page-with-single-merged"})
    void incompleteResponsesFailInsteadOfReturningPartOfTheList(String kind)
    {
        String body = kind.equals("missing-body") ? "" : IntStream.rangeClosed(1, 100)
                .mapToObj(number -> """
                        {"number":%s,"title":"Candidate %s","html_url":"u%s","merged_at":%s,"base":{"ref":"master"}}
                        """.formatted(number, number, number, kind.equals("full-page-with-single-merged") && number == 1 ? "\"2026-08-01T00:00:00Z\"" : "null"))
                .collect(joining(",", "[", "]"));
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchMergedPullRequestsWithCommit("token", REPOSITORY, SHA)).isInstanceOf(RuntimeException.class);
    }
}
