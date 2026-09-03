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
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RenameAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorktreeMutationClaim;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
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
    private FileTaskLogStore logs;
    private DefaultAgentEventService events;
    private DefaultAgentMetricsService metrics;
    private RecordingApprovalService approvals;
    private ScheduledExecutorService scheduler;
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
                new FixedWorktreeService(worktree),
                resources,
                new StaticAgentDriverRegistry(ImmutableList.of(driver)),
                CLOCK);
        task = taskService.create(new CreateAgentTaskCommand(
                TaskId.random(),
                "workspace-a",
                DeterministicAgentDriver.LANE_ID,
                new RepositoryIdentity("bytequay", "bytequay"),
                "0123456789012345678901234567890123456789",
                "Lifecycle test",
                new AgentTaskDefaults(
                        Optional.of("model-a"),
                        Optional.of("low"),
                        AgentPermissionPolicy.AUTO)));
        logs = new FileTaskLogStore(resources);
        events = new DefaultAgentEventService(logs, stores, stores, CLOCK);
        metrics = new DefaultAgentMetricsService(stores, stores, CLOCK);
        approvals = new RecordingApprovalService();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        service = service(driver, new AgentRuntimeRegistry());
    }

    @AfterEach
    void tearDown()
    {
        scheduler.shutdownNow();
    }

    private static final class FixedWorktreeService
            implements WorktreeService
    {
        private final Path worktree;

        private FixedWorktreeService(Path worktree)
        {
            this.worktree = worktree;
        }

        @Override
        public TaskWorktree provision(ProvisionWorktreeCommand command)
        {
            return new TaskWorktree(
                    command.taskId(),
                    command.workspaceId(),
                    command.physicalRepository(),
                    worktree.resolve(command.taskId().toString()),
                    "bytequay/task/" + command.taskId(),
                    command.startCommit());
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
    void describesTheSettingsAStartedTurnActuallyRecords()
    {
        AgentTurnSettings described = service.effectiveSettings(task.id());
        AgentTurn started = service.start(command("first", "key-1"));

        // The surface that shows the user what is about to run must not be able to disagree with
        // what starting it snapshots, so both go through one derivation.
        AgentTurnSettings recorded = started.settings().orElseThrow();
        assertThat(described.model()).isEqualTo(recorded.model());
        assertThat(described.effort()).isEqualTo(recorded.effort());
        assertThat(described.permissionPolicy()).isEqualTo(recorded.permissionPolicy());
        assertThat(described.sandboxPolicy()).isEqualTo(recorded.sandboxPolicy());
        assertThat(described.maximumRuntime()).isEqualTo(recorded.maximumRuntime());
        // Attachments belong to a message, so a described turn carries none.
        assertThat(described.attachments()).isEmpty();
    }

    @Test
    void refusesToDescribeSettingsForATaskThatDoesNotExist()
    {
        assertThatThrownBy(() -> service.effectiveSettings(TaskId.random()))
                .isInstanceOf(AgentTaskNotFoundException.class);
    }

    @Test
    void runsMultipleTurnsInOneSessionAndRoutesSynchronousUsage()
    {
        driver.emitUsageOnOpen(usage("usage-1", 4, 1, 2));
        AgentTurn first = service.start(command("first", "key-1"));
        assertThat(first.state()).isEqualTo(TurnState.RUNNING);
        driver.emitUsage(usage("usage-1", 4, 1, 2));
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USAGE)
                .hasSize(1);
        assertThat(stores.find(task.id()).orElseThrow().metrics().inputTokens()).isEqualTo(4);
        driver.complete("first response");
        assertThat(service.get(first.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.outboxEntries())
                .filteredOn(entry -> entry.turnId().equals(Optional.of(first.id())))
                .filteredOn(entry -> entry.payload() instanceof AgentEventOutboxPayload.Inline inline
                        && inline.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.TURN_COMPLETED)
                .hasSize(1);

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
    void namesTheTaskFromItsFirstAnswerOnce()
    {
        AgentTask interactive = interactiveTask("Investigate the flaky cherry-pick preview test and fix the root cause.");
        assertThat(interactive.title()).isEqualTo("Investigate the flaky cherry-pick preview test and fix the root cause");
        assertThat(interactive.titleSource()).isEqualTo(TitleSource.PROVISIONAL);

        service.start(new StartAgentTurnCommand(interactive.id(), "first", ImmutableList.of(), "key-1"));
        driver.complete("The preview reads every commit body. Trimming it to the selection fixes the flake.");

        AgentTask named = stores.find(interactive.id()).orElseThrow();
        assertThat(named.title()).isEqualTo("The preview reads every commit body");
        assertThat(named.titleSource()).isEqualTo(TitleSource.AGENT);
        assertThat(named.worktreeMutationClaim()).isEmpty();

        service.start(new StartAgentTurnCommand(interactive.id(), "second", ImmutableList.of(), "key-2"));
        driver.complete("Done. Nothing else to rename.");

        assertThat(stores.find(interactive.id()).orElseThrow().title()).isEqualTo("The preview reads every commit body");
    }

    @Test
    void keepsAUserChosenTitleOverALaterProposal()
    {
        AgentTask interactive = interactiveTask("Fix the flaky preview");
        service.start(new StartAgentTurnCommand(interactive.id(), "first", ImmutableList.of(), "key-1"));
        long version = stores.find(interactive.id()).orElseThrow().version();
        taskService.rename(new RenameAgentTaskCommand(interactive.id(), version, "Preview flake"));

        driver.complete("The preview reads every commit body.");

        AgentTask after = stores.find(interactive.id()).orElseThrow();
        assertThat(after.title()).isEqualTo("Preview flake");
        assertThat(after.titleSource()).isEqualTo(TitleSource.USER);
    }

    private AgentTask interactiveTask(String firstMessage)
    {
        return taskService.createInteractive(new CreateInteractiveAgentTaskCommand(
                TaskId.random(),
                "workspace-a",
                DeterministicAgentDriver.LANE_ID,
                new RepositoryIdentity("bytequay", "bytequay"),
                "0123456789012345678901234567890123456789",
                firstMessage,
                new AgentTaskDefaults(
                        Optional.of("model-a"),
                        Optional.of("low"),
                        AgentPermissionPolicy.AUTO)));
    }

    @Test
    void handlesProviderCompletionThatPrecedesStartReturn()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        service = service(driver, runtimeRegistry);
        approvals.beforeDeny(() -> awaitTaskLock(runtimeRegistry));
        driver.completeNextStart();

        AgentTurn completed = service.start(command("first", "key-1"));

        assertThat(completed.state()).isEqualTo(TurnState.COMPLETED);
        assertThat(completed.terminalReason()).isEmpty();
        assertThat(approvals.deniedTurns).containsExactly(completed.id());
        assertThat(runtimeRegistry.running(completed.id())).isEmpty();
    }

    @Test
    void commitsUsageMetricsAndReplayEventTogetherAndDeduplicatesAfterRetry()
    {
        AgentTurn running = service.start(command("first", "key-1"));
        ProviderUsageSample sample = usage("usage-1", 4, 1, 2);
        AgentTask taskBefore = stores.find(task.id()).orElseThrow();
        AgentTurn turnBefore = stores.find(running.id()).orElseThrow();
        stores.failOutboxInsert(AgentEventPayload.Kind.USAGE, new AgentStorageException("blocked usage outbox"));

        assertThatThrownBy(() -> driver.emitUsage(sample))
                .isInstanceOf(AgentStorageException.class)
                .hasMessage("blocked usage outbox");

        assertThat(stores.find(task.id())).contains(taskBefore);
        assertThat(stores.find(running.id())).contains(turnBefore);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USAGE)
                .isEmpty();

        driver.emitUsage(sample);
        driver.emitUsage(sample);

        assertThat(stores.find(task.id()).orElseThrow().metrics().inputTokens()).isEqualTo(4);
        assertThat(stores.find(running.id()).orElseThrow().metrics().inputTokens()).isEqualTo(4);
        assertThat(stores.outboxEntries())
                .filteredOn(entry -> entry.eventId().equals(EventId.providerUsage(task.id(), running.id(), "usage-1")))
                .hasSize(1);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USAGE)
                .hasSize(1);
        driver.complete("done");
    }

    @Test
    void keepsTheWorktreeClaimWhenAProviderCannotBeShownStopped()
    {
        AgentTurn running = service.start(command("first", "key-1"));

        driver.completeUnverified("done");

        // A turn that cannot prove nothing it started is still running keeps the claim, so no later
        // turn edits a worktree something may still be writing to.
        AgentTask task = stores.find(this.task.id()).orElseThrow();
        assertThat(task.worktreeMutationClaim()).isPresent();
        assertThat(stores.find(running.id()).orElseThrow().state()).isEqualTo(TurnState.FAILED);
        assertThatThrownBy(() -> service.start(command("second", "key-2")))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void releasesTheWorktreeClaimWhenAProviderIsShownStopped()
    {
        service.start(command("first", "key-1"));

        driver.complete("done");

        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(service.start(command("second", "key-2"))).isNotNull();
        driver.complete("done again");
    }

    @Test
    void stopsTheTasksProcessAndInterruptsItsTurn()
    {
        AgentTurn running = service.start(command("first", "key-1"));
        AgentRuntimeStopImpact impact = service.stopImpact(task.id());
        assertThat(impact.activeTurnId()).contains(running.id());
        assertThat(impact.liveProcess()).isTrue();

        AgentTask stopped = service.stopRuntime(task.id(), impact.generation());

        // The process is gone and verified so, which is what releases the worktree.
        assertThat(stores.find(running.id()).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stopped.worktreeMutationClaim()).isEmpty();
        assertThat(service.stopImpact(task.id()).liveProcess()).isFalse();
        // A later turn opens a fresh process rather than the one that was stopped.
        assertThat(service.start(command("second", "key-2"))).isNotNull();
        assertThat(driver.openedReferences()).hasSize(2);
        driver.complete("done");
    }

    @Test
    void refusesToStopARuntimeTheUserDidNotConfirm()
    {
        AgentRuntimeStopImpact confirmedEarlier = service.stopImpact(task.id());
        service.start(command("first", "key-1"));

        // The confirmation predates the turn, so it must not be able to stop it.
        assertThatThrownBy(() -> service.stopRuntime(task.id(), confirmedEarlier.generation()))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("changed");
        driver.complete("done");
    }

    @Test
    void keepsTheClaimWhenTheStoppedProcessTreeCannotBeVerified()
    {
        service.start(command("first", "key-1"));
        driver.failNextCloseVerification();
        String generation = service.stopImpact(task.id()).generation();

        assertThatThrownBy(() -> service.stopRuntime(task.id(), generation))
                .isInstanceOf(AgentUnavailableException.class);

        // The turn is over, but something it started may still be writing, so nothing else may.
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isPresent();
        assertThatThrownBy(() -> service.start(command("second", "key-2")))
                .isInstanceOf(AgentConflictException.class);

        // A later close that does verify the tree is what releases the task again.
        AgentTask released = service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());
        assertThat(released.worktreeMutationClaim()).isEmpty();
        assertThat(service.start(command("second", "key-2"))).isNotNull();
        driver.complete("done");
    }

    @Test
    void stopReleasesAClaimLeftByATurnWhoseProviderCouldNotBeShownQuiet()
    {
        service.start(command("first", "key-1"));
        driver.completeUnverified("done");
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isPresent();

        AgentTask stopped = service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());

        // The only thing that could still have been writing was the process, and it is now gone.
        assertThat(stopped.worktreeMutationClaim()).isEmpty();
        assertThat(service.start(command("second", "key-2"))).isNotNull();
        driver.complete("done again");
    }

    @Test
    void stopSettlesATurnThatFailedBeforeReachingTheProvider()
    {
        driver.failNextStart();
        assertThatThrownBy(() -> service.start(command("first", "key-1"))).isInstanceOf(IllegalStateException.class);
        AgentTurn stuck = stores.list(task.id()).getFirst();
        assertThat(stuck.state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(stuck.startedAt()).isPresent();
        // No provider run exists, so ordinary cancellation has nothing to signal.
        assertThatThrownBy(() -> service.cancel(stuck.id())).isInstanceOf(AgentConflictException.class);

        AgentTask stopped = service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());

        // The closed process was all there was, so the verified stop settles the turn and the task.
        assertThat(stores.find(stuck.id()).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stopped.worktreeMutationClaim()).isEmpty();
        assertThat(service.start(command("second", "key-2"))).isNotNull();
        driver.complete("done");
    }

    @Test
    void persistsTheThreadReferenceOnlyOnceATurnHasRunOnIt()
    {
        driver.failNextStart();
        assertThatThrownBy(() -> service.start(command("first", "key-1"))).isInstanceOf(IllegalStateException.class);

        // A thread that never ran a turn has nothing to resume after a restart; remembering it
        // would make every later turn fail at resume.
        assertThat(stores.find(task.id()).orElseThrow().sessionReference()).isEmpty();
        service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());

        service.start(command("second", "key-2"));

        assertThat(stores.find(task.id()).orElseThrow().sessionReference()).isPresent();
        driver.complete("done");
    }

    @Test
    void recordsUsageWithoutProviderIdentity()
    {
        AgentTurn running = service.start(command("first", "key-1"));
        ProviderUsageSample sample = new ProviderUsageSample(
                Optional.empty(),
                ProviderUsageSample.Semantics.DELTA,
                4,
                1,
                2,
                Optional.empty(),
                CostProvenance.UNAVAILABLE,
                NOW);

        driver.emitUsage(sample);
        driver.emitUsage(sample);

        assertThat(stores.find(running.id()).orElseThrow().metrics().inputTokens()).isEqualTo(8);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USAGE)
                .hasSize(2);
        driver.complete("done");
    }

    @Test
    void retainsCompletedProviderHandleUntilTerminalTransactionCanRetry()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        service = service(driver, runtimeRegistry);
        AgentTurn running = service.start(command("first", "key-1"));
        AgentTask taskBefore = stores.find(task.id()).orElseThrow();
        AgentTurn turnBefore = stores.find(running.id()).orElseThrow();
        stores.failTurnUpdate(TurnState.COMPLETED, new AgentStorageException("blocked terminal transaction"));

        driver.complete("done");

        assertThat(stores.find(task.id())).contains(taskBefore);
        assertThat(stores.find(running.id())).contains(turnBefore);
        assertThat(runtimeRegistry.running(running.id())).isPresent();
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isPresent();

        assertThat(service.get(running.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(runtimeRegistry.running(running.id())).isEmpty();
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.outboxEntries())
                .filteredOn(entry -> entry.turnId().equals(Optional.of(running.id())))
                .filteredOn(entry -> entry.payload() instanceof AgentEventOutboxPayload.Inline inline
                        && inline.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.TURN_COMPLETED)
                .hasSize(1);
    }

    @Test
    void rollsBackTerminalTransactionWhenLifecycleOutboxInsertFails()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        service = service(driver, runtimeRegistry);
        AgentTurn running = service.start(command("first", "key-1"));
        AgentTask taskBefore = stores.find(task.id()).orElseThrow();
        AgentTurn turnBefore = stores.find(running.id()).orElseThrow();
        stores.failOutboxInsert(
                AgentEventPayload.Kind.LIFECYCLE,
                new AgentStorageException("blocked terminal outbox"));

        driver.complete("done");

        assertThat(stores.find(task.id())).contains(taskBefore);
        assertThat(stores.find(running.id())).contains(turnBefore);
        assertThat(runtimeRegistry.running(running.id())).isPresent();
        assertThat(stores.outboxEntries())
                .filteredOn(entry -> entry.turnId().equals(Optional.of(running.id())))
                .filteredOn(entry -> entry.payload() instanceof AgentEventOutboxPayload.Inline inline
                        && inline.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.TURN_COMPLETED)
                .isEmpty();

        assertThat(service.get(running.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(runtimeRegistry.running(running.id())).isEmpty();
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.outboxEntries())
                .filteredOn(entry -> entry.turnId().equals(Optional.of(running.id())))
                .filteredOn(entry -> entry.payload() instanceof AgentEventOutboxPayload.Inline inline
                        && inline.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.TURN_COMPLETED)
                .hasSize(1);
    }

    @Test
    void retainsCompletedProviderHandleUntilTerminalEventCanDrain()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        FailOnceDrainEventService configuredEvents = new FailOnceDrainEventService(events, false);
        service = service(driver, runtimeRegistry, configuredEvents);
        AgentTurn running = service.start(command("first", "key-1"));
        configuredEvents.failNextDrain();

        driver.complete("done");

        assertThat(stores.find(running.id()).orElseThrow().state()).isEqualTo(TurnState.COMPLETED);
        assertThat(runtimeRegistry.running(running.id())).isPresent();
        assertThat(stores.listPending(task.id(), 100)).hasSize(1);

        assertThat(service.get(running.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(runtimeRegistry.running(running.id())).isEmpty();
        assertThat(stores.listPending(task.id(), 100)).isEmpty();
    }

    @Test
    void retriesTerminalApprovalCleanupBeforeStartingAnotherTurn()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        service = service(driver, runtimeRegistry);
        AgentTurn first = service.start(command("first", "key-1"));
        approvals.failNextDeny(new AgentStorageException("blocked approval store"));

        driver.complete("done");

        assertThat(stores.find(first.id()).orElseThrow().state()).isEqualTo(TurnState.COMPLETED);
        assertThat(runtimeRegistry.running(first.id())).isPresent();

        AgentTurn second = service.start(command("second", "key-2"));
        assertThat(runtimeRegistry.running(first.id())).isEmpty();
        assertThat(second.state()).isEqualTo(TurnState.RUNNING);
        driver.complete("done");
    }

    @Test
    void terminalizesWithAnAdvancingClock()
    {
        Clock clock = new AdvancingClock(NOW.plusMillis(1));
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        metrics = new DefaultAgentMetricsService(stores, stores, clock);
        service = service(driver, runtimeRegistry, events, approvals, clock);
        AgentTurn running = service.start(command("first", "key-1"));

        driver.complete("done");

        assertThat(service.get(running.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(runtimeRegistry.running(running.id())).isEmpty();
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
    }

    @Test
    void terminalizesExceptionalProviderCompletion()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        service = service(driver, runtimeRegistry);
        AgentTurn running = service.start(command("first", "key-1"));

        driver.failCompletion(new AgentStorageException("provider future failed"));

        assertThat(service.get(running.id())).satisfies(turn -> {
            assertThat(turn.state()).isEqualTo(TurnState.INTERRUPTED);
            assertThat(turn.terminalReason()).contains("provider future failed");
        });
        assertThat(runtimeRegistry.running(running.id())).isEmpty();
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
    }

    @Test
    void persistsUserMessageBeforeStartingProvider()
    {
        driver.beforeStart(() -> {
            assertThat(events.readAfter(task.id(), 0, 100))
                    .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                    .singleElement()
                    .satisfies(event -> assertThat(((AgentEventPayload.UserMessage) event.payload()).text()).isEqualTo("first"));
            assertThat(stores.outboxEntries())
                    .filteredOn(entry -> entry.payload() instanceof AgentEventOutboxPayload.UserMessageBlob)
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.sequence()).contains(1L);
                        assertThat(entry.payload()).isInstanceOf(AgentEventOutboxPayload.UserMessageBlob.class);
                    });
            assertThat(stores.list(task.id())).singleElement().satisfies(turn -> {
                assertThat(turn.state()).isEqualTo(TurnState.ACCEPTED);
                assertThat(turn.startedAt()).isPresent();
                assertThat(turn.providerTurnReference()).isEmpty();
            });
        });

        AgentTurn turn = service.start(command("first", "key-1"));

        assertThat(turn.state()).isEqualTo(TurnState.RUNNING);
        assertThat(turn.startedAt()).isPresent();
        assertThat(turn.providerTurnReference()).isPresent();
        driver.complete("done");
        assertThat(service.get(turn.id()).state()).isEqualTo(TurnState.COMPLETED);
    }

    @Test
    void doesNotStartProviderWhenUserMessageCannotDrain()
    {
        service = service(driver, new AgentRuntimeRegistry(), new FailOnceDrainEventService(events));

        assertThatThrownBy(() -> service.start(command("first", "key-1")))
                .isInstanceOf(AgentStorageException.class)
                .hasMessage("blocked event log");

        assertThat(driver.openedReferences()).isEmpty();
        assertThat(stores.list(task.id()))
                .singleElement()
                .satisfies(turn -> assertThat(turn.state()).isEqualTo(TurnState.FAILED));
        assertThat(stores.outboxEntries())
                .hasSize(2)
                .allSatisfy(entry -> assertThat(entry.sequence()).isPresent());
        assertThat(stores.list(task.id())).singleElement().satisfies(turn ->
                assertThat(turn.startedAt()).isEmpty());
    }

    @Test
    void drainsPendingUserMessageBeforeReturningIdempotentTurnOrStartingAnother()
    {
        service = service(driver, new AgentRuntimeRegistry(), new FailOnceDrainEventService(events));

        assertThatThrownBy(() -> service.start(command("first", "key-1")))
                .isInstanceOf(AgentStorageException.class)
                .hasMessage("blocked event log");

        AgentTurn repeated = service.start(command("ignored", "key-1"));

        assertThat(repeated.state()).isEqualTo(TurnState.FAILED);
        assertThat(driver.openedReferences()).isEmpty();
        assertThat(stores.outboxEntries())
                .hasSize(2)
                .allSatisfy(entry -> assertThat(entry.sequence()).isPresent());

        AgentTurn next = service.start(command("second", "key-2"));
        driver.complete("done");

        assertThat(service.get(next.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("first", "second");
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
        assertThat(stores.find(task.id()).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(driver.openedReferences()).isEmpty();
    }

    @Test
    void returnsIdempotentTurnWithoutRequiringProviderAgain()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        driver.complete("done");
        driver.unavailable();

        assertThat(service.start(command("ignored", "key-1"))).isEqualTo(service.get(first.id()));
        assertThat(driver.commands()).hasSize(1);
    }

    @Test
    void rejectsConcurrentTurnAndCancelsTheLiveProviderRun()
    {
        AgentTurn running = service.start(command("first", "key-1"));
        driver.beforeCancel(() -> assertThat(approvals.deniedTurns).contains(running.id()));

        assertThatThrownBy(() -> service.start(command("second", "key-2")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("active turn");

        AgentTurn cancelled = service.cancel(running.id());
        assertThat(cancelled.state()).isEqualTo(TurnState.CANCELLED);
        assertThat(cancelled.cancellationRequestedAt()).contains(NOW);
        assertThat(approvals.deniedTurns).contains(running.id());
    }

    @Test
    void stillCancelsProviderWhenDurableApprovalDenialFails()
    {
        AgentTurn running = service.start(command("first", "key-1"));
        approvals.failNextDeny(new AgentStorageException("blocked approval store"));

        assertThatThrownBy(() -> service.cancel(running.id()))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("blocked approval store");

        assertThat(service.get(running.id()).state()).isEqualTo(TurnState.CANCELLED);
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
    void releasesTaskLockBeforeDenyingApprovalsAfterCompletion()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        service = service(driver, runtimeRegistry);
        approvals.beforeDeny(() -> awaitTaskLock(runtimeRegistry));
        AgentTurn running = service.start(command("first", "key-1"));

        driver.complete("done");

        assertThat(service.get(running.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(approvals.deniedTurns).contains(running.id());
    }

    @Test
    void releasesTaskLockBeforeDenyingApprovalsAfterStartFailure()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        service = service(driver, runtimeRegistry);
        approvals.beforeDeny(() -> awaitTaskLock(runtimeRegistry));
        driver.failNextStart();

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start failed");

        assertThat(stores.list(task.id())).singleElement().satisfies(turn ->
                assertThat(turn.state()).isEqualTo(TurnState.ACCEPTED));
    }

    @Test
    void nextReadClosesApprovalWhenCompletionDenialCouldNotPersist()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        DefaultAgentApprovalService durableApprovals = durableApprovals(runtimeRegistry);
        service = service(driver, runtimeRegistry, events, durableApprovals);
        AgentTurn running = service.start(command("first", "key-1"));
        ProviderApprovalRequest providerRequest = approvalRequest("completion-approval");
        CompletableFuture<ProviderApprovalDecision> provider = driver.requestApproval(providerRequest, Duration.ofMinutes(1))
                .toCompletableFuture();
        ApprovalRequest approval = stores.listPending(running.id()).getFirst().request();
        stores.failApprovalResolve(new AgentStorageException("blocked approval resolution"));

        driver.complete("done");

        assertThat(stores.find(approval.id()).orElseThrow().pending()).isTrue();
        assertThat(provider).isCompletedExceptionally();

        assertThat(service.get(running.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(stores.find(approval.id()).orElseThrow().pending()).isFalse();
        assertThat(runtimeRegistry.running(running.id())).isEmpty();
    }

    @Test
    void restartClosesApprovalWhenFailedStartDenialCouldNotPersist()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry();
        DefaultAgentApprovalService durableApprovals = durableApprovals(runtimeRegistry);
        service = service(driver, runtimeRegistry, events, durableApprovals);
        ApprovalId approvalId = ApprovalId.random();
        driver.beforeStart(() -> {
            AgentTurn accepted = stores.findActive(task.id()).orElseThrow();
            durableApprovals.request(new ApprovalRequest(
                    approvalId,
                    task.id(),
                    accepted.id(),
                    approvalRequest("start-approval"),
                    NOW), Duration.ofMinutes(1));
            stores.failApprovalResolve(new AgentStorageException("blocked approval resolution"));
            throw new IllegalStateException("start failed after approval");
        });

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("start failed after approval");

        assertThat(stores.list(task.id())).singleElement().satisfies(turn ->
                assertThat(turn.state()).isEqualTo(TurnState.ACCEPTED));
        assertThat(stores.find(approvalId).orElseThrow().pending()).isTrue();

        durableApprovals(new AgentRuntimeRegistry()).denyAllPending();

        assertThat(stores.find(approvalId).orElseThrow().pending()).isFalse();
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
    void keepsUncertainStartActiveAndBlocksAnotherSend()
    {
        driver.failNextStart();

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start failed");
        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> {
            assertThat(turn.state()).isEqualTo(TurnState.ACCEPTED);
            assertThat(turn.startedAt()).isPresent();
            assertThat(turn.providerTurnReference()).isEmpty();
            assertThat(turn.terminalReason()).isEmpty();
        });
        assertThat(stores.find(task.id()).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.turn(
                        stores.findActive(task.id()).orElseThrow().id(),
                        stores.findActive(task.id()).orElseThrow().createdAt()));
        assertThat(stores.findActive(task.id())).isPresent();
        assertThatThrownBy(() -> service.start(command("do not resend", "key-2")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("active turn");
    }

    @Test
    void cancelsProviderWhenRunningTurnCannotPersist()
    {
        boolean[] cancelled = {false};
        driver.beforeCancel(() -> cancelled[0] = true);
        stores.failTurnUpdate(TurnState.RUNNING, new AgentStorageException("blocked turn store"));

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isInstanceOf(AgentStorageException.class)
                .hasMessage("blocked turn store");

        assertThat(cancelled[0]).isTrue();
        assertThat(stores.find(task.id()).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> {
            assertThat(turn.state()).isEqualTo(TurnState.CANCELLED);
            assertThat(turn.startedAt()).isPresent();
            assertThat(turn.providerTurnReference()).isEmpty();
        });

        AgentTurn repeated = service.start(command("do not resend", "key-1"));
        assertThat(repeated.id()).isEqualTo(stores.list(task.id()).getFirst().id());
        assertThat(driver.commands()).hasSize(1);
    }

    @Test
    void preservesFailureWhenSessionReferenceAndCloseThrowTheSameInstance()
    {
        Clock clock = new AdvancingClock(NOW.plusMillis(1));
        metrics = new DefaultAgentMetricsService(stores, stores, clock);
        service = service(driver, new AgentRuntimeRegistry(), events, approvals, clock);
        IllegalStateException failure = new IllegalStateException("session transport failed");
        driver.failNextSessionReferenceAndClose(failure);

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isSameAs(failure);

        assertThat(stores.list(task.id())).singleElement().satisfies(turn ->
                assertThat(turn.state()).isEqualTo(TurnState.FAILED));
    }

    @Test
    void preservesFailureWhenTurnReferenceAndCancelThrowTheSameInstance()
    {
        IllegalStateException failure = new IllegalStateException("turn transport failed");
        driver.failNextTurnReferenceAndCancel(failure);

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isSameAs(failure);

        assertThat(stores.list(task.id())).singleElement().satisfies(turn ->
                assertThat(turn.state()).isEqualTo(TurnState.ACCEPTED));
        assertThat(stores.find(task.id()).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.findActive(task.id())).isPresent();
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
        return service(configuredDriver, runtimeRegistry, events);
    }

    private DefaultAgentTurnService service(
            DeterministicAgentDriver configuredDriver,
            AgentRuntimeRegistry runtimeRegistry,
            AgentEventService configuredEvents)
    {
        return service(configuredDriver, runtimeRegistry, configuredEvents, approvals);
    }

    private DefaultAgentTurnService service(
            DeterministicAgentDriver configuredDriver,
            AgentRuntimeRegistry runtimeRegistry,
            AgentEventService configuredEvents,
            AgentApprovalService configuredApprovals)
    {
        return service(configuredDriver, runtimeRegistry, configuredEvents, configuredApprovals, CLOCK);
    }

    private DefaultAgentTurnService service(
            DeterministicAgentDriver configuredDriver,
            AgentRuntimeRegistry runtimeRegistry,
            AgentEventService configuredEvents,
            AgentApprovalService configuredApprovals,
            Clock clock)
    {
        return new DefaultAgentTurnService(
                stores,
                stores,
                stores,
                stores,
                logs,
                resources,
                new StaticAgentDriverRegistry(ImmutableList.of(configuredDriver)),
                configuredEvents,
                metrics,
                configuredApprovals,
                runtimeRegistry,
                clock,
                Duration.ofMinutes(5));
    }

    private DefaultAgentApprovalService durableApprovals(AgentRuntimeRegistry runtimeRegistry)
    {
        return new DefaultAgentApprovalService(
                stores,
                stores,
                stores,
                stores,
                events,
                scheduler,
                CLOCK,
                runtimeRegistry);
    }

    private StartAgentTurnCommand command(String message, String idempotencyKey)
    {
        return new StartAgentTurnCommand(
                task.id(),
                message,
                ImmutableList.<AgentAttachment>of(),
                idempotencyKey);
    }

    private void awaitTaskLock(AgentRuntimeRegistry runtimeRegistry)
    {
        CompletableFuture.runAsync(() -> {
            synchronized (runtimeRegistry.taskLock(task.id())) {
                // Acquiring the monitor proves approval callbacks run outside it.
            }
        }).orTimeout(5, TimeUnit.SECONDS).join();
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

    private static ProviderApprovalRequest approvalRequest(String requestId)
    {
        return new ProviderApprovalRequest(
                requestId,
                ProviderApprovalRequest.Kind.COMMAND,
                "Run command",
                "git status",
                ImmutableList.of("command:git status"),
                ImmutableList.of(DENY),
                root(),
                NOW);
    }

    private static final class RecordingApprovalService
            implements AgentApprovalService
    {
        private final List<ApprovalRequest> requests = new ArrayList<>();
        private final List<TurnId> deniedTurns = new ArrayList<>();
        private Runnable beforeDeny = () -> {};
        private RuntimeException denyFailure;

        @Override
        public CompletionStage<ApprovalDecision> request(ApprovalRequest request, Duration timeout)
        {
            requests.add(request);
            return CompletableFuture.completedFuture(new ApprovalDecision(
                    request.id(),
                    ApprovalDecision.Decision.ALLOW,
                    Optional.of("command:run"),
                    false,
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
            beforeDeny.run();
            if (denyFailure != null) {
                RuntimeException failure = denyFailure;
                denyFailure = null;
                throw failure;
            }
        }

        @Override
        public void denyAllPending()
        {
            throw new UnsupportedOperationException();
        }

        private void failNextDeny(RuntimeException failure)
        {
            denyFailure = failure;
        }

        private void beforeDeny(Runnable action)
        {
            beforeDeny = action;
        }
    }

    private static final class FailOnceDrainEventService
            implements AgentEventService
    {
        private final AgentEventService delegate;
        private boolean fail = true;

        private FailOnceDrainEventService(AgentEventService delegate)
        {
            this(delegate, true);
        }

        private FailOnceDrainEventService(AgentEventService delegate, boolean fail)
        {
            this.delegate = delegate;
            this.fail = fail;
        }

        private void failNextDrain()
        {
            fail = true;
        }

        @Override
        public AgentEventEnvelope append(UnsequencedAgentEvent event)
        {
            return delegate.append(event);
        }

        @Override
        public AgentEventEnvelope drain(EventId eventId)
        {
            if (fail) {
                fail = false;
                throw new AgentStorageException("blocked event log");
            }
            return delegate.drain(eventId);
        }

        @Override
        public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
        {
            return delegate.readAfter(taskId, afterSequence, limit);
        }

        @Override
        public Flow.Publisher<AgentEventEnvelope> subscribe(TaskId taskId, long afterSequence)
        {
            return delegate.subscribe(taskId, afterSequence);
        }
    }

    private static final class AdvancingClock
            extends Clock
    {
        private Instant instant;

        private AdvancingClock(Instant instant)
        {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public synchronized Instant instant()
        {
            Instant current = instant;
            instant = instant.plusMillis(1);
            return current;
        }
    }
}
