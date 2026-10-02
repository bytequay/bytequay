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

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubHarvestClient
{
    private static final RepoRef REPOSITORY = RepoRef.of("trinodb", "trino");

    @Test
    void listsTheClosedPullRequestsThePersonWroteOrReviewedNewestCloseFirst()
    {
        Fixture fixture = fixture();
        fixture.rest().expect(requestTo(allOf(containsString("/search/issues"), containsString("author%3Aalice"))))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer pat"))
                .andRespond(withSuccess("""
                        {"total_count":2,"incomplete_results":false,"items":[
                          {"id":1,"number":101,"title":"Mine","closed_at":"2026-09-01T00:00:00Z","pull_request":{"merged_at":"2026-09-01T00:00:00Z"}},
                          {"id":2,"number":7,"title":"An issue","closed_at":"2026-09-02T00:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));
        fixture.rest().expect(requestTo(allOf(containsString("/search/issues"), containsString("reviewed-by%3Aalice"))))
                .andRespond(withSuccess("""
                        {"total_count":2,"incomplete_results":false,"items":[
                          {"id":3,"number":103,"title":"Reviewed","closed_at":"2026-09-03T00:00:00Z","pull_request":{"merged_at":null}},
                          {"id":1,"number":101,"title":"Mine","closed_at":"2026-09-01T00:00:00Z","pull_request":{"merged_at":"2026-09-01T00:00:00Z"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().closedPulls("pat", REPOSITORY, "alice")).containsExactly(
                new GitHubHarvestClient.ClosedPull(103, "Reviewed", Instant.parse("2026-09-03T00:00:00Z"), false),
                new GitHubHarvestClient.ClosedPull(101, "Mine", Instant.parse("2026-09-01T00:00:00Z"), true));
        fixture.rest().verify();
        assertThatThrownBy(() -> fixture.client().closedPulls("pat", REPOSITORY, "alice bob")).hasMessageContaining("Invalid GitHub login");
    }

    @Test
    void readsAClosedPullRequestsThreadsReviewsAndCommentsWithTheirAuthorsRoles()
    {
        Fixture fixture = fixture();
        fixture.graphql().expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess("""
                {"data":{"repository":{"pullRequest":{"number":31201,"title":"Add a property","url":"https://github.com/trinodb/trino/pull/31201","merged":true,
                  "closedAt":"2026-09-20T10:00:00Z",
                  "reviewThreads":{"nodes":[{"id":"thread-1","isResolved":true,"isOutdated":false,"path":"plugin/x/A.java","comments":{"nodes":[
                    {"id":"note-1","body":"Please document it.","url":"https://github.com/trinodb/trino/pull/31201#discussion_r1","authorAssociation":"MEMBER",
                     "author":{"__typename":"User","login":"bo"},"createdAt":"2026-09-19T10:00:00Z"},
                    {"id":"note-2","body":"Coverage ok","url":"u","authorAssociation":"NONE","author":{"__typename":"Bot","login":"codecov"},
                     "createdAt":"2026-09-19T11:00:00Z"}]}}]},
                  "reviews":{"nodes":[{"id":"review-1","body":"Looks good","url":"u","authorAssociation":"OWNER","author":{"__typename":"User","login":"dana"},
                    "submittedAt":"2026-09-19T12:00:00Z"}]},
                  "comments":{"nodes":[{"id":"comment-1","body":"Thanks","url":"u","authorAssociation":"CONTRIBUTOR","author":null,"createdAt":"2026-09-19T13:00:00Z"}]}
                }}}}
                """, MediaType.APPLICATION_JSON));

        GitHubHarvestClient.PullDiscussion discussion = fixture.client().discussion("pat", REPOSITORY, 31201).orElseThrow();

        assertThat(discussion.merged()).isTrue();
        GitHubHarvestClient.ReviewThread thread = discussion.threads().getFirst();
        assertThat(thread.resolved()).isTrue();
        assertThat(thread.path()).contains("plugin/x/A.java");
        assertThat(thread.comments()).extracting(GitHubHarvestClient.Note::maintainer).containsExactly(true, false);
        assertThat(thread.comments()).extracting(GitHubHarvestClient.Note::bot).containsExactly(false, true);
        assertThat(discussion.reviews().getFirst().maintainer()).isTrue();
        assertThat(discussion.comments().getFirst().author()).isEqualTo("ghost");
    }

    @Test
    void anOpenPullRequestHasNothingToLearnAndARateLimitStops()
    {
        Fixture fixture = fixture();
        fixture.graphql().expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess("""
                {"data":{"repository":{"pullRequest":{"number":5,"title":"Open","url":"u","merged":false,"closedAt":null,
                  "reviewThreads":{"nodes":[]},"reviews":{"nodes":[]},"comments":{"nodes":[]}}}}}
                """, MediaType.APPLICATION_JSON));
        fixture.graphql().expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess("""
                {"errors":[{"type":"RATE_LIMITED","message":"API rate limit exceeded"}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().discussion("pat", REPOSITORY, 5)).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> fixture.client().discussion("pat", REPOSITORY, 6)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rate limit");
    }

    private static Fixture fixture()
    {
        RestClient.Builder rest = RestClient.builder().baseUrl("https://api.github.test");
        RestClient.Builder graphql = RestClient.builder().baseUrl("https://api.github.test/graphql");
        MockRestServiceServer restServer = MockRestServiceServer.bindTo(rest).build();
        MockRestServiceServer graphqlServer = MockRestServiceServer.bindTo(graphql).build();
        return new Fixture(new GitHubHarvestClient(rest.build(), graphql.build()), restServer, graphqlServer);
    }

    private record Fixture(GitHubHarvestClient client, MockRestServiceServer rest, MockRestServiceServer graphql) {}
}
