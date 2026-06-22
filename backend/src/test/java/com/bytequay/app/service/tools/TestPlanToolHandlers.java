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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.tools.PlanToolHandlers.RecordPlanArgs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brain's {@code record_plan} write tool: it writes a PLAN_RECORDED event
 * on the task's open PlanStage, enforces the server-assigned source (brain
 * for the first, brain-revision after), and refuses when no PlanStage is open.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestPlanToolHandlers
{
    private static final ToolCall CALL = new ToolCall("brain-thread", null, AgentRole.TASK);

    @Autowired
    private PlanToolHandlers tools;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void firstPlanIsSourcedBrainAndWritesAPlanRecordedEvent()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);

        ToolOutcome.Completed out = completed(tools.recordPlan(
                new RecordPlanArgs(taskId, planJson("finalized")), CALL));

        assertThat(out.isError()).isFalse();
        assertThat(out.text()).contains("\"source\":\"brain\"").contains("\"status\":\"finalized\"");
        List<StageEvent> recorded = stageStore.findEventsByStage(plan.id()).stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_RECORDED).toList();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).payloadJson()).contains("\"id\":").contains("\"plannedAt\":");
    }

    @Test
    void secondPlanOnTheSameStageIsARevision()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.PLAN_STAGE, null);

        tools.recordPlan(new RecordPlanArgs(taskId, planJson("suggested")), CALL);
        ToolOutcome.Completed revision = completed(tools.recordPlan(
                new RecordPlanArgs(taskId, planJson("finalized")), CALL));

        assertThat(revision.text()).contains("\"source\":\"brain-revision\"");
    }

    @Test
    void recordingWithoutAnOpenPlanStageErrors()
    {
        String taskId = seedTask();
        // No PlanStage opened.
        ToolOutcome.Completed out = completed(tools.recordPlan(
                new RecordPlanArgs(taskId, planJson("finalized")), CALL));

        assertThat(out.isError()).isTrue();
        assertThat(out.text()).contains("no open PlanStage");
    }

    @Test
    void readPlanSummaryReturnsTheLatestFinalizedPlan()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        tools.recordPlan(new RecordPlanArgs(taskId, planJson("suggested")), CALL);
        tools.recordPlan(new RecordPlanArgs(taskId, planJson("finalized")), CALL);

        ToolOutcome.Completed out = completed(tools.readPlanSummary(
                new PlanToolHandlers.ReadPlanSummaryArgs(taskId), CALL));

        assertThat(out.isError()).isFalse();
        assertThat(out.text())
                .contains("\"plan\":")
                .contains("\"status\":\"finalized\"")
                .contains("\"conversationSummaries\":[]");
    }

    @Test
    void readPlanSummaryErrorsWhenNoFinalizedPlan()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        tools.recordPlan(new RecordPlanArgs(taskId, planJson("suggested")), CALL);

        ToolOutcome.Completed out = completed(tools.readPlanSummary(
                new PlanToolHandlers.ReadPlanSummaryArgs(taskId), CALL));

        assertThat(out.isError()).isTrue();
    }

    @Test
    void notePlanConcernWritesAFollowupEvent()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);

        ToolOutcome.Completed out = completed(tools.notePlanConcern(
                new PlanToolHandlers.NotePlanConcernArgs(taskId, "the retry default is wrong"), CALL));

        assertThat(out.isError()).isFalse();
        assertThat(out.text()).contains("\"eventId\":");
        assertThat(stageStore.findEventsByStage(plan.id()))
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo(StageEventType.PLAN_FOLLOWUP_NOTED);
                    assertThat(e.payloadJson()).contains("the retry default is wrong")
                            .contains("\"status\":\"open\"");
                });
    }

    @Test
    void recordingAfterTheStageClosedErrors()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        stageStore.closeStage(plan.id(), "approved");

        ToolOutcome.Completed out = completed(tools.recordPlan(
                new RecordPlanArgs(taskId, planJson("finalized")), CALL));

        assertThat(out.isError()).isTrue();
    }

    private JsonNode planJson(String status)
    {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", status);
        node.put("source", "ignored-server-overrides-this");
        node.set("understanding", mapper.createObjectNode().put("summary", "bump the retry default"));
        return node;
    }

    private static ToolOutcome.Completed completed(ToolOutcome outcome)
    {
        return (ToolOutcome.Completed) outcome;
    }

    @Test
    void readPlanConversationIncludesTheTrunkSeedThenBrainPlanning()
    {
        String taskId = seedTask();
        String threadId = taskStore.findTaskById(taskId).orElseThrow().threadId();
        // Trunk seed on the task thread, before the cut (task createdAt 09:00).
        threadStore.appendMessage(message(threadId, 1, "user", "let's tidy the assertj nits",
                Instant.parse("2026-06-20T08:59:00Z")));
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        // Brain thread + a planning turn inside the PlanStage window.
        String brainId = "ws-default.brain-" + UUID.randomUUID();
        threadStore.saveThread(new Thread(
                brainId, ThreadKind.BRAIN_AGENT, "anthropic", null, "Brain", ThreadStatus.IDLE,
                "claude-haiku-4-5-20251001", 0L, 0L, 0L, plan.openedAt(), plan.openedAt(), null, null,
                ThreadFlow.BUILD, "ws-default", null, null, null, List.of(), 1, taskId));
        threadStore.appendMessage(message(brainId, 1, "assistant", "here is the structured plan",
                plan.openedAt().plusSeconds(1)));

        ToolOutcome.Completed out = completed(tools.readPlanConversation(
                new PlanToolHandlers.ReadPlanConversationArgs(taskId, null, null), CALL));

        assertThat(out.isError()).isFalse();
        // Both halves of the full plan stage are present, trunk seed first.
        assertThat(out.text())
                .contains("let's tidy the assertj nits")
                .contains("here is the structured plan");
        assertThat(out.text().indexOf("let's tidy the assertj nits"))
                .isLessThan(out.text().indexOf("here is the structured plan"));
    }

    private static ThreadMessage message(String threadId, long seq, String role, String text, Instant ts)
    {
        return new ThreadMessage(
                "m" + seq + "-" + threadId, threadId, null, seq, role, "text",
                "{\"text\":\"" + text + "\"}", null, null, null, null, ts);
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Plan tools test", ThreadStatus.RUNNING, "claude-sonnet-4-6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
