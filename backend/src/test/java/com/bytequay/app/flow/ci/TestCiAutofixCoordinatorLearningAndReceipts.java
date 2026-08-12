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

import com.bytequay.app.flow.ci.CiAutofixRecords.CiLesson;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.LearningCompletionState;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.gate.UserGateRecords.AuthorizedCiUpdate;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGateRecords.GateState;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentProcessAttempt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.InProcessCiLearningAgentSupervisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.FAILED_ACTIONS;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.FAILED_UNSUPPORTED;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.GREEN;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.PENDING;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.UNSTABLE;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeApplied;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeCiObservation;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeUnavailableProbe;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.observation;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.prepareCiObservation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Green receipt watching, the isolated learner run behind an accepted
 * lesson, and provider batch delivery.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestCiAutofixCoordinatorLearningAndReceipts
        extends BaseTestCiAutofixCoordinator
{
    @Test
    void realExecutorCommitsAttemptBeforeItsOnlyProviderCallAndReplaysSystemClock()
    {
        CompletedReady ready = openReadyGate("executor-attempt-boundary");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "executor-attempt-boundary-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var step = githubEffects.steps(authorized.planId()).getFirst();
        AtomicInteger pushes = new AtomicInteger();

        var receipt = executeApplied(
                runtime,
                userGates,
                githubEffects,
                claim,
                step,
                Clock.systemUTC(),
                () -> assertThat(count(
                        "flow_github_external_effect_attempt",
                        "operation_id = '" + claim.operationId() + "'"))
                        .isEqualTo(1),
                pushes).orElseThrow();

        assertThat(pushes.get()).isEqualTo(1);
        assertThat(count(
                "flow_github_external_effect_receipt", "1 = 1"))
                .isEqualTo(1);
        assertThat(receipt.recordedAt().getNano() % 1_000_000).isZero();
        assertThat(githubEffects.exactReceipt(authorized.planId()))
                .contains(receipt);

        restart();
        assertThat(executeApplied(
                runtime,
                userGates,
                githubEffects,
                claim,
                githubEffects.steps(authorized.planId()).getFirst(),
                Clock.systemUTC(),
                () -> {
                    throw new AssertionError(
                            "terminal replay must not call the provider");
                },
                pushes)).contains(receipt);
        assertThat(pushes.get()).isEqualTo(1);
        for (Claim conflicting : List.of(
                new Claim(
                        claim.operationId(), claim.taskId(),
                        OperationKind.RECONCILE_TASK, claim.generation(),
                        claim.claimToken(), claim.workerId(),
                        claim.expiresAt()),
                new Claim(
                        claim.operationId(), claim.taskId(), claim.kind(),
                        claim.generation(), "wrong-token", claim.workerId(),
                        claim.expiresAt()),
                new Claim(
                        claim.operationId(), claim.taskId(), claim.kind(),
                        claim.generation() + 1, claim.claimToken(),
                        claim.workerId(), claim.expiresAt()),
                new Claim(
                        claim.operationId(), "wrong-task", claim.kind(),
                        claim.generation(), claim.claimToken(),
                        claim.workerId(), claim.expiresAt()),
                new Claim(
                        claim.operationId(), claim.taskId(), claim.kind(),
                        claim.generation(), claim.claimToken(),
                        "wrong-worker", claim.expiresAt()))) {
            assertThatThrownBy(() -> executeApplied(
                    runtime,
                    userGates,
                    githubEffects,
                    conflicting,
                    githubEffects.steps(authorized.planId()).getFirst(),
                    Clock.fixed(runtimeNow, ZoneOffset.UTC),
                    () -> {
                        throw new AssertionError(
                                "conflicting replay called the provider");
                    },
                    pushes)).isInstanceOf(RuntimeException.class);
        }
        assertThat(pushes.get()).isEqualTo(1);
    }

    @Test
    void receiptWatchAcceptsExactGreenProviderBatch()
    {
        Claim greenClaim = observationClaim("observe-green");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, greenClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        assertThat(green.sourceObservationOperationId())
                .isEqualTo(greenClaim.operationId());
        assertThat(green.sourceReceiptId()).isNotBlank();
        assertThat(runtime.pendingWork(task.taskId()))
                .noneMatch(work -> work.kind() == PendingKind.FINAL_RED
                        && work.externalKey().equals(green.roundId()));
        assertThat(runtime.operation(greenClaim.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);
        assertThat(jdbc.queryForObject(
                "SELECT not_before FROM flow_runtime_dispatch_ticket "
                        + "WHERE operation_id = ?",
                Long.class, greenClaim.operationId()))
                .isEqualTo(runtimeNow.plus(Duration.ofMinutes(5))
                        .toEpochMilli());
    }

    @Test
    void exactGreenRunsOneIsolatedLearnerAndAcceptedSaveWinsFailedReturn()
    {
        Claim greenClaim = observationClaim("learn-green");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, greenClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var subject = learningCoordinator.learningSubject(start.run().inputRef())
                .orElseThrow();
        String failedLogRef = subject.failedLogRefs().getFirst();
        assertThat(start.session().role()).isEqualTo(AgentRole.CI_LEARNER);
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER)
                .orElseThrow().sessionId())
                .isNotEqualTo(start.session().sessionId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        var supervisor = new InProcessCiLearningAgentSupervisor(runtime);
        var handle = supervisor.launch(
                start, learning, learningCoordinator, capability -> {
                    assertThat(capability.readCiRepairEvidence())
                            .contains("publicationPolicy=")
                            .contains("greenPolicy=")
                            .contains("outputHead=" + subject.publishedHead())
                            .contains("outputDiffDigest="
                                    + subject.outputDiffDigest())
                            .contains("repairOutcome=")
                            .contains("repairResult=")
                            .contains("failedLogCount=1")
                            .doesNotContain(
                                    subject.subjectId(),
                                    subject.receiptId(),
                                    subject.redRoundId(),
                                    green.roundId(),
                                    subject.repairAttemptId(),
                                    subject.outputChangeSetRevisionId(),
                                    failedLogRef);
                    assertThat(capability.readCiLog(
                            failedLogRef, 0, 1_024))
                            .contains("failure");
                    assertThatThrownBy(() -> capability.readCiLog(
                            "unrelated-log", 0, 1_024))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> capability.readCiLog(
                            failedLogRef, 0, 32_769))
                            .isInstanceOf(IllegalArgumentException.class);
                    capability.saveCiLesson(
                            "Fix the exact CI failure",
                            "The bound repair resolved the failed check.");
                    assertThatThrownBy(capability::readCiRepairEvidence)
                            .isInstanceOf(
                                    FlowRuntime.StaleCapabilityException.class);
                    assertThatThrownBy(() -> capability.saveCiLesson(
                            "Fix the exact CI failure",
                            "The bound repair resolved the failed check."))
                            .isInstanceOf(
                                    FlowRuntime.StaleCapabilityException.class);
                    return new InProcessCiLearningAgentSupervisor
                            .AgentCompletion(
                                    TerminalOutcome.FAILED, null,
                                    "OPAQUE_LEARNER_FAILED_AFTER_SAVE");
                });

        AgentResult result = supervisor.awaitAndFinish(handle, TTL);
        var completion = learningCoordinator.learningCompletion(
                learning.operationId()).orElseThrow();

        assertThat(result.terminalOutcome())
                .isEqualTo(TerminalOutcome.FAILED);
        assertThat(completion.state())
                .isEqualTo(LearningCompletionState.CANDIDATE);
        assertThat(learningCoordinator.lesson(completion.lessonId()).orElseThrow())
                .satisfies(lesson -> {
                    assertThat(lesson.title())
                            .isEqualTo("Fix the exact CI failure");
                    assertThat(lesson.markdown())
                            .isEqualTo(
                                    "The bound repair resolved the failed check.");
                });
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    void acceptedLessonWinsCancellationBeforeLearnerBodyReturns()
    {
        Claim greenClaim = observationClaim("learn-late-cancel");
        assertThat(executeCiObservation(
                runtime,
                observationCoordinator,
                greenClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                GREEN)).isPresent();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        CountDownLatch lessonAccepted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        var supervisor = new InProcessCiLearningAgentSupervisor(runtime);
        var handle = supervisor.launch(
                start,
                learning,
                learningCoordinator,
                capability -> {
                    capability.saveCiLesson(
                            "Accepted before cancellation",
                            "The exact saved lesson remains authoritative.");
                    lessonAccepted.countDown();
                    awaitLatch(releaseBody);
                    return new InProcessCiLearningAgentSupervisor
                            .AgentCompletion(
                                    TerminalOutcome.COMPLETED,
                                    "saved lesson",
                                    null);
                });
        awaitLatch(lessonAccepted);
        AtomicReference<AgentResult> cancellation = new AtomicReference<>();
        AtomicReference<Throwable> cancellationFailure =
                new AtomicReference<>();
        CountDownLatch cancelEntered = new CountDownLatch(1);
        Thread cancelThread = Thread.ofVirtual().start(() -> {
            cancelEntered.countDown();
            try {
                cancellation.set(supervisor.cancel(handle, TTL));
            }
            catch (Throwable failure) {
                cancellationFailure.set(failure);
            }
        });
        awaitLatch(cancelEntered);
        awaitThreadBlockedOrEnded(cancelThread);
        releaseBody.countDown();
        joinThread(cancelThread);

        assertThat(cancellationFailure.get()).isNull();
        assertThat(cancellation.get().terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        assertThat(learningCoordinator.learningCompletion(learning.operationId())
                .orElseThrow().state())
                .isEqualTo(LearningCompletionState.CANDIDATE);
    }

    @Test
    void stoppedCompletionSealRejectsChangedWriterResult()
    {
        ReviewReady writerReady = prepareCleanReview("completion-seal-writer");
        String writerContent = "sealed writer completion";
        AgentProcessAttempt writerAttempt = ReflectionTestUtils.invokeMethod(
                runtime,
                "reserveInProcessWriterAttempt",
                writerReady.binding().run().runId(),
                writerReady.claim(),
                writerReady.binding().fence());
        ReflectionTestUtils.invokeMethod(
                runtime,
                "activateInProcessWriterAttempt",
                writerAttempt.processAttemptId(),
                writerReady.claim(),
                writerReady.binding().fence(),
                1L, NOW, 1L, "restarted-writer");
        markAttemptStopped(
                writerAttempt.processAttemptId(), TerminalOutcome.COMPLETED,
                writerContent, null);

        assertThatThrownBy(() -> runtime.finishTaskAgentReviewTurn(
                writerReady.binding().run().runId(), writerReady.claim(),
                writerReady.binding().fence(), TerminalOutcome.COMPLETED,
                "changed writer completion", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped completion");
        assertThat(runtime.resultForRun(
                writerReady.binding().run().runId())).isEmpty();
        AgentResult writerResult = runtime.finishTaskAgentReviewTurn(
                writerReady.binding().run().runId(), writerReady.claim(),
                writerReady.binding().fence(), TerminalOutcome.COMPLETED,
                writerContent, null);
        assertThat(runtime.finishTaskAgentReviewTurn(
                writerReady.binding().run().runId(), writerReady.claim(),
                writerReady.binding().fence(), TerminalOutcome.COMPLETED,
                writerContent, null))
                .isEqualTo(writerResult);
    }

    @Test
    void stoppedCompletionSealRejectsChangedReviewerResult()
    {
        ReviewerClaim reviewer = prepareReviewerClaim(
                "completion-seal-reviewer");
        String reviewerContent = "sealed reviewer completion";
        AgentProcessAttempt reviewerAttempt =
                ReflectionTestUtils.invokeMethod(
                        runtime,
                        "reserveInProcessReviewerAttempt",
                        reviewer.start().run().runId(),
                        reviewer.claim(),
                        reviewer.request().requestId());
        ReflectionTestUtils.invokeMethod(
                runtime,
                "activateInProcessReviewerAttempt",
                reviewerAttempt.processAttemptId(),
                reviewer.claim(),
                1L, NOW, 1L, "restarted-reviewer");
        markAttemptStopped(
                reviewerAttempt.processAttemptId(), TerminalOutcome.COMPLETED,
                reviewerContent, null);

        assertThatThrownBy(() -> runtime.finishReviewerAgentRun(
                reviewer.start().run().runId(), reviewer.claim(),
                TerminalOutcome.COMPLETED,
                "changed reviewer completion", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped completion");
        assertThat(runtime.resultForRun(
                reviewer.start().run().runId())).isEmpty();
        AgentResult reviewerResult = runtime.finishReviewerAgentRun(
                reviewer.start().run().runId(), reviewer.claim(),
                TerminalOutcome.COMPLETED, reviewerContent, null);
        assertThat(runtime.finishReviewerAgentRun(
                reviewer.start().run().runId(), reviewer.claim(),
                TerminalOutcome.COMPLETED, reviewerContent, null))
                .isEqualTo(reviewerResult);
    }

    @Test
    void stoppedCompletionSealRejectsChangedLearnerResult()
    {
        Claim greenClaim = observationClaim("completion-seal-learner");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, greenClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        String learnerError = "SEALED_LEARNER_FAILURE";
        AgentProcessAttempt learnerAttempt =
                runtime.reserveInProcessCiLearningAttempt(
                        start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                learnerAttempt.processAttemptId(), learning,
                1L, NOW, 1L, "restarted-learner");
        markAttemptStopped(
                learnerAttempt.processAttemptId(), TerminalOutcome.FAILED,
                null, learnerError);

        assertThatThrownBy(() -> runtime.finishCiLearningAgentRun(
                start.run().runId(), learning, TerminalOutcome.FAILED,
                null, "CHANGED_LEARNER_FAILURE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped completion");
        assertThat(runtime.resultForRun(start.run().runId())).isEmpty();
        AgentResult learnerResult = runtime.finishCiLearningAgentRun(
                start.run().runId(), learning, TerminalOutcome.FAILED,
                null, learnerError);
        assertThat(runtime.finishCiLearningAgentRun(
                start.run().runId(), learning, TerminalOutcome.FAILED,
                null, learnerError)).isEqualTo(learnerResult);
    }

    @Test
    void finalizedCandidateIsOfferedOnlyToTheNextExactRepositoryRepair()
    {
        Claim greenClaim = observationClaim("lesson-next-repair-green");
        List<CiLesson> lessons = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            CiRound green = executeCiObservation(
                    runtime, observationCoordinator, greenClaim,
                    Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                    .orElseThrow();
            assertThat(green.state()).isEqualTo(RoundState.GREEN);
            Claim learning = runtime.claimNextCiLearning("learner", TTL)
                    .orElseThrow();
            FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                    learning).orElseThrow();
            int lessonIndex = index;
            var supervisor = new InProcessCiLearningAgentSupervisor(runtime);
            var handle = supervisor.launch(
                    start, learning, learningCoordinator, capability -> {
                        capability.saveCiLesson(
                                "Candidate lesson " + lessonIndex,
                                "Bounded repair hint " + lessonIndex);
                        return new InProcessCiLearningAgentSupervisor
                                .AgentCompletion(
                                        TerminalOutcome.COMPLETED,
                                        "saved " + lessonIndex,
                                        null);
                    });
            supervisor.awaitAndFinish(handle, TTL);
            String lessonId = learningCoordinator.learningCompletion(
                    learning.operationId()).orElseThrow().lessonId();
            lessons.add(learningCoordinator.lesson(lessonId).orElseThrow());
            if (index < 6) {
                advancePublicationClock(Duration.ofMinutes(6));
                Claim redClaim = runtime.claimNextCiObservation(
                        "observer", TTL).orElseThrow();
                CiRound red = executeCiObservation(
                        runtime, observationCoordinator, redClaim,
                        Clock.fixed(runtimeNow, ZoneOffset.UTC),
                        FAILED_ACTIONS).orElseThrow();
                CompletedReady next = openReadyGate(
                        "lesson-next-repair-" + index, red);
                greenClaim = publishReadyAndClaimObservation(
                        next, "lesson-next-repair-" + index);
            }
        }

        CiLesson crossRepository = lessons.getLast();
        jdbc.update(
                "UPDATE flow_ci_lesson SET repository_id = 'other/repo' "
                        + "WHERE lesson_id = ?",
                crossRepository.lessonId());
        long tiedAt = lessons.get(5).createdAt().toEpochMilli();
        jdbc.update(
                "UPDATE flow_ci_lesson SET created_at = ? "
                        + "WHERE lesson_id = ?",
                tiedAt, lessons.get(4).lessonId());
        List<CiLesson> expected = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            expected.add(learningCoordinator.lesson(
                    lessons.get(index).lessonId()).orElseThrow());
        }
        expected.sort(Comparator.comparing(CiLesson::createdAt).reversed()
                .thenComparing(CiLesson::lessonId));
        expected = List.copyOf(expected.subList(0, 5));

        advancePublicationClock(Duration.ofMinutes(6));
        Claim redClaim = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound red = executeCiObservation(
                runtime, observationCoordinator, redClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS)
                .orElseThrow();
        StartedRepair repair = startRepair(red);
        CiRepairCoordinator.RepairToolContext context =
                repairCoordinator.repairToolContext(repair.binding());

        assertThat(context.failureSummary())
                .contains("taskGoal=" + task.goalText())
                .contains("failedRemoteHead=" + red.remoteHead())
                .contains("requiredCiPolicyRevision=");
        List<String> expectedSummary = new ArrayList<>();
        for (int index = 0; index < expected.size(); index++) {
            CiLesson lesson = expected.get(index);
            expectedSummary.add(index + ": " + lesson.title()
                    + " [" + lesson.contentDigest() + "]");
        }
        String summary = context.candidateLessonSummary();
        assertThat(summary.lines().toList())
                .containsExactlyElementsOf(expectedSummary);
        for (CiLesson lesson : lessons) {
            assertThat(summary).doesNotContain(lesson.lessonId());
        }
        assertThat(summary)
                .doesNotContain(lessons.getFirst().title())
                .doesNotContain(crossRepository.title());
        for (int index = 0; index < expected.size(); index++) {
            CiLesson lesson = expected.get(index);
            assertThat(context.readCandidateLesson(index))
                    .contains("UNTRUSTED PRIOR HINT")
                    .contains(lesson.title())
                    .contains(lesson.contentDigest())
                    .contains(lesson.markdown())
                    .doesNotContain(lesson.lessonId());
        }
        assertThatThrownBy(() -> context.readCandidateLesson(5))
                .isInstanceOf(IllegalArgumentException.class);

        jdbc.update(
                "UPDATE flow_ci_lesson SET repository_id = 'other/repo' "
                        + "WHERE lesson_id = ?",
                expected.getFirst().lessonId());
        assertThatThrownBy(context::candidateLessonSummary)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("context changed");
    }

    @Test
    void exactGreenWithoutSaveIsMissedAndNeverInventsALesson()
    {
        Claim greenClaim = observationClaim("learn-missed");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, greenClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var supervisor = new InProcessCiLearningAgentSupervisor(runtime);
        var handle = supervisor.launch(
                start, learning, learningCoordinator,
                capability -> new InProcessCiLearningAgentSupervisor
                        .AgentCompletion(
                                TerminalOutcome.COMPLETED,
                                "opaque prose is not a lesson", null));

        supervisor.awaitAndFinish(handle, TTL);
        var completion = learningCoordinator.learningCompletion(
                learning.operationId()).orElseThrow();

        assertThat(completion.state())
                .isEqualTo(LearningCompletionState.MISSED);
        assertThat(completion.lessonId()).isNull();
        assertThat(count("flow_ci_lesson", "1 = 1")).isZero();
    }

    @Test
    void emptyPolicyGreenCommitsWithoutLearningUntilNonemptySuccessor()
    {
        Claim first = observationClaim("learn-empty-policy");
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:empty",
                "github-check-policy-digest:empty",
                PolicyResolution.RESOLVED, null, List.of(),
                List.of("SUCCESS"));

        CiRound emptyGreen = executeCiObservation(
                runtime, observationCoordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(emptyGreen.state()).isEqualTo(RoundState.GREEN);
        assertThat(emptyGreen.checkObservationIds()).isEmpty();
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isZero();

        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:nonempty",
                "github-check-policy-digest:nonempty",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        advancePublicationClock(Duration.ofMinutes(6));
        rebuildCiCoordinators(
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim retry = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound nonemptyGreen = executeCiObservation(
                runtime, observationCoordinator, retry,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(nonemptyGreen.state()).isEqualTo(RoundState.GREEN);
        assertThat(nonemptyGreen.checkObservationIds()).isNotEmpty();
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isEqualTo(1);
    }

    @Test
    void laterGreenPolicyAdvanceKeepsTheFirstReceiptLearningOpportunity()
    {
        Claim first = observationClaim("learn-successor");
        CiRound original = executeCiObservation(
                runtime, observationCoordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        String operationId = jdbc.queryForObject(
                "SELECT operation_id FROM flow_runtime_operation "
                        + "WHERE kind = 'RUN_CI_LEARNING'",
                String.class);

        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:successor",
                "github-check-policy-digest:successor",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        advancePublicationClock(Duration.ofMinutes(6));
        rebuildCiCoordinators(
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim retry = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound successor = executeCiObservation(
                runtime, observationCoordinator, retry,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(successor.roundId()).isNotEqualTo(original.roundId());
        assertThat(autofix.roundById(original.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.GREEN);
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT operation_id FROM flow_runtime_operation "
                        + "WHERE kind = 'RUN_CI_LEARNING'",
                String.class)).isEqualTo(operationId);
    }

    @Test
    void queuedLearningStartStalesWithoutLaunchAndCancellationReplays()
    {
        Claim first = observationClaim("learn-stale-start");
        CiRound original = executeCiObservation(
                runtime, observationCoordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(original.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning(
                "learner", Duration.ofMinutes(30)).orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);

        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:stale-learning",
                "github-check-policy-digest:stale-learning",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        advancePublicationClock(Duration.ofMinutes(6));
        rebuildCiCoordinators(
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim observation = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound successor = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(successor.roundId()).isNotEqualTo(original.roundId());

        assertThat(learningCoordinator.beginCiLearning(learning)).isEmpty();
        assertThat(learningCoordinator.beginCiLearning(learning)).isEmpty();
        assertThat(runtime.operation(learning.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CANCELED);
        assertThat(runtime.run(start.run().runId()).orElseThrow().state())
                .isEqualTo(RunState.CANCELED);
        assertThat(learningCoordinator.learningCompletion(
                learning.operationId()).orElseThrow())
                .satisfies(completion -> {
                    assertThat(completion.state())
                            .isEqualTo(LearningCompletionState.MISSED);
                    assertThat(completion.reasonCode())
                            .isEqualTo("CI_LEARNING_GREEN_SUPERSEDED");
                });
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    void unbegunLearningStalesWithoutCreatingAnyAgentOwner()
    {
        Claim first = observationClaim("learn-stale-before-begin");
        CiRound original = executeCiObservation(
                runtime, observationCoordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning(
                "learner", Duration.ofMinutes(30)).orElseThrow();
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:stale-before-begin",
                "github-check-policy-digest:stale-before-begin",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        advancePublicationClock(Duration.ofMinutes(6));
        rebuildCiCoordinators(
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim observation = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound successor = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(successor.roundId()).isNotEqualTo(original.roundId());
        assertThat(learningCoordinator.beginCiLearning(learning)).isEmpty();
        assertThat(learningCoordinator.beginCiLearning(learning)).isEmpty();
        assertThat(runtime.operation(learning.operationId()).orElseThrow())
                .satisfies(operation -> {
                    assertThat(operation.state())
                            .isEqualTo(OperationState.CANCELED);
                    assertThat(operation.resultRef())
                            .isEqualTo("CI_LEARNING_GREEN_SUPERSEDED");
                });
        assertThat(count("flow_runtime_agent_session",
                "role = 'CI_LEARNER'")).isZero();
        assertThat(count("flow_runtime_agent_run",
                "role = 'CI_LEARNER'")).isZero();
        assertThat(count("flow_runtime_agent_result",
                "run_id IN (SELECT run_id FROM flow_runtime_agent_run "
                        + "WHERE role = 'CI_LEARNER')")).isZero();
        assertThat(learningCoordinator.learningCompletion(
                learning.operationId())).isEmpty();
    }

    @Test
    void reservedLearnerExpiryRedrivesTheSameIsolatedRun()
    {
        Claim observation = observationClaim("learn-reserved-expiry");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);

        expireLearningRuntime();

        assertThat(learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isTrue();
        assertThat(learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isTrue();
        assertThat(runtime.operation(learning.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
        assertThat(runtime.run(start.run().runId()).orElseThrow().state())
                .isEqualTo(RunState.QUEUED);
        assertThat(autofix.roundById(green.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.GREEN);
        Claim redelivery = runtime.claimNextCiLearning("learner-2", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart same = learningCoordinator.beginCiLearning(
                redelivery).orElseThrow();
        assertThat(redelivery.generation())
                .isEqualTo(learning.generation() + 1);
        assertThat(same.run().runId()).isEqualTo(start.run().runId());
        assertThat(same.session().sessionId())
                .isEqualTo(start.session().sessionId());
        assertThat(count("flow_runtime_agent_run",
                "operation_id = '" + learning.operationId() + "'"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_agent_session",
                "session_id = '" + start.session().sessionId() + "'"))
                .isEqualTo(1);
    }

    @Test
    void activatedLearnerExpiryQuarantinesOnlyTheLearner()
    {
        Claim observation = observationClaim("learn-activated-expiry");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "detached-ci-learner");
        transition(TaskStatus.COMPLETED);

        expireLearningRuntime();

        assertThat(learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(runtime.operation(learning.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.FAILED);
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
        assertThat(learningCoordinator.learningCompletion(
                learning.operationId())).isEmpty();
        assertThat(autofix.roundById(green.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.GREEN);
    }

    @Test
    void activeLearnerDoesNotBlockObservationOrRepairCapacity()
    {
        Claim observation = observationClaim("learn-live-capacity");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Duration learnerTtl = Duration.ofMinutes(10);
        Claim learning = runtime.claimNextCiLearning(
                "learner", learnerTtl, 1).orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "live-ci-learner");

        advancePublicationClock(Duration.ofMinutes(6));
        Claim redObservation = runtime.claimNextCiObservation(
                "observer", TTL, 1).orElseThrow();
        CiRound red = executeCiObservation(
                runtime, observationCoordinator, redObservation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS)
                .orElseThrow();

        assertThat(red.state()).isEqualTo(RoundState.QUEUED);
        Claim repair = runtime.claimNextCiAutofix("repair", TTL, 1)
                .orElseThrow();
        assertThat(repair.kind()).isEqualTo(OperationKind.RECONCILE_TASK);
        assertThat(runtime.operation(learning.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);
    }

    @Test
    void quarantinedLearnerDoesNotConsumeRepairCapacity()
    {
        Claim observation = observationClaim("learn-quarantine-capacity");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "detached-ci-learner");

        expireLearningRuntime();
        assertThat(learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();

        Claim redObservation = runtime.claimNextCiObservation(
                "observer", TTL, 1).orElseThrow();
        CiRound red = executeCiObservation(
                runtime, observationCoordinator, redObservation,
                Clock.fixed(
                        runtimeNow.plus(TTL).plusSeconds(1),
                        ZoneOffset.UTC),
                FAILED_ACTIONS).orElseThrow();
        assertThat(red.state()).isEqualTo(RoundState.QUEUED);

        Claim repair = runtime.claimNextCiAutofix("repair", TTL, 1)
                .orElseThrow();
        assertThat(repair.kind()).isEqualTo(OperationKind.RECONCILE_TASK);
        assertThat(runtime.operation(learning.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.FAILED);
    }

    @Test
    void stoppedRecoveryKeepsAcceptedSealAndNeverInventsOpaqueProse()
    {
        Claim observation = observationClaim("learn-stopped-recovery");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "stopped-ci-learner");
        runtime.sealCiLearningLesson(
                attempt.processAttemptId(), learning,
                "Recovered exact lesson",
                "The accepted semantic command survives restart.");
        markLearningAttemptStopped(attempt.processAttemptId());

        expireLearningRuntime();

        assertThat(learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        AgentResult result = runtime.resultForRun(
                start.run().runId()).orElseThrow();
        var completion = learningCoordinator.learningCompletion(
                learning.operationId()).orElseThrow();
        assertThat(result.terminalOutcome())
                .isEqualTo(TerminalOutcome.FAILED);
        assertThat(result.finalContent()).isNull();
        assertThat(result.errorRef())
                .isEqualTo("RECOVERED_STOPPED_TEST");
        assertThat(completion.state())
                .isEqualTo(LearningCompletionState.CANDIDATE);
        assertThat(learningCoordinator.lesson(completion.lessonId()).orElseThrow())
                .satisfies(lesson -> {
                    assertThat(lesson.title())
                            .isEqualTo("Recovered exact lesson");
                    assertThat(lesson.markdown()).isEqualTo(
                            "The accepted semantic command survives restart.");
                });
        jdbc.update(
                "UPDATE flow_runtime_agent_result "
                        + "SET error_ref = 'CORRUPT' "
                        + "WHERE run_id = ?",
                start.run().runId());
        assertThatThrownBy(() -> learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay is inconsistent");
    }

    @Test
    void stoppedRecoveryWithoutCurrentSealedGreenIsMissed()
    {
        Claim observation = observationClaim("learn-stopped-stale");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "stale-stopped-ci-learner");
        runtime.sealCiLearningLesson(
                attempt.processAttemptId(), learning,
                "Stale lesson", "This must not become a candidate.");
        markLearningAttemptStopped(attempt.processAttemptId());
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:stale-before-recovery",
                "github-check-policy-digest:stale-before-recovery",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        expireLearningRuntime();

        assertThat(learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(learningCoordinator.learningCompletion(
                learning.operationId()).orElseThrow())
                .satisfies(completion -> {
                    assertThat(completion.state())
                            .isEqualTo(LearningCompletionState.MISSED);
                    assertThat(completion.reasonCode())
                            .isEqualTo("GREEN_SUPERSEDED");
                });
        assertThat(count("flow_ci_lesson", "1 = 1")).isZero();
    }

    @Test
    void stoppedRecoveryWithoutASealNeverLearnsFromMissingProse()
    {
        Claim observation = observationClaim("learn-stopped-no-seal");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "unsealed-stopped-ci-learner");
        runtime.revokeInProcessCiLearningCapability(
                attempt.processAttemptId(), learning);
        markLearningAttemptStopped(attempt.processAttemptId());

        expireLearningRuntime();

        assertThat(learningCoordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(learningCoordinator.learningCompletion(
                learning.operationId()).orElseThrow())
                .satisfies(completion -> {
                    assertThat(completion.state())
                            .isEqualTo(LearningCompletionState.MISSED);
                    assertThat(completion.reasonCode())
                            .isEqualTo("LESSON_NOT_PROPOSED");
                });
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        assertThat(count("flow_ci_lesson", "1 = 1")).isZero();
    }

    @Test
    void acceptedLessonSealReplaysAfterGreenAuthorityChanges()
    {
        Claim observation = observationClaim("learn-seal-replay");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "seal-replay-ci-learner");
        String digest = learningCoordinator.saveLesson(
                start, learning, attempt.processAttemptId(),
                "Durable response-loss lesson",
                "Identical retry returns the accepted seal.");
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:after-seal",
                "github-check-policy-digest:after-seal",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        assertThat(learningCoordinator.saveLesson(
                start, learning, attempt.processAttemptId(),
                "Durable response-loss lesson",
                "Identical retry returns the accepted seal."))
                .isEqualTo(digest);
        assertThatThrownBy(() -> learningCoordinator.saveLesson(
                start, learning, attempt.processAttemptId(),
                "Conflicting lesson",
                "A second semantic command must not replace the first."))
                .isInstanceOf(IllegalStateException.class);
        assertThat(autofix.roundById(green.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.GREEN);
        assertThat(count("flow_ci_learning_lesson_request", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void finalizedCandidateReplaysAfterLaterPolicyAdvance()
    {
        Claim observation = observationClaim("learn-finalize-replay");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = learningCoordinator.beginCiLearning(
                learning).orElseThrow();
        var supervisor = new InProcessCiLearningAgentSupervisor(runtime);
        var completion = new InProcessCiLearningAgentSupervisor
                .AgentCompletion(
                        TerminalOutcome.CANCELED, null,
                        "OPAQUE_CANCELED_AFTER_SAVE");
        var handle = supervisor.launch(
                start, learning, learningCoordinator, capability -> {
                    capability.saveCiLesson(
                            "Finalized response-loss lesson",
                            "The candidate remains immutable after response loss.");
                    return completion;
                });
        AgentResult first = supervisor.awaitAndFinish(handle, TTL);
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:after-finalize",
                "github-check-policy-digest:after-finalize",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        AgentResult replay = learningCoordinator.finish(
                start, learning, completion);

        assertThat(replay).isEqualTo(first);
        assertThat(learningCoordinator.learningCompletion(
                learning.operationId()).orElseThrow().state())
                .isEqualTo(LearningCompletionState.CANDIDATE);
        assertThat(count("flow_ci_lesson", "1 = 1")).isEqualTo(1);
    }

    @Test
    void learningBeginRejectsCorruptionAcrossItsFrozenOwnerGraph()
    {
        Claim observation = observationClaim("learn-graph-corruption");
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        String subjectId = runtime.operation(
                learning.operationId()).orElseThrow().inputRef();
        String repairResultId = jdbc.queryForObject(
                "SELECT repair_result_id FROM flow_ci_learning_subject "
                        + "WHERE subject_id = ?",
                String.class, subjectId);
        String stopProof = jdbc.queryForObject(
                "SELECT stop_proof_ref FROM flow_runtime_agent_result "
                        + "WHERE result_id = ?",
                String.class, repairResultId);
        jdbc.update(
                "UPDATE flow_runtime_agent_result "
                        + "SET stop_proof_ref = 'corrupt-proof' "
                        + "WHERE result_id = ?",
                repairResultId);
        assertThatThrownBy(() -> learningCoordinator.beginCiLearning(learning))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed content");
        jdbc.update(
                "UPDATE flow_runtime_agent_result SET stop_proof_ref = ? "
                        + "WHERE result_id = ?",
                stopProof, repairResultId);

        String evidenceDigest = jdbc.queryForObject(
                "SELECT evidence_digest "
                        + "FROM flow_ci_learning_green_observation "
                        + "WHERE subject_id = ? AND ordinal = 0",
                String.class, subjectId);
        jdbc.update(
                "UPDATE flow_ci_learning_green_observation "
                        + "SET evidence_digest = 'corrupt-evidence' "
                        + "WHERE subject_id = ? AND ordinal = 0",
                subjectId);
        assertThatThrownBy(() -> learningCoordinator.beginCiLearning(learning))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update(
                "UPDATE flow_ci_learning_green_observation "
                        + "SET evidence_digest = ? "
                        + "WHERE subject_id = ? AND ordinal = 0",
                evidenceDigest, subjectId);

        String receiptDigest = jdbc.queryForObject(
                "SELECT receipt_digest FROM flow_ci_learning_subject "
                        + "WHERE subject_id = ?",
                String.class, subjectId);
        updateWithoutForeignKeys(
                "UPDATE flow_ci_learning_subject "
                        + "SET receipt_digest = ? WHERE subject_id = ?",
                "corrupt-receipt", subjectId);
        assertThatThrownBy(() -> learningCoordinator.beginCiLearning(learning))
                .isInstanceOf(IllegalStateException.class);
        updateWithoutForeignKeys(
                "UPDATE flow_ci_learning_subject "
                        + "SET receipt_digest = ? WHERE subject_id = ?",
                receiptDigest, subjectId);

        assertThat(learningCoordinator.beginCiLearning(learning)).isPresent();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
    }

    @Test
    void receiptWatchQueuesExactRedProviderBatchWithItsLog()
    {
        Claim redClaim = observationClaim("observe-red");
        CiRound red = executeCiObservation(
                runtime, observationCoordinator, redClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS)
                .orElseThrow();

        assertThat(red.state()).isEqualTo(RoundState.QUEUED);
        assertThat(red.failedLogRefs()).hasSize(1);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .filteredOn(work -> work.externalKey().equals(red.roundId()))
                .singleElement()
                .satisfies(work -> assertThat(work.payloadRef())
                        .isEqualTo("ci-round:" + red.roundId()));
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + redClaim.operationId() + "'"))
                .isEqualTo(1);
    }

    @Test
    void unsupportedFailureRearmsAndLaterGreenIsAccepted()
    {
        Claim first = observationClaim("observe-unsupported");

        assertThat(executeCiObservation(
                runtime, observationCoordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_UNSUPPORTED))
                .isEmpty();
        assertThat(runtime.operation(first.operationId()).orElseThrow())
                .satisfies(operation -> {
                    assertThat(operation.state())
                            .isEqualTo(OperationState.READY);
                    assertThat(operation.resultRef())
                            .isEqualTo("CI_OBSERVATION_PROVIDER_UNSUPPORTED");
                });
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'"))
                .isZero();
        advancePublicationClock(Duration.ofMinutes(6));
        rebuildCiCoordinators(
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim retry = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        assertThat(retry.operationId()).isEqualTo(first.operationId());
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, retry,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
    }

    @Test
    void unstableProviderReadStoresNothingAndIdenticalRetryReusesRound()
    {
        Claim first = observationClaim("observe-replay");
        assertThat(executeCiObservation(
                runtime, observationCoordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), UNSTABLE)).isEmpty();
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'"))
                .isZero();

        advancePublicationClock(Duration.ofMinutes(2));
        rebuildCiCoordinators(
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim accepted = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound firstGreen = executeCiObservation(
                runtime, observationCoordinator, accepted,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        int observations = count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'");

        advancePublicationClock(Duration.ofMinutes(6));
        rebuildCiCoordinators(
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        assertThat(runtime.operation(first.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);
        assertThat(jdbc.queryForMap(
                "SELECT delivery_state, not_before FROM "
                        + "flow_runtime_dispatch_ticket WHERE operation_id = ?",
                first.operationId()))
                .containsEntry("delivery_state", "AVAILABLE");
        Claim replay = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound sameGreen = executeCiObservation(
                runtime, observationCoordinator, replay,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(sameGreen.roundId()).isEqualTo(firstGreen.roundId());
        assertThat(sameGreen.evidenceRevision())
                .isEqualTo(firstGreen.evidenceRevision());
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'"))
                .isEqualTo(observations);
    }

    @Test
    void acceptedBatchResponseLossReplaysTheExactHistoricalRound()
    {
        Claim claim = observationClaim("observe-response-loss");
        var delivery = prepareCiObservation(
                runtime, observationCoordinator, claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN);

        CiRound first = observationCoordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()).orElseThrow();
        int observations = count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'");
        int rounds = count("flow_ci_round",
                "source_observation_operation_id = '"
                        + claim.operationId() + "'");
        int finalRed = runtime.pendingWork(task.taskId()).stream()
                .filter(work -> work.kind() == PendingKind.FINAL_RED)
                .toList().size();
        int learningSubjects = count(
                "flow_ci_learning_subject", "receipt_id IS NOT NULL");
        int learningOperations = count(
                "flow_runtime_operation", "kind = 'RUN_CI_LEARNING'");

        CiRound replay = observationCoordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()).orElseThrow();

        assertThat(replay).isEqualTo(first);
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isEqualTo(observations);
        assertThat(count("flow_ci_round",
                "source_observation_operation_id = '"
                        + claim.operationId() + "'"))
                .isEqualTo(rounds);
        assertThat(runtime.pendingWork(task.taskId()).stream()
                .filter(work -> work.kind() == PendingKind.FINAL_RED))
                .hasSize(finalRed);
        assertThat(count(
                "flow_ci_learning_subject", "receipt_id IS NOT NULL"))
                .isEqualTo(learningSubjects);
        assertThat(count(
                "flow_runtime_operation", "kind = 'RUN_CI_LEARNING'"))
                .isEqualTo(learningOperations);
    }

    @Test
    void greenLearningReservationRollsBackWithLateWatchRearmFailure()
    {
        Claim claim = observationClaim("learn-accept-rollback");
        var delivery = prepareCiObservation(
                runtime, observationCoordinator, claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN);
        jdbc.execute("""
                CREATE TRIGGER reject_green_learning_rearm
                BEFORE UPDATE OF delivery_state
                ON flow_runtime_dispatch_ticket
                WHEN OLD.operation_id = '%s'
                  AND NEW.delivery_state = 'AVAILABLE'
                BEGIN
                    SELECT RAISE(ABORT, 'green rearm rejected');
                END
                """.formatted(claim.operationId()));

        assertThatThrownBy(() -> observationCoordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("flow_ci_learning_subject", "1 = 1")).isZero();
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isZero();
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isZero();
        assertThat(runtime.operation(claim.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);

        jdbc.execute("DROP TRIGGER reject_green_learning_rearm");
        assertThat(observationCoordinator.acceptCiObservation(
                delivery.activation(), delivery.proof())).isPresent();
        assertThat(count("flow_ci_learning_subject", "1 = 1")).isEqualTo(1);
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isEqualTo(1);
    }

    @Test
    void lateAcceptanceFailureRollsBackEveryOwnerAndExactRetrySucceeds()
    {
        Claim claim = observationClaim("observe-rollback");
        var delivery = prepareCiObservation(
                runtime, observationCoordinator, claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS);
        int pendingBefore = runtime.pendingWork(task.taskId()).stream()
                .filter(work -> work.kind() == PendingKind.FINAL_RED)
                .toList().size();
        jdbc.execute("""
                CREATE TRIGGER reject_ci_observation_rearm
                BEFORE UPDATE OF delivery_state
                ON flow_runtime_dispatch_ticket
                WHEN OLD.operation_id = '%s'
                  AND NEW.delivery_state = 'AVAILABLE'
                BEGIN
                    SELECT RAISE(ABORT, 'CI rearm rejected');
                END
                """.formatted(claim.operationId()));

        assertThatThrownBy(() -> observationCoordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isZero();
        assertThat(count("flow_ci_round",
                "source_observation_operation_id = '"
                        + claim.operationId() + "'"))
                .isZero();
        assertThat(runtime.operation(claim.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);
        assertThat(runtime.pendingWork(task.taskId()).stream()
                .filter(work -> work.kind() == PendingKind.FINAL_RED))
                .hasSize(pendingBefore);

        jdbc.execute("DROP TRIGGER reject_ci_observation_rearm");
        CiRound accepted = observationCoordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()).orElseThrow();
        assertThat(accepted.state()).isEqualTo(RoundState.QUEUED);
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isEqualTo(1);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .hasSize(pendingBefore + 1);
    }

    @Test
    void policyAdvanceRejectsOldBatchUntilTheNextCurrentPolicyPoll()
    {
        Claim first = observationClaim("observe-policy-advance");
        var oldPolicyDelivery = prepareCiObservation(
                runtime, observationCoordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN);
        var current = autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:advanced",
                "github-check-policy-digest:advanced",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        assertThat(observationCoordinator.acceptCiObservation(
                oldPolicyDelivery.activation(), oldPolicyDelivery.proof()))
                .isEmpty();
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'"))
                .isZero();

        advancePublicationClock(Duration.ofMinutes(2));
        rebuildCiCoordinators(
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim retry = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound green = executeCiObservation(
                runtime, observationCoordinator, retry,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.policyRevisionId())
                .isEqualTo(current.policyRevisionId());
        assertThat(green.sourceObservationOperationId())
                .isEqualTo(first.operationId());
    }

    @Test
    void unrelatedInternalFactCannotCompleteAProviderBatch()
    {
        Claim claim = observationClaim("observe-mixed-source");
        autofix.observeCi(pr.prId(), new NormalizedCheck(
                runtime.pullRequest(pr.prId()).orElseThrow().currentRemoteHead(),
                "GITHUB_CHECK:7:build", "internal-check", "internal-run",
                1, "internal-revision", "build", "COMPLETED", "SUCCESS",
                NOW, NOW.plusSeconds(1), NOW.plusSeconds(1),
                "internal:evidence"));

        CiRound collecting = executeCiObservation(
                runtime, observationCoordinator, claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), PENDING)
                .orElseThrow();

        assertThat(collecting.state()).isEqualTo(RoundState.COLLECTING);
        assertThat(collecting.sourceObservationOperationId())
                .isEqualTo(claim.operationId());
        assertThat(collecting.checkObservationIds()).isEmpty();
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isEqualTo(1);
        assertThat(runtime.pendingWork(task.taskId()))
                .noneMatch(work -> work.kind() == PendingKind.FINAL_RED
                        && work.externalKey().equals(collecting.roundId()));
    }

    @Test
    void newerReceiptSupersessionMakesOldClaimRecoveryIdempotent()
    {
        Claim oldWatch = observationClaim("observe-old-receipt");
        CiRound firstRed = executeCiObservation(
                runtime, observationCoordinator, oldWatch,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS)
                .orElseThrow();
        CompletedReady secondReady = openReadyGate(
                "observe-second-receipt", firstRed);
        GateRevision secondRevision = secondReady.revision();
        AuthorizedCiUpdate secondAuthorization = userGates.authorizeCiUpdate(
                secondRevision.gateId(), secondRevision.revision(),
                secondRevision.subjectDigest(), secondRevision.actionDigest(),
                "observe-second-receipt-authorization");
        Claim secondPublication = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var secondReceipt = executeApplied(
                runtime, userGates, githubEffects, secondPublication,
                githubEffects.steps(secondAuthorization.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC), () -> {},
                new AtomicInteger()).orElseThrow();

        Claim secondWatch = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound secondRed = executeCiObservation(
                runtime, observationCoordinator, secondWatch,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS)
                .orElseThrow();
        CompletedReady thirdReady = openReadyGate(
                "observe-third-receipt", secondRed);
        GateRevision thirdRevision = thirdReady.revision();
        AuthorizedCiUpdate thirdAuthorization = userGates.authorizeCiUpdate(
                thirdRevision.gateId(), thirdRevision.revision(),
                thirdRevision.subjectDigest(), thirdRevision.actionDigest(),
                "observe-third-receipt-authorization");
        Claim thirdPublication = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var thirdReceipt = executeApplied(
                runtime, userGates, githubEffects, thirdPublication,
                githubEffects.steps(thirdAuthorization.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC), () -> {},
                new AtomicInteger()).orElseThrow();

        assertThat(runtime.operation(oldWatch.operationId()).orElseThrow())
                .satisfies(operation -> {
                    assertThat(operation.state())
                            .isEqualTo(OperationState.CANCELED);
                    assertThat(operation.resultRef())
                            .isEqualTo("CI_HEAD_SUPERSEDED:"
                                    + secondReceipt.receiptId());
                });
        assertThat(runtime.operation(secondWatch.operationId()).orElseThrow()
                .resultRef()).isEqualTo(
                        "CI_HEAD_SUPERSEDED:" + thirdReceipt.receiptId());
        runtime.recoverExpiredCiObservation(
                oldWatch.operationId(), oldWatch.generation());
        runtime.recoverExpiredCiObservation(
                oldWatch.operationId(), oldWatch.generation());
        assertThat(count("flow_runtime_operation",
                "kind = 'OBSERVE_CI' AND state <> 'CANCELED'"))
                .isEqualTo(1);
    }

    @Test
    void attemptInsertFailureRollsBackBeforeProviderCall()
    {
        CompletedReady ready = openReadyGate("executor-attempt-rollback");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "executor-attempt-rollback-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var step = githubEffects.steps(authorized.planId()).getFirst();
        jdbc.execute("""
                CREATE TRIGGER reject_effect_attempt
                BEFORE INSERT ON flow_github_external_effect_attempt
                BEGIN
                    SELECT RAISE(ABORT, 'attempt insert rejected');
                END
                """);
        AtomicInteger pushes = new AtomicInteger();

        assertThatThrownBy(() -> executeApplied(
                runtime,
                userGates,
                githubEffects,
                claim,
                step,
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                () -> {},
                pushes)).isInstanceOf(RuntimeException.class);

        assertThat(pushes.get()).isZero();
        assertThat(count(
                "flow_github_external_effect_attempt", "1 = 1")).isZero();
    }

    @Test
    void invalidProviderTargetReplaysWithoutAnotherProviderCommand()
    {
        assertTerminalProviderRejectionReplays(true);
    }

    @Test
    void divergedProviderTargetReplaysWithoutAnotherProviderCommand()
    {
        assertTerminalProviderRejectionReplays(false);
    }

    @Test
    void unavailablePreflightRetainsTheBarrierWithoutAnAttempt()
    {
        CompletedReady ready = openReadyGate("provider-preflight-unavailable");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "provider-preflight-unavailable-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        AtomicInteger pushes = new AtomicInteger();

        assertThat(executeUnavailableProbe(
                runtime,
                userGates,
                githubEffects,
                claim,
                githubEffects.steps(authorized.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                false,
                pushes)).isEmpty();

        assertThat(pushes.get()).isZero();
        assertThat(githubEffects.attempts(authorized.planId())).isEmpty();
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
        assertThat(userGates.transitions(revision.gateId()).getLast())
                .satisfies(transition -> {
                    assertThat(transition.toState())
                            .isEqualTo(GateState.NEEDS_ATTENTION);
                    assertThat(transition.reasonCode())
                            .isEqualTo("EFFECT_PREPARATION_UNAVAILABLE");
                });
    }

    @Test
    void invalidPostAttemptProbeRetainsTheBarrier()
    {
        CompletedReady ready = openReadyGate("provider-post-invalid");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "provider-post-invalid-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        AtomicInteger pushes = new AtomicInteger();

        assertThat(executeUnavailableProbe(
                runtime,
                userGates,
                githubEffects,
                claim,
                githubEffects.steps(authorized.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                true,
                pushes)).isEmpty();

        assertThat(pushes.get()).isEqualTo(1);
        assertThat(githubEffects.attempts(authorized.planId())).hasSize(1);
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
        assertThat(userGates.transitions(revision.gateId()).getLast())
                .satisfies(transition -> {
                    assertThat(transition.toState())
                            .isEqualTo(GateState.NEEDS_ATTENTION);
                    assertThat(transition.reasonCode())
                            .isEqualTo("EFFECT_PROBE_UNAVAILABLE");
                });
    }

    @Test
    void activatedExpiryIsProbeOnlyAndRecoveryReplays()
    {
        CompletedReady ready = openReadyGate("publish-probe-recovery");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "publish-probe-recovery-key");
        Claim first = runtime.claimNextPublish(
                "publisher", Duration.ofSeconds(1)).orElseThrow();
        var activation = userGates.beginCiUpdateEffect(first);
        githubEffects.recordObservation(
                first,
                observation(
                        runtime, first, activation, null,
                        ProbeOutcome.ABSENT),
                NOW);
        githubEffects.activateAttempt(first, authorized.planId(), NOW);
        advancePublicationClock(Duration.ofSeconds(2));

        assertThatThrownBy(() -> userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe-only");
        userGates.recoverExpiredCiUpdateProbe(
                first.operationId(), first.generation());
        userGates.recoverExpiredCiUpdateProbe(
                first.operationId(), first.generation());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
        assertThat(userGates.transitions(revision.gateId()).getLast()
                .toState())
                .isEqualTo(GateState.NEEDS_ATTENTION);

        advancePublicationClock(Duration.ofSeconds(5));
        Claim probe = runtime.claimNextPublish("probe-worker", TTL)
                .orElseThrow();
        var probeActivation = userGates.beginCiUpdateEffect(probe);
        assertThat(probeActivation.mutationAllowed()).isFalse();
        userGates.applyCiUpdateObservation(
                probe,
                null,
                githubEffects.attempts(authorized.planId()).getLast(),
                observation(
                        runtime,
                        probe,
                        probeActivation,
                        githubEffects.attempts(
                                authorized.planId()).getLast(),
                        ProbeOutcome.ABSENT));
        assertThat(userGates.transitions(revision.gateId()).getLast()
                .toState())
                .isEqualTo(GateState.AUTHORIZED);
        advancePublicationClock(Duration.ofSeconds(5));
        Claim retry = runtime.claimNextPublish("retry-worker", TTL)
                .orElseThrow();
        assertThat(userGates.beginCiUpdateEffect(retry).mutationAllowed())
                .isTrue();
    }

    @Test
    void authorityDriftAfterAttemptRetainsProbeOnlyBarrierOnAbsent()
    {
        CompletedReady ready = openReadyGate("publish-authority-unproven");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "publish-authority-unproven-key");
        Claim first = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(first);
        githubEffects.recordObservation(
                first,
                observation(
                        runtime, first, activation, null,
                        ProbeOutcome.ABSENT),
                NOW);
        var activated = githubEffects.activateAttempt(
                first, authorized.planId(), NOW);
        runtime.consumePublishExecutionHandle(
                activated.executionHandle(),
                first,
                activated.attempt().attemptId(),
                activated.attempt().executionTokenDigest());
        userGates.applyCiUpdateObservation(
                first,
                activated.executionHandle(),
                activated.attempt(),
                observation(
                        runtime,
                        first,
                        activation,
                        activated.attempt(),
                        ProbeOutcome.ABSENT));
        publishCheckPolicy("authority-drift", List.of("/usr/bin/true"));
        advancePublicationClock(Duration.ofSeconds(5));
        Claim second = runtime.claimNextPublish("probe-worker", TTL)
                .orElseThrow();

        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(second))
                .isInstanceOf(
                        UserGates.DurableProbeRequiredException.class);
        advancePublicationClock(Duration.ofSeconds(5));
        Claim third = runtime.claimNextPublish("probe-worker-2", TTL)
                .orElseThrow();
        var probeOnly = userGates.beginCiUpdateEffect(third);
        assertThat(probeOnly.mutationAllowed()).isFalse();
        userGates.applyCiUpdateObservation(
                third,
                null,
                activated.attempt(),
                observation(
                        runtime,
                        third,
                        probeOnly,
                        activated.attempt(),
                        ProbeOutcome.ABSENT));

        assertThat(userGates.transitions(revision.gateId()).getLast()
                .toState()).isEqualTo(GateState.NEEDS_ATTENTION);
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
    }

    @Test
    void sameTimestampUnknownAfterAbsentCannotAuthorizeAttempt()
    {
        CompletedReady ready = openReadyGate("probe-order");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "probe-order-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        githubEffects.recordObservation(
                claim,
                observation(
                        runtime, claim, activation, null,
                        ProbeOutcome.ABSENT),
                NOW);
        githubEffects.recordObservation(
                claim,
                observation(
                        runtime, claim, activation, null,
                        ProbeOutcome.UNKNOWN),
                NOW);

        assertThatThrownBy(() -> githubEffects.activateAttempt(
                claim, authorized.planId(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latest exact-expected");
        assertThat(count("flow_github_external_effect_attempt", "1 = 1"))
                .isZero();
    }

    @Test
    void appliedSettlementAcceptsEqualConcurrentRemoteObservation()
    {
        CompletedReady ready = openReadyGate("publish-observed-race");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "publish-observed-race-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        runtime.advanceRemoteHead(
                activation.prId(), activation.expectedRemoteHead(),
                activation.proposedHead());

        assertThat(userGates.applyCiUpdateObservation(
                claim,
                null,
                null,
                observation(
                        runtime, claim, activation, null,
                        ProbeOutcome.APPLIED))).isPresent();
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(count("flow_github_external_effect_receipt", "1 = 1"))
                .isEqualTo(1);
    }
}
