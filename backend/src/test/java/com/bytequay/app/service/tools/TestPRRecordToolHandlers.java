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
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.sqlite.SqliteReviewRoundStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.BrainReviewServiceImpl;
import com.bytequay.app.service.runs.AgentRunServiceImpl;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.tools.PRRecordToolHandlers.RecordLocalReviewArgs;
import com.bytequay.app.service.tools.PRRecordToolHandlers.RecordPrCheckArgs;
import com.bytequay.app.service.tools.PRRecordToolHandlers.RecordPrDescriptionArgs;
import com.bytequay.app.service.tools.PRRecordToolHandlers.RecordPrProgressArgs;
import com.bytequay.app.service.tools.PRRecordToolHandlers.ResolvePrCommentArgs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
class TestPRRecordToolHandlers
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final PRService prService = mock(PRService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final BrainReviewServiceImpl brainReview = mock(BrainReviewServiceImpl.class);
    private final SqliteReviewRoundStore roundStore = mock(SqliteReviewRoundStore.class);
    private final AgentRunServiceImpl agentRuns = mock(AgentRunServiceImpl.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PRRecordToolHandlers handlers =
            new PRRecordToolHandlers(
                    prService, taskStore, brainReview, roundStore, agentRuns, phaseMachine, git);

    private final ToolCall taskCall = new ToolCall(ThreadScope.TASK,
            "thread-1", null, AgentRole.TASK, "task1", null);

    private static Task task()
    {
        return new Task(
                "task1", "thread-1", 1L, TaskStatus.RUNNING,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, "T", null, null, null, TaskPhase.IMPLEMENTING, null, 0, null);
    }

    private PR pr()
    {
        return PR.create("pr1", "task1", "feature/x", "main", "T", "", NOW);
    }

    @Test
    void recordDescriptionMaterialisesThePrThenWritesIt()
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(prService.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(pr());

        ToolOutcome outcome = handlers.recordPrDescription(
                new RecordPrDescriptionArgs("Better title", "does the thing"), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(prService).createForTask("task1", "feature/x", "main", "T", "");
        verify(prService).recordProgress("pr1", "creating-draft");
        verify(prService).updateDetails("pr1", "Better title", "does the thing");
    }

    @Test
    void recordProgressMaterialisesThePrThenWritesTheRequestedPhase()
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(prService.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(pr());

        ToolOutcome outcome = handlers.recordPrProgress(new RecordPrProgressArgs("starting"), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(prService).recordProgress("pr1", "starting");
    }

    @Test
    void recordProgressRejectsAnUnknownPhaseBeforeMaterialisingThePr()
    {
        ToolOutcome outcome = handlers.recordPrProgress(new RecordPrProgressArgs("pushing"), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(prService, never()).createForTask(any(), any(), any(), any(), any());
        verify(prService, never()).recordProgress(any(), any());
    }

    @Test
    void recordCheckMaterialisesThePrThenRecordsIt()
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(prService.createForTask(any(), any(), any(), any(), any())).thenReturn(pr());

        ToolOutcome outcome = handlers.recordPrCheck(
                new RecordPrCheckArgs("local", "mvn verify", "passed", 1200L), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(prService).recordCheck("pr1", "local", "mvn verify", "passed", 1200L);
    }

    @Test
    void recordLocalReviewCannotStartBrainBeforeValidation()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(prService.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(pr());
        when(git.commitCountUniqueTo(Path.of("/tmp/wt/feature-x"), "feature/x", "main"))
                .thenReturn(1);

        ToolOutcome outcome = handlers.recordLocalReview(new RecordLocalReviewArgs(true), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).text()).contains("validation will start Brain review");
        verify(phaseMachine).transition(
                "task1", TaskPhase.VALIDATING, "development_handoff", Actor.AGENT);
        verify(brainReview, never()).reviewBeforeLocalOpen(any(), any());
    }

    @Test
    void recordLocalReviewRejectsV2BeforeLegacyPrOrGitMutation()
            throws Exception
    {
        when(taskStore.isV2Task("task1")).thenReturn(true);

        ToolOutcome outcome = handlers.recordLocalReview(
                new RecordLocalReviewArgs(true), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        assertThat(((ToolOutcome.Completed) outcome).text())
                .contains("exact StageTurn result");
        verify(prService, never()).createForTask(any(), any(), any(), any(), any());
        verify(phaseMachine, never()).transition(any(), any(), any(), any());
        verify(git, never()).hasUncommittedChanges(any());
    }

    @Test
    void recordLocalReviewCheckpointsADirtyWorktreeOutsideTheAgentSandbox()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(prService.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(pr());
        when(git.hasUncommittedChanges(Path.of("/tmp/wt/feature-x"))).thenReturn(true);
        when(git.commit(Path.of("/tmp/wt/feature-x"), "T")).thenReturn(Optional.of("abc123"));
        when(git.commitCountUniqueTo(Path.of("/tmp/wt/feature-x"), "feature/x", "main"))
                .thenReturn(1);

        ToolOutcome outcome = handlers.recordLocalReview(new RecordLocalReviewArgs(true), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(git).stageAll(
                Path.of("/tmp/wt/feature-x"), List.of(".bytequay-hooks"));
        verify(git).commit(Path.of("/tmp/wt/feature-x"), "T");
        verify(phaseMachine).transition(
                "task1", TaskPhase.VALIDATING, "development_handoff", Actor.AGENT);
    }

    @Test
    void recordLocalReviewParksWithTheGitErrorWhenCheckpointingFails()
            throws Exception
    {
        Task task = task();
        Path worktree = Path.of("/tmp/wt/feature-x");
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task));
        when(prService.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(pr());
        when(git.hasUncommittedChanges(worktree)).thenReturn(true);
        doThrow(new IOException("index denied"))
                .when(git).stageAll(worktree, List.of(".bytequay-hooks"));

        ToolOutcome outcome = handlers.recordLocalReview(new RecordLocalReviewArgs(true), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        assertThat(((ToolOutcome.Completed) outcome).text())
                .contains("could not checkpoint Git changes", "index denied");
        verify(taskStore).saveTask(argThat(saved -> saved.errorMessage() != null
                && saved.errorMessage().contains("index denied")));
        verify(phaseMachine).transition(
                "task1", TaskPhase.NEEDS_ATTENTION,
                "development_handoff_checkpoint_failed", Actor.AGENT);
    }

    @Test
    void recordLocalReviewRejectsABranchWithNoCommitAhead()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(prService.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(pr());
        when(git.commitCountUniqueTo(Path.of("/tmp/wt/feature-x"), "feature/x", "main"))
                .thenReturn(0);

        ToolOutcome outcome = handlers.recordLocalReview(new RecordLocalReviewArgs(true), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        assertThat(((ToolOutcome.Completed) outcome).text()).contains("at least one commit");
        verify(phaseMachine, never()).transition(any(), any(), any(), any());
    }

    @Test
    void resolvePrCommentRepliesThenResolves()
    {
        stubTaskPrComments(comment("cm1"));
        ToolOutcome outcome = handlers.resolvePrComment(
                new ResolvePrCommentArgs("cm1", "addressed", "Fixed the null guard."), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(prService).addComment(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                eq("Fixed the null guard."), eq("cm1"));
        verify(prService).resolveCommentForAgent("cm1");
        verify(prService, never()).dismissCommentForAgent(any());
    }

    @Test
    void resolvePrCommentAddressingRequiresAReply()
    {
        stubTaskPrComments(comment("cm1"));
        ToolOutcome outcome = handlers.resolvePrComment(
                new ResolvePrCommentArgs("cm1", "addressed", null), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(prService, never()).resolveCommentForAgent(any());
    }

    @Test
    void resolvePrCommentRoutesToDismissWhenResolutionIsDismissed()
    {
        stubTaskPrComments(comment("cm1"));
        ToolOutcome outcome = handlers.resolvePrComment(
                new ResolvePrCommentArgs("cm1", "dismissed", null), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(prService).dismissCommentForAgent("cm1");
        verify(prService, never()).resolveCommentForAgent(any());
    }

    @Test
    void resolvePrCommentRejectsACommentFromAnotherTask()
    {
        stubTaskPrComments(comment("own-comment"));

        ToolOutcome outcome = handlers.resolvePrComment(
                new ResolvePrCommentArgs("foreign-comment", "addressed", "fix"), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(prService, never()).resolveCommentForAgent("foreign-comment");
        verify(prService, never()).dismissCommentForAgent("foreign-comment");
    }

    @Test
    void resolvePrCommentRequiresCommentId()
    {
        ToolOutcome outcome = handlers.resolvePrComment(
                new ResolvePrCommentArgs(null, null, null), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(prService, never()).resolveCommentForAgent(any());
        verify(prService, never()).dismissCommentForAgent(any());
    }

    @Test
    void rejectsATrunkTurnWithNoTask()
    {
        ToolOutcome outcome = handlers.recordPrDescription(
                new RecordPrDescriptionArgs(null, "x"),
                new ToolCall(ThreadScope.TRUNK,
                        "thread-1", null, AgentRole.TRUNK));

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(prService, never()).createForTask(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsATaskWithoutABranchYet()
    {
        Task noBranch = new Task(
                "task1", "thread-1", 1L, TaskStatus.RUNNING,
                null, null, "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, "T", null, null, null, TaskPhase.PLANNING, null, 0, null);
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(noBranch));

        ToolOutcome outcome = handlers.recordPrCheck(
                new RecordPrCheckArgs("local", "mvn verify", "passed", 1L), taskCall);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(prService, never()).recordCheck(eq("pr1"), any(), any(), any(), any());
    }

    private void stubTaskPrComments(PRComment... comments)
    {
        PR localPr = pr();
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(prService.createForTask("task1", "feature/x", "main", "T", ""))
                .thenReturn(localPr);
        when(prService.comments(localPr.id())).thenReturn(List.of(comments));
    }

    private static PRComment comment(String id)
    {
        return new PRComment(
                id, "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, "brain", "body", NOW,
                null, null, null, null, null, null, null, null);
    }
}
