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

import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoIssueIntakePage;
import com.bytequay.app.domain.RepoIssuePage;
import com.bytequay.app.domain.RepoRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestGitHubClientIssueIntake
{
    @Test
    void recoveryPageIncludesClosedIssuesAndExcludesPullRequests()
    {
        RestClient.Builder restBuilder = RestClient.builder()
                .baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(restBuilder).build();
        GitHubClient client = new GitHubClient(
                restBuilder.build(),
                RestClient.builder().baseUrl("https://graphql.test").build());
        server.expect(requestTo("https://api.github.test/repos/acme/widget/issues"
                        + "?state=all&sort=created&direction=desc&page=2&per_page=100"))
                .andRespond(withSuccess("""
                        [
                          {"id": 13, "number": 13, "title": "PR", "state": "open",
                           "comments": 0, "pull_request": {}, "labels": []},
                          {"id": 12, "number": 12, "title": "Open", "state": "open",
                           "comments": 0, "labels": []},
                          {"id": 11, "number": 11, "title": "Retitled", "state": "closed",
                           "comments": 0, "labels": []}
                        ]
                        """, MediaType.APPLICATION_JSON));

        RepoIssuePage page = client.fetchRepoIssuePage(
                "pat", RepoRef.of("acme", "widget"), 2, 100);

        assertThat(page.hasMore()).isFalse();
        assertThat(page.issues()).extracting(RepoIssue::number)
                .containsExactly(12, 11);
        server.verify();
    }

    @Test
    void creationPageKeepsSharedBoundaryButReturnsOnlyOpenIssues()
    {
        RestClient.Builder restBuilder = RestClient.builder().baseUrl("https://api.github.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restBuilder).build();
        GitHubClient client = new GitHubClient(
                restBuilder.build(), RestClient.builder().baseUrl("https://graphql.test").build());
        server.expect(requestTo("https://api.github.test/repos/acme/widget/issues"
                        + "?state=all&sort=created&direction=desc&page=1&per_page=100"))
                .andRespond(withSuccess("""
                        [
                          {"id": 13, "number": 13, "title": "PR", "state": "open",
                           "comments": 0, "pull_request": {}, "labels": []},
                          {"id": 12, "number": 12, "title": "Open", "state": "open",
                           "comments": 0, "labels": []},
                          {"id": 11, "number": 11, "title": "Closed", "state": "closed",
                           "comments": 0, "labels": []}
                        ]
                        """, MediaType.APPLICATION_JSON));

        RepoIssueIntakePage page = client.fetchRepoIssueIntakePage(
                "pat", RepoRef.of("acme", "widget"), 1, 100);

        assertThat(page.newestNumber()).isEqualTo(13);
        assertThat(page.oldestNumber()).isEqualTo(11);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.openIssues()).extracting(RepoIssue::number).containsExactly(12);
        server.verify();
    }
}
