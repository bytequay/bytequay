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
import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.ReviewRoundStore;
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
    @Autowired
    private ReviewRoundStore reviewRoundStore;

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
        appendStageMessage(threadId, taskId, 1, "assistant", "tool_call",
                "{\"name\":\"read_file\",\"path\":\"Foo.java\"}", open, stage.id().toString());
        // A user steering message in the window — drives interventionsCount.
        // Anchor at openedAt so it predates the open stage's wall-clock window end.
        appendStageMessage(threadId, taskId, 2, "user", "text",
                "{\"text\":\"bump the retry default\",\"managedSkills\":[\"ponytail\"]}",
                open, stage.id().toString());
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
        assertThat(detail.conversation()).anySatisfy(r -> {
            assertThat(r.kind()).isEqualTo("user");
            assertThat(r.managedSkills()).containsExactly("ponytail");
        });

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
        // A dev-agent text turn + a tool call, stamped with this stage.
        appendStageMessage(threadId, taskId, 1, "assistant", "text",
                "{\"text\":\"Removing the unused import.\"}", open, stage.id().toString());
        appendStageMessage(threadId, taskId, 2, "assistant", "tool_call",
                "{\"name\":\"read_file\",\"path\":\"Foo.java\"}", open, stage.id().toString());

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("agent") && "Removing the unused import.".equals(r.text()));
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("tool_call") && "read_file".equals(r.toolLabel()));
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("iteration_marker") && r.iterationNumber() == 1);
    }

    @Test
    void developmentConversationProjectsRemotePullRequestCreation()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        stageStore.recordEvent(stage.id(), taskId, StageEventType.PULL_REQUEST_CREATED, Map.of(
                "branch", "feature/timeline",
                "baseBranch", "main",
                "number", 145,
                "url", "https://github.com/acme/widget/pull/145",
                "additions", 12,
                "deletions", 3));

        StageDetailData detail = detailService.getDetail(stage.id());

        StageDetailData.ConversationRow created = detail.conversation().stream()
                .filter(row -> row.kind().equals("pull_request_created"))
                .findFirst().orElseThrow();
        assertThat(created.text()).isEqualTo("PR pushed successfully");
        assertThat(created.pullRequest()).isNotNull();
        assertThat(created.pullRequest().phase()).isEqualTo("created");
        assertThat(created.pullRequest().branch()).isEqualTo("feature/timeline");
        assertThat(created.pullRequest().baseBranch()).isEqualTo("main");
        assertThat(created.pullRequest().number()).isEqualTo(145);
        assertThat(created.pullRequest().additions()).isEqualTo(12);
        assertThat(created.pullRequest().deletions()).isEqualTo(3);
    }

    @Test
    void developmentPhasesKeepValidationDoneAndShowAParkedBrainReview()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        Instant now = stage.openedAt();
        taskStore.appendPhaseEvent(
                taskId, TaskPhase.VALIDATING, TaskPhase.INTERNAL_REVIEW,
                now, "validation_passed", Actor.AGENT);
        taskStore.updatePhase(taskId, TaskPhase.NEEDS_ATTENTION);
        reviewRoundStore.insert(new ReviewRound(
                UUID.randomUUID().toString(), taskId, 1, List.of(),
                ReviewRound.STATUS_PAUSED, ReviewRound.ReviewRoundStats.empty(), null,
                now, null, null, ReviewRound.ORIGIN_BRAIN, null, 1,
                ReviewRound.DEFAULT_BRAIN_BUDGET));

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.devPhases())
                .filteredOn(phase -> phase.key().equals("validation"))
                .singleElement()
                .extracting(TaskBrainViewData.DevPhase::status)
                .isEqualTo("done");
        assertThat(detail.devPhases())
                .filteredOn(phase -> phase.key().equals("brainReview"))
                .singleElement()
                .satisfies(phase -> {
                    assertThat(phase.status()).isEqualTo("future");
                    assertThat(phase.meta()).isEqualTo("review failed");
                });
    }

    @Test
    void developmentConversationProjectsPullRequestPreparation()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        stageStore.recordEvent(stage.id(), taskId, StageEventType.PULL_REQUEST_PROGRESS, Map.of(
                "phase", "starting", "branch", "feature/timeline", "baseBranch", "main"));

        StageDetailData.ConversationRow progress = detailService.getDetail(stage.id()).conversation().stream()
                .filter(row -> row.kind().equals("pull_request_progress"))
                .findFirst().orElseThrow();

        assertThat(progress.text()).isEqualTo("Starting pull request");
        assertThat(progress.pullRequest().phase()).isEqualTo("starting");
        assertThat(progress.pullRequest().branch()).isEqualTo("feature/timeline");
    }

    @Test
    void developmentConversationProjectsTerminalPullRequestPublishFailure()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        stageStore.recordEvent(stage.id(), taskId, StageEventType.PULL_REQUEST_PROGRESS, Map.of(
                "phase", "failed",
                "branch", "feature/timeline",
                "baseBranch", "main",
                "failedStep", "push_branch",
                "reason", "remote rejected the push"));

        StageDetailData.ConversationRow failure = detailService.getDetail(stage.id()).conversation().stream()
                .filter(row -> row.kind().equals("pull_request_progress"))
                .findFirst().orElseThrow();

        assertThat(failure.text()).isEqualTo("PR push failed");
        assertThat(failure.pullRequest().phase()).isEqualTo("failed");
        assertThat(failure.pullRequest().failedStep()).isEqualTo("push_branch");
        assertThat(failure.pullRequest().reason()).isEqualTo("remote rejected the push");
    }

    @Test
    void conversationMergesRowsFromTheDecoupledStageStore()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        // A turn written to the NEW stage_messages store (its own seq 0) must
        // still surface in the stage's conversation — the read-side merge.
        threadStore.appendStageMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, taskId, 0L,
                "assistant", "text", "{\"text\":\"From the stage store.\"}",
                null, 100L, 50L, 5L, stage.openedAt(), stage.id().toString(), ThreadScope.STAGE));

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("agent") && "From the stage store.".equals(r.text()));
    }

    @Test
    void toolCallDetailComesFromTheNestedInputArgs()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        Instant open = stage.openedAt();
        // The real claude-code shape: name under toolName, args nested in input.
        appendStageMessage(threadId, taskId, 1, "tool", "tool_call",
                "{\"callId\":\"c1\",\"toolName\":\"Read\",\"input\":{\"file_path\":\"CostMeter.tsx\"}}",
                open, stage.id().toString());
        appendStageMessage(threadId, taskId, 2, "tool", "tool_call",
                "{\"callId\":\"c2\",\"toolName\":\"Bash\",\"input\":{\"command\":\"grep -rn useMemo\"}}",
                open, stage.id().toString());

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("tool_call") && "CostMeter.tsx".equals(r.toolDetail()));
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("tool_call") && "grep -rn useMemo".equals(r.toolDetail()));
    }

    @Test
    void searchToolCallDetailPrefersThePatternOverTheScopedPath()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        Instant open = stage.openedAt();
        // A Grep carries both; showing the path renders every search in a run
        // as the same row, so the pattern has to win.
        appendStageMessage(threadId, taskId, 1, "tool", "tool_call",
                "{\"callId\":\"c1\",\"toolName\":\"Grep\","
                        + "\"input\":{\"pattern\":\"CodeGraphService\",\"path\":\"backend/src\"}}",
                open, stage.id().toString());

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("tool_call") && "CodeGraphService".equals(r.toolDetail()));
    }

    @Test
    void editToolCallSurfacesAnOldNewDiff()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        Instant open = stage.openedAt();
        appendStageMessage(threadId, taskId, 1, "tool", "tool_call",
                "{\"callId\":\"c1\",\"toolName\":\"Edit\",\"input\":{\"file_path\":\"Foo.md\","
                        + "\"old_string\":\"const x = 1\",\"new_string\":\"const x = 2\"}}",
                open, stage.id().toString());

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("tool_call")
                        && r.toolDiff() != null
                        && r.toolDiff().contains("- const x = 1")
                        && r.toolDiff().contains("+ const x = 2"));
    }

    @Test
    void toolCallPairsItsResultRowByCallId()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        Instant open = stage.openedAt();
        appendStageMessage(threadId, taskId, 1, "tool", "tool_call",
                "{\"callId\":\"c1\",\"toolName\":\"Bash\",\"input\":{\"command\":\"mvn verify\"}}",
                open, stage.id().toString());
        appendStageMessage(threadId, taskId, 2, "tool", "tool_result",
                "{\"callId\":\"c1\",\"isError\":true,\"output\":\"BUILD FAILURE\"}",
                open, stage.id().toString());

        StageDetailData detail = detailService.getDetail(stage.id());

        // The result is folded onto the tool_call row, not emitted standalone.
        assertThat(detail.conversation()).noneMatch(r -> r.kind().equals("tool_result"));
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("tool_call")
                        && "BUILD FAILURE".equals(r.toolResult())
                        && Boolean.TRUE.equals(r.toolError()));
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
                ThreadFlow.BUILD, "ws-default", null, null, 1, taskId));
        appendTrunkMessage(brainId, 1, "user", "text",
                "{\"text\":\"tidy the nits\"}", plan.openedAt());
        appendTrunkMessage(brainId, 2, "assistant", "text",
                "{\"text\":\"Here is the plan.\"}", plan.openedAt().plusSeconds(1));

        StageDetailData detail = detailService.getDetail(plan.id());

        // The plan stage's transcript is the brain conversation, not the dev thread.
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("user") && "tidy the nits".equals(r.text()));
        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("agent") && "Here is the plan.".equals(r.text()));
    }

    @Test
    void planStageConversationMergesItsSelfReviewTranscriptChronologicallyWithoutDuplicates()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        String brainId = "ws-default.brain-" + UUID.randomUUID();
        threadStore.saveThread(new Thread(
                brainId, ThreadKind.BRAIN_AGENT, "anthropic", null, "Brain", ThreadStatus.IDLE,
                "claude-haiku-4-5-20251001", 0L, 0L, 0L, plan.openedAt(), plan.openedAt(), null, null,
                ThreadFlow.BUILD, "ws-default", null, null, 1, taskId));
        appendTrunkMessage(brainId, 1, "assistant", "text",
                "{\"text\":\"Planning complete.\"}", plan.openedAt().plusSeconds(2));

        ThreadMessage selfReview = new ThreadMessage(
                UUID.randomUUID().toString(), brainId, taskId, 0L,
                "assistant", "text", "{\"text\":\"Checking the finalized plan.\"}",
                null, 100L, 50L, 5L, plan.openedAt().plusSeconds(1),
                plan.id().toString(), ThreadScope.STAGE);
        threadStore.appendStageMessage(selfReview);
        // Simulate a transition/backfill overlap: the same durable message
        // may still exist on the brain thread while also in stage_messages.
        threadStore.appendMessage(selfReview);

        StageInstance other = stageStore.openStage(taskId, StageType.REVIEW_STAGE, null);
        threadStore.appendStageMessage(new ThreadMessage(
                UUID.randomUUID().toString(), brainId, taskId, 0L,
                "assistant", "text", "{\"text\":\"Other stage log.\"}",
                null, 100L, 50L, 5L, plan.openedAt(),
                other.id().toString(), ThreadScope.STAGE));

        StageDetailData detail = detailService.getDetail(plan.id());

        assertThat(detail.conversation().stream()
                .filter(row -> row.text() != null)
                .map(StageDetailData.ConversationRow::text))
                .containsExactly("Checking the finalized plan.", "Planning complete.");
    }

    @Test
    void conversationIncludesOnlyRowsStampedWithThisStagesId()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        Instant open = stage.openedAt();
        iterationStore.save(TaskStageIteration
                .opened(UUID.randomUUID(), stage.id(), taskId, "turn-1", 1, "red_ci", open));

        // Stamped with THIS stage but dated a day before it opened — still
        // belongs, because stage_id is the source of truth, not the clock.
        appendStageMessage(threadId, taskId, 1, "assistant", "text",
                "{\"text\":\"mine despite the old timestamp\"}",
                open.minusSeconds(86_400), stage.id().toString());
        // Stamped with a DIFFERENT stage but inside this stage's own window —
        // must still be excluded; only the stage_id match counts.
        StageInstance other = stageStore.openStage(taskId, StageType.REVIEW_STAGE, null);
        appendStageMessage(threadId, taskId, 2, "assistant", "text",
                "{\"text\":\"belongs to another stage\"}",
                open, other.id().toString());

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.conversation()).anyMatch(
                r -> r.kind().equals("agent") && "mine despite the old timestamp".equals(r.text()));
        assertThat(detail.conversation()).noneMatch(
                r -> "belongs to another stage".equals(r.text()));
    }

    @Test
    void iterationLogExcludesTrunkMessagesTypedWhileTheIterationWasOpen()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        Instant open = stage.openedAt();
        iterationStore.save(TaskStageIteration
                .opened(UUID.randomUUID(), stage.id(), taskId, "turn-1", 1, "red_ci", open));

        // A trunk-chat message (no task focused) timed inside iteration #1's
        // window — the per-iteration log must not sweep it in either, the
        // same leak buildConversation() had before it was scoped by stage id.
        appendTrunkMessage(threadId, 1, "user", "text",
                "{\"text\":\"trunk-level chat, not this iteration\"}", open);
        appendStageMessage(threadId, taskId, 2, "user", "text",
                "{\"text\":\"the iteration's own steering message\"}", open, stage.id().toString());

        StageDetailData detail = detailService.getDetail(stage.id());

        StageDetailData.IterationDetail iter1 = detail.iterations().get(0);
        assertThat(iter1.log()).noneMatch(r -> r.kind().equals("user_message")
                && "trunk-level chat, not this iteration".equals(r.userMessage().text()));
        assertThat(iter1.log()).anyMatch(r -> r.kind().equals("user_message")
                && "the iteration's own steering message".equals(r.userMessage().text()));
    }

    @Test
    void conversationExcludesTrunkMessagesTypedWhileTheStageWasOpen()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        Instant open = stage.openedAt();
        // A message typed on the thread's trunk chat (no task focused) whose
        // timestamp happens to fall inside this stage's open window — must
        // not be swept in as if it were the stage's own transcript.
        appendTrunkMessage(threadId, 1, "user", "text",
                "{\"text\":\"trunk-level chat, not this stage\"}", open);
        appendStageMessage(threadId, taskId, 2, "assistant", "text",
                "{\"text\":\"the stage's own reply\"}", open, stage.id().toString());

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.conversation()).noneMatch(
                r -> "trunk-level chat, not this stage".equals(r.text()));
        assertThat(detail.conversation()).anyMatch(
                r -> "the stage's own reply".equals(r.text()));
    }

    @Test
    void conversationExcludesAnotherTasksMessagesOnTheSameThread()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        String otherTaskId = seedTask(threadId, 2L);
        StageInstance stage = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        Instant open = stage.openedAt();
        // A sibling task's message on the same thread, timed inside this
        // stage's window — must not leak into this stage's transcript either.
        appendTaskMessage(threadId, otherTaskId, 1, "assistant", "text",
                "{\"text\":\"belongs to the other task\"}", open);

        StageDetailData detail = detailService.getDetail(stage.id());

        assertThat(detail.conversation()).noneMatch(
                r -> "belongs to the other task".equals(r.text()));
    }

    @Test
    void unknownStageIs404()
    {
        assertThatThrownBy(() -> detailService.getDetail(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    private void appendTrunkMessage(
            String threadId, long seq, String role, String type, String json, Instant ts)
    {
        threadStore.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, null, seq, role, type, json,
                null, 100L, 50L, 5L, ts, null, ThreadScope.TRUNK));
    }

    private void appendTaskMessage(
            String threadId, String taskId, long seq, String role, String type, String json, Instant ts)
    {
        threadStore.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, taskId, seq, role, type, json,
                null, 100L, 50L, 5L, ts, null, ThreadScope.TASK));
    }

    private void appendStageMessage(
            String threadId, String taskId, long seq, String role, String type, String json,
            Instant ts, String stageId)
    {
        threadStore.appendStageMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, taskId, seq, role, type, json,
                null, 100L, 50L, 5L, ts, stageId, ThreadScope.STAGE));
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
        return seedTask(threadId, 1L);
    }

    private String seedTask(String threadId, long seq)
    {
        Instant now = Instant.parse("2026-06-21T09:00:00Z");
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, threadId, seq, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
