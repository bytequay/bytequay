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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestReviewRoundService
{
    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private static final String TASK_ID = "t1.k1";
    private static final String REPO = "acme/widgets";
    private static final int PR_NUMBER = 42;
    private static final UUID BACKING_STAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final TaskStore taskStore = mock(TaskStore.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final ReviewRoundStore roundStore = mock(ReviewRoundStore.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final ReviewRoundServiceImpl service = new ReviewRoundServiceImpl(
            taskStore, stageStore, roundStore, agentRuns, threadStore, scheduler, turnStore,
            phaseMachine, pullRequests, git, brainReview, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reconcileNeverOpensASecondRoundButRefreshesTheLiveOnesStats()
    {
        Task task = task();
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(roundStore.findById(live.id())).thenReturn(Optional.of(live));
        ReviewComment stillOpen = new ReviewComment(
                UUID.randomUUID(), TASK_ID, "src/Foo.java", 1, "nit", NOW,
                ReviewCommentSource.REMOTE_REVIEWER, null, false, 1L, UUID.fromString(live.id()), null, null,
                "RIGHT", null, null);
        when(stageStore.findCommentsByRound(UUID.fromString(live.id()))).thenReturn(List.of(stillOpen));

        service.reconcile(task);

        verify(stageStore, never()).findUnroundedRemoteComments(anyString());
        verify(agentRuns, never()).open(any(), any(), any(), any(), any(), any());
        // A round parked before recomputeStats existed (or one that simply
        // fell behind) self-heals on the next reconcile sweep instead of
        // staying stuck until its next resolve.
        verify(roundStore).save(argThat(r -> r.stats().open() == 1));
    }

    @Test
    void reconcileWaitsWithinTheDebounceWindow()
    {
        Task task = task();
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.empty());
        when(stageStore.findUnroundedRemoteComments(TASK_ID)).thenReturn(
                List.of(comment("c1", NOW.minus(Duration.ofMinutes(2)))));

        service.reconcile(task);

        verify(agentRuns, never()).open(any(), any(), any(), any(), any(), any());
        verify(roundStore, never()).save(any());
    }

    @Test
    void reconcileOpensARoundOnceDebounceElapsesAndAssignsComments()
    {
        Task task = task();
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.empty());
        ReviewComment c1 = comment("c1", NOW.minus(Duration.ofMinutes(15)));
        ReviewComment c2 = comment("c2", NOW.minus(Duration.ofMinutes(12)));
        when(stageStore.findUnroundedRemoteComments(TASK_ID)).thenReturn(List.of(c1, c2));
        when(roundStore.nextIndex(TASK_ID)).thenReturn(1);
        AgentRun run = new AgentRun(
                "run1", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.open(eq(TASK_ID), eq(AgentRun.KIND_REVIEW_ROUND), eq(AgentRun.SOURCE_REMOTE),
                any(), eq(StageType.REVIEW_ROUND_STAGE), eq(null))).thenReturn(run);
        Thread thread = idleThread();
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        when(scheduler.enqueueTaskTurn(any(), anyString(), anyString(), any(), any())).thenReturn("turn-1");

        service.reconcile(task);

        verify(stageStore).assignCommentsToRound(eq(List.of(c1.id(), c2.id())), any());
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_ADDRESSING.equals(r.status())
                && TASK_ID.equals(r.taskId()) && run.id().equals(r.runId())
                // Stats start reflecting the real batch size, not empty() —
                // the rail's "N comments" banner must be right from round 1,
                // not just after the first resolve.
                && r.stats().open() == 2 && r.stats().fixed() == 0 && r.stats().replied() == 0));
        verify(scheduler).enqueueTaskTurn(eq(thread), anyString(), eq(TASK_ID), eq(run.stageId()), any());
    }

    @Test
    void closeOpenRoundsCancelsTheRunAndClosesTheLiveRound()
    {
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));

        service.closeOpenRounds(TASK_ID, "pr_merged");

        verify(agentRuns).transition(live.runId(), AgentRun.STATUS_CANCELLED, "pr_merged");
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_CLOSED.equals(r.status())
                && live.id().equals(r.id())));
    }

    @Test
    void closeOpenRoundsIsANoOpWhenTheTaskHasNoLiveRound()
    {
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.empty());

        service.closeOpenRounds(TASK_ID, "pr_merged");

        verify(agentRuns, never()).transition(any(), any(), any());
        verify(roundStore, never()).save(any());
    }

    @Test
    void recomputeStatsClassifiesEachCommentByItsResolvedAndReplyState()
    {
        UUID roundId = UUID.randomUUID();
        ReviewRound round = round(ReviewRound.STATUS_ADDRESSING);
        when(roundStore.findById(roundId.toString())).thenReturn(Optional.of(round));
        ReviewComment fixed = new ReviewComment(
                UUID.randomUUID(), TASK_ID, "src/Foo.java", 1, "fix this", NOW,
                ReviewCommentSource.REMOTE_REVIEWER, null, /* resolved */ true, 1L, roundId, null, null,
                "RIGHT", null, null);
        ReviewComment replied = new ReviewComment(
                UUID.randomUUID(), TASK_ID, "src/Bar.java", 2, "why?", NOW,
                ReviewCommentSource.REMOTE_REVIEWER, null, /* resolved */ true, 2L, roundId, "because", NOW,
                "RIGHT", null, null);
        ReviewComment stillOpen = new ReviewComment(
                UUID.randomUUID(), TASK_ID, "src/Baz.java", 3, "nit", NOW,
                ReviewCommentSource.REMOTE_REVIEWER, null, /* resolved */ false, 3L, roundId, null, null,
                "RIGHT", null, null);
        when(stageStore.findCommentsByRound(roundId)).thenReturn(List.of(fixed, replied, stillOpen));

        service.recomputeStats(roundId.toString());

        verify(roundStore).save(argThat(r -> r.stats().fixed() == 1 && r.stats().replied() == 1
                && r.stats().open() == 1 && r.stats().pushedBack() == 0));
    }

    @Test
    void recomputeStatsIsANoOpForAnUnknownRound()
    {
        when(roundStore.findById("missing")).thenReturn(Optional.empty());

        service.recomputeStats("missing");

        verify(roundStore, never()).save(any());
    }

    @Test
    void onTurnFinishedHandsTheLiveAddressingRoundToBrainReview()
    {
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        Task task = task();
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn(BACKING_STAGE_ID.toString())));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                live.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 1, null, null, null, NOW, null);
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(run));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        // The round's own addressing-turn completion hands off to the R21(b)
        // brain verification pass instead of arming the gate directly.
        verify(brainReview).reviewBeforeRoundGate(live, task);
        verify(agentRuns, never()).transition(anyString(), anyString(), anyString());
    }

    @Test
    void onTurnFinishedSkipsBrainReviewOnceAlreadyVerified()
    {
        // iteration > 0 / a verdict already recorded means the brain loop is
        // mid-flight or done — this listener only ever hands off ONCE
        // (BrainReviewServiceImpl owns every subsequent turn on this round).
        ReviewRound alreadyLooping = round(ReviewRound.STATUS_ADDRESSING).withBrainVerdict(
                ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(alreadyLooping));
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn(BACKING_STAGE_ID.toString())));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verifyNoInteractions(brainReview);
    }

    @Test
    void onTurnFinishedIgnoresATurnFromADifferentStage()
    {
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn("some-other-stage")));
        AgentRun run = new AgentRun(
                live.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 1, null, null, null, NOW, null);
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(run));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(agentRuns, never()).transition(anyString(), anyString(), anyString());
    }

    @Test
    void approveRequiresTheRoundToBeAwaitingGate()
    {
        ReviewRound addressing = round(ReviewRound.STATUS_ADDRESSING);
        when(roundStore.findById(addressing.id())).thenReturn(Optional.of(addressing));

        assertThatThrownBy(() -> service.approve(addressing.id())).hasMessageContaining("not awaiting its gate");

        verifyNoInteractions(pullRequests, git);
    }

    @Test
    void nothingIsPostedOrPushedUntilApprove()
    {
        // Opening + addressing a round must never touch GitHub or the
        // worktree — only the explicit approve() gate may.
        Task task = task();
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.empty());
        when(stageStore.findUnroundedRemoteComments(TASK_ID)).thenReturn(
                List.of(comment("c1", NOW.minus(Duration.ofMinutes(15)))));
        when(roundStore.nextIndex(TASK_ID)).thenReturn(1);
        AgentRun run = new AgentRun(
                "run1", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.open(any(), any(), any(), any(), any(), any())).thenReturn(run);

        service.reconcile(task);

        verifyNoInteractions(pullRequests, git);
        verify(phaseMachine, never()).transition(anyString(), any(), anyString(), any());
    }

    @Test
    void approvePostsOnlyDraftedRepliesPushesAndClosesTheRound()
            throws Exception
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        Task task = task();
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        ReviewComment drafted = draftedComment("c1", 111L, "Fixed, thanks!");
        ReviewComment undrafted = comment("c2", NOW);
        when(stageStore.findCommentsByRound(UUID.fromString(gated.id())))
                .thenReturn(List.of(drafted, undrafted));
        when(roundStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReviewRound result = service.approve(gated.id());

        verify(pullRequests).replyToReviewThread(REPO, PR_NUMBER, 111L, "Fixed, thanks!");
        verify(pullRequests, never()).replyToReviewThread(anyString(), anyInt(), eq(0L), anyString());
        verify(git).push(Path.of(task.worktreePath()));
        verify(phaseMachine).transition(TASK_ID, TaskPhase.PUSHED_AWAITING_CI, "round_approved", Actor.HUMAN);
        verify(agentRuns).transition(gated.runId(), AgentRun.STATUS_SUCCEEDED, "round_approved");
        assertThat(result.status()).isEqualTo(ReviewRound.STATUS_POSTED);
        assertThat(result.postedAt()).isEqualTo(NOW);
    }

    private static Task task()
    {
        return new Task(
                TASK_ID, "t1", 1L, TaskStatus.IN_REVIEW, "dev/x", "/tmp/wt", "main", "/tmp/clone",
                null, null, null, null, null, "DEVELOP", PR_NUMBER, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null,
                null, TaskPhase.AWAITING_REMOTE_REVIEW, null, 0, REPO + "#" + PR_NUMBER);
    }

    private static Thread idleThread()
    {
        return new Thread(
                "t1", ThreadKind.CLI_AGENT, "claude-code", null, "Round test",
                ThreadStatus.IDLE, "claude-sonnet-4.6", 0L, 0L, 0L, NOW, NOW,
                null, null, ThreadFlow.BUILD, "ws-default", null, null);
    }

    private static ThreadTurn turn(String stageId)
    {
        return new ThreadTurn(
                "turn-1", "t1", TASK_ID, ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "prompt", NOW, NOW, NOW, NOW,
                null, TurnInitiator.unattended("review-round"),
                stageId, ThreadScope.TASK);
    }

    private static ReviewRound round(String status)
    {
        boolean gated = ReviewRound.STATUS_AWAITING_GATE.equals(status)
                || ReviewRound.STATUS_POSTED.equals(status);
        return new ReviewRound(
                UUID.randomUUID().toString(), TASK_ID, 1, List.of(), status,
                ReviewRound.ReviewRoundStats.empty(), "run1", NOW.minusSeconds(60),
                gated ? NOW : null, null,
                ReviewRound.ORIGIN_EXTERNAL, null, 0, ReviewRound.DEFAULT_BRAIN_BUDGET);
    }

    private static UUID commentId(String suffix)
    {
        return UUID.nameUUIDFromBytes(suffix.getBytes(StandardCharsets.UTF_8));
    }

    private static ReviewComment comment(String idSuffix, Instant createdAt)
    {
        return new ReviewComment(
                commentId(idSuffix), TASK_ID, "Foo.java", 10, "please fix", createdAt,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/" + REPO + "/pull/" + PR_NUMBER + "#discussion_r" + idSuffix,
                false, 0L, null, null, null, "RIGHT", null, null);
    }

    private static ReviewComment draftedComment(String idSuffix, long remoteCommentId, String draftBody)
    {
        return new ReviewComment(
                commentId(idSuffix), TASK_ID, "Foo.java", 10, "please fix", NOW,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/" + REPO + "/pull/" + PR_NUMBER + "#discussion_r" + idSuffix,
                false, remoteCommentId, null, draftBody, NOW, "RIGHT", null, null);
    }
}
