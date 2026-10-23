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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskActivityReader
{
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final TaskLogStore logs = mock(TaskLogStore.class);
    private final AgentApprovalStore approvals = mock(AgentApprovalStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final TaskActivityReader reader = new TaskActivityReader(logs, approvals, turns);

    @Test
    void quotesTheNewestRootMessageButNeverASubagentsOrAPartialOne()
    {
        TurnId turn = TurnId.random();
        AgentEventPayload finalAnswer = new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, "  Fixed the checkpoint.  ");
        AgentEventEnvelope answer = event(turn, AgentEventSource.root(), finalAnswer);
        assertThat(TaskActivityReader.rootMessage(answer)).isTrue();
        assertThat(TaskActivityReader.rootMessage(event(turn, AgentEventSource.root(),
                new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.PARTIAL, "Fixed the")))).isFalse();
        assertThat(TaskActivityReader.rootMessage(event(turn, AgentEventSource.root(),
                new AgentEventPayload.AssistantMessage(MessageKind.REASONING_SUMMARY, MessagePhase.FINAL, "Thinking")))).isFalse();
        assertThat(TaskActivityReader.rootMessage(event(turn,
                new AgentEventSource(Optional.of("child"), Optional.of("root"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, "A subagent's answer")))).isFalse();

        AgentTask task = task();
        when(logs.findLatest(eq(task.id()), any(), anyInt())).thenReturn(Optional.of(answer));
        when(approvals.listPending()).thenReturn(ImmutableList.of());
        when(turns.list(task.id())).thenReturn(ImmutableList.of());

        TaskActivity activity = reader.read(ImmutableList.of(task)).get(task.id());
        assertThat(activity.lastMessage()).contains(new TaskMessage(TaskMessage.Author.AGENT, "Fixed the checkpoint.", NOW, Optional.of(turn)));
        assertThat(activity.pendingApproval()).isEmpty();
        assertThat(activity.latestTurn()).isEmpty();
    }

    @Test
    void quotesTheKeptFinalAnswerOnceRetentionRemovedTheTranscript()
    {
        AgentTask deleted = task()
                .complete(new TaskSummary("All green.", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60))
                .withCleanupState(CleanupState.DELETED, Optional.empty(), NOW.plusSeconds(120));
        AgentTask unreadable = task();
        when(logs.findLatest(eq(unreadable.id()), any(), anyInt())).thenThrow(new AgentStorageException("Task resources are missing"));
        when(approvals.listPending()).thenReturn(ImmutableList.of());

        Map<TaskId, TaskActivity> activity = reader.read(ImmutableList.of(deleted, unreadable));

        assertThat(activity.get(deleted.id()).lastMessage()).contains(new TaskMessage(TaskMessage.Author.AGENT, "All green.", NOW, Optional.empty()));
        assertThat(activity.get(unreadable.id()).lastMessage()).isEmpty();
        verify(logs, never()).findLatest(eq(deleted.id()), any(), anyInt());
    }

    private static AgentTask task()
    {
        return AgentTask.created(TaskId.random(), Optional.of("workspace"), true, "Fix the checkpoint", new AgentLaneId("codex"),
                new RepositoryIdentity("owner", "repository"), Path.of("/tmp/activity-worktree"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }

    private static AgentEventEnvelope event(TurnId turn, AgentEventSource source, AgentEventPayload payload)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, TaskId.random(), Optional.of(turn), source, NOW, payload);
    }
}
