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
package com.bytequay.app.service.localpr;

import com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane;
import com.bytequay.app.developmentflow.execution.LegacySagaCapacity;
import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPushAuthorization;
import com.bytequay.app.domain.TaskPushEffect;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskPushStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestTaskPushSaga
{
    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final Path WORKTREE = Path.of("/tmp/worktree");

    private final PRService prs = mock(PRService.class);
    private final TaskStore tasks = mock(TaskStore.class);
    private final WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final PatResolver pats = mock(PatResolver.class);
    private final ReviewRoundStore rounds = mock(ReviewRoundStore.class);
    private final CodeFingerprints fingerprints = mock(CodeFingerprints.class);
    private final InMemoryPushStore pushes = new InMemoryPushStore();
    private final PlatformTransactionManager transactions = new TestTransactionManager();
    private final TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
    private final TaskPhaseMachine taskMachine = mock(TaskPhaseMachine.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final LocalReviewSubmissionStore submissions = mock(LocalReviewSubmissionStore.class);
    private final LegacySagaCapacity capacity = mock(LegacySagaCapacity.class);
    private final LegacySagaCapacity.Attempt capacityAttempt =
            mock(LegacySagaCapacity.Attempt.class);
    private final TaskPushSaga saga = new TaskPushSaga(
            prs, tasks, watchedRepos, git, pullRequests, pats, rounds, fingerprints,
            pushes, commands, taskMachine, notifications, submissions, capacity,
            new ObjectMapper());

    @BeforeEach
    void setUp()
            throws Exception
    {
        PR pr = localPr();
        Task task = task();
        ReviewRound round = approvedRound();
        when(prs.findById(pr.id())).thenReturn(Optional.of(pr));
        when(prs.comments(pr.id())).thenReturn(List.of());
        when(prs.checks(pr.id())).thenReturn(List.of());
        when(tasks.findTaskById(task.id())).thenReturn(Optional.of(task));
        when(taskMachine.spendLocalShipAuthorizationInCommand(anyString(), any()))
                .thenReturn(true);
        when(rounds.findByTask(task.id())).thenReturn(List.of(round));
        when(rounds.findById(round.id())).thenReturn(Optional.of(round));
        when(watchedRepos.findAll()).thenReturn(List.of());
        when(git.headSha(WORKTREE)).thenReturn("head-1");
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin"))
                .thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(fingerprints.fingerprint(WORKTREE)).thenReturn("fingerprint-1");
        when(pats.resolve("acme/widget")).thenReturn("pat");
        when(capacity.tryAcquire(anyString(), anyString(), any()))
                .thenReturn(Optional.of(capacityAttempt));
        when(pullRequests.listPullRequests(any(), any(), any())).thenReturn(List.of());
        when(pullRequests.createPullRequest(any(), any(), any())).thenReturn(remotePr());
        PR pushed = pr.withRemote(
                "acme/widget", 42, "https://github.com/acme/widget/pull/42", NOW)
                .withStatus(PR.STATUS_REMOTE_DRAFTED, NOW);
        when(prs.recordPushInCommand(
                pr.id(), "acme/widget", 42, "https://github.com/acme/widget/pull/42"))
                .thenReturn(pushed);
        when(prs.updateAuthor(pr.id(), "@you")).thenReturn(pushed);
    }

    @Test
    void legacyPushSagaNeverClaimsAV2Task()
    {
        when(tasks.isV2Task("task-1")).thenReturn(true);

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("typed publish runtime");

        verifyNoInteractions(git);
        assertThat(pushes.findActiveByTask("task-1")).isEmpty();
    }

    @Test
    void pushClaimsBeforeExternalIoAndFinalizesAfterDurableEvidence()
            throws Exception
    {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(git).push(WORKTREE);
        when(pullRequests.listPullRequests(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of();
        });
        when(pullRequests.createPullRequest(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return remotePr();
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(taskMachine).finalizeLocalShipInCommand(
                "task-1", Actor.AGENT, "local_pr_pushed");

        saga.push("pr-1", false);

        verify(git).push(WORKTREE);
        verify(pullRequests).createPullRequest(any(), eq(new RepoRef("acme", "widget")), any());
        verify(taskMachine).finalizeLocalShipInCommand(
                "task-1", Actor.AGENT, "local_pr_pushed");
        assertThat(pushes.findActiveByTask("task-1")).isEmpty();
        assertThat(pushes.findEffects(pushes.authorization.token()))
                .allMatch(TaskPushEffect::completed)
                .extracting(TaskPushEffect::attempts)
                .containsExactly(1, 1);
    }

    @Test
    void capacityDenialLeavesTheExactEffectPendingWithoutGitOrGithubIo()
            throws Exception
    {
        when(capacity.tryAcquire(anyString(), anyString(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("awaiting retry or recovery");
        String token = pushes.authorization.token();
        assertThat(pushes.findEffects(token))
                .allMatch(effect -> effect.status() == TaskPushEffect.Status.PENDING)
                .allMatch(effect -> effect.attempts() == 0);

        clearInvocations(git, fingerprints, pullRequests);
        saga.drive(token);
        saga.drive(token);

        verifyNoInteractions(git, fingerprints, pullRequests);
        assertThat(pushes.findEffects(token))
                .allMatch(effect -> effect.status() == TaskPushEffect.Status.PENDING)
                .allMatch(effect -> effect.attempts() == 0);
        verify(capacity, times(3)).tryAcquire(
                "task-1", "legacy-task-push-effect:1",
                Set.of(CapacityLane.GITHUB));
    }

    @Test
    void lostCapacityLeavesTheClaimInFlightForRemoteProbeRecovery()
            throws Exception
    {
        doAnswer(ignored -> {
            when(capacityAttempt.leaseLost()).thenReturn(true);
            throw new IOException("capacity owner interrupted");
        }).when(git).push(WORKTREE);

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("awaiting retry or recovery");

        TaskPushEffect effect = pushes.findEffect(
                pushes.authorization.token(), TaskPushSaga.EFFECT_PUSH_BRANCH).orElseThrow();
        assertThat(effect.status()).isEqualTo(TaskPushEffect.Status.IN_FLIGHT);
        assertThat(effect.claimOwner()).isNotBlank();
        verify(prs, never()).recordPushFailureInCommand(any(), any(), any());
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void sweepRecoversALostAutomaticPushWakeup()
            throws Exception
    {
        when(tasks.listByPhases(List.of(TaskPhase.AWAITING_PUSH), 50))
                .thenReturn(List.of(task()));
        when(tasks.isAutoMerge("task-1")).thenReturn(true);
        when(prs.findByTask("task-1")).thenReturn(Optional.of(localPr()));

        saga.reconcileActive();

        verify(git).push(WORKTREE);
        verify(taskMachine).finalizeLocalShipInCommand(
                "task-1", Actor.AGENT, "local_pr_pushed");
    }

    @Test
    void automaticCapParksBeforeAnyEffectIsClaimed()
    {
        when(taskMachine.spendLocalShipAuthorizationInCommand(
                "task-1", Actor.AGENT)).thenReturn(false);

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(taskMachine).spendLocalShipAuthorizationInCommand(
                "task-1", Actor.AGENT);
        assertThat(pushes.authorization).isNull();
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    void staleReviewBasisStartsFreshValidationBeforeAuthorization()
            throws Exception
    {
        when(fingerprints.fingerprint(WORKTREE)).thenReturn("changed");

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("revalidation started");

        verify(taskMachine).invalidateLocalShipInCommand(
                "task-1", Actor.AGENT, "local_push_fingerprint_changed");
        assertThat(pushes.authorization).isNull();
        verify(git, never()).push(any());
    }

    @Test
    void zeroAttemptFingerprintChangeRevokesAndStartsFreshValidation()
            throws Exception
    {
        when(fingerprints.fingerprint(WORKTREE))
                .thenReturn("fingerprint-1", "changed");

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("reviewed code changed");

        verify(taskMachine).invalidateLocalShipInCommand(
                "task-1", Actor.AGENT, "local_push_fingerprint_changed");
        assertThat(pushes.findActiveByTask("task-1")).isEmpty();
        assertThat(pushes.findEffects(pushes.authorization.token()))
                .allMatch(effect -> effect.attempts() == 0);
        verify(git, never()).push(any());
    }

    @Test
    void aPostEffectFingerprintChangeParksAndRetainsTheCursor()
    {
        when(fingerprints.fingerprint(WORKTREE))
                .thenReturn(
                        "fingerprint-1", "fingerprint-1",
                        "fingerprint-1", "changed");

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("reviewed code changed");

        verify(taskMachine).parkOperationalInCommand(
                "task-1", Actor.AGENT, "local_push_fingerprint_changed");
        verify(taskMachine, never()).finalizeLocalShipInCommand(any(), any(), any());
        assertThat(pushes.findActiveByTask("task-1")).isPresent();
        assertThat(pushes.findEffects(pushes.authorization.token()))
                .allMatch(TaskPushEffect::completed);
    }

    @Test
    void createRaceAdoptsTheExactPullRequestThatAppearsAfterFailure()
    {
        when(pullRequests.listPullRequests(any(), any(), any()))
                .thenReturn(List.of(), List.of(remotePr()));
        when(pullRequests.createPullRequest(any(), any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "already exists"));

        saga.push("pr-1", false);

        verify(pullRequests).createPullRequest(any(), any(), any());
        verify(prs).recordPushInCommand(
                "pr-1", "acme/widget", 42, "https://github.com/acme/widget/pull/42");
    }

    @Test
    void aDeferredRetryDoesNotReportThePushAsComplete()
            throws Exception
    {
        doThrow(new IOException("offline")).when(git).push(WORKTREE);

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("git push failed");

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("awaiting retry or recovery");

        verify(git).push(WORKTREE);
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
        verify(prs, never()).recordPushFailureInCommand(any(), any(), any());
        assertThat(pushes.findActiveByTask("task-1")).isPresent();
    }

    @Test
    void aPermanentFailureRecordsItsDurableResultBeforeParking()
    {
        when(pullRequests.listPullRequests(any(), any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "credentials rejected"));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(pushes.findEffect(
                    pushes.authorization.token(), TaskPushSaga.EFFECT_ENSURE_PULL_REQUEST)
                    .orElseThrow().status()).isEqualTo(TaskPushEffect.Status.PERMANENT_FAILED);
            return null;
        }).when(prs).recordPushFailureInCommand(
                eq("pr-1"), eq(TaskPushSaga.EFFECT_ENSURE_PULL_REQUEST), anyString());

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("credentials rejected");

        verify(prs).recordPushFailureInCommand(
                eq("pr-1"), eq(TaskPushSaga.EFFECT_ENSURE_PULL_REQUEST),
                contains("credentials rejected"));
        verify(taskMachine).parkOperationalInCommand(
                "task-1", Actor.AGENT, "local_push_failed");
    }

    @Test
    void anExpiredPushClaimProbesTheRemoteHeadBeforeContinuing()
            throws Exception
    {
        doAnswer(invocation -> {
            pushes.expireClaim(TaskPushSaga.EFFECT_PUSH_BRANCH);
            throw new AssertionError("simulated process crash");
        }).when(git).push(WORKTREE);

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(AssertionError.class)
                .hasMessage("simulated process crash");
        when(git.remoteHeadSha(WORKTREE, "origin", "feature/x"))
                .thenReturn(Optional.of("head-1"));

        saga.drive(pushes.authorization.token());

        verify(git, times(1)).push(WORKTREE);
        verify(git).remoteHeadSha(WORKTREE, "origin", "feature/x");
        assertThat(pushes.findActiveByTask("task-1")).isEmpty();
    }

    @Test
    void matchingRemoteOpenEventCompletesTheActiveSagaWithoutRepeatingIo()
            throws Exception
    {
        doAnswer(invocation -> {
            throw new AssertionError("simulated crash after remote push");
        }).when(git).push(WORKTREE);
        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(AssertionError.class)
                .hasMessage("simulated crash after remote push");
        when(git.remoteHeadSha(WORKTREE, "origin", "feature/x"))
                .thenReturn(Optional.of("head-1"));
        when(pullRequests.listPullRequests(any(), any(), any()))
                .thenReturn(List.of(remotePr()));

        assertThat(saga.adoptRemotePullRequest(
                "task-1", "acme/widget", 42,
                "https://github.com/acme/widget/pull/42")).isTrue();

        verify(git, times(1)).push(WORKTREE);
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
        verify(taskMachine).finalizeLocalShipInCommand(
                "task-1", Actor.AGENT, "local_pr_pushed");
        assertThat(pushes.findActiveByTask("task-1")).isEmpty();
        assertThat(pushes.findEffects(pushes.authorization.token()))
                .allMatch(TaskPushEffect::completed)
                .extracting(TaskPushEffect::attempts)
                .containsExactly(1, 1);
    }

    @Test
    void startupAdoptsAnExactRemotePrWithoutRepeatingExternalEffects()
            throws Exception
    {
        PR orphan = localPr()
                .withRemote(
                        "acme/widget", 42,
                        "https://github.com/acme/widget/pull/42", NOW)
                .withStatus(PR.STATUS_REMOTE_DRAFTED, NOW);
        pushes.orphanedRemotePullRequestTaskIds = List.of("task-1");
        when(prs.findByTask("task-1")).thenReturn(Optional.of(orphan));
        when(prs.findById("pr-1")).thenReturn(Optional.of(orphan));
        when(git.remoteHeadSha(WORKTREE, "origin", "feature/x"))
                .thenReturn(Optional.of("head-1"));
        when(pullRequests.listPullRequests(any(), any(), any()))
                .thenReturn(List.of(remotePr()));

        saga.reconcileActive();

        verify(git, never()).push(any());
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
        verify(prs, never()).recordPushInCommand(any(), any(), anyInt(), any());
        verify(taskMachine).finalizeLocalShipInCommand(
                "task-1", Actor.WEBHOOK, "legacy_remote_pr_adopted");
        assertThat(pushes.findActiveByTask("task-1")).isEmpty();
        assertThat(pushes.findEffects(pushes.authorization.token()))
                .allMatch(TaskPushEffect::completed)
                .extracting(TaskPushEffect::attempts)
                .containsExactly(1, 1);
    }

    @Test
    void exactRecoveryRequestAddsOneAttemptAndResumesTheRetainedCursor()
            throws Exception
    {
        when(pullRequests.listPullRequests(any(), any(), any()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "credentials rejected"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("credentials rejected");
        Task parked = task()
                .withPhase(TaskPhase.NEEDS_ATTENTION)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(parked));

        TaskPushSaga.RecoveryPlan plan = saga.prepareRecovery("task-1", 1)
                .orElseThrow();
        when(tasks.recoveryRequest("task-1")).thenReturn(Optional.of(
                new TaskRecoveryRequest(
                        "recovery-1", TaskRecoveryRequest.KIND_EXTERNAL_SAGA,
                        saga.recoveryPayload(plan), NOW)));

        commands.executeVoid("task-1", () -> saga.resumeExternalSagaInCommand(plan));

        TaskPushEffect rearmed = pushes.findEffect(
                plan.token(), TaskPushSaga.EFFECT_ENSURE_PULL_REQUEST).orElseThrow();
        assertThat(rearmed.status()).isEqualTo(TaskPushEffect.Status.RETRYABLE_FAILED);
        assertThat(rearmed.attemptLimit()).isEqualTo(rearmed.attempts() + 1);

        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(task()));
        saga.drive(plan.token());

        verify(git, times(1)).push(WORKTREE);
        verify(pullRequests).createPullRequest(any(), any(), any());
        assertThat(pushes.findActiveByTask("task-1")).isEmpty();
        assertThat(pushes.findEffect(
                plan.token(), TaskPushSaga.EFFECT_ENSURE_PULL_REQUEST).orElseThrow().attempts())
                .isEqualTo(2);
    }

    @Test
    void recoveryVerifierIgnoresRequestsWithoutAnActivePushAuthorization()
            throws Exception
    {
        when(pullRequests.listPullRequests(any(), any(), any()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "credentials rejected"));

        assertThatThrownBy(() -> saga.push("pr-1", false))
                .isInstanceOf(ResponseStatusException.class);
        Task parked = task()
                .withPhase(TaskPhase.NEEDS_ATTENTION)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(parked));
        TaskPushSaga.RecoveryPlan plan = saga.prepareRecovery("task-1", 1)
                .orElseThrow();
        when(tasks.recoveryRequest("task-2")).thenReturn(Optional.of(
                new TaskRecoveryRequest(
                        "recovery-2", TaskRecoveryRequest.KIND_EXTERNAL_SAGA,
                        saga.recoveryPayload(plan), NOW)));

        assertThat(saga.verifyRecoveryRequest("task-2")).isEmpty();
    }

    private static PR localPr()
    {
        return PR.create(
                "pr-1", "task-1", "feature/x", "main", "Add cache", "desc", NOW)
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW);
    }

    private static Task task()
    {
        return new Task(
                "task-1", "thread-1", 1L, TaskStatus.AWAITING_REVIEW,
                "feature/x", WORKTREE.toString(), "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null,
                null, TaskPhase.AWAITING_PUSH, null, 0, null, null);
    }

    private static ReviewRound approvedRound()
    {
        return new ReviewRound(
                "round-1", "task-1", 1, List.of(), ReviewRoundState.CLOSED,
                ReviewRound.ReviewRoundStats.empty(), "run-1", NOW, null, null,
                ReviewRound.ORIGIN_BRAIN, ReviewRound.VERDICT_APPROVED, 1, 5,
                null, "fingerprint-1", 0, 0, 0, null, NOW);
    }

    private static PullRequest remotePr()
    {
        return new PullRequest(
                1L, "acme/widget", 42, "Add cache", "you",
                "https://github.com/acme/widget/pull/42", NOW, NOW,
                PullRequest.Origin.AUTHORED, List.of(), Map.of(), true,
                null, null, null, List.of(), null, 0, 0, 0, null,
                "open", null, null, null, null, null, Map.of(), null, null, "feature/x");
    }

    private static final class InMemoryPushStore
            implements TaskPushStore
    {
        private TaskPushAuthorization authorization;
        private final Map<String, TaskPushEffect> effects = new LinkedHashMap<>();
        private List<String> orphanedRemotePullRequestTaskIds = List.of();

        private void expireClaim(String key)
        {
            TaskPushEffect effect = effects.get(key);
            effects.put(key, copy(
                    effect, effect.status(), effect.attempts(), effect.firstClaimedAt(),
                    effect.lastClaimedAt(), effect.claimOwner(), Instant.EPOCH,
                    effect.lastErrorClass(), effect.lastError(), effect.nextAttemptAt(),
                    effect.evidenceJson(), effect.completedAt()));
        }

        @Override
        public void insert(
                TaskPushAuthorization value, List<String> effectKeys, int attemptLimit)
        {
            authorization = value;
            long id = 1;
            for (String key : effectKeys) {
                effects.put(key, new TaskPushEffect(
                        id++, value.token(), key, TaskPushEffect.Status.PENDING,
                        0, attemptLimit, null, null, null, null,
                        null, null, null, null, null));
            }
        }

        @Override
        public Optional<TaskPushAuthorization> findAuthorization(String token)
        {
            return authorization == null || !authorization.token().equals(token)
                    ? Optional.empty() : Optional.of(authorization);
        }

        @Override
        public Optional<TaskPushAuthorization> findActiveByTask(String taskId)
        {
            return authorization != null && authorization.taskId().equals(taskId)
                    && authorization.active() ? Optional.of(authorization) : Optional.empty();
        }

        @Override
        public List<TaskPushAuthorization> findRecoverable(Instant now, int limit)
        {
            return authorization != null && authorization.active()
                    ? List.of(authorization) : List.of();
        }

        @Override
        public List<String> findOrphanedRemotePullRequestTaskIds(int limit)
        {
            return orphanedRemotePullRequestTaskIds.stream().limit(limit).toList();
        }

        @Override
        public List<TaskPushEffect> findEffects(String token)
        {
            return new ArrayList<>(effects.values());
        }

        @Override
        public Optional<TaskPushEffect> findEffect(String token, String effectKey)
        {
            return Optional.ofNullable(effects.get(effectKey));
        }

        @Override
        public boolean claimEffect(
                String token, String key, String owner, Instant now, Instant leaseUntil)
        {
            TaskPushEffect effect = effects.get(key);
            if (effect == null || effect.completed() || effect.exhausted()) {
                return false;
            }
            if (effect.status() == TaskPushEffect.Status.PERMANENT_FAILED
                    || effect.status() == TaskPushEffect.Status.RETRYABLE_FAILED
                            && effect.nextAttemptAt() != null
                            && effect.nextAttemptAt().isAfter(now)
                    || effect.status() == TaskPushEffect.Status.IN_FLIGHT
                            && effect.leaseUntil() != null
                            && !effect.leaseUntil().isBefore(now)) {
                return false;
            }
            effects.put(key, copy(
                    effect, TaskPushEffect.Status.IN_FLIGHT, effect.attempts() + 1,
                    effect.firstClaimedAt() == null ? now : effect.firstClaimedAt(),
                    now, owner, leaseUntil, null, null, null, null, null));
            return true;
        }

        @Override
        public boolean completeEffect(
                String token, String key, String owner, String evidenceJson, Instant at)
        {
            TaskPushEffect effect = effects.get(key);
            if (effect == null || effect.status() != TaskPushEffect.Status.IN_FLIGHT
                    || !owner.equals(effect.claimOwner())) {
                return false;
            }
            effects.put(key, copy(
                    effect, TaskPushEffect.Status.COMPLETED, effect.attempts(),
                    effect.firstClaimedAt(), effect.lastClaimedAt(), null, null,
                    null, null, null, evidenceJson, at));
            return true;
        }

        @Override
        public boolean completeObservedEffect(
                String token, String key, String evidenceJson, Instant at)
        {
            TaskPushEffect effect = effects.get(key);
            if (authorization == null || !authorization.active()
                    || effect == null || effect.completed()) {
                return false;
            }
            effects.put(key, copy(
                    effect, TaskPushEffect.Status.COMPLETED,
                    Math.max(1, effect.attempts()),
                    effect.firstClaimedAt() == null ? at : effect.firstClaimedAt(),
                    effect.lastClaimedAt() == null ? at : effect.lastClaimedAt(),
                    null, null, null, null, null, evidenceJson, at));
            return true;
        }

        @Override
        public boolean failEffect(
                String token,
                String key,
                String owner,
                TaskPushEffect.Status status,
                String errorClass,
                String error,
                Instant nextAttemptAt)
        {
            TaskPushEffect effect = effects.get(key);
            if (effect == null || effect.status() != TaskPushEffect.Status.IN_FLIGHT
                    || !owner.equals(effect.claimOwner())) {
                return false;
            }
            effects.put(key, copy(
                    effect, status, effect.attempts(), effect.firstClaimedAt(),
                    effect.lastClaimedAt(), null, null, errorClass, error,
                    nextAttemptAt, null, null));
            return true;
        }

        @Override
        public boolean rearmEffect(
                String token, String key, int addedAllowance, Instant retryAt)
        {
            TaskPushEffect effect = effects.get(key);
            if (effect == null || addedAllowance < 1
                    || effect.status() != TaskPushEffect.Status.PERMANENT_FAILED
                            && !effect.exhausted()) {
                return false;
            }
            effects.put(key, new TaskPushEffect(
                    effect.id(), effect.token(), effect.effectKey(),
                    TaskPushEffect.Status.RETRYABLE_FAILED,
                    effect.attempts(), effect.attempts() + addedAllowance,
                    effect.firstClaimedAt(), effect.lastClaimedAt(), null, null,
                    effect.lastErrorClass(), effect.lastError(), retryAt,
                    effect.evidenceJson(), effect.completedAt()));
            return true;
        }

        @Override
        public boolean revokeIfUnclaimed(String token, String outcome, Instant at)
        {
            if (authorization == null || !authorization.active()
                    || effects.values().stream().anyMatch(TaskPushEffect::claimed)) {
                return false;
            }
            authorization = new TaskPushAuthorization(
                    authorization.token(), authorization.taskId(), authorization.prId(),
                    authorization.runId(), authorization.headSha(), authorization.codeFingerprint(),
                    authorization.actor(), authorization.basisKind(), authorization.basisId(),
                    authorization.overrideReason(), authorization.payloadJson(),
                    authorization.payloadDigest(), authorization.effectKeysJson(),
                    authorization.createdAt(), at, null, outcome);
            return true;
        }

        @Override
        public boolean sealActive(String taskId, String outcome, Instant at)
        {
            if (authorization == null || !authorization.active()
                    || !authorization.taskId().equals(taskId)) {
                return false;
            }
            authorization = new TaskPushAuthorization(
                    authorization.token(), authorization.taskId(), authorization.prId(),
                    authorization.runId(), authorization.headSha(), authorization.codeFingerprint(),
                    authorization.actor(), authorization.basisKind(), authorization.basisId(),
                    authorization.overrideReason(), authorization.payloadJson(),
                    authorization.payloadDigest(), authorization.effectKeysJson(),
                    authorization.createdAt(), at, null, outcome);
            return true;
        }

        @Override
        public boolean consumeIfComplete(String token, String outcome, Instant at)
        {
            if (authorization == null || !authorization.active()
                    || effects.values().stream().anyMatch(effect -> !effect.completed())) {
                return false;
            }
            authorization = new TaskPushAuthorization(
                    authorization.token(), authorization.taskId(), authorization.prId(),
                    authorization.runId(), authorization.headSha(), authorization.codeFingerprint(),
                    authorization.actor(), authorization.basisKind(), authorization.basisId(),
                    authorization.overrideReason(), authorization.payloadJson(),
                    authorization.payloadDigest(), authorization.effectKeysJson(),
                    authorization.createdAt(), null, at, outcome);
            return true;
        }

        private static TaskPushEffect copy(
                TaskPushEffect source,
                TaskPushEffect.Status status,
                int attempts,
                Instant firstClaimedAt,
                Instant lastClaimedAt,
                String claimOwner,
                Instant leaseUntil,
                String errorClass,
                String error,
                Instant nextAttemptAt,
                String evidence,
                Instant completedAt)
        {
            return new TaskPushEffect(
                    source.id(), source.token(), source.effectKey(), status,
                    attempts, source.attemptLimit(), firstClaimedAt, lastClaimedAt,
                    claimOwner, leaseUntil, errorClass, error, nextAttemptAt,
                    evidence, completedAt);
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
