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

import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubPullRequestWriteClient
{
    @Test
    void closesPullRequest()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/pulls/12"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("{\"state\":\"closed\"}"))
                .andRespond(withSuccess());

        client.closePullRequest("pat", PullRequestRef.of("upstream", "project", 12));

        server.verify();
    }

    @Test
    void closesIssue()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/12"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
                .andExpect(content().json("{\"state\":\"closed\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.closeIssue("pat", PullRequestRef.of("upstream", "project", 12));

        server.verify();
    }

    @Test
    void addsReactionsToEachSupportedGitHubResourceWhenViewerHasNotReacted()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/12/reactions?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess("""
                        [{"id":900,"content":"heart","user":{"login":"other"}}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/12/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("{\"content\":\"heart\"}"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/comments/41/reactions?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess("""
                        [{"id":901,"content":"heart","user":{"login":"me"}}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/comments/41/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("{\"content\":\"+1\"}"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/pulls/comments/42/reactions?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/upstream/project/pulls/comments/42/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("{\"content\":\"eyes\"}"))
                .andRespond(withSuccess());

        PullRequestRef pullRequest = PullRequestRef.of("upstream", "project", 12);
        client.togglePullRequestReaction("pat", pullRequest, "heart", "me");
        client.toggleIssueCommentReaction("pat", pullRequest, 41, "+1", "me");
        client.toggleReviewCommentReaction("pat", pullRequest, 42, "eyes", "me");

        server.verify();
    }

    @Test
    void removesReactionsFromEachSupportedGitHubResourceWhenViewerHasReacted()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/12/reactions?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess("""
                        [{"id":101,"content":"heart","user":{"login":"ME"}}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/12/reactions/101"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/comments/41/reactions?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess("""
                        [{"id":102,"content":"+1","user":{"login":"me"}}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/comments/41/reactions/102"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/pulls/comments/42/reactions?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess("""
                        [{"id":103,"content":"eyes","user":{"login":"me"}}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/upstream/project/pulls/comments/42/reactions/103"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess());

        PullRequestRef pullRequest = PullRequestRef.of("upstream", "project", 12);
        client.togglePullRequestReaction("pat", pullRequest, "heart", "me");
        client.toggleIssueCommentReaction("pat", pullRequest, 41, "+1", "me");
        client.toggleReviewCommentReaction("pat", pullRequest, 42, "eyes", "me");

        server.verify();
    }

    @Test
    void postsSingleInlineReviewComment()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/pulls/12/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("""
                        {
                          "path":"src/Main.java",
                          "body":"Please simplify this",
                          "line":18,
                          "side":"RIGHT",
                          "start_line":16,
                          "start_side":"RIGHT",
                          "commit_id":"head-sha"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "id":91,
                          "user":{
                            "login":"me",
                            "avatar_url":"https://avatars.test/me",
                            "type":"User"
                          },
                          "body":"Please simplify this",
                          "created_at":"2026-08-25T05:00:00Z",
                          "html_url":"https://github.test/upstream/project/pull/12#discussion_r91",
                          "pull_request_review_id":77,
                          "path":"src/Main.java",
                          "diff_hunk":"@@ -16,3 +16,3 @@",
                          "line":18,
                          "original_line":18,
                          "start_line":16,
                          "original_start_line":16,
                          "side":"RIGHT",
                          "reactions":{"eyes":1}
                        }
                        """, MediaType.APPLICATION_JSON));

        PrTimelineEvent posted = client.createReviewComment(
                "pat",
                PullRequestRef.of("upstream", "project", 12),
                new InlineReviewComment(
                        "src/Main.java", "Please simplify this", 18, "RIGHT", 16, "RIGHT", "head-sha"));

        assertThat(posted.githubId()).isEqualTo(91);
        assertThat(posted.event()).isEqualTo("review_thread");
        assertThat(posted.actor()).isEqualTo("me");
        assertThat(posted.timestamp()).isEqualTo(java.time.Instant.parse("2026-08-25T05:00:00Z"));
        assertThat(posted.payload().actorAvatarUrl()).isEqualTo("https://avatars.test/me");
        assertThat(posted.payload().htmlUrl())
                .isEqualTo("https://github.test/upstream/project/pull/12#discussion_r91");
        assertThat(posted.payload().path()).isEqualTo("src/Main.java");
        assertThat(posted.payload().diffHunk()).isEqualTo("@@ -16,3 +16,3 @@");
        assertThat(posted.payload().line()).isEqualTo(18);
        assertThat(posted.payload().startLine()).isEqualTo(16);
        assertThat(posted.payload().side()).isEqualTo("RIGHT");
        assertThat(posted.payload().resolved()).isFalse();
        assertThat(posted.payload().outdated()).isFalse();
        assertThat(posted.payload().comments()).singleElement().satisfies(comment -> {
            assertThat(comment.githubId()).isEqualTo(91);
            assertThat(comment.actor()).isEqualTo("me");
            assertThat(comment.body()).isEqualTo("Please simplify this");
            assertThat(comment.reviewId()).isEqualTo(77);
            assertThat(comment.reactions()).singleElement().satisfies(reaction -> {
                assertThat(reaction.content()).isEqualTo("eyes");
                assertThat(reaction.count()).isEqualTo(1);
            });
        });
        server.verify();
    }

    @Test
    void submitsInlineCommentsAsOneReview()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/pulls/12/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("""
                        {
                          "event":"COMMENT",
                          "commit_id":"head-sha",
                          "comments":[{
                            "path":"src/Main.java",
                            "body":"First note",
                            "line":18,
                            "side":"RIGHT"
                          },{
                            "path":"src/Other.java",
                            "body":"Second note",
                            "line":9,
                            "side":"LEFT"
                          }]
                        }
                        """))
                .andRespond(withSuccess());

        client.submitReview(
                "pat",
                PullRequestRef.of("upstream", "project", 12),
                "COMMENT",
                "",
                ImmutableList.of(
                        new InlineReviewComment(
                                "src/Main.java", "First note", 18, "RIGHT", null, null, "head-sha"),
                        new InlineReviewComment(
                                "src/Other.java", "Second note", 9, "LEFT", null, null, "head-sha")));

        server.verify();
    }

    @Test
    void createsPullRequestThroughExistingWriteClient()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/pulls"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("""
                        {
                          "title":"PR title",
                          "body":"PR body",
                          "head":"contributor:bump-branch",
                          "base":"main"
                        }
                        """))
                .andRespond(withSuccess("""
                        {"id":900,"number":12,"html_url":"https://github.com/upstream/project/pull/12"}
                        """, MediaType.APPLICATION_JSON));

        CreatedPullRequest created = client.createPullRequest(
                "pat",
                RepoRef.of("upstream", "project"),
                "contributor:bump-branch",
                "main",
                "PR title",
                "PR body");

        assertThat(created).isEqualTo(new CreatedPullRequest(
                900, 12, "https://github.com/upstream/project/pull/12"));
        server.verify();
    }

    @Test
    void updatesIssueTypeThroughTheIssuesEndpoint()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/12"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
                .andExpect(content().json("""
                        {"type":"Bug"}
                        """))
                .andRespond(withSuccess("""
                        {"type":{"name":"Bug"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.updateIssueType(
                "pat", PullRequestRef.of("upstream", "project", 12), "Bug")).isEqualTo("Bug");
        server.verify();
    }

    @Test
    void updatesIssueAssigneesAndLabelsThroughTheIssuesEndpoint()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        server.expect(requestTo("https://api.github.test/repos/upstream/project/issues/12"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().json("""
                        {"assignees":["alice"],"labels":["bug"]}
                        """))
                .andRespond(withSuccess("""
                        {
                          "assignees":[{"login":"alice"}],
                          "labels":[{"name":"bug"}],
                          "updated_at":"2026-08-23T10:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.updateIssueMetadata(
                "pat",
                PullRequestRef.of("upstream", "project", 12),
                ImmutableList.of("alice"),
                ImmutableList.of("bug")))
                .isEqualTo(new GitHubPullRequestWriteClient.IssueMetadata(
                        ImmutableList.of("alice"),
                        ImmutableList.of("bug"),
                        java.time.Instant.parse("2026-08-23T10:00:00Z")));
        server.verify();
    }

    @Test
    void addsAndRemovesRequestedReviewers()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                builder.build(),
                RestClient.builder().baseUrl("https://api.github.test/graphql").build());
        String url = "https://api.github.test/repos/upstream/project/pulls/12/requested_reviewers";
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"reviewers\":[\"alice\"]}"))
                .andRespond(withSuccess());
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(content().json("{\"reviewers\":[\"alice\"]}"))
                .andRespond(withSuccess());

        PullRequestRef pullRequest = PullRequestRef.of("upstream", "project", 12);
        client.updateReviewerRequest("pat", pullRequest, "alice", true);
        client.updateReviewerRequest("pat", pullRequest, "alice", false);
        server.verify();
    }

    @Test
    void resolvesAndUnresolvesReviewThreadsWithGraphQl()
    {
        RestClient.Builder restBuilder = RestClient.builder().baseUrl("https://api.github.test");
        RestClient.Builder graphQlBuilder = RestClient.builder().baseUrl("https://api.github.test/graphql");
        MockRestServiceServer graphQlServer = MockRestServiceServer.bindTo(graphQlBuilder).build();
        GitHubPullRequestWriteClient client = new GitHubPullRequestWriteClient(
                restBuilder.build(), graphQlBuilder.build());
        graphQlServer.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().string(containsString("resolveReviewThread")))
                .andExpect(content().string(containsString("\"threadId\":\"PRRT_1\"")))
                .andRespond(withSuccess("""
                        {"data":{"resolveReviewThread":{"thread":{"id":"PRRT_1","isResolved":true}}}}
                        """, MediaType.APPLICATION_JSON));
        graphQlServer.expect(requestTo("https://api.github.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer pat"))
                .andExpect(content().string(containsString("unresolveReviewThread")))
                .andRespond(withSuccess("""
                        {"data":{"unresolveReviewThread":{"thread":{"id":"PRRT_1","isResolved":false}}}}
                        """, MediaType.APPLICATION_JSON));

        client.setReviewThreadResolved("pat", "PRRT_1", true);
        client.setReviewThreadResolved("pat", "PRRT_1", false);

        graphQlServer.verify();
    }
}
