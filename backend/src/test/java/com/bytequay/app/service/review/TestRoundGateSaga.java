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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.RoundGateAuthorization;
import com.bytequay.app.domain.RoundGateEffect;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.RoundGateStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestRoundGateSaga
{
    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final Path WORKTREE = Path.of("/tmp/round-gate-worktree");
    private static final String ROUND_ID = "00000000-0000-0000-0000-000000000001";

    private final ReviewRoundStore rounds = mock(ReviewRoundStore.class);
    private final InMemoryRoundGateStore gates = new InMemoryRoundGateStore();
    private final TaskStore tasks = mock(TaskStore.class);
    private final StageStore stages = mock(StageStore.class);
    private final ReviewRoundStateMachine roundMachine = mock(ReviewRoundStateMachine.class);
    private final TaskPhaseMachine taskMachine = mock(TaskPhaseMachine.class);
    private final TaskCommandExecutor commands =
            new TaskCommandExecutor(new TestTransactionManager());
    private final PRService prs = mock(PRService.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final CodeFingerprints fingerprints = mock(CodeFingerprints.class);
    private final AtomicReference<Task> task = new AtomicReference<>(task());
    private final AtomicReference<ReviewRound> round = new AtomicReference<>(round());
    private final RoundGateSaga saga = new RoundGateSaga(
            rounds, gates, tasks, stages, roundMachine, taskMachine, commands,
            prs, pullRequests, git, fingerprints, new ObjectMapper(), Runnable::run);

    @BeforeEach
    void setUp()
            throws Exception
    {
        when(tasks.findTaskById("task-1")).thenAnswer(ignored -> Optional.of(task.get()));
        when(rounds.findById(ROUND_ID)).thenAnswer(ignored -> Optional.of(round.get()));
        when(stages.findCommentsByRound(any())).thenReturn(List.of());
        when(git.headSha(WORKTREE)).thenReturn("head-1");
        when(fingerprints.fingerprint(WORKTREE)).thenReturn("fingerprint-1");
        doAnswer(invocation -> {
            String token = invocation.getArgument(2);
            round.set(withActiveToken(round.get(), token));
            return round.get();
        }).when(roundMachine).authorizeGateInCommand(
                any(), any(), any(), anyInt(), any(), any());
        when(roundMachine.postInCommand(any(), any(), any(), any()))
                .thenAnswer(ignored -> round.get());
    }

    @Test
    void approvalClaimsOutsideIoAndFinalizesOnlyAfterEvidence()
            throws Exception
    {
        doAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(git).push(WORKTREE);

        saga.approve(ROUND_ID);
        verify(git, never()).push(any());
        saga.onAuthorized(new RoundGateAuthorizedEvent(
                "task-1", ROUND_ID, gates.authorization.token()));

        verify(git).push(WORKTREE);
        verify(roundMachine).postInCommand(
                "task-1", ROUND_ID, gates.authorization.token(), "round_gate_posted");
        assertThat(gates.findAuthorization(gates.authorization.token()).orElseThrow().active())
                .isFalse();
        assertThat(gates.findEffects(gates.authorization.token()))
                .allMatch(RoundGateEffect::completed)
                .extracting(RoundGateEffect::attempts)
                .containsExactly(1);
    }

    @Test
    void expiredPushClaimUsesTheRemoteHeadInsteadOfPushingAgain()
            throws Exception
    {
        doAnswer(ignored -> {
            gates.expireClaim(RoundGateSaga.EFFECT_PUSH_BRANCH);
            throw new AssertionError("simulated process crash");
        }).when(git).push(WORKTREE);

        saga.approve(ROUND_ID);
        assertThatThrownBy(() -> saga.drive(gates.authorization.token()))
                .isInstanceOf(AssertionError.class)
                .hasMessage("simulated process crash");
        when(git.remoteHeadSha(WORKTREE, "origin", "feature/x"))
                .thenReturn(Optional.of("head-1"));

        saga.drive(gates.authorization.token());

        verify(git, times(1)).push(WORKTREE);
        verify(git).remoteHeadSha(WORKTREE, "origin", "feature/x");
        verify(roundMachine).postInCommand(
                "task-1", ROUND_ID, gates.authorization.token(), "round_gate_posted");
    }

    @Test
    void livePushClaimWaitsForItsLeaseInsteadOfParkingOrReplaying()
            throws Exception
    {
        saga.approve(ROUND_ID);
        String token = gates.authorization.token();
        Instant claimedAt = Instant.now();
        assertThat(gates.claimEffect(
                token, RoundGateSaga.EFFECT_PUSH_BRANCH, "worker-1",
                claimedAt, claimedAt.plusSeconds(300))).isTrue();

        saga.drive(token);

        verify(git, never()).push(any());
        verify(git, never()).remoteHeadSha(any(), any(), any());
        verify(roundMachine, never()).postInCommand(any(), any(), any(), any());
        verify(roundMachine, never()).parkInCommand(any(), any(), any());
        verify(taskMachine, never()).parkOperationalInCommand(any(), any(), any());
        assertThat(gates.findEffect(
                token, RoundGateSaga.EFFECT_PUSH_BRANCH).orElseThrow().status())
                .isEqualTo(RoundGateEffect.Status.IN_FLIGHT);
    }

    @Test
    void aStoppedTaskCannotClaimTheAuthorizedEffect()
            throws Exception
    {
        doAnswer(invocation -> {
            String token = invocation.getArgument(2);
            round.set(withActiveToken(round.get(), token));
            task.set(task.get().withStatus(TaskStatus.PAUSED));
            return round.get();
        }).when(roundMachine).authorizeGateInCommand(
                any(), any(), any(), anyInt(), any(), any());

        saga.approve(ROUND_ID);
        saga.onAuthorized(new RoundGateAuthorizedEvent(
                "task-1", ROUND_ID, gates.authorization.token()));

        verify(git, never()).push(any());
        assertThat(gates.findEffects(gates.authorization.token()).getFirst().attempts()).isZero();
    }

    @Test
    void exactRecoveryRearmsOnlyTheFailedCursor()
            throws Exception
    {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "credentials rejected"))
                .when(git).push(WORKTREE);
        saga.approve(ROUND_ID);
        assertThatThrownBy(() -> saga.drive(gates.authorization.token()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("credentials rejected");
        String token = gates.authorization.token();
        task.set(task.get()
                .withPhase(TaskPhase.NEEDS_ATTENTION)
                .withStatus(TaskStatus.NEEDS_ATTENTION));
        round.set(pausedAtGate(round.get(), token));

        RoundGateSaga.RecoveryPlan plan = saga.prepareRecovery("task-1", 1).orElseThrow();
        when(tasks.recoveryRequest("task-1")).thenReturn(Optional.of(
                new TaskRecoveryRequest(
                        "recovery-1", TaskRecoveryRequest.KIND_EXTERNAL_SAGA,
                        saga.recoveryPayload(plan), NOW)));

        clearInvocations(git, fingerprints);
        commands.executeVoid("task-1", () -> saga.resumeExternalSagaInCommand(plan));

        RoundGateEffect rearmed = gates.findEffect(token, plan.effectKey()).orElseThrow();
        assertThat(rearmed.status()).isEqualTo(RoundGateEffect.Status.RETRYABLE_FAILED);
        assertThat(rearmed.attemptLimit()).isEqualTo(rearmed.attempts() + 1);
        verify(roundMachine).resumeInCommand("task-1", ROUND_ID, "round_gate_recovered");
        verifyNoInteractions(git, fingerprints);
    }

    @Test
    void retryBoundParksImmediatelyAfterTheLastFailedAttempt()
            throws Exception
    {
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "offline"))
                .when(git).push(WORKTREE);
        saga.approve(ROUND_ID);
        String token = gates.authorization.token();

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThatThrownBy(() -> saga.drive(token))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("offline");
            if (attempt < 3) {
                gates.makeRetryDue(RoundGateSaga.EFFECT_PUSH_BRANCH);
            }
        }

        RoundGateEffect failed = gates.findEffect(
                token, RoundGateSaga.EFFECT_PUSH_BRANCH).orElseThrow();
        assertThat(failed.status()).isEqualTo(RoundGateEffect.Status.PERMANENT_FAILED);
        assertThat(failed.attempts()).isEqualTo(failed.attemptLimit());
        verify(roundMachine).parkInCommand("task-1", ROUND_ID, "round_gate_effect_failed");
        verify(taskMachine).parkOperationalInCommand(
                "task-1", Actor.AGENT, "round_gate_effect_failed");
    }

    @Test
    void retryableProbeFailuresBackOffAndEventuallyPark()
            throws Exception
    {
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "push offline"))
                .when(git).push(WORKTREE);
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "probe offline"))
                .when(git).remoteHeadSha(WORKTREE, "origin", "feature/x");
        saga.approve(ROUND_ID);
        String token = gates.authorization.token();

        assertThatThrownBy(() -> saga.drive(token))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("push offline");
        gates.makeRetryDue(RoundGateSaga.EFFECT_PUSH_BRANCH);
        saga.drive(token);
        RoundGateEffect retrying = gates.findEffect(
                token, RoundGateSaga.EFFECT_PUSH_BRANCH).orElseThrow();
        assertThat(retrying.status()).isEqualTo(RoundGateEffect.Status.RETRYABLE_FAILED);
        assertThat(retrying.attempts()).isEqualTo(2);

        gates.makeRetryDue(RoundGateSaga.EFFECT_PUSH_BRANCH);
        saga.drive(token);

        RoundGateEffect failed = gates.findEffect(
                token, RoundGateSaga.EFFECT_PUSH_BRANCH).orElseThrow();
        assertThat(failed.status()).isEqualTo(RoundGateEffect.Status.PERMANENT_FAILED);
        assertThat(failed.attempts()).isEqualTo(failed.attemptLimit());
        verify(git, times(1)).push(WORKTREE);
        verify(git, times(2)).remoteHeadSha(WORKTREE, "origin", "feature/x");
        verify(roundMachine).parkInCommand("task-1", ROUND_ID, "round_gate_effect_failed");
        verify(taskMachine).parkOperationalInCommand(
                "task-1", Actor.AGENT, "round_gate_effect_failed");
    }

    @Test
    void addressingDraftMutationPreservesTheAdmittedTurnRevision()
    {
        round.set(withStatus(round.get(), ReviewRoundState.ADDRESSING));
        AtomicReference<String> edited = new AtomicReference<>();

        saga.editPayload("task-1", ROUND_ID, () -> edited.set("saved"));

        assertThat(edited).hasValue("saved");
        assertThat(gates.revisionBumps).isZero();
    }

    @Test
    void awaitingGateEditAdvancesTheHumanReviewedPayloadRevision()
    {
        AtomicReference<String> edited = new AtomicReference<>();

        saga.editPayload("task-1", ROUND_ID, () -> edited.set("saved"));

        assertThat(edited).hasValue("saved");
        assertThat(gates.revisionBumps).isEqualTo(1);
    }

    @Test
    void awaitingGateEditRevokesAnUnclaimedAuthorization()
    {
        String token = "token-1";
        gates.insert(authorization(token), List.of("reply:1"), 3);
        round.set(withActiveToken(round.get(), token));

        saga.editPayload("task-1", ROUND_ID, () -> {});

        RoundGateAuthorization revoked = gates.findAuthorization(token).orElseThrow();
        assertThat(revoked.active()).isFalse();
        assertThat(revoked.outcome()).isEqualTo("payload_edited");
        assertThat(gates.revisionBumps).isEqualTo(1);
    }

    @Test
    void awaitingGateEditIsRejectedAfterAnEffectWasClaimed()
    {
        String token = "token-1";
        gates.insert(authorization(token), List.of("reply:1"), 3);
        round.set(withActiveToken(round.get(), token));
        assertThat(gates.claimEffect(
                token, "reply:1", "worker-1", NOW, NOW.plusSeconds(30))).isTrue();
        AtomicReference<String> edited = new AtomicReference<>();

        assertThatThrownBy(() -> saga.editPayload(
                "task-1", ROUND_ID, () -> edited.set("saved")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot change after posting has started");

        assertThat(edited.get()).isNull();
        assertThat(gates.findAuthorization(token).orElseThrow().active()).isTrue();
        assertThat(gates.revisionBumps).isZero();
    }

    private static Task task()
    {
        return new Task(
                "task-1", "thread-1", 1L, TaskStatus.IN_REVIEW, "feature/x",
                WORKTREE.toString(), "main", "/tmp/repo", null, null, null,
                null, null, "DEVELOP", 42, null, 0L, 0L, 0L, null, NOW,
                null, null, null, null, null, null,
                TaskPhase.AWAITING_REMOTE_REVIEW, null, 0, "acme/widget#42");
    }

    private static RoundGateAuthorization authorization(String token)
    {
        return new RoundGateAuthorization(
                token, "task-1", ROUND_ID, 0, 1, Actor.HUMAN,
                "fingerprint-1", "{}", "payload-digest", "[\"reply:1\"]",
                NOW, null, null, null);
    }

    private static ReviewRound round()
    {
        return new ReviewRound(
                ROUND_ID, "task-1", 1, List.of(), ReviewRoundState.AWAITING_GATE,
                ReviewRound.ReviewRoundStats.empty(), "run-1", NOW, NOW, null,
                ReviewRound.ORIGIN_EXTERNAL, ReviewRound.VERDICT_APPROVED, 1, 5,
                null, "fingerprint-1", 0, 0, 0, null, null);
    }

    private static ReviewRound withActiveToken(ReviewRound value, String token)
    {
        return new ReviewRound(
                value.id(), value.taskId(), value.idx(), value.reviewers(), value.status(),
                value.stats(), value.runId(), value.openedAt(), value.gatedAt(), value.postedAt(),
                value.origin(), value.brainVerdict(), value.iteration(), value.budget(),
                value.pausedFrom(), value.codeFingerprint(), value.enqueueFailures(),
                value.kickAttempt(), value.gateRevision(), token, value.closedAt());
    }

    private static ReviewRound withStatus(ReviewRound value, ReviewRoundState status)
    {
        return new ReviewRound(
                value.id(), value.taskId(), value.idx(), value.reviewers(), status,
                value.stats(), value.runId(), value.openedAt(), value.gatedAt(), value.postedAt(),
                value.origin(), value.brainVerdict(), value.iteration(), value.budget(),
                value.pausedFrom(), value.codeFingerprint(), value.enqueueFailures(),
                value.kickAttempt(), value.gateRevision(), value.activeGateToken(),
                value.closedAt());
    }

    private static ReviewRound pausedAtGate(ReviewRound value, String token)
    {
        return new ReviewRound(
                value.id(), value.taskId(), value.idx(), value.reviewers(), ReviewRoundState.PAUSED,
                value.stats(), value.runId(), value.openedAt(), value.gatedAt(), value.postedAt(),
                value.origin(), value.brainVerdict(), value.iteration(), value.budget(),
                ReviewRoundState.AWAITING_GATE, value.codeFingerprint(), value.enqueueFailures(),
                value.kickAttempt(), value.gateRevision(), token, value.closedAt());
    }

    private static final class InMemoryRoundGateStore
            implements RoundGateStore
    {
        private RoundGateAuthorization authorization;
        private final Map<String, RoundGateEffect> effects = new LinkedHashMap<>();
        private int revisionBumps;

        @Override
        public void insert(
                RoundGateAuthorization value, List<String> effectKeys, int attemptLimit)
        {
            authorization = value;
            effectKeys.forEach(key -> effects.put(key, new RoundGateEffect(
                    effects.size() + 1L, value.token(), key, RoundGateEffect.Status.PENDING,
                    0, attemptLimit, null, null, null, null, null, null,
                    null, null, null)));
        }

        @Override
        public Optional<RoundGateAuthorization> findAuthorization(String token)
        {
            return authorization != null && authorization.token().equals(token)
                    ? Optional.of(authorization) : Optional.empty();
        }

        @Override
        public Optional<RoundGateAuthorization> findActiveByRound(String roundId)
        {
            return authorization != null && authorization.active()
                    && authorization.roundId().equals(roundId)
                    ? Optional.of(authorization) : Optional.empty();
        }

        @Override
        public Optional<RoundGateAuthorization> findActiveByTask(String taskId)
        {
            return authorization != null && authorization.active()
                    && authorization.taskId().equals(taskId)
                    ? Optional.of(authorization) : Optional.empty();
        }

        @Override
        public List<RoundGateAuthorization> findRecoverable(Instant now, int limit)
        {
            return authorization != null && authorization.active()
                    ? List.of(authorization) : List.of();
        }

        @Override
        public List<RoundGateEffect> findEffects(String token)
        {
            return new ArrayList<>(effects.values());
        }

        @Override
        public Optional<RoundGateEffect> findEffect(String token, String effectKey)
        {
            return Optional.ofNullable(effects.get(effectKey));
        }

        @Override
        public boolean claimEffect(
                String token, String effectKey, String owner, Instant now, Instant leaseUntil)
        {
            RoundGateEffect value = effects.get(effectKey);
            if (value == null || value.attempts() >= value.attemptLimit()
                    || value.status() == RoundGateEffect.Status.COMPLETED
                    || value.status() == RoundGateEffect.Status.PERMANENT_FAILED
                    || value.status() == RoundGateEffect.Status.RETRYABLE_FAILED
                            && value.nextAttemptAt() != null && value.nextAttemptAt().isAfter(now)
                    || value.status() == RoundGateEffect.Status.IN_FLIGHT
                            && value.leaseUntil() != null && !value.leaseUntil().isBefore(now)) {
                return false;
            }
            effects.put(effectKey, new RoundGateEffect(
                    value.id(), token, effectKey, RoundGateEffect.Status.IN_FLIGHT,
                    value.attempts() + 1, value.attemptLimit(),
                    value.firstClaimedAt() == null ? now : value.firstClaimedAt(), now,
                    owner, leaseUntil, null, null, null, value.evidenceJson(), null));
            return true;
        }

        @Override
        public boolean completeEffect(
                String token, String effectKey, String owner, String evidence, Instant completedAt)
        {
            RoundGateEffect value = effects.get(effectKey);
            if (value == null || value.status() != RoundGateEffect.Status.IN_FLIGHT
                    || !owner.equals(value.claimOwner())) {
                return false;
            }
            effects.put(effectKey, new RoundGateEffect(
                    value.id(), token, effectKey, RoundGateEffect.Status.COMPLETED,
                    value.attempts(), value.attemptLimit(), value.firstClaimedAt(),
                    value.lastClaimedAt(), null, null, null, null, null, evidence, completedAt));
            return true;
        }

        @Override
        public boolean completeProbedEffect(
                String token, String effectKey, String evidence, Instant completedAt)
        {
            RoundGateEffect value = effects.get(effectKey);
            boolean recoverable = value != null && value.attempts() > 0
                    && (value.status() == RoundGateEffect.Status.RETRYABLE_FAILED
                            || value.status() == RoundGateEffect.Status.IN_FLIGHT
                                    && value.leaseUntil() != null
                                    && !value.leaseUntil().isAfter(completedAt));
            if (!recoverable) {
                return false;
            }
            effects.put(effectKey, new RoundGateEffect(
                    value.id(), token, effectKey, RoundGateEffect.Status.COMPLETED,
                    value.attempts(), value.attemptLimit(), value.firstClaimedAt(),
                    value.lastClaimedAt(), null, null, null, null, null, evidence, completedAt));
            return true;
        }

        @Override
        public boolean failEffect(
                String token, String effectKey, String owner, RoundGateEffect.Status status,
                String errorClass, String error, Instant nextAttemptAt)
        {
            RoundGateEffect value = effects.get(effectKey);
            if (value == null || value.status() != RoundGateEffect.Status.IN_FLIGHT
                    || !owner.equals(value.claimOwner())) {
                return false;
            }
            effects.put(effectKey, new RoundGateEffect(
                    value.id(), token, effectKey, status, value.attempts(), value.attemptLimit(),
                    value.firstClaimedAt(), value.lastClaimedAt(), null, null, errorClass,
                    error, nextAttemptAt, value.evidenceJson(), value.completedAt()));
            return true;
        }

        @Override
        public boolean markExhausted(
                String token, String effectKey, String errorClass, String error)
        {
            RoundGateEffect value = effects.get(effectKey);
            if (value == null || !value.exhausted() || value.completed()) {
                return false;
            }
            effects.put(effectKey, new RoundGateEffect(
                    value.id(), token, effectKey, RoundGateEffect.Status.PERMANENT_FAILED,
                    value.attempts(), value.attemptLimit(), value.firstClaimedAt(),
                    value.lastClaimedAt(), null, null, errorClass, error, null,
                    value.evidenceJson(), value.completedAt()));
            return true;
        }

        @Override
        public boolean rearmEffect(
                String token, String effectKey, int allowance, Instant retryAt)
        {
            RoundGateEffect value = effects.get(effectKey);
            if (value == null || !failed(value)) {
                return false;
            }
            effects.put(effectKey, new RoundGateEffect(
                    value.id(), token, effectKey, RoundGateEffect.Status.RETRYABLE_FAILED,
                    value.attempts(), value.attempts() + allowance, value.firstClaimedAt(),
                    value.lastClaimedAt(), null, null, value.lastErrorClass(),
                    value.lastError(), retryAt, value.evidenceJson(), value.completedAt()));
            return true;
        }

        @Override
        public boolean revokeIfUnclaimed(String token, String outcome, Instant revokedAt)
        {
            if (authorization == null || !authorization.active()
                    || effects.values().stream().anyMatch(RoundGateEffect::claimed)) {
                return false;
            }
            authorization = withOutcome(authorization, revokedAt, null, outcome);
            return true;
        }

        @Override
        public boolean bumpGateRevision(
                String taskId, String roundId, int expectedRevision, String activeToken)
        {
            revisionBumps++;
            return true;
        }

        @Override
        public boolean sealActive(String taskId, String outcome, Instant revokedAt)
        {
            if (authorization == null || !authorization.active()
                    || !taskId.equals(authorization.taskId())) {
                return false;
            }
            authorization = withOutcome(authorization, revokedAt, null, outcome);
            return true;
        }

        @Override
        public boolean consumeIfComplete(String token, String outcome, Instant consumedAt)
        {
            if (authorization == null || !authorization.active()
                    || effects.values().stream().anyMatch(effect -> !effect.completed())) {
                return false;
            }
            authorization = withOutcome(authorization, null, consumedAt, outcome);
            return true;
        }

        private void expireClaim(String effectKey)
        {
            RoundGateEffect value = effects.get(effectKey);
            effects.put(effectKey, new RoundGateEffect(
                    value.id(), value.token(), value.effectKey(), value.status(), value.attempts(),
                    value.attemptLimit(), value.firstClaimedAt(), value.lastClaimedAt(),
                    value.claimOwner(), Instant.EPOCH, value.lastErrorClass(), value.lastError(),
                    value.nextAttemptAt(), value.evidenceJson(), value.completedAt()));
        }

        private void makeRetryDue(String effectKey)
        {
            RoundGateEffect value = effects.get(effectKey);
            effects.put(effectKey, new RoundGateEffect(
                    value.id(), value.token(), value.effectKey(), value.status(), value.attempts(),
                    value.attemptLimit(), value.firstClaimedAt(), value.lastClaimedAt(),
                    value.claimOwner(), value.leaseUntil(), value.lastErrorClass(), value.lastError(),
                    Instant.EPOCH, value.evidenceJson(), value.completedAt()));
        }

        private static boolean failed(RoundGateEffect effect)
        {
            return effect.status() == RoundGateEffect.Status.PERMANENT_FAILED
                    || effect.exhausted();
        }

        private static RoundGateAuthorization withOutcome(
                RoundGateAuthorization value,
                Instant revokedAt,
                Instant consumedAt,
                String outcome)
        {
            return new RoundGateAuthorization(
                    value.token(), value.taskId(), value.roundId(), value.gateRevision(),
                    value.attempt(), value.actor(), value.codeFingerprint(), value.payloadJson(),
                    value.payloadDigest(), value.effectKeysJson(), value.approvedAt(), revokedAt,
                    consumedAt, outcome);
        }
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
