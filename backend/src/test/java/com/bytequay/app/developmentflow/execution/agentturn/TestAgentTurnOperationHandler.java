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
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
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
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.REMOTE_REPAIR_RESULT_NORMALIZATION_PURPOSE;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.STAGE_OPERATION_KIND;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.TASK_OPERATION_KIND;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.TASK_OUTCOME_SUMMARY_OPERATION_KIND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestAgentTurnOperationHandler
{
    private static final String WORKTREE = "/tmp/worktree-task-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorktreeWriterLeaseManager writers;
    private CodeFingerprints fingerprints;
    private GitRunner git;
    private FakeProvider provider;

    @BeforeEach
    void setUp()
            throws Exception
    {
        writers = mock(WorktreeWriterLeaseManager.class);
        fingerprints = mock(CodeFingerprints.class);
        git = mock(GitRunner.class);
        when(fingerprints.fingerprint(Path.of(WORKTREE)))
                .thenReturn("fingerprint-1");
        when(git.headSha(Path.of(WORKTREE))).thenReturn("head-1");
        when(git.currentBranch(Path.of(WORKTREE))).thenReturn("dev/task-1");
        when(git.inProgressOperations(Path.of(WORKTREE)))
                .thenReturn(List.of());
        when(git.hasUncommittedChanges(Path.of(WORKTREE))).thenReturn(false);
        when(git.mergeBase(Path.of(WORKTREE), "head-2", "base-1"))
                .thenReturn(Optional.of("base-1"));
        when(git.commitTreeSha(Path.of(WORKTREE), "head-1"))
                .thenReturn("tree-1");
        when(git.commitTreeSha(Path.of(WORKTREE), "head-2"))
                .thenReturn("tree-2");
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
        assertThat(provider.request.permissionPromptTool())
                .isEqualTo("mcp__bytequay__approval_prompt");
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
        ActiveAgentContextRegistry contexts = new ActiveAgentContextRegistry();
        AgentTurnOperationHandler handler = handler(summaryTurn(), contexts);
        provider.onStart = () -> assertThat(contexts.find(
                        "trunk-1",
                        AgentTurnOperationHandler.mcpAgentKey(
                                TASK_TURN, "task-turn-1", "operation-1"))
                .orElseThrow().toolNames())
                .contains("read_commit_summary")
                .doesNotContain("approval_prompt", "ask_user_question");

        DispatchTicket.DispatchResult result = handler.execute(context(envelope));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.READ_ONLY);
        assertThat(provider.request.toolEndpoint().ownerKind()).isEqualTo(TASK_TURN);
        assertThat(provider.request.toolEndpoint().profile())
                .isEqualTo(AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY);
        assertThat(provider.request.permissionPromptTool()).isNull();
        assertThat(envelope.capacityRequest().exclusiveTask()).isFalse();
        verify(writers, never()).acquire(any(), any());
        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result);
        assertThat(decoded.payload().purpose())
                .isEqualTo("TASK_COMPLETION_SUMMARY");
    }

    @Test
    void automaticRemoteBrainReviewsCannotSuspendTheirOwningEpisode()
            throws Exception
    {
        for (String purpose : List.of(
                "REMOTE_CI_BRAIN_REVIEW", "BRANCH_SYNC_BRAIN_REVIEW")) {
            provider = new FakeProvider();
            provider.result = successfulRemoteBrain();
            ActiveAgentContextRegistry contexts = new ActiveAgentContextRegistry();
            AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                    turn(TASK_TURN, launchInput()), purpose);
            provider.onStart = () -> {
                String agentKey = AgentTurnOperationHandler.mcpAgentKey(
                        TASK_TURN, "task-turn-1", "operation-1");
                assertThat(contexts.find("trunk-1", agentKey)
                        .orElseThrow().toolNames())
                        .contains("read_remote_pr_status")
                        .doesNotContain("approval_prompt", "ask_user_question");
                assertThat(contexts.findScope("trunk-1", agentKey)
                        .orElseThrow().taskId()).isEqualTo("task-1");
                assertThat(contexts.findScope("trunk-1", agentKey)
                        .orElseThrow().stageId()).isNull();
            };

            DispatchTicket.DispatchResult result = handler(turn, contexts)
                    .execute(context(envelope(TASK_TURN, false)));

            assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            assertThat(provider.request.permissionPromptTool()).isNull();
            assertThat(provider.request.preapprovedMcpTools())
                    .contains("read_remote_pr_status", "read_dev_report")
                    .doesNotContain("approval_prompt", "ask_user_question", "run_checks");
        }
    }

    @Test
    void developmentBrainResultRepairHasNoToolsPermissionOrContinuation()
            throws Exception
    {
        ActiveAgentContextRegistry contexts = new ActiveAgentContextRegistry();
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(TASK_TURN, launchInput()),
                "DEVELOPMENT_BRAIN_RESULT_REPAIR");
        provider.onStart = () -> assertThat(contexts.find(
                        "trunk-1",
                        AgentTurnOperationHandler.mcpAgentKey(
                                TASK_TURN, "task-turn-1", "operation-1"))
                .orElseThrow().toolNames()).isEmpty();

        DispatchTicket.DispatchResult result = handler(turn, contexts)
                .execute(context(envelope(TASK_TURN, false)));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.READ_ONLY);
        assertThat(provider.request.permissionPromptTool()).isNull();
        assertThat(provider.request.preapprovedMcpTools()).isEmpty();
        assertThat(provider.request.resumeSessionId()).isNull();
        assertThat(provider.request.fallbackPrompt()).isNull();
        assertThat(provider.request.priorCumulativeInputTokens()).isZero();
        assertThat(provider.request.priorCumulativeOutputTokens()).isZero();
        verify(writers, never()).acquire(any(), any());
    }

    @Test
    void remoteRepairResultNormalizationIsFreshToolFreeAndStrict()
            throws Exception
    {
        ActiveAgentContextRegistry contexts = new ActiveAgentContextRegistry();
        AgentTurnOperationHandler.ExactTurn turn = withBrainIdentity(
                withPurpose(
                        turn(TASK_TURN, toolFreeLaunchInput()),
                        REMOTE_REPAIR_RESULT_NORMALIZATION_PURPOSE),
                "claude", "claude-opus-4-1");
        provider.result = successfulRemoteStage();
        provider.onStart = () -> assertThat(contexts.find(
                        "trunk-1",
                        AgentTurnOperationHandler.mcpAgentKey(
                                TASK_TURN, "task-turn-1", "operation-1"))
                .orElseThrow().toolNames()).isEmpty();

        DispatchTicket.DispatchResult result = handler(turn, contexts)
                .execute(context(envelope(TASK_TURN, false)));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.READ_ONLY);
        assertThat(provider.request.toolEndpoint().approvalPromptTool()).isNull();
        assertThat(provider.request.permissionPromptTool()).isNull();
        assertThat(provider.request.images()).isEmpty();
        assertThat(provider.request.resumeSessionId()).isNull();
        assertThat(provider.request.fallbackPrompt()).isNull();
        assertThat(provider.request.priorCumulativeInputTokens()).isZero();
        assertThat(provider.request.priorCumulativeOutputTokens()).isZero();
        verify(writers, never()).acquire(any(), any());

        provider.onStart = () -> {};
        provider.result = new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.SUCCEEDED,
                "session-2", "{\"schemaVersion\":1,\"summary\":\"fixed\"} prose",
                1, 1, 0, 124L, null);
        DispatchTicket.DispatchResult malformed = handler(turn)
                .execute(context(envelope(TASK_TURN, false)));
        assertThat(malformed.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(MAPPER.readTree(malformed.payloadJson()).path("disposition")
                .asText()).isEqualTo("OWNER_OUTPUT_MALFORMED");
    }

    @Test
    void remoteRepairResultNormalizationRejectsContinuationAndImages()
            throws Exception
    {
        String resumed = launchInput().replace(
                "\"toolEndpoint\"",
                "\"resumeSessionId\":\"session-1\","
                        + "\"fallbackPrompt\":\"history\",\"toolEndpoint\"");
        String withImage = launchInput().replace(
                "\"toolEndpoint\"",
                "\"images\":[{\"path\":\"/tmp/input.png\","
                        + "\"mediaType\":\"image/png\","
                        + "\"digest\":\""
                        + "0".repeat(64)
                        + "\"}],\"toolEndpoint\"");

        for (String input : List.of(resumed, withImage)) {
            AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                    turn(TASK_TURN, input),
                    REMOTE_REPAIR_RESULT_NORMALIZATION_PURPOSE);
            DispatchTicket.DispatchResult result = handler(turn)
                    .execute(context(envelope(TASK_TURN, false)));

            assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
            assertThat(result.error()).contains("fresh text-only turn");
        }
        assertThat(provider.opens).isZero();
    }

    @Test
    void remoteRepairResultNormalizationRejectsAnApprovalGate()
            throws Exception
    {
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(TASK_TURN, launchInput()),
                REMOTE_REPAIR_RESULT_NORMALIZATION_PURPOSE);

        DispatchTicket.DispatchResult result = handler(turn)
                .execute(context(envelope(TASK_TURN, false)));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.error()).contains("exact typed Turn");
        assertThat(provider.opens).isZero();
    }

    @Test
    void successfulPlanTurnRemainsReadOnlyAndNeedsNoOutputCodeSubject()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(TASK_TURN, false);

        DispatchTicket.DispatchResult result = handler(planTurn())
                .execute(context(envelope));

        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result);
        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(decoded.payload().purpose()).isEqualTo("PLAN_DRAFT");
        assertThat(decoded.payload().outputCodeSubject()).isNull();
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.READ_ONLY);
        assertThat(provider.request.permissionPromptTool())
                .isEqualTo("mcp__bytequay__approval_prompt");
        verify(writers, never()).acquire(any(), any());
        verifyNoInteractions(fingerprints, git);
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
        ActiveAgentContextRegistry contexts = new ActiveAgentContextRegistry();
        AgentTurnOperationHandler handler = handler(turn, contexts);
        ExecutionContext context = context(envelope);
        AtomicBoolean insideAuthorization = new AtomicBoolean();
        WorktreeWriterLeaseManager.Lease lease = authorizeStageMutation(
                context, insideAuthorization);
        when(git.hasUncommittedChanges(Path.of(WORKTREE)))
                .thenReturn(false, true, false);
        when(fingerprints.fingerprint(Path.of(WORKTREE)))
                .thenReturn("fingerprint-1", "fingerprint-2");
        doAnswer(invocation -> {
            assertThat(insideAuthorization).isTrue();
            provider.events.add("stage");
            return null;
        }).when(git).stageAll(
                Path.of(WORKTREE), List.of(".bytequay-hooks"));
        when(git.commit(
                Path.of(WORKTREE), "ByteQuay checkpoint: IMPLEMENT"))
                .thenAnswer(invocation -> {
                    assertThat(insideAuthorization).isTrue();
                    provider.events.add("commit");
                    return Optional.of("head-2");
                });
        AtomicBoolean firstHeadProbe = new AtomicBoolean(true);
        when(git.headSha(Path.of(WORKTREE))).thenAnswer(invocation -> {
            assertThat(insideAuthorization).isTrue();
            if (firstHeadProbe.getAndSet(false)) {
                return "head-1";
            }
            provider.events.add("observe");
            return "head-2";
        });
        provider.result = successful();
        provider.onStart = () -> {
            String agentKey = AgentTurnOperationHandler.mcpAgentKey(
                    STAGE_TURN, "stage-turn-1", "operation-1");
            assertThat(contexts.find("trunk-1", agentKey)
                    .orElseThrow().toolNames())
                    .contains("run_checks", "ask_user_question")
                    .doesNotContain(
                            "record_local_review", "record_dev_report",
                            "record_pr_check", "record_review_verdict");
            assertThat(contexts.findWorktreePath("trunk-1", agentKey))
                    .contains(WORKTREE);
            assertThat(contexts.findScope("trunk-1", agentKey)
                    .orElseThrow().stageId()).isEqualTo("stage-1");
        };

        DispatchTicket.DispatchResult result = handler.execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.WORKTREE_WRITE);
        assertThat(provider.session.writerFence.fencingToken()).isEqualTo(19);
        assertThat(provider.session.writerFence.operationId()).isEqualTo("operation-1");
        assertThat(provider.events)
                .containsExactly(
                        "open", "authorize", "start", "stage", "commit", "observe");
        verify(writers).acquire(context, WORKTREE);
        verify(writers).authorizeMutation(context, lease);
        verify(git).stageAll(
                Path.of(WORKTREE), List.of(".bytequay-hooks"));
        verify(git).commit(
                Path.of(WORKTREE), "ByteQuay checkpoint: IMPLEMENT");
        AgentTurnOperationHandler.Evidence evidence = MAPPER.readValue(
                result.evidenceJson(), AgentTurnOperationHandler.Evidence.class);
        assertThat(evidence.writerFence().fencingToken()).isEqualTo(19);
        assertThat(evidence.outputCodeSubject()).isNotNull();
        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result);
        assertThat(decoded.requireOutputCodeSubject("base-1")).isEqualTo(
                new AgentTurnOperationHandler.OutputCodeSubject(
                        "fingerprint-2", "head-2", "base-1", true, "base-1",
                        "tree-1", "tree-2"));
        assertThatThrownBy(() -> decoded.requireOutputCodeSubject("other-base"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact frozen base");
    }

    @Test
    void successfulStageTurnWithNoChangesDoesNotCreateAnEmptyCheckpoint()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = turn(
                STAGE_TURN, launchInput(STAGE_TURN));
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        when(git.headSha(Path.of(WORKTREE))).thenReturn("head-1");
        when(git.mergeBase(Path.of(WORKTREE), "head-1", "base-1"))
                .thenReturn(Optional.of("base-1"));
        when(fingerprints.fingerprint(Path.of(WORKTREE)))
                .thenReturn("fingerprint-1");

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result);
        AgentTurnOperationHandler.OutputCodeSubject output =
                decoded.requireOutputCodeSubject("base-1");
        assertThat(output.headSha()).isEqualTo("head-1");
        assertThat(output.sourceTreeSha()).isEqualTo("tree-1");
        assertThat(output.resultTreeSha()).isEqualTo("tree-1");
        verify(git, never()).stageAll(any(), any());
        verify(git, never()).commit(any(), any());
    }

    @Test
    void remoteCiEmptyCommitIsRestoredUnderTheWriterFence()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(STAGE_TURN, launchInput(STAGE_TURN)),
                "REMOTE_CI_REPAIR");
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = successfulRemoteStage();
        when(git.headSha(Path.of(WORKTREE)))
                .thenReturn("head-1", "empty-head", "head-1", "head-1");
        when(git.commitTreeSha(Path.of(WORKTREE), "empty-head"))
                .thenReturn("tree-1");
        when(git.mergeBase(Path.of(WORKTREE), "head-1", "base-1"))
                .thenReturn(Optional.of("base-1"));

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        AgentTurnOperationHandler.OutputCodeSubject output =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result)
                        .requireOutputCodeSubject("base-1");
        assertThat(output.headSha()).isEqualTo("head-1");
        assertThat(output.sourceTreeSha()).isEqualTo("tree-1");
        assertThat(output.resultTreeSha()).isEqualTo("tree-1");
        assertThat(output.discardedNoChangeHeadSha()).isEqualTo("empty-head");
        assertThat(output.restoredHeadSha()).isEqualTo("head-1");
        verify(git).resetHard(Path.of(WORKTREE), "head-1");
    }

    @Test
    void remoteCiEmptyCommitRestoreFailureFailsClosed()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(STAGE_TURN, launchInput(STAGE_TURN)),
                "REMOTE_CI_REPAIR");
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = successfulRemoteStage();
        when(git.headSha(Path.of(WORKTREE)))
                .thenReturn("head-1", "empty-head", "empty-head");
        when(fingerprints.fingerprint(Path.of(WORKTREE)))
                .thenReturn("fingerprint-1", "fingerprint-2");
        when(git.commitTreeSha(Path.of(WORKTREE), "empty-head"))
                .thenReturn("tree-1");
        doAnswer(invocation -> {
            throw new IOException("reset failed");
        }).when(git).resetHard(Path.of(WORKTREE), "head-1");

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(MAPPER.readTree(result.payloadJson()).path("disposition")
                .asText()).isEqualTo("WORKTREE_RESTORE_FAILED");
        assertThat(result.error()).contains("restore failed");
        ArgumentCaptor<WorktreeWriterLeaseManager.QuarantineEvidence> evidence =
                ArgumentCaptor.forClass(
                        WorktreeWriterLeaseManager.QuarantineEvidence.class);
        verify(writers).quarantine(
                eq(context), any(),
                evidence.capture());
        assertThat(evidence.getValue().observedHeadSha())
                .isEqualTo("empty-head");
        assertThat(evidence.getValue().observedClean()).isTrue();
        assertThat(evidence.getValue().observedCodeFingerprint())
                .isEqualTo("fingerprint-2");
        assertThat(evidence.getValue().expectedCodeFingerprint())
                .isEqualTo("fingerprint-1");
    }

    @Test
    void detachedSourceIsCleanedBeforeSwitchingBackToTheTaskBranch()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = turn(
                STAGE_TURN, launchInput(STAGE_TURN));
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        when(git.currentBranch(Path.of(WORKTREE)))
                .thenReturn(null, null, "dev/task-1");

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        InOrder order = inOrder(git);
        order.verify(git).resetHard(Path.of(WORKTREE), "HEAD");
        order.verify(git).cleanUntracked(
                Path.of(WORKTREE), List.of(".bytequay-hooks"));
        order.verify(git).switchBranch(Path.of(WORKTREE), "dev/task-1");
        order.verify(git).resetHard(Path.of(WORKTREE), "head-1");
    }

    @Test
    void lingeringGitOperationStateIsQuarantinedInsteadOfAccepted()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = turn(
                STAGE_TURN, launchInput(STAGE_TURN));
        ExecutionContext context = context(envelope);
        WorktreeWriterLeaseManager.Lease lease = authorizeStageMutation(
                context, new AtomicBoolean());
        when(git.inProgressOperations(Path.of(WORKTREE)))
                .thenReturn(List.of("rebase-merge"));

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        verify(writers).quarantine(
                eq(context), eq(lease), any());
    }

    @Test
    void remoteCiRestoreWithExpectedHeadButDirtyWorktreeIsQuarantined()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(STAGE_TURN, launchInput(STAGE_TURN)),
                "REMOTE_CI_REPAIR");
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = successfulRemoteStage();
        when(git.headSha(Path.of(WORKTREE)))
                .thenReturn("head-1", "empty-head", "head-1", "head-1");
        when(git.commitTreeSha(Path.of(WORKTREE), "empty-head"))
                .thenReturn("tree-1");
        when(git.hasUncommittedChanges(Path.of(WORKTREE)))
                .thenReturn(false, false, false, true, true);

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        ArgumentCaptor<WorktreeWriterLeaseManager.QuarantineEvidence> evidence =
                ArgumentCaptor.forClass(
                        WorktreeWriterLeaseManager.QuarantineEvidence.class);
        verify(writers).quarantine(
                eq(context), any(),
                evidence.capture());
        assertThat(evidence.getValue().observedHeadSha()).isEqualTo("head-1");
        assertThat(evidence.getValue().observedClean()).isFalse();
    }

    @Test
    void failedStageProviderRestoresTrackedAndUntrackedOutput()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = turn(
                STAGE_TURN, launchInput(STAGE_TURN));
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.FAILED,
                "session-1", "", 1, 1, 0, 123L, "provider failed");

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(MAPPER.readTree(result.payloadJson()).path("disposition")
                .asText()).isEqualTo("PROVIDER_FAILED");
        verify(git).resetHard(Path.of(WORKTREE), "head-1");
        verify(git, times(2)).cleanUntracked(
                Path.of(WORKTREE), List.of(".bytequay-hooks"));
        verify(writers, never()).quarantine(any(), any(), any());
    }

    @Test
    void canceledStageProviderAlsoRestoresItsSource()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = turn(
                STAGE_TURN, launchInput(STAGE_TURN));
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.CANCELED,
                "session-1", "", 1, 1, 0, 123L, "provider canceled");

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.CANCELED);
        verify(git).resetHard(Path.of(WORKTREE), "head-1");
        verify(git, times(2)).cleanUntracked(
                Path.of(WORKTREE), List.of(".bytequay-hooks"));
    }

    @Test
    void malformedRemoteCiOutputFreezesCandidateBeforeRestoringSource()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(STAGE_TURN, launchInput(STAGE_TURN)),
                "REMOTE_CI_REPAIR");
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.SUCCEEDED,
                "session-1", "I fixed it, but this is not JSON", 1, 1, 0,
                123L, null);
        when(git.hasUncommittedChanges(Path.of(WORKTREE)))
                .thenReturn(false, true, false, false);
        when(git.headSha(Path.of(WORKTREE)))
                .thenReturn("head-1", "head-2", "head-1");
        when(git.mergeBase(Path.of(WORKTREE), "head-2", "head-1"))
                .thenReturn(Optional.of("head-1"));
        when(git.commitParentShas(Path.of(WORKTREE), "head-2"))
                .thenReturn(List.of("head-1"));

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result);
        assertThat(decoded.payload().disposition())
                .isEqualTo(AgentTurnOperationHandler.Disposition
                        .OWNER_OUTPUT_MALFORMED);
        assertThat(decoded.payload().finalText())
                .isEqualTo("I fixed it, but this is not JSON");
        assertThat(decoded.payload().outputCodeSubject()).isNotNull().satisfies(output -> {
            assertThat(output.headSha()).isEqualTo("head-2");
            assertThat(output.sourceTreeSha()).isEqualTo("tree-1");
            assertThat(output.resultTreeSha()).isEqualTo("tree-2");
            assertThat(output.sourceHeadMergeBaseSha()).isEqualTo("head-1");
            assertThat(output.candidateParentSha()).isEqualTo("head-1");
        });
        assertThat(result.error()).startsWith("OWNER_OUTPUT_MALFORMED:");
        verify(git).stageAll(
                Path.of(WORKTREE), List.of(".bytequay-hooks"));
        verify(git).commit(
                Path.of(WORKTREE),
                "ByteQuay checkpoint: REMOTE_CI_REPAIR");
        verify(git).resetHard(Path.of(WORKTREE), "head-1");
        verify(git).commitParentShas(Path.of(WORKTREE), "head-2");
        verify(git, times(2)).cleanUntracked(
                Path.of(WORKTREE), List.of(".bytequay-hooks"));
    }

    @Test
    void malformedRemoteCiMultiCommitCandidateFailsAndRestoresSource()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(STAGE_TURN, launchInput(STAGE_TURN)),
                "REMOTE_CI_REPAIR");
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.SUCCEEDED,
                "session-1", "I fixed it, but this is not JSON", 1, 1, 0,
                123L, null);
        when(git.headSha(Path.of(WORKTREE)))
                .thenReturn("head-1", "head-2", "head-1");
        when(git.mergeBase(Path.of(WORKTREE), "head-2", "head-1"))
                .thenReturn(Optional.of("head-1"));
        when(git.commitParentShas(Path.of(WORKTREE), "head-2"))
                .thenReturn(List.of("intermediate-head"));

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(MAPPER.readTree(result.payloadJson()).path("disposition")
                .asText()).isEqualTo("WORKTREE_OUTPUT_REJECTED");
        assertThat(result.error()).contains("exactly one direct child");
        verify(git).resetHard(Path.of(WORKTREE), "head-1");
        verify(git, times(2)).cleanUntracked(
                Path.of(WORKTREE), List.of(".bytequay-hooks"));
        verify(writers, never()).quarantine(any(), any(), any());
    }

    @Test
    void validRemoteCiResultRetainsMultiCommitOutput()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(STAGE_TURN, launchInput(STAGE_TURN)),
                "REMOTE_CI_REPAIR");
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = successfulRemoteStage();
        when(git.headSha(Path.of(WORKTREE)))
                .thenReturn("head-1", "head-2");
        when(git.mergeBase(Path.of(WORKTREE), "head-2", "head-1"))
                .thenReturn(Optional.of("head-1"));
        when(git.commitParentShas(Path.of(WORKTREE), "head-2"))
                .thenReturn(List.of("intermediate-head"));

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        AgentTurnOperationHandler.OutputCodeSubject output =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result)
                        .requireOutputCodeSubject("base-1");
        assertThat(output.headSha()).isEqualTo("head-2");
        assertThat(output.sourceHeadMergeBaseSha()).isEqualTo("head-1");
        assertThat(output.candidateParentSha()).isNull();
        verify(git, never()).commitParentShas(any(), any());
    }

    @Test
    void remoteStageStrictJsonRejectsTrailingFencesAndDuplicateKeys()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(STAGE_TURN, launchInput(STAGE_TURN)),
                "REMOTE_CI_REPAIR");
        for (String malformed : List.of(
                "{\"schemaVersion\":1,\"summary\":\"fixed\"} trailing prose",
                "```json\n{\"schemaVersion\":1,\"summary\":\"fixed\"}\n```",
                "{\"schemaVersion\":1,\"summary\":\"first\","
                        + "\"summary\":\"second\"}")) {
            ExecutionContext context = context(envelope);
            authorizeStageMutation(context, new AtomicBoolean());
            provider.result = new AgentTurnProviderSession.Result(
                    AgentTurnProviderSession.Completion.SUCCEEDED,
                    "session-1", malformed, 1, 1, 0, 123L, null);

            DispatchTicket.DispatchResult result = handler(turn).execute(context);

            assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
            assertThat(MAPPER.readTree(result.payloadJson()).path("disposition")
                    .asText()).isEqualTo("OWNER_OUTPUT_MALFORMED");
        }
    }

    @Test
    void malformedRemoteStageRestoreFailureUsesTheCentralQuarantine()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(STAGE_TURN, launchInput(STAGE_TURN)),
                "REMOTE_CI_REPAIR");
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.SUCCEEDED,
                "session-1", "not strict JSON", 1, 1, 0, 123L, null);
        doAnswer(invocation -> {
            throw new IOException("reset failed");
        }).when(git).resetHard(Path.of(WORKTREE), "head-1");

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(MAPPER.readTree(result.payloadJson()).path("disposition")
                .asText()).isEqualTo("WORKTREE_RESTORE_FAILED");
        ArgumentCaptor<WorktreeWriterLeaseManager.QuarantineEvidence> evidence =
                ArgumentCaptor.forClass(
                        WorktreeWriterLeaseManager.QuarantineEvidence.class);
        verify(writers).quarantine(
                eq(context), any(),
                evidence.capture());
        assertThat(evidence.getValue().reason())
                .contains("restore failed");
    }

    @Test
    void malformedRemoteBrainOutputBecomesOneTypedProviderFailure()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(TASK_TURN, false);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(TASK_TURN, launchInput()),
                "REMOTE_CI_BRAIN_REVIEW");
        provider.result = new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.SUCCEEDED,
                "session-1", "APPROVED in prose", 1, 1, 0, 123L, null);

        DispatchTicket.DispatchResult result = handler(turn)
                .execute(context(envelope));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), result);
        assertThat(decoded.payload().disposition())
                .isEqualTo(AgentTurnOperationHandler.Disposition
                        .OWNER_OUTPUT_MALFORMED);
        assertThat(decoded.payload().finalText()).isEqualTo("APPROVED in prose");
        assertThat(result.error()).startsWith("OWNER_OUTPUT_MALFORMED:");
        verify(writers, never()).acquire(any(), any());
    }

    @Test
    void remoteBrainStrictJsonRejectsTrailingAndDuplicateFields()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(TASK_TURN, false);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(TASK_TURN, launchInput()),
                "REMOTE_CI_BRAIN_REVIEW");
        for (String malformed : List.of(
                "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                        + "\"summary\":\"safe\",\"findings\":[]} prose",
                "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                        + "\"verdict\":\"CHANGES_REQUESTED\","
                        + "\"summary\":\"unsafe\",\"findings\":[\"fix\"]}")) {
            provider.result = new AgentTurnProviderSession.Result(
                    AgentTurnProviderSession.Completion.SUCCEEDED,
                    "session-1", malformed, 1, 1, 0, 123L, null);

            DispatchTicket.DispatchResult result = handler(turn)
                    .execute(context(envelope));

            assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
            assertThat(MAPPER.readTree(result.payloadJson()).path("disposition")
                    .asText()).isEqualTo("OWNER_OUTPUT_MALFORMED");
        }
        verify(writers, never()).acquire(any(), any());
    }

    @Test
    void checkpointFailureRestoresBeforeInfrastructureRetry()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = turn(
                STAGE_TURN, launchInput(STAGE_TURN));
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        when(git.hasUncommittedChanges(Path.of(WORKTREE)))
                .thenReturn(false, true, false);
        doAnswer(invocation -> {
            throw new IOException("stage failed");
        }).when(git).stageAll(
                Path.of(WORKTREE), List.of(".bytequay-hooks"));

        assertThatThrownBy(() -> handler(turn).execute(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint");
        verify(git).resetHard(Path.of(WORKTREE), "head-1");
        verify(writers, never()).quarantine(any(), any(), any());
    }

    @Test
    void outputCaptureFailureRestoresBeforeInfrastructureRetry()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = turn(
                STAGE_TURN, launchInput(STAGE_TURN));
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        doAnswer(invocation -> {
            throw new IOException("tree probe failed");
        }).when(git).commitTreeSha(Path.of(WORKTREE), "head-1");

        assertThatThrownBy(() -> handler(turn).execute(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capture");
        verify(git).resetHard(Path.of(WORKTREE), "head-1");
        verify(writers, never()).quarantine(any(), any(), any());
    }

    @Test
    void remoteCiRejectsResetToBaseWithUnrelatedNewChange()
            throws Exception
    {
        assertRemoteCiHistoryRewriteRejected("base-1");
    }

    @Test
    void remoteCiRejectsAmendedOrSquashedTaskHistory()
            throws Exception
    {
        assertRemoteCiHistoryRewriteRejected("older-task-ancestor");
    }

    private void assertRemoteCiHistoryRewriteRejected(String lineageBase)
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        AgentTurnOperationHandler.ExactTurn turn = withPurpose(
                turn(STAGE_TURN, launchInput(STAGE_TURN)),
                "REMOTE_CI_REPAIR");
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        provider.result = successfulRemoteStage();
        when(git.headSha(Path.of(WORKTREE)))
                .thenReturn("head-1", "rewritten-head", "head-1");
        when(git.commitTreeSha(Path.of(WORKTREE), "rewritten-head"))
                .thenReturn("rewritten-tree");
        when(git.mergeBase(Path.of(WORKTREE), "rewritten-head", "base-1"))
                .thenReturn(Optional.of("base-1"));
        when(git.mergeBase(Path.of(WORKTREE), "rewritten-head", "head-1"))
                .thenReturn(Optional.of(lineageBase));

        DispatchTicket.DispatchResult result = handler(turn).execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(MAPPER.readTree(result.payloadJson()).path("disposition")
                .asText()).isEqualTo("WORKTREE_OUTPUT_REJECTED");
        assertThat(result.error()).contains("rewrote or discarded");
        verify(git).resetHard(Path.of(WORKTREE), "head-1");
        verify(writers, never()).quarantine(any(), any(), any());
    }

    @Test
    void oldWriterResultWithoutTreeProofFailsClosed()
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = envelope(STAGE_TURN, true);
        ExecutionContext context = context(envelope);
        authorizeStageMutation(context, new AtomicBoolean());
        DispatchTicket.DispatchResult result = handler(
                turn(STAGE_TURN, launchInput(STAGE_TURN))).execute(context);
        ObjectNode payload = (ObjectNode) MAPPER.readTree(result.payloadJson());
        ObjectNode evidence = (ObjectNode) MAPPER.readTree(result.evidenceJson());
        ((ObjectNode) payload.path("outputCodeSubject"))
                .remove(List.of("sourceTreeSha", "resultTreeSha"));
        ((ObjectNode) evidence.path("outputCodeSubject"))
                .remove(List.of("sourceTreeSha", "resultTreeSha"));
        DispatchTicket.DispatchResult legacy = new DispatchTicket.DispatchResult(
                result.fence(), result.outcome(), MAPPER.writeValueAsString(payload),
                MAPPER.writeValueAsString(evidence), result.error());
        AgentTurnOwnerResultCodec.OwnerResult decoded =
                new AgentTurnOwnerResultCodec(MAPPER).decode(
                        envelope.owner(), envelope.fence(), legacy);

        assertThatThrownBy(() -> decoded.requireOutputCodeSubject("base-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tree proof");
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
                provider, writers, fingerprints, git, contexts,
                new ToolExposurePolicy(), MAPPER);
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

    private WorktreeWriterLeaseManager.Lease authorizeStageMutation(
            ExecutionContext context,
            AtomicBoolean insideAuthorization)
    {
        WorktreeWriterLeaseManager.Lease lease = new WorktreeWriterLeaseManager.Lease(
                WORKTREE, "task-1", "operation-1", 1, 19, "dispatcher",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(30));
        WorktreeWriterLeaseManager.WriterAuthorization authorization =
                mock(WorktreeWriterLeaseManager.WriterAuthorization.class);
        WorktreeWriterLeaseManager.MutationFence mutationFence =
                mock(WorktreeWriterLeaseManager.MutationFence.class);
        when(writers.acquire(context, WORKTREE)).thenReturn(lease);
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
            insideAuthorization.set(true);
            try {
                return mutation.apply(mutationFence);
            }
            finally {
                insideAuthorization.set(false);
            }
        }).when(authorization).run(any());
        return lease;
    }

    private AgentTurnOperationHandler handler(AgentTurnOperationHandler.ExactTurn turn)
    {
        return handler(turn, new ActiveAgentContextRegistry());
    }

    private AgentTurnOperationHandler handler(
            AgentTurnOperationHandler.ExactTurn turn,
            ActiveAgentContextRegistry contexts)
    {
        return new AgentTurnOperationHandler(
                (kind, id) -> kind == turn.ownerKind() && id.equals(turn.turnId())
                        ? Optional.of(turn) : Optional.empty(),
                provider,
                writers,
                fingerprints,
                git,
                contexts,
                new ToolExposurePolicy(),
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

    private static AgentTurnOperationHandler.ExactTurn planTurn()
    {
        return new AgentTurnOperationHandler.ExactTurn(
                TASK_TURN, "task-turn-1", "trunk-1", "workspace-1", "task-1",
                1, "stage-1", 1L, "PLAN", "PLAN_DRAFT", "QUEUED",
                "operation-1", 1, "fingerprint-1", "head-1", "base-1",
                launchInput(), WORKTREE, "ACTIVE", "stage-1", 1L, false,
                "fingerprint-1", "head-1", "base-1", "codex", "gpt-5.6");
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

    private static AgentTurnOperationHandler.ExactTurn withPurpose(
            AgentTurnOperationHandler.ExactTurn turn, String purpose)
    {
        return new AgentTurnOperationHandler.ExactTurn(
                turn.ownerKind(), turn.turnId(), turn.taskId(), turn.taskEpoch(),
                turn.stageId(), turn.stageGeneration(), purpose, turn.turnStatus(),
                turn.operationId(), turn.semanticAttempt(), turn.expectedCodeFingerprint(),
                turn.expectedHeadSha(), turn.expectedBaseSha(), turn.launchInput(),
                turn.worktreePath(), turn.taskLifecycle(), turn.currentStageId(),
                turn.currentStageGeneration(), turn.stageCompleted(),
                turn.currentCodeFingerprint(), turn.currentHeadSha(),
                turn.currentBaseSha(), turn.brainProvider(), turn.brainModel());
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

    private static String toolFreeLaunchInput()
            throws JsonProcessingException
    {
        ObjectNode launch = (ObjectNode) MAPPER.readTree(launchInput());
        ((ObjectNode) launch.path("toolEndpoint"))
                .remove("approvalPromptTool");
        return MAPPER.writeValueAsString(launch);
    }

    private static AgentTurnProviderSession.Result successful()
    {
        return new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.SUCCEEDED,
                "session-1", "approved", 11, 7, 3, 123L, null);
    }

    private static AgentTurnProviderSession.Result successfulRemoteStage()
    {
        return new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.SUCCEEDED,
                "session-1",
                "{\"schemaVersion\":1,\"summary\":\"fixed CI\"}",
                11, 7, 3, 123L, null);
    }

    private static AgentTurnProviderSession.Result successfulRemoteBrain()
    {
        return new AgentTurnProviderSession.Result(
                AgentTurnProviderSession.Completion.SUCCEEDED,
                "session-1",
                "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                        + "\"summary\":\"safe\",\"findings\":[]}",
                11, 7, 3, 123L, null);
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
