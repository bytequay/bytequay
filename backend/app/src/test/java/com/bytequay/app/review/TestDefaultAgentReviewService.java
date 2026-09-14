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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultAgentReviewService
{
    private static final String WORKSPACE = "workspace-1";
    private static final String REPO = "owner/repo";
    private static final int NUMBER = 7;
    private static final String HEAD = "a1b2c3d4e5f0123456789abcdef0123456789abc";
    private static final String NEW_HEAD = "b2c3d4e5f60123456789abcdef0123456789abcd";
    private static final String BASE_SHA = "1111111111111111111111111111111111111111";
    private static final String MERGE_BASE = "2222222222222222222222222222222222222222";
    private static final Path REPOSITORY = Path.of("/tmp/bytequay/owner-repo");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AgentLaneId LANE = new AgentLaneId("codex");
    private static final TaskId TASK_ID = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final TurnId TURN_ONE = TurnId.valueOf("22222222-2222-4222-8222-222222222222");
    private static final TurnId TURN_TWO = TurnId.valueOf("33333333-3333-4333-8333-333333333333");
    private static final QueuedInputId QUEUED_INPUT = QueuedInputId.valueOf("44444444-4444-4444-8444-444444444444");
    /** The unit separator the commit log format puts between fields. */
    private static final String FIELD = "\u001f";
    private static final AgentTaskDefaults DEFAULTS = new AgentTaskDefaults(Optional.of("gpt-5-codex"), Optional.of("medium"), AgentPermissionPolicy.AUTO, Optional.empty());
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(
            Optional.of("gpt-5-codex"),
            Optional.of("medium"),
            AgentPermissionPolicy.AUTO,
            Duration.ofMinutes(30),
            ImmutableList.of());

    @Test
    void startCreatesOneTaskAndOneRunningRunDrivenByAByteQuayRequest()
    {
        Fixture fixture = new Fixture();
        when(fixture.driver.capabilities()).thenReturn(new AgentCapabilities(
                true, true, true, true, true, true, true, true, true, true,
                ImmutableSet.of(AgentPermissionPolicy.AUTO), ImmutableSet.of("text/plain")));

        AgentReviewProjection projection = fixture.service.start(WORKSPACE, REPO, NUMBER);

        assertThat(projection.task().nestedAgents()).isFalse();
        assertThat(projection.task().steering()).isTrue();
        assertThat(projection.review().repo()).isEqualTo(REPO);
        assertThat(projection.review().observedHeadSha()).contains(HEAD);
        assertThat(projection.review().state()).isEqualTo(AgentReview.State.OPEN);
        assertThat(projection.runs()).hasSize(1);
        assertThat(projection.runs().getFirst().state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(projection.runs().getFirst().scope()).isEqualTo(ReviewRun.Scope.FULL);
        assertThat(projection.byteQuayTurnIds()).containsExactly(TURN_ONE.toString());

        ArgumentCaptor<CreateAgentTaskCommand> created = ArgumentCaptor.forClass(CreateAgentTaskCommand.class);
        verify(fixture.tasks, times(1)).create(created.capture());
        assertThat(created.getValue().workspaceId()).isEqualTo(WORKSPACE);
        assertThat(created.getValue().startCommit()).isEqualTo(HEAD);
        assertThat(created.getValue().title()).isEqualTo("Review owner/repo#7");
        assertThat(created.getValue().generatedBranch()).isEqualTo("octocat/bytequay/review-owner-repo-7-" + created.getValue().taskId().toString().substring(0, 8));

        // The review request is written by ByteQuay, so the transcript must not attribute it to the user.
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(1)).start(started.capture());
        assertThat(started.getValue().message())
                .contains("Review pull request owner/repo#7")
                .contains("a1b2c3d")
                .contains("Report with review_save_summary");
        assertThat(started.getValue().idempotencyKey()).startsWith("review-run:");
        verify(fixture.observer, times(1)).watch(any(AgentReview.class));
    }

    @Test
    void startIsIdempotentForTheSamePullRequest()
    {
        Fixture fixture = new Fixture();

        AgentReviewProjection first = fixture.service.start(WORKSPACE, REPO, NUMBER);
        AgentReviewProjection second = fixture.service.start(WORKSPACE, "Owner/Repo", NUMBER);

        assertThat(second.review().id()).isEqualTo(first.review().id());
        assertThat(second.runs()).hasSize(1);
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
        verify(fixture.worktrees, never()).inspect(any(TaskWorktree.class));
    }

    @Test
    void projectionKeepsValidationEvidenceOutsideTheInitialContextSelection()
    {
        Fixture fixture = new Fixture();
        ReviewRun run = fixture.store.findRun(fixture.service.start(WORKSPACE, REPO, NUMBER).runs().getFirst().id()).orElseThrow();
        ReviewEvidence context = new ReviewEvidence("E1", "src/App.java", "frozen head", "parse(input)", "");
        ReviewEvidence validation = new ReviewEvidence("E2", "command output: regression test", "validation turn", "HEAD passed; old condition failed", "");
        ReviewInvestigation investigation = new ReviewInvestigation("Parser behavior", ImmutableList.of("src/App.java"),
                ImmutableList.of("E1"), ImmutableList.of(), ImmutableList.of());
        fixture.store.updateRun(run.withInvestigation(investigation, NOW).withEvidence("E1", context, NOW).withEvidence("E2", validation, NOW));

        assertThat(fixture.service.get(run.reviewId()).runs().getFirst().evidence()).containsExactly(context, validation);
    }

    @Test
    void startRefusesAPullRequestOutsideTheMonitoredRepository()
    {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, "other/repo", NUMBER))
                .isInstanceOf(ReviewInvalidRequestException.class)
                .hasMessageContaining("does not belong to this workspace");

        verify(fixture.tasks, never()).create(any(CreateAgentTaskCommand.class));
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void unavailableGitHubAccountPreventsTaskCreation()
    {
        Fixture fixture = new Fixture();
        when(fixture.credentials.resolveGitHubCredential()).thenThrow(new IllegalStateException("GitHub credential unavailable"));

        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GitHub credential unavailable");

        assertThat(fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER)).isEmpty();
        verify(fixture.tasks, never()).create(any(CreateAgentTaskCommand.class));
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void preparationFailureEndsTheRunAndPausesFollowUp()
    {
        Fixture fixture = new Fixture();
        GitHubCredential credential = fixture.credentials.resolveGitHubCredential();
        when(fixture.credentials.resolveGitHubCredential()).thenReturn(credential).thenThrow(new IllegalStateException("GitHub credential unavailable"));

        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GitHub credential unavailable");

        AgentReview review = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow();
        ReviewRun failed = fixture.store.listRuns(review.id()).getLast();
        assertThat(failed.state()).isEqualTo(ReviewRun.State.FAILED);
        assertThat(failed.outcome()).contains("Cannot start the review turn: GitHub credential unavailable");
        assertThat(failed.turnIds()).isEmpty();
        assertThat(review.followUpPaused()).isTrue();
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void reconcileSettlesAStartupThatStoppedBeforeTurnAcceptance()
    {
        Fixture fixture = new Fixture();
        GitHubCredential credential = fixture.credentials.resolveGitHubCredential();
        when(fixture.credentials.resolveGitHubCredential()).thenReturn(credential).thenThrow(new NoClassDefFoundError("Startup class unavailable"));
        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).isInstanceOf(NoClassDefFoundError.class);
        String reviewId = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow().id();
        assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.RUNNING);

        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.FAILED);
        assertThat(fixture.store.listRuns(reviewId).getLast().outcome()).contains("The review stopped before its turn was accepted");
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isTrue();
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void resumeRetriesAnInterruptedStartupOnTheSameReviewAndTask()
    {
        Fixture fixture = new Fixture();
        GitHubCredential credential = fixture.credentials.resolveGitHubCredential();
        when(fixture.credentials.resolveGitHubCredential())
                .thenReturn(credential)
                .thenThrow(new NoClassDefFoundError("Startup class unavailable"))
                .thenReturn(credential);
        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).isInstanceOf(NoClassDefFoundError.class);
        String reviewId = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow().id();

        AgentReviewProjection resumed = fixture.service.resume(reviewId);

        assertThat(resumed.review().id()).isEqualTo(reviewId);
        assertThat(resumed.review().taskId()).isEqualTo(TASK_ID.toString());
        assertThat(resumed.review().followUpPaused()).isFalse();
        assertThat(fixture.store.listRuns(reviewId)).extracting(ReviewRun::state)
                .containsExactly(ReviewRun.State.FAILED, ReviewRun.State.RUNNING);
        assertThat(resumed.byteQuayTurnIds()).containsExactly(TURN_ONE.toString());
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void reconcileReattachesAnAcceptedTurnEvenAfterTheFirstRepairWriteFails()
    {
        Fixture fixture = new Fixture();
        doThrow(new ReviewConflictException("Initial attachment failed"))
                .doThrow(new ReviewConflictException("Repair attachment failed"))
                .doCallRealMethod()
                .when(fixture.store).updateRun(any(ReviewRun.class));
        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).hasMessageContaining("Initial attachment failed");
        String reviewId = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow().id();
        ReviewRun unlinked = fixture.store.listRuns(reviewId).getLast();
        AgentTurn accepted = AgentTurn.accepted(TURN_ONE, TASK_ID, "review-run:" + unlinked.id(), SETTINGS, NOW);
        when(fixture.turns.findTurnByIdempotencyKey(TASK_ID, accepted.idempotencyKey())).thenReturn(Optional.of(accepted));
        when(fixture.turns.get(TURN_ONE)).thenReturn(accepted);

        assertThatThrownBy(() -> fixture.service.reconcile(reviewId)).hasMessageContaining("Repair attachment failed");
        assertThat(fixture.store.listRuns(reviewId).getLast().turnIds()).isEmpty();
        fixture.service.reconcile(reviewId);
        fixture.service.resume(reviewId);

        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(fixture.store.listRuns(reviewId).getLast().turnIds()).containsExactly(TURN_ONE.toString());
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void reconcileReattachesTheInvestigationBeforeStartingValidation()
    {
        Fixture fixture = new Fixture();
        doThrow(new ReviewConflictException("Initial attachment failed"))
                .doCallRealMethod()
                .when(fixture.store).updateRun(any(ReviewRun.class));
        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).hasMessageContaining("Initial attachment failed");
        String reviewId = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow().id();
        ReviewRun unlinked = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(unlinked.withSummary(summary(), NOW).withInvestigation(investigation(), NOW));
        AgentTurn completed = settledTurn(TURN_ONE, TurnState.COMPLETED, Optional.empty());
        when(fixture.turns.findTurnByIdempotencyKey(TASK_ID, "review-run:" + unlinked.id())).thenReturn(Optional.of(completed));
        when(fixture.turns.get(TURN_ONE)).thenReturn(completed);

        fixture.service.reconcile(reviewId);

        ReviewRun validating = fixture.store.listRuns(reviewId).getLast();
        assertThat(validating.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(validating.turnIds()).containsExactly(TURN_ONE.toString(), TURN_TWO.toString());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        completeValidation(fixture, validating);
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(unlinked.id());
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void reconcileCannotTreatAnInProgressStartupAsAnOrphan()
            throws Exception
    {
        Fixture fixture = new Fixture();
        GitHubCredential credential = fixture.credentials.resolveGitHubCredential();
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch continueStartup = new CountDownLatch(1);
        CountDownLatch reconciling = new CountDownLatch(1);
        when(fixture.credentials.resolveGitHubCredential()).thenReturn(credential).thenAnswer(invocation -> {
            preparing.countDown();
            assertThat(continueStartup.await(5, TimeUnit.SECONDS)).isTrue();
            return credential;
        });
        when(fixture.turns.get(TURN_ONE)).thenReturn(AgentTurn.accepted(TURN_ONE, TASK_ID, "review-run", SETTINGS, NOW));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var started = executor.submit(() -> fixture.service.start(WORKSPACE, REPO, NUMBER));
            try {
                assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();
                String reviewId = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow().id();
                var reconciled = executor.submit(() -> {
                    reconciling.countDown();
                    fixture.service.reconcile(reviewId);
                });
                assertThat(reconciling.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> reconciled.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                var localComment = executor.submit(() -> fixture.service.sendMessage(reviewId, 0, "Explain this line", "local-comment", false));
                assertThatThrownBy(() -> localComment.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                continueStartup.countDown();
                assertThat(started.get(5, TimeUnit.SECONDS).runs().getLast().state()).isEqualTo(ReviewRun.State.RUNNING);
                reconciled.get(5, TimeUnit.SECONDS);
                localComment.get(5, TimeUnit.SECONDS);
                ArgumentCaptor<StartAgentTurnCommand> startedTurns = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
                verify(fixture.turns, times(2)).start(startedTurns.capture());
                assertThat(startedTurns.getValue().developmentAutomation()).isEmpty();
                assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.RUNNING);
                verify(fixture.turns, never()).findTurnByIdempotencyKey(any(TaskId.class), anyString());
            }
            finally {
                continueStartup.countDown();
            }
        }
    }

    @Test
    void askStartsATurnWhenTheTaskIsIdle()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        String runId = fixture.store.listRuns(reviewId).getLast().id();

        ReviewMessage message = fixture.service.ask(reviewId, Optional.empty(), Optional.empty(), 1, "  Why is the parser reset here?  ");

        assertThat(message.body()).isEqualTo("Why is the parser reset here?");
        assertThat(message.author()).isEqualTo(ReviewMessage.Author.USER);
        assertThat(message.runId()).isEqualTo(runId);
        assertThat(message.turnId()).contains(TURN_TWO.toString());
        assertThat(message.queuedInputId()).isEmpty();
        assertThat(fixture.store.listMessages(reviewId)).containsExactly(message);
        AgentReviewProjection.MessageView view = fixture.service.get(reviewId).messages().getFirst();
        assertThat(view.message()).isEqualTo(message);
        assertThat(view.turnId()).contains(TURN_TWO.toString());
        assertThat(view.messageEventId()).isEmpty();

        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(2)).start(started.capture());
        assertThat(started.getValue().message()).contains("Question about summary").contains("Why is the parser reset here?");
        assertThat(started.getValue().idempotencyKey()).isEqualTo("review-ask:" + message.id());
        verify(fixture.turns, never()).enqueue(any(QueueAgentInputCommand.class));
    }

    @Test
    void cardQuestionKeepsItsRunAndSelectedContent()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun original = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(original.withSummary(summary(), NOW));
        String cardId = original.id() + ":purpose";
        ReviewMessage message = fixture.service.ask(reviewId, Optional.empty(), Optional.of(cardId), 1, "Why this approach?");
        assertThat(message.cardId()).contains(cardId);
        assertThat(message.runId()).isEqualTo(original.id());
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(2)).start(started.capture());
        assertThat(started.getValue().message()).contains("review_reply targeting " + cardId, "Selected card:\nFix the parser reset");
        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.empty(), Optional.of(original.id() + ":commit:unknown"), 1, "Explain"))
                .hasMessageContaining("unknown commit");
        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.empty(), Optional.of("another-run:purpose"), 1, "Explain"))
                .hasMessageContaining("does not belong");
    }

    @Test
    void askEnqueuesWhenTheTaskIsBusy()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
        when(fixture.turns.enqueue(any(QueueAgentInputCommand.class))).thenReturn(queuedInput());

        ReviewMessage message = fixture.service.ask(reviewId, Optional.empty(), Optional.empty(), 1, "Does this handle an empty patch?");

        assertThat(message.queuedInputId()).contains(QUEUED_INPUT.toString());
        assertThat(message.turnId()).isEmpty();

        ArgumentCaptor<QueueAgentInputCommand> queued = ArgumentCaptor.forClass(QueueAgentInputCommand.class);
        verify(fixture.turns, times(1)).enqueue(queued.capture());
        assertThat(queued.getValue().taskId()).isEqualTo(TASK_ID);
        assertThat(queued.getValue().idempotencyKey()).isEqualTo("review-ask:" + message.id());
        // Only the review request itself started a turn.
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void queuedQuestionProjectionKeepsItsMessageEventAcrossRetry()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
        when(fixture.turns.enqueue(any(QueueAgentInputCommand.class))).thenReturn(queuedInput());
        ReviewMessage message = fixture.service.ask(reviewId, Optional.empty(), Optional.empty(), 1, "Explain this limitation");
        QueuedAgentInput queued = queuedInput();
        when(fixture.queueStore.find(QUEUED_INPUT)).thenReturn(Optional.of(queued));
        AgentReviewProjection.MessageView waiting = fixture.service.get(reviewId).messages().getFirst();
        assertThat(waiting.turnId()).isEmpty();
        assertThat(waiting.messageEventId()).isEmpty();

        EventId eventId = EventId.valueOf("55555555-5555-4555-8555-555555555555");
        QueuedAgentInput starting = queued.starting(TURN_ONE, eventId, NOW);
        when(fixture.queueStore.find(QUEUED_INPUT)).thenReturn(Optional.of(starting));
        AgentReviewProjection.MessageView claimed = fixture.service.get(reviewId).messages().getFirst();
        assertThat(claimed.turnId()).contains(TURN_ONE.toString());
        assertThat(claimed.messageEventId()).contains(eventId.toString());

        QueuedAgentInput retried = starting.paused("Provider was not started", NOW).starting(TURN_TWO, eventId, NOW).started(NOW);
        when(fixture.queueStore.find(QUEUED_INPUT)).thenReturn(Optional.of(retried));
        AgentReviewProjection.MessageView delivered = fixture.service.get(reviewId).messages().getFirst();
        assertThat(delivered.turnId()).contains(TURN_TWO.toString());
        assertThat(delivered.messageEventId()).contains(eventId.toString());
        assertThat(delivered.message()).isEqualTo(message);
        assertThat(fixture.store.listMessages(reviewId)).containsExactly(message);
        when(fixture.queueStore.find(QUEUED_INPUT)).thenReturn(Optional.empty());
        AgentReviewProjection.MessageView retained = fixture.service.get(reviewId).messages().getFirst();
        assertThat(retained.message()).isEqualTo(message);
        assertThat(retained.turnId()).isEmpty();
        assertThat(retained.messageEventId()).isEmpty();
        verify(fixture.turns, never()).findQueuedByIdempotencyKey(any(), anyString());
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
        verify(fixture.turns, times(1)).enqueue(any(QueueAgentInputCommand.class));
    }

    @Test
    void askRefusesAStaleRevisionAndAWithdrawnFinding()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        String runId = fixture.store.listRuns(reviewId).getLast().id();
        ReviewFinding revised = fixture.store.insertFinding(
                finding("finding-1", reviewId, runId, 1, ReviewFinding.State.OPEN).revised("Reset the parser again", "Body", Optional.of("major"), ImmutableList.of(), NOW));
        ReviewFinding withdrawn = fixture.store.insertFinding(finding("finding-2", reviewId, runId, 2, ReviewFinding.State.WITHDRAWN));

        assertThat(revised.revision()).isEqualTo(2);
        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.of(revised.id()), Optional.empty(), 1, "Still true?"))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("revised to revision 2");
        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.of(withdrawn.id()), Optional.empty(), 1, "Still true?"))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("withdrew this finding");
        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.empty(), Optional.empty(), 4, "Still true?"))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("The summary was revised to revision 1");

        assertThat(fixture.store.listMessages(reviewId)).isEmpty();
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void resolveAndReopenAreVersionChecked()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        String runId = fixture.store.listRuns(reviewId).getLast().id();
        ReviewFinding stored = fixture.store.insertFinding(finding("finding-1", reviewId, runId, 1, ReviewFinding.State.OPEN));

        ReviewFinding resolved = fixture.service.resolveFinding(reviewId, stored.id(), stored.version(), "  Fixed in a follow-up  ");
        assertThat(resolved.state()).isEqualTo(ReviewFinding.State.RESOLVED);
        assertThat(resolved.stateReason()).contains("Fixed in a follow-up");
        assertThat(resolved.version()).isEqualTo(stored.version() + 1);

        ReviewFinding reopened = fixture.service.reopenFinding(reviewId, stored.id(), resolved.version());
        assertThat(reopened.state()).isEqualTo(ReviewFinding.State.OPEN);
        assertThat(reopened.stateReason()).isEmpty();

        assertThatThrownBy(() -> fixture.service.resolveFinding(reviewId, stored.id(), stored.version(), "again"))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("reload before resolving");
        assertThatThrownBy(() -> fixture.service.reopenFinding(reviewId, stored.id(), reopened.version()))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("Only a locally resolved finding can be reopened");
        assertThat(fixture.store.findFinding(stored.id()).orElseThrow().state()).isEqualTo(ReviewFinding.State.OPEN);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reconcileAcceptsACompletedRunWithASummaryAndCoverage(boolean paused)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewRun validating = beginValidation(fixture, run);
        completeValidation(fixture, validating);
        if (paused) {
            fixture.service.stop(reviewId);
        }

        fixture.service.reconcile(reviewId);

        ReviewRun accepted = fixture.store.listRuns(reviewId).getLast();
        assertThat(accepted.state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(accepted.endedAt()).contains(NOW);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(run.id());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isEqualTo(paused);
        // The observed head is already reviewed, so nothing new is scheduled.
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void pausedInvestigationWaitsForResumeBeforeStartingValidation()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(run.withSummary(summary(), NOW).withInvestigation(investigation(), NOW));
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.COMPLETED, Optional.empty()));
        fixture.service.stop(reviewId);

        fixture.service.reconcile(reviewId);

        ReviewRun paused = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(paused.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(paused.validationStarted()).isFalse();
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));

        fixture.service.resume(reviewId);

        ReviewRun validating = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(validating.validationStarted()).isTrue();
        assertThat(validating.turnIds()).containsExactly(TURN_ONE.toString(), TURN_TWO.toString());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void investigationStartsOneValidationTurnOnlyAfterItsWorktreeClaimIsReleased()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(run.withSummary(summary(), NOW).withInvestigation(investigation(), NOW));
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.COMPLETED, Optional.empty()));
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.recovery("cleanup", NOW), NOW));

        fixture.service.reconcile(reviewId);
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
        assertThat(fixture.store.findRun(run.id()).orElseThrow().validationStarted()).isFalse();

        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask());
        fixture.service.reconcile(reviewId);
        fixture.service.reconcile(reviewId);

        ReviewRun validating = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(validating.validationStarted()).isTrue();
        assertThat(validating.investigation()).contains(investigation());
        assertThat(validating.turnIds()).containsExactly(TURN_ONE.toString(), TURN_TWO.toString());
        assertThat(validating.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(2)).start(started.capture());
        assertThat(started.getValue().taskId()).isEqualTo(TASK_ID);
        assertThat(started.getValue().idempotencyKey()).isEqualTo("review-validate:" + run.id());
        assertThat(started.getValue().message()).isEqualTo(ReviewPrompts.validationRequest(validating));
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
    }

    @Test
    void missingInvestigationUsesTheSharedRepairBeforeValidation()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(run.withSummary(summary(), NOW));
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.COMPLETED, Optional.empty()));

        fixture.service.reconcile(reviewId);

        ReviewRun repairing = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(repairing.completionRepairUsed()).isTrue();
        assertThat(repairing.validationStarted()).isFalse();
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(2)).start(started.capture());
        assertThat(started.getValue().message()).isEqualTo(ReviewPrompts.investigationRepair(repairing));

        fixture.store.updateRun(repairing.withInvestigation(investigation(), NOW));
        when(fixture.turns.get(TURN_TWO)).thenReturn(settledTurn(TURN_TWO, TurnState.COMPLETED, Optional.empty()));
        fixture.service.reconcile(reviewId);

        ReviewRun validating = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(validating.validationStarted()).isTrue();
        assertThat(validating.completionRepairUsed()).isTrue();
        assertThat(validating.turnIds()).hasSize(3);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void validationReattachesAfterDispatchWithoutStartingAnotherTurn()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(run.withSummary(summary(), NOW).withInvestigation(investigation(), NOW));
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.COMPLETED, Optional.empty()));
        doThrow(new ReviewConflictException("Validation association failed")).doCallRealMethod()
                .when(fixture.store).updateRun(argThat(candidate -> candidate.turnIds().size() == 2));

        assertThatThrownBy(() -> fixture.service.reconcile(reviewId)).hasMessageContaining("Validation association failed");
        assertThat(fixture.store.findRun(run.id()).orElseThrow().validationStarted()).isTrue();
        assertThat(fixture.store.findRun(run.id()).orElseThrow().turnIds()).containsExactly(TURN_ONE.toString());

        fixture.service.reconcile(reviewId);
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.findRun(run.id()).orElseThrow().turnIds()).containsExactly(TURN_ONE.toString(), TURN_TWO.toString());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void aPersistedValidationStartMustDispatchBeforeAcceptingCompleteOutput()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(run.withSummary(summary(), NOW).withInvestigation(investigation(), NOW).withValidationStarted(NOW).withCoverage(coverage(), NOW));
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.COMPLETED, Optional.empty()));

        fixture.service.reconcile(reviewId);

        ReviewRun validating = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(validating.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(validating.turnIds()).containsExactly(TURN_ONE.toString(), TURN_TWO.toString());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(2)).start(started.capture());
        assertThat(started.getValue().idempotencyKey()).isEqualTo("review-validate:" + run.id());

        completeValidation(fixture, validating);
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.findRun(run.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
    }

    @Test
    void repairReattachesBeforeACompletedValidationCanExhaustItsBudget()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());
        when(fixture.turns.get(TURN_TWO)).thenReturn(settledTurn(TURN_TWO, TurnState.COMPLETED, Optional.empty()));
        doThrow(new ReviewConflictException("Repair association failed")).doCallRealMethod()
                .when(fixture.store).updateRun(argThat(candidate -> candidate.turnIds().size() == 3));

        assertThatThrownBy(() -> fixture.service.reconcile(reviewId)).hasMessageContaining("Repair association failed");
        fixture.service.reconcile(reviewId);

        ReviewRun repairing = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(repairing.turnIds()).hasSize(3);
        assertThat(repairing.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(repairing.completionRepairUsed()).isTrue();
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void partialCoveragePreservesTheLastSuccessfulIncrementalBaselineWithoutRepair()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun baseline = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());
        completeValidation(fixture, baseline);
        fixture.service.reconcile(reviewId);
        pushHead(fixture, NEW_HEAD);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(ok(""));
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false));
        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());
        fixture.store.updateRun(validating.withCoverage(new ReviewCoverage(false, ImmutableList.of("The parser integration path was not reviewed"), NOW), NOW));
        TurnId validation = TurnId.valueOf(validating.turnIds().getLast());
        when(fixture.turns.get(validation)).thenReturn(settledTurn(validation, TurnState.COMPLETED, Optional.empty()));

        fixture.service.reconcile(reviewId);

        ReviewRun incomplete = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(incomplete.state()).isEqualTo(ReviewRun.State.INCOMPLETE);
        assertThat(incomplete.completionRepairUsed()).isFalse();
        assertThat(incomplete.summary()).isPresent();
        assertThat(incomplete.investigation()).isPresent();
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(baseline.id());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isTrue();
        verify(fixture.turns, times(4)).start(any(StartAgentTurnCommand.class));

        fixture.service.resume(reviewId);

        ReviewRun retry = fixture.store.listRuns(reviewId).getLast();
        assertThat(retry.scope()).isEqualTo(ReviewRun.Scope.INCREMENTAL);
        assertThat(retry.priorHeadSha()).contains(HEAD);
        assertThat(retry.headSha()).isEqualTo(NEW_HEAD);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExplicitInvestigationOmissionStopsBeforeValidationOrRepair(boolean paused)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(run.withSummary(summary(), NOW)
                .withCoverage(new ReviewCoverage(false, ImmutableList.of("The required source is unavailable"), NOW), NOW));
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.COMPLETED, Optional.empty()));
        if (paused) {
            fixture.service.stop(reviewId);
        }

        fixture.service.reconcile(reviewId);

        ReviewRun incomplete = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(incomplete.state()).isEqualTo(ReviewRun.State.INCOMPLETE);
        assertThat(incomplete.validationStarted()).isFalse();
        assertThat(incomplete.completionRepairUsed()).isFalse();
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isTrue();
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void incompleteCommitExplanationsCannotCompleteValidation()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());
        ReviewSummary missingCommit = new ReviewSummary(2, "Fix parser state", ImmutableList.of(), "", Optional.empty(), ImmutableList.of());
        fixture.store.updateRun(validating.withSummary(missingCommit, NOW).withCoverage(coverage(), NOW));
        when(fixture.turns.get(TURN_TWO)).thenReturn(settledTurn(TURN_TWO, TurnState.COMPLETED, Optional.empty()));

        fixture.service.reconcile(reviewId);

        ReviewRun repairing = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(repairing.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(repairing.completionRepairUsed()).isTrue();
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
    }

    @Test
    void completedReviewWaitsForItsRecoveryClaimBeforeBecomingAnAcceptedBaseline()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewRun validating = beginValidation(fixture, run);
        completeValidation(fixture, validating);
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.recovery("pending-cleanup", NOW), NOW));

        fixture.service.reconcile(reviewId);
        fixture.service.resume(reviewId);

        assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask());
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(run.id());
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void recoveryPreservesTheReviewAndRequiresAnAcceptedBaselineBeforeIncrementalReview()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun original = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(original.withSummary(summary(), NOW).withCoverage(coverage(), NOW));
        ReviewFinding originalFinding = fixture.store.insertFinding(finding("finding-1", reviewId, original.id(), 1, ReviewFinding.State.OPEN));
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.recovery("old-launch", NOW), NOW));
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.INTERRUPTED, Optional.of("Cleanup unverified")));
        fixture.service.reconcile(reviewId);

        fixture.service.resume(reviewId);
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();

        // Runtime recovery releases the claim; it does not turn the interrupted review into an accepted baseline.
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask());
        AgentReviewProjection resumed = fixture.service.resume(reviewId);
        ReviewRun baseline = fixture.store.listRuns(reviewId).getLast();
        assertThat(resumed.review().taskId()).isEqualTo(TASK_ID.toString());
        assertThat(baseline.scope()).isEqualTo(ReviewRun.Scope.FULL);
        assertThat(baseline.priorHeadSha()).isEmpty();
        assertThat(fixture.store.listRuns(reviewId).getFirst().state()).isEqualTo(ReviewRun.State.FAILED);
        assertThat(fixture.store.listRuns(reviewId).getFirst().summary()).contains(summary());
        assertThat(fixture.store.findFinding(originalFinding.id())).contains(originalFinding);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();

        ReviewRun validating = beginValidation(fixture, baseline);
        completeValidation(fixture, validating);
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(baseline.id());

        when(fixture.pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(NEW_HEAD));
        when(fixture.git.run(REPOSITORY, "rev-parse", "--verify", "refs/bytequay/review/" + NUMBER + "^{commit}")).thenReturn(ok(NEW_HEAD));
        when(fixture.git.run(REPOSITORY, "merge-base", NEW_HEAD, BASE_SHA)).thenReturn(ok(MERGE_BASE));
        when(fixture.git.run(REPOSITORY, "log", "--reverse", "--format=%H%x1f%an%x1f%s", MERGE_BASE + ".." + NEW_HEAD))
                .thenReturn(ok(NEW_HEAD + FIELD + "Alice" + FIELD + "Address review\n"));
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(ok(""));
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false));
        TurnId updateTurn = TurnId.valueOf("55555555-5555-4555-8555-555555555555");
        when(fixture.turns.start(any(StartAgentTurnCommand.class))).thenReturn(AgentTurn.accepted(updateTurn, TASK_ID, "review-update", SETTINGS, NOW));

        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);

        ReviewRun incremental = fixture.store.listRuns(reviewId).getLast();
        assertThat(incremental.scope()).isEqualTo(ReviewRun.Scope.INCREMENTAL);
        assertThat(incremental.priorHeadSha()).contains(HEAD);
        assertThat(incremental.headSha()).isEqualTo(NEW_HEAD);
        assertThat(fixture.store.listFindings(reviewId)).extracting(ReviewFinding::id).containsExactly(originalFinding.id());
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        verify(fixture.completions, never()).complete(any(CompleteAgentTaskCommand.class));
    }

    @Test
    void reconcileSpendsTheRepairBudgetOnAMissingCoverageRecord()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewRun reported = beginValidation(fixture, run);
        when(fixture.turns.get(TURN_TWO)).thenReturn(settledTurn(TURN_TWO, TurnState.COMPLETED, Optional.empty()));

        fixture.service.reconcile(reviewId);

        ReviewRun repairing = fixture.store.listRuns(reviewId).getLast();
        assertThat(repairing.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(repairing.completionRepairUsed()).isTrue();
        assertThat(repairing.turnIds()).hasSize(3).startsWith(TURN_ONE.toString(), TURN_TWO.toString());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();

        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(3)).start(started.capture());
        assertThat(started.getValue().message()).isEqualTo(ReviewPrompts.completionRepair(reported));
        assertThat(started.getValue().idempotencyKey()).isEqualTo("review-repair:" + run.id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reconcileMarksARunIncompleteOnceTheRepairBudgetIsSpent(boolean paused)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewRun validating = beginValidation(fixture, run);
        AgentTurn repair = fixture.turns.start(new StartAgentTurnCommand(TASK_ID, "Repair coverage", ImmutableList.of(), "review-repair:" + run.id()));
        fixture.store.updateRun(validating.withCompletionRepairUsed(NOW).withTurn(repair.id().toString(), NOW));
        when(fixture.turns.get(repair.id())).thenReturn(settledTurn(repair.id(), TurnState.COMPLETED, Optional.empty()));
        if (paused) {
            fixture.service.stop(reviewId);
            fixture.service.reconcile(reviewId);

            assertThat(fixture.store.findRun(run.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.RUNNING);
            verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
            fixture.store.updateReview(fixture.store.findReview(reviewId).orElseThrow().withFollowUpPaused(false, NOW));
        }

        fixture.service.reconcile(reviewId);

        ReviewRun incomplete = fixture.store.listRuns(reviewId).getLast();
        assertThat(incomplete.state()).isEqualTo(ReviewRun.State.INCOMPLETE);
        assertThat(incomplete.outcome()).contains("The run ended without a coverage record");
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void reconcileStopsTheRunAndPausesFollowUpForACancelledTurn()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.CANCELLED, Optional.of("Stopped")));

        fixture.service.reconcile(reviewId);

        ReviewRun stopped = fixture.store.listRuns(reviewId).getLast();
        assertThat(stopped.state()).isEqualTo(ReviewRun.State.STOPPED);
        assertThat(stopped.outcome()).contains("Stopped by the user");
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isTrue();
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void stopCancelsTheActiveTurnAndPausesFollowUp()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
        when(fixture.turns.get(TURN_ONE)).thenReturn(AgentTurn.accepted(TURN_ONE, TASK_ID, "review-run", SETTINGS, NOW));

        AgentReviewProjection projection = fixture.service.stop(reviewId);

        verify(fixture.turns, times(1)).cancel(TURN_ONE);
        assertThat(projection.review().followUpPaused()).isTrue();
        assertThat(projection.activeTurn().orElseThrow().id()).isEqualTo(TURN_ONE.toString());
        assertThat(projection.task().mutationClaim()).contains(WorktreeMutationClaim.Kind.TURN.name());
    }

    @Test
    void projectionCarriesTheStoredPullRequest()
    {
        Fixture fixture = new Fixture();
        PullRequest stored = new PullRequest(
                1L, REPO, NUMBER, "Avoid duplicate field index lookup", "alice", "url", NOW, NOW, PullRequest.Origin.AUTHORED,
                ImmutableList.of(), null, false, null, null, null, ImmutableList.of(), null,
                0, 0, 0, null, "merged", null, null, null, null, null, null, null, null, null, null);
        when(fixture.pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.of(stored));

        assertThat(fixture.service.start(WORKSPACE, REPO, NUMBER).pullRequest()).contains(stored);
    }

    @Test
    void endClosesTheReviewBeforeAskingForWorktreeCleanup()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();

        AgentReviewProjection projection = fixture.service.end(reviewId);

        assertThat(projection.review().state()).isEqualTo(AgentReview.State.CLOSED);
        assertThat(projection.review().closedAt()).contains(NOW);
        ReviewRun stopped = fixture.store.listRuns(reviewId).getLast();
        assertThat(stopped.state()).isEqualTo(ReviewRun.State.STOPPED);
        assertThat(stopped.outcome()).contains("Review ended");

        InOrder order = inOrder(fixture.observer, fixture.completions, fixture.store);
        order.verify(fixture.observer).unwatch(reviewId);
        order.verify(fixture.completions).complete(new CompleteAgentTaskCommand(TASK_ID, Optional.of("Agent review ended by the user")));
        order.verify(fixture.store).updateReview(argThat(review -> review.state() == AgentReview.State.CLOSED));
        order.verify(fixture.completions).cleanupWorktree(TASK_ID);
    }

    @Test
    void observeHeadRecordsTheNewerHeadOnlyOnTheMatchingReview()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        AgentReview other = fixture.store.insertReview(new AgentReview(
                "other-review",
                WORKSPACE,
                REPO,
                9,
                OptionalLong.empty(),
                TaskId.random().toString(),
                AgentReview.State.OPEN,
                false,
                Optional.empty(),
                Optional.of(HEAD),
                0,
                NOW,
                NOW,
                Optional.empty()));
        when(fixture.turns.get(TURN_ONE)).thenReturn(AgentTurn.accepted(TURN_ONE, TASK_ID, "review-run", SETTINGS, NOW));

        fixture.service.observeHead("Owner/Repo", NUMBER, NEW_HEAD);

        assertThat(fixture.store.findReview(reviewId).orElseThrow().observedHeadSha()).contains(NEW_HEAD);
        assertThat(fixture.store.findReview(other.id()).orElseThrow().observedHeadSha()).contains(HEAD);
        // The run is still running, so the new head is only recorded, never scheduled.
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void rewrittenHistoryPausesFollowUpUntilUpdateRealignsTheWorktree()
    {
        Fixture fixture = rewrittenHistory();
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, false, false));

        assertThatThrownBy(() -> fixture.service.observeHead(REPO, NUMBER, NEW_HEAD))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("history was rewritten");
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isTrue();

        // A later refresh leaves the paused review alone instead of fetching and refusing again.
        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);
        verify(fixture.worktrees, times(1)).inspect(taskWorktree());
        verify(fixture.git, never()).run(eq(taskWorktree().canonicalWorktree()), anyList());

        // Update is the user's realignment decision: the clean worktree moves and a full review runs.
        // Ignored build output from the agent's test runs marks the snapshot dirty without blocking the move.
        when(fixture.worktrees.inspect(taskWorktree()))
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false), new WorktreeSnapshot(taskWorktree(), NEW_HEAD, true, false));
        when(fixture.git.run(taskWorktree().canonicalWorktree(), realign(NEW_HEAD))).thenReturn(ok(""));

        fixture.service.update(reviewId);

        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        ReviewRun rerun = fixture.store.listRuns(reviewId).getLast();
        assertThat(rerun.headSha()).isEqualTo(NEW_HEAD);
        assertThat(rerun.scope()).isEqualTo(ReviewRun.Scope.FULL);
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void retryAtANewerHeadMovesTheWorktreeSoALaterRewriteCanRealignFromThere()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.FAILED, Optional.of("Provider failed")));
        fixture.service.reconcile(reviewId);
        Path worktree = taskWorktree().canonicalWorktree();

        // GitHub moved to MID before the refresh reached the review, so the observed head is still HEAD.
        String mid = "d4e5f6a7b80123456789abcdef0123456789abcd";
        pushHead(fixture, mid);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, mid)).thenReturn(ok(""));
        when(fixture.worktrees.inspect(taskWorktree()))
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, false, false), new WorktreeSnapshot(taskWorktree(), mid, false, false));
        when(fixture.git.run(worktree, fastForward(mid))).thenReturn(ok(""));

        fixture.service.resume(reviewId);

        assertThat(fixture.store.listRuns(reviewId).getLast().headSha()).isEqualTo(mid);
        verify(fixture.git).run(worktree, fastForward(mid));

        // The worktree now sits on MID, not the start commit; the rewrite is realigned from there.
        when(fixture.turns.get(TURN_TWO)).thenReturn(settledTurn(TURN_TWO, TurnState.FAILED, Optional.of("Provider failed")));
        fixture.service.reconcile(reviewId);
        pushHead(fixture, NEW_HEAD);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", mid, NEW_HEAD)).thenReturn(new GitCommandRunner.Result(1, "", false));
        when(fixture.worktrees.inspect(taskWorktree()))
                .thenReturn(new WorktreeSnapshot(taskWorktree(), mid, false, false), new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false));
        when(fixture.git.run(worktree, realign(NEW_HEAD))).thenReturn(ok(""));

        fixture.service.update(reviewId);

        assertThat(fixture.store.listRuns(reviewId).getLast().headSha()).isEqualTo(NEW_HEAD);
        verify(fixture.git).run(worktree, realign(NEW_HEAD));
    }

    @Test
    void updateRefusesToRealignADirtyWorktreeOrOneWithLocalCommits()
    {
        Fixture fixture = rewrittenHistory();
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        String localCommit = "c3d4e5f6a70123456789abcdef0123456789abcd";
        when(fixture.worktrees.inspect(taskWorktree()))
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false), new WorktreeSnapshot(taskWorktree(), localCommit, false, false));
        when(fixture.git.run(taskWorktree().canonicalWorktree(), "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none"))
                .thenReturn(ok("?? repro.txt\0"), ok(""));
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", localCommit, NEW_HEAD)).thenReturn(new GitCommandRunner.Result(1, "", false));

        assertThatThrownBy(() -> fixture.service.update(reviewId)).hasMessageContaining("uncommitted experiments");
        assertThatThrownBy(() -> fixture.service.update(reviewId)).hasMessageContaining("local commits");

        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isTrue();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        verify(fixture.git, never()).run(eq(taskWorktree().canonicalWorktree()), anyList());
    }

    /** An accepted review of HEAD whose pull request was force-pushed to NEW_HEAD. */
    private static Fixture rewrittenHistory()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewRun validating = beginValidation(fixture, run);
        completeValidation(fixture, validating);
        fixture.service.reconcile(reviewId);
        pushHead(fixture, NEW_HEAD);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(new GitCommandRunner.Result(1, "", false));
        return fixture;
    }

    private static List<String> fastForward(String head)
    {
        return ImmutableList.of("-c", "core.hooksPath=/dev/null", "merge", "--ff-only", "--no-overwrite-ignore", head);
    }

    private static List<String> realign(String head)
    {
        return ImmutableList.of("-c", "core.hooksPath=/dev/null", "checkout", "--no-overwrite-ignore", "--no-recurse-submodules", "-B", taskWorktree().generatedBranch(), head);
    }

    /** GitHub and the fetched pull request ref both report the given head. */
    private static void pushHead(Fixture fixture, String head)
    {
        when(fixture.pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(head));
        when(fixture.git.run(REPOSITORY, "rev-parse", "--verify", "refs/bytequay/review/" + NUMBER + "^{commit}")).thenReturn(ok(head));
        when(fixture.git.run(REPOSITORY, "merge-base", head, BASE_SHA)).thenReturn(ok(MERGE_BASE));
        when(fixture.git.run(REPOSITORY, "log", "--reverse", "--format=%H%x1f%an%x1f%s", MERGE_BASE + ".." + head))
                .thenReturn(ok(head + FIELD + "Alice" + FIELD + "Fix the parser\n"));
    }

    private static AgentTask idleTask()
    {
        return AgentTask.created(TASK_ID, true, "Review owner/repo#7", TitleSource.USER, LANE, taskWorktree(), DEFAULTS, NOW);
    }

    private static AgentTask busyTask()
    {
        return idleTask().claimWorktreeMutation(WorktreeMutationClaim.turn(TURN_ONE, NOW), NOW);
    }

    private static TaskWorktree taskWorktree()
    {
        return new TaskWorktree(
                TASK_ID,
                WORKSPACE,
                new RepositoryIdentity("owner", "repo"),
                Path.of("/tmp/bytequay/worktrees").resolve(TASK_ID.toString()),
                "bytequay/task/" + TASK_ID,
                HEAD);
    }

    private static AgentTurn settledTurn(TurnId turnId, TurnState state, Optional<String> terminalReason)
    {
        return new AgentTurn(
                turnId,
                TASK_ID,
                "review-run",
                state,
                0,
                NOW,
                Optional.of(NOW),
                Optional.of(NOW),
                Optional.empty(),
                Optional.empty(),
                false,
                Optional.empty(),
                Optional.of(SETTINGS),
                terminalReason,
                AgentMetrics.empty());
    }

    private static QueuedAgentInput queuedInput()
    {
        return QueuedAgentInput.queued(QUEUED_INPUT, TASK_ID, 1, "review-ask", new BlobRef("queued.txt", "text/plain", 4), "Queued question", SETTINGS, NOW);
    }

    private static ReviewSummary summary()
    {
        return new ReviewSummary(1, "Fix the parser reset",
                ImmutableList.of(new ReviewSummary.CommitExplanation(HEAD, "Reset state before parsing each file")), "", Optional.empty(), ImmutableList.of());
    }

    private static ReviewInvestigation investigation()
    {
        return new ReviewInvestigation("Each parser invocation owns fresh state", ImmutableList.of("src/App.java"), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    private static ReviewRun beginValidation(Fixture fixture, ReviewRun run)
    {
        ReviewSummary summary = new ReviewSummary(1, "Fix the parser reset", run.commits().stream()
                .map(commit -> new ReviewSummary.CommitExplanation(commit.sha(), "Reset state before parsing each file")).collect(toImmutableList()),
                "", Optional.empty(), ImmutableList.of());
        fixture.store.updateRun(run.withSummary(summary, NOW).withInvestigation(investigation(), NOW));
        when(fixture.turns.get(TurnId.valueOf(run.turnIds().getLast())))
                .thenReturn(settledTurn(TurnId.valueOf(run.turnIds().getLast()), TurnState.COMPLETED, Optional.empty()));
        fixture.service.reconcile(run.reviewId());
        return fixture.store.findRun(run.id()).orElseThrow();
    }

    private static void completeValidation(Fixture fixture, ReviewRun run)
    {
        fixture.store.updateRun(run.withCoverage(coverage(), NOW));
        TurnId validation = TurnId.valueOf(run.turnIds().getLast());
        when(fixture.turns.get(validation)).thenReturn(settledTurn(validation, TurnState.COMPLETED, Optional.empty()));
    }

    private static ReviewCoverage coverage()
    {
        return new ReviewCoverage(true, ImmutableList.of(), NOW);
    }

    private static ReviewFinding finding(String findingId, String reviewId, String runId, int ordinal, ReviewFinding.State state)
    {
        return new ReviewFinding(
                findingId,
                reviewId,
                runId,
                ordinal,
                "Reset the parser",
                "The parser keeps state across files",
                Optional.of("major"),
                new ReviewAnchor("src/App.java", "RIGHT", 3, 3, ImmutableList.of("added")),
                ImmutableList.of(),
                1,
                state,
                Optional.empty(),
                HEAD,
                false,
                0,
                NOW,
                NOW);
    }

    private static PullRequestDetail detail()
    {
        return new PullRequestDetail(
                12L,
                REPO,
                NUMBER,
                "Fix the parser",
                "https://github.test/owner/repo/pull/7",
                "open",
                false,
                true,
                "clean",
                "alice",
                null,
                NOW,
                NOW,
                null,
                "main",
                "feature",
                "Body",
                ImmutableList.of(),
                ImmutableList.of(),
                null,
                ImmutableList.of(),
                1,
                0,
                1,
                0,
                PullRequestDetail.CiStatus.PASSING,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                true);
    }

    private static GitCommandRunner.Result ok(String output)
    {
        return new GitCommandRunner.Result(0, output, false);
    }

    private static final class Fixture
    {
        private final AgentReviewStore store = spy(new InMemoryReviewStore());
        private final ReviewDraftStore drafts = mock(ReviewDraftStore.class);
        private final AgentTaskService tasks = mock(AgentTaskService.class);
        private final AgentTurnService turns = mock(AgentTurnService.class);
        private final QueuedInputStore queueStore = mock(QueuedInputStore.class);
        private final AgentCompletionService completions = mock(AgentCompletionService.class);
        private final AgentApprovalService approvals = mock(AgentApprovalService.class);
        private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        private final AgentDriver driver = mock(AgentDriver.class);
        private final ModelSettingsService models = mock(ModelSettingsService.class);
        private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
        private final WorktreeService worktrees = mock(WorktreeService.class);
        private final GitCommandRunner git = mock(GitCommandRunner.class);
        private final PullRequestService pullRequests = mock(PullRequestService.class);
        private final PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        private final CredentialService credentials = mock(CredentialService.class);
        private final ReviewTaskObserver observer = mock(ReviewTaskObserver.class);
        private final Map<String, AgentTurn> acceptedTurns = new LinkedHashMap<>();
        private final DefaultAgentReviewService service;

        private Fixture()
        {
            service = new DefaultAgentReviewService(
                    store,
                    drafts,
                    tasks,
                    turns,
                    queueStore,
                    completions,
                    approvals,
                    drivers,
                    models,
                    workspaces,
                    worktrees,
                    git,
                    pullRequests,
                    refresh,
                    credentials,
                    observer,
                    CLOCK);
            when(drivers.require(LANE)).thenReturn(driver);
            when(driver.capabilities()).thenReturn(AgentCapabilities.none());
            when(driver.label()).thenReturn("Codex");
            when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.of("0.151.0"), "ready", AgentCapabilities.none()));
            when(models.agentModel(eq(AgentModelType.REVIEW), any())).thenReturn(new AgentModelSetting(
                    AgentModelType.REVIEW, true, "codex", "Codex", "gpt-5-codex", "GPT-5 Codex", Optional.of("medium")));
            when(workspaces.requireReadyRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(
                    WORKSPACE,
                    REPOSITORY,
                    1,
                    RepoRef.of("owner", "repo"),
                    OptionalLong.empty(),
                    RepoRef.of("owner", "repo"),
                    "origin",
                    "origin",
                    "main",
                    ImmutableList.of()));
            when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("token", new UserProfile("octocat", "Octo Cat", "", "", 0, 0, 0, "", "", "", "")));
            when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
            when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(HEAD));
            when(pullRequests.getPullRequestDiffFiles(REPO, NUMBER, null, null))
                    .thenReturn(ImmutableList.of(new DiffFile("src/App.java", "modified", 1, 0, "@@ -1,2 +1,3 @@\n context\n+added\n")));
            when(tasks.create(any(CreateAgentTaskCommand.class))).thenReturn(idleTask());
            when(tasks.get(TASK_ID)).thenReturn(idleTask());
            when(turns.start(any(StartAgentTurnCommand.class)))
                    .thenAnswer(invocation -> {
                        StartAgentTurnCommand command = invocation.getArgument(0);
                        if (command == null) {
                            return null;
                        }
                        TurnId id = acceptedTurns.isEmpty() ? TURN_ONE : acceptedTurns.size() == 1 ? TURN_TWO : TurnId.random();
                        AgentTurn accepted = AgentTurn.accepted(id, TASK_ID, command.idempotencyKey(), SETTINGS, NOW);
                        acceptedTurns.put(command.idempotencyKey(), accepted);
                        return accepted;
                    });
            when(turns.findTurnByIdempotencyKey(eq(TASK_ID), anyString()))
                    .thenAnswer(invocation -> Optional.ofNullable(acceptedTurns.get(invocation.getArgument(1))));
            when(turns.get(any(TurnId.class))).thenAnswer(invocation -> acceptedTurns.values().stream()
                    .filter(turn -> turn.id().equals(invocation.getArgument(0))).findFirst().orElse(null));
            stubGit();
        }

        private void stubGit()
        {
            when(git.run(eq(REPOSITORY), anyList())).thenReturn(ok(""));
            when(git.run(taskWorktree().canonicalWorktree(), "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none")).thenReturn(ok(""));
            when(git.run(REPOSITORY, "rev-parse", "--verify", "refs/bytequay/review/" + NUMBER + "^{commit}")).thenReturn(ok(HEAD));
            when(git.run(REPOSITORY, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}")).thenReturn(ok(BASE_SHA));
            when(git.run(REPOSITORY, "merge-base", HEAD, BASE_SHA)).thenReturn(ok(MERGE_BASE));
            when(git.run(REPOSITORY, "log", "--reverse", "--format=%H%x1f%an%x1f%s", MERGE_BASE + ".." + HEAD))
                    .thenReturn(ok(HEAD + FIELD + "Alice" + FIELD + "Fix the parser\n"));
        }
    }

    /** Durable review storage with the optimistic-version contract the SQLite store implements. */
    static class InMemoryReviewStore
            implements AgentReviewStore
    {
        private final Map<String, AgentReview> reviews = new LinkedHashMap<>();
        private final Map<String, ReviewRun> runs = new LinkedHashMap<>();
        private final Map<String, ReviewFinding> findings = new LinkedHashMap<>();
        private final Map<String, ReviewMessage> messages = new LinkedHashMap<>();

        @Override
        public AgentReview insertReview(AgentReview review)
        {
            reviews.put(review.id(), review);
            return review;
        }

        @Override
        public AgentReview updateReview(AgentReview review)
        {
            AgentReview stored = reviews.get(review.id());
            checkUpdate(stored == null ? OptionalLong.empty() : OptionalLong.of(stored.version()), review.version(), "Review", review.id());
            AgentReview next = review.withVersion(review.version() + 1);
            reviews.put(next.id(), next);
            return next;
        }

        @Override
        public Optional<AgentReview> findReview(String reviewId)
        {
            return Optional.ofNullable(reviews.get(reviewId));
        }

        @Override
        public Optional<AgentReview> findOpenReview(String workspaceId, String repo, int number)
        {
            return reviews.values().stream()
                    .filter(review -> review.state() == AgentReview.State.OPEN)
                    .filter(review -> review.workspaceId().equals(workspaceId) && review.repo().equals(repo) && review.number() == number)
                    .findFirst();
        }

        @Override
        public Optional<AgentReview> findReviewByTask(String taskId)
        {
            return reviews.values().stream().filter(review -> review.taskId().equals(taskId)).findFirst();
        }

        @Override
        public List<AgentReview> listOpenReviews()
        {
            return reviews.values().stream().filter(review -> review.state() == AgentReview.State.OPEN).collect(toImmutableList());
        }

        @Override
        public List<AgentReview> listReviews(String workspaceId)
        {
            return reviews.values().stream()
                    .filter(review -> review.workspaceId().equals(workspaceId))
                    .sorted(Comparator.comparing(AgentReview::updatedAt).reversed().thenComparing(AgentReview::id))
                    .collect(toImmutableList());
        }

        @Override
        public ReviewRun insertRun(ReviewRun run)
        {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public ReviewRun updateRun(ReviewRun run)
        {
            ReviewRun stored = runs.get(run.id());
            checkUpdate(stored == null ? OptionalLong.empty() : OptionalLong.of(stored.version()), run.version(), "Review run", run.id());
            ReviewRun next = run.withVersion(run.version() + 1);
            runs.put(next.id(), next);
            return next;
        }

        @Override
        public void acceptRun(AgentReview review, ReviewRun run)
        {
            updateRun(run);
            updateReview(review);
        }

        @Override
        public ReviewFinding saveFinding(ReviewFinding finding, boolean insert, ReviewRun run)
        {
            ReviewFinding saved = insert ? insertFinding(finding) : updateFinding(finding);
            updateRun(run);
            return saved;
        }

        @Override
        public ReviewMessage insertMessage(ReviewMessage message, ReviewRun run)
        {
            ReviewMessage saved = insertMessage(message);
            updateRun(run);
            return saved;
        }

        @Override
        public Optional<ReviewRun> findRun(String runId)
        {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public List<ReviewRun> listRuns(String reviewId)
        {
            return runs.values().stream()
                    .filter(run -> run.reviewId().equals(reviewId))
                    .sorted(Comparator.comparingInt(ReviewRun::ordinal))
                    .collect(toImmutableList());
        }

        @Override
        public ReviewFinding insertFinding(ReviewFinding finding)
        {
            findings.put(finding.id(), finding);
            return finding;
        }

        @Override
        public ReviewFinding updateFinding(ReviewFinding finding)
        {
            ReviewFinding stored = findings.get(finding.id());
            checkUpdate(stored == null ? OptionalLong.empty() : OptionalLong.of(stored.version()), finding.version(), "Review finding", finding.id());
            ReviewFinding next = finding.withVersion(finding.version() + 1);
            findings.put(next.id(), next);
            return next;
        }

        @Override
        public Optional<ReviewFinding> findFinding(String findingId)
        {
            return Optional.ofNullable(findings.get(findingId));
        }

        @Override
        public List<ReviewFinding> listFindings(String reviewId)
        {
            return findings.values().stream()
                    .filter(finding -> finding.reviewId().equals(reviewId))
                    .sorted(Comparator.comparingInt(ReviewFinding::ordinal))
                    .collect(toImmutableList());
        }

        @Override
        public ReviewMessage insertMessage(ReviewMessage message)
        {
            messages.put(message.id(), message);
            return message;
        }

        @Override
        public ReviewMessage updateMessage(ReviewMessage message)
        {
            ReviewMessage stored = messages.get(message.id());
            checkUpdate(stored == null ? OptionalLong.empty() : OptionalLong.of(stored.version()), message.version(), "Review message", message.id());
            ReviewMessage next = message.withVersion(message.version() + 1);
            messages.put(next.id(), next);
            return next;
        }

        @Override
        public List<ReviewMessage> listMessages(String reviewId)
        {
            return messages.values().stream()
                    .filter(message -> message.reviewId().equals(reviewId))
                    .sorted(Comparator.comparing(ReviewMessage::createdAt))
                    .collect(toImmutableList());
        }

        private static void checkUpdate(OptionalLong storedVersion, long expectedVersion, String label, String id)
        {
            if (storedVersion.isEmpty()) {
                throw new ReviewNotFoundException(label + " is absent: " + id);
            }
            if (storedVersion.orElseThrow() != expectedVersion) {
                throw new ReviewConflictException(label + " version changed: " + id);
            }
        }
    }
}
