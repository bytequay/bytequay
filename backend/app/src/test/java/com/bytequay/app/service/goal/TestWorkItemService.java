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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestWorkItemService
{
    private static final Instant NOW = Instant.EPOCH;
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
    private final WorkItemService service = new WorkItemService(store, goals, workspaces, references, transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp()
    {
        when(workspaces.findById("workspace")).thenReturn(Optional.of(mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE))));
        goals.save(new Goal("goal", "workspace", "Objective", "", true, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, Optional.empty(), NOW, NOW));
        when(references.related(eq("workspace"), any())).thenAnswer(call -> ImmutableList.of(call.getArgument(1)));
        when(references.view(eq("workspace"), any())).thenAnswer(call -> {
            GoalItem link = call.getArgument(1);
            return new GoalItemView(link.kind(), link.key(), link.label(), link.title(), GoalItemState.UNKNOWN, "Evidence unavailable", false,
                    Optional.empty(), Optional.empty(), Optional.of(NOW));
        });
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
                Optional.empty(), "Different evidence");
        assertThatThrownBy(() -> service.change("workspace", first.id(), changed)).hasMessageContaining("content changed");
    }

    private static WorkItemCommand decision(WorkItemView item, WorkItemCommand.Action action, String detail)
    {
        return new WorkItemCommand(UUID.randomUUID().toString(), item.revision(), action, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), detail);
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
                state, state.name(), false, Optional.of("https://github.com/org/repo/pull/" + number), Optional.empty(), Optional.of(NOW)));
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
