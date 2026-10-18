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
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiJob;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.GitHubActionsClient;
import com.bytequay.app.ci.StartCiAutofixCommand;
import com.bytequay.app.repository.github.CreatedPullRequest;
import com.bytequay.app.repository.github.GitHubDeliveryChecks;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDevelopmentFollowupCoordinator
{
    private static final Instant NOW = Instant.EPOCH;
    private static final DevelopmentAutomation FULL = new DevelopmentAutomation(true, true, true, Optional.of(AgentLaneId.CLAUDE_CLI));
    private static final DevelopmentAutomation DRAFT_ONLY = new DevelopmentAutomation(true, true, false, Optional.empty());
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private static final String NEXT = "c".repeat(40);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final AgentTurnStore turnStore = mock(AgentTurnStore.class);
    private final DevelopmentGitInspector git = mock(DevelopmentGitInspector.class);
    private final CiAutofixService ci = mock(CiAutofixService.class);
    private final GitHubActionsClient actions = mock(GitHubActionsClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final GitHubPullRequestReadClient pullRequests = mock(GitHubPullRequestReadClient.class);
    private final GitHubPullRequestWriteClient writes = mock(GitHubPullRequestWriteClient.class);
    private String deliveredHead = HEAD;
    private boolean promoted;
    private final Clock clock = mock(Clock.class);
    private final Runnable waitForCi = mock(Runnable.class);
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private final List<AgentTurn> history = new ArrayList<>();
    private final DevelopmentFollowupCoordinator coordinator = new DevelopmentFollowupCoordinator(
            turns, turnStore, git, pending::add, ci, actions, credentials, pullRequests, writes, clock, waitForCi);
    private final TaskId taskId = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("owner", "repo"),
            Path.of("/tmp", taskId.toString()), "bytequay/task/" + taskId, BASE);
    private DevelopmentPublicationRoute route = new DevelopmentPublicationRoute(new RepoRef("owner", "repo"), new RepoRef("owner", "repo"),
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
        when(ci.startDevelopment(any(), any(), any(), any(), any())).thenAnswer(call -> {
            GitHubPullRequestIdentity published = call.getArgument(3);
            deliveredHead = published.headSha();
            return ciRun().finish(CiAutofixRun.FinishReason.GREEN, "Checks passed", NOW);
        });
        when(actions.inspect(any())).thenAnswer(ignored -> actionsSnapshot(deliveredHead, false));
        when(credentials.resolveGitHubToken("workspace")).thenReturn("token");
        when(pullRequests.fetchDeliveryChecks(eq("token"), any())).thenAnswer(ignored -> checks(pr(deliveredHead, !promoted)));
        when(pullRequests.fetchPullRequestIdentity(eq("token"), any())).thenAnswer(ignored -> pr(deliveredHead, !promoted));
        doAnswer(call -> {
            Runnable action = call.getArgument(2);
            action.run();
            return null;
        }).when(turns).executeDevelopmentAction(any(), any(), any());
        doAnswer(ignored -> {
            promoted = true;
            return null;
        }).when(writes).markReadyForReview("token", "node-7");
        when(git.synchronizeSource(any(), anyString(), anyString(), any())).thenAnswer(call -> {
            String head = call.getArgument(2);
            Runnable authority = call.getArgument(3);
            authority.run();
            when(git.inspectLocal(worktree, route)).thenReturn(local(head, false));
            return local(head, false);
        });
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
        verifyNoInteractions(ci, writes);
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
        verifyNoInteractions(ci, writes);
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
        prepare(local(HEAD, true), local(HEAD, true), new DevelopmentAutomation(true, false, false, Optional.empty()), true);
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
        verifyNoInteractions(ci, writes);
    }

    @Test
    void queuedTurnInheritsPendingWorkFromThePreviousAcceptedBaseline()
    {
        var commitOnly = new DevelopmentAutomation(true, false, false, Optional.empty());
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

        verifyNoInteractions(ci, writes);
        noFollowup();
        verify(turns, never()).finishDevelopmentContinuation(any(), any());

        advance();

        if (autoCiFix) {
            verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        }
        else {
            verifyNoInteractions(ci, writes);
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
        verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        verify(writes).markReadyForReview("token", "node-7");
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void dirtyWorkWithAutoCommitDisabledDoesNotPublishCommittedSubset()
    {
        prepare(local(BASE, false), local(HEAD, true), new DevelopmentAutomation(false, true, true, Optional.empty()), true);
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
        prepare(local(BASE, false), local(HEAD, false), new DevelopmentAutomation(true, false, false, Optional.empty()), true);
        advance();
        noFollowup();
        verifyNoInteractions(ci, writes);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void autoCommitFinishesLocallyWhenPublicationAndCiAreDisabled()
    {
        prepare(local(BASE, false), local(BASE, true), new DevelopmentAutomation(true, false, false, Optional.empty()), true);
        when(git.inspectLocal(worktree, route)).thenReturn(local(BASE, true), local(HEAD, false));
        generated(AUTO_COMMIT, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(AUTO_COMMIT)), anyString());
        verify(turns, never()).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verifyNoInteractions(ci, writes);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void commitQuestionStopsBeforePublicationAndDoesNotFail()
    {
        prepare(local(BASE, false), local(BASE, true), FULL, true);
        generated(AUTO_COMMIT, false);
        advance();
        verify(turns, never()).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        verifyNoInteractions(ci, writes);
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
        verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        verify(writes).markReadyForReview("token", "node-7");
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void pushOnlyTurnStartsCiForTheRewrittenPublishedHead(boolean autoDraftPr)
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
        prepare(local(HEAD, false), local(HEAD, false), new DevelopmentAutomation(true, autoDraftPr, true, Optional.empty()), true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));

        advance();

        verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
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

        verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unchangedPublishedHeadDoesNotRestartCiWhenDraftStatusChanges(boolean draft)
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, draft))));

        advance();

        verifyNoInteractions(ci, writes);
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void unchangedUnpublishedHeadDoesNotPublishExistingWork()
    {
        prepare(local(HEAD, false), local(HEAD, false), FULL, true);

        advance();

        verifyNoInteractions(ci, writes);
        noFollowup();
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void pushOnlyTurnDoesNotStartDisabledCi()
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
        prepare(local(HEAD, false), local(HEAD, false), DRAFT_ONLY, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));

        advance();

        verifyNoInteractions(ci, writes);
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

        verifyNoInteractions(ci, writes);
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

        verifyNoInteractions(ci, writes);
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

        verifyNoInteractions(ci, writes);
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
        verifyNoInteractions(ci);
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
        when(git.isAncestor(worktree, NEXT, startingHead)).thenReturn(true);
        DevelopmentLocalFacts rewritten = new DevelopmentLocalFacts(route.headBranch(), HEAD, HEAD + ":false", 0, 1, true, false);
        prepare(local(startingHead, false), rewritten, FULL, true);
        AtomicReference<DevelopmentPublicationFacts> remote = trackedRemote(Optional.of(NEXT), Optional.of(existing));
        generated(CREATE_DRAFT_PR, true, () -> coordinator.publishDraftPullRequest(taskId.toString(), "Remove commit trailers", "Body"));

        advance();

        var order = inOrder(git, ci, turns);
        order.verify(git).pushDevelopmentHead(worktree, route, HEAD, Optional.of(NEXT));
        order.verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        order.verify(turns).finishDevelopmentContinuation(taskId, source.id());
        assertThat(remote.get().remoteHeadSha()).contains(HEAD);
        verify(writes, never()).createPullRequest(anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void remoteHistoryAlreadyDivergentBeforeTheTurnCannotBeReplaced()
    {
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
        prepare(local(BASE, false), local(HEAD, false), FULL, true);

        advance();

        noFollowup();
        verify(git, never()).pushDevelopmentHead(any(), any(), anyString(), any());
        verify(ci, never()).startDevelopment(any(), any(), any(), any(), any());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("neither the current nor the starting task revision"));
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
        verify(ci, never()).startDevelopment(any(), any(), any(), any(), any());
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
        verifyNoInteractions(ci, writes);
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
                ci, actions, credentials, pullRequests, writes, clock, waitForCi);
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
        coordinator.branchRenamed(taskId, source.id(), worktree, renamed);
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

    @Test
    void publishReusesTheTrackingPullRequestInsteadOfOpeningASecond()
    {
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
    void publishSendsAForkQualifiedHeadForACrossRepositoryRoute()
    {
        route = new DevelopmentPublicationRoute(new RepoRef("forker", "repo"), new RepoRef("upstream", "repo"),
                "https://github.com/forker/repo.git", "upstream", "release", BASE, worktree.generatedBranch());
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
                "upstream", "release", BASE, route.headBranch());
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
    void coordinatorDrivesCiUsingAcceptedRepairAgentAndOriginalActivation(String laneId)
    {
        AgentLaneId lane = new AgentLaneId(laneId);
        preparePublished(new DevelopmentAutomation(true, true, true, Optional.of(lane)));
        CiAutofixRun run = ciRun();
        doReturn(run).when(ci).startDevelopment(any(), any(), any(), any(), any());
        when(ci.advance(run.id())).thenReturn(run, run.finish(CiAutofixRun.FinishReason.GREEN, "Checks passed", NOW));
        advance();
        var order = inOrder(ci, waitForCi, turns);
        order.verify(ci).startDevelopment(eq(new StartCiAutofixCommand(
                "workspace",
                7,
                lane,
                "development-ci:" + taskId + ":" + source.id())), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        order.verify(ci).advance(run.id());
        order.verify(waitForCi).run();
        order.verify(ci).advance(run.id());
        order.verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void forkPublicationContinuesThroughCiSynchronizationAndReadyPromotion()
    {
        route = new DevelopmentPublicationRoute(route.headRepository(), RepoRef.of("upstream", "repo"), route.pushUrl(), "upstream", "main", BASE, route.headBranch());
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()));
        preparePublished(FULL);
        advance();
        var order = inOrder(ci, git, writes, turns);
        order.verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        order.verify(git).synchronizeSource(any(), eq(HEAD), eq(HEAD), any());
        order.verify(writes).markReadyForReview("token", "node-7");
        order.verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void changedActionsHeadRepositoryPreventsReadyPromotion()
    {
        preparePublished(FULL);
        CiHeadSnapshot snapshot = actionsSnapshot(HEAD, false);
        when(actions.inspect(any())).thenReturn(new CiHeadSnapshot(snapshot.pullRequest(), true, snapshot.author(), RepoRef.of("other", "repo"),
                snapshot.headRef(), snapshot.headSha(), snapshot.baseRef(), snapshot.baseSha(), snapshot.url(), snapshot.workflowRuns()));
        advance();
        verify(git, never()).synchronizeSource(any(), anyString(), anyString(), any());
        verify(writes, never()).markReadyForReview(anyString(), anyString());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Actions evidence no longer matches"));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ciUsesTheDevelopmentAgentUnlessExplicitlyOverridden(boolean explicitCiAgent)
    {
        preparePublished(explicitCiAgent ? FULL : new DevelopmentAutomation(true, true, true, Optional.empty()));
        advance();
        ArgumentCaptor<StartCiAutofixCommand> command = ArgumentCaptor.forClass(StartCiAutofixCommand.class);
        verify(ci).startDevelopment(command.capture(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
        assertThat(command.getValue().laneId()).isEqualTo(explicitCiAgent ? AgentLaneId.CLAUDE_CLI : AgentLaneId.CODEX_CLI);
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
        noFollowup();
    }

    @Test
    void disablingCiLeavesTheVerifiedDraft()
    {
        preparePublished(new DevelopmentAutomation(true, true, false, Optional.empty()));
        advance();
        verify(ci, never()).startDevelopment(any(), any(), any(), any(), any());
        verifyNoInteractions(writes);
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
        prepare(local(BASE, false), local(HEAD, false), new DevelopmentAutomation(true, false, true, Optional.of(AgentLaneId.CLAUDE_CLI)), true);
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
        doReturn(paused).when(ci).startDevelopment(any(), any(), any(), any(), any());
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
        doReturn(run).when(ci).startDevelopment(any(), any(), any(), any(), any());
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
        doReturn(paused).when(ci).startDevelopment(any(), any(), any(), any(), any());
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
        doReturn(ciRun().finish(CiAutofixRun.FinishReason.MAX_ATTEMPTS, "Three repairs failed", NOW)).when(ci).startDevelopment(any(), any(), any(), any(), any());
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Three repairs failed"));
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
    }

    @Test
    void stopDuringCiWaitCannotAdvanceOrFinishAnotherOwner()
    {
        preparePublished(FULL);
        CiAutofixRun run = ciRun();
        doReturn(run).when(ci).startDevelopment(any(), any(), any(), any(), any());
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

    @Test
    void greenRepairSynchronizesExactPublishedHeadBeforeReadyAndRelease()
    {
        preparePublished(FULL);
        doAnswer(ignored -> {
            deliveredHead = NEXT;
            when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(NEXT), Optional.of(pr(NEXT, true))));
            return ciRun().finish(CiAutofixRun.FinishReason.GREEN, "Repaired checks passed", NOW);
        }).when(ci).startDevelopment(any(), any(), any(), any(), any());
        advance();
        var ordered = inOrder(pullRequests, git, writes, turns);
        ordered.verify(pullRequests).fetchDeliveryChecks("token", pr(HEAD, true).pullRequest());
        ordered.verify(git).synchronizeSource(any(), eq(HEAD), eq(NEXT), any());
        ordered.verify(pullRequests).fetchDeliveryChecks("token", pr(HEAD, true).pullRequest());
        ordered.verify(writes).markReadyForReview("token", "node-7");
        ordered.verify(pullRequests).fetchPullRequestIdentity("token", pr(HEAD, true).pullRequest());
        ordered.verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fullCheckGateWaitsForMissingChecksOrNewQueuedWorkflow(boolean missing)
    {
        preparePublished(FULL);
        if (missing) {
            when(pullRequests.fetchDeliveryChecks(eq("token"), any())).thenReturn(
                    new GitHubDeliveryChecks("node-7", pr(HEAD, true), ImmutableList.of("Required check build has not reported"), ImmutableList.of()));
        }
        else {
            when(actions.inspect(any())).thenReturn(actionsSnapshot(HEAD, true));
        }
        doAnswer(ignored -> {
            verify(git, never()).synchronizeSource(any(), anyString(), anyString(), any());
            verify(writes, never()).markReadyForReview(anyString(), anyString());
            verify(turns, never()).finishDevelopmentContinuation(any(), any());
            when(pullRequests.fetchDeliveryChecks(eq("token"), any())).thenAnswer(call -> checks(pr(HEAD, !promoted)));
            when(actions.inspect(any())).thenReturn(actionsSnapshot(HEAD, false));
            return null;
        }).when(waitForCi).run();
        advance();
        verify(turns).reportDevelopmentProgress(eq(taskId), eq(source.id()), contains("Waiting for PR checks:"));
        verify(writes).markReadyForReview("token", "node-7");
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void missingChecksExpireWithoutPromotionOrSourceMutation()
    {
        preparePublished(FULL);
        when(pullRequests.fetchDeliveryChecks(eq("token"), any())).thenReturn(
                new GitHubDeliveryChecks("node-7", pr(HEAD, true), ImmutableList.of("Required workflow excludes draft PRs"), ImmutableList.of()));
        doAnswer(ignored -> {
            when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(8)));
            return null;
        }).when(waitForCi).run();
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Required workflow excludes draft PRs"));
        verify(git, never()).synchronizeSource(any(), anyString(), anyString(), any());
        verify(writes, never()).markReadyForReview(anyString(), anyString());
    }

    @Test
    void thirdPartyFailureLeavesDraftAndExplainsRequiredAction()
    {
        preparePublished(FULL);
        when(pullRequests.fetchDeliveryChecks(eq("token"), any())).thenReturn(
                new GitHubDeliveryChecks("node-7", pr(HEAD, true), ImmutableList.of(), ImmutableList.of("External security check failed")));
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("External security check failed"));
        verify(git, never()).synchronizeSource(any(), anyString(), anyString(), any());
        verify(writes, never()).markReadyForReview(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changedHeadOrBaseCannotUseEarlierGreenEvidence(boolean headChanged)
    {
        preparePublished(FULL);
        GitHubPullRequestIdentity moved = new GitHubPullRequestIdentity(pr(HEAD, true).pullRequest(), pr(HEAD, true).url(), route.headRepository(), route.headBranch(),
                headChanged ? NEXT : HEAD, route.baseRepository(), "main", headChanged ? BASE : NEXT, GitHubPullRequestIdentity.State.OPEN, true, "owner");
        when(pullRequests.fetchDeliveryChecks(eq("token"), any())).thenReturn(checks(moved));
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("head or base changed"));
        verify(git, never()).synchronizeSource(any(), anyString(), anyString(), any());
        verify(writes, never()).markReadyForReview(anyString(), anyString());
    }

    @Test
    void sourceSynchronizationFailureLeavesDraftAndFailsTask()
    {
        preparePublished(FULL);
        doThrow(new IllegalStateException("Source worktree has uncommitted files"))
                .when(git).synchronizeSource(any(), anyString(), anyString(), any());
        advance();
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Source worktree has uncommitted files"));
        verify(writes, never()).markReadyForReview(anyString(), anyString());
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
    }

    @Test
    void finalChecksRevalidateAfterSynchronization()
    {
        preparePublished(FULL);
        when(pullRequests.fetchDeliveryChecks(eq("token"), any())).thenReturn(checks(pr(HEAD, true)), checks(pr(NEXT, true)));
        advance();
        verify(git).synchronizeSource(any(), eq(HEAD), eq(HEAD), any());
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("head or base changed"));
        verify(writes, never()).markReadyForReview(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptedCiSettingControlsGeneratedDraftContinuation(boolean autoCiFix)
    {
        prepare(local(BASE, false), local(HEAD, false), new DevelopmentAutomation(true, true, autoCiFix, Optional.empty()), true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.empty(), Optional.empty()), publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
        generated(CREATE_DRAFT_PR, true);
        advance();
        verify(turns).startGeneratedTurn(eq(taskId), eq(origin(CREATE_DRAFT_PR)), anyString());
        if (autoCiFix) {
            verify(ci).startDevelopment(any(), eq(taskId), eq(source.id()), eq(pr(HEAD, true)), eq(NOW));
            verify(git).synchronizeSource(any(), eq(HEAD), eq(HEAD), any());
            verify(writes).markReadyForReview("token", "node-7");
        }
        else {
            verifyNoInteractions(ci, writes);
            verify(git, never()).synchronizeSource(any(), anyString(), anyString(), any());
        }
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    @Test
    void existingReadyPrNeedsNoReadyMutation()
    {
        preparePublished(FULL);
        promoted = true;
        advance();
        verify(git).synchronizeSource(any(), eq(HEAD), eq(HEAD), any());
        verify(writes, never()).markReadyForReview(anyString(), anyString());
        verify(turns).finishDevelopmentContinuation(taskId, source.id());
    }

    @Test
    void uncertainPromotionIsNeverRetriedOrRolledBack()
    {
        preparePublished(FULL);
        doThrow(new IllegalStateException("Connection closed after request")).when(writes).markReadyForReview(anyString(), anyString());
        advance();
        verify(writes, times(1)).markReadyForReview("token", "node-7");
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Ready-for-review result may have changed"));
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
    }

    @Test
    void promotionRaceSurfacesRemoteUncertaintyWithoutRetry()
    {
        preparePublished(FULL);
        when(pullRequests.fetchPullRequestIdentity(eq("token"), any())).thenReturn(pr(NEXT, false));
        advance();
        verify(writes, times(1)).markReadyForReview("token", "node-7");
        verify(turns).failDevelopmentContinuation(eq(taskId), eq(source.id()), contains("Ready-for-review result may have changed"));
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
    }

    @Test
    void stopWhileWaitingForFullChecksPreventsAllLaterActions()
    {
        preparePublished(FULL);
        when(pullRequests.fetchDeliveryChecks(eq("token"), any())).thenReturn(
                new GitHubDeliveryChecks("node-7", pr(HEAD, true), ImmutableList.of("build pending"), ImmutableList.of()));
        doAnswer(ignored -> {
            when(turns.ownsDevelopmentContinuation(taskId, source.id())).thenReturn(false);
            return null;
        }).when(waitForCi).run();
        advance();
        verify(git, never()).synchronizeSource(any(), anyString(), anyString(), any());
        verify(writes, never()).markReadyForReview(anyString(), anyString());
        verify(turns, never()).finishDevelopmentContinuation(any(), any());
        verify(turns, never()).failDevelopmentContinuation(any(), any(), anyString());
    }

    private void preparePublished(DevelopmentAutomation automation)
    {
        prepare(local(BASE, false), local(HEAD, false), automation, true);
        when(git.inspectPublication(worktree, route)).thenReturn(publication(Optional.of(HEAD), Optional.of(pr(HEAD, true))));
    }

    private CiHeadSnapshot actionsSnapshot(String head, boolean queued)
    {
        CiJob job = new CiJob(1, 1, "build", 1, "completed", Optional.of("success"), "https://github.com/job/1", ImmutableList.of());
        CiWorkflowRun workflow = new CiWorkflowRun(1, 1, "build", 1, "completed", Optional.of("success"), head, "https://github.com/run/1", ImmutableList.of(job));
        List<CiWorkflowRun> workflows = queued
                ? ImmutableList.of(workflow, new CiWorkflowRun(2, 2, "queued", 1, "queued", Optional.empty(), head, "https://github.com/run/2", ImmutableList.of()))
                : ImmutableList.of(workflow);
        return new CiHeadSnapshot(pr(head, true).pullRequest(), true, "owner", route.headRepository(), route.headBranch(), head, "main", BASE, pr(head, true).url(), workflows);
    }

    private GitHubDeliveryChecks checks(GitHubPullRequestIdentity identity)
    {
        return new GitHubDeliveryChecks("node-7", identity, ImmutableList.of(), ImmutableList.of());
    }

    private CiAutofixRun ciRun()
    {
        return CiAutofixRun.create(CiAutofixRunId.random(), "owned-ci",
                new CiAutofixRun.Scope(pr(HEAD, true).pullRequest(), route.headRepository(), "workspace", route.headRepository(), "owner", route.headBranch(), "main",
                        source.settings().orElseThrow().developmentAutomation().orElseThrow().ciLaneId().orElse(task.laneId()).value(), Optional.of(taskId.toString())),
                deliveredHead, deliveredHead, BASE, new CiAutofixRun.Authorization(NOW, NOW.plus(Duration.ofHours(8))), NOW);
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

    private static DevelopmentResultReport report(boolean ready)
    {
        return new DevelopmentResultReport(ready, ready ? Optional.empty() : Optional.of("Which implementation?"));
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
