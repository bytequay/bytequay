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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPushAuthorization;
import com.bytequay.app.domain.TaskPushEffect;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.repository.TaskPushStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestTaskPushStore
{
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Autowired
    private TaskPushStore pushes;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private PRStore prs;

    @Test
    void authorizationCannotBeRevokedAfterAnEffectWasClaimed()
    {
        TaskPushAuthorization authorization = authorization(seedTask());
        pushes.insert(authorization, List.of("push_branch", "ensure_pull_request"), 2);

        assertThat(pushes.findActiveByTask(authorization.taskId())).contains(authorization);
        assertThat(pushes.claimEffect(
                authorization.token(), "push_branch", "worker-1", NOW, NOW.plusSeconds(30)))
                .isTrue();
        assertThat(pushes.revokeIfUnclaimed(
                authorization.token(), "replan", NOW.plusSeconds(1)))
                .isFalse();

        TaskPushEffect claimed = pushes.findEffect(
                authorization.token(), "push_branch").orElseThrow();
        assertThat(claimed.status()).isEqualTo(TaskPushEffect.Status.IN_FLIGHT);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.claimOwner()).isEqualTo("worker-1");

        assertThat(pushes.sealActive(
                authorization.taskId(), "task_cancelled", NOW.plusSeconds(2)))
                .isTrue();
        assertThat(pushes.findActiveByTask(authorization.taskId())).isEmpty();
        assertThat(pushes.findAuthorization(authorization.token()).orElseThrow().outcome())
                .isEqualTo("task_cancelled");
    }

    @Test
    void retryClaimsAreDueAndCompletionConsumptionAreCompareAndSet()
    {
        TaskPushAuthorization authorization = authorization(seedTask());
        pushes.insert(authorization, List.of("push_branch", "ensure_pull_request"), 3);

        assertThat(pushes.claimEffect(
                authorization.token(), "push_branch", "worker-1", NOW, NOW.plusSeconds(30)))
                .isTrue();
        assertThat(pushes.failEffect(
                authorization.token(), "push_branch", "other-worker",
                TaskPushEffect.Status.RETRYABLE_FAILED,
                "IOException", "network", NOW.plusSeconds(10)))
                .isFalse();
        assertThat(pushes.failEffect(
                authorization.token(), "push_branch", "worker-1",
                TaskPushEffect.Status.RETRYABLE_FAILED,
                "IOException", "network", NOW.plusSeconds(10)))
                .isTrue();
        assertThat(pushes.claimEffect(
                authorization.token(), "push_branch", "worker-2",
                NOW.plusSeconds(9), NOW.plusSeconds(39)))
                .isFalse();
        assertThat(pushes.claimEffect(
                authorization.token(), "push_branch", "worker-2",
                NOW.plusSeconds(10), NOW.plusSeconds(40)))
                .isTrue();
        assertThat(pushes.completeEffect(
                authorization.token(), "push_branch", "worker-2", "{}", NOW.plusSeconds(11)))
                .isTrue();

        assertThat(pushes.claimEffect(
                authorization.token(), "ensure_pull_request", "worker-3",
                NOW.plusSeconds(12), NOW.plusSeconds(42)))
                .isTrue();
        assertThat(pushes.completeEffect(
                authorization.token(), "ensure_pull_request", "worker-3", "{}", NOW.plusSeconds(13)))
                .isTrue();
        assertThat(pushes.consumeIfComplete(
                authorization.token(), TaskPushAuthorization.OUTCOME_PUSHED, NOW.plusSeconds(14)))
                .isTrue();
        assertThat(pushes.findActiveByTask(authorization.taskId())).isEmpty();
        assertThat(pushes.findAuthorization(authorization.token()).orElseThrow().outcome())
                .isEqualTo(TaskPushAuthorization.OUTCOME_PUSHED);
    }

    @Test
    void stoppedTaskCannotClaimAnExternalEffect()
    {
        String taskId = seedTask();
        TaskPushAuthorization authorization = authorization(taskId);
        pushes.insert(authorization, List.of("push_branch"), 2);
        tasks.updateStatusIf(taskId, TaskStatus.AWAITING_REVIEW, TaskStatus.PAUSED);

        assertThat(pushes.claimEffect(
                authorization.token(), "push_branch", "worker-1", NOW, NOW.plusSeconds(30)))
                .isFalse();
        assertThat(pushes.findEffect(authorization.token(), "push_branch").orElseThrow().attempts())
                .isZero();
    }

    @Test
    void recoverableSweepSkipsParkedAuthorizationsBeforeItsLimit()
    {
        String parkedTaskId = seedTask();
        TaskPushAuthorization parked = authorization(parkedTaskId);
        pushes.insert(parked, List.of("push_branch"), 2);
        tasks.updateStatusIf(
                parkedTaskId, TaskStatus.AWAITING_REVIEW, TaskStatus.PAUSED);
        String runnableTaskId = seedTask();
        TaskPushAuthorization runnable = authorization(runnableTaskId);
        pushes.insert(runnable, List.of("push_branch"), 2);

        assertThat(pushes.findRecoverable(NOW, 1))
                .extracting(TaskPushAuthorization::token)
                .containsExactly(runnable.token());
    }

    @Test
    void orphanSweepAppliesRemoteIdentityPredicateBeforeItsLimit()
    {
        seedTask();
        String orphanTaskId = seedTask();
        prs.save(PR.create(
                        "pr-" + orphanTaskId, orphanTaskId, "feature", "main",
                        "Orphaned remote PR", "", NOW)
                .withRemote(
                        "acme/widget", 42,
                        "https://github.com/acme/widget/pull/42", NOW)
                .withStatus(PR.STATUS_REMOTE_DRAFTED, NOW));

        assertThat(pushes.findOrphanedRemotePullRequestTaskIds(1))
                .containsExactly(orphanTaskId);
    }

    @Test
    void observedRemoteEffectIsDurableAndMakesTheTokenNonrevocable()
    {
        TaskPushAuthorization authorization = authorization(seedTask());
        pushes.insert(authorization, List.of("push_branch", "ensure_pull_request"), 2);

        assertThat(pushes.completeObservedEffect(
                authorization.token(), "push_branch", "{\"headSha\":\"head-1\"}", NOW))
                .isTrue();

        TaskPushEffect observed = pushes.findEffect(
                authorization.token(), "push_branch").orElseThrow();
        assertThat(observed.completed()).isTrue();
        assertThat(observed.attempts()).isEqualTo(1);
        assertThat(pushes.revokeIfUnclaimed(
                authorization.token(), "replan", NOW.plusSeconds(1)))
                .isFalse();
        assertThat(pushes.sealActive(
                authorization.taskId(), "test_cleanup", NOW.plusSeconds(2)))
                .isTrue();
    }

    @Test
    void exactParkedFailureCanReceiveABoundedRetryAllowance()
    {
        String taskId = seedTask();
        TaskPushAuthorization authorization = authorization(taskId);
        pushes.insert(authorization, List.of("push_branch"), 3);
        assertThat(pushes.claimEffect(
                authorization.token(), "push_branch", "worker-1", NOW,
                NOW.plusSeconds(30))).isTrue();
        assertThat(pushes.failEffect(
                authorization.token(), "push_branch", "worker-1",
                TaskPushEffect.Status.PERMANENT_FAILED,
                "Http401", "credentials", null)).isTrue();
        tasks.updatePhase(taskId, TaskPhase.NEEDS_ATTENTION);
        tasks.updateStatusIf(
                taskId, TaskStatus.AWAITING_REVIEW, TaskStatus.NEEDS_ATTENTION);

        assertThat(pushes.rearmEffect(
                authorization.token(), "push_branch", 1, NOW.plusSeconds(1)))
                .isTrue();

        TaskPushEffect rearmed = pushes.findEffect(
                authorization.token(), "push_branch").orElseThrow();
        assertThat(rearmed.status()).isEqualTo(TaskPushEffect.Status.RETRYABLE_FAILED);
        assertThat(rearmed.attempts()).isEqualTo(1);
        assertThat(rearmed.attemptLimit()).isEqualTo(2);
    }

    @Test
    void rejectingAStaleRequestPreservesTheRecoveryCheckpointAndPark()
    {
        String taskId = seedTask();
        tasks.checkpointRecovery(taskId, TaskPhase.AWAITING_PUSH, "{\"reason\":\"failed\"}");
        tasks.updatePhase(taskId, TaskPhase.NEEDS_ATTENTION);
        tasks.updateStatusIf(
                taskId, TaskStatus.AWAITING_REVIEW, TaskStatus.NEEDS_ATTENTION);
        tasks.recordRecoveryRequest(
                taskId, "request-1", TaskRecoveryRequest.KIND_EXTERNAL_SAGA, "{}", NOW);

        assertThat(tasks.clearRecoveryRequest(
                taskId, "other-request", "{\"reason\":\"rejected\"}"))
                .isFalse();
        assertThat(tasks.clearRecoveryRequest(
                taskId, "request-1", "{\"reason\":\"rejected\"}"))
                .isTrue();

        assertThat(tasks.recoveryRequest(taskId)).isEmpty();
        assertThat(tasks.recoveryPhase(taskId)).contains(TaskPhase.AWAITING_PUSH);
        Task parked = tasks.findTaskById(taskId).orElseThrow();
        assertThat(parked.phase()).isEqualTo(TaskPhase.NEEDS_ATTENTION);
        assertThat(parked.status()).isEqualTo(TaskStatus.NEEDS_ATTENTION);
    }

    private TaskPushAuthorization authorization(String taskId)
    {
        return new TaskPushAuthorization(
                UUID.randomUUID().toString(), taskId, "pr-" + taskId, "run-1", "head-1",
                "fingerprint-1", Actor.HUMAN, TaskPushAuthorization.BASIS_BRAIN_REVIEW,
                "round-1", null, "{}", "digest", "[\"push_branch\",\"ensure_pull_request\"]",
                NOW, null, null, null);
    }

    private String seedTask()
    {
        String suffix = UUID.randomUUID().toString();
        Thread thread = new Thread(
                "thread-" + suffix, ThreadKind.CLI_AGENT, "claude-code",
                null, "Push saga store test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, NOW, NOW, null, null, ThreadFlow.BUILD,
                "ws-default", null, null);
        threads.saveThread(thread);

        String taskId = "task-" + suffix;
        tasks.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.AWAITING_REVIEW,
                "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null));
        tasks.updatePhase(taskId, TaskPhase.AWAITING_PUSH);
        return taskId;
    }
}
