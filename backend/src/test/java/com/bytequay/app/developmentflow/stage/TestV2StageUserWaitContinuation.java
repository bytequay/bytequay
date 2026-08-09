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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator.SteeringAdmission;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Attachment;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.CliContinuation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.LocalContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.LocalTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Predecessor;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
    void cliSessionIsReusedForDurableUserWaitButNotExplicitReplacement()
            throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        SqliteStageSteeringStore store = mock(SqliteStageSteeringStore.class);
        LocalDevelopmentStageManager local =
                mock(LocalDevelopmentStageManager.class);
        LocalContext context = new LocalContext(
                "task-1", "trunk-1", "workspace-1", 3,
                "stage-1", 2, 7, StageCheckpoint.IMPLEMENTING,
                "code-1", "head-1", "base-1", "/tmp/task-1",
                "{\"kind\":\"CLI\",\"agentOrProvider\":\"codex\","
                        + "\"model\":\"gpt-5.6\",\"account\":null,"
                        + "\"reasoningEffort\":null}",
                "codex", "gpt-5.6", null);
        Attachment repeated = new Attachment(
                1, "image/png", "/tmp/source.png", "a".repeat(64));
        Attachment current = new Attachment(
                2, "image/jpeg", "/tmp/current.jpg", "b".repeat(64));
        var predecessorLaunch = json.createObjectNode();
        predecessorLaunch.put("prompt", "original instruction");
        var predecessorImages = predecessorLaunch.putArray("images");
        predecessorImages.addObject()
                .put("path", repeated.contentRef())
                .put("mediaType", repeated.mediaType())
                .put("digest", repeated.contentDigest());
        when(store.requireLocalContext(any(), eq(7L))).thenReturn(context);
        when(store.attachments(anyString()))
                .thenReturn(List.of(repeated, current));
        when(store.isUserWaitContinuation("wait-request")).thenReturn(true);
        when(store.cliContinuation(
                any(Request.class), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new CliContinuation(
                        predecessorLaunch.toString(),
                        "execution-1", "session-1")));
        when(store.executionLog("execution-1"))
                .thenReturn(List.of("assistant asked a question"));
        when(local.replaceImplementationTurnInCommand(any(), any(), anyString()))
                .thenReturn(CommandResult.applied(mock(StageManager.State.class)));
        when(local.requestImplementationInCommand(any(), any(), anyString()))
                .thenReturn(CommandResult.applied(mock(StageManager.State.class)));
        LocalDevelopmentRuntimeCoordinator runtime =
                new LocalDevelopmentRuntimeCoordinator(
                        mock(TaskCommandExecutor.class), mock(TaskManager.class),
                        local, mock(SqliteLocalDevelopmentRuntimeStore.class),
                        mock(PRService.class), json,
                        Clock.fixed(NOW, ZoneOffset.UTC), 8080);
        runtime.setSteeringStore(store);
        Request wait = steeringRequest(
                "wait-request",
                V2StageSteeringRuntime.Mode.CANCEL_AND_REPLACE);
        Request replacement = steeringRequest(
                "replace-request",
                V2StageSteeringRuntime.Mode.CANCEL_AND_REPLACE);

        try (MockedStatic<TaskCommandExecutor> ignored =
                mockStatic(TaskCommandExecutor.class)) {
            runtime.admitUserWaitContinuationInCommand(wait, 7);
            runtime.admitSteeringInCommand(replacement, 7);
        }

        ArgumentCaptor<LocalTurn> waitTurn = ArgumentCaptor.forClass(LocalTurn.class);
        verify(store).insertLocalContinuationTurn(
                waitTurn.capture(), eq(PREDECESSOR));
        JsonNode waitLaunch = json.readTree(waitTurn.getValue().launchInput());
        assertThat(waitLaunch.path("resumeSessionId").asText())
                .isEqualTo("session-1");
        assertThat(waitLaunch.path("fallbackPrompt").asText())
                .contains("original instruction")
                .contains("assistant asked a question")
                .contains("Proceed");
        assertThat(waitLaunch.path("images").findValuesAsText("path"))
                .containsExactly("/tmp/source.png", "/tmp/current.jpg");

        ArgumentCaptor<LocalTurn> replacementTurn =
                ArgumentCaptor.forClass(LocalTurn.class);
        verify(store).insertLocalTurn(replacementTurn.capture());
        JsonNode replacementLaunch =
                json.readTree(replacementTurn.getValue().launchInput());
        assertThat(replacementLaunch.has("resumeSessionId")).isFalse();
        assertThat(replacementLaunch.has("fallbackPrompt")).isFalse();
        assertThat(replacementLaunch.path("images").findValuesAsText("path"))
                .containsExactly("/tmp/source.png", "/tmp/current.jpg");
    }

    @Test
    void lateUserWaitReconstructsExactHistoryWithoutBranchingTheCliSession()
    {
        ObjectMapper json = new ObjectMapper();
        SqliteStageSteeringStore store = mock(SqliteStageSteeringStore.class);
        Request request = steeringRequest(
                "wait-request", V2StageSteeringRuntime.Mode.CANCEL_AND_REPLACE);
        String sourceDigest = "a".repeat(64);
        String previousLaunch = """
                {"prompt":"original instruction","images":[
                  {"path":"/tmp/source.png","mediaType":"image/png",
                   "digest":"%s"}]}
                """.formatted(sourceDigest);
        when(store.isUserWaitContinuation(request.id())).thenReturn(true);
        when(store.cliContinuation(
                any(Request.class), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new CliContinuation(
                        previousLaunch,
                        "execution-1", "session-1", false)));
        when(store.executionLog("execution-1"))
                .thenReturn(List.of("assistant asked the exact question"));
        var launch = json.createObjectNode();
        launch.put("prompt", "Proceed");
        StageCliContinuity.freezeImages(
                json, launch, List.of(new Attachment(
                        1, "image/jpeg", "/tmp/current.jpg",
                        "b".repeat(64))));

        StageCliContinuity.apply(
                json, launch, request, WorkModelKind.CLI, "Proceed", store,
                new StageCliContinuity.Fence(
                        "stage-1", 2, "code-1", "head-1", "base-1",
                        "codex", "gpt-5.6", "/tmp/task-1"));

        assertThat(launch.path("prompt").asText())
                .contains("original instruction")
                .contains("assistant asked the exact question")
                .contains("Proceed");
        assertThat(launch.has("resumeSessionId")).isFalse();
        assertThat(launch.has("fallbackPrompt")).isFalse();
        assertThat(launch.path("images").findValuesAsText("path"))
                .containsExactly("/tmp/source.png", "/tmp/current.jpg");
    }

    @Test
    void apiExactContinuationsReconstructWithoutAResumeToken()
    {
        ObjectMapper json = new ObjectMapper();
        SqliteStageSteeringStore store = mock(SqliteStageSteeringStore.class);
        CliContinuation source = new CliContinuation(
                "{\"prompt\":\"original instruction\"}",
                "execution-1", "session-1");
        when(store.cliContinuation(
                eq(PREDECESSOR.ownerId()), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(Optional.of(source));
        Request userWait = steeringRequest(
                "wait-request", V2StageSteeringRuntime.Mode.CANCEL_AND_REPLACE);
        when(store.isUserWaitContinuation(userWait.id())).thenReturn(true);
        when(store.cliContinuation(
                any(Request.class), anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString(),
                anyString())).thenReturn(Optional.of(source));
        when(store.executionLog("execution-1"))
                .thenReturn(List.of("durable API execution trace"));
        var automatic = json.createObjectNode().put("prompt", "Address findings");
        var answer = json.createObjectNode().put("prompt", "Proceed");
        StageCliContinuity.Fence fence = new StageCliContinuity.Fence(
                "stage-1", 2, "code-1", "head-1", "base-1",
                "openai", "gpt-5.6", "/tmp/task-1");

        StageCliContinuity.applyExact(
                json, automatic, PREDECESSOR.ownerId(), WorkModelKind.API,
                "Address findings", store, fence);
        StageCliContinuity.apply(
                json, answer, userWait, WorkModelKind.API, "Proceed", store,
                fence);

        assertThat(automatic.path("prompt").asText())
                .contains("original instruction")
                .contains("durable API execution trace")
                .contains("Address findings");
        assertThat(answer.path("prompt").asText())
                .contains("original instruction")
                .contains("durable API execution trace")
                .contains("Proceed");
        assertThat(List.of(automatic, answer)).allSatisfy(launch -> {
            assertThat(launch.has("resumeSessionId")).isFalse();
            assertThat(launch.has("fallbackPrompt")).isFalse();
        });
    }

    @Test
    void stageLaunchRejectsAnInvalidPersistedImageDigest()
    {
        ObjectMapper json = new ObjectMapper();
        assertThatThrownBy(() -> StageCliContinuity.freezeImages(
                json, json.createObjectNode(),
                List.of(new Attachment(
                        1, "image/png", "/tmp/image.png", "not-a-digest"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
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
        when(repairs.prepareCiSteeringInCommand(any())).thenReturn(true);
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
        verify(repairs).prepareCiSteeringInCommand(any());
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

    private static Request steeringRequest(
            String requestId, V2StageSteeringRuntime.Mode mode)
    {
        return new Request(
                requestId, "command-" + requestId, "task-1", 3,
                "stage-1", StageKind.LOCAL_DEVELOPMENT, 2, 7,
                StageCheckpoint.IMPLEMENTING, mode, "Proceed",
                "a".repeat(64), PREDECESSOR, "PENDING", null, null, null,
                "user", NOW);
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
