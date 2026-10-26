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
import com.bytequay.agent.core.AutomationLevel;
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
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.ReviewThreadReply;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.StartCiAutofixCommand;
import com.bytequay.app.discussion.ReviewFixService;
import com.bytequay.app.repository.github.CreatedPullRequest;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static com.bytequay.agent.core.DevelopmentTurnOrigin.Kind.AUTO_COMMIT;
import static com.bytequay.agent.core.DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR;
import static com.bytequay.agent.core.DevelopmentTurnOrigin.Kind.REFINE_SUMMARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestDevelopmentFollowupCoordinator
{
    private static final Instant NOW = Instant.EPOCH;
    private static final DevelopmentAutomation FULL = new DevelopmentAutomation(true, AutomationLevel.CI_FIX, Optional.of(AgentLaneId.CLAUDE_CLI));
    private static final DevelopmentAutomation DRAFT_ONLY = new DevelopmentAutomation(true, AutomationLevel.DRAFT_PR, Optional.empty());
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private static final String NEXT = "c".repeat(40);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final AgentTurnStore turnStore = mock(AgentTurnStore.class);
    private final DevelopmentGitInspector git = mock(DevelopmentGitInspector.class);
    private final CiAutofixService ci = mock(CiAutofixService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final GitHubPullRequestWriteClient writes = mock(GitHubPullRequestWriteClient.class);
    private final Clock clock = mock(Clock.class);
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private final List<AgentTurn> history = new ArrayList<>();
    // Heads the task branch has pointed at, as its reflog records them.
    private final Set<String> held = new HashSet<>();
    private final DevelopmentBranchService branches = mock(DevelopmentBranchService.class);
    private final ReviewFixService reviewFixes = mock(ReviewFixService.class);
    private List<ReviewThreadReply> reviewReplies = ImmutableList.of();
    private final DevelopmentFollowupCoordinator coordinator = new DevelopmentFollowupCoordinator(
            turns, turnStore, git, pending::add, ci, credentials, writes, clock, branches, reviewFixes);
    private final TaskId taskId = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("owner", "repo"),
            Path.of("/tmp", taskId.toString()), "bytequay/task/" + taskId, BASE);
    private DevelopmentPublicationRoute route = new DevelopmentPublicationRoute(new RepoRef("owner", "repo"), new RepoRef("owner", "repo"),
            "https://github.com/owner/repo.git", "origin", "main", BASE, worktree.generatedBranch(), Optional.empty());
    private AgentTask task = created(taskId, true, "Develop", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO).withDevelopmentAutomation(FULL), NOW);
    private AgentTurn source;
    private DevelopmentTurnCompletion outcome;

    @BeforeEach
    void setUp()
    {
        when(clock.instant()).thenReturn(NOW);
        when(turnStore.list(taskId)).thenAnswer(ignored -> ImmutableList.copyOf(history));
        when(turnStore.update(any())).thenAnswer(call -> {
            AgentTurn updated = call.getArgument(0);
            for (int index = 0; index < history.size(); index++) {
                if (history.get(index).id().equals(updated.id())) {
                    history.set(index, updated);
                }
            }
            return updated;
        });
        when(turnStore.findActive(taskId)).thenAnswer(ignored -> history.stream()
                .filter(turn -> turn.state() == TurnState.ACCEPTED || turn.state() == TurnState.RUNNING).findFirst());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()));
        when(git.isAncestor(eq(worktree), anyString(), anyString())).thenAnswer(call -> {
            String ancestor = call.getArgument(1);
            String descendant = call.getArgument(2);
            return ancestor.equals(descendant) || ancestor.equals(BASE) || (ancestor.equals(HEAD) && descendant.equals(NEXT));
        });
        when(git.branchHeld(eq(worktree), anyString())).thenAnswer(call -> held.contains(call.<String>getArgument(1)));
        when(ci.startDevelopment(any(), any(), any(), any())).thenAnswer(ignored -> ciRun());
        when(credentials.resolveGitHubToken("workspace")).thenReturn("token");
        doAnswer(call -> {
            Runnable action = call.getArgument(2);
            action.run();
            return null;
        }).when(turns).executeDevelopmentAction(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void questionRefinesChangedWorkWithoutReleasingDelivery(boolean dirty)
    {
        prepare(local(BASE, false), local(HEAD, dirty), FULL, false);
        advance();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(REFINE_SUMMARY)), anyString());
        verify(git, never()).inspectPublication(any(), any());
        verify(turns, never()).executeDevelopmentAction(any(), any(), any());
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verifyNoInteractions(branches, writes);
        noFollowup();
    }

    @Test
    void localOnlyChangedWorkRefinesWithoutAReadinessReport()
    {
        prepare(local(BASE, false), local(HEAD, true), DevelopmentAutomation.LOCAL_ONLY, true);
        AgentTurn noReport = AgentTurn.accepted(source.id(), taskId, source.idempotencyKey(), source.settings().orElseThrow(), NOW)
                .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider"), Optional.empty())
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        history.set(history.size() - 1, noReport);
        outcome = new DevelopmentTurnCompletion(task, noReport, true, Optional.empty());
        advance();
        verify(turns).reportDevelopmentProgress(taskId, source.id(), "Refining summary…");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(REFINE_SUMMARY)), prompt.capture());
        assertThat(prompt.getValue()).contains("including any uncommitted changes", "purpose", "commits", "only these two fields", "design and implementation");
        assertThat(prompt.getValue()).contains("short, complete sentences", "applicable writing skills");
        assertThat(prompt.getValue()).doesNotContain("combinedResult", "and limitations", "brief evidence", "Problem / Approach / Result");
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verifyNoInteractions(writes);
        noFollowup();
    }

    @Test
    void refinementThatChangesCodeDiscardsOnlyItsCandidate()
    {
        prepare(local(BASE, false), local(HEAD, false), DevelopmentAutomation.LOCAL_ONLY, true);
        generated(REFINE_SUMMARY, true, () -> when(git.inspectLocal(worktree, route)).thenReturn(local(NEXT, false)));
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Summary refinement changed"));
        assertThat(history.getLast().developmentResult().orElseThrow().learningSummary()).isEmpty();
        assertThat(history.getLast().developmentResult().orElseThrow().readyForDelivery()).isTrue();
    }

    @Test
    void unchangedReadOnlyTurnDoesNotDeliverExistingWork()
    {
        prepare(local(HEAD, true), local(HEAD, true), FULL, true);
        advance();
        noFollowup();
        verify(turns, never()).startGeneratedTurn(eq(taskId), eq(origin(REFINE_SUMMARY)), anyString());
        verify(git, never()).inspectPublication(any(), any());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void answerCanCommitEarlierDirtyWorkWithoutNewEdits()
    {
        history.add(running(FULL).withDevelopmentResult(report(false))
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW));
        prepare(local(HEAD, true), local(HEAD, true), new DevelopmentAutomation(true, AutomationLevel.COMMIT, Optional.empty()), true);
        when(git.inspectLocal(worktree, route)).thenReturn(local(HEAD, true), local(NEXT, false));
        generated(AUTO_COMMIT, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), contains("Auto commit"));
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void acceptedQueuePrecedesDeliveryAndKeepsOwnershipHandoff()
    {
        prepare(local(BASE, false), local(HEAD, true), FULL, true);
        when(turns.handoffDevelopmentContinuationToQueue(taskId, source.id())).thenReturn(true);
        advance();
        noFollowup();
        verify(turns, never()).startGeneratedTurn(eq(taskId), eq(origin(REFINE_SUMMARY)), anyString());
        verify(git, never()).inspectLocal(any(), any());
        verify(turns, never()).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void localOnlyQueueCoalescesChangesIntoTheFinalSummary()
    {
        prepare(local(BASE, false), local(HEAD, true), DevelopmentAutomation.LOCAL_ONLY, true);
        TurnId previous = source.id();
        when(turns.handoffDevelopmentContinuationToQueue(taskId, previous)).thenAnswer(ignored -> {
            prepare(local(HEAD, true), local(HEAD, true), DevelopmentAutomation.LOCAL_ONLY, true);
            return true;
        });
        advance();
        verify(turns, never()).startGeneratedTurn(eq(taskId), eq(new DevelopmentTurnOrigin(REFINE_SUMMARY, previous)), anyString());
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(REFINE_SUMMARY)), anyString());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verifyNoInteractions(writes);
    }

    @Test
    void queuedTurnInheritsPendingWorkFromThePreviousAcceptedBaseline()
    {
        var commitOnly = new DevelopmentAutomation(true, AutomationLevel.COMMIT, Optional.empty());
        prepare(local(BASE, false), local(HEAD, true), commitOnly, true);
        TurnId previous = source.id();
        when(turns.handoffDevelopmentContinuationToQueue(taskId, previous)).thenAnswer(ignored -> {
            prepare(local(HEAD, true), local(HEAD, true), commitOnly, true);
            return true;
        });
        advance();
        when(git.inspectLocal(worktree, route)).thenReturn(local(HEAD, true), local(NEXT, false));
        generated(AUTO_COMMIT, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), anyString());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).finishDevelopmentContinuation(taskId, previous);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void queuedTurnInheritsPublishedWorkAndControlsWhetherCiStarts(boolean autoCiFix)
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);
        TurnId previous = source.id();
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        when(turns.handoffDevelopmentContinuationToQueue(taskId, previous)).thenAnswer(ignored -> {
            prepare(local(HEAD, false), local(HEAD, false), autoCiFix ? FULL : DRAFT_ONLY, true);
            return true;
        });

        advance();

        verify(ci, never()).startDevelopment(any(), any(), any(), any());

        verifyNoInteractions(writes);
        noFollowup();
        verify(turns, never()).finishDevelopmentContinuation(any(), any());

        advance();

        if (autoCiFix) {
            verify(ci).startDevelopment(any(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        }
        else {
            verify(ci, never()).startDevelopment(any(), any(), any(), any());
            verifyNoInteractions(writes);
        }
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).finishDevelopmentContinuation(taskId, previous);
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void commitsThenPublishesExactlyOnceAndVerifiesBothResults()
    {
        prepare(local(BASE, false), local(BASE, true), FULL, true);
        when(git.inspectLocal(worktree, route)).thenReturn(local(BASE, true), local(HEAD, false), local(HEAD, false));
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()), publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        generated(AUTO_COMMIT, true);
        generated(CREATE_DRAFT_PR, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), contains("Do not push"));
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), contains(DevelopmentResultBridge.PUBLISH_TOOL_NAME));
        verify(ci).startDevelopment(any(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        verify(writes, never()).markReadyForReview(any(), any());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void dirtyWorkWithAutoCommitDisabledDoesNotPublishCommittedSubset()
    {
        prepare(local(BASE, false), local(HEAD, true), new DevelopmentAutomation(false, AutomationLevel.CI_FIX, Optional.empty()), true);
        advance();
        noFollowup();
        verify(git, never()).inspectPublication(any(), any());
    }

    @Test
    void noCommittedDiffDoesNotCreatePullRequest()
    {
        DevelopmentLocalFacts emptyDiff = new DevelopmentLocalFacts(route.headBranch(), HEAD, "different", 0, 1, false, false);
        prepare(local(BASE, false), emptyDiff, FULL, true);
        advance();
        noFollowup();
    }

    @Test
    void disabledPublicationAndCiLeaveCommitsLocal()
    {
        prepare(local(BASE, false), local(HEAD, false), new DevelopmentAutomation(true, AutomationLevel.COMMIT, Optional.empty()), true);
        advance();
        noFollowup();
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verifyNoInteractions(writes);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void autoCommitFinishesLocallyWhenPublicationAndCiAreDisabled()
    {
        prepare(local(BASE, false), local(BASE, true), new DevelopmentAutomation(true, AutomationLevel.COMMIT, Optional.empty()), true);
        when(git.inspectLocal(worktree, route)).thenReturn(local(BASE, true), local(HEAD, false));
        generated(AUTO_COMMIT, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), anyString());
        verify(turns, never()).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verifyNoInteractions(writes);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void commitQuestionStopsBeforePublicationAndDoesNotFail()
    {
        prepare(local(BASE, false), local(BASE, true), FULL, true);
        generated(AUTO_COMMIT, false);
        advance();
        verify(turns, never()).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verifyNoInteractions(writes);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void dirtyCommitResultFailsWithoutAnotherAttempt()
    {
        prepare(local(BASE, false), local(BASE, true), FULL, true);
        generated(AUTO_COMMIT, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), anyString());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("left uncommitted"));
    }

    @Test
    void existingExactPublishedRevisionDoesNotCreateAnotherPr()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        advance();
        noFollowup();
        verify(ci).startDevelopment(any(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        verify(writes, never()).markReadyForReview(any(), any());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void pushOnlyTurnStartsCiForTheRewrittenPublishedHead()
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
        prepare(local(HEAD, false), local(HEAD, false), new DevelopmentAutomation(true, AutomationLevel.CI_FIX, Optional.empty()), true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));

        advance();

        verify(ci).startDevelopment(any(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void pullRequestOnlyTurnStartsCiForTheAlreadyPushedHead()
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.empty()));
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));

        advance();

        verify(ci).startDevelopment(any(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void reviewRepliesGoToTheReviewServiceWithWhetherTheFixIsThePushedHead()
    {
        DevelopmentAutomation reviewFix = new DevelopmentAutomation(true, AutomationLevel.REVIEW_FIX, Optional.empty());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.empty()));
        reviewReplies = ImmutableList.of(new ReviewThreadReply(11, "Renamed it.", true));
        prepare(local(HEAD, false), local(HEAD, false), reviewFix, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));

        advance();

        verify(reviewFixes).publish(taskId, source.id(), true);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());

        // Below Auto-Review fix nothing is handed over.
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);
        advance();
        verifyNoMoreInteractions(reviewFixes);
    }

    @Test
    void repliesSavedEarlierArePostedByALaterDeliveryThatPushes()
    {
        DevelopmentAutomation reviewFix = new DevelopmentAutomation(true, AutomationLevel.REVIEW_FIX, Optional.empty());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.empty()));
        when(reviewFixes.awaitingPost(taskId)).thenReturn(true);
        prepare(local(HEAD, false), local(HEAD, false), reviewFix, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));

        advance();

        verify(reviewFixes).publish(taskId, source.id(), true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unchangedPublishedHeadDoesNotRestartCiWhenDraftStatusChanges(boolean draft)
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, draft))));

        advance();

        verify(ci, never()).startDevelopment(any(), any(), any(), any());

        verifyNoInteractions(writes);
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void unchangedUnpublishedHeadDoesNotPublishExistingWork()
    {
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);

        advance();

        verify(ci, never()).startDevelopment(any(), any(), any(), any());

        verifyNoInteractions(writes);
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void workARestartLeftUnpublishedIsPublishedByTheNextTurn()
    {
        task = task.withSettledDevelopmentContinuation(true, NOW);
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()), publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        generated(CREATE_DRAFT_PR, true);

        advance();

        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), contains(DevelopmentResultBridge.PUBLISH_TOOL_NAME));
        verify(ci).startDevelopment(any(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void workARestartLeftUncommittedIsCommittedByTheNextTurn()
    {
        task = task.withSettledDevelopmentContinuation(true, NOW);
        prepare(local(HEAD, true), local(HEAD, true), new DevelopmentAutomation(true, AutomationLevel.COMMIT, Optional.empty()), true);
        when(git.inspectLocal(worktree, route)).thenReturn(local(HEAD, true), local(NEXT, false));
        generated(AUTO_COMMIT, true);

        advance();

        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), contains("Auto commit"));
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void pushOnlyTurnDoesNotStartDisabledCi()
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
        prepare(local(HEAD, false), local(HEAD, false), DRAFT_ONLY, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));

        advance();

        verify(ci, never()).startDevelopment(any(), any(), any(), any());

        verifyNoInteractions(writes);
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void pushOnlyTurnRequiresTheAcceptedRemoteBaseline()
    {
        when(git.inspectPublication(worktree, route)).thenThrow(new IllegalStateException("Authentication unavailable"));
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);
        doReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true)))).when(git).inspectPublication(worktree, route);

        advance();

        verify(ci, never()).startDevelopment(any(), any(), any(), any());

        verifyNoInteractions(writes);
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Remote baseline unavailable: Authentication unavailable"));
    }

    @Test
    void pushOnlyTurnCannotAttachCiToAPrWithAnotherHead()
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(NEXT, true))));

        advance();

        verify(ci, never()).startDevelopment(any(), any(), any(), any());

        verifyNoInteractions(writes);
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Pull request and remote branch revisions do not agree"));
    }

    @Test
    void unchangedLocalWorkCannotAdoptAnotherPublishedHead()
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(BASE), Optional.of(pr(BASE, true))));
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));

        advance();

        verify(ci, never()).startDevelopment(any(), any(), any(), any());

        verifyNoInteractions(writes);
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("The published revision does not match the clean task revision"));
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void explicitCliPushCreatesTheMissingDraftWithoutPushingAgain(boolean existingRemote, boolean localChanged)
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(existingRemote ? Optional.of(BASE) : Optional.empty(), Optional.empty()));
        prepare(local(localChanged ? BASE : HEAD, false), local(HEAD, false), DRAFT_ONLY, true);
        AtomicReference<DevelopmentPublicationFacts> remote = trackedRemote(Optional.of(HEAD), Optional.empty());
        generated(CREATE_DRAFT_PR, true, () -> coordinator.publishDraftPullRequest(taskId.toString(), "Add a thing", "Body"));

        advance();

        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verify(git, never()).pushDevelopmentHead(any(), any(), anyString(), any());
        verify(writes).createPullRequest("token", route.baseRepository(), route.headBranch(), route.baseBranch(), "Add a thing", "Body", true);
        assertThat(remote.get().matchingPullRequests()).containsExactly(pr(HEAD, true));
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
    }

    @Test
    void laterRevisionReusesExistingPr()
    {
        GitHubPullRequestIdentity existing = pr(HEAD, false);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(existing)));
        prepare(local(HEAD, false), local(NEXT, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(
                publication(Optional.of(HEAD), Optional.of(existing)), publication(Optional.of(NEXT), Optional.of(pr(NEXT, false))));
        generated(CREATE_DRAFT_PR, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), contains("already tracks this branch"));
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rewrittenPublishedHistoryIsPushedAndMonitoredAtTheNewHead(boolean unpublishedCommit)
    {
        String startingHead = unpublishedCommit ? "d".repeat(40) : NEXT;
        GitHubPullRequestIdentity existing = pr(NEXT, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(existing)));
        held.add(NEXT);
        DevelopmentLocalFacts rewritten = new DevelopmentLocalFacts(route.headBranch(), HEAD, HEAD + ":false", 0, 1, true, false);
        prepare(local(startingHead, false), rewritten, FULL, true);
        AtomicReference<DevelopmentPublicationFacts> remote = trackedRemote(Optional.of(NEXT), Optional.of(existing));
        generated(CREATE_DRAFT_PR, true, () -> coordinator.publishDraftPullRequest(taskId.toString(), "Remove commit trailers", "Body"));

        advance();

        var order = inOrder(git, ci, turns);
        order.verify(git).pushDevelopmentHead(worktree, route, HEAD, Optional.of(NEXT));
        order.verify(ci).startDevelopment(any(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        order.verify(turns).finishDevelopmentContinuation(taskId, source.id());
        assertThat(remote.get().remoteHeadSha()).contains(HEAD);
        verify(writes, never()).createPullRequest(anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void queuedTurnPublishesTheRewriteItsPreviousTurnLeftUnpushed()
    {
        GitHubPullRequestIdentity existing = pr(NEXT, true);
        AtomicReference<DevelopmentPublicationFacts> remote = trackedRemote(Optional.of(NEXT), Optional.of(existing));
        prepare(local(NEXT, false), local(HEAD, false), FULL, true);
        TurnId rewrite = source.id();
        when(turns.handoffDevelopmentContinuationToQueue(taskId, rewrite)).thenAnswer(ignored -> {
            prepare(local(HEAD, false), local(HEAD, false), FULL, true);
            return true;
        });
        advance();
        generated(CREATE_DRAFT_PR, true, () -> coordinator.publishDraftPullRequest(taskId.toString(), "Split commits", "Body"));

        advance();

        verify(git).pushDevelopmentHead(worktree, route, HEAD, Optional.of(NEXT));
        assertThat(remote.get().remoteHeadSha()).contains(HEAD);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void remoteCommitsTheTaskNeverHeldCannotBeReplaced()
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
        prepare(local(BASE, false), local(HEAD, false), FULL, true);

        advance();

        noFollowup();
        verify(git, never()).pushDevelopmentHead(any(), any(), anyString(), any());
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("The PR branch has commits this task never had"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rewriteCannotReplaceARemoteChangeDuringDevelopmentOrPublication(boolean duringPublication)
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
        prepare(local(NEXT, false), local(HEAD, false), FULL, true);
        DevelopmentPublicationFacts changed = publication(Optional.of(BASE), Optional.of(pr(BASE, true)));
        if (duringPublication) {
            AtomicReference<DevelopmentPublicationFacts> remote = trackedRemote(Optional.of(NEXT), Optional.of(pr(NEXT, true)));
            generated(CREATE_DRAFT_PR, true, () -> {
                remote.set(changed);
                coordinator.publishDraftPullRequest(taskId.toString(), "Remove commit trailers", "Body");
            });
        }
        else {
            when(git.inspectPublication(worktree, route)).thenReturn(changed);
        }

        advance();

        verify(git, never()).pushDevelopmentHead(any(), any(), anyString(), any());
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("remote branch changed"));
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
    }

    @Test
    void externallyChangedRemoteIsRefusedBeforePublication()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.empty()));
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("remote branch changed"));
    }

    @ParameterizedTest
    @EnumSource(value = GitHubPullRequestIdentity.State.class, names = {"CLOSED", "MERGED"})
    void closedOrMergedPrCannotBeReplaced(GitHubPullRequestIdentity.State state)
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
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
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        when(git.inspectLocal(worktree, route)).thenReturn(local(HEAD, false), local(NEXT, false));
        generated(CREATE_DRAFT_PR, true);
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("changed the verified local revision"));
    }

    @Test
    void publicationQuestionReturnsControlWithoutAssumingItsRemoteResult()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        generated(CREATE_DRAFT_PR, false);
        advance();
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verifyNoInteractions(writes);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void uncertainPublicationFailsWithoutRetrying()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        when(turns.startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Response lost")));
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Remote result may have changed"));
    }

    @Test
    void acceptingATurnReadsTheRemoteBesideItInsteadOfInsideSend()
    {
        source = running(FULL);
        history.add(source);
        when(git.capture(task)).thenReturn(new DevelopmentBaseline(worktree, route, local(BASE, false)));

        coordinator.accepted(task, source);

        verify(git, never()).inspectPublication(any(), any());
        assertThat(pending).hasSize(1);
        finishRemoteRead();
        verify(git).inspectPublication(worktree, route);
    }

    /** Accepts the turn, then completes that same turn without a report, leaving the checkout at {@code after}. */
    private void unreported(DevelopmentLocalFacts after)
    {
        source = running(FULL);
        history.add(source);
        when(git.capture(task)).thenReturn(new DevelopmentBaseline(worktree, route, local(BASE, false)));
        coordinator.accepted(task, source);
        finishRemoteRead();
        source = source.finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        history.set(history.size() - 1, source);
        outcome = new DevelopmentTurnCompletion(task, source, true, Optional.empty());
        when(turns.ownsDevelopmentContinuation(taskId, source.id())).thenReturn(true);
        when(git.inspectLocal(worktree, route)).thenReturn(after);
    }

    @Test
    void missingReportOnAnUntouchedCheckoutEndsTheTurnQuietly()
    {
        unreported(local(BASE, false));

        advance();

        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
        noFollowup();
    }

    @Test
    void missingReportAfterRealWorkStillFails()
    {
        unreported(local(HEAD, false));

        advance();

        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("did not report"));
        noFollowup();
    }

    @Test
    void missingReportFailsInsteadOfAssumingReadiness()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        AgentTurn noReport = running(FULL).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        outcome = new DevelopmentTurnCompletion(task, noReport, true, Optional.empty());
        when(turns.ownsDevelopmentContinuation(taskId, noReport.id())).thenReturn(true);
        coordinator.completed(outcome);
        pending.remove().run();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(noReport.id()), contains("did not report"));
        noFollowup();
    }

    @Test
    void interruptedApprovalEndsAutomationWithItsReason()
    {
        prepare(local(BASE, false), local(HEAD, true), FULL, true);
        AgentTurn interrupted = running(FULL).finish(TurnState.INTERRUPTED, AgentMetrics.empty(), Optional.of("Approval request timed out"), NOW);
        outcome = new DevelopmentTurnCompletion(task, interrupted, true, Optional.empty());
        when(turns.ownsDevelopmentContinuation(taskId, interrupted.id())).thenReturn(true);

        advance();

        verify(turns).failDevelopmentContinuation(taskId, interrupted.id(), "Automation stopped. Check development result: Approval request timed out");
        verify(git, never()).inspectLocal(any(), any());
        verify(git, never()).inspectPublication(any(), any());
        noFollowup();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cleanupAndQuiescenceAreBothRequired(boolean quiescent)
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        outcome = new DevelopmentTurnCompletion(task, source, quiescent, quiescent ? Optional.of("Cleanup failed") : Optional.empty());
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Automation stopped"));
        verifyNoInteractions(branches);
        noFollowup();
    }

    @Test
    void lostRemoteBaselineAllowsDevelopmentButBlocksPublication()
    {
        when(git.inspectPublication(worktree, route)).thenThrow(new IllegalStateException("Authentication unavailable"));
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Remote baseline unavailable: Authentication unavailable"));
    }

    @Test
    void generatedReadyReportCannotMakeAnUnverifiedPublicationSuccessful()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        generated(CREATE_DRAFT_PR, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("pushed revision was not verified"));
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
    }

    @Test
    void newlyCreatedPrMustBeDraft()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()), publication(Optional.of(HEAD), Optional.of(pr(HEAD, false))));
        generated(CREATE_DRAFT_PR, true);
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("not a draft"));
    }

    @Test
    void wrongBasePrIsNotReused()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
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
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(new DevelopmentPublicationFacts(Optional.of(HEAD), ImmutableList.of(pr(HEAD, true), pr(HEAD, false))));
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Several pull requests"));
    }

    @Test
    void stopDuringBaselineCaptureDoesNotRetainTheAcceptedSnapshot()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true,
                () -> history.replaceAll(turn -> turn.finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("Stopped"), NOW)));
        advance();
        noFollowup();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("baseline is unavailable"));
    }

    @Test
    void revokedOwnershipStopsScheduledCallback()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        when(turns.ownsDevelopmentContinuation(taskId, source.id())).thenReturn(false);
        advance();
        noFollowup();
        verify(git, never()).inspectLocal(any(), any());
    }

    @Test
    void stoppingDuringInspectionCannotDispatchOrSettleAnotherOwner()
    {
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
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
        prepare(local(BASE, false), local(HEAD, false), FULL, true);
        var rejected = new DevelopmentFollowupCoordinator(turns, turnStore, git, ignored -> {
            throw new RejectedExecutionException("stopped");
        },
                ci, credentials, writes, clock, branches, reviewFixes);
        rejected.completed(outcome);
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Could not start"));
    }

    @Test
    void publishedBodiesCarryNoProviderSignOff()
    {
        String signed = """
                Three small cleanups, one per commit.

                🤖 Generated with [Claude Code](https://claude.com/claude-code)
                Co-Authored-By: Claude <noreply@anthropic.com>""";
        assertThat(DevelopmentFollowupCoordinator.withoutAttribution(signed))
                .isEqualTo("Three small cleanups, one per commit.");
        // A body that never signed itself is published exactly as the agent wrote it.
        assertThat(DevelopmentFollowupCoordinator.withoutAttribution("Body.\n\n- one\n- two\n"))
                .isEqualTo("Body.\n\n- one\n- two");
        // The model name in the trailer must not let it through, and the title is scrubbed too.
        assertThat(DevelopmentFollowupCoordinator.withoutAttribution("Clean up nits\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>")).isEqualTo("Clean up nits");
    }

    @Test
    void conciseFollowupsKeepTheirScopeAndDistinctAuthorizationRules()
    {
        var baseline = new DevelopmentBaseline(worktree, route, local(BASE, false));
        String summary = DevelopmentFollowupCoordinator.summaryPrompt(baseline, local(HEAD, true));
        String commit = DevelopmentFollowupCoordinator.commitPrompt(baseline, local(HEAD, true));
        String publication = DevelopmentFollowupCoordinator.publicationPrompt(baseline, local(HEAD, false), Optional.empty());
        for (String prompt : ImmutableList.of(summary, commit, publication)) {
            assertThat(prompt).contains(worktree.canonicalWorktree().toString(), route.headBranch(), HEAD, "report_development_result");
        }
        assertThat(summary).contains(BASE, "Read the current diff", "Reporting only", "do not edit files, create or rewrite commits",
                "push, publish, run repairs, or start a review", "Keep all text private", "never write a summary file",
                "Preserve the existing question and pending user decision", "do not release held work", "only purpose and",
                "one per actual commit, with full sha, title and summary", "does not authorize delivery of held work",
                "frozen comparison base..HEAD, excluding the base and earlier history", "commits list when that range is empty");
        assertThat(commit).contains("accepted Auto commit setting", "only the completed task changes", "Verify ownership, branch and HEAD",
                "stop and report any mismatch", "Preserve existing commits", "never amend, rebase, reset, or change branches",
                "Do not discard changes", "Exclude ignored build output and credentials", "local validation limitations", "Do not push or create a pull request",
                "readyForDelivery=false if a user decision remains", "ByteQuay controls later stages using accepted automation settings");
        assertThat(publication).contains("accepted Auto draft PR setting", "single publication of the verified revision", "Recheck branch, exact HEAD",
                "clean staged/unstaged/untracked status (excluding ignored build output)", "no open Git operation", "Stop and report any mismatch",
                "exactly once with title and body as tool input", "never write a body file", "its own lease", "draft PR creation or existing PR update",
                "Do not commit, edit code, amend, rebase, reset, switch branches", "independent CI repair loop", "stop without retrying",
                "Keep local execution details private", "commands run, test classes, counts, pass/fail results", "build or validation outcomes",
                "logs, tooling, sandbox and environment limitations", "Never put these in the PR", "Testing, Validation or Test plan",
                "readyForDelivery=false if a user decision is needed");
        assertThat(summary.length()).isLessThan(1600);
        assertThat(commit.length()).isLessThan(1250);
        assertThat(publication.length()).isLessThan(2100);
    }

    @Test
    void publicationPromptAsksForNoToolingNotesAndNoSignOff()
    {
        String prompt = DevelopmentFollowupCoordinator.publicationPrompt(new DevelopmentBaseline(worktree, route, local(BASE, false)), local(HEAD, false), Optional.empty());
        assertThat(prompt).contains(
                "Fill relevant sections only", "leave other sections exactly as the template leaves them",
                "attribution, co-authorship, or generated-with trailer");
        assertThat(prompt).doesNotContain("preserve local-check limitations", "local-check limitations");
    }

    @Test
    void publishIsRefusedWhenByteQuayIsNotRequestingADraftPullRequest()
    {
        assertThatThrownBy(() -> coordinator.publishDraftPullRequest(taskId.toString(), "Title", "Body"))
                .hasMessageContaining("not requesting a draft pull request");
        verifyNoInteractions(writes);
    }

    @Test
    void publishPushesTheVerifiedRevisionAndOpensADraftPullRequest()
    {
        prepare(local(BASE, false), local(HEAD, false), DRAFT_ONLY, true);
        AtomicReference<DevelopmentPublicationFacts> remote = trackedRemote(Optional.empty(), Optional.empty());
        generated(CREATE_DRAFT_PR, true, () -> assertThat(
                coordinator.publishDraftPullRequest(taskId.toString(), "Add a thing", "Body"))
                .contains(HEAD, "created draft pull request"));

        advance();

        verify(git).pushDevelopmentHead(worktree, route, HEAD, Optional.empty());
        verify(writes).createPullRequest("token", route.baseRepository(), route.headBranch(), route.baseBranch(), "Add a thing", "Body", true);
        assertThat(remote.get().remoteHeadSha()).contains(HEAD);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void publishesTheRenamedBranchWithoutLosingTheStartingRevision()
    {
        prepare(local(BASE, false), local(HEAD, false), DRAFT_ONLY, true);
        TaskWorktree renamed = task.withBranch("owner/bytequay/convert-parquet-time-millis-to-micro", NOW).taskWorktree().orElseThrow();
        when(branches.prepare(task)).thenAnswer(ignored -> {
            coordinator.branchRenamed(taskId, worktree, renamed);
            return task.withBranch(renamed.generatedBranch(), NOW);
        });
        route = route.withHeadBranch(renamed.generatedBranch());
        when(git.inspectLocal(renamed, route)).thenReturn(local(HEAD, false));
        AtomicReference<DevelopmentPublicationFacts> remote = trackedRemote(Optional.empty(), Optional.empty());
        when(git.inspectPublication(renamed, route)).thenAnswer(ignored -> remote.get());
        doAnswer(ignored -> {
            remote.set(publication(Optional.of(HEAD), Optional.empty()));
            return null;
        }).when(git).pushDevelopmentHead(renamed, route, HEAD, Optional.empty());
        generated(CREATE_DRAFT_PR, true, () -> assertThat(coordinator.publishDraftPullRequest(taskId.toString(), "Convert Parquet time", "Body"))
                .contains(HEAD, "created draft pull request"));

        advance();

        verify(git).pushDevelopmentHead(renamed, route, HEAD, Optional.empty());
        verify(writes).createPullRequest("token", route.baseRepository(), renamed.generatedBranch(), "main", "Convert Parquet time", "Body", true);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
        verify(git, never()).pushDevelopmentHead(eq(worktree), any(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDeferredNameOnlyChangeDoesNotBecomeUndeliveredCode(boolean hasReport)
    {
        prepare(local(BASE, false), local(BASE, false), DRAFT_ONLY, true);
        if (!hasReport) {
            AgentTurn noReport = AgentTurn.accepted(source.id(), taskId, source.idempotencyKey(), source.settings().orElseThrow(), NOW)
                    .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider"), Optional.empty())
                    .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
            history.set(history.size() - 1, noReport);
            outcome = new DevelopmentTurnCompletion(task, noReport, true, Optional.empty());
        }
        TaskWorktree renamed = task.withBranch("owner/bytequay/name-the-investigation", NOW).taskWorktree().orElseThrow();
        when(branches.prepare(task)).thenAnswer(ignored -> {
            coordinator.branchRenamed(taskId, worktree, renamed);
            return task.withBranch(renamed.generatedBranch(), NOW);
        });
        route = route.withHeadBranch(renamed.generatedBranch());
        when(git.inspectLocal(renamed, route)).thenReturn(local(BASE, false));

        advance();

        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).startGeneratedTurn(any(), any(), anyString());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
        verifyNoInteractions(writes);
        if (!hasReport) {
            verifyNoInteractions(branches);
            verify(turns, never()).executeDevelopmentAction(any(), any(), any());
        }
    }

    @Test
    void oldRemoteReadCompletingAfterRenameCannotRestoreTheOldRoute()
    {
        source = running(DRAFT_ONLY);
        history.add(source);
        DevelopmentPublicationRoute originalRoute = route;
        when(git.capture(task)).thenReturn(new DevelopmentBaseline(worktree, route, local(BASE, false)));
        coordinator.accepted(task, source);
        Runnable oldRemoteRead = pending.remove();
        source = source.withDevelopmentResult(report(true)).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        history.set(0, source);
        outcome = new DevelopmentTurnCompletion(task, source, true, Optional.empty());
        when(turns.ownsDevelopmentContinuation(taskId, source.id())).thenReturn(true);
        TaskWorktree renamed = task.withBranch("owner/bytequay/name-the-investigation", NOW).taskWorktree().orElseThrow();
        route = route.withHeadBranch(renamed.generatedBranch());
        when(git.inspectLocal(renamed, route)).thenReturn(local(BASE, false));
        when(git.inspectPublication(worktree, originalRoute)).thenThrow(new IllegalStateException("Task worktree is on another branch"));
        when(branches.prepare(task)).thenAnswer(ignored -> {
            coordinator.branchRenamed(taskId, worktree, renamed);
            oldRemoteRead.run();
            return task.withBranch(renamed.generatedBranch(), NOW);
        });

        advance();

        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
        verify(git, never()).inspectLocal(eq(worktree), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishReusesTheTrackingPullRequestInsteadOfOpeningASecond(boolean assigned)
    {
        if (assigned) {
            route = new DevelopmentPublicationRoute(route.headRepository(), route.baseRepository(), route.pushUrl(), route.sourceRemote(),
                    route.baseBranch(), route.baseSha(), "existing-pr", Optional.of(PullRequestRef.of("owner", "repo", 7)));
        }
        GitHubPullRequestIdentity existing = pr(BASE, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(BASE), Optional.of(existing)));
        prepare(local(BASE, false), local(HEAD, false), DRAFT_ONLY, true);
        AtomicReference<DevelopmentPublicationFacts> remote = trackedRemote(Optional.of(BASE), Optional.of(existing));
        generated(CREATE_DRAFT_PR, true, () -> assertThat(
                coordinator.publishDraftPullRequest(taskId.toString(), "Add a thing", "Body"))
                .contains(existing.url(), "already tracks this branch"));

        advance();

        verify(git).pushDevelopmentHead(worktree, route, HEAD, Optional.of(BASE));
        verify(writes, never()).createPullRequest(anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        assertThat(remote.get().remoteHeadSha()).contains(HEAD);
    }

    @Test
    void assignedPullRequestCannotFallBackToCreatingAnother()
    {
        route = new DevelopmentPublicationRoute(route.headRepository(), route.baseRepository(), route.pushUrl(), route.sourceRemote(),
                route.baseBranch(), route.baseSha(), "existing-pr", Optional.of(PullRequestRef.of("owner", "repo", 7)));
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()));
        prepare(local(BASE, false), local(HEAD, false), DRAFT_ONLY, true);

        advance();

        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("assigned pull request is unavailable"));
        verify(git, never()).pushDevelopmentHead(any(), any(), anyString(), any());
        verifyNoInteractions(writes);
    }

    @Test
    void assignedPullRequestPromptsDistinguishLocalAndRemoteBranches()
    {
        route = new DevelopmentPublicationRoute(route.headRepository(), route.baseRepository(), route.pushUrl(), route.sourceRemote(),
                route.baseBranch(), route.baseSha(), "existing-pr", Optional.of(PullRequestRef.of("owner", "repo", 7)));
        DevelopmentBaseline baseline = new DevelopmentBaseline(worktree, route, local(BASE, false));

        assertThat(DevelopmentFollowupCoordinator.commitPrompt(baseline, local(HEAD, false))).contains("Branch: '" + worktree.generatedBranch() + "'");
        assertThat(DevelopmentFollowupCoordinator.summaryPrompt(baseline, local(HEAD, false))).contains("Branch: '" + worktree.generatedBranch() + "'");
        assertThat(DevelopmentFollowupCoordinator.publicationPrompt(baseline, local(HEAD, false), Optional.of(pr(BASE, false))))
                .contains("Branch: '" + worktree.generatedBranch() + "'", "Push repository/branch: owner/repo / 'existing-pr'", "creates no second one");
    }

    @Test
    void publishSendsAForkQualifiedHeadForACrossRepositoryRoute()
    {
        route = new DevelopmentPublicationRoute(new RepoRef("forker", "repo"), new RepoRef("upstream", "repo"),
                "https://github.com/forker/repo.git", "upstream", "release", BASE, worktree.generatedBranch(), Optional.empty());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()));
        prepare(local(BASE, false), local(HEAD, false), DRAFT_ONLY, true);
        trackedRemote(Optional.empty(), Optional.empty());
        generated(CREATE_DRAFT_PR, true, () -> coordinator.publishDraftPullRequest(taskId.toString(), "Add a thing", "Body"));

        advance();

        verify(writes).createPullRequest("token", new RepoRef("upstream", "repo"), "forker:" + route.headBranch(), "release", "Add a thing", "Body", true);
    }

    /**
     * Models the remote the way publication moves it: the push lands the revision, and creating the
     * pull request is what makes one appear on the branch.
     */
    private AtomicReference<DevelopmentPublicationFacts> trackedRemote(Optional<String> remoteHead, Optional<GitHubPullRequestIdentity> existing)
    {
        AtomicReference<DevelopmentPublicationFacts> remote = new AtomicReference<>(publication(remoteHead, existing));
        when(git.inspectPublication(worktree, route)).thenAnswer(ignored -> remote.get());
        doAnswer(call -> {
            remote.set(publication(Optional.of(HEAD), existing.map(ignored -> pr(HEAD, true))));
            return null;
        }).when(git).pushDevelopmentHead(worktree, route, HEAD, remoteHead);
        when(writes.createPullRequest(anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(call -> {
                    remote.set(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
                    return new CreatedPullRequest(1, 7, pr(HEAD, true).url());
                });
        return remote;
    }

    @Test
    void publicationPromptPinsForkRoutingLeaseAndExactObject()
    {
        var fork = new DevelopmentPublicationRoute(new RepoRef("forker", "repo"), new RepoRef("upstream", "repo"), "https://github.com/forker/repo.git",
                "upstream", "release", BASE, route.headBranch(), Optional.empty());
        var baseline = new DevelopmentBaseline(worktree, fork, local(BASE, false));
        String prompt = DevelopmentFollowupCoordinator.publicationPrompt(baseline, local(HEAD, false), Optional.empty());
        assertThat(prompt).contains(HEAD, "upstream/repo", "'release'", "forker/repo", DevelopmentResultBridge.PUBLISH_TOOL_NAME, "Do not commit", "stop without");
        assertThat(prompt).doesNotContain("--force-with-lease", "pr create", "--repo", "--base '");
    }

    @Test
    void publicationPromptTellsTheAgentToPublishThroughTheToolWithoutCredentials()
    {
        String prompt = DevelopmentFollowupCoordinator.publicationPrompt(new DevelopmentBaseline(worktree, route, local(BASE, false)),
                local(HEAD, false), Optional.of(pr(BASE, true)));
        assertThat(prompt).contains(DevelopmentResultBridge.PUBLISH_TOOL_NAME, "already tracks this branch",
                "ByteQuay owns the push and the GitHub write", "never read or ask for credentials");
        assertThat(prompt).contains("Never run git push, a GitHub CLI command");
        assertThat(prompt).doesNotContain("--force-with-lease", "pr create", "--body-file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude", "codex"})
    void coordinatorStartsCiForThePublishedHeadAndFinishesWithoutWaiting(String laneId)
    {
        AgentLaneId lane = new AgentLaneId(laneId);
        preparePublished(new DevelopmentAutomation(true, AutomationLevel.CI_FIX, Optional.of(lane)));
        advance();
        var order = inOrder(ci, turns);
        order.verify(ci).startDevelopment(eq(new StartCiAutofixCommand(
                "workspace",
                7,
                lane,
                "development-ci:" + taskId + ":" + HEAD)), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        order.verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(ci, never()).advance(any());
        verify(writes, never()).markReadyForReview(any(), any());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void acceptedTurnStartsFromCiRepairsOfACleanSource()
    {
        when(ci.repairedHead(taskId, BASE)).thenReturn(Optional.of(NEXT));
        when(git.capture(task)).thenReturn(new DevelopmentBaseline(worktree, route, local(BASE, false)), new DevelopmentBaseline(worktree, route, local(NEXT, false)));
        AgentTurn turn = running(FULL);
        history.add(turn);
        coordinator.accepted(task, turn);
        verify(git).synchronizeSource(new DevelopmentBaseline(worktree, route, local(BASE, false)), BASE, NEXT);
        verify(git, times(2)).capture(task);
    }

    @Test
    void acceptedTurnKeepsADirtySourceWhereItIs()
    {
        when(ci.repairedHead(taskId, BASE)).thenReturn(Optional.of(NEXT));
        when(git.capture(task)).thenReturn(new DevelopmentBaseline(worktree, route, local(BASE, true)));
        AgentTurn turn = running(FULL);
        history.add(turn);
        coordinator.accepted(task, turn);
        verify(git, never()).synchronizeSource(any(), anyString(), anyString());
    }

    @Test
    void failedRepairSynchronizationStillStartsTheTurn()
    {
        when(ci.repairedHead(taskId, BASE)).thenReturn(Optional.of(NEXT));
        when(git.capture(task)).thenReturn(new DevelopmentBaseline(worktree, route, local(BASE, false)));
        when(git.synchronizeSource(any(), anyString(), anyString())).thenThrow(new IllegalStateException("Source HEAD changed before synchronization"));
        AgentTurn turn = running(FULL);
        history.add(turn);
        coordinator.accepted(task, turn);
        verify(git, times(1)).capture(task);
        verify(turnStore).findActive(taskId);
    }

    @Test
    void ciAttachedAfterADelayKeepsTheTurnsCompletionAsItsAuthorization()
    {
        preparePublished(FULL);
        coordinator.completed(outcome);
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(24)));
        pending.remove().run();
        verify(ci).startDevelopment(any(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ciUsesTheDevelopmentAgentUnlessExplicitlyOverridden(boolean explicitCiAgent)
    {
        preparePublished(explicitCiAgent ? FULL : new DevelopmentAutomation(true, AutomationLevel.CI_FIX, Optional.empty()));
        advance();
        ArgumentCaptor<StartCiAutofixCommand> command = ArgumentCaptor.forClass(StartCiAutofixCommand.class);
        verify(ci).startDevelopment(command.capture(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        assertThat(command.getValue().laneId()).isEqualTo(explicitCiAgent ? AgentLaneId.CLAUDE_CLI : AgentLaneId.CODEX_CLI);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
        noFollowup();
    }

    @Test
    void disablingCiLeavesTheVerifiedDraft()
    {
        preparePublished(new DevelopmentAutomation(true, AutomationLevel.DRAFT_PR, Optional.empty()));
        advance();
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        verifyNoInteractions(writes);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptedCiSettingControlsGeneratedDraftContinuation(boolean autoCiFix)
    {
        prepare(local(BASE, false), local(HEAD, false), new DevelopmentAutomation(true, autoCiFix ? AutomationLevel.CI_FIX : AutomationLevel.DRAFT_PR, Optional.empty()), true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()), publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        generated(CREATE_DRAFT_PR, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        if (autoCiFix) {
            verify(ci).startDevelopment(any(), eq(taskId), eq(pr(HEAD, true)), eq(NOW));
        }
        else {
            verify(ci, never()).startDevelopment(any(), any(), any(), any());
        }
        verify(writes, never()).markReadyForReview(any(), any());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    private void preparePublished(DevelopmentAutomation automation)
    {
        prepare(local(BASE, false), local(HEAD, false), automation, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
    }

    private CiAutofixRun ciRun()
    {
        return CiAutofixRun.create(CiAutofixRunId.random(), "owned-ci",
                new CiAutofixRun.Scope(pr(HEAD, true).pullRequest(), route.headRepository(), "workspace", route.headRepository(), "owner", route.headBranch(), "main",
                        source.settings().orElseThrow().developmentAutomation().orElseThrow().ciLaneId().orElse(task.laneId()).value(), Optional.of(taskId.toString())),
                HEAD, HEAD, BASE, new CiAutofixRun.Authorization(NOW), NOW);
    }

    private void prepare(DevelopmentLocalFacts before, DevelopmentLocalFacts after, DevelopmentAutomation automation, boolean ready)
    {
        prepare(before, after, automation, ready, () -> {});
    }

    /** Accepts and completes a turn, with {@code duringCapture} standing in for what happens while its baseline is captured. */
    private void prepare(DevelopmentLocalFacts before, DevelopmentLocalFacts after, DevelopmentAutomation automation, boolean ready, Runnable duringCapture)
    {
        source = running(automation);
        history.add(source);
        held.add(before.headSha());
        held.add(after.headSha());
        when(git.capture(task)).thenAnswer(ignored -> {
            duringCapture.run();
            return new DevelopmentBaseline(worktree, route, before);
        });
        coordinator.accepted(task, source);
        finishRemoteRead();
        source = source.withDevelopmentResult(report(ready)).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        history.set(history.size() - 1, source);
        outcome = new DevelopmentTurnCompletion(task, source, true, Optional.empty());
        when(turns.ownsDevelopmentContinuation(taskId, source.id())).thenReturn(true);
        when(git.inspectLocal(worktree, route)).thenReturn(after);
        generated(REFINE_SUMMARY, true);
        clearInvocations(git);
    }

    private void generated(DevelopmentTurnOrigin.Kind kind, boolean ready)
    {
        generated(kind, ready, () -> {});
    }

    /** The provider turn the coordinator starts, with {@code duringTurn} standing in for the root agent's tool call. */
    private void generated(DevelopmentTurnOrigin.Kind kind, boolean ready, Runnable duringTurn)
    {
        doAnswer(ignored -> {
            // The generated turn is the active one while the agent works, which is when it may call
            // ByteQuay's tools; it settles only once that work is done.
            AgentTurn active = AgentTurn.accepted(TurnId.random(), taskId, "generated-" + kind, source.settings().orElseThrow(), NOW)
                    .withDevelopmentOrigin(origin(kind))
                    .markDeliveryStarted(NOW).running(new ProviderTurnRef("generated-" + kind), Optional.empty());
            history.add(active);
            duringTurn.run();
            DevelopmentResultReport result = kind == REFINE_SUMMARY
                    ? new DevelopmentResultReport(ready, Optional.empty(), Optional.of(new DevelopmentLearningSummary(
                            "Current task changes", ImmutableList.of(), Optional.empty(), "Focused tests passed", ImmutableList.of())))
                    : report(ready);
            AgentTurn generated = active.withDevelopmentResult(result)
                    .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
            history.set(history.size() - 1, generated);
            return CompletableFuture.completedFuture(new DevelopmentTurnCompletion(task, generated, true, Optional.empty()));
        }).when(turns).startGeneratedTurn(eq(taskId), eq(origin(kind)), anyString());
    }

    private void advance()
    {
        coordinator.completed(outcome);
        pending.remove().run();
    }

    /** Runs the remote read that accepting a turn starts beside it, as it finishes while the turn runs. */
    private void finishRemoteRead()
    {
        while (!pending.isEmpty()) {
            pending.remove().run();
        }
    }

    private void noFollowup()
    {
        verify(turns, never()).startGeneratedTurn(any(), argThat(origin -> origin.kind() != REFINE_SUMMARY), anyString());
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
        return new DevelopmentLocalFacts(route.headBranch(), head, head + ":" + dirty, dirty ? 1 : 0, head.equals(BASE) ? 0 : 1, !head.equals(BASE), false);
    }

    private DevelopmentResultReport report(boolean ready)
    {
        return new DevelopmentResultReport(ready, ready ? Optional.empty() : Optional.of("Which implementation?"), Optional.empty(), reviewReplies);
    }

    private GitHubPullRequestIdentity pr(String head, boolean draft)
    {
        return new GitHubPullRequestIdentity(new PullRequestRef(
                route.baseRepository().owner(),
                route.baseRepository().repo(),
                7), "https://github.com/" + route.baseRepository().fullName() + "/pull/7", route.headRepository(), route.headBranch(), head,
                route.baseRepository(), route.baseBranch(), BASE, GitHubPullRequestIdentity.State.OPEN, draft, "owner");
    }

    private static DevelopmentPublicationFacts publication(Optional<String> head, Optional<GitHubPullRequestIdentity> pr)
    {
        return new DevelopmentPublicationFacts(head, pr.stream().collect(ImmutableList.toImmutableList()));
    }
}
