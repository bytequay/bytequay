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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorkspaceLease;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static com.bytequay.agent.spi.ProviderAgentEventSource.root;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDefaultAgentTurnService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    private Path temporaryDirectory;

    private InMemoryAgentStores stores;
    private FileAgentResourceService resources;
    private DefaultAgentEventService events;
    private DefaultAgentMetricsService metrics;
    private RecordingApprovalService approvals;
    private DeterministicAgentDriver driver;
    private AgentTask task;
    private DefaultAgentTaskService taskService;
    private DefaultAgentTurnService service;

    @BeforeEach
    void setUp()
    {
        stores = new InMemoryAgentStores();
        resources = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));
        driver = new DeterministicAgentDriver();
        Path worktree = temporaryDirectory.resolve("worktree").toAbsolutePath();
        taskService = new DefaultAgentTaskService(
                stores,
                new FixedWorkspaceService(worktree),
                new UnusedWorktreeService(),
                resources,
                new StaticAgentDriverRegistry(ImmutableList.of(driver)),
                CLOCK);
        task = taskService.create(new CreateAgentTaskCommand(
                DeterministicAgentDriver.LANE_ID,
                worktree,
                new RepositoryIdentity("bytequay", "bytequay"),
                "Lifecycle test",
                new AgentTaskDefaults(
                        Optional.of("model-a"),
                        Optional.of("low"),
                        AgentPermissionPolicy.AUTO),
                Optional.empty(),
                false));
        events = new DefaultAgentEventService(
                new FileTaskLogStore(resources),
                stores,
                stores,
                CLOCK);
        metrics = new DefaultAgentMetricsService(stores, stores, CLOCK);
        approvals = new RecordingApprovalService();
        service = service(driver, new AgentRuntimeRegistry());
    }

    private static final class UnusedWorktreeService
            implements WorktreeService
    {
        @Override
        public TaskWorktree provision(ProvisionWorktreeCommand command)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorktreeSnapshot inspect(TaskWorktree worktree)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cleanup(TaskWorktree worktree)
        {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void runsMultipleTurnsInOneSessionAndRoutesSynchronousUsage()
    {
        driver.emitUsageOnOpen(usage("usage-1", 4, 1, 2));
        AgentTurn first = service.start(command("first", "key-1"));
        assertThat(first.state()).isEqualTo(TurnState.RUNNING);
        driver.complete("first response");
        assertThat(service.get(first.id()).state()).isEqualTo(TurnState.COMPLETED);

        AgentTurn repeated = service.start(command("ignored", "key-1"));
        assertThat(repeated.id()).isEqualTo(first.id());

        AgentTurn second = service.start(command("second", "key-2"));
        driver.complete("second response");

        assertThat(service.get(second.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(driver.openedReferences()).hasSize(1);
        assertThat(driver.commands())
                .extracting(AgentTurnCommand::message)
                .containsExactly("first", "second");
        assertThat(stores.find(task.id()).orElseThrow().metrics().inputTokens()).isEqualTo(4);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("first", "second");
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.ASSISTANT_MESSAGE)
                .extracting(event -> ((AgentEventPayload.AssistantMessage) event.payload()).text())
                .containsExactly("first response", "second response");
    }

    @Test
    void persistsUserMessageBeforeStartingProvider()
    {
        driver.beforeStart(() -> assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .singleElement()
                .satisfies(event -> assertThat(((AgentEventPayload.UserMessage) event.payload()).text())
                        .isEqualTo("first")));

        AgentTurn turn = service.start(command("first", "key-1"));

        driver.complete("done");
        assertThat(service.get(turn.id()).state()).isEqualTo(TurnState.COMPLETED);
    }

    @Test
    void rejectsUnavailableProviderBeforePersistingTurnOrUserMessage()
    {
        driver.unavailable();

        assertThatThrownBy(() -> service.start(command("first", "key-1")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("unavailable");

        assertThat(stores.list(task.id())).isEmpty();
        assertThat(events.readAfter(task.id(), 0, 100)).isEmpty();
        assertThat(stores.find(task.id()).orElseThrow().state()).isEqualTo(TaskState.READY);
        assertThat(driver.openedReferences()).isEmpty();
    }

    @Test
    void returnsIdempotentTurnWithoutRequiringProviderAgain()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        driver.complete("done");
        driver.unavailable();

        assertThat(service.start(command("ignored", "key-1"))).isEqualTo(service.get(first.id()));
    }

    @Test
    void rejectsConcurrentTurnAndCancelsTheLiveProviderRun()
    {
        AgentTurn running = service.start(command("first", "key-1"));

        assertThatThrownBy(() -> service.start(command("second", "key-2")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("active turn");

        AgentTurn cancelled = service.cancel(running.id());
        assertThat(cancelled.state()).isEqualTo(TurnState.CANCELLED);
        assertThat(cancelled.cancellationRequestedAt()).contains(NOW);
        assertThat(approvals.deniedTurns).containsExactly(running.id());
    }

    @Test
    void releasesTaskLockBeforeCallingProviderCancellation()
    {
        AgentTurn running = service.start(command("first", "key-1"));
        driver.beforeCancel(() -> CompletableFuture.runAsync(() -> assertThatThrownBy(() -> driver.requestApproval(
                new ProviderApprovalRequest(
                        "provider-request",
                        ProviderApprovalRequest.Kind.COMMAND,
                        "Run command",
                        "git status",
                        ImmutableList.of("command:git status"),
                        ImmutableList.of(DENY),
                        root(),
                        NOW),
                Duration.ofSeconds(10)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("cancelled"))
                .orTimeout(5, TimeUnit.SECONDS)
                .join());

        assertThat(service.cancel(running.id()).state()).isEqualTo(TurnState.CANCELLED);
    }

    @Test
    void routesProviderApprovalForTheCurrentTurn()
    {
        AgentTurn running = service.start(command("approval", "key-1"));
        ProviderApprovalRequest request = new ProviderApprovalRequest(
                "provider-request",
                ProviderApprovalRequest.Kind.COMMAND,
                "Run command",
                "Run tests",
                ImmutableList.of("command:run"),
                ImmutableList.of(
                        ALLOW_ONCE,
                        DENY),
                root(),
                NOW);

        ProviderApprovalDecision decision = driver.requestApproval(request, Duration.ofSeconds(10))
                .toCompletableFuture()
                .join();

        assertThat(decision.decision()).isEqualTo(ProviderApprovalDecision.Decision.ALLOW);
        assertThat(approvals.requests).singleElement().satisfies(approval -> {
            assertThat(approval.taskId()).isEqualTo(task.id());
            assertThat(approval.turnId()).isEqualTo(running.id());
            assertThat(approval.providerRequest()).isEqualTo(request);
        });
        driver.complete("approved");
    }

    @Test
    void rejectsProviderApprovalAfterTurnFinishes()
    {
        AgentTurn running = service.start(command("approval", "key-1"));
        driver.complete("done");

        assertThat(service.get(running.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThatThrownBy(() -> driver.requestApproval(new ProviderApprovalRequest(
                "late-request",
                ProviderApprovalRequest.Kind.COMMAND,
                "Run command",
                "Run tests",
                ImmutableList.of("command:run"),
                ImmutableList.of(DENY),
                root(),
                NOW), Duration.ofSeconds(10)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("no active turn");
        assertThat(approvals.requests).isEmpty();
    }

    @Test
    void resumesPersistedSessionAfterRuntimeRegistryIsLost()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        driver.complete("first response");
        assertThat(service.get(first.id()).state()).isEqualTo(TurnState.COMPLETED);
        AgentSessionRef reference = stores.find(task.id()).orElseThrow().sessionReference().orElseThrow();

        DeterministicAgentDriver resumedDriver = new DeterministicAgentDriver();
        DefaultAgentTurnService resumedService = service(resumedDriver, new AgentRuntimeRegistry());
        AgentTurn second = resumedService.start(command("second", "key-2"));

        assertThat(resumedDriver.openedReferences()).containsExactly(reference);
        resumedDriver.complete("second response");
        assertThat(resumedService.get(second.id()).state()).isEqualTo(TurnState.COMPLETED);
    }

    @Test
    void recordsFailedStartAsTerminalTurn()
    {
        driver.failNextStart();

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start failed");
        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> {
            assertThat(turn.state()).isEqualTo(TurnState.FAILED);
            assertThat(turn.terminalReason()).contains("deterministic start failed");
        });
        assertThat(stores.findActive(task.id())).isEmpty();
    }

    @Test
    void preservesEachTurnsEffectiveSettingsAndOpaqueAttachmentReferences()
    {
        Path attachmentPath = temporaryDirectory.resolve("image.png").toAbsolutePath();
        AgentTurn first = service.start(new StartAgentTurnCommand(
                task.id(),
                "first",
                ImmutableList.of(new AgentAttachment("image.png", "image/png", attachmentPath)),
                "key-1"));
        AgentTurnSettings firstSettings = first.settings().orElseThrow();
        assertThat(firstSettings.model()).contains("model-a");
        assertThat(firstSettings.effort()).contains("low");
        assertThat(firstSettings.permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        assertThat(firstSettings.sandboxPolicy()).isEqualTo(AgentSandboxPolicy.WORKSPACE_WRITE);
        assertThat(firstSettings.maximumRuntime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(firstSettings.attachments()).singleElement().satisfies(reference -> {
            assertThat(reference.id()).isNotBlank();
            assertThat(reference.name()).isEqualTo("image.png");
            assertThat(reference.mediaType()).isEqualTo("image/png");
            assertThat(reference.id()).doesNotContain(attachmentPath.toString());
        });
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .singleElement()
                .satisfies(event -> assertThat(((AgentEventPayload.UserMessage) event.payload()).attachments())
                        .isEqualTo(firstSettings.attachments()));
        driver.complete("first response");

        AgentTask current = taskService.get(task.id());
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                task.id(),
                current.version(),
                new AgentTaskDefaults(
                        Optional.of("model-b"),
                        Optional.of("high"),
                        AgentPermissionPolicy.AUTO)));
        AgentTurn second = service.start(command("second", "key-2"));
        driver.complete("second response");

        assertThat(stores.find(first.id()).orElseThrow().settings()).contains(firstSettings);
        AgentTurnSettings secondSettings = stores.find(second.id()).orElseThrow().settings().orElseThrow();
        assertThat(secondSettings.model()).contains("model-b");
        assertThat(secondSettings.effort()).contains("high");
        assertThat(secondSettings.permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        assertThat(secondSettings.sandboxPolicy()).isEqualTo(AgentSandboxPolicy.WORKSPACE_WRITE);
        assertThat(secondSettings.maximumRuntime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(secondSettings.attachments()).isEmpty();
        assertThat(driver.commands().getFirst().attachments())
                .extracting(AgentAttachment::path)
                .containsExactly(attachmentPath);
    }

    private DefaultAgentTurnService service(
            DeterministicAgentDriver configuredDriver,
            AgentRuntimeRegistry runtimeRegistry)
    {
        return new DefaultAgentTurnService(
                stores,
                stores,
                resources,
                new StaticAgentDriverRegistry(ImmutableList.of(configuredDriver)),
                events,
                metrics,
                approvals,
                runtimeRegistry,
                CLOCK,
                Duration.ofMinutes(5));
    }

    private StartAgentTurnCommand command(String message, String idempotencyKey)
    {
        return new StartAgentTurnCommand(
                task.id(),
                message,
                ImmutableList.<AgentAttachment>of(),
                idempotencyKey);
    }

    private static ProviderUsageSample usage(String eventId, long input, long cached, long output)
    {
        return new ProviderUsageSample(
                Optional.of(eventId),
                ProviderUsageSample.Semantics.DELTA,
                input,
                cached,
                output,
                Optional.<BigDecimal>empty(),
                CostProvenance.UNAVAILABLE,
                NOW);
    }

    private static final class RecordingApprovalService
            implements AgentApprovalService
    {
        private final List<ApprovalRequest> requests = new ArrayList<>();
        private final List<TurnId> deniedTurns = new ArrayList<>();

        @Override
        public CompletionStage<ApprovalDecision> request(ApprovalRequest request, Duration timeout)
        {
            requests.add(request);
            return CompletableFuture.completedFuture(new ApprovalDecision(
                    request.id(),
                    ApprovalDecision.Decision.ALLOW,
                    Optional.of("command:run"),
                    NOW,
                    false));
        }

        @Override
        public ApprovalDecision resolve(ResolveApprovalCommand command)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void denyPending(TurnId turnId)
        {
            deniedTurns.add(turnId);
        }
    }

    private static final class FixedWorkspaceService
            implements AgentWorkspaceService
    {
        private final Path worktree;

        private FixedWorkspaceService(Path worktree)
        {
            this.worktree = worktree;
        }

        @Override
        public WorkspaceLease acquire(TaskId taskId, Path requestedPath, RepositoryIdentity repository)
        {
            return new WorkspaceLease(taskId, worktree, () -> {});
        }

        @Override
        public WorkspaceLease restore(TaskId taskId, Path canonicalWorktree, RepositoryIdentity repository)
        {
            return new WorkspaceLease(taskId, worktree, () -> {});
        }

        @Override
        public WorkspaceSnapshot inspect(Path ignored)
        {
            return new WorkspaceSnapshot(
                    worktree,
                    new RepositoryIdentity("bytequay", "bytequay"),
                    Optional.of("main"),
                    "0123456789012345678901234567890123456789",
                    false);
        }

        @Override
        public void release(TaskId taskId) {}
    }
}
