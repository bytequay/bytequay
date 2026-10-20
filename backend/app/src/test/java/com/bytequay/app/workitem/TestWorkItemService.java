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
package com.bytequay.app.workitem;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.epic.EpicBreakdowns;
import com.bytequay.app.service.epic.EpicService;
import com.bytequay.app.service.epic.EpicSources;
import com.bytequay.app.service.epic.InMemoryEpicStore;
import com.bytequay.app.service.goal.InMemoryGoalStore;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemContextLink;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestWorkItemService
{
    private static final Instant NOW = Instant.EPOCH;
    private static final String EPIC = "ISSUE:github.com:1";
    private static final String OTHER_EPIC = "JIRA:5:cloud:10";
    private final InMemoryWorkItemStore store = new InMemoryWorkItemStore();
    private final InMemoryGoalStore goals = new InMemoryGoalStore();
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final GoalReferenceReader references = mock(GoalReferenceReader.class);
    private final AgentUnitOfWork transactions = new AgentUnitOfWork()
    {
        @Override
        public <T> T execute(Work<T> work)
        {
            return work.run();
        }
    };
    private final List<Object> published = new ArrayList<>();
    private final EpicSources epics = mock(EpicSources.class);
    private final InMemoryEpicStore epicRecords = new InMemoryEpicStore();
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final WorkItemService service = new WorkItemService(store, goals, workspaces, references, epics, epicRecords, tasks, transactions, published::add,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp()
    {
        when(workspaces.findById("workspace")).thenReturn(Optional.of(mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE))));
        goals.save(new Goal("goal", "workspace", "Objective", "", true, false, Optional.empty(), Optional.empty(), 0, Optional.empty(), false, NOW, NOW));
        when(references.related(eq("workspace"), any())).thenAnswer(call -> ImmutableList.of(call.getArgument(1)));
        when(references.view(eq("workspace"), any())).thenAnswer(call -> {
            GoalItem link = call.getArgument(1);
            return new GoalItemView(link.kind(), link.key(), link.label(), link.title(), GoalItemState.UNKNOWN, "Evidence unavailable", false,
                    Optional.empty(), Optional.empty(), Optional.of(NOW), Optional.empty());
        });
    }

    @Test
    void publicationClaimsThePreviewAndRetainsLocalContextAcrossAttemptsAndExposure()
    {
        String id = UUID.randomUUID().toString();
        String body = "Reproduction and acceptance criteria\n" + "evidence\n".repeat(4000);
        WorkItemView created = service.create("workspace", id, Optional.empty(), "Private investigation", WorkItem.Kind.DEVELOP, body, WorkItem.CompletionRule.MERGED_DELIVERY);
        assertThat(created.acceptanceCriteria()).isEqualTo(body);
        assertThat(created.links()).isEmpty();
        assertThatThrownBy(() -> service.beginPublication("workspace", id, 9, "publish-1", "GITHUB:org/repo")).hasMessageContaining("changed");
        service.beginPublication("workspace", id, created.revision(), "publish-1", "GITHUB:org/repo");
        assertThatThrownBy(() -> service.beginPublication("workspace", id, find(id).revision(), "publish-2", "GITHUB:org/repo"))
                .hasMessageContaining("may already have succeeded");
        WorkItemLink published = GoalReferenceReader.issueLink(new GitHubIssueSource("github.com", 43, "org/repo", 7,
                "https://github.com/org/repo/issues/7", "Private investigation", body, NOW), NOW);
        WorkItemView exposed = service.published("workspace", id, published, "publish-1");
        assertThat(exposed.id()).isEqualTo(created.id());
        assertThat(exposed.acceptanceCriteria()).isEqualTo(body);
        assertThat(exposed.links()).extracting(GoalItemView::key).containsExactly("org/repo#7");
        assertThat(service.list("workspace")).hasSize(1);
        assertThat(exposed.history()).anyMatch(event -> event.operationId().equals("publish-1:settled"));
        service.beginPublication("workspace", id, exposed.revision(), "publish-jira", "JIRA:cloud:ENG");
        service.publicationRejected("workspace", id, "publish-jira");
        service.beginPublication("workspace", id, find(id).revision(), "publish-jira-retry", "JIRA:cloud:ENG");
    }

    @Test
    void contextualReferencesRecordOnlyActualChangesAndKeepSourcesAndPlanningIndependent()
    {
        WorkItemLink source = link(GoalItemKind.ISSUE, "org/repo#5", "ISSUE:github.com:5");
        when(references.requireLink("workspace", GoalItemKind.ISSUE, source.reference().key())).thenReturn(source);
        WorkItemView item = service.attach("workspace", "goal", GoalItemKind.ISSUE, source.reference().key());
        WorkItemView assigned = service.joinLocalEpic("workspace", EPIC, item.id()).item();
        WorkItemView blocker = epicWork(EPIC, "Related preparation");
        service.change("workspace", item.id(), arrange(assigned, WorkItemCommand.Action.BLOCK, Optional.empty(), blocker.id()));
        WorkItem before = service.requireItem("workspace", item.id());
        List<WorkItemContextLink> links = ImmutableList.of(new WorkItemContextLink(blocker.id(), blocker.title()),
                new WorkItemContextLink("ISSUE:github.com:9", "Related report"));

        WorkItemView linked = service.changeContextLinks("workspace", item.id(), before.revision(), links, "Linked related reports");

        assertThat(linked.contextLinks()).containsExactlyElementsOf(links);
        assertThat(linked.history().getLast().action()).isEqualTo("CONTEXT_UPDATED");
        assertThat(linked.history().getLast().detail()).isEqualTo("Linked related reports");
        assertThat(service.requireItem("workspace", item.id())).usingRecursiveComparison().ignoringFields("revision", "history", "updatedAt", "contextLinks")
                .isEqualTo(before);
        assertThat(store.findByIdentity(source.identity())).map(WorkItem::id).contains(item.id());
        assertThat(store.findByIdentity("ISSUE:github.com:9")).as("contextual references do not own sources").isEmpty();
        assertThat(service.changeContextLinks("workspace", item.id(), before.revision(), links, "Retried")).isEqualTo(linked);
        assertThatThrownBy(() -> service.changeContextLinks("workspace", item.id(), before.revision(), ImmutableList.of(), "Unlink"))
                .hasMessageContaining("Work item changed");
        assertThatThrownBy(() -> service.changeContextLinks("other", item.id(), linked.revision(), links, "Elsewhere"))
                .hasMessageContaining("belongs to workspace workspace");

        WorkItemView unlinked = service.changeContextLinks("workspace", item.id(), linked.revision(), ImmutableList.of(), "Unlinked related reports");
        assertThat(unlinked.contextLinks()).isEmpty();
        assertThat(unlinked.history()).hasSize(before.history().size() + 2);
        assertThat(unlinked.links()).isEqualTo(linked.links());
        assertThat(unlinked.epicId()).isEqualTo(linked.epicId());
        assertThat(unlinked.blockedBy()).isEqualTo(linked.blockedBy());
        assertThat(service.changeContextLinks("workspace", item.id(), linked.revision(), ImmutableList.of(), "Retried")).isEqualTo(unlinked);
    }

    @Test
    void contextualReferencesSurviveOrdinaryDecisionsMembershipChangesAndPublication()
    {
        WorkItemView item = service.create("workspace", UUID.randomUUID().toString(), Optional.empty(), "Private work", WorkItem.Kind.DEVELOP,
                "Retain all context", WorkItem.CompletionRule.MERGED_DELIVERY);
        List<WorkItemContextLink> links = ImmutableList.of(new WorkItemContextLink("ISSUE:github.com:9", "Related report"));
        WorkItemView linked = service.changeContextLinks("workspace", item.id(), item.revision(), links, "Linked related report");
        service.change("workspace", item.id(), arrange(linked, WorkItemCommand.Action.PRIORITIZE, Optional.of(WorkItem.Priority.HIGH), ""));
        service.receipt("workspace", item.id(), "receipt-1", "Useful investigation");
        service.joinLocalEpic("workspace", EPIC, item.id());
        WorkItemView sibling = epicWork(EPIC, "Another piece");
        service.orderEpic("workspace", EPIC, ImmutableList.of(sibling.id(), item.id()));
        service.removeFromEpic("workspace", item.id());
        WorkItemLink source = GoalReferenceReader.issueLink(new GitHubIssueSource("github.com", 43, "org/repo", 7,
                "https://github.com/org/repo/issues/7", item.title(), item.acceptanceCriteria(), NOW), NOW);
        service.beginPublication("workspace", item.id(), find(item.id()).revision(), "publication-1", "GITHUB:org/repo");

        assertThat(service.published("workspace", item.id(), source, "publication-1").contextLinks()).containsExactlyElementsOf(links);
        assertThat(store.findByIdentity(source.identity())).map(WorkItem::contextLinks).contains(links);
    }

    @Test
    void oneSourceTaskAndPullRemainOneOutcomeAndMissingEvidenceNeverCompletesIt()
    {
        WorkItemLink issue = link(GoalItemKind.ISSUE, "org/repo#1", "ISSUE:github.com:42");
        WorkItemLink task = link(GoalItemKind.TASK, UUID.randomUUID().toString(), "TASK:attempt");
        WorkItemLink pull = link(GoalItemKind.PULL_REQUEST, "org/repo#2", "PULL_REQUEST:github.com:org/repo#2");
        for (WorkItemLink link : ImmutableList.of(issue, task, pull)) {
            when(references.requireLink("workspace", link.reference().kind(), link.reference().key())).thenReturn(link);
            when(references.related("workspace", link)).thenReturn(ImmutableList.of(issue, task, pull));
        }
        WorkItemView first = service.attach("workspace", "goal", GoalItemKind.ISSUE, issue.reference().key());
        WorkItemView retry = service.attach("workspace", "goal", GoalItemKind.TASK, task.reference().key());
        service.attach("workspace", "goal", GoalItemKind.PULL_REQUEST, pull.reference().key());
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(service.list("workspace")).hasSize(1);
        assertThat(retry.links()).hasSize(3);
        assertThat(retry.state()).isEqualTo(WorkItem.State.WORKING);
        assertThat(retry.revision()).isEqualTo(first.revision());
    }

    @Test
    void aRecommendedIssueBecomesTheGoalsWorkOnceAndConcludedOrOtherGoalsWorkIsNotTakenOver()
    {
        WorkItemLink issue = link(GoalItemKind.ISSUE, "org/repo#31252", "ISSUE:github.com:42");
        when(references.requireLink("workspace", GoalItemKind.ISSUE, "org/repo#31252")).thenReturn(issue);
        String id = UUID.randomUUID().toString();
        WorkItemView adopted = service.adopt("workspace", "goal", id, "Fix #31252", "Key checkpoint actions by path and deletion vector", GoalItemKind.ISSUE, "org/repo#31252");
        assertThat(adopted.id()).isEqualTo(id);
        assertThat(adopted.goalId()).contains("goal");
        assertThat(adopted.kind()).isEqualTo(WorkItem.Kind.DEVELOP);
        assertThat(adopted.state()).as("the task starts only when the user sends the composer").isEqualTo(WorkItem.State.PLANNED);
        assertThat(adopted.completionRule()).isEqualTo(WorkItem.CompletionRule.MERGED_DELIVERY);
        assertThat(adopted.acceptanceCriteria()).isEqualTo("Key checkpoint actions by path and deletion vector");
        assertThat(service.adopt("workspace", "goal", UUID.randomUUID().toString(), "Fix #31252", "Another wording", GoalItemKind.ISSUE, "org/repo#31252"))
                .as("the source's work is found again, never duplicated").isEqualTo(adopted);
        assertThat(service.list("workspace")).hasSize(1);

        goals.save(new Goal("goal-2", "workspace", "Another objective", "", false, false, Optional.empty(), Optional.empty(), 0, Optional.empty(), false, NOW, NOW));
        assertThatThrownBy(() -> service.adopt("workspace", "goal-2", UUID.randomUUID().toString(), "Fix #31252", "Fix it", GoalItemKind.ISSUE, "org/repo#31252"))
                .hasMessageContaining("another Goal");
        service.change("workspace", id, decision(adopted, WorkItemCommand.Action.DROP, "Not now"));
        assertThatThrownBy(() -> service.adopt("workspace", "goal", UUID.randomUUID().toString(), "Fix #31252", "Fix it", GoalItemKind.ISSUE, "org/repo#31252"))
                .hasMessageContaining("Reopen");
    }

    @Test
    void theWorkPicksUpThePullRequestItsTaskPublishedUntilItIsConcluded()
    {
        WorkItemLink issue = link(GoalItemKind.ISSUE, "org/repo#31252", "ISSUE:github.com:42");
        WorkItemLink task = link(GoalItemKind.TASK, "task-1", "TASK:task-1");
        WorkItemLink pull = link(GoalItemKind.PULL_REQUEST, "org/repo#31260", "PULL_REQUEST:github.com:org/repo#31260");
        when(references.requireLink("workspace", GoalItemKind.ISSUE, "org/repo#31252")).thenReturn(issue);
        when(references.related("workspace", issue)).thenReturn(ImmutableList.of(issue, task));
        String id = UUID.randomUUID().toString();
        WorkItemView working = service.adopt("workspace", "goal", id, "Fix #31252", "Rows survive a checkpoint", GoalItemKind.ISSUE, "org/repo#31252");
        assertThat(working.links()).hasSize(2);

        when(references.related("workspace", task)).thenReturn(ImmutableList.of(task, issue));
        assertThat(service.linkRelated("workspace", id)).as("nothing new was published").isEqualTo(working);
        when(references.related("workspace", task)).thenReturn(ImmutableList.of(task, issue, pull));
        WorkItemView published = service.linkRelated("workspace", id);
        assertThat(published.links()).extracting(GoalItemView::key).containsExactly("org/repo#31252", "task-1", "org/repo#31260");
        assertThat(service.linkRelated("workspace", id)).isEqualTo(published);

        WorkItemView dropped = service.change("workspace", id, decision(published, WorkItemCommand.Action.DROP, "Not now"));
        WorkItemLink later = link(GoalItemKind.PULL_REQUEST, "org/repo#31270", "PULL_REQUEST:github.com:org/repo#31270");
        when(references.related("workspace", task)).thenReturn(ImmutableList.of(task, issue, pull, later));
        assertThat(service.linkRelated("workspace", id)).as("concluded work keeps the evidence it was decided on").isEqualTo(dropped);
    }

    @Test
    void aDecisionOnGoalWorkIsPublishedOnceButNotItsReplayOrWorkOutsideAGoal()
    {
        String id = UUID.randomUUID().toString();
        WorkItemView created = service.create("workspace", id, Optional.of("goal"), "Fix the checkpoint", WorkItem.Kind.DEVELOP, "Rows survive",
                WorkItem.CompletionRule.ACCEPTED_RESULT);
        WorkItemView edited = service.change("workspace", id, new WorkItemCommand(UUID.randomUUID().toString(), created.revision(), WorkItemCommand.Action.EDIT,
                Optional.empty(), Optional.of("No rows lost"), Optional.empty(), Optional.empty(), "", Optional.empty(), Optional.empty(), false));
        assertThat(published).as("editing the scope is no decision to report").isEmpty();
        WorkItemCommand accept = decision(edited, WorkItemCommand.Action.ACCEPT, "The new test passes");
        service.change("workspace", id, accept);
        service.change("workspace", id, accept);
        assertThat(published).singleElement().isEqualTo(new WorkItemDecided("workspace", "goal", id, "Fix the checkpoint", WorkItem.Kind.DEVELOP,
                WorkItemCommand.Action.ACCEPT, accept.operationId(), "User accepted all criteria: No rows lost\nThe new test passes"));

        String loose = UUID.randomUUID().toString();
        WorkItemView outside = service.create("workspace", loose, Optional.empty(), "Tidy imports", WorkItem.Kind.DEVELOP, "Imports sorted",
                WorkItem.CompletionRule.ACCEPTED_RESULT);
        service.change("workspace", loose, decision(outside, WorkItemCommand.Action.DROP, "Not needed"));
        assertThat(published).hasSize(1);
    }

    @Test
    void aTaskResultIsRecordedOncePerTurnAndLeavesTheStateToTheUser()
    {
        String id = UUID.randomUUID().toString();
        service.create("workspace", id, Optional.of("goal"), "Fix the checkpoint", WorkItem.Kind.DEVELOP, "Rows survive", WorkItem.CompletionRule.MERGED_DELIVERY);
        service.receipt("workspace", id, "turn:1", "Task turn finished: the new test passes");
        service.receipt("workspace", id, "turn:1", "Task turn finished: the new test passes");
        WorkItemView item = service.list("workspace").getFirst();
        assertThat(item.history()).filteredOn(event -> event.action().equals("RECEIPT")).singleElement()
                .satisfies(event -> assertThat(event.detail()).isEqualTo("Task turn finished: the new test passes"));
        assertThat(item.state()).isEqualTo(WorkItem.State.PLANNED);

        service.summary("workspace", id, "summary:op-1", "What changed: nothing yet");
        service.summary("workspace", id, "summary:op-1", "What changed: nothing yet");
        assertThat(service.list("workspace").getFirst().history()).filteredOn(event -> event.action().equals("SUMMARY")).singleElement()
                .satisfies(event -> assertThat(event.detail()).isEqualTo("What changed: nothing yet"));
    }

    @Test
    void acceptsLearningOnceRejectsStaleEditsAndRetainsHistoryOnReopenAndDetach()
    {
        String id = UUID.randomUUID().toString();
        WorkItemView created = service.create("workspace", id, Optional.of("goal"), "Learn the code", WorkItem.Kind.LEARN, "Explain the call path",
                WorkItem.CompletionRule.ACCEPTED_RESULT);
        assertThat(service.create("workspace", id, Optional.of("goal"), "Learn the code", WorkItem.Kind.LEARN, "Explain the call path",
                WorkItem.CompletionRule.ACCEPTED_RESULT)).isEqualTo(created);
        WorkItemCommand accept = decision(created, WorkItemCommand.Action.ACCEPT, "Reviewed the saved call-path notes");
        WorkItemView done = service.change("workspace", id, accept);
        assertThat(done.state()).isEqualTo(WorkItem.State.DONE);
        assertThat(service.change("workspace", id, accept)).isEqualTo(done);
        assertThatThrownBy(() -> service.change("workspace", id, decision(created, WorkItemCommand.Action.DROP, "stale"))).hasMessageContaining("changed");
        WorkItemView reopened = service.change("workspace", id, decision(done, WorkItemCommand.Action.REOPEN, "Investigate another path"));
        WorkItemView detached = service.change("workspace", id, decision(reopened, WorkItemCommand.Action.MOVE, ""));
        assertThat(detached.goalId()).isEmpty();
        assertThat(detached.history()).hasSize(4);
        assertThat(detached.history().get(1).detail()).contains("Reviewed the saved call-path notes");
        assertThatThrownBy(() -> service.change("other", id, decision(detached, WorkItemCommand.Action.DROP, "wrong workspace")))
                .hasMessageContaining("belongs to workspace");
    }

    @Test
    void anAbandonedPullRequestNeitherDeliversNorBlocksTheOneThatReplacedIt()
    {
        WorkItemView created = service.create("workspace", UUID.randomUUID().toString(), Optional.of("goal"), "Fix bug", WorkItem.Kind.DEVELOP, "Fix the bug",
                WorkItem.CompletionRule.MERGED_DELIVERY);
        WorkItemView abandoned = service.link("workspace", created.id(), created.revision(), GoalItemKind.PULL_REQUEST, pull(2, GoalItemState.ENDED));
        assertThatThrownBy(() -> service.change("workspace", created.id(), decision(abandoned, WorkItemCommand.Action.ACCEPT, "Fixed")))
                .hasMessageContaining("Link a merged PR");
        WorkItemView merged = service.link("workspace", created.id(), abandoned.revision(), GoalItemKind.PULL_REQUEST, pull(3, GoalItemState.MERGED));
        WorkItemView open = service.link("workspace", created.id(), merged.revision(), GoalItemKind.PULL_REQUEST, pull(4, GoalItemState.WORKING));
        assertThatThrownBy(() -> service.change("workspace", created.id(), decision(open, WorkItemCommand.Action.ACCEPT, "Fixed")))
                .hasMessageContaining("refresh the others");
        pull(4, GoalItemState.ENDED);
        WorkItemView done = service.change("workspace", created.id(), decision(open, WorkItemCommand.Action.ACCEPT, "Fixed"));
        assertThat(done.state()).isEqualTo(WorkItem.State.DONE);
        assertThat(done.history().getLast().detail()).contains("Observed merged PRs: https://github.com/org/repo/pull/3",
                "Abandoned PRs, closed without merging: https://github.com/org/repo/pull/2, https://github.com/org/repo/pull/4");
    }

    @Test
    void attemptsOnOneSourceStayOneOutcomeAndConcludedOrForeignWorkRefusesAnother()
    {
        WorkItemLink source = GoalReferenceReader.issueLink(new GitHubIssueSource("github.com", 42, "org/repo", 1, "https://github.com/org/repo/issues/1",
                "Fix bug", "Accepted description", NOW), NOW);
        service.requireStartable("workspace", source.identity());
        service.recordAttempt(task(), source);
        service.recordAttempt(task(), source);
        WorkItemView item = service.list("workspace").getFirst();
        assertThat(service.list("workspace")).hasSize(1);
        assertThat(item.state()).isEqualTo(WorkItem.State.WORKING);
        assertThat(item.links()).extracting(GoalItemView::kind).containsExactly(GoalItemKind.ISSUE, GoalItemKind.TASK, GoalItemKind.TASK);
        assertThat(store.find(item.id()).orElseThrow().links()).allSatisfy(link -> assertThat(link.issueSource()).contains(source.issueSource().orElseThrow()));
        assertThatThrownBy(() -> service.requireStartable("other", source.identity())).hasMessageContaining("belongs to workspace workspace");
        service.change("workspace", item.id(), decision(item, WorkItemCommand.Action.DROP, "Not needed now"));
        assertThatThrownBy(() -> service.requireStartable("workspace", source.identity())).hasMessageContaining("Reopen the work item");
        assertThatThrownBy(() -> service.recordAttempt(task(), source)).hasMessageContaining("Reopen the work item");
        assertThat(store.find(item.id()).orElseThrow().links()).hasSize(3);
    }

    @Test
    void rejectsChangedRetryContent()
    {
        WorkItemView first = service.create("workspace", UUID.randomUUID().toString(), Optional.of("goal"),
                "First", WorkItem.Kind.LEARN, "Read", WorkItem.CompletionRule.ACCEPTED_RESULT);
        WorkItemCommand accept = decision(first, WorkItemCommand.Action.ACCEPT, "Accepted notes");
        service.change("workspace", first.id(), accept);
        WorkItemCommand changed = new WorkItemCommand(accept.operationId(), accept.expectedRevision(), accept.action(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), "Different evidence", Optional.empty(), Optional.empty(), false);
        assertThatThrownBy(() -> service.change("workspace", first.id(), changed)).hasMessageContaining("content changed");
    }

    @Test
    void workWaitsOnlyOnItsGoalsWorkWithoutCyclesAndADroppedBlockerReleasesItsDependentsOnlyWhenAsked()
    {
        WorkItemView reading = service.create("workspace", UUID.randomUUID().toString(), Optional.of("goal"), "Read the checkpoint code", WorkItem.Kind.LEARN,
                "Explain it", WorkItem.CompletionRule.ACCEPTED_RESULT);
        WorkItemView fixing = service.create("workspace", UUID.randomUUID().toString(), Optional.of("goal"), "Fix the checkpoint", WorkItem.Kind.DEVELOP,
                "Rows survive", WorkItem.CompletionRule.ACCEPTED_RESULT);
        WorkItemView loose = service.create("workspace", UUID.randomUUID().toString(), Optional.empty(), "Unrelated", WorkItem.Kind.DEVELOP, "Other",
                WorkItem.CompletionRule.ACCEPTED_RESULT);
        assertThat(ImmutableList.of(reading.rank(), fixing.rank())).as("new work joins the end of the order").containsExactly(0L, 1L);
        assertThat(reading.priority()).isEqualTo(WorkItem.Priority.NORMAL);

        WorkItemView high = service.change("workspace", fixing.id(), arrange(fixing, WorkItemCommand.Action.PRIORITIZE, Optional.of(WorkItem.Priority.HIGH), ""));
        assertThat(high.priority()).isEqualTo(WorkItem.Priority.HIGH);
        WorkItemView waiting = service.change("workspace", fixing.id(), arrange(high, WorkItemCommand.Action.BLOCK, Optional.empty(), reading.id()));
        assertThat(waiting.blockedBy()).containsExactly(reading.id());
        assertThat(waiting.history().getLast().detail()).isEqualTo("Waits on Read the checkpoint code");
        assertThatThrownBy(() -> service.change("workspace", reading.id(), arrange(reading, WorkItemCommand.Action.BLOCK, Optional.empty(), fixing.id())))
                .hasMessageContaining("already waits on this work");
        assertThatThrownBy(() -> service.change("workspace", reading.id(), arrange(reading, WorkItemCommand.Action.BLOCK, Optional.empty(), reading.id())))
                .hasMessageContaining("itself");
        assertThatThrownBy(() -> service.change("workspace", reading.id(), arrange(reading, WorkItemCommand.Action.BLOCK, Optional.empty(), loose.id())))
                .hasMessageContaining("its own Goal");
        assertThatThrownBy(() -> service.change("workspace", reading.id(), decision(reading, WorkItemCommand.Action.MOVE, "")))
                .as("blockers stay within one Goal").hasMessageContaining("before it leaves its Goal");

        assertThat(service.order("workspace", "goal", ImmutableList.of(fixing.id(), reading.id()))).extracting(WorkItemView::id, WorkItemView::rank)
                .containsExactly(tuple(fixing.id(), 0L), tuple(reading.id(), 1L));
        assertThatThrownBy(() -> service.order("workspace", "goal", ImmutableList.of(fixing.id()))).hasMessageContaining("Order it again");

        WorkItemView blocker = find(reading.id());
        assertThatThrownBy(() -> service.change("workspace", reading.id(), decision(blocker, WorkItemCommand.Action.DROP, "Not needed")))
                .as("dropped work is not done").hasMessageContaining("Waiting on this work: Fix the checkpoint");
        WorkItemCommand dropAndRelease = new WorkItemCommand(UUID.randomUUID().toString(), blocker.revision(), WorkItemCommand.Action.DROP, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), "Not needed", Optional.empty(), Optional.empty(), true);
        assertThat(service.change("workspace", reading.id(), dropAndRelease).state()).isEqualTo(WorkItem.State.DROPPED);
        WorkItemView released = find(fixing.id());
        assertThat(released.blockedBy()).isEmpty();
        assertThat(released.history().getLast().detail()).isEqualTo("No longer waits on Read the checkpoint code, which was dropped: Not needed");
    }

    @Test
    void epicWorkKeepsAnOrderOfItsOwnBesideItsGoalsAndOnlyAMarkedEpicTakesWork()
    {
        WorkItemView cache = epicWork(EPIC, "Cache table metadata");
        WorkItemView invalidate = epicWork(EPIC, "Invalidate on DDL");
        assertThat(ImmutableList.of(cache.epicRank(), invalidate.epicRank())).as("new work joins the end of the Epic").containsExactly(0L, 1L);
        assertThat(cache.epicId()).contains(EPIC);
        assertThat(cache.goalId()).isEmpty();
        assertThat(cache.kind()).isEqualTo(WorkItem.Kind.DEVELOP);
        assertThat(epicWork(EPIC, "Cache table metadata", cache.id())).as("a retried quick add creates nothing new").isEqualTo(cache);

        assertThat(service.orderEpic("workspace", EPIC, ImmutableList.of(invalidate.id(), cache.id()))).extracting(WorkItemView::id, WorkItemView::epicRank)
                .containsExactly(tuple(invalidate.id(), 0L), tuple(cache.id(), 1L));
        assertThatThrownBy(() -> service.orderEpic("workspace", EPIC, ImmutableList.of(cache.id()))).hasMessageContaining("Order it again");

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Epic not found")).when(epics).require("workspace", "ISSUE:github.com:7");
        assertThatThrownBy(() -> epicWork("ISSUE:github.com:7", "Elsewhere")).hasMessageContaining("Epic not found");
    }

    @Test
    void addingExistingWorkToAnEpicReusesItsItemAndMovesItFromAnotherEpic()
    {
        WorkItemLink issue = link(GoalItemKind.ISSUE, "org/repo#5", "ISSUE:github.com:5");
        when(references.requireLink("workspace", GoalItemKind.ISSUE, "org/repo#5")).thenReturn(issue);
        WorkItemService.EpicJoin joined = service.joinEpic("workspace", EPIC, GoalItemKind.ISSUE, "org/repo#5");
        assertThat(joined.movedFrom()).isEmpty();
        assertThat(joined.item().epicId()).contains(EPIC);
        assertThat(joined.item().title()).as("new work takes its source's title").isEqualTo("Fix bug");
        assertThat(service.joinEpic("workspace", EPIC, GoalItemKind.ISSUE, "org/repo#5")).as("joining again changes nothing").isEqualTo(joined);

        WorkItemService.EpicJoin moved = service.joinEpic("workspace", OTHER_EPIC, GoalItemKind.ISSUE, "org/repo#5");
        assertThat(moved.item().id()).isEqualTo(joined.item().id());
        assertThat(moved.item().epicId()).contains(OTHER_EPIC);
        assertThat(moved.movedFrom()).contains(EPIC);
        assertThat(service.list("workspace")).hasSize(1);

        WorkItemLink epicIssue = link(GoalItemKind.ISSUE, "org/repo#1", EPIC);
        when(references.requireLink("workspace", GoalItemKind.ISSUE, "org/repo#1")).thenReturn(epicIssue);
        assertThatThrownBy(() -> service.joinEpic("workspace", EPIC, GoalItemKind.ISSUE, "org/repo#1")).hasMessageContaining("its own work");
    }

    @Test
    void workWaitsOnWorkOfItsOwnEpicButNotOfAnother()
    {
        WorkItemView reading = epicWork(EPIC, "Read the reader");
        WorkItemView fixing = epicWork(EPIC, "Fix the reader");
        WorkItemView elsewhere = epicWork(OTHER_EPIC, "Another Epic's work");
        assertThat(service.change("workspace", fixing.id(), arrange(fixing, WorkItemCommand.Action.BLOCK, Optional.empty(), reading.id())).blockedBy())
                .containsExactly(reading.id());
        assertThatThrownBy(() -> service.change("workspace", reading.id(), arrange(reading, WorkItemCommand.Action.BLOCK, Optional.empty(), elsewhere.id())))
                .hasMessageContaining("its own Goal or Epic");
    }

    @Test
    void takingWorkOutOfItsEpicKeepsItsIdentityContextAndGoalMembership()
    {
        WorkItemView planned = epicWork(EPIC, "Benchmark the cache");
        WorkItemView waiting = epicWork(EPIC, "Publish the numbers");
        service.change("workspace", waiting.id(), arrange(waiting, WorkItemCommand.Action.BLOCK, Optional.empty(), planned.id()));
        assertThat(service.removeFromEpic("workspace", planned.id()).orElseThrow().epicId()).isEmpty();
        assertThat(service.list("workspace")).extracting(WorkItemView::id).containsExactly(planned.id(), waiting.id());
        WorkItemView released = find(waiting.id());
        assertThat(released.blockedBy()).as("nothing waits across unrelated work").isEmpty();
        assertThat(released.history().getLast().detail()).isEqualTo("No longer waits on Benchmark the cache, which left the Epic");

        WorkItemView goalWork = service.create("workspace", UUID.randomUUID().toString(), Optional.of("goal"), "Goal work", WorkItem.Kind.DEVELOP, "Done",
                WorkItem.CompletionRule.MERGED_DELIVERY);
        WorkItemLink issue = link(GoalItemKind.ISSUE, "org/repo#9", "ISSUE:github.com:9");
        when(references.requireLink("workspace", GoalItemKind.ISSUE, "org/repo#9")).thenReturn(issue);
        when(references.related("workspace", issue)).thenReturn(ImmutableList.of(issue));
        service.attach("workspace", "goal", GoalItemKind.ISSUE, "org/repo#9");
        WorkItemView shared = service.joinEpic("workspace", EPIC, GoalItemKind.ISSUE, "org/repo#9").item();
        WorkItemView kept = service.removeFromEpic("workspace", shared.id()).orElseThrow();
        assertThat(kept.goalId()).as("the Goal keeps its work").contains("goal");
        assertThat(kept.epicId()).isEmpty();
        assertThat(kept.history().getLast().action()).isEqualTo("LEFT_EPIC");
        assertThat(service.list("workspace")).extracting(WorkItemView::id).contains(goalWork.id(), shared.id());
    }

    @Test
    void unmarkingAnEpicKeepsAllWorkAndEndsWaitingOnlyTheEpicAllowed()
    {
        WorkItemView quick = epicWork(EPIC, "Quick idea");
        WorkItemLink issue = link(GoalItemKind.ISSUE, "org/repo#3", "ISSUE:github.com:3");
        WorkItemLink task = link(GoalItemKind.TASK, "task-3", "TASK:task-3");
        when(references.requireLink("workspace", GoalItemKind.ISSUE, "org/repo#3")).thenReturn(issue);
        when(references.related("workspace", issue)).thenReturn(ImmutableList.of(issue, task));
        WorkItemView attempted = service.joinEpic("workspace", EPIC, GoalItemKind.ISSUE, "org/repo#3").item();
        service.change("workspace", attempted.id(), arrange(attempted, WorkItemCommand.Action.BLOCK, Optional.empty(), quick.id()));
        WorkItemView unrelated = epicWork(OTHER_EPIC, "Another Epic's work");
        epicRecords.save(Epic.of(EPIC, "workspace"));

        service.releaseEpic("workspace", EPIC);

        assertThat(service.list("workspace")).extracting(WorkItemView::id).containsExactlyInAnyOrder(quick.id(), attempted.id(), unrelated.id());
        WorkItemView stayed = find(attempted.id());
        assertThat(stayed.epicId()).isEmpty();
        assertThat(stayed.blockedBy()).isEmpty();
        assertThat(stayed.history().getLast().detail())
                .isEqualTo("Left its Epic, which was unmarked; work and history retained\nNo longer waits on Quick idea, which left the Epic");
        assertThat(find(unrelated.id()).epicId()).as("other Epics keep their work").contains(OTHER_EPIC);
        assertThat(epicRecords.find(EPIC)).as("its children and what the user did with them go too").isEmpty();
    }

    @Test
    void localEpicsHoldNewAndExistingWorkAndUnmarkingRetainsEverything()
    {
        IssueDevelopmentStore markers = mock(IssueDevelopmentStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JiraService> jira = mock(ObjectProvider.class);
        EpicSources sources = new EpicSources(markers, workspaces, jira, store, epicRecords);
        WorkItemService localWork = new WorkItemService(store, goals, workspaces, references, sources, epicRecords, tasks, transactions, published::add,
                Clock.fixed(NOW, ZoneOffset.UTC));
        EpicService localEpics = new EpicService(epicRecords, sources, localWork, markers, mock(GitHubRepoClient.class), mock(CredentialService.class), jira,
                transactions, mock(RepositoryIssueStore.class), workspaces, mock(EpicBreakdowns.class), Clock.fixed(NOW, ZoneOffset.UTC));
        WorkItemView parent = localWork.create("workspace", UUID.randomUUID().toString(), Optional.empty(), "Hive field names", WorkItem.Kind.DEVELOP,
                "Support slash characters across connectors", WorkItem.CompletionRule.MERGED_DELIVERY);
        String epicId = EpicSources.localEpicId(parent.id());
        assertThat(parent.isEpic()).isFalse();
        localEpics.setLocalEpic("workspace", parent.id(), true);
        assertThat(localWork.list("workspace").getFirst().isEpic()).isTrue();
        assertThatThrownBy(() -> localEpics.setLocalEpic("other", parent.id(), false)).hasMessageContaining("belongs to workspace workspace");

        WorkItemView child = localWork.createInEpic("workspace", UUID.randomUUID().toString(), epicId, "Fix Iceberg", WorkItem.Kind.DEVELOP,
                "Read slash fields", WorkItem.CompletionRule.MERGED_DELIVERY);
        WorkItemView existing = localWork.create("workspace", UUID.randomUUID().toString(), Optional.of("goal"), "Fix Hive", WorkItem.Kind.DEVELOP,
                "Hive reads slash fields", WorkItem.CompletionRule.MERGED_DELIVERY);
        WorkItemService.EpicJoin joined = localWork.joinLocalEpic("workspace", epicId, existing.id());
        assertThat(joined.item().id()).isEqualTo(existing.id());
        assertThat(joined.item().epicId()).contains(epicId);
        assertThat(joined.item().goalId()).contains("goal");
        assertThat(joined.item().acceptanceCriteria()).isEqualTo(existing.acceptanceCriteria());
        assertThat(localWork.joinLocalEpic("workspace", epicId, existing.id())).as("assigning again changes nothing").isEqualTo(joined);
        localWork.change("workspace", existing.id(), arrange(joined.item(), WorkItemCommand.Action.BLOCK, Optional.empty(), child.id()));

        assertThatThrownBy(() -> localWork.createInEpic("workspace", parent.id(), epicId, parent.title(), parent.kind(), parent.acceptanceCriteria(),
                parent.completionRule())).hasMessageContaining("its own work");
        assertThatThrownBy(() -> localWork.joinLocalEpic("workspace", epicId, parent.id())).hasMessageContaining("its own work");
        assertThatThrownBy(() -> localEpics.setLocalEpic("workspace", child.id(), true)).hasMessageContaining("Remove this work item");
        assertThatThrownBy(() -> localWork.requireAttemptable("workspace", parent.id())).hasMessageContaining("Epic");
        AgentTask attempt = task();
        assertThatThrownBy(() -> localWork.persistAttempt(attempt, parent.id())).hasMessageContaining("Epic");
        verify(tasks, never()).insert(attempt);

        localEpics.setLocalEpic("workspace", parent.id(), false);
        assertThat(sources.marked("workspace", epicId)).isFalse();
        assertThat(localWork.list("workspace")).extracting(WorkItemView::id).containsExactly(parent.id(), child.id(), existing.id());
        assertThat(localWork.list("workspace")).allMatch(item -> !item.isEpic() && item.epicId().isEmpty());
        WorkItem retained = localWork.requireItem("workspace", existing.id());
        assertThat(retained.goalId()).contains("goal");
        assertThat(retained.acceptanceCriteria()).isEqualTo(existing.acceptanceCriteria());
        assertThat(retained.blockedBy()).isEmpty();
        assertThat(retained.history()).anyMatch(event -> event.action().equals("LEFT_EPIC"));
        assertThat(localWork.requireItem("workspace", parent.id()).acceptanceCriteria()).isEqualTo(parent.acceptanceCriteria());
        localWork.requireAttemptable("workspace", parent.id());
    }

    @Test
    void publishingALocalEpicKeepsItsIdentityAndCannotBypassItsParentGuards()
    {
        WorkItemView parent = service.create("workspace", UUID.randomUUID().toString(), Optional.empty(), "Hive field names", WorkItem.Kind.DEVELOP,
                "Support slash characters", WorkItem.CompletionRule.MERGED_DELIVERY);
        String epicId = EpicSources.localEpicId(parent.id());
        epicRecords.save(Epic.of(epicId, "workspace"));
        WorkItemLink issue = GoalReferenceReader.issueLink(new GitHubIssueSource("github.com", 43, "org/repo", 7,
                "https://github.com/org/repo/issues/7", parent.title(), parent.acceptanceCriteria(), NOW), NOW);
        service.beginPublication("workspace", parent.id(), parent.revision(), "publish-1", "GITHUB:org/repo");
        WorkItemView exposed = service.published("workspace", parent.id(), issue, "publish-1");
        assertThat(exposed.id()).isEqualTo(parent.id());
        assertThat(exposed.isEpic()).isTrue();
        assertThat(epicRecords.find(epicId)).isPresent();
        assertThat(service.list("workspace")).hasSize(1);

        assertThatThrownBy(() -> service.requireStartable("workspace", issue.identity())).hasMessageContaining("Epic organizes work");
        assertThatThrownBy(() -> service.recordAttempt(task(), issue)).hasMessageContaining("Epic organizes work");
        when(references.requireLink("workspace", GoalItemKind.ISSUE, issue.reference().key())).thenReturn(issue);
        assertThatThrownBy(() -> service.joinEpic("workspace", epicId, GoalItemKind.ISSUE, issue.reference().key())).hasMessageContaining("its own work");
        assertThatThrownBy(() -> service.joinEpic("workspace", epicId, issue)).hasMessageContaining("its own work");
        assertThatThrownBy(() -> service.joinLocalEpic("workspace", EPIC, parent.id())).as("Epics cannot be nested").hasMessageContaining("Epic");
        assertThat(service.list("workspace").getFirst().epicId()).isEmpty();
    }

    @Test
    void aSyncedChildBecomesEpicWorkUnderItsTitleOrBringsTheWorkThatOwnsIt()
    {
        WorkItemLink child = new WorkItemLink("ISSUE:github.com:8", new GoalItem(GoalItemKind.ISSUE, "org/repo#8", "#8", "Parse the plan", NOW));
        WorkItemView created = service.joinEpic("workspace", EPIC, child);
        assertThat(created.title()).isEqualTo("Parse the plan");
        assertThat(created.epicId()).contains(EPIC);
        assertThat(service.joinEpic("workspace", EPIC, child)).as("syncing again changes nothing").isEqualTo(created);

        WorkItemLink owned = link(GoalItemKind.ISSUE, "org/repo#9", "ISSUE:github.com:9");
        when(references.requireLink("workspace", GoalItemKind.ISSUE, "org/repo#9")).thenReturn(owned);
        WorkItemView goalWork = service.attach("workspace", "goal", GoalItemKind.ISSUE, "org/repo#9");
        WorkItemView joined = service.joinEpic("workspace", EPIC, owned);
        assertThat(joined.id()).isEqualTo(goalWork.id());
        assertThat(joined.goalId()).as("it keeps its Goal").contains("goal");
        assertThat(joined.epicId()).contains(EPIC);
        assertThat(service.list("workspace")).hasSize(2);

        WorkItemView goalOnly = service.create("workspace", UUID.randomUUID().toString(), Optional.of("goal"), "Goal only", WorkItem.Kind.LEARN,
                "Explain the parser", WorkItem.CompletionRule.ACCEPTED_RESULT);
        List<String> goalOrder = ImmutableList.of(goalOnly.id(), joined.id());
        List<WorkItemView> orderedGoal = service.order("workspace", "goal", goalOrder);
        assertThat(find(joined.id()).epicRank()).as("Goal ordering preserves the Epic's order").isEqualTo(joined.epicRank());
        assertThat(service.order("workspace", "goal", goalOrder)).as("unchanged order preserves revisions and timestamps").isEqualTo(orderedGoal);
        List<String> epicOrder = ImmutableList.of(joined.id(), created.id());
        List<WorkItemView> orderedEpic = service.orderEpic("workspace", EPIC, epicOrder);
        assertThat(find(joined.id()).rank()).as("Epic ordering preserves the Goal's order").isEqualTo(1);
        assertThat(find(joined.id()).epicRank()).isZero();
        assertThat(service.orderEpic("workspace", EPIC, epicOrder)).isEqualTo(orderedEpic);
        List<WorkItemView> beforeRejectedOrder = service.list("workspace");
        assertThatThrownBy(() -> service.order("workspace", "goal", ImmutableList.of(joined.id(), joined.id()))).hasMessageContaining("The Goal's work changed");
        assertThatThrownBy(() -> service.orderEpic("workspace", EPIC, ImmutableList.of(joined.id(), goalOnly.id()))).hasMessageContaining("The Epic's work changed");
        assertThat(service.list("workspace")).as("rejected orders update no items").isEqualTo(beforeRejectedOrder);
    }

    @Test
    void aTaskStartedForWorkWithoutASourceBecomesItsAttemptAndSourcedOrConcludedWorkRefusesOne()
    {
        WorkItemView quick = epicWork(EPIC, "Profile the reader");
        service.requireAttemptable("workspace", quick.id());
        AgentTask task = task();
        service.persistAttempt(task, quick.id());
        verify(tasks).insert(task);
        WorkItemView working = find(quick.id());
        assertThat(working.state()).isEqualTo(WorkItem.State.WORKING);
        assertThat(working.links()).extracting(GoalItemView::kind, GoalItemView::key).containsExactly(tuple(GoalItemKind.TASK, task.id().toString()));
        assertThatThrownBy(() -> service.requireAttemptable("other", quick.id())).hasMessageContaining("belongs to workspace workspace");

        WorkItemLink issue = link(GoalItemKind.ISSUE, "org/repo#5", "ISSUE:github.com:5");
        when(references.requireLink("workspace", GoalItemKind.ISSUE, "org/repo#5")).thenReturn(issue);
        WorkItemView sourced = service.joinEpic("workspace", EPIC, GoalItemKind.ISSUE, "org/repo#5").item();
        assertThatThrownBy(() -> service.requireAttemptable("workspace", sourced.id())).hasMessageContaining("from its source");

        service.change("workspace", quick.id(), decision(working, WorkItemCommand.Action.DROP, "Not needed"));
        assertThatThrownBy(() -> service.requireAttemptable("workspace", quick.id())).hasMessageContaining("Reopen the work item");
        AgentTask late = task();
        assertThatThrownBy(() -> service.persistAttempt(late, quick.id())).as("the transaction checks again").hasMessageContaining("Reopen the work item");
        verify(tasks, never()).insert(late);
    }

    @Test
    void acceptingQuickAddedWorkSettlesItsCriteriaAndRuleInTheSameDecision()
    {
        WorkItemView quick = epicWork(EPIC, "Close the stale ticket");
        WorkItemCommand accept = new WorkItemCommand(UUID.randomUUID().toString(), quick.revision(), WorkItemCommand.Action.ACCEPT, Optional.empty(),
                Optional.of("Close the stale ticket"), Optional.of(WorkItem.CompletionRule.ACCEPTED_RESULT), Optional.empty(), "Closed in Jira as Done",
                Optional.empty(), Optional.empty(), false);
        WorkItemView done = service.change("workspace", quick.id(), accept);
        assertThat(done.state()).isEqualTo(WorkItem.State.DONE);
        assertThat(done.acceptanceCriteria()).isEqualTo("Close the stale ticket");
        assertThat(done.completionRule()).isEqualTo(WorkItem.CompletionRule.ACCEPTED_RESULT);
        assertThat(done.history().getLast().detail()).isEqualTo("User accepted all criteria: Close the stale ticket\nClosed in Jira as Done");
    }

    @Test
    void anEpicIsDoneOnlyWithWorkAllDoneAndDroppedWorkCountedApart()
    {
        assertThatThrownBy(() -> service.requireEpicDone("workspace", EPIC)).as("no work yet").hasMessageContaining("all its work is done");
        WorkItemView cache = epicWork(EPIC, "Cache metadata");
        WorkItemView spike = epicWork(EPIC, "Old spike");
        epicWork(OTHER_EPIC, "Another Epic's work");
        service.change("workspace", spike.id(), decision(spike, WorkItemCommand.Action.DROP, "Not needed"));
        assertThatThrownBy(() -> service.requireEpicDone("workspace", EPIC)).hasMessageContaining("all its work is done");
        service.change("workspace", cache.id(), new WorkItemCommand(UUID.randomUUID().toString(), cache.revision(), WorkItemCommand.Action.ACCEPT, Optional.empty(),
                Optional.of("Cache metadata"), Optional.of(WorkItem.CompletionRule.ACCEPTED_RESULT), Optional.empty(), "Shipped", Optional.empty(), Optional.empty(), false));
        service.requireEpicDone("workspace", EPIC);
    }

    private WorkItemView epicWork(String epicId, String title)
    {
        return epicWork(epicId, title, UUID.randomUUID().toString());
    }

    private WorkItemView epicWork(String epicId, String title, String id)
    {
        return service.createInEpic("workspace", id, epicId, title, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY);
    }

    private WorkItemView find(String id)
    {
        return service.list("workspace").stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private static WorkItemCommand arrange(WorkItemView item, WorkItemCommand.Action action, Optional<WorkItem.Priority> priority, String blocker)
    {
        return new WorkItemCommand(UUID.randomUUID().toString(), item.revision(), action, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "",
                priority, Optional.of(blocker).filter(value -> !value.isEmpty()), false);
    }

    private static WorkItemCommand decision(WorkItemView item, WorkItemCommand.Action action, String detail)
    {
        return new WorkItemCommand(UUID.randomUUID().toString(), item.revision(), action, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), detail,
                Optional.empty(), Optional.empty(), false);
    }

    private static WorkItemLink link(GoalItemKind kind, String key, String identity)
    {
        return new WorkItemLink(identity, new GoalItem(kind, key, key, "Fix bug", NOW));
    }

    /** Stores PR {@code number} in the given state and returns its key. */
    private String pull(int number, GoalItemState state)
    {
        WorkItemLink link = link(GoalItemKind.PULL_REQUEST, "org/repo#" + number, "PULL_REQUEST:github.com:org/repo#" + number);
        when(references.requireLink("workspace", GoalItemKind.PULL_REQUEST, link.reference().key())).thenReturn(link);
        when(references.view("workspace", link.reference())).thenReturn(new GoalItemView(GoalItemKind.PULL_REQUEST, link.reference().key(), "#" + number, "Fix",
                state, state.name(), false, Optional.of("https://github.com/org/repo/pull/" + number), Optional.empty(), Optional.of(NOW), Optional.empty()));
        return link.reference().key();
    }

    private static AgentTask task()
    {
        AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(task.id()).thenReturn(TaskId.random());
        when(task.title()).thenReturn("Attempt");
        when(task.createdAt()).thenReturn(NOW);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        return task;
    }
}
