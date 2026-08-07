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

import com.bytequay.app.beans.stage.BrainFeedRow;
import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.sqlite.IterationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brain endpoint surfaces live values computed from the stage-event
 * stream: autonomous-push totals, the current budget slice, the
 * budget-exhaustion approval card, the ready-to-merge state, and the
 * matching brain-feed rows.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestStageBrain
{
    @Autowired
    private StageService stageService;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private IterationService iterationService;
    @Autowired
    private IterationStore iterationStore;
    @Autowired
    private ThreadTurnStore threadTurnStore;

    @Test
    void brainFeedIncludesIterationSummariesInChronologicalOrder()
    {
        String taskId = seedTask();
        String threadId = taskStore.findTaskById(taskId).orElseThrow().threadId();
        stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        // Real monitor turns so the summary turn-event's turn_id FK holds.
        seedTurn("turn-a", threadId, taskId);
        seedTurn("turn-b", threadId, taskId);

        // Two iterations, each summarised in-line.
        iterationService.begin(taskId, "turn-a", IterationService.TRIGGER_RED_CI);
        UUID iterA = iterationStore.findByTurnId("turn-a").orElseThrow().id();
        iterationService.recordSummary(iterA, "fix #1: bumped retry default");
        iterationService.begin(taskId, "turn-b", IterationService.TRIGGER_RED_CI);
        UUID iterB = iterationStore.findByTurnId("turn-b").orElseThrow().id();
        iterationService.recordSummary(iterB, "fix #2: widened timeout");

        TaskBrainViewData brain = stageService.getBrain(taskId);

        List<String> summaryBodies = brain.brainFeed().stream()
                .filter(r -> r.type().equals("ITERATION_SUMMARY"))
                .map(BrainFeedRow::body)
                .toList();
        assertThat(summaryBodies)
                .containsExactly("fix #1: bumped retry default", "fix #2: widened timeout");
        // Interleaved with the stage-open row, all chronological.
        assertThat(brain.brainFeed().get(0).type()).isEqualTo("STAGE_OPENED");
    }

    @Test
    void brainFeedProjectsRemotePullRequestCreationFromDevelopment()
    {
        String taskId = seedTask();
        StageInstance development = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        stageStore.recordEvent(development.id(), taskId, StageEventType.PULL_REQUEST_CREATED, Map.of(
                "branch", "feature/timeline",
                "baseBranch", "main",
                "number", 145,
                "url", "https://github.com/acme/widget/pull/145",
                "additions", 12,
                "deletions", 3));

        BrainFeedRow created = stageService.getBrain(taskId).brainFeed().stream()
                .filter(row -> row.type().equals("PUSHED_PR_CREATED"))
                .findFirst().orElseThrow();

        assertThat(created.stageId()).isEqualTo(development.id().toString());
        assertThat(created.body()).isEqualTo("PR pushed successfully");
        assertThat(created.pullRequest()).isNotNull();
        assertThat(created.pullRequest().phase()).isEqualTo("created");
        assertThat(created.pullRequest().branch()).isEqualTo("feature/timeline");
        assertThat(created.pullRequest().baseBranch()).isEqualTo("main");
        assertThat(created.pullRequest().number()).isEqualTo(145);
        assertThat(created.pullRequest().additions()).isEqualTo(12);
        assertThat(created.pullRequest().deletions()).isEqualTo(3);
    }

    @Test
    void brainFeedProjectsPullRequestPreparationFromDevelopment()
    {
        String taskId = seedTask();
        StageInstance development = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        stageStore.recordEvent(development.id(), taskId, StageEventType.PULL_REQUEST_PROGRESS, Map.of(
                "phase", "creating-draft", "branch", "feature/timeline", "baseBranch", "main"));

        BrainFeedRow progress = stageService.getBrain(taskId).brainFeed().stream()
                .filter(row -> row.type().equals("PULL_REQUEST_PROGRESS"))
                .findFirst().orElseThrow();

        assertThat(progress.body()).isEqualTo("Creating draft");
        assertThat(progress.pullRequest().phase()).isEqualTo("creating-draft");
        assertThat(progress.pullRequest().branch()).isEqualTo("feature/timeline");
        assertThat(progress.pullRequest().baseBranch()).isEqualTo("main");
    }

    @Test
    void brainFeedProjectsTerminalPullRequestPublishFailureFromDevelopment()
    {
        String taskId = seedTask();
        StageInstance development = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        stageStore.recordEvent(development.id(), taskId, StageEventType.PULL_REQUEST_PROGRESS, Map.of(
                "phase", "failed",
                "branch", "feature/timeline",
                "baseBranch", "main",
                "failedStep", "ensure_pull_request",
                "reason", "GitHub returned 403"));

        BrainFeedRow failure = stageService.getBrain(taskId).brainFeed().stream()
                .filter(row -> row.type().equals("PULL_REQUEST_PROGRESS"))
                .findFirst().orElseThrow();

        assertThat(failure.body()).isEqualTo("PR push failed");
        assertThat(failure.pullRequest().phase()).isEqualTo("failed");
        assertThat(failure.pullRequest().failedStep()).isEqualTo("ensure_pull_request");
        assertThat(failure.pullRequest().reason()).isEqualTo("GitHub returned 403");
    }

    @Test
    void brainFeedProjectsPlanSelfReviewStartAndFinish()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_SELF_REVIEW_STARTED,
                Map.of("iteration", 1));
        stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved"));

        List<BrainFeedRow> reviewRows = stageService.getBrain(taskId).brainFeed().stream()
                .filter(row -> row.type().startsWith("PLAN_SELF_REVIEW"))
                .toList();

        assertThat(reviewRows).extracting(BrainFeedRow::type).containsExactly(
                "PLAN_SELF_REVIEW_STARTED", "PLAN_SELF_REVIEWED");
        assertThat(reviewRows).extracting(BrainFeedRow::body).containsExactly(
                "Brain started mandatory plan self-review",
                "Brain finished mandatory plan self-review · approved");
        assertThat(reviewRows).allSatisfy(row ->
                assertThat(row.stageId()).isEqualTo(plan.id().toString()));
    }

    @Test
    void brainFeedIncludesConversationWithStageRefAndUserScrubber()
    {
        String taskId = seedTask();
        UUID stageId = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null).id();

        // A brain thread with a user question and an assistant reply that
        // mentions the stage by name.
        String brainThreadId = "ws-default.brain-" + UUID.randomUUID();
        Thread brain = new Thread(
                brainThreadId, ThreadKind.BRAIN_AGENT, "anthropic", null, "Brain",
                ThreadStatus.IDLE, "claude-haiku-4-5-20251001", 0L, 0L, 0L,
                Instant.parse("2026-06-21T10:00:00Z"), Instant.parse("2026-06-21T10:00:00Z"),
                null, null, ThreadFlow.BUILD, "ws-default", null, null,
                1, taskId);
        threadStore.saveThread(brain);
        appendBrainMsg(brainThreadId, 1, "user", "How many pushes have we done?",
                Instant.parse("2026-06-21T10:01:00Z"));
        appendBrainMsg(brainThreadId, 2, "assistant",
                "CiFixingStage iteration #1 pushed the retry-count fix.",
                Instant.parse("2026-06-21T10:01:30Z"));

        TaskBrainViewData brainView = stageService.getBrain(taskId);

        BrainFeedRow userRow = brainView.brainFeed().stream()
                .filter(r -> r.type().equals("USER_MESSAGE")).findFirst().orElseThrow();
        assertThat(userRow.body()).isEqualTo("How many pushes have we done?");

        BrainFeedRow agentRow = brainView.brainFeed().stream()
                .filter(r -> r.type().equals("BRAIN_AGENT_RESPONSE")).findFirst().orElseThrow();
        assertThat(agentRow.body()).contains("CiFixingStage");
        assertThat(agentRow.referencedStageId()).isEqualTo(stageId.toString());

        assertThat(brainView.scrubbers().userMessages()).hasSize(1);
        assertThat(brainView.scrubbers().userMessages().get(0).label())
                .contains("How many pushes");
    }

    @Test
    void brainFeedCarriesAttachedImagePathsOnAUserMessageRowOnly()
    {
        String taskId = seedTask();
        String brainThreadId = "ws-default.brain-" + UUID.randomUUID();
        Thread brain = new Thread(
                brainThreadId, ThreadKind.BRAIN_AGENT, "anthropic", null, "Brain",
                ThreadStatus.IDLE, "claude-haiku-4-5-20251001", 0L, 0L, 0L,
                Instant.parse("2026-06-21T10:00:00Z"), Instant.parse("2026-06-21T10:00:00Z"),
                null, null, ThreadFlow.BUILD, "ws-default", null, null,
                1, taskId);
        threadStore.saveThread(brain);
        threadStore.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), brainThreadId, null, 1, "user", "text",
                "{\"text\":\"see this\",\"images\":[\"/tmp/attachments/a.png\"],"
                        + "\"managedSkills\":[\"ponytail-review\"]}",
                null, null, null, null, Instant.parse("2026-06-21T10:01:00Z"),
                null, ThreadScope.TRUNK));
        appendBrainMsg(brainThreadId, 2, "assistant", "Got it.", Instant.parse("2026-06-21T10:01:30Z"));

        TaskBrainViewData brainView = stageService.getBrain(taskId);

        BrainFeedRow userRow = brainView.brainFeed().stream()
                .filter(r -> r.type().equals("USER_MESSAGE")).findFirst().orElseThrow();
        assertThat(userRow.images()).containsExactly("/tmp/attachments/a.png");
        assertThat(userRow.managedSkills()).containsExactly("ponytail-review");

        BrainFeedRow agentRow = brainView.brainFeed().stream()
                .filter(r -> r.type().equals("BRAIN_AGENT_RESPONSE")).findFirst().orElseThrow();
        assertThat(agentRow.images()).isEmpty();
        assertThat(agentRow.managedSkills()).isEmpty();
    }

    @Test
    void brainFeedReadsOnlyTheBrainThreadNotTheDevThread()
    {
        String taskId = seedTask();
        String devThreadId = taskStore.findTaskById(taskId).orElseThrow().threadId();
        // A dev-agent turn on the task/dev thread must NOT surface in the brain
        // feed — the feed reads only the brain thread; dev work shows as stage
        // checkpoints, not as transcript.
        appendBrainMsg(devThreadId, 1, "assistant", "Now I'll make the six edits.",
                Instant.parse("2026-06-20T10:00:00Z"));
        // The brain thread carries the plan-stage conversation (the trunk seed
        // copied in + the brain's planning).
        String brainId = "ws-default.brain-" + UUID.randomUUID();
        threadStore.saveThread(new Thread(
                brainId, ThreadKind.BRAIN_AGENT, "anthropic", null, "Brain", ThreadStatus.IDLE,
                "claude-haiku-4-5-20251001", 0L, 0L, 0L,
                Instant.parse("2026-06-20T09:00:00Z"), Instant.parse("2026-06-20T09:00:00Z"),
                null, null, ThreadFlow.BUILD, "ws-default", null, null, 1, taskId));
        appendBrainMsg(brainId, 1, "assistant", "Here is the plan.",
                Instant.parse("2026-06-20T09:01:00Z"));

        List<String> bodies = stageService.getBrain(taskId).brainFeed().stream()
                .map(BrainFeedRow::body)
                .toList();

        assertThat(bodies).contains("Here is the plan.");
        assertThat(bodies).doesNotContain("Now I'll make the six edits.");
    }

    private void appendBrainMsg(String threadId, long seq, String role, String text, Instant ts)
    {
        String contentJson = "{\"text\":\"" + text.replace("\"", "\\\"") + "\"}";
        threadStore.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, null, seq, role, "text", contentJson,
                null, null, null, null, ts, null, ThreadScope.TRUNK));
    }

    @Test
    void brainFeedMapsAClosedReviewStageToPanelReviewCompleted()
    {
        String taskId = seedTask();
        StageInstance parent = stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        StageInstance review = stageStore.openStage(taskId, StageType.REVIEW_STAGE, parent.id());
        stageStore.closeStage(review.id(), "review_pass_terminated",
                Map.of("seatNames", List.of("Claude", "GPT-5"), "findingCount", 3, "agreedCount", 2));

        TaskBrainViewData brain = stageService.getBrain(taskId);

        BrainFeedRow panel = brain.brainFeed().stream()
                .filter(r -> r.type().equals("PANEL_REVIEW_COMPLETED"))
                .findFirst().orElseThrow();
        assertThat(panel.referencedStageId()).isEqualTo(review.id().toString());
        assertThat(panel.body()).contains("Claude", "GPT-5").contains("2 of 3");
        // The generic STAGE_CLOSED row is suppressed for review stages — the
        // panel entry replaces it.
        assertThat(brain.brainFeed()).noneMatch(r ->
                r.type().equals("STAGE_CLOSED") && r.stageId().equals(review.id().toString()));
    }

    @Test
    void rightRailIsPanelSpawnableInInternalReviewWithAPr()
    {
        String taskId = seedTask();
        StageInstance active = stageStore.openStage(
                taskId, StageType.DEVELOPMENT_STAGE, null);
        taskStore.saveTask(taskStore.findTaskById(taskId).orElseThrow().withPrNumber(42));
        taskStore.updatePhase(taskId, TaskPhase.INTERNAL_REVIEW);

        TaskBrainViewData brain = stageService.getBrain(taskId);

        assertThat(brain.rightRail().panelSpawnable()).isTrue();
        assertThat(brain.rightRail().parentStageId()).isEqualTo(active.id().toString());
    }

    @Test
    void rightRailIsNotPanelSpawnableWithoutAPr()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        taskStore.updatePhase(taskId, TaskPhase.INTERNAL_REVIEW);

        TaskBrainViewData brain = stageService.getBrain(taskId);

        assertThat(brain.rightRail().panelSpawnable()).isFalse();
        assertThat(brain.rightRail().parentStageId()).isNull();
    }

    @Test
    void rightRailCostBreakdownSumsDevMessagesByStage()
    {
        String taskId = seedTask();
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        // Two cost-bearing dev-thread messages for this task, inside the window.
        threadStore.appendStageMessage(new ThreadMessage(
                UUID.randomUUID().toString(), devThread, taskId, 1, "assistant", "text",
                "{\"text\":\"working\"}", null, 100L, 50L, 300L, stage.openedAt(),
                stage.id().toString(), ThreadScope.STAGE));
        threadStore.appendStageMessage(new ThreadMessage(
                UUID.randomUUID().toString(), devThread, taskId, 2, "assistant", "text",
                "{\"text\":\"more\"}", null, 100L, 50L, 200L, stage.openedAt(),
                stage.id().toString(), ThreadScope.STAGE));

        TaskBrainViewData brain = stageService.getBrain(taskId);

        // 500 milli → 50 cents, all attributed to the dev agent + the stage.
        assertThat(brain.rightRail().costBreakdown().totalCents()).isEqualTo(50);
        assertThat(brain.aggregate().costCents()).isEqualTo(50);
        assertThat(brain.rightRail().costBreakdown().perAgent())
                .anySatisfy(a -> {
                    assertThat(a.agentKind()).isEqualTo("dev");
                    assertThat(a.costCents()).isEqualTo(50);
                });
        assertThat(brain.rightRail().costBreakdown().perStage())
                .anySatisfy(s -> assertThat(s.stageId()).isEqualTo(stage.id().toString()));
    }

    @Test
    void closedStageFeedRowCarriesTokenRollup()
    {
        String taskId = seedTask();
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        // A turn_done row stamped with this stage's id contributes its tokens.
        ThreadMessage turnDone = new ThreadMessage(
                UUID.randomUUID().toString(), devThread, taskId, 1, "system", "turn_done",
                "{}", 1_000L, 20_000L, 10_000L, 0L, stage.openedAt(),
                stage.id().toString(), ThreadScope.STAGE);
        threadStore.appendStageMessage(turnDone);
        stageStore.closeStage(stage.id(), "phase_transition");

        TaskBrainViewData brain = stageService.getBrain(taskId);

        BrainFeedRow closed = brain.brainFeed().stream()
                .filter(r -> "STAGE_CLOSED".equals(r.type())
                        && stage.id().toString().equals(r.stageId()))
                .findFirst()
                .orElseThrow();
        // 30k combined tokens; "finished" verb + token segment present.
        assertThat(closed.body()).contains("finished").contains("30k tokens");
    }

    @Test
    void rightRailPlanCardReflectsAnAwaitingFinalizedPlan()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, Map.of(
                "id", "rev-1",
                "status", "finalized",
                "source", "brain",
                "understanding", Map.of("summary", "bump the retry default"),
                "intent", Map.of(
                        "summary", "change the default and add a test",
                        "steps", List.of(Map.of("ordinal", 1, "action", "edit RetryConfig"))),
                "signals", Map.of("riskLevel", "low", "estimatedComplexity", "small",
                        "componentsCount", 2, "expectedGain", "fewer flakes")));

        assertThat(stageService.getBrain(taskId).rightRail().plan().state()).isEqualTo("draft");
        stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved", "reviewedRevisionId", "rev-1"));

        TaskBrainViewData.PlanCard card = stageService.getBrain(taskId).rightRail().plan();

        assertThat(card).isNotNull();
        assertThat(card.state()).isEqualTo("awaiting");
        assertThat(card.understandingSummary()).isEqualTo("bump the retry default");
        assertThat(card.steps()).singleElement()
                .satisfies(s -> assertThat(s.action()).isEqualTo("edit RetryConfig"));
        assertThat(card.signals().riskLevel()).isEqualTo("low");
    }

    @Test
    void rightRailPlanCardStaysDraftUntilAFinalizedRecordIsStructurallyComplete()
    {
        // record_plan is callable more than once per turn (an early stake,
        // then the real one) — a call already carrying status=finalized but
        // with no goal/steps yet must not read as an actionable "awaiting"
        // card; that flashes a false-positive Approve button for the few
        // seconds until the real, complete call lands.
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, Map.of(
                "id", "rev-1",
                "status", "finalized",
                "source", "brain",
                "signals", Map.of("riskLevel", "low", "estimatedComplexity", "small")));

        TaskBrainViewData.PlanCard card = stageService.getBrain(taskId).rightRail().plan();

        assertThat(card.state()).isEqualTo("draft");

        // The real, complete call lands moments later — same round trip
        // updates the card to awaiting.
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, Map.of(
                "id", "rev-2",
                "status", "finalized",
                "source", "brain",
                "understanding", Map.of("summary", "bump the retry default"),
                "intent", Map.of(
                        "summary", "change the default and add a test",
                        "steps", List.of(Map.of("ordinal", 1, "action", "edit RetryConfig"))),
                "signals", Map.of("riskLevel", "low", "estimatedComplexity", "small")));
        stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved", "reviewedRevisionId", "rev-2"));

        TaskBrainViewData.PlanCard updated = stageService.getBrain(taskId).rightRail().plan();
        assertThat(updated.state()).isEqualTo("awaiting");
    }

    @Test
    void rightRailPlanCardAcceptsStringStepsAndAlternateKeysAndDropsBlanks()
    {
        // record_plan is free-form JSON, so the brain may emit steps as plain
        // strings or objects keyed by something other than 'action'. The card
        // must surface the text rather than render blank numbered lines, and a
        // step with no recoverable text is dropped entirely.
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        List<Object> steps = List.of(
                "1. plain string step",
                Map.of("ordinal", 2, "description", "keyed by description"),
                Map.of("ordinal", 3));
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, Map.of(
                "id", "rev-1",
                "status", "finalized",
                "source", "brain",
                "intent", Map.of("summary", "do the thing", "steps", steps)));

        TaskBrainViewData.PlanCard card = stageService.getBrain(taskId).rightRail().plan();

        assertThat(card.steps()).hasSize(2);
        assertThat(card.steps().get(0).action()).isEqualTo("plain string step");
        assertThat(card.steps().get(1).action()).isEqualTo("keyed by description");
    }

    @Test
    void rightRailPlanCardFallsBackToTopLevelStepsWhenIntentStepsIsEmpty()
    {
        // Observed in production: the brain sometimes writes steps at the
        // payload's top level (with an "intent_steps_note" pointing there)
        // instead of nested under intent.steps as record_plan's schema says.
        // Without the fallback this renders 0 steps -> "draft" -> an
        // unapprovable plan despite an otherwise-complete, finalized record.
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, Map.of(
                "id", "rev-1",
                "status", "finalized",
                "source", "brain",
                "understanding", Map.of("summary", "bump the retry default"),
                "intent", Map.of(
                        "summary", "change the default and add a test",
                        "validationStrategy", "mvn verify"),
                "intent_steps_note", "see steps below",
                "steps", List.of(Map.of("ordinal", 1, "action", "edit RetryConfig")),
                "signals", Map.of("riskLevel", "low", "estimatedComplexity", "small")));
        stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved", "reviewedRevisionId", "rev-1"));

        TaskBrainViewData.PlanCard card = stageService.getBrain(taskId).rightRail().plan();

        assertThat(card.state()).isEqualTo("awaiting");
        assertThat(card.steps()).singleElement()
                .satisfies(s -> assertThat(s.action()).isEqualTo("edit RetryConfig"));
    }

    @Test
    void rightRailPlanCardRequiresAnApprovedReviewBoundToTheLatestRevision()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, Map.of(
                "id", "rev-1", "status", "finalized",
                "understanding", Map.of("summary", "fix the boundary"),
                "intent", Map.of("summary", "fix it", "steps", List.of("Change it"))));

        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved"));
        assertThat(stageService.getBrain(taskId).rightRail().plan().state()).isEqualTo("draft");

        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved", "reviewedRevisionId", "rev-old"));
        assertThat(stageService.getBrain(taskId).rightRail().plan().state()).isEqualTo("draft");

        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "changes_requested", "reviewedRevisionId", "rev-1"));
        assertThat(stageService.getBrain(taskId).rightRail().plan().state())
                .isEqualTo("revision_required");

        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved", "reviewedRevisionId", "rev-1"));
        assertThat(stageService.getBrain(taskId).rightRail().plan().state())
                .isEqualTo("awaiting");
    }

    @Test
    void rightRailPlanCardIsLockedAfterApproval()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED,
                Map.of("id", "rev-1", "status", "finalized"));
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_APPROVED,
                Map.of("approvedRevisionId", "rev-1"));
        stageStore.closeStage(plan.id(), "plan_approved", Map.of());

        TaskBrainViewData.PlanCard card = stageService.getBrain(taskId).rightRail().plan();
        assertThat(card.state()).isEqualTo("locked");
    }

    @Test
    void brainTaskReflectsPausedStatus()
    {
        String taskId = seedTask();
        assertThat(stageService.getBrain(taskId).task().paused()).isFalse();

        taskStore.saveTask(taskStore.findTaskById(taskId).orElseThrow().withStatus(TaskStatus.PAUSED));

        assertThat(stageService.getBrain(taskId).task().paused()).isTrue();
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Brain test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);

        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }

    private void seedTurn(String turnId, String threadId, String taskId)
    {
        Instant now = Instant.parse("2026-06-20T09:30:00Z");
        threadTurnStore.saveTurn(new ThreadTurn(
                turnId, threadId, taskId, ThreadResourceLane.CLI, ThreadTurnStatus.QUEUED,
                "monitor work", now, now, null, null, null, TurnInitiator.unattended("test"),
                null, ThreadScope.TASK));
    }
}
