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
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.RoundGateAuthorization;
import com.bytequay.app.domain.RoundGateEffect;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.AgentRunStore;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.RoundGateStore;
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
class TestRoundGateStore
{
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Autowired
    private RoundGateStore gates;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private AgentRunStore runs;
    @Autowired
    private ReviewRoundStore rounds;

    @Test
    void exactRunnableCheckpointClaimsAndConsumesEffects()
    {
        Fixture fixture = seed();
        gates.insert(fixture.authorization(), List.of("push_branch"), 2);

        assertThat(gates.claimEffect(
                fixture.authorization().token(), "push_branch", "worker-1",
                NOW, NOW.plusSeconds(30))).isTrue();
        assertThat(gates.revokeIfUnclaimed(
                fixture.authorization().token(), "edit", NOW.plusSeconds(1))).isFalse();
        assertThat(gates.completeEffect(
                fixture.authorization().token(), "push_branch", "worker-1", "{}",
                NOW.plusSeconds(2))).isTrue();
        assertThat(gates.consumeIfComplete(
                fixture.authorization().token(), RoundGateAuthorization.OUTCOME_POSTED,
                NOW.plusSeconds(3))).isTrue();

        assertThat(gates.findActiveByTask(fixture.taskId())).isEmpty();
        RoundGateEffect effect = gates.findEffect(
                fixture.authorization().token(), "push_branch").orElseThrow();
        assertThat(effect.completed()).isTrue();
        assertThat(effect.attempts()).isEqualTo(1);
    }

    @Test
    void stoppedTaskCannotClaimAnExternalEffect()
    {
        Fixture fixture = seed();
        gates.insert(fixture.authorization(), List.of("push_branch"), 2);
        tasks.updateStatusIf(fixture.taskId(), TaskStatus.IN_REVIEW, TaskStatus.PAUSED);

        assertThat(gates.claimEffect(
                fixture.authorization().token(), "push_branch", "worker-1",
                NOW, NOW.plusSeconds(30))).isFalse();
        assertThat(gates.findEffect(
                fixture.authorization().token(), "push_branch").orElseThrow().attempts())
                .isZero();
    }

    @Test
    void recoverableSweepSkipsParkedAuthorizationsBeforeItsLimit()
    {
        Fixture parked = seed();
        gates.insert(parked.authorization(), List.of("push_branch"), 2);
        tasks.updateStatusIf(
                parked.taskId(), TaskStatus.IN_REVIEW, TaskStatus.PAUSED);
        Fixture runnable = seed();
        gates.insert(runnable.authorization(), List.of("push_branch"), 2);

        assertThat(gates.findRecoverable(NOW, 1))
                .extracting(RoundGateAuthorization::token)
                .containsExactly(runnable.authorization().token());
    }

    @Test
    void exactParkedFailureCanReceiveBoundedAllowance()
    {
        Fixture fixture = seed();
        gates.insert(fixture.authorization(), List.of("push_branch"), 2);
        assertThat(gates.claimEffect(
                fixture.authorization().token(), "push_branch", "worker-1",
                NOW, NOW.plusSeconds(30))).isTrue();
        assertThat(gates.failEffect(
                fixture.authorization().token(), "push_branch", "worker-1",
                RoundGateEffect.Status.PERMANENT_FAILED,
                "Http401", "credentials", null)).isTrue();
        assertThat(rounds.parkIf(
                fixture.roundId(), ReviewRoundState.AWAITING_GATE)).isTrue();
        tasks.updatePhase(fixture.taskId(), TaskPhase.NEEDS_ATTENTION);
        tasks.updateStatusIf(
                fixture.taskId(), TaskStatus.IN_REVIEW, TaskStatus.NEEDS_ATTENTION);

        assertThat(gates.rearmEffect(
                fixture.authorization().token(), "push_branch", 1,
                NOW.plusSeconds(1))).isTrue();

        RoundGateEffect effect = gates.findEffect(
                fixture.authorization().token(), "push_branch").orElseThrow();
        assertThat(effect.status()).isEqualTo(RoundGateEffect.Status.RETRYABLE_FAILED);
        assertThat(effect.attemptLimit()).isEqualTo(effect.attempts() + 1);
    }

    private Fixture seed()
    {
        String suffix = UUID.randomUUID().toString();
        Thread thread = new Thread(
                "thread-" + suffix, ThreadKind.CLI_AGENT, "claude-code", null,
                "Round gate store test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, NOW, NOW, null, null, ThreadFlow.BUILD,
                "ws-default", null, null);
        threads.saveThread(thread);
        String taskId = "task-" + suffix;
        tasks.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.IN_REVIEW, "feature/x", "/tmp/wt",
                "main", "/tmp", null, null, null, null, null, "DEVELOP", 42, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null));
        tasks.updatePhase(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);
        String runId = "run-" + suffix;
        runs.insert(new AgentRun(
                runId, taskId, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE,
                null, null, null, AgentRun.STATUS_AWAITING_GATE, 0, 5,
                null, null, NOW, null));
        String roundId = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        rounds.insert(new ReviewRound(
                roundId, taskId, 1, List.of(), ReviewRoundState.AWAITING_GATE,
                ReviewRound.ReviewRoundStats.empty(), runId, NOW, NOW, null,
                ReviewRound.ORIGIN_EXTERNAL, ReviewRound.VERDICT_APPROVED, 1, 5,
                null, "fingerprint-1", 0, 0, 0, token, null));
        RoundGateAuthorization authorization = new RoundGateAuthorization(
                token, taskId, roundId, 0, 0, Actor.HUMAN, "fingerprint-1",
                "{}", "digest", "[\"push_branch\"]", NOW, null, null, null);
        return new Fixture(taskId, roundId, authorization);
    }

    private record Fixture(
            String taskId, String roundId, RoundGateAuthorization authorization) {}
}
