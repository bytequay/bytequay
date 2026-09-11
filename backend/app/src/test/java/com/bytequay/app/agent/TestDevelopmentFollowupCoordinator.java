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

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnCompletion;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.DevelopmentStopAfter;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.StartCiAutofixCommand;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static com.bytequay.agent.core.DevelopmentTurnOrigin.Kind.AUTO_COMMIT;
import static com.bytequay.agent.core.DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDevelopmentFollowupCoordinator
{
    private static final Instant NOW = Instant.EPOCH;
    private static final DevelopmentAutomation FULL = new DevelopmentAutomation(true, true, true, Optional.of(AgentLaneId.CLAUDE_CLI));
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private static final String NEXT = "c".repeat(40);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final AgentTurnStore turnStore = mock(AgentTurnStore.class);
    private final DevelopmentGitInspector git = mock(DevelopmentGitInspector.class);
    private final GhCliService gh = mock(GhCliService.class);
    private final CiAutofixService ci = mock(CiAutofixService.class);
    private final Clock clock = mock(Clock.class);
    private final Runnable waitForCi = mock(Runnable.class);
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private final List<AgentTurn> history = new ArrayList<>();
    private final DevelopmentFollowupCoordinator coordinator = new DevelopmentFollowupCoordinator(turns, turnStore, git, gh, pending::add, ci, clock, waitForCi);
    private final TaskId taskId = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("owner", "repo"),
            Path.of("/tmp", taskId.toString()), "bytequay/task/" + taskId, BASE);
    private final DevelopmentPublicationRoute route = new DevelopmentPublicationRoute(new RepoRef("owner", "repo"), new RepoRef("owner", "repo"),
            "https://github.com/owner/repo.git", "origin", "main", BASE, worktree.generatedBranch());
    private final AgentTask task = AgentTask.created(taskId, true, "Develop", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO).withDevelopmentAutomation(FULL), NOW);
    private AgentTurn source;
    private DevelopmentTurnCompletion outcome;

    @BeforeEach
    void setUp()
    {
        when(clock.instant()).thenReturn(NOW);
        when(turnStore.list(taskId)).thenAnswer(ignored -> ImmutableList.copyOf(history));
        when(turnStore.findActive(taskId)).thenAnswer(ignored -> history.stream()
                .filter(turn -> turn.state() == TurnState.ACCEPTED || turn.state() == TurnState.RUNNING).findFirst());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()));
        when(git.isAncestor(eq(worktree), anyString(), anyString())).thenReturn(true);
        when(gh.findBinary()).thenReturn(Optional.of("/usr/local/bin/gh"));
        when(ci.startDevelopment(any(), any(), any(), any(), any())).thenAnswer(ignored -> ciRun().finish(CiAutofixRun.FinishReason.GREEN, "Checks passed", NOW));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void questionSettlesBeforeInspectingDirtyOrCommittedWork(boolean dirty)
    {
        prepare(local(BASE, false), local(HEAD, dirty), FULL, false, Optional.empty());
        advance();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(git, never()).inspectLocal(any(), any());
        verify(git, never()).inspectPublication(any(), any());
        noFollowup();
    }

    @Test
    void unchangedReadOnlyTurnDoesNotDeliverExistingWork()
    {
        prepare(local(HEAD, true), local(HEAD, true), FULL, true, Optional.empty());
        advance();
        noFollowup();
        verify(git, never()).inspectPublication(any(), any());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void answerCanCommitEarlierDirtyWorkWithoutNewEdits()
    {
        history.add(running(FULL).withDevelopmentResult(report(false, Optional.empty()))
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW));
        prepare(local(HEAD, true), local(HEAD, true), new DevelopmentAutomation(true, false, false, Optional.empty()), true, Optional.empty());
        when(git.inspectLocal(worktree, route)).thenReturn(local(HEAD, true), local(NEXT, false));
        generated(AUTO_COMMIT, true, Optional.empty());
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), contains("Auto commit"));
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void acceptedQueuePrecedesDeliveryAndKeepsOwnershipHandoff()
    {
        prepare(local(BASE, false), local(HEAD, true), FULL, true, Optional.empty());
        when(turns.handoffDevelopmentContinuationToQueue(taskId, source.id())).thenReturn(true);
        advance();
        noFollowup();
        verify(git, never()).inspectLocal(any(), any());
        verify(turns, never()).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void queuedTurnInheritsPendingWorkFromThePreviousAcceptedBaseline()
    {
        var commitOnly = new DevelopmentAutomation(true, false, false, Optional.empty());
        prepare(local(BASE, false), local(HEAD, true), commitOnly, true, Optional.empty());
        TurnId previous = source.id();
        when(turns.handoffDevelopmentContinuationToQueue(taskId, previous)).thenAnswer(ignored -> {
            prepare(local(HEAD, true), local(HEAD, true), commitOnly, true, Optional.empty());
            return true;
        });
        advance();
        when(git.inspectLocal(worktree, route)).thenReturn(local(HEAD, true), local(NEXT, false));
        generated(AUTO_COMMIT, true, Optional.empty());
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), anyString());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).finishDevelopmentContinuation(taskId, previous);
    }

    @Test
    void commitsThenPublishesExactlyOnceAndVerifiesBothResults()
    {
        prepare(local(BASE, false), local(BASE, true), FULL, true, Optional.empty());
        when(git.inspectLocal(worktree, route)).thenReturn(local(BASE, true), local(HEAD, false), local(HEAD, false));
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()), publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        generated(AUTO_COMMIT, true, Optional.empty());
        generated(CREATE_DRAFT_PR, true, Optional.empty());
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), contains("Do not push"));
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), contains("--force-with-lease="));
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void dirtyWorkWithAutoCommitDisabledDoesNotPublishCommittedSubset()
    {
        prepare(local(BASE, false), local(HEAD, true), new DevelopmentAutomation(false, true, true, Optional.empty()), true, Optional.empty());
        advance();
        noFollowup();
        verify(git, never()).inspectPublication(any(), any());
    }

    @Test
    void noCommittedDiffDoesNotCreatePullRequest()
    {
        DevelopmentLocalFacts emptyDiff = new DevelopmentLocalFacts(route.headBranch(), HEAD, HEAD, "different", 0, 0, 0, 0, 1, false, false);
        prepare(local(BASE, false), emptyDiff, FULL, true, Optional.empty());
        advance();
        noFollowup();
    }

    @ParameterizedTest
    @EnumSource(value = DevelopmentStopAfter.class, names = {"LOCAL_CHANGES", "COMMITS"})
    void sourceStopLimitLeavesWorkLocal(DevelopmentStopAfter limit)
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.of(limit));
        advance();
        noFollowup();
    }

    @Test
    void commitTurnCanNarrowLaterPublication()
    {
        prepare(local(BASE, false), local(BASE, true), FULL, true, Optional.empty());
        when(git.inspectLocal(worktree, route)).thenReturn(local(BASE, true), local(HEAD, false));
        generated(AUTO_COMMIT, true, Optional.of(DevelopmentStopAfter.COMMITS));
        advance();
        verify(turns, never()).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void commitQuestionStopsBeforePublicationAndDoesNotFail()
    {
        prepare(local(BASE, false), local(BASE, true), FULL, true, Optional.empty());
        generated(AUTO_COMMIT, false, Optional.empty());
        advance();
        verify(turns, never()).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void dirtyCommitResultFailsWithoutAnotherAttempt()
    {
        prepare(local(BASE, false), local(BASE, true), FULL, true, Optional.empty());
        generated(AUTO_COMMIT, true, Optional.empty());
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), anyString());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("left uncommitted"));
    }

    @Test
    void existingExactPublishedRevisionDoesNotCreateAnotherPr()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        advance();
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void laterRevisionReusesExistingPr()
    {
        GitHubPullRequestIdentity existing = pr(HEAD, false);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(existing)));
        prepare(local(HEAD, false), local(NEXT, false), FULL, true, Optional.empty());
        when(git.inspectPublication(worktree, route)).thenReturn(
                publication(Optional.of(HEAD), Optional.of(existing)), publication(Optional.of(NEXT), Optional.of(pr(NEXT, false))));
        generated(CREATE_DRAFT_PR, true, Optional.empty());
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), contains("Reuse existing PR"));
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void externallyChangedRemoteIsRefusedBeforePublication()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.empty()));
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("remote branch changed"));
    }

    @ParameterizedTest
    @EnumSource(value = GitHubPullRequestIdentity.State.class, names = {"CLOSED", "MERGED"})
    void closedOrMergedPrCannotBeReplaced(GitHubPullRequestIdentity.State state)
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        GitHubPullRequestIdentity open = pr(HEAD, true);
        GitHubPullRequestIdentity closed = new GitHubPullRequestIdentity(open.pullRequest(), open.url(), open.headRepository(), open.headBranch(), open.headSha(),
                open.baseRepository(), open.baseBranch(), open.baseSha(), state, open.draft(), open.author());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(closed)));
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("closed or merged"));
    }

    @Test
    void publicationCannotChangeTheLocalRevision()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        when(git.inspectLocal(worktree, route)).thenReturn(local(HEAD, false), local(NEXT, false));
        generated(CREATE_DRAFT_PR, true, Optional.empty());
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("changed the verified local revision"));
    }

    @Test
    void publicationQuestionReturnsControlWithoutAssumingItsRemoteResult()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        generated(CREATE_DRAFT_PR, false, Optional.empty());
        advance();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void uncertainPublicationFailsWithoutRetrying()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        when(turns.startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Response lost")));
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Remote result may have changed"));
    }

    @Test
    void missingReportFailsInsteadOfAssumingReadiness()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        AgentTurn noReport = running(FULL).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        outcome = new DevelopmentTurnCompletion(task, noReport, true, Optional.empty());
        when(turns.ownsDevelopmentContinuation(taskId, noReport.id())).thenReturn(true);
        coordinator.completed(outcome);
        pending.remove().run();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(noReport.id()), contains("did not report"));
        noFollowup();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cleanupAndQuiescenceAreBothRequired(boolean quiescent)
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        outcome = new DevelopmentTurnCompletion(task, source, quiescent, quiescent ? Optional.of("Cleanup failed") : Optional.empty());
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Automation failed"));
        noFollowup();
    }

    @Test
    void lostRemoteBaselineAllowsDevelopmentButBlocksPublication()
    {
        when(git.inspectPublication(worktree, route)).thenThrow(new IllegalStateException("Authentication unavailable"));
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Remote baseline unavailable: Authentication unavailable"));
    }

    @Test
    void generatedReadyReportCannotMakeAnUnverifiedPublicationSuccessful()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        generated(CREATE_DRAFT_PR, true, Optional.empty());
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("pushed revision was not verified"));
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
    }

    @Test
    void newlyCreatedPrMustBeDraft()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        when(git.inspectPublication(worktree, route)).thenReturn(
                publication(Optional.empty(), Optional.empty()), publication(Optional.of(HEAD), Optional.of(pr(HEAD, false))));
        generated(CREATE_DRAFT_PR, true, Optional.empty());
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("not a draft"));
    }

    @Test
    void wrongBasePrIsNotReused()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        GitHubPullRequestIdentity wrongBase = new GitHubPullRequestIdentity(new PullRequestRef("owner", "repo", 7),
                "https://github.com/owner/repo/pull/7", route.headRepository(), route.headBranch(), HEAD,
                route.baseRepository(), "other", BASE, GitHubPullRequestIdentity.State.OPEN, true, "owner");
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(wrongBase)));
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("base or head does not match"));
    }

    @Test
    void ambiguousPrIdentityDoesNotDispatchPublication()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        when(git.inspectPublication(worktree, route)).thenReturn(new DevelopmentPublicationFacts(Optional.of(HEAD), ImmutableList.of(pr(HEAD, true), pr(HEAD, false))));
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Several pull requests"));
    }

    @Test
    void localChangesLimitPreventsCommittingDirtyWork()
    {
        prepare(local(BASE, false), local(BASE, true), FULL, true, Optional.of(DevelopmentStopAfter.LOCAL_CHANGES));
        advance();
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void stopDuringBaselineCaptureDoesNotRetainTheAcceptedSnapshot()
    {
        when(git.inspectPublication(worktree, route)).thenAnswer(ignored -> {
            history.replaceAll(turn -> turn.finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("Stopped"), NOW));
            return publication(Optional.empty(), Optional.empty());
        });
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("baseline is unavailable"));
    }

    @Test
    void revokedOwnershipStopsScheduledCallback()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        when(turns.ownsDevelopmentContinuation(taskId, source.id())).thenReturn(false);
        advance();
        noFollowup();
        verify(git, never()).inspectLocal(any(), any());
    }

    @Test
    void stoppingDuringInspectionCannotDispatchOrSettleAnotherOwner()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        when(git.inspectLocal(worktree, route)).thenAnswer(ignored -> {
            when(turns.ownsDevelopmentContinuation(taskId, source.id())).thenReturn(false);
            return local(HEAD, false);
        });
        advance();
        noFollowup();
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
    }

    @Test
    void rejectedExecutorFailsTheContinuation()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.empty());
        var rejected = new DevelopmentFollowupCoordinator(turns, turnStore, git, gh, ignored -> { throw new RejectedExecutionException("stopped"); }, ci, clock, waitForCi);
        rejected.completed(outcome);
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Could not start"));
    }

    @Test
    void publicationPromptPinsForkRoutingLeaseAndExactObject()
    {
        var fork = new DevelopmentPublicationRoute(new RepoRef("forker", "repo"), new RepoRef("upstream", "repo"), "https://github.com/forker/repo.git",
                "upstream", "release", BASE, route.headBranch());
        var baseline = new DevelopmentBaseline(worktree, fork, local(BASE, false));
        String prompt = DevelopmentFollowupCoordinator.publicationPrompt(baseline, local(HEAD, false), publication(Optional.empty(), Optional.empty()),
                Optional.empty(), "/path with spaces/gh");
        assertThat(prompt).contains("--force-with-lease=refs/heads/" + route.headBranch() + ":'", HEAD + ":refs/heads/" + route.headBranch(),
                "--repo 'upstream/repo'", "--base 'release'", "--head 'forker:", "pr create --draft", "Do not commit", "without retrying");
    }

    @Test
    void sameRepositoryPublicationUsesBranchOnlyHeadForOrganizationSupport()
    {
        String prompt = DevelopmentFollowupCoordinator.publicationPrompt(new DevelopmentBaseline(worktree, route, local(BASE, false)),
                local(HEAD, false), publication(Optional.empty(), Optional.empty()), Optional.empty(), "/usr/local/bin/gh");
        assertThat(prompt).contains("--head '" + route.headBranch() + "'").doesNotContain("--head 'owner:");
    }

    @Test
    void coordinatorDrivesCiUsingAcceptedProviderAndOriginalActivation()
    {
        preparePublished(FULL);
        CiAutofixRun run = ciRun();
        when(ci.startDevelopment(any(), any(), any(), any(), any())).thenReturn(run);
        when(ci.advance(run.id())).thenReturn(run, run.finish(CiAutofixRun.FinishReason.GREEN, "Checks passed", NOW));
        advance();
        var order = inOrder(ci, waitForCi, turns);
        order.verify(ci).startDevelopment(eq(new StartCiAutofixCommand("workspace", 7, AgentLaneId.CLAUDE_CLI,
                3, true, true, true, "development-ci:" + taskId + ":" + source.id())), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        order.verify(ci).advance(run.id());
        order.verify(waitForCi).run();
        order.verify(ci).advance(run.id());
        order.verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void timeSpentBeforeCiAttachmentDoesNotRenewAuthorization()
    {
        preparePublished(FULL);
        coordinator.completed(outcome);
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(2)));
        pending.remove().run();
        verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
    }

    @Test
    void expiredContinuationCannotAttachCi()
    {
        preparePublished(FULL);
        coordinator.completed(outcome);
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(8)));
        pending.remove().run();
        verify(ci, never()).startDevelopment(any(), any(), any(), any(), any());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("authorization expired"));
    }

    @Test
    void leaveDraftLimitStillAllowsCiRepairs()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true, Optional.of(DevelopmentStopAfter.DRAFT));
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        advance();
        verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
    }

    @Test
    void missingCiProviderFailsAfterPreservingTheVerifiedDraft()
    {
        preparePublished(DevelopmentAutomation.DEFAULT);
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Select a supported CI provider"));
        verify(ci, never()).startDevelopment(any(), any(), any(), any(), any());
        noFollowup();
    }

    @Test
    void disablingCiLeavesTheVerifiedDraft()
    {
        preparePublished(new DevelopmentAutomation(true, true, false, Optional.empty()));
        advance();
        verify(ci, never()).startDevelopment(any(), any(), any(), any(), any());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void ciCanAttachToPublishedWorkWithoutAutomaticDraftCreation()
    {
        preparePublished(new DevelopmentAutomation(true, false, true, Optional.of(AgentLaneId.CLAUDE_CLI)));
        advance();
        verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        noFollowup();
    }

    @Test
    void ciOnlyDoesNotPublishUnpublishedWork()
    {
        prepare(local(BASE, false), local(HEAD, false), new DevelopmentAutomation(true, false, true, Optional.of(AgentLaneId.CLAUDE_CLI)), true, Optional.empty());
        advance();
        verify(ci, never()).startDevelopment(any(), any(), any(), any(), any());
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void ciQuestionRetainsSourceOwnershipUntilTheRunContinues()
    {
        preparePublished(FULL);
        CiAutofixRun paused = ciRun().pause(CiAutofixRun.PauseReason.INVALID_GIT_RESULT, "How should this failure be fixed?", NOW);
        when(ci.startDevelopment(any(), any(), any(), any(), any())).thenReturn(paused);
        when(ci.canMessage(paused)).thenReturn(true);
        when(ci.advance(paused.id())).thenReturn(paused, paused.finish(CiAutofixRun.FinishReason.GREEN, "Checks passed", NOW));
        doAnswer(ignored -> {
            verify(turns, never()).finishDevelopmentContinuation(any(), any());
            verify(turns, never()).startGeneratedTurn(any(), any(), anyString());
            return null;
        }).when(waitForCi).run();
        advance();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void unrecoverableCiPauseFailsTheParentWithoutDispatchingMoreWork()
    {
        preparePublished(FULL);
        CiAutofixRun run = ciRun().pause(CiAutofixRun.PauseReason.TURN_FAILED, "Provider failed", NOW);
        when(ci.startDevelopment(any(), any(), any(), any(), any())).thenReturn(run);
        when(ci.get(run.id())).thenReturn(run);
        advance();
        verify(ci, never()).advance(any());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Provider failed"));
    }

    @Test
    void replyRacingPausedPollDoesNotFailTheNewRepair()
    {
        preparePublished(FULL);
        CiAutofixRun paused = ciRun().pause(CiAutofixRun.PauseReason.INVALID_GIT_RESULT, "Choose a repair", NOW);
        CiAutofixRun resumed = paused.resume(NOW).withVersion(paused.version() + 1);
        when(ci.startDevelopment(any(), any(), any(), any(), any())).thenReturn(paused);
        when(ci.canMessage(paused)).thenReturn(false);
        when(ci.get(paused.id())).thenReturn(resumed);
        when(ci.advance(paused.id())).thenReturn(resumed.finish(CiAutofixRun.FinishReason.GREEN, "Checks passed", NOW));
        advance();
        verify(ci).advance(paused.id());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void exhaustedCiFailsTheParent()
    {
        preparePublished(FULL);
        when(ci.startDevelopment(any(), any(), any(), any(), any())).thenReturn(ciRun().finish(CiAutofixRun.FinishReason.MAX_ATTEMPTS, "Three repairs failed", NOW));
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Three repairs failed"));
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
    }

    @Test
    void stopDuringCiWaitCannotAdvanceOrFinishAnotherOwner()
    {
        preparePublished(FULL);
        CiAutofixRun run = ciRun();
        when(ci.startDevelopment(any(), any(), any(), any(), any())).thenReturn(run);
        when(ci.advance(run.id())).thenReturn(run);
        doAnswer(ignored -> {
            when(turns.ownsDevelopmentContinuation(taskId, source.id())).thenReturn(false);
            return null;
        }).when(waitForCi).run();
        advance();
        verify(ci).advance(run.id());
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    private void preparePublished(DevelopmentAutomation automation)
    {
        prepare(local(BASE, false), local(HEAD, false), automation, true, Optional.empty());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
    }

    private CiAutofixRun ciRun()
    {
        return CiAutofixRun.create(CiAutofixRunId.random(), "owned-ci",
                new CiAutofixRun.Scope(pr(HEAD, true).pullRequest(), "workspace", Optional.of(route.headRepository()), "owner", route.headBranch(), "main",
                        AgentLaneId.CLAUDE_CLI.value(), Optional.of(taskId.toString())),
                HEAD, HEAD, BASE, new CiAutofixRun.Authorization(true, true, true, 3, NOW, NOW.plus(Duration.ofHours(8))), NOW);
    }

    private void prepare(DevelopmentLocalFacts before, DevelopmentLocalFacts after, DevelopmentAutomation automation, boolean ready, Optional<DevelopmentStopAfter> limit)
    {
        source = running(automation);
        history.add(source);
        when(git.capture(task)).thenReturn(new DevelopmentBaseline(worktree, route, before));
        coordinator.accepted(task, source);
        source = source.withDevelopmentResult(report(ready, limit)).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        history.set(history.size() - 1, source);
        outcome = new DevelopmentTurnCompletion(task, source, true, Optional.empty());
        when(turns.ownsDevelopmentContinuation(taskId, source.id())).thenReturn(true);
        when(git.inspectLocal(worktree, route)).thenReturn(after);
        clearInvocations(git);
    }

    private void generated(DevelopmentTurnOrigin.Kind kind, boolean ready, Optional<DevelopmentStopAfter> limit)
    {
        when(turns.startGeneratedTurn(eq(taskId), eq(origin(kind)), anyString())).thenAnswer(ignored -> {
            AgentTurn acceptedTurn = AgentTurn.accepted(TurnId.random(), taskId, "generated-" + kind, source.settings().orElseThrow(), NOW)
                    .withDevelopmentOrigin(origin(kind));
            AgentTurn generated = acceptedTurn.markDeliveryStarted(NOW).running(new ProviderTurnRef("generated-" + kind), Optional.empty())
                    .withDevelopmentResult(report(ready, limit)).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
            history.add(generated);
            return CompletableFuture.completedFuture(new DevelopmentTurnCompletion(task, generated, true, Optional.empty()));
        });
    }

    private void advance()
    {
        coordinator.completed(outcome);
        pending.remove().run();
    }

    private void noFollowup()
    {
        verify(turns, never()).startGeneratedTurn(any(), any(), anyString());
    }

    private AgentTurn running(DevelopmentAutomation automation)
    {
        AgentTurnSettings settings = new AgentTurnSettings(Optional.of("model"), Optional.empty(), AgentPermissionPolicy.AUTO,
                Duration.ofMinutes(10), ImmutableList.of(), Optional.empty(), Optional.of(automation));
        return AgentTurn.accepted(TurnId.random(), taskId, "user-" + history.size(), settings, NOW)
                .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider"), Optional.empty());
    }

    private DevelopmentTurnOrigin origin(DevelopmentTurnOrigin.Kind kind)
    {
        return new DevelopmentTurnOrigin(kind, source.id());
    }

    private DevelopmentLocalFacts local(String head, boolean dirty)
    {
        return new DevelopmentLocalFacts(route.headBranch(), head, head, head + ":" + dirty, dirty ? 1 : 0, 0, 0, dirty ? 1 : 0, head.equals(BASE) ? 0 : 1,
                !head.equals(BASE), false);
    }

    private static DevelopmentResultReport report(boolean ready, Optional<DevelopmentStopAfter> limit)
    {
        return new DevelopmentResultReport(ready, ready ? Optional.empty() : Optional.of("Which implementation?"), limit);
    }

    private GitHubPullRequestIdentity pr(String head, boolean draft)
    {
        return new GitHubPullRequestIdentity(new PullRequestRef("owner", "repo", 7), "https://github.com/owner/repo/pull/7", route.headRepository(), route.headBranch(), head,
                route.baseRepository(), route.baseBranch(), BASE, GitHubPullRequestIdentity.State.OPEN, draft, "owner");
    }

    private static DevelopmentPublicationFacts publication(Optional<String> head, Optional<GitHubPullRequestIdentity> pr)
    {
        return new DevelopmentPublicationFacts(head, pr.stream().collect(ImmutableList.toImmutableList()));
    }
}
