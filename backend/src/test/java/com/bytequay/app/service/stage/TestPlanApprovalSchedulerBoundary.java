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
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.StageAgent;
import com.bytequay.app.service.threads.ThreadRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers the real PlanStageService -> AgentScheduler -> AgentRunService boundary. */
@SpringBootTest
class TestPlanApprovalSchedulerBoundary
{
    @Autowired
    private PlanStageService plans;
    @Autowired
    private StageStore stages;
    @Autowired
    private TaskStore tasks;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private ThreadTurnStore turns;
    @Autowired
    private AgentRunService runs;
    @MockitoBean
    private ThreadRegistry registry;

    @BeforeEach
    void keepTheRealSchedulerOffExternalProviders()
    {
        StageAgent agent = mock(StageAgent.class);
        when(registry.resolvedWorkModel(any())).thenReturn(
                new WorkModel(WorkModelKind.CLI, "claude-code", null, null));
        when(registry.getOrCreateStageAgent(any(), any(), anyString())).thenReturn(agent);
        when(agent.workingDir()).thenReturn("/tmp");
        when(agent.metrics()).thenReturn(AgentMetrics.empty());
        when(agent.send(anyString())).thenReturn(new CompletableFuture<>());
    }

    @Test
    void approvalCommitsWithACorrelatedDevRunAndDurableTurn()
    {
        String taskId = seedTask();
        StageInstance plan = stages.openStage(taskId, StageType.PLAN_STAGE, null);
        stages.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, Map.of(
                "id", "rev-boundary", "status", "finalized", "goal", "Implement it",
                "intent", Map.of("summary", "Implement it", "steps", List.of("Change it"))));
        stages.recordEvent(plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED, Map.of(
                "verdict", "approved", "reviewedRevisionId", "rev-boundary"));

        assertThatCode(() -> plans.approveByStage(plan.id())).doesNotThrowAnyException();

        StageInstance dev = stages.findActiveStage(taskId).orElseThrow();
        assertThat(dev.type()).isEqualTo(StageType.DEVELOPMENT_STAGE);
        assertThat(stages.findStageById(plan.id()).orElseThrow().state())
                .isEqualTo(StageState.CLOSED);
        assertThat(tasks.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.IMPLEMENTING);

        List<ThreadTurn> durableTurns = turns.listTurnsByExactTaskIdAndStatus(
                taskId, ThreadTurnStatus.QUEUED, 10);
        if (durableTurns.isEmpty()) {
            durableTurns = turns.listTurnsByExactTaskIdAndStatus(
                    taskId, ThreadTurnStatus.RUNNING, 10);
        }
        assertThat(durableTurns).hasSize(1);
        ThreadTurn kickoff = durableTurns.get(0);
        assertThat(kickoff.stageId()).isEqualTo(dev.id().toString());
        assertThat(kickoff.agentRunId()).isNotBlank();
        AgentRun run = runs.findById(kickoff.agentRunId()).orElseThrow();
        assertThat(run.kind()).isEqualTo(AgentRun.KIND_DEV);
        assertThat(run.stageId()).isEqualTo(dev.id().toString());
        assertThat(run.threadId()).isEqualTo(kickoff.threadId());
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-07-27T03:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Plan approval boundary", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD,
                "ws-default", null, null);
        threads.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        tasks.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
