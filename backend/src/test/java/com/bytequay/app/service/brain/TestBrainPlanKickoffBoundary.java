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
package com.bytequay.app.service.brain;

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.threads.PlanKickoffRequested;
import com.bytequay.app.service.threads.TaskBrainAgent;
import com.bytequay.app.service.threads.ThreadRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers the real BrainService -> scheduler transaction boundary. */
@SpringBootTest
class TestBrainPlanKickoffBoundary
{
    @Autowired
    private BrainServiceImpl brain;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private StageStore stages;
    @Autowired
    private ThreadTurnStore turns;
    @MockitoBean
    private ThreadRegistry registry;

    @BeforeEach
    void keepTheSchedulerOffExternalProviders()
    {
        TaskBrainAgent agent = mock(TaskBrainAgent.class);
        when(registry.resolvedWorkModelForTurn(any(), any(), anyString())).thenReturn(
                new WorkModel(WorkModelKind.CLI, "claude-code", "claude-opus-4-8", null));
        when(registry.getOrCreateTaskBrainAgent(any())).thenReturn(agent);
        when(agent.workingDir()).thenReturn("/tmp");
        when(agent.metrics()).thenReturn(AgentMetrics.empty());
        when(agent.send(anyString())).thenReturn(new CompletableFuture<>());
    }

    @Test
    void planningKickoffCommitsTheBrainBeforeQueuingItsTaskCommand()
    {
        String taskId = seedTask();
        StageInstance plan = stages.openStage(taskId, StageType.PLAN_STAGE, null);

        assertThatCode(() -> brain.onPlanKickoff(
                new PlanKickoffRequested(taskId, "plan this", null)))
                .doesNotThrowAnyException();

        Thread brainThread = threads.findBrainThreadByTask(taskId).orElseThrow();
        assertThat(brainThread.parentTaskId()).isEqualTo(taskId);
        List<ThreadTurn> durable = turns.listTurnsByTaskId(brainThread.id(), 10);
        assertThat(durable).hasSize(1);
        assertThat(durable.getFirst().threadId()).isEqualTo(brainThread.id());
        assertThat(durable.getFirst().stageId()).isEqualTo(plan.id().toString());
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-07-27T12:00:00Z");
        WorkModel model = new WorkModel(
                WorkModelKind.CLI, "claude-code", "claude-opus-4-8", null);
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Planning boundary", ThreadStatus.RUNNING, "claude-opus-4-8",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD,
                "ws-default", model, null);
        threads.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        tasks.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.PENDING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
