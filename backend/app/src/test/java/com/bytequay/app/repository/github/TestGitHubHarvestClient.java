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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubHarvestClient
        extends AbstractGitHubClientTest
{
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repository");

    private final GitHubHarvestClient client = new GitHubHarvestClient(restClient, graphQlClient);

    @Test
    void listsTheClosedPullRequestsThePersonWroteOrReviewedNewestCloseFirst()
    {
        server.expect(requestTo(allOf(containsString("/search/issues"), containsString("author%3Aalice"))))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer pat"))
                .andRespond(withSuccess("""
                        {"total_count":2,"incomplete_results":false,"items":[
                          {"id":1,"number":101,"title":"Mine","closed_at":"2026-09-01T00:00:00Z","pull_request":{"merged_at":"2026-09-01T00:00:00Z"}},
                          {"id":2,"number":7,"title":"An issue","closed_at":"2026-09-02T00:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(allOf(containsString("/search/issues"), containsString("reviewed-by%3Aalice"))))
                .andRespond(withSuccess("""
                        {"total_count":2,"incomplete_results":false,"items":[
                          {"id":3,"number":103,"title":"Reviewed","closed_at":"2026-09-03T00:00:00Z","pull_request":{"merged_at":null}},
                          {"id":1,"number":101,"title":"Mine","closed_at":"2026-09-01T00:00:00Z","pull_request":{"merged_at":"2026-09-01T00:00:00Z"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.closedPulls("pat", REPOSITORY, "alice")).containsExactly(
                new GitHubHarvestClient.ClosedPull(103, "Reviewed", Instant.parse("2026-09-03T00:00:00Z"), false),
                new GitHubHarvestClient.ClosedPull(101, "Mine", Instant.parse("2026-09-01T00:00:00Z"), true));
        assertThatThrownBy(() -> client.closedPulls("pat", REPOSITORY, "alice bob")).hasMessageContaining("Invalid GitHub login");
    }

    @Test
    void findsAClosedPullRequestByNumberOrByEveryWordInItsTitle()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repository/issues/31354")).andRespond(withSuccess("""
                {"id":1,"number":31354,"title":"Fix checkpoint write","closed_at":"2026-09-20T00:00:00Z","pull_request":{"merged_at":"2026-09-20T00:00:00Z"}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repository/issues/31355")).andRespond(withSuccess("""
                {"id":2,"number":31355,"title":"Still open","closed_at":null,"pull_request":{"merged_at":null}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repository/issues/9")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(allOf(containsString("/search/issues"), containsString("owner"),
                        containsString("%20is%3Apr%20is%3Aclosed%20in%3Atitle%20%22checkpoint%22%20%22is%22%20%22open%22%20%22null%22"), containsString("per_page=10"))))
                .andRespond(withSuccess("""
                        {"total_count":2,"incomplete_results":false,"items":[
                          {"id":3,"number":31200,"title":"Checkpoint null counts","closed_at":"2026-09-01T00:00:00Z","pull_request":{"merged_at":null}},
                          {"id":4,"number":8,"title":"An issue about checkpoints","closed_at":"2026-09-02T00:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.findClosedPulls("pat", REPOSITORY, " #31354 "))
                .containsExactly(new GitHubHarvestClient.ClosedPull(31354, "Fix checkpoint write", Instant.parse("2026-09-20T00:00:00Z"), true));
        assertThat(client.findClosedPulls("pat", REPOSITORY, "31355")).isEmpty();
        assertThat(client.findClosedPulls("pat", REPOSITORY, "9")).isEmpty();
        // Search syntax in the typed words is split into quoted words, so the repository and state qualifiers cannot be replaced.
        assertThat(client.findClosedPulls("pat", REPOSITORY, "checkpoint \"is:open\" (null)"))
                .containsExactly(new GitHubHarvestClient.ClosedPull(31200, "Checkpoint null counts", Instant.parse("2026-09-01T00:00:00Z"), false));
        assertThat(client.findClosedPulls("pat", REPOSITORY, " :: ")).isEmpty();
    }

    @Test
    void findsAPullRequestByItsLinkOnlyInTheMonitoredRepository()
    {
        for (int read = 0; read < 2; read++) {
            server.expect(requestTo("https://api.github.test/repos/owner/repository/issues/28755")).andRespond(withSuccess("""
                    {"id":1,"number":28755,"title":"Add table credentials","closed_at":"2026-08-20T00:00:00Z","pull_request":{"merged_at":"2026-08-20T00:00:00Z"}}
                    """, MediaType.APPLICATION_JSON));
        }
        GitHubHarvestClient.ClosedPull expected = new GitHubHarvestClient.ClosedPull(28755, "Add table credentials", Instant.parse("2026-08-20T00:00:00Z"), true);

        assertThat(client.findClosedPulls("pat", REPOSITORY, "https://github.com/owner/repository/pull/28755")).containsExactly(expected);
        assertThat(client.findClosedPulls("pat", REPOSITORY, " github.com/Owner/Repository/pull/28755/files#diff-1 ")).containsExactly(expected);
        assertThatThrownBy(() -> client.findClosedPulls("pat", REPOSITORY, "https://github.com/apache/iceberg/pull/28755"))
                .hasMessage("Only a pull request of owner/repository, the repository this workspace learns from, can be learned from");
    }

    @Test
    void readsAClosedPullRequestsThreadsReviewsAndCommentsWithTheirAuthorsRoles()
    {
        graphServer.expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess("""
                {"data":{"repository":{"pullRequest":{"number":31201,"title":"Add a property","url":"https://github.com/owner/repository/pull/31201","merged":true,
                  "closedAt":"2026-09-20T10:00:00Z",
                  "reviewThreads":{"nodes":[{"id":"thread-1","isResolved":true,"isOutdated":false,"path":"plugin/x/A.java","comments":{"nodes":[
                    {"id":"note-1","body":"Please document it.","url":"https://github.com/owner/repository/pull/31201#discussion_r1","authorAssociation":"MEMBER",
                     "author":{"__typename":"User","login":"bo"},"createdAt":"2026-09-19T10:00:00Z","pullRequestReview":{"authorCanPushToRepository":true}},
                    {"id":"note-2","body":"Coverage ok","url":"u","authorAssociation":"NONE","author":{"__typename":"Bot","login":"codecov"},
                     "createdAt":"2026-09-19T11:00:00Z","pullRequestReview":{"authorCanPushToRepository":false}}]}}]},
                  "reviews":{"nodes":[
                    {"id":"review-1","body":"Looks good","url":"u","authorAssociation":"OWNER","author":{"__typename":"User","login":"dana"},
                     "submittedAt":"2026-09-19T12:00:00Z","authorCanPushToRepository":true},
                    {"id":"review-2","body":"Consider a test","url":"u","authorAssociation":"MEMBER","author":{"__typename":"User","login":"eve"},
                     "submittedAt":"2026-09-19T12:30:00Z","authorCanPushToRepository":false}]},
                  "comments":{"nodes":[
                    {"id":"comment-1","body":"Thanks","url":"u","authorAssociation":"CONTRIBUTOR","author":null,"createdAt":"2026-09-19T13:00:00Z"},
                    {"id":"comment-2","body":"Please squash","url":"u","authorAssociation":"OWNER","author":{"__typename":"User","login":"dana"},
                     "createdAt":"2026-09-19T14:00:00Z"},
                    {"id":"comment-3","body":"Nit: rename it","url":"u","authorAssociation":"MEMBER","author":{"__typename":"User","login":"eve"},
                     "createdAt":"2026-09-19T15:00:00Z"}]}
                }}}}
                """, MediaType.APPLICATION_JSON));

        GitHubHarvestClient.PullDiscussion discussion = client.discussion("pat", REPOSITORY, 31201).orElseThrow();

        assertThat(discussion.merged()).isTrue();
        GitHubHarvestClient.ReviewThread thread = discussion.threads().getFirst();
        assertThat(thread.resolved()).isTrue();
        assertThat(thread.path()).contains("plugin/x/A.java");
        assertThat(thread.comments()).extracting(GitHubHarvestClient.Note::maintainer).containsExactly(true, false);
        assertThat(thread.comments()).extracting(GitHubHarvestClient.Note::bot).containsExactly(false, true);
        // A maintainer can push; an organization member who can only triage is a MEMBER too, and is not one.
        assertThat(discussion.reviews()).extracting(GitHubHarvestClient.Note::maintainer).containsExactly(true, false);
        assertThat(discussion.comments()).extracting(GitHubHarvestClient.Note::author, GitHubHarvestClient.Note::maintainer)
                .containsExactly(tuple("ghost", false), tuple("dana", true), tuple("eve", false));
    }

    @Test
    void readsAMergedPullRequestsReviewHeadsAndThreadLinesForAReplay()
    {
        graphServer.expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess("""
                {"data":{"repository":{"pullRequest":{"number":31201,"title":"Add a property","url":"https://github.com/owner/repository/pull/31201","merged":true,
                  "baseRefName":"master","author":{"__typename":"User","login":"alice"},"mergeCommit":{"oid":"%s"},
                  "reviews":{"nodes":[
                    {"author":{"__typename":"User","login":"bo"},"authorCanPushToRepository":true,"state":"COMMENTED","submittedAt":"2026-09-19T10:00:00Z",
                     "commit":{"oid":"%s"}},
                    {"author":{"__typename":"User","login":"dana"},"authorCanPushToRepository":true,"state":"PENDING","submittedAt":null,"commit":null},
                    {"author":{"__typename":"User","login":"cy"},"authorCanPushToRepository":false,"state":"APPROVED","submittedAt":"2026-09-20T10:00:00Z",
                     "commit":null}]},
                  "reviewThreads":{"nodes":[
                    {"path":"plugin/x/A.java","diffSide":"RIGHT","line":null,"startLine":null,"originalLine":14,"originalStartLine":12,"comments":{"nodes":[
                      {"id":"note-1","body":"Please document it.","url":"u","author":{"__typename":"User","login":"bo"},
                       "createdAt":"2026-09-19T10:00:00Z","originalCommit":{"oid":"%s"},"pullRequestReview":{"authorCanPushToRepository":true}}]}},
                    {"path":null,"diffSide":"RIGHT","comments":{"nodes":[]}}]}
                }}}}
                """.formatted("m".repeat(40), "a".repeat(40), "a".repeat(40)), MediaType.APPLICATION_JSON));

        GitHubHarvestClient.PullReplay replay = client.replay("pat", REPOSITORY, 31201).orElseThrow();

        assertThat(replay.mergeCommit()).contains("m".repeat(40));
        assertThat(replay.baseRef()).isEqualTo("master");
        assertThat(replay.reviews()).extracting(GitHubHarvestClient.SubmittedReview::author).containsExactly("bo", "cy");
        assertThat(replay.reviews().getFirst().commit()).contains("a".repeat(40));
        assertThat(replay.reviews().getLast().commit()).isEmpty();
        assertThat(replay.reviews()).extracting(GitHubHarvestClient.SubmittedReview::maintainer).containsExactly(true, false);
        GitHubHarvestClient.ThreadStart thread = replay.threads().getFirst();
        assertThat(replay.threads()).hasSize(1);
        assertThat(thread.line()).isEmpty();
        assertThat(thread.originalLine()).contains(14);
        assertThat(thread.originalStartLine()).contains(12);
        assertThat(thread.originalCommit()).contains("a".repeat(40));
        assertThat(thread.comment().maintainer()).isTrue();
    }

    @Test
    void anOpenPullRequestHasNothingToLearnAndARateLimitStops()
    {
        graphServer.expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess("""
                {"data":{"repository":{"pullRequest":{"number":5,"title":"Open","url":"u","merged":false,"closedAt":null,
                  "reviewThreads":{"nodes":[]},"reviews":{"nodes":[]},"comments":{"nodes":[]}}}}}
                """, MediaType.APPLICATION_JSON));
        graphServer.expect(requestTo("https://api.github.test/graphql")).andRespond(withSuccess("""
                {"errors":[{"type":"RATE_LIMITED","message":"API rate limit exceeded"}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.discussion("pat", REPOSITORY, 5)).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> client.discussion("pat", REPOSITORY, 6)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rate limit");
    }
}
