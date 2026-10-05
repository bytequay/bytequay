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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.core.AgentEventSource.root;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalReceipts
{
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private final TaskId taskId = TaskId.random();
    private final TurnId turnId = TurnId.random();
    private final WorkItemStore items = mock(WorkItemStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final TaskLogStore logs = mock(TaskLogStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final GoalConversationService conversation = mock(GoalConversationService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final GoalReceipts receipts = new GoalReceipts(items, workspaces, tasks, turns, logs, beans.getBeanProvider(WorkItemService.class),
            beans.getBeanProvider(GoalConversationService.class));

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("workItemService", work);
        beans.registerSingleton("goalConversationService", conversation);
        when(logs.findLatest(eq(taskId), any(), anyInt())).thenReturn(Optional.of(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(),
                7, taskId, Optional.of(turnId), root(), NOW, new AgentEventPayload.AssistantMessage(ProviderAgentEvent.MessageKind.ANSWER,
                ProviderAgentEvent.MessagePhase.FINAL, "Keyed checkpoint actions by path and deletion vector; the new test passes."))));
    }

    @Test
    void aFinishedTurnOfAGoalsTaskLeavesAReceiptAndIsReportedWithoutAskingTheAgent()
    {
        when(items.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item(Optional.of("goal-1"))));
        receipts.record(task(true), turn(TurnState.COMPLETED));
        verify(work).receipt("workspace", "work-1", "turn:" + turnId, "Task turn finished: Keyed checkpoint actions by path and deletion vector; the new test passes.");
        verify(work).linkRelated("workspace", "work-1");
        verify(conversation).report("workspace", "goal-1", "turn:" + turnId, "work-1",
                "A turn of the task for \"Fix #31252\" finished: Keyed checkpoint actions by path and deletion vector; the new test passes.");
        verify(conversation, never()).tell(any(), any(), any(), any());
    }

    @Test
    void aFailedOrInterruptedTurnTellsTheAgentWhyItStopped()
    {
        when(items.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item(Optional.of("goal-1"))));
        receipts.record(task(true), turn(TurnState.FAILED));
        verify(work).receipt("workspace", "work-1", "turn:" + turnId, "Task turn failed: The provider stopped");
        verify(conversation).tell("workspace", "goal-1", "turn:" + turnId, "A turn of the task for \"Fix #31252\" failed: The provider stopped");

        TurnId interrupted = TurnId.random();
        receipts.record(task(true), turn(interrupted, TurnState.INTERRUPTED));
        verify(conversation).tell("workspace", "goal-1", "turn:" + interrupted, "A turn of the task for \"Fix #31252\" was interrupted: The provider stopped");
    }

    @Test
    void onlyAnInteractiveTasksEndedTurnOnAGoalsWorkCounts()
    {
        when(items.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item(Optional.empty())));
        receipts.record(task(true), turn(TurnState.COMPLETED));
        when(items.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item(Optional.of("goal-1"))));
        receipts.record(task(false), turn(TurnState.COMPLETED));
        receipts.record(task(true), turn(TurnState.CANCELLED));
        verifyNoInteractions(work, conversation);
    }

    @Test
    void turnsThatFinishedWhileByteQuayWasStoppedAreRecordedAtStartupAndToldOnce()
    {
        TurnId earlier = TurnId.random();
        WorkItemLink link = new WorkItemLink(WorkItemLink.taskIdentity(taskId.toString()), new GoalItem(GoalItemKind.TASK, taskId.toString(), "Task", "Fix #31252", NOW));
        WorkItem item = new WorkItem("work-1", "workspace", Optional.of("goal-1"), "Fix #31252", WorkItem.Kind.DEVELOP, "Rows survive a checkpoint",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.WORKING, 1, ImmutableList.of(link),
                ImmutableList.of(new WorkItemEvent("turn:" + earlier, "", "RECEIPT", "Task turn finished: the first test", NOW)), NOW, NOW, WorkItem.Priority.NORMAL, 0,
                ImmutableList.of(), Optional.empty(), 0);
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.id()).thenReturn("workspace");
        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace));
        when(items.list("workspace")).thenReturn(ImmutableList.of(item, item(Optional.empty())));
        when(items.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item));
        when(tasks.find(taskId)).thenReturn(Optional.of(task(true)));
        TurnId failed = TurnId.random();
        when(turns.list(taskId)).thenReturn(ImmutableList.of(turn(earlier, TurnState.COMPLETED), turn(turnId, TurnState.COMPLETED), turn(failed, TurnState.FAILED),
                turn(TurnId.random(), TurnState.CANCELLED)));

        receipts.recover();
        verify(conversation).settleInterrupted();
        verify(work).receipt("workspace", "work-1", "turn:" + turnId, "Task turn finished: Keyed checkpoint actions by path and deletion vector; the new test passes.");
        verify(work, never()).receipt(any(), any(), eq("turn:" + earlier), any());
        verify(work).receipt("workspace", "work-1", "turn:" + failed, "Task turn failed: The provider stopped");
        verify(conversation).tell("workspace", "goal-1", "recovered:" + failed, "While ByteQuay was stopped, 2 turns of the task for \"Fix #31252\" ended. "
                + "The last failed: The provider stopped");

        // Missed turns that all finished normally are reported, not asked about.
        when(turns.list(taskId)).thenReturn(ImmutableList.of(turn(earlier, TurnState.COMPLETED), turn(turnId, TurnState.COMPLETED)));
        receipts.recover();
        verify(conversation).report("workspace", "goal-1", "recovered:" + turnId, "work-1", "While ByteQuay was stopped, a turn of the task for \"Fix #31252\" ended. "
                + "The last finished: Keyed checkpoint actions by path and deletion vector; the new test passes.");
    }

    @Test
    void aTaskGoesOnlyOnceItsGoalWorkRecordedEveryTurnEvenWhenTheGoalWasCancelled()
    {
        receipts.capture(task(true));
        verifyNoInteractions(work);

        TurnId earlier = TurnId.random();
        WorkItem item = new WorkItem("work-1", "workspace", Optional.of("goal-1"), "Fix #31252", WorkItem.Kind.DEVELOP, "Rows survive a checkpoint",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.WORKING, 1, ImmutableList.of(),
                ImmutableList.of(new WorkItemEvent("turn:" + earlier, "", "RECEIPT", "Task turn finished: the first test", NOW)), NOW, NOW, WorkItem.Priority.NORMAL, 0,
                ImmutableList.of(), Optional.empty(), 0);
        when(items.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item));
        when(turns.list(taskId)).thenReturn(ImmutableList.of(turn(earlier, TurnState.COMPLETED), turn(turnId, TurnState.COMPLETED)));
        // A cancelled Goal has no agent left to tell; its work still records the turn, and the task may go.
        doThrow(new IllegalStateException("Goal not found")).when(conversation).report(any(), any(), any(), any(), any());
        receipts.capture(task(true));
        verify(work).receipt("workspace", "work-1", "turn:" + turnId, "Task turn finished: Keyed checkpoint actions by path and deletion vector; the new test passes.");
        verify(work, never()).receipt(any(), any(), eq("turn:" + earlier), any());

        // A receipt that cannot be saved keeps the task and its log for the next try.
        doThrow(new IllegalStateException("database is locked")).when(work).receipt(any(), any(), any(), any());
        assertThatThrownBy(() -> receipts.capture(task(true)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("database is locked");
    }

    private AgentTask task(boolean interactive)
    {
        return AgentTask.created(taskId, Optional.of("workspace"), interactive, "Development", new AgentLaneId("codex"), new RepositoryIdentity("trinodb", "trino"),
                Path.of("/tmp/goal-receipts"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }

    private AgentTurn turn(TurnState state)
    {
        return turn(turnId, state);
    }

    private AgentTurn turn(TurnId id, TurnState state)
    {
        return AgentTurn.accepted(id, taskId, "turn", new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofHours(1),
                ImmutableList.of()), NOW).markDeliveryStarted(NOW).running(new ProviderTurnRef("provider"), Optional.empty())
                .finish(state, AgentMetrics.empty(), state == TurnState.COMPLETED ? Optional.empty() : Optional.of("The provider stopped"), NOW);
    }

    private static WorkItem item(Optional<String> goalId)
    {
        return new WorkItem("work-1", "workspace", goalId, "Fix #31252", WorkItem.Kind.DEVELOP, "Rows survive a checkpoint", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.WORKING, 1, ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0);
    }
}
