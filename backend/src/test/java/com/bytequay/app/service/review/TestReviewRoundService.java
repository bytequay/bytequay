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
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
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
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.RemoteDevelopmentStageService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestReviewRoundService
{
    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private static final String TASK_ID = "t1.k1";
    private static final String REPO = "acme/widgets";
    private static final int PR_NUMBER = 42;
    private static final UUID REMOTE_STAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

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
    private final RemoteDevelopmentStageService remoteStages = mock(RemoteDevelopmentStageService.class);
    private final PRService prService = mock(PRService.class);
    private final ReviewRoundServiceImpl service = new ReviewRoundServiceImpl(
            taskStore, stageStore, roundStore, agentRuns, threadStore, scheduler, turnStore,
            phaseMachine, pullRequests, git, brainReview, remoteStages, prService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void findByTaskBackfillsOpenBrainFindingCountForOlderRounds()
    {
        ReviewRound brain = new ReviewRound(
                "brain-round", TASK_ID, 1, List.of(), ReviewRound.STATUS_CLOSED,
                ReviewRound.ReviewRoundStats.empty(), "run-brain", NOW, null, null,
                ReviewRound.ORIGIN_BRAIN, ReviewRound.VERDICT_CHANGES_REQUESTED, 3, 3);
        PR pr = PR.create("pr1", TASK_ID, "dev/x", "main", "T", "", NOW);
        PRComment finding = new PRComment(
                "c1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, PRTimelineEntry.ACTOR_BRAIN, "still open", NOW,
                null, null, null, null, null, "RIGHT", null, null);
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(brain));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(List.of(finding));

        assertThat(service.findByTask(TASK_ID).getFirst().stats().open()).isEqualTo(1);
    }

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
    void reconcileHandsACompletedKickoffToBrainAfterAMissedFinishEvent()
    {
        Task task = task();
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        ReviewRound handedOff = live.withStatus(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        AgentRun run = roundRun(live);
        when(roundStore.findLiveByTask(TASK_ID))
                .thenReturn(Optional.of(live), Optional.of(handedOff));
        when(roundStore.findById(live.id()))
                .thenReturn(Optional.of(live), Optional.of(handedOff));
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(run));
        when(turnStore.listTurnsByTaskId(task.threadId(), 100)).thenReturn(List.of(
                turn(run.stageId(), run.id(), ThreadTurnStatus.COMPLETED)));

        service.reconcile(task);
        service.reconcile(task);

        verify(brainReview, times(1)).reviewBeforeRoundGate(live, task);
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void reconcileWaitsForAQueuedKickoffInsteadOfDuplicatingIt()
    {
        Task task = task();
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        AgentRun run = roundRun(live);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(roundStore.findById(live.id())).thenReturn(Optional.of(live));
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(run));
        when(turnStore.listTurnsByTaskId(task.threadId(), 100)).thenReturn(List.of(
                turn(run.stageId(), run.id(), ThreadTurnStatus.QUEUED)));

        service.reconcile(task);

        verifyNoInteractions(brainReview);
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void reconcileRetriesAFailedKickoffWhileTheTaskIsRunnable()
    {
        Task task = task();
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        AgentRun run = roundRun(live);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(roundStore.findById(live.id())).thenReturn(Optional.of(live));
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(run));
        when(turnStore.listTurnsByTaskId(task.threadId(), 100)).thenReturn(List.of(
                turn(run.stageId(), run.id(), ThreadTurnStatus.FAILED)));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleThread()));

        service.reconcile(task);

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(run.stageId()),
                argThat(i -> "review-round".equals(i.source())), eq(run.id()));
        verifyNoInteractions(brainReview);
    }

    @Test
    void reconcileEnqueuesAKickoffMissingFromPersistentTurnHistory()
    {
        Task task = task();
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        AgentRun run = roundRun(live);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(roundStore.findById(live.id())).thenReturn(Optional.of(live));
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleThread()));

        service.reconcile(task);

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(run.stageId()),
                argThat(i -> "review-round".equals(i.source())), eq(run.id()));
    }

    @Test
    void reconcileLeavesAFailedKickoffParkedWhenTheTaskNeedsAttention()
    {
        Task task = task().withStatus(TaskStatus.NEEDS_ATTENTION);
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        AgentRun run = roundRun(live);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(roundStore.findById(live.id())).thenReturn(Optional.of(live));
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(run));

        service.reconcile(task);

        verifyNoInteractions(brainReview);
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
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
        verify(agentRuns, never()).openInStage(any(), any(), any(), any(), any());
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
        when(remoteStages.ensureOpen(TASK_ID)).thenReturn(remoteStage());
        AgentRun run = new AgentRun(
                "run1", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID.toString(), null,
                REMOTE_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.openInStage(eq(TASK_ID), eq(AgentRun.KIND_REVIEW_ROUND), eq(AgentRun.SOURCE_REMOTE),
                eq(REMOTE_STAGE_ID.toString()), eq(null))).thenReturn(run);
        Thread thread = idleThread();
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        when(scheduler.enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn("turn-1");

        service.reconcile(task);

        verify(stageStore).assignCommentsToRound(eq(List.of(c1.id(), c2.id())), any());
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_ADDRESSING.equals(r.status())
                && TASK_ID.equals(r.taskId()) && run.id().equals(r.runId())
                // Stats start reflecting the real batch size, not empty() —
                // the rail's "N comments" banner must be right from round 1,
                // not just after the first resolve.
                && r.stats().open() == 2 && r.stats().fixed() == 0 && r.stats().replied() == 0));
        verify(scheduler).enqueueTaskTurn(
                eq(thread), anyString(), eq(TASK_ID), eq(run.stageId()), any(), eq(run.id()));
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
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn(REMOTE_STAGE_ID.toString(), live.runId())));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));

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
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn(REMOTE_STAGE_ID.toString(),
                alreadyLooping.runId())));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verifyNoInteractions(brainReview);
    }

    @Test
    void failedAddressingTurnDoesNotOpenBrainVerification()
    {
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(turnStore.findTurnById("turn-1"))
                .thenReturn(Optional.of(turn(REMOTE_STAGE_ID.toString(), live.runId())));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", true));

        verifyNoInteractions(brainReview);
        verify(taskStore, never()).findTaskById(anyString());
    }

    @Test
    void onTurnFinishedIgnoresATurnFromADifferentRun()
    {
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn(REMOTE_STAGE_ID.toString(),
                "some-other-run")));

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
    void approveCannotReplayAnAlreadyPostedRound()
    {
        ReviewRound posted = round(ReviewRound.STATUS_POSTED);
        when(roundStore.findById(posted.id())).thenReturn(Optional.of(posted));

        assertThatThrownBy(() -> service.approve(posted.id()))
                .hasMessageContaining("not awaiting its gate");

        verifyNoInteractions(taskStore, stageStore, pullRequests, git);
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
        when(remoteStages.ensureOpen(TASK_ID)).thenReturn(remoteStage());
        AgentRun run = new AgentRun(
                "run1", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID.toString(), null,
                REMOTE_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.openInStage(any(), any(), any(), any(), any())).thenReturn(run);

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
        ReviewComment undrafted = comment("c2", NOW, true);
        when(stageStore.findCommentsByRound(UUID.fromString(gated.id())))
                .thenReturn(List.of(drafted, undrafted));
        when(roundStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReviewRound result = service.approve(gated.id());

        verify(pullRequests).replyToReviewThread(REPO, PR_NUMBER, 111L, "Fixed, thanks!");
        verify(stageStore).saveReviewComment(argThat(comment ->
                comment.remoteCommentId() == 111L && NOW.equals(comment.draftReplyPostedAt())));
        verify(pullRequests, never()).replyToReviewThread(anyString(), anyInt(), eq(0L), anyString());
        verify(git).push(Path.of(task.worktreePath()));
        verify(phaseMachine).transition(TASK_ID, TaskPhase.PUSHED_AWAITING_CI, "round_approved", Actor.HUMAN);
        verify(agentRuns).transition(gated.runId(), AgentRun.STATUS_SUCCEEDED, "round_approved");
        assertThat(result.status()).isEqualTo(ReviewRound.STATUS_POSTED);
        assertThat(result.postedAt()).isEqualTo(NOW);
    }

    @Test
    void approveUsesTheCorrectReplyApiForMixedInlineAndGeneralComments()
            throws Exception
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        Task task = task();
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        ReviewComment inline = draftedComment("inline", 111L, "Inline reply");
        ReviewComment general = generalDraftedComment("general", 222L, "General reply");
        when(stageStore.findCommentsByRound(UUID.fromString(gated.id())))
                .thenReturn(List.of(inline, general));
        when(roundStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(gated.id());

        verify(pullRequests).replyToReviewThread(REPO, PR_NUMBER, 111L, "Inline reply");
        verify(pullRequests).commentOnPullRequest(REPO, PR_NUMBER, 0L, "General reply", false);
        verify(pullRequests).setReviewThreadResolved(REPO, PR_NUMBER, 0L, 111L, true);
        verify(pullRequests, never()).setReviewThreadResolved(REPO, PR_NUMBER, 0L, 222L, true);
        verify(stageStore).markRemoteThreadResolutionPosted(inline.id(), NOW);
    }

    @Test
    void approveCheckpointsAuthorizationBeforeAnyRemoteEffect()
            throws Exception
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        ReviewComment fixed = comment("fixed", NOW, true);
        when(stageStore.findCommentsByRound(UUID.fromString(gated.id())))
                .thenReturn(List.of(fixed));
        when(stageStore.isRemoteThreadResolutionPosted(fixed.id()))
                .thenReturn(false, true);
        when(roundStore.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("phase write failed"))
                .doNothing()
                .when(phaseMachine).transition(
                        TASK_ID, TaskPhase.PUSHED_AWAITING_CI, "round_approved", Actor.HUMAN);

        assertThatThrownBy(() -> service.approve(gated.id()))
                .hasMessageContaining("phase write failed");
        verifyNoInteractions(pullRequests, git);
        service.approve(gated.id());

        verify(pullRequests, times(1)).setReviewThreadResolved(
                REPO, PR_NUMBER, 0L, fixed.remoteCommentId(), true);
        verify(stageStore, times(1)).markRemoteThreadResolutionPosted(fixed.id(), NOW);
        verify(git, times(1)).push(Path.of(task().worktreePath()));
    }

    @Test
    void parkedTaskWinsTheRaceBeforeRoundApprovalCanPublish()
            throws Exception
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        AtomicReference<Task> current = new AtomicReference<>(task());
        when(taskStore.findTaskById(TASK_ID)).thenAnswer(inv -> Optional.of(current.get()));
        CountDownLatch parkOwnsLock = new CountDownLatch(1);
        CountDownLatch releasePark = new CountDownLatch(1);
        CompletableFuture<Void> park = CompletableFuture.runAsync(() ->
                TaskPhaseMachine.withTaskLock(TASK_ID, () -> {
                    current.set(task().withStatus(TaskStatus.NEEDS_ATTENTION));
                    parkOwnsLock.countDown();
                    try {
                        releasePark.await();
                    }
                    catch (InterruptedException e) {
                        java.lang.Thread.currentThread().interrupt();
                    }
                    return null;
                }));
        assertThat(parkOwnsLock.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Throwable> approval = CompletableFuture.supplyAsync(() -> {
            try {
                service.approve(gated.id());
                return null;
            }
            catch (Throwable failure) {
                return failure;
            }
        });
        releasePark.countDown();
        park.get(5, TimeUnit.SECONDS);

        assertThat(approval.get(5, TimeUnit.SECONDS))
                .hasMessageContaining("round gate is stale");
        verifyNoInteractions(stageStore, pullRequests, git);
    }

    @Test
    void approveResumesAfterAPartialReplyFailureWithoutRepostingSuccessfulReplies()
            throws Exception
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        Task task = task();
        AtomicReference<Task> currentTask = new AtomicReference<>(task);
        when(taskStore.findTaskById(TASK_ID))
                .thenAnswer(ignored -> Optional.of(currentTask.get()));
        when(taskStore.listPhaseEvents(TASK_ID)).thenReturn(List.of(new TaskPhaseEvent(
                1L, TASK_ID, TaskPhase.AWAITING_REMOTE_REVIEW, TaskPhase.PUSHED_AWAITING_CI,
                NOW, "round_approved", Actor.HUMAN)));
        doAnswer(ignored -> {
            currentTask.set(taskAtPhase(TaskPhase.PUSHED_AWAITING_CI));
            return null;
        }).when(phaseMachine).transition(
                TASK_ID, TaskPhase.PUSHED_AWAITING_CI, "round_approved", Actor.HUMAN);
        ReviewComment first = draftedComment("c1", 111L, "First reply");
        ReviewComment second = draftedComment("c2", 222L, "Second reply");
        UUID roundId = UUID.fromString(gated.id());
        when(stageStore.findCommentsByRound(roundId)).thenReturn(List.of(first, second));
        when(stageStore.saveReviewComment(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roundStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("second reply failed"))
                .doNothing()
                .when(pullRequests).replyToReviewThread(REPO, PR_NUMBER, 222L, "Second reply");

        assertThatThrownBy(() -> service.approve(gated.id()))
                .hasMessageContaining("second reply failed");

        ArgumentCaptor<ReviewComment> savedReply = ArgumentCaptor.forClass(ReviewComment.class);
        verify(stageStore).saveReviewComment(savedReply.capture());
        assertThat(savedReply.getValue().draftReplyPostedAt()).isEqualTo(NOW);
        verify(roundStore, never()).save(any());
        verify(git).push(Path.of(task.worktreePath()));

        when(stageStore.findCommentsByRound(roundId))
                .thenReturn(List.of(savedReply.getValue(), second));
        ReviewRound result = service.approve(gated.id());

        verify(pullRequests, times(1)).replyToReviewThread(REPO, PR_NUMBER, 111L, "First reply");
        verify(pullRequests, times(2)).replyToReviewThread(REPO, PR_NUMBER, 222L, "Second reply");
        verify(git, times(2)).push(Path.of(task.worktreePath()));
        verify(phaseMachine, times(1)).transition(
                TASK_ID, TaskPhase.PUSHED_AWAITING_CI, "round_approved", Actor.HUMAN);
        assertThat(result.status()).isEqualTo(ReviewRound.STATUS_POSTED);
    }

    @Test
    void approveResumesRemoteEffectsFromTheDurableApprovalCheckpoint()
            throws Exception
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        Task alreadyPushed = taskAtPhase(TaskPhase.PUSHED_AWAITING_CI);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(alreadyPushed));
        when(taskStore.listPhaseEvents(TASK_ID)).thenReturn(List.of(new TaskPhaseEvent(
                1L, TASK_ID, TaskPhase.AWAITING_REMOTE_REVIEW, TaskPhase.PUSHED_AWAITING_CI,
                NOW, "round_approved", Actor.HUMAN)));
        when(roundStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewRound result = service.approve(gated.id());

        verify(stageStore).findCommentsByRound(UUID.fromString(gated.id()));
        verify(git).push(Path.of(alreadyPushed.worktreePath()));
        verifyNoInteractions(pullRequests);
        verify(phaseMachine, never()).transition(anyString(), any(), anyString(), any());
        verify(agentRuns).transition(gated.runId(), AgentRun.STATUS_SUCCEEDED, "round_approved");
        assertThat(result.status()).isEqualTo(ReviewRound.STATUS_POSTED);
    }

    @Test
    void pushedTaskWithoutThisRoundsCheckpointCannotSkipRemoteEffectsAndFinalize()
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        when(taskStore.findTaskById(TASK_ID))
                .thenReturn(Optional.of(taskAtPhase(TaskPhase.PUSHED_AWAITING_CI)));
        when(taskStore.listPhaseEvents(TASK_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.approve(gated.id()))
                .hasMessageContaining("no approval checkpoint");

        verifyNoInteractions(stageStore, pullRequests, git);
        verify(roundStore, never()).save(any());
    }

    @Test
    void staleRoundGateCannotPublishAfterTheTaskBecameTerminal()
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        when(taskStore.findTaskById(TASK_ID))
                .thenReturn(Optional.of(taskAtPhase(TaskPhase.COMPLETED)));

        assertThatThrownBy(() -> service.approve(gated.id()))
                .hasMessageContaining("round gate is stale");

        verifyNoInteractions(stageStore, pullRequests, git);
        verify(roundStore, never()).save(any());
    }

    @Test
    void staleRoundGateCannotPublishForANeedsAttentionTask()
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        Task parked = taskAtPhase(TaskPhase.AWAITING_REMOTE_REVIEW)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(parked));

        assertThatThrownBy(() -> service.approve(gated.id()))
                .hasMessageContaining("round gate is stale");

        verifyNoInteractions(stageStore, pullRequests, git);
        verify(roundStore, never()).save(any());
    }

    @Test
    void approvePushFailurePostsNoRepliesAndLeavesTheRoundGated()
            throws Exception
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        Task task = task();
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(stageStore.findCommentsByRound(UUID.fromString(gated.id())))
                .thenReturn(List.of(draftedComment("c1", 111L, "First reply")));
        doThrow(new IOException("network down"))
                .when(git).push(Path.of(task.worktreePath()));

        assertThatThrownBy(() -> service.approve(gated.id()))
                .hasMessageContaining("push failed");

        verifyNoInteractions(pullRequests);
        verify(stageStore, never()).saveReviewComment(any());
        verify(roundStore, never()).save(any());
    }

    @Test
    void approvePreflightsEveryDraftBeforePostingTheFirstReply()
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        ReviewComment valid = draftedComment("c1", 111L, "First reply");
        ReviewComment missingRemoteId = new ReviewComment(
                commentId("c2"), TASK_ID, "Foo.java", 10, "please fix", NOW,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/" + REPO + "/pull/" + PR_NUMBER + "#discussion_rc2",
                true, null, UUID.fromString(gated.id()), "Second reply", NOW,
                "RIGHT", null, null);
        when(stageStore.findCommentsByRound(UUID.fromString(gated.id())))
                .thenReturn(List.of(valid, missingRemoteId));

        assertThatThrownBy(() -> service.approve(gated.id()))
                .hasMessageContaining("no valid remote comment id");

        verifyNoInteractions(pullRequests, git);
        verify(stageStore, never()).saveReviewComment(any());
        verify(roundStore, never()).save(any());
    }

    @Test
    void approveRejectsUnresolvedCommentsBeforeAnyRemoteEffect()
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(roundStore.findById(gated.id())).thenReturn(Optional.of(gated));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(stageStore.findCommentsByRound(UUID.fromString(gated.id())))
                .thenReturn(List.of(comment("open", NOW)));

        assertThatThrownBy(() -> service.approve(gated.id()))
                .hasMessageContaining("1 unresolved comments");

        verifyNoInteractions(pullRequests, git);
        verify(stageStore, never()).saveReviewComment(any());
        verify(roundStore, never()).save(any());
    }

    private static Task task()
    {
        return taskAtPhase(TaskPhase.AWAITING_REMOTE_REVIEW);
    }

    private static Task taskAtPhase(TaskPhase phase)
    {
        return new Task(
                TASK_ID, "t1", 1L, TaskStatus.IN_REVIEW, "dev/x", "/tmp/wt", "main", "/tmp/clone",
                null, null, null, null, null, "DEVELOP", PR_NUMBER, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null,
                null, phase, null, 0, REPO + "#" + PR_NUMBER);
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
        return turn(stageId, null);
    }

    private static ThreadTurn turn(String stageId, String runId)
    {
        return turn(stageId, runId, ThreadTurnStatus.COMPLETED);
    }

    private static ThreadTurn turn(String stageId, String runId, ThreadTurnStatus status)
    {
        return new ThreadTurn(
                "turn-1", "t1", TASK_ID, ThreadResourceLane.CLI,
                status, "prompt", NOW, NOW, NOW,
                status == ThreadTurnStatus.QUEUED || status == ThreadTurnStatus.RUNNING ? null : NOW,
                status == ThreadTurnStatus.FAILED ? "failed" : null,
                TurnInitiator.unattended("review-round"),
                stageId, ThreadScope.TASK, runId);
    }

    private static AgentRun roundRun(ReviewRound round)
    {
        return new AgentRun(
                round.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID.toString(), null, REMOTE_STAGE_ID.toString(),
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
    }

    private static StageInstance remoteStage()
    {
        return new StageInstance(
                REMOTE_STAGE_ID, TASK_ID, StageType.REMOTE_DEVELOPMENT_STAGE,
                StageState.OPEN, NOW, null, null);
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
        return comment(idSuffix, createdAt, false);
    }

    private static ReviewComment comment(String idSuffix, Instant createdAt, boolean resolved)
    {
        return new ReviewComment(
                commentId(idSuffix), TASK_ID, "Foo.java", 10, "please fix", createdAt,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/" + REPO + "/pull/" + PR_NUMBER + "#discussion_r" + idSuffix,
                resolved, 999L, null, null, null, "RIGHT", null, null);
    }

    private static ReviewComment draftedComment(String idSuffix, long remoteCommentId, String draftBody)
    {
        return new ReviewComment(
                commentId(idSuffix), TASK_ID, "Foo.java", 10, "please fix", NOW,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/" + REPO + "/pull/" + PR_NUMBER + "#discussion_r" + idSuffix,
                true, remoteCommentId, null, draftBody, NOW, "RIGHT", null, null);
    }

    private static ReviewComment generalDraftedComment(String idSuffix, long remoteCommentId, String draftBody)
    {
        return new ReviewComment(
                commentId(idSuffix), TASK_ID, null, 0, "general question", NOW,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/" + REPO + "/pull/" + PR_NUMBER + "#issuecomment-" + idSuffix,
                true, remoteCommentId, null, draftBody, NOW, "RIGHT", null, null);
    }
}
