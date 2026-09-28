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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubRepoClient
{
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repo");

    @Test
    void readsExactIssueActivityAfterAWrite()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("""
                        {"number":7,"comments":5,"updated_at":"2026-09-16T00:00:01Z"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchIssueActivity("pat", REPOSITORY, 7))
                .isEqualTo(new GitHubRepoClient.IssueActivity(5, Instant.parse("2026-09-16T00:00:01Z")));
        fixture.server().verify();
    }

    @Test
    void fetchesFreshIssueSourceAndRefusesClosedOrPullRequestSources()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("""
                        {"id":70,"number":7,"state":"open","title":"Fix selection","body":"Description","updated_at":"2026-09-16T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));
        assertThat(fixture.client().fetchOpenIssueSource("pat", REPOSITORY, 7)).satisfies(source -> {
            assertThat(source.issueId()).isEqualTo(70);
            assertThat(source.body()).isEqualTo("Description");
            assertThat(source.url()).isEqualTo("https://github.com/owner/repo/issues/7");
        });
        fixture.server().verify();
        fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("{\"id\":70,\"number\":7,\"state\":\"closed\"}", MediaType.APPLICATION_JSON));
        GitHubRepoClient client = fixture.client();
        assertThatThrownBy(() -> client.fetchOpenIssueSource("pat", REPOSITORY, 7)).isInstanceOf(AgentConflictException.class);
        fixture.server().verify();
        Fixture pull = fixture();
        pull.server().expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("{\"id\":70,\"number\":7,\"state\":\"open\",\"pull_request\":{\"url\":\"pull\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> pull.client().fetchOpenIssueSource("pat", REPOSITORY, 7)).isInstanceOf(AgentConflictException.class);
        pull.server().verify();
    }

    @Test
    void fetchesContributorLabelAndOverviewProjections()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/contributors?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":1,"login":"alice","avatar_url":"avatar","type":"User"}]
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/labels?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":2,"name":"bug","color":"ff0000"}]
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo"))
                .andRespond(withSuccess("""
                        {"description":"Description","stargazers_count":7,"topics":["java","github"]}
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/languages"))
                .andRespond(withSuccess("""
                        {"Java":100}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchContributors("pat", REPOSITORY))
                .extracting(RepositoryContributor::id)
                .containsExactly(1L);
        assertThat(fixture.client().fetchLabels("pat", REPOSITORY))
                .extracting(RepositoryLabel::id)
                .containsExactly(2L);
        assertThat(fixture.client().fetchOverview("pat", REPOSITORY))
                .satisfies(overview -> {
                    assertThat(overview.description()).isEqualTo("Description");
                    assertThat(overview.stargazersCount()).isEqualTo(7);
                    assertThat(overview.topics()).containsExactly("java", "github");
                    assertThat(overview.languages()).containsEntry("Java", 100L);
                });
        fixture.server().verify();
    }

    @Test
    void fetchesOpenIssuesAndExcludesPullRequestsFromGitHubIssuesEndpoint()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/issues?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":10,"number":7,"title":"Issue","body":"Details",
                          "user":{"login":"alice","avatar_url":"avatar"},
                          "html_url":"https://github.test/owner/repo/issues/7",
                          "labels":[{"name":"bug"}],"assignees":[{"login":"bob"}],
                          "comments":2,"created_at":"2026-08-20T00:00:00Z",
                          "updated_at":"2026-08-21T00:00:00Z"},
                         {"id":11,"number":8,"title":"PR","pull_request":{"url":"pull"},
                          "created_at":"2026-08-20T00:00:00Z","updated_at":"2026-08-21T00:00:00Z"}]
                        """, MediaType.APPLICATION_JSON));

        GitHubRepoClient.IssuePage page = fixture.client().fetchOpenIssuesPage("pat", REPOSITORY, 1);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.issues()).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.id()).isEqualTo(10);
                    assertThat(issue.labels()).containsExactly("bug");
                    assertThat(issue.assignees()).containsExactly("bob");
                });
        fixture.server().verify();
    }

    @Test
    void previewsAClosedIssueDirectlyByNumber()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/issues/30611"))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess("""
                        {"id":10,"number":30611,"title":"Kerberos container times out","body":"Issue details",
                         "state":"closed","user":{"login":"alice"},"labels":[{"name":"bug"}],
                         "updated_at":"2026-08-21T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        GitHubIssuePreview preview = fixture.client().fetchIssuePreview("pat", REPOSITORY, 30611);

        assertThat(preview.title()).isEqualTo("Kerberos container times out");
        assertThat(preview.body()).isEqualTo("Issue details");
        assertThat(preview.author()).isEqualTo("alice");
        assertThat(preview.labels()).containsExactly("bug");
        assertThat(preview.updatedAt()).isEqualTo(Instant.parse("2026-08-21T00:00:00Z"));
        assertThat(preview.state()).isEqualTo("closed");
        assertThat(preview.pullRequest()).isFalse();
        fixture.server().verify();
    }

    @Test
    void identifiesPullRequestsReturnedByTheIssuesEndpoint()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/issues/8"))
                .andRespond(withSuccess("""
                        {"title":"Pull request","state":"open","updated_at":"2026-08-21T00:00:00Z",
                         "pull_request":{"url":"https://api.github.test/repos/owner/repo/pulls/8"}}
                        """, MediaType.APPLICATION_JSON));

        GitHubIssuePreview preview = fixture.client().fetchIssuePreview("pat", REPOSITORY, 8);

        assertThat(preview.pullRequest()).isTrue();
        assertThat(preview.state()).isEqualTo("open");
        assertThat(preview.body()).isEmpty();
        assertThat(preview.author()).isEqualTo("unknown");
        assertThat(preview.labels()).isEmpty();
        fixture.server().verify();
    }

    @Test
    void preservesGitHubNotFoundForIssuePreviews()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> fixture.client().fetchIssuePreview("pat", REPOSITORY, 7))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        fixture.server().verify();
    }

    @Test
    void rejectsIncompleteIssuePreviews()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().fetchIssuePreview("pat", REPOSITORY, 7))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        fixture.server().verify();
    }

    @Test
    void fetchesCommitAvatarsBySha()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits?sha=main&per_page=2&page=1"))
                .andRespond(withSuccess("""
                        [{"sha":"abc",
                          "author":{"login":"percy-r","avatar_url":"https://avatars/percy-r"},
                          "committer":{"login":"ezra-b","avatar_url":"https://avatars/ezra-b"}},
                         {"sha":"def","author":null,"committer":null}]
                        """, MediaType.APPLICATION_JSON));

        CommitAvatarSnapshot snapshot = fixture.client().fetchCommitAvatarSnapshot(
                "pat", REPOSITORY, "main", 2);

        assertThat(snapshot.succeeded()).isTrue();
        assertThat(snapshot.avatars()).containsExactly(
                new CommitAvatarSnapshot.CommitAvatar(
                        "abc",
                        Optional.of("percy-r"),
                        Optional.of("https://avatars/percy-r"),
                        Optional.of("ezra-b"),
                        Optional.of("https://avatars/ezra-b")));
        fixture.server().verify();
    }

    @Test
    void reportsCommitAvatarProviderFailure()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits?sha=main&per_page=2&page=1"))
                .andRespond(withServerError());

        CommitAvatarSnapshot snapshot = fixture.client().fetchCommitAvatarSnapshot(
                "pat", REPOSITORY, "main", 2);

        assertThat(snapshot.succeeded()).isFalse();
        assertThat(snapshot.avatars()).isEmpty();
        fixture.server().verify();
    }

    @Test
    void keepsAvatarPaginationStableAndCapsTheLastPage()
    {
        Fixture fixture = fixture();
        String firstPage = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> "{\"sha\":\"commit-" + index + "\",\"author\":{\"login\":\"alice\"}}")
                .collect(joining(",", "[", "]"));
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits?sha=main&per_page=100&page=1"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo(
                        "https://api.github.test/repos/owner/repo/commits?sha=main&per_page=100&page=2"))
                .andRespond(withSuccess("""
                        [{"sha":"commit-101","author":{"login":"alice"}},
                         {"sha":"commit-102","author":{"login":"alice"}}]
                        """, MediaType.APPLICATION_JSON));

        CommitAvatarSnapshot snapshot = fixture.client().fetchCommitAvatarSnapshot("pat", REPOSITORY, "main", 101);

        assertThat(snapshot.succeeded()).isTrue();
        assertThat(snapshot.avatars()).extracting(CommitAvatarSnapshot.CommitAvatar::sha)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 101).mapToObj(index -> "commit-" + index).toList());
        fixture.server().verify();
    }

    @Test
    void fetchesCurrentAndAvailableIssueTypes()
    {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
                .andRespond(withSuccess("""
                        {"id":10,"number":7,"title":"Issue","type":{"name":"Task"}}
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo("https://api.github.test/repos/owner/repo/issue-types"))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
                .andRespond(withSuccess("""
                        [{"id":1,"name":"Task"},{"id":2,"name":"Bug"}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client().fetchIssueType("pat", REPOSITORY, 7)).isEqualTo("Task");
        assertThat(fixture.client().fetchIssueTypes("pat", REPOSITORY)).containsExactly("Task", "Bug");
        fixture.server().verify();
    }

    @Test
    void historySearchQuotesPlainWordsAndRetainsClosureAndCompletenessObservations()
    {
        Fixture fixture = fixture();
        fixture.server().expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/search/issues");
            assertThat(request.getURI().getQuery()).isEqualTo("q=repo:owner/repo is:issue is:closed in:title,body \"engine/cache\" \"OR\""
                    + "&sort=updated&order=desc&per_page=20&page=1");
        }).andExpect(header("Authorization", "Bearer pat")).andRespond(withSuccess("""
                {"total_count":40,"incomplete_results":true,"items":[
                  {"id":10,"number":7,"title":"Closed issue","body":"Not pursued","state":"closed","state_reason":"not_planned",
                   "html_url":"https://github.com/owner/repo/issues/7","repository_url":"https://api.github.com/repos/owner/repo",
                   "updated_at":"2026-08-21T00:00:00Z","closed_at":"2026-08-20T00:00:00Z"}]}
                """, MediaType.APPLICATION_JSON));
        GitHubSearchResponse result = fixture.client().searchHistory("pat", REPOSITORY, false, "engine/cache OR");
        assertThat(result.totalCount()).isEqualTo(40);
        assertThat(result.incompleteResults()).isTrue();
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.stateReason()).isEqualTo("not_planned");
            assertThat(item.body()).isEqualTo("Not pursued");
            assertThat(item.closedAt()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
            assertThat(item.pullRequest()).isNull();
        });
        fixture.server().verify();
    }

    @Test
    void mergedHistorySearchUsesFixedQualifiersAndPreservesMissingCompleteness()
    {
        Fixture fixture = fixture();
        fixture.server().expect(request -> assertThat(request.getURI().getQuery())
                        .isEqualTo("q=repo:owner/repo is:pr is:merged in:title,body&sort=updated&order=desc&per_page=20&page=1"))
                .andRespond(withSuccess("""
                        {"total_count":1,"items":[{"id":10,"number":7,"title":"Merged PR","state":"closed",
                        "updated_at":"2026-08-21T00:00:00Z","pull_request":{"merged_at":"2026-08-20T00:00:00Z"}}]}
                        """, MediaType.APPLICATION_JSON));
        GitHubSearchResponse result = fixture.client().searchHistory("pat", REPOSITORY, true, "");
        assertThat(result.incompleteResults()).isNull();
        assertThat(result.items().getFirst().pullRequest().mergedAt()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
        fixture.server().verify();
    }

    @Test
    void historySearchRejectsRawQualifiersAndQuoteEscapesWithoutSendingRequests()
    {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> fixture.client().searchHistory("pat", REPOSITORY, false, "repo:other/repo"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain words");
        assertThatThrownBy(() -> fixture.client().searchHistory("pat", REPOSITORY, true, "word\" OR is:open"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain words");
        fixture.server().verify();
    }

    private static Fixture fixture()
    {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new GitHubRepoClient(builder.build()), server);
    }

    private record Fixture(GitHubRepoClient client, MockRestServiceServer server)
    {
        private Fixture
        {
            java.util.Objects.requireNonNull(client, "client is null");
            java.util.Objects.requireNonNull(server, "server is null");
        }
    }
}
