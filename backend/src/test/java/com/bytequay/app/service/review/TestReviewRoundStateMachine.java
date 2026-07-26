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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.LocalReviewBrainHandoffStore;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.RemoteDevelopmentStageService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.statemachine.IllegalTransitionException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.domain.ReviewRoundState.ADDRESSING;
import static com.bytequay.app.domain.ReviewRoundState.AWAITING_GATE;
import static com.bytequay.app.domain.ReviewRoundState.CLOSED;
import static com.bytequay.app.domain.ReviewRoundState.PAUSED;
import static com.bytequay.app.domain.ReviewRoundState.POSTED;
import static com.bytequay.app.domain.ReviewRoundState.TRIAGING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewRoundStateMachine
{
    private static final String TASK_ID = "task-1";
    private static final String ROUND_ID = "00000000-0000-0000-0000-0000000000c1";
    private static final String RUN_ID = "run-1";
    private static final String REPLACEMENT_RUN_ID = "run-2";
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    private final ReviewRoundStore rounds = mock(ReviewRoundStore.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final TaskStore tasks = mock(TaskStore.class);
    private final StageStore stages = mock(StageStore.class);
    private final PRService prs = mock(PRService.class);
    private final ValidationPassStore validations = mock(ValidationPassStore.class);
    private final LocalReviewBrainHandoffStore handoffs = mock(LocalReviewBrainHandoffStore.class);
    private final LocalReviewSubmissionStore submissions = mock(LocalReviewSubmissionStore.class);
    private final ThreadTurnStore turns = mock(ThreadTurnStore.class);
    private final RemoteDevelopmentStageService remoteStages = mock(RemoteDevelopmentStageService.class);
    private final CodeFingerprints fingerprints = mock(CodeFingerprints.class);
    private final TaskPhaseMachine taskPhases = mock(TaskPhaseMachine.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactionManager = new TestTransactionManager();
    private final TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
    private final ReviewRoundStateMachine machine =
            new ReviewRoundStateMachine(
                    rounds, runs, tasks, stages, prs, validations, handoffs,
                    submissions, turns, remoteStages, fingerprints, taskPhases,
                    commands, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void graphKeepsForwardMovesAndUniversalSealExplicit()
    {
        assertThat(ReviewRoundStateMachine.isLegalTransition(TRIAGING, ADDRESSING)).isTrue();
        assertThat(ReviewRoundStateMachine.isLegalTransition(ADDRESSING, TRIAGING)).isTrue();
        assertThat(ReviewRoundStateMachine.isLegalTransition(AWAITING_GATE, POSTED)).isTrue();
        assertThat(ReviewRoundStateMachine.isLegalTransition(POSTED, CLOSED)).isTrue();
        assertThat(ReviewRoundStateMachine.isLegalTransition(PAUSED, AWAITING_GATE)).isTrue();
        assertThat(ReviewRoundStateMachine.isLegalTransition(POSTED, TRIAGING)).isFalse();
        assertThat(ReviewRoundStateMachine.isLegalTransition(CLOSED, TRIAGING)).isFalse();
    }

    @Test
    void parkCheckpointsTheExactRoundStateAndPausesItsRun()
    {
        ReviewRound active = round(ADDRESSING, null, 2);
        ReviewRound paused = round(PAUSED, ADDRESSING, 2);
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(active), Optional.of(active), Optional.of(paused));
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(AgentRun.STATUS_RUNNING)));
        when(rounds.parkIf(ROUND_ID, ADDRESSING)).thenReturn(true);
        when(runs.pauseInCommand(TASK_ID, RUN_ID, "cost cap"))
                .thenReturn(run(AgentRun.STATUS_PAUSED));

        assertThat(machine.park(ROUND_ID, "cost cap")).isEqualTo(paused);

        verify(rounds).parkIf(ROUND_ID, ADDRESSING);
        verify(events).publishEvent(new ReviewRoundTransitionedEvent(
                TASK_ID, ROUND_ID, ADDRESSING, PAUSED, "cost cap"));
    }

    @Test
    void resumeRestoresGateAndAdvancesTheDurableKick()
    {
        ReviewRound paused = round(PAUSED, AWAITING_GATE, 2);
        ReviewRound resumed = round(AWAITING_GATE, null, 3);
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(paused), Optional.of(paused), Optional.of(resumed));
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(AgentRun.STATUS_PAUSED)));
        when(rounds.resumeIf(ROUND_ID, AWAITING_GATE)).thenReturn(true);
        when(runs.restartInCommand(TASK_ID, RUN_ID))
                .thenReturn(run(REPLACEMENT_RUN_ID, AgentRun.STATUS_QUEUED));
        when(runs.transitionInCommand(
                TASK_ID, REPLACEMENT_RUN_ID, AgentRun.STATUS_AWAITING_GATE,
                "review_resumed"))
                .thenReturn(run(REPLACEMENT_RUN_ID, AgentRun.STATUS_AWAITING_GATE));

        assertThat(machine.resume(ROUND_ID, "runtime stopped").kickAttempt()).isEqualTo(3);

        verify(rounds).resumeIf(ROUND_ID, AWAITING_GATE);
        verify(events).publishEvent(new ReviewRoundTransitionedEvent(
                TASK_ID, ROUND_ID, PAUSED, AWAITING_GATE, "runtime stopped"));
    }

    @Test
    void ambiguousLegacyPauseCannotResume()
    {
        ReviewRound paused = round(PAUSED, null, 0);
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(paused), Optional.of(paused));

        assertThatThrownBy(() -> machine.resume(ROUND_ID, "resume"))
                .hasMessageContaining("no unambiguous pause checkpoint");
        verify(rounds, never()).resumeIf(any(), any());
    }

    @Test
    void sealClosesPostedHistoryWithoutRunningThePublishGate()
    {
        ReviewRound posted = round(POSTED, null, 0);
        ReviewRound closed = round(CLOSED, null, 0);
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(posted), Optional.of(posted), Optional.of(closed));
        when(rounds.sealIf(eq(ROUND_ID), eq(POSTED), any())).thenReturn(true);

        assertThat(machine.seal(ROUND_ID, "task terminal").status()).isEqualTo(CLOSED);

        verify(rounds).sealIf(eq(ROUND_ID), eq(POSTED), any());
        verify(events).publishEvent(new ReviewRoundTransitionedEvent(
                TASK_ID, ROUND_ID, POSTED, CLOSED, "task terminal"));
        verify(runs, never()).transitionInCommand(any(), any(), any(), any());
    }

    @Test
    void publicIntentRejectsAnAmbientTransaction()
    {
        when(rounds.findById(ROUND_ID)).thenReturn(Optional.of(round(TRIAGING, null, 0)));

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                assertThatThrownBy(() -> machine.seal(ROUND_ID, "terminal"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ambient transaction"));
    }

    @Test
    void illegalParkFromPostedIsRejectedBeforeAnyWrite()
    {
        ReviewRound posted = round(POSTED, null, 0);
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(posted), Optional.of(posted));

        assertThatThrownBy(() -> machine.park(ROUND_ID, "not legal"))
                .isInstanceOf(IllegalTransitionException.class);
        verify(rounds, never()).parkIf(any(), any());
    }

    @Test
    void ownedTerminalReviewTurnQueuesItsRunAndBecomesConclusionReady()
    {
        ReviewRound active = round(TRIAGING, null, 0);
        ThreadTurn turn = turn("turn-1", "brain-review", ThreadTurnStatus.COMPLETED);
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(active), Optional.of(active));
        when(turns.findTurnById("turn-1")).thenReturn(Optional.of(turn));
        when(turns.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(active, "brain-review")))
                .thenReturn(Optional.of("turn-1"));
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(AgentRun.STATUS_RUNNING)));
        when(runs.transitionInCommand(
                TASK_ID, RUN_ID, AgentRun.STATUS_QUEUED, "review_attempt_finished"))
                .thenReturn(run(AgentRun.STATUS_QUEUED));

        ReviewRoundStateMachine.OwnedTurnEnded ended =
                machine.recordOwnedTurnEnded(ROUND_ID, "turn-1");

        assertThat(ended.action())
                .isEqualTo(ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);
    }

    @Test
    void staleTerminalTurnCannotAdvanceTheCurrentKick()
    {
        ReviewRound active = round(TRIAGING, null, 2);
        ThreadTurn turn = turn("turn-old", "brain-review", ThreadTurnStatus.COMPLETED);
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(active), Optional.of(active));
        when(turns.findTurnById("turn-old")).thenReturn(Optional.of(turn));
        when(turns.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(active, "brain-review")))
                .thenReturn(Optional.of("turn-current"));

        ReviewRoundStateMachine.OwnedTurnEnded ended =
                machine.recordOwnedTurnEnded(ROUND_ID, "turn-old");

        assertThat(ended.action())
                .isEqualTo(ReviewRoundStateMachine.OwnedTurnAction.NONE);
        verify(runs, never()).transitionInCommand(any(), any(), any(), any());
    }

    @Test
    void gateAuthorizationIsRevisionAndFingerprintFenced()
    {
        ReviewRound waiting = round(AWAITING_GATE, null, 0);
        ReviewRound authorized = withToken(waiting, "gate-token");
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(waiting), Optional.of(waiting), Optional.of(authorized));
        when(tasks.findTaskById(TASK_ID))
                .thenReturn(Optional.of(task(TaskPhase.AWAITING_REMOTE_REVIEW, TaskStatus.IN_REVIEW)));
        when(runs.findById(RUN_ID))
                .thenReturn(Optional.of(run(AgentRun.STATUS_AWAITING_GATE)));
        when(rounds.authorizeGateIf(ROUND_ID, 0, "sha256:abc", "gate-token"))
                .thenReturn(true);

        ReviewRound result = machine.authorizeGate(
                ROUND_ID, "gate-token", 0, "sha256:abc", "user_approved");

        assertThat(result.activeGateToken()).isEqualTo("gate-token");
        verify(events).publishEvent(new RoundGateAuthorizedEvent(
                TASK_ID, ROUND_ID, "gate-token"));
    }

    @Test
    void conclusionCarriesTheExactPersistedKickAndTerminalTurnFence()
    {
        ReviewRound active = withVerdict(round(TRIAGING, null, 2)
                        .withStats(new ReviewRound.ReviewRoundStats(0, 0, 0, 3)),
                ReviewRound.VERDICT_APPROVED);
        ReviewRound gated = copy(active, AWAITING_GATE, active.iteration(),
                active.codeFingerprint(), active.kickAttempt());
        ThreadTurn turn = turn("turn-1", "brain-review", ThreadTurnStatus.COMPLETED);
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(active), Optional.of(active), Optional.of(gated));
        when(turns.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(active, "brain-review")))
                .thenReturn(Optional.of(turn.id()));
        when(turns.findTurnById(turn.id())).thenReturn(Optional.of(turn));
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(AgentRun.STATUS_QUEUED)));
        when(tasks.findTaskById(TASK_ID))
                .thenReturn(Optional.of(task(TaskPhase.AWAITING_REMOTE_REVIEW, TaskStatus.IN_REVIEW)));
        when(fingerprints.fingerprint(Path.of("/tmp/worktree"))).thenReturn("sha256:abc");
        when(rounds.concludeIf(
                eq(ROUND_ID), eq(TRIAGING), eq(AWAITING_GATE), any(), any(),
                eq(ReviewRound.VERDICT_APPROVED), any(), isNull()))
                .thenReturn(true);
        when(runs.transitionInCommand(
                TASK_ID, RUN_ID, AgentRun.STATUS_AWAITING_GATE, "drafts_ready"))
                .thenReturn(run(AgentRun.STATUS_AWAITING_GATE));

        machine.concludeBrain(ROUND_ID, turn.id());

        verify(rounds).concludeIf(
                ROUND_ID, TRIAGING, AWAITING_GATE,
                new ReviewRoundStore.AttemptFence(
                        active.iteration(), active.gateRevision(), active.kickAttempt(),
                        turn.id(), ReviewRoundStateMachine.kickKey(active, "brain-review")),
                ReviewRound.ReviewRoundStats.empty(), ReviewRound.VERDICT_APPROVED,
                NOW, null);
    }

    @Test
    void finishAddressingRequiresTheExactAttemptBoundGreenClaim()
    {
        ReviewRound addressing = round(ADDRESSING, null, 2);
        ReviewRound triaging = copy(
                addressing, TRIAGING, addressing.iteration() + 1, "sha256:def", 0);
        ThreadTurn turn = turn("turn-1", "brain-review-fix", ThreadTurnStatus.COMPLETED);
        String claimKey = "review-round:" + TASK_ID + ':' + ROUND_ID
                + ":turn-1:sha256:def";
        ValidationClaim claim = greenClaim(
                claimKey, "review-round", ROUND_ID, "sha256:def");
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(addressing), Optional.of(addressing), Optional.of(triaging));
        when(turns.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")))
                .thenReturn(Optional.of(turn.id()));
        when(turns.findTurnById(turn.id())).thenReturn(Optional.of(turn));
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(AgentRun.STATUS_QUEUED)));
        when(validations.findByClaimKey(claimKey)).thenReturn(Optional.of(claim));
        when(tasks.findTaskById(TASK_ID))
                .thenReturn(Optional.of(task(TaskPhase.AWAITING_REMOTE_REVIEW, TaskStatus.IN_REVIEW)));
        when(fingerprints.fingerprint(Path.of("/tmp/worktree"))).thenReturn("sha256:def");
        when(rounds.finishAddressingIf(eq(ROUND_ID), any(), eq(claimKey), eq("sha256:def")))
                .thenReturn(true);

        assertThat(machine.finishAddressing(ROUND_ID, turn.id(), claimKey).status())
                .isEqualTo(TRIAGING);

        verify(rounds).finishAddressingIf(
                ROUND_ID,
                new ReviewRoundStore.AttemptFence(
                        addressing.iteration(), addressing.gateRevision(),
                        addressing.kickAttempt(), turn.id(),
                        ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")),
                claimKey, "sha256:def");
    }

    @Test
    void finishAddressingRejectsAnUnboundGreenClaimBeforeLifecycleMutation()
    {
        ReviewRound addressing = round(ADDRESSING, null, 0);
        ThreadTurn turn = turn("turn-1", "brain-review-fix", ThreadTurnStatus.COMPLETED);
        String claimKey = "dev-round:" + TASK_ID + ":sha256:def";
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(addressing), Optional.of(addressing));
        when(turns.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")))
                .thenReturn(Optional.of(turn.id()));
        when(turns.findTurnById(turn.id())).thenReturn(Optional.of(turn));
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(AgentRun.STATUS_QUEUED)));
        when(validations.findByClaimKey(claimKey)).thenReturn(Optional.of(
                greenClaim(claimKey, "dev-round", null, "sha256:def")));

        assertThatThrownBy(() -> machine.finishAddressing(ROUND_ID, turn.id(), claimKey))
                .hasMessageContaining("not bound to the completed round attempt");

        verify(rounds, never()).finishAddressingIf(any(), any(), any(), any());
    }

    @Test
    void finishAddressingRejectsGreenEvidenceFromAnotherAttempt()
    {
        ReviewRound addressing = round(ADDRESSING, null, 0);
        ThreadTurn turn = turn("turn-1", "brain-review-fix", ThreadTurnStatus.COMPLETED);
        String claimKey = "review-round:" + TASK_ID + ':' + ROUND_ID
                + ":stale-turn:sha256:def";
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(addressing), Optional.of(addressing));
        when(turns.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")))
                .thenReturn(Optional.of(turn.id()));
        when(turns.findTurnById(turn.id())).thenReturn(Optional.of(turn));
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(AgentRun.STATUS_QUEUED)));
        when(validations.findByClaimKey(claimKey)).thenReturn(Optional.of(
                greenClaim(claimKey, "review-round", ROUND_ID, "sha256:def")));

        assertThatThrownBy(() -> machine.finishAddressing(ROUND_ID, turn.id(), claimKey))
                .hasMessageContaining("not bound to the completed round attempt");

        verify(rounds, never()).finishAddressingIf(any(), any(), any(), any());
    }

    @Test
    void gateInvalidationValidatesClaimIdentityBeforeMutatingAnything()
    {
        ReviewRound waiting = withToken(round(AWAITING_GATE, null, 0), "gate-token");
        String observed = "sha256:def";
        String claimKey = "review-gate-revalidation:" + TASK_ID + ':' + ROUND_ID
                + ":0:1:" + observed;
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(waiting), Optional.of(waiting));
        when(tasks.findTaskById(TASK_ID))
                .thenReturn(Optional.of(task(TaskPhase.AWAITING_REMOTE_REVIEW, TaskStatus.IN_REVIEW)));
        when(runs.findById(RUN_ID))
                .thenReturn(Optional.of(run(AgentRun.STATUS_AWAITING_GATE)));
        when(fingerprints.fingerprint(Path.of("/tmp/worktree"))).thenReturn(observed);
        when(validations.findByClaimKey(claimKey)).thenReturn(Optional.of(
                greenClaim(claimKey, "dev-round", null, observed)));

        assertThatThrownBy(() -> machine.invalidateGateFingerprint(
                ROUND_ID, "gate-token", observed))
                .hasMessageContaining("claim identity is already occupied");

        verify(rounds, never()).invalidateGateFingerprintIf(any(), any());
        verify(validations, never()).insertClaim(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void gateInvalidationReplayRejectsACancelledClaimInsteadOfSilentlyWedging()
    {
        ReviewRound invalidated = round(TRIAGING, null, 1);
        String observed = "sha256:def";
        String claimKey = "review-gate-revalidation:" + TASK_ID + ':' + ROUND_ID
                + ":0:1:" + observed;
        ValidationClaim cancelled = new ValidationClaim(
                1, claimKey, TASK_ID, "review-gate-revalidation", ROUND_ID,
                observed, null, null, NOW.minusSeconds(10), null, null, null,
                NOW, null, null, null);
        when(rounds.findById(ROUND_ID))
                .thenReturn(Optional.of(invalidated), Optional.of(invalidated));
        when(tasks.findTaskById(TASK_ID))
                .thenReturn(Optional.of(task(TaskPhase.AWAITING_REMOTE_REVIEW, TaskStatus.IN_REVIEW)));
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(AgentRun.STATUS_QUEUED)));
        when(fingerprints.fingerprint(Path.of("/tmp/worktree"))).thenReturn(observed);
        when(validations.findLatestByRoundAndContext(
                ROUND_ID, "review-gate-revalidation"))
                .thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> machine.invalidateGateFingerprint(
                ROUND_ID, "revoked-token", observed))
                .hasMessageContaining("claim identity is already occupied");

        verify(rounds, never()).invalidateGateFingerprintIf(any(), any());
    }

    private static ReviewRound round(
            ReviewRoundState state, ReviewRoundState pausedFrom, int kickAttempt)
    {
        return new ReviewRound(
                ROUND_ID, TASK_ID, 1, List.of(), state,
                ReviewRound.ReviewRoundStats.empty(), RUN_ID, NOW,
                state == AWAITING_GATE || state == POSTED ? NOW : null,
                state == POSTED ? NOW : null,
                ReviewRound.ORIGIN_EXTERNAL, null, 1, 5, pausedFrom,
                "sha256:abc", 0, kickAttempt, 0, null,
                state == CLOSED ? NOW : null);
    }

    private static AgentRun run(String status)
    {
        return run(RUN_ID, status);
    }

    private static AgentRun run(String runId, String status)
    {
        return new AgentRun(
                runId, TASK_ID, AgentRun.KIND_REVIEW_ROUND, null,
                null, ROUND_ID, null, status, 1, 5, null, null, NOW, null);
    }

    private static ThreadTurn turn(
            String id, String source, ThreadTurnStatus status)
    {
        return new ThreadTurn(
                id, "thread-1", TASK_ID, ThreadResourceLane.CLI, status,
                "prompt", NOW, NOW, NOW, NOW, null,
                TurnInitiator.unattended(source), "stage-1", ThreadScope.STAGE, RUN_ID);
    }

    private static Task task(TaskPhase phase, TaskStatus status)
    {
        return new Task(
                TASK_ID, "thread-1", 1, status, "dev/x", "/tmp/worktree",
                "main", "/tmp/repo", null, null, null, null, null,
                "DEVELOP", null, null, 0, 0, 0, null, NOW, null, null,
                null, null, null, null, phase, null, 0, null);
    }

    private static ReviewRound withToken(ReviewRound round, String token)
    {
        return new ReviewRound(
                round.id(), round.taskId(), round.idx(), round.reviewers(), round.status(),
                round.stats(), round.runId(), round.openedAt(), round.gatedAt(), round.postedAt(),
                round.origin(), round.brainVerdict(), round.iteration(), round.budget(),
                round.pausedFrom(), round.codeFingerprint(), round.enqueueFailures(),
                round.kickAttempt(), round.gateRevision(), token, round.closedAt());
    }

    private static ReviewRound withVerdict(ReviewRound round, String verdict)
    {
        return new ReviewRound(
                round.id(), round.taskId(), round.idx(), round.reviewers(), round.status(),
                round.stats(), round.runId(), round.openedAt(), round.gatedAt(), round.postedAt(),
                round.origin(), verdict, round.iteration(), round.budget(), round.pausedFrom(),
                round.codeFingerprint(), round.enqueueFailures(), round.kickAttempt(),
                round.gateRevision(), round.activeGateToken(), round.closedAt());
    }

    private static ReviewRound copy(
            ReviewRound round,
            ReviewRoundState state,
            int iteration,
            String fingerprint,
            int kickAttempt)
    {
        return new ReviewRound(
                round.id(), round.taskId(), round.idx(), round.reviewers(), state,
                round.stats(), round.runId(), round.openedAt(),
                state == AWAITING_GATE ? NOW : null, round.postedAt(), round.origin(),
                round.brainVerdict(), iteration, round.budget(), null, fingerprint,
                0, kickAttempt, round.gateRevision(), round.activeGateToken(),
                state == CLOSED ? NOW : null);
    }

    private static ValidationClaim greenClaim(
            String claimKey, String context, String roundId, String fingerprint)
    {
        return new ValidationClaim(
                1, claimKey, TASK_ID, context, roundId, fingerprint,
                null, null, NOW.minusSeconds(10), NOW, true, "[]",
                null, null, null, null);
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
        protected void doBegin(Object transaction, TransactionDefinition definition)
        {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status)
        {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status)
        {
        }
    }
}
