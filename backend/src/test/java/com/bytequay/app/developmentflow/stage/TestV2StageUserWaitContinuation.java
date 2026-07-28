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

import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator.SteeringAdmission;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2StageUserWaitContinuation
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    private static final Predecessor PREDECESSOR = new Predecessor(
            "STAGE_TURN", "previous-turn", "IMPLEMENT_LOCAL_PLAN",
            "previous-ticket", "previous-operation", 1,
            "code-1", "head-1", "base-1");

    @Test
    void localContinuationAdmitsOneStableSuccessor()
    {
        Harness harness = harness(
                StageKind.LOCAL_DEVELOPMENT,
                StageCheckpoint.IMPLEMENTING,
                fence(PREDECESSOR), true);
        when(harness.local().admitUserWaitContinuationInCommand(any(), anyLong()))
                .thenReturn(new SteeringAdmission(
                        "local-next", "local-next-operation", "local-ticket"));

        V2StageSteeringRuntime.ContinuationResult accepted =
                harness.runtime().continueUserWait(
                        PREDECESSOR.ownerId(), PREDECESSOR.operationId(),
                        "task-1", "stage-1", "QUESTION", "question-1", "Proceed");
        V2StageSteeringRuntime.ContinuationResult replay =
                harness.runtime().continueUserWait(
                        PREDECESSOR.ownerId(), PREDECESSOR.operationId(),
                        "task-1", "stage-1", "QUESTION", "question-1", "Proceed");

        assertThat(accepted.status()).isEqualTo("ADMITTED");
        assertThat(accepted.successorTurnId()).isEqualTo("local-next");
        assertThat(replay).isEqualTo(accepted);
        verify(harness.local(), times(1))
                .admitUserWaitContinuationInCommand(any(), anyLong());
        verify(harness.store()).insertUserWaitLink(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void remoteContinuationReturnsToTheRepairOwner()
    {
        Predecessor remote = new Predecessor(
                "STAGE_TURN", "previous-remote-turn", "REMOTE_CI_REPAIR",
                "previous-remote-ticket", "previous-remote-operation", 2,
                "code-1", "head-1", "base-1");
        Harness harness = harness(
                StageKind.REMOTE_DEVELOPMENT,
                StageCheckpoint.WAITING_CI, null, true, remote);
        RemoteRepairTurnRuntime repairs = harness.remoteRepairs().getObject();
        when(repairs.admitSteeringInCommand(any())).thenReturn(
                new SqliteRemoteRepairTurnStore.TurnRequest(
                        "CI", "repair-row", "episode-1", "step-1",
                        "remote-next", "remote-next-operation",
                        "remote-next-ticket", 3));

        V2StageSteeringRuntime.ContinuationResult accepted =
                harness.runtime().continueUserWait(
                        remote.ownerId(), remote.operationId(),
                        "task-1", "stage-1", "PERMISSION", "permission-1",
                        "Allow once");

        assertThat(accepted.status()).isEqualTo("ADMITTED");
        assertThat(accepted.successorTurnId()).isEqualTo("remote-next");
        verify(repairs).admitSteeringInCommand(any());
        verify(harness.local(), never())
                .admitUserWaitContinuationInCommand(any(), anyLong());
    }

    @Test
    void changedCodeSubjectSupersedesWithoutStartingAWriter()
    {
        Harness harness = harness(
                StageKind.LOCAL_DEVELOPMENT,
                StageCheckpoint.IMPLEMENTING,
                fence(PREDECESSOR), false);

        V2StageSteeringRuntime.ContinuationResult result =
                harness.runtime().continueUserWait(
                        PREDECESSOR.ownerId(), PREDECESSOR.operationId(),
                        "task-1", "stage-1", "QUESTION", "question-stale", "Proceed");

        assertThat(result.status()).isEqualTo("SUPERSEDED");
        verify(harness.store()).markSuperseded(anyString(), anyString());
        verify(harness.local(), never())
                .admitUserWaitContinuationInCommand(any(), anyLong());
        verify(harness.remoteRepairs().getObject(), never())
                .admitSteeringInCommand(any());
    }

    private static Harness harness(
            StageKind kind,
            StageCheckpoint checkpoint,
            ResultFence pending,
            boolean currentSubject)
    {
        return harness(kind, checkpoint, pending, currentSubject, PREDECESSOR);
    }

    @SuppressWarnings("unchecked")
    private static Harness harness(
            StageKind kind,
            StageCheckpoint checkpoint,
            ResultFence pending,
            boolean currentSubject,
            Predecessor predecessor)
    {
        TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
        when(commands.execute(anyString(), any())).thenAnswer(invocation ->
                invocation.<Supplier<?>>getArgument(1).get());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(commands).executeVoid(anyString(), any());
        StageManager.Store stages = mock(StageManager.Store.class);
        StageManager.State stage = new StageManager.State(
                "stage-1", "task-1", kind, 2, 7, checkpoint, null, pending);
        when(stages.findOwner("task-1", "stage-1")).thenReturn(Optional.of(
                new StageManager.OwnerState(
                        "task-1", TaskLifecycle.ACTIVE, 3, "stage-1", stage)));
        SqliteStageSteeringStore store = mock(SqliteStageSteeringStore.class);
        AtomicReference<Request> persisted = new AtomicReference<>();
        when(store.userWaitSuccessor(anyString(), anyString())).thenAnswer(invocation -> {
            Request current = persisted.get();
            return current != null && "ADMITTED".equals(current.status())
                    ? Optional.of(current.successorOwnerId())
                    : Optional.empty();
        });
        when(store.findByCommand(anyString())).thenReturn(Optional.empty());
        when(store.findUserWaitPredecessor(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(predecessor));
        doAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return null;
        }).when(store).insert(any(), any());
        when(store.find(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(persisted.get()));
        when(store.isUserWaitContinuation(anyString())).thenReturn(true);
        when(store.predecessorQuiesced(any())).thenReturn(true);
        when(store.userWaitSubjectIsCurrent(any())).thenReturn(currentSubject);
        doAnswer(invocation -> {
            Request current = persisted.get();
            persisted.set(withStatus(
                    current, "ADMITTED", invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3)));
            return null;
        }).when(store).markAdmitted(
                anyString(), anyString(), anyString(), anyString(), any());
        doAnswer(invocation -> {
            persisted.set(withStatus(
                    persisted.get(), "SUPERSEDED", null, null, null));
            return null;
        }).when(store).markSuperseded(anyString(), anyString());
        LocalDevelopmentRuntimeCoordinator local =
                mock(LocalDevelopmentRuntimeCoordinator.class);
        PlanRuntimeCoordinator plan = mock(PlanRuntimeCoordinator.class);
        ObjectProvider<RemoteRepairTurnRuntime> remoteRepairs =
                mock(ObjectProvider.class);
        RemoteRepairTurnRuntime repairs = mock(RemoteRepairTurnRuntime.class);
        when(remoteRepairs.getObject()).thenReturn(repairs);
        ObjectProvider<RemoteFeedbackRuntimeCoordinator> remoteFeedback =
                mock(ObjectProvider.class);
        when(remoteFeedback.getObject()).thenReturn(
                mock(RemoteFeedbackRuntimeCoordinator.class));
        V2StageSteeringRuntime runtime = new V2StageSteeringRuntime(
                commands, stages, store, local, plan,
                mock(ChatAttachmentStore.class), mock(DispatchTicketControl.class),
                remoteRepairs, remoteFeedback,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Harness(runtime, store, local, remoteRepairs);
    }

    private static Request withStatus(
            Request request,
            String status,
            String ownerKind,
            String ownerId,
            String operationId)
    {
        return new Request(
                request.id(), request.commandId(), request.taskId(),
                request.taskEpoch(), request.stageId(), request.stageKind(),
                request.stageGeneration(), request.acceptedStageVersion(),
                request.acceptedCheckpoint(), request.mode(), request.body(),
                request.contentDigest(), request.predecessor(), status,
                ownerKind, ownerId, operationId,
                request.requestedBy(), request.requestedAt());
    }

    private static ResultFence fence(Predecessor predecessor)
    {
        return new ResultFence(
                3, "stage-1", 2, predecessor.operationId(),
                predecessor.attempt(), predecessor.codeFingerprint(),
                predecessor.headSha(), predecessor.baseSha());
    }

    private record Harness(
            V2StageSteeringRuntime runtime,
            SqliteStageSteeringStore store,
            LocalDevelopmentRuntimeCoordinator local,
            ObjectProvider<RemoteRepairTurnRuntime> remoteRepairs) {}
}
