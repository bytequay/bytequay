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
import com.bytequay.agent.core.AgentConflictException;
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
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.context.ContextDiscussionReader;
import com.bytequay.app.context.ContextUsageRecorder;
import com.bytequay.app.context.ReviewContextService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewEvidenceAnswer;
import com.bytequay.core.review.ReviewEvidenceRequest;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.review.ReviewValidation;
import com.bytequay.core.store.ContextUsageStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    void contextSnapshotIsStoredBeforeSynchronousProviderLaunch()
    {
        Fixture fixture = new Fixture();
        ReviewContextSnapshot snapshot = contextSnapshot();
        doAnswer(invocation -> {
            ReviewRun run = invocation.getArgument(1);
            return fixture.store.updateRun(run.withContext(snapshot, ImmutableMap.of(), NOW));
        }).when(fixture.context).prepareRun(any(AgentReview.class), any(ReviewRun.class), any(WorkspaceRepositoryContext.class), any());
        doAnswer(invocation -> {
            StartAgentTurnCommand command = invocation.getArgument(0);
            AgentReview review = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow();
            ReviewRun saved = fixture.store.listRuns(review.id()).getLast();
            assertThat(saved.context()).contains(snapshot);
            assertThat(saved.turnIds()).isEmpty();
            assertThat(command.idempotencyKey()).isEqualTo(saved.currentStep().orElseThrow().key(saved.id()));
            assertThat(command.message()).doesNotContain(snapshot.bootstrap().orElseThrow().text());
            AgentTurn accepted = AgentTurn.accepted(TURN_ONE, TASK_ID, command.idempotencyKey(), SETTINGS, NOW);
            when(fixture.turnStore.findActive(TASK_ID)).thenReturn(Optional.of(accepted));
            RepositoryContextTurnInput input = new RepositoryContextTurnInput(fixture.store,
                    new ReviewToolAssignment(fixture.turnStore, fixture.queueStore, fixture.store), new ReviewLocks(), CLOCK,
                    mock(ContextDiscussionReader.class), mock(ContextUsageRecorder.class));
            assertThat(input.prepare(idleTask(), accepted, command.message())).contains(snapshot.bootstrap().orElseThrow().text());
            AgentTurn running = accepted.markDeliveryStarted(NOW).running(new ProviderTurnRef("provider-turn"), Optional.empty());
            when(fixture.turnStore.find(TURN_ONE)).thenReturn(Optional.of(running));
            fixture.acceptedTurns.put(command.idempotencyKey(), running);
            return running;
        }).when(fixture.turns).start(any(StartAgentTurnCommand.class));

        AgentReviewProjection projection = fixture.service.start(WORKSPACE, REPO, NUMBER);

        ReviewContextSnapshot captured = projection.runs().getFirst().context().orElseThrow();
        assertThat(captured.bootstrap()).isEqualTo(snapshot.bootstrap());
        assertThat(captured.deliveries()).hasSize(1);
        assertThat(projection.runs().getFirst().contextDeliveryStates().get(TURN_ONE.toString()).outcome())
                .isEqualTo(AgentReviewProjection.ContextDeliveryState.Outcome.CONFIRMED);
        assertThat(projection.byteQuayTurnIds()).containsExactly(TURN_ONE.toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedContextPreparationStopsBeforeProviderLaunchAndPreservesSavedContext(boolean saved)
    {
        Fixture fixture = new Fixture();
        ReviewContextSnapshot snapshot = contextSnapshot();
        ReviewConflictException failure = new ReviewConflictException("Context preparation failed");
        doAnswer(invocation -> {
            if (saved) {
                ReviewRun run = invocation.getArgument(1);
                fixture.store.updateRun(run.withContext(snapshot, ImmutableMap.of(), NOW));
            }
            throw failure;
        }).when(fixture.context).prepareRun(any(AgentReview.class), any(ReviewRun.class), any(WorkspaceRepositoryContext.class), any());

        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).isSameAs(failure);

        AgentReview review = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow();
        ReviewRun run = fixture.store.listRuns(review.id()).getLast();
        assertThat(run.state()).isEqualTo(ReviewRun.State.FAILED);
        assertThat(run.context()).isEqualTo(saved ? Optional.of(snapshot) : Optional.empty());
        assertThat(run.outcome()).hasValueSatisfying(outcome -> assertThat(outcome).contains("Context preparation failed"));
        assertThat(review.followUpPaused()).isTrue();
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startCreatesOneTaskAndOneRunningRunDrivenByAByteQuayRequest(boolean children)
    {
        Fixture fixture = new Fixture();
        when(fixture.driver.capabilities()).thenReturn(new AgentCapabilities(
                true, true, true, true, true, true, true, true, true, true,
                ImmutableSet.of(AgentPermissionPolicy.AUTO), ImmutableSet.of("text/plain")));
        when(fixture.driver.reviewChildAgents(idleTask().sessionReference(), idleTask().defaults().model())).thenReturn(children);

        AgentReviewProjection projection = fixture.service.start(WORKSPACE, REPO, NUMBER);

        assertThat(projection.task().nestedAgents()).isEqualTo(children);
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
        assertThat(started.getValue().idempotencyKey()).startsWith("review-step:");
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
                ImmutableList.of("E1"), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
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
    void reconcileDispatchesAPersistedStartupAssignmentExactlyOnce()
    {
        Fixture fixture = new Fixture();
        GitHubCredential credential = fixture.credentials.resolveGitHubCredential();
        when(fixture.credentials.resolveGitHubCredential()).thenReturn(credential).thenThrow(new NoClassDefFoundError("Startup class unavailable"));
        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).isInstanceOf(NoClassDefFoundError.class);
        String reviewId = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow().id();
        assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.RUNNING);

        fixture.service.reconcile(reviewId);
        fixture.service.reconcile(reviewId);

        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        assertThat(run.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(run.outcome()).isEmpty();
        assertThat(run.turnIds()).containsExactly(TURN_ONE.toString());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(1)).start(started.capture());
        assertThat(started.getValue().idempotencyKey()).isEqualTo(run.currentStep().orElseThrow().key(run.id()));
    }

    @Test
    void recoveredStartupCapturesContextBeforeAdmissionAndReusesIt()
    {
        Fixture fixture = new Fixture();
        ReviewContextSnapshot snapshot = contextSnapshot();
        doAnswer(invocation -> {
            throw new NoClassDefFoundError("Interrupted context preparation");
        }).doAnswer(invocation -> {
            ReviewRun run = invocation.getArgument(1);
            assertThat(invocation.<Optional<ReviewRun>>getArgument(3)).isEmpty();
            return fixture.store.updateRun(run.withContext(snapshot, ImmutableMap.of(), NOW));
        }).when(fixture.context).prepareRun(any(AgentReview.class), any(ReviewRun.class), any(WorkspaceRepositoryContext.class), any());
        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).isInstanceOf(NoClassDefFoundError.class);
        String reviewId = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow().id();
        doAnswer(invocation -> {
            StartAgentTurnCommand command = invocation.getArgument(0);
            ReviewRun saved = fixture.store.listRuns(reviewId).getLast();
            assertThat(saved.context()).contains(snapshot);
            assertThat(saved.turnIds()).isEmpty();
            AgentTurn accepted = AgentTurn.accepted(TURN_ONE, TASK_ID, command.idempotencyKey(), SETTINGS, NOW);
            fixture.acceptedTurns.put(command.idempotencyKey(), accepted);
            return accepted;
        }).when(fixture.turns).start(any(StartAgentTurnCommand.class));

        fixture.service.reconcile(reviewId);
        fixture.service.reconcile(reviewId);

        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        assertThat(run.context()).contains(snapshot);
        assertThat(run.turnIds()).containsExactly(TURN_ONE.toString());
        verify(fixture.context, times(2)).prepareRun(any(AgentReview.class), any(ReviewRun.class), any(WorkspaceRepositoryContext.class), any());
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRecoveredContextPreparationPausesTheUnacceptedRun(boolean saved)
    {
        Fixture fixture = new Fixture();
        ReviewContextSnapshot snapshot = contextSnapshot();
        ReviewConflictException failure = new ReviewConflictException("Context preparation failed");
        doAnswer(invocation -> {
            throw new NoClassDefFoundError("Interrupted context preparation");
        }).doAnswer(invocation -> {
            if (saved) {
                ReviewRun run = invocation.getArgument(1);
                fixture.store.updateRun(run.withContext(snapshot, ImmutableMap.of(), NOW));
            }
            throw failure;
        }).when(fixture.context).prepareRun(any(AgentReview.class), any(ReviewRun.class), any(WorkspaceRepositoryContext.class), any());
        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).isInstanceOf(NoClassDefFoundError.class);
        String reviewId = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow().id();

        assertThatThrownBy(() -> fixture.service.reconcile(reviewId)).isSameAs(failure);

        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        assertThat(run.state()).isEqualTo(ReviewRun.State.FAILED);
        assertThat(run.context()).isEqualTo(saved ? Optional.of(snapshot) : Optional.empty());
        assertThat(run.outcome()).hasValueSatisfying(outcome -> assertThat(outcome).contains("Context preparation failed"));
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isTrue();
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void acceptedHistoricalTurnsAndLaterAssignmentsKeepTheirOriginalContext()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        fixture.service.reconcile(reviewId);
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());

        assertThat(validating.context()).isEmpty();
        assertThat(validating.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.VALIDATE);
        verify(fixture.context, times(1)).prepareRun(any(AgentReview.class), any(ReviewRun.class), any(WorkspaceRepositoryContext.class), any());
        verify(fixture.workspaces, never()).requireReadableRepository(anyString());
    }

    @Test
    void resumeContinuesAPersistedStartupOnTheSameReviewAndTask()
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
                .containsExactly(ReviewRun.State.RUNNING);
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
        AgentTurn accepted = AgentTurn.accepted(TURN_ONE, TASK_ID, "review-step:" + unlinked.id() + ":1", SETTINGS, NOW);
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
        fixture.store.updateRun(sealInvestigation(unlinked.withSummary(summary(), NOW).withInvestigation(investigation(), NOW)));
        AgentTurn completed = settledTurn(TURN_ONE, TurnState.COMPLETED, Optional.empty());
        when(fixture.turns.findTurnByIdempotencyKey(TASK_ID, "review-step:" + unlinked.id() + ":1")).thenReturn(Optional.of(completed));
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
                ReviewRun run = fixture.store.listRuns(reviewId).getLast();
                verify(fixture.turns, times(2)).findTurnByIdempotencyKey(TASK_ID, run.currentStep().orElseThrow().key(run.id()));
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
        fixture.store.insertRun(new ReviewRun("newer-run", reviewId, 2, original.scope(), original.baseRef(), original.baseSha(), original.mergeBaseSha(), NEW_HEAD,
                Optional.of(HEAD), original.commits(), original.diff(), ImmutableList.of(), original.state(), Optional.empty(), Optional.empty(), Optional.empty(),
                original.steps(), false, original.operations(), original.evidence(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty()));
        String cardId = original.id() + ":purpose";
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        ReviewToolAssignment assignments = new ReviewToolAssignment(turnStore, fixture.queueStore, fixture.store);
        doAnswer(invocation -> {
            StartAgentTurnCommand command = invocation.getArgument(0);
            ReviewMessage saved = fixture.store.listMessages(reviewId).getFirst();
            assertThat(saved.turnId()).isEmpty();
            assertThat(saved.queuedInputId()).isEmpty();
            assertThat(command.idempotencyKey()).isEqualTo("review-ask:" + saved.id());
            AgentTurn accepted = AgentTurn.accepted(TURN_TWO, TASK_ID, command.idempotencyKey(), SETTINGS, NOW);
            when(turnStore.findActive(TASK_ID)).thenReturn(Optional.of(accepted));
            ReviewToolAssignment.Binding binding = assignments.resolve(reviewId, Optional.of(TURN_TWO));
            assertThat(binding.run().id()).isEqualTo(original.id());
            assertThat(binding.discussion()).contains(saved);
            fixture.acceptedTurns.put(command.idempotencyKey(), accepted);
            return accepted;
        }).when(fixture.turns).start(any(StartAgentTurnCommand.class));
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
        when(fixture.turns.enqueue(any(QueueAgentInputCommand.class))).thenAnswer(invocation -> {
            QueueAgentInputCommand command = invocation.getArgument(0);
            ReviewMessage saved = fixture.store.listMessages(reviewId).getFirst();
            assertThat(saved.turnId()).isEmpty();
            assertThat(saved.queuedInputId()).isEmpty();
            assertThat(command.idempotencyKey()).isEqualTo("review-ask:" + saved.id());
            return queuedInput();
        });

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void questionRemainsSavedWhenAdmissionFails(boolean queued)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(run.withSummary(summary(), NOW));
        String cardId = run.id() + ":purpose";
        AgentConflictException failure = new AgentConflictException("Admission failed");
        if (queued) {
            when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
            doThrow(failure).when(fixture.turns).enqueue(any(QueueAgentInputCommand.class));
        }
        else {
            doThrow(failure).when(fixture.turns).start(any(StartAgentTurnCommand.class));
        }

        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.empty(), Optional.of(cardId), 1, "Explain the parser reset"))
                .isSameAs(failure);

        ReviewMessage saved = fixture.store.listMessages(reviewId).getFirst();
        assertThat(saved.runId()).isEqualTo(run.id());
        assertThat(saved.cardId()).contains(cardId);
        assertThat(saved.targetRevision()).isEqualTo(1);
        assertThat(saved.body()).isEqualTo("Explain the parser reset");
        assertThat(saved.turnId()).isEmpty();
        assertThat(saved.queuedInputId()).isEmpty();
        assertThat(fixture.service.get(reviewId).messages()).extracting(AgentReviewProjection.MessageView::message).containsExactly(saved);
        if (queued) {
            verify(fixture.turns).findQueuedByIdempotencyKey(TASK_ID, "review-ask:" + saved.id());
        }
        else {
            verify(fixture.turns).findTurnByIdempotencyKey(TASK_ID, "review-ask:" + saved.id());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void questionRecoversAcceptedDeliveryWhenAdmissionThrows(boolean queued)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        AgentConflictException failure = new AgentConflictException("Provider launch failed after acceptance");
        if (queued) {
            when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
            doAnswer(invocation -> {
                QueueAgentInputCommand command = invocation.getArgument(0);
                when(fixture.turns.findQueuedByIdempotencyKey(TASK_ID, command.idempotencyKey())).thenReturn(Optional.of(queuedInput()));
                throw failure;
            }).when(fixture.turns).enqueue(any(QueueAgentInputCommand.class));
        }
        else {
            doAnswer(invocation -> {
                StartAgentTurnCommand command = invocation.getArgument(0);
                fixture.acceptedTurns.put(command.idempotencyKey(), AgentTurn.accepted(TURN_TWO, TASK_ID, command.idempotencyKey(), SETTINGS, NOW));
                throw failure;
            }).when(fixture.turns).start(any(StartAgentTurnCommand.class));
        }

        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.empty(), Optional.empty(), 1, "Explain the parser reset"))
                .isSameAs(failure);

        ReviewMessage saved = fixture.store.listMessages(reviewId).getFirst();
        assertThat(saved.turnId()).isEqualTo(queued ? Optional.empty() : Optional.of(TURN_TWO.toString()));
        assertThat(saved.queuedInputId()).isEqualTo(queued ? Optional.of(QUEUED_INPUT.toString()) : Optional.empty());
        assertThat(fixture.store.listMessages(reviewId)).hasSize(1);
        verify(fixture.turns, times(queued ? 1 : 2)).start(any(StartAgentTurnCommand.class));
        verify(fixture.turns, times(queued ? 1 : 0)).enqueue(any(QueueAgentInputCommand.class));
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
        ReviewFinding original = finding("finding-1", reviewId, runId, 1, ReviewFinding.State.OPEN);
        ReviewFinding revised = fixture.store.insertFinding(
                original.revised("Reset the parser again", "Body", Optional.of("major"), original.anchor(), ImmutableList.of(), HEAD, NOW));
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
        fixture.store.updateRun(sealInvestigation(run.withSummary(summary(), NOW).withInvestigation(investigation(), NOW)));
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
        fixture.store.updateRun(sealInvestigation(run.withSummary(summary(), NOW).withInvestigation(investigation(), NOW)));
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
        assertThat(started.getValue().idempotencyKey()).isEqualTo("review-step:" + run.id() + ":2");
        assertThat(started.getValue().message()).isEqualTo(ReviewPrompts.stepRequest(validating));
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
    }

    @Test
    void investigationAndValidationEachRecoverWithoutRestartingTheReview()
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
        assertThat(started.getValue().message()).isEqualTo(ReviewPrompts.stepRequest(repairing));

        fixture.store.updateRun(sealInvestigation(repairing.withInvestigation(investigation(), NOW)));
        when(fixture.turns.get(TURN_TWO)).thenReturn(settledTurn(TURN_TWO, TurnState.COMPLETED, Optional.empty()));
        fixture.service.reconcile(reviewId);

        ReviewRun validating = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(validating.validationStarted()).isTrue();
        assertThat(validating.completionRepairUsed()).isTrue();
        assertThat(validating.turnIds()).hasSize(3);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));

        // Repairing investigation must not spend validation's own automatic continuation.
        settleCurrentTurn(fixture, validating);
        fixture.service.reconcile(reviewId);
        fixture.service.reconcile(reviewId);
        ReviewRun validationRepair = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(validationRepair.currentStep().orElseThrow().repair()).isTrue();
        assertThat(validationRepair.turnIds()).hasSize(4);
        completeValidation(fixture, validationRepair);
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        assertThat(fixture.store.findRun(run.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(run.id());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        verify(fixture.turns, times(4)).start(any(StartAgentTurnCommand.class));
        verify(fixture.turns, never()).synchronizeWorktree(any(), anyLong(), any());
    }

    @Test
    void validationReattachesAfterDispatchWithoutStartingAnotherTurn()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(sealInvestigation(run.withSummary(summary(), NOW).withInvestigation(investigation(), NOW)));
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
    void aPersistedValidationAssignmentDispatchesExactlyOnce()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(sealInvestigation(run.withSummary(summary(), NOW).withInvestigation(investigation(), NOW))
                .withStep(run.currentStep().orElseThrow().next(ReviewStep.Kind.VALIDATE), NOW));
        when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.COMPLETED, Optional.empty()));

        fixture.service.reconcile(reviewId);

        ReviewRun validating = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(validating.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(validating.turnIds()).containsExactly(TURN_ONE.toString(), TURN_TWO.toString());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(2)).start(started.capture());
        assertThat(started.getValue().idempotencyKey()).isEqualTo("review-step:" + run.id() + ":2");

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
    void requestsSpecificEvidenceThenValidatesTheNewInvestigation()
    {
        Fixture fixture = new Fixture();
        ReviewRun validating = beginCandidateValidation(fixture);
        submitCandidateValidation(fixture, validating, ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE);

        fixture.service.reconcile(validating.reviewId());

        ReviewRun collecting = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(collecting.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.COLLECT_EVIDENCE);
        assertThat(collecting.coverage()).isEmpty();
        assertThat(collecting.steps().get(1).validation().orElseThrow().candidates().getFirst().request().orElseThrow().question()).isEqualTo("Does the caller close the parser?");
        assertThat(fixture.store.findReview(validating.reviewId()).orElseThrow().latestCompletedRunId()).isEmpty();

        finishEvidenceCollection(fixture, collecting, Optional.empty());
        fixture.service.reconcile(validating.reviewId());
        ReviewRun revalidating = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(revalidating.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.VALIDATE);
        submitCandidateValidation(fixture, revalidating, ReviewCandidateValidation.Outcome.REJECTED);
        fixture.service.reconcile(validating.reviewId());

        ReviewRun accepted = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(accepted.state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(accepted.steps()).extracting(ReviewStep::kind).containsExactly(
                ReviewStep.Kind.INVESTIGATE, ReviewStep.Kind.VALIDATE, ReviewStep.Kind.COLLECT_EVIDENCE, ReviewStep.Kind.VALIDATE);
        assertThat(accepted.currentStep().orElseThrow().validation().orElseThrow().investigationStep()).isEqualTo(3);
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        verify(fixture.turns, times(4)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void aCarriedFindingCanRequestEvidenceWithoutPrematureReportRepair()
    {
        Fixture fixture = new Fixture();
        ReviewRun validating = beginCandidateValidation(fixture);
        ReviewFinding carried = finding("carried", validating.reviewId(), "earlier-run", 1, ReviewFinding.State.OPEN).pendingReassessment(NOW);
        fixture.store.insertFinding(carried);
        submitCandidateValidation(fixture, validating, ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE);

        fixture.service.reconcile(validating.reviewId());

        ReviewRun collecting = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(collecting.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.COLLECT_EVIDENCE);
        assertThat(collecting.completionRepairUsed()).isFalse();
        assertThat(fixture.store.findFinding(carried.id()).orElseThrow().outdated()).isTrue();
    }

    @Test
    void aCompleteReportCannotSilentlyDropACarriedOpenFinding()
    {
        Fixture fixture = new Fixture();
        ReviewRun validating = beginCandidateValidation(fixture);
        ReviewFinding carried = finding("carried", validating.reviewId(), "earlier-run", 1, ReviewFinding.State.OPEN).pendingReassessment(NOW);
        fixture.store.insertFinding(carried);
        submitCandidateValidation(fixture, validating, ReviewCandidateValidation.Outcome.REJECTED);

        fixture.service.reconcile(validating.reviewId());

        ReviewRun repairing = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(repairing.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(repairing.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.VALIDATE);
        assertThat(repairing.currentStep().orElseThrow().repair()).isTrue();
        assertThat(fixture.store.findReview(validating.reviewId()).orElseThrow().latestCompletedRunId()).isEmpty();
    }

    @Test
    void evidenceLoopStopsAfterTwoCollectionsWithoutAdvancingTheBaseline()
    {
        Fixture fixture = new Fixture();
        ReviewRun current = beginCandidateValidation(fixture);
        for (int round = 0; round < 2; round++) {
            submitCandidateValidation(fixture, current, ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE);
            fixture.service.reconcile(current.reviewId());
            ReviewRun collecting = fixture.store.findRun(current.id()).orElseThrow();
            finishEvidenceCollection(fixture, collecting, Optional.of("The integration dependency is unavailable"));
            fixture.service.reconcile(current.reviewId());
            current = fixture.store.findRun(current.id()).orElseThrow();
        }
        submitCandidateValidation(fixture, current, ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE);
        fixture.service.reconcile(current.reviewId());

        ReviewRun incomplete = fixture.store.findRun(current.id()).orElseThrow();
        assertThat(incomplete.state()).isEqualTo(ReviewRun.State.INCOMPLETE);
        assertThat(incomplete.coverage().orElseThrow().complete()).isFalse();
        assertThat(incomplete.steps()).hasSize(6);
        assertThat(incomplete.outcome()).contains("The evidence follow-up limit was exhausted; candidates remain unresolved");
        assertThat(incomplete.completionRepairUsed()).isFalse();
        assertThat(fixture.store.findReview(current.reviewId()).orElseThrow().followUpPaused()).isTrue();
        assertThat(fixture.store.findReview(current.reviewId()).orElseThrow().latestCompletedRunId()).isEmpty();
        verify(fixture.turns, times(6)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void pausedEvidenceRequestWaitsForResumeAndReattachesAnAcceptedCollection()
    {
        Fixture fixture = new Fixture();
        ReviewRun validating = beginCandidateValidation(fixture);
        submitCandidateValidation(fixture, validating, ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE);
        fixture.service.stop(validating.reviewId());
        fixture.service.reconcile(validating.reviewId());
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
        doThrow(new ReviewConflictException("Evidence association failed")).doCallRealMethod()
                .when(fixture.store).updateRun(argThat(candidate -> candidate.turnIds().size() == 3));

        assertThatThrownBy(() -> fixture.service.resume(validating.reviewId())).hasMessageContaining("Evidence association failed");
        fixture.service.reconcile(validating.reviewId());
        fixture.service.reconcile(validating.reviewId());

        ReviewRun collecting = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(collecting.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.COLLECT_EVIDENCE);
        assertThat(collecting.turnIds()).hasSize(3);
        assertThat(collecting.completionRepairUsed()).isFalse();
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void anUnresolvedCandidateStopsWithoutSpendingAnEvidenceRound()
    {
        Fixture fixture = new Fixture();
        ReviewRun validating = beginCandidateValidation(fixture);
        submitCandidateValidation(fixture, validating, ReviewCandidateValidation.Outcome.UNRESOLVED);

        fixture.service.reconcile(validating.reviewId());

        ReviewRun incomplete = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(incomplete.state()).isEqualTo(ReviewRun.State.INCOMPLETE);
        assertThat(incomplete.steps()).hasSize(2);
        assertThat(incomplete.outcome()).contains("The review has unresolved candidates or incomplete coverage");
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void validationCanRequestInvestigationOfANewCandidate()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());
        ReviewEvidence evidence = new ReviewEvidence("E1", "src/App.java", "frozen head", "new Parser()", "allocation");
        ReviewValidation report = new ReviewValidation(1, ImmutableList.of(candidate(ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE)),
                new ReviewCoverage(false, ImmutableList.of("New parser ownership concern"), ImmutableList.of(), NOW), ImmutableList.of("The parser may leak"));
        fixture.store.updateRun(validating.withEvidence("E1", evidence, NOW)
                .replaceCurrentStep(validating.currentStep().orElseThrow().withValidation(report), NOW));
        settleCurrentTurn(fixture, validating);

        fixture.service.reconcile(reviewId);

        ReviewRun collecting = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(collecting.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.COLLECT_EVIDENCE);
        assertThat(collecting.steps().get(1).validation().orElseThrow().newCandidates()).containsExactly("The parser may leak");
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
    }

    @Test
    void aSavedEvidenceResponseWaitsForItsOwnTurnToComplete()
    {
        Fixture fixture = new Fixture();
        ReviewRun validating = beginCandidateValidation(fixture);
        submitCandidateValidation(fixture, validating, ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE);
        fixture.service.reconcile(validating.reviewId());
        ReviewRun collecting = fixture.store.findRun(validating.id()).orElseThrow();
        finishEvidenceCollection(fixture, collecting, Optional.empty());
        TurnId evidenceTurn = TurnId.valueOf(collecting.turnIds().getLast());
        AgentTurn accepted = fixture.acceptedTurns.get(collecting.currentStep().orElseThrow().key(collecting.id()));
        when(fixture.turns.get(evidenceTurn)).thenReturn(accepted);

        fixture.service.reconcile(validating.reviewId());

        assertThat(fixture.store.findRun(validating.id()).orElseThrow().currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.COLLECT_EVIDENCE);
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
        settleCurrentTurn(fixture, collecting);
        fixture.service.reconcile(validating.reviewId());
        verify(fixture.turns, times(4)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void changedFindingRevisionCannotReuseAnEarlierValidation()
    {
        Fixture fixture = new Fixture();
        ReviewRun validating = beginCandidateValidation(fixture);
        ReviewFinding finding = fixture.store.insertFinding(finding("finding-1", validating.reviewId(), validating.id(), 1, ReviewFinding.State.OPEN));
        ReviewCandidateValidation candidate = new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.SUPPORTED, "The owner never closes the parser",
                ImmutableList.of("E1"), "No caller cleanup exists", Optional.of(finding.id()), Optional.of(finding.revision()), Optional.empty());
        ReviewValidation report = new ReviewValidation(1, ImmutableList.of(candidate), coverage(), ImmutableList.of());
        fixture.store.updateRun(validating.replaceCurrentStep(validating.currentStep().orElseThrow().withValidation(report), NOW).withCoverage(coverage(), NOW));
        fixture.store.updateFinding(finding.revised("Different claim", "Different behavior", Optional.empty(), finding.anchor(), ImmutableList.of(), validating.headSha(), NOW));
        settleCurrentTurn(fixture, validating);

        fixture.service.reconcile(validating.reviewId());

        ReviewRun repairing = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(repairing.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(repairing.steps().get(1).validation()).contains(report);
        assertThat(repairing.currentStep().orElseThrow().number()).isEqualTo(3);
        assertThat(repairing.currentStep().orElseThrow().repair()).isTrue();
        assertThat(repairing.currentStep().orElseThrow().sealed()).isFalse();
        assertThat(fixture.store.findReview(validating.reviewId()).orElseThrow().latestCompletedRunId()).isEmpty();
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void unavailableOptionalTestsDoNotPreventAcceptingAValidatedFinding()
    {
        Fixture fixture = new Fixture();
        ReviewRun validating = beginCandidateValidation(fixture);
        ReviewFinding finding = fixture.store.insertFinding(finding("finding-1", validating.reviewId(), validating.id(), 1, ReviewFinding.State.OPEN));
        ReviewCandidateValidation candidate = new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.SUPPORTED, "The owner never closes the parser",
                ImmutableList.of("E1"), "No caller cleanup exists", Optional.of(finding.id()), Optional.of(finding.revision()), Optional.empty());
        ReviewCoverage coverage = new ReviewCoverage(true, ImmutableList.of(), ImmutableList.of("Snowflake cloud tests unavailable without credentials"), NOW);
        ReviewValidation report = new ReviewValidation(1, ImmutableList.of(candidate), coverage, ImmutableList.of());
        fixture.store.updateRun(validating.replaceCurrentStep(validating.currentStep().orElseThrow().withValidation(report), NOW));
        settleCurrentTurn(fixture, validating);

        fixture.service.reconcile(validating.reviewId());
        fixture.service.reconcile(validating.reviewId());

        ReviewRun accepted = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(accepted.state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(accepted.coverage()).contains(coverage);
        assertThat(accepted.currentStep().orElseThrow().validation()).contains(report);
        assertThat(fixture.store.findReview(validating.reviewId()).orElseThrow().latestCompletedRunId()).contains(validating.id());
        assertThat(fixture.store.findReview(validating.reviewId()).orElseThrow().followUpPaused()).isFalse();
        assertThat(fixture.store.findFinding(finding.id())).contains(finding);
        assertThat(accepted.steps()).hasSize(2);
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void validationCannotAcceptAnEarlierInvestigationRevision()
    {
        Fixture fixture = new Fixture();
        ReviewRun validating = beginCandidateValidation(fixture);
        submitCandidateValidation(fixture, validating, ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE);
        fixture.service.reconcile(validating.reviewId());
        ReviewRun collecting = fixture.store.findRun(validating.id()).orElseThrow();
        finishEvidenceCollection(fixture, collecting, Optional.empty());
        fixture.service.reconcile(validating.reviewId());
        ReviewRun revalidating = fixture.store.findRun(validating.id()).orElseThrow();
        ReviewCandidateValidation candidate = candidate(ReviewCandidateValidation.Outcome.REJECTED);
        fixture.store.updateRun(revalidating.replaceCurrentStep(revalidating.currentStep().orElseThrow()
                .withValidation(new ReviewValidation(1, ImmutableList.of(candidate), coverage(), ImmutableList.of())), NOW));
        settleCurrentTurn(fixture, revalidating);

        fixture.service.reconcile(validating.reviewId());

        ReviewRun repairing = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(repairing.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(repairing.currentStep().orElseThrow().repair()).isTrue();
        assertThat(repairing.currentStep().orElseThrow().number()).isEqualTo(5);
        assertThat(fixture.store.findReview(validating.reviewId()).orElseThrow().latestCompletedRunId()).isEmpty();
        submitCandidateValidation(fixture, repairing, ReviewCandidateValidation.Outcome.REJECTED);
        fixture.service.reconcile(validating.reviewId());
        assertThat(fixture.store.findRun(validating.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
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
        fixture.service.update(reviewId);
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());
        fixture.store.updateRun(validating.withCoverage(new ReviewCoverage(false, ImmutableList.of("The parser integration path was not reviewed"),
                ImmutableList.of("Cloud tests unavailable without credentials"), NOW), NOW));
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
                .withCoverage(new ReviewCoverage(false, ImmutableList.of("The required source is unavailable"), ImmutableList.of(), NOW), NOW));
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
        assertThat(fixture.store.findFinding(originalFinding.id()).orElseThrow().outdated()).isTrue();
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();

        ReviewEvidence evidence = new ReviewEvidence("E1", "src/App.java", "frozen head", "Parser state is reset", "The original trigger remains");
        ReviewFinding mapped = fixture.store.findFinding(originalFinding.id()).orElseThrow();
        ReviewFinding revalidated = mapped.revised(mapped.title(), mapped.body(), mapped.severity(), mapped.anchor(), ImmutableList.of(evidence), baseline.headSha(), NOW);
        fixture.store.updateFinding(revalidated);
        ReviewRun evidenced = fixture.store.updateRun(baseline.withEvidence("E1", evidence, NOW));
        ReviewInvestigation assessment = new ReviewInvestigation("Parser lifetime", ImmutableList.of("src/App.java"), ImmutableList.of("E1"),
                ImmutableList.of("Does the earlier finding still hold?"), ImmutableList.of(), ImmutableList.of());
        ReviewRun validating = beginValidation(fixture, evidenced, assessment);
        ReviewCandidateValidation supported = new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.SUPPORTED,
                "The original trigger and contract still hold", ImmutableList.of("E1"), "No counterexample found",
                Optional.of(revalidated.id()), Optional.of(revalidated.revision()), Optional.empty());
        ReviewValidation report = new ReviewValidation(1, ImmutableList.of(supported), coverage(), ImmutableList.of());
        fixture.store.updateRun(validating.withCoverage(coverage(), NOW)
                .replaceCurrentStep(validating.currentStep().orElseThrow().withValidation(report), NOW));
        settleCurrentTurn(fixture, validating);
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

        fixture.service.update(reviewId);

        ReviewRun incremental = fixture.store.listRuns(reviewId).getLast();
        assertThat(incremental.scope()).isEqualTo(ReviewRun.Scope.INCREMENTAL);
        assertThat(incremental.priorHeadSha()).contains(HEAD);
        assertThat(incremental.headSha()).isEqualTo(NEW_HEAD);
        assertThat(fixture.store.listFindings(reviewId)).extracting(ReviewFinding::id).containsExactly(originalFinding.id());
        ReviewFinding pending = fixture.store.findFinding(originalFinding.id()).orElseThrow();
        assertThat(pending.outdated()).isTrue();
        assertThat(pending.validatedHeadSha()).isEqualTo(HEAD);
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        verify(fixture.completions, never()).complete(any(CompleteAgentTaskCommand.class));
    }

    @Test
    void aChangedBaseStartsAFullReviewEvenWhenThePullRequestHeadIsUnchanged()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun first = fixture.store.listRuns(reviewId).getFirst();
        ReviewRun validating = beginValidation(fixture, first);
        completeValidation(fixture, validating);
        fixture.service.reconcile(reviewId);
        ReviewFinding finding = fixture.store.insertFinding(finding("finding-1", reviewId, first.id(), 1, ReviewFinding.State.OPEN));
        String newerBase = "3".repeat(40);
        when(fixture.git.run(REPOSITORY, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}")).thenReturn(ok(newerBase));
        when(fixture.git.run(REPOSITORY, "merge-base", HEAD, newerBase)).thenReturn(ok(MERGE_BASE));

        assertThat(fixture.service.currentScopeMatches(first)).isFalse();

        fixture.service.update(reviewId);

        ReviewRun updated = fixture.store.listRuns(reviewId).getLast();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(2);
        assertThat(updated.headSha()).isEqualTo(HEAD);
        assertThat(updated.baseSha()).isEqualTo(newerBase);
        assertThat(updated.scope()).isEqualTo(ReviewRun.Scope.FULL);
        ReviewFinding pending = fixture.store.findFinding(finding.id()).orElseThrow();
        assertThat(pending.outdated()).isTrue();
        assertThat(pending.validatedHeadSha()).isEqualTo(HEAD);

        ReviewMessage question = fixture.service.ask(reviewId, Optional.of(finding.id()), Optional.empty(), pending.revision(), "Still valid?");
        assertThat(question.runId()).isEqualTo(updated.id());
        assertThat(question.runId()).isNotEqualTo(first.id());
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(4)).start(started.capture());
        assertThat(started.getValue().message()).contains("reviewed head " + HEAD.substring(0, 7),
                "pending reassessment in the current review scope", "current base and head");
    }

    @Test
    void reopeningAResolvedFindingCanStartExplicitReassessmentAtTheSameHeadAndBase()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun first = fixture.store.listRuns(reviewId).getFirst();
        completeValidation(fixture, beginValidation(fixture, first));
        fixture.service.reconcile(reviewId);
        ReviewFinding finding = fixture.store.insertFinding(finding("finding-1", reviewId, first.id(), 1, ReviewFinding.State.OPEN));
        fixture.service.resolveFinding(reviewId, finding.id(), finding.version(), "Addressed locally");
        String newerBase = "3".repeat(40);
        when(fixture.git.run(REPOSITORY, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}")).thenReturn(ok(newerBase));
        when(fixture.git.run(REPOSITORY, "merge-base", HEAD, newerBase)).thenReturn(ok(MERGE_BASE));

        fixture.service.update(reviewId);
        ReviewRun second = fixture.store.listRuns(reviewId).getLast();
        ReviewFinding resolved = fixture.store.findFinding(finding.id()).orElseThrow();
        assertThat(resolved.state()).isEqualTo(ReviewFinding.State.RESOLVED);
        assertThat(resolved.outdated()).isTrue();
        completeValidation(fixture, beginValidation(fixture, second));
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.findRun(second.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);

        ReviewFinding reopened = fixture.service.reopenFinding(reviewId, finding.id(), resolved.version());
        assertThat(reopened.outdated()).isTrue();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(2);

        fixture.service.update(reviewId);

        ReviewRun reassessment = fixture.store.listRuns(reviewId).getLast();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(3);
        assertThat(reassessment.id()).isNotEqualTo(second.id());
        assertThat(reassessment.headSha()).isEqualTo(HEAD);
        assertThat(reassessment.baseSha()).isEqualTo(newerBase);
        assertThat(reassessment.scope()).isEqualTo(ReviewRun.Scope.FULL);
        assertThat(fixture.store.findFinding(finding.id()).orElseThrow().outdated()).isTrue();
    }

    @Test
    void reconcileSpendsTheRepairBudgetOnAMissingCoverageRecord()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        beginValidation(fixture, run);
        when(fixture.turns.get(TURN_TWO)).thenReturn(settledTurn(TURN_TWO, TurnState.COMPLETED, Optional.empty()));

        fixture.service.reconcile(reviewId);

        ReviewRun repairing = fixture.store.listRuns(reviewId).getLast();
        assertThat(repairing.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(repairing.completionRepairUsed()).isTrue();
        assertThat(repairing.turnIds()).hasSize(3).startsWith(TURN_ONE.toString(), TURN_TWO.toString());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();

        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(3)).start(started.capture());
        assertThat(started.getValue().message()).isEqualTo(ReviewPrompts.stepRequest(repairing));
        assertThat(started.getValue().idempotencyKey()).isEqualTo("review-step:" + run.id() + ":2:repair");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reconcileMarksARunIncompleteOnceTheRepairBudgetIsSpent(boolean paused)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewRun validating = beginValidation(fixture, run);
        AgentTurn repair = fixture.turns.start(new StartAgentTurnCommand(TASK_ID, "Repair coverage", ImmutableList.of(), "review-step:" + run.id() + ":2:repair"));
        fixture.store.updateRun(validating.replaceCurrentStep(validating.currentStep().orElseThrow().forRepair(), NOW)
                .withCompletionRepairUsed(NOW).withTurn(repair.id().toString(), NOW));
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
        assertThat(incomplete.outcome()).contains("The agent stopped before saving a complete review report");
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).isEmpty();
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));

        fixture.service.resume(reviewId);

        ReviewRun retry = fixture.store.listRuns(reviewId).getLast();
        assertThat(retry.id()).isNotEqualTo(incomplete.id());
        assertThat(retry.headSha()).isEqualTo(incomplete.headSha());
        assertThat(retry.completionRepairUsed()).isFalse();
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        completeValidation(fixture, beginValidation(fixture, retry));
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.findRun(retry.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(retry.id());
        assertThat(fixture.store.findRun(incomplete.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.INCOMPLETE);
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

    @ParameterizedTest
    @EnumSource(value = TurnState.class, names = {"FAILED", "INTERRUPTED"})
    void aKnownInterruptedTurnContinuesTheSameFrozenReviewAfterCleanup(TurnState state)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(run.withSummary(summary(), NOW));
        AgentTurn failed = AgentTurn.accepted(TURN_ONE, TASK_ID, run.currentStep().orElseThrow().key(run.id()), SETTINGS, NOW)
                .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider-turn"), Optional.empty())
                .finish(state, AgentMetrics.empty(), Optional.of("Connection lost"), NOW);
        when(fixture.turns.get(TURN_ONE)).thenReturn(failed);
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.recovery("cleanup", NOW), NOW));
        fixture.service.reconcile(reviewId);
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));

        pushHead(fixture, NEW_HEAD);
        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask());
        fixture.service.reconcile(reviewId);
        fixture.service.reconcile(reviewId);

        ReviewRun continuing = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(continuing.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(continuing.headSha()).isEqualTo(HEAD);
        assertThat(continuing.summary()).contains(summary());
        assertThat(continuing.currentStep().orElseThrow().repair()).isTrue();
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
        completeValidation(fixture, beginValidation(fixture, continuing));
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        assertThat(fixture.store.findRun(run.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        verify(fixture.turns, never()).synchronizeWorktree(any(), anyLong(), any());
    }

    @Test
    void automaticContinuationPreservesAnAlreadySealedValidation()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());
        completeValidation(fixture, validating);
        ReviewValidation report = fixture.store.findRun(validating.id()).orElseThrow().currentStep().orElseThrow().validation().orElseThrow();
        AgentTurn failed = AgentTurn.accepted(TURN_TWO, TASK_ID, validating.currentStep().orElseThrow().key(validating.id()), SETTINGS, NOW)
                .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider-turn"), Optional.empty())
                .finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("Connection lost after saving report"), NOW);
        when(fixture.turns.get(TURN_TWO)).thenReturn(failed);

        fixture.service.reconcile(reviewId);
        ReviewRun continuing = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(continuing.currentStep().orElseThrow().validation()).contains(report);
        assertThat(ReviewPrompts.stepRequest(continuing)).contains("already saved and sealed", "without resubmitting");
        settleCurrentTurn(fixture, continuing);
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.findRun(validating.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "authentication", "unknown-delivery"})
    void automaticContinuationDoesNotOverrideAStopOrRequiredUserAction(String blocker)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        AgentTurn turn = AgentTurn.accepted(TURN_ONE, TASK_ID, run.currentStep().orElseThrow().key(run.id()), SETTINGS, NOW).markDeliveryStarted(NOW);
        if (!blocker.equals("unknown-delivery")) {
            turn = turn.running(new ProviderTurnRef("provider-turn"), Optional.empty());
        }
        if (blocker.equals("stop")) {
            turn = turn.requestCancellation(NOW);
        }
        when(fixture.turns.get(TURN_ONE)).thenReturn(turn.finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of(blocker),
                blocker.equals("authentication") ? Optional.of(AgentTurnFailureKind.AUTHENTICATION_REQUIRED) : Optional.empty(), NOW));

        fixture.service.reconcile(reviewId);
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.findRun(run.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.FAILED);
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void anUnsentLaunchFailureIsRetriedWithoutWaitingForAUserOrTaskEvent()
    {
        Fixture fixture = new Fixture();
        doThrow(new IllegalStateException("Temporary launch failure"))
                .doAnswer(invocation -> fixture.acceptTurn(invocation.getArgument(0)))
                .when(fixture.turns).start(any(StartAgentTurnCommand.class));

        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        assertThat(run.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(run.turnIds()).containsExactly(TURN_ONE.toString());
        ArgumentCaptor<StartAgentTurnCommand> commands = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns, times(2)).start(commands.capture());
        assertThat(commands.getAllValues().getFirst()).isEqualTo(commands.getAllValues().getLast());
        completeValidation(fixture, beginValidation(fixture, run));
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.findRun(run.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
    }

    @Test
    void anAcceptedLaunchIsReattachedAfterItsResponseFails()
    {
        Fixture fixture = new Fixture();
        doAnswer(invocation -> {
            fixture.acceptTurn(invocation.getArgument(0));
            throw new IllegalStateException("Response lost after acceptance");
        }).when(fixture.turns).start(any(StartAgentTurnCommand.class));

        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();

        assertThat(fixture.store.listRuns(reviewId).getLast().turnIds()).containsExactly(TURN_ONE.toString());
        assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.RUNNING);
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void aPersistentLaunchFailureStopsAfterTheAutomaticAttempt()
    {
        Fixture fixture = new Fixture();
        doThrow(new IllegalStateException("Launch unavailable")).when(fixture.turns).start(any(StartAgentTurnCommand.class));

        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).hasMessage("Launch unavailable");

        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.listRuns(reviewId).getLast().state()).isEqualTo(ReviewRun.State.FAILED);
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void aLaunchRequiringAuthenticationIsNotRetried()
    {
        Fixture fixture = new Fixture();
        doThrow(new AgentProviderUnavailableException(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                Optional.empty(), "Sign in required", AgentCapabilities.none())))
                .when(fixture.turns).start(any(StartAgentTurnCommand.class));

        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER)).hasMessage("Sign in required");
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void runtimePreparationCleanupDoesNotAdvanceOrRestartTheReview()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        AgentReview review = fixture.store.findReview(reviewId).orElseThrow();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();

        fixture.service.reconcilePreparation(reviewId);

        verify(fixture.preparationCleanup).reconcile(review);
        assertThat(fixture.store.listRuns(reviewId)).containsExactly(run);
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
        order.verify(fixture.observer).confirmRetainedUsage(argThat(review -> review.id().equals(reviewId)));
        order.verify(fixture.observer).unwatch(reviewId);
        order.verify(fixture.completions).complete(new CompleteAgentTaskCommand(TASK_ID, Optional.of("Agent review ended by the user")));
        order.verify(fixture.store).updateReview(argThat(review -> review.state() == AgentReview.State.CLOSED));
        order.verify(fixture.completions).cleanupWorktree(TASK_ID);
    }

    @Test
    void updateResumeAndEndRefuseWhileARunHoldsTheWorkingCopy()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.localRun("run-1", NOW), NOW));

        assertThatThrownBy(() -> fixture.service.update(reviewId)).isInstanceOf(ReviewConflictException.class).hasMessage("A run is using this working copy");
        assertThatThrownBy(() -> fixture.service.resume(reviewId)).isInstanceOf(ReviewConflictException.class).hasMessage("A run is using this working copy");
        assertThatThrownBy(() -> fixture.service.end(reviewId)).isInstanceOf(ReviewConflictException.class).hasMessage("A run is using this working copy");

        verify(fixture.refresh, never()).refreshAllDetail(REPO, NUMBER);
        verify(fixture.observer, never()).unwatch(reviewId);
        verify(fixture.completions, never()).complete(any());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().state()).isEqualTo(AgentReview.State.OPEN);
    }

    @Test
    void workingCopyEditsRunUnderTheClaimOnlyWhileNothingElseHoldsTheWorkingCopy()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        AtomicInteger mutations = new AtomicInteger();
        Function<AgentTask, String> mutation = claimed -> "saved " + claimed.id() + " " + mutations.incrementAndGet();

        // A running review owns the working copy even between steps, when its task holds no claim.
        assertWorkingCopyBusy(fixture, reviewId, mutation, "The review agent is working");
        fixture.service.stop(reviewId);
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.localRun("run-1", NOW), NOW));
        assertWorkingCopyBusy(fixture, reviewId, mutation, "A run is using this working copy");
        when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
        when(fixture.turns.get(TURN_ONE)).thenReturn(AgentTurn.accepted(TURN_ONE, TASK_ID, "review-run", SETTINGS, NOW));
        assertWorkingCopyBusy(fixture, reviewId, mutation, "The review agent is working");
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, "update", NOW), NOW));
        assertWorkingCopyBusy(fixture, reviewId, mutation, "The working copy is being updated");
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask());
        when(fixture.turns.queued(TASK_ID)).thenReturn(ImmutableList.of(queuedInput()));
        assertWorkingCopyBusy(fixture, reviewId, mutation, "Remove or resume the queued question");
        when(fixture.turns.queued(TASK_ID)).thenReturn(ImmutableList.of());
        verify(fixture.turns, never()).synchronizeWorktree(eq(TASK_ID), anyLong(), any());

        assertThat(fixture.service.mutateWorkingCopy(reviewId, mutation)).isEqualTo("saved " + TASK_ID + " 1");
        verify(fixture.turns).synchronizeWorktree(eq(TASK_ID), eq(idleTask().version()), any());

        AgentTask completed = idleTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty()), NOW, NOW.plusSeconds(60));
        when(fixture.tasks.get(TASK_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertWorkingCopyBusy(fixture, reviewId, mutation, "The working copy is gone");
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask());
        fixture.service.end(reviewId);
        assertWorkingCopyBusy(fixture, reviewId, mutation, "The review has ended");
        assertThat(mutations).hasValue(1);
    }

    private static void assertWorkingCopyBusy(Fixture fixture, String reviewId, Function<AgentTask, String> mutation, String message)
    {
        assertThatThrownBy(() -> fixture.service.mutateWorkingCopy(reviewId, mutation)).isInstanceOf(ReviewConflictException.class).hasMessage(message);
    }

    @Test
    void endRefusesSavedEditsBeforeItStopsObservingTheReview()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false, true));

        assertThatThrownBy(() -> fixture.service.end(reviewId)).isInstanceOf(ReviewConflictException.class).hasMessage("Discard saved edits before ending the review");

        // Completion refuses an open Git operation too, so End refuses it while it still observes the review.
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, false, true, false));
        assertThatThrownBy(() -> fixture.service.end(reviewId)).isInstanceOf(ReviewConflictException.class)
                .hasMessage("Finish the open Git operation in the working copy before ending the review");

        verify(fixture.observer, never()).unwatch(reviewId);
        verify(fixture.completions, never()).complete(any());
        assertThat(fixture.store.findReview(reviewId).orElseThrow().state()).isEqualTo(AgentReview.State.OPEN);

        // Ignored build output alone is not a saved edit.
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false, false));
        assertThat(fixture.service.end(reviewId).review().state()).isEqualTo(AgentReview.State.CLOSED);
    }

    @Test
    void endClosesReviewWhenRetainedUsageCannotBeReplayed()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        doThrow(new IllegalStateException("Task log unavailable")).when(fixture.observer).confirmRetainedUsage(any(AgentReview.class));

        AgentReviewProjection projection = fixture.service.end(reviewId);

        assertThat(projection.review().state()).isEqualTo(AgentReview.State.CLOSED);
        verify(fixture.observer).unwatch(reviewId);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void observingANewHeadWaitsForExplicitUpdateBeforeMovingTheWorktree(boolean rewritten)
    {
        Fixture fixture = rewrittenHistory();
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        if (!rewritten) {
            when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(ok(""));
        }

        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.findReview(reviewId).orElseThrow().observedHeadSha()).contains(NEW_HEAD);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        verify(fixture.worktrees, never()).inspect(taskWorktree());
        verify(fixture.git, never()).run(eq(taskWorktree().canonicalWorktree()), anyList());
        verify(fixture.experiments, never()).synchronize(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));

        // Ignored build output from the agent's test runs marks the snapshot dirty without blocking the move.
        when(fixture.worktrees.inspect(taskWorktree()))
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false), new WorktreeSnapshot(taskWorktree(), NEW_HEAD, true, false));
        List<String> move = rewritten ? realign(NEW_HEAD) : fastForward(NEW_HEAD);
        when(fixture.git.run(taskWorktree().canonicalWorktree(), move)).thenReturn(ok(""));

        fixture.service.update(reviewId);

        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        ReviewRun rerun = fixture.store.listRuns(reviewId).getLast();
        assertThat(rerun.headSha()).isEqualTo(NEW_HEAD);
        assertThat(rerun.scope()).isEqualTo(rewritten ? ReviewRun.Scope.FULL : ReviewRun.Scope.INCREMENTAL);
        verify(fixture.git).run(taskWorktree().canonicalWorktree(), move);
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void aNewRemoteHeadDoesNotInterruptTheCurrentInvestigationAndValidation()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        pushHead(fixture, NEW_HEAD);
        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);

        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getFirst());
        assertThat(validating.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.VALIDATE);
        completeValidation(fixture, validating);
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        assertThat(fixture.store.listRuns(reviewId).getFirst().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(fixture.store.listRuns(reviewId).getFirst().headSha()).isEqualTo(HEAD);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().observedHeadSha()).contains(NEW_HEAD);
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
        verify(fixture.worktrees, never()).inspect(taskWorktree());
        verify(fixture.experiments, never()).synchronize(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
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
    void updateRefusesAnOpenGitOperationOrUnrelatedLocalCommits()
    {
        Fixture fixture = rewrittenHistory();
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        String localCommit = "c3d4e5f6a70123456789abcdef0123456789abcd";
        when(fixture.worktrees.inspect(taskWorktree()))
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, true), new WorktreeSnapshot(taskWorktree(), localCommit, false, false));
        // Even if the remote incorporated a local commit, only the recorded review baseline may move.
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", localCommit, NEW_HEAD)).thenReturn(ok(""));

        assertThatThrownBy(() -> fixture.service.update(reviewId)).hasMessageContaining("open Git operation");
        assertThatThrownBy(() -> fixture.service.update(reviewId)).hasMessageContaining("local commits");

        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isTrue();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        verify(fixture.git, never()).run(eq(taskWorktree().canonicalWorktree()), anyList());
        verify(fixture.experiments, never()).synchronize(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
    }

    @Test
    void updatePreservesDirtyExperimentsBeforeStartingTheReviewAtTheRemoteHead()
    {
        Fixture fixture = rewrittenHistory();
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(ok(""));
        when(fixture.worktrees.inspect(taskWorktree()))
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false), new WorktreeSnapshot(taskWorktree(), NEW_HEAD, true, false));
        when(fixture.git.run(taskWorktree().canonicalWorktree(), fastForward(NEW_HEAD))).thenReturn(ok(""));

        fixture.service.update(reviewId);

        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(2);
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        assertThat(run.headSha()).isEqualTo(NEW_HEAD);
        assertThat(run.diff()).isEqualTo(fixture.store.listRuns(reviewId).getFirst().diff());
        verify(fixture.experiments).synchronize(eq(taskWorktree()), eq(HEAD), eq(NEW_HEAD), any(Runnable.class));
        verify(fixture.git).run(taskWorktree().canonicalWorktree(), fastForward(NEW_HEAD));
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void discardedConflictingExperimentsDoNotBlockTheNewReview()
    {
        Fixture fixture = rewrittenHistory();
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        when(fixture.worktrees.inspect(taskWorktree()))
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false), new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false));
        when(fixture.git.run(taskWorktree().canonicalWorktree(), realign(NEW_HEAD))).thenReturn(ok(""));
        doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return Optional.of("Conflicting private experiments were discarded; the review checkout is clean at the updated PR revision");
        }).when(fixture.experiments).synchronize(eq(taskWorktree()), eq(HEAD), eq(NEW_HEAD), any(Runnable.class));

        fixture.service.update(reviewId);

        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(2);
        assertThat(fixture.store.listRuns(reviewId).getLast().headSha()).isEqualTo(NEW_HEAD);
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
        verify(fixture.git).run(taskWorktree().canonicalWorktree(), realign(NEW_HEAD));
    }

    @Test
    void failedExperimentSynchronizationPausesWithoutStartingAnotherRun()
    {
        Fixture fixture = rewrittenHistory();
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false));
        doThrow(new IllegalStateException("Cannot preserve private experiments for synchronization"))
                .when(fixture.experiments).synchronize(eq(taskWorktree()), eq(HEAD), eq(NEW_HEAD), any(Runnable.class));

        assertThatThrownBy(() -> fixture.service.update(reviewId)).isInstanceOf(ReviewConflictException.class).hasMessageContaining("Cannot preserve private experiments");

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
        return new ReviewInvestigation("Each parser invocation owns fresh state", ImmutableList.of("src/App.java"),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    private static ReviewRun beginCandidateValidation(Fixture fixture)
    {
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewInvestigation investigation = new ReviewInvestigation("Parser lifetime", ImmutableList.of("src/App.java"), ImmutableList.of("E1"),
                ImmutableList.of("The parser may leak"), ImmutableList.of(), ImmutableList.of());
        ReviewEvidence evidence = new ReviewEvidence("E1", "src/App.java", "frozen head", "new Parser()", "allocation");
        fixture.store.updateRun(sealInvestigation(run.withSummary(summary(), NOW).withInvestigation(investigation, NOW).withEvidence("E1", evidence, NOW)));
        settleCurrentTurn(fixture, run);
        fixture.service.reconcile(reviewId);
        return fixture.store.findRun(run.id()).orElseThrow();
    }

    private static ReviewCandidateValidation candidate(ReviewCandidateValidation.Outcome outcome)
    {
        Optional<ReviewEvidenceRequest> request = outcome == ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE
                ? Optional.of(new ReviewEvidenceRequest("Does the caller close the parser?", "Inspect the caller finally block", "A guaranteed close rejects the leak"))
                : Optional.empty();
        return new ReviewCandidateValidation(1, outcome, "Parser ownership checked", ImmutableList.of("E1"),
                "Caller cleanup must be checked", Optional.empty(), Optional.empty(), request);
    }

    private static void submitCandidateValidation(Fixture fixture, ReviewRun run, ReviewCandidateValidation.Outcome outcome)
    {
        int revision = run.steps().stream().filter(step -> step.investigation().isPresent()).reduce((first, second) -> second).orElseThrow().number();
        ReviewCoverage coverage = outcome == ReviewCandidateValidation.Outcome.REJECTED
                ? coverage() : new ReviewCoverage(false, ImmutableList.of("Parser ownership unresolved"), ImmutableList.of(), NOW);
        ReviewValidation report = new ReviewValidation(revision, ImmutableList.of(candidate(outcome)), coverage, ImmutableList.of());
        fixture.store.updateRun(run.replaceCurrentStep(run.currentStep().orElseThrow().withValidation(report), NOW));
        settleCurrentTurn(fixture, run);
    }

    private static void finishEvidenceCollection(Fixture fixture, ReviewRun run, Optional<String> limitation)
    {
        ReviewInvestigation previous = run.investigation().orElseThrow();
        ReviewInvestigation report = new ReviewInvestigation(
                previous.changeMap(), previous.reviewedFiles(), previous.contextEvidenceRefs(), previous.candidates(), ImmutableList.of(),
                ImmutableList.of(new ReviewEvidenceAnswer(1, "Read the caller and checked the cleanup path", ImmutableList.of("E1"), limitation)));
        fixture.store.updateRun(sealInvestigation(run.withInvestigation(report, NOW)));
        settleCurrentTurn(fixture, run);
    }

    private static void settleCurrentTurn(Fixture fixture, ReviewRun run)
    {
        TurnId turn = TurnId.valueOf(run.turnIds().getLast());
        when(fixture.turns.get(turn)).thenReturn(settledTurn(turn, TurnState.COMPLETED, Optional.empty()));
    }

    private static ReviewRun sealInvestigation(ReviewRun run)
    {
        return run.replaceCurrentStep(run.currentStep().orElseThrow().withInvestigation(run.investigation().orElseThrow()), NOW);
    }

    private static ReviewRun beginValidation(Fixture fixture, ReviewRun run)
    {
        return beginValidation(fixture, run, investigation());
    }

    private static ReviewRun beginValidation(Fixture fixture, ReviewRun run, ReviewInvestigation investigation)
    {
        ReviewSummary summary = new ReviewSummary(1, "Fix the parser reset", run.commits().stream()
                .map(commit -> new ReviewSummary.CommitExplanation(commit.sha(), "Reset state before parsing each file")).collect(toImmutableList()),
                "", Optional.empty(), ImmutableList.of());
        fixture.store.updateRun(sealInvestigation(run.withSummary(summary, NOW).withInvestigation(investigation, NOW)));
        when(fixture.turns.get(TurnId.valueOf(run.turnIds().getLast())))
                .thenReturn(settledTurn(TurnId.valueOf(run.turnIds().getLast()), TurnState.COMPLETED, Optional.empty()));
        fixture.service.reconcile(run.reviewId());
        return fixture.store.findRun(run.id()).orElseThrow();
    }

    private static void completeValidation(Fixture fixture, ReviewRun run)
    {
        int investigationStep = run.steps().stream().filter(step -> step.investigation().isPresent()).reduce((first, second) -> second).orElseThrow().number();
        ReviewValidation report = new ReviewValidation(investigationStep, ImmutableList.of(), coverage(), ImmutableList.of());
        fixture.store.updateRun(run.withCoverage(coverage(), NOW).replaceCurrentStep(run.currentStep().orElseThrow().withValidation(report), NOW));
        TurnId validation = TurnId.valueOf(run.turnIds().getLast());
        when(fixture.turns.get(validation)).thenReturn(settledTurn(validation, TurnState.COMPLETED, Optional.empty()));
    }

    private static ReviewCoverage coverage()
    {
        return new ReviewCoverage(true, ImmutableList.of(), ImmutableList.of(), NOW);
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
                Optional.of(new ReviewAnchor("src/App.java", "RIGHT", 3, 3, ImmutableList.of("added"))),
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

    private static ReviewContextSnapshot contextSnapshot()
    {
        return new ReviewContextSnapshot("context", true, 7, 1, Optional.of(BASE_SHA), Optional.of(ContextPacket.create("Prepared reference packet")),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    private static final class Fixture
    {
        private final AgentReviewStore store = spy(new InMemoryReviewStore());
        private final ReviewDraftStore drafts = mock(ReviewDraftStore.class);
        private final AgentTaskService tasks = mock(AgentTaskService.class);
        private final AgentTurnService turns = mock(AgentTurnService.class);
        private final AgentTurnStore turnStore = mock(AgentTurnStore.class);
        private final QueuedInputStore queueStore = mock(QueuedInputStore.class);
        private final AgentCompletionService completions = mock(AgentCompletionService.class);
        private final AgentApprovalService approvals = mock(AgentApprovalService.class);
        private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        private final AgentDriver driver = mock(AgentDriver.class);
        private final ModelSettingsService models = mock(ModelSettingsService.class);
        private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
        private final WorktreeService worktrees = mock(WorktreeService.class);
        private final GitCommandRunner git = mock(GitCommandRunner.class);
        private final ReviewExperiments experiments = mock(ReviewExperiments.class);
        private final PullRequestService pullRequests = mock(PullRequestService.class);
        private final PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        private final CredentialService credentials = mock(CredentialService.class);
        private final ReviewTaskObserver observer = mock(ReviewTaskObserver.class);
        private final ReviewContextService context = mock(ReviewContextService.class);
        private final ReviewContextPreparationCleanup preparationCleanup = mock(ReviewContextPreparationCleanup.class);
        private final ContextUsageStore contextUsage = mock(ContextUsageStore.class);
        private final Map<String, AgentTurn> acceptedTurns = new LinkedHashMap<>();
        private final DefaultAgentReviewService service;

        private Fixture()
        {
            service = new DefaultAgentReviewService(
                    store,
                    drafts,
                    tasks,
                    turns,
                    turnStore,
                    queueStore,
                    completions,
                    approvals,
                    drivers,
                    models,
                    workspaces,
                    worktrees,
                    git,
                    experiments,
                    pullRequests,
                    refresh,
                    credentials,
                    observer,
                    new ReviewLocks(),
                    context,
                    preparationCleanup,
                    contextUsage,
                    CLOCK);
            when(context.prepareRun(any(AgentReview.class), any(ReviewRun.class), any(WorkspaceRepositoryContext.class), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(drivers.require(LANE)).thenReturn(driver);
            doAnswer(invocation -> {
                Consumer<AgentTask> action = invocation.getArgument(2);
                action.accept(tasks.get(invocation.getArgument(0)));
                return null;
            }).when(turns).synchronizeWorktree(eq(TASK_ID), anyLong(), any());
            doAnswer(invocation -> {
                invocation.getArgument(3, Runnable.class).run();
                return Optional.empty();
            }).when(experiments).synchronize(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
            when(driver.capabilities()).thenReturn(AgentCapabilities.none());
            when(driver.label()).thenReturn("Codex");
            when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.of("0.151.0"), "ready", AgentCapabilities.none()));
            when(models.agentModel(eq(AgentModelType.REVIEW), any())).thenReturn(new AgentModelSetting(
                    AgentModelType.REVIEW, true, "codex", "Codex", "gpt-5-codex", "GPT-5 Codex", Optional.of("medium")));
            WorkspaceRepositoryContext workspace = new WorkspaceRepositoryContext(
                    WORKSPACE,
                    REPOSITORY,
                    1,
                    RepoRef.of("owner", "repo"),
                    OptionalLong.empty(),
                    RepoRef.of("owner", "repo"),
                    "origin",
                    "origin",
                    "main",
                    ImmutableList.of());
            when(workspaces.requireReadyRepository(WORKSPACE)).thenReturn(workspace);
            when(workspaces.requireReadableRepository(WORKSPACE)).thenReturn(workspace);
            when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("token", new UserProfile("octocat", "Octo Cat", "", "", 0, 0, 0, "", "", "", "")));
            when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
            when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(HEAD));
            when(pullRequests.getPullRequestDiffFiles(REPO, NUMBER, null, null))
                    .thenReturn(ImmutableList.of(new DiffFile("src/App.java", "modified", 1, 0, "@@ -1,2 +1,3 @@\n context\n+added\n")));
            when(tasks.create(any(CreateAgentTaskCommand.class))).thenReturn(idleTask());
            when(tasks.get(TASK_ID)).thenReturn(idleTask());
            when(worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, false, false));
            when(turns.start(any(StartAgentTurnCommand.class)))
                    .thenAnswer(invocation -> acceptTurn(invocation.getArgument(0)));
            when(turns.findTurnByIdempotencyKey(eq(TASK_ID), anyString()))
                    .thenAnswer(invocation -> Optional.ofNullable(acceptedTurns.get(invocation.getArgument(1))));
            when(turns.get(any(TurnId.class))).thenAnswer(invocation -> acceptedTurns.values().stream()
                    .filter(turn -> turn.id().equals(invocation.getArgument(0))).findFirst().orElse(null));
            stubGit();
        }

        private AgentTurn acceptTurn(StartAgentTurnCommand command)
        {
            if (command == null) {
                return null;
            }
            TurnId id = acceptedTurns.isEmpty() ? TURN_ONE : acceptedTurns.size() == 1 ? TURN_TWO : TurnId.random();
            AgentTurn accepted = AgentTurn.accepted(id, TASK_ID, command.idempotencyKey(), SETTINGS, NOW);
            acceptedTurns.put(command.idempotencyKey(), accepted);
            return accepted;
        }

        private void stubGit()
        {
            when(git.run(eq(REPOSITORY), anyList())).thenReturn(ok(""));
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
        public List<ReviewRun> listRecentRuns(String workspaceId, String repo, int limit)
        {
            checkArgument(limit >= 1 && limit <= 10, "Recent run limit must be between 1 and 10");
            return runs.values().stream()
                    .filter(run -> findReview(run.reviewId())
                            .filter(review -> review.workspaceId().equals(workspaceId) && review.repo().equalsIgnoreCase(repo)).isPresent())
                    .sorted(Comparator.comparing(ReviewRun::updatedAt).reversed().thenComparing(ReviewRun::id))
                    .limit(limit)
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
            ReviewMessage next = new ReviewMessage(message.id(), message.reviewId(), message.findingId(), message.cardId(), message.author(),
                    message.body(), message.targetRevision(), message.runId(), message.turnId(), message.queuedInputId(), message.version() + 1, message.createdAt());
            messages.put(next.id(), next);
            return next;
        }

        @Override
        public Optional<ReviewMessage> findMessage(String messageId)
        {
            return Optional.ofNullable(messages.get(messageId));
        }

        @Override
        public List<ReviewMessage> listEarlierMessages(String reviewId, String runId, Optional<String> findingId, Optional<String> cardId,
                int targetRevision, Instant createdAt, String messageId, int limit)
        {
            return messages.values().stream()
                    .filter(message -> message.reviewId().equals(reviewId) && message.runId().equals(runId))
                    .filter(message -> message.findingId().equals(findingId) && message.cardId().equals(cardId))
                    .filter(message -> message.targetRevision() == targetRevision)
                    .filter(message -> message.createdAt().isBefore(createdAt) || (message.createdAt().equals(createdAt) && message.id().compareTo(messageId) < 0))
                    .sorted(Comparator.comparing(ReviewMessage::createdAt).thenComparing(ReviewMessage::id).reversed())
                    .limit(limit)
                    .collect(toImmutableList());
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
