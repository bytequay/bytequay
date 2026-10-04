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
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.WorkItemStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.core.AgentEventSource.root;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestGoalReceipts
{
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private final TaskId taskId = TaskId.random();
    private final TurnId turnId = TurnId.random();
    private final WorkItemStore items = mock(WorkItemStore.class);
    private final TaskLogStore logs = mock(TaskLogStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final GoalConversationService conversation = mock(GoalConversationService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final GoalReceipts receipts = new GoalReceipts(items, logs, beans.getBeanProvider(WorkItemService.class), beans.getBeanProvider(GoalConversationService.class));

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
    void aFinishedTurnOfAGoalsTaskLeavesAReceiptAndTellsTheAgent()
    {
        when(items.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item(Optional.of("goal-1"))));
        receipts.record(task(true), turn(TurnState.COMPLETED));
        verify(work).receipt("workspace", "work-1", "turn:" + turnId, "Task turn finished: Keyed checkpoint actions by path and deletion vector; the new test passes.");
        verify(work).linkRelated("workspace", "work-1");
        verify(conversation).tell("workspace", "goal-1", "turn:" + turnId,
                "A task for \"Fix #31252\" finished a turn: Keyed checkpoint actions by path and deletion vector; the new test passes.");
    }

    @Test
    void onlyAnInteractiveTasksCompletedTurnOnAGoalsWorkCounts()
    {
        when(items.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item(Optional.empty())));
        receipts.record(task(true), turn(TurnState.COMPLETED));
        when(items.findByIdentity(WorkItemLink.taskIdentity(taskId.toString()))).thenReturn(Optional.of(item(Optional.of("goal-1"))));
        receipts.record(task(false), turn(TurnState.COMPLETED));
        receipts.record(task(true), turn(TurnState.FAILED));
        verifyNoInteractions(work, conversation);
    }

    private AgentTask task(boolean interactive)
    {
        return AgentTask.created(taskId, Optional.of("workspace"), interactive, "Development", new AgentLaneId("codex"), new RepositoryIdentity("trinodb", "trino"),
                Path.of("/tmp/goal-receipts"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }

    private AgentTurn turn(TurnState state)
    {
        return AgentTurn.accepted(turnId, taskId, "turn", new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofHours(1),
                ImmutableList.of()), NOW).markDeliveryStarted(NOW).running(new ProviderTurnRef("provider"), Optional.empty())
                .finish(state, AgentMetrics.empty(), state == TurnState.COMPLETED ? Optional.empty() : Optional.of("The provider stopped"), NOW);
    }

    private static WorkItem item(Optional<String> goalId)
    {
        return new WorkItem("work-1", "workspace", goalId, "Fix #31252", WorkItem.Kind.DEVELOP, "Rows survive a checkpoint", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.WORKING, 1, ImmutableList.of(), ImmutableList.of(), NOW, NOW);
    }
}
