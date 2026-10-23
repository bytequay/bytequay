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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.AutomationLevel;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.backport.BackportChainService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.PullRequestStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRelatedWorkspaceWork
{
    @TempDir
    Path repository;
    private final AppSettingsStore settings = mock(AppSettingsStore.class);
    private final WorkspaceRelationships relationships = mock(WorkspaceRelationships.class);
    private final WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final TaskLogStore logs = mock(TaskLogStore.class);
    private InteractiveAgentTaskCreator creator;
    private final AgentTaskService taskService = mock(AgentTaskService.class);
    private final AgentTurnService turnService = mock(AgentTurnService.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final GitCommandRunner git = new GitCommandRunner();
    private final Map<String, AgentTurn> dispatched = new ConcurrentHashMap<>();
    private final Map<TaskId, AgentTask> taskData = new ConcurrentHashMap<>();
    private AgentTask source;
    private AgentTurn sourceTurn;
    private String oldCommit;
    private String newCommit;

    @BeforeEach
    void setUp()
            throws Exception
    {
        command("init", "-q");
        command("config", "user.name", "Test");
        command("config", "user.email", "test@example.com");
        Files.writeString(repository.resolve("API.txt"), "old API");
        command("add", ".");
        command("commit", "-qm", "Old API");
        oldCommit = command("rev-parse", "HEAD");
        Files.writeString(repository.resolve("API.txt"), "new API");
        command("commit", "-qam", "New API");
        newCommit = command("rev-parse", "HEAD");
        command("update-ref", "refs/remotes/origin/main", newCommit);
        Map<String, String> saved = new ConcurrentHashMap<>();
        when(settings.get(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        doAnswer(call -> {
            saved.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(settings).put(anyString(), anyString());
        source = task(TaskId.random(), "repository", new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO));
        sourceTurn = accepted(source.id(), "human-request");
        taskData.put(source.id(), source);
        when(tasks.find(any())).thenAnswer(call -> Optional.ofNullable(taskData.get(call.getArgument(0))));
        when(turns.findActive(source.id())).thenReturn(Optional.of(sourceTurn));
        when(turns.findByIdempotencyKey(any(), anyString())).thenAnswer(call -> Optional.ofNullable(dispatched.get(call.getArgument(1))));
        Workspace destination = new Workspace("iceberg", "Iceberg", 2, RepoRef.of("apache", "iceberg"), Optional.empty(), Optional.empty(),
                2, RepoRef.of("apache", "iceberg"), Workspace.Topology.DIRECT, "main", "https://github.com/apache/iceberg.git",
                Optional.empty(), Optional.empty(), repository.toString(), Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH);
        when(relationships.requireRelated("repository", "iceberg")).thenReturn(destination);
        when(relationships.related("repository")).thenReturn(ImmutableList.of(
                new WorkspaceRelationships.Related("iceberg", WorkspaceRelationships.Position.DEPENDENCY, "Read core")));
        when(repositories.requireLocalRepository("iceberg")).thenReturn(new WorkspaceRepositoryContext("iceberg", repository, 2,
                RepoRef.of("apache", "iceberg"), OptionalLong.empty(), RepoRef.of("apache", "iceberg"), "origin", "origin", "main", ImmutableList.of()));
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of("iceberg"))).thenReturn(new AgentModelSetting(
                AgentModelType.DEVELOPMENT, true, "claude", "Claude", "destination-model", "Destination model", Optional.of("high")));
        when(appSettings.developmentAutomation()).thenReturn(new DevelopmentAutomation(true, AutomationLevel.CI_FIX, Optional.empty()));
        var driver = mock(AgentDriver.class);
        when(drivers.require(AgentLaneId.CLAUDE_CLI)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        var ready = new AgentProviderStatus(AgentLaneId.CLAUDE_CLI, AgentProviderStatus.Readiness.READY, Optional.of("test"), "Ready", AgentCapabilities.none());
        when(driver.probe()).thenReturn(ready);
        when(driver.probe(any())).thenReturn(ready);
        var provisioning = mock(WorkspaceProvisioningService.class);
        when(provisioning.synchronizeCode("iceberg")).thenReturn(new WorkspaceCodeSyncResult("iceberg", true, Optional.of(Instant.EPOCH), ImmutableSet.of()));
        var localRepository = repositories.requireLocalRepository("iceberg");
        when(repositories.requireReadyRepository("iceberg")).thenReturn(localRepository);
        var credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubCredential()).thenReturn(new CredentialService.GitHubCredential("test-token",
                new UserProfile("tester", "", "", "", 0, 0, 0, "", "", "", "")));
        when(taskService.getInteractive(anyString(), any())).thenAnswer(call -> {
            AgentTask task = taskData.get(call.getArgument(1));
            if (task == null) {
                throw new AgentTaskNotFoundException(call.getArgument(1));
            }
            return task;
        });
        when(taskService.createInteractive(any())).thenAnswer(call -> {
            CreateInteractiveAgentTaskCommand command = call.getArgument(0);
            AgentTask target = AgentTask.created(command.taskId(), true, Optional.of(command.creationFingerprint()), "Investigation", TitleSource.USER,
                    command.laneId(), new TaskWorktree(command.taskId(), command.workspaceId(), command.physicalRepository(),
                            repository.resolve(command.taskId().toString()), command.generatedBranch(), command.startCommit()), command.defaults(), Instant.EPOCH);
            taskData.put(target.id(), target);
            return target;
        });
        creator = new InteractiveAgentTaskCreator(taskService, turnService, drivers, models, repositories, provisioning, git, appSettings,
                new AgentTaskStartupProgress(), mock(PullRequestStore.class), credentials);
        when(turnService.start(any())).thenAnswer(call -> {
            StartAgentTurnCommand request = call.getArgument(0);
            AgentTurn turn = accepted(request.taskId(), request.idempotencyKey());
            dispatched.put(request.idempotencyKey(), turn);
            return turn;
        });
    }

    @Test
    void relatedWorkUsesTheDestinationClaudeAccountAndItsOwnWorktree()
    {
        AgentLaneId sourceAccount = new AgentLaneId("claude-account-source");
        AgentLaneId destinationAccount = new AgentLaneId("claude-account-destination");
        source = AgentTask.created(source.id(), Optional.of("repository"), true, source.title(), sourceAccount, source.repository(), repository, source.defaults(), Instant.EPOCH)
                .withSessionReference(new AgentSessionRef(sourceAccount, 1, "source-session"), Instant.EPOCH);
        taskData.put(source.id(), source);
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of("iceberg"))).thenReturn(new AgentModelSetting(
                AgentModelType.DEVELOPMENT, true, destinationAccount.value(), "Claude · Destination", "destination-model", "Destination model", Optional.of("high")));
        AgentDriver driver = drivers.require(AgentLaneId.CLAUDE_CLI);
        when(drivers.require(destinationAccount)).thenReturn(driver);
        var ready = new AgentProviderStatus(destinationAccount, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none());
        when(driver.probe()).thenReturn(ready);
        when(driver.probe(any())).thenReturn(ready);
        var view = service().start(source.id().toString(), sourceTurn.id(), request("different-account", oldCommit));
        AgentTask target = taskData.get(TaskId.valueOf(view.targetTaskId()));
        assertThat(target.laneId()).isEqualTo(destinationAccount);
        assertThat(target.worktree()).isNotEqualTo(source.worktree());
        assertThat(source.sessionReference().orElseThrow().laneId()).isEqualTo(sourceAccount);
        verify(drivers).require(destinationAccount);
        when(drivers.require(destinationAccount)).thenThrow(new AgentUnavailableException("Removed account"));
        assertThat(service().start(source.id().toString(), sourceTurn.id(), request("removed-account", oldCommit)).state()).isEqualTo("UNAVAILABLE");
        verify(drivers, never()).require(sourceAccount);
    }

    @Test
    void startsOldAndNewInvestigationsWithFrozenLocalOnlySettingsAndNoSourceContext()
    {
        var service = service();
        command("tag", "v1", oldCommit);
        var oldRequest = request("old", command("rev-parse", "v1^{commit}"));
        var old = service.start(source.id().toString(), sourceTurn.id(), oldRequest);
        command("tag", "-f", "v1", newCommit);
        assertThat(service().start(source.id().toString(), sourceTurn.id(), oldRequest).commit()).contains(oldCommit);
        var next = service.start(source.id().toString(), sourceTurn.id(), request("new", newCommit));
        assertThat(old.targetTaskId()).isNotEqualTo(next.targetTaskId());
        assertThat(old.commit()).contains(oldCommit);
        assertThat(next.commit()).contains(newCommit);
        assertThat(command("rev-parse", "HEAD")).isEqualTo(newCommit);
        var created = ArgumentCaptor.forClass(CreateInteractiveAgentTaskCommand.class);
        verify(taskService, times(2)).createInteractive(created.capture());
        assertThat(created.getAllValues()).extracting(CreateInteractiveAgentTaskCommand::startCommit).containsExactly(oldCommit, newCommit);
        assertThat(created.getAllValues()).allSatisfy(value -> {
            assertThat(value.workspaceId()).isEqualTo("iceberg");
            assertThat(value.laneId()).isEqualTo(AgentLaneId.CLAUDE_CLI);
            assertThat(value.defaults().model()).contains("destination-model");
            assertThat(value.defaults().developmentAutomation()).contains(new DevelopmentAutomation(true, AutomationLevel.COMMIT, Optional.empty()));
            assertThat(value.message()).contains("Check API behavior", "commands and limitations").doesNotContain("Secret source title", "repository");
        });
        ArgumentCaptor<StartAgentTurnCommand> initial = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turnService, times(2)).start(initial.capture());
        assertThat(initial.getAllValues()).allSatisfy(value -> {
            assertThat(value.developmentAutomation()).isEqualTo(created.getValue().defaults().developmentAutomation());
            assertThat(value.attachments()).isEmpty();
        });
    }

    @Test
    void replaysOneTargetAcrossConcurrentCallsAndServiceRestartButRejectsChangedRequests()
    {
        var service = service();
        var request = request("same", oldCommit);
        var first = CompletableFuture.supplyAsync(() -> service.start(source.id().toString(), sourceTurn.id(), request));
        var second = CompletableFuture.supplyAsync(() -> service.start(source.id().toString(), sourceTurn.id(), request));
        assertThat(first.join().targetTaskId()).isEqualTo(second.join().targetTaskId());
        assertThat(service().start(source.id().toString(), sourceTurn.id(), request).targetTaskId()).isEqualTo(first.join().targetTaskId());
        verify(taskService).createInteractive(any());
        verify(turnService).start(any());
        assertThatThrownBy(() -> service.start(source.id().toString(), sourceTurn.id(), request("same", newCommit)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
        when(relationships.requireRelated("repository", "iceberg")).thenThrow(new IllegalArgumentException("Not related"));
        assertThatThrownBy(() -> service.start(source.id().toString(), sourceTurn.id(), request)).hasMessageContaining("Not related");
        assertThatThrownBy(() -> service.read(source.id().toString(), sourceTurn.id(), "same")).hasMessageContaining("Not related");
    }

    @Test
    void adoptsAnAcceptedTurnAfterItsResponseWasLost()
    {
        doAnswer(call -> {
            StartAgentTurnCommand command = call.getArgument(0);
            dispatched.put(command.idempotencyKey(), accepted(command.taskId(), command.idempotencyKey()));
            throw new IllegalStateException("Lost response");
        }).when(turnService).start(any());
        var request = request("uncertain", oldCommit);
        var first = service().start(source.id().toString(), sourceTurn.id(), request);
        assertThat(first.limitation()).contains("Lost response");
        var replay = service().start(source.id().toString(), sourceTurn.id(), request);
        assertThat(replay.targetTaskId()).isEqualTo(first.targetTaskId());
        assertThat(replay.state()).isEqualTo("RUNNING");
        assertThat(replay.limitation()).isEmpty();
        verify(taskService).createInteractive(any());
        verify(turnService).start(any());
    }

    @Test
    void missingRevisionDoesNotFallBackAndStaleTurnsCannotStartWork()
    {
        var service = service();
        var view = service.start(source.id().toString(), sourceTurn.id(), request("missing", "a".repeat(40)));
        assertThat(view.state()).isEqualTo("UNAVAILABLE");
        assertThat(view.limitation()).get().asString().contains("unavailable locally");
        verify(taskService, never()).createInteractive(any());
        assertThatThrownBy(() -> service.start(source.id().toString(), TurnId.random(), request("stale", oldCommit)))
                .isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> service.read(TaskId.random().toString(), sourceTurn.id(), "missing"))
                .isInstanceOf(AgentConflictException.class);
        when(relationships.requireRelated("repository", "iceberg")).thenThrow(new IllegalArgumentException("Not related"));
        assertThatThrownBy(() -> service.start(source.id().toString(), sourceTurn.id(), request("new", oldCommit)))
                .hasMessageContaining("Not related");
    }

    @Test
    void readsOnlyTheRequestedRootTurnAnswerWithoutWaitingForTaskCompletion()
    {
        var service = service();
        var view = service.start(source.id().toString(), sourceTurn.id(), request("result", oldCommit));
        String key = "related-work:" + view.targetTaskId();
        AgentTurn requested = dispatched.get(key).markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider-turn"), Optional.empty())
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), Instant.EPOCH);
        dispatched.put(key, requested);
        var finalAnswer = event(requested, AgentEventSource.root(), MessagePhase.FINAL, "Revision " + oldCommit + ": upstream issue not reproduced; no fix needed");
        var child = new AgentEventSource(Optional.of("child"), Optional.of("root"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        when(logs.findLatest(eq(requested.taskId()), any(), eq(10_000))).thenAnswer(call -> {
            Predicate<AgentEventEnvelope> predicate = call.getArgument(1);
            assertThat(predicate.test(event(accepted(requested.taskId(), "later"), AgentEventSource.root(), MessagePhase.FINAL, "Wrong turn"))).isFalse();
            assertThat(predicate.test(event(requested, child, MessagePhase.FINAL, "Child answer"))).isFalse();
            assertThat(predicate.test(event(requested, AgentEventSource.root(), MessagePhase.PARTIAL, "Still working"))).isFalse();
            assertThat(predicate.test(finalAnswer)).isTrue();
            return Optional.of(finalAnswer);
        });
        var answer = service.read(source.id().toString(), sourceTurn.id(), "result");
        assertThat(answer.state()).isEqualTo("FINISHED");
        assertThat(answer.result()).contains(finalAnswer.payload() instanceof AgentEventPayload.AssistantMessage message ? message.text() : "");
        verify(taskService).createInteractive(any());
        verify(turnService).start(any());
        doReturn(Optional.empty()).when(logs).findLatest(eq(requested.taskId()), any(), eq(10_000));
        assertThat(service.read(source.id().toString(), sourceTurn.id(), "result").limitation()).get().asString().contains("missing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerBridgeBindsTheAcceptedTurnAndPreservesOlderCodexThreads()
    {
        var service = service();
        ObjectProvider<RelatedWorkspaceWork> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(service);
        ObjectProvider<BackportChainService> chainProvider = mock(ObjectProvider.class);
        var chainService = mock(BackportChainService.class);
        when(chainProvider.getObject()).thenReturn(chainService);
        var resolver = new RelatedWorkspaceBridgeResolverImpl(provider, turns, new ObjectMapper().findAndRegisterModules(), chainProvider);
        var bridge = resolver.resolve(source.id().toString()).orElseThrow();
        assertThat(bridge.call().apply("""
                {"action":"START","operationKey":"bridge","workspaceId":"iceberg","purpose":"INVESTIGATE",
                 "question":"Check API behavior","commit":"%s","revision":"v1"}
                """.formatted(oldCommit))).contains("RUNNING", oldCommit);
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"READ\",\"operationKey\":\"bridge\",\"workspaceId\":\"other\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        bridge.call().apply("{\"action\":\"SAVE_CHAIN\",\"operationKey\":\"backport\",\"destinations\":[\"test/internal-repository\"]}");
        verify(chainService).save(source.id().toString(), sourceTurn.id(), new BackportChainService.Request(
                "backport", Optional.of(source.id().toString()), Optional.empty(), ImmutableList.of("test/internal-repository"), Optional.empty(), Optional.empty()));
        bridge.call().apply("""
                {"action":"DISCOVER_BACKPORT","operationKey":"route-settings"}
                """);
        verify(chainService).discover(source.id().toString(), sourceTurn.id());
        bridge.call().apply("""
                {"action":"SAVE_CHAIN","operationKey":"existing-pr","sourcePullRequest":{"workspaceId":"oss","number":1234},
                 "destinations":["internal","private"],"sourceHops":[-1,-1]}
                """);
        verify(chainService).save(source.id().toString(), sourceTurn.id(), new BackportChainService.Request(
                "existing-pr", Optional.empty(), Optional.empty(), ImmutableList.of("internal", "private"),
                Optional.of(new BackportChainService.SourcePullRequest("oss", 1234)), Optional.of(ImmutableList.of(-1, -1))));
        for (String malformed : ImmutableList.of(
                "{\"action\":\"READ\",\"operationKey\":123}",
                "{\"action\":\"READ\",\"operationKey\":\"a\",\"action\":\"READ_CHAIN\",\"chainId\":\"c\"}",
                "{\"action\":\"READ\",\"operationKey\":\"a\"} {}",
                "{\"action\":\"READ_CHAIN\",\"operationKey\":\"a\",\"chainId\":1}",
                "{\"action\":\"SAVE_CHAIN\",\"operationKey\":\"a\",\"destinations\":[1]}",
                "{\"action\":\"SAVE_CHAIN\",\"operationKey\":\"a\",\"destinations\":[\"internal\"],\"sourceHops\":[0.5]}",
                "{\"action\":\"SAVE_CHAIN\",\"operationKey\":\"a\",\"destinations\":[\"internal\"],\"workspaceId\":\"other\"}",
                "{\"action\":\"DISCOVER_BACKPORT\",\"operationKey\":\"a\",\"destinations\":[]}",
                "{\"action\":\"UNKNOWN\",\"operationKey\":\"a\"}", "null")) {
            assertThatThrownBy(() -> bridge.call().apply(malformed)).isInstanceOf(IllegalArgumentException.class);
        }
        when(turns.findActive(source.id())).thenReturn(Optional.of(accepted(source.id(), "later-user-turn")));
        assertThatThrownBy(() -> bridge.call().apply("{\"action\":\"READ\",\"operationKey\":\"bridge\"}"))
                .isInstanceOf(AgentConflictException.class);
        taskData.put(source.id(), source.withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "old-thread"), Instant.EPOCH));
        assertThat(resolver.resolve(source.id().toString())).isEmpty();
        taskData.put(source.id(), source.withSessionReference(new AgentSessionRef(AgentLaneId.CODEX_CLI, 2, "new-thread"), Instant.EPOCH));
        assertThat(resolver.resolve(source.id().toString())).isPresent();
    }

    @Test
    void deletedDestinationIsUnavailableInsteadOfPermanentlyStarting()
    {
        var service = service();
        var view = service.start(source.id().toString(), sourceTurn.id(), request("deleted", oldCommit));
        taskData.remove(TaskId.valueOf(view.targetTaskId()));
        dispatched.clear();
        when(turns.findActive(source.id())).thenReturn(Optional.empty());
        assertThat(service.list("repository")).singleElement().satisfies(work -> {
            assertThat(work.state()).isEqualTo("UNAVAILABLE");
            assertThat(work.targetTaskId()).isNull();
            assertThat(work.limitation()).get().asString().contains("unavailable");
        });
    }

    @Test
    void generatedRelatedRequestCannotStartAnotherHandoff()
    {
        var generated = accepted(source.id(), "related-work:" + source.id());
        when(turns.findActive(source.id())).thenReturn(Optional.of(generated));
        assertThatThrownBy(() -> service().start(source.id().toString(), generated.id(), request("recursive", oldCommit)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("automated turn");
        verify(turnService, never()).start(any());
    }

    @Test
    void separatelyRequestedFixUsesDestinationDefaultsWithoutACommitOverride()
    {
        var request = new RelatedWorkspaceWork.Request("fix", "iceberg", RelatedWorkspaceWork.Purpose.FIX, "Fix this upstream issue", Optional.empty(), Optional.empty());
        var view = service().start(source.id().toString(), sourceTurn.id(), request);
        assertThat(view.state()).isEqualTo("RUNNING");
        var created = ArgumentCaptor.forClass(CreateInteractiveAgentTaskCommand.class);
        verify(taskService).createInteractive(created.capture());
        assertThat(created.getValue().startCommit()).isEqualTo(newCommit);
        assertThat(created.getValue().defaults().developmentAutomation()).contains(appSettings.developmentAutomation());
    }

    private RelatedWorkspaceWork service()
    {
        return new RelatedWorkspaceWork(settings, new ObjectMapper().findAndRegisterModules(), relationships, repositories, tasks, turns,
                mock(AgentApprovalStore.class), logs, mock(AgentReviewStore.class), creator, turnService, taskService, models, appSettings, git, mock(InteractiveTaskNaming.class));
    }

    private RelatedWorkspaceWork.Request request(String key, String commit)
    {
        return new RelatedWorkspaceWork.Request(key, "iceberg", RelatedWorkspaceWork.Purpose.INVESTIGATE, "Check API behavior", Optional.of(commit), Optional.of("v1"));
    }

    private AgentTask task(TaskId id, String workspaceId, AgentTaskDefaults defaults)
    {
        return AgentTask.created(id, Optional.of(workspaceId), true, "Secret source title", AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("owner", workspaceId), repository, defaults, Instant.EPOCH);
    }

    private static AgentTurn accepted(TaskId taskId, String key)
    {
        return AgentTurn.accepted(TurnId.random(), taskId, key, new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO,
                Duration.ofMinutes(5), ImmutableList.of(), Optional.empty(), Optional.empty()), Instant.EPOCH);
    }

    private static AgentEventEnvelope event(AgentTurn turn, AgentEventSource source, MessagePhase phase, String text)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, turn.taskId(), Optional.of(turn.id()), source,
                Instant.EPOCH, new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, phase, text));
    }

    private String command(String... args)
    {
        var result = git.run(repository, args);
        assertThat(result.successful()).as(result.output()).isTrue();
        return result.output().strip();
    }
}
