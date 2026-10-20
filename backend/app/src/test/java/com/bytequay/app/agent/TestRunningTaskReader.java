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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.BlobRef;
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
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.agent.RunningTask.Kind;
import com.bytequay.app.agent.RunningTask.Status;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRunningTaskReader
{
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ZERO,
            ImmutableList.of(), Optional.empty(), Optional.empty());

    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentApprovalStore approvals = mock(AgentApprovalStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final QueuedInputStore queues = mock(QueuedInputStore.class);
    private final CherryPickExecutionStore cherryPicks = mock(CherryPickExecutionStore.class);
    private final CiAutofixStore ciFixes = mock(CiAutofixStore.class);
    private final Clock clock = mock(Clock.class);
    private final RunningTaskReader reader = new RunningTaskReader(workspaces, tasks, turns, approvals, queues, reviews, cherryPicks, ciFixes, clock);

    @BeforeEach
    void setup()
    {
        when(clock.instant()).thenReturn(NOW);
        RepoRef repo = new RepoRef("owner", "repo");
        Workspace workspace = new Workspace("workspace", "Repository", 1, repo, Optional.empty(), Optional.empty(), 1, repo, Workspace.Topology.DIRECT,
                "main", "https://github.com/owner/repo.git", Optional.empty(), Optional.empty(), "/tmp/repository", Optional.empty(), Optional.empty(), 0, NOW, NOW);
        when(workspaces.findAllRetained()).thenReturn(ImmutableList.of(workspace));
    }

    @Test
    void keepsEveryRunningTaskAndOrdersByLatestRunRatherThanTaskEdits()
    {
        AgentTask old = task(true);
        AgentTask newer = task(true);
        AgentTask idle = task(true);
        AgentTurn oldTurn = turn(old, NOW.minusSeconds(300));
        AgentTurn newerTurn = turn(newer, NOW.minusSeconds(10));
        when(tasks.listByWorkspace("workspace")).thenReturn(ImmutableList.of(old, idle, newer));
        when(turns.list(old.id())).thenReturn(ImmutableList.of(oldTurn));
        when(turns.list(newer.id())).thenReturn(ImmutableList.of(newerTurn));
        assertThat(reader.current()).extracting(RunningTask::id).containsExactly(newer.id().toString(), old.id().toString());
        assertThat(reader.current()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo(Status.RUNNING);
            assertThat(item.expiresAt()).isEmpty();
        });
    }

    @Test
    void restoresFinishedRunsAndExpiresExactlyTenMinutesAfterCompletion()
    {
        AgentTask task = task(true);
        AgentTurn turn = turn(task, NOW.minusSeconds(60)).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        show(task, turn);
        when(clock.instant()).thenReturn(NOW.plusSeconds(599));
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.COMPLETED);
            assertThat(item.expiresAt()).contains(NOW.plusSeconds(600));
        });
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        assertThat(reader.current()).isEmpty();
        show(task, turn(task, NOW.plusSeconds(601)));
        when(clock.instant()).thenReturn(NOW.plusSeconds(601));
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.RUNNING);
            assertThat(item.expiresAt()).isEmpty();
        });
    }

    @Test
    void distinguishesFailuresStopsAndApprovalsFromSuccess()
    {
        AgentTask task = task(true);
        AgentTurn turn = turn(task, NOW.minusSeconds(60));
        show(task, turn.finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("Provider failed"), NOW));
        assertThat(reader.current()).singleElement().extracting(RunningTask::status).isEqualTo(Status.FAILED);
        show(task, turn.finish(TurnState.CANCELLED, AgentMetrics.empty(), Optional.of("Stopped by user"), NOW));
        assertThat(reader.current()).singleElement().extracting(RunningTask::status).isEqualTo(Status.STOPPED);
        var providerRequest = new ProviderApprovalRequest(ProviderApprovalRequest.Kind.TOOL, "Permission needed", "Run tests", ImmutableList.of("test"),
                ImmutableList.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY), false, ProviderAgentEventSource.root(), NOW);
        AgentApproval approval = AgentApproval.pending(new ApprovalRequest(ApprovalId.random(), task.id(), turn.id(), providerRequest, NOW));
        when(approvals.listPending()).thenReturn(ImmutableList.of(approval));
        show(task, turn);
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.WAITING);
            assertThat(item.expiresAt()).isEmpty();
        });
        show(task, turn(task, NOW));
        assertThat(reader.current()).singleElement().extracting(RunningTask::status).isEqualTo(Status.RUNNING);
    }

    @Test
    void keepsAutomationAndUnverifiedCleanupVisible()
    {
        AgentTask task = task(true);
        AgentTurn finished = turn(task, NOW.minusSeconds(1200)).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.minusSeconds(900));
        show(task.withDevelopmentContinuation(true, NOW), finished);
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.RUNNING);
            assertThat(item.expiresAt()).isEmpty();
        });
        show(task.claimWorktreeMutation(WorktreeMutationClaim.recovery("launch", NOW), NOW), finished);
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.BLOCKED);
            assertThat(item.expiresAt()).isEmpty();
        });
        show(task.claimWorktreeMutation(WorktreeMutationClaim.turn(finished.id(), NOW), NOW), finished);
        assertThat(reader.current()).singleElement().extracting(RunningTask::status).isEqualTo(Status.RUNNING);
    }

    @Test
    void routesReviewsAndWaitsForTheWholeRun()
    {
        AgentTask task = task(false);
        AgentTurn finished = turn(task, NOW.minusSeconds(60)).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.minusSeconds(10));
        AgentReview review = pullRequestReview("review", "workspace", "owner/repo", 42, task.id().toString(), NOW);
        when(reviews.findReviewByTask(task.id().toString())).thenReturn(Optional.of(review));
        ReviewRun run = new ReviewRun("run", review.id(), 1, ReviewRun.Scope.FULL, "main", "base", "base", "head", Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(finished.id().toString()), ReviewRun.State.RUNNING,
                Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(ReviewStep.initial()), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW.minusSeconds(60), NOW.minusSeconds(10), Optional.empty());
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(run));
        show(task, finished);
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(Kind.REVIEW);
            assertThat(item.targetId()).isEqualTo("review");
            assertThat(item.status()).isEqualTo(Status.RUNNING);
            assertThat(item.expiresAt()).isEmpty();
        });
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(run.ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW)));
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.COMPLETED);
            assertThat(item.expiresAt()).contains(NOW.plusSeconds(600));
        });
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(run.ended(ReviewRun.State.INCOMPLETE, Optional.empty(), NOW)));
        assertThat(reader.current()).singleElement().extracting(RunningTask::status).isEqualTo(Status.FAILED);
    }

    @Test
    void waitsForQueuedHandoffsButDoesNotKeepIdlePausedQueuesForever()
    {
        AgentTask task = task(true);
        AgentTurn finished = turn(task, NOW.minusSeconds(60)).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        QueuedAgentInput input = QueuedAgentInput.queued(QueuedInputId.random(), task.id(), 1, "next-message", new BlobRef("input.txt", "text/plain", 4), "Next", SETTINGS, NOW);
        show(task, finished);
        when(queues.listQueued(task.id())).thenReturn(ImmutableList.of(input));
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.RUNNING);
            assertThat(item.expiresAt()).isEmpty();
        });
        when(queues.listQueued(task.id())).thenReturn(ImmutableList.of(input.paused("Please resume", NOW)));
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.COMPLETED);
            assertThat(item.expiresAt()).contains(NOW.plusSeconds(600));
        });
    }

    @Test
    void opensStandaloneCiOnItsPullRequestAndRetainsItsActualFinishTime()
    {
        AgentTask task = task(false);
        String sha = "a".repeat(40);
        RepoRef repo = new RepoRef("owner", "repo");
        CiAutofixRun run = CiAutofixRun.create(CiAutofixRunId.random(), "ci", new CiAutofixRun.Scope(new PullRequestRef("owner", "repo", 42), repo,
                "workspace", repo, "user", "feature", "main", "codex", Optional.empty()), sha, sha, sha,
                new CiAutofixRun.Authorization(NOW), NOW);
        show(task, turn(task, NOW));
        when(ciFixes.findByAgentTaskId(task.id().toString())).thenReturn(Optional.of(run));
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(Kind.CI_FIX);
            assertThat(item.targetId()).isEqualTo("42");
            assertThat(item.status()).isEqualTo(Status.RUNNING);
        });
        when(ciFixes.findByAgentTaskId(task.id().toString())).thenReturn(Optional.of(run.finish(CiAutofixRun.FinishReason.GREEN, "Passed", NOW)));
        assertThat(reader.current()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(Status.COMPLETED);
            assertThat(item.expiresAt()).contains(NOW.plusSeconds(600));
        });
    }

    @Test
    void excludesInternalTasksWithNoUserFacingDestination()
    {
        AgentTask task = task(false);
        show(task, turn(task, NOW));
        assertThat(reader.current()).isEmpty();
    }

    private void show(AgentTask task, AgentTurn turn)
    {
        when(tasks.listByWorkspace("workspace")).thenReturn(ImmutableList.of(task));
        when(turns.list(task.id())).thenReturn(ImmutableList.of(turn));
    }

    private static AgentTask task(boolean interactive)
    {
        return AgentTask.created(TaskId.random(), Optional.of("workspace"), interactive, "Fix the connector", new AgentLaneId("codex"),
                new RepositoryIdentity("owner", "repo"), Path.of("/tmp/running-task"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW.minusSeconds(3600));
    }

    private static AgentTurn turn(AgentTask task, Instant at)
    {
        return AgentTurn.accepted(TurnId.random(), task.id(), "request", SETTINGS, at)
                .markDeliveryStarted(at).running(new ProviderTurnRef("provider-" + at), Optional.empty());
    }
}
