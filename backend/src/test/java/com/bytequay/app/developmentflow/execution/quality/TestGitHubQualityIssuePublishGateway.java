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
package com.bytequay.app.developmentflow.execution.quality;

import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Status;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoIssuePage;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubQualityIssuePublishGateway
{
    private static final String PAT = "pat";
    private static final String MARKER = "<!-- quality-operation:operation-1 -->";
    private static final RepoRef REPO = RepoRef.of("acme", "widget");

    private PullRequestRepository github;
    private GitHubQualityIssuePublishGateway gateway;

    @BeforeEach
    void setUp()
    {
        github = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        when(pats.resolve("acme/widget")).thenReturn(PAT);
        gateway = new GitHubQualityIssuePublishGateway(github, pats);
    }

    @Test
    void markerFindsAClosedRetitledIssue()
    {
        RepoIssue retitled = issue(17, "A user changed this title", "closed");
        when(github.fetchRepoIssuePage(PAT, REPO, 1, 100))
                .thenReturn(new RepoIssuePage(List.of(retitled), false));
        when(github.fetchIssueDetail(PAT, REPO, 17))
                .thenReturn(detail(retitled, MARKER));

        assertThat(gateway.findExisting(operation()))
                .contains(retitled);
        verify(github, never()).fetchRepoIssues(PAT, REPO, "all");
    }

    @Test
    void markerRecoveryWalksPastOneHundredNewerItems()
    {
        List<RepoIssue> newer = IntStream.rangeClosed(101, 200)
                .mapToObj(number -> issue(number, "Newer " + number, "open"))
                .toList();
        RepoIssue older = issue(50, "Retitled older issue", "closed");
        when(github.fetchRepoIssuePage(PAT, REPO, 1, 100))
                .thenReturn(new RepoIssuePage(newer, true));
        when(github.fetchRepoIssuePage(PAT, REPO, 2, 100))
                .thenReturn(new RepoIssuePage(List.of(older), false));
        when(github.fetchIssueDetail(eq(PAT), eq(REPO), anyInt()))
                .thenAnswer(invocation -> {
                    int number = invocation.getArgument(2);
                    RepoIssue issue = number == older.number()
                            ? older : issue(number, "Newer " + number, "open");
                    return detail(issue, number == older.number() ? MARKER : "none");
                });

        Optional<RepoIssue> found = gateway.findExisting(operation());

        assertThat(found).contains(older);
        verify(github).fetchRepoIssuePage(PAT, REPO, 2, 100);
    }

    @Test
    void duplicateMarkersRemainAnErrorAcrossPages()
    {
        RepoIssue first = issue(17, "First", "open");
        RepoIssue second = issue(9, "Second", "closed");
        when(github.fetchRepoIssuePage(PAT, REPO, 1, 100))
                .thenReturn(new RepoIssuePage(List.of(first), true));
        when(github.fetchRepoIssuePage(PAT, REPO, 2, 100))
                .thenReturn(new RepoIssuePage(List.of(second), false));
        when(github.fetchIssueDetail(PAT, REPO, 17))
                .thenReturn(detail(first, MARKER));
        when(github.fetchIssueDetail(PAT, REPO, 9))
                .thenReturn(detail(second, MARKER));

        assertThatThrownBy(() -> gateway.findExisting(operation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple GitHub issues");
    }

    private static Operation operation()
    {
        return new Operation(
                "publish-1", "operation-1", "notification-1", "task-1", 1,
                "workspace-1", "trunk-1", "acme", "widget",
                "Original title", "body\n" + MARKER, MARKER, "a".repeat(64),
                Status.REQUESTED, null, null, null, null, "ticket-1");
    }

    private static RepoIssue issue(int number, String title, String state)
    {
        return new RepoIssue(
                number, number, title, "bot", state,
                "https://example.test/issues/" + number, Instant.EPOCH,
                List.of(), 0);
    }

    private static IssueDetail detail(RepoIssue issue, String body)
    {
        return new IssueDetail(
                issue.id(), issue.number(), issue.title(), body, issue.author(),
                null, issue.state(), issue.htmlUrl(), Instant.EPOCH,
                Instant.EPOCH, "closed".equals(issue.state())
                        ? Instant.EPOCH : null,
                List.of(), List.of(), null, List.of(), List.of(), false);
    }
}
