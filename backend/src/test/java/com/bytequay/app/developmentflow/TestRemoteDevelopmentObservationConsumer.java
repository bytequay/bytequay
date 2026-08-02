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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeMode;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.ObservationDisposition;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentObservationConsumer;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentObservationConsumer.Hooks;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteMergeObservationCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteMergeRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer;
import com.bytequay.app.developmentflow.stage.RemoteObservationDomainHooks;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.AuthorityKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.AutomationPolicy;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.MarkReadyDeliveryContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.MarkReadyDispatch;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.ReadinessEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.RemoteContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.RuntimeDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.StartReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationEvidence;
import com.bytequay.app.domain.PR;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRemoteDevelopmentObservationConsumer
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void acceptsOnceThenIngestsBeforeNamedProtocolHooks()
    {
        SqliteRemoteDevelopmentRuntimeStore store = mock(
                SqliteRemoteDevelopmentRuntimeStore.class);
        when(store.findFeedbackBatchAwaitingHead(
                "stage-1", "head-1", "base-1")).thenReturn(Optional.empty());
        List<String> order = new ArrayList<>();
        RemoteDevelopmentObservationConsumer consumer =
                new RemoteDevelopmentObservationConsumer(
                        store, mock(RemoteFeedbackRuntimeCoordinator.class),
                        mock(RemoteDevelopmentRuntimeCoordinator.class),
                        new Hooks(
                                ignored -> {
                                    order.add("ci");
                                    return ObservationDisposition.CONTINUE;
                                },
                                ignored -> {
                                    order.add("branch");
                                    return ObservationDisposition.CONTINUE;
                                },
                                ignored -> order.add("merge"),
                                (ignored, readiness) -> order.add("readiness")),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicInteger acceptances = new AtomicInteger();
        TaskCommandExecutor commands = CommandTestSupport.executor();

        RemoteObservationConsumer.Consumption result = commands.execute(
                "task-1", () -> consumer.consume(
                        candidate(true), () -> {
                            acceptances.incrementAndGet();
                            order.add("accept");
                        }));

        assertThat(result).isEqualTo(
                RemoteObservationConsumer.Consumption.ACCEPTED);
        assertThat(acceptances).hasValue(1);
        assertThat(order).containsExactly("accept", "branch", "ci", "merge");
        verify(store).ingestObserved(any());
    }

    @Test
    void supersededSubjectDoesNotAcceptOrRunProtocols()
    {
        SqliteRemoteDevelopmentRuntimeStore store = mock(
                SqliteRemoteDevelopmentRuntimeStore.class);
        List<String> hooks = new ArrayList<>();
        RemoteDevelopmentObservationConsumer consumer =
                new RemoteDevelopmentObservationConsumer(
                        store, mock(RemoteFeedbackRuntimeCoordinator.class),
                        mock(RemoteDevelopmentRuntimeCoordinator.class),
                        new Hooks(
                                ignored -> {
                                    hooks.add("ci");
                                    return ObservationDisposition.CONTINUE;
                                },
                                ignored -> {
                                    hooks.add("branch");
                                    return ObservationDisposition.CONTINUE;
                                },
                                ignored -> hooks.add("merge"),
                                (ignored, readiness) -> hooks.add("readiness")),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicInteger acceptances = new AtomicInteger();

        RemoteObservationConsumer.Consumption result =
                CommandTestSupport.executor().execute(
                        "task-1", () -> consumer.consume(
                                candidate(false), acceptances::incrementAndGet));

        assertThat(result).isEqualTo(
                RemoteObservationConsumer.Consumption.SUPERSEDED);
        assertThat(acceptances).hasValue(0);
        assertThat(hooks).isEmpty();
        verify(store, never()).ingestObserved(any());
    }

    @Test
    void greenProvisionalPushDefersEverySemanticHookUntilFreshObservation()
    {
        SqliteRemoteDevelopmentRuntimeStore store = mock(
                SqliteRemoteDevelopmentRuntimeStore.class);
        RemoteDevelopmentRuntimeCoordinator remote = mock(
                RemoteDevelopmentRuntimeCoordinator.class);
        when(store.findFeedbackBatchAwaitingHead(
                "stage-1", "head-1", "base-1"))
                .thenReturn(Optional.of("feedback-1"));
        when(store.requireContext("task-1", "stage-1")).thenReturn(
                new RemoteContext(
                        "workspace-1", "trunk-1", "task-1", 1, 1,
                        "stage-1", 1, 1, "WAITING_REMOTE_REVIEW", "binding-1",
                        "snapshot-1", 1, "head-1", "base-1", "fingerprint",
                        "/tmp/worktree"));
        when(store.findOpenFeedbackBatch(
                "stage-1", "head-1", "base-1")).thenReturn(Optional.empty());
        when(store.freezeNextBatch(
                any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(Optional.empty());
        when(store.findAutomationEligibilityEvidenceId("task-1", 1))
                .thenReturn(Optional.empty());
        ReadinessEvidence readiness = new ReadinessEvidence(
                "readiness-1", "snapshot-1", "ci-1", "policy-1",
                "head-1", "base-1", true);
        when(remote.proveReadinessInCommand(
                any(), any(), any(), any(), any())).thenReturn(readiness);

        AtomicInteger ciCalls = new AtomicInteger();
        List<String> order = new ArrayList<>();
        RemoteDevelopmentObservationConsumer consumer =
                new RemoteDevelopmentObservationConsumer(
                        store, mock(RemoteFeedbackRuntimeCoordinator.class), remote,
                        new Hooks(
                                ignored -> {
                                    order.add("ci");
                                    return ciCalls.getAndIncrement() == 0
                                            ? ObservationDisposition
                                                    .DEFER_UNTIL_PUSH_DELIVERY
                                            : ObservationDisposition.CONTINUE;
                                },
                                ignored -> {
                                    order.add("branch");
                                    return ObservationDisposition.CONTINUE;
                                },
                                ignored -> order.add("merge"),
                                (ignored, accepted) -> order.add("readiness")),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicInteger acceptances = new AtomicInteger();
        TaskCommandExecutor commands = CommandTestSupport.executor();

        commands.execute("task-1", () -> consumer.consume(
                candidate(true, RemoteObservationOperationHandler.PrState.OPEN),
                acceptances::incrementAndGet));

        assertThat(order).containsExactly("branch", "ci");
        verify(remote, never()).resumeFeedbackCompletionInCommand(any(), any());
        verify(remote, never()).proveReadinessInCommand(
                any(), any(), any(), any(), any());

        commands.execute("task-1", () -> consumer.consume(
                candidate(true, RemoteObservationOperationHandler.PrState.OPEN),
                acceptances::incrementAndGet));

        assertThat(acceptances).hasValue(2);
        assertThat(order).containsExactly(
                "branch", "ci", "branch", "ci", "merge", "readiness");
        verify(remote).resumeFeedbackCompletionInCommand("task-1", "feedback-1");
        verify(remote).proveReadinessInCommand(
                any(), any(), any(), any(), any());
    }

    @Test
    void mergeAutomationCanStartInsideTheObservationCommand()
    {
        SqliteRemoteMergeRuntimeStore store = mock(
                SqliteRemoteMergeRuntimeStore.class);
        RemoteMergeRuntimeCoordinator.Command command =
                new RemoteMergeRuntimeCoordinator.Command(
                        "command-1", "automation", "task-1", "stage-1",
                        "readiness-1", "authorization-1", "operation-1",
                        "ticket-1",
                        SqliteRemoteMergeRuntimeStore.AuthorityKind.AUTO_MERGE_POLICY,
                        "squash", 3);
        when(store.findStart("authorization-1")).thenReturn(Optional.of(
                new StartReceipt(
                        "authorization-1", "readiness-1",
                        SqliteRemoteMergeRuntimeStore.AuthorityKind.AUTO_MERGE_POLICY,
                        null, "merge-1", "operation-1", "ticket-1", "task-1",
                        "stage-1", 1, 1, 1, 3, "head-1", "base-1",
                        MergeMode.DIRECT, "squash")));
        TaskCommandExecutor commands = CommandTestSupport.executor();
        RemoteMergeRuntimeCoordinator coordinator =
                new RemoteMergeRuntimeCoordinator(
                        commands, mock(RemoteDevelopmentStageManager.class), store);

        RemoteMergeRuntimeCoordinator.Result result = commands.execute(
                "task-1", () -> coordinator.startInCommand(command));

        assertThat(result.disposition()).isEqualTo(
                CommandResult.Disposition.DUPLICATE);
    }

    @Test
    void greenDraftStartsPolicyMarkReadyWithoutAutoApprove()
    {
        SqliteRemoteDevelopmentRuntimeStore store = mock(
                SqliteRemoteDevelopmentRuntimeStore.class);
        RemoteDevelopmentRuntimeCoordinator remote = mock(
                RemoteDevelopmentRuntimeCoordinator.class);
        when(store.findFeedbackBatchAwaitingHead(
                "stage-1", "head-1", "base-1")).thenReturn(Optional.empty());
        when(store.requireContext("task-1", "stage-1")).thenReturn(
                new RemoteContext(
                        "workspace-1", "trunk-1", "task-1", 1, 1,
                        "stage-1", 1, 1, "AWAITING_READY", "binding-1",
                        "snapshot-1", 1, "head-1", "base-1", "fingerprint",
                        "/tmp/worktree"));
        when(store.requireAutomationPolicy("task-1")).thenReturn(
                new AutomationPolicy(
                        "policy-1", "task-1", 1, "USER", false, false,
                        false, 0, 0, false, false, false, "user", NOW));
        RemoteDevelopmentObservationConsumer consumer =
                new RemoteDevelopmentObservationConsumer(
                        store, mock(RemoteFeedbackRuntimeCoordinator.class), remote,
                        new Hooks(ignored -> ObservationDisposition.CONTINUE,
                                ignored -> ObservationDisposition.CONTINUE,
                                ignored -> {},
                                (ignored, readiness) -> {}),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        CommandTestSupport.executor().execute(
                "task-1", () -> consumer.consume(
                        candidate(true, RemoteObservationOperationHandler.PrState.DRAFT),
                        () -> {}));

        verify(remote).startMarkReadyInCommand(
                "task-1", "stage-1",
                RemoteDevelopmentRuntimeCoordinator.MarkReadyAuthority.POLICY,
                null, 3);
    }

    @Test
    void manualMarkReadyIsIdempotentForTheExactDraftSubject()
    {
        SqliteRemoteDevelopmentRuntimeStore store = mock(
                SqliteRemoteDevelopmentRuntimeStore.class);
        RemoteContext context = new RemoteContext(
                "workspace-1", "trunk-1", "task-1", 1, 1, "stage-1", 1, 1,
                "AWAITING_READY", "binding-1", "snapshot-1", 1, "head-1",
                "base-1", "fingerprint", "/tmp/worktree");
        MarkReadyDispatch dispatch = new MarkReadyDispatch(
                "authorization-1", "mark-ready-1", "operation-1", "ticket-1",
                AuthorityKind.MANUAL, 1);
        when(store.requireContext("task-1", "stage-1")).thenReturn(context);
        when(store.findMarkReadyDispatch(
                "task-1", "stage-1", "head-1", "base-1"))
                .thenReturn(Optional.empty(), Optional.of(dispatch));
        when(store.authorizeMarkReady(
                any(), any(), any(), any(), any(), any(), anyInt(),
                any())).thenReturn(dispatch);
        RemoteDevelopmentRuntimeCoordinator coordinator =
                new RemoteDevelopmentRuntimeCoordinator(
                        CommandTestSupport.executor(),
                        mock(RemoteDevelopmentStageManager.class), store,
                        new ObjectMapper(), mock(PRService.class),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(coordinator.startMarkReady(
                "task-1", "stage-1",
                RemoteDevelopmentRuntimeCoordinator.MarkReadyAuthority.HUMAN,
                "user-1", 3)).contains(dispatch);
        assertThat(coordinator.startMarkReady(
                "task-1", "stage-1",
                RemoteDevelopmentRuntimeCoordinator.MarkReadyAuthority.HUMAN,
                "user-1", 3)).contains(dispatch);

        verify(store, times(1)).authorizeMarkReady(
                any(), any(), any(), any(), any(), any(), anyInt(),
                any());
    }

    @Test
    void acceptedMarkReadyProjectsTheStablePrEdgeExactlyOnce()
    {
        SqliteRemoteDevelopmentRuntimeStore store = mock(
                SqliteRemoteDevelopmentRuntimeStore.class);
        RemoteDevelopmentStageManager remote = mock(
                RemoteDevelopmentStageManager.class);
        PRService prs = mock(PRService.class);
        MarkReadyDeliveryContext delivery = new MarkReadyDeliveryContext(
                "mark-ready-1", "operation-1", "SUCCEEDED", "task-1", 1,
                "stage-1", 1, 7, "head-1", "base-1", "snapshot-1", true);
        RemoteContext context = new RemoteContext(
                "workspace-1", "trunk-1", "task-1", 1, 1, "stage-1", 1, 7,
                "AWAITING_READY", "binding-1", "snapshot-1", 1, "head-1",
                "base-1", "fingerprint", "/tmp/worktree");
        PR drafted = PR.create(
                        "pr-1", "task-1", "feature/x", "main", "Title", "",
                        NOW.minusSeconds(10))
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW.minusSeconds(9))
                .withRemote("acme/widget", 17,
                        "https://github.com/acme/widget/pull/17",
                        NOW.minusSeconds(8))
                .withStatus(PR.STATUS_REMOTE_DRAFTED, NOW.minusSeconds(8));
        AtomicReference<RuntimeDeliveryReceipt> receipt =
                new AtomicReference<>();
        when(store.requireMarkReadyDelivery("operation-1"))
                .thenReturn(delivery);
        when(store.findRuntimeDeliveryReceipt("operation-1"))
                .thenAnswer(ignored -> Optional.ofNullable(receipt.get()));
        doAnswer(invocation -> {
            receipt.set(invocation.getArgument(0));
            return null;
        }).when(store).insertRuntimeDeliveryReceipt(any());
        when(store.requireContext("task-1", "stage-1")).thenReturn(context);
        when(remote.completeMarkReadyInCommand(any())).thenReturn(
                CommandResult.applied(mock(StageManager.State.class)));
        when(prs.findByTask("task-1")).thenReturn(Optional.of(drafted));
        when(prs.transition(
                "pr-1", PR.STATUS_REMOTE_OPEN, "v2-remote-runtime"))
                .thenReturn(drafted.withStatus(PR.STATUS_REMOTE_OPEN, NOW));
        RemoteDevelopmentRuntimeCoordinator coordinator =
                new RemoteDevelopmentRuntimeCoordinator(
                        CommandTestSupport.executor(), remote, store,
                        new ObjectMapper(), prs,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.STAGE, "stage-1",
                RemoteDevelopmentRuntimeCoordinator.MARK_READY_CALLBACK);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 1, null,
                "head-1", "base-1");
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED, "{}", "{}", null);

        assertThat(coordinator.deliverMarkReady(owner, fence, result).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(coordinator.deliverMarkReady(owner, fence, result).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);

        verify(remote, times(1)).completeMarkReadyInCommand(any());
        verify(prs, times(1)).transition(
                "pr-1", PR.STATUS_REMOTE_OPEN, "v2-remote-runtime");
    }

    @Test
    void acceptedAlreadyOpenObservationProjectsTheStablePrEdge()
    {
        SqliteRemoteDevelopmentRuntimeStore store = mock(
                SqliteRemoteDevelopmentRuntimeStore.class);
        RemoteDevelopmentStageManager remote = mock(
                RemoteDevelopmentStageManager.class);
        RemoteCiRepairRuntimeCoordinator ciRepair = mock(
                RemoteCiRepairRuntimeCoordinator.class);
        PRService prs = mock(PRService.class);
        RemoteContext context = new RemoteContext(
                "workspace-1", "trunk-1", "task-1", 1, 1, "stage-1", 1, 7,
                "AWAITING_READY", "binding-1", "snapshot-1", 1, "head-1",
                "base-1", "fingerprint", "/tmp/worktree");
        PR drafted = PR.create(
                        "pr-1", "task-1", "feature/x", "main", "Title", "",
                        NOW.minusSeconds(10))
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW.minusSeconds(9))
                .withRemote("acme/widget", 17,
                        "https://github.com/acme/widget/pull/17",
                        NOW.minusSeconds(8))
                .withStatus(PR.STATUS_REMOTE_DRAFTED, NOW.minusSeconds(8));
        when(ciRepair.acceptObservationInCommand(any()))
                .thenReturn(ObservationDisposition.CONTINUE);
        when(store.requireContext("task-1", "stage-1")).thenReturn(context);
        when(remote.acceptObservedReadyInCommand(any())).thenReturn(
                CommandResult.applied(mock(StageManager.State.class)));
        when(prs.findByTask("task-1")).thenReturn(Optional.of(drafted));
        when(prs.transition(
                "pr-1", PR.STATUS_REMOTE_OPEN, "remote-observer"))
                .thenReturn(drafted.withStatus(PR.STATUS_REMOTE_OPEN, NOW));
        RemoteObservationDomainHooks hooks = new RemoteObservationDomainHooks(
                store, remote, ciRepair,
                mock(BranchSyncRuntimeCoordinator.class),
                mock(RemoteMergeObservationCoordinator.class),
                mock(RemoteMergeRuntimeCoordinator.class), prs);

        CommandTestSupport.executor().execute("task-1", () ->
                hooks.acceptCiInCommand(candidate(
                        true, RemoteObservationOperationHandler.PrState.OPEN)));

        verify(prs).transition(
                "pr-1", PR.STATUS_REMOTE_OPEN, "remote-observer");
    }

    private static RemoteObservationConsumer.Candidate candidate(boolean current)
    {
        return candidate(
                current, RemoteObservationOperationHandler.PrState.CLOSED);
    }

    private static RemoteObservationConsumer.Candidate candidate(
            boolean current, RemoteObservationOperationHandler.PrState prState)
    {
        ObservationDelivery delivery = new ObservationDelivery(
                "row-1", "operation-1", "task-1", 1, "stage-1", 1,
                "binding-1", "ci-policy-1", "acme/widget", 17,
                "head-1", "base-1", "head-1", "base-1", 0, 1, current,
                mock(RemoteCiPolicy.Policy.class), Set.of());
        RemoteObservationOperationHandler.FeedbackFact feedback =
                new RemoteObservationOperationHandler.FeedbackFact(
                        RemoteObservationOperationHandler.FeedbackKind.TOP_LEVEL_COMMENT,
                        "comment:41", "reviewer", false, null, "41", null,
                        null, "please fix", null, "raw-comment");
        RemoteObservationOperationHandler.Observation observation =
                new RemoteObservationOperationHandler.Observation(
                        1, "observation-1", "head-1", "base-1",
                        prState,
                        RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                        RemoteObservationOperationHandler.MergeQueueState.NONE,
                        RemoteObservationOperationHandler.MergeQueueCapability.SUPPORTED,
                        0, 0, 0, 0, 0, 0, List.of(), List.of(feedback),
                        null, null, null, "raw-observation", NOW.toEpochMilli());
        RemoteCiPolicy.Evaluation ci = new RemoteCiPolicy.Evaluation(
                RemoteCiPolicy.CheckState.PASSED,
                RemoteCiPolicy.PolicyOutcome.ACCEPTED, List.of(), 0, 0);
        ObservationEvidence evidence = new ObservationEvidence(
                "snapshot-1", "ci-1", 1, "head-1", "base-1",
                RemoteCiPolicy.PolicyOutcome.ACCEPTED, NOW.toEpochMilli());
        return new RemoteObservationConsumer.Candidate(
                delivery, observation, ci, evidence);
    }
}
