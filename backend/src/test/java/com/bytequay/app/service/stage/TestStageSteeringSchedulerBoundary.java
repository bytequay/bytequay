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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.StageAgent;
import com.bytequay.app.service.threads.ThreadRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers the real steering -> task command -> scheduler persistence boundary. */
@SpringBootTest
class TestStageSteeringSchedulerBoundary
{
    @Autowired
    private StageSteeringService steering;
    @Autowired
    private StageStore stages;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private ThreadTurnStore turns;
    @Autowired
    private IterationStore iterations;
    @Autowired
    private AgentRunService runs;
    @MockitoBean
    private ThreadRegistry registry;

    @Test
    void steeringCommitsRunTurnAndIterationBeforeProviderDispatch()
            throws Exception
    {
        String workingDir = System.getProperty("user.dir");
        String taskId = seedTask(workingDir);
        StageInstance stage = stages.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        StageAgent agent = mock(StageAgent.class);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        CountDownLatch dispatched = new CountDownLatch(1);
        AtomicReference<Throwable> dispatchFailure = new AtomicReference<>();
        WorkModel workModel = new WorkModel(WorkModelKind.CLI, "claude-code", null, null);
        when(registry.resolvedWorkModel(any())).thenReturn(workModel);
        when(registry.resolvedWorkModelForTurn(any(), any(), anyString())).thenReturn(workModel);
        when(registry.getOrCreateStageAgent(any(), any(), anyString())).thenReturn(agent);
        when(agent.workingDir()).thenReturn(workingDir);
        when(agent.metrics()).thenReturn(AgentMetrics.empty());
        when(agent.send(anyString())).thenAnswer(invocation -> {
            try {
                List<TaskStageIteration> visible = iterations.findByStage(stage.id());
                assertThat(visible).hasSize(1);
                assertThat(visible.get(0).trigger())
                        .isEqualTo(IterationService.TRIGGER_USER_STEERING);
            }
            catch (Throwable failure) {
                dispatchFailure.set(failure);
            }
            finally {
                dispatched.countDown();
            }
            return completion;
        });

        try {
            StageSteeringService.SteerResult result = steering.steer(
                    stage.id(), "Fix the failing workflow", null);

            assertThat(dispatched.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatchFailure.get()).isNull();
            ThreadTurn turn = turns.findTurnById(result.turnId()).orElseThrow();
            TaskStageIteration iteration = iterations.findByTurnId(turn.id()).orElseThrow();
            AgentRun run = runs.findById(turn.agentRunId()).orElseThrow();
            assertThat(turn.taskId()).isEqualTo(taskId);
            assertThat(turn.stageId()).isEqualTo(stage.id().toString());
            assertThat(iteration.turnId()).isEqualTo(turn.id());
            assertThat(iteration.trigger()).isEqualTo(IterationService.TRIGGER_USER_STEERING);
            assertThat(run.kind()).isEqualTo(AgentRun.KIND_CI_FIX);
            assertThat(run.stageId()).isEqualTo(stage.id().toString());
        }
        finally {
            completion.complete(null);
        }
    }

    private String seedTask(String workingDir)
    {
        Instant now = Instant.parse("2026-07-27T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Stage steering boundary", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD,
                "ws-default", null, null);
        threads.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        tasks.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", workingDir,
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
