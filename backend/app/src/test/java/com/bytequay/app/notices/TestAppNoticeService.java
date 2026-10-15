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
package com.bytequay.app.notices;

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.store.PullRequestStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAppNoticeService
{
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ZERO, ImmutableList.of());

    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentApprovalStore approvals = mock(AgentApprovalStore.class);
    private final QueuedInputStore queues = mock(QueuedInputStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final AppNoticeService service = new AppNoticeService(tasks, turns, approvals, queues, reviews, pullRequests, new ObjectMapper());
    private final AgentTask task = AgentTask.created(TaskId.random(), Optional.of("workspace"), true, "Fix recovery", new AgentLaneId("codex"),
            new RepositoryIdentity("owner", "repo"), Path.of("/tmp/notice-worktree"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);

    @Test
    void finishesOnlyWhenTheTaskIsSettledAndCoalescesFailuresWithPausedMessages()
    {
        AgentTurn turn = turn();
        show(task, turn.finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(10)));
        assertThat(service.current()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo(AppNotice.Kind.TURN_FINISHED);
            assertThat(notice.turnId()).contains(turn.id().toString());
            assertThat(notice.reviewId()).isEmpty();
            assertThat(notice.title()).isEqualTo(task.title());
            assertThat(notice.pullRequest()).isEmpty();
            assertThat(notice.pullRequestTitle()).isEmpty();
            assertThat(notice.changes()).isEmpty();
        });
        show(task.withDevelopmentContinuation(true, NOW), turn.finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(10)));
        assertThat(service.current()).isEmpty();
        show(task, turn.finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("Sign in required"), NOW.plusSeconds(10)));
        paused(QueuedAgentInput.State.PAUSED);
        assertThat(service.current()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo(AppNotice.Kind.FAILURE);
            assertThat(notice.message()).contains("Sign in required", "Queued messages are paused");
        });
        show(task, turn.finish(TurnState.CANCELLED, AgentMetrics.empty(), Optional.of("Stopped by user"), NOW.plusSeconds(10)));
        when(queues.listQueued(task.id())).thenReturn(ImmutableList.of());
        assertThat(service.current()).isEmpty();
    }

    @Test
    void questionsTakePrecedenceOverCompletionAndPausedQueues()
    {
        AgentTurn asked = turn().withDevelopmentResult(new DevelopmentResultReport(false, Optional.of("Keep the public API?")))
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(10));
        AgentTurn summary = AgentTurn.accepted(TurnId.random(), task.id(), "summary", SETTINGS, NOW.plusSeconds(11))
                .withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.REFINE_SUMMARY, asked.id()))
                .markDeliveryStarted(NOW.plusSeconds(11)).running(new ProviderTurnRef("summary"), Optional.empty())
                .withDevelopmentResult(new DevelopmentResultReport(true, Optional.empty()))
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(12));
        show(task, asked, summary);
        paused(QueuedAgentInput.State.PAUSED);
        assertThat(service.current()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo(AppNotice.Kind.QUESTION);
            assertThat(notice.turnId()).contains(asked.id().toString());
            assertThat(notice.message()).isEqualTo("Keep the public API?");
        });
        show(task, asked, turnAt(NOW.plusSeconds(20)));
        assertThat(service.current()).isEmpty();
    }

    @Test
    void distinguishesQuestionToolsAndIgnoresStaleApprovals()
    {
        AgentTurn turn = turn();
        show(task, turn);
        approval(turn, ImmutableList.of("AskUserQuestion"), "{\"questions\":[{\"question\":\"Which branch?\"}]}");
        assertThat(service.current()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo(AppNotice.Kind.QUESTION);
            assertThat(notice.message()).isEqualTo("Which branch?");
        });
        approval(turn, ImmutableList.of("mvn verify"), "Run the tests");
        assertThat(service.current()).singleElement().extracting(AppNotice::kind).isEqualTo(AppNotice.Kind.APPROVAL);
        show(task, turn, turnAt(NOW.plusSeconds(20)));
        assertThat(service.current()).isEmpty();
        when(approvals.listPending()).thenReturn(ImmutableList.of());
        assertThat(service.current()).isEmpty();
    }

    @Test
    void reportsOnlyUnresolvedShutdownAndQueueBlocks()
    {
        AgentTurn done = turn().finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(10));
        show(task.claimWorktreeMutation(WorktreeMutationClaim.recovery("launch", NOW), NOW), done);
        assertThat(service.current()).singleElement().extracting(AppNotice::kind).isEqualTo(AppNotice.Kind.RECOVERY);
        show(task.claimWorktreeMutation(WorktreeMutationClaim.turn(done.id(), NOW), NOW), done);
        assertThat(service.current()).singleElement().extracting(AppNotice::kind).isEqualTo(AppNotice.Kind.RECOVERY);
        show(task, done);
        paused(QueuedAgentInput.State.UNCERTAIN);
        assertThat(service.current()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo(AppNotice.Kind.QUEUE_PAUSED);
            assertThat(notice.message()).contains("Delivery is uncertain");
        });
        when(queues.listQueued(task.id())).thenReturn(ImmutableList.of(queued()));
        assertThat(service.current()).isEmpty();
    }

    @Test
    void readsLiveTasksOutsideTheRecentPageAndExcludesInternalWork()
    {
        show(task, turn());
        when(tasks.listRecent(200)).thenReturn(ImmutableList.of());
        approval(turns.list(task.id()).getFirst(), ImmutableList.of("AskUserQuestion"), "");
        assertThat(service.current()).hasSize(1);
        AgentTask internal = AgentTask.created(TaskId.random(), Optional.of("workspace"), false, "Internal", new AgentLaneId("codex"),
                task.repository(), task.worktree(), task.defaults(), NOW);
        when(tasks.listLive()).thenReturn(ImmutableList.of(internal));
        assertThat(service.current()).isEmpty();
    }

    @Test
    void includesReviewTasksAndRoutesTheirCompletionAndRequestsToTheReview()
    {
        AgentTask reviewer = AgentTask.created(TaskId.random(), task.workspaceId(), false, "Review owner/repo#42", task.laneId(),
                new RepositoryIdentity("fork-owner", "repo"), task.worktree(), task.defaults(), NOW);
        AgentReview review = new AgentReview("review", "workspace", "owner/repo", OptionalInt.of(42), OptionalLong.empty(), reviewer.id().toString(),
                Optional.empty(), AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
        when(reviews.listOpenReviews()).thenReturn(ImmutableList.of(review));
        PullRequest pullRequest = new PullRequest(123, "owner/repo", 42, "Improve  recovery\nfor interrupted tasks", "author", "https://github.com/owner/repo/pull/42",
                NOW, NOW, PullRequest.Origin.OTHER, ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null, 0, 0, 0,
                "open", null, null, null, null, null, null, null, null, null, null);
        when(pullRequests.findIdByRepoAndNumber("owner/repo", 42)).thenReturn(Optional.of(123L));
        when(pullRequests.findById(123L)).thenReturn(Optional.of(pullRequest));
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), reviewer.id(), "review-input", SETTINGS, NOW)
                .markDeliveryStarted(NOW).running(new ProviderTurnRef("review-turn"), Optional.empty());
        show(reviewer, turn.finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(10)));
        assertThat(service.current()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo(AppNotice.Kind.TURN_FINISHED);
            assertThat(notice.taskId()).isEqualTo(reviewer.id().toString());
            assertThat(notice.reviewId()).contains(review.id());
            assertThat(notice.title()).isEqualTo("Review owner/repo#42");
            assertThat(notice.pullRequest()).contains("owner/repo#42");
            assertThat(notice.pullRequestTitle()).contains("Improve recovery for interrupted tasks");
            assertThat(notice.changes()).isEmpty();
        });
        show(reviewer, turn);
        approval(turn, ImmutableList.of("AskUserQuestion"), "");
        assertThat(service.current()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo(AppNotice.Kind.QUESTION);
            assertThat(notice.reviewId()).contains(review.id());
            assertThat(notice.pullRequestTitle()).contains("Improve recovery for interrupted tasks");
        });
        when(pullRequests.findById(123L)).thenReturn(Optional.empty());
        assertThat(service.current()).singleElement().satisfies(notice -> {
            assertThat(notice.pullRequest()).contains("owner/repo#42");
            assertThat(notice.pullRequestTitle()).isEmpty();
        });
        when(reviews.listOpenReviews()).thenReturn(ImmutableList.of());
        assertThat(service.current()).isEmpty();
    }

    @Test
    void summarizesTheLatestReviewScopeWithCommitFileAndLineCounts()
    {
        AgentReview review = new AgentReview("review", "workspace", "owner/repo", OptionalInt.of(42), OptionalLong.empty(), task.id().toString(),
                Optional.empty(), AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
        when(reviews.listOpenReviews()).thenReturn(ImmutableList.of(review));
        AgentTurn done = turn().finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(10));
        show(task, done);
        ReviewRun full = new ReviewRun("full", review.id(), 1, ReviewRun.Scope.FULL, "main", "base", "base", "head", Optional.empty(), Optional.empty(),
                ImmutableList.of(new ReviewCommit("a", "First", "author"), new ReviewCommit("b", "Second", "author")),
                ImmutableList.of(new DiffFile("a.java", "modified", 30, 8, null), new DiffFile("b.java", "modified", 12, 4, null)),
                ImmutableList.of(done.id().toString()), ReviewRun.State.ACCEPTED, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.of(NOW));
        when(reviews.listRuns(review.id())).thenReturn(ImmutableList.of(full));
        assertThat(service.current()).singleElement().extracting(AppNotice::changes).isEqualTo(Optional.of(new AppNotice.Changes(false, 2, 2, 42, 12)));

        ReviewRun update = new ReviewRun("update", review.id(), 2, ReviewRun.Scope.INCREMENTAL, "main", "base", "base", "head", Optional.empty(), Optional.of("prior"),
                ImmutableList.of(new ReviewCommit("c", "Rename image", "author")), ImmutableList.of(new DiffFile("image.png", "renamed", 0, 0, null)),
                ImmutableList.of(done.id().toString()), ReviewRun.State.ACCEPTED, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.of(NOW));
        when(reviews.listRuns(review.id())).thenReturn(ImmutableList.of(update, full));
        assertThat(service.current()).singleElement().extracting(AppNotice::changes).isEqualTo(Optional.of(new AppNotice.Changes(true, 1, 1, 0, 0)));
    }

    @Test
    void waitsForTheReviewRunToSettleBetweenAutomaticTurns()
    {
        AgentReview review = new AgentReview("review", "workspace", "owner/repo", OptionalInt.of(42), OptionalLong.empty(), task.id().toString(),
                Optional.empty(), AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
        when(reviews.listOpenReviews()).thenReturn(ImmutableList.of(review));
        AgentTurn investigation = turn().finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(10));
        ReviewRun run = new ReviewRun("run", review.id(), 1, ReviewRun.Scope.FULL, "main", "base", "base", "head", Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(investigation.id().toString()), ReviewRun.State.RUNNING,
                Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(ReviewStep.initial()), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
        when(reviews.listRuns(review.id())).thenReturn(ImmutableList.of(run));
        show(task, investigation);
        assertThat(service.current()).isEmpty();

        run = run.withStep(ReviewStep.initial().next(ReviewStep.Kind.VALIDATE), NOW.plusSeconds(11));
        when(reviews.listRuns(review.id())).thenReturn(ImmutableList.of(run));
        assertThat(service.current()).isEmpty();
        AgentTurn validation = turnAt(NOW.plusSeconds(12));
        show(task, investigation, validation);
        approval(validation, ImmutableList.of("AskUserQuestion"), "");
        assertThat(service.current()).singleElement().extracting(AppNotice::kind).isEqualTo(AppNotice.Kind.QUESTION);
        approval(validation, ImmutableList.of("mvn verify"), "Run the tests");
        assertThat(service.current()).singleElement().extracting(AppNotice::kind).isEqualTo(AppNotice.Kind.APPROVAL);
        when(approvals.listPending()).thenReturn(ImmutableList.of());
        show(task, investigation, validation.finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("Sign in required"), NOW.plusSeconds(20)));
        assertThat(service.current()).singleElement().extracting(AppNotice::kind).isEqualTo(AppNotice.Kind.FAILURE);

        show(task, investigation, validation.finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(20)));
        assertThat(service.current()).isEmpty();
        when(reviews.listRuns(review.id())).thenReturn(ImmutableList.of(run.withTurn(validation.id().toString(), NOW.plusSeconds(12))
                .ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW.plusSeconds(21))));
        assertThat(service.current()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo(AppNotice.Kind.TURN_FINISHED);
            assertThat(notice.turnId()).contains(validation.id().toString());
            assertThat(notice.reviewId()).contains(review.id());
        });
    }

    private void show(AgentTask current, AgentTurn... history)
    {
        when(tasks.listRecent(200)).thenReturn(ImmutableList.of(current));
        when(tasks.listLive()).thenReturn(ImmutableList.of(current));
        when(turns.list(current.id())).thenReturn(ImmutableList.copyOf(history));
    }

    private AgentTurn turn()
    {
        return turnAt(NOW);
    }

    private AgentTurn turnAt(Instant at)
    {
        return AgentTurn.accepted(TurnId.random(), task.id(), "input-" + at, SETTINGS, at)
                .markDeliveryStarted(at).running(new ProviderTurnRef("provider-" + at), Optional.empty());
    }

    private void approval(AgentTurn turn, ImmutableList<String> scope, String detail)
    {
        var request = new ProviderApprovalRequest(ProviderApprovalRequest.Kind.TOOL, "Permission needed", detail, scope,
                ImmutableList.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY), ProviderAgentEventSource.root(), NOW);
        when(approvals.listPending()).thenReturn(ImmutableList.of(AgentApproval.pending(new ApprovalRequest(ApprovalId.random(), turn.taskId(), turn.id(), request, NOW))));
    }

    private void paused(QueuedAgentInput.State state)
    {
        QueuedAgentInput input = state == QueuedAgentInput.State.UNCERTAIN
                ? queued().starting(TurnId.random(), EventId.random(), NOW).uncertain("Backend restarted", NOW.plusSeconds(10))
                : queued().paused("Backend restarted", NOW.plusSeconds(10));
        when(queues.listQueued(task.id())).thenReturn(ImmutableList.of(input));
    }

    private QueuedAgentInput queued()
    {
        return QueuedAgentInput.queued(QueuedInputId.random(), task.id(), 1, "next-message", new BlobRef("input.txt", "text/plain", 4), "Next", SETTINGS, NOW);
    }
}
