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
package com.bytequay.app.service.goal;

import com.bytequay.app.jira.JiraDtos;
import com.bytequay.app.jira.JiraIssue;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.GoalRecommendationStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalCandidates
{
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final RepoRef TRINO = RepoRef.of("trinodb", "trino");

    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final IssueDevelopmentStore development = mock(IssueDevelopmentStore.class);
    private final WorkItemStore items = mock(WorkItemStore.class);
    private final GoalRecommendationStore recommendations = mock(GoalRecommendationStore.class);
    private final JiraService jira = mock(JiraService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final GitHubPullRequestReadClient gitHub = mock(GitHubPullRequestReadClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final GoalCandidates candidates = new GoalCandidates(workspaces, issues, development, items, recommendations, beans.getBeanProvider(JiraService.class),
            gitHub, credentials);

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("jiraService", jira);
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepositoryId()).thenReturn(1L);
        when(workspace.monitoredRepository()).thenReturn(TRINO);
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        when(items.findByIdentity(anyString())).thenReturn(Optional.empty());
        when(development.findTask(anyString(), anyLong())).thenReturn(Optional.empty());
        when(development.findEpic(anyString(), anyLong())).thenReturn(Optional.empty());
        when(recommendations.list(any())).thenReturn(ImmutableList.of());
    }

    @Test
    void offersOpenWorkNearestTheGoalFirstWithoutWorkedOrDismissedSourcesAndSaysWhatItRead()
    {
        when(issues.findPage(1, TRINO, 0, 100)).thenReturn(new RepositoryIssuePage(1, TRINO, ImmutableList.of(
                issue(1, 101, "Checkpoint drops rows after a deletion vector", ImmutableList.of("delta-lake")),
                issue(2, 102, "Refresh the theme", ImmutableList.of()),
                issue(3, 103, "Checkpoint writer is slow", ImmutableList.of()),
                issue(4, 104, "Delta checkpoint retries", ImmutableList.of()),
                issue(5, 105, "Delta checkpoint test", ImmutableList.of())), 0, 100, 900, false, Optional.of(NOW)));
        when(development.findTask("github.com", 3)).thenReturn(Optional.of(mock(IssueTask.class, withSettings().mockMaker(MockMakers.INLINE))));
        when(items.findByIdentity(WorkItemLink.issueIdentity("github.com", 4))).thenReturn(Optional.of(mock(WorkItem.class, withSettings().mockMaker(MockMakers.INLINE))));
        when(recommendations.list("goal")).thenReturn(ImmutableList.of(new GoalRecommendation("card-1", "goal", "workspace", new GoalRecommendation.Card(
                GoalRecommendation.Kind.WORK, "Fix #105", "You learned checkpoints", "Fix it", ImmutableList.of(), Optional.empty(), Optional.of(105), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()),
                GoalRecommendation.State.DISMISSED, Optional.empty(), Optional.empty(), Optional.empty(), "op-1", NOW, NOW)));
        when(jira.cachedIssues()).thenReturn(ImmutableList.of(ticket("10412", "TRINO-12", "Delta checkpoint loses deletion vectors", "indeterminate", "workspace"),
                ticket("10413", "TRINO-13", "Delta checkpoint correctness in another workspace", "new", "other"),
                ticket("10414", "TRINO-14", "Delta checkpoint correctness, done", "done", "workspace")));

        GoalCandidates.Pool pool = candidates.pool(goal("Delta Lake checkpoint correctness"));
        assertThat(pool.candidates()).extracting(GoalCandidates.Candidate::id).as("nearest the goal first; worked, dismissed and other sources left out")
                .containsExactly("#101", "TRINO-12", "#102");
        assertThat(pool.candidates().get(1).status()).contains("In progress");
        assertThat(pool.candidates()).extracting(GoalCandidates.Candidate::openedAt).as("an issue says when it was opened; a cached ticket does not")
                .containsExactly(Optional.of(NOW), Optional.empty(), Optional.of(NOW));
        assertThat(pool.coverage()).isEqualTo(new GoalCandidates.Coverage(5, 900, Optional.of(NOW), 2));
        assertThat(pool.has("#103")).isFalse();
        assertThat(candidates.ticket("workspace", "trino-12")).map(GoalCandidates::reference).contains("connection:cloud:10412");
        assertThat(candidates.ticket("workspace", "TRINO-13")).as("a ticket mapped to another workspace is not this one's").isEmpty();
    }

    @Test
    void epicMarkersExcludeContainersAndUnmarkingRestoresPractice()
    {
        when(issues.findPage(1, TRINO, 0, 100)).thenReturn(new RepositoryIssuePage(1, TRINO,
                ImmutableList.of(issue(1, 101, "Delta Lake correctness", ImmutableList.of())), 0, 100, 1, false, Optional.of(NOW)));
        when(development.findEpic("github.com", 1)).thenReturn(Optional.of(new IssueEpic("github.com", 1, "trinodb/trino", 101, true, NOW)));
        assertThat(candidates.pool(goal("Delta Lake")).candidates()).isEmpty();
        when(development.findEpic("github.com", 1)).thenReturn(Optional.of(new IssueEpic("github.com", 1, "trinodb/trino", 101, false, NOW)));
        assertThat(candidates.pool(goal("Delta Lake")).candidates()).extracting(GoalCandidates.Candidate::id).containsExactly("#101");
    }

    @Test
    void aPracticeSourceIsReadAgainLeftOutOnceClosedFixedOrClaimedAndDoubtedWhileAPullRequestIsOpen()
    {
        when(credentials.resolveGitHubToken("workspace")).thenReturn("token");
        PullRequestRef issue = PullRequestRef.of("trinodb", "trino", 31252);
        when(gitHub.fetchIssueLinks("token", issue)).thenReturn(new GitHubPullRequestReadClient.IssueLinks(true,
                ImmutableList.of(new GitHubPullRequestReadClient.LinkedPull(31100, "CLOSED"))));
        assertThat(candidates.read(goal("Delta"), card(Optional.of(31252), Optional.empty()))).as("a closed pull request claims nothing")
                .isEqualTo(GoalCandidates.Read.NOTHING);

        when(gitHub.fetchIssueLinks("token", issue)).thenReturn(new GitHubPullRequestReadClient.IssueLinks(true,
                ImmutableList.of(new GitHubPullRequestReadClient.LinkedPull(31270, "OPEN"))));
        GoalCandidates.Read open = candidates.read(goal("Delta"), card(Optional.of(31252), Optional.empty()));
        assertThat(open.unready()).as("an open pull request may stall, so the issue stays").isEmpty();
        assertThat(open.doubt()).contains("Pull request #31270 is open and linked to close issue #31252; it may already fix it.");

        when(gitHub.fetchIssueLinks("token", issue)).thenReturn(new GitHubPullRequestReadClient.IssueLinks(true,
                ImmutableList.of(new GitHubPullRequestReadClient.LinkedPull(31100, "CLOSED"), new GitHubPullRequestReadClient.LinkedPull(31260, "MERGED"))));
        assertThat(candidates.read(goal("Delta"), card(Optional.of(31252), Optional.empty())).unready())
                .contains("Issue #31252 was fixed by pull request #31260, merged.");

        when(gitHub.fetchIssueLinks("token", issue)).thenReturn(new GitHubPullRequestReadClient.IssueLinks(false, ImmutableList.of()));
        assertThat(candidates.read(goal("Delta"), card(Optional.of(31252), Optional.empty())).unready()).contains("Issue #31252 is closed.");

        when(gitHub.fetchIssueLinks("token", issue)).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        GoalCandidates.Read unread = candidates.read(goal("Delta"), card(Optional.of(31252), Optional.empty()));
        assertThat(unread.unready()).as("an unreadable source floats from the cache").isEmpty();
        assertThat(unread.doubt()).contains("Issue #31252 could not be read again just now, so this card rests on ByteQuay's last sync of it.");

        when(jira.refreshIssue("connection", "cloud", "10414")).thenReturn(ticket("10414", "TRINO-14", "Delta checkpoint", "done", "workspace"));
        assertThat(candidates.read(goal("Delta"), card(Optional.empty(), Optional.of("connection:cloud:10414"))).unready())
                .contains("Ticket TRINO-14 is no longer open for practice; it is Done.");
        when(jira.refreshIssue("connection", "cloud", "10412")).thenReturn(ticket("10412", "TRINO-12", "Delta checkpoint", "indeterminate", "workspace"));
        assertThat(candidates.read(goal("Delta"), card(Optional.empty(), Optional.of("connection:cloud:10412")))).isEqualTo(GoalCandidates.Read.NOTHING);
    }

    @Test
    void jirasHighestPrioritiesAreKeptBeforeTheLimitAndStillReadNearestFirst()
    {
        // A hundred and one issues share the goal's words; a Highest ticket shares none, yet stays a candidate.
        ImmutableList.Builder<RepositoryIssue> near = ImmutableList.builder();
        for (int index = 0; index <= GoalCandidates.MAX_CANDIDATES; index++) {
            near.add(issue(index + 1, 1000 + index, "Delta checkpoint " + index, ImmutableList.of()));
        }
        when(issues.findPage(1, TRINO, 0, 100)).thenReturn(new RepositoryIssuePage(1, TRINO, near.build(), 0, 100, 101, false, Optional.of(NOW)));
        when(jira.cachedIssues()).thenReturn(ImmutableList.of(new JiraIssue("connection", "cloud", "10499", "TRINO-99", "https://trino.atlassian.net/browse/TRINO-99",
                "Upgrade the metastore client", "", "Bug", new JiraDtos.Status("1", "To do", "new"), Optional.empty(), ImmutableList.of(),
                Optional.of(new JiraDtos.Choice("1", "Highest")), Optional.empty(), false, false, NOW, Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                Optional.empty(), Optional.of(new JiraDtos.WorkspaceLink("workspace", "workspace")))));

        List<GoalCandidates.Candidate> pool = candidates.pool(goal("Delta checkpoint")).candidates();
        assertThat(pool).hasSize(GoalCandidates.MAX_CANDIDATES);
        assertThat(pool).extracting(GoalCandidates.Candidate::id).contains("TRINO-99");
        assertThat(pool.getLast().id()).as("the agent still reads the candidates nearest the goal first").isEqualTo("TRINO-99");
    }

    private static GoalRecommendation.Card card(Optional<Integer> issue, Optional<String> ticket)
    {
        return new GoalRecommendation.Card(GoalRecommendation.Kind.WORK, "Fix it", "You learned checkpoints", "Fix it", ImmutableList.of(), Optional.empty(), issue, ticket,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static GoalView goal(String title)
    {
        return new GoalView("goal", "workspace", title, "", true, Optional.empty(), Optional.empty(), 0, Optional.empty(), false, ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(), NOW, NOW);
    }

    private static RepositoryIssue issue(long id, int number, String title, List<String> labels)
    {
        return new RepositoryIssue(id, number, title, "", "bo", "", "https://github.com/trinodb/trino/issues/" + number, labels, ImmutableList.of(), 0, NOW,
                NOW.plusSeconds(number));
    }

    private static JiraIssue ticket(String id, String key, String title, String category, String workspaceId)
    {
        return new JiraIssue("connection", "cloud", id, key, "https://trino.atlassian.net/browse/" + key, title, "", "Bug",
                new JiraDtos.Status("3", category.equals("indeterminate") ? "In progress" : category.equals("done") ? "Done" : "To do", category), Optional.empty(),
                ImmutableList.of(), Optional.empty(), Optional.empty(), false, false, NOW, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(),
                Optional.of(new JiraDtos.WorkspaceLink(workspaceId, workspaceId)));
    }
}
