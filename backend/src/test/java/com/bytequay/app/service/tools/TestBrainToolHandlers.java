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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.tools.BrainToolHandlers.CheckCoverageArgs;
import com.bytequay.app.service.tools.BrainToolHandlers.CountOperationsArgs;
import com.bytequay.app.service.tools.BrainToolHandlers.PanelFindingsArgs;
import com.bytequay.app.service.tools.BrainToolHandlers.PhaseHistoryArgs;
import com.bytequay.app.service.tools.BrainToolHandlers.PrStatusArgs;
import com.bytequay.app.service.tools.BrainToolHandlers.StageMetricsArgs;
import com.bytequay.app.service.tools.BrainToolHandlers.UnresolvedCommentsArgs;
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
 * Exercises the brain agent's read-only tools against seeded data. The
 * data-backed tools (operations, stage metrics, phase history, unresolved
 * comments, panel findings) assert real shapes; the PR/commit tools assert
 * graceful errors when the task has no linked PR (no remote fixture stood up).
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestBrainToolHandlers
{
    private static final ToolCall CALL = new ToolCall("brain-thread", null, AgentRole.TASK);

    @Autowired
    private BrainToolHandlers tools;
    @Autowired
    private TaskPhaseMachine machine;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void countOperationsReturnsZeroWithNoOperationEvents()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);

        ToolOutcome.Completed out = completed(tools.countOperations(
                new CountOperationsArgs(taskId, null), CALL));

        assertThat(out.isError()).isFalse();
        assertThat(out.text()).contains("\"count\":0");
    }

    @Test
    void readStageMetricsReturnsAllStages()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        stageStore.openStage(taskId, StageType.REVIEW_MONITOR_STAGE, null);

        ToolOutcome.Completed out = completed(tools.readStageMetrics(
                new StageMetricsArgs(taskId, "all"), CALL));

        assertThat(out.isError()).isFalse();
        assertThat(out.text()).contains("CI_FIXING_STAGE").contains("REVIEW_MONITOR_STAGE");
    }

    @Test
    void readPhaseHistoryReturnsTransitions()
    {
        String taskId = seedTask();
        machine.transition(taskId, TaskPhase.VALIDATING, "ready", Actor.AGENT);

        ToolOutcome.Completed out = completed(tools.readPhaseHistory(
                new PhaseHistoryArgs(taskId, null), CALL));

        assertThat(out.isError()).isFalse();
        assertThat(out.text()).contains("VALIDATING");
    }

    @Test
    void listUnresolvedCommentsFiltersBySource()
    {
        String taskId = seedTask();
        stageStore.saveReviewComment(new ReviewComment(
                null, taskId, "src/Foo.java", 12, "nit", Instant.parse("2026-06-20T10:00:00Z"),
                ReviewCommentSource.LOCAL_USER, null, false));

        ToolOutcome.Completed all = completed(tools.listUnresolvedComments(
                new UnresolvedCommentsArgs(taskId, null), CALL));
        assertThat(all.isError()).isFalse();
        assertThat(all.text()).contains("src/Foo.java").contains("LOCAL_USER");

        ToolOutcome.Completed remote = completed(tools.listUnresolvedComments(
                new UnresolvedCommentsArgs(taskId, "REMOTE_REVIEWER"), CALL));
        assertThat(remote.text()).doesNotContain("src/Foo.java");

        ToolOutcome.Completed bad = completed(tools.listUnresolvedComments(
                new UnresolvedCommentsArgs(taskId, "NONSENSE"), CALL));
        assertThat(bad.isError()).isTrue();
    }

    @Test
    void reviewPanelFindingsAreEmptyUntilPanelsRun()
    {
        String taskId = seedTask();
        ToolOutcome.Completed out = completed(tools.readReviewPanelFindings(
                new PanelFindingsArgs(taskId, "all"), CALL));
        assertThat(out.isError()).isFalse();
        assertThat(out.text()).isEqualTo("[]");
    }

    @Test
    void prStatusErrorsWhenNoLinkedPr()
    {
        String taskId = seedTask();
        ToolOutcome.Completed pr = completed(tools.readRemotePrStatus(new PrStatusArgs(taskId), CALL));
        assertThat(pr.isError()).isTrue();
    }

    @Test
    void coverageReportsMissingTestForAnUntestedFile()
    {
        String taskId = seedTask();
        // workingDir is /tmp; no matching test file exists for this path.
        ToolOutcome.Completed cov = completed(tools.checkTestCoverage(
                new CheckCoverageArgs(taskId, List.of("src/DoesNotExist.java")), CALL));
        assertThat(cov.isError()).isFalse();
        assertThat(cov.text()).contains("\"hasTest\":false");
    }

    /**
     * Canned-scenario coverage: seed one Task with known state, then run the
     * canonical brain-agent questions through the tools that ground each
     * answer, asserting the facts surface. (We test the tools, not a mocked
     * LLM — asserting a stubbed model's own output would be circular. The
     * cost / steering / cross-task questions are answered from the context
     * digest, which has no dedicated tool, so they're out of scope here.)
     */
    @Test
    void cannedScenariosAnswerCanonicalQuestionsFromState()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        stageStore.openStage(taskId, StageType.REVIEW_MONITOR_STAGE, null);
        machine.transition(taskId, TaskPhase.VALIDATING, "ready", Actor.AGENT);
        machine.transition(taskId, TaskPhase.INTERNAL_REVIEW, "validated", Actor.AGENT);

        // "What's the status of CiFixingStage / the review monitor?"
        ToolOutcome.Completed metrics = completed(tools.readStageMetrics(
                new StageMetricsArgs(taskId, "all"), CALL));
        assertThat(metrics.text()).contains("CI_FIXING_STAGE").contains("REVIEW_MONITOR_STAGE");

        // "Why are we here / what happened phase-wise?"
        ToolOutcome.Completed phases = completed(tools.readPhaseHistory(
                new PhaseHistoryArgs(taskId, null), CALL));
        assertThat(phases.text()).contains("VALIDATING").contains("INTERNAL_REVIEW");

        // "How many operations have run?" (none yet — OPERATION_* aren't written)
        ToolOutcome.Completed ops = completed(tools.countOperations(
                new CountOperationsArgs(taskId, null), CALL));
        assertThat(ops.text()).contains("\"count\":0");

        // "What's blocking the merge?" — no linked PR yet, so the tool says so
        // rather than fabricating an answer.
        ToolOutcome.Completed pr = completed(tools.readRemotePrStatus(new PrStatusArgs(taskId), CALL));
        assertThat(pr.isError()).isTrue();
    }

    private static ToolOutcome.Completed completed(ToolOutcome outcome)
    {
        return (ToolOutcome.Completed) outcome;
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Brain tools test", ThreadStatus.RUNNING, "claude-sonnet-4-6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        // No worktree path and no linked PR — exercises the graceful-error paths.
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
