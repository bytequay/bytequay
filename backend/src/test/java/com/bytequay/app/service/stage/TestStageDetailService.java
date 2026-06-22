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

import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Composes the stage-detail payload from real seeded data: iteration bands
 * with time-windowed tool calls + summaries, the derivable metrics subset
 * (uncomputed fields omitted), CI-fix history from iteration summaries, and
 * null realtime CI for a task with no linked PR.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestStageDetailService
{
    @Autowired
    private StageDetailService detailService;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private IterationStore iterationStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void composesIterationBandsToolCallsSummariesAndMetrics()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        Instant open = stage.openedAt();

        // Two iterations, generous windows so seeded rows land inside iter #1.
        iterationStore.save(TaskStageIteration
                .opened(UUID.randomUUID(), stage.id(), taskId, "turn-1", 1, "red_ci", open)
                .withEnded(open.plusSeconds(3600), "push_completed")
                .withSummary("fix #1: bumped retry default", open.plusSeconds(3500)));
        iterationStore.save(TaskStageIteration
                .opened(UUID.randomUUID(), stage.id(), taskId, "turn-2", 2, "red_ci",
                        open.plusSeconds(3601)));

        // A dev-thread tool call inside both iter #1's window and the
        // (open) stage's [openedAt, now] window — anchor at openedAt so it
        // predates the query's wall-clock now.
        appendMessage(threadId, taskId, 1, "assistant", "tool_call",
                "{\"name\":\"read_file\",\"path\":\"Foo.java\"}", open);
        // A user steering message in the window — drives interventionsCount.
        // Anchor at openedAt so it predates the open stage's wall-clock window end.
        appendMessage(threadId, taskId, 2, "user", "text",
                "{\"text\":\"bump the retry default\"}", open);
        // A stage event (recorded ~now, inside iter #1's window) so the
        // iteration log surfaces a stage_event row.
        stageStore.recordEvent(stage.id(), taskId, StageEventType.NOTIFY_FIRED,
                Map.of("reason", "ready_for_merge"));

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.stage().type()).isEqualTo("CI_FIXING_STAGE");
        assertThat(detail.stage().iterationCount()).isEqualTo(2);
        assertThat(detail.iterations()).hasSize(2);

        StageDetailData.IterationDetail iter1 = detail.iterations().get(0);
        assertThat(iter1.summaryText()).isEqualTo("fix #1: bumped retry default");
        assertThat(iter1.recordedBy()).isEqualTo("agent");
        // The read tool call groups into a 'code' operation card with the
        // tool call nested inside.
        StageDetailData.LogRow op = iter1.log().stream()
                .filter(r -> r.kind().equals("operation")).findFirst().orElseThrow();
        assertThat(op.operation().operation()).isEqualTo("code");
        assertThat(op.operation().toolCalls()).anyMatch(r -> r.kind().equals("tool_call"));
        assertThat(iter1.log()).anyMatch(r -> r.kind().equals("iteration_summary"));
        assertThat(iter1.log()).anyMatch(r -> r.kind().equals("stage_event"));

        StageDetailData.StageMetricsSubset m = detail.stage().metrics();
        assertThat(m.loopIterations()).isEqualTo(2);
        assertThat(m.toolCallsCount()).isEqualTo(1);
        assertThat(m.panelInvocationsCount()).isZero();
        // New metrics: the read tool call infers a 'code' operation run; the
        // user message counts as one intervention.
        assertThat(m.operationsCount()).containsEntry("code", 1);
        assertThat(m.interventionsCount()).isEqualTo(1);
        assertThat(m.backflowsCount()).isZero();
        assertThat(m.activeTimeSec()).isNotNull();
        assertThat(m.waitingUserTimeSec()).isNotNull();

        // CI-fix history is the simple iteration-summary list (no fabrication).
        assertThat(detail.ciFixHistory()).hasSize(2);
        assertThat(detail.ciFixHistory().get(0).summaryText()).isEqualTo("fix #1: bumped retry default");

        // No linked PR → null realtime CI; navigator includes the stage.
        assertThat(detail.realtimeCi()).isNull();
        assertThat(detail.allStages()).anyMatch(s -> s.id().equals(stage.id().toString()));
    }

    @Test
    void ciFixHistorySurfacesTheEnrichedFailingCheckDetail()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        Instant open = stage.openedAt();
        iterationStore.save(TaskStageIteration
                .opened(UUID.randomUUID(), stage.id(), taskId, "turn-1", 1, "red_ci", open)
                .withSummary("fix #1", open.plusSeconds(60)));
        // The enriched LOOP_ITERATION_STARTED event for iteration #1.
        stageStore.recordEvent(stage.id(), taskId, StageEventType.LOOP_ITERATION_STARTED, Map.of(
                "iterationNumber", 1,
                "trigger", "red_ci",
                "failedCheck", "frontend / lint",
                "errorMessage", "ESLint: 3 problems",
                "actionsRunUrl", "https://github.com/acme/widget/actions/runs/42"));

        StageDetailData detail = detailService.getDetail(stage.id());

        StageDetailData.CiFixHistoryEntry entry = detail.ciFixHistory().get(0);
        assertThat(entry.failedCheck()).isEqualTo("frontend / lint");
        assertThat(entry.errorMessage()).isEqualTo("ESLint: 3 problems");
        assertThat(entry.actionsRunUrl()).contains("/actions/runs/42");
    }

    @Test
    void conversationCarriesAgentTurnsToolCallsAndIterationMarkers()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        Instant open = stage.openedAt();
        iterationStore.save(TaskStageIteration
                .opened(UUID.randomUUID(), stage.id(), taskId, "turn-1", 1, "red_ci", open));
        // A dev-agent text turn + a tool call on the task thread, in the window.
        appendMessage(threadId, taskId, 1, "assistant", "text",
                "{\"text\":\"Removing the unused import.\"}", open);
        appendMessage(threadId, taskId, 2, "assistant", "tool_call",
                "{\"name\":\"read_file\",\"path\":\"Foo.java\"}", open);

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("agent") && "Removing the unused import.".equals(r.text()));
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("tool_call") && "read_file".equals(r.toolLabel()));
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("iteration_marker") && r.iterationNumber() == 1);
    }

    @Test
    void planStageConversationReadsTheBrainThread()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        String brainId = "ws-default.brain-" + UUID.randomUUID();
        threadStore.saveThread(new Thread(
                brainId, ThreadKind.BRAIN_AGENT, "anthropic", null, "Brain", ThreadStatus.IDLE,
                "claude-haiku-4-5-20251001", 0L, 0L, 0L, plan.openedAt(), plan.openedAt(), null, null,
                ThreadFlow.BUILD, "ws-default", null, null, null, List.of(), 1, taskId));
        appendMessage(brainId, null, 1, "user", "text",
                "{\"text\":\"tidy the nits\"}", plan.openedAt());
        appendMessage(brainId, null, 2, "assistant", "text",
                "{\"text\":\"Here is the plan.\"}", plan.openedAt().plusSeconds(1));

        StageDetailData detail = detailService.getDetail(plan.id());

        // The plan stage's transcript is the brain conversation, not the dev thread.
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("user") && "tidy the nits".equals(r.text()));
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("agent") && "Here is the plan.".equals(r.text()));
    }

    @Test
    void unknownStageIs404()
    {
        assertThatThrownBy(() -> detailService.getDetail(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    private void appendMessage(
            String threadId, String taskId, long seq, String role, String type, String json, Instant ts)
    {
        threadStore.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, taskId, seq, role, type, json,
                null, 100L, 50L, 5L, ts));
    }

    private String seedThread()
    {
        Instant now = Instant.parse("2026-06-21T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Detail test", ThreadStatus.RUNNING, "claude-sonnet-4-6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        return thread.id();
    }

    private String seedTask(String threadId)
    {
        Instant now = Instant.parse("2026-06-21T09:00:00Z");
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, threadId, 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
