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
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.FinishReason;
import com.bytequay.core.ci.CiAutofixRun.PauseReason;
import com.bytequay.core.ci.CiAutofixRun.Status;
import com.bytequay.core.ci.CiAutofixRun.Warning;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.UserProfile;
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
import java.util.Optional;

import static java.lang.Math.toIntExact;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

    @TempDir
    private Path temporaryDirectory;

    private MemoryStore store;
    private GitHubActionsClient gitHub;
    private AgentTaskService taskService;
    private AgentTurnService turnService;
    private AgentWorkspaceService workspaceService;
    private AgentResourceService resourceService;
    private AgentEventService eventService;
    private CiAutofixLogReader logReader;
    private CiMemoryService memoryService;
    private CredentialService credentials;
    private GitHubAccountClient accounts;
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
        workspaceService = mock(AgentWorkspaceService.class);
        resourceService = mock(AgentResourceService.class);
        eventService = mock(AgentEventService.class);
        memoryService = mock(CiMemoryService.class);
        credentials = mock(CredentialService.class);
        accounts = mock(GitHubAccountClient.class);
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
        when(accounts.fetchUserProfile("token")).thenReturn(profile("author"));
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
        when(resourceService.require(taskId)).thenReturn(resources);
        when(eventService.readAfter(any(), any(Long.class), any(Integer.class))).thenReturn(ImmutableList.of());
        logReader = new CiAutofixLogReader(gitHub, resourceService, eventService);

        service = new DefaultCiAutofixService(
                store,
                gitHub,
                taskService,
                turnService,
                workspaceService,
                logReader,
                memoryService,
                credentials,
                accounts,
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
                .contains("- Head: " + HEAD + " (feature)")
                .contains("- Base: " + BASE + " (main)")
                .contains("Build / compile failed at Compile")
                .contains("call read_ci_memory")
                .contains("Fix compile, type-check, dependency, or build failures before downstream test")
                .contains("Commit the\nrepair as \"fixup! <target subject>\"")
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

        when(workspaceService.inspect(worktree)).thenReturn(new WorkspaceSnapshot(
                worktree,
                REPOSITORY,
                Optional.of("another-branch"),
                HEAD,
                false));
        assertThatThrownBy(() -> service.start(startCommand("branch"))).hasMessageContaining("branch");

        when(workspaceService.inspect(worktree)).thenReturn(workspace(false, "c".repeat(40)));
        assertThatThrownBy(() -> service.start(startCommand("head"))).hasMessageContaining("HEAD");

        when(workspaceService.inspect(worktree)).thenReturn(new WorkspaceSnapshot(
                worktree,
                new RepositoryIdentity("another", "repo"),
                Optional.of("feature"),
                HEAD,
                false));
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
        assertThat(run.warnings()).singleElement().satisfies(warning ->
                assertThat(warning.code()).isEqualTo("CI_MEMORY_UPDATE_FAILED"));
    }

    @Test
    void reconcilesTurnStatesWithoutUsingAgentTextForControl()
    {
        CiAutofixRun run = service.start(startCommand("states"));
        when(eventService.readAfter(eq(taskId), eq(0L), eq(1000))).thenReturn(ImmutableList.of(finalMessage(
                "{\"state\":\"GREEN\",\"push\":\"--force\"}")));

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
    void cancelsIdempotentlyAndSignalsAnActiveTurn()
    {
        CiAutofixRun run = service.start(startCommand("cancel"));

        CiAutofixRun cancelled = service.cancel(run.id());
        assertThat(cancelled.status()).isEqualTo(Status.FINISHED);
        assertThat(cancelled.finishReason()).contains(FinishReason.CANCELLED);
        verify(turnService).cancel(turnId);

        assertThat(service.cancel(run.id())).isEqualTo(cancelled);
        verify(turnService, times(1)).cancel(turnId);
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
        when(taskService.get(taskId)).thenReturn(readyTask);

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
                workspaceService,
                logReader,
                memoryService,
                credentials,
                accounts,
                requestedClock);
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
        return runningTurn.finish(state, TurnMetrics.empty(), reason, NOW);
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
