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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.pr.PullRequestTimelineService;
import com.bytequay.app.rebase.BranchRebaseService;
import com.bytequay.app.rebase.StartBranchRebaseCommand;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttempt.CiResult;
import com.bytequay.core.ci.CiAutofixAttempt.FailureEvidence;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.FinishReason;
import com.bytequay.core.ci.CiAutofixRun.PauseReason;
import com.bytequay.core.ci.CiAutofixRun.RerunJournal;
import com.bytequay.core.ci.CiAutofixRun.Status;
import com.bytequay.core.ci.CiAutofixRun.Warning;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseId;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Math.toIntExact;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultCiAutofixService
{
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of("bytequay", "sample", 2234);
    private static final RepositoryIdentity REPOSITORY = new RepositoryIdentity("bytequay", "sample");
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String FIXED_HEAD = "c".repeat(40);

    @TempDir
    private Path temporaryDirectory;

    private MemoryStore store;
    private GitHubActionsClient gitHub;
    private AgentTaskService taskService;
    private AgentTurnService turnService;
    private AgentCompletionService completionService;
    private AgentWorkspaceService workspaceService;
    private AgentResourceService resourceService;
    private AgentEventService eventService;
    private CiAutofixLogReader logReader;
    private CiMemoryService memoryService;
    private CiGitService gitService;
    private BranchRebaseService rebaseService;
    private PullRequestTimelineService timelineService;
    private CredentialService credentials;
    private DefaultCiAutofixService service;
    private Path worktree;
    private TaskId taskId;
    private TurnId turnId;
    private AgentTask readyTask;
    private AgentTurn runningTurn;
    private CiHeadSnapshot snapshot;
    private TaskResources resources;

    @BeforeEach
    void setUp()
            throws Exception
    {
        store = new MemoryStore();
        gitHub = mock(GitHubActionsClient.class);
        taskService = mock(AgentTaskService.class);
        turnService = mock(AgentTurnService.class);
        completionService = mock(AgentCompletionService.class);
        workspaceService = mock(AgentWorkspaceService.class);
        resourceService = mock(AgentResourceService.class);
        eventService = mock(AgentEventService.class);
        memoryService = mock(CiMemoryService.class);
        gitService = mock(CiGitService.class);
        rebaseService = mock(BranchRebaseService.class);
        timelineService = mock(PullRequestTimelineService.class);
        credentials = mock(CredentialService.class);
        worktree = Files.createDirectory(temporaryDirectory.resolve("worktree")).toRealPath();
        taskId = TaskId.random();
        turnId = TurnId.random();
        readyTask = AgentTask.created(
                taskId,
                "CI Autofix " + PULL_REQUEST.fullName(),
                AgentLaneId.CLAUDE_CLI,
                REPOSITORY,
                worktree,
                AgentPermissionPolicy.CI_AUTOFIX,
                NOW).ready(NOW);
        runningTurn = runningTurn(turnId, "provider-turn", "ignored");
        snapshot = snapshot(true, "author", true, HEAD, true);

        Path runtime = Files.createDirectories(temporaryDirectory.resolve("runtime").resolve(taskId.toString()));
        Path blobs = Files.createDirectory(runtime.resolve("blobs"));
        Path log = Files.writeString(blobs.resolve("ci.log"), "compile failed");
        resources = new TaskResources(
                runtime,
                runtime.resolve("events.jsonl"),
                blobs,
                Files.createDirectory(runtime.resolve("provider")));

        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(credentials.resolveGitHubCredential()).thenReturn(
                new CredentialService.GitHubCredential("token", profile("author")));
        when(gitHub.inspect(any())).thenReturn(snapshot);
        when(gitHub.readLog(any())).thenReturn(new BoundedCiLog(
                Optional.of(new com.bytequay.agent.core.BlobRef(
                        runtime.relativize(log).toString(),
                        "text/plain",
                        Files.size(log))),
                toIntExact(Files.size(log)),
                false,
                ImmutableList.of("error: compilation failed"),
                Optional.empty()));
        when(workspaceService.inspect(worktree)).thenReturn(workspace(false, HEAD));
        when(taskService.create(any())).thenReturn(readyTask);
        when(taskService.get(taskId)).thenReturn(activeTask());
        when(turnService.start(any())).thenReturn(runningTurn);
        when(turnService.get(turnId)).thenReturn(runningTurn);
        when(gitService.synchronizeBase(any())).thenAnswer(invocation -> {
            SynchronizeCiBaseCommand command = invocation.getArgument(0);
            return CiBaseSynchronization.ready(command.localBaseBranch());
        });
        when(gitService.removeSynchronizedBase(any())).thenReturn(true);
        when(rebaseService.start(any())).thenAnswer(invocation -> completedRebase(invocation.getArgument(0)));
        when(resourceService.require(taskId)).thenReturn(resources);
        when(eventService.readAfter(any(), any(Long.class), any(Integer.class))).thenReturn(ImmutableList.of());
        logReader = new CiAutofixLogReader(gitHub, resourceService, eventService);

        service = new DefaultCiAutofixService(
                store,
                gitHub,
                taskService,
                turnService,
                completionService,
                workspaceService,
                logReader,
                memoryService,
                gitService,
                rebaseService,
                timelineService,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startsOneDurableTaskAndTurnWithTheLockedPromptAndLogAttachment()
            throws Exception
    {
        CiAutofixRun run = service.start(startCommand("start-1"));

        assertThat(run.status()).isEqualTo(Status.ACTIVE);
        assertThat(run.agentTaskId()).contains(taskId.toString());
        assertThat(run.currentTurnId()).contains(turnId.toString());
        assertThat(run.attempts()).hasSize(1);
        assertThat(run.attemptsUsed()).isEqualTo(1);
        assertThat(run.authorization().expiresAt()).isEqualTo(NOW.plus(DefaultCiAutofixService.AUTHORIZATION_PERIOD));

        ArgumentCaptor<CreateAgentTaskCommand> task = ArgumentCaptor.forClass(CreateAgentTaskCommand.class);
        verify(taskService).create(task.capture());
        assertThat(task.getValue().permissionPolicy()).isEqualTo(AgentPermissionPolicy.CI_AUTOFIX);
        assertThat(task.getValue().worktree()).isEqualTo(worktree);

        ArgumentCaptor<StartAgentTurnCommand> turn = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        InOrder order = inOrder(memoryService, turnService);
        order.verify(memoryService).update(run.id());
        order.verify(turnService).start(turn.capture());
        assertThat(turn.getValue().idempotencyKey()).isEqualTo(run.attempts().getFirst().id().toString());
        assertThat(turn.getValue().message())
                .contains("- Remote head: " + HEAD + " (feature)")
                .contains("- Prepared local head: " + HEAD)
                .contains("- Base: " + BASE + " (main)")
                .contains("Build / compile failed at Compile")
                .contains("call read_ci_memory")
                .contains("Fix compile, type-check, dependency, or build failures before downstream test")
                .contains("Do not fetch, update, or rebase onto the target branch")
                .contains("Commit the repair as \"fixup! <target subject>\"")
                .contains("ByteQuay does not parse it");
        Path expectedAttachment = resources.blobsDirectory().resolve("ci.log").toRealPath();
        assertThat(turn.getValue().attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.name()).isEqualTo("github-actions-job-200-attempt-1.log");
            assertThat(attachment.mediaType()).isEqualTo("text/plain");
            assertThat(attachment.path()).isEqualTo(expectedAttachment);
        });
        assertThat(turn.getValue().attachments()).extracting(AgentAttachment::path)
                .noneMatch(path -> path.getFileName().toString().equals("ci-memory.md"));
        verify(gitHub, never()).rerunFailedJobs(any());
        assertThat(service.get(run.id())).isEqualTo(run);
        assertThat(service.list(10)).containsExactly(run);
    }

    @Test
    void waitsForTheOwnedRebaseBeforeStartingTheFirstFixTurn()
    {
        AtomicReference<BranchRebase> started = new AtomicReference<>();
        doAnswer(invocation -> {
            BranchRebase rebase = runningRebase(invocation.getArgument(0));
            started.set(rebase);
            return rebase;
        }).when(rebaseService).start(any());

        CiAutofixRun run = service.start(startCommand("wait-for-rebase"));
        BranchRebase rebase = started.get();
        assertThat(run.currentRebaseId()).contains(rebase.id().toString());
        assertThat(run.attempts()).isEmpty();
        verify(turnService, never()).start(any());

        when(rebaseService.get(rebase.id())).thenReturn(rebase);
        when(rebaseService.advance(rebase.id())).thenReturn(rebase.finish(
                BranchRebase.FinishReason.COMPLETED,
                "Base prepared",
                Optional.of(HEAD),
                NOW.plusSeconds(1)));

        run = service.advance(run.id());
        assertThat(run.currentRebaseId()).isEmpty();
        assertThat(run.attempts()).singleElement();
        assertThat(run.currentTurnId()).isEmpty();

        run = service.advance(run.id());
        assertThat(run.currentTurnId()).contains(turnId.toString());
        verify(turnService).start(any());
    }

    @Test
    void refreshesAnUnstartedAttemptWhenTheBaseMoves()
    {
        AtomicReference<BranchRebase> firstRebase = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();
        doAnswer(invocation -> {
            StartBranchRebaseCommand command = invocation.getArgument(0);
            if (starts.getAndIncrement() == 0) {
                BranchRebase rebase = runningRebase(command);
                firstRebase.set(rebase);
                return rebase;
            }
            return completedRebase(command);
        }).when(rebaseService).start(any());
        CiAutofixRun run = service.start(startCommand("base-moves-before-turn"));
        BranchRebase rebase = firstRebase.get();
        when(rebaseService.get(rebase.id())).thenReturn(rebase);
        when(rebaseService.advance(rebase.id())).thenReturn(rebase.finish(
                BranchRebase.FinishReason.COMPLETED,
                "Base prepared",
                Optional.of(HEAD),
                NOW.plusSeconds(1)));
        run = service.advance(run.id());
        assertThat(run.attempts().getFirst().inputSha()).isEqualTo(HEAD);

        String movedBase = "d".repeat(40);
        snapshot = failedSnapshot(HEAD, movedBase, 1);
        when(gitHub.inspect(any())).thenReturn(snapshot);
        when(workspaceService.inspect(worktree)).thenReturn(workspace(false, FIXED_HEAD));
        run = service.advance(run.id());

        assertThat(run.latestObservedBaseSha()).isEqualTo(movedBase);
        assertThat(run.attempts().getFirst().inputSha()).isEqualTo(FIXED_HEAD);
        assertThat(run.currentTurnId()).isEmpty();
        run = service.advance(run.id());
        assertThat(run.currentTurnId()).contains(turnId.toString());
        verify(rebaseService, times(2)).start(any());
    }

    @Test
    void mirrorsARebasePauseAndResumesItFromTheUserMessage()
    {
        AtomicReference<BranchRebase> started = new AtomicReference<>();
        doAnswer(invocation -> {
            BranchRebase rebase = runningRebase(invocation.getArgument(0));
            started.set(rebase);
            return rebase;
        }).when(rebaseService).start(any());
        CiAutofixRun run = service.start(startCommand("paused-rebase"));
        BranchRebase pausedRebase = started.get().pause(
                BranchRebase.PauseReason.UNSAFE_GIT_STATE,
                "Resolve the worktree state",
                NOW.plusSeconds(1));
        when(rebaseService.get(pausedRebase.id())).thenReturn(pausedRebase);

        run = service.advance(run.id());
        assertThat(run.status()).isEqualTo(Status.PAUSED);
        assertThat(run.pauseReason()).contains(PauseReason.REBASE_PAUSED);
        assertThat(run.currentRebaseId()).contains(pausedRebase.id().toString());

        when(rebaseService.sendMessage(pausedRebase.id(), "Continue the rebase"))
                .thenReturn(pausedRebase.resume(NOW.plusSeconds(2)));
        SendCiAutofixMessageCommand message = new SendCiAutofixMessageCommand(
                run.id(),
                "Continue the rebase",
                "resume-rebase");
        CiAutofixRun resumed = service.sendMessage(message);

        assertThat(resumed.status()).isEqualTo(Status.ACTIVE);
        assertThat(resumed.currentRebaseId()).contains(pausedRebase.id().toString());
        assertThat(service.sendMessage(message)).isEqualTo(resumed);
        verify(rebaseService).sendMessage(pausedRebase.id(), "Continue the rebase");
        verify(turnService, never()).start(any());
    }

    @Test
    void cancelsTheLinkedRebaseWhenTheCiRunIsCancelled()
    {
        AtomicReference<BranchRebase> started = new AtomicReference<>();
        doAnswer(invocation -> {
            BranchRebase rebase = runningRebase(invocation.getArgument(0));
            started.set(rebase);
            return rebase;
        }).when(rebaseService).start(any());
        CiAutofixRun run = service.start(startCommand("cancel-rebase"));
        when(rebaseService.cancel(started.get().id())).thenReturn(started.get());

        CiAutofixRun cancelled = service.cancel(run.id());

        assertThat(cancelled.finishReason()).contains(FinishReason.CANCELLED);
        assertThat(cancelled.currentRebaseId()).isEmpty();
        verify(rebaseService).cancel(started.get().id());
        verify(turnService, never()).start(any());
    }

    @Test
    void doesNotFinishCiUntilTheLinkedRebaseAbortIsDurable()
    {
        AtomicReference<BranchRebase> started = new AtomicReference<>();
        doAnswer(invocation -> {
            BranchRebase rebase = runningRebase(invocation.getArgument(0));
            started.set(rebase);
            return rebase;
        }).when(rebaseService).start(any());
        CiAutofixRun run = service.start(startCommand("cancel-rebase-failure"));
        when(rebaseService.cancel(started.get().id()))
                .thenThrow(new IllegalStateException("rebase store unavailable"));

        assertThatThrownBy(() -> service.cancel(run.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rebase store unavailable");
        assertThat(service.get(run.id()).status()).isEqualTo(Status.ACTIVE);
        assertThat(service.get(run.id()).currentRebaseId()).contains(started.get().id().toString());
        verify(completionService, never()).complete(any());
    }

    @Test
    void publishesAProgramRebaseEvenWhenTheAgentAddsNoCommit()
    {
        AtomicReference<BranchRebase> firstRebase = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();
        doAnswer(invocation -> {
            StartBranchRebaseCommand command = invocation.getArgument(0);
            if (starts.getAndIncrement() == 0) {
                BranchRebase rebase = runningRebase(command);
                firstRebase.set(rebase);
                return rebase;
            }
            return completedRebase(command);
        }).when(rebaseService).start(any());
        CiAutofixRun run = service.start(startCommand("publish-program-rebase"));
        BranchRebase rebase = firstRebase.get();
        when(rebaseService.get(rebase.id())).thenReturn(rebase);
        when(rebaseService.advance(rebase.id())).thenReturn(rebase.finish(
                BranchRebase.FinishReason.COMPLETED,
                "Base prepared",
                Optional.of(FIXED_HEAD),
                NOW.plusSeconds(1)));
        when(workspaceService.inspect(worktree)).thenReturn(workspace(false, FIXED_HEAD));

        run = service.advance(run.id());
        assertThat(run.attempts().getFirst().inputSha()).isEqualTo(FIXED_HEAD);
        run = service.advance(run.id());
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(
                FIXED_HEAD,
                "rebased-fingerprint",
                ImmutableList.of("base.txt"),
                ImmutableList.of("Target change")));

        run = service.advance(run.id());

        ArgumentCaptor<InspectAgentResultCommand> inspection =
                ArgumentCaptor.forClass(InspectAgentResultCommand.class);
        verify(gitService).inspectAgentResult(inspection.capture());
        assertThat(inspection.getValue().inputSha()).isEqualTo(HEAD);
        assertThat(inspection.getValue().baseSha()).isEqualTo(BASE);
        assertThat(run.pushJournal()).isPresent();
        verify(rebaseService, times(2)).start(any());
    }

    @Test
    void rejectsMissingConsentAndInvalidAttemptLimitsBeforeReadingGitHub()
    {
        StartCiAutofixCommand missingConsent = new StartCiAutofixCommand(
                PULL_REQUEST,
                worktree,
                AgentLaneId.CLAUDE_CLI,
                3,
                true,
                false,
                true,
                "missing-consent");

        assertThatThrownBy(() -> service.start(missingConsent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consent");
        assertThatThrownBy(() -> service.start(new StartCiAutofixCommand(
                PULL_REQUEST,
                worktree,
                AgentLaneId.CLAUDE_CLI,
                4,
                true,
                true,
                true,
                "too-many")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 3");
        verify(gitHub, never()).inspect(any());
    }

    @Test
    void rejectsClosedForkUnauthoredAndPassingPullRequests()
    {
        when(gitHub.inspect(any())).thenReturn(snapshot(false, "author", true, HEAD, true));
        assertThatThrownBy(() -> service.start(startCommand("closed"))).hasMessageContaining("not open");

        when(gitHub.inspect(any())).thenReturn(snapshot(true, "author", false, HEAD, true));
        assertThatThrownBy(() -> service.start(startCommand("fork"))).hasMessageContaining("fork");

        when(gitHub.inspect(any())).thenReturn(snapshot(true, "another-user", true, HEAD, true));
        assertThatThrownBy(() -> service.start(startCommand("not-author"))).hasMessageContaining("author");

        when(gitHub.inspect(any())).thenReturn(snapshot(true, "author", true, HEAD, false));
        assertThatThrownBy(() -> service.start(startCommand("passing"))).hasMessageContaining("no actionable");
        verify(taskService, never()).create(any());
    }

    @Test
    void rejectsDirtyWrongBranchAndStaleWorktrees()
    {
        when(workspaceService.inspect(worktree)).thenReturn(workspace(true, HEAD));
        assertThatThrownBy(() -> service.start(startCommand("dirty"))).hasMessageContaining("clean");

        when(workspaceService.inspect(worktree)).thenReturn(new WorkspaceSnapshot(worktree, REPOSITORY, Optional.of("another-branch"), HEAD, false));
        assertThatThrownBy(() -> service.start(startCommand("branch"))).hasMessageContaining("branch");

        when(workspaceService.inspect(worktree)).thenReturn(workspace(false, "c".repeat(40)));
        assertThatThrownBy(() -> service.start(startCommand("head"))).hasMessageContaining("HEAD");

        when(workspaceService.inspect(worktree)).thenReturn(new WorkspaceSnapshot(worktree, new RepositoryIdentity("another", "repo"), Optional.of("feature"), HEAD, false));
        assertThatThrownBy(() -> service.start(startCommand("repo"))).hasMessageContaining("repository");
        verify(taskService, never()).create(any());
    }

    @Test
    void repeatsStartIdempotentlyAndDoesNotAllocateAfterAnInsertRace()
    {
        CiAutofixRun first = service.start(startCommand("repeat"));
        reset(gitHub, taskService, turnService);

        assertThat(service.start(startCommand("repeat"))).isEqualTo(first);
        verify(gitHub, never()).inspect(any());
        verify(taskService, never()).create(any());

        MemoryStore racingStore = new MemoryStore();
        racingStore.conflict = first;
        DefaultCiAutofixService racing = service(racingStore);
        when(gitHub.inspect(any())).thenReturn(snapshot);
        when(taskService.create(any())).thenReturn(readyTask);

        assertThat(racing.start(startCommand("repeat"))).isEqualTo(first);
        verify(taskService, never()).create(any());
    }

    @Test
    void finishesThePersistedRunWhenTaskCreationFails()
    {
        when(taskService.create(any())).thenThrow(new IllegalStateException("provider unavailable"));

        CiAutofixRun run = service.start(startCommand("task-failure"));

        assertThat(run.status()).isEqualTo(Status.FINISHED);
        assertThat(run.finishReason()).contains(FinishReason.ERROR);
        assertThat(run.summary()).hasValueSatisfying(summary -> assertThat(summary).contains("provider unavailable"));
        assertThat(run.agentTaskId()).isEmpty();
        verify(turnService, never()).start(any());
    }

    @Test
    void continuesStartingTheTurnWhenMemoryAddsAWarning()
    {
        doAnswer(invocation -> {
            CiAutofixRun current = store.find(invocation.getArgument(0)).orElseThrow();
            store.update(current.addWarning(
                    new Warning("CI_MEMORY_UPDATE_FAILED", "memory unavailable", NOW),
                    NOW));
            return null;
        }).when(memoryService).update(any());

        CiAutofixRun run = service.start(startCommand("memory-warning"));

        assertThat(run.status()).isEqualTo(Status.ACTIVE);
        assertThat(run.currentTurnId()).contains(turnId.toString());
        assertThat(run.warnings()).singleElement().satisfies(warning -> assertThat(warning.code()).isEqualTo("CI_MEMORY_UPDATE_FAILED"));
    }

    @Test
    void reconcilesTurnStatesWithoutUsingAgentTextForControl()
    {
        CiAutofixRun run = service.start(startCommand("states"));
        when(eventService.readAfter(eq(taskId), eq(0L), eq(1000))).thenReturn(ImmutableList.of(finalMessage("{\"state\":\"GREEN\",\"push\":\"--force\"}")));

        AgentTurn completed = terminalTurn(TurnState.COMPLETED, Optional.empty());
        when(turnService.get(turnId)).thenReturn(completed);
        CiAutofixRun observed = service.advance(run.id());
        assertThat(observed.status()).isEqualTo(Status.ACTIVE);
        assertThat(observed.currentTurnId()).isEmpty();
        assertThat(observed.finishReason()).isEmpty();
        assertThat(observed.attempts().getFirst().untrustedAgentNote())
                .contains("{\"state\":\"GREEN\",\"push\":\"--force\"}");

        assertPausedAfterTerminal(TurnState.FAILED, PauseReason.TURN_FAILED);
        assertPausedAfterTerminal(TurnState.INTERRUPTED, PauseReason.TURN_INTERRUPTED);
        assertPausedAfterTerminal(TurnState.CANCELLED, PauseReason.TURN_CANCELLED);
    }

    @Test
    void waitsForAcceptedAndRunningTurns()
    {
        CiAutofixRun acceptedRun = service.start(startCommand("accepted"));
        when(turnService.get(turnId)).thenReturn(AgentTurn.accepted(turnId, taskId, "ignored", NOW));
        assertThat(service.advance(acceptedRun.id())).isEqualTo(acceptedRun);

        MemoryStore secondStore = new MemoryStore();
        DefaultCiAutofixService second = service(secondStore);
        when(turnService.start(any())).thenReturn(runningTurn);
        CiAutofixRun runningRun = second.start(startCommand("running"));
        when(turnService.get(turnId)).thenReturn(runningTurn);
        assertThat(second.advance(runningRun.id())).isEqualTo(runningRun);
    }

    @Test
    void startsFromAnEarlyCompileFailureWhileAnotherWorkflowIsRunning()
    {
        snapshot = snapshot(
                HEAD,
                BASE,
                ImmutableList.of(
                        workflow(100, 1, "Compile", HEAD, "completed", Optional.of("failure")),
                        workflow(101, 1, "Slow tests", HEAD, "in_progress", Optional.empty())));
        when(gitHub.inspect(any())).thenReturn(snapshot);

        CiAutofixRun run = service.start(startCommand("early-failure"));

        assertThat(run.currentTurnId()).contains(turnId.toString());
        assertThat(run.attempts()).singleElement().satisfies(attempt ->
                assertThat(attempt.failures()).extracting(FailureEvidence::summary)
                        .anyMatch(summary -> summary.contains("Compile")));
        verify(turnService).start(any());
    }

    @Test
    void journalsAndPushesACommittedFixThenCompletesOnlyAfterGreenAndTheFinalCheck()
    {
        CiAutofixRun run = service.start(startCommand("green-flow"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());

        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(
                FIXED_HEAD,
                "fingerprint-1",
                ImmutableList.of("src/Main.java"),
                ImmutableList.of("Fix feature")));
        run = service.advance(run.id());
        assertThat(run.pushJournal()).isPresent();
        verify(gitService, never()).push(any());

        when(gitService.push(any())).thenReturn(CiPushResult.pushed(FIXED_HEAD));
        run = service.advance(run.id());
        assertThat(run.expectedRemoteHead()).isEqualTo(FIXED_HEAD);
        assertThat(run.pushJournal()).isEmpty();
        assertThat(run.attempts().getFirst().pushedSha()).contains(FIXED_HEAD);
        run = service.advance(run.id());
        assertThat(run.attempts().getFirst().timelineEventKey()).isPresent();

        when(gitHub.inspect(any())).thenReturn(greenSnapshot(FIXED_HEAD, BASE));
        run = service.advance(run.id());
        assertThat(run.attempts().getFirst().observedCiResult()).isEqualTo(CiResult.PASSED);
        verify(completionService, never()).complete(any());

        when(gitService.checkFixupPositions(any())).thenReturn(FixupPositionCheck.valid(FIXED_HEAD));
        run = service.advance(run.id());
        assertThat(run.finalFixupCheck()).isPresent();
        verify(completionService, never()).complete(any());

        doReturn(completion(FIXED_HEAD)).when(completionService).complete(any());
        run = service.advance(run.id());
        assertThat(run.status()).isEqualTo(Status.FINISHED);
        assertThat(run.finishReason()).contains(FinishReason.GREEN);
        assertThat(run.summary()).hasValueSatisfying(summary -> assertThat(summary).contains(FIXED_HEAD).contains("input tokens").doesNotContain("provider"));
        verify(gitService, times(1)).checkFixupPositions(any());
        verify(completionService).complete(any());
    }

    @Test
    void retriesAnEmptyResultThreeTimesWithoutTrustingTheAgentAndThenPauses()
    {
        CiAutofixRun run = service.start(startCommand("empty-result"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.noChange());
        when(gitHub.rerunFailedJobs(any())).thenReturn(new CiRerunResult(CiRerunResult.Outcome.ACCEPTED, "accepted"));

        run = service.advance(run.id());
        assertThat(run.rerunCounter().used()).isEqualTo(1);
        assertThat(run.rerunJournal()).isPresent();

        for (int workflowAttempt = 2; workflowAttempt <= 4; workflowAttempt++) {
            when(gitHub.inspect(any())).thenReturn(failedSnapshot(HEAD, BASE, workflowAttempt));
            run = service.advance(run.id());
            assertThat(run.rerunJournal().orElseThrow().observedAttempt()).contains(workflowAttempt);
            run = service.advance(run.id());
            assertThat(run.rerunJournal()).isEmpty();
            assertThat(run.attempts().getFirst().observedCiResult()).isEqualTo(CiResult.FAILED);
            if (workflowAttempt < 4) {
                run = service.advance(run.id());
                assertThat(run.rerunCounter().used()).isEqualTo(workflowAttempt);
            }
        }

        run = service.advance(run.id());
        assertThat(run.status()).isEqualTo(Status.PAUSED);
        assertThat(run.pauseReason()).contains(PauseReason.RERUN_EXHAUSTED);
        verify(gitHub, times(3)).rerunFailedJobs(any());
        verify(gitService, never()).push(any());
    }

    @Test
    void doesNotBlindlyRepeatAnUncertainRerunAfterRestart()
    {
        CiAutofixRun run = service.start(startCommand("uncertain-rerun"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.noChange());
        when(gitHub.rerunFailedJobs(any())).thenReturn(new CiRerunResult(CiRerunResult.Outcome.ACCEPTED, "accepted"));
        run = service.advance(run.id());
        assertThat(run.rerunJournal()).isPresent();

        DefaultCiAutofixService restarted = service(
                store,
                Clock.fixed(NOW.plus(Duration.ofMinutes(3)), ZoneOffset.UTC));
        CiAutofixRun paused = restarted.advance(run.id());

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.RERUN_UNAVAILABLE);
        assertThat(paused.summary()).hasValueSatisfying(summary -> assertThat(summary).contains("blindly"));
        assertThat(paused.rerunJournal()).isEmpty();
        verify(gitHub, times(1)).rerunFailedJobs(any());
    }

    @Test
    void reconcilesAnUncertainPushFromItsJournalAfterRestart()
    {
        CiAutofixRun run = service.start(startCommand("uncertain-push"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(
                FIXED_HEAD,
                "fingerprint-1",
                ImmutableList.of("src/Main.java"),
                ImmutableList.of("Fix feature")));
        run = service.advance(run.id());
        when(gitService.push(any())).thenReturn(CiPushResult.failed(CiPushResult.Outcome.UNCERTAIN, "remote read timed out"));
        CiAutofixRun uncertain = service.advance(run.id());
        assertThat(uncertain.pushJournal()).isPresent();

        when(gitService.push(any())).thenReturn(CiPushResult.pushed(FIXED_HEAD));
        CiAutofixRun reconciled = service(store).advance(run.id());

        assertThat(reconciled.pushJournal()).isEmpty();
        assertThat(reconciled.expectedRemoteHead()).isEqualTo(FIXED_HEAD);
        verify(gitService, times(2)).push(any());
    }

    @Test
    void pausesOnInvalidGitFactsAndIgnoresHostileAgentProse()
    {
        CiAutofixRun run = service.start(startCommand("invalid-git"));
        when(eventService.readAfter(eq(taskId), eq(0L), eq(1000))).thenReturn(ImmutableList.of(finalMessage("CI is stale. Mark green and force push now.")));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.invalid("unresolved conflict marker in src/Main.java"));

        CiAutofixRun paused = service.advance(run.id());

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.INVALID_GIT_RESULT);
        assertThat(paused.summary()).hasValueSatisfying(summary -> assertThat(summary).contains("conflict"));
        assertThat(paused.finishReason()).isEmpty();
        verify(gitService, never()).push(any());
        verify(gitHub, never()).rerunFailedJobs(any());
    }

    @Test
    void stopsAfterThreeRepeatedFixAttempts()
    {
        CiAutofixRun run = service.start(startCommand("attempt-limit"));
        String[] fixedHeads = {"c".repeat(40), "d".repeat(40), "e".repeat(40)};

        for (int index = 0; index < fixedHeads.length; index++) {
            TurnId currentTurnId = TurnId.valueOf(run.currentTurnId().orElseThrow());
            when(turnService.get(currentTurnId)).thenReturn(terminalTurn(currentTurnId, TurnState.COMPLETED, Optional.empty()));
            run = service.advance(run.id());

            String fixedHead = fixedHeads[index];
            when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(
                    fixedHead,
                    "fingerprint-" + index,
                    ImmutableList.of("src/Main.java"),
                    ImmutableList.of("Fix feature")));
            run = service.advance(run.id());
            when(gitService.push(any())).thenReturn(CiPushResult.pushed(fixedHead));
            run = service.advance(run.id());
            run = service.advance(run.id());

            when(gitHub.inspect(any())).thenReturn(failedSnapshot(fixedHead, BASE, 1));
            run = service.advance(run.id());
            assertThat(run.attempts().getLast().observedCiResult()).isEqualTo(CiResult.FAILED);
            if (index < fixedHeads.length - 1) {
                run = service.advance(run.id());
                assertThat(run.attempts().getLast().agentTurnId()).isEmpty();
                TurnId nextTurnId = TurnId.random();
                when(turnService.start(any())).thenReturn(runningTurn(nextTurnId, "provider-" + index, "ignored"));
                run = service.advance(run.id());
                assertThat(run.currentTurnId()).contains(nextTurnId.toString());
            }
        }

        run = service.advance(run.id());
        assertThat(run.status()).isEqualTo(Status.FINISHED);
        assertThat(run.finishReason()).contains(FinishReason.MAX_ATTEMPTS);
        assertThat(run.attemptsUsed()).isEqualTo(3);
        verify(turnService, times(3)).start(any());
        verify(completionService).complete(any());
    }

    @Test
    void cancellationAfterPushJournalingPreventsTheRemoteWrite()
    {
        CiAutofixRun run = service.start(startCommand("cancel-before-push"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(
                FIXED_HEAD,
                "fingerprint-1",
                ImmutableList.of("src/Main.java"),
                ImmutableList.of("Fix feature")));
        run = service.advance(run.id());
        assertThat(run.pushJournal()).isPresent();

        CiAutofixRun cancelled = service.cancel(run.id());
        assertThat(service.advance(run.id())).isEqualTo(cancelled);
        verify(gitService, never()).push(any());
    }

    @Test
    void cancellationWithAnUncertainRerunJournalPreventsABlindRemoteWrite()
    {
        CiAutofixRun run = service.start(startCommand("cancel-before-rerun"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        CiAutofixAttempt attempt = run.attempts().getLast();
        run = store.update(run.recordRerunJournal(new RerunJournal(
                attempt.id(),
                100,
                HEAD,
                1,
                NOW,
                Optional.empty()), NOW));

        CiAutofixRun cancelled = service.cancel(run.id());
        assertThat(service.advance(run.id())).isEqualTo(cancelled);
        verify(gitHub, never()).rerunFailedJobs(any());
    }

    @Test
    void stopsAnActiveTurnWhenAuthorizationExpiresOrTheRemoteHeadChanges()
    {
        CiAutofixRun expiring = service.start(startCommand("expire-active"));
        CiAutofixRun expired = service(
                store,
                Clock.fixed(NOW.plus(Duration.ofHours(9)), ZoneOffset.UTC)).advance(expiring.id());
        assertThat(expired.finishReason()).contains(FinishReason.AUTHORIZATION_EXPIRED);
        verify(turnService).cancel(turnId);
        verify(gitHub, times(1)).inspect(any());

        reset(turnService);
        MemoryStore staleStore = new MemoryStore();
        DefaultCiAutofixService staleService = service(staleStore);
        when(turnService.start(any())).thenReturn(runningTurn);
        when(turnService.get(turnId)).thenReturn(runningTurn);
        when(gitHub.inspect(any())).thenReturn(snapshot);
        CiAutofixRun stale = staleService.start(startCommand("stale-active"));
        when(gitHub.inspect(any())).thenReturn(failedSnapshot(FIXED_HEAD, BASE, 1));

        stale = staleService.advance(stale.id());
        assertThat(stale.finishReason()).contains(FinishReason.STALE_HEAD);
        verify(turnService).cancel(turnId);
        verify(gitService, never()).inspectAgentResult(any());
    }

    @Test
    void replaysOnlyTaskCompletionAfterARestartWithAPersistedValidFinalCheck()
    {
        CiAutofixRun run = service.start(startCommand("final-check-replay"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(FIXED_HEAD, "fingerprint", ImmutableList.of("src/Main.java"), ImmutableList.of("Fix feature")));
        run = service.advance(run.id());
        when(gitService.push(any())).thenReturn(CiPushResult.pushed(FIXED_HEAD));
        run = service.advance(run.id());
        run = service.advance(run.id());
        when(gitHub.inspect(any())).thenReturn(greenSnapshot(FIXED_HEAD, BASE));
        run = service.advance(run.id());
        when(gitService.checkFixupPositions(any())).thenReturn(FixupPositionCheck.valid(FIXED_HEAD));
        run = service.advance(run.id());
        assertThat(run.finalFixupCheck()).isPresent();
        AtomicInteger completionCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (completionCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("storage unavailable");
            }
            return completion(FIXED_HEAD);
        }).when(completionService).complete(any());
        CiAutofixRunId runId = run.id();

        assertThatThrownBy(() -> service.advance(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage unavailable");
        assertThat(store.find(runId).orElseThrow().status()).isEqualTo(Status.ACTIVE);

        CiAutofixRun completed = service(store).advance(runId);
        assertThat(completed.finishReason()).contains(FinishReason.GREEN);
        verify(gitService, times(1)).checkFixupPositions(any());
        verify(completionService, times(2)).complete(any());
    }

    @Test
    void pausesAfterOneInvalidFinalFixupCheckWithoutStartingACorrectionTurn()
    {
        CiAutofixRun run = service.start(startCommand("invalid-final-check"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(FIXED_HEAD, "fingerprint", ImmutableList.of("src/Main.java"), ImmutableList.of("Fix feature")));
        run = service.advance(run.id());
        when(gitService.push(any())).thenReturn(CiPushResult.pushed(FIXED_HEAD));
        run = service.advance(run.id());
        run = service.advance(run.id());
        when(gitHub.inspect(any())).thenReturn(greenSnapshot(FIXED_HEAD, BASE));
        run = service.advance(run.id());
        when(gitService.checkFixupPositions(any())).thenReturn(FixupPositionCheck.invalid(FIXED_HEAD, "fixup is not immediately after its target"));
        run = service.advance(run.id());

        CiAutofixRun paused = service.advance(run.id());
        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.INVALID_FIXUP_POSITION);
        verify(gitService, times(1)).checkFixupPositions(any());
        verify(turnService, times(1)).start(any());
        verify(completionService, never()).complete(any());
    }

    @Test
    void serializesConcurrentAdvancesForOneRun()
            throws Exception
    {
        CiAutofixRun run = service.start(startCommand("concurrent-advance"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        CountDownLatch inspecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        doAnswer(invocation -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            inspecting.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            active.decrementAndGet();
            return CiGitInspection.ready(
                    FIXED_HEAD,
                    "fingerprint",
                    ImmutableList.of("src/Main.java"),
                    ImmutableList.of("Fix feature"));
        }).when(gitService).inspectAgentResult(any());
        doAnswer(invocation -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            active.decrementAndGet();
            return CiPushResult.failed(CiPushResult.Outcome.UNCERTAIN, "wait");
        }).when(gitService).push(any());
        CiAutofixRunId runId = run.id();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.advance(runId));
            assertThat(inspecting.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.advance(runId));
            assertThat(second.isDone()).isFalse();
            release.countDown();
            first.get();
            second.get();
        }

        assertThat(maximum).hasValue(1);
        verify(gitService, times(1)).inspectAgentResult(any());
        verify(gitService, times(1)).push(any());
    }

    @Test
    void cancelsIdempotentlyAndSignalsAnActiveTurn()
    {
        doThrow(new AgentConflictException("Task has an active turn"))
                .doThrow(new AgentConflictException("Task has an active turn"))
                .doReturn(completion(HEAD))
                .when(completionService).complete(any());
        CiAutofixRun run = service.start(startCommand("cancel"));

        CiAutofixRun cancelled = service.cancel(run.id());
        assertThat(cancelled.status()).isEqualTo(Status.FINISHED);
        assertThat(cancelled.finishReason()).contains(FinishReason.CANCELLED);
        verify(turnService).cancel(turnId);
        verify(completionService).complete(any());

        assertThat(service.cancel(run.id())).isEqualTo(cancelled);
        verify(turnService, times(1)).cancel(turnId);
        verify(completionService, times(2)).complete(any());

        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.CANCELLED, Optional.of("cancelled")));
        assertThat(service.advance(run.id())).isEqualTo(cancelled);
        verify(completionService, times(3)).complete(any());
    }

    @Test
    void cancellationClosesTheTaskAfterATurnAlreadyFailed()
    {
        CiAutofixRun run = service.start(startCommand("cancel-failed-turn"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.FAILED, Optional.of("provider failed")));
        CiAutofixRun paused = service.advance(run.id());

        CiAutofixRun cancelled = service.cancel(paused.id());

        assertThat(cancelled.finishReason()).contains(FinishReason.CANCELLED);
        verify(completionService).complete(any());
    }

    @Test
    void resumesOnlyAPausedAuthorizedRunAndKeepsMessageIdempotency()
    {
        CiAutofixRun initial = service.start(startCommand("message"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.FAILED, Optional.of("failed")));
        CiAutofixRun paused = service.advance(initial.id());
        assertThat(paused.status()).isEqualTo(Status.PAUSED);

        TurnId recoveryId = TurnId.random();
        AgentTurn recovery = runningTurn(recoveryId, "recovery", "message-key");
        when(turnService.start(any())).thenReturn(recovery);
        when(turnService.get(recoveryId)).thenReturn(recovery);
        when(workspaceService.inspect(worktree)).thenReturn(workspace(true, "c".repeat(40)));

        SendCiAutofixMessageCommand command = new SendCiAutofixMessageCommand(
                paused.id(),
                "Please finish resolving the conflict",
                "message-key");
        CiAutofixRun resumed = service.sendMessage(command);
        assertThat(resumed.status()).isEqualTo(Status.ACTIVE);
        assertThat(resumed.currentTurnId()).contains(recoveryId.toString());
        assertThat(resumed.attemptsUsed()).isEqualTo(1);

        ArgumentCaptor<StartAgentTurnCommand> turn = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turnService, times(2)).start(turn.capture());
        StartAgentTurnCommand messageTurn = turn.getAllValues().getLast();
        assertThat(messageTurn.message()).isEqualTo("Please finish resolving the conflict");
        assertThat(messageTurn.attachments()).isEmpty();

        assertThat(service.sendMessage(command)).isEqualTo(resumed);
        verify(turnService, times(2)).start(any());
        verify(workspaceService).restore(taskId, worktree, REPOSITORY);
    }

    @Test
    void rejectsMessagesForActiveStaleOrOversizedRuns()
    {
        CiAutofixRun active = service.start(startCommand("message-reject"));
        assertThatThrownBy(() -> service.sendMessage(new SendCiAutofixMessageCommand(
                active.id(),
                "retry",
                "different-key")))
                .isInstanceOf(CiAutofixConflictException.class)
                .hasMessageContaining("paused");

        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.FAILED, Optional.of("failed")));
        CiAutofixRun paused = service.advance(active.id());
        when(gitHub.inspect(any())).thenReturn(snapshot(true, "author", true, "c".repeat(40), true));
        assertThatThrownBy(() -> service.sendMessage(new SendCiAutofixMessageCommand(
                paused.id(),
                "retry",
                "stale-key")))
                .isInstanceOf(CiAutofixConflictException.class)
                .hasMessageContaining("head changed");

        assertThatThrownBy(() -> service.sendMessage(new SendCiAutofixMessageCommand(
                paused.id(),
                "x".repeat(DefaultCiAutofixService.MAXIMUM_MESSAGE_CHARACTERS + 1),
                "large")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void expiresPausedAuthorizationAndRejectsAnUnreadyTask()
    {
        CiAutofixRun active = service.start(startCommand("message-safety"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.FAILED, Optional.of("failed")));
        CiAutofixRun paused = service.advance(active.id());

        DefaultCiAutofixService expiredService = service(
                store,
                Clock.fixed(NOW.plus(Duration.ofHours(9)), ZoneOffset.UTC));
        CiAutofixRun expired = expiredService.sendMessage(new SendCiAutofixMessageCommand(
                paused.id(),
                "retry",
                "expired-key"));
        assertThat(expired.finishReason()).contains(FinishReason.AUTHORIZATION_EXPIRED);
        verify(turnService, times(1)).start(any());

        MemoryStore unreadyStore = new MemoryStore();
        DefaultCiAutofixService unreadyService = service(unreadyStore);
        when(turnService.start(any())).thenReturn(runningTurn);
        CiAutofixRun unreadyActive = unreadyService.start(startCommand("unready"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.FAILED, Optional.of("failed")));
        CiAutofixRun unreadyPaused = unreadyService.advance(unreadyActive.id());
        when(taskService.get(taskId)).thenReturn(readyTask.fail("task failed", NOW, NOW.plus(Duration.ofDays(7))));

        assertThatThrownBy(() -> unreadyService.sendMessage(new SendCiAutofixMessageCommand(
                unreadyPaused.id(),
                "retry",
                "unready-key")))
                .isInstanceOf(CiAutofixConflictException.class)
                .hasMessageContaining("not ready");
    }

    private void assertPausedAfterTerminal(TurnState state, PauseReason reason)
    {
        MemoryStore stateStore = new MemoryStore();
        DefaultCiAutofixService stateService = service(stateStore);
        when(turnService.start(any())).thenReturn(runningTurn);
        CiAutofixRun run = stateService.start(startCommand("state-" + state));
        when(turnService.get(turnId)).thenReturn(terminalTurn(state, Optional.of("provider fact")));

        CiAutofixRun observed = stateService.advance(run.id());
        assertThat(observed.status()).isEqualTo(Status.PAUSED);
        assertThat(observed.pauseReason()).contains(reason);
        assertThat(observed.summary()).hasValueSatisfying(summary -> assertThat(summary).contains("provider fact"));
    }

    private DefaultCiAutofixService service(MemoryStore requestedStore)
    {
        return service(requestedStore, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DefaultCiAutofixService service(MemoryStore requestedStore, Clock requestedClock)
    {
        return new DefaultCiAutofixService(
                requestedStore,
                gitHub,
                taskService,
                turnService,
                completionService,
                workspaceService,
                logReader,
                memoryService,
                gitService,
                rebaseService,
                timelineService,
                credentials,
                requestedClock);
    }

    private BranchRebase completedRebase(StartBranchRebaseCommand command)
    {
        BranchRebase running = runningRebase(command);
        WorkspaceSnapshot workspace = workspaceService.inspect(command.worktree());
        return running.finish(
                BranchRebase.FinishReason.COMPLETED,
                "Base prepared",
                Optional.of(workspace.head()),
                NOW);
    }

    private BranchRebase runningRebase(StartBranchRebaseCommand command)
    {
        WorkspaceSnapshot workspace = workspaceService.inspect(command.worktree());
        return BranchRebase.create(
                        BranchRebaseId.random(),
                        command.idempotencyKey(),
                        command.agentTaskId().toString(),
                        command.repository(),
                        command.worktree(),
                        command.workBranch(),
                        command.baseBranch(),
                        workspace.head(),
                        snapshot.baseSha(),
                        NOW);
    }

    private StartCiAutofixCommand startCommand(String key)
    {
        return new StartCiAutofixCommand(
                PULL_REQUEST,
                worktree,
                AgentLaneId.CLAUDE_CLI,
                3,
                true,
                true,
                true,
                key);
    }

    private WorkspaceSnapshot workspace(boolean dirty, String head)
    {
        return new WorkspaceSnapshot(worktree, REPOSITORY, Optional.of("feature"), head, dirty);
    }

    private AgentTask activeTask()
    {
        return readyTask.activate(NOW);
    }

    private AgentTurn runningTurn(TurnId id, String providerReference, String idempotencyKey)
    {
        return AgentTurn.accepted(id, taskId, idempotencyKey, NOW)
                .running(new ProviderTurnRef(providerReference), Optional.empty(), NOW);
    }

    private AgentTurn terminalTurn(TurnState state, Optional<String> reason)
    {
        return terminalTurn(turnId, state, reason);
    }

    private AgentTurn terminalTurn(TurnId id, TurnState state, Optional<String> reason)
    {
        return runningTurn(id, "provider-turn", "ignored")
                .finish(state, TurnMetrics.empty(), reason, NOW);
    }

    private AgentEventEnvelope finalMessage(String text)
    {
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                1,
                taskId,
                Optional.of(turnId),
                NOW,
                new ProviderAgentEvent.Message(
                        Optional.of("message-1"),
                        Optional.of(NOW),
                        ProviderAgentEvent.MessagePhase.FINAL,
                        text,
                        Optional.empty()));
    }

    private CiHeadSnapshot snapshot(boolean open, String author, boolean sameRepository, String head, boolean failed)
    {
        CiStep step = new CiStep(
                1,
                "Compile",
                "completed",
                Optional.of(failed ? "failure" : "success"));
        CiJob job = new CiJob(
                200,
                100,
                "compile",
                1,
                "completed",
                Optional.of(failed ? "failure" : "success"),
                "https://github.com/bytequay/sample/actions/runs/100/job/200",
                ImmutableList.of(step));
        CiWorkflowRun workflow = new CiWorkflowRun(
                100,
                10,
                "Build",
                1,
                "completed",
                Optional.of(failed ? "failure" : "success"),
                head,
                "https://github.com/bytequay/sample/actions/runs/100",
                ImmutableList.of(job));
        return new CiHeadSnapshot(
                PULL_REQUEST,
                open,
                author,
                sameRepository,
                "feature",
                head,
                "main",
                BASE,
                "https://github.com/bytequay/sample/pull/2234",
                ImmutableList.of(workflow));
    }

    private CiHeadSnapshot failedSnapshot(String head, String base, int workflowAttempt)
    {
        return snapshot(
                head,
                base,
                ImmutableList.of(workflow(
                        100,
                        workflowAttempt,
                        "Build",
                        head,
                        "completed",
                        Optional.of("failure"))));
    }

    private CiHeadSnapshot greenSnapshot(String head, String base)
    {
        return snapshot(
                head,
                base,
                ImmutableList.of(workflow(
                        100,
                        1,
                        "Build",
                        head,
                        "completed",
                        Optional.of("success"))));
    }

    private CiHeadSnapshot snapshot(String head, String base, List<CiWorkflowRun> workflows)
    {
        return new CiHeadSnapshot(
                PULL_REQUEST,
                true,
                "author",
                true,
                "feature",
                head,
                "main",
                base,
                "https://github.com/bytequay/sample/pull/2234",
                workflows);
    }

    private static CiWorkflowRun workflow(
            long id,
            int attempt,
            String name,
            String head,
            String status,
            Optional<String> conclusion)
    {
        CiStep step = new CiStep(
                1,
                name,
                status,
                conclusion);
        CiJob job = new CiJob(
                id * 2,
                id,
                name.toLowerCase(Locale.ROOT),
                attempt,
                status,
                conclusion,
                "https://github.com/bytequay/sample/actions/runs/" + id + "/job/" + id * 2,
                ImmutableList.of(step));
        return new CiWorkflowRun(
                id,
                id + 1000,
                name,
                attempt,
                status,
                conclusion,
                head,
                "https://github.com/bytequay/sample/actions/runs/" + id,
                ImmutableList.of(job));
    }

    private TaskCompletion completion(String head)
    {
        TaskSummary summary = new TaskSummary(
                "untrusted agent response",
                workspace(false, head),
                ImmutableList.of(),
                TaskMetrics.empty(),
                ImmutableList.of(),
                Optional.of("verified"));
        return new TaskCompletion(taskId, summary, NOW, NOW.plus(Duration.ofDays(7)));
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, login, "", "", 0, 0, 0, null, null, null, null);
    }

    private static final class MemoryStore
            implements CiAutofixStore
    {
        private CiAutofixRun run;
        private CiAutofixRun conflict;

        @Override
        public void insert(CiAutofixRun value)
        {
            if (conflict != null) {
                run = conflict;
                throw new CiAutofixConflictException("simulated insert race");
            }
            if (run != null) {
                throw new CiAutofixConflictException("run already exists");
            }
            run = value;
        }

        @Override
        public Optional<CiAutofixRun> find(CiAutofixRunId runId)
        {
            return Optional.ofNullable(run).filter(value -> value.id().equals(runId));
        }

        @Override
        public Optional<CiAutofixRun> findByIdempotencyKey(String key)
        {
            return Optional.ofNullable(run).filter(value -> value.startIdempotencyKey().equals(key));
        }

        @Override
        public Optional<CiAutofixRun> findByAgentTaskId(String requestedTaskId)
        {
            return Optional.ofNullable(run)
                    .filter(value -> value.agentTaskId().filter(requestedTaskId::equals).isPresent());
        }

        @Override
        public List<CiAutofixRun> listRecent(int limit)
        {
            return run == null ? ImmutableList.of() : ImmutableList.of(run);
        }

        @Override
        public List<CiAutofixRun> listNonFinished(int limit)
        {
            return run == null || run.status() == Status.FINISHED ? ImmutableList.of() : ImmutableList.of(run);
        }

        @Override
        public CiAutofixRun update(CiAutofixRun value)
        {
            if (run == null || value.version() != run.version()) {
                throw new CiAutofixConflictException("version changed");
            }
            run = value.withVersion(value.version() + 1);
            return run;
        }
    }
}
