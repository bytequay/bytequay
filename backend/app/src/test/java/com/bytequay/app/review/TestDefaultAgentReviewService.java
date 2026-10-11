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
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
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
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCandidate;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewEvidenceAnswer;
import com.bytequay.core.review.ReviewEvidenceRequest;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewPerspectiveAssessment;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.review.ReviewValidation;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.bytequay.app.review.ReviewTestData.discoveryCandidate;
import static com.bytequay.app.review.ReviewTestData.perspectives;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void developmentReviewUsesASeparateReviewTaskAndFrozenUnpushedCommits()
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource();
        localSource(fixture, source, HEAD);
        when(fixture.worktrees.inspect(source.taskWorktree().orElseThrow()))
                .thenReturn(new WorktreeSnapshot(source.taskWorktree().orElseThrow(), HEAD, true, false, true));

        when(fixture.browse.read(source.worktree(), "symbolic-ref", "--quiet", "--short", "HEAD")).thenReturn(ok("feature/configuration"));

        AgentReviewProjection result = fixture.service.startForTask(WORKSPACE, source.id().toString());
        verify(fixture.worktrees, never()).inspect(source.taskWorktree().orElseThrow());
        assertThat(result.review().sourceTaskId()).contains(source.id().toString());
        assertThat(result.review().taskId()).isEqualTo(TASK_ID.toString()).isNotEqualTo(source.id().toString());
        assertThat(result.review().number()).isEmpty();
        assertThat(result.review().pullRequestId()).isEmpty();
        assertThat(result.runs().getFirst().baseSha()).isEqualTo(BASE_SHA);
        assertThat(result.runs().getFirst().headSha()).isEqualTo(HEAD);
        assertThat(result.runs().getFirst().commits()).extracting(commit -> commit.sha()).containsExactly(HEAD);
        assertThat(fixture.service.findForTask(WORKSPACE, source.id().toString()).orElseThrow().review().id()).isEqualTo(result.review().id());
        assertThat(fixture.service.startForTask(WORKSPACE, source.id().toString()).review().id()).isEqualTo(result.review().id());
        verify(fixture.models).agentModel(AgentModelType.REVIEW, Optional.of(WORKSPACE));
        verify(fixture.tasks, times(1)).create(argThat(command -> command.startCommit().equals(HEAD) && !command.taskId().equals(source.id())));
        verify(fixture.turns).start(argThat(command -> command.taskId().equals(TASK_ID) && command.message().contains("uncommitted development edits are excluded")));
        verify(fixture.pullRequests, never()).getPullRequestDetail(anyString(), anyInt());
        verify(fixture.refresh, never()).refreshAllDetail(anyString(), anyInt());
        verify(fixture.turns, never()).synchronizeWorktree(eq(source.id()), anyLong(), any());
    }

    @Test
    void importedDevelopmentReviewUsesThePullRequestBaseRatherThanItsInitialHead()
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource(HEAD);
        localSource(fixture, source, HEAD);
        when(fixture.assignments.findByTaskId(source.id().toString()))
                .thenReturn(Optional.of(new PullRequestTask(source.id().toString(), WORKSPACE, REPO, NUMBER, "Initial PR context")));
        when(fixture.pullRequests.findPullRequestDetail(REPO, NUMBER)).thenReturn(Optional.of(detail()));
        when(fixture.browse.require(source.worktree(), "rev-parse", "--verify", "refs/remotes/origin/main^{commit}")).thenReturn(BASE_SHA);

        AgentReviewProjection result = fixture.service.startForTask(WORKSPACE, source.id().toString());

        assertThat(result.review().number()).hasValue(NUMBER);
        assertThat(result.runs().getFirst().baseRef()).isEqualTo("main");
        assertThat(result.runs().getFirst().baseSha()).isEqualTo(BASE_SHA);
        assertThat(result.runs().getFirst().commits()).hasSize(1);
        verify(fixture.refresh, never()).refreshAllDetail(anyString(), anyInt());
        verify(fixture.pullRequests, never()).getPullRequestDetail(anyString(), anyInt());
    }

    @Test
    void localReviewUpdatesReuseTheReviewerAndDoNotFollowRemoteHeads()
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource();
        localSource(fixture, source, HEAD);
        AgentReviewProjection first = fixture.service.startForTask(WORKSPACE, source.id().toString());
        ReviewRun run = fixture.store.listRuns(first.review().id()).getFirst();
        fixture.store.updateRun(run.ended(ReviewRun.State.FAILED, Optional.of("Stopped for test"), NOW));
        localSource(fixture, source, NEW_HEAD);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(ok(""));
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, false, false, false),
                new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false, false));
        when(fixture.git.run(taskWorktree().canonicalWorktree(), fastForward(NEW_HEAD))).thenReturn(ok(""));

        AgentReviewProjection updated = fixture.service.update(first.review().id());

        assertThat(updated.review().taskId()).isEqualTo(first.review().taskId());
        assertThat(updated.runs()).hasSize(2);
        assertThat(updated.runs().getLast().headSha()).isEqualTo(NEW_HEAD);
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        verify(fixture.refresh, never()).refreshAllDetail(anyString(), anyInt());
        verify(fixture.turns, never()).synchronizeWorktree(eq(source.id()), anyLong(), any());
    }

    @Test
    void localReviewRejectsUnavailableOrWrongWorkspaceSourceBeforeProvisioning()
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource();
        localSource(fixture, source, HEAD);
        when(fixture.tasks.getInteractive("other-workspace", source.id())).thenThrow(new AgentConflictException("Wrong workspace"));
        assertThatThrownBy(() -> fixture.service.startForTask("other-workspace", source.id().toString())).hasMessage("Wrong workspace");
        when(fixture.git.gitPaths(eq(source.worktree()), anyList())).thenAnswer(invocation -> {
            List<String> markers = invocation.getArgument(1);
            return markers.stream().map(ignored -> Path.of("/tmp")).collect(toImmutableList());
        });
        assertThatThrownBy(() -> fixture.service.startForTask(WORKSPACE, source.id().toString())).hasMessageContaining("open Git operation");
        verify(fixture.tasks, never()).create(any(CreateAgentTaskCommand.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedReviewInsertionDiscardsOnlyTheUnstartedReviewer(boolean development)
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource();
        localSource(fixture, source, HEAD);
        ReviewConflictException failure = new ReviewConflictException("The review row could not be stored");
        doThrow(failure).when(fixture.store).insertReview(any(AgentReview.class));

        assertThatThrownBy(() -> {
            if (development) {
                fixture.service.startForTask(WORKSPACE, source.id().toString());
            }
            else {
                fixture.service.start(WORKSPACE, REPO, NUMBER);
            }
        }).isSameAs(failure);

        verify(fixture.completions).delete(TASK_ID);
        verify(fixture.completions, never()).delete(source.id());
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));
        verify(fixture.observer, never()).watch(any(AgentReview.class));
        assertThat(fixture.store.listOpenReviews()).isEmpty();
    }

    @Test
    void reviewInsertionRollbackPreservesTheOriginalFailureWhenCleanupFails()
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource();
        localSource(fixture, source, HEAD);
        ReviewConflictException failure = new ReviewConflictException("The review row could not be stored");
        AgentConflictException cleanupFailure = new AgentConflictException("Cleanup could not verify the generated worktree");
        doThrow(failure).when(fixture.store).insertReview(any(AgentReview.class));
        doThrow(cleanupFailure).when(fixture.completions).delete(TASK_ID);

        assertThatThrownBy(() -> fixture.service.startForTask(WORKSPACE, source.id().toString())).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(cleanupFailure);
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void reviewInsertionWithAnUncertainResponseKeepsItsRecordedTask()
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource();
        localSource(fixture, source, HEAD);
        ReviewConflictException failure = new ReviewConflictException("The insertion response was interrupted");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw failure;
        }).when(fixture.store).insertReview(any(AgentReview.class));

        assertThatThrownBy(() -> fixture.service.startForTask(WORKSPACE, source.id().toString())).isSameAs(failure);
        assertThat(fixture.store.listOpenReviews()).hasSize(1);
        verify(fixture.completions, never()).delete(any(TaskId.class));
    }

    @Test
    void deletingDevelopmentSourceRetainsReviewerResultsAndRefusesFurtherUpdates()
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource();
        localSource(fixture, source, HEAD);
        AgentReviewProjection original = fixture.service.startForTask(WORKSPACE, source.id().toString());
        when(fixture.tasks.getInteractive(WORKSPACE, source.id())).thenThrow(new AgentTaskNotFoundException(source.id()));

        assertThat(fixture.service.get(original.review().id()).runs()).hasSize(1);
        assertThat(fixture.service.get(original.review().id()).review().sourceTaskId()).contains(source.id().toString());
        assertThatThrownBy(() -> fixture.service.update(original.review().id())).isInstanceOf(ReviewConflictException.class)
                .hasMessage("The development task was deleted; retained review results remain available");
        assertThat(fixture.store.listRuns(original.review().id())).hasSize(1);
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
    }

    @Test
    void explicitLocalUpdateAdoptsANewlyCreatedPullRequestWithoutReplacingReviewer()
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource();
        localSource(fixture, source, HEAD);
        AgentReviewProjection original = fixture.service.startForTask(WORKSPACE, source.id().toString());
        when(fixture.assignments.findByTaskId(source.id().toString()))
                .thenReturn(Optional.of(new PullRequestTask(source.id().toString(), WORKSPACE, REPO, NUMBER, "Initial PR context")));
        when(fixture.pullRequests.findPullRequestDetail(REPO, NUMBER)).thenReturn(Optional.of(detail()));
        when(fixture.browse.require(source.worktree(), "rev-parse", "--verify", "refs/remotes/origin/main^{commit}")).thenReturn(BASE_SHA);

        AgentReviewProjection updated = fixture.service.update(original.review().id());

        assertThat(updated.review().number()).hasValue(NUMBER);
        assertThat(updated.review().pullRequestId()).hasValue(detail().id());
        assertThat(updated.review().sourceTaskId()).contains(source.id().toString());
        assertThat(updated.review().taskId()).isEqualTo(original.review().taskId());
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        verify(fixture.refresh, never()).refreshAllDetail(anyString(), anyInt());
    }

    private static AgentTask developmentSource()
    {
        return developmentSource(BASE_SHA);
    }

    private static AgentTask developmentSource(String startCommit)
    {
        TaskId id = TaskId.valueOf("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        TaskWorktree worktree = new TaskWorktree(id, WORKSPACE, new RepositoryIdentity("owner", "repo"), Path.of("/tmp/bytequay/worktrees").resolve(id.toString()),
                "bytequay/task/" + id, startCommit);
        return AgentTask.created(id, true, "Handle missing configuration", TitleSource.USER, LANE, worktree, DEFAULTS, NOW);
    }

    private static void localSource(Fixture fixture, AgentTask source, String head)
    {
        when(fixture.tasks.getInteractive(WORKSPACE, source.id())).thenReturn(source);
        when(fixture.worktrees.ownedCheckout(source.taskWorktree().orElseThrow())).thenReturn(source.worktree());
        when(fixture.browse.require(source.worktree(), "rev-parse", "--verify", "HEAD^{commit}")).thenReturn(head);
        when(fixture.git.gitPaths(eq(source.worktree()), anyList())).thenAnswer(invocation -> {
            List<String> markers = invocation.getArgument(1);
            return markers.stream().map(marker -> source.worktree().resolve(marker)).collect(toImmutableList());
        });
        when(fixture.worktrees.inspect(source.taskWorktree().orElseThrow()))
                .thenReturn(new WorktreeSnapshot(source.taskWorktree().orElseThrow(), head, false, false, false));
        when(fixture.browse.read(source.worktree(), "symbolic-ref", "--quiet", "--short", "HEAD")).thenReturn(ok(source.taskWorktree().orElseThrow().generatedBranch()));
        when(fixture.browse.require(source.worktree(), "merge-base", head, BASE_SHA)).thenReturn(MERGE_BASE);
        when(fixture.browse.history(source.worktree(), MERGE_BASE, head))
                .thenReturn(new WorktreeDiffReader.History(ImmutableList.of(new DiffCommit(head, BASE_SHA, "Handle missing configuration", "Developer", NOW)), false));
        when(fixture.browse.diff(source.worktree(), MERGE_BASE, Optional.of(head)))
                .thenReturn(new WorktreeDiffReader.Diff(ImmutableList.of(new DiffFile("src/App.java", "modified", 1, 0, "@@ -1 +1,2 @@\n context\n+added\n")), false));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startCreatesOneTaskAndOneRunningRunDrivenByAByteQuayRequest(boolean children)
    {
        Fixture fixture = new Fixture();
        when(fixture.driver.capabilities()).thenReturn(new AgentCapabilities(
                true,
                true,
                true,
                ImmutableSet.of(AgentPermissionPolicy.AUTO),
                ImmutableSet.of("text/plain")));
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
                .contains("review_get_context", "assignment and scope", "assignment.guidance")
                .hasSizeLessThan(600);
        assertThat(started.getValue().idempotencyKey()).startsWith("review-step:");
        verify(fixture.observer, times(1)).watch(any(AgentReview.class));
    }

    @Test
    void listingReviewsDoesNotInspectEveryWorkingCopy()
    {
        Fixture fixture = new Fixture();
        AgentReviewProjection started = fixture.service.start(WORKSPACE, REPO, NUMBER);
        clearInvocations(fixture.worktrees, fixture.git, fixture.experiments);

        AgentReviewProjection listed = fixture.service.list(WORKSPACE).getFirst();

        assertThat(listed.review()).isEqualTo(started.review());
        assertThat(listed.runs()).isEqualTo(started.runs());
        assertThat(listed.task()).isEqualTo(started.task());
        assertThat(listed.worktreeSync()).isEmpty();
        verifyNoInteractions(fixture.worktrees, fixture.git, fixture.experiments);

        assertThat(fixture.service.get(started.review().id()).worktreeSync()).isPresent();
        verify(fixture.worktrees).ownedCheckout(taskWorktree());
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
    void explorationPreparesOnceWithoutAProviderAndLaterReviewReusesItsTask()
    {
        Fixture fixture = new Fixture();
        when(fixture.tasks.prepare(any(CreateAgentTaskCommand.class))).thenReturn(idleTask());
        when(fixture.driver.probe()).thenThrow(new AssertionError("Exploration must not probe the agent provider"));

        AgentReviewProjection prepared = fixture.service.prepare(WORKSPACE, REPO, NUMBER);
        String reviewId = prepared.review().id();
        assertThat(prepared.review().followUpPaused()).isTrue();
        assertThat(prepared.review().worktreeHeadSha()).contains(HEAD);
        assertThat(prepared.runs()).isEmpty();
        assertThat(fixture.service.prepare(WORKSPACE, "Owner/Repo", NUMBER).review().id()).isEqualTo(reviewId);
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.listRuns(reviewId)).isEmpty();
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));

        AgentReviewProjection started = fixture.service.start(WORKSPACE, REPO, NUMBER);
        assertThat(started.review().id()).isEqualTo(reviewId);
        assertThat(started.review().taskId()).isEqualTo(prepared.review().taskId());
        assertThat(started.review().followUpPaused()).isFalse();
        assertThat(started.runs()).hasSize(1);
        assertThat(fixture.service.start(WORKSPACE, REPO, NUMBER).runs()).hasSize(1);
        verify(fixture.tasks, times(1)).prepare(argThat(command -> command.startCommit().equals(HEAD)));
        verify(fixture.tasks, never()).create(any(CreateAgentTaskCommand.class));
        verify(fixture.turns, times(1)).start(argThat(command -> command.taskId().equals(TASK_ID)));
    }

    @Test
    void explorationRejectsAPullRequestOutsideTheWatchedRepository()
    {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.prepare(WORKSPACE, "someone/else", NUMBER)).hasMessageContaining("does not belong to this workspace");

        verify(fixture.tasks, never()).prepare(any(CreateAgentTaskCommand.class));
        verify(fixture.tasks, never()).create(any(CreateAgentTaskCommand.class));
        verify(fixture.git, never()).run(any(Path.class), anyList());
    }

    @Test
    void aReplayReviewsItsPastHeadOutsideThePullRequestsReviewsAndEndsWithItsRun()
    {
        Fixture fixture = new Fixture();
        when(fixture.browse.history(REPOSITORY, MERGE_BASE, HEAD)).thenReturn(new WorktreeDiffReader.History(ImmutableList.of(
                new DiffCommit(HEAD, MERGE_BASE, "Fix the parser", "Alice", NOW)), false));
        when(fixture.browse.diff(REPOSITORY, MERGE_BASE, Optional.of(HEAD))).thenReturn(new WorktreeDiffReader.Diff(ImmutableList.of(
                new DiffFile("src/App.java", "modified", 1, 0, "@@ -1 +1,2 @@")), false));
        when(fixture.pullRequests.findPullRequestDetail(REPO, NUMBER)).thenReturn(Optional.of(detail()));
        AgentReview.Replay replay = new AgentReview.Replay("replay-1", NUMBER, true, NOW);

        AgentReviewProjection started = fixture.service.startReplay(WORKSPACE, new AgentReviewService.ReplayStart(replay, HEAD, "main", MERGE_BASE));

        assertThat(started.review().replay()).contains(replay);
        assertThat(started.review().number()).isEmpty();
        assertThat(started.runs().getFirst().headSha()).isEqualTo(HEAD);
        assertThat(started.runs().getFirst().baseSha()).isEqualTo(MERGE_BASE);
        assertThat(started.runs().getFirst().diff()).extracting(DiffFile::filename).containsExactly("src/App.java");
        ArgumentCaptor<CreateAgentTaskCommand> created = ArgumentCaptor.forClass(CreateAgentTaskCommand.class);
        verify(fixture.tasks).create(created.capture());
        assertThat(created.getValue().title()).isEqualTo("Replay review #" + NUMBER + " · with memory");
        assertThat(created.getValue().startCommit()).isEqualTo(HEAD);
        ArgumentCaptor<StartAgentTurnCommand> turn = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(fixture.turns).start(turn.capture());
        assertThat(turn.getValue().message()).startsWith("Review a past change in " + REPO + " at frozen head").contains("Replay cutoff", "origin", "and do not fetch.")
                .doesNotContain("Pull request title: Fix the parser", REPO + "#" + NUMBER);
        assertThat(fixture.service.list(WORKSPACE)).isEmpty();
        assertThat(fixture.service.find(WORKSPACE, REPO, NUMBER)).isEmpty();

        String reviewId = started.review().id();
        fixture.store.updateRun(fixture.store.listRuns(reviewId).getLast().ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW));
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().state()).isEqualTo(AgentReview.State.CLOSED);
        verify(fixture.completions).cleanupWorktree(TASK_ID);
    }

    @Test
    void projectionKeepsValidationEvidenceOutsideTheInitialContextSelection()
    {
        Fixture fixture = new Fixture();
        ReviewRun run = fixture.store.findRun(fixture.service.start(WORKSPACE, REPO, NUMBER).runs().getFirst().id()).orElseThrow();
        ReviewEvidence context = new ReviewEvidence("E1", "src/App.java", "frozen head", "parse(input)", "");
        ReviewEvidence validation = new ReviewEvidence("E2", "command output: regression test", "validation turn", "HEAD passed; old condition failed", "");
        ReviewInvestigation investigation = new ReviewInvestigation("Parser behavior", ImmutableList.of("src/App.java"),
                ImmutableList.of("E1"), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
        fixture.store.updateRun(run.withInvestigation(investigation, NOW).withEvidence("E1", context, NOW).withEvidence("E2", validation, NOW));

        assertThat(fixture.service.get(run.reviewId()).runs().getFirst().evidence().values()).containsExactly(context, validation);
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
        when(fixture.turns.findTurnByIdempotencyKey(eq(TASK_ID), anyString())).thenThrow(new AgentConflictException("Assignment unavailable"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.start(WORKSPACE, REPO, NUMBER))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("Assignment unavailable");

        AgentReview review = fixture.store.findOpenReview(WORKSPACE, REPO, NUMBER).orElseThrow();
        ReviewRun failed = fixture.store.listRuns(review.id()).getLast();
        assertThat(failed.state()).isEqualTo(ReviewRun.State.FAILED);
        assertThat(failed.outcome()).contains("Cannot start the review turn: Assignment unavailable");
        assertThat(failed.turnIds()).isEmpty();
        assertThat(review.followUpPaused()).isTrue();
        verify(fixture.turns, never()).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void reconcileDispatchesAPersistedStartupAssignmentExactlyOnce()
    {
        Fixture fixture = new Fixture();
        when(fixture.turns.findTurnByIdempotencyKey(eq(TASK_ID), anyString())).thenThrow(new NoClassDefFoundError("Startup class unavailable"))
                .thenAnswer(invocation -> Optional.ofNullable(fixture.acceptedTurns.get(invocation.getArgument(1))));
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
    void acceptedHistoricalTurnsAndLaterAssignmentsKeepTheirOriginalContext()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        fixture.service.reconcile(reviewId);
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());

        assertThat(validating.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.VALIDATE);
        verify(fixture.workspaces, never()).requireReadableRepository(anyString());
    }

    @Test
    void resumeContinuesAPersistedStartupOnTheSameReviewAndTask()
    {
        Fixture fixture = new Fixture();
        when(fixture.turns.findTurnByIdempotencyKey(eq(TASK_ID), anyString())).thenThrow(new NoClassDefFoundError("Startup class unavailable"))
                .thenAnswer(invocation -> Optional.ofNullable(fixture.acceptedTurns.get(invocation.getArgument(1))));
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
        completeCleanReview(fixture, validating);
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
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch continueStartup = new CountDownLatch(1);
        CountDownLatch reconciling = new CountDownLatch(1);
        when(fixture.turns.findTurnByIdempotencyKey(eq(TASK_ID), anyString())).thenAnswer(invocation -> {
            preparing.countDown();
            assertThat(continueStartup.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.ofNullable(fixture.acceptedTurns.get(invocation.getArgument(1)));
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
                original.steps(), false, original.operations(), original.evidence(), Optional.empty(), 0, NOW, NOW, Optional.empty()));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void questionCardIdentityIsReadableBeforeDispatchReturns(boolean queued)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        EventId eventId = EventId.random();
        Runnable assertAccepted = () -> {
            ReviewMessage question = fixture.store.listMessages(reviewId).getFirst();
            assertThat(question.turnId()).isEmpty();
            assertThat(question.queuedInputId()).isEmpty();
            String key = "review-ask:" + question.id();
            if (queued) {
                when(fixture.queueStore.findQueuedByIdempotencyKey(TASK_ID, key)).thenReturn(Optional.of(queuedInput().starting(TURN_TWO, eventId, NOW)));
            }
            else {
                when(fixture.turnStore.findByIdempotencyKey(TASK_ID, key)).thenReturn(Optional.of(AgentTurn.accepted(TURN_TWO, TASK_ID, key, SETTINGS, NOW)));
            }
            AgentReviewProjection.MessageView view = fixture.service.get(reviewId).messages().getFirst();
            assertThat(view.turnId()).contains(TURN_TWO.toString());
            assertThat(view.message().body()).isEqualTo("Check the upstream behavior");
            assertThat(view.messageEventId()).isEqualTo(queued ? Optional.of(eventId.toString()) : Optional.empty());
        };
        if (queued) {
            when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
            when(fixture.turns.enqueue(any())).thenAnswer(invocation -> {
                assertAccepted.run();
                return queuedInput();
            });
        }
        else {
            when(fixture.turns.start(any())).thenAnswer(invocation -> {
                assertAccepted.run();
                StartAgentTurnCommand command = invocation.getArgument(0);
                return AgentTurn.accepted(TURN_TWO, TASK_ID, command.idempotencyKey(), SETTINGS, NOW);
            });
        }
        fixture.service.ask(reviewId, Optional.empty(), Optional.empty(), 1, "Check the upstream behavior");
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
    void questionNothingAcceptedIsRemovedWhenAdmissionFails(boolean queued)
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

        // No answer can come, so the question does not stay looking sent; the caller shows the error and keeps the text to ask again.
        assertThat(fixture.store.listMessages(reviewId)).isEmpty();
        assertThat(fixture.service.get(reviewId).messages()).isEmpty();
        if (queued) {
            verify(fixture.turns).findQueuedByIdempotencyKey(eq(TASK_ID), startsWith("review-ask:"));
        }
        else {
            verify(fixture.turns).findTurnByIdempotencyKey(eq(TASK_ID), startsWith("review-ask:"));
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
    void askRefusesAStaleRevision()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        String runId = fixture.store.listRuns(reviewId).getLast().id();
        ReviewFinding original = finding("finding-1", reviewId, runId, 1, ReviewFinding.State.OPEN);
        ReviewFinding revised = fixture.store.insertFinding(
                original.revised("Reset the parser again", "Body", Optional.of("major"), original.anchor(), ImmutableList.of(), HEAD, NOW));

        assertThat(revised.revision()).isEqualTo(2);
        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.of(revised.id()), Optional.empty(), 1, "Still true?"))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("revised to revision 2");
        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.empty(), Optional.empty(), 4, "Still true?"))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("The summary was revised to revision 1");

        assertThat(fixture.store.listMessages(reviewId)).isEmpty();
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void askContinuesAWithdrawnFindingDiscussion(boolean queued)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        String runId = fixture.store.listRuns(reviewId).getLast().id();
        ReviewFinding withdrawn = fixture.store.insertFinding(finding("finding-1", reviewId, runId, 1, ReviewFinding.State.WITHDRAWN));
        if (queued) {
            when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
            when(fixture.turns.enqueue(any(QueueAgentInputCommand.class))).thenReturn(queuedInput());
        }

        ReviewMessage first = fixture.service.ask(reviewId, Optional.of(withdrawn.id()), Optional.empty(), withdrawn.revision(), "Why was this withdrawn?");
        ReviewMessage second = fixture.service.ask(reviewId, Optional.of(withdrawn.id()), Optional.empty(), withdrawn.revision(), "Which check confirmed the fix?");

        assertThat(fixture.store.listMessages(reviewId)).containsExactly(first, second);
        assertThat(second.findingId()).contains(withdrawn.id());
        assertThat(second.targetRevision()).isEqualTo(withdrawn.revision());
        assertThat(second.runId()).isEqualTo(runId);
        assertThat(second.turnId().isPresent()).isEqualTo(!queued);
        assertThat(second.queuedInputId()).isEqualTo(queued ? Optional.of(QUEUED_INPUT.toString()) : Optional.empty());
        assertThat(fixture.store.findFinding(withdrawn.id())).contains(withdrawn);
        verify(fixture.turns, times(queued ? 1 : 3)).start(any(StartAgentTurnCommand.class));
        verify(fixture.turns, times(queued ? 2 : 0)).enqueue(any(QueueAgentInputCommand.class));

        fixture.store.updateReview(fixture.store.findReview(reviewId).orElseThrow().closed(NOW));
        assertThatThrownBy(() -> fixture.service.ask(reviewId, Optional.of(withdrawn.id()), Optional.empty(), withdrawn.revision(), "One more question"))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("The review has ended");
        assertThat(fixture.store.listMessages(reviewId)).containsExactly(first, second);
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

    @Test
    void developerResolvesWhatItFixedAndCannotUndoTheUsersResolution()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        String runId = fixture.store.listRuns(reviewId).getLast().id();
        ReviewFinding stored = fixture.store.insertFinding(finding("finding-1", reviewId, runId, 1, ReviewFinding.State.OPEN));

        ReviewFinding resolved = fixture.service.resolveFromDeveloper(reviewId, stored.id(), "developer-task", true);
        assertThat(resolved.state()).isEqualTo(ReviewFinding.State.RESOLVED);
        assertThat(resolved.resolvedByTaskId()).contains("developer-task");
        assertThat(resolved.stateReason()).isEmpty();
        assertThat(resolved.version()).isEqualTo(stored.version() + 1);
        // Already resolved: a repeat, also from another task, changes nothing.
        assertThat(fixture.service.resolveFromDeveloper(reviewId, stored.id(), "another-task", true)).isEqualTo(resolved);

        ReviewFinding reopened = fixture.service.resolveFromDeveloper(reviewId, stored.id(), "developer-task", false);
        assertThat(reopened.state()).isEqualTo(ReviewFinding.State.OPEN);
        assertThat(reopened.resolvedByTaskId()).isEmpty();
        assertThat(fixture.service.resolveFromDeveloper(reviewId, stored.id(), "developer-task", false)).isEqualTo(reopened);

        // The user's own resolution keeps its reason and is not the agent's to reopen.
        ReviewFinding mine = fixture.service.resolveFinding(reviewId, stored.id(), reopened.version(), "Handled elsewhere");
        assertThat(fixture.service.resolveFromDeveloper(reviewId, stored.id(), "developer-task", true)).isEqualTo(mine);
        assertThat(mine.resolvedByTaskId()).isEmpty();
        assertThatThrownBy(() -> fixture.service.resolveFromDeveloper(reviewId, stored.id(), "developer-task", false))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("only the user reopens");
        assertThat(fixture.store.findFinding(stored.id()).orElseThrow()).isEqualTo(mine);

        // The user reopens what a development agent resolved, and the finding then names no task.
        fixture.service.reopenFinding(reviewId, stored.id(), mine.version());
        ReviewFinding byDeveloper = fixture.service.resolveFromDeveloper(reviewId, stored.id(), "developer-task", true);
        assertThat(byDeveloper.resolvedByTaskId()).contains("developer-task");
        ReviewFinding byUser = fixture.service.reopenFinding(reviewId, stored.id(), byDeveloper.version());
        assertThat(byUser.state()).isEqualTo(ReviewFinding.State.OPEN);
        assertThat(byUser.resolvedByTaskId()).isEmpty();

        ReviewFinding withdrawn = fixture.store.insertFinding(finding("finding-2", reviewId, runId, 2, ReviewFinding.State.WITHDRAWN));
        assertThatThrownBy(() -> fixture.service.resolveFromDeveloper(reviewId, withdrawn.id(), "developer-task", true))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("withdrew");
        assertThatThrownBy(() -> fixture.service.resolveFromDeveloper(reviewId, "missing", "developer-task", true)).isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void developerReplyIsRecordedWithoutAskingTheReviewerOrClosingTheFinding()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        String runId = fixture.store.listRuns(reviewId).getLast().id();
        ReviewFinding stored = fixture.store.insertFinding(finding("finding-1", reviewId, runId, 1, ReviewFinding.State.OPEN));

        ReviewMessage reply = fixture.service.replyFromDeveloper(reviewId, stored.id(), "developer-task", "reply-1", "  Fixed in abc1234  ");
        assertThat(reply.author()).isEqualTo(ReviewMessage.Author.AGENT);
        assertThat(reply.developerTaskId()).contains("developer-task");
        assertThat(reply.findingId()).contains(stored.id());
        assertThat(reply.body()).isEqualTo("Fixed in abc1234");
        assertThat(reply.targetRevision()).isEqualTo(stored.revision());
        assertThat(reply.turnId()).isEmpty();
        assertThat(reply.queuedInputId()).isEmpty();

        assertThat(fixture.service.replyFromDeveloper(reviewId, stored.id(), "developer-task", "reply-1", "Fixed in abc1234")).isEqualTo(reply);
        assertThat(fixture.store.listMessages(reviewId)).containsExactly(reply);
        assertThatThrownBy(() -> fixture.service.replyFromDeveloper(reviewId, stored.id(), "developer-task", "reply-1", "Rejected instead"))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("reused");
        assertThatThrownBy(() -> fixture.service.replyFromDeveloper(reviewId, stored.id(), "another-task", "reply-1", "Fixed in abc1234"))
                .isInstanceOf(ReviewConflictException.class);
        assertThatThrownBy(() -> fixture.service.replyFromDeveloper(reviewId, "missing", "developer-task", "reply-2", "Fixed"))
                .isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> fixture.service.replyFromDeveloper(reviewId, stored.id(), "developer-task", "reply-3", "  "))
                .isInstanceOf(ReviewInvalidRequestException.class);

        // Only the review's own start asked for a turn: a developer's reply is a record, not a question to the reviewer.
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));
        verify(fixture.turns, never()).enqueue(any(QueueAgentInputCommand.class));
        assertThat(fixture.store.findFinding(stored.id()).orElseThrow()).isEqualTo(stored);
        assertThat(fixture.service.get(reviewId).messages().getFirst().message()).isEqualTo(reply);

        // An ended review keeps its findings on screen, so the reply is still recorded there.
        fixture.service.end(reviewId);
        assertThat(fixture.service.replyFromDeveloper(reviewId, stored.id(), "developer-task", "reply-4", "Rejected: the caller already checks this").developerTaskId())
                .contains("developer-task");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroFindingsCompleteWithoutAnotherDiscoveryRoundAfterCleanup(boolean paused)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getLast());
        completeValidation(fixture, validating);
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.recovery("cleanup", NOW), NOW));
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.findRun(validating.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.RUNNING);
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask());
        if (paused) {
            fixture.service.stop(reviewId);
        }
        fixture.service.reconcile(reviewId);
        fixture.service.reconcile(reviewId);

        ReviewRun accepted = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(accepted.state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(accepted.steps()).extracting(ReviewStep::kind).containsExactly(ReviewStep.Kind.INVESTIGATE, ReviewStep.Kind.VALIDATE);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(validating.id());
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
        verify(fixture.tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        verify(fixture.turns, never()).synchronizeWorktree(any(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reconcileAcceptsACompletedRunWithASummaryAndCoverage(boolean paused)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewRun validating = beginValidation(fixture, run);
        completeCleanReview(fixture, validating);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterReviewsRetainTheFirstExplanationAndAdvanceTheirOwnCoverageBaseline(boolean firstReviewAccepted)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun initial = fixture.store.listRuns(reviewId).getLast();
        if (firstReviewAccepted) {
            completeCleanReview(fixture, beginValidation(fixture, initial));
            fixture.service.reconcile(reviewId);
        }
        else {
            fixture.store.updateRun(initial.withSummary(summary(), NOW).ended(ReviewRun.State.FAILED, Optional.of("Investigation interrupted"), NOW));
            when(fixture.turns.get(TURN_ONE)).thenReturn(settledTurn(TURN_ONE, TurnState.FAILED, Optional.of("Investigation interrupted")));
        }
        ReviewRun explanationOwner = fixture.store.findRun(initial.id()).orElseThrow();
        ReviewSummary explanation = explanationOwner.summary().orElseThrow();

        pushHead(fixture, NEW_HEAD);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(ok(""));
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false, false));
        fixture.service.update(reviewId);
        ReviewRun second = fixture.store.listRuns(reviewId).getLast();
        assertThat(second.scope()).isEqualTo(firstReviewAccepted ? ReviewRun.Scope.INCREMENTAL : ReviewRun.Scope.FULL);
        assertThat(second.summary()).isEmpty();
        assertThat(second.investigation()).isEmpty();
        assertThat(second.commits()).extracting(ReviewCommit::sha).containsExactly(NEW_HEAD);

        ReviewEvidence evidence = new ReviewEvidence("E1", "src/App.java", "Source at " + NEW_HEAD, "parser.reset();", "Current parser lifecycle");
        ReviewInvestigation investigation = new ReviewInvestigation("The updated parser resets before dispatching each new input", ImmutableList.of("src/App.java"),
                ImmutableList.of("E1"), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
        fixture.store.updateRun(sealInvestigation(second.withEvidence("E1", evidence, NOW).withInvestigation(investigation, NOW)));
        settleCurrentTurn(fixture, second);
        fixture.service.reconcile(reviewId);
        ReviewRun validating = fixture.store.findRun(second.id()).orElseThrow();
        assertThat(validating.validationStarted()).isTrue();
        assertThat(validating.summary()).isEmpty();
        assertThat(validating.investigation()).contains(investigation);

        completeCleanReview(fixture, validating);
        fixture.service.reconcile(reviewId);
        ReviewRun accepted = fixture.store.findRun(second.id()).orElseThrow();
        assertThat(accepted.state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(accepted.summary()).isEmpty();
        assertThat(accepted.investigation()).contains(investigation);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(second.id());
        assertThat(fixture.store.findRun(initial.id())).contains(explanationOwner);
        assertThat(explanation.commits()).extracting(ReviewSummary.CommitExplanation::sha).containsExactly(HEAD);

        String thirdHead = "c".repeat(40);
        pushHead(fixture, thirdHead);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", NEW_HEAD, thirdHead)).thenReturn(ok(""));
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), thirdHead, false, false, false));
        fixture.service.update(reviewId);

        ReviewRun third = fixture.store.listRuns(reviewId).getLast();
        assertThat(third.scope()).isEqualTo(ReviewRun.Scope.INCREMENTAL);
        assertThat(third.priorHeadSha()).contains(NEW_HEAD);
        assertThat(third.headSha()).isEqualTo(thirdHead);
        assertThat(third.summary()).isEmpty();
        assertThat(third.investigation()).isEmpty();
        assertThat(fixture.store.findRun(initial.id())).contains(explanationOwner);
        assertThat(fixture.store.findRun(second.id()).orElseThrow().summary()).isEmpty();
    }

    @Test
    void explicitExplanationRefreshUsesTheNewHeadWithoutReviewingItsFindings()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun initial = fixture.store.listRuns(reviewId).getLast();
        completeCleanReview(fixture, beginValidation(fixture, initial));
        fixture.service.reconcile(reviewId);
        ReviewRun original = fixture.store.findRun(initial.id()).orElseThrow();
        ReviewFinding finding = fixture.store.insertFinding(finding("finding-1", reviewId, original.id(), 1, ReviewFinding.State.OPEN));
        pushHead(fixture, NEW_HEAD);
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false, false));

        AgentReviewProjection refreshed = fixture.service.refreshExplanation(reviewId, original.id(), original.summary().orElseThrow().revision());

        ReviewRun explaining = refreshed.runs().getLast();
        assertThat(explaining.explanationOnly()).isTrue();
        assertThat(explaining.headSha()).isEqualTo(NEW_HEAD);
        assertThat(explaining.commits()).extracting(ReviewCommit::sha).containsExactly(NEW_HEAD);
        assertThat(explaining.steps()).hasSize(1);
        assertThat(explaining.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.EXPLAIN);
        assertThat(explaining.investigation()).isEmpty();
        assertThat(explaining.coverage()).isEmpty();
        assertThat(ReviewCards.explanationRun(refreshed.runs())).contains(original);
        assertThat(refreshed.findings()).containsExactly(finding);
        assertThat(refreshed.review().latestCompletedRunId()).contains(original.id());

        ReviewSummary updated = new ReviewSummary(1, "Explain the updated parser", ImmutableList.of(new ReviewSummary.CommitExplanation(NEW_HEAD, "Reset before dispatch")),
                "", Optional.empty(), ImmutableList.of());
        fixture.store.updateRun(explaining.withSummary(updated, NOW));
        settleCurrentTurn(fixture, explaining);
        TurnId refreshTurn = TurnId.valueOf(explaining.turnIds().getLast());
        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.turn(refreshTurn, NOW), NOW));
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.findRun(explaining.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(ReviewCards.explanationRun(fixture.store.listRuns(reviewId))).contains(original);

        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask());
        fixture.service.reconcile(reviewId);
        ReviewRun accepted = fixture.store.findRun(explaining.id()).orElseThrow();
        assertThat(accepted.state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(accepted.summary()).contains(updated);
        assertThat(accepted.investigation()).isEmpty();
        assertThat(accepted.coverage()).isEmpty();
        assertThat(accepted.steps()).hasSize(1);
        assertThat(ReviewCards.explanationRun(fixture.store.listRuns(reviewId))).contains(accepted);
        assertThat(fixture.store.findRun(original.id())).contains(original);
        assertThat(fixture.store.listFindings(reviewId)).containsExactly(finding);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(original.id());
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));

        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(ok(""));
        fixture.service.update(reviewId);
        ReviewRun reviewing = fixture.store.listRuns(reviewId).getLast();
        assertThat(reviewing.explanationOnly()).isFalse();
        assertThat(reviewing.headSha()).isEqualTo(NEW_HEAD);
        assertThat(reviewing.scope()).isEqualTo(ReviewRun.Scope.INCREMENTAL);
        assertThat(reviewing.priorHeadSha()).contains(HEAD);
        assertThat(reviewing.summary()).isEmpty();
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(original.id());
    }

    @Test
    void explanationRefreshRepairsOnlyItsOwnMissingReportAndRetainsThePreviousExplanation()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun initial = fixture.store.listRuns(reviewId).getLast();
        completeCleanReview(fixture, beginValidation(fixture, initial));
        fixture.service.reconcile(reviewId);
        ReviewRun original = fixture.store.findRun(initial.id()).orElseThrow();
        fixture.service.refreshExplanation(reviewId, original.id(), 1);
        ReviewRun explaining = fixture.store.listRuns(reviewId).getLast();
        settleCurrentTurn(fixture, explaining);

        fixture.service.reconcile(reviewId);

        ReviewRun repairing = fixture.store.findRun(explaining.id()).orElseThrow();
        assertThat(repairing.explanationOnly()).isTrue();
        assertThat(repairing.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.EXPLAIN);
        assertThat(repairing.currentStep().orElseThrow().repair()).isTrue();
        assertThat(repairing.steps()).hasSize(1);
        assertThat(ReviewCards.explanationRun(fixture.store.listRuns(reviewId))).contains(original);
        settleCurrentTurn(fixture, repairing);
        fixture.service.reconcile(reviewId);
        ReviewRun incomplete = fixture.store.findRun(explaining.id()).orElseThrow();
        assertThat(incomplete.state()).isEqualTo(ReviewRun.State.INCOMPLETE);
        assertThat(incomplete.outcome()).contains("The agent stopped before saving a complete change explanation");
        assertThat(incomplete.coverage()).isEmpty();
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(original.id());
        assertThat(ReviewCards.explanationRun(fixture.store.listRuns(reviewId))).contains(original);
        verify(fixture.turns, times(4)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void explanationRefreshRejectsStaleDisplayedCardsAndConcurrentAssignments()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun initial = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(initial.withSummary(summary(), NOW));
        assertThatThrownBy(() -> fixture.service.refreshExplanation(reviewId, initial.id(), 1))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("working");
        completeCleanReview(fixture, beginValidation(fixture, fixture.store.findRun(initial.id()).orElseThrow()));
        fixture.service.reconcile(reviewId);

        assertThatThrownBy(() -> fixture.service.refreshExplanation(reviewId, initial.id(), 2))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("revised");
        assertThatThrownBy(() -> fixture.service.refreshExplanation(reviewId, "another-run", 1))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("revised");
        when(fixture.turns.queued(TASK_ID)).thenReturn(ImmutableList.of(queuedInput()));
        assertThatThrownBy(() -> fixture.service.refreshExplanation(reviewId, initial.id(), 1))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("queued");
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        verify(fixture.refresh, never()).refreshAllDetail(REPO, NUMBER);
    }

    @Test
    void findingQuestionsAfterExplanationRefreshKeepTheFindingsScope()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun initial = fixture.store.listRuns(reviewId).getLast();
        completeCleanReview(fixture, beginValidation(fixture, initial));
        fixture.service.reconcile(reviewId);
        ReviewFinding finding = fixture.store.insertFinding(finding("finding-1", reviewId, initial.id(), 1, ReviewFinding.State.OPEN));
        fixture.service.refreshExplanation(reviewId, initial.id(), 1);
        ReviewRun explaining = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(explaining.withSummary(summary(), NOW));
        settleCurrentTurn(fixture, explaining);
        fixture.service.reconcile(reviewId);

        ReviewMessage question = fixture.service.ask(reviewId, Optional.of(finding.id()), Optional.empty(), finding.revision(), "Is this still an issue?");

        assertThat(question.runId()).isEqualTo(initial.id());
        assertThat(question.findingId()).contains(finding.id());
    }

    @ParameterizedTest
    @EnumSource(value = TurnState.class, names = {"CANCELLED", "FAILED"})
    void unsuccessfulExplanationRefreshDoesNotReplaceEvenACompleteDraft(TurnState state)
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun initial = fixture.store.listRuns(reviewId).getLast();
        completeCleanReview(fixture, beginValidation(fixture, initial));
        fixture.service.reconcile(reviewId);
        ReviewRun original = fixture.store.findRun(initial.id()).orElseThrow();
        fixture.service.refreshExplanation(reviewId, original.id(), 1);
        ReviewRun explaining = fixture.store.listRuns(reviewId).getLast();
        fixture.store.updateRun(explaining.withSummary(summary(), NOW));
        TurnId turnId = TurnId.valueOf(explaining.turnIds().getLast());
        when(fixture.turns.get(turnId)).thenReturn(settledTurn(turnId, state, Optional.of("Explanation interrupted")));

        fixture.service.reconcile(reviewId);

        ReviewRun failed = fixture.store.findRun(explaining.id()).orElseThrow();
        assertThat(failed.state()).isEqualTo(state == TurnState.CANCELLED ? ReviewRun.State.STOPPED : ReviewRun.State.FAILED);
        assertThat(ReviewCards.explanationRun(fixture.store.listRuns(reviewId))).contains(original);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(original.id());
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void explanationRefreshRecoversAnAcceptedTurnWhoseAssociationWasNotSaved()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun initial = fixture.store.listRuns(reviewId).getLast();
        completeCleanReview(fixture, beginValidation(fixture, initial));
        fixture.service.reconcile(reviewId);
        doThrow(new IllegalStateException("Refresh association failed"))
                .when(fixture.store).updateRun(argThat(run -> run.explanationOnly() && !run.turnIds().isEmpty()));

        assertThatThrownBy(() -> fixture.service.refreshExplanation(reviewId, initial.id(), 1)).hasMessage("Refresh association failed");
        ReviewRun explaining = fixture.store.listRuns(reviewId).getLast();
        assertThat(explaining.active()).isTrue();
        assertThat(explaining.turnIds()).isEmpty();
        doAnswer(invocation -> invocation.callRealMethod()).when(fixture.store).updateRun(any(ReviewRun.class));
        fixture.service.reconcile(reviewId);

        ReviewRun attached = fixture.store.findRun(explaining.id()).orElseThrow();
        assertThat(attached.turnIds()).hasSize(1);
        fixture.store.updateRun(attached.withSummary(summary(), NOW));
        settleCurrentTurn(fixture, attached);
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.findRun(explaining.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(initial.id());
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void developmentExplanationRefreshFreezesTheLatestCommittedSourceHead()
    {
        Fixture fixture = new Fixture();
        AgentTask source = developmentSource();
        localSource(fixture, source, HEAD);
        String reviewId = fixture.service.startForTask(WORKSPACE, source.id().toString()).review().id();
        ReviewRun initial = fixture.store.listRuns(reviewId).getLast();
        completeCleanReview(fixture, beginValidation(fixture, initial));
        fixture.service.reconcile(reviewId);
        localSource(fixture, source, NEW_HEAD);
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false, false));

        AgentReviewProjection result = fixture.service.refreshExplanation(reviewId, initial.id(), 1);

        ReviewRun explaining = result.runs().getLast();
        assertThat(explaining.explanationOnly()).isTrue();
        assertThat(explaining.headSha()).isEqualTo(NEW_HEAD);
        assertThat(explaining.commits()).extracting(ReviewCommit::sha).containsExactly(NEW_HEAD);
        assertThat(result.review().latestCompletedRunId()).contains(initial.id());
        assertThat(result.findings()).isEmpty();
        verify(fixture.refresh, never()).refreshAllDetail(anyString(), anyInt());
        verify(fixture.turns, never()).synchronizeWorktree(eq(source.id()), anyLong(), any());
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
    void validationWaitsForTheSecondReaderAndSettlesItsLeadsAsCandidates()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        verify(fixture.sweeps).start(any(AgentReview.class), argThat(started -> started.id().equals(run.id())), any(AgentTask.class), any(Runnable.class));
        ReviewInvestigation investigation = new ReviewInvestigation("Parser lifetime", ImmutableList.of("src/App.java"), ImmutableList.of("E1"),
                ImmutableList.of(discoveryCandidate(1, "The parser may leak")), ImmutableList.of(), ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
        fixture.store.updateRun(sealInvestigation(run.withSummary(summary(), NOW).withInvestigation(investigation, NOW)));
        settleCurrentTurn(fixture, run);
        when(fixture.sweeps.pending(reviewId, run.id())).thenReturn(true);

        fixture.service.reconcile(reviewId);

        ReviewRun waiting = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(waiting.state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(waiting.validationStarted()).isFalse();
        verify(fixture.sweeps, never()).take(anyString(), anyString());
        verify(fixture.turns, times(1)).start(any(StartAgentTurnCommand.class));

        when(fixture.sweeps.pending(reviewId, run.id())).thenReturn(false);
        when(fixture.sweeps.take(reviewId, run.id())).thenReturn(ImmutableList.of(
                new ReviewLead("src/App.java", OptionalInt.of(12), ReviewLead.Lens.WORDS, ReviewLead.Kind.NIT, "The comment says the parser is shared.",
                        "Say it is created per file."),
                new ReviewLead("", OptionalInt.empty(), ReviewLead.Lens.SIBLINGS, ReviewLead.Kind.QUESTION, "The description asks for a release note.", "Propose one.")));

        fixture.service.reconcile(reviewId);

        ReviewRun validating = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(validating.validationStarted()).isTrue();
        ReviewStep sealed = validating.latestSealedInvestigation().orElseThrow();
        assertThat(sealed.number()).isEqualTo(1);
        ReviewInvestigation report = sealed.investigation().orElseThrow();
        assertThat(report).isEqualTo(validating.investigation().orElseThrow());
        assertThat(report.candidates()).extracting(ReviewCandidate::number).containsExactly(1, 2, 3);
        assertThat(report.candidates().getFirst()).isEqualTo(discoveryCandidate(1, "The parser may leak"));
        ReviewCandidate lead = report.candidates().get(1);
        assertThat(lead.hypothesis()).isEqualTo("Nit at src/App.java:12: The comment says the parser is shared. The author should: Say it is created per file.");
        assertThat(lead.evidenceRefs()).isEmpty();
        assertThat(lead.attributedSource()).contains("second reader");
        assertThat(lead.originStep()).isEqualTo(1);
        assertThat(report.candidates().get(2).perspective()).isEqualTo(ReviewPerspectiveAssessment.Perspective.INTERACTIONS);
        assertThat(report.perspectives()).isEqualTo(investigation.perspectives());
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void leadsBeyondTheCandidateCapacityAreLeftOut()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        int capacity = ReviewTools.candidateLimit(fixture.store, run);
        ImmutableList.Builder<ReviewCandidate> recorded = ImmutableList.builder();
        for (int number = 1; number < capacity; number++) {
            recorded.add(discoveryCandidate(number, "Hypothesis " + number));
        }
        ReviewInvestigation investigation = new ReviewInvestigation("Parser lifetime", ImmutableList.of("src/App.java"), ImmutableList.of("E1"),
                recorded.build(), ImmutableList.of(), ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
        fixture.store.updateRun(sealInvestigation(run.withSummary(summary(), NOW).withInvestigation(investigation, NOW)));
        settleCurrentTurn(fixture, run);
        ReviewLead lead = new ReviewLead("src/App.java", OptionalInt.of(12), ReviewLead.Lens.TESTS, ReviewLead.Kind.SUGGESTION, "Two fixtures are the same.", "Merge them.");
        when(fixture.sweeps.take(reviewId, run.id())).thenReturn(ImmutableList.of(lead, lead, lead));

        fixture.service.reconcile(reviewId);

        ReviewRun validating = fixture.store.findRun(run.id()).orElseThrow();
        assertThat(validating.validationStarted()).isTrue();
        assertThat(validating.latestSealedInvestigation().orElseThrow().investigation().orElseThrow().candidates()).hasSize(capacity);
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
        completeCleanReview(fixture, validationRepair);
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

        completeCleanReview(fixture, validating);
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
                new ReviewCoverage(false, ImmutableList.of("New parser ownership concern"), ImmutableList.of(), NOW),
                ImmutableList.of(discoveryCandidate(1, "The parser may leak")), ImmutableList.of(), "Selection recorded", ImmutableList.of());
        fixture.store.updateRun(validating.withEvidence("E1", evidence, NOW)
                .replaceCurrentStep(validating.currentStep().orElseThrow().withValidation(report), NOW));
        settleCurrentTurn(fixture, validating);

        fixture.service.reconcile(reviewId);

        ReviewRun collecting = fixture.store.findRun(validating.id()).orElseThrow();
        assertThat(collecting.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.COLLECT_EVIDENCE);
        assertThat(collecting.steps().get(1).validation().orElseThrow().newCandidates()).extracting(value -> value.hypothesis()).containsExactly("The parser may leak");
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
        ReviewValidation report = new ReviewValidation(1, ImmutableList.of(candidate), coverage(), ImmutableList.of(),
                candidate.findingId().stream().collect(toImmutableList()), "Select the supported defect", ImmutableList.of());
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
        ReviewValidation report = new ReviewValidation(1, ImmutableList.of(candidate), coverage, ImmutableList.of(), ImmutableList.of(finding.id()), "Select the supported defect",
                ImmutableList.of());
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
                .withValidation(new ReviewValidation(1, ImmutableList.of(candidate), coverage(), ImmutableList.of(),
                        ImmutableList.of(), "No supported findings", ImmutableList.of())), NOW));
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
        completeCleanReview(fixture, baseline);
        fixture.service.reconcile(reviewId);
        pushHead(fixture, NEW_HEAD);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(ok(""));
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false, false));
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
        completeCleanReview(fixture, validating);
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
                ImmutableList.of(discoveryCandidate(1, "Does the earlier finding still hold?", Optional.of(revalidated.id()))),
                ImmutableList.of(), ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
        ReviewRun validating = beginValidation(fixture, evidenced, assessment);
        ReviewCandidateValidation supported = new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.SUPPORTED,
                "The original trigger and contract still hold", ImmutableList.of("E1"), "No counterexample found",
                Optional.of(revalidated.id()), Optional.of(revalidated.revision()), Optional.empty());
        ReviewValidation report = new ReviewValidation(1, ImmutableList.of(supported), coverage(), ImmutableList.of(), ImmutableList.of(), "Selection recorded",
                ImmutableList.of());
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
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false, false));
        when(fixture.pullRequests.getPullRequestDiffFiles(REPO, NUMBER, null, null))
                .thenReturn(ImmutableList.of(new DiffFile("src/App.java", "modified", 1, 0, "@@ -1,2 +11,3 @@\n context\n+added\n")));
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
        assertThat(pending.anchor()).isEqualTo(originalFinding.anchor());
        ReviewAnchor reassessedAnchor = new ReviewAnchor("src/App.java", "RIGHT", 12, 12, ImmutableList.of("added"));
        ReviewFinding reassessed = fixture.store.updateFinding(pending.revised(pending.title(), pending.body(), pending.severity(),
                Optional.of(reassessedAnchor), pending.evidence(), incremental.headSha(), NOW));
        assertThat(reassessed.validatedHeadSha()).isEqualTo(NEW_HEAD);
        assertThat(reassessed.anchor()).contains(reassessedAnchor);
        assertThat(reassessed.outdated()).isFalse();
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
        completeCleanReview(fixture, validating);
        fixture.service.reconcile(reviewId);
        ReviewFinding finding = fixture.store.insertFinding(finding("finding-1", reviewId, first.id(), 1, ReviewFinding.State.OPEN));
        String newerBase = "3".repeat(40);
        when(fixture.git.run(REPOSITORY, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}")).thenReturn(ok(newerBase));
        when(fixture.git.run(REPOSITORY, "merge-base", HEAD, newerBase)).thenReturn(ok(MERGE_BASE));

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
        completeCleanReview(fixture, beginValidation(fixture, first));
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
        completeCleanReview(fixture, beginValidation(fixture, second));
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
        completeCleanReview(fixture, beginValidation(fixture, retry));
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
        completeCleanReview(fixture, beginValidation(fixture, continuing));
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
        completeCleanReview(fixture, beginValidation(fixture, run));
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
    void stopCancelsTheActiveTurnAndPausesFollowUp()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
        when(fixture.turns.get(TURN_ONE)).thenReturn(AgentTurn.accepted(TURN_ONE, TASK_ID, "review-run", SETTINGS, NOW));

        AgentReviewProjection projection = fixture.service.stop(reviewId);

        verify(fixture.turns, times(1)).cancel(TURN_ONE);
        verify(fixture.sweeps).cancel(reviewId);
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
                0, 0, 0, "merged", null, null, null, null, null, null, null, null, null, null);
        when(fixture.pullRequests.findStored(REPO, NUMBER)).thenReturn(Optional.of(stored));

        assertThat(fixture.service.start(WORKSPACE, REPO, NUMBER).pullRequest()).contains(stored);
    }

    @Test
    void projectionPreparesTheExistingDraftBeforeShowingItsPreview()
    {
        Fixture fixture = new Fixture();
        ReviewDraft saved = ReviewDraft.empty(REPO, NUMBER, NOW).withReview("User notes", "COMMENT", NOW);
        ReviewDraft prepared = saved.withVersion(1);
        when(fixture.drafts.find(REPO, NUMBER)).thenReturn(Optional.of(saved));
        when(fixture.publications.draft(REPO, NUMBER)).thenReturn(prepared);

        AgentReviewProjection projection = fixture.service.start(WORKSPACE, REPO, NUMBER);

        assertThat(projection.draft()).contains(prepared);
        verify(fixture.publications).draft(REPO, NUMBER);
    }

    @Test
    void endClosesTheReviewBeforeAskingForWorktreeCleanup()
            throws IOException
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        Path mavenRepository = ReviewMavenRepository.local(reviewId, HEAD);
        Files.createDirectories(mavenRepository.resolve("org/example"));

        AgentReviewProjection projection = fixture.service.end(reviewId);

        assertThat(mavenRepository.getParent()).doesNotExist();
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

        AgentTask completed = idleTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60));
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
    void observeHeadRecordsTheNewerHeadOnlyOnTheMatchingReview()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        AgentReview other = fixture.store.insertReview(new AgentReview(
                "other-review",
                WORKSPACE,
                REPO,
                OptionalInt.of(9),
                OptionalLong.empty(),
                TaskId.random().toString(),
                Optional.empty(),
                AgentReview.State.OPEN,
                false,
                Optional.empty(),
                Optional.of(HEAD),
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
    void observingANewHeadCatchesUpTheCleanWorktreeWithoutReviewingAgain(boolean rewritten)
    {
        Fixture fixture = rewrittenHistory();
        AutoCheckout checkout = new AutoCheckout(fixture);
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        ReviewRun assessed = fixture.store.listRuns(reviewId).getFirst();
        ReviewFinding finding = fixture.store.insertFinding(finding("preserved", reviewId, assessed.id(), 1, ReviewFinding.State.OPEN));
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD))
                .thenReturn(new GitCommandRunner.Result(rewritten ? 1 : 0, "", false));

        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);
        fixture.service.reconcile(reviewId);

        assertThat(checkout.head).isEqualTo(NEW_HEAD);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().worktreeHeadSha()).contains(NEW_HEAD);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        assertThat(fixture.store.listRuns(reviewId)).containsExactly(assessed);
        assertThat(fixture.store.findFinding(finding.id())).contains(finding);
        AgentReviewProjection.WorktreeSyncView sync = fixture.service.get(reviewId).worktreeSync().orElseThrow();
        assertThat(sync.state()).isEqualTo(AgentReviewProjection.WorktreeSyncView.State.CURRENT);
        assertThat(sync.headSha()).contains(NEW_HEAD);
        assertThat(sync.dirty()).isFalse();
        verify(fixture.git).run(taskWorktree().canonicalWorktree(), rewritten ? realign(NEW_HEAD) : fastForward(NEW_HEAD));
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
        verify(fixture.experiments, never()).synchronize(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
    }

    @Test
    void repeatedCatchUpTracksItsOwnHeadAndExplicitUpdateReviewsTheCurrentRevision()
    {
        Fixture fixture = rewrittenHistory();
        AutoCheckout checkout = new AutoCheckout(fixture);
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        ReviewRun assessed = fixture.store.listRuns(reviewId).getFirst();
        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);
        String thirdHead = "c3d4e5f6a70123456789abcdef0123456789abcd";
        pushHead(fixture, thirdHead);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", NEW_HEAD, thirdHead)).thenReturn(ok(""));
        fixture.service.observeHead(REPO, NUMBER, thirdHead);

        assertThat(checkout.head).isEqualTo(thirdHead);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().worktreeHeadSha()).contains(thirdHead);
        assertThat(fixture.store.listRuns(reviewId)).containsExactly(assessed);
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));

        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, thirdHead)).thenReturn(ok(""));
        fixture.service.update(reviewId);

        assertThat(fixture.store.listRuns(reviewId)).hasSize(2);
        assertThat(fixture.store.listRuns(reviewId).getFirst()).isEqualTo(assessed);
        assertThat(fixture.store.listRuns(reviewId).getLast().headSha()).isEqualTo(thirdHead);
        verify(fixture.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"turn", "runtime", "local-run", "queue", "active-turn"})
    void automaticCatchUpWaitsForEveryWorkingCopyOwnerAndQueuedQuestion(String owner)
    {
        Fixture fixture = rewrittenHistory();
        AutoCheckout checkout = new AutoCheckout(fixture);
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        switch (owner) {
            case "turn" -> when(fixture.tasks.get(TASK_ID)).thenReturn(busyTask());
            case "runtime" -> when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.recovery("runtime", NOW), NOW));
            case "local-run" -> when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask().claimWorktreeMutation(WorktreeMutationClaim.localRun("run", NOW), NOW));
            case "queue" -> when(fixture.turns.queued(TASK_ID)).thenReturn(ImmutableList.of(queuedInput()));
            case "active-turn" -> when(fixture.turnStore.findActive(TASK_ID)).thenReturn(Optional.of(AgentTurn.accepted(TURN_ONE, TASK_ID, "ask", SETTINGS, NOW)));
            default -> throw new AssertionError(owner);
        }
        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);

        assertThat(checkout.head).isEqualTo(HEAD);
        assertThat(fixture.service.get(reviewId).worktreeSync().orElseThrow().state()).isEqualTo(AgentReviewProjection.WorktreeSyncView.State.PENDING);
        verify(fixture.experiments, never()).synchronizeClean(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));

        when(fixture.tasks.get(TASK_ID)).thenReturn(idleTask());
        when(fixture.turns.queued(TASK_ID)).thenReturn(ImmutableList.of());
        when(fixture.turnStore.findActive(TASK_ID)).thenReturn(Optional.empty());
        fixture.service.reconcile(reviewId);

        assertThat(checkout.head).isEqualTo(NEW_HEAD);
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"staged", "unstaged", "untracked", "local-commit", "experiment", "operation"})
    void automaticCatchUpPreservesPrivateWorkAndReportsTheReason(String obstacle)
    {
        Fixture fixture = rewrittenHistory();
        AutoCheckout checkout = new AutoCheckout(fixture);
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        switch (obstacle) {
            case "staged" -> checkout.entries = "1 M. N... 100644 100644 100644 a b saved.txt\0";
            case "unstaged" -> checkout.entries = "1 .M N... 100644 100644 100644 a b saved.txt\0";
            case "untracked" -> checkout.entries = "? private.txt\0";
            case "local-commit" -> checkout.head = "c3d4e5f6a70123456789abcdef0123456789abcd";
            case "experiment" -> when(fixture.experiments.hasSavedExperiment(taskWorktree())).thenReturn(true);
            case "operation" -> when(fixture.git.gitPaths(eq(taskWorktree().canonicalWorktree()), anyList()))
                    .thenAnswer(invocation -> invocation.<List<String>>getArgument(1).stream().map(name -> Path.of(".")).collect(toImmutableList()));
            default -> throw new AssertionError(obstacle);
        }
        String previousHead = checkout.head;
        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);

        AgentReviewProjection.WorktreeSyncView sync = fixture.service.get(reviewId).worktreeSync().orElseThrow();
        assertThat(sync.state()).isEqualTo(AgentReviewProjection.WorktreeSyncView.State.BLOCKED);
        assertThat(sync.reason()).isPresent();
        assertThat(sync.headSha()).contains(previousHead);
        assertThat(sync.dirty()).isEqualTo(ImmutableSet.of("staged", "unstaged", "untracked").contains(obstacle));
        assertThat(checkout.head).isEqualTo(previousHead);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        verify(fixture.experiments, never()).synchronizeClean(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void staleObservedHeadCannotRollBackANewerFetchedRemoteRevision()
    {
        Fixture fixture = rewrittenHistory();
        AutoCheckout checkout = new AutoCheckout(fixture);
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        String thirdHead = "c3d4e5f6a70123456789abcdef0123456789abcd";
        pushHead(fixture, thirdHead);
        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);

        assertThat(checkout.head).isEqualTo(HEAD);
        verify(fixture.refresh).refreshAllDetail(REPO, NUMBER);
        verify(fixture.experiments, never()).synchronizeClean(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void catchUpRechecksBothTheObservedRevisionAndSavedEditsUnderTheClaim(boolean remoteChanged)
    {
        Fixture fixture = rewrittenHistory();
        AutoCheckout checkout = new AutoCheckout(fixture);
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        doAnswer(invocation -> {
            if (remoteChanged) {
                fixture.store.updateReview(fixture.store.findReview(reviewId).orElseThrow().withObservedHead(BASE_SHA, NOW));
            }
            else {
                checkout.entries = "? created-during-fetch.txt\0";
            }
            invocation.<Consumer<AgentTask>>getArgument(2).accept(idleTask());
            return null;
        }).when(fixture.turns).synchronizeWorktree(eq(TASK_ID), anyLong(), any());

        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);

        assertThat(checkout.head).isEqualTo(HEAD);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        verify(fixture.experiments, never()).synchronizeClean(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void developmentSourceReviewsNeverCatchUpFromThePublishedPr()
    {
        Fixture fixture = rewrittenHistory();
        AutoCheckout checkout = new AutoCheckout(fixture);
        AgentReview review = fixture.store.listOpenReviews().getFirst();
        fixture.store.updateReview(new AgentReview(review.id(), review.workspaceId(), review.repo(), review.number(), review.pullRequestId(), review.taskId(),
                Optional.of("development-task"), review.state(), review.followUpPaused(), review.latestCompletedRunId(), review.observedHeadSha(), review.worktreeHeadSha(),
                review.version(), review.createdAt(), review.updatedAt(), review.closedAt()));

        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);
        fixture.service.reconcile(review.id());

        assertThat(checkout.head).isEqualTo(HEAD);
        assertThat(fixture.store.findReview(review.id()).orElseThrow().observedHeadSha()).contains(HEAD);
        assertThat(fixture.service.get(review.id()).worktreeSync()).isEmpty();
        verify(fixture.experiments, never()).discard(any(TaskWorktree.class), anyString());
        verify(fixture.experiments, never()).synchronizeClean(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
    }

    @Test
    void alreadyAlignedRemoteHeadRepairsTheDurableBaselineWithoutMovingFiles()
    {
        Fixture fixture = rewrittenHistory();
        AutoCheckout checkout = new AutoCheckout(fixture);
        checkout.head = NEW_HEAD;
        String reviewId = fixture.store.listOpenReviews().getFirst().id();

        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);

        assertThat(fixture.store.findReview(reviewId).orElseThrow().worktreeHeadSha()).contains(NEW_HEAD);
        verify(fixture.turns).synchronizeWorktree(eq(TASK_ID), anyLong(), any());
        verify(fixture.git, never()).run(eq(taskWorktree().canonicalWorktree()), anyList());
        verify(fixture.experiments, never()).synchronizeClean(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void automaticMoveFailureDoesNotPauseReviewOrLaunchAnotherRun(boolean truncated)
    {
        Fixture fixture = rewrittenHistory();
        AutoCheckout checkout = new AutoCheckout(fixture);
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        doReturn(new GitCommandRunner.Result(truncated ? 0 : 1, "Cannot overwrite ignored output", truncated))
                .when(fixture.git).run(taskWorktree().canonicalWorktree(), fastForward(NEW_HEAD));

        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);

        assertThat(checkout.head).isEqualTo(HEAD);
        assertThat(fixture.service.get(reviewId).worktreeSync().orElseThrow().state()).isEqualTo(AgentReviewProjection.WorktreeSyncView.State.UNAVAILABLE);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isFalse();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void projectionParsesRealNullDelimitedPorcelainWithoutWritingTheIndex(@TempDir Path repository)
            throws Exception
    {
        GitCommandRunner realGit = new GitCommandRunner();
        assertThat(realGit.run(repository, "init", "-b", taskWorktree().generatedBranch()).successful()).isTrue();
        assertThat(realGit.run(repository, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--allow-empty", "-m", "Initial").successful()).isTrue();
        String realHead = realGit.run(repository, "rev-parse", "HEAD").output().strip();
        Files.writeString(repository.resolve("private.txt"), "Keep me");
        Fixture fixture = rewrittenHistory();
        new AutoCheckout(fixture);
        String reviewId = fixture.store.listOpenReviews().getFirst().id();
        fixture.store.updateReview(fixture.store.findReview(reviewId).orElseThrow().withObservedHead(realHead, NOW));
        when(fixture.git.runLocalBoundedStdout(eq(taskWorktree().canonicalWorktree()), eq(Duration.ofSeconds(2)), eq(128 * 1024), anyList()))
                .thenAnswer(invocation -> realGit.runLocalBoundedStdout(repository, Duration.ofSeconds(2), 128 * 1024, invocation.getArgument(3)));

        AgentReviewProjection.WorktreeSyncView sync = fixture.service.get(reviewId).worktreeSync().orElseThrow();

        assertThat(sync.headSha()).contains(realHead);
        assertThat(sync.dirty()).isTrue();
        assertThat(sync.state()).isEqualTo(AgentReviewProjection.WorktreeSyncView.State.CURRENT);
        assertThat(Files.readString(repository.resolve("private.txt"))).isEqualTo("Keep me");
    }

    @Test
    void aNewRemoteHeadDoesNotInterruptTheCurrentInvestigationAndValidation()
    {
        Fixture fixture = new Fixture();
        AutoCheckout checkout = new AutoCheckout(fixture);
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        pushHead(fixture, NEW_HEAD);
        fixture.service.observeHead(REPO, NUMBER, NEW_HEAD);

        assertThat(checkout.head).isEqualTo(HEAD);
        ReviewRun validating = beginValidation(fixture, fixture.store.listRuns(reviewId).getFirst());
        assertThat(validating.currentStep().orElseThrow().kind()).isEqualTo(ReviewStep.Kind.VALIDATE);
        assertThat(checkout.head).isEqualTo(HEAD);
        completeCleanReview(fixture, validating);
        fixture.service.reconcile(reviewId);

        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        assertThat(fixture.store.listRuns(reviewId).getFirst().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(fixture.store.listRuns(reviewId).getFirst().headSha()).isEqualTo(HEAD);
        assertThat(fixture.store.findReview(reviewId).orElseThrow().observedHeadSha()).contains(NEW_HEAD);
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
        assertThat(checkout.head).isEqualTo(NEW_HEAD);
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
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, false, false, false), new WorktreeSnapshot(taskWorktree(), mid, false, false, false));
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
                .thenReturn(new WorktreeSnapshot(taskWorktree(), mid, false, false, false), new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false, false));
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
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, true, true), new WorktreeSnapshot(taskWorktree(), localCommit, false, false, false));
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
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false, true), new WorktreeSnapshot(taskWorktree(), NEW_HEAD, true, false, true));
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
                .thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false, true), new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false, false));
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
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, true, false, true));
        doThrow(new IllegalStateException("Cannot preserve private experiments for synchronization"))
                .when(fixture.experiments).synchronize(eq(taskWorktree()), eq(HEAD), eq(NEW_HEAD), any(Runnable.class));

        assertThatThrownBy(() -> fixture.service.update(reviewId)).isInstanceOf(ReviewConflictException.class).hasMessageContaining("Cannot preserve private experiments");

        assertThat(fixture.store.findReview(reviewId).orElseThrow().followUpPaused()).isTrue();
        assertThat(fixture.store.listRuns(reviewId)).hasSize(1);
        verify(fixture.git, never()).run(eq(taskWorktree().canonicalWorktree()), anyList());
    }

    /** An accepted review of HEAD whose pull request was force-pushed to NEW_HEAD. */
    @Test
    void anUpdateCarriesAnAcceptedReviewToAHeadWithTheSameChange()
    {
        Fixture fixture = new Fixture();
        ReviewFinding finding = acceptReviewWithOneSupportedFinding(fixture);
        String reviewId = finding.reviewId();
        ReviewRun accepted = fixture.store.listRuns(reviewId).getLast();
        ReviewFinding resolved = fixture.store.insertFinding(finding("finding-2", reviewId, "earlier-run", 2, ReviewFinding.State.RESOLVED));
        pushRebasedHead(fixture, "+added");

        fixture.service.update(reviewId);

        assertThat(fixture.store.listRuns(reviewId)).hasSize(2);
        ReviewRun carried = fixture.store.listRuns(reviewId).getLast();
        assertThat(carried.state()).isEqualTo(ReviewRun.State.ACCEPTED);
        assertThat(carried.headSha()).isEqualTo(NEW_HEAD);
        assertThat(carried.priorHeadSha()).contains(HEAD);
        assertThat(carried.turnIds()).isEmpty();
        assertThat(carried.steps()).isEqualTo(accepted.steps());
        assertThat(carried.coverage()).isEqualTo(accepted.coverage());
        assertThat(carried.evidence()).isEqualTo(accepted.evidence());
        assertThat(carried.outcome().orElseThrow()).contains("identical", "`" + ReviewPrompts.shortSha(HEAD) + "`");
        assertThat(fixture.store.findReview(reviewId).orElseThrow().latestCompletedRunId()).contains(carried.id());
        ReviewFinding moved = fixture.store.findFinding(finding.id()).orElseThrow();
        assertThat(moved.validatedHeadSha()).isEqualTo(NEW_HEAD);
        assertThat(moved.outdated()).isFalse();
        assertThat(moved.revision()).isEqualTo(finding.revision());
        assertThat(moved.anchor()).contains(new ReviewAnchor("src/App.java", "RIGHT", 12, 12, ImmutableList.of("added")));
        assertThat(fixture.store.findFinding(resolved.id()).orElseThrow().validatedHeadSha()).isEqualTo(HEAD);
        verify(fixture.turns, times(2)).start(any(StartAgentTurnCommand.class));
        verify(fixture.sweeps, times(1)).start(any(AgentReview.class), any(ReviewRun.class), any(AgentTask.class), any(Runnable.class));

        // The carried run is the review of this head, so another update has nothing to do.
        fixture.service.update(reviewId);

        assertThat(fixture.store.listRuns(reviewId)).hasSize(2);
    }

    @Test
    void aDifferentChangeOrAFindingRevisedSinceAcceptanceStartsAnotherReview()
    {
        Fixture changed = new Fixture();
        ReviewFinding read = acceptReviewWithOneSupportedFinding(changed);
        pushRebasedHead(changed, "+added differently");

        changed.service.update(read.reviewId());

        assertThat(changed.store.listRuns(read.reviewId())).hasSize(2);
        assertThat(changed.store.listRuns(read.reviewId()).getLast().state()).isEqualTo(ReviewRun.State.RUNNING);
        assertThat(changed.store.findFinding(read.id()).orElseThrow().outdated()).isTrue();
        verify(changed.turns, times(3)).start(any(StartAgentTurnCommand.class));

        Fixture revised = new Fixture();
        ReviewFinding original = acceptReviewWithOneSupportedFinding(revised);
        revised.store.updateFinding(original.revised(original.title(), "A discussion reworded the claim", original.severity(), original.anchor(), original.evidence(), HEAD, NOW));
        pushRebasedHead(revised, "+added");

        revised.service.update(original.reviewId());

        assertThat(revised.store.listRuns(original.reviewId()).getLast().state()).isEqualTo(ReviewRun.State.RUNNING);
        verify(revised.git, never()).runLocalBoundedStdout(eq(REPOSITORY), any(Duration.class), anyInt(), anyList());
        verify(revised.turns, times(3)).start(any(StartAgentTurnCommand.class));
    }

    /** An accepted, complete review at {@code HEAD} whose one open finding its validation supported and selected. */
    private static ReviewFinding acceptReviewWithOneSupportedFinding(Fixture fixture)
    {
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewFinding finding = fixture.store.insertFinding(finding("finding-1", reviewId, run.id(), 1, ReviewFinding.State.OPEN));
        ReviewEvidence evidence = new ReviewEvidence("E1", "src/App.java", "frozen head", "new Parser()", "allocation");
        ReviewInvestigation investigation = new ReviewInvestigation("Parser lifetime", ImmutableList.of("src/App.java"), ImmutableList.of("E1"),
                ImmutableList.of(discoveryCandidate(1, "The parser may leak")), ImmutableList.of(), ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
        ReviewRun validating = beginValidation(fixture, fixture.store.updateRun(run.withEvidence("E1", evidence, NOW)), investigation);
        ReviewCandidateValidation supported = new ReviewCandidateValidation(1, ReviewCandidateValidation.Outcome.SUPPORTED, "The owner never closes the parser",
                ImmutableList.of("E1"), "No counterexample found", Optional.of(finding.id()), Optional.of(finding.revision()), Optional.empty());
        ReviewValidation report = new ReviewValidation(1, ImmutableList.of(supported), coverage(), ImmutableList.of(), ImmutableList.of(finding.id()), "Selection recorded",
                ImmutableList.of());
        fixture.store.updateRun(validating.withCoverage(coverage(), NOW).replaceCurrentStep(validating.currentStep().orElseThrow().withValidation(report), NOW));
        settleCurrentTurn(fixture, validating);
        fixture.service.reconcile(reviewId);
        assertThat(fixture.store.findRun(run.id()).orElseThrow().state()).isEqualTo(ReviewRun.State.ACCEPTED);
        return fixture.store.findFinding(finding.id()).orElseThrow();
    }

    /**
     * The pull request now sits at {@code NEW_HEAD}, rebased so that its hunk moved ten lines down, with the working copy
     * already caught up. Both ranges have the same commit message, and the new range adds {@code addedLine}.
     */
    private static void pushRebasedHead(Fixture fixture, String addedLine)
    {
        pushHead(fixture, NEW_HEAD);
        when(fixture.git.run(REPOSITORY, "merge-base", "--is-ancestor", HEAD, NEW_HEAD)).thenReturn(new GitCommandRunner.Result(1, "", false));
        when(fixture.worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), NEW_HEAD, false, false, false));
        when(fixture.pullRequests.getPullRequestDiffFiles(REPO, NUMBER, null, null))
                .thenReturn(ImmutableList.of(new DiffFile("src/App.java", "modified", 1, 0, "@@ -1,2 +11,3 @@\n context\n+added\n")));
        when(fixture.git.runLocalBoundedStdout(eq(REPOSITORY), any(Duration.class), anyInt(), anyList())).thenAnswer(invocation -> {
            List<String> arguments = invocation.getArgument(3);
            boolean rebased = arguments.getLast().contains(NEW_HEAD);
            return ok(arguments.getFirst().equals("log") ? "Fix the parser\n\u001e\n"
                    : "diff --git a/src/App.java b/src/App.java\nindex " + (rebased ? "333..444" : "111..222") + " 100644\n--- a/src/App.java\n+++ b/src/App.java\n"
                            + "@@ -1,2 +" + (rebased ? 11 : 1) + ",3 @@\n context\n" + (rebased ? addedLine : "+added") + "\n");
        });
    }

    private static Fixture rewrittenHistory()
    {
        Fixture fixture = new Fixture();
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewRun validating = beginValidation(fixture, run);
        completeCleanReview(fixture, validating);
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
                Optional.empty(),
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
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
    }

    private static ReviewRun beginCandidateValidation(Fixture fixture)
    {
        String reviewId = fixture.service.start(WORKSPACE, REPO, NUMBER).review().id();
        ReviewRun run = fixture.store.listRuns(reviewId).getLast();
        ReviewInvestigation investigation = new ReviewInvestigation("Parser lifetime", ImmutableList.of("src/App.java"), ImmutableList.of("E1"),
                ImmutableList.of(discoveryCandidate(1, "The parser may leak")), ImmutableList.of(), ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
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
        ReviewValidation report = new ReviewValidation(revision, ImmutableList.of(candidate(outcome)), coverage, ImmutableList.of(), ImmutableList.of(), "Selection recorded",
                ImmutableList.of());
        fixture.store.updateRun(run.replaceCurrentStep(run.currentStep().orElseThrow().withValidation(report), NOW));
        settleCurrentTurn(fixture, run);
    }

    private static void finishEvidenceCollection(Fixture fixture, ReviewRun run, Optional<String> limitation)
    {
        ReviewInvestigation previous = run.investigation().orElseThrow();
        ReviewInvestigation report = new ReviewInvestigation(
                previous.changeMap(), previous.reviewedFiles(), previous.contextEvidenceRefs(), previous.candidates(), ImmutableList.of(),
                ImmutableList.of(new ReviewEvidenceAnswer(1, "Read the caller and checked the cleanup path", ImmutableList.of("E1"), limitation)),
                Optional.empty(), perspectives(), Optional.empty());
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

    private static void completeCleanReview(Fixture fixture, ReviewRun run)
    {
        completeValidation(fixture, run);
    }

    private static void completeValidation(Fixture fixture, ReviewRun run)
    {
        int investigationStep = run.steps().stream().filter(step -> step.investigation().isPresent()).reduce((first, second) -> second).orElseThrow().number();
        ReviewValidation report = new ReviewValidation(investigationStep, ImmutableList.of(), coverage(), ImmutableList.of(), ImmutableList.of(), "Selection recorded",
                ImmutableList.of());
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
                NOW, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC);
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

    private static final class AutoCheckout
    {
        private String head = HEAD;
        private String entries = "";

        private AutoCheckout(Fixture fixture)
        {
            Path checkout = taskWorktree().canonicalWorktree();
            when(fixture.worktrees.ownedCheckout(taskWorktree())).thenReturn(checkout);
            when(fixture.git.runLocalBoundedStdout(eq(checkout), eq(Duration.ofSeconds(2)), eq(128 * 1024), anyList()))
                    .thenAnswer(invocation -> ok("# branch.oid " + head + "\0# branch.head " + taskWorktree().generatedBranch() + "\0" + entries));
            when(fixture.git.gitPaths(eq(checkout), anyList())).thenAnswer(invocation -> invocation.<List<String>>getArgument(1).stream()
                    .map(name -> checkout.resolve("absent-git-operation").resolve(name)).collect(toImmutableList()));
            when(fixture.worktrees.inspect(taskWorktree())).thenAnswer(invocation -> new WorktreeSnapshot(taskWorktree(), head, false, false, false));
            when(fixture.git.run(eq(checkout), anyList())).thenAnswer(invocation -> {
                head = invocation.<List<String>>getArgument(1).getLast();
                return ok("");
            });
            when(fixture.git.run(eq(REPOSITORY), eq("merge-base"), eq("--is-ancestor"), anyString(), anyString())).thenReturn(ok(""));
            doAnswer(invocation -> {
                invocation.getArgument(3, Runnable.class).run();
                return null;
            }).when(fixture.experiments).synchronizeClean(any(TaskWorktree.class), anyString(), anyString(), any(Runnable.class));
        }
    }

    private static final class Fixture
    {
        private final AgentReviewStore store = spy(new InMemoryReviewStore());
        private final ReviewDraftStore drafts = mock(ReviewDraftStore.class);
        private final ReviewPublicationService publications = mock(ReviewPublicationService.class);
        private final AgentTaskService tasks = mock(AgentTaskService.class);
        private final AgentEventService events = mock(AgentEventService.class);
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
        private final WorktreeDiffReader browse = mock(WorktreeDiffReader.class);
        private final PullRequestTaskStore assignments = mock(PullRequestTaskStore.class);
        private final PullRequestStore pullRequestStore = mock(PullRequestStore.class);
        private final ReviewExperiments experiments = mock(ReviewExperiments.class);
        private final PullRequestService pullRequests = mock(PullRequestService.class);
        private final PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        private final CredentialService credentials = mock(CredentialService.class);
        private final ReviewTaskObserver observer = mock(ReviewTaskObserver.class);
        private final ReviewSweep sweeps = mock(ReviewSweep.class);
        private final Map<String, AgentTurn> acceptedTurns = new LinkedHashMap<>();
        private final DefaultAgentReviewService service;

        private Fixture()
        {
            service = new DefaultAgentReviewService(
                    store,
                    drafts,
                    publications,
                    tasks,
                    events,
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
                    browse,
                    assignments,
                    pullRequestStore,
                    experiments,
                    pullRequests,
                    refresh,
                    credentials,
                    observer,
                    sweeps,
                    new ReviewLocks(),
                    CLOCK);
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
            when(worktrees.inspect(taskWorktree())).thenReturn(new WorktreeSnapshot(taskWorktree(), HEAD, false, false, false));
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
            // No range can be compared unless a test supplies it, so a new head is a different change by default.
            when(git.runLocalBoundedStdout(eq(REPOSITORY), any(Duration.class), anyInt(), anyList())).thenReturn(new GitCommandRunner.Result(128, "", false));
            when(git.run(REPOSITORY, "rev-parse", "--verify", "refs/bytequay/review/" + NUMBER + "^{commit}")).thenReturn(ok(HEAD));
            when(git.run(REPOSITORY, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}")).thenReturn(ok(BASE_SHA));
            when(git.run(REPOSITORY, "merge-base", HEAD, BASE_SHA)).thenReturn(ok(MERGE_BASE));
            when(git.run(REPOSITORY, "log", "--reverse", "--format=%H%x1f%an%x1f%s", MERGE_BASE + ".." + HEAD))
                    .thenReturn(ok(HEAD + FIELD + "Alice" + FIELD + "Fix the parser\n"));
        }
    }
}
