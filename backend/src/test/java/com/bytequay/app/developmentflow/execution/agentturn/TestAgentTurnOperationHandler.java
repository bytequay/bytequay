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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ToolExposurePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE_TURN;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK_TURN;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.STAGE_OPERATION_KIND;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.TASK_OPERATION_KIND;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.TASK_OUTCOME_SUMMARY_OPERATION_KIND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAgentTurnOperationHandler
{
    private static final String WORKTREE = "/tmp/worktree-task-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorktreeWriterLeaseManager writers;
    private FakeProvider provider;

    @BeforeEach
    void setUp()
    {
        writers = mock(WorktreeWriterLeaseManager.class);
        provider = new FakeProvider();
    }

    @Test
    void executesExactTaskBrainInputReadOnlyAndReturnsTypedRawEvidence()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(TASK_TURN, false);
        AgentTurnOperationHandler handler = handler(turn(TASK_TURN, launchInput()));
        ExecutionContext context = context(envelope);
        provider.result = successful();

        DispatchTicket.DispatchResult result = handler.execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.READ_ONLY);
        assertThat(provider.request.transport())
                .isEqualTo(AgentTurnProviderSession.Transport.CLI);
        assertThat(provider.request.prompt()).isEqualTo("review exact revision");
        assertThat(provider.request.systemPrompt()).isEqualTo("brain role");
        assertThat(provider.request.workingDirectory()).isEqualTo(Path.of(WORKTREE));
        verify(writers, never()).acquire(any(), any());
        verify(context).providerSession("codex", "session-1");
        verify(context).processStarted(123, "test/provider");
        verify(context).appendLog(0, "{\"event\":\"started\"}");
        verify(context).recordUsage(11, 7, 3);

        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result);
        assertThat(decoded.payload().finalText()).isEqualTo("approved");
        assertThat(decoded.payload().providerSessionId()).isEqualTo("session-1");
        assertThat(decoded.payload().disposition())
                .isEqualTo(AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED);
    }

    @Test
    void executesTerminalTaskOutcomeSummaryAsANonExclusiveTaskTurn()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = summaryEnvelope();
        AgentTurnOperationHandler handler = handler(summaryTurn());

        DispatchTicket.DispatchResult result = handler.execute(context(envelope));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.READ_ONLY);
        assertThat(provider.request.toolEndpoint().ownerKind()).isEqualTo(TASK_TURN);
        assertThat(provider.request.toolEndpoint().profile())
                .isEqualTo(AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY);
        assertThat(envelope.capacityRequest().exclusiveTask()).isFalse();
        verify(writers, never()).acquire(any(), any());
        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result);
        assertThat(decoded.payload().purpose())
                .isEqualTo("TASK_COMPLETION_SUMMARY");
    }

    @Test
    void admitsOnlyExplicitTerminalTaskBrainConversationWithoutTaskLease()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = terminalConversationEnvelope();
        AgentTurnOperationHandler.ExactTurn conversation = terminalTurn(
                "TASK_BRAIN_CONVERSATION");

        DispatchTicket.DispatchResult accepted = handler(conversation)
                .execute(context(envelope));

        assertThat(accepted.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(envelope.capacityRequest().exclusiveTask()).isFalse();
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.READ_ONLY);

        provider = new FakeProvider();
        DispatchTicket.DispatchResult generic = handler(terminalTurn("BRAIN_REVIEW"))
                .execute(context(envelope));
        assertThat(generic.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(generic.error()).contains("capacity scope or writer mode");
        assertThat(provider.opens).isZero();
    }

    @Test
    void stageTurnAcquiresExactWriterLeaseBeforeOpeningProvider()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = turn(
                STAGE_TURN, launchInput(STAGE_TURN));
        AgentTurnOperationHandler handler = handler(turn);
        ExecutionContext context = context(envelope);
        WorktreeWriterLeaseManager.Lease lease = new WorktreeWriterLeaseManager.Lease(
                WORKTREE, "task-1", "operation-1", 1, 19, "dispatcher",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(30));
        when(writers.acquire(context, WORKTREE)).thenReturn(lease);
        WorktreeWriterLeaseManager.WriterAuthorization authorization =
                mock(WorktreeWriterLeaseManager.WriterAuthorization.class);
        WorktreeWriterLeaseManager.MutationFence mutationFence =
                mock(WorktreeWriterLeaseManager.MutationFence.class);
        when(mutationFence.worktreePath()).thenReturn(WORKTREE);
        when(mutationFence.taskId()).thenReturn("task-1");
        when(mutationFence.operationId()).thenReturn("operation-1");
        when(mutationFence.taskEpoch()).thenReturn(1L);
        when(mutationFence.fencingToken()).thenReturn(19L);
        when(writers.authorizeMutation(context, lease)).thenReturn(authorization);
        doAnswer(invocation -> {
            provider.events.add("authorize");
            Function<WorktreeWriterLeaseManager.MutationFence, ?> mutation =
                    invocation.getArgument(0);
            return mutation.apply(mutationFence);
        }).when(authorization).run(any());
        provider.result = successful();

        DispatchTicket.DispatchResult result = handler.execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.WORKTREE_WRITE);
        assertThat(provider.session.writerFence.fencingToken()).isEqualTo(19);
        assertThat(provider.session.writerFence.operationId()).isEqualTo("operation-1");
        assertThat(provider.events).containsExactly("open", "authorize", "start");
        verify(writers).acquire(context, WORKTREE);
        verify(writers).authorizeMutation(context, lease);
        AgentTurnOperationHandler.Evidence evidence = MAPPER.readValue(
                result.evidenceJson(), AgentTurnOperationHandler.Evidence.class);
        assertThat(evidence.writerFence().fencingToken()).isEqualTo(19);
    }

    @Test
    void rejectsBrainWriterCapacityAndStaleCodeWithoutLaunchingProvider()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope invalidBrain = envelope(TASK_TURN, true);
        AgentTurnOperationHandler handler = handler(turn(TASK_TURN, launchInput()));

        DispatchTicket.DispatchResult invalidCapacity = handler.execute(context(invalidBrain));

        assertThat(invalidCapacity.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(provider.opens).isZero();

        AgentTurnOperationHandler.ExactTurn stale = withCurrentFingerprint(
                turn(STAGE_TURN, launchInput(STAGE_TURN)), "new-fingerprint");
        DispatchTicket.DispatchResult staleResult = handler(stale)
                .execute(context(envelope(STAGE_TURN, true)));

        assertThat(staleResult.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(staleResult.error()).contains("code subject");
        assertThat(provider.opens).isZero();
    }

    @Test
    void strictFrozenInputRejectsUnknownFieldsAndWrongBrainIdentity()
            throws Exception
    {
        String unknown = launchInput().replace(
                "\"prompt\":\"review exact revision\"",
                "\"prompt\":\"review exact revision\",\"command\":\"rm -rf /\"");
        DispatchTicket.DispatchResult unknownResult = handler(turn(TASK_TURN, unknown))
                .execute(context(envelope(TASK_TURN, false)));
        assertThat(unknownResult.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(unknownResult.error()).contains("invalid frozen");

        String wrongModel = launchInput().replace("gpt-5.6", "other-model");
        DispatchTicket.DispatchResult wrongIdentity = handler(turn(TASK_TURN, wrongModel))
                .execute(context(envelope(TASK_TURN, false)));
        assertThat(wrongIdentity.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(wrongIdentity.error()).contains("immutable identity");
        assertThat(provider.opens).isZero();
    }

    @Test
    void apiTransportUsesOnlyTheApiCapacityLane()
            throws Exception
    {
        String input = launchInput()
                .replace("\"transport\":\"CLI\"", "\"transport\":\"API\"")
                .replace("\"provider\":\"codex\"", "\"provider\":\"openai\"")
                .replace("\"model\":\"gpt-5.6\"", "\"model\":\"gpt-5.6-api\"");
        AgentTurnOperationHandler.ExactTurn turn = withBrainIdentity(
                turn(TASK_TURN, input), "openai", "gpt-5.6-api");
        DispatchTicket.DispatchEnvelope envelope = envelope(
                TASK_TURN, false, CapacityManager.CapacityLane.API);

        DispatchTicket.DispatchResult result = handler(turn).execute(context(envelope));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.request.transport())
                .isEqualTo(AgentTurnProviderSession.Transport.API);
        verify(writers, never()).acquire(any(), any());
    }

    @Test
    void rejectsNonNormalizedFrozenWorktreePath()
            throws Exception
    {
        String input = launchInput().replace(
                "/tmp/worktree-task-1", "/tmp/ignored/../worktree-task-1");

        DispatchTicket.DispatchResult result = handler(turn(TASK_TURN, input))
                .execute(context(envelope(TASK_TURN, false)));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.error()).contains("normalized");
        assertThat(provider.opens).isZero();
    }

    @Test
    void cancellationStopsTheExactProviderSession()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(TASK_TURN, false);
        AgentTurnOperationHandler handler = handler(turn(TASK_TURN, launchInput()));
        ExecutionContext context = mock(ExecutionContext.class);
        AtomicBoolean cancelRequested = new AtomicBoolean();
        when(context.envelope()).thenReturn(envelope);
        when(context.isCancellationRequested()).thenAnswer(ignored -> cancelRequested.get());
        doAnswer(invocation -> {
            cancelRequested.set(true);
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(context).onCancellation(any());
        provider.result = new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.CANCELED,
                null, "", 0, 0, 0, 123L, "provider session canceled");

        DispatchTicket.DispatchResult result = handler.execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.CANCELED);
        assertThat(provider.session.canceled).isTrue();
    }

    @Test
    void userWaitStopsProviderButSucceedsTheExactTurn()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(TASK_TURN, false);
        ActiveAgentContextRegistry contexts = new ActiveAgentContextRegistry();
        AgentTurnOperationHandler.ExactTurn turn = turn(TASK_TURN, launchInput());
        AgentTurnOperationHandler handler = new AgentTurnOperationHandler(
                (kind, id) -> kind == turn.ownerKind() && id.equals(turn.turnId())
                        ? Optional.of(turn) : Optional.empty(),
                provider, writers, contexts, new ToolExposurePolicy(), MAPPER);
        provider.onStart = () -> assertThat(contexts.requestStop(
                "trunk-1",
                AgentTurnOperationHandler.mcpAgentKey(
                        TASK_TURN, "task-turn-1", "operation-1"),
                "USER_WAIT:QUESTION:question-1")).isTrue();

        DispatchTicket.DispatchResult result = handler.execute(context(envelope));

        AgentTurnOperationHandler.RawResult payload = MAPPER.readValue(
                result.payloadJson(), AgentTurnOperationHandler.RawResult.class);
        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(payload.disposition())
                .isEqualTo(AgentTurnOperationHandler.Disposition.USER_WAIT);
        assertThat(payload.userWait())
                .isEqualTo(new AgentTurnOperationHandler.UserWaitRef(
                        "QUESTION", "question-1"));
        assertThat(provider.session.canceled).isTrue();
    }

    @Test
    void providerSuccessRacingCancellationIsStillDeliveredToTheOwner()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(TASK_TURN, false);
        AgentTurnOperationHandler handler = handler(turn(TASK_TURN, launchInput()));
        ExecutionContext context = mock(ExecutionContext.class);
        AtomicBoolean cancelRequested = new AtomicBoolean();
        AtomicReference<Runnable> cancelAction = new AtomicReference<>();
        when(context.envelope()).thenReturn(envelope);
        when(context.isCancellationRequested()).thenAnswer(
                ignored -> cancelRequested.get());
        doAnswer(invocation -> {
            cancelAction.set(invocation.getArgument(0));
            return null;
        }).when(context).onCancellation(any());
        provider.onStart = () -> {
            cancelRequested.set(true);
            cancelAction.get().run();
        };
        provider.result = successful();

        DispatchTicket.DispatchResult result = handler.execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.session.canceled).isTrue();
        assertThat(new AgentTurnOwnerResultCodec(MAPPER).decode(
                envelope.owner(), envelope.fence(), result).payload().finalText())
                .isEqualTo("approved");
    }

    @Test
    void restartReconciliationNeverReopensTheProvider()
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler handler = handler(
                turn(STAGE_TURN, launchInput(STAGE_TURN)));

        DispatchTicket.DispatchResult result = handler.reconcile(context(envelope));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.INDETERMINATE);
        assertThat(result.error()).contains("reconciliation");
        assertThat(provider.opens).isZero();
    }

    @Test
    void ownerCodecRejectsAResultForAnotherFence()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(TASK_TURN, false);
        DispatchTicket.DispatchResult result = handler(turn(TASK_TURN, launchInput()))
                .execute(context(envelope));
        DispatchTicket.OperationFence stale = new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 2,
                "fingerprint-1", "head-1", "base-1");

        assertThatThrownBy(() -> new AgentTurnOwnerResultCodec(MAPPER)
                .decode(envelope.owner(), stale, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale");
    }

    private AgentTurnOperationHandler handler(AgentTurnOperationHandler.ExactTurn turn)
    {
        return new AgentTurnOperationHandler(
                (kind, id) -> kind == turn.ownerKind() && id.equals(turn.turnId())
                        ? Optional.of(turn) : Optional.empty(),
                provider,
                writers,
                MAPPER);
    }

    private static ExecutionContext context(DispatchTicket.DispatchEnvelope envelope)
    {
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.envelope()).thenReturn(envelope);
        return context;
    }

    private static DispatchTicket.DispatchEnvelope envelope(
            DispatchTicket.OwnerKind ownerKind, boolean writer)
    {
        return envelope(ownerKind, writer, CapacityManager.CapacityLane.CLI);
    }

    private static DispatchTicket.DispatchEnvelope envelope(
            DispatchTicket.OwnerKind ownerKind,
            boolean writer,
            CapacityManager.CapacityLane lane)
    {
        boolean stage = ownerKind == STAGE_TURN;
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 1,
                "fingerprint-1", "head-1", "base-1");
        CapacityManager.CapacityRequest capacity = new CapacityManager.CapacityRequest(
                "operation-1",
                CapacityManager.WorkflowSource.V2,
                Set.of(lane),
                new CapacityManager.CapacityScope("workspace-1", "trunk-1", "task-1", 1L),
                false,
                true,
                writer);
        return new DispatchTicket.DispatchEnvelope(
                stage ? STAGE_OPERATION_KIND : TASK_OPERATION_KIND,
                DispatchTicket.AsyncFamily.AGENT_TURN,
                new DispatchTicket.OwnerReference(
                        ownerKind, stage ? "stage-turn-1" : "task-turn-1", "owner-result"),
                fence,
                capacity);
    }

    private static DispatchTicket.DispatchEnvelope summaryEnvelope()
    {
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, null, null, "operation-1", 1, null, null, null);
        CapacityManager.CapacityRequest capacity = new CapacityManager.CapacityRequest(
                "operation-1",
                CapacityManager.WorkflowSource.V2,
                Set.of(
                        CapacityManager.CapacityLane.CLI,
                        CapacityManager.CapacityLane.REVIEW),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", "task-1", 1L),
                false,
                false,
                false);
        return new DispatchTicket.DispatchEnvelope(
                TASK_OUTCOME_SUMMARY_OPERATION_KIND,
                DispatchTicket.AsyncFamily.AGENT_TURN,
                new DispatchTicket.OwnerReference(
                        TASK_TURN, "task-turn-1", "TASK_OUTCOME_SUMMARY_RESULT"),
                fence,
                capacity);
    }

    private static DispatchTicket.DispatchEnvelope terminalConversationEnvelope()
    {
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, null, null, "operation-1", 1,
                "fingerprint-1", "head-1", "base-1");
        CapacityManager.CapacityRequest capacity = new CapacityManager.CapacityRequest(
                "operation-1", CapacityManager.WorkflowSource.V2,
                Set.of(
                        CapacityManager.CapacityLane.CLI,
                        CapacityManager.CapacityLane.REVIEW),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", "task-1", 1L),
                false, false, false);
        return new DispatchTicket.DispatchEnvelope(
                TASK_OPERATION_KIND, DispatchTicket.AsyncFamily.AGENT_TURN,
                new DispatchTicket.OwnerReference(
                        TASK_TURN, "task-turn-1", "TASK_TURN_RESULT"),
                fence, capacity);
    }

    private static AgentTurnOperationHandler.ExactTurn turn(
            DispatchTicket.OwnerKind ownerKind, String launchInput)
    {
        boolean stage = ownerKind == STAGE_TURN;
        return new AgentTurnOperationHandler.ExactTurn(
                ownerKind,
                stage ? "stage-turn-1" : "task-turn-1",
                "task-1",
                1,
                "stage-1",
                1L,
                stage ? "IMPLEMENT" : "BRAIN_REVIEW",
                "QUEUED",
                "operation-1",
                1,
                "fingerprint-1",
                "head-1",
                "base-1",
                launchInput,
                WORKTREE,
                "ACTIVE",
                "stage-1",
                1L,
                false,
                "fingerprint-1",
                "head-1",
                "base-1",
                stage ? null : "codex",
                stage ? null : "gpt-5.6");
    }

    private static AgentTurnOperationHandler.ExactTurn summaryTurn()
    {
        return new AgentTurnOperationHandler.ExactTurn(
                TASK_TURN,
                "task-turn-1",
                "task-1",
                1,
                null,
                null,
                "TASK_COMPLETION_SUMMARY",
                "QUEUED",
                "operation-1",
                1,
                null,
                null,
                null,
                launchInput(),
                WORKTREE,
                "COMPLETED",
                null,
                null,
                false,
                null,
                null,
                null,
                "codex",
                "gpt-5.6");
    }

    private static AgentTurnOperationHandler.ExactTurn terminalTurn(String purpose)
    {
        return new AgentTurnOperationHandler.ExactTurn(
                TASK_TURN, "task-turn-1", "task-1", 1,
                null, null, purpose, "QUEUED", "operation-1", 1,
                "fingerprint-1", "head-1", "base-1", launchInput(),
                WORKTREE, "COMPLETED", null, null, false,
                "fingerprint-1", "head-1", "base-1", "codex", "gpt-5.6");
    }

    private static AgentTurnOperationHandler.ExactTurn withCurrentFingerprint(
            AgentTurnOperationHandler.ExactTurn turn, String fingerprint)
    {
        return new AgentTurnOperationHandler.ExactTurn(
                turn.ownerKind(), turn.turnId(), turn.taskId(), turn.taskEpoch(),
                turn.stageId(), turn.stageGeneration(), turn.purpose(), turn.turnStatus(),
                turn.operationId(), turn.semanticAttempt(), turn.expectedCodeFingerprint(),
                turn.expectedHeadSha(), turn.expectedBaseSha(), turn.launchInput(),
                turn.worktreePath(), turn.taskLifecycle(), turn.currentStageId(),
                turn.currentStageGeneration(), turn.stageCompleted(), fingerprint,
                turn.currentHeadSha(), turn.currentBaseSha(), turn.brainProvider(),
                turn.brainModel());
    }

    private static AgentTurnOperationHandler.ExactTurn withBrainIdentity(
            AgentTurnOperationHandler.ExactTurn turn,
            String provider,
            String model)
    {
        return new AgentTurnOperationHandler.ExactTurn(
                turn.ownerKind(), turn.turnId(), turn.taskId(), turn.taskEpoch(),
                turn.stageId(), turn.stageGeneration(), turn.purpose(), turn.turnStatus(),
                turn.operationId(), turn.semanticAttempt(), turn.expectedCodeFingerprint(),
                turn.expectedHeadSha(), turn.expectedBaseSha(), turn.launchInput(),
                turn.worktreePath(), turn.taskLifecycle(), turn.currentStageId(),
                turn.currentStageGeneration(), turn.stageCompleted(),
                turn.currentCodeFingerprint(), turn.currentHeadSha(),
                turn.currentBaseSha(), provider, model);
    }

    private static String launchInput()
    {
        return launchInput(TASK_TURN);
    }

    private static String launchInput(DispatchTicket.OwnerKind ownerKind)
    {
        boolean stage = ownerKind == STAGE_TURN;
        return """
                {"schemaVersion":1,"transport":"CLI","provider":"codex",
                 "credentialAccount":null,"model":"gpt-5.6",
                 "reasoningEffort":"high","workingDirectory":"/tmp/worktree-task-1",
                 "systemPrompt":"brain role","prompt":"review exact revision",
                 "toolEndpoint":{"serverName":"bytequay",
                  "url":"http://127.0.0.1:53123/api/v2/%s/%s/operations/operation-1/mcp",
                  "ownerKind":"%s","ownerId":"%s","operationId":"operation-1",
                  "profile":"%s",
                  "approvalPromptTool":"mcp__bytequay__approval_prompt"}}
                """.formatted(
                stage ? "stage-turns" : "task-turns",
                stage ? "stage-turn-1" : "task-turn-1",
                ownerKind,
                stage ? "stage-turn-1" : "task-turn-1",
                stage ? "STAGE_DEVELOPMENT" : "TASK_BRAIN_READ_ONLY");
    }

    private static AgentTurnProviderSession.Result successful()
    {
        return new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.SUCCEEDED,
                "session-1", "approved", 11, 7, 3, 123L, null);
    }

    private static final class FakeProvider
            implements AgentTurnProviderSession
    {
        private int opens;
        private Request request;
        private Result result = successful();
        private FakeSession session;
        private final List<String> events = new ArrayList<>();
        private Runnable onStart = () -> {};

        @Override
        public Session open(Request request, Observer observer)
        {
            opens++;
            events.add("open");
            this.request = request;
            session = new FakeSession(request, observer, result, events, onStart);
            return session;
        }
    }

    private static final class FakeSession
            implements AgentTurnProviderSession.Session
    {
        private final AgentTurnProviderSession.Request request;
        private final AgentTurnProviderSession.Observer observer;
        private final AgentTurnProviderSession.Result result;
        private final List<String> events;
        private final Runnable onStart;
        private boolean canceled;
        private AgentTurnProviderSession.WriterFence writerFence;

        private FakeSession(
                AgentTurnProviderSession.Request request,
                AgentTurnProviderSession.Observer observer,
                AgentTurnProviderSession.Result result,
                List<String> events,
                Runnable onStart)
        {
            this.request = request;
            this.observer = observer;
            this.result = result;
            this.events = events;
            this.onStart = onStart;
        }

        @Override
        public AgentTurnProviderSession.Result startAndAwait(
                AgentTurnProviderSession.WriterFence writerFence)
        {
            this.writerFence = writerFence;
            events.add("start");
            if (canceled) {
                return new AgentTurnProviderSession.Result(
                        AgentTurnProviderSession.Completion.CANCELED,
                        null, "", 0, 0, 0, null, "provider session canceled");
            }
            onStart.run();
            observer.providerSession(request.provider(), "session-1");
            observer.processStarted(123, "test/provider");
            observer.log(0, "{\"event\":\"started\"}");
            return result;
        }

        @Override
        public void cancel()
        {
            canceled = true;
        }

        @Override
        public void close() {}
    }
}
