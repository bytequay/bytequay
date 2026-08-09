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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator.SteeringAdmission;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Predecessor;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2StageSteeringRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final ResultFence PENDING = new ResultFence(
            3, "stage-1", 2, "previous-operation", 1,
            "code-1", "head-1", "base-1");
    private static final Predecessor PREDECESSOR = new Predecessor(
            "STAGE_TURN", "previous-turn", "IMPLEMENT_LOCAL_PLAN",
            "previous-ticket", "previous-operation", 1,
            "code-1", "head-1", "base-1");

    @Test
    void exactReplacementReplayReturnsTheSameRequestAfterAdmission()
    {
        Harness harness = harness();

        String accepted = harness.runtime().steer(
                "task-1", "stage-1", "Retry the stage", List.of(),
                V2StageSteeringRuntime.Mode.CANCEL_AND_REPLACE,
                PREDECESSOR.ownerId());
        String replay = harness.runtime().steer(
                "task-1", "stage-1", "Retry the stage", List.of(),
                V2StageSteeringRuntime.Mode.CANCEL_AND_REPLACE,
                PREDECESSOR.ownerId());

        assertThat(replay).isEqualTo(accepted);
        assertThat(harness.persisted().get())
                .extracting(Request::status, Request::successorOwnerId)
                .containsExactly("ADMITTED", "replacement-turn");
        verify(harness.store(), times(1)).insert(any(), any());
        verify(harness.local(), times(1))
                .replaceMalformedResultPendingInCommand(any(), anyLong());
        verify(harness.store(), never()).predecessorQuiesced(any());
        verify(harness.tickets(), times(1))
                .requestCancel(PREDECESSOR.ticketId());
        verify(harness.tickets(), never())
                .requestCancel("replacement-ticket");
    }

    @Test
    void exactReplacementRejectsAChangedPredecessor()
    {
        Harness harness = harness();

        assertThatThrownBy(() -> harness.runtime().steer(
                "task-1", "stage-1", "Retry the stage", List.of(),
                V2StageSteeringRuntime.Mode.CANCEL_AND_REPLACE,
                "stale-turn"))
                .isInstanceOf(CommandRejectedException.class)
                .hasMessageContaining("stale-turn")
                .hasMessageContaining(PREDECESSOR.ownerId());

        verify(harness.store(), never()).insert(any(), any());
        verify(harness.tickets(), never()).requestCancel(anyString());
        verify(harness.local(), never())
                .admitSteeringInCommand(any(), anyLong());
    }

    @Test
    void newLocalReviewSteeringIsRejectedWhileBaseSyncOwnsTheStage()
    {
        Harness harness = harness(owner(null, StageCheckpoint.LOCAL_REVIEW));
        when(harness.store().hasLiveLocalPublishBaseSync(any()))
                .thenReturn(true);

        assertThatThrownBy(() -> harness.runtime().steer(
                "task-1", "stage-1", "Change the review", List.of(),
                V2StageSteeringRuntime.Mode.APPEND, null))
                .isInstanceOf(CommandRejectedException.class)
                .hasMessageContaining("active publish base sync");

        verify(harness.store(), never()).insert(any(), any());
        verify(harness.local(), never())
                .admitSteeringInCommand(any(), anyLong());
    }

    @Test
    void baseSyncOpenedBetweenPersistenceAndAdmissionQueuesSteering()
    {
        Harness harness = harness(owner(null, StageCheckpoint.LOCAL_REVIEW));
        when(harness.store().hasLiveLocalPublishBaseSync(any()))
                .thenReturn(false, true);

        String requestId = harness.runtime().steer(
                "task-1", "stage-1", "Change the review", List.of(),
                V2StageSteeringRuntime.Mode.APPEND, null);

        assertThat(requestId).isEqualTo(harness.persisted().get().id());
        assertThat(harness.persisted().get().status()).isEqualTo("PENDING");
        verify(harness.store()).insert(any(), any());
        verify(harness.local(), never())
                .admitSteeringInCommand(any(), anyLong());
    }

    private static Harness harness()
    {
        return harness(owner(PENDING));
    }

    private static Harness harness(StageManager.OwnerState initialOwner)
    {
        TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
        when(commands.execute(anyString(), any())).thenAnswer(invocation ->
                invocation.<Supplier<?>>getArgument(1).get());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(commands).executeVoid(anyString(), any());

        AtomicReference<StageManager.OwnerState> owner =
                new AtomicReference<>(initialOwner);
        StageManager.Store stages = mock(StageManager.Store.class);
        when(stages.findOwner("task-1", "stage-1")).thenAnswer(invocation ->
                Optional.of(owner.get()));

        AtomicReference<Request> persisted = new AtomicReference<>();
        SqliteStageSteeringStore store = mock(SqliteStageSteeringStore.class);
        when(store.findByCommand(anyString())).thenAnswer(invocation -> {
            Request current = persisted.get();
            return current != null
                    && current.commandId().equals(invocation.getArgument(0))
                    ? Optional.of(current) : Optional.empty();
        });
        when(store.findPredecessor(PENDING))
                .thenReturn(Optional.of(PREDECESSOR));
        doAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return null;
        }).when(store).insert(any(), any());
        when(store.find(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(persisted.get()));
        when(store.malformedLocalResultPendingReady(any(), any()))
                .thenReturn(true);
        when(store.predecessorQuiesced(any())).thenReturn(true);
        doAnswer(invocation -> {
            persisted.set(withAdmission(
                    persisted.get(),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3)));
            return null;
        }).when(store).markAdmitted(
                anyString(), anyString(), anyString(), anyString(), any());

        LocalDevelopmentRuntimeCoordinator local =
                mock(LocalDevelopmentRuntimeCoordinator.class);
        when(local.replaceMalformedResultPendingInCommand(any(), anyLong()))
                .thenReturn(new SteeringAdmission(
                        "replacement-turn", "replacement-operation",
                        "replacement-ticket"));
        ChatAttachmentStore attachments = mock(ChatAttachmentStore.class);
        when(attachments.save("stage-1", List.of())).thenReturn(List.of());
        DispatchTicketControl tickets = mock(DispatchTicketControl.class);
        ObjectProvider<RemoteRepairTurnRuntime> remoteRepairs =
                mock(ObjectProvider.class);
        ObjectProvider<RemoteFeedbackRuntimeCoordinator> remoteFeedback =
                mock(ObjectProvider.class);
        V2StageSteeringRuntime runtime = new V2StageSteeringRuntime(
                commands, stages, store, local,
                mock(PlanRuntimeCoordinator.class), attachments, tickets,
                remoteRepairs, remoteFeedback,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Harness(runtime, store, local, tickets, persisted);
    }

    private static StageManager.OwnerState owner(ResultFence pending)
    {
        return owner(pending, StageCheckpoint.IMPLEMENTING);
    }

    private static StageManager.OwnerState owner(
            ResultFence pending, StageCheckpoint checkpoint)
    {
        StageManager.State stage = new StageManager.State(
                "stage-1", "task-1", StageKind.LOCAL_DEVELOPMENT, 2, 7,
                checkpoint, null, pending);
        return new StageManager.OwnerState(
                "task-1", TaskLifecycle.ACTIVE, 3, "stage-1", stage);
    }

    private static Request withAdmission(
            Request request, String ownerKind, String ownerId,
            String operationId)
    {
        return new Request(
                request.id(), request.commandId(), request.taskId(),
                request.taskEpoch(), request.stageId(), request.stageKind(),
                request.stageGeneration(), request.acceptedStageVersion(),
                request.acceptedCheckpoint(), request.mode(), request.body(),
                request.contentDigest(), request.predecessor(), "ADMITTED",
                ownerKind, ownerId, operationId,
                request.requestedBy(), request.requestedAt());
    }

    private record Harness(
            V2StageSteeringRuntime runtime,
            SqliteStageSteeringStore store,
            LocalDevelopmentRuntimeCoordinator local,
            DispatchTicketControl tickets,
            AtomicReference<Request> persisted) {}
}
