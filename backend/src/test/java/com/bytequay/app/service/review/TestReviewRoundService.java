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

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewRoundService
{
    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private static final String TASK_ID = "t1.k1";
    private static final String REPO = "acme/widgets";
    private static final int PR_NUMBER = 42;

    private final TaskStore tasks = mock(TaskStore.class);
    private final StageStore stages = mock(StageStore.class);
    private final ReviewRoundStore rounds = mock(ReviewRoundStore.class);
    private final BrainReviewService brain = mock(BrainReviewService.class);
    private final PRService prs = mock(PRService.class);
    private final ReviewRoundStateMachine roundMachine = mock(ReviewRoundStateMachine.class);
    private final RoundGateSaga gateSaga = mock(RoundGateSaga.class);
    private final TaskCommandExecutor commands =
            new TaskCommandExecutor(new TestTransactionManager());
    private final ReviewRoundServiceImpl service = new ReviewRoundServiceImpl(
            tasks, stages, rounds, brain, prs, commands, roundMachine, gateSaga,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void currentTaskExists()
    {
        when(tasks.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
    }

    @Test
    void findByTaskProjectsAttentionWithoutWritingLifecycleState()
    {
        ReviewRound live = round(ReviewRound.STATUS_TRIAGING);
        when(rounds.findByTask(TASK_ID)).thenReturn(List.of(live));
        when(tasks.findTaskById(TASK_ID)).thenReturn(Optional.of(
                task().withStatus(TaskStatus.NEEDS_ATTENTION)));

        assertThat(service.findByTask(TASK_ID).getFirst().status())
                .isEqualTo(ReviewRound.STATUS_PAUSED);

        verify(rounds, never()).save(any());
    }

    @Test
    void findByTaskProjectsOpenBrainFindingsForOlderRounds()
    {
        ReviewRound brainRound = new ReviewRound(
                "brain-round", TASK_ID, 1, List.of(), ReviewRound.STATUS_CLOSED,
                ReviewRound.ReviewRoundStats.empty(), "run-brain", NOW, null, null,
                ReviewRound.ORIGIN_BRAIN, ReviewRound.VERDICT_CHANGES_REQUESTED, 3, 3);
        PR pr = PR.create("pr1", TASK_ID, "dev/x", "main", "T", "", NOW);
        PRComment finding = new PRComment(
                "c1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, PRTimelineEntry.ACTOR_BRAIN, "still open", NOW,
                null, null, null, null, null, "RIGHT", null, null);
        when(rounds.findByTask(TASK_ID)).thenReturn(List.of(brainRound));
        when(prs.findByTask(TASK_ID)).thenReturn(Optional.of(pr));
        when(prs.comments(pr.id())).thenReturn(List.of(finding));

        assertThat(service.findByTask(TASK_ID).getFirst().stats().open()).isEqualTo(1);
    }

    @Test
    void existingRoundIsRefreshedAndDrivenWithoutOpeningAnother()
    {
        ReviewRound live = round(ReviewRound.STATUS_ADDRESSING);
        ReviewComment open = comment("open", NOW, false);
        when(rounds.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(rounds.findById(live.id())).thenReturn(Optional.of(live));
        when(stages.findCommentsByRound(UUID.fromString(live.id()))).thenReturn(List.of(open));

        service.reconcile(task());

        verify(rounds).updateStats(
                live.id(), new ReviewRound.ReviewRoundStats(0, 0, 0, 1));
        verify(roundMachine, never()).openExternalInCommand(anyString(), anyString(), any());
        verify(brain).reviewBeforeRoundGate(live, task());
    }

    @Test
    void freshRemoteCommentsOpenThroughTheRoundMachine()
    {
        ReviewComment first = comment("one", NOW.minus(Duration.ofMinutes(15)), false);
        ReviewComment second = comment("two", NOW.minus(Duration.ofMinutes(12)), false);
        PR pr = PR.create("pr1", TASK_ID, "dev/x", "main", "T", "", NOW);
        ReviewRound opened = round(ReviewRound.STATUS_ADDRESSING);
        when(rounds.findLiveByTask(TASK_ID)).thenReturn(Optional.empty());
        when(stages.findUnroundedRemoteComments(TASK_ID)).thenReturn(List.of(first, second));
        when(prs.findByTask(TASK_ID)).thenReturn(Optional.of(pr));
        when(roundMachine.openExternalInCommand(
                TASK_ID, pr.id(), List.of(first.id(), second.id()))).thenReturn(opened);

        service.reconcile(task());

        verify(roundMachine).openExternalInCommand(
                TASK_ID, pr.id(), List.of(first.id(), second.id()));
        verify(brain).reviewBeforeRoundGate(opened, task());
    }

    @Test
    void debounceDoesNotOpenAPartialRemoteBatch()
    {
        when(rounds.findLiveByTask(TASK_ID)).thenReturn(Optional.empty());
        when(stages.findUnroundedRemoteComments(TASK_ID)).thenReturn(
                List.of(comment("recent", NOW.minus(Duration.ofMinutes(2)), false)));

        service.reconcile(task());

        verify(roundMachine, never()).openExternalInCommand(anyString(), anyString(), any());
    }

    @Test
    void closeSealsEveryNonclosedRoundThroughTheMachine()
    {
        ReviewRound addressing = round(ReviewRound.STATUS_ADDRESSING);
        ReviewRound posted = round(ReviewRound.STATUS_POSTED);
        ReviewRound closed = round(ReviewRound.STATUS_CLOSED);
        when(rounds.findByTask(TASK_ID)).thenReturn(List.of(addressing, posted, closed));

        service.closeOpenRounds(TASK_ID, "task_terminal");

        verify(roundMachine).sealInCommand(TASK_ID, addressing.id(), "task_terminal");
        verify(roundMachine).sealInCommand(TASK_ID, posted.id(), "task_terminal");
        verify(roundMachine, never()).sealInCommand(TASK_ID, closed.id(), "task_terminal");
    }

    @Test
    void approveDelegatesToTheDurableGateSaga()
    {
        ReviewRound gated = round(ReviewRound.STATUS_AWAITING_GATE);
        when(gateSaga.approve(gated.id())).thenReturn(gated);

        assertThat(service.approve(gated.id())).isSameAs(gated);

        verify(gateSaga).approve(gated.id());
    }

    @Test
    void recomputeStatsUsesATargetedMetadataUpdate()
    {
        ReviewRound round = round(ReviewRound.STATUS_ADDRESSING);
        UUID roundId = UUID.fromString(round.id());
        when(rounds.findById(round.id())).thenReturn(Optional.of(round));
        when(stages.findCommentsByRound(roundId)).thenReturn(List.of(
                comment("fixed", NOW, true),
                comment("open", NOW, false),
                repliedComment("replied")));

        service.recomputeStats(round.id());

        verify(rounds).updateStats(
                round.id(), new ReviewRound.ReviewRoundStats(1, 1, 0, 1));
    }

    private static Task task()
    {
        return new Task(
                TASK_ID, "t1", 1L, TaskStatus.IN_REVIEW, "dev/x", "/tmp/wt",
                "main", "/tmp/clone", null, null, null, null, null, "DEVELOP",
                PR_NUMBER, null, 0L, 0L, 0L, null, NOW, null, null, null,
                null, null, null, TaskPhase.AWAITING_REMOTE_REVIEW, null, 0,
                REPO + "#" + PR_NUMBER);
    }

    private static ReviewRound round(ReviewRoundState status)
    {
        boolean gated = status == ReviewRound.STATUS_AWAITING_GATE
                || status == ReviewRound.STATUS_POSTED;
        return new ReviewRound(
                UUID.randomUUID().toString(), TASK_ID, 1, List.of(), status,
                ReviewRound.ReviewRoundStats.empty(), "run1", NOW.minusSeconds(60),
                gated ? NOW : null, null, ReviewRound.ORIGIN_EXTERNAL, null, 0,
                ReviewRound.DEFAULT_BRAIN_BUDGET);
    }

    private static ReviewComment comment(String suffix, Instant createdAt, boolean resolved)
    {
        UUID id = UUID.nameUUIDFromBytes(suffix.getBytes(StandardCharsets.UTF_8));
        return new ReviewComment(
                id, TASK_ID, "Foo.java", 10, "please fix", createdAt,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/" + REPO + "/pull/" + PR_NUMBER + "#discussion_r" + suffix,
                resolved, 999L, null, null, null, "RIGHT", null, null);
    }

    private static ReviewComment repliedComment(String suffix)
    {
        UUID id = UUID.nameUUIDFromBytes(suffix.getBytes(StandardCharsets.UTF_8));
        return new ReviewComment(
                id, TASK_ID, "Foo.java", 10, "please fix", NOW,
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/" + REPO + "/pull/" + PR_NUMBER + "#discussion_r" + suffix,
                true, 999L, null, "done", null, "RIGHT", null, null);
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
