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
package com.bytequay.app.scheduler;

import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.stage.PlanStageService;
import com.bytequay.app.service.threads.AgentScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stranded-dev reconciler: recover a DevelopmentStage that opened but never
 * got a turn, then — if a recovery still doesn't take — surface it. Healthy
 * stages (recent activity, an active turn) stay untouched.
 */
@SpringBootTest
class TestStrandedDevStageReconciler
{
    @Autowired
    private StrandedDevStageReconciler reconciler;
    @Autowired
    private PlanStageService planStageService;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ThreadTurnStore turnStore;
    /** Mocked so the recovery re-enqueue never dispatches a real agent — the
     *  stranded state is exactly "dev stage open, no turn row". */
    @MockitoBean
    private AgentScheduler scheduler;

    @Test
    void recoversOnceThenSurfaces()
    {
        String taskId = seedImplementingTask();
        StageInstance dev = stageStore.findActiveStage(taskId).orElseThrow();
        Instant pastGrace = Instant.now().plus(Duration.ofMinutes(6));

        // First stranded hit: re-enqueue the kickoff and mark the recovery.
        reconciler.reconcile(taskStore.findTaskById(taskId).orElseThrow(), pastGrace);

        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.IMPLEMENTING);
        assertThat(stageStore.findEventsByStage(dev.id()))
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo(StageEventType.DEV_KICKOFF_RECOVERED);
                    assertThat(e.payloadJson()).contains("\"reenqueued\":true");
                });

        // Still stranded on the next hit: hand it to the human.
        reconciler.reconcile(taskStore.findTaskById(taskId).orElseThrow(), pastGrace);

        Task task = taskStore.findTaskById(taskId).orElseThrow();
        assertThat(task.phase()).isEqualTo(TaskPhase.NEEDS_ATTENTION);
        assertThat(task.errorMessage()).contains("Development stalled");
        assertThat(stageStore.findEventsByStage(dev.id()))
                .anyMatch(e -> e.eventType() == StageEventType.DEV_FAILED);
    }

    @Test
    void aStageStillWithinTheGraceWindowIsUntouched()
    {
        String taskId = seedImplementingTask();
        StageInstance dev = stageStore.findActiveStage(taskId).orElseThrow();

        // "now" is essentially the stage's open time — inside the grace window.
        reconciler.reconcile(taskStore.findTaskById(taskId).orElseThrow(), Instant.now());

        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.IMPLEMENTING);
        assertThat(stageStore.findEventsByStage(dev.id()))
                .noneMatch(e -> e.eventType() == StageEventType.DEV_KICKOFF_RECOVERED);
    }

    @Test
    void aHealthyStageWithAnActiveTurnIsUntouched()
    {
        String taskId = seedImplementingTask();
        StageInstance dev = stageStore.findActiveStage(taskId).orElseThrow();
        String threadId = taskStore.findTaskById(taskId).orElseThrow().threadId();
        saveTurn(taskId, threadId, ThreadTurnStatus.QUEUED);

        // Well past the grace window, but a queued turn means work is pending.
        reconciler.reconcile(taskStore.findTaskById(taskId).orElseThrow(),
                Instant.now().plus(Duration.ofMinutes(30)));

        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.IMPLEMENTING);
        assertThat(stageStore.findEventsByStage(dev.id()))
                .noneMatch(e -> e.eventType() == StageEventType.DEV_KICKOFF_RECOVERED);
    }

    /** Seed a task that is IMPLEMENTING with an open DevelopmentStage and a
     *  closed, approved PlanStage — but no dev turn (the scheduler is mocked),
     *  i.e. the stranded signature. */
    private String seedImplementingTask()
    {
        Instant now = Instant.parse("2026-07-21T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Reconciler test", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "rev-1");
        payload.put("status", "finalized");
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("summary", "do the thing");
        intent.put("pushStrategy", "await_approval");
        payload.put("intent", intent);
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, payload);
        stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED, Map.of("verdict", "approved"));
        planStageService.approveByStage(plan.id());
        return taskId;
    }

    private void saveTurn(String taskId, String threadId, ThreadTurnStatus status)
    {
        Instant now = Instant.parse("2026-07-21T09:00:00Z");
        turnStore.saveTurn(new ThreadTurn(
                UUID.randomUUID().toString(), threadId, taskId, ThreadResourceLane.CLI, status,
                "kickoff", now, now, now, null, null, TurnInitiator.unattended("plan-approved")));
    }
}
