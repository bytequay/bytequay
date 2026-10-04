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
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.IssueTask;
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
    private final GoalCandidates candidates = new GoalCandidates(workspaces, issues, development, items, recommendations, beans.getBeanProvider(JiraService.class));

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
        when(development.findGoal(anyString(), anyLong())).thenReturn(Optional.empty());
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
                GoalRecommendation.Kind.WORK, "Fix #105", "You learned checkpoints", "Fix it", ImmutableList.of(), Optional.empty(), Optional.of(105), Optional.empty()),
                GoalRecommendation.State.DISMISSED, Optional.empty(), Optional.empty(), "op-1", NOW, NOW)));
        when(jira.cachedIssues()).thenReturn(ImmutableList.of(ticket("10412", "TRINO-12", "Delta checkpoint loses deletion vectors", "indeterminate", "workspace"),
                ticket("10413", "TRINO-13", "Delta checkpoint correctness in another workspace", "new", "other"),
                ticket("10414", "TRINO-14", "Delta checkpoint correctness, done", "done", "workspace")));

        GoalCandidates.Pool pool = candidates.pool(goal("Delta Lake checkpoint correctness"));
        assertThat(pool.candidates()).extracting(GoalCandidates.Candidate::id).as("nearest the goal first; worked, dismissed and other sources left out")
                .containsExactly("#101", "TRINO-12", "#102");
        assertThat(pool.candidates().get(1).status()).contains("In progress");
        assertThat(pool.coverage()).isEqualTo(new GoalCandidates.Coverage(5, 900, Optional.of(NOW), 2));
        assertThat(pool.has("#103")).isFalse();
        assertThat(candidates.ticket("workspace", "trino-12")).map(GoalCandidates::reference).contains("connection:cloud:10412");
        assertThat(candidates.ticket("workspace", "TRINO-13")).as("a ticket mapped to another workspace is not this one's").isEmpty();
    }

    private static GoalView goal(String title)
    {
        return new GoalView("goal", "workspace", title, "", true, Optional.empty(), Optional.empty(), 0, Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), NOW, NOW);
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
