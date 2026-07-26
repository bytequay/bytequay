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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.LocalReviewSubmission;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.AgentRunStore;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The P1b insert-vs-targeted-update contract on the four lifecycle
 * stores: status moves only through compare-and-set that touches nothing
 * else, and metadata updates can never write status.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestLifecycleTargetedUpdates
{
    private static final Instant NOW = Instant.parse("2026-07-25T09:00:00Z");

    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ThreadTurnStore turnStore;
    @Autowired
    private ValidationPassStore validationStore;
    @Autowired
    private ReviewRoundStore roundStore;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private AgentRunStore runStore;
    @Autowired
    private LocalReviewSubmissionStore submissionStore;

    @Test
    void taskStatusCasOnlyMovesFromTheExpectedState()
    {
        String taskId = seedTask();

        assertThat(taskStore.updateStatusIf(taskId, TaskStatus.IDLE, TaskStatus.PAUSED)).isFalse();
        assertThat(taskStore.findTaskById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.RUNNING);

        assertThat(taskStore.updateStatusIf(taskId, TaskStatus.RUNNING, TaskStatus.PAUSED)).isTrue();
        Task updated = taskStore.findTaskById(taskId).orElseThrow();
        assertThat(updated.status()).isEqualTo(TaskStatus.PAUSED);
        assertThat(updated.branchName()).isEqualTo("feature");
    }

    @Test
    void pauseCheckpointRoundTripsAndSurvivesFullRowSaves()
    {
        String taskId = seedTask();

        taskStore.checkpointPause(taskId, TaskStatus.RUNNING);
        taskStore.requestResume(taskId, NOW);
        taskStore.clearProcessPid(taskId);
        assertThat(taskStore.pausedStatus(taskId)).contains(TaskStatus.RUNNING);
        assertThat(taskStore.resumeRequestedAt(taskId)).contains(NOW);

        // A full-row save must not clobber the entity-managed checkpoint.
        Task task = taskStore.findTaskById(taskId).orElseThrow();
        taskStore.saveTask(task.withStatus(TaskStatus.PAUSED));
        assertThat(taskStore.pausedStatus(taskId)).contains(TaskStatus.RUNNING);
        assertThat(taskStore.resumeRequestedAt(taskId)).contains(NOW);

        taskStore.clearPauseCheckpoint(taskId);
        assertThat(taskStore.pausedStatus(taskId)).isEmpty();
        assertThat(taskStore.resumeRequestedAt(taskId)).isEmpty();
    }

    @Test
    void recoveryCheckpointAndRequestRoundTrip()
    {
        String taskId = seedTask();

        taskStore.checkpointRecovery(taskId, TaskPhase.VALIDATING, "{\"reason\":\"validation_failed\"}");
        taskStore.recordRecoveryRequest(taskId, "req-1", TaskRecoveryRequest.KIND_NORMAL, null, NOW);
        assertThat(taskStore.recoveryPhase(taskId)).contains(TaskPhase.VALIDATING);
        TaskRecoveryRequest request = taskStore.recoveryRequest(taskId).orElseThrow();
        assertThat(request.id()).isEqualTo("req-1");
        assertThat(request.kind()).isEqualTo(TaskRecoveryRequest.KIND_NORMAL);
        assertThat(request.requestedAt()).isEqualTo(NOW);

        // A full-row save must not clobber the entity-managed checkpoint.
        taskStore.saveTask(taskStore.findTaskById(taskId).orElseThrow()
                .withStatus(TaskStatus.NEEDS_ATTENTION));
        assertThat(taskStore.recoveryPhase(taskId)).contains(TaskPhase.VALIDATING);
        assertThat(taskStore.recoveryRequest(taskId)).isPresent();

        taskStore.clearRecoveryState(taskId);
        assertThat(taskStore.recoveryPhase(taskId)).isEmpty();
        assertThat(taskStore.recoveryRequest(taskId)).isEmpty();
    }

    @Test
    void submissionRowsAreInsertOnlyWithTargetedOutcomeStamps()
    {
        String taskId = seedTask();
        assertThat(submissionStore.nextSeq(taskId)).isEqualTo(1L);

        submissionStore.insert(new LocalReviewSubmission(
                "sub-1", "evt-1", taskId, "pr-1", null, 1L,
                "[\"c1\"]", "[{\"id\":\"c1\",\"order\":0}]", NOW,
                null, 0, 0, NOW, null, null, null, null));
        assertThat(submissionStore.nextSeq(taskId)).isEqualTo(2L);
        assertThat(submissionStore.listOpenByTask(taskId)).hasSize(1);

        submissionStore.bindRun("sub-1", "run-9", NOW);
        submissionStore.incrementFailures("sub-1");
        LocalReviewSubmission bound = submissionStore.findById("sub-1").orElseThrow();
        assertThat(bound.agentRunId()).isEqualTo("run-9");
        assertThat(bound.activatedAt()).isEqualTo(NOW);
        assertThat(bound.failures()).isEqualTo(1);

        submissionStore.markCompleted("sub-1", NOW);
        assertThat(submissionStore.listOpenByTask(taskId)).isEmpty();
        // Completed rows are immune to a later blanket cancel.
        submissionStore.cancelOpenForTask(taskId, "replan", NOW);
        assertThat(submissionStore.findById("sub-1").orElseThrow().canceledAt()).isNull();
    }

    @Test
    void roundMetadataUpdatesCannotClobberLifecycleColumns()
    {
        ReviewRound round = seedRound(seedTask());

        roundStore.updateStats(round.id(), new ReviewRound.ReviewRoundStats(1, 2, 3, 4));
        roundStore.updateRunId(round.id(), "run-77");
        roundStore.updateGateTimes(round.id(), NOW, null);

        ReviewRound reloaded = roundStore.findById(round.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(ReviewRound.STATUS_TRIAGING);
        assertThat(reloaded.stats().open()).isEqualTo(4);
        assertThat(reloaded.runId()).isEqualTo("run-77");
        assertThat(reloaded.brainVerdict()).isNull();
        assertThat(reloaded.gatedAt()).isEqualTo(NOW);
        assertThat(reloaded.postedAt()).isNull();

        assertThat(roundStore.parkIf(round.id(), ReviewRound.STATUS_TRIAGING)).isTrue();
        roundStore.updateStats(round.id(), new ReviewRound.ReviewRoundStats(2, 3, 4, 5));
        ReviewRound paused = roundStore.findById(round.id()).orElseThrow();
        assertThat(paused.status()).isEqualTo(ReviewRound.STATUS_PAUSED);
        assertThat(paused.pausedFrom()).isEqualTo(ReviewRound.STATUS_TRIAGING);
        assertThat(paused.stats().open()).isEqualTo(5);
    }

    @Test
    void roundParkResumeAndSealUpdateTheirCheckpointsAtomically()
    {
        ReviewRound round = seedRound(seedTask());
        assertThat(roundStore.updateBrainVerdictIf(
                round.id(), ReviewRound.STATUS_TRIAGING,
                ReviewRound.VERDICT_APPROVED)).isTrue();

        assertThat(roundStore.parkIf(round.id(), ReviewRound.STATUS_ADDRESSING)).isFalse();
        assertThat(roundStore.parkIf(round.id(), ReviewRound.STATUS_TRIAGING)).isTrue();
        ReviewRound paused = roundStore.findById(round.id()).orElseThrow();
        assertThat(paused.status()).isEqualTo(ReviewRound.STATUS_PAUSED);
        assertThat(paused.pausedFrom()).isEqualTo(ReviewRound.STATUS_TRIAGING);
        assertThat(paused.brainVerdict()).isEqualTo(ReviewRound.VERDICT_APPROVED);
        assertThat(roundStore.findLiveByTask(round.taskId())).isEmpty();
        assertThat(roundStore.findAllLive()).doesNotContain(paused);

        assertThat(roundStore.resumeIf(round.id(), ReviewRound.STATUS_ADDRESSING)).isFalse();
        assertThat(roundStore.resumeIf(round.id(), ReviewRound.STATUS_TRIAGING)).isTrue();
        ReviewRound resumed = roundStore.findById(round.id()).orElseThrow();
        assertThat(resumed.status()).isEqualTo(ReviewRound.STATUS_TRIAGING);
        assertThat(resumed.pausedFrom()).isNull();
        assertThat(resumed.kickAttempt()).isEqualTo(1);
        assertThat(resumed.brainVerdict()).isNull();

        assertThat(roundStore.sealIf(
                round.id(), ReviewRound.STATUS_ADDRESSING, NOW)).isFalse();
        assertThat(roundStore.sealIf(
                round.id(), ReviewRound.STATUS_TRIAGING, NOW)).isTrue();
        ReviewRound closed = roundStore.findById(round.id()).orElseThrow();
        assertThat(closed.status()).isEqualTo(ReviewRound.STATUS_CLOSED);
        assertThat(closed.closedAt()).isEqualTo(NOW);
    }

    @Test
    void oneTaskCannotOwnTwoCoordinatorRounds()
    {
        String taskId = seedTask();
        seedRound(taskId);

        assertThatThrownBy(() -> seedRound(taskId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void roundInsertCannotBecomeAFullRowLifecycleUpdate()
    {
        ReviewRound round = seedRound(seedTask());

        assertThatThrownBy(() -> roundStore.insert(
                round.withStatus(ReviewRound.STATUS_CLOSED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        assertThat(roundStore.findById(round.id()).orElseThrow().status())
                .isEqualTo(ReviewRound.STATUS_TRIAGING);
    }

    @Test
    void finishAddressingIsFencedByTheExactTurnKickAndGreenClaim()
    {
        String taskId = seedTask();
        Task task = taskStore.findTaskById(taskId).orElseThrow();
        String roundId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        roundStore.insert(new ReviewRound(
                roundId, taskId, 1, List.of(), ReviewRound.STATUS_ADDRESSING,
                ReviewRound.ReviewRoundStats.empty(), runId, NOW, null, null,
                ReviewRound.ORIGIN_BRAIN, ReviewRound.VERDICT_CHANGES_REQUESTED,
                1, 5, null, "sha256:before", 0, 0, 0, null, null));
        String turnId = UUID.randomUUID().toString();
        String kickKey = roundId + ":addressing:brain-review-fix:1:0:0";
        turnStore.insertTurn(new ThreadTurn(
                turnId, task.threadId(), taskId, ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "fix", NOW, NOW, NOW, NOW,
                null, TurnInitiator.unattended("brain-review-fix"), "stage-1",
                ThreadScope.STAGE, runId), true, kickKey);
        String claimKey = "review-round:" + taskId + ':' + roundId
                + ':' + turnId + ":sha256:after";
        long claimId = validationStore.insertClaim(
                        claimKey, taskId, "review-round", roundId,
                        "sha256:after", null, null, NOW)
                .orElseThrow();
        validationStore.finishPass(claimId, NOW, true, 0, "[]");

        assertThat(roundStore.finishAddressingIf(
                roundId,
                new ReviewRoundStore.AttemptFence(
                        1, 0, 0, "stale-turn", kickKey),
                claimKey, "sha256:after"))
                .isFalse();
        assertThat(roundStore.finishAddressingIf(
                roundId,
                new ReviewRoundStore.AttemptFence(
                        1, 0, 0, turnId, kickKey),
                claimKey, "sha256:after"))
                .isTrue();
        ReviewRound updated = roundStore.findById(roundId).orElseThrow();
        assertThat(updated.status()).isEqualTo(ReviewRound.STATUS_TRIAGING);
        assertThat(updated.codeFingerprint()).isEqualTo("sha256:after");
        assertThat(updated.iteration()).isEqualTo(2);
    }

    @Test
    void conclusionIsFencedByTheExactTerminalTurnAndKick()
    {
        String taskId = seedTask();
        Task task = taskStore.findTaskById(taskId).orElseThrow();
        String roundId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        roundStore.insert(new ReviewRound(
                roundId, taskId, 1, List.of(), ReviewRound.STATUS_TRIAGING,
                ReviewRound.ReviewRoundStats.empty(), runId, NOW, null, null,
                ReviewRound.ORIGIN_BRAIN, ReviewRound.VERDICT_APPROVED,
                2, 5, null, "sha256:reviewed", 0, 3, 1, null, null));
        String turnId = UUID.randomUUID().toString();
        String kickKey = roundId + ":triaging:brain-review:2:1:3";
        turnStore.insertTurn(new ThreadTurn(
                turnId, task.threadId(), taskId, ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "review", NOW, NOW, NOW, NOW,
                null, TurnInitiator.unattended("brain-review"), "stage-1",
                ThreadScope.STAGE, runId), true, kickKey);
        ReviewRound.ReviewRoundStats stats = new ReviewRound.ReviewRoundStats(1, 2, 0, 0);

        assertThat(roundStore.concludeIf(
                roundId, ReviewRound.STATUS_TRIAGING, ReviewRound.STATUS_CLOSED,
                new ReviewRoundStore.AttemptFence(2, 1, 3, turnId, "stale-kick"),
                stats, ReviewRound.VERDICT_APPROVED, null, NOW))
                .isFalse();
        assertThat(roundStore.concludeIf(
                roundId, ReviewRound.STATUS_TRIAGING, ReviewRound.STATUS_CLOSED,
                new ReviewRoundStore.AttemptFence(2, 1, 3, turnId, kickKey),
                stats, ReviewRound.VERDICT_APPROVED, null, NOW))
                .isTrue();
        ReviewRound updated = roundStore.findById(roundId).orElseThrow();
        assertThat(updated.status()).isEqualTo(ReviewRound.STATUS_CLOSED);
        assertThat(updated.closedAt()).isEqualTo(NOW);
        assertThat(updated.stats()).isEqualTo(stats);
    }

    @Test
    void stageStateCasStampsAndClearsClosedAt()
    {
        StageInstance stage = stageStore.openStage(seedTask(), StageType.DEVELOPMENT_STAGE, null);

        assertThat(stageStore.updateStateIf(stage.id(), StageState.CLOSED, StageState.OPEN, null))
                .isFalse();

        assertThat(stageStore.updateStateIf(stage.id(), StageState.OPEN, StageState.CLOSED, NOW))
                .isTrue();
        StageInstance closed = stageStore.findStageById(stage.id()).orElseThrow();
        assertThat(closed.state()).isEqualTo(StageState.CLOSED);
        assertThat(closed.closedAt()).contains(NOW);

        assertThat(stageStore.updateStateIf(stage.id(), StageState.CLOSED, StageState.OPEN, null))
                .isTrue();
        StageInstance reopened = stageStore.findStageById(stage.id()).orElseThrow();
        assertThat(reopened.state()).isEqualTo(StageState.OPEN);
        assertThat(reopened.closedAt()).isEmpty();
    }

    @Test
    void runStatusCasAndMetadataUpdatesAreIndependent()
    {
        AgentRun run = seedRun(seedTask());

        assertThat(runStore.updateStatusIf(
                run.id(), AgentRun.STATUS_QUEUED, AgentRun.STATUS_SUCCEEDED, NOW)).isFalse();

        runStore.updateProgress(run.id(), 3, 1500L, 200L, 100L);
        runStore.updateBudget(run.id(), 7);
        runStore.updateHeadline(run.id(), "fixing checks", "in progress");

        AgentRun reloaded = runStore.findById(run.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(AgentRun.STATUS_RUNNING);
        assertThat(reloaded.iterations()).isEqualTo(3);
        assertThat(reloaded.budget()).isEqualTo(7);
        assertThat(reloaded.headline()).isEqualTo("fixing checks");

        assertThat(runStore.updateStatusIf(
                run.id(), AgentRun.STATUS_RUNNING, AgentRun.STATUS_SUCCEEDED, NOW)).isTrue();
        AgentRun succeeded = runStore.findById(run.id()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(AgentRun.STATUS_SUCCEEDED);
        assertThat(succeeded.finishedAt()).isEqualTo(NOW);
    }

    private String seedTask()
    {
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Targeted update test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, NOW, NOW, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);

        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null));
        return taskId;
    }

    private ReviewRound seedRound(String taskId)
    {
        return roundStore.insert(new ReviewRound(
                UUID.randomUUID().toString(), taskId, 1, List.of(),
                ReviewRound.STATUS_TRIAGING, ReviewRound.ReviewRoundStats.empty(),
                null, NOW, null, null,
                ReviewRound.ORIGIN_BRAIN, null, 1, 5));
    }

    private AgentRun seedRun(String taskId)
    {
        return runStore.save(new AgentRun(
                UUID.randomUUID().toString(), taskId, AgentRun.KIND_CI_FIX, AgentRun.SOURCE_LOCAL,
                null, null, null, AgentRun.STATUS_RUNNING, 0, 5, null, null,
                NOW, null, "ws-default", null, "claude-code", "claude-sonnet-4.6",
                0L, 0L, 0L, 0, null, null, null));
    }
}
