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

import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.agent.AgentStartupRecovery;
import com.bytequay.app.pr.PullRequestTimelineService;
import com.bytequay.app.rebase.BranchRebaseService;
import com.bytequay.app.rebase.StartBranchRebaseCommand;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
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
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseId;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.core.AgentEventSource.root;
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
    private static final RepoRef FORK = RepoRef.of("author", "sample");
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String FIXED_HEAD = "c".repeat(40);

    @TempDir
    private Path temporaryDirectory;

    private InMemoryCiAutofixStore store;
    private GitHubActionsClient gitHub;
    private AgentTaskService taskService;
    private AgentTurnService turnService;
    private AgentCompletionService completionService;
    private AgentDriverRegistry drivers;
    private WorktreeService worktreeService;
    private WorkspaceRepositoryService workspaces;
    private WorkspaceProvisioningService provisioning;
    private AgentResourceService resourceService;
    private AgentEventService eventService;
    private CiAutofixLogReader logReader;
    private CiMemoryService memoryService;
    private CiGitService gitService;
    private BranchRebaseService rebaseService;
    private PullRequestTimelineService timelineService;
    private CredentialService credentials;
    private DefaultCiAutofixService service;
    private DevelopmentCiOwnership ownership;
    private AgentTaskStore taskStore;
    private AgentTurnStore turnStore;
    private AgentRuntimeRegistry runtimes;
    private ObjectProvider<AgentStartupRecovery> recovery;
    private AtomicInteger recoveryRequests;
    private Path worktree;
    private TaskId taskId;
    private TaskWorktree taskWorktree;
    private TurnId turnId;
    private AgentTask openTask;
    private AgentTurn runningTurn;
    private CiHeadSnapshot snapshot;
    private TaskResources resources;

    @BeforeEach
    void setUp()
            throws Exception
    {
        store = new InMemoryCiAutofixStore();
        gitHub = mock(GitHubActionsClient.class);
        taskService = mock(AgentTaskService.class);
        turnService = mock(AgentTurnService.class);
        completionService = mock(AgentCompletionService.class);
        drivers = mock(AgentDriverRegistry.class);
        worktreeService = mock(WorktreeService.class);
        workspaces = mock(WorkspaceRepositoryService.class);
        provisioning = mock(WorkspaceProvisioningService.class);
        resourceService = mock(AgentResourceService.class);
        eventService = mock(AgentEventService.class);
        memoryService = mock(CiMemoryService.class);
        gitService = mock(CiGitService.class);
        rebaseService = mock(BranchRebaseService.class);
        timelineService = mock(PullRequestTimelineService.class);
        credentials = mock(CredentialService.class);
        taskId = TaskId.random();
        worktree = Files.createDirectory(temporaryDirectory.resolve(taskId.toString())).toRealPath();
        taskWorktree = new TaskWorktree(
                taskId,
                "workspace-1",
                REPOSITORY,
                worktree,
                "bytequay/task/" + taskId,
                HEAD);
        turnId = TurnId.random();
        openTask = AgentTask.created(
                taskId,
                false,
                "CI Autofix " + PULL_REQUEST.fullName(),
                TitleSource.USER,
                AgentLaneId.CLAUDE_CLI,
                taskWorktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.CI_AUTOFIX),
                NOW);
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
        AgentDriver driver = mock(AgentDriver.class);
        AgentCapabilities capabilities = new AgentCapabilities(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                Set.of(AgentPermissionPolicy.CI_AUTOFIX),
                Set.of());
        AgentProviderStatus providerStatus = new AgentProviderStatus(
                AgentLaneId.CLAUDE_CLI,
                AgentProviderStatus.Readiness.READY,
                Optional.of("test"),
                "ready",
                capabilities);
        when(drivers.require(AgentLaneId.CLAUDE_CLI)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(capabilities);
        when(driver.probe()).thenReturn(providerStatus);
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspaceContext());
        when(provisioning.synchronizeCode("workspace-1")).thenReturn(
                new WorkspaceCodeSyncResult("workspace-1", true, Optional.of(NOW)));
        when(gitHub.inspect(any())).thenReturn(snapshot);
        when(gitHub.readLog(any())).thenReturn(new BoundedCiLog(
                Optional.of(new com.bytequay.agent.core.BlobRef(
                        runtime.relativize(log).toString(),
                        "text/plain",
                        Files.size(log))),
                toIntExact(Files.size(log)),
                false,
                false,
                false,
                ImmutableList.of("error: compilation failed"),
                Optional.empty()));
        when(worktreeService.inspect(any())).thenAnswer(invocation ->
                new WorktreeSnapshot(invocation.getArgument(0), HEAD, false, false));
        when(taskService.create(any())).thenAnswer(invocation -> task(invocation.getArgument(0)));
        when(taskService.get(any())).thenAnswer(invocation -> {
            TaskId requested = invocation.getArgument(0);
            if (!requested.equals(taskId)) {
                throw new AgentTaskNotFoundException(requested);
            }
            return activeTask();
        });
        when(turnService.start(any())).thenAnswer(_ -> runningTurn);
        when(turnService.get(any())).thenAnswer(_ -> runningTurn);
        when(gitService.synchronizeBase(any())).thenAnswer(invocation -> {
            SynchronizeCiBaseCommand command = invocation.getArgument(0);
            return CiBaseSynchronization.ready(command.localBaseBranch());
        });
        when(gitService.removeSynchronizedBase(any())).thenReturn(true);
        when(rebaseService.start(any())).thenAnswer(invocation -> completedRebase(invocation.getArgument(0)));
        when(resourceService.require(any())).thenReturn(resources);
        when(eventService.readAfter(any(), any(Long.class), any(Integer.class))).thenReturn(ImmutableList.of());
        logReader = new CiAutofixLogReader(gitHub, resourceService, eventService);

        recoveryRequests = new AtomicInteger();
        recovery = new ObjectProvider<>()
        {
            @Override
            public AgentStartupRecovery getObject()
            {
                recoveryRequests.incrementAndGet();
                throw new AgentConflictException("Recovered child process is unavailable");
            }
        };
        taskStore = mock(AgentTaskStore.class);
        turnStore = mock(AgentTurnStore.class);
        runtimes = new AgentRuntimeRegistry();
        ownership = new DevelopmentCiOwnership(store, taskStore, turnStore, eventService, runtimes, Clock.fixed(NOW, ZoneOffset.UTC));
        service = new DefaultCiAutofixService(
                store,
                gitHub,
                taskService,
                turnService,
                completionService,
                drivers,
                worktreeService,
                workspaces,
                provisioning,
                logReader,
                memoryService,
                gitService,
                rebaseService,
                timelineService,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                ownership,
                recovery);
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
        assertThat(task.getValue().defaults().permissionPolicy()).isEqualTo(AgentPermissionPolicy.CI_AUTOFIX);
        assertThat(task.getValue().workspaceId()).isEqualTo("workspace-1");
        assertThat(task.getValue().physicalRepository()).isEqualTo(REPOSITORY);
        assertThat(task.getValue().startCommit()).isEqualTo(HEAD);
        assertThat(task.getValue().generatedBranch()).isEqualTo("author/bytequay/ci-autofix-bytequay-sample-" + taskId.toString().substring(0, 8));

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
        assertThat(ownership.instructions(openTask)).isEmpty();
        verify(eventService, never()).readHistory(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startsRepairWithFailedStepEvidenceWhenEveryLogIsUnavailable(boolean development)
    {
        when(gitHub.readLog(any())).thenReturn(BoundedCiLog.unavailable("GitHub Actions log download failed after one retry"));
        CiAutofixRun run;
        if (development) {
            AgentTask parent = developmentParent();
            TurnId source = reserveParent(parent);
            run = service.startDevelopment(startCommand("unavailable-logs"), parent.id(), source, publishedPr(), NOW);
            assertThat(runtimes.ownsDevelopmentContinuation(parent.id(), source)).isTrue();
        }
        else {
            run = service.start(startCommand("unavailable-logs"));
        }

        assertThat(run.status()).isEqualTo(Status.ACTIVE);
        assertThat(run.currentTurnId()).contains(turnId.toString());
        assertThat(run.attemptsUsed()).isEqualTo(1);
        assertThat(run.rerunCounter().used()).isZero();
        assertThat(run.attempts().getFirst().failures()).singleElement().satisfies(failure -> {
            assertThat(failure.summary()).contains("Build / compile failed at Compile", "log unavailable");
            assertThat(failure.log()).isEmpty();
        });
        ArgumentCaptor<StartAgentTurnCommand> turn = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turnService).start(turn.capture());
        assertThat(turn.getValue().attachments()).isEmpty();
        assertThat(turn.getValue().message()).contains("Build / compile failed at Compile", "log download failed after one retry", "not evidence that a check passed");
        verify(gitHub, never()).rerunFailedJobs(any());
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
        assertThat(run.taskStartCommit()).contains(HEAD);
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
        when(worktreeService.inspect(taskWorktree)).thenReturn(worktree(false, FIXED_HEAD));
        run = service.advance(run.id());

        assertThat(run.latestObservedBaseSha()).isEqualTo(movedBase);
        assertThat(run.attempts().getFirst().inputSha()).isEqualTo(FIXED_HEAD);
        assertThat(run.taskStartCommit()).contains(HEAD);
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
        when(worktreeService.inspect(taskWorktree)).thenReturn(worktree(false, FIXED_HEAD));

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
                "workspace-1",
                PULL_REQUEST.number(),
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
                "workspace-1",
                PULL_REQUEST.number(),
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
    void rejectsClosedUnroutableForkAndUnauthoredPullRequests()
    {
        when(gitHub.inspect(any())).thenReturn(snapshot(false, "author", true, HEAD, true));
        assertThatThrownBy(() -> service.start(startCommand("closed"))).hasMessageContaining("not open");

        when(gitHub.inspect(any())).thenReturn(snapshot(true, "author", false, HEAD, true));
        assertThatThrownBy(() -> service.start(startCommand("fork"))).hasMessageContaining("fork");

        when(gitHub.inspect(any())).thenReturn(snapshot(true, "another-user", true, HEAD, true));
        assertThatThrownBy(() -> service.start(startCommand("not-author"))).hasMessageContaining("author");
        verify(taskService, never()).create(any());
        verify(provisioning, never()).synchronizeCode(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void pushesToTheVerifiedHeadRepositoryAndKeepsBaseSynchronizationUpstream(boolean forkHead)
    {
        useForkWorkspace(forkHead);
        CiAutofixRun run = service.start(startCommand("fork-routing"));
        assertThat(run.scope().headRepository()).isEqualTo(snapshot.headRepository());
        assertThat(run.scope().physicalRepository()).contains(FORK);
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(
                FIXED_HEAD, "fingerprint", ImmutableList.of("src/Main.java"), ImmutableList.of("Fix feature")));
        run = service.advance(run.id());
        when(gitService.push(any())).thenReturn(CiPushResult.pushed(FIXED_HEAD));

        run = service.advance(run.id());

        assertThat(run.expectedRemoteHead()).isEqualTo(FIXED_HEAD);
        ArgumentCaptor<PushCiHeadCommand> push = ArgumentCaptor.forClass(PushCiHeadCommand.class);
        verify(gitService).push(push.capture());
        assertThat(push.getValue().remote()).isEqualTo(forkHead ? "origin" : "upstream");
        assertThat(push.getValue().remoteRepository()).isEqualTo(forkHead ? new RepositoryIdentity(FORK.owner(), FORK.repo()) : REPOSITORY);
        assertThat(push.getValue().physicalRepository()).isEqualTo(new RepositoryIdentity(FORK.owner(), FORK.repo()));
        assertThat(push.getValue().remoteHeadRef()).isEqualTo(snapshot.headRef());
        assertThat(push.getValue().journal().expectedRemoteSha()).isEqualTo(HEAD);
        ArgumentCaptor<SynchronizeCiBaseCommand> synchronize = ArgumentCaptor.forClass(SynchronizeCiBaseCommand.class);
        verify(gitService, times(2)).synchronizeBase(synchronize.capture());
        assertThat(synchronize.getAllValues()).allSatisfy(command -> {
            assertThat(command.sourceRemote()).isEqualTo("upstream");
            assertThat(command.remoteRepository()).isEqualTo(REPOSITORY);
        });
        ArgumentCaptor<InspectCiHeadQuery> inspect = ArgumentCaptor.forClass(InspectCiHeadQuery.class);
        verify(gitHub, times(4)).inspect(inspect.capture());
        assertThat(inspect.getAllValues()).extracting(InspectCiHeadQuery::pullRequest).containsOnly(PULL_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsChangedPullRequestScopeBeforeAJournaledPush(boolean baseRetarget)
    {
        useForkWorkspace(true);
        CiAutofixRun run = service.start(startCommand("fork-changed-before-push"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(
                FIXED_HEAD, "fingerprint", ImmutableList.of("src/Main.java"), ImmutableList.of("Fix feature")));
        run = service.advance(run.id());
        assertThat(run.pushJournal()).isPresent();
        when(gitHub.inspect(any())).thenReturn(changedScopeSnapshot(baseRetarget));

        CiAutofixRun rejected = service.advance(run.id());

        assertThat(rejected.finishReason()).contains(FinishReason.STALE_HEAD);
        verify(gitService, never()).push(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsChangedPullRequestScopeBeforeResumingARepair(boolean baseRetarget)
    {
        useForkWorkspace(true);
        CiAutofixRun run = service.start(startCommand("fork-changed-before-message"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.FAILED, Optional.of("repair failed")));
        CiAutofixRun paused = service.advance(run.id());
        when(gitHub.inspect(any())).thenReturn(changedScopeSnapshot(baseRetarget));

        assertThatThrownBy(() -> service.sendMessage(new SendCiAutofixMessageCommand(paused.id(), "Retry", "fork-resume")))
                .isInstanceOf(CiAutofixConflictException.class).hasMessageContaining("head or base branch changed");
        verify(turnService, times(1)).start(any());
    }

    @Test
    void watchesPendingCiWithoutRebasingAndStartsFixingAfterAFailure()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));

        CiAutofixRun run = service.start(startCommand("pending-to-failed"));

        assertThat(run.status()).isEqualTo(Status.ACTIVE);
        assertThat(run.agentTaskId()).contains(taskId.toString());
        assertThat(run.attempts()).isEmpty();
        assertThat(run.currentTurnId()).isEmpty();
        assertThat(run.currentRebaseId()).isEmpty();
        verify(gitService, never()).synchronizeBase(any());
        verify(rebaseService, never()).start(any());
        verify(turnService, never()).start(any());

        when(gitHub.inspect(any())).thenReturn(failedSnapshot(HEAD, BASE, 1));
        run = service.advance(run.id());

        assertThat(run.attempts()).singleElement();
        assertThat(run.currentTurnId()).isEmpty();
        verify(rebaseService).start(any());

        run = service.advance(run.id());
        assertThat(run.currentTurnId()).contains(turnId.toString());
        verify(turnService).start(any());
    }

    @Test
    void finishesAPendingWatchWhenCiBecomesGreenWithoutStartingAFix()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));
        CiAutofixRun run = service.start(startCommand("pending-to-green"));

        when(gitHub.inspect(any())).thenReturn(greenSnapshot(HEAD, BASE));
        when(gitService.checkFixupPositions(any())).thenReturn(FixupPositionCheck.valid(HEAD));
        run = service.advance(run.id());

        assertThat(run.status()).isEqualTo(Status.ACTIVE);
        assertThat(run.finalFixupCheck()).isPresent();
        assertThat(run.attempts()).isEmpty();

        doReturn(completion(HEAD)).when(completionService).complete(any());
        run = service.advance(run.id());

        assertThat(run.status()).isEqualTo(Status.FINISHED);
        assertThat(run.finishReason()).contains(FinishReason.GREEN);
        assertThat(run.attempts()).isEmpty();
        verify(rebaseService, never()).start(any());
        verify(turnService, never()).start(any());
    }

    @Test
    void persistsGreenBeforeCleanupAndReplaysTerminalEventDeliveryOnRetry()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));
        CiAutofixRun run = service.start(startCommand("green-cleanup-retry"));
        when(gitHub.inspect(any())).thenReturn(greenSnapshot(HEAD, BASE));
        when(gitService.checkFixupPositions(any())).thenReturn(FixupPositionCheck.valid(HEAD));
        run = service.advance(run.id());
        CiAutofixRunId runId = run.id();
        AtomicInteger completionAttempts = new AtomicInteger();
        when(completionService.complete(any())).thenAnswer(_ -> {
            Status expected = completionAttempts.incrementAndGet() == 1 ? Status.ACTIVE : Status.FINISHED;
            assertThat(store.find(runId).orElseThrow().status()).isEqualTo(expected);
            return completion(HEAD);
        });
        AtomicInteger cleanupAttempts = new AtomicInteger();
        doAnswer(_ -> {
            assertThat(store.find(runId).orElseThrow().status()).isEqualTo(Status.FINISHED);
            if (cleanupAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("worktree busy");
            }
            return null;
        }).when(completionService).cleanupWorktree(taskId);

        CiAutofixRun finished = service.advance(run.id());

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        when(taskService.get(taskId)).thenReturn(completedTask(HEAD));
        assertThat(service.advance(finished.id())).isEqualTo(finished);
        verify(completionService, times(2)).complete(any());
        verify(completionService, times(2)).cleanupWorktree(taskId);
    }

    @Test
    void reconcilesCompletedGreenTaskAfterFinishedRunWriteFails()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));
        CiAutofixRun run = service.start(startCommand("green-finish-reconcile"));
        when(gitHub.inspect(any())).thenReturn(greenSnapshot(HEAD, BASE));
        when(gitService.checkFixupPositions(any())).thenReturn(FixupPositionCheck.valid(HEAD));
        run = service.advance(run.id());
        doReturn(completion(HEAD)).when(completionService).complete(any());
        store.failNextUpdate(new CiAutofixConflictException("simulated finished write failure"));
        CiAutofixRunId runId = run.id();

        assertThatThrownBy(() -> service.advance(runId))
                .isInstanceOf(CiAutofixConflictException.class)
                .hasMessageContaining("finished write failure");
        assertThat(store.find(runId).orElseThrow().status()).isEqualTo(Status.ACTIVE);
        verify(completionService, never()).cleanupWorktree(taskId);

        when(taskService.get(taskId)).thenReturn(completedTask(HEAD));
        CiAutofixRun reconciled = service.advance(runId);

        assertThat(reconciled.status()).isEqualTo(Status.FINISHED);
        assertThat(reconciled.finishReason()).contains(FinishReason.GREEN);
        verify(completionService, times(2)).complete(any());
        verify(completionService).cleanupWorktree(taskId);
    }

    @Test
    void reportsManagedTaskProvisioningFailureWithoutAdoptingAPath()
    {
        doThrow(new IllegalArgumentException("exact PR head is unavailable")).when(taskService).create(any());

        assertThatThrownBy(() -> service.start(startCommand("provision-failed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact PR head");

        CiAutofixRun persisted = store.listRecent(10).getFirst();
        assertThat(persisted.status()).isEqualTo(Status.ACTIVE);
        assertThat(persisted.agentTaskId()).isPresent();
    }

    @Test
    void recoversThePreassignedTaskAfterAStartCrash()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));
        doThrow(new AssertionError("simulated process death")).when(taskService).create(any());

        assertThatThrownBy(() -> service.start(startCommand("start-crash")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("simulated process death");
        CiAutofixRun persisted = store.listRecent(10).getFirst();
        TaskId reserved = TaskId.valueOf(persisted.agentTaskId().orElseThrow());

        when(credentials.resolveGitHubCredential()).thenReturn(new CredentialService.GitHubCredential("token", profile("another-user")));
        doAnswer(invocation -> task(invocation.getArgument(0))).when(taskService).create(any());
        CiAutofixRun recovered = service.advance(persisted.id());

        assertThat(recovered.status()).isEqualTo(Status.ACTIVE);
        assertThat(taskId).isEqualTo(reserved);
        assertThat(taskService.get(reserved).taskWorktree()).contains(taskWorktree);
        assertThat(taskWorktree.generatedBranch()).isEqualTo("author/bytequay/ci-autofix-bytequay-sample-" + reserved.toString().substring(0, 8));
    }

    @Test
    void pausedAdvanceRecoversThePreassignedTaskAfterAStartCrash()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));
        doThrow(new AssertionError("simulated process death")).when(taskService).create(any());
        assertThatThrownBy(() -> service.start(startCommand("paused-start-crash")))
                .isInstanceOf(AssertionError.class);
        CiAutofixRun persisted = store.listRecent(10).getFirst();
        CiAutofixRun paused = store.update(persisted.pause(
                PauseReason.ERROR,
                "Task provisioning was interrupted",
                NOW));

        doAnswer(invocation -> task(invocation.getArgument(0))).when(taskService).create(any());
        CiAutofixRun recovered = service.advance(paused.id());

        TaskId reserved = TaskId.valueOf(recovered.agentTaskId().orElseThrow());
        assertThat(recovered.status()).isEqualTo(Status.PAUSED);
        assertThat(taskService.get(reserved).taskWorktree()).contains(taskWorktree);
    }

    @Test
    void cancellationRecoversThePreassignedTaskAfterAStartCrash()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));
        doThrow(new AssertionError("simulated process death")).when(taskService).create(any());
        assertThatThrownBy(() -> service.start(startCommand("cancel-start-crash")))
                .isInstanceOf(AssertionError.class);
        CiAutofixRun persisted = store.listRecent(10).getFirst();
        TaskId reserved = TaskId.valueOf(persisted.agentTaskId().orElseThrow());

        doAnswer(invocation -> task(invocation.getArgument(0))).when(taskService).create(any());
        CiAutofixRun cancelled = service.cancel(persisted.id());

        assertThat(cancelled.status()).isEqualTo(Status.FINISHED);
        assertThat(cancelled.finishReason()).contains(FinishReason.CANCELLED);
        assertThat(taskId).isEqualTo(reserved);
        verify(completionService).complete(any());
        verify(completionService).cleanupWorktree(reserved);
    }

    @Test
    void cancellationPreservesTheStartCrashGapUntilItsTaskCanBeVerified()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));
        doThrow(new AssertionError("simulated process death")).when(taskService).create(any());
        assertThatThrownBy(() -> service.start(startCommand("cancel-routing-crash")))
                .isInstanceOf(AssertionError.class);
        CiAutofixRun persisted = store.listRecent(10).getFirst();
        when(workspaces.requireReadyRepository("workspace-1"))
                .thenReturn(workspaceContext(new RepositoryIdentity("bytequay", "other")));

        CiAutofixRun preserved = service.cancel(persisted.id());

        assertThat(preserved.status()).isEqualTo(Status.PAUSED);
        assertThat(preserved.summary()).hasValueSatisfying(summary -> assertThat(summary).contains("selection changed"));
        verify(completionService, never()).complete(any());

        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspaceContext());
        doAnswer(invocation -> task(invocation.getArgument(0))).when(taskService).create(any());
        assertThat(service.cancel(preserved.id()).status()).isEqualTo(Status.FINISHED);
    }

    @Test
    void refusesToProvisionACrashedStartInANewRepositorySelection()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));
        doThrow(new AssertionError("simulated process death")).when(taskService).create(any());
        assertThatThrownBy(() -> service.start(startCommand("start-routing-crash")))
                .isInstanceOf(AssertionError.class);
        CiAutofixRun persisted = store.listRecent(10).getFirst();
        reset(taskService);
        when(taskService.get(any())).thenThrow(new AgentTaskNotFoundException(
                TaskId.valueOf(persisted.agentTaskId().orElseThrow())));
        when(workspaces.requireReadyRepository("workspace-1"))
                .thenReturn(workspaceContext(new RepositoryIdentity("bytequay", "other")));

        assertThatThrownBy(() -> service.advance(persisted.id()))
                .isInstanceOf(CiAutofixConflictException.class)
                .hasMessageContaining("selection changed");
        verify(taskService, never()).create(any());
    }

    @Test
    void adoptsTheTaskCreatedByAConcurrentMonitor()
    {
        when(gitHub.inspect(any())).thenReturn(pendingSnapshot(HEAD, BASE));
        AtomicReference<AgentTask> winner = new AtomicReference<>();
        doAnswer(invocation -> {
            TaskId requested = invocation.getArgument(0);
            if (winner.get() == null) {
                throw new AgentTaskNotFoundException(requested);
            }
            return winner.get();
        }).when(taskService).get(any());
        doAnswer(invocation -> {
            winner.set(task(invocation.getArgument(0)));
            throw new AgentConflictException("concurrent monitor created the task");
        }).when(taskService).create(any());

        CiAutofixRun run = service.start(startCommand("start-race"));

        assertThat(run.status()).isEqualTo(Status.ACTIVE);
        assertThat(run.agentTaskId()).contains(winner.get().id().toString());
        verify(taskService).create(any());
    }

    @Test
    void repeatsStartIdempotentlyAndDoesNotAllocateAfterAnInsertRace()
    {
        CiAutofixRun first = service.start(startCommand("repeat"));
        reset(gitHub, taskService, turnService);
        when(taskService.get(taskId)).thenReturn(openTask);

        assertThat(service.start(startCommand("repeat"))).isEqualTo(first);
        verify(gitHub, never()).inspect(any());
        verify(taskService, never()).create(any());

        InMemoryCiAutofixStore racingStore = new InMemoryCiAutofixStore();
        racingStore.conflict = first;
        DefaultCiAutofixService racing = service(racingStore);
        when(gitHub.inspect(any())).thenReturn(snapshot);
        when(taskService.get(taskId)).thenReturn(openTask);

        assertThat(racing.start(startCommand("repeat"))).isEqualTo(first);
        verify(taskService, never()).create(any());
    }

    @Test
    void rejectsARepeatedStartKeyFromAnotherWorkspace()
    {
        service.start(startCommand("workspace-key"));
        StartCiAutofixCommand wrongWorkspace = new StartCiAutofixCommand(
                "workspace-2",
                PULL_REQUEST.number(),
                AgentLaneId.CLAUDE_CLI,
                3,
                true,
                true,
                true,
                "workspace-key");

        assertThatThrownBy(() -> service.start(wrongWorkspace))
                .isInstanceOf(CiAutofixConflictException.class)
                .hasMessageContaining("another workspace");
    }

    @Test
    void retriesTheActiveTaskAfterTaskCreationFails()
    {
        doThrow(new IllegalStateException("provider unavailable")).when(taskService).create(any());

        assertThatThrownBy(() -> service.start(startCommand("task-failure")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider unavailable");

        CiAutofixRun persisted = store.listRecent(10).getFirst();
        assertThat(persisted.status()).isEqualTo(Status.ACTIVE);
        assertThat(persisted.agentTaskId()).isPresent();
        verify(turnService, never()).start(any());

        doAnswer(invocation -> task(invocation.getArgument(0))).when(taskService).create(any());
        CiAutofixRun recovered = service.advance(persisted.id());

        TaskId reserved = TaskId.valueOf(persisted.agentTaskId().orElseThrow());
        assertThat(recovered.status()).isEqualTo(Status.ACTIVE);
        assertThat(taskId).isEqualTo(reserved);
        assertThat(taskService.get(reserved).taskWorktree()).contains(taskWorktree);
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
        when(turnService.get(turnId)).thenReturn(AgentTurn.accepted(turnId, taskId, "ignored", turnSettings(), NOW));
        assertThat(service.advance(acceptedRun.id())).isEqualTo(acceptedRun);

        InMemoryCiAutofixStore secondStore = new InMemoryCiAutofixStore();
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
        InMemoryCiAutofixStore staleStore = new InMemoryCiAutofixStore();
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
    void cancellationPreservesAnAlreadyPausedDirtyWorktree()
    {
        CiAutofixRun run = service.start(startCommand("cancel-paused-dirty"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.FAILED, Optional.of("provider failed")));
        CiAutofixRun paused = service.advance(run.id());
        when(worktreeService.inspect(taskWorktree)).thenReturn(worktree(true, HEAD));

        CiAutofixRun preserved = service.cancel(paused.id());

        assertThat(preserved).isEqualTo(paused);
        assertThat(preserved.status()).isEqualTo(Status.PAUSED);
        verify(completionService, never()).complete(any());
        verify(completionService, never()).cleanupWorktree(any());
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
        when(worktreeService.inspect(taskWorktree)).thenReturn(worktree(true, "c".repeat(40)));

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
    }

    @Test
    void authenticationExpiryPausesWithoutConsumingAnotherFixAttempt()
    {
        CiAutofixRun initial = service.start(startCommand("auth-expired"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(
                TurnState.FAILED,
                Optional.of("Claude sign-in required"),
                Optional.of(AgentTurnFailureKind.AUTHENTICATION_REQUIRED)));

        CiAutofixRun paused = service.advance(initial.id());

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.AUTHENTICATION_REQUIRED);
        assertThat(paused.attemptsUsed()).isEqualTo(1);
        assertThat(paused.attempts()).hasSize(1);

        TurnId retryId = TurnId.random();
        AgentTurn retry = runningTurn(retryId, "provider-retry", "auth-retry-key");
        when(turnService.start(any())).thenReturn(retry);
        when(turnService.get(retryId)).thenReturn(retry);
        CiAutofixRun resumed = service.sendMessage(new SendCiAutofixMessageCommand(
                paused.id(),
                "Retry after provider login",
                "auth-retry-key"));

        assertThat(resumed.status()).isEqualTo(Status.ACTIVE);
        assertThat(resumed.currentTurnId()).contains(retryId.toString());
        assertThat(resumed.attemptsUsed()).isEqualTo(1);
        assertThat(resumed.attempts()).hasSize(1);
        assertThat(resumed.attempts().getFirst().agentTurnId()).contains(turnId.toString());
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
                .hasMessageContaining("head or base branch changed");

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

        InMemoryCiAutofixStore closedStore = new InMemoryCiAutofixStore();
        DefaultCiAutofixService closedService = service(closedStore);
        when(turnService.start(any())).thenReturn(runningTurn);
        CiAutofixRun openRun = closedService.start(startCommand("closed"));
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.FAILED, Optional.of("failed")));
        CiAutofixRun pausedRun = closedService.advance(openRun.id());
        when(taskService.get(taskId)).thenReturn(openTask.fail("task failed", NOW, NOW.plus(Duration.ofDays(7))));

        assertThatThrownBy(() -> closedService.sendMessage(new SendCiAutofixMessageCommand(
                pausedRun.id(),
                "retry",
                "closed-key")))
                .isInstanceOf(CiAutofixConflictException.class)
                .hasMessageContaining("not open");
    }

    private void assertPausedAfterTerminal(TurnState state, PauseReason reason)
    {
        InMemoryCiAutofixStore stateStore = new InMemoryCiAutofixStore();
        DefaultCiAutofixService stateService = service(stateStore);
        when(turnService.start(any())).thenReturn(runningTurn);
        CiAutofixRun run = stateService.start(startCommand("state-" + state));
        when(turnService.get(turnId)).thenReturn(terminalTurn(state, Optional.of("provider fact")));

        CiAutofixRun observed = stateService.advance(run.id());
        assertThat(observed.status()).isEqualTo(Status.PAUSED);
        assertThat(observed.pauseReason()).contains(reason);
        assertThat(observed.summary()).hasValueSatisfying(summary -> assertThat(summary).contains("provider fact"));
    }

    @Test
    void carriesAcceptedUserScopeAcrossPagesIntoEveryLinkedCiSession()
    {
        AgentTask parent = developmentParent();
        TurnId source = reserveParent(parent);
        AgentTurn earlier = parentTurn(parent.id(), TurnId.random());
        AgentTurn user = parentTurn(parent.id(), source);
        AgentTurn generated = parentTurn(parent.id(), TurnId.random())
                .withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR, source));
        AgentTurn later = parentTurn(parent.id(), TurnId.random());
        AgentTurn unsent = parentTurn(parent.id(), TurnId.random())
                .finishKnownUnsent(AgentMetrics.empty(), "Not delivered", NOW);
        when(turnStore.list(parent.id())).thenReturn(ImmutableList.of(earlier, unsent, user, generated, later));
        when(eventService.readHistory(parent.id(), OptionalLong.empty(), OptionalLong.of(0))).thenReturn(new AgentHistoryPage(
                ImmutableList.of(
                        parentMessage(parent.id(), earlier.id(), 1, "Only edit src/A.java and src/B.java", AgentEventPayload.DeliveryKind.TURN),
                        new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 2, parent.id(), Optional.of(earlier.id()), root(), NOW,
                                new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, "UNTRUSTED ASSISTANT INSTRUCTIONS")),
                        parentMessage(parent.id(), unsent.id(), 3, "UNSENT REQUEST", AgentEventPayload.DeliveryKind.TURN)),
                OptionalLong.empty(), OptionalLong.of(100), 7));
        when(eventService.readHistory(parent.id(), OptionalLong.empty(), OptionalLong.of(100))).thenReturn(new AgentHistoryPage(
                ImmutableList.of(
                        parentMessage(parent.id(), source, 4, "Use the second option. Do not edit .github/workflows.", AgentEventPayload.DeliveryKind.QUEUED),
                        parentMessage(parent.id(), source, 5, "Do not merge or delete the branch", AgentEventPayload.DeliveryKind.STEER),
                        parentMessage(parent.id(), generated.id(), 6, "GENERATED PUBLICATION INSTRUCTIONS", AgentEventPayload.DeliveryKind.TURN),
                        parentMessage(parent.id(), later.id(), 7, "LATER OR UNDELIVERED REQUEST", AgentEventPayload.DeliveryKind.TURN)),
                OptionalLong.of(0), OptionalLong.empty(), 7));

        service.startDevelopment(startCommand("scoped-ci"), parent.id(), source, publishedPr(), NOW);
        String instructions = ownership.instructions(activeTask());
        assertThat(instructions).containsSubsequence("Only edit src/A.java and src/B.java", "Use the second option", "Do not merge or delete the branch")
                .contains("Do not edit .github/workflows.", "The current CI/rebase instructions still govern", "ByteQuay owns remote writes")
                .doesNotContain("UNTRUSTED ASSISTANT", "GENERATED PUBLICATION", "LATER OR UNDELIVERED", "UNSENT REQUEST");

        when(eventService.readHistory(eq(parent.id()), any(), any())).thenThrow(new AssertionError("CI sessions must reuse the accepted scope snapshot"));
        assertThat(ownership.instructions(activeTask())).isEqualTo(instructions);
        assertThat(ownership.instructions(activeTask())).isEqualTo(instructions);
        ownership.revoke(parent.id());
        assertThatThrownBy(() -> ownership.instructions(activeTask())).isInstanceOf(CiAutofixConflictException.class);
    }

    @Test
    void scopesTheFirstRebaseBeforeAnyRepairAndPreservesTheRegisteredOwnerIdentity()
    {
        AgentTask parent = developmentParent();
        TurnId user = reserveParent(parent);
        AgentTurn generated = parentTurn(parent.id(), TurnId.random())
                .withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, user));
        when(turnStore.find(generated.id())).thenReturn(Optional.of(generated));
        when(turnStore.list(parent.id())).thenReturn(ImmutableList.of(parentTurn(parent.id(), user), generated));
        synchronized (runtimes.taskLock(parent.id())) {
            ReflectionTestUtils.invokeMethod(runtimes, "releaseDevelopmentOwner", parent.id(), user);
            ReflectionTestUtils.invokeMethod(runtimes, "reserveDevelopmentOwner", parent.id(), generated.id());
        }
        doAnswer(invocation -> {
            assertThat(store.run.attempts()).isEmpty();
            assertThat(ownership.instructions(activeTask())).contains("Implement the requested change");
            assertThat(runtimes.ownsDevelopmentContinuation(parent.id(), generated.id())).isTrue();
            assertThat(runtimes.ownsDevelopmentContinuation(parent.id(), user)).isFalse();
            return runningRebase(invocation.getArgument(0));
        }).when(rebaseService).start(any());

        CiAutofixRun run = service.startDevelopment(startCommand("scoped-rebase"), parent.id(), generated.id(), publishedPr(), NOW);

        assertThat(run.currentRebaseId()).isPresent();
        assertThat(run.attempts()).isEmpty();
        verify(turnService, never()).start(any());
    }

    @Test
    void refusesMissingOrOversizedParentInputBeforeStartingCiWork()
    {
        AgentTask parent = developmentParent();
        TurnId source = reserveParent(parent);
        when(eventService.readHistory(eq(parent.id()), any(), any())).thenReturn(
                new AgentHistoryPage(ImmutableList.of(), OptionalLong.empty(), OptionalLong.empty(), 0));
        assertThatThrownBy(() -> service.startDevelopment(startCommand("missing-scope"), parent.id(), source, publishedPr(), NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Development user inputs are missing");

        when(eventService.readHistory(eq(parent.id()), any(), any())).thenReturn(new AgentHistoryPage(
                ImmutableList.of(parentMessage(parent.id(), source, 1, "x".repeat(128 * 1024), AgentEventPayload.DeliveryKind.TURN)),
                OptionalLong.empty(), OptionalLong.empty(), 1));
        assertThatThrownBy(() -> service.startDevelopment(startCommand("large-scope"), parent.id(), source, publishedPr(), NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Development user scope exceeds the prompt limit");
        assertThat(store.listRecent(10)).isEmpty();
        verify(taskService, never()).create(any());
        verify(turnService, never()).start(any());
    }

    @Test
    void identifiesUnavailableParentAttachmentsWithoutInventingTheirContents()
    {
        AgentTask parent = developmentParent();
        TurnId source = reserveParent(parent);
        when(eventService.readHistory(eq(parent.id()), any(), any())).thenReturn(new AgentHistoryPage(
                ImmutableList.of(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, parent.id(), Optional.of(source), root(), NOW,
                        new AgentEventPayload.UserMessage("Follow the attached constraints", ImmutableList.of(new AgentAttachmentReference("scope", "scope.png", "image/png")),
                                AgentEventPayload.DeliveryKind.TURN))), OptionalLong.empty(), OptionalLong.empty(), 1));

        service.startDevelopment(startCommand("attachment-scope"), parent.id(), source, publishedPr(), NOW);

        assertThat(ownership.instructions(activeTask())).contains("Attachment unavailable in this CI task: scope.png", "do not guess its contents");
    }

    @Test
    void persistsDevelopmentOwnerBeforeCreatingWorkAndKeepsItsOriginalBudget()
    {
        AgentTask parent = developmentParent();
        TurnId source = reserveParent(parent);
        Instant activated = NOW.minusSeconds(3600);
        doAnswer(invocation -> {
            assertThat(store.run.scope().parentTaskId()).contains(parent.id().toString());
            return task(invocation.getArgument(0));
        }).when(taskService).create(any());

        CiAutofixRun run = service.startDevelopment(startCommand("development"), parent.id(), source, publishedPr(), activated);
        assertThat(run.authorization().authorizedAt()).isEqualTo(activated);
        assertThat(run.authorization().expiresAt()).isEqualTo(activated.plus(Duration.ofHours(8)));
        assertThat(run.authorization().maxFixAttempts()).isEqualTo(3);
        assertThat(service.startDevelopment(startCommand("development"), parent.id(), source, publishedPr(), NOW).authorization()).isEqualTo(run.authorization());
        assertThatThrownBy(() -> service.start(startCommand("development"))).isInstanceOf(CiAutofixConflictException.class);
        verify(taskService).create(any());
    }

    @Test
    void lostDevelopmentOwnerCannotReplayPushOrReprovisionItsChild()
    {
        AgentTask parent = developmentParent();
        TurnId source = reserveParent(parent);
        CiAutofixRun run = service.startDevelopment(startCommand("owned-push"), parent.id(), source, publishedPr(), NOW);
        when(turnService.get(turnId)).thenReturn(terminalTurn(TurnState.COMPLETED, Optional.empty()));
        run = service.advance(run.id());
        when(gitService.inspectAgentResult(any())).thenReturn(CiGitInspection.ready(
                FIXED_HEAD, "fingerprint", ImmutableList.of("src/Main.java"), ImmutableList.of("Fix feature")));
        run = service.advance(run.id());
        CiAutofixRun pending = run;
        assertThat(pending.pushJournal()).isPresent();

        DefaultCiAutofixService restarted = service(store);
        assertThatThrownBy(() -> restarted.advance(pending.id())).isInstanceOf(CiAutofixConflictException.class);
        assertThatThrownBy(() -> restarted.startDevelopment(startCommand("owned-push"), parent.id(), source, publishedPr(), NOW))
                .isInstanceOf(CiAutofixConflictException.class);
        verify(gitService, never()).push(any());
        verify(taskService).create(any());
    }

    @Test
    void unavailableRecoveredChildStopBlocksDeletionWithoutReplayingItsRun()
    {
        AgentTask parent = developmentParent();
        CiAutofixRun run = service.startDevelopment(startCommand("owned-recovery"), parent.id(), reserveParent(parent), publishedPr(), NOW);
        AgentTask child = activeTask();
        AgentTask recovered = child.claimWorktreeMutation(WorktreeMutationClaim.recovery("launch", NOW), NOW);
        when(taskService.get(taskId)).thenReturn(recovered);

        assertThatThrownBy(() -> service.deleteDevelopment(parent.id())).isInstanceOf(AgentConflictException.class);
        assertThat(ownership.authorized(run)).isFalse();
        assertThat(recoveryRequests.get()).isEqualTo(1);
        verify(completionService, never()).delete(any());
        verify(gitService, never()).push(any());
        verify(rebaseService, never()).cancel(any());
        assertThatThrownBy(() -> service.advance(run.id())).isInstanceOf(CiAutofixConflictException.class);
    }

    @Test
    void deletesPreservedChildrenOnlyAfterShutdown()
    {
        AgentTask parent = developmentParent();
        CiAutofixRun run = service.startDevelopment(startCommand("owned-delete"), parent.id(), reserveParent(parent), publishedPr(), NOW);
        when(turnService.stopImpact(taskId)).thenReturn(new AgentRuntimeStopImpact(taskId, "generation", Optional.empty(), false));
        when(turnService.stopRuntime(taskId, "generation")).thenReturn(activeTask());
        doAnswer(_ -> {
            assertThat(store.run.status()).isEqualTo(Status.FINISHED);
            assertThat(ownership.authorized(store.run)).isFalse();
            return null;
        }).when(completionService).delete(taskId);

        service.deleteDevelopment(parent.id());

        assertThat(store.run.finishReason()).contains(FinishReason.CANCELLED);
        assertThat(store.run.scope().parentTaskId()).contains(parent.id().toString());
        assertThat(store.run.scope().pullRequest()).isEqualTo(run.scope().pullRequest());
        verify(completionService).delete(taskId);
        verify(rebaseService, never()).cancel(any());
        verify(gitHub, never()).rerunFailedJobs(any());
    }

    @Test
    void stoppedDevelopmentReleasesTheBranchButPreservesItsChild()
    {
        AgentTask parent = developmentParent();
        CiAutofixRun run = service.startDevelopment(startCommand("owned-stop"), parent.id(), reserveParent(parent), publishedPr(), NOW);
        store.update(run.pause(PauseReason.TURN_INTERRUPTED, "Approval request timed out", NOW));
        when(turnService.stopImpact(taskId)).thenReturn(new AgentRuntimeStopImpact(taskId, "generation", Optional.empty(), false));
        when(turnService.stopRuntime(taskId, "generation")).thenReturn(activeTask());
        AgentTask idle = parent.withDevelopmentContinuation(false, NOW);
        assertThatThrownBy(() -> ownership.requireSourceAdmission(idle)).isInstanceOf(AgentConflictException.class);

        service.stopDevelopment(parent.id());

        ownership.requireSourceAdmission(idle);
        assertThat(store.run.finishReason()).contains(FinishReason.CANCELLED);
        assertThat(store.run.agentTaskId()).isEqualTo(run.agentTaskId());
        assertThat(store.run.scope()).isEqualTo(run.scope());
        assertThat(service.canMessage(store.run)).isFalse();
        assertThatThrownBy(() -> service.advance(run.id())).isInstanceOf(CiAutofixConflictException.class);
        verify(completionService, never()).delete(any());
        verify(completionService, never()).cleanupWorktree(any());
        verify(gitService, never()).push(any());
    }

    @Test
    void rejectsStandaloneAttachmentDuringSourceWorkAndReservesAdmissionWhenIdle()
    {
        AgentTask parent = developmentParent();
        when(taskStore.listByWorkspace("workspace-1")).thenReturn(ImmutableList.of(parent));
        when(taskStore.find(parent.id())).thenReturn(Optional.of(parent));
        assertThatThrownBy(() -> service.start(startCommand("manual-busy"))).isInstanceOf(CiAutofixConflictException.class);
        verify(taskService, never()).create(any());

        AgentTask idle = parent.withDevelopmentContinuation(false, NOW);
        when(taskStore.find(parent.id())).thenReturn(Optional.of(idle));
        service.start(startCommand("manual-idle"));
        assertThatThrownBy(() -> ownership.requireSourceAdmission(idle)).isInstanceOf(AgentConflictException.class);
    }

    @Test
    void canMessageUsesTheFreshRunAndNeverResumesARevokedParent()
    {
        AgentTask parent = developmentParent();
        CiAutofixRun run = service.startDevelopment(startCommand("owned-message"), parent.id(), reserveParent(parent), publishedPr(), NOW);
        CiAutofixRun paused = store.update(run.pause(PauseReason.INVALID_GIT_RESULT, "Needs a user correction", NOW));
        assertThat(service.canMessage(paused)).isTrue();
        store.update(paused.resume(NOW));
        assertThat(service.canMessage(paused)).isFalse();
        CiAutofixRun pausedAgain = store.update(store.run.pause(PauseReason.INVALID_GIT_RESULT, "Needs a user correction", NOW));
        ownership.revoke(parent.id());
        assertThat(service.canMessage(pausedAgain)).isFalse();
        assertThatThrownBy(() -> service.sendMessage(new SendCiAutofixMessageCommand(run.id(), "Answer", "owned-answer")))
                .isInstanceOf(CiAutofixConflictException.class);
    }

    @Test
    void stopDrainsAdmittedProvisioningBeforeInspectingTheChild()
            throws Exception
    {
        AgentTask parent = developmentParent();
        TurnId source = reserveParent(parent);
        CountDownLatch provisioningStarted = new CountDownLatch(1);
        CountDownLatch releaseProvisioning = new CountDownLatch(1);
        doAnswer(invocation -> {
            provisioningStarted.countDown();
            assertThat(releaseProvisioning.await(5, TimeUnit.SECONDS)).isTrue();
            return task(invocation.getArgument(0));
        }).when(taskService).create(any());
        when(turnService.stopImpact(any())).thenAnswer(invocation -> new AgentRuntimeStopImpact(invocation.getArgument(0), "stopped", Optional.empty(), false));
        when(turnService.stopRuntime(any(), eq("stopped"))).thenAnswer(_ -> activeTask());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var starting = executor.submit(() -> service.startDevelopment(startCommand("owned-provision"), parent.id(), source, publishedPr(), NOW));
            assertThat(provisioningStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var stopping = executor.submit(() -> service.stopDevelopment(parent.id()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ownership.authorized(store.run) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(ownership.authorized(store.run)).isFalse();
            assertThat(stopping.isDone()).isFalse();
            releaseProvisioning.countDown();
            starting.get(5, TimeUnit.SECONDS);
            stopping.get(5, TimeUnit.SECONDS);
        }
        verify(taskService).create(any());
        verify(turnService).stopRuntime(taskId, "stopped");
        verify(turnService, never()).start(any());
        assertThatThrownBy(() -> service.advance(store.run.id())).isInstanceOf(CiAutofixConflictException.class);
    }

    @Test
    void ownedCancelRevokesTheSourceBeforeStoppingItsChild()
    {
        AgentTask parent = developmentParent();
        TurnId source = reserveParent(parent);
        CiAutofixRun run = service.startDevelopment(startCommand("owned-cancel"), parent.id(), source, publishedPr(), NOW);
        doReturn(parent).when(taskService).get(parent.id());
        when(turnService.stopImpact(any())).thenAnswer(invocation -> new AgentRuntimeStopImpact(invocation.getArgument(0), "stop", Optional.empty(), false));
        when(turnService.stopRuntime(taskId, "stop")).thenReturn(activeTask());
        when(turnService.stopRuntime(parent.id(), "stop")).thenAnswer(_ -> {
            ReflectionTestUtils.invokeMethod(runtimes, "stopDevelopment", parent.id());
            service.stopDevelopment(parent.id());
            return parent;
        });

        service.cancel(run.id());

        assertThat(runtimes.ownsDevelopmentContinuation(parent.id(), source)).isFalse();
        InOrder stopped = inOrder(turnService);
        stopped.verify(turnService).stopRuntime(parent.id(), "stop");
        stopped.verify(turnService).stopRuntime(taskId, "stop");
        assertThat(ownership.authorized(store.run)).isFalse();
    }

    @Test
    void rejectsExpiredOrChangedDevelopmentScopeBeforeProvisioning()
    {
        AgentTask parent = developmentParent();
        TurnId source = reserveParent(parent);
        assertThatThrownBy(() -> service.startDevelopment(startCommand("expired-owned"), parent.id(), source, publishedPr(), NOW.minus(Duration.ofHours(8))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expired");
        GitHubPullRequestIdentity expected = publishedPr();
        GitHubPullRequestIdentity moved = new GitHubPullRequestIdentity(expected.pullRequest(), expected.url(), expected.headRepository(), expected.headBranch(),
                FIXED_HEAD, expected.baseRepository(), expected.baseBranch(), expected.baseSha(), expected.state(), expected.draft(), expected.author());
        assertThatThrownBy(() -> service.startDevelopment(startCommand("moved-owned"), parent.id(), source, moved, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
        GitHubPullRequestIdentity differentFork = new GitHubPullRequestIdentity(expected.pullRequest(), expected.url(), FORK, expected.headBranch(),
                expected.headSha(), expected.baseRepository(), expected.baseBranch(), expected.baseSha(), expected.state(), expected.draft(), expected.author());
        assertThatThrownBy(() -> service.startDevelopment(startCommand("fork-owned"), parent.id(), source, differentFork, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
        verify(taskService, never()).create(any());
        verify(provisioning, never()).synchronizeCode(any());
    }

    @Test
    void rejectsAnUnavailableAcceptedCiRepairAgentWithoutFallback()
    {
        AgentTask parent = developmentParent();
        TurnId source = reserveParent(parent);
        doThrow(new IllegalArgumentException("Accepted CI repair agent is unavailable")).when(drivers).require(AgentLaneId.CLAUDE_CLI);
        assertThatThrownBy(() -> service.startDevelopment(startCommand("unavailable-owned"), parent.id(), source, publishedPr(), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unavailable");
        verify(gitHub, never()).inspect(any());
        verify(taskService, never()).create(any());
    }

    private AgentTask developmentParent()
    {
        TaskId parent = TaskId.random();
        String branch = "bytequay/task/" + parent;
        snapshot = new CiHeadSnapshot(snapshot.pullRequest(), snapshot.open(), snapshot.author(), snapshot.headRepository(), branch, snapshot.headSha(),
                snapshot.baseRef(), snapshot.baseSha(), snapshot.url(), snapshot.workflowRuns());
        when(gitHub.inspect(any())).thenReturn(snapshot);
        TaskWorktree source = new TaskWorktree(parent, "workspace-1", REPOSITORY, temporaryDirectory.resolve(parent.toString()), branch, HEAD);
        return AgentTask.created(parent, true, "Development", TitleSource.USER, AgentLaneId.CLAUDE_CLI, source,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW).withDevelopmentContinuation(true, NOW);
    }

    private TurnId reserveParent(AgentTask parent)
    {
        TurnId source = TurnId.random();
        when(taskStore.find(parent.id())).thenReturn(Optional.of(parent));
        AgentTurn user = parentTurn(parent.id(), source);
        when(turnStore.find(source)).thenReturn(Optional.of(user));
        when(turnStore.list(parent.id())).thenReturn(ImmutableList.of(user));
        when(eventService.readHistory(eq(parent.id()), any(), any())).thenReturn(new AgentHistoryPage(
                ImmutableList.of(parentMessage(parent.id(), source, 1, "Implement the requested change", AgentEventPayload.DeliveryKind.TURN)),
                OptionalLong.empty(), OptionalLong.empty(), 1));
        synchronized (runtimes.taskLock(parent.id())) {
            ReflectionTestUtils.invokeMethod(runtimes, "reserveDevelopmentOwner", parent.id(), source);
        }
        return source;
    }

    private static AgentTurn parentTurn(TaskId parent, TurnId turn)
    {
        return AgentTurn.accepted(turn, parent, turn.toString(), new AgentTurnSettings(
                Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(10), ImmutableList.of()), NOW);
    }

    private static AgentEventEnvelope parentMessage(TaskId parent, TurnId turn, long sequence, String text, AgentEventPayload.DeliveryKind deliveryKind)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), sequence, parent, Optional.of(turn), root(), NOW,
                new AgentEventPayload.UserMessage(text, ImmutableList.of(), deliveryKind));
    }

    private GitHubPullRequestIdentity publishedPr()
    {
        RepoRef repository = RepoRef.of(PULL_REQUEST.owner(), PULL_REQUEST.repo());
        return new GitHubPullRequestIdentity(PULL_REQUEST, snapshot.url(), repository, snapshot.headRef(), HEAD, repository,
                snapshot.baseRef(), snapshot.baseSha(), GitHubPullRequestIdentity.State.OPEN, true, snapshot.author());
    }

    private DefaultCiAutofixService service(InMemoryCiAutofixStore requestedStore)
    {
        return service(requestedStore, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DefaultCiAutofixService service(InMemoryCiAutofixStore requestedStore, Clock requestedClock)
    {
        return new DefaultCiAutofixService(
                requestedStore,
                gitHub,
                taskService,
                turnService,
                completionService,
                drivers,
                worktreeService,
                workspaces,
                provisioning,
                logReader,
                memoryService,
                gitService,
                rebaseService,
                timelineService,
                credentials,
                requestedClock,
                new DevelopmentCiOwnership(requestedStore, taskStore, turnStore, eventService, runtimes, requestedClock),
                recovery);
    }

    private BranchRebase completedRebase(StartBranchRebaseCommand command)
    {
        BranchRebase running = runningRebase(command);
        WorktreeSnapshot workspace = worktreeService.inspect(command.taskWorktree());
        return running.finish(
                BranchRebase.FinishReason.COMPLETED,
                "Base prepared",
                Optional.of(workspace.head()),
                NOW);
    }

    private BranchRebase runningRebase(StartBranchRebaseCommand command)
    {
        WorktreeSnapshot workspace = worktreeService.inspect(command.taskWorktree());
        TaskWorktree owned = command.taskWorktree();
        return BranchRebase.create(
                        BranchRebaseId.random(),
                        command.idempotencyKey(),
                        owned.taskId().toString(),
                        RepoRef.of(owned.physicalRepository().owner(), owned.physicalRepository().name()),
                        owned.canonicalWorktree(),
                        owned.generatedBranch(),
                        command.baseBranch(),
                        workspace.head(),
                        snapshot.baseSha(),
                        NOW);
    }

    private StartCiAutofixCommand startCommand(String key)
    {
        return new StartCiAutofixCommand(
                "workspace-1",
                PULL_REQUEST.number(),
                AgentLaneId.CLAUDE_CLI,
                3,
                true,
                true,
                true,
                key);
    }

    private WorktreeSnapshot worktree(boolean dirty, String head)
    {
        return new WorktreeSnapshot(taskWorktree, head, dirty, false);
    }

    private Optional<WorktreeSnapshot> summaryWorktree(String head)
    {
        return Optional.of(worktree(false, head));
    }

    private WorkspaceRepositoryContext workspaceContext()
    {
        return workspaceContext(REPOSITORY);
    }

    private void useForkWorkspace(boolean forkHead)
    {
        if (forkHead) {
            snapshot = withHeadRepository(snapshot, FORK);
        }
        when(gitHub.inspect(any())).thenReturn(snapshot);
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(new WorkspaceRepositoryContext(
                "workspace-1", temporaryDirectory, 1, FORK, OptionalLong.of(2), RepoRef.of(PULL_REQUEST.owner(), PULL_REQUEST.repo()),
                "upstream", "origin", "main", ImmutableList.of()));
    }

    private static CiHeadSnapshot withHeadRepository(CiHeadSnapshot snapshot, RepoRef repository)
    {
        return new CiHeadSnapshot(snapshot.pullRequest(), snapshot.open(), snapshot.author(), repository, snapshot.headRef(), snapshot.headSha(),
                snapshot.baseRef(), snapshot.baseSha(), snapshot.url(), snapshot.workflowRuns());
    }

    private CiHeadSnapshot changedScopeSnapshot(boolean baseRetarget)
    {
        return new CiHeadSnapshot(snapshot.pullRequest(), snapshot.open(), snapshot.author(),
                baseRetarget ? snapshot.headRepository() : RepoRef.of("another-owner", "sample"), snapshot.headRef(), snapshot.headSha(),
                baseRetarget ? "release" : snapshot.baseRef(), snapshot.baseSha(), snapshot.url(), snapshot.workflowRuns());
    }

    private WorkspaceRepositoryContext workspaceContext(RepositoryIdentity selectedRepository)
    {
        RepoRef repository = RepoRef.of(REPOSITORY.owner(), REPOSITORY.name());
        return new WorkspaceRepositoryContext(
                "workspace-1",
                temporaryDirectory,
                1,
                RepoRef.of(selectedRepository.owner(), selectedRepository.name()),
                OptionalLong.empty(),
                repository,
                "origin",
                "origin",
                "main",
                ImmutableList.of());
    }

    private AgentTask task(CreateAgentTaskCommand command)
    {
        taskId = command.taskId();
        try {
            worktree = Files.createDirectories(temporaryDirectory.resolve(taskId.toString())).toRealPath();
        }
        catch (IOException failure) {
            throw new AssertionError(failure);
        }
        taskWorktree = new TaskWorktree(
                taskId,
                command.workspaceId(),
                command.physicalRepository(),
                worktree,
                command.generatedBranch(),
                command.startCommit());
        openTask = AgentTask.created(
                taskId,
                false,
                command.title(),
                TitleSource.USER,
                command.laneId(),
                taskWorktree,
                command.defaults(),
                NOW);
        runningTurn = runningTurn(turnId, "provider-turn", "ignored");
        return openTask;
    }

    private AgentTask activeTask()
    {
        return openTask;
    }

    private AgentTask completedTask(String head)
    {
        TaskSummary summary = new TaskSummary(
                "completed",
                summaryWorktree(head),
                AgentMetrics.empty(),
                Optional.empty());
        return openTask.complete(summary, NOW, NOW.plus(Duration.ofDays(7)));
    }

    private AgentTurn runningTurn(TurnId id, String providerReference, String idempotencyKey)
    {
        return AgentTurn.accepted(id, taskId, idempotencyKey, turnSettings(), NOW)
                .markDeliveryStarted(NOW)
                .running(new ProviderTurnRef(providerReference), Optional.empty());
    }

    private static AgentTurnSettings turnSettings()
    {
        return new AgentTurnSettings(
                Optional.empty(),
                Optional.empty(),
                AgentPermissionPolicy.AUTO,
                Duration.ofMinutes(5),
                ImmutableList.of());
    }

    private AgentTurn terminalTurn(TurnState state, Optional<String> reason)
    {
        return terminalTurn(turnId, state, reason);
    }

    private AgentTurn terminalTurn(
            TurnState state,
            Optional<String> reason,
            Optional<AgentTurnFailureKind> failureKind)
    {
        return runningTurn(turnId, "provider-turn", "ignored")
                .finish(state, AgentMetrics.empty(), reason, failureKind, NOW);
    }

    private AgentTurn terminalTurn(TurnId id, TurnState state, Optional<String> reason)
    {
        return runningTurn(id, "provider-turn", "ignored")
                .finish(state, AgentMetrics.empty(), reason, NOW);
    }

    private AgentEventEnvelope finalMessage(String text)
    {
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                EventId.random(),
                1,
                taskId,
                Optional.of(turnId),
                root(),
                NOW,
                new AgentEventPayload.AssistantMessage(
                        MessageKind.ANSWER,
                        MessagePhase.FINAL,
                        text));
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
                sameRepository ? RepoRef.of(PULL_REQUEST.owner(), PULL_REQUEST.repo()) : FORK,
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

    private CiHeadSnapshot pendingSnapshot(String head, String base)
    {
        return snapshot(
                head,
                base,
                ImmutableList.of(workflow(
                        100,
                        1,
                        "Build",
                        head,
                        "in_progress",
                        Optional.empty())));
    }

    private CiHeadSnapshot snapshot(String head, String base, List<CiWorkflowRun> workflows)
    {
        return new CiHeadSnapshot(
                PULL_REQUEST,
                true,
                "author",
                RepoRef.of(PULL_REQUEST.owner(), PULL_REQUEST.repo()),
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
                summaryWorktree(head),
                AgentMetrics.empty(),
                Optional.of("verified"));
        return new TaskCompletion(taskId, summary, NOW, NOW.plus(Duration.ofDays(7)));
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, login, "", "", 0, 0, 0, null, null, null, null);
    }
}
