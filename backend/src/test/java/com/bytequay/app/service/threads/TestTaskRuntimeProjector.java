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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Task;
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
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime projection's contract against the real stores: only the
 * pointer turn is authoritative, coordinator-owned failures stay IDLE,
 * uncoordinated failures project ERRORED with the turn's failure
 * fields, a completed pointer promotes the oldest queued follower, and
 * the projection never moves a task out of ERRORED or touches a
 * stopped/gated status.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestTaskRuntimeProjector
{
    private static final Instant NOW = Instant.parse("2026-07-25T09:00:00Z");

    @Autowired
    private TaskRuntimeProjector projector;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ThreadTurnStore turnStore;

    @Test
    void runningPointerTurnProjectsRunningAndCompletionReturnsIdle()
    {
        Seed seed = seed(TaskStatus.PENDING);
        insertTurn(seed, "turn-1", ThreadTurnStatus.RUNNING, "user", null);
        taskStore.setCurrentLivenessTurnIdIf(seed.taskId, null, "turn-1");

        projector.project(seed.taskId);
        assertThat(status(seed)).isEqualTo(TaskStatus.RUNNING);

        finishTurn("turn-1", ThreadTurnStatus.COMPLETED, null);
        projector.project(seed.taskId);
        assertThat(status(seed)).isEqualTo(TaskStatus.IDLE);
    }

    @Test
    void completedPointerPromotesTheOldestQueuedFollower()
    {
        Seed seed = seed(TaskStatus.RUNNING);
        insertTurn(seed, "turn-a", ThreadTurnStatus.COMPLETED, "user", null);
        insertTurn(seed, "turn-b", ThreadTurnStatus.QUEUED, "user", null);
        taskStore.setCurrentLivenessTurnIdIf(seed.taskId, null, "turn-a");

        projector.project(seed.taskId);

        assertThat(taskStore.currentLivenessTurnId(seed.taskId)).contains("turn-b");
        assertThat(status(seed)).isEqualTo(TaskStatus.IDLE);
    }

    @Test
    void coordinatorOwnedFailureStaysIdleWhileUncoordinatedFailureProjectsErrored()
    {
        Seed coordinated = seed(TaskStatus.RUNNING);
        insertTurn(coordinated, "fix-turn", ThreadTurnStatus.FAILED, "brain-review-fix", "boom");
        taskStore.setCurrentLivenessTurnIdIf(coordinated.taskId, null, "fix-turn");
        projector.project(coordinated.taskId);
        assertThat(status(coordinated)).isEqualTo(TaskStatus.IDLE);

        Seed uncoordinated = seed(TaskStatus.RUNNING);
        insertTurn(uncoordinated, "user-turn", ThreadTurnStatus.FAILED, "user", "exploded");
        taskStore.setCurrentLivenessTurnIdIf(uncoordinated.taskId, null, "user-turn");
        projector.project(uncoordinated.taskId);
        Task errored = taskStore.findTaskById(uncoordinated.taskId).orElseThrow();
        assertThat(errored.status()).isEqualTo(TaskStatus.ERRORED);
        assertThat(errored.errorMessage()).isEqualTo("exploded");

        // ERRORED's exits belong to the explicit retry intent — a later
        // projection cannot clear it even if the pointer looks alive.
        finishTurn("user-turn", ThreadTurnStatus.COMPLETED, null);
        projector.project(uncoordinated.taskId);
        assertThat(status(uncoordinated)).isEqualTo(TaskStatus.ERRORED);
    }

    @Test
    void stoppedAndGatedStatusesAreNeverTouched()
    {
        Seed paused = seed(TaskStatus.PAUSED);
        insertTurn(paused, "turn-p", ThreadTurnStatus.RUNNING, "user", null);
        taskStore.setCurrentLivenessTurnIdIf(paused.taskId, null, "turn-p");

        projector.project(paused.taskId);

        assertThat(status(paused)).isEqualTo(TaskStatus.PAUSED);
    }

    private TaskStatus status(Seed seed)
    {
        return taskStore.findTaskById(seed.taskId).orElseThrow().status();
    }

    private void insertTurn(Seed seed, String turnId, ThreadTurnStatus status, String source, String error)
    {
        ThreadTurn turn = new ThreadTurn(
                turnId, seed.threadId, seed.taskId, ThreadResourceLane.CLI,
                status, "input", NOW, NOW,
                status == ThreadTurnStatus.QUEUED ? null : NOW,
                status == ThreadTurnStatus.QUEUED || status == ThreadTurnStatus.RUNNING ? null : NOW,
                error,
                "user".equals(source) ? TurnInitiator.user() : TurnInitiator.unattended(source),
                null, ThreadScope.TASK, null);
        turnStore.insertTurn(turn, TurnLiveness.CODE.affectsTask(), null);
    }

    private void finishTurn(String turnId, ThreadTurnStatus status, String error)
    {
        ThreadTurn existing = turnStore.findTurnById(turnId).orElseThrow();
        turnStore.saveTurn(new ThreadTurn(
                existing.id(), existing.threadId(), existing.taskId(), existing.lane(),
                status, existing.input(), existing.createdAt(), NOW,
                existing.startedAt(), NOW, error,
                existing.initiator(), existing.stageId(), existing.scope(), existing.agentRunId()));
    }

    private Seed seed(TaskStatus status)
    {
        String threadId = UUID.randomUUID().toString();
        threadStore.saveThread(new Thread(
                threadId, ThreadKind.CLI_AGENT, "claude-code",
                null, "Projection test", ThreadStatus.IDLE, "claude-sonnet-4.6",
                0L, 0L, 0L, NOW, NOW, null, null, ThreadFlow.BUILD, "ws-default", null, null));
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, threadId, 1L, status, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null));
        return new Seed(threadId, taskId);
    }

    private record Seed(String threadId, String taskId)
    {
    }
}
