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

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.LocalPRService;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.tools.LocalPRToolHandlers.RecordPrCheckArgs;
import com.bytequay.app.service.tools.LocalPRToolHandlers.RecordPrDescriptionArgs;
import com.bytequay.app.service.tools.LocalPRToolHandlers.ResolvePrCommentArgs;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dev agent writes the local PR's description + checks through these tools.
 * They must materialise the PR row from the task on first use — the agent is
 * told commits are captured automatically, so it never calls record_pr_commit
 * to seed the row first.
 */
class TestLocalPRToolHandlers
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final LocalPRService localPr = mock(LocalPRService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final ReviewRoundStore roundStore = mock(ReviewRoundStore.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final LocalPRToolHandlers handlers =
            new LocalPRToolHandlers(localPr, taskStore, brainReview, roundStore, agentRuns);

    private final ToolCall taskCall = new ToolCall("thread-1", null, AgentRole.TASK, "task1", null);

    private static Task task()
    {
        return new Task(
                "task1", "thread-1", 1L, TaskStatus.RUNNING,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, "T", null, null, null, TaskPhase.IMPLEMENTING, null, 0, null);
    }

    private LocalPR pr()
    {
        return LocalPR.create("pr1", "task1", "feature/x", "main", "T", "", NOW);
    }

    @Test
    void recordDescriptionMaterialisesThePrThenWritesIt()
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(localPr.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(pr());

        ToolOutcome outcome = handlers.recordPrDescription(
                new RecordPrDescriptionArgs("Better title", "does the thing"), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(localPr).createForTask("task1", "feature/x", "main", "T", "");
        verify(localPr).updateDetails("pr1", "Better title", "does the thing");
    }

    @Test
    void recordCheckMaterialisesThePrThenRecordsIt()
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(localPr.createForTask(any(), any(), any(), any(), any())).thenReturn(pr());

        ToolOutcome outcome = handlers.recordPrCheck(
                new RecordPrCheckArgs("local", "mvn verify", "passed", 1200L), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(localPr).recordCheck("pr1", "local", "mvn verify", "passed", 1200L);
    }

    @Test
    void resolvePrCommentDefaultsToResolve()
    {
        ToolOutcome outcome = handlers.resolvePrComment(
                new ResolvePrCommentArgs("cm1", "addressed"), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(localPr).resolveComment("cm1");
        verify(localPr, never()).dismissComment(any());
    }

    @Test
    void resolvePrCommentRoutesToDismissWhenResolutionIsDismissed()
    {
        ToolOutcome outcome = handlers.resolvePrComment(
                new ResolvePrCommentArgs("cm1", "dismissed"), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(localPr).dismissComment("cm1");
        verify(localPr, never()).resolveComment(any());
    }

    @Test
    void resolvePrCommentRequiresCommentId()
    {
        ToolOutcome outcome = handlers.resolvePrComment(
                new ResolvePrCommentArgs(null, null), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(localPr, never()).resolveComment(any());
        verify(localPr, never()).dismissComment(any());
    }

    @Test
    void rejectsATrunkTurnWithNoTask()
    {
        ToolOutcome outcome = handlers.recordPrDescription(
                new RecordPrDescriptionArgs(null, "x"), new ToolCall("thread-1", null, AgentRole.TRUNK));

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(localPr, never()).createForTask(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsATaskWithoutABranchYet()
    {
        Task noBranch = new Task(
                "task1", "thread-1", 1L, TaskStatus.RUNNING,
                null, null, "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, "T", null, null, null, TaskPhase.QUEUED, null, 0, null);
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(noBranch));

        ToolOutcome outcome = handlers.recordPrCheck(
                new RecordPrCheckArgs("local", "mvn verify", "passed", 1L), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(localPr, never()).recordCheck(eq("pr1"), any(), any(), any(), any());
    }
}
