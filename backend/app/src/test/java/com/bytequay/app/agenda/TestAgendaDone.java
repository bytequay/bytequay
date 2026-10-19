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
package com.bytequay.app.agenda;

import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaItem;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RecentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAgendaDone
{
    private static final Instant MADE = Instant.parse("2026-10-16T09:02:00Z");

    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final WorkItemStore workItems = mock(WorkItemStore.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JiraService> jira = mock(ObjectProvider.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final IssueDevelopmentService issueDevelopment = mock(IssueDevelopmentService.class);
    private final AgendaDone done = new AgendaDone(pullRequests, mock(AgentTaskStore.class), mock(AgentTurnStore.class), mock(AgentReviewStore.class), workItems,
            mock(EpicStore.class), mock(RecentStore.class), mock(TaskPullRequestOwnership.class), jira, workspaces, issues, issueDevelopment,
            Clock.fixed(MADE.plusSeconds(3600), ZoneOffset.UTC));

    @Test
    void theUsersOwnMarkWins()
    {
        stored(pullRequest(1, null, null, null, PullRequestDetail.CiStatus.FAILING).build());
        AgendaItem fix = pull(1, AgendaItem.Action.FIX);

        assertThat(status(day(fix, ImmutableMap.of(fix.ref(), AgendaDay.Mark.DONE)), fix)).satisfies(status -> {
            assertThat(status.state()).isEqualTo(AgendaDone.Status.State.DONE);
            assertThat(status.byYou()).isTrue();
        });
        assertThat(status(day(fix, ImmutableMap.of(fix.ref(), AgendaDay.Mark.DISMISSED)), fix).state()).isEqualTo(AgendaDone.Status.State.DISMISSED);
        assertThat(status(day(fix, ImmutableMap.of()), fix).state()).isEqualTo(AgendaDone.Status.State.OPEN);
    }

    @Test
    void storedFactsCloseWhatTheyAnswer()
    {
        Instant later = MADE.plusSeconds(600);
        stored(pullRequest(2, null, later, null, PullRequestDetail.CiStatus.PASSING).build());
        AgendaItem merge = pull(2, AgendaItem.Action.MERGE);
        assertThat(status(day(merge, ImmutableMap.of()), merge).text()).contains("Merged");

        stored(pullRequest(3, later, null, later, PullRequestDetail.CiStatus.PASSING).build());
        AgendaItem review = pull(3, AgendaItem.Action.REVIEW);
        assertThat(status(day(review, ImmutableMap.of()), review)).satisfies(status -> {
            assertThat(status.text()).contains("Review submitted");
            assertThat(status.at()).contains(later);
            assertThat(status.byYou()).isFalse();
        });

        // A review submitted before the agenda was made does not answer it.
        stored(pullRequest(4, MADE.minusSeconds(60), null, null, PullRequestDetail.CiStatus.PASSING).build());
        AgendaItem stale = pull(4, AgendaItem.Action.REVIEW);
        assertThat(status(day(stale, ImmutableMap.of()), stale).state()).isEqualTo(AgendaDone.Status.State.OPEN);

        WorkItem started = new WorkItem("w22", "ws1", Optional.empty(), "Expose snapshot id", WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.WORKING, 2, ImmutableList.of(), ImmutableList.of(), MADE, later, WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.of("epic"), 0,
                ImmutableList.of());
        when(workItems.find("w22")).thenReturn(Optional.of(started));
        AgendaItem start = new AgendaItem("work:w22", AgendaItem.Action.START, "Start “Expose snapshot id”", "Expose snapshot id", "", Optional.empty(), Optional.of("ws1"),
                AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w22"), Optional.of("Iceberg write path · 3/6"));
        assertThat(status(day(start, ImmutableMap.of()), start).text()).contains("Started");
    }

    @Test
    void anIssueReadsStartedOnceItHasATask()
    {
        Workspace trino = new Workspace("ws1", "Trino", 1, RepoRef.of("trinodb", "trino"), Optional.empty(), Optional.empty(), 1, RepoRef.of("trinodb", "trino"),
                Workspace.Topology.DIRECT, "master", "https://github.com/trinodb/trino.git", Optional.empty(), Optional.empty(), "/managed/trino", Optional.empty(),
                Optional.empty(), 0, MADE, MADE);
        when(workspaces.findAll()).thenReturn(ImmutableList.of(trino));
        when(issues.find(1, 77)).thenReturn(Optional.of(new RepositoryIssue(901, 77, "Snapshot ids in $snapshots", "body", "marlo-t", "",
                "https://github.com/trinodb/trino/issues/77", ImmutableList.of(), ImmutableList.of(), 0, MADE, MADE)));
        AgendaItem start = new AgendaItem("issue:trinodb/trino#77", AgendaItem.Action.START, "Start “Snapshot ids in $snapshots”", "Snapshot ids", "", Optional.empty(),
                Optional.of("ws1"), AgendaTarget.issue("trinodb/trino", 77, "ws1"), Optional.empty());

        when(issueDevelopment.metadata(901)).thenReturn(new IssueDevelopmentService.Metadata(false, Optional.empty()));
        assertThat(status(day(start, ImmutableMap.of()), start).state()).isEqualTo(AgendaDone.Status.State.OPEN);
        when(issueDevelopment.metadata(901)).thenReturn(new IssueDevelopmentService.Metadata(false, Optional.of(new IssueDevelopmentService.TaskLink("t1", "ws1"))));
        assertThat(status(day(start, ImmutableMap.of()), start).text()).contains("Started");
    }

    private AgendaDone.Status status(AgendaDay day, AgendaItem item)
    {
        Map<String, AgendaDone.Status> statuses = done.statuses(day);
        assertThat(statuses).containsKey(item.ref());
        return done.status(day, item);
    }

    private void stored(PullRequest pr)
    {
        when(pullRequests.findIdByRepoAndNumber(anyString(), eq(pr.number()))).thenReturn(Optional.of(pr.id()));
        when(pullRequests.findById(pr.id())).thenReturn(Optional.of(pr));
    }

    private static PullRequestBuilder pullRequest(int number, Instant reviewedAt, Instant mergedAt, Instant headPushedAt, PullRequestDetail.CiStatus ci)
    {
        return new PullRequestBuilder(number, reviewedAt, mergedAt, headPushedAt, ci);
    }

    private record PullRequestBuilder(int number, Instant reviewedAt, Instant mergedAt, Instant headPushedAt, PullRequestDetail.CiStatus ci)
    {
        PullRequest build()
        {
            return new PullRequest(number, "trinodb/trino", number, "PR " + number, "marlo-t", "https://github.com/trinodb/trino/pull/" + number, MADE.minusSeconds(86400),
                    MADE.minusSeconds(60), PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of(), ImmutableMap.of(), false, null, reviewedAt, null, ImmutableList.of(),
                    ci, 10, 2, 0, mergedAt == null ? "open" : "merged", mergedAt, mergedAt, null, null, headPushedAt, "branch", null, null, null, null);
        }
    }

    private static AgendaItem pull(int number, AgendaItem.Action action)
    {
        return new AgendaItem("pr:trinodb/trino#" + number, action, "PR " + number, "PR " + number, "", Optional.empty(), Optional.of("ws1"),
                AgendaTarget.pull("trinodb/trino", number, Optional.of("ws1")), Optional.empty());
    }

    private static AgendaDay day(AgendaItem item, Map<String, AgendaDay.Mark> marks)
    {
        return new AgendaDay(LocalDate.parse("2026-10-16"), AgendaDay.State.READY, MADE, MADE.minusSeconds(86400), "claude", "Sonnet", "", ImmutableList.of(item),
                Optional.empty(), marks, Optional.empty(), Optional.empty());
    }
}
