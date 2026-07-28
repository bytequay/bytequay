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
package com.bytequay.app.service.checks;

import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The claimed dev-round flow end to end against the real store: claim
 * inserted and committed first, checks executed by the single admitted
 * owner outside any transaction, terminal CAS once, and the finished
 * event published with the pass outcome. Terminal claims replay their
 * event instead of re-running.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestValidationClaimService
{
    private static final Instant NOW = Instant.parse("2026-07-25T09:00:00Z");

    @Autowired
    private ValidationPassStore store;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private TaskCommandExecutor commands;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void claimsRunsAndPublishesExactlyOnce()
            throws Exception
    {
        String taskId = seedTask("/tmp/claimed-worktree");
        ValidationPassService checks = mock(ValidationPassService.class);
        when(checks.runChecks(taskId)).thenReturn(List.of());
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        when(fingerprints.fingerprint(any(Path.class))).thenReturn("fp-" + taskId);
        List<Object> published = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher events = published::add;

        ValidationClaimService service = new ValidationClaimService(
                store, taskStore, mock(ReviewRoundStore.class),
                checks, fingerprints,
                new ValidationExecutorRegistry(), commands, events, mapper);

        service.claimAndRunDevRound(taskId);

        String claimKey = "dev-round:" + taskId + ":0:fp-" + taskId;
        ValidationClaim claim = awaitTerminal(claimKey);
        assertThat(claim.isTerminalGreen()).isTrue();
        assertThat(published)
                .filteredOn(ValidationPassFinishedEvent.class::isInstance)
                .hasSize(1);

        // A second call for the same fingerprint replays the finished
        // event from the terminal claim without another run.
        service.claimAndRunDevRound(taskId);
        assertThat(published)
                .filteredOn(ValidationPassFinishedEvent.class::isInstance)
                .hasSize(2);
        assertThat(((ValidationPassFinishedEvent) published.get(1)).passed()).isTrue();
    }

    @Test
    void stoppedTaskCannotAdmitValidation()
    {
        String taskId = seedTask("/tmp/stopped-validation-worktree");
        taskStore.updateStatusIf(taskId, TaskStatus.RUNNING, TaskStatus.PAUSED);
        ValidationPassService checks = mock(ValidationPassService.class);
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        ValidationClaimService service = new ValidationClaimService(
                store, taskStore, mock(ReviewRoundStore.class),
                checks, fingerprints,
                new ValidationExecutorRegistry(), commands, ignored -> {}, mapper);

        service.claimAndRunDevRound(taskId);

        verify(fingerprints, never()).fingerprint(any(Path.class));
        verify(checks, never()).runChecks(taskId);
    }

    @Test
    void stoppedTaskFreezesOwedGateValidationWithoutSubmittingExecutor()
    {
        String taskId = seedTask("/tmp/stopped-gate-validation-worktree");
        taskStore.updatePhase(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);
        taskStore.updateStatusIf(taskId, TaskStatus.RUNNING, TaskStatus.PAUSED);
        String roundId = UUID.randomUUID().toString();
        String fingerprint = "gate-fingerprint";
        String claimKey = ValidationClaimService.gateRevalidationClaimKey(
                taskId, roundId, 2, 3, fingerprint);
        store.insertClaim(
                claimKey, taskId, ValidationClaimService.CONTEXT_GATE_REVALIDATION,
                roundId, fingerprint, null, null, NOW);
        ReviewRound round = mock(ReviewRound.class);
        when(round.id()).thenReturn(roundId);
        when(round.taskId()).thenReturn(taskId);
        when(round.status()).thenReturn(ReviewRound.STATUS_TRIAGING);
        when(round.origin()).thenReturn(ReviewRound.ORIGIN_EXTERNAL);
        when(round.gateRevision()).thenReturn(2);
        when(round.kickAttempt()).thenReturn(3);
        when(round.codeFingerprint()).thenReturn("prior-fingerprint");
        ReviewRoundStore rounds = mock(ReviewRoundStore.class);
        when(rounds.findById(roundId)).thenReturn(Optional.of(round));
        ValidationPassService checks = mock(ValidationPassService.class);
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        ValidationExecutorRegistry registry = mock(ValidationExecutorRegistry.class);
        ValidationClaimService service = new ValidationClaimService(
                store, taskStore, rounds, checks, fingerprints,
                registry, commands, ignored -> {}, mapper);

        assertThat(service.claimAndRunGateRevalidation(roundId)).isTrue();

        verify(registry, never()).submitIfAbsent(any(), any());
        verify(fingerprints, never()).fingerprint(any(Path.class));
        verify(checks, never()).runChecks(taskId);
    }

    @Test
    void stoppingTaskDuringChecksPersistsResultWithoutPublishingAdvance()
            throws Exception
    {
        String taskId = seedTask("/tmp/stopped-during-validation-worktree");
        CountDownLatch checksStarted = new CountDownLatch(1);
        CountDownLatch releaseChecks = new CountDownLatch(1);
        ValidationPassService checks = mock(ValidationPassService.class);
        when(checks.runChecks(taskId)).thenAnswer(ignored -> {
            checksStarted.countDown();
            assertThat(releaseChecks.await(2, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        when(fingerprints.fingerprint(any(Path.class))).thenReturn("fp-" + taskId);
        List<Object> published = new CopyOnWriteArrayList<>();
        ValidationClaimService service = new ValidationClaimService(
                store, taskStore, mock(ReviewRoundStore.class),
                checks, fingerprints, new ValidationExecutorRegistry(), commands,
                published::add, mapper);

        service.claimAndRunDevRound(taskId);
        assertThat(checksStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(taskStore.updateStatusIf(
                taskId, TaskStatus.RUNNING, TaskStatus.PAUSED)).isTrue();
        releaseChecks.countDown();

        ValidationClaim claim = awaitTerminal(
                "dev-round:" + taskId + ":0:fp-" + taskId);
        assertThat(claim.isTerminalGreen()).isTrue();
        assertThat(published)
                .filteredOn(ValidationPassFinishedEvent.class::isInstance)
                .isEmpty();
    }

    @Test
    void startupRecoveryReclaimsAnExpiredValidationLease()
    {
        String taskId = seedTask("/tmp/restarted-validation-worktree");
        Task task = taskStore.findTaskById(taskId).orElseThrow();
        String fingerprint = "fp-" + taskId;
        long validationEpoch = taskStore.listPhaseEvents(taskId).stream()
                .filter(event -> event.toPhase() == TaskPhase.VALIDATING)
                .mapToLong(event -> event.id())
                .max()
                .orElse(0L);
        String claimKey = "dev-round:" + taskId + ":" + validationEpoch + ":" + fingerprint;
        store.insertClaim(
                claimKey, taskId, "dev-round", null,
                fingerprint, null, null, NOW);
        assertThat(store.acquireOwner(
                claimKey, "owner-before-restart", "dead-executor",
                NOW.minusSeconds(1), NOW.minusSeconds(2))).isTrue();

        TaskStore restartedTasks = mock(TaskStore.class);
        when(restartedTasks.findTaskById(taskId)).thenReturn(Optional.of(task));
        when(restartedTasks.listByPhases(any(), anyInt())).thenReturn(List.of(task));
        when(restartedTasks.listPhaseEvents(taskId))
                .thenReturn(taskStore.listPhaseEvents(taskId));
        ValidationPassService checks = mock(ValidationPassService.class);
        when(checks.runChecks(taskId)).thenReturn(List.of());
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        when(fingerprints.fingerprint(any(Path.class))).thenReturn(fingerprint);
        ValidationExecutorRegistry restartedRegistry = mock(ValidationExecutorRegistry.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        doReturn(renewal).when(restartedRegistry)
                .scheduleLeaseRenewal(any(Runnable.class), anyLong());
        when(restartedRegistry.submitIfAbsent(eq(claimKey), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return true;
                });
        List<Object> published = new CopyOnWriteArrayList<>();
        ValidationClaimService restarted = new ValidationClaimService(
                store, restartedTasks, mock(ReviewRoundStore.class),
                checks, fingerprints, restartedRegistry, commands,
                published::add, mapper);

        restarted.reconcileClaims();

        assertThat(store.findByClaimKey(claimKey).orElseThrow().isTerminalGreen()).isTrue();
        verify(checks).runChecks(taskId);
        verify(renewal).cancel(false);
        assertThat(published)
                .filteredOn(ValidationPassFinishedEvent.class::isInstance)
                .hasSize(1);
    }

    private ValidationClaim awaitTerminal(String claimKey)
            throws InterruptedException
    {
        for (int i = 0; i < 100; i++) {
            ValidationClaim claim = store.findByClaimKey(claimKey).orElse(null);
            if (claim != null && claim.endedAt() != null) {
                return claim;
            }
            java.lang.Thread.sleep(50);
        }
        throw new AssertionError("claim " + claimKey + " never reached a terminal state");
    }

    private String seedTask(String worktree)
    {
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Claim service test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, NOW, NOW, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", worktree, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null));
        taskStore.updatePhase(taskId, TaskPhase.VALIDATING);
        return taskId;
    }
}
