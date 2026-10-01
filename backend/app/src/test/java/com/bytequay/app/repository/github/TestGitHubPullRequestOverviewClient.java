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

import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubPullRequestOverviewClient
{
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of("owner", "repo", 42);

    @Test
    void discoversPullRequestsByForkHeadWithBranchRefsAndAllPages()
    {
        Fixture fixture = fixture();
        String firstPage = IntStream.range(1, 101)
                .mapToObj(number -> """
                        {"id":%d,"number":%d,"title":"Draft","state":"open","draft":true,
                         "head":{"ref":"feature/change"},"user":{"login":"contributor"}}
                        """.formatted(number, number))
                .collect(joining(",", "[", "]"));
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls?state=all&head=contributor%3Afeature%2Fchange&per_page=100&page=1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer pat"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls?state=all&head=contributor%3Afeature%2Fchange&per_page=100&page=2"))
                .andRespond(withSuccess("""
                        [{"id":101,"number":101,"title":"Earlier attempt","state":"closed",
                          "merged_at":"2026-09-07T00:00:00Z","head":{"ref":"feature/change"}}]
                        """, MediaType.APPLICATION_JSON));

        List<FetchedPullRequest> found = fixture.client().fetchPullRequestsByHead("pat", RepoRef.of("owner", "repo"), "contributor:feature/change");

        assertThat(found).hasSize(101);
        assertThat(found.getFirst().headRef()).isEqualTo("feature/change");
        assertThat(found.getFirst().draft()).isTrue();
        assertThat(found.getFirst().repo()).isEqualTo("owner/repo");
        assertThat(found.getLast().state()).isEqualTo("closed");
        assertThat(found.getLast().mergedAt()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
        fixture.verify();
    }

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
    void readsDefaultDiffContentsAtThePullRequestsBaseAndHead()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls/42"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer pat"))
                .andRespond(withSuccess("""
                        {"body":"See #7","base":{"sha":"base-sha"},"head":{"sha":"head-sha"}}
                        """, MediaType.APPLICATION_JSON));
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/contents/src/App.java?ref=base-sha"))
                .andRespond(withSuccess("old content", MediaType.TEXT_PLAIN));
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/contents/src/App.java?ref=head-sha"))
                .andRespond(withSuccess("new content", MediaType.TEXT_PLAIN));

        assertThat(fixture.client().fetchPrDiffFileContent("pat", PULL_REQUEST, "src/App.java"))
                .isEqualTo(new DiffFileContent("old content", "new content"));
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
        assertThat(fetched.pullRequest().orElseThrow().origin()).isEqualTo(OTHER);
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
                        {"node_id":"PR_42","reactions":{"+1":2,"eyes":1}}
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"nodes":[{"id":"PR_42","reactionGroups":[
                          {"content":"THUMBS_UP","viewerHasReacted":true},
                          {"content":"EYES","viewerHasReacted":false}
                        ]}]}}
                        """, MediaType.APPLICATION_JSON));

        PrRawDetail detail = fixture.client().fetchPrDetail("pat", PULL_REQUEST);

        assertThat(detail.body()).isEqualTo("""
                Includes

                - [Linked issue #7](https://github.com/owner/repo/issues/7)
                - [Linked pull #8](https://github.com/owner/repo/pull/8)""");
        assertThat(fixture.client().fetchPrDescriptionReactions("pat", PULL_REQUEST))
                .extracting(reaction -> reaction.content() + ":" + reaction.count() + ":" + reaction.viewerHasReacted())
                .containsExactly("+1:2:true", "eyes:1:false");
        fixture.verify();
    }

    @Test
    void preservesUnknownViewerStateWhenGraphQlReactionGroupsArePartial()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/issues/42"))
                .andRespond(withSuccess("""
                        {"node_id":"PR_42","reactions":{"+1":2}}
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"nodes":[{"id":"PR_42","reactionGroups":null}]},
                         "errors":[{"message":"Reaction groups unavailable"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchPrDescriptionReactions("pat", PULL_REQUEST))
                .singleElement()
                .satisfies(reaction -> assertThat(reaction.viewerHasReacted()).isNull());
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
    void failedReviewFetchThrowsInsteadOfReportingNoReviews()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/pulls/42/reviews?per_page=100&page=1"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        // An empty list would read as "no reviews" and sweep the cached rows.
        assertThatThrownBy(() -> fixture.client().fetchPrReviews("pat", PULL_REQUEST))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        fixture.verify();
    }

    @Test
    void searchesPullRequestsMostRecentlyUpdatedFirst()
    {
        Fixture fixture = fixture();
        fixture.api().expect(request -> assertThat(request.getURI().getQuery()).isEqualTo("q=is:pr author:me&per_page=20&page=1&sort=updated&order=desc"))
                .andRespond(withSuccess("{\"total_count\":0,\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(fixture.client().searchPullRequestsPaged("pat", "is:pr author:me", 1, 20).items()).isEmpty();
        fixture.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"total_count\":1,\"items\":null}", "{\"total_count\":0,\"items\":[],\"incomplete_results\":true}"})
    void incompleteSearchResponsesCannotReportACompleteEmptyDiscovery(String body)
    {
        Fixture fixture = fixture();
        fixture.api().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/search/issues"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().searchPullRequestsPaged("pat", "is:pr author:me", 1, 100))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        fixture.verify();
    }

    @Test
    void searchResultCeilingRemainsIncompleteWhenMoreRowsExist()
    {
        Fixture fixture = fixture();
        String items = IntStream.rangeClosed(901, 1000).mapToObj(number -> """
                {"id":%d,"number":%d,"title":"Pull request","html_url":"https://github.com/owner/repo/pull/%d",
                 "repository_url":"https://api.github.com/repos/owner/repo","state":"open","user":{"login":"author"},
                 "created_at":"2026-09-20T00:00:00Z","updated_at":"2026-09-21T00:00:00Z"}
                """.formatted(number, number, number)).collect(joining(",", "[", "]"));
        fixture.api().expect(request -> assertThat(request.getURI().getQuery()).contains("page=10"))
                .andRespond(withSuccess("{\"total_count\":1001,\"items\":" + items + "}", MediaType.APPLICATION_JSON));

        assertThat(fixture.client().searchPullRequestsPaged("pat", "is:pr author:me", 10, 100).hasMore()).isTrue();
        fixture.verify();
    }

    @Test
    void datesCommitEventsByCommitterTime()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/issues/42/timeline?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"event":"committed","sha":"abc","message":"Fix it",
                          "author":{"name":"Alice","date":"2026-08-20T09:00:00Z"},
                          "committer":{"name":"Alice","date":"2026-08-20T10:00:00Z"}}]
                        """, MediaType.APPLICATION_JSON));
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/pulls/42/commits?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"sha":"abc","commit":{"message":"Fix it",
                          "author":{"name":"Alice","date":"2026-08-20T09:00:00Z"},
                          "committer":{"name":"Alice","date":"2026-08-20T10:00:00Z"}}}]
                        """, MediaType.APPLICATION_JSON));

        List<PrTimelineEvent> timeline = fixture.client().fetchPrTimeline("pat", PULL_REQUEST);
        List<PrTimelineEvent> commits = fixture.client().fetchPrCommits("pat", PULL_REQUEST);

        assertThat(timeline).singleElement().extracting(PrTimelineEvent::timestamp)
                .isEqualTo(Instant.parse("2026-08-20T10:00:00Z"));
        assertThat(commits).singleElement().extracting(PrTimelineEvent::timestamp)
                .isEqualTo(Instant.parse("2026-08-20T10:00:00Z"));
        fixture.verify();
    }

    @Test
    void mapsRequestedPeopleAndTeamsFromTimelineEvents()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/issues/42/timeline?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":1,"event":"review_requested","created_at":"2026-08-20T10:00:00Z",
                          "actor":{"login":"alice"},"requested_reviewer":{"login":"bob"}},
                         {"id":2,"event":"review_requested","created_at":"2026-08-20T10:00:00Z",
                          "actor":{"login":"alice"},"requested_reviewer":{"login":"carol"}},
                         {"id":3,"event":"review_requested","created_at":"2026-08-20T10:00:00Z",
                          "actor":{"login":"alice"},"requested_team":{"name":"Connector engineers","slug":"connectors"}},
                         {"id":4,"event":"review_requested","created_at":"2026-08-20T10:00:00Z",
                          "actor":{"login":"alice"}},
                         {"id":5,"event":"review_requested","created_at":"2026-08-20T10:00:00Z",
                          "actor":{"login":"alice"},"requested_team":{"slug":""}}]
                        """, MediaType.APPLICATION_JSON));

        List<PrTimelineEvent> timeline = fixture.client().fetchPrTimeline("pat", PULL_REQUEST);

        assertThat(timeline)
                .extracting(event -> event.githubId() + ":" + event.actor() + ":" + event.payload().requestedReviewer())
                .containsExactly("1:alice:bob", "2:alice:carol", "3:alice:owner/connectors", "4:alice:null", "5:alice:null");
        fixture.verify();
    }

    @Test
    void mapsAssigneesFromTimelineEvents()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/issues/42/timeline?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":1,"event":"assigned","created_at":"2026-08-20T10:00:00Z",
                          "actor":{"login":"alice"},"assignee":{"login":"bob"}},
                         {"id":2,"event":"unassigned","created_at":"2026-08-20T11:00:00Z",
                          "actor":{"login":"alice"},"assignee":{"login":"carol"}}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchPrTimeline("pat", PULL_REQUEST))
                .extracting(event -> event.event() + ":" + event.payload().assignee())
                .containsExactly("assigned:bob", "unassigned:carol");
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
                            {"content":"THUMBS_UP","users":{"totalCount":1},"viewerHasReacted":true},
                            {"content":"HEART","users":{"totalCount":2},"viewerHasReacted":false},
                            {"content":"EYES","users":{"totalCount":0},"viewerHasReacted":true}]}],
                          "pageInfo":{"hasNextPage":false,"endCursor":"cursor"}
                        }}}}}
                        """, MediaType.APPLICATION_JSON));

        List<PrTimelineEvent> timeline = fixture.client().fetchPrTimeline("pat", PULL_REQUEST);

        assertThat(timeline).hasSize(2);
        assertThat(timeline.getFirst().payload().reactions())
                .extracting(reaction -> reaction.content() + ":" + reaction.count() + ":" + reaction.viewerHasReacted())
                .containsExactly("+1:1:true", "heart:2:false");
        assertThat(timeline.getLast().payload().reactions()).isEmpty();
        fixture.verify();
    }

    @Test
    void enrichesIssueCommentsWithViewerReactionState()
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/issues/42/comments?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":21,"node_id":"IC_21","body":"Looks good",
                          "created_at":"2026-08-20T10:00:00Z",
                          "user":{"login":"alice","type":"User"},
                          "reactions":{"+1":2,"heart":1}}]
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"nodes":[{"id":"IC_21","reactionGroups":[
                          {"content":"THUMBS_UP","viewerHasReacted":false},
                          {"content":"HEART","viewerHasReacted":true}
                        ]}]}}
                        """, MediaType.APPLICATION_JSON));

        List<PrTimelineEvent> comments = fixture.client().fetchPrIssueComments("pat", PULL_REQUEST);

        assertThat(comments).singleElement().satisfies(comment ->
                assertThat(comment.payload().reactions())
                        .extracting(reaction -> reaction.content() + ":" + reaction.count() + ":" + reaction.viewerHasReacted())
                        .containsExactly("+1:2:false", "heart:1:true"));
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
                          {"id":11,"node_id":"RC_11","pull_request_review_id":5,"path":"src/App.java","line":12,
                           "diff_hunk":"@@ -1 +1 @@\\n-old\\n+new","body":"Please verify #8",
                           "created_at":"2026-08-20T10:00:00Z","html_url":"https://github/comment/11",
                           "user":{"login":"alice","avatar_url":"https://avatars/alice","type":"User"},
                           "reactions":{"+1":1}},
                          {"id":12,"node_id":"RC_12","pull_request_review_id":5,"in_reply_to_id":11,"path":"src/App.java",
                           "body":"Done #8","created_at":"2026-08-20T10:05:00Z",
                           "user":{"login":"bob","type":"User"}}
                        ]
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andRespond(withSuccess("""
                        {"data":{"nodes":[
                          {"id":"RC_11","reactionGroups":[
                            {"content":"THUMBS_UP","viewerHasReacted":true}]},
                          {"id":"RC_12","reactionGroups":[]}
                        ]}}
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
                    .satisfies(reaction -> {
                        assertThat(reaction.content()).isEqualTo("+1");
                        assertThat(reaction.viewerHasReacted()).isTrue();
                    });
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

    @Test
    void loadsReviewCommentsAndThreadLocationsAcrossPages()
    {
        Fixture fixture = fixture();
        String comments = IntStream.rangeClosed(1, 100)
                .mapToObj(id -> """
                        {"id":%d,"path":"a.txt","line":%d,"original_line":200,"start_line":1,"original_start_line":198,
                         "side":"RIGHT","body":"Comment %d",
                         "created_at":"2026-08-20T10:00:00Z","user":{"login":"alice","avatar_url":"https://avatars/alice"}}
                        """.formatted(id, id, id))
                .collect(joining(",", "[", "]"));
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls/42/comments?per_page=100&page=1"))
                .andRespond(withSuccess(comments, MediaType.APPLICATION_JSON));
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls/42/comments?per_page=100&page=2"))
                .andRespond(withSuccess("""
                        [{"id":101,"in_reply_to_id":1,"body":"Reply on the next page",
                          "created_at":"2026-08-20T10:01:00Z","user":{"login":"bob","avatar_url":"https://avatars/bob"}}]
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().json("""
                        {"variables":{"owner":"owner","repo":"repo","number":42,"after":null}}
                        """))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{"reviewThreads":{
                          "nodes":[{"id":"PRRT_1","isResolved":false,"isOutdated":false,
                            "path":"a.txt","line":7,"originalLine":1,"diffSide":"RIGHT",
                            "comments":{"nodes":[{"databaseId":1}]}}],
                          "pageInfo":{"hasNextPage":true,"endCursor":"next-thread-page"}
                        }}}}}
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().json("""
                        {"variables":{"owner":"owner","repo":"repo","number":42,"after":"next-thread-page"}}
                        """))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{"reviewThreads":{
                          "nodes":[{"id":"PRRT_100","isResolved":true,"isOutdated":true,
                            "path":"a.txt","line":null,"originalLine":100,"startLine":null,
                            "originalStartLine":98,"diffSide":"LEFT","comments":{"nodes":[{"databaseId":100}]}}],
                          "pageInfo":{"hasNextPage":false,"endCursor":"last-thread-page"}
                        }}}}}
                        """, MediaType.APPLICATION_JSON));

        List<PrTimelineEvent> threads = fixture.client().fetchPrReviewThreads("pat", PULL_REQUEST);

        assertThat(threads).hasSize(100);
        assertThat(threads.getFirst().payload()).satisfies(payload -> {
            assertThat(payload.line()).isEqualTo(7);
            assertThat(payload.originalLine()).isEqualTo(1);
            assertThat(payload.startLine()).isNull();
            assertThat(payload.originalStartLine()).isNull();
            assertThat(payload.side()).isEqualTo("RIGHT");
            assertThat(payload.actorAvatarUrl()).isEqualTo("https://avatars/alice");
            assertThat(payload.comments()).extracting(comment -> comment.body()).containsExactly("Comment 1", "Reply on the next page");
            assertThat(payload.comments().getLast().actorAvatarUrl()).isEqualTo("https://avatars/bob");
        });
        assertThat(threads.get(1).payload()).satisfies(payload -> {
            assertThat(payload.path()).isEqualTo("a.txt");
            assertThat(payload.line()).isEqualTo(2);
            assertThat(payload.originalLine()).isEqualTo(200);
            assertThat(payload.startLine()).isEqualTo(1);
            assertThat(payload.originalStartLine()).isEqualTo(198);
            assertThat(payload.side()).isEqualTo("RIGHT");
            assertThat(payload.resolved()).isFalse();
            assertThat(payload.outdated()).isFalse();
        });
        assertThat(threads.getLast().payload()).satisfies(payload -> {
            assertThat(payload.resolved()).isTrue();
            assertThat(payload.outdated()).isTrue();
            assertThat(payload.line()).isNull();
            assertThat(payload.originalLine()).isEqualTo(100);
            assertThat(payload.startLine()).isNull();
            assertThat(payload.originalStartLine()).isEqualTo(98);
            assertThat(payload.side()).isEqualTo("LEFT");
        });
        fixture.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsSubmittedReviewCommentLocationsFromThreads(boolean outdated)
    {
        Fixture fixture = fixture();
        fixture.api().expect(requestTo("https://api.github.test/repos/owner/repo/pulls/42/reviews/5/comments?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":11,"pull_request_review_id":5,"path":"src/App.java","position":4,"original_position":4,
                          "diff_hunk":"@@ -16,3 +16,3 @@\\n-old\\n+new","body":"Please verify this",
                          "created_at":"2026-08-20T10:00:00Z","html_url":"https://github/comment/11",
                          "user":{"login":"alice","avatar_url":"https://avatars/alice","type":"User"}}]
                        """, MediaType.APPLICATION_JSON));
        fixture.graphQl().expect(requestTo("https://api.github.test/graphql"))
                .andExpect(content().json("""
                        {"variables":{"owner":"owner","repo":"repo","number":42,"after":null}}
                        """))
                .andRespond(withSuccess("""
                        {"data":{"repository":{"pullRequest":{"reviewThreads":{
                          "nodes":[{"id":"PRRT_1","isResolved":false,"isOutdated":%s,
                            "path":"src/App.java","line":%s,"originalLine":18,"startLine":%s,
                            "originalStartLine":16,"diffSide":"RIGHT","comments":{"nodes":[{"databaseId":11}]}}],
                          "pageInfo":{"hasNextPage":false,"endCursor":null}
                        }}}}}
                        """.formatted(outdated, outdated ? "null" : "18", outdated ? "null" : "16"), MediaType.APPLICATION_JSON));

        List<PrTimelineEvent> threads = fixture.client().fetchReviewComments("pat", PULL_REQUEST, 5);

        assertThat(threads).singleElement().satisfies(thread -> {
            assertThat(thread.githubId()).isEqualTo(11);
            assertThat(thread.event()).isEqualTo("review_thread");
            assertThat(thread.payload().outdated()).isEqualTo(outdated);
            assertThat(thread.payload().resolved()).isFalse();
            assertThat(thread.payload().path()).isEqualTo("src/App.java");
            assertThat(thread.payload().line()).isEqualTo(outdated ? null : 18);
            assertThat(thread.payload().startLine()).isEqualTo(outdated ? null : 16);
            assertThat(thread.payload().originalLine()).isEqualTo(18);
            assertThat(thread.payload().originalStartLine()).isEqualTo(16);
            assertThat(thread.payload().side()).isEqualTo("RIGHT");
            assertThat(thread.payload().diffHunk()).isEqualTo("@@ -16,3 +16,3 @@\n-old\n+new");
            assertThat(thread.payload().comments()).singleElement().satisfies(comment -> {
                assertThat(comment.reviewId()).isEqualTo(5);
                assertThat(comment.body()).isEqualTo("Please verify this");
            });
        });
        fixture.verify();
    }

    @Test
    void conditionalDiffFilesReuseTheStoredCopyOnNotModified()
    {
        Fixture fixture = fixture();
        fixture.api.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/7/files?per_page=100&page=1"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"files-1\""))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, "\"files-1\""));

        ConditionalList<DiffFile> result = fixture.client.fetchPrDiffFiles(
                "pat",
                PullRequestRef.of("owner", "repo", 7),
                storedFiles(1),
                Optional.of("\"files-1\""));

        assertThat(result.unchanged()).isTrue();
        assertThat(result.etag()).contains("\"files-1\"");
        fixture.verify();
    }

    @Test
    void conditionalDiffFilesIgnoreTheStoredEtagBeyondOnePage()
    {
        Fixture fixture = fixture();
        fixture.api.expect(requestTo("https://api.github.test/repos/owner/repo/pulls/7/files?per_page=100&page=1"))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
                .andRespond(withSuccess(
                        """
                        [{"filename":"a.txt","status":"modified","additions":1,"deletions":0,"patch":"+line"}]
                        """, MediaType.APPLICATION_JSON));

        ConditionalList<DiffFile> result = fixture.client.fetchPrDiffFiles(
                "pat",
                PullRequestRef.of("owner", "repo", 7),
                storedFiles(101),
                Optional.of("\"files-1\""));

        assertThat(result.unchanged()).isFalse();
        assertThat(result.orElse(ImmutableList.of())).extracting(DiffFile::filename).containsExactly("a.txt");
        fixture.verify();
    }

    private static List<DiffFile> storedFiles(int count)
    {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> new DiffFile("file-" + index + ".txt", "modified", 1, 0, "+line"))
                .toList();
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
