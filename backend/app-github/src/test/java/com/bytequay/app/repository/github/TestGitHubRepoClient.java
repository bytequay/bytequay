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
import com.bytequay.core.domain.GitHubIssuePreview;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
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
        extends AbstractGitHubClientTest
{
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repo");

    private final GitHubRepoClient client = new GitHubRepoClient(restClient);

    @Test
    void readsExactIssueActivityAfterAWrite()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("""
                        {"number":7,"comments":5,"updated_at":"2026-09-16T00:00:01Z"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchIssueActivity("pat", REPOSITORY, 7))
                .isEqualTo(new GitHubRepoClient.IssueActivity(5, Instant.parse("2026-09-16T00:00:01Z")));
    }

    @Test
    void fetchesFreshIssueSource()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("""
                        {"id":70,"number":7,"state":"open","title":"Fix selection","body":"Description","updated_at":"2026-09-16T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.fetchOpenIssueSource("pat", REPOSITORY, 7)).satisfies(source -> {
            assertThat(source.issueId()).isEqualTo(70);
            assertThat(source.body()).isEqualTo("Description");
            assertThat(source.url()).isEqualTo("https://github.com/owner/repo/issues/7");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":70,\"number\":7,\"state\":\"closed\"}",
            "{\"id\":70,\"number\":7,\"state\":\"open\",\"pull_request\":{\"url\":\"pull\"}}"})
    void refusesClosedOrPullRequestSources(String response)
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.fetchOpenIssueSource("pat", REPOSITORY, 7)).isInstanceOf(AgentConflictException.class);
    }

    @Test
    void fetchesContributorLabelAndOverviewProjections()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/contributors?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":1,"login":"alice","avatar_url":"avatar","type":"User"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/labels?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":2,"name":"bug","color":"ff0000"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repo"))
                .andRespond(withSuccess("""
                        {"description":"Description","stargazers_count":7,"topics":["java","github"]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/languages"))
                .andRespond(withSuccess("""
                        {"Java":100}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchContributors("pat", REPOSITORY))
                .extracting(RepositoryContributor::id)
                .containsExactly(1L);
        assertThat(client.fetchLabels("pat", REPOSITORY))
                .extracting(RepositoryLabel::id)
                .containsExactly(2L);
        assertThat(client.fetchOverview("pat", REPOSITORY))
                .satisfies(overview -> {
                    assertThat(overview.description()).isEqualTo("Description");
                    assertThat(overview.stargazersCount()).isEqualTo(7);
                    assertThat(overview.topics()).containsExactly("java", "github");
                    assertThat(overview.languages()).containsEntry("Java", 100L);
                });
    }

    @Test
    void fetchesOpenIssuesAndExcludesPullRequestsFromGitHubIssuesEndpoint()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues?per_page=100&page=1"))
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

        GitHubRepoClient.IssuePage page = client.fetchOpenIssuesPage("pat", REPOSITORY, 1);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.issues()).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.id()).isEqualTo(10);
                    assertThat(issue.labels()).containsExactly("bug");
                    assertThat(issue.assignees()).containsExactly("bob");
                });
    }

    @Test
    void readsOneNotifiedIssueCardAndRecognizesClosure()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("""
                        {"id":10,"number":7,"state":"open","title":"Updated issue","body":"Details",
                         "assignees":[{"login":"alice"}],"comments":3,
                         "created_at":"2026-08-20T00:00:00Z","updated_at":"2026-08-21T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("{\"number\":7,\"state\":\"closed\"}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchIssue("pat", REPOSITORY, 7)).get().satisfies(issue -> {
            assertThat(issue.title()).isEqualTo("Updated issue");
            assertThat(issue.assignees()).containsExactly("alice");
            assertThat(issue.commentCount()).isEqualTo(3);
        });
        assertThat(client.fetchIssue("pat", REPOSITORY, 7)).isEmpty();
    }

    @Test
    void incrementalIssuesReadAllStatesSinceCheckpointAndExcludePullRequests()
    {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/repos/owner/repo/issues");
            assertThat(request.getURI().getQuery()).isEqualTo("state=all&sort=updated&direction=asc&since=2026-08-20T00:00:00Z&per_page=100&page=2");
        }).andRespond(withSuccess("""
                [{"id":10,"number":7,"state":"open","title":"Updated issue","created_at":"2026-08-20T00:00:00Z","updated_at":"2026-08-21T00:00:00Z"},
                 {"number":8,"state":"closed"},
                 {"number":9,"state":"open","pull_request":{}}]
                """, MediaType.APPLICATION_JSON));

        GitHubRepoClient.ChangedIssuePage result = client.fetchChangedIssuesPage("pat", REPOSITORY, Instant.parse("2026-08-20T00:00:00Z"), 2);

        assertThat(result.openIssues()).singleElement().satisfies(issue -> assertThat(issue.number()).isEqualTo(7));
        assertThat(result.closedNumbers()).containsExactly(8);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void incompleteIncrementalIssueFailsRatherThanLosingItBehindAnAdvancedCheckpoint()
    {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/repos/owner/repo/issues"))
                .andRespond(withSuccess("[{\"number\":7,\"state\":\"open\"}]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchChangedIssuesPage("pat", REPOSITORY, Instant.EPOCH, 1))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void previewsAClosedIssueDirectlyByNumber()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/30611"))
                .andExpect(header("Authorization", "Bearer pat"))
                .andRespond(withSuccess("""
                        {"id":10,"number":30611,"title":"Kerberos container times out","body":"Issue details",
                         "state":"closed","user":{"login":"alice"},"labels":[{"name":"bug"}],
                         "updated_at":"2026-08-21T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        GitHubIssuePreview preview = client.fetchIssuePreview("pat", REPOSITORY, 30611);

        assertThat(preview.title()).isEqualTo("Kerberos container times out");
        assertThat(preview.body()).isEqualTo("Issue details");
        assertThat(preview.author()).isEqualTo("alice");
        assertThat(preview.labels()).containsExactly("bug");
        assertThat(preview.updatedAt()).isEqualTo(Instant.parse("2026-08-21T00:00:00Z"));
        assertThat(preview.state()).isEqualTo("closed");
        assertThat(preview.pullRequest()).isFalse();
    }

    @Test
    void identifiesPullRequestsReturnedByTheIssuesEndpoint()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/8"))
                .andRespond(withSuccess("""
                        {"title":"Pull request","state":"open","updated_at":"2026-08-21T00:00:00Z",
                         "pull_request":{"url":"https://api.github.test/repos/owner/repo/pulls/8"}}
                        """, MediaType.APPLICATION_JSON));

        GitHubIssuePreview preview = client.fetchIssuePreview("pat", REPOSITORY, 8);

        assertThat(preview.pullRequest()).isTrue();
        assertThat(preview.state()).isEqualTo("open");
        assertThat(preview.body()).isEmpty();
        assertThat(preview.author()).isEqualTo("unknown");
        assertThat(preview.labels()).isEmpty();
    }

    @Test
    void preservesGitHubNotFoundForIssuePreviews()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchIssuePreview("pat", REPOSITORY, 7))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void rejectsIncompleteIssuePreviews()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchIssuePreview("pat", REPOSITORY, 7))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void fetchesCommitAvatarsBySha()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/commits?sha=main&per_page=2&page=1"))
                .andRespond(withSuccess("""
                        [{"sha":"abc",
                          "author":{"login":"percy-r","avatar_url":"https://avatars/percy-r"},
                          "committer":{"login":"ezra-b","avatar_url":"https://avatars/ezra-b"}},
                         {"sha":"def","author":null,"committer":null}]
                        """, MediaType.APPLICATION_JSON));

        CommitAvatarSnapshot snapshot = client.fetchCommitAvatarSnapshot("pat", REPOSITORY, "main", 2);

        assertThat(snapshot.succeeded()).isTrue();
        assertThat(snapshot.avatars()).containsExactly(
                new CommitAvatarSnapshot.CommitAvatar(
                        "abc",
                        Optional.of("percy-r"),
                        Optional.of("https://avatars/percy-r"),
                        Optional.of("ezra-b"),
                        Optional.of("https://avatars/ezra-b")));
    }

    @Test
    void reportsCommitAvatarProviderFailure()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/commits?sha=main&per_page=2&page=1"))
                .andRespond(withServerError());

        CommitAvatarSnapshot snapshot = client.fetchCommitAvatarSnapshot("pat", REPOSITORY, "main", 2);

        assertThat(snapshot.succeeded()).isFalse();
        assertThat(snapshot.avatars()).isEmpty();
    }

    @Test
    void keepsAvatarPaginationStableAndCapsTheLastPage()
    {
        String firstPage = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> "{\"sha\":\"commit-" + index + "\",\"author\":{\"login\":\"alice\"}}")
                .collect(joining(",", "[", "]"));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/commits?sha=main&per_page=100&page=1"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/commits?sha=main&per_page=100&page=2"))
                .andRespond(withSuccess("""
                        [{"sha":"commit-101","author":{"login":"alice"}},
                         {"sha":"commit-102","author":{"login":"alice"}}]
                        """, MediaType.APPLICATION_JSON));

        CommitAvatarSnapshot snapshot = client.fetchCommitAvatarSnapshot("pat", REPOSITORY, "main", 101);

        assertThat(snapshot.succeeded()).isTrue();
        assertThat(snapshot.avatars()).extracting(CommitAvatarSnapshot.CommitAvatar::sha)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 101).mapToObj(index -> "commit-" + index).toList());
    }

    @Test
    void fetchesCurrentAndAvailableIssueTypes()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7"))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
                .andRespond(withSuccess("""
                        {"id":10,"number":7,"title":"Issue","type":{"name":"Task"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issue-types"))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
                .andRespond(withSuccess("""
                        [{"id":1,"name":"Task"},{"id":2,"name":"Bug"}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchIssueType("pat", REPOSITORY, 7)).isEqualTo("Task");
        assertThat(client.fetchIssueTypes("pat", REPOSITORY)).containsExactly("Task", "Bug");
    }

    @Test
    void reportSearchQuotesPlainWordsAndRetainsClosureAndCompletenessObservations()
    {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/search/issues");
            assertThat(request.getURI().getQuery()).isEqualTo("q=repo:owner/repo in:title,body \"engine/cache\" \"OR\"" + "&sort=updated&order=desc&per_page=10&page=1");
        }).andExpect(header("Authorization", "Bearer pat")).andRespond(withSuccess("""
                {"total_count":40,"incomplete_results":true,"items":[
                  {"id":10,"number":7,"title":"Closed issue","body":"Not pursued","state":"closed","state_reason":"not_planned",
                   "html_url":"https://github.com/owner/repo/issues/7","repository_url":"https://api.github.com/repos/owner/repo",
                   "updated_at":"2026-08-21T00:00:00Z","closed_at":"2026-08-20T00:00:00Z"}]}
                """, MediaType.APPLICATION_JSON));
        GitHubSearchResponse result = client.searchReports("pat", REPOSITORY, "engine/cache OR");
        assertThat(result.totalCount()).isEqualTo(40);
        assertThat(result.incompleteResults()).isTrue();
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.stateReason()).isEqualTo("not_planned");
            assertThat(item.body()).isEqualTo("Not pursued");
            assertThat(item.closedAt()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
            assertThat(item.pullRequest()).isNull();
        });
    }

    @Test
    void authoredIssueSearchAsksForOpenIssuesUpdatedInTheWindowAndReadsAssigneesAndComments()
    {
        server.expect(request -> assertThat(request.getURI().getQuery())
                        .isEqualTo("q=is:issue is:open archived:false author:viewer updated:>=2026-09-25&sort=updated&order=desc&per_page=100"))
                .andRespond(withSuccess("""
                        {"total_count":1,"items":[{"id":10,"number":42,"title":"Document a limit","state":"open",
                        "repository_url":"https://api.github.com/repos/owner/repo","html_url":"https://github.com/owner/repo/issues/42",
                        "created_at":"2026-10-20T00:00:00Z","updated_at":"2026-10-21T00:00:00Z",
                        "assignees":[{"login":"maintainer"}],"comments":3}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.searchAuthoredIssues("pat", "viewer", LocalDate.parse("2026-09-25")).items()).singleElement().satisfies(item -> {
            assertThat(item.assignees()).extracting(GitHubUser::login).containsExactly("maintainer");
            assertThat(item.comments()).isEqualTo(3);
        });
        assertThatThrownBy(() -> client.searchAuthoredIssues("pat", "viewer is:pr", LocalDate.parse("2026-09-25")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid GitHub login");
    }

    @Test
    void reportSearchRetainsMergedPullRequestsAndMissingCompleteness()
    {
        server.expect(request -> assertThat(request.getURI().getQuery())
                        .isEqualTo("q=repo:owner/repo in:title,body \"Merged\"&sort=updated&order=desc&per_page=10&page=1"))
                .andRespond(withSuccess("""
                        {"total_count":1,"items":[{"id":10,"number":7,"title":"Merged PR","state":"closed",
                        "updated_at":"2026-08-21T00:00:00Z","pull_request":{"merged_at":"2026-08-20T00:00:00Z"}}]}
                        """, MediaType.APPLICATION_JSON));
        GitHubSearchResponse result = client.searchReports("pat", REPOSITORY, "Merged");
        assertThat(result.incompleteResults()).isNull();
        assertThat(result.items().getFirst().pullRequest().mergedAt()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
    }

    @Test
    void reportSearchFindsIssuesAndPullRequestsInAnyState()
    {
        server.expect(request -> assertThat(request.getURI().getQuery())
                        .isEqualTo("q=repo:owner/repo in:title,body \"deletion\" \"vector\" \"checkpoint\"&sort=updated&order=desc&per_page=10&page=1"))
                .andRespond(withSuccess("""
                        {"total_count":1,"items":[{"id":10,"number":31252,"title":"Checkpoint loses a remove","state":"open",
                        "updated_at":"2026-08-21T00:00:00Z","html_url":"https://github.com/owner/repo/issues/31252"}]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.searchReports("pat", REPOSITORY, "deletion vector checkpoint").items()).singleElement()
                .satisfies(item -> assertThat(item.number()).isEqualTo(31252));
        assertThatThrownBy(() -> client.searchReports("pat", REPOSITORY, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Name the words");
    }

    @Test
    void reportSearchRejectsRawQualifiersAndQuoteEscapesWithoutSendingRequests()
    {
        assertThatThrownBy(() -> client.searchReports("pat", REPOSITORY, "repo:other/repo"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain words");
        assertThatThrownBy(() -> client.searchReports("pat", REPOSITORY, "word\" OR is:open"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain words");
    }

    @Test
    void readsSubIssuesOfEveryRepositoryTheyLiveIn()
    {
        server.expect(requestTo("https://api.github.test/repos/owner/repo/issues/7/sub_issues?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":71,"number":8,"title":"Parse","state":"open","html_url":"https://github.com/owner/repo/issues/8",
                          "repository_url":"https://api.github.test/repos/owner/repo"},
                         {"id":72,"number":3,"title":"Docs","state":"closed","repository_url":"https://api.github.test/repos/other/docs"},
                         {"id":73,"number":4,"title":null,"state":"open"}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchSubIssues("pat", REPOSITORY, 7)).containsExactly(
                new GitHubRepoClient.SubIssue(71, 8, "Parse", false, Optional.of("https://github.com/owner/repo/issues/8"), REPOSITORY),
                new GitHubRepoClient.SubIssue(72, 3, "Docs", true, Optional.empty(), RepoRef.of("other", "docs")));
    }
}
