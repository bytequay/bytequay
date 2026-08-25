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

import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubPullRequestOverviewClient
{
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of("owner", "repo", 42);

    @Test
    void mapsTheSelfContainedOverviewHeader()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls/42"))
                .andRespond(withSuccess("""
                        {
                          "title":"Improve overview","html_url":"https://github.com/owner/repo/pull/42",
                          "state":"closed","merged_at":"2026-08-20T10:00:00Z","draft":false,
                          "created_at":"2026-08-19T10:00:00Z","updated_at":"2026-08-20T10:00:00Z",
                          "user":{"login":"alice","avatar_url":"https://avatars/alice"},
                          "body":"Description","labels":[{"name":"docs","color":"d4c5f9"}],
                          "assignees":[{"login":"bob"}],"milestone":{"title":"v1"},
                          "requested_reviewers":[{"login":"carol"}],
                          "head":{"sha":"abc","ref":"feature"},"base":{"ref":"main"},
                          "mergeable":true,"mergeable_state":"clean","additions":10,"deletions":2,
                          "changed_files":3
                        }
                        """, MediaType.APPLICATION_JSON));

        PrRawDetail detail = fixture.client().fetchPrDetail("pat", PULL_REQUEST);

        assertThat(detail.title()).isEqualTo("Improve overview");
        assertThat(detail.state()).isEqualTo("merged");
        assertThat(detail.baseRef()).isEqualTo("main");
        assertThat(detail.headRef()).isEqualTo("feature");
        assertThat(detail.labelColors()).containsEntry("docs", "d4c5f9");
        assertThat(detail.assignees()).containsExactly("bob");
        assertThat(detail.milestone()).isEqualTo("v1");
        fixture.verify();
    }

    @Test
    void conditionallyFetchesPullRequestWithTheStoredEtag()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls/42"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"etag-1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        var fetched = fixture.client().getPullRequest("pat", PULL_REQUEST, Optional.of("\"etag-1\""));

        assertThat(fetched.pullRequest()).isEmpty();
        assertThat(fetched.etag()).contains("\"etag-1\"");
        fixture.verify();
    }

    @Test
    void conditionalFetchPreservesGitHubNotFound()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls/42"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"etag-1\""))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"message\":\"Not Found\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().getPullRequest("pat", PULL_REQUEST, Optional.of("\"etag-1\"")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        fixture.verify();
    }

    @Test
    void storesTheEtagReturnedWithModifiedPullRequestData()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls/42"))
                .andRespond(withSuccess("""
                        {"id":12,"number":42,"title":"Title","state":"open","labels":[],
                         "requested_reviewers":[],"draft":false,
                         "mergeable":false,"mergeable_state":"dirty"}
                        """, MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"etag-2\""));

        var fetched = fixture.client().getPullRequest("pat", PULL_REQUEST, Optional.empty());

        assertThat(fetched.pullRequest()).isPresent();
        assertThat(fetched.pullRequest().orElseThrow().mergeable()).isFalse();
        assertThat(fetched.pullRequest().orElseThrow().mergeableState()).isEqualTo("dirty");
        assertThat(fetched.etag()).contains("\"etag-2\"");
        fixture.verify();
    }

    @Test
    void fetchesEveryAssignableRepositoryUserWithStableIdentity()
    {
        Fixture fixture = fixture();
        String firstPage = IntStream.range(0, 100)
                .mapToObj(index -> """
                        {"id":%d,"login":"user%d","avatar_url":"https://avatars/user%d","type":"User"}
                        """.formatted(index + 1, index, index))
                .collect(joining(",", "[", "]"));
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/assignees?per_page=100&page=1"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/assignees?per_page=100&page=2"))
                .andRespond(withSuccess("""
                        [{"id":101,"login":"copilot[bot]","avatar_url":"https://avatars/copilot","type":"Bot"}]
                        """, MediaType.APPLICATION_JSON));

        List<GitHubRepositoryUser> users = fixture.client().fetchAssignableRepositoryUsers("pat", RepoRef.of("owner", "repo"));

        assertThat(users).hasSize(101);
        assertThat(users.getFirst()).isEqualTo(new GitHubRepositoryUser(1, "user0", "https://avatars/user0", false));
        assertThat(users.getLast()).isEqualTo(new GitHubRepositoryUser(101, "copilot[bot]", "https://avatars/copilot", true));
        fixture.verify();
    }

    @Test
    void enrichesFullAndShortIssueReferencesAndFetchesDescriptionReactions()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls/42"))
                .andRespond(withSuccess("""
                        {"title":"Overview","state":"open","body":"Includes\\n\\n- https://github.com/owner/repo/issues/7\\n- #8",
                         "labels":[],"assignees":[],"requested_reviewers":[],"head":{},"base":{},
                         "draft":false,"additions":0,"deletions":0,"changed_files":0}
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"ref0":{"__typename":"Issue","title":"Linked issue","number":7},
                                 "ref1":{"__typename":"PullRequest","title":"Linked pull","number":8}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/issues/42"))
                .andRespond(withSuccess("""
                        {"reactions":{"+1":2,"eyes":1}}
                        """, MediaType.APPLICATION_JSON));

        PrRawDetail detail = fixture.client().fetchPrDetail("pat", PULL_REQUEST);

        assertThat(detail.body()).isEqualTo("""
                Includes

                - [Linked issue #7](https://github.com/owner/repo/issues/7)
                - [Linked pull #8](https://github.com/owner/repo/pull/8)""");
        assertThat(fixture.client().fetchPrDescriptionReactions("pat", PULL_REQUEST))
                .extracting(reaction -> reaction.content() + ":" + reaction.count())
                .containsExactly("+1:2", "eyes:1");
        fixture.verify();
    }

    @Test
    void fetchesEveryReviewPage()
    {
        Fixture fixture = fixture();
        String firstPage = IntStream.range(0, 100)
                .mapToObj(index -> """
                        {"id":%d,"state":"COMMENTED","submitted_at":"2026-08-20T10:00:00Z",
                         "user":{"login":"reviewer%d","type":"User"}}
                        """.formatted(index + 1, index))
                .collect(joining(",", "[", "]"));
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/pulls/42/reviews?per_page=100&page=1"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/pulls/42/reviews?per_page=100&page=2"))
                .andRespond(withSuccess("""
                        [{"id":101,"state":"APPROVED","submitted_at":"2026-08-20T11:00:00Z",
                          "user":{"login":"last","type":"User"}}]
                        """, MediaType.APPLICATION_JSON));

        List<PrReviewState> reviews = fixture.client().fetchPrReviews("pat", PULL_REQUEST);

        assertThat(reviews).hasSize(101);
        assertThat(reviews.getLast().login()).isEqualTo("last");
        fixture.verify();
    }

    @Test
    void addsGraphQlReactionsToReviewTimelineEvents()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/issues/42/timeline?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":5,"event":"reviewed","state":"commented","body":"LGTM",
                          "submitted_at":"2026-08-20T10:00:00Z",
                          "user":{"login":"alice","type":"User"}},
                         {"event":"committed","sha":"abc","message":"Fix it",
                          "author":{"name":"alice","date":"2026-08-20T09:00:00Z"}}]
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{"reviews":{
                          "nodes":[{"databaseId":5,"reactionGroups":[
                            {"content":"THUMBS_UP","users":{"totalCount":1}},
                            {"content":"HEART","users":{"totalCount":2}},
                            {"content":"EYES","users":{"totalCount":0}}]}],
                          "pageInfo":{"hasNextPage":false,"endCursor":"cursor"}
                        }}}}}
                        """, MediaType.APPLICATION_JSON));

        List<PrTimelineEvent> timeline = fixture.client().fetchPrTimeline("pat", PULL_REQUEST, null);

        assertThat(timeline).hasSize(2);
        assertThat(timeline.getFirst().payload().reactions())
                .extracting(reaction -> reaction.content() + ":" + reaction.count())
                .containsExactly("+1:1", "heart:2");
        assertThat(timeline.getLast().payload().reactions()).isEmpty();
        fixture.verify();
    }

    @Test
    void groupsReviewCommentsAndEnrichesEveryTimelineBody()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/pulls/42/comments?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [
                          {"id":11,"pull_request_review_id":5,"path":"src/App.java","line":12,
                           "diff_hunk":"@@ -1 +1 @@\\n-old\\n+new","body":"Please verify #8",
                           "created_at":"2026-08-20T10:00:00Z","html_url":"https://github/comment/11",
                           "user":{"login":"alice","avatar_url":"https://avatars/alice","type":"User"},
                           "reactions":{"+1":1}},
                          {"id":12,"pull_request_review_id":5,"in_reply_to_id":11,"path":"src/App.java",
                           "body":"Done #8","created_at":"2026-08-20T10:05:00Z",
                           "user":{"login":"bob","type":"User"}}
                        ]
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{"reviewThreads":{
                          "nodes":[{"id":"PRRT_1","isResolved":true,"isOutdated":false,
                            "path":"src/App.java","line":12,"diffSide":"RIGHT",
                            "comments":{"nodes":[{"databaseId":11}]}}],
                          "pageInfo":{"hasNextPage":false,"endCursor":"cursor"}
                        }}}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"ref0":{"__typename":"PullRequest","title":"Linked pull","number":8}}}
                        """, MediaType.APPLICATION_JSON));

        List<PrTimelineEvent> threads = fixture.client().fetchPrReviewThreads("pat", PULL_REQUEST);
        PrTimelineEvent issueComment = new PrTimelineEvent(
                99L,
                "commented",
                "eve",
                Instant.parse("2026-08-20T10:10:00Z"),
                "See #8",
                Optional.empty());
        List<PrTimelineEvent> enriched = fixture.client().enrichTimelineReferences(
                "pat", PULL_REQUEST, ImmutableList.of(issueComment, threads.getFirst()));

        assertThat(threads).singleElement().satisfies(thread -> {
            assertThat(thread.event()).isEqualTo("review_thread");
            assertThat(thread.payload().resolved()).isTrue();
            assertThat(thread.payload().path()).isEqualTo("src/App.java");
            assertThat(thread.payload().comments()).hasSize(2);
            assertThat(thread.payload().comments().getFirst().reactions()).singleElement()
                    .satisfies(reaction -> assertThat(reaction.content()).isEqualTo("+1"));
        });
        assertThat(enriched.getFirst().body())
                .isEqualTo("See [Linked pull #8](https://github.com/owner/repo/pull/8)");
        assertThat(enriched.getLast().payload().comments())
                .extracting(comment -> comment.body())
                .containsExactly(
                        "Please verify [Linked pull #8](https://github.com/owner/repo/pull/8)",
                        "Done [Linked pull #8](https://github.com/owner/repo/pull/8)");
        fixture.verify();
    }

    private static Fixture fixture()
    {
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer api = MockRestServiceServer.bindTo(apiBuilder).build();
        RestClient.Builder graphQlBuilder = RestClient.builder().baseUrl("https://api.github.test/graphql");
        MockRestServiceServer graphQl = MockRestServiceServer.bindTo(graphQlBuilder).build();
        return new Fixture(
                new GitHubPullRequestReadClient(apiBuilder.build(), graphQlBuilder.build()),
                api,
                graphQl);
    }

    private record Fixture(
            GitHubPullRequestReadClient client,
            MockRestServiceServer api,
            MockRestServiceServer graphQl)
    {
        void verify()
        {
            api.verify();
            graphQl.verify();
        }
    }
}
