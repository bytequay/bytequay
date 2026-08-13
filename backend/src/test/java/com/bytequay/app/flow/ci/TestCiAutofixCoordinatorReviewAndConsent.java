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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.gate.UserGateRecords.AuthorizedCiUpdate;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateConsentRevision;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateEffectActivation;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGateRecords.GateState;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetSource;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckPolicyRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.bytequay.app.flow.runtime.LocalChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.ConnectionCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.flow.github.GitHubProviderFixtures.observation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reviewer reservation and completion, one-shot consent authorization,
 * and the publication barrier that guards reconciliation.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestCiAutofixCoordinatorReviewAndConsent
        extends BaseTestCiAutofixCoordinator
{
    @Test
    void lateSecondRevisionFailureRollsBackAndStoppedRetryDoesNotRerunBody()
    {
        CompletedReady first = openReadyGate("rollback-first-ready");
        String firstCandidate = runtime.currentChangeSet(task.taskId())
                .orElseThrow().headSha();
        runtime.advanceRemoteHead(
                pr.prId(), publishedHead, firstCandidate);
        publishedHead = firstCandidate;
        ReviewerResultReady second = prepareReviewerResult(
                "rollback-second-ready", "failure-rollback-second");
        AtomicInteger bodies = new AtomicInteger();
        CountDownLatch sealed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = second.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        second.binding(),
                        second.claim(),
                        capability -> {
                            bodies.incrementAndGet();
                            capability.readyForReview();
                            sealed.countDown();
                            awaitLatch(release);
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque retryable ready",
                                            null);
                        });
        awaitLatch(sealed);
        jdbc.execute("""
                CREATE TRIGGER fail_ready_result
                BEFORE INSERT ON flow_runtime_agent_result
                WHEN NEW.run_id = '%s'
                BEGIN
                    SELECT RAISE(ABORT, 'forced ready result failure');
                END
                """.formatted(second.binding().run().runId()));
        release.countDown();

        assertThatThrownBy(() -> second.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor,
                        second.binding(),
                        handle,
                        TTL)).isInstanceOf(RuntimeException.class);

        var gateAfterFailure = userGates.gate(pr.prId()).orElseThrow();
        assertThat(gateAfterFailure.currentRevision()).isEqualTo(1);
        assertThat(userGates.transitions(gateAfterFailure.gateId()))
                .extracting(transition -> transition.reasonCode())
                .containsExactly("READY");
        assertThat(userGates.revisionForRun(
                second.binding().run().runId())).isEmpty();
        assertThat(runtime.resultForRun(second.binding().run().runId()))
                .isEmpty();
        var readyRequest = runtime.readyForReviewRequestForRun(
                second.binding().run().runId()).orElseThrow();
        assertThat(userGates.subject(readyRequest.subjectRef())).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_user_gate_ci_update_action "
                        + "WHERE action_ref = ?",
                Integer.class,
                readyRequest.actionRef())).isEqualTo(1);
        assertThat(runtime.session(task.taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.RUNNING);
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(second.claim().operationId());
        assertThat(bodies).hasValue(1);

        jdbc.execute("DROP TRIGGER fail_ready_result");
        AgentResult retried = second.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor,
                        second.binding(),
                        handle,
                        TTL);
        assertThat(retried.runId()).isEqualTo(second.binding().run().runId());
        assertThat(bodies).hasValue(1);
        var gate = userGates.gate(pr.prId()).orElseThrow();
        assertThat(gate.currentRevision()).isEqualTo(2);
        assertThat(userGates.transitions(gate.gateId()))
                .extracting(transition -> transition.reasonCode())
                .containsExactly(
                        "READY", "SUPERSEDED_BY_READY", "READY");
        assertThat(userGates.revisionForRun(
                first.ready().binding().run().runId()))
                .contains(first.revision());
    }

    @Test
    void reviewerReservationRejectsAnOlderEvidenceAttempt()
    {
        ReviewReady ready = prepareCleanReview("latest-check-evidence");
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        String finalizerKey = "TEST_LATEST_CHECK:"
                + ready.binding().run().runId();
        var handle = supervisor.launch(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence(),
                finalizerKey,
                (runId, claim, fence, completion) ->
                        runtime.finishTaskAgentReviewTurn(
                                runId,
                                claim,
                                fence,
                                completion.terminalOutcome(),
                                completion.finalContent(),
                                completion.errorRef()),
                capability -> {
                    capability.runChecks(localChecks, repositoryRoot, null);
                    ChangeSetRevision current = runtime.currentChangeSet(
                            task.taskId()).orElseThrow();
                    LocalChecks.ReviewerEvidence old =
                            localChecks.reviewerEvidence(
                                    task.taskId(),
                                    current.changeSetRevisionId(),
                                    GateIntent.CI_UPDATE);
                    capability.runChecks(localChecks, repositoryRoot, null);
                    assertThatThrownBy(() ->
                            capability.spawnAdversarialReviewer(
                                    repositoryRoot,
                                    ready.binding().run()
                                            .inputChangeSetRevisionId(),
                                    reviewOrigin(ready),
                                    old))
                            .isInstanceOf(
                                    FlowRuntime.StaleOwnerRevisionException.class)
                            .hasMessageContaining("complete/latest");
                    request.set(capability.spawnAdversarialReviewer(
                            repositoryRoot,
                            ready.binding().run()
                                    .inputChangeSetRevisionId(),
                            reviewOrigin(ready),
                            localChecks.reviewerEvidence(
                                    task.taskId(),
                                    current.changeSetRevisionId(),
                                    GateIntent.CI_UPDATE)));
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        supervisor.awaitAndFinalize(handle, TTL, finalizerKey);

        assertThat(request.get().checkRunRefs()).hasSize(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT attempt_sequence FROM flow_runtime_local_check_run
                WHERE check_run_id = ?
                """,
                Long.class,
                request.get().checkRunRefs().getFirst())).isEqualTo(2);
    }

    @Test
    void durableReviewerRequestReplaysAfterPolicyAdvances()
    {
        ReviewReady ready = prepareCleanReview("frozen-policy-replay");
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.review().launchTaskInspection(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    capability.runChecks();
                    request.set(capability.spawnAdversarialReviewer());
                    LocalCheckPolicyRevision current = localChecks
                            .currentPolicy(task.repositoryId()).orElseThrow();
                    localChecks.recordPolicy(
                            task.repositoryId(),
                            current.policyRevisionId(),
                            "test-policy:v2",
                            "test-policy-digest:v2",
                            List.of(new LocalChecks.ProfileDefinition(
                                    "true",
                                    List.of("/usr/bin/true"),
                                    ".",
                                    List.of(),
                                    Duration.ofSeconds(5),
                                    List.of(GateIntent.CI_UPDATE))));
                    assertThat(capability.spawnAdversarialReviewer())
                            .isEqualTo(request.get());
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        ready.review().awaitTaskInspection(
                supervisor, ready.binding(), handle, TTL);

        assertThat(request.get().localCheckPolicyRevisionId())
                .isNotEqualTo(localChecks.currentPolicy(task.repositoryId())
                        .orElseThrow().policyRevisionId());
    }

    @Test
    void timedOutLocalCheckSealsToolsAndLeavesTaskInAttention()
    {
        assertBoundaryUnprovenNeedsAttention(
                "timeout", List.of("/bin/sleep", "8"),
                Duration.ofSeconds(1));
    }

    @Test
    void heldOpenCheckPipeSealsToolsAndLeavesTaskInAttention()
    {
        assertBoundaryUnprovenNeedsAttention(
                "held-pipe",
                List.of("/bin/sh", "-c", "(sleep 8) & exit 0"),
                Duration.ofSeconds(5));
    }

    @Test
    void missingTerminalReviewerCommandConsumesInputAndReplaysAfterCancel()
    {
        ReviewReady ready = prepareCleanReview("missing-review-command");
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.COMPLETED,
                "I claim success without the required command",
                null);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.review().launchTaskInspection(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> completion);
        AgentResult result = ready.review().awaitTaskInspection(
                supervisor, ready.binding(), handle, TTL);

        Task attention = runtime.task(task.taskId()).orElseThrow();
        assertThat(attention.status()).isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.pendingId().equals(
                        ready.binding().input().pendingId()))
                .singleElement()
                .satisfies(work -> assertThat(work.handledByOperationId())
                        .isEqualTo(ready.claim().operationId()));
        assertThatThrownBy(() -> runtime.transitionTask(
                attention.taskId(),
                attention.currentLifecycleRevisionId(),
                TaskStatus.ACTIVE,
                "UNSAFE_RESUME",
                "test:resume"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("typed recovery");
        runtime.transitionTask(
                attention.taskId(),
                attention.currentLifecycleRevisionId(),
                TaskStatus.CANCELED,
                "USER_CANCELED",
                "test:canceled");

        AgentResult replayed = runtime.finishTaskAgentReviewTurn(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence(),
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef());
        assertThat(replayed).isEqualTo(result);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_writer_lease WHERE task_id = ?",
                Integer.class,
                task.taskId())).isZero();
    }

    @Test
    void taskOwnedCorrectionBecomesTheFrozenReviewerSubject()
    {
        ReviewReady ready = prepareCleanReview("task-correction");
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.review().launchTaskInspection(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    capability.runTool(() -> {
                        commitCiChange(
                                "task-correction.txt",
                                "corrected\n",
                                "Task correction");
                        runtime.adoptChangeSet(
                                ready.claim(),
                                ready.binding().fence(),
                                repositoryRoot,
                                ready.binding().run()
                                        .inputChangeSetRevisionId());
                    });
                    capability.runChecks();
                    request.set(capability.spawnAdversarialReviewer());
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        ready.review().awaitTaskInspection(
                supervisor, ready.binding(), handle, TTL);

        ChangeSetRevision current = runtime.currentChangeSet(task.taskId())
                .orElseThrow();
        assertThat(current.source()).isEqualTo(ChangeSetSource.TASK_AGENT);
        assertThat(current.sourceOperationId())
                .isEqualTo(ready.claim().operationId());
        assertThat(current.sourceRunId())
                .isEqualTo(ready.binding().run().runId());
        assertThat(request.get().changeSetRevisionId())
                .isEqualTo(current.changeSetRevisionId());
        assertThat(request.get().reviewedHeadSha())
                .isEqualTo(current.headSha());

        Claim reviewerClaim = claim(OperationKind.RUN_REVIEWER);
        var reviewerStart = ready.review().beginReviewer(
                request.get().requestId(), reviewerClaim);
        var reviewerSupervisor = new InProcessReviewerAgentSupervisor(runtime);
        var reviewerHandle = ready.review().launchReviewer(
                reviewerSupervisor,
                reviewerStart,
                reviewerClaim,
                capability -> new InProcessReviewerAgentSupervisor
                        .AgentCompletion(
                                TerminalOutcome.COMPLETED,
                                "opaque corrected review",
                                null));
        ready.review().awaitReviewer(
                reviewerSupervisor, reviewerHandle, TTL);
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(runtime.selectNext(reconciliation).orElseThrow().kind())
                .isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim continuation = claim(OperationKind.RUN_TASK_TURN);
        var continuationBinding = ready.review()
                .beginReviewerResultContinuation(continuation, TTL);
        var continuationHandle = ready.review()
                .launchReviewerResultContinuation(
                        supervisor,
                        continuationBinding,
                        continuation,
                        capability -> {
                            capability.readyForReview();
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque corrected ready",
                                            null);
                        });
        ready.review().awaitReviewerResultContinuation(
                supervisor,
                continuationBinding,
                continuationHandle,
                TTL);

        var revision = userGates.revisionForRun(
                continuationBinding.run().runId()).orElseThrow();
        var subject = userGates.subject(
                revision.subjectManifestRef()).orElseThrow();
        assertThat(subject.changeSetRevisionId())
                .isEqualTo(current.changeSetRevisionId());
        assertThat(subject.proposedHead()).isEqualTo(current.headSha());
        assertThat(subject.originCiFixPendingId())
                .isEqualTo(request.get().originCiFixPendingId());
        assertThat(subject.originCiFixSourceKind())
                .isEqualTo(request.get().originCiFixSourceKind());
        assertThat(subject.originCiFixSourceId())
                .isEqualTo(request.get().originCiFixSourceId());
    }

    @Test
    void reviewerCompletionReplacesReadyReconciliationWithResultPriority()
    {
        assertReviewerCompletionReplacesReconciliation(false);
    }

    @Test
    void reviewerCompletionReplacesWaitingReconciliationWithResultPriority()
    {
        assertReviewerCompletionReplacesReconciliation(true);
    }

    @Test
    void changedReviewerResultTurnWithoutFreshReviewNeedsAttention()
    {
        ReviewerResultReady ready = prepareReviewerResult("changed-no-review");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.runTool(() -> {
                                commitCiChange(
                                        "changed-after-review.txt",
                                        "changed\n",
                                        "Change after review");
                                runtime.adoptChangeSet(
                                        ready.claim(),
                                        ready.binding().fence(),
                                        repositoryRoot,
                                        ready.binding().run()
                                                .inputChangeSetRevisionId());
                            });
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque no child",
                                            null);
                        });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(jdbc.queryForObject(
                """
                SELECT reason_code
                FROM flow_runtime_task_lifecycle_revision
                WHERE task_id = ? ORDER BY sequence DESC LIMIT 1
                """,
                String.class,
                task.taskId())).isEqualTo("CHANGED_WITHOUT_FRESH_REVIEW");
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind()
                        == PendingKind.AGENT_RESULT_READY)
                .singleElement()
                .satisfies(work -> assertThat(work.handledByOperationId())
                        .isEqualTo(ready.claim().operationId()));
    }

    @Test
    void changedReviewerResultTurnCanRequestFreshReviewer()
    {
        ReviewerResultReady ready = prepareReviewerResult("changed-reviewed");
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.runTool(() -> {
                                commitCiChange(
                                        "changed-and-reviewed.txt",
                                        "changed\n",
                                        "Change and review");
                                runtime.adoptChangeSet(
                                        ready.claim(),
                                        ready.binding().fence(),
                                        repositoryRoot,
                                        ready.binding().run()
                                                .inputChangeSetRevisionId());
                            });
                            capability.runChecks();
                            request.set(
                                    capability.spawnAdversarialReviewer());
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque fresh child",
                                            null);
                        });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        ChangeSetRevision current = runtime.currentChangeSet(task.taskId())
                .orElseThrow();
        assertThat(request.get().changeSetRevisionId())
                .isEqualTo(current.changeSetRevisionId());
        assertThat(request.get().reviewedHeadSha())
                .isEqualTo(current.headSha());
        assertThat(runtime.session(task.taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.PARKED_CHILD);
        assertThat(runtime.operation(request.get().reviewerOperationId())
                .orElseThrow().state()).isEqualTo(OperationState.READY);
    }

    @ParameterizedTest
    @EnumSource(value = TerminalOutcome.class, names = {"FAILED", "CANCELED"})
    void terminalParentWithCommittedRequestStillParksAndActivatesReviewer(
            TerminalOutcome parentOutcome)
    {
        ReviewReady ready = prepareCleanReview(
                "terminal-parent-" + parentOutcome);
        ParkedReview parked = parkForReviewer(ready, parentOutcome);

        assertThat(parked.parentResult().terminalOutcome())
                .isEqualTo(parentOutcome);
        assertThat(runtime.session(task.taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.PARKED_CHILD);
        Claim reviewer = claim(OperationKind.RUN_REVIEWER);
        var start = ready.review().beginReviewer(
                parked.request().requestId(), reviewer);
        assertThat(start.run().state()).isEqualTo(RunState.QUEUED);
        assertThat(start.request()).isEqualTo(parked.request());
    }

    @Test
    void terminalReviewerRequestRejectsConflictingReplayAndLaterTools()
    {
        ReviewReady ready = prepareCleanReview("terminal-replay");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        String finalizerKey = "TEST_TASK_REVIEW:" + ready.binding().run()
                .runId();
        var handle = supervisor.launch(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence(),
                finalizerKey,
                (runId, claim, fence, completion) ->
                        runtime.finishTaskAgentReviewTurn(
                                runId,
                                claim,
                                fence,
                                completion.terminalOutcome(),
                                completion.finalContent(),
                                completion.errorRef()),
                capability -> {
                    capability.runChecks(
                            localChecks, repositoryRoot, null);
                    ChangeSetRevision current = runtime.currentChangeSet(
                            task.taskId()).orElseThrow();
                    ReviewerRequest request = capability
                            .spawnAdversarialReviewer(
                                    repositoryRoot,
                                    ready.binding().run()
                                            .inputChangeSetRevisionId(),
                                    reviewOrigin(ready),
                                    localChecks.reviewerEvidence(
                                            task.taskId(),
                                            current.changeSetRevisionId(),
                                            GateIntent.CI_UPDATE));
                    assertThat(capability.replayAdversarialReviewer(
                            repositoryRoot,
                            ready.binding().run()
                                    .inputChangeSetRevisionId(),
                            reviewOrigin(ready),
                            request.localCheckPolicyRevisionId(),
                            request.checkRunRefs())).isEqualTo(request);
                    assertThatThrownBy(() ->
                            capability.replayAdversarialReviewer(
                                    repositoryRoot,
                                    ready.binding().run()
                                            .inputChangeSetRevisionId(),
                                    reviewOrigin(ready),
                                    request.localCheckPolicyRevisionId(),
                                    List.of("conflicting-check")))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("terminal arguments");
                    assertThatThrownBy(() -> capability.runTool(() -> {}))
                            .isInstanceOf(
                                    FlowRuntime.StaleCapabilityException.class);
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        supervisor.awaitAndFinalize(handle, TTL, finalizerKey);
    }

    @Test
    void expiredReservedReviewerRedrivesTheSameRun()
    {
        ReviewerClaim reviewer = prepareReviewerClaim("reserved-expiry");
        insertReviewerProcessAttempt(
                reviewer, "reserved-reviewer", "RESERVED");
        jdbc.update("""
                UPDATE flow_runtime_agent_run
                SET prompt_manifest_ref = ?
                WHERE run_id = ?
                """,
                "adversarial-reviewer-prompt:v2",
                reviewer.start().run().runId());
        expireRuntime();

        assertThat(runtime.recoverExpiredClaim(
                reviewer.claim().operationId(),
                reviewer.claim().generation())).isTrue();
        assertThat(runtime.operation(reviewer.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.RETRYABLE);
        runtime.redriveRetryable(reviewer.claim().operationId());
        Claim redriven = claim(OperationKind.RUN_REVIEWER);
        var start = runtime.startReviewerAgent(
                reviewer.request().requestId(),
                redriven,
                "adversarial-reviewer-prompt:v3",
                "immutable-git-object-reader:v1");
        assertThat(start.run().runId()).isEqualTo(reviewer.start().run().runId());
        assertThat(start.run().promptManifestRef())
                .isEqualTo("adversarial-reviewer-prompt:v2");
        assertThat(redriven.generation())
                .isEqualTo(reviewer.claim().generation() + 1);
    }

    @Test
    void expiredActivatedReviewerQuarantinesWithoutRedrive()
    {
        ReviewerClaim reviewer = prepareReviewerClaim("activated-expiry");
        insertReviewerProcessAttempt(
                reviewer, "activated-reviewer", "ACTIVATED");
        jdbc.update("""
                UPDATE flow_runtime_agent_run
                SET state = 'RUNNING', started_at = ?
                WHERE run_id = ? AND state = 'QUEUED'
                """, NOW.toEpochMilli(), reviewer.start().run().runId());
        expireRuntime();

        assertThat(runtime.recoverExpiredClaim(
                reviewer.claim().operationId(),
                reviewer.claim().generation())).isFalse();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.operation(reviewer.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.FAILED);
        assertThatThrownBy(() -> runtime.redriveRetryable(
                reviewer.claim().operationId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never-launched retry");
        assertThat(runtime.claimNext("other-worker", TTL)).isEmpty();
    }

    @Test
    void exactManualAuthorizationBuildsOneClaimablePlanAndBeginReplays()
    {
        CompletedReady ready = openReadyGate("manual-authorization");
        GateRevision revision = ready.revision();

        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(),
                revision.revision(),
                revision.subjectDigest(),
                revision.actionDigest(),
                "authorize-manual-1");
        assertThat(userGates.authorizeCiUpdate(
                revision.gateId(),
                revision.revision(),
                revision.subjectDigest(),
                revision.actionDigest(),
                "authorize-manual-1")).isEqualTo(authorized);
        assertThat(githubEffects.steps(authorized.planId())).singleElement()
                .satisfies(step -> {
                    assertThat(step.ordinal()).isEqualTo(1);
                    assertThat(step.forcePush()).isFalse();
                });
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN, GateState.AUTHORIZED);
        assertThat(jdbc.queryForMap(
                """
                SELECT actor_type, actor_id
                FROM flow_user_gate_transition
                WHERE gate_id = ? AND reason_code = 'MANUAL_AUTHORIZATION'
                """,
                revision.gateId()))
                .containsEntry("actor_type", "USER")
                .containsEntry("actor_id", "LOCAL_DESKTOP_USER");

        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        assertThat(userGates.beginCiUpdateEffect(claim))
                .isEqualTo(activation);
        assertThat(activation.operationId())
                .isEqualTo(authorized.operationId());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.AUTHORIZED,
                        GateState.EXECUTING);
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_step", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void oneShotConsentAuthorizesOnlyTheNewExactGateGraph()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-one-shot");
        assertThat(userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-one-shot")).isEqualTo(consent);

        CompletedReady ready = openReadyGate("automatic-authorization");
        GateRevision revision = ready.revision();
        String authorizationId = jdbc.queryForObject(
                "SELECT authorization_id FROM flow_user_gate_authorization",
                String.class);
        var authorization = userGates.authorization(
                authorizationId).orElseThrow();
        assertThat(authorization.authority())
                .isEqualTo("CI_UPDATE_CONSENT");
        assertThat(authorization.actorId())
                .isEqualTo("USER_GATES_CI_CONSENT");
        assertThat(authorization.consentId()).isEqualTo(consent.consentId());
        assertThat(authorization.consentRevision())
                .isEqualTo(consent.revision());
        assertThat(authorization.consentDigest())
                .isEqualTo(consent.revisionDigest());
        assertThat(count("flow_user_gate_authorization", "consent_id IS NOT NULL"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_attempt", "1 = 1"))
                .isZero();
        assertThat(count("flow_github_external_effect_probe", "1 = 1"))
                .isZero();
        assertThat(count("flow_github_external_effect_receipt", "1 = 1"))
                .isZero();
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.reasonCode())
                .containsExactly("READY", "CI_UPDATE_CONSENT_AUTHORIZATION");
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "later-manual-click"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("AUTHORIZATION_AUTHORITY_CONFLICT");

        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        assertThat(activation.operationId())
                .isEqualTo(authorization.operationId());
    }

    @Test
    void consentDoesNotRetroactivelyAuthorizeAnExistingOpenGate()
    {
        CompletedReady ready = openReadyGate("consent-not-retroactive");
        userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-after-open");

        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(userGates.transitions(ready.revision().gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN);
    }

    @Test
    void revokedConsentStopsOnlyAnUnbegunEffect()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-before-revoke");
        CompletedReady ready = openReadyGate("consent-revoked-before-begin");
        var authorization = userGates.authorization(jdbc.queryForObject(
                "SELECT authorization_id FROM flow_user_gate_authorization",
                String.class)).orElseThrow();
        userGates.revokeCiUpdateConsent(
                consent.consentId(), consent.revision(), "revoke-before-begin");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(claim))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("CI_UPDATE_CONSENT_REVOKED");
        assertThat(runtime.operation(authorization.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CANCELED);
        assertThat(userGates.transitions(ready.revision().gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN, GateState.AUTHORIZED, GateState.STALE);
    }

    @Test
    void expiredConsentStopsBeforeBegin()
    {
        userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofMinutes(10)),
                "grant-before-expiry");
        CompletedReady expiring = openReadyGate("consent-expires-before-begin");
        var expiringAuthorization = userGates.authorization(
                jdbc.queryForObject(
                        "SELECT authorization_id "
                                + "FROM flow_user_gate_authorization",
                        String.class)).orElseThrow();
        advancePublicationClock(Duration.ofMinutes(11));
        Claim expiredClaim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(expiredClaim))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("CI_UPDATE_CONSENT_EXPIRED");
        assertThat(runtime.operation(expiringAuthorization.operationId())
                .orElseThrow().state()).isEqualTo(OperationState.CANCELED);
        assertThat(userGates.transitions(expiring.revision().gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN, GateState.AUTHORIZED, GateState.STALE);
    }

    @Test
    void executingConsentEffectIsFrozenAcrossLaterRevocation()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-before-execution");
        openReadyGate("consent-revoked-after-begin");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);

        userGates.revokeCiUpdateConsent(
                consent.consentId(), consent.revision(),
                "revoke-after-begin");

        assertThat(userGates.beginCiUpdateEffect(claim))
                .isEqualTo(activation);
        assertThat(runtime.operation(activation.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);
    }

    @Test
    void begunConsentRemainsFrozenThroughNeverStartedClaimRecovery()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofMinutes(10)),
                "grant-before-begun-recovery");
        openReadyGate("consent-begun-recovery");
        Claim first = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        userGates.beginCiUpdateEffect(first);
        userGates.revokeCiUpdateConsent(
                consent.consentId(), consent.revision(),
                "revoke-begun-recovery");
        advancePublicationClock(TTL.plusSeconds(1));

        userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation());
        Claim redelivery = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();

        assertThat(userGates.beginCiUpdateEffect(redelivery).mutationAllowed())
                .isTrue();
        advancePublicationClock(TTL.plusSeconds(1));
        userGates.recoverExpiredCiUpdateEffect(
                redelivery.operationId(), redelivery.generation());
        Claim third = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        assertThat(userGates.beginCiUpdateEffect(third).mutationAllowed())
                .isTrue();
    }

    @Test
    void unbegunConsentRecoveryStillHonorsRevocation()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-before-unbegun-recovery");
        openReadyGate("consent-unbegun-recovery");
        Claim first = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        userGates.revokeCiUpdateConsent(
                consent.consentId(), consent.revision(),
                "revoke-unbegun-recovery");
        advancePublicationClock(TTL.plusSeconds(1));

        userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation());
        Claim redelivery = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();

        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(redelivery))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("CI_UPDATE_CONSENT_REVOKED");
    }

    @Test
    void consentGrantIsBoundedAndConcurrentReplayCreatesOneRevision()
            throws Exception
    {
        assertThatThrownBy(() -> userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(24)).plusMillis(1),
                "too-long-consent"))
                .isInstanceOf(IllegalArgumentException.class);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<CiUpdateConsentRevision> first =
                new AtomicReference<>();
        AtomicReference<CiUpdateConsentRevision> second =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable grant = () -> {
            awaitLatch(start);
            try {
                CiUpdateConsentRevision value =
                        userGates.grantCiUpdateConsent(
                        task.taskId(), NOW.plus(Duration.ofHours(1)),
                        "concurrent-consent-grant");
                if (!first.compareAndSet(null, value)) {
                    second.set(value);
                }
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        };
        Thread left = new Thread(grant, "left-consent-grant");
        Thread right = new Thread(grant, "right-consent-grant");
        left.start();
        right.start();
        start.countDown();
        left.join(30_000);
        right.join(30_000);

        assertThat(failure.get()).isNull();
        assertThat(first.get()).isEqualTo(second.get());
        assertThat(count(
                "flow_user_gate_ci_consent_revision", "1 = 1"))
                .isEqualTo(1);
        assertThat(count(
                "flow_user_gate_ci_consent_current", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void consentCommandReplayIsExactAndActiveGrantCannotBeReplaced()
    {
        var granted = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "exact-grant-key");
        assertThatThrownBy(() -> userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(2)),
                "exact-grant-key"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_REPLAY_CONFLICT");
        assertThatThrownBy(() -> userGates.grantCiUpdateConsent(
                "different-task", NOW.plus(Duration.ofHours(1)),
                "exact-grant-key"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_REPLAY_CONFLICT");
        assertThatThrownBy(() -> userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "second-active-grant"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_ALREADY_ACTIVE");
        assertThat(count(
                "flow_user_gate_ci_consent_revision", "1 = 1"))
                .isEqualTo(1);

        var revoked = userGates.revokeCiUpdateConsent(
                granted.consentId(), granted.revision(),
                "exact-revoke-key");
        assertThat(userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "exact-grant-key")).isEqualTo(granted);
        assertThat(userGates.revokeCiUpdateConsent(
                granted.consentId(), granted.revision(),
                "exact-revoke-key")).isEqualTo(revoked);
        assertThat(userGates.currentCiUpdateConsent(task.taskId()))
                .contains(revoked);
        assertThatThrownBy(() -> userGates.revokeCiUpdateConsent(
                "different-consent", granted.revision(),
                "exact-revoke-key"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_REPLAY_CONFLICT");
        assertThatThrownBy(() -> userGates.revokeCiUpdateConsent(
                granted.consentId(), granted.revision() + 1,
                "exact-revoke-key"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_REPLAY_CONFLICT");
    }

    @Test
    void revokedConsentBeforeGateCreationLeavesTheNewGateManual()
    {
        var granted = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-then-revoke");
        userGates.revokeCiUpdateConsent(
                granted.consentId(), granted.revision(),
                "revoke-before-open");

        CompletedReady ready = openReadyGate("revoked-consent-new-gate");

        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(userGates.transitions(ready.revision().gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN);
    }

    @Test
    void automaticAuthorizationRollsBackWithStoppedFinalizationAndRetries()
    {
        userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "rollback-consent");
        ReviewerResultReady ready = prepareReviewerResult(
                "automatic-authorization-rollback");
        String finalContent = "opaque automatic rollback";
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.readyForReview();
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            finalContent,
                                            null);
                        });
        jdbc.execute("""
                CREATE TRIGGER fail_automatic_effect_step
                BEFORE INSERT ON flow_github_external_effect_step
                BEGIN
                    SELECT RAISE(ABORT, 'forced automatic effect failure');
                END
                """);
        assertThatThrownBy(() -> ready.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        handle,
                        TTL)).isInstanceOf(RuntimeException.class);
        assertThat(runtime.resultForRun(
                ready.binding().run().runId())).isEmpty();
        assertThat(userGates.gate(pr.prId())).isEmpty();
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isZero();
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isZero();

        jdbc.execute("DROP TRIGGER fail_automatic_effect_step");
        var prepared = userGates.prepareFinalization(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence());
        AgentResult result = userGates.finalizeReady(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence(),
                TerminalOutcome.COMPLETED,
                finalContent,
                null,
                prepared);

        assertThat(result.terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        assertThat(count("flow_user_gate_authorization", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void consentSchemaAndGraphRejectMixedScopeOrMissingAuthority()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "schema-consent");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE flow_user_gate_ci_consent_revision "
                        + "SET head_repository_name = 'wrong' "
                        + "WHERE consent_id = ? AND revision = ?",
                consent.consentId(), consent.revision()))
                .isInstanceOf(RuntimeException.class);
        openReadyGate("corrupt-consent-graph");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE flow_user_gate_authorization "
                        + "SET consent_id = NULL, consent_revision = NULL, "
                        + "consent_digest = NULL "
                        + "WHERE authority = 'CI_UPDATE_CONSENT'"))
                .isInstanceOf(RuntimeException.class);
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        jdbc.execute((ConnectionCallback<Void>)
                connection -> {
                    connection.createStatement().execute(
                            "PRAGMA foreign_keys=OFF");
                    try (var statement = connection.prepareStatement(
                            "UPDATE flow_user_gate_ci_consent_revision "
                                    + "SET head_repository_name = 'corrupt' "
                                    + "WHERE consent_id = ? "
                                    + "AND revision = ?")) {
                        statement.setString(1, consent.consentId());
                        statement.setLong(2, consent.revision());
                        statement.executeUpdate();
                    }
                    return null;
                });
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(claim))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CI_UPDATE consent digest is inconsistent");
    }

    @Test
    void concurrentAuthorizationAndBeginDeliveriesCreateOneExactGraph()
            throws Exception
    {
        CompletedReady ready = openReadyGate("concurrent-authorization");
        GateRevision revision = ready.revision();
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AuthorizedCiUpdate> first = new AtomicReference<>();
        AtomicReference<AuthorizedCiUpdate> second = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread firstThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                first.set(userGates.authorizeCiUpdate(
                        revision.gateId(), revision.revision(),
                        revision.subjectDigest(), revision.actionDigest(),
                        "concurrent-key"));
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "first-authorization");
        Thread secondThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                second.set(userGates.authorizeCiUpdate(
                        revision.gateId(), revision.revision(),
                        revision.subjectDigest(), revision.actionDigest(),
                        "concurrent-key"));
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "second-authorization");
        firstThread.start();
        secondThread.start();
        assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        firstThread.join(30_000);
        secondThread.join(30_000);
        assertThat(firstThread.isAlive()).isFalse();
        assertThat(secondThread.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(first.get()).isEqualTo(second.get());
        assertThat(count("flow_user_gate_authorization", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isEqualTo(1);

        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        CountDownLatch beginStart = new CountDownLatch(1);
        AtomicReference<CiUpdateEffectActivation> firstBegin =
                new AtomicReference<>();
        AtomicReference<CiUpdateEffectActivation> secondBegin =
                new AtomicReference<>();
        Thread firstBeginThread = new Thread(() -> {
            awaitLatch(beginStart);
            try {
                firstBegin.set(userGates.beginCiUpdateEffect(claim));
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "first-begin");
        Thread secondBeginThread = new Thread(() -> {
            awaitLatch(beginStart);
            try {
                secondBegin.set(userGates.beginCiUpdateEffect(claim));
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "second-begin");
        firstBeginThread.start();
        secondBeginThread.start();
        beginStart.countDown();
        firstBeginThread.join(30_000);
        secondBeginThread.join(30_000);
        assertThat(failure.get()).isNull();
        assertThat(firstBegin.get()).isNotNull();
        assertThat(firstBegin.get()).isEqualTo(secondBegin.get());
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.AUTHORIZED,
                        GateState.EXECUTING);
    }

    @Test
    void stableBeginDriftCancelsPublicationAndRearmsParkedReconciliation()
    {
        CompletedReady ready = openReadyGate("publish-stale-rearm");
        var registered = runtime.registerFinalRed(
                "later-round",
                task.taskId(),
                pr.prId(),
                publishedHead,
                "later-payload");
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(),
                revision.revision(),
                revision.subjectDigest(),
                revision.actionDigest(),
                "authorize-stale-1");
        Claim publish = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        assertThat(runtime.selectNext(reconciliation)).isEmpty();
        assertThat(runtime.operation(reconciliation.operationId())
                .orElseThrow().state()).isEqualTo(OperationState.WAITING);
        runtime.advanceRemoteHead(
                pr.prId(), publishedHead, publishedHead + "-advanced");
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(publish))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("REMOTE_SUBJECT_STALE");
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(publish))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("REMOTE_SUBJECT_STALE");
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CANCELED);
        assertThat(runtime.operation(reconciliation.operationId())
                .orElseThrow().state()).isEqualTo(OperationState.READY);
        Claim resumed = claim(OperationKind.RECONCILE_TASK);
        assertThat(resumed.operationId())
                .isEqualTo(reconciliation.operationId());
        assertThat(registered.reconciliationOperationId())
                .isEqualTo(reconciliation.operationId());
    }

    @Test
    void authorizationRejectsConflictsAndLateFailureRollsBackEveryOwner()
    {
        CompletedReady ready = openReadyGate("authorization-rollback");
        GateRevision revision = ready.revision();
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                "wrong-subject-digest", revision.actionDigest(),
                "wrong-digest-key"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("DISPLAYED_GATE_DIGEST_CHANGED");

        jdbc.execute("""
                CREATE TRIGGER fail_effect_step
                BEFORE INSERT ON flow_github_external_effect_step
                BEGIN
                    SELECT RAISE(ABORT, 'forced effect step failure');
                END
                """);
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "rollback-key"))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isZero();
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isZero();
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN);

        jdbc.execute("DROP TRIGGER fail_effect_step");
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "rollback-key");
        assertThat(authorized.prSequence()).isEqualTo(1);
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "different-key"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(count("flow_user_gate_authorization", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void authorizationStaleIsTerminalEvenIfInspectionLaterFails()
            throws Exception
    {
        CompletedReady ready = openReadyGate("authorization-stale-terminal");
        GateRevision revision = ready.revision();
        String moved = runtime.currentChangeSet(task.taskId())
                .orElseThrow().headSha();
        runtime.advanceRemoteHead(pr.prId(), publishedHead, moved);
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "stale-terminal-key"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("REMOTE_SUBJECT_STALE");
        runtime.advanceRemoteHead(pr.prId(), moved, publishedHead);
        Path git = repositoryRoot.resolve(".git");
        Path hiddenGit = repositoryRoot.resolve(".git-hidden");
        Files.move(git, hiddenGit);
        try {
            assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                    revision.gateId(), revision.revision(),
                    revision.subjectDigest(), revision.actionDigest(),
                    "stale-terminal-key"))
                    .isInstanceOf(
                            UserGates.AuthorizationRejectedException.class)
                    .hasMessage("REMOTE_SUBJECT_STALE");
        }
        finally {
            Files.move(hiddenGit, git);
        }
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN, GateState.STALE);
        assertThat(jdbc.queryForMap(
                """
                SELECT actor_type, actor_id
                FROM flow_user_gate_transition
                WHERE gate_id = ? AND reason_code = 'AUTHORIZATION_STALE'
                """,
                revision.gateId()))
                .containsEntry("actor_type", "PROGRAM")
                .containsEntry("actor_id", "USER_GATES_AUTHORIZATION");
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isZero();
        runtime.advanceRemoteHead(pr.prId(), publishedHead, moved);
        publishedHead = moved;
        CompletedReady replacement = openReadyGate(
                "authorization-after-stale", "failure-2");
        assertThat(replacement.revision().revision()).isEqualTo(2);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.STALE,
                        GateState.OPEN);
    }

    @Test
    void neverStartedPublishRecoveryIsOwnerSpecificAndIdempotent()
    {
        CompletedReady ready = openReadyGate("publish-recovery");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "recovery-key");
        Claim first = runtime.claimNextPublish(
                "publisher", Duration.ofSeconds(1)).orElseThrow();
        advancePublicationClock(Duration.ofSeconds(2));
        assertThatThrownBy(() -> runtime.recoverExpiredClaim(
                first.operationId(), first.generation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner-specific");
        userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation());
        userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);

        Claim second = runtime.claimNextPublish(
                "publisher-2", Duration.ofSeconds(1)).orElseThrow();
        userGates.beginCiUpdateEffect(second);
        advancePublicationClock(Duration.ofSeconds(2));
        userGates.recoverExpiredCiUpdateEffect(
                second.operationId(), second.generation());
        userGates.recoverExpiredCiUpdateEffect(
                second.operationId(), second.generation());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.AUTHORIZED,
                        GateState.EXECUTING,
                        GateState.AUTHORIZED);
    }

    @Test
    void transientBeginFailureLeavesTheExactClaimRetryable()
    {
        CompletedReady ready = openReadyGate("publish-begin-rollback");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "begin-rollback-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        jdbc.execute("""
                CREATE TRIGGER fail_effect_begin
                BEFORE INSERT ON flow_user_gate_transition
                WHEN NEW.reason_code = 'EFFECT_BEGIN'
                BEGIN
                    SELECT RAISE(ABORT, 'forced effect begin failure');
                END
                """);
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(claim))
                .isInstanceOf(RuntimeException.class);
        assertThat(runtime.assertPublishClaim(claim).operationId())
                .isEqualTo(authorized.operationId());
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN, GateState.AUTHORIZED);

        jdbc.execute("DROP TRIGGER fail_effect_begin");
        assertThat(userGates.beginCiUpdateEffect(claim).operationId())
                .isEqualTo(authorized.operationId());
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.AUTHORIZED,
                        GateState.EXECUTING);
    }

    @Test
    void publicationBarrierBlocksReconciliationClaim()
    {
        CompletedReady ready = openReadyGate("publish-barrier");
        GateRevision revision = ready.revision();
        userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "barrier-key");
        runtime.registerFinalRed(
                "barrier-round", task.taskId(), pr.prId(), publishedHead,
                "barrier-payload");
        assertThat(runtime.claimNext("writer", TTL)).isEmpty();
        Task current = runtime.task(task.taskId()).orElseThrow();
        assertThatThrownBy(() -> runtime.transitionTask(
                task.taskId(),
                current.currentLifecycleRevisionId(),
                TaskStatus.WAITING_USER,
                "USER_PAUSED",
                "pause"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("publication is live");
        assertThatThrownBy(() -> runtime.transitionTask(
                task.taskId(),
                current.currentLifecycleRevisionId(),
                TaskStatus.CANCELED,
                "USER_CANCELED",
                "cancel"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("publication is live");
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH' "
                + "AND state = 'READY'"))
                .isEqualTo(1);
    }

    @Test
    void concurrentPublicationReservationAndWorkSelectionCannotBothWin()
            throws Exception
    {
        CompletedReady ready = openReadyGate("publish-selection-race");
        runtime.registerFinalRed(
                "publish-selection-later",
                task.taskId(),
                pr.prId(),
                publishedHead,
                "publish-selection-payload");
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        GateRevision revision = ready.revision();
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AuthorizedCiUpdate> authorized =
                new AtomicReference<>();
        AtomicReference<Optional<Operation>> selected =
                new AtomicReference<>();
        AtomicReference<Throwable> authorizationFailure =
                new AtomicReference<>();
        AtomicReference<Throwable> selectionFailure =
                new AtomicReference<>();
        Thread authorizationThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                authorized.set(userGates.authorizeCiUpdate(
                        revision.gateId(), revision.revision(),
                        revision.subjectDigest(), revision.actionDigest(),
                        "selection-race-key"));
            }
            catch (Throwable thrown) {
                authorizationFailure.set(thrown);
            }
        }, "publication-reservation");
        Thread selectionThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                selected.set(runtime.selectNext(reconciliation));
            }
            catch (Throwable thrown) {
                selectionFailure.set(thrown);
            }
        }, "work-selection");
        authorizationThread.start();
        selectionThread.start();
        assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        authorizationThread.join(30_000);
        selectionThread.join(30_000);
        assertThat(selectionFailure.get()).isNull();
        if (authorized.get() != null) {
            assertThat(authorizationFailure.get()).isNull();
            assertThat(selected.get()).isEmpty();
            assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                    .isEqualTo(1);
            assertThat(runtime.task(task.taskId()).orElseThrow()
                    .selectedWriterOperationId()).isNull();
        }
        else {
            assertThat(authorizationFailure.get()).isNotNull();
            assertThat(selected.get()).isPresent();
            assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                    .isZero();
            assertThat(runtime.task(task.taskId()).orElseThrow()
                    .selectedWriterOperationId()).isNotNull();
        }
    }

    @Test
    void concurrentPublicationReservationAndTaskTerminationCannotBothWin()
            throws Exception
    {
        CompletedReady ready = openReadyGate("publish-lifecycle-race");
        GateRevision revision = ready.revision();
        Task current = runtime.task(task.taskId()).orElseThrow();
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AuthorizedCiUpdate> authorized =
                new AtomicReference<>();
        AtomicReference<Throwable> authorizationFailure =
                new AtomicReference<>();
        AtomicReference<Throwable> transitionFailure =
                new AtomicReference<>();
        Thread authorizationThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                authorized.set(userGates.authorizeCiUpdate(
                        revision.gateId(), revision.revision(),
                        revision.subjectDigest(), revision.actionDigest(),
                        "lifecycle-race-key"));
            }
            catch (Throwable thrown) {
                authorizationFailure.set(thrown);
            }
        }, "publication-lifecycle-reservation");
        Thread transitionThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                runtime.transitionTask(
                        task.taskId(),
                        current.currentLifecycleRevisionId(),
                        TaskStatus.CANCELED,
                        "USER_CANCELED",
                        "race-cancel");
            }
            catch (Throwable thrown) {
                transitionFailure.set(thrown);
            }
        }, "task-termination");
        authorizationThread.start();
        transitionThread.start();
        assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        authorizationThread.join(30_000);
        transitionThread.join(30_000);
        if (authorized.get() != null) {
            assertThat(authorizationFailure.get()).isNull();
            assertThat(transitionFailure.get()).isNotNull();
            assertThat(runtime.task(task.taskId()).orElseThrow().status())
                    .isEqualTo(TaskStatus.ACTIVE);
            assertThat(runtime.operation(authorized.get().operationId())
                    .orElseThrow().state()).isEqualTo(OperationState.READY);
        }
        else {
            assertThat(authorizationFailure.get()).isNotNull();
            assertThat(transitionFailure.get()).isNull();
            assertThat(runtime.task(task.taskId()).orElseThrow().status())
                    .isEqualTo(TaskStatus.CANCELED);
            assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                    .isZero();
        }
    }

    @Test
    void effectPlanReaderRejectsDigestCorruptionAndSchemaRejectsPayloadDrift()
    {
        CompletedReady ready = openReadyGate("plan-corruption");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "plan-corruption-key");
        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE flow_github_external_effect_step
                SET proposed_head = 'different-head' WHERE plan_id = ?
                """,
                authorized.planId())).isInstanceOf(RuntimeException.class);
        jdbc.update(
                """
                UPDATE flow_github_external_effect_step
                SET precondition_digest = 'corrupt' WHERE plan_id = ?
                """,
                authorized.planId());
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "plan-corruption-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest graph");
    }

    @Test
    void appliedProviderProbeConsumesTheExactPlanAndReplays()
    {
        CompletedReady ready = openReadyGate("publish-applied");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "publish-applied-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        githubEffects.recordObservation(
                claim,
                observation(
                        runtime, claim, activation, null,
                        ProbeOutcome.ABSENT),
                NOW);
        var activated = githubEffects.activateAttempt(
                claim, authorized.planId(), NOW);
        assertThat(count("flow_github_external_effect_attempt", "1 = 1"))
                .isEqualTo(1);
        runtime.consumePublishExecutionHandle(
                activated.executionHandle(), claim,
                activated.attempt().attemptId(),
                activated.attempt().executionTokenDigest());

        var applied = observation(
                runtime, claim, activation, activated.attempt(),
                ProbeOutcome.APPLIED);
        var receipt = userGates.applyCiUpdateObservation(
                claim, activated.executionHandle(), activated.attempt(),
                applied).orElseThrow();
        assertThat(userGates.applyCiUpdateObservation(
                claim, activated.executionHandle(), activated.attempt(),
                applied))
                .contains(receipt);
        assertThatThrownBy(() -> userGates.applyCiUpdateObservation(
                claim, activated.executionHandle(), activated.attempt(),
                observation(
                        runtime, claim, activation, activated.attempt(),
                        ProbeOutcome.UNKNOWN)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runtime.pullRequest(pr.prId()).orElseThrow()
                .currentRemoteHead()).isEqualTo(activation.proposedHead());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN, GateState.AUTHORIZED,
                        GateState.EXECUTING, GateState.CONSUMED);
        assertThat(githubEffects.exactReceipt(authorized.planId()))
                .contains(receipt);
    }

    @Test
    void receiptOwnedObservationWatchIsAtomicWithAppliedSettlement()
    {
        CompletedReady ready = openReadyGate("receipt-watch-rollback");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "receipt-watch-rollback-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        var applied = observation(
                runtime, claim, activation, null, ProbeOutcome.APPLIED);
        int probesBefore = count(
                "flow_github_external_effect_probe", "1 = 1");
        jdbc.execute("""
                CREATE TRIGGER reject_observation_watch
                BEFORE INSERT ON flow_runtime_operation
                WHEN NEW.kind = 'OBSERVE_CI'
                BEGIN
                    SELECT RAISE(ABORT, 'watch insert rejected');
                END
                """);

        assertThatThrownBy(() -> userGates.applyCiUpdateObservation(
                claim, null, null, applied))
                .isInstanceOf(RuntimeException.class);
        assertThat(githubEffects.exactReceipt(authorized.planId())).isEmpty();
        assertThat(count("flow_github_external_effect_probe", "1 = 1"))
                .isEqualTo(probesBefore);
        assertThat(runtime.pullRequest(pr.prId()).orElseThrow()
                .currentRemoteHead()).isEqualTo(activation.expectedRemoteHead());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);
        assertThat(userGates.transitions(revision.gateId()).getLast()
                .toState()).isEqualTo(GateState.EXECUTING);
        assertThat(count("flow_runtime_operation", "kind = 'OBSERVE_CI'"))
                .isZero();

        jdbc.execute("DROP TRIGGER reject_observation_watch");
        var receipt = userGates.applyCiUpdateObservation(
                claim, null, null, applied).orElseThrow();
        assertThat(githubEffects.exactReceipt(authorized.planId()))
                .contains(receipt);
        assertThat(runtime.pullRequest(pr.prId()).orElseThrow()
                .currentRemoteHead()).isEqualTo(activation.proposedHead());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(count("flow_runtime_operation", "kind = 'OBSERVE_CI'"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_dispatch_ticket",
                "operation_id IN (SELECT operation_id FROM "
                        + "flow_runtime_operation WHERE kind = 'OBSERVE_CI')"))
                .isEqualTo(1);
    }
}
