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
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnCompletion;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InputDeliveryId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.QueuedInputNotFoundException;
import com.bytequay.agent.core.RenameAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.ReviewTurnInputResolver;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.SteerAgentTurnCommand;
import com.bytequay.agent.core.StoreAgentAttachmentCommand;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMemory;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentDeliveryUncertainException;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.AgentTurnInput;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.CodeQuery;
import com.bytequay.agent.spi.CodeQueryBridge;
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.bytequay.agent.spi.SessionAccess;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.bytequay.agent.spi.ProviderAgentEventSource.root;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    private Optional<AgentDriverRegistry> scopedRegistry = Optional.empty();
    private AgentRuntimeRegistry runtimeRegistry;
    private FixedWorktreeService worktrees;
    private final AtomicReference<String> managedInstructions = new AtomicReference<>("");
    private final List<AgentTask> instructionTasks = new CopyOnWriteArrayList<>();
    private boolean codeQueryUnavailable;
    private TaskWorktree queriedWorktree;
    private Optional<Consumer<DevelopmentTurnCompletion>> developmentListener = Optional.empty();
    private Consumer<TaskId> stopChildren = ignored -> {};
    private BiConsumer<AgentTask, AgentTurn> developmentAccepted = (acceptedTask, turn) -> {};
    private ReviewTurnInputResolver reviewTurnInput = ReviewTurnInputResolver.NONE;

    @BeforeEach
    void setUp()
    {
        stores = new InMemoryAgentStores();
        resources = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));
        driver = new DeterministicAgentDriver();
        Path worktree = temporaryDirectory.resolve("worktree").toAbsolutePath();
        worktrees = new FixedWorktreeService(worktree);
        taskService = new DefaultAgentTaskService(stores, worktrees, resources, new StaticAgentDriverRegistry(ImmutableList.of(driver)), CLOCK);
        TaskId taskId = TaskId.random();
        task = taskService.create(new CreateAgentTaskCommand(
                taskId,
                "workspace-a",
                DeterministicAgentDriver.LANE_ID,
                new RepositoryIdentity("bytequay", "bytequay"),
                "0123456789012345678901234567890123456789",
                TaskBranches.generate(taskId, "user", "Lifecycle test"),
                "Lifecycle test",
                new AgentTaskDefaults(Optional.of("model-a"), Optional.of("low"), AgentPermissionPolicy.AUTO)));
        logs = new FileTaskLogStore(resources);
        events = new DefaultAgentEventService(logs, stores, stores, CLOCK);
        metrics = new DefaultAgentMetricsService(stores, stores, logs, CLOCK);
        approvals = new RecordingApprovalService();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
        service = service(driver, runtimeRegistry);
    }

    @Test
    void sharedCheckoutClaimsExcludeDeveloperAndReviewerInBothDirections()
    {
        TaskId reviewerId = TaskId.random();
        AgentTask reviewer = AgentTask.created(reviewerId, false, "Reviewer", TitleSource.USER, task.laneId(),
                task.taskWorktree().orElseThrow(), task.defaults(), NOW);
        stores.insert(reviewer);
        resources.allocate(reviewerId);
        assertThat(runtimeRegistry.taskLock(reviewerId)).isSameAs(runtimeRegistry.taskLock(task.id()));
        service.claimLocalRun(task.id(), "developer-build");
        assertThatThrownBy(() -> service.claimLocalRun(reviewerId, "review-build")).isInstanceOf(AgentConflictException.class).hasMessageContaining("shared working copy");
        service.releaseLocalRun(task.id(), "developer-build");
        service.claimLocalRun(reviewerId, "review-build");
        AgentRuntimeRegistry restarted = new AgentRuntimeRegistry(stores, ignored -> {});
        assertThat(restarted.taskLock(reviewerId)).isSameAs(restarted.taskLock(task.id()));
        assertThatThrownBy(() -> service(driver, restarted).claimLocalRun(task.id(), "developer-build"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("shared working copy");
        service.releaseLocalRun(reviewerId, "review-build");
        service.start(new StartAgentTurnCommand(reviewerId, "Review the edits", ImmutableList.of(), "shared-review"));
        assertThat(driver.openedContexts().getLast().access()).isEqualTo(SessionAccess.REVIEW_READ_ONLY);
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
        private int cleanupCalls;
        private boolean dirty;

        private FixedWorktreeService(Path worktree)
        {
            this.worktree = worktree;
        }

        @Override
        public Path ownedCheckout(TaskWorktree worktree)
        {
            return worktree.canonicalWorktree();
        }

        @Override
        public TaskWorktree provision(ProvisionWorktreeCommand command)
        {
            return new TaskWorktree(
                    command.taskId(),
                    command.workspaceId(),
                    command.physicalRepository(),
                    worktree.resolve(command.taskId().toString()),
                    command.generatedBranch(),
                    command.startCommit());
        }

        @Override
        public WorktreeSnapshot inspect(TaskWorktree worktree)
        {
            return new WorktreeSnapshot(worktree, worktree.startCommit(), dirty, false, dirty);
        }

        @Override
        public void delete(TaskWorktree worktree)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cleanup(TaskWorktree worktree)
        {
            cleanupCalls++;
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
    void trustsCreationReadinessOnlyForTheFreshInteractiveFirstTurn()
    {
        AgentTask interactive = interactiveTask("First request");
        int probesAfterCreation = driver.probeCount();
        driver.readiness(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);

        AgentTurn first = service.start(StartAgentTurnCommand.trustedInitialInteractiveTurn(interactive.id(), "First request", ImmutableList.of(), "create-key"));

        assertThat(first.state()).isEqualTo(TurnState.RUNNING);
        assertThat(driver.probeCount()).isEqualTo(probesAfterCreation);
        driver.complete("done");

        assertThatThrownBy(() -> service.start(new StartAgentTurnCommand(interactive.id(), "Later request", ImmutableList.of(), "later-key")))
                .isInstanceOf(AgentProviderUnavailableException.class);
        assertThat(driver.probeCount()).isEqualTo(probesAfterCreation + 1);
    }

    @Test
    void rejectsTaskCreationReadinessForANonInteractiveTask()
    {
        int probesBeforeStart = driver.probeCount();

        assertThatThrownBy(() -> service.start(StartAgentTurnCommand.trustedInitialInteractiveTurn(task.id(), "First request", ImmutableList.of(), "create-key")))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("fresh interactive task");

        assertThat(driver.probeCount()).isEqualTo(probesBeforeStart);
        assertThat(stores.list(task.id())).isEmpty();
    }

    @Test
    void reusesAPassedReadinessCheckForASendButNeverAFailedOne()
    {
        AdvancingClock clock = new AdvancingClock(NOW.plusMillis(1));
        metrics = new DefaultAgentMetricsService(stores, stores, logs, clock);
        service = service(driver, runtimeRegistry, events, approvals, clock);
        int probesBefore = driver.probeCount();

        service.start(command("first", "key-1"));
        driver.complete("first done");
        service.start(command("second", "key-2"));
        driver.complete("second done");
        assertThat(driver.probeCount()).isEqualTo(probesBefore + 1);

        clock.advance(Duration.ofMinutes(10));
        driver.readiness(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThatThrownBy(() -> service.start(command("third", "key-3"))).isInstanceOf(AgentProviderUnavailableException.class);
        driver.readiness(AgentProviderStatus.Readiness.READY);
        assertThat(service.start(command("third", "key-3")).state()).isEqualTo(TurnState.RUNNING);
        assertThat(driver.probeCount()).isEqualTo(probesBefore + 3);
    }

    @Test
    void checksReadinessBeforeTakingTheTaskLock()
            throws Exception
    {
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        driver.beforeProbe(() -> {
            checking.countDown();
            awaitUninterruptibly(release);
        });
        CompletableFuture<AgentTurn> started = CompletableFuture.supplyAsync(() -> service.start(command("first", "key-1")));
        assertThat(checking.await(5, TimeUnit.SECONDS)).isTrue();

        // A retry looks its key up under the task lock first; a slow provider check must not hold that lock.
        assertThat(CompletableFuture.supplyAsync(() -> service.findTurnByIdempotencyKey(task.id(), "key-1")).get(5, TimeUnit.SECONDS)).isEmpty();

        release.countDown();
        assertThat(started.get(5, TimeUnit.SECONDS).state()).isEqualTo(TurnState.RUNNING);
    }

    @Test
    void pausesAQueuedInputThatNothingWillStart()
    {
        List<Runnable> queueStarts = new ArrayList<>();
        service = service(driver, runtimeRegistry, events, approvals, CLOCK, queueStarts::add);
        TaskId reviewerId = TaskId.random();
        stores.insert(AgentTask.created(reviewerId, false, "Reviewer", TitleSource.USER, task.laneId(), task.taskWorktree().orElseThrow(), task.defaults(), NOW));
        resources.allocate(reviewerId);
        service.start(command("first", "key-1"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "Next", ImmutableList.of(), "queued"));
        driver.complete("first done");

        // A run on the shared working copy took it between the turn's end and the queue start; its end starts nothing.
        service.claimLocalRun(reviewerId, "review-build");
        queueStarts.forEach(Runnable::run);

        QueuedAgentInput paused = stores.find(queued.id()).orElseThrow();
        assertThat(paused.state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        assertThat(paused.pauseReason().orElseThrow()).startsWith("Not started: ").contains("shared working copy");
        service.releaseLocalRun(reviewerId, "review-build");
        assertThat(service.resumeQueued(task.id()).state()).isEqualTo(TurnState.RUNNING);
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
        assertThat(metrics.taskTotals(task.id()).inputTokens()).isEqualTo(4);
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
        assertThat(driver.openedReferences())
                .hasSize(2)
                .allSatisfy(reference -> assertThat(reference).isEqualTo(driver.openedReferences().getFirst()));
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
    void aComposerProviderChangeAppliesOnlyToTheNextTurnWithAFreshSession()
    {
        DeterministicAgentDriver nextDriver = new DeterministicAgentDriver(new AgentLaneId("next-provider"));
        var registry = new StaticAgentDriverRegistry(ImmutableList.of(driver, nextDriver));
        scopedRegistry = Optional.of(registry);
        taskService = new DefaultAgentTaskService(stores, worktrees, resources, registry, CLOCK);
        service = service(driver, runtimeRegistry);
        AgentTurn first = service.start(command("Investigate", "first"));
        AgentTask running = taskService.get(task.id());
        AgentTaskDefaults selected = new AgentTaskDefaults(Optional.of("model-b"), Optional.of("high"), AgentPermissionPolicy.AUTO).withLaneId(nextDriver.id());
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(task.id(), running.version(), selected));

        assertThat(service.get(first.id()).settings().orElseThrow().model()).contains("model-a");
        assertThat(taskService.get(task.id()).laneId()).isEqualTo(driver.id());
        assertThat(nextDriver.openedContexts()).isEmpty();
        driver.complete("Investigation saved");
        AgentTurn second = service.start(command("Validate saved findings", "second"));

        assertThat(second.settings().orElseThrow().laneId()).contains(nextDriver.id());
        assertThat(second.settings().orElseThrow().model()).contains("model-b");
        assertThat(nextDriver.openedContexts()).singleElement().satisfies(context -> {
            assertThat(context.sessionReference()).isEmpty();
            assertThat(context.worktree()).isEqualTo(task.worktree());
            assertThat(context.model()).contains("model-b");
        });
        assertThat(taskService.get(task.id()).laneId()).isEqualTo(nextDriver.id());
        assertThat(service.get(first.id()).settings().orElseThrow().laneId()).contains(driver.id());
        assertThat(service.start(command("Duplicate", "second")).id()).isEqualTo(second.id());
        nextDriver.complete("Validated");
        service.start(command("Continue", "third"));
        assertThat(nextDriver.openedReferences()).hasSize(2).allSatisfy(reference -> assertThat(reference).isEqualTo(nextDriver.openedReferences().getFirst()));
    }

    @Test
    void referenceReuseStopsAtAProviderHandoff()
    {
        AgentTurn first = service.start(command("First", "first"));
        driver.complete("Done");
        AgentTurn previous = service.get(first.id());
        AgentTurn current = AgentTurn.accepted(TurnId.random(), task.id(), "second", previous.settings().orElseThrow().withLaneId(new AgentLaneId("different-provider")), NOW);
        assertThat(ReviewTurnInputResolver.stillInConversation(ImmutableList.of(previous, current), turn -> true)).isEmpty();
        AgentTurn sameProvider = AgentTurn.accepted(TurnId.random(), task.id(), "same", previous.settings().orElseThrow(), NOW);
        assertThat(ReviewTurnInputResolver.stillInConversation(ImmutableList.of(previous, sameProvider), turn -> true)).contains(previous);
    }

    @Test
    void queuedInputKeepsItsSelectedProviderWhenTheComposerChangesAgain()
    {
        DeterministicAgentDriver nextDriver = new DeterministicAgentDriver(new AgentLaneId("queued-provider"));
        var registry = new StaticAgentDriverRegistry(ImmutableList.of(driver, nextDriver));
        scopedRegistry = Optional.of(registry);
        taskService = new DefaultAgentTaskService(stores, worktrees, resources, registry, CLOCK);
        service = service(driver, runtimeRegistry);
        service.start(command("First", "first"));
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(task.id(), taskService.get(task.id()).version(), task.defaults().withLaneId(nextDriver.id())));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "Next", ImmutableList.of(), "queued"));
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(task.id(), taskService.get(task.id()).version(), task.defaults().withLaneId(driver.id())));
        driver.complete("First done");

        assertThat(nextDriver.commands()).singleElement().satisfies(command -> assertThat(command.message()).isEqualTo("Next"));
        assertThat(queued.settings().laneId()).contains(nextDriver.id());
        nextDriver.complete("Next done");
        service.start(command("Third", "third"));
        assertThat(driver.openedContexts().getLast().sessionReference()).isEmpty();
        assertThat(taskService.get(task.id()).laneId()).isEqualTo(driver.id());
    }

    @Test
    void persistsTypedProviderFailureOnTheTerminalTurn()
    {
        AgentTurn turn = service.start(command("first", "key-1"));

        driver.fail("Claude sign-in required", AgentTurnFailureKind.AUTHENTICATION_REQUIRED);

        AgentTurn failed = service.get(turn.id());
        assertThat(failed.state()).isEqualTo(TurnState.FAILED);
        assertThat(failed.failureKind()).contains(AgentTurnFailureKind.AUTHENTICATION_REQUIRED);
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
    }

    @Test
    void snapshotsSubmissionAutomationWithoutChangingTaskDefaultsOrQueuedWork()
    {
        AgentTask interactive = interactiveTask("Implement the change");
        DevelopmentAutomation automation = new DevelopmentAutomation(true, true, true, Optional.of(AgentLaneId.CLAUDE_CLI));
        AgentTask configured = taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                interactive.id(), interactive.version(), interactive.defaults().withDevelopmentAutomation(automation)));
        AgentTurn first = service.start(new StartAgentTurnCommand(configured.id(), configured.version(), "first", ImmutableList.of(), "automation-1")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.LOCAL_ONLY.forDevelopment())));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(
                configured.id(), taskService.get(configured.id()).version(), "second", ImmutableList.of(), "automation-queue"));
        AgentTask current = taskService.get(configured.id());
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                current.id(), current.version(), current.defaults().withDevelopmentAutomation(DevelopmentAutomation.LOCAL_ONLY)));

        assertThat(first.settings().orElseThrow().developmentAutomation()).contains(DevelopmentAutomation.LOCAL_ONLY.forDevelopment());
        assertThat(queued.settings().developmentAutomation()).contains(automation);
        AgentTurn replay = service.start(new StartAgentTurnCommand(configured.id(), "different text", ImmutableList.of(), "automation-1")
                .withDevelopmentAutomation(Optional.of(automation)));
        assertThat(replay.settings()).isEqualTo(first.settings());
        driver.complete("first done");

        assertThat(stores.findActive(configured.id()).orElseThrow().settings().orElseThrow().developmentAutomation()).contains(automation);
        assertThat(taskService.get(configured.id()).defaults().developmentAutomation()).contains(DevelopmentAutomation.LOCAL_ONLY);
        driver.complete("second done");
    }

    @Test
    void deepSeekTurnsUseNormalAutomationAndPreserveQueuedSettings()
    {
        AgentLaneId lane = new AgentLaneId("deepseek-key-11111111-1111-1111-1111-111111111111");
        driver = new DeterministicAgentDriver(lane);
        taskService = new DefaultAgentTaskService(stores, worktrees, resources, new StaticAgentDriverRegistry(ImmutableList.of(driver)), CLOCK);
        service = service(driver, runtimeRegistry);
        TaskId taskId = TaskId.random();
        AgentTask deepSeek = taskService.createInteractive(new CreateInteractiveAgentTaskCommand(
                taskId, "workspace-a", lane, new RepositoryIdentity("bytequay", "bytequay"),
                "0123456789012345678901234567890123456789",
                TaskBranches.generate(taskId, "user", "DeepSeek task"), "DeepSeek task",
                new AgentTaskDefaults(Optional.of("deepseek-flash"), Optional.of("high"), AgentPermissionPolicy.AUTO)
                        .withDevelopmentAutomation(DevelopmentAutomation.DEFAULT),
                "deepseek-fingerprint", Optional.empty()));

        assertThat(service.effectiveSettings(deepSeek.id()).developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);

        AgentTurn first = service.start(new StartAgentTurnCommand(
                deepSeek.id(), "first", ImmutableList.of(), "deepseek-first")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.LOCAL_ONLY.forDevelopment())));
        assertThat(first.settings().orElseThrow().developmentAutomation()).contains(DevelopmentAutomation.LOCAL_ONLY.forDevelopment());
        assertThat(driver.openedContexts().getLast().codeQueries()).isEmpty();
        assertThat(driver.openedContexts().getLast().managedInstructions())
                .contains("Commit finished development work: true", "Auto draft PR: false")
                .doesNotContain("Private local review");
        long version = taskService.get(deepSeek.id()).version();
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(
                deepSeek.id(), version, "queued", ImmutableList.of(), "deepseek-queue",
                Optional.of(DevelopmentAutomation.DEFAULT)));
        assertThat(queued.settings().developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);

        AgentTask current = taskService.get(deepSeek.id());
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                deepSeek.id(), current.version(),
                new AgentTaskDefaults(Optional.of("deepseek-v4-pro"), current.defaults().effort(),
                        current.defaults().permissionPolicy(), current.defaults().serviceTier(),
                        current.defaults().developmentAutomation())));
        driver.unsupportedModel(Optional.of("deepseek-flash"));
        driver.complete("first done");
        assertThat(stores.find(queued.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        assertThat(stores.findActive(deepSeek.id())).isEmpty();
        assertThatThrownBy(() -> service.resumeQueued(deepSeek.id()))
                .isInstanceOf(AgentProviderUnavailableException.class).hasMessageContaining("model unavailable");
        driver.unsupportedModel(Optional.empty());
        AgentTurn resumed = service.resumeQueued(deepSeek.id());
        assertThat(resumed.settings().orElseThrow().model()).contains("deepseek-flash");
    }

    @Test
    void workflowTurnsCannotAcquireAnInteractiveAutomationOverride()
    {
        assertThatThrownBy(() -> service.start(command("first", "workflow-automation")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.DEFAULT))))
                .isInstanceOf(InvalidAgentRequestException.class).hasMessageContaining("interactive task");
        assertThat(stores.findActive(task.id())).isEmpty();
    }

    @Test
    void doesNotUseTheFirstAnswerAsTheTaskTitle()
    {
        AgentTask interactive = interactiveTask("Investigate the flaky cherry-pick preview test and fix the root cause.");
        assertThat(interactive.title()).isEqualTo("Investigate the flaky cherry-pick…");
        assertThat(interactive.titleSource()).isEqualTo(TitleSource.PROVISIONAL);

        service.start(new StartAgentTurnCommand(interactive.id(), "first", ImmutableList.of(), "key-1"));
        driver.complete("The preview reads every commit body. Trimming it to the selection fixes the flake.");

        AgentTask afterFirstAnswer = stores.find(interactive.id()).orElseThrow();
        assertThat(afterFirstAnswer.title()).isEqualTo(interactive.title());
        assertThat(afterFirstAnswer.titleSource()).isEqualTo(TitleSource.PROVISIONAL);
        assertThat(afterFirstAnswer.worktreeMutationClaim()).isEmpty();

        service.start(new StartAgentTurnCommand(interactive.id(), "second", ImmutableList.of(), "key-2"));
        driver.complete("Done. Nothing else to rename.");

        assertThat(stores.find(interactive.id()).orElseThrow().title()).isEqualTo(interactive.title());
    }

    @Test
    void keepsAUserChosenTitleAcrossTurnCompletion()
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

    @Test
    void worktreeSynchronizationHoldsMutationOwnershipAgainstConcurrentTurnAdmission()
            throws Exception
    {
        CompletableFuture<Void> attempted = new CompletableFuture<>();
        AtomicReference<CompletableFuture<AgentTurn>> competing = new AtomicReference<>();

        service.synchronizeWorktree(task.id(), task.version(), claimed -> {
            assertThat(claimed.worktreeMutationClaim()).hasValueSatisfying(claim -> assertThat(claim.kind()).isEqualTo(WorktreeMutationClaim.Kind.COMPLETION));
            assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEqualTo(claimed.worktreeMutationClaim());
            assertThatThrownBy(() -> service.start(new StartAgentTurnCommand(task.id(), "Nested start", ImmutableList.of(), "nested-sync-start")))
                    .isInstanceOf(AgentConflictException.class).hasMessageContaining("claimed");
            competing.set(CompletableFuture.supplyAsync(() -> {
                attempted.complete(null);
                return service.start(new StartAgentTurnCommand(task.id(), "Concurrent start", ImmutableList.of(), "concurrent-sync-start"));
            }));
            attempted.orTimeout(5, TimeUnit.SECONDS).join();
            assertThatThrownBy(() -> competing.get().get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(stores.findActive(task.id())).isEmpty();
        });

        assertThat(competing.get().get(5, TimeUnit.SECONDS).state()).isEqualTo(TurnState.RUNNING);
        driver.complete("Started after synchronization");
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
    }

    @Test
    void worktreeUpdatesRefreshTheTaskWithoutRevivingItsPreviousTurn()
    {
        service.start(command("First request", "before-worktree-save"));
        driver.complete("Done");
        AgentTask before = taskService.get(task.id());

        service.synchronizeWorktree(task.id(), before.version(), claimed -> {
            assertThat(worktreeEvents(task.id())).hasSize(1);
            assertThat(taskService.get(task.id()).version()).isEqualTo(claimed.version());
            assertThat(claimed.worktreeMutationClaim()).isPresent();
        });

        AgentTask after = taskService.get(task.id());
        assertThat(after.version()).isEqualTo(before.version() + 2);
        assertThat(worktreeEvents(task.id())).hasSize(2).allSatisfy(event -> assertThat(event.turnId()).isEmpty());
        assertThatThrownBy(() -> service.start(new StartAgentTurnCommand(task.id(), before.version(), "Amend and force push", ImmutableList.of(), "after-save")))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Task version changed");
        assertThat(service.findTurnByIdempotencyKey(task.id(), "after-save")).isEmpty();

        AgentTurn accepted = service.start(new StartAgentTurnCommand(task.id(), after.version(), "Amend and force push", ImmutableList.of(), "after-save"));
        assertThat(service.start(new StartAgentTurnCommand(task.id(), before.version(), "Amend and force push", ImmutableList.of(), "after-save"))).isEqualTo(accepted);
        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("First request", "Amend and force push");
    }

    @Test
    void failedWorktreeActionStillPublishesItsReleasedVersion()
    {
        assertThatThrownBy(() -> service.synchronizeWorktree(task.id(), task.version(), claimed -> {
            assertThat(worktreeEvents(task.id())).hasSize(1);
            throw new IllegalStateException("Cannot save this file");
        })).hasMessage("Cannot save this file");

        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEmpty();
        assertThat(taskService.get(task.id()).version()).isEqualTo(task.version() + 2);
        assertThat(worktreeEvents(task.id())).hasSize(2).allSatisfy(event -> assertThat(event.turnId()).isEmpty());
    }

    @Test
    void worktreeClaimAndRefreshRollBackTogetherBeforeTheAction()
    {
        stores.failOutboxInsert(AgentEventPayload.Kind.LIFECYCLE, new AgentStorageException("Cannot record refresh"));
        AtomicInteger writes = new AtomicInteger();

        assertThatThrownBy(() -> service.synchronizeWorktree(task.id(), task.version(), claimed -> writes.incrementAndGet()))
                .isInstanceOf(AgentStorageException.class).hasMessage("Cannot record refresh");

        assertThat(writes).hasValue(0);
        assertThat(taskService.get(task.id())).isEqualTo(task);
        assertThat(worktreeEvents(task.id())).isEmpty();
    }

    @Test
    void failedReleaseOutboxWriteKeepsTheOriginalErrorAndRetriesClaimCleanup()
    {
        assertThatThrownBy(() -> service.synchronizeWorktree(task.id(), task.version(), claimed ->
                stores.failOutboxInsert(AgentEventPayload.Kind.LIFECYCLE, new AgentStorageException("Cannot record released version"))))
                .isInstanceOf(AgentStorageException.class).hasMessage("Cannot record released version");

        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEmpty();
        assertThat(taskService.get(task.id()).version()).isEqualTo(task.version() + 2);
        assertThat(worktreeEvents(task.id())).hasSize(2);
    }

    @Test
    void failedLocalRunOutboxWriteDoesNotLeaveAnUnreportedClaim()
    {
        stores.failOutboxInsert(AgentEventPayload.Kind.LIFECYCLE, new AgentStorageException("Cannot record run claim"));
        assertThatThrownBy(() -> service.claimLocalRun(task.id(), "run-1"))
                .isInstanceOf(AgentStorageException.class).hasMessage("Cannot record run claim");
        assertThat(taskService.get(task.id())).isEqualTo(task);
        assertThat(worktreeEvents(task.id())).isEmpty();

        service.claimLocalRun(task.id(), "run-1");
        stores.failOutboxInsert(AgentEventPayload.Kind.LIFECYCLE, new AgentStorageException("Cannot record run release"));
        assertThatThrownBy(() -> service.releaseLocalRun(task.id(), "run-1"))
                .isInstanceOf(AgentStorageException.class).hasMessage("Cannot record run release");
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).contains(WorktreeMutationClaim.localRun("run-1", NOW));
        assertThat(worktreeEvents(task.id())).hasSize(1);

        service.releaseLocalRun(task.id(), "run-1");
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEmpty();
        assertThat(worktreeEvents(task.id())).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedLocalRunRefreshDrainPreservesTheCommittedOwnershipAndCanReplay(boolean failRelease)
    {
        FailLifecycleDrainEventService failing = new FailLifecycleDrainEventService(events, stores, AgentEventPayload.LifecyclePhase.WORKTREE_UPDATED, !failRelease);
        service = service(driver, runtimeRegistry, failing);
        AgentTask claimed = service.claimLocalRun(task.id(), "run-1");
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEqualTo(claimed.worktreeMutationClaim());
        assertThatThrownBy(() -> service.start(command("While running", "during-refresh-failure")))
                .isInstanceOf(AgentConflictException.class).hasMessage("A run is using this working copy");
        if (failRelease) {
            failing.failNextDrain();
        }
        service.releaseLocalRun(task.id(), "run-1");
        AgentTask released = taskService.get(task.id());
        assertThat(released.worktreeMutationClaim()).isEmpty();

        // A task request drains pending notifications before accepting a new turn.
        service.start(new StartAgentTurnCommand(task.id(), released.version(), "After the local run", ImmutableList.of(), "after-local-run"));

        assertThat(worktreeEvents(task.id())).hasSize(2).allSatisfy(event -> assertThat(event.turnId()).isEmpty());
        assertThat(stores.outboxEntries()).allSatisfy(event -> assertThat(event.sequence()).isPresent());
        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("After the local run");
    }

    @Test
    void worktreeActionRetainsItsDurableClaimWhenTheRefreshCannotDrain()
    {
        FailLifecycleDrainEventService failing = new FailLifecycleDrainEventService(events, stores, AgentEventPayload.LifecyclePhase.WORKTREE_UPDATED);
        service = service(driver, runtimeRegistry, failing);
        AtomicInteger writes = new AtomicInteger();
        service.synchronizeWorktree(task.id(), task.version(), claimed -> {
            assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEqualTo(claimed.worktreeMutationClaim());
            writes.incrementAndGet();
            failing.failNextDrain();
        });

        AgentTask saved = taskService.get(task.id());
        assertThat(writes).hasValue(1);
        assertThat(saved.worktreeMutationClaim()).isEmpty();
        assertThat(saved.version()).isEqualTo(task.version() + 2);
        service.start(new StartAgentTurnCommand(task.id(), saved.version(), "Continue", ImmutableList.of(), "after-save-refresh-failure"));
        assertThat(worktreeEvents(task.id())).hasSize(2).allSatisfy(event -> assertThat(event.turnId()).isEmpty());
    }

    private List<AgentEventEnvelope> worktreeEvents(TaskId taskId)
    {
        return events.readAfter(taskId, 0, 100).stream()
                .filter(event -> event.payload() instanceof AgentEventPayload.Lifecycle lifecycle && lifecycle.phase() == AgentEventPayload.LifecyclePhase.WORKTREE_UPDATED)
                .collect(toImmutableList());
    }

    @Test
    void worktreeSynchronizationRejectsActiveQueuedAndClaimedTasks()
    {
        AtomicInteger calls = new AtomicInteger();
        Consumer<AgentTask> action = ignored -> calls.incrementAndGet();
        service.start(new StartAgentTurnCommand(task.id(), "Run review", ImmutableList.of(), "sync-active"));
        assertThatThrownBy(() -> service.synchronizeWorktree(task.id(), taskService.get(task.id()).version(), action))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("active turn");
        service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "Queued review", ImmutableList.of(), "sync-queued"));
        driver.fail("Stopped review", AgentTurnFailureKind.AUTHENTICATION_REQUIRED);
        assertThatThrownBy(() -> service.synchronizeWorktree(task.id(), taskService.get(task.id()).version(), action))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("queued input");
        service.removeQueued(task.id(), service.queued(task.id()).getFirst().id());
        WorktreeMutationClaim owner = new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, "other-owner", NOW);
        AgentTask claimed = stores.update(taskService.get(task.id()).claimWorktreeMutation(owner, NOW));
        assertThatThrownBy(() -> service.synchronizeWorktree(task.id(), claimed.version(), action))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("claimed");
        assertThat(calls).hasValue(0);
        stores.update(claimed.releaseWorktreeMutation(owner, NOW));
    }

    @Test
    void localRunClaimExcludesTurnsQueuedInputAndOtherWritersUntilItsOwnerReleasesIt()
    {
        AgentTask claimed = service.claimLocalRun(task.id(), "run-1");
        assertThat(claimed.worktreeMutationClaim()).contains(WorktreeMutationClaim.localRun("run-1", NOW));
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEqualTo(claimed.worktreeMutationClaim());

        assertThatThrownBy(() -> service.start(command("While running", "local-run-start")))
                .isInstanceOf(AgentConflictException.class).hasMessage("A run is using this working copy");
        assertThatThrownBy(() -> service.enqueue(new QueueAgentInputCommand(task.id(), claimed.version(), "Later", ImmutableList.of(), "local-run-queue")))
                .isInstanceOf(AgentConflictException.class).hasMessage("A run is using this working copy");
        assertThatThrownBy(() -> service.synchronizeWorktree(task.id(), claimed.version(), ignored -> {}))
                .isInstanceOf(AgentConflictException.class).hasMessage("A run is using this working copy");
        assertThatThrownBy(() -> service.claimLocalRun(task.id(), "run-2"))
                .isInstanceOf(AgentConflictException.class).hasMessage("A run is using this working copy");

        // Only the run that holds the claim releases it, and releasing twice is harmless.
        service.releaseLocalRun(task.id(), "run-2");
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEqualTo(claimed.worktreeMutationClaim());
        service.releaseLocalRun(task.id(), "run-1");
        service.releaseLocalRun(task.id(), "run-1");
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEmpty();
        assertThat(worktreeEvents(task.id())).hasSize(2).allSatisfy(event -> assertThat(event.turnId()).isEmpty());
    }

    @Test
    void localRunClaimFollowsTheTurnStartRulesWithoutProviderReadiness()
    {
        service.start(command("Run review", "local-run-active"));
        assertThatThrownBy(() -> service.claimLocalRun(task.id(), "run-1"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("active turn");
        service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "Queued review", ImmutableList.of(), "local-run-queued"));
        driver.fail("Stopped review", AgentTurnFailureKind.AUTHENTICATION_REQUIRED);
        assertThatThrownBy(() -> service.claimLocalRun(task.id(), "run-1"))
                .isInstanceOf(AgentConflictException.class).hasMessage("Remove or resume the queued question");
        service.removeQueued(task.id(), service.queued(task.id()).getFirst().id());
        WorktreeMutationClaim completion = new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, "other-owner", NOW);
        AgentTask held = stores.update(taskService.get(task.id()).claimWorktreeMutation(completion, NOW));
        assertThatThrownBy(() -> service.claimLocalRun(task.id(), "run-1"))
                .isInstanceOf(AgentConflictException.class).hasMessage("Task worktree is claimed by COMPLETION");
        // A LOCAL_RUN release never touches another kind of claim.
        service.releaseLocalRun(task.id(), "other-owner");
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).contains(completion);
        stores.update(held.releaseWorktreeMutation(completion, NOW));

        driver.failProbe(new IllegalStateException("A local run never probes the provider"));
        assertThat(service.claimLocalRun(task.id(), "run-1").worktreeMutationClaim().map(WorktreeMutationClaim::kind)).contains(WorktreeMutationClaim.Kind.LOCAL_RUN);
    }

    @Test
    void worktreeSynchronizationReleasesClaimOnSuccessAndFailure()
    {
        service.synchronizeWorktree(task.id(), task.version(), ignored -> {});
        AgentTask synchronizedTask = taskService.get(task.id());
        assertThat(synchronizedTask.worktreeMutationClaim()).isEmpty();
        assertThatThrownBy(() -> service.synchronizeWorktree(task.id(), task.version(), ignored -> {}))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("version changed");

        AgentTask development = interactiveTask("Develop a feature");
        assertThatThrownBy(() -> service.synchronizeWorktree(development.id(), development.version(), claimed -> {
            assertThat(claimed.worktreeMutationClaim()).isPresent();
            throw new IllegalStateException("Git move failed");
        })).hasMessage("Git move failed");
        AgentTask retained = taskService.get(development.id());
        assertThat(retained.worktreeMutationClaim()).isEmpty();
    }

    @Test
    void worktreeSynchronizationRejectsActiveAndQueuedWork()
    {
        AgentTask development = interactiveTask("Develop a feature");
        service.start(new StartAgentTurnCommand(development.id(), "Run tests", ImmutableList.of(), "private-running"));
        assertThatThrownBy(() -> service.synchronizeWorktree(development.id(), taskService.get(development.id()).version(), ignored -> {}))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("active turn");
        service.enqueue(new QueueAgentInputCommand(development.id(), taskService.get(development.id()).version(), "Later", ImmutableList.of(), "private-later"));
        driver.fail("Test execution failed", AgentTurnFailureKind.AUTHENTICATION_REQUIRED);
        assertThatThrownBy(() -> service.synchronizeWorktree(development.id(), taskService.get(development.id()).version(), ignored -> {}))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("queued input");
        QueuedAgentInput queued = service.queued(development.id()).getFirst();
        service.removeQueued(development.id(), queued.id());
        service.synchronizeWorktree(development.id(), taskService.get(development.id()).version(), ignored -> {});
        assertThat(service.effectiveSettings(development.id()).developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);
    }

    private AgentTask interactiveTask(String firstMessage)
    {
        TaskId taskId = TaskId.random();
        return taskService.createInteractive(new CreateInteractiveAgentTaskCommand(
                taskId,
                "workspace-a",
                DeterministicAgentDriver.LANE_ID,
                new RepositoryIdentity("bytequay", "bytequay"),
                "0123456789012345678901234567890123456789",
                TaskBranches.generate(taskId, "user", firstMessage),
                firstMessage,
                new AgentTaskDefaults(Optional.of("model-a"), Optional.of("low"), AgentPermissionPolicy.AUTO),
                "test-creation-fingerprint", Optional.empty()));
    }

    @Test
    void handlesProviderCompletionThatPrecedesStartReturn()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
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

        assertThat(metrics.taskTotals(task.id()).inputTokens()).isEqualTo(4);
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
    void acceptsRootPlanBeforeProviderStartAcknowledgement()
    {
        List<ProviderAgentEvent.PlanItem> items = ImmutableList.of(new ProviderAgentEvent.PlanItem("Implement", ProviderAgentEvent.PlanItemState.IN_PROGRESS));
        driver.afterStart(() -> {
            AgentTurn accepted = stores.findActive(task.id()).orElseThrow();
            assertThat(accepted.providerTurnReference()).isEmpty();
            ProviderAgentEventSource source = new ProviderAgentEventSource(
                    Optional.of("root-thread"), Optional.empty(), Optional.of("/"), Optional.of("turn-" + accepted.id()), Optional.empty());
            driver.emit(new ProviderAgentEvent.Plan(Optional.empty(), items, source));
        });
        AgentTurn running = service.start(command("Implement", "early-plan"));
        assertThat(running.state()).isEqualTo(TurnState.RUNNING);
        assertThat(running.plan()).contains(items);
        driver.complete("Finished");
    }

    @Test
    void persistsRootPlanProgressAtomicallyAndStartsTheNextTurnWithoutAPlan()
    {
        AgentTurn running = service.start(command("Implement and verify", "plan-1"));
        List<ProviderAgentEvent.PlanItem> initial = ImmutableList.of(new ProviderAgentEvent.PlanItem("Implement", ProviderAgentEvent.PlanItemState.IN_PROGRESS));
        ProviderAgentEventSource source = new ProviderAgentEventSource(
                Optional.of("root-thread"), Optional.empty(), Optional.of("/"), running.providerTurnReference().map(ProviderTurnRef::value), Optional.empty());
        ProviderAgentEvent.Plan update = new ProviderAgentEvent.Plan(Optional.of("plan"), initial, source);
        stores.failOutboxInsert(AgentEventPayload.Kind.PLAN_UPDATED, new AgentStorageException("blocked plan outbox"));
        assertThatThrownBy(() -> driver.emit(update)).isInstanceOf(AgentStorageException.class).hasMessage("blocked plan outbox");
        assertThat(stores.find(running.id()).orElseThrow().plan()).isEmpty();
        assertThat(events.readAfter(task.id(), 0, 100)).filteredOn(event -> event.type() == AgentEventPayload.Kind.PLAN_UPDATED).isEmpty();

        driver.emit(update);
        assertThat(stores.find(running.id()).orElseThrow().plan()).contains(initial);
        List<ProviderAgentEvent.PlanItem> completed = ImmutableList.of(new ProviderAgentEvent.PlanItem("Implement", ProviderAgentEvent.PlanItemState.COMPLETED));
        driver.emit(new ProviderAgentEvent.Plan(Optional.of("plan"), completed, source));
        assertThat(stores.find(running.id()).orElseThrow().plan()).contains(completed);
        driver.emit(new ProviderAgentEvent.Plan(Optional.of("plan"), ImmutableList.of(), source));
        assertThat(stores.find(running.id()).orElseThrow().plan()).contains(ImmutableList.of());
        assertThat(events.readAfter(task.id(), 0, 100)).filteredOn(event -> event.type() == AgentEventPayload.Kind.PLAN_UPDATED).hasSize(3);
        driver.complete("Finished");
        assertThat(stores.find(running.id()).orElseThrow().plan()).contains(ImmutableList.of());
        AgentTurn next = service.start(command("Next request", "plan-2"));
        assertThat(next.plan()).isEmpty();
        driver.complete("Finished");
    }

    @Test
    void childPlansRemainScopedEventsAndStaleRootPlansCannotOverwriteTheSnapshot()
    {
        AgentTurn running = service.start(command("Implement and verify", "plan-scope"));
        List<ProviderAgentEvent.PlanItem> rootItems = ImmutableList.of(new ProviderAgentEvent.PlanItem("Implement", ProviderAgentEvent.PlanItemState.IN_PROGRESS));
        // Claude does not provide a provider turn id; the sink remains bound to the accepted application turn.
        driver.emit(new ProviderAgentEvent.Plan(Optional.empty(), rootItems, ProviderAgentEventSource.root()));
        ProviderAgentEventSource child = new ProviderAgentEventSource(
                Optional.of("child-thread"), Optional.of("root-thread"), Optional.of("/reviewer"), Optional.of("child-turn"), Optional.empty());
        List<ProviderAgentEvent.PlanItem> childItems = ImmutableList.of(new ProviderAgentEvent.PlanItem("Review", ProviderAgentEvent.PlanItemState.PENDING));
        driver.emit(new ProviderAgentEvent.Plan(Optional.empty(), childItems, child));
        assertThat(stores.find(running.id()).orElseThrow().plan()).contains(rootItems);
        assertThat(events.readAfter(task.id(), 0, 100)).filteredOn(event -> event.type() == AgentEventPayload.Kind.PLAN_UPDATED)
                .anySatisfy(event -> assertThat(event.source().parentProviderThreadId()).contains("root-thread"));

        ProviderAgentEventSource stale = new ProviderAgentEventSource(Optional.of("root-thread"), Optional.empty(), Optional.of("/"), Optional.of("older-turn"), Optional.empty());
        assertThatThrownBy(() -> driver.emit(new ProviderAgentEvent.Plan(Optional.empty(), childItems, stale)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another provider turn");
        assertThat(stores.find(running.id()).orElseThrow().plan()).contains(rootItems);
        driver.complete("Finished");
        assertThat(stores.find(running.id()).orElseThrow().plan()).contains(rootItems);
    }

    @Test
    void commitsDevelopmentReadinessWithItsEventAndIgnoresAnOlderDuplicate()
    {
        AgentTask development = interactiveTask("Development");
        AgentTurn running = service.start(new StartAgentTurnCommand(development.id(), "Make the change", ImmutableList.of(), "development")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.DEFAULT)));
        DevelopmentResultReport question = new DevelopmentResultReport(false, Optional.of("Which implementation?"));
        ProviderAgentEvent.DevelopmentResult first = developmentReport(running, "report-1", question, Optional.empty());
        stores.failOutboxInsert(AgentEventPayload.Kind.DEVELOPMENT_RESULT, new AgentStorageException("blocked report outbox"));

        assertThatThrownBy(() -> driver.emit(first)).isInstanceOf(AgentStorageException.class).hasMessage("blocked report outbox");
        assertThat(stores.find(running.id()).orElseThrow().developmentResult()).isEmpty();
        assertThat(events.readAfter(development.id(), 0, 100)).filteredOn(event -> event.type() == AgentEventPayload.Kind.DEVELOPMENT_RESULT).isEmpty();

        driver.emit(first);
        driver.emit(first);
        assertThat(stores.find(running.id()).orElseThrow().developmentResult()).contains(question);
        assertThat(events.readAfter(development.id(), 0, 100)).filteredOn(event -> event.type() == AgentEventPayload.Kind.DEVELOPMENT_RESULT).hasSize(1);

        DevelopmentResultReport ready = new DevelopmentResultReport(true, Optional.empty());
        driver.emit(developmentReport(running, "report-2", ready, Optional.empty()));
        driver.emit(first);
        driver.complete("Finished");

        assertThat(stores.find(running.id()).orElseThrow().developmentResult()).contains(ready);
        assertThat(events.readAfter(development.id(), 0, 100)).filteredOn(event -> event.type() == AgentEventPayload.Kind.DEVELOPMENT_RESULT).hasSize(2);
    }

    @Test
    void rejectsDevelopmentReportsFromChildrenAndOtherProviderTurns()
    {
        AgentTask development = interactiveTask("Development");
        AgentTurn running = service.start(new StartAgentTurnCommand(development.id(), "Make the change", ImmutableList.of(), "development")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.DEFAULT)));
        DevelopmentResultReport ready = new DevelopmentResultReport(true, Optional.empty());
        ProviderAgentEvent.DevelopmentResult child = developmentReport(running, "child-report", ready, Optional.of("root-thread"));
        assertThatThrownBy(() -> driver.emit(child)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("root agent");

        ProviderAgentEvent.DevelopmentResult stale = new ProviderAgentEvent.DevelopmentResult(
                Optional.empty(),
                ready,
                new ProviderAgentEventSource(Optional.of("root-thread"), Optional.empty(), Optional.of("/"), Optional.of("older-turn"), Optional.of("report-1")));
        assertThatThrownBy(() -> driver.emit(stale)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another provider turn");
        assertThat(stores.find(running.id()).orElseThrow().developmentResult()).isEmpty();
        driver.complete("Finished");
    }

    @Test
    void ordinaryTurnsCannotAcquireReadinessFromProviderEvents()
    {
        AgentTurn running = service.start(command("Discuss the code", "ordinary"));
        ProviderAgentEvent.DevelopmentResult report = developmentReport(running, "report-1", new DevelopmentResultReport(true, Optional.empty()), Optional.empty());
        assertThatThrownBy(() -> driver.emit(report)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not enabled");
        assertThat(stores.find(running.id()).orElseThrow().developmentResult()).isEmpty();
        driver.complete("Finished");
    }

    @Test
    void keepsATrustedReadOnlyQuestionFromCommittingWhenStartedOrQueued()
    {
        AgentTask development = interactiveTask("Development");
        AgentTurn question = service.start(new StartAgentTurnCommand(development.id(), "Why this design?", ImmutableList.of(), "learning-question")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.LOCAL_ONLY)));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(development.id(), taskService.get(development.id()).version(),
                "And this test?", ImmutableList.of(), "learning-queue", Optional.of(DevelopmentAutomation.LOCAL_ONLY)));

        assertThat(question.settings().orElseThrow().developmentAutomation()).contains(DevelopmentAutomation.LOCAL_ONLY);
        assertThat(queued.settings().developmentAutomation()).contains(DevelopmentAutomation.LOCAL_ONLY);
        assertThat(driver.openedContexts().getFirst().managedInstructions())
                .contains("No automatic delivery is enabled for this turn").doesNotContain("Commit finished development work");
        driver.complete("Because");
        driver.complete("It covers the edge");
    }

    @Test
    void localOnlyInstructionsUseTheAcceptedSelectionWithoutRequiringAReportingTool()
    {
        AgentTask development = interactiveTask("Development");
        service.start(new StartAgentTurnCommand(development.id(), "Make the change", ImmutableList.of(), "development")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.LOCAL_ONLY)));

        assertThat(driver.openedContexts().getFirst().managedInstructions())
                .contains("No automatic delivery is enabled for this turn", "unless the user explicitly requests a commit, push, or PR update",
                        "does not change the task's development scope")
                .doesNotContain("report_development_result", "Private local review", "NEVER commit", "return to development");
        driver.complete("Finished");
    }

    @Test
    void localOnlyFeedbackDoesNotRestrictLaterDevelopmentOrQueuedPublication()
    {
        AgentTask development = interactiveTask("Development");
        service.start(new StartAgentTurnCommand(development.id(), "Explain this change", ImmutableList.of(), "feedback")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.LOCAL_ONLY)));
        driver.complete("Explained");
        Optional<AgentSessionRef> reference = taskService.get(development.id()).sessionReference();

        AgentTurn publish = service.start(new StartAgentTurnCommand(development.id(), "Push and update the PR", ImmutableList.of(), "publish"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(development.id(), taskService.get(development.id()).version(),
                "Update the PR description too", ImmutableList.of(), "publish-description"));

        assertThat(publish.settings().orElseThrow().developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);
        assertThat(queued.settings().developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);
        assertThat(driver.openedContexts().getLast().sessionReference()).isEqualTo(reference);
        assertThat(driver.openedContexts().getLast().managedInstructions())
                .contains("Commit finished development work: true").doesNotContain("Private local review", "NEVER commit", "return to development");
        reportReady(publish);
        driver.complete("Published");
        AgentTurn next = stores.findActive(development.id()).orElseThrow();
        assertThat(next.settings().orElseThrow().developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);
        reportReady(next);
        driver.complete("Description updated");
    }

    @Test
    void automationInstructionsExplainFixedSettingsWhenUserTextConflicts()
    {
        AgentTask development = interactiveTask("Development");
        service.start(new StartAgentTurnCommand(development.id(), "Make the change but skip CI", ImmutableList.of(), "development")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.DEFAULT)));

        assertThat(driver.openedContexts().getFirst().managedInstructions())
                .contains("Commit finished development work: true", "Auto draft PR: true", "Auto CI fix: true",
                        "accepted settings alone control subsequent delivery", "readyForDelivery=false",
                        "Preserve unfinished work and wait for the user's decision", "do not execute the conflicting step",
                        "review-before-publication hold does not prohibit authorized local commits", "Do not commit solely to enable review",
                        "Keep readyForDelivery=false until the publication hold is lifted",
                        "not a user instruction to skip later enabled steps")
                .doesNotContain("must change before resubmitting");
        driver.complete("Auto CI fix conflicts with skipping CI");
    }

    @Test
    void ownsDevelopmentAdmissionUntilSettledAndNeverDispatchesFromReplay()
    {
        List<DevelopmentTurnCompletion> completed = new ArrayList<>();
        enableDevelopment(completed::add);
        AgentTurn source = service.start(command("Change the code", "delivery-source"));
        reportReady(source);
        driver.complete("Done");

        assertThat(completed).hasSize(1);
        assertThat(completed.getFirst().providerQuiescent()).isTrue();
        assertThat(service.ownsDevelopmentContinuation(task.id(), source.id())).isTrue();
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
        assertThatThrownBy(() -> service.start(command("Another change", "delivery-race"))).isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "Later", ImmutableList.of(), "delivery-queue")))
                .isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> completionService().complete(new CompleteAgentTaskCommand(task.id(), Optional.empty()))).isInstanceOf(AgentConflictException.class);
        assertThat(service.start(command("Replay", "delivery-source")).id()).isEqualTo(source.id());
        service.get(source.id());
        assertThat(completed).hasSize(1);

        service.finishDevelopmentContinuation(task.id(), source.id());

        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isFalse();
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
        assertThat(events.readAfter(task.id(), 0, 100).getLast().payload())
                .isEqualTo(new AgentEventPayload.Lifecycle(AgentEventPayload.LifecyclePhase.DEVELOPMENT_AUTOMATION_UPDATED, "Development automation settled"));
        service.start(command("Local follow-up", "local-only").withDevelopmentAutomation(Optional.of(DevelopmentAutomation.LOCAL_ONLY.forDevelopment())));
        driver.complete("Done locally");
        assertThat(completed).hasSize(2);
        service.finishDevelopmentContinuation(task.id(), completed.getLast().turn().id());
    }

    @Test
    void capturesUserAndQueuedTurnsBeforeProviderStartWithoutRecapturingGeneratedTurns()
    {
        List<TurnId> captured = new ArrayList<>();
        developmentAccepted = (acceptedTask, turn) -> {
            awaitTaskLock(runtimeRegistry);
            assertThat(driver.commands()).hasSize(captured.size());
            assertThat(stores.find(turn.id())).contains(turn);
            assertThat(acceptedTask.worktreeMutationClaim()).contains(WorktreeMutationClaim.turn(turn.id(), turn.createdAt()));
            assertThat(acceptedTask.developmentContinuationUnfinished()).isTrue();
            assertThat(events.readAfter(task.id(), 0, 100)).filteredOn(event -> event.turnId().equals(Optional.of(turn.id())))
                    .extracting(AgentEventEnvelope::payload).hasAtLeastOneElementOfType(AgentEventPayload.UserMessage.class);
            captured.add(turn.id());
        };
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("First", "capture-source"));
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isFalse();
        service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "Second", ImmutableList.of(), "capture-queue"));
        assertThat(captured).containsExactly(source.id());
        reportReady(source);
        driver.complete("First done");

        assertThat(service.handoffDevelopmentContinuationToQueue(task.id(), source.id())).isTrue();
        AgentTurn queued = stores.findActive(task.id()).orElseThrow();
        assertThat(captured).containsExactly(source.id(), queued.id());
        reportReady(queued);
        driver.complete("Second done");
        service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, queued.id()), "Commit");
        reportReady(stores.findActive(task.id()).orElseThrow());
        driver.complete("Committed");

        assertThat(captured).containsExactly(source.id(), queued.id());
        assertThat(driver.commands()).hasSize(3);
        service.finishDevelopmentContinuation(task.id(), queued.id());
    }

    @ParameterizedTest
    @EnumSource(value = AgentTurnResult.State.class, names = {"FAILED", "INTERRUPTED", "CANCELLED"})
    void unsuccessfulDevelopmentTurnKeepsFilesAndHistoryAndAcceptsFreshWork(AgentTurnResult.State outcome)
            throws IOException
    {
        List<DevelopmentTurnCompletion> completed = new ArrayList<>();
        enableDevelopment(completed::add);
        Path file = Files.createDirectories(task.taskWorktree().orElseThrow().canonicalWorktree()).resolve("change.txt");
        Files.writeString(file, "Uncommitted work");
        worktrees.dirty = true;
        AgentTurn source = service.start(command("Change the code", "failed-development"));
        runtimeRegistry.running(source.id()).orElseThrow().completion().toCompletableFuture().complete(new AgentTurnResult(
                outcome, Optional.empty(), Optional.of("Request could not finish"), Duration.ZERO, true));

        AgentTask retained = service.failDevelopmentContinuation(task.id(), source.id(), "Request could not finish");

        assertThat(retained.state()).isEqualTo(TaskState.OPEN);
        assertThat(retained.developmentContinuationUnfinished()).isFalse();
        assertThat(retained.worktreeMutationClaim()).isEmpty();
        assertThat(retained.worktreeRemovedAt()).isEmpty();
        assertThat(Files.readString(file)).isEqualTo("Uncommitted work");
        assertThat(worktrees.cleanupCalls).isZero();
        assertThat(events.readAfter(task.id(), 0, 100)).extracting(AgentEventEnvelope::payload)
                .contains(new AgentEventPayload.Lifecycle(AgentEventPayload.LifecyclePhase.DEVELOPMENT_AUTOMATION_UPDATED, "Automation stopped. Request could not finish"));
        assertThat(service.start(command("Do not replay", "failed-development")).id()).isEqualTo(source.id());
        assertThat(completed).hasSize(1);
        assertThat(driver.commands()).hasSize(1);

        developmentAccepted = (acceptedTask, turn) -> assertThatThrownBy(() ->
                service.failDevelopmentContinuation(task.id(), source.id(), "Late failure from the old request"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("owner changed");
        AgentTurn next = service.start(command("Continue with these files", "fresh-development"));
        reportReady(next);
        driver.complete("Ready");

        assertThat(service.ownsDevelopmentContinuation(task.id(), next.id())).isTrue();
        assertThat(service.ownsDevelopmentContinuation(task.id(), source.id())).isFalse();
        assertThat(driver.openedContexts().getLast().worktree()).isEqualTo(task.taskWorktree().orElseThrow().canonicalWorktree());
        assertThat(events.readAfter(task.id(), 0, 100)).filteredOn(event -> event.payload() instanceof AgentEventPayload.UserMessage)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("Change the code", "Continue with these files");
        assertThat(Files.readString(file)).isEqualTo("Uncommitted work");
        service.finishDevelopmentContinuation(task.id(), next.id());
    }

    @Test
    void deliveryFailureAfterSuccessfulTurnDoesNotStartItsQueuedInput()
    {
        enableDevelopment(completion -> service.failDevelopmentContinuation(task.id(), completion.turn().id(), "Publication failed"));
        AgentTurn source = service.start(command("Change the code", "publication-failure"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(
                task.id(), taskService.get(task.id()).version(), "Queued follow-up", ImmutableList.of(), "paused-followup"));
        reportReady(source);

        driver.complete("Development done");

        assertThat(taskService.get(task.id()).state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.find(queued.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        assertThat(driver.commands()).hasSize(1);
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
        assertThat(worktrees.cleanupCalls).isZero();
        AgentTurn resumed = service.resumeQueued(task.id());
        developmentListener = Optional.of(ignored -> {});
        reportReady(resumed);
        driver.complete("Queued request finished");
        assertThat(service.ownsDevelopmentContinuation(task.id(), resumed.id())).isTrue();
        service.finishDevelopmentContinuation(task.id(), resumed.id());
    }

    @Test
    void aStopWaitingForAnotherShutdownCannotStopTheNextRequest()
            throws Exception
    {
        enableDevelopment(ignored -> {});
        AgentTurn first = service.start(command("First request", "first-before-stop"));
        String generation = service.stopImpact(task.id()).generation();
        CompletableFuture<Void> secondEntered = new CompletableFuture<>();
        CompletableFuture<AgentTask> secondStop;
        AgentTurn next;
        synchronized (runtimeRegistry.stopLock(task.id())) {
            secondStop = CompletableFuture.supplyAsync(() -> {
                secondEntered.complete(null);
                return service.stopRuntime(task.id(), generation);
            });
            secondEntered.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> secondStop.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            service.stopRuntime(task.id(), generation);
            next = service.start(command("Continue after Stop", "new-request-before-waiter"));
        }

        assertThatThrownBy(() -> secondStop.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(AgentConflictException.class).hasRootCauseMessage("Task runtime changed since the stop was confirmed: " + task.id());
        assertThat(service.get(first.id()).state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(service.get(next.id()).state()).isEqualTo(TurnState.RUNNING);
        assertThat(runtimeRegistry.session(task.id())).isPresent();
        reportReady(next);
        driver.complete("Done");
        assertThat(service.ownsDevelopmentContinuation(task.id(), next.id())).isTrue();
        service.finishDevelopmentContinuation(task.id(), next.id());
    }

    @Test
    void preparesReviewInputOnlyForTheProviderAfterAcceptance()
    {
        List<TurnId> prepared = new ArrayList<>();
        String reference = "\n<repository-context>Frozen reference ✓</repository-context>";
        reviewTurnInput = (acceptedTask, turn, message) -> {
            awaitTaskLock(runtimeRegistry);
            assertThat(turn.state()).isEqualTo(TurnState.ACCEPTED);
            assertThat(stores.find(turn.id())).contains(turn);
            assertThat(acceptedTask.worktreeMutationClaim()).contains(WorktreeMutationClaim.turn(turn.id(), turn.createdAt()));
            assertThat(driver.openedContexts()).isEmpty();
            assertThat(events.readAfter(task.id(), 0, 100))
                    .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                    .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                    .containsExactly(message);
            prepared.add(turn.id());
            return message + reference;
        };

        AgentTurn accepted = service.start(command("Review this change", "context-input"));

        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("Review this change" + reference);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("Review this change");
        assertThat(accepted.providerTurnReference()).isPresent();
        assertThat(stores.find(accepted.id()).orElseThrow().setup()).contains("deterministic-setup");  // what the provider session was set up with, kept with the turn
        assertThat(service.start(command("Ignored duplicate", "context-input")).id()).isEqualTo(accepted.id());
        assertThat(prepared).containsExactly(accepted.id());
        assertThat(driver.commands()).hasSize(1);
    }

    @Test
    void sendsTheRepositoryReferenceOnlyToTheProviderAndRecordsItOnTheTurn()
    {
        reviewTurnInput = new ReviewTurnInputResolver()
        {
            @Override
            public String prepare(AgentTask acceptedTask, AgentTurn turn, String message)
            {
                return message + "\n<review-reference/>";
            }

            @Override
            public Reference reference(AgentTask acceptedTask, AgentTurn turn)
            {
                return new Reference(ImmutableList.of("CONTRIBUTING.md"), ImmutableList.of(), "Repository guidelines: keep commits small.")
                        .plus(new Reference(ImmutableList.of(), ImmutableList.of(new TurnMemory("m12", "Document every new table property.")),
                                "Repository memory: [m12] Document every new table property."));
            }
        };

        AgentTurn accepted = service.start(command("Fix the flaky test", "guided-input"));

        // Guidelines, then memory, precede the review reference; the transcript keeps the user's own words.
        assertThat(driver.commands()).extracting(AgentTurnCommand::message)
                .containsExactly("Fix the flaky test\n\nRepository guidelines: keep commits small.\n\nRepository memory: [m12] Document every new table property."
                        + "\n<review-reference/>");
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("Fix the flaky test");
        assertThat(stores.find(accepted.id()).orElseThrow().guidelines()).containsExactly("CONTRIBUTING.md");
        assertThat(stores.find(accepted.id()).orElseThrow().memories()).containsExactly(new TurnMemory("m12", "Document every new table property."));
    }

    @Test
    void tellsTheResolverOnceATurnHasEndedWithItsEventsLogged()
    {
        List<AgentTurn> finished = new ArrayList<>();
        reviewTurnInput = new ReviewTurnInputResolver()
        {
            @Override
            public String prepare(AgentTask acceptedTask, AgentTurn turn, String message)
            {
                return message;
            }

            @Override
            public void finished(AgentTask endedTask, AgentTurn turn)
            {
                finished.add(turn);
            }
        };

        AgentTurn accepted = service.start(command("Fix the flaky test", "finished-input"));
        assertThat(finished).isEmpty();
        driver.complete("done");

        assertThat(finished).singleElement().satisfies(turn -> {
            assertThat(turn.id()).isEqualTo(accepted.id());
            assertThat(turn.state()).isEqualTo(TurnState.COMPLETED);
        });
    }

    @Test
    void reviewPreparationFailureSettlesAsKnownUnsentBeforeOpeningTheProvider()
    {
        reviewTurnInput = (acceptedTask, turn, message) -> {
            throw new AgentStorageException("Cannot persist required review reference");
        };

        assertThatThrownBy(() -> service.start(command("Review", "context-failure")))
                .isInstanceOf(AgentStorageException.class).hasMessageContaining("required review reference");

        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> {
            assertThat(turn.state()).isEqualTo(TurnState.FAILED);
            assertThat(turn.knownUnsent()).isTrue();
            assertThat(turn.startedAt()).isEmpty();
            assertThat(turn.providerTurnReference()).isEmpty();
        });
        assertThat(driver.commands()).isEmpty();
        assertThat(driver.openedContexts()).isEmpty();
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEmpty();
    }

    @Test
    void stopDuringReviewPreparationPreventsProviderLaunch()
    {
        reviewTurnInput = (acceptedTask, turn, message) -> {
            CompletableFuture.runAsync(() -> service.stopRuntime(acceptedTask.id(), "turn:" + turn.id())).orTimeout(5, TimeUnit.SECONDS).join();
            return message + "\nFrozen reference";
        };

        assertThatThrownBy(() -> service.start(command("Review", "context-stop"))).isInstanceOf(AgentConflictException.class);

        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> {
            assertThat(turn.state()).isEqualTo(TurnState.INTERRUPTED);
            assertThat(turn.startedAt()).isEmpty();
            assertThat(turn.providerTurnReference()).isEmpty();
        });
        assertThat(driver.commands()).isEmpty();
        assertThat(driver.openedContexts()).isEmpty();
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEmpty();
    }

    @Test
    void replacedTurnCannotLaunchItsPreparedReviewInput()
    {
        AtomicBoolean replace = new AtomicBoolean(true);
        reviewTurnInput = (acceptedTask, turn, message) -> {
            if (replace.getAndSet(false)) {
                CompletableFuture.runAsync(() -> {
                    service.stopRuntime(acceptedTask.id(), "turn:" + turn.id());
                    service.start(command("Replacement", "context-replacement"));
                }).orTimeout(5, TimeUnit.SECONDS).join();
            }
            return message + "\nFrozen reference";
        };

        assertThatThrownBy(() -> service.start(command("Original", "context-original"))).isInstanceOf(AgentConflictException.class);

        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("Replacement\nFrozen reference");
        assertThat(stores.findByIdempotencyKey(task.id(), "context-original").orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.findByIdempotencyKey(task.id(), "context-replacement").orElseThrow().state()).isEqualTo(TurnState.RUNNING);
    }

    @Test
    void preparesQueuedAndResumedReviewInputsWhenTheirTurnsActuallyStart()
    {
        List<TurnId> prepared = new ArrayList<>();
        reviewTurnInput = (acceptedTask, turn, message) -> {
            prepared.add(turn.id());
            return message + "\nReference for accepted turn " + turn.id();
        };
        AgentTurn first = service.start(command("First", "context-first"));
        service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "Second", ImmutableList.of(), "context-second"));
        QueuedAgentInput third = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "Third", ImmutableList.of(), "context-third"));
        assertThat(prepared).containsExactly(first.id());

        driver.complete("First finished");
        AgentTurn second = stores.findActive(task.id()).orElseThrow();
        assertThat(prepared).containsExactly(first.id(), second.id());
        service.cancel(second.id());
        assertThat(stores.find(third.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        assertThat(prepared).containsExactly(first.id(), second.id());

        AgentTurn resumed = service.resumeQueued(task.id());

        assertThat(prepared).containsExactly(first.id(), second.id(), resumed.id());
        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly(
                "First\nReference for accepted turn " + first.id(),
                "Second\nReference for accepted turn " + second.id(),
                "Third\nReference for accepted turn " + resumed.id());
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("First", "Second", "Third");
    }

    @Test
    void baselineCaptureFailureSettlesTheAcceptedTurnWithoutStartingTheProvider()
    {
        List<DevelopmentTurnCompletion> completed = new ArrayList<>();
        developmentAccepted = (acceptedTask, turn) -> {
            throw new AgentStorageException("Cannot inspect accepted Git baseline");
        };
        enableDevelopment(completed::add);

        assertThatThrownBy(() -> service.start(command("Change", "capture-failure")))
                .isInstanceOf(AgentStorageException.class).hasMessageContaining("Git baseline");

        AgentTurn failed = stores.list(task.id()).getLast();
        assertThat(failed.state()).isEqualTo(TurnState.FAILED);
        assertThat(failed.knownUnsent()).isTrue();
        assertThat(failed.startedAt()).isEmpty();
        assertThat(driver.commands()).isEmpty();
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEmpty();
        assertThat(completed).hasSize(1);
        service.failDevelopmentContinuation(task.id(), failed.id(), "Capture failed");
    }

    @Test
    void stopDuringBaselineCaptureCannotLaunchTheAcceptedTurnAfterward()
    {
        developmentAccepted = (acceptedTask, turn) -> CompletableFuture.runAsync(() ->
                service.stopRuntime(acceptedTask.id(), "turn:" + turn.id())).orTimeout(5, TimeUnit.SECONDS).join();
        enableDevelopment(ignored -> {});

        assertThatThrownBy(() -> service.start(command("Change", "capture-stop"))).isInstanceOf(AgentConflictException.class);

        assertThat(taskService.get(task.id()).state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.list(task.id()).getLast().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(driver.commands()).isEmpty();
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
    }

    @Test
    void shutdownDuringBaselineCapturePreservesTheAcceptedClaimWithoutLaunching()
    {
        developmentAccepted = (acceptedTask, turn) -> runtimeRegistry.close();
        enableDevelopment(ignored -> {});

        assertThatThrownBy(() -> service.start(command("Change", "capture-shutdown")))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("shutting down");

        assertThat(stores.list(task.id()).getLast().state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isPresent();
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
        assertThat(driver.commands()).isEmpty();
    }

    @Test
    void nativeCompletionOwnsWorkEvenWhenAReaderPersistsItsTerminalResultFirst()
    {
        List<DevelopmentTurnCompletion> completed = new ArrayList<>();
        enableDevelopment(completed::add);
        AgentTurn source = service.start(command("Change", "reader-race"));
        reportReady(source);
        CompletionStage<?> reader = runtimeRegistry.running(source.id()).orElseThrow().completion().whenComplete((result, failure) -> {
            assertThat(service.get(source.id()).state()).isEqualTo(TurnState.COMPLETED);
            assertThat(completed).isEmpty();
            assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
            assertThatThrownBy(() -> service.start(command("Racing Send", "reader-send"))).isInstanceOf(AgentConflictException.class);
        });

        driver.complete("Done");
        reader.toCompletableFuture().join();

        assertThat(completed).hasSize(1);
        assertThat(service.ownsDevelopmentContinuation(task.id(), source.id())).isTrue();
        service.finishDevelopmentContinuation(task.id(), source.id());
    }

    @Test
    void generatedTurnsPrepareReferencesOutsideTheLockWithoutRecapturingTheDevelopmentBaseline()
    {
        List<TurnId> baselines = new ArrayList<>();
        developmentAccepted = (acceptedTask, turn) -> baselines.add(turn.id());
        reviewTurnInput = new ReviewTurnInputResolver()
        {
            @Override
            public String prepare(AgentTask acceptedTask, AgentTurn turn, String message)
            {
                assertThat(Thread.holdsLock(runtimeRegistry.taskLock(acceptedTask.id()))).isFalse();
                return message;
            }

            @Override
            public Reference reference(AgentTask acceptedTask, AgentTurn turn)
            {
                assertThat(Thread.holdsLock(runtimeRegistry.taskLock(acceptedTask.id()))).isFalse();
                return new Reference(ImmutableList.of("AGENTS.md"), ImmutableList.of(), "Guidelines for " + turn.id());
            }
        };
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("Change", "shared-reference"));
        reportReady(source);
        driver.complete("Done");
        CompletionStage<DevelopmentTurnCompletion> completion = service.startGeneratedTurn(
                task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()), "Commit");
        AgentTurn generated = stores.findActive(task.id()).orElseThrow();

        assertThat(driver.commands().getLast().message()).isEqualTo("Commit\n\nGuidelines for " + generated.id());
        assertThat(generated.guidelines()).containsExactly("AGENTS.md");
        assertThat(baselines).containsExactly(source.id());
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
        assertThat(service.ownsDevelopmentContinuation(task.id(), source.id())).isTrue();
        driver.complete("Committed");
        assertThat(completion.toCompletableFuture().join().providerQuiescent()).isTrue();
        service.finishDevelopmentContinuation(task.id(), source.id());
    }

    @Test
    void stoppingDuringGeneratedInputPreparationCannotLaunchTheTurnAfterward()
    {
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("Change", "generated-stop"));
        reportReady(source);
        driver.complete("Done");
        reviewTurnInput = (acceptedTask, turn, message) -> {
            CompletableFuture.runAsync(() -> service.stopRuntime(acceptedTask.id(), service.stopImpact(acceptedTask.id()).generation()))
                    .orTimeout(5, TimeUnit.SECONDS).join();
            return message;
        };

        service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()), "Commit");

        assertThat(stores.list(task.id())).filteredOn(turn -> turn.developmentOrigin().isPresent())
                .singleElement().satisfies(turn -> assertThat(turn.state()).isEqualTo(TurnState.INTERRUPTED));
        assertThat(driver.commands()).hasSize(1);
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
    }

    @Test
    void generatedTurnsKeepTheWholeSourceSnapshotAndTheirQuestionBlocksFurtherWork()
    {
        List<DevelopmentTurnCompletion> completed = new ArrayList<>();
        enableDevelopment(completed::add);
        AgentTurn source = service.start(command("Change", "generated-source"));
        reportReady(source);
        driver.complete("Done");
        AgentTask current = taskService.get(task.id());
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(task.id(), current.version(),
                new AgentTaskDefaults(Optional.of("model-b"), Optional.of("high"), AgentPermissionPolicy.AUTO, Optional.empty(), Optional.of(DevelopmentAutomation.LOCAL_ONLY))));
        DevelopmentTurnOrigin origin = new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id());

        CompletionStage<DevelopmentTurnCompletion> generated = service.startGeneratedTurn(task.id(), origin, "Commit the finished changes");
        assertThat(service.startGeneratedTurn(task.id(), origin, "Duplicate request")).isSameAs(generated);
        AgentTurn followup = stores.findActive(task.id()).orElseThrow();
        assertThat(followup.settings()).isEqualTo(source.settings());
        assertThat(followup.developmentOrigin()).contains(origin);
        assertThat(driver.commands().getLast().model()).isEqualTo(source.settings().orElseThrow().model());
        assertThat(driver.commands().getLast().effort()).isEqualTo(source.settings().orElseThrow().effort());
        assertThat(driver.openedContexts().getLast().permissionPolicy()).isEqualTo(source.settings().orElseThrow().permissionPolicy());
        driver.emit(developmentReport(followup, "commit-question", new DevelopmentResultReport(false, Optional.of("Which change should be committed?")), Optional.empty()));
        driver.complete("Choose a change");

        assertThat(generated.toCompletableFuture().join().turn().developmentResult().orElseThrow().readyForDelivery()).isFalse();
        assertThat(completed).hasSize(1);
        assertThatThrownBy(() -> service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR, source.id()), "Publish"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user answer");
        service.finishDevelopmentContinuation(task.id(), source.id());
    }

    @Test
    void refinementIgnoresDeliveryReadinessAndNeverRecursivelyStartsAContinuation()
    {
        List<DevelopmentTurnCompletion> completed = new ArrayList<>();
        enableDevelopment(completed::add);
        AgentTurn source = service.start(command("Change locally", "summary-source")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.LOCAL_ONLY.forDevelopment())));
        driver.complete("Local edits complete");
        assertThat(completed).hasSize(1);
        DevelopmentTurnOrigin origin = new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.REFINE_SUMMARY, source.id());
        CompletionStage<DevelopmentTurnCompletion> refinement = service.startGeneratedTurn(task.id(), origin, "Refine the private summary");
        assertThat(stores.findActive(task.id()).orElseThrow().settings()).isEqualTo(source.settings());
        reportReady(stores.findActive(task.id()).orElseThrow());
        driver.complete("Summary recorded");
        assertThat(refinement.toCompletableFuture().join().providerQuiescent()).isTrue();
        assertThat(completed).hasSize(1);
        assertThatThrownBy(() -> service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()), "Commit"))
                .isInstanceOf(IllegalArgumentException.class);
        service.finishDevelopmentContinuation(task.id(), source.id());
    }

    @Test
    void refinementAndLocalQuestionsPreserveHeldDelivery()
    {
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("Change but ask first", "held-source"));
        driver.emit(developmentReport(source, "held", new DevelopmentResultReport(false, Optional.of("Which implementation?")), Optional.empty()));
        driver.complete("Please choose");
        service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.REFINE_SUMMARY, source.id()), "Refine summary");
        reportReady(stores.findActive(task.id()).orElseThrow());
        driver.complete("Summary recorded");
        assertThatThrownBy(() -> service.handoffDevelopmentContinuationToQueue(task.id(), source.id()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user answer");
        service.finishDevelopmentContinuation(task.id(), source.id());

        AgentTurn question = service.start(command("Explain this card", "local-question")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.LOCAL_ONLY)));
        driver.complete("Explanation");
        // A read-only question is no development turn, so the delivery stays held for the user's answer.
        assertThat(service.ownsDevelopmentContinuation(task.id(), question.id())).isFalse();
        assertThatThrownBy(() -> service.resumeQueued(task.id()))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Answer the development question");
    }

    @Test
    void stopDiscardsCompletedSummaryBeforeReleasingItsVerificationMarker()
    {
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("Change locally", "stop-summary-source")
                .withDevelopmentAutomation(Optional.of(DevelopmentAutomation.LOCAL_ONLY.forDevelopment())));
        driver.complete("Done");
        service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.REFINE_SUMMARY, source.id()), "Refine summary");
        AgentTurn refinement = stores.findActive(task.id()).orElseThrow();
        var summary = new DevelopmentLearningSummary("Purpose", ImmutableList.of(), Optional.empty(), "Tests were inspected", ImmutableList.of());
        driver.emit(developmentReport(refinement, "summary", new DevelopmentResultReport(true, Optional.empty(), Optional.of(summary)), Optional.empty()));
        driver.complete("Summary recorded");

        service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());

        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isFalse();
        assertThat(service.get(refinement.id()).developmentResult().orElseThrow().learningSummary()).isEmpty();
    }

    @Test
    void generatedReadinessCannotEnableDraftPublicationDisabledAtAcceptance()
    {
        enableDevelopment(ignored -> {});
        task = taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(task.id(), task.version(),
                task.defaults().withDevelopmentAutomation(new DevelopmentAutomation(true, false, false, Optional.empty()))));
        AgentTurn source = service.start(command("Commit only", "limited-source"));
        reportReady(source);
        driver.complete("Done");
        CompletionStage<DevelopmentTurnCompletion> generated = service.startGeneratedTurn(
                task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()), "Commit");
        reportReady(stores.findActive(task.id()).orElseThrow());
        driver.complete("Committed");
        generated.toCompletableFuture().join();

        assertThatThrownBy(() -> service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR, source.id()), "Publish"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accepted automation settings");
        service.finishDevelopmentContinuation(task.id(), source.id());
    }

    @Test
    void readinessAndUpdatedDefaultsCannotEnableAnOperationDisabledAtAcceptance()
    {
        enableDevelopment(ignored -> {});
        DevelopmentAutomation commitOnly = new DevelopmentAutomation(true, false, false, Optional.empty());
        task = taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(task.id(), task.version(), task.defaults().withDevelopmentAutomation(commitOnly)));
        AgentTurn source = service.start(command("Commit my changes locally", "disabled-publish-source"));
        reportReady(source);
        driver.complete("Ready to publish");
        AgentTask current = taskService.get(task.id());
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(task.id(), current.version(), current.defaults().withDevelopmentAutomation(DevelopmentAutomation.DEFAULT)));

        assertThatThrownBy(() -> service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR, source.id()), "Publish"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accepted automation settings");

        assertThat(stores.list(task.id())).extracting(AgentTurn::id).containsExactly(source.id());
        assertThat(driver.commands()).hasSize(1);
        service.finishDevelopmentContinuation(task.id(), source.id());
    }

    @Test
    void aFailedQueuedLaunchOwnsItsFailureAfterAtomicHandoff()
    {
        List<DevelopmentTurnCompletion> completed = new ArrayList<>();
        enableDevelopment(completed::add);
        AgentTurn source = service.start(command("First", "handoff-source"));
        service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "Queued", ImmutableList.of(), "handoff-queue"));
        reportReady(source);
        driver.complete("Done");
        driver.failNextStart();

        assertThatThrownBy(() -> service.handoffDevelopmentContinuationToQueue(task.id(), source.id())).isInstanceOf(IllegalStateException.class);

        assertThat(completed).hasSize(2);
        AgentTurn failed = completed.getLast().turn();
        assertThat(failed.id()).isNotEqualTo(source.id());
        assertThat(failed.state()).isEqualTo(TurnState.FAILED);
        assertThat(failed.knownUnsent()).isTrue();
        assertThat(service.ownsDevelopmentContinuation(task.id(), failed.id())).isTrue();
        assertThat(service.ownsDevelopmentContinuation(task.id(), source.id())).isFalse();
        assertThat(service.failDevelopmentContinuation(task.id(), failed.id(), "Queued development failed").state()).isEqualTo(TaskState.OPEN);
    }

    @Test
    void terminalPersistenceFailureStopsAutomationAndPreservesTheOpenTask()
    {
        List<DevelopmentTurnCompletion> completed = new ArrayList<>();
        enableDevelopment(completed::add);
        AgentTurn source = service.start(command("Change", "storage-source"));
        reportReady(source);
        stores.failOutboxInsert(AgentEventPayload.Kind.LIFECYCLE, new AgentStorageException("blocked terminal outbox"));

        driver.complete("Done");

        assertThat(completed).isEmpty();
        assertThat(taskService.get(task.id()).state()).isEqualTo(TaskState.OPEN);
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isFalse();
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
    }

    @Test
    void stopStillClosesTheProviderWhenApprovalCleanupFails()
    {
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("Change", "stop-source"));
        approvals.failNextDeny(new AgentStorageException("approval cleanup failed"));

        assertThatThrownBy(() -> service.stopRuntime(task.id(), "turn:" + source.id())).isInstanceOf(AgentStorageException.class);

        assertThat(runtimeRegistry.session(task.id())).isEmpty();
        assertThat(stores.find(source.id()).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
        assertThat(service.stopRuntime(task.id(), service.stopImpact(task.id()).generation()).state()).isEqualTo(TaskState.OPEN);
    }

    @Test
    void stopsChildrenWithoutAuthorityBeforeReleasingParentOwnership()
    {
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("Change", "child-stop-source"));
        reportReady(source);
        driver.complete("Done");
        CompletionStage<DevelopmentTurnCompletion> generated = service.startGeneratedTurn(
                task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()), "Commit");
        stopChildren = stoppedTask -> {
            awaitTaskLock(runtimeRegistry);
            assertThat(stoppedTask).isEqualTo(task.id());
            assertThat(service.ownsDevelopmentContinuation(task.id(), source.id())).isFalse();
            assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isTrue();
            assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
            assertThat(runtimeRegistry.session(task.id())).isPresent();
            assertThatThrownBy(() -> completionService().delete(task.id())).isInstanceOf(AgentConflictException.class);
        };

        AgentTask stopped = service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());

        assertThat(stopped.state()).isEqualTo(TaskState.OPEN);
        assertThat(stopped.developmentContinuationUnfinished()).isFalse();
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
        assertThat(runtimeRegistry.session(task.id())).isEmpty();
        assertThat(generated.toCompletableFuture()).isCompletedExceptionally();
        assertThat(worktrees.cleanupCalls).isZero();
        stopChildren = ignored -> {};
        AgentTurn next = service.start(command("Continue after Stop", "after-stop"));
        reportReady(next);
        driver.complete("Done");
        assertThat(service.ownsDevelopmentContinuation(task.id(), next.id())).isTrue();
        service.finishDevelopmentContinuation(task.id(), next.id());
    }

    @Test
    void childAndSourceStopFailuresKeepTheParentExcludedUntilBothCanStop()
    {
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("Change", "child-stop-failure"));
        reportReady(source);
        driver.complete("Done");
        service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()), "Commit");
        AgentStorageException childFailure = new AgentStorageException("Child writer could not be verified stopped");
        stopChildren = ignored -> {
            throw childFailure;
        };
        driver.failNextCloseVerification();

        assertThatThrownBy(() -> service.failDevelopmentContinuation(task.id(), source.id(), "Stop failed"))
                .isSameAs(childFailure).satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));

        assertThat(runtimeRegistry.unverifiedClose(task.id())).isPresent();
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isPresent();
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isTrue();
        assertThat(service.ownsDevelopmentContinuation(task.id(), source.id())).isFalse();
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
        assertThatThrownBy(() -> service.start(command("Competing change", "blocked-source"))).isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> completionService().delete(task.id())).isInstanceOf(AgentConflictException.class);

        assertThatThrownBy(() -> service.stopRuntime(task.id(), service.stopImpact(task.id()).generation())).isSameAs(childFailure);
        assertThat(runtimeRegistry.session(task.id())).isEmpty();
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isTrue();
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
        stopChildren = ignored -> {};
        assertThat(service.stopRuntime(task.id(), service.stopImpact(task.id()).generation()).state()).isEqualTo(TaskState.OPEN);
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
    }

    @Test
    void lostContinuationStopsChildrenEvenWhenItsSourceRecoveryClaimRemains()
    {
        enableDevelopment(ignored -> {});
        WorktreeMutationClaim recovery = WorktreeMutationClaim.recovery("lost-source", NOW);
        task = stores.update(task.withDevelopmentContinuation(true, NOW).claimWorktreeMutation(recovery, NOW));
        List<TaskId> stoppedChildren = new ArrayList<>();
        stopChildren = stoppedTask -> {
            awaitTaskLock(runtimeRegistry);
            stoppedChildren.add(stoppedTask);
        };

        AgentTask retained = service.failLostDevelopmentContinuation(task.id());

        assertThat(stoppedChildren).containsExactly(task.id());
        assertThat(retained.state()).isEqualTo(TaskState.OPEN);
        assertThat(retained.worktreeMutationClaim()).contains(recovery);
        assertThat(retained.developmentContinuationUnfinished()).isTrue();
        stores.update(retained.releaseWorktreeMutation(recovery, NOW));
        stopChildren = ignored -> {
            throw new AgentStorageException("Child is still unverified");
        };
        assertThatThrownBy(() -> service.failLostDevelopmentContinuation(task.id())).hasMessageContaining("Child is still unverified");
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
        stopChildren = stoppedChildren::add;
        AgentTask stopped = service.failLostDevelopmentContinuation(task.id());
        assertThat(stopped.state()).isEqualTo(TaskState.OPEN);
        assertThat(stopped.developmentContinuationUnfinished()).isFalse();
        assertThat(stoppedChildren).containsExactly(task.id(), task.id());
        assertThat(worktrees.cleanupCalls).isZero();
        AgentTurn next = service.start(command("Continue after restart", "after-lost-continuation"));
        reportReady(next);
        driver.complete("Done");
        service.finishDevelopmentContinuation(task.id(), next.id());
    }

    @Test
    void sharedBranchAdmissionBlocksSourceStartCompleteAndDelete()
    {
        runtimeRegistry = new AgentRuntimeRegistry(stores, candidate -> {
            assertThat(Thread.holdsLock(runtimeRegistry.taskLock(candidate.id()))).isTrue();
            throw new AgentConflictException("CI owns the source branch");
        });
        service = service(driver, runtimeRegistry);

        assertThatThrownBy(() -> service.start(command("Change", "ci-reserved-start"))).hasMessageContaining("CI owns");
        assertThatThrownBy(() -> completionService().complete(new CompleteAgentTaskCommand(task.id(), Optional.empty()))).hasMessageContaining("CI owns");
        assertThatThrownBy(() -> completionService().delete(task.id())).hasMessageContaining("CI owns");

        assertThat(stores.list(task.id())).isEmpty();
        assertThat(driver.commands()).isEmpty();
        assertThat(taskService.get(task.id()).worktreeMutationClaim()).isEmpty();
    }

    @Test
    void rechecksSharedBranchAdmissionAfterCapturingTheAcceptedBaseline()
    {
        AtomicBoolean reserved = new AtomicBoolean();
        runtimeRegistry = new AgentRuntimeRegistry(stores, candidate -> {
            assertThat(Thread.holdsLock(runtimeRegistry.taskLock(candidate.id()))).isTrue();
            if (reserved.get()) {
                throw new AgentConflictException("CI owns the source branch");
            }
        });
        enableDevelopment(ignored -> {});
        developmentAccepted = (acceptedTask, turn) -> reserved.set(true);

        assertThatThrownBy(() -> service.start(command("Change", "ci-reserved-after-capture"))).hasMessageContaining("CI owns");

        assertThat(driver.commands()).isEmpty();
        assertThat(stores.list(task.id()).getLast().knownUnsent()).isTrue();
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
        service.failDevelopmentContinuation(task.id(), stores.list(task.id()).getLast().id(), "CI admission changed");
    }

    @Test
    void shutdownRevokesAContinuationWithoutClearingItsUnfinishedMarker()
    {
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("Change", "shutdown-source"));
        reportReady(source);
        driver.complete("Done");

        runtimeRegistry.close();

        assertThat(service.ownsDevelopmentContinuation(task.id(), source.id())).isFalse();
        assertThat(taskService.get(task.id()).developmentContinuationUnfinished()).isTrue();
        assertThatThrownBy(() -> service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()), "Commit"))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void successfulProviderCompletionAfterCancellationCannotAuthorizeGeneratedWork()
    {
        enableDevelopment(ignored -> {});
        driver.beforeCancel(() -> driver.complete("Completed while cancellation was arriving"));
        AgentTurn source = service.start(command("Change", "cancel-success"));
        reportReady(source);

        assertThat(service.cancel(source.id()).state()).isEqualTo(TurnState.COMPLETED);

        assertThatThrownBy(() -> service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()), "Commit"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cancelled");
        service.failDevelopmentContinuation(task.id(), source.id(), "Cancelled");
    }

    @Test
    void cancellingACompletedGeneratedTurnRevokesItsLiveContinuation()
    {
        enableDevelopment(ignored -> {});
        AgentTurn source = service.start(command("Change", "cancel-generated"));
        reportReady(source);
        driver.complete("Done");
        service.startGeneratedTurn(task.id(), new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, source.id()), "Commit");
        AgentTurn generated = stores.findActive(task.id()).orElseThrow();
        reportReady(generated);
        driver.complete("Committed");

        assertThat(service.cancel(generated.id()).state()).isEqualTo(TurnState.COMPLETED);

        assertThat(taskService.get(task.id()).state()).isEqualTo(TaskState.OPEN);
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
        assertThat(runtimeRegistry.session(task.id())).isEmpty();
    }

    @Test
    void cancellationThatSettlesTheProviderResultPreventsItsPendingLiveCallback()
    {
        List<DevelopmentTurnCompletion> completed = new ArrayList<>();
        enableDevelopment(completed::add);
        AgentTurn source = service.start(command("Change", "cancel-before-native-callback"));
        reportReady(source);
        CompletionStage<?> cancellation = runtimeRegistry.running(source.id()).orElseThrow().completion().whenComplete((result, failure) -> service.cancel(source.id()));

        driver.complete("Finished before cancellation reached the provider");
        cancellation.toCompletableFuture().join();

        assertThat(completed).isEmpty();
        assertThat(taskService.get(task.id()).state()).isEqualTo(TaskState.OPEN);
        assertThat(runtimeRegistry.hasDevelopmentOwner(task.id())).isFalse();
    }

    @Test
    void cancellingAnOldCompletedTurnDoesNotRevokeANewerContinuation()
    {
        enableDevelopment(ignored -> {});
        AgentTurn old = service.start(command("First", "cancel-old"));
        reportReady(old);
        driver.complete("Done");
        service.finishDevelopmentContinuation(task.id(), old.id());
        AgentTurn current = service.start(command("Second", "cancel-current"));
        reportReady(current);
        driver.complete("Done again");

        service.cancel(old.id());

        assertThat(service.ownsDevelopmentContinuation(task.id(), current.id())).isTrue();
        assertThat(taskService.get(task.id()).state()).isEqualTo(TaskState.OPEN);
        service.finishDevelopmentContinuation(task.id(), current.id());
    }

    private void enableDevelopment(Consumer<DevelopmentTurnCompletion> listener)
    {
        developmentListener = Optional.of(listener);
        service = service(driver, runtimeRegistry);
        AgentTask interactive = interactiveTask("Development");
        task = taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(interactive.id(), interactive.version(),
                interactive.defaults().withDevelopmentAutomation(DevelopmentAutomation.DEFAULT)));
    }

    private void reportReady(AgentTurn turn)
    {
        driver.emit(developmentReport(turn, "ready-" + turn.id(), new DevelopmentResultReport(true, Optional.empty()), Optional.empty()));
    }

    private static ProviderAgentEvent.DevelopmentResult developmentReport(AgentTurn turn, String itemId, DevelopmentResultReport report, Optional<String> parentThreadId)
    {
        return new ProviderAgentEvent.DevelopmentResult(Optional.empty(), report, new ProviderAgentEventSource(
                Optional.of("root-thread"), parentThreadId, Optional.of("/"), turn.providerTurnReference().map(ProviderTurnRef::value), Optional.of(itemId)));
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
    void settlesATurnThatFailedBeforeReachingTheProvider()
    {
        driver.failNextStart();
        assertThatThrownBy(() -> service.start(command("first", "key-1"))).isInstanceOf(IllegalStateException.class);

        // The provider refused the start, so the turn is known unsent and its stopped process releases the task.
        AgentTurn failed = stores.list(task.id()).getFirst();
        assertThat(failed.state()).isEqualTo(TurnState.FAILED);
        assertThat(failed.knownUnsent()).isTrue();
        assertThat(failed.startedAt()).isPresent();
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
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
        ProviderUsageSample sample = new ProviderUsageSample(Optional.empty(), ProviderUsageSample.Semantics.DELTA, 4, 1, 2, Optional.empty());

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
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
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
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
        service = service(driver, runtimeRegistry);
        AgentTurn running = service.start(command("first", "key-1"));
        AgentTask taskBefore = stores.find(task.id()).orElseThrow();
        AgentTurn turnBefore = stores.find(running.id()).orElseThrow();
        stores.failOutboxInsert(AgentEventPayload.Kind.LIFECYCLE, new AgentStorageException("blocked terminal outbox"));

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
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
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
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
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
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
        metrics = new DefaultAgentMetricsService(stores, stores, logs, clock);
        service = service(driver, runtimeRegistry, events, approvals, clock);
        AgentTurn running = service.start(command("first", "key-1"));

        driver.complete("done");

        assertThat(service.get(running.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(runtimeRegistry.running(running.id())).isEmpty();
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
    }

    @Test
    void retainsTheClaimForAnExceptionalProviderCompletionUntilExplicitStop()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
        service = service(driver, runtimeRegistry);
        AgentTurn running = service.start(command("first", "key-1"));

        driver.failCompletion(new AgentStorageException("provider future failed"));

        assertThat(service.get(running.id())).satisfies(turn -> {
            assertThat(turn.state()).isEqualTo(TurnState.INTERRUPTED);
            assertThat(turn.terminalReason()).contains("provider future failed");
        });
        assertThat(runtimeRegistry.running(running.id())).isEmpty();
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isPresent();
        assertThatThrownBy(() -> service.start(command("second", "key-2")))
                .isInstanceOf(AgentConflictException.class);

        AgentTask stopped = service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());
        assertThat(stopped.worktreeMutationClaim()).isEmpty();
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
            assertThat(events.readAfter(task.id(), 0, 100))
                    .filteredOn(event -> event.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                            && lifecycle.phase() == AgentEventPayload.LifecyclePhase.TURN_STARTED)
                    .hasSize(1);
        });

        AgentTurn turn = service.start(command("first", "key-1"));

        assertThat(turn.state()).isEqualTo(TurnState.RUNNING);
        assertThat(turn.startedAt()).isPresent();
        assertThat(turn.providerTurnReference()).isPresent();
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.TURN_STARTED)
                .hasSize(1);
        driver.complete("done");
        assertThat(service.get(turn.id()).state()).isEqualTo(TurnState.COMPLETED);
    }

    @Test
    void doesNotStartProviderWhenUserMessageCannotDrain()
    {
        service = service(driver, new AgentRuntimeRegistry(stores, ignored -> {}), new FailOnceDrainEventService(events));

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
        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> assertThat(turn.startedAt()).isEmpty());
    }

    @Test
    void retainsTheClaimWhenAKnownUnsentSessionCannotBeVerifiedClosed()
    {
        FailLifecycleDrainEventService failingEvents = new FailLifecycleDrainEventService(events, stores, AgentEventPayload.LifecyclePhase.TURN_STARTED);
        service = service(driver, runtimeRegistry, failingEvents);
        driver.failNextCloseVerification();

        assertThatThrownBy(() -> service.start(command("first", "key-1")))
                .isInstanceOf(AgentStorageException.class)
                .hasMessage("blocked lifecycle event")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .singleElement()
                        .satisfies(suppressed -> {
                            assertThat(suppressed).isInstanceOf(AgentUnavailableException.class);
                            assertThat(suppressed.getMessage())
                                    .isEqualTo("deterministic process tree could not be verified stopped");
                        }));

        assertThat(driver.commands()).isEmpty();
        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> {
            assertThat(turn.state()).isEqualTo(TurnState.FAILED);
            assertThat(turn.knownUnsent()).isTrue();
        });
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isPresent();
        assertThat(runtimeRegistry.session(task.id())).isPresent();
        assertThat(runtimeRegistry.unverifiedClose(task.id())).isPresent();
        assertThatThrownBy(() -> service.start(command("do not overlap", "key-2")))
                .isInstanceOf(AgentConflictException.class);

        AgentTask stopped = service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());

        assertThat(stopped.worktreeMutationClaim()).isEmpty();
        assertThat(runtimeRegistry.session(task.id())).isEmpty();
        AgentTurn next = service.start(command("second", "key-2"));
        driver.complete("done");
        assertThat(service.get(next.id()).state()).isEqualTo(TurnState.COMPLETED);
    }

    @Test
    void drainsPendingUserMessageBeforeReturningIdempotentTurnOrStartingAnother()
    {
        service = service(driver, new AgentRuntimeRegistry(stores, ignored -> {}), new FailOnceDrainEventService(events));

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
    void aRestrictedAccountCannotResumeAnExistingTaskInAnotherRepository()
    {
        service.start(command("first", "key-1"));
        driver.complete("done");
        AgentDriverRegistry registry = mock(AgentDriverRegistry.class);
        when(registry.require(driver.id())).thenReturn(driver);
        doThrow(new AgentUnavailableException("Account belongs to another repository"))
                .when(registry).checkWorkspaceAccess(driver.id(), task.workspaceId());
        scopedRegistry = Optional.of(registry);
        service = service(driver, runtimeRegistry, events, approvals, CLOCK);
        assertThatThrownBy(() -> service.start(command("second", "key-2")))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("another repository");
        assertThat(driver.commands()).hasSize(1);
        assertThat(stores.list(task.id())).hasSize(1);
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
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
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
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
        service = service(driver, runtimeRegistry);
        approvals.beforeDeny(() -> awaitTaskLock(runtimeRegistry));
        driver.failNextStart();

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start failed");

        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> assertThat(turn.state()).isEqualTo(TurnState.FAILED));
    }

    @Test
    void aProviderWithdrawsAnApprovalItSettledItself()
    {
        DefaultAgentApprovalService durableApprovals = durableApprovals(runtimeRegistry);
        service = service(driver, runtimeRegistry, events, durableApprovals);
        AgentTurn running = service.start(command("Inspect", "approval-withdrawn"));
        CompletableFuture<ProviderApprovalDecision> pending = driver.requestApproval(approvalRequest("withdrawn"), Duration.ofMinutes(5)).toCompletableFuture();
        assertThat(stores.listPending(running.id())).hasSize(1);

        pending.cancel(false);

        assertThat(stores.listPending(running.id())).isEmpty();
        assertThat(events.readAfter(task.id(), 0, 100)).anySatisfy(event -> assertThat(event.payload())
                .isInstanceOfSatisfying(AgentEventPayload.ApprovalResolved.class,
                        resolved -> assertThat(resolved.outcome()).isEqualTo(AgentEventPayload.ApprovalOutcome.CANCELLED)));
        driver.complete("The agent that asked was closed");
    }

    @Test
    void providerDistinguishesApprovalExpiryFromAnExplicitDenial()
            throws Exception
    {
        DefaultAgentApprovalService durableApprovals = durableApprovals(runtimeRegistry);
        service = service(driver, runtimeRegistry, events, durableApprovals);
        AgentTurn running = service.start(command("Inspect", "approval-timeout"));

        ProviderApprovalDecision expired = driver.requestApproval(approvalRequest("expired"), Duration.ofMillis(20))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(expired).isEqualTo(ProviderApprovalDecision.expired());
        assertThat(expired.scopedPermission()).isEmpty();
        assertThat(stores.listPending(running.id())).isEmpty();
        CompletableFuture<ProviderApprovalDecision> next = driver.requestApproval(approvalRequest("explicit-denial"), Duration.ofMinutes(1)).toCompletableFuture();
        ApprovalId approvalId = stores.listPending(running.id()).getFirst().request().id();
        durableApprovals.resolve(new ResolveApprovalCommand(approvalId, ApprovalDecision.Decision.DENY, Optional.empty(), false));
        assertThat(next.join()).isEqualTo(ProviderApprovalDecision.deny());
        driver.complete("Denied action was skipped");
    }

    @Test
    void aStoppedSessionsApprovalCallbackCannotCreateAGateForTheNextTurn()
    {
        DefaultAgentApprovalService durableApprovals = durableApprovals(runtimeRegistry);
        service = service(driver, runtimeRegistry, events, durableApprovals);
        AgentTurn first = service.start(command("First request", "approval-first"));
        AgentSessionContext oldContext = driver.openedContexts().getFirst();
        service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());
        AgentTurn next = service.start(command("Next request", "approval-next"));

        assertThatThrownBy(() -> oldContext.approvals().request(approvalRequest("late-old-approval"), Duration.ofMinutes(1)))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("stopped or replaced turn");

        assertThat(stores.listPending(first.id())).isEmpty();
        assertThat(stores.listPending(next.id())).isEmpty();
        assertThat(events.readAfter(task.id(), 0, 100)).noneMatch(event -> event.payload() instanceof AgentEventPayload.ApprovalRequested);
        assertThat(driver.openedContexts()).hasSize(2);
        CompletableFuture<ProviderApprovalDecision> current = driver.openedContexts().getLast().approvals()
                .request(approvalRequest("current-approval"), Duration.ofMinutes(1)).toCompletableFuture();
        ApprovalId approvalId = stores.listPending(next.id()).getFirst().request().id();
        durableApprovals.resolve(new ResolveApprovalCommand(approvalId, ApprovalDecision.Decision.DENY, Optional.empty(), false));
        assertThat(current.join()).isEqualTo(ProviderApprovalDecision.deny());
        driver.complete("Current request finished");
    }

    @Test
    void nextReadClosesApprovalWhenCompletionDenialCouldNotPersist()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
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
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
        DefaultAgentApprovalService durableApprovals = durableApprovals(runtimeRegistry);
        service = service(driver, runtimeRegistry, events, durableApprovals);
        ApprovalId approvalId = ApprovalId.random();
        driver.beforeStart(() -> {
            AgentTurn accepted = stores.findActive(task.id()).orElseThrow();
            durableApprovals.request(new ApprovalRequest(approvalId, task.id(), accepted.id(), approvalRequest("start-approval"), NOW), Duration.ofMinutes(1));
            stores.failApprovalResolve(new AgentStorageException("blocked approval resolution"));
            // A provider that already asked for approval may have started the turn.
            throw new AgentDeliveryUncertainException("start failed after approval", null);
        });

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isInstanceOf(AgentDeliveryUncertainException.class)
                .hasMessage("start failed after approval");

        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> assertThat(turn.state()).isEqualTo(TurnState.INTERRUPTED));
        assertThat(stores.find(approvalId).orElseThrow().pending()).isTrue();

        durableApprovals(new AgentRuntimeRegistry(stores, ignored -> {})).denyAllPending();

        assertThat(stores.find(approvalId).orElseThrow().pending()).isFalse();
    }

    @Test
    void routesProviderApprovalForTheCurrentTurn()
    {
        AgentTurn running = service.start(command("approval", "key-1"));
        ProviderApprovalRequest request = new ProviderApprovalRequest(
                ProviderApprovalRequest.Kind.COMMAND,
                "Run command",
                "Run tests",
                ImmutableList.of("command:run"),
                ImmutableList.of(ALLOW_ONCE, DENY),
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
        DefaultAgentTurnService resumedService = service(resumedDriver, new AgentRuntimeRegistry(stores, ignored -> {}));
        AgentTurn second = resumedService.start(command("second", "key-2"));

        assertThat(resumedDriver.openedReferences()).containsExactly(reference);
        resumedDriver.complete("second response");
        assertThat(resumedService.get(second.id()).state()).isEqualTo(TurnState.COMPLETED);
    }

    @Test
    void resolvesManagedInstructionsWithInteractiveAndWorkflowTaskIdentity()
    {
        AgentTask interactive = interactiveTask("Implement the change");

        for (AgentTask candidate : ImmutableList.of(task, interactive)) {
            AgentTurn running = service.start(new StartAgentTurnCommand(candidate.id(), "first", ImmutableList.of(), "identity"));
            assertThat(instructionTasks.getLast()).isEqualTo(candidate
                    .claimWorktreeMutation(WorktreeMutationClaim.turn(running.id(), running.createdAt()), running.createdAt()).withVersion(candidate.version() + 1));
            driver.complete("done");
        }
        assertThat(instructionTasks).extracting(AgentTask::interactive).containsExactly(false, true);
        assertThat(instructionTasks).extracting(AgentTask::permissionPolicy).containsExactly(AgentPermissionPolicy.AUTO, AgentPermissionPolicy.AUTO);
    }

    @Test
    void refreshesManagedInstructionsForNewResumedAndQueuedTurns()
    {
        managedInstructions.set("Lead with the next action.");
        AgentTurn first = service.start(command("first", "key-1"));
        AgentSessionContext firstContext = driver.openedContexts().getFirst();
        assertThat(firstContext.managedInstructions()).isEqualTo("Lead with the next action.");
        assertThat(instructionTasks).containsExactly(task
                .claimWorktreeMutation(WorktreeMutationClaim.turn(first.id(), first.createdAt()), first.createdAt()).withVersion(task.version() + 1));

        managedInstructions.set("Keep each explanation short.");
        assertThat(firstContext.managedInstructions()).isEqualTo("Lead with the next action.");
        driver.complete("first response");
        AgentSessionRef reference = stores.find(task.id()).orElseThrow().sessionReference().orElseThrow();

        DeterministicAgentDriver resumedDriver = new DeterministicAgentDriver();
        DefaultAgentTurnService resumedService = service(resumedDriver, new AgentRuntimeRegistry(stores, ignored -> {}));
        resumedService.start(command("second", "key-2"));
        assertThat(resumedDriver.openedContexts()).singleElement().satisfies(context -> {
            assertThat(context.sessionReference()).contains(reference);
            assertThat(context.managedInstructions()).isEqualTo("Keep each explanation short.");
        });

        resumedService.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "third", ImmutableList.of(), "queue-1"));
        managedInstructions.set("");
        resumedDriver.complete("second response");

        assertThat(resumedDriver.openedContexts()).extracting(AgentSessionContext::managedInstructions).containsExactly("Keep each explanation short.", "");
        assertThat(resumedDriver.commands()).extracting(AgentTurnCommand::message).containsExactly("second", "third");
        assertThat(instructionTasks).hasSize(3).allSatisfy(instructionTask -> {
            assertThat(instructionTask.id()).isEqualTo(task.id());
            assertThat(instructionTask.interactive()).isFalse();
            assertThat(instructionTask.permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        });
        assertThat(instructionTasks.get(1).sessionReference()).contains(reference);
        assertThat(instructionTasks.get(2).sessionReference()).contains(reference);
        resumedDriver.complete("third response");
    }

    @Test
    void stopsAnUncertainStartWithoutResendingIt()
    {
        driver.failNextStartUncertainly();

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isInstanceOf(AgentDeliveryUncertainException.class)
                .hasMessageContaining("not acknowledged");
        // The provider may have taken the turn, so it ends interrupted rather than unsent, and its process is stopped.
        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> {
            assertThat(turn.state()).isEqualTo(TurnState.INTERRUPTED);
            assertThat(turn.knownUnsent()).isFalse();
            assertThat(turn.startedAt()).isPresent();
            assertThat(turn.providerTurnReference()).isEmpty();
            assertThat(turn.terminalReason()).isPresent();
        });
        assertThat(stores.find(task.id()).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.findActive(task.id())).isEmpty();
        assertThat(driver.commands()).isEmpty();
        // Nothing resends it; the user's next message is a turn of its own.
        assertThat(service.start(command("next request", "key-2"))).isNotNull();
        driver.complete("done");
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
            assertThat(turn.knownUnsent()).isFalse();
        });

        AgentTurn repeated = service.start(command("do not resend", "key-1"));
        assertThat(repeated.id()).isEqualTo(stores.list(task.id()).getFirst().id());
        assertThat(driver.commands()).hasSize(1);
    }

    @Test
    void preservesFailureWhenSessionReferenceAndCloseThrowTheSameInstance()
    {
        Clock clock = new AdvancingClock(NOW.plusMillis(1));
        metrics = new DefaultAgentMetricsService(stores, stores, logs, clock);
        runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
        service = service(driver, runtimeRegistry, events, approvals, clock);
        IllegalStateException failure = new IllegalStateException("session transport failed");
        driver.failNextSessionReferenceAndClose(failure);

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isSameAs(failure);

        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> assertThat(turn.state()).isEqualTo(TurnState.FAILED));
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isPresent();
        assertThat(runtimeRegistry.session(task.id())).isPresent();
        assertThat(runtimeRegistry.unverifiedClose(task.id())).contains(failure);
        assertThatThrownBy(() -> service.start(command("do not overlap", "key-2")))
                .isInstanceOf(AgentConflictException.class);
    }

    @Test
    void preservesFailureWhenTurnReferenceAndCancelThrowTheSameInstance()
    {
        IllegalStateException failure = new IllegalStateException("turn transport failed");
        driver.failNextTurnReferenceAndCancel(failure);

        assertThatThrownBy(() -> service.start(command("fail", "key-1")))
                .isSameAs(failure);

        assertThat(stores.list(task.id())).singleElement().satisfies(turn -> assertThat(turn.state()).isEqualTo(TurnState.ACCEPTED));
        assertThat(stores.find(task.id()).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.findActive(task.id())).isPresent();
    }

    @Test
    void preservesEachTurnsEffectiveSettingsAndOpaqueAttachmentReferences()
    {
        Path attachmentPath = temporaryDirectory.resolve("image.png").toAbsolutePath();
        AgentTurn first = service.start(new StartAgentTurnCommand(task.id(), "first", ImmutableList.of(new AgentAttachment("image.png", "image/png", attachmentPath)), "key-1"));
        AgentTurnSettings firstSettings = first.settings().orElseThrow();
        assertThat(firstSettings.model()).contains("model-a");
        assertThat(firstSettings.effort()).contains("low");
        assertThat(firstSettings.permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
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
                new AgentTaskDefaults(Optional.of("model-b"), Optional.of("high"), AgentPermissionPolicy.AUTO)));
        AgentTurn second = service.start(command("second", "key-2"));
        driver.complete("second response");

        assertThat(stores.find(first.id()).orElseThrow().settings()).contains(firstSettings);
        AgentTurnSettings secondSettings = stores.find(second.id()).orElseThrow().settings().orElseThrow();
        assertThat(secondSettings.model()).contains("model-b");
        assertThat(secondSettings.effort()).contains("high");
        assertThat(secondSettings.permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        assertThat(secondSettings.maximumRuntime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(secondSettings.attachments()).isEmpty();
        assertThat(driver.commands().getFirst().attachments())
                .extracting(AgentAttachment::path)
                .containsExactly(attachmentPath);
        assertThat(driver.openedContexts()).satisfiesExactly(
                firstContext -> {
                    assertThat(firstContext.sessionReference()).isEmpty();
                    assertThat(firstContext.model()).contains("model-a");
                    assertThat(firstContext.effort()).contains("low");
                },
                secondContext -> {
                    assertThat(secondContext.sessionReference()).contains(driver.openedReferences().getFirst());
                    assertThat(secondContext.model()).contains("model-b");
                    assertThat(secondContext.effort()).contains("high");
                });
        assertThat(driver.openedReferences())
                .hasSize(2)
                .allSatisfy(reference -> assertThat(reference).isEqualTo(driver.openedReferences().getFirst()));
    }

    @Test
    void steersTheRunningTurnAndRecordsTheDelivery()
    {
        AgentTurn first = service.start(command("first", "key-1"));

        AgentInputDelivery delivery = service.steer(new SteerAgentTurnCommand(first.id(), "also this", ImmutableList.of(), "steer-1"));

        assertThat(delivery.state()).isEqualTo(AgentInputDelivery.State.DELIVERED);
        assertThat(driver.steers()).extracting(AgentTurnInput::message).containsExactly("also this");
        // The message was durable before the send, as a steer into the same turn.
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).deliveryKind())
                .containsExactly(AgentEventPayload.DeliveryKind.TURN, AgentEventPayload.DeliveryKind.STEER);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .allSatisfy(event -> assertThat(event.turnId()).contains(first.id()));
        // A repeated key is the same delivery, not a second send.
        assertThat(service.steer(new SteerAgentTurnCommand(first.id(), "ignored", ImmutableList.of(), "steer-1")).id()).isEqualTo(delivery.id());
        assertThat(driver.steers()).hasSize(1);

        driver.complete("done");
        // The turn the user was looking at has ended: a steer for it is stale, never delivered to the next one.
        assertThatThrownBy(() -> service.steer(new SteerAgentTurnCommand(first.id(), "late", ImmutableList.of(), "steer-2")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("not running");
        assertThat(driver.steers()).hasSize(1);
    }

    @Test
    void steersTheFullQueuedInputOnceWithoutStartingItAgain()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        var attachment = resources.storeAttachment(new StoreAgentAttachmentCommand(task.id(), "queued.png", "image/png",
                Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")));
        String message = "Full queued instructions\n".repeat(30);
        QueuedAgentInput input = service.enqueue(new QueueAgentInputCommand(
                task.id(), taskService.get(task.id()).version(), message, ImmutableList.of(attachment.input()), "queue-steer"));
        driver.beforeSteer(() -> {
            assertThat(service.queued(task.id())).isEmpty();
            assertThat(stores.find(input.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.STARTED);
        });

        AgentInputDelivery delivery = service.steerQueued(task.id(), first.id(), input.id());

        assertThat(delivery.state()).isEqualTo(AgentInputDelivery.State.DELIVERED);
        assertThat(driver.steers()).singleElement().satisfies(steer -> {
            assertThat(steer.message()).isEqualTo(message);
            assertThat(steer.attachments()).containsExactly(attachment.input());
        });
        assertThat(stores.find(input.id())).hasValueSatisfying(queued -> {
            assertThat(queued.turnId()).contains(first.id());
            assertThat(queued.messageEventId()).contains(EventId.steerMessage(delivery.id()));
        });
        assertThat(service.steerQueued(task.id(), first.id(), input.id()).id()).isEqualTo(delivery.id());
        assertThat(driver.steers()).hasSize(1);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text()).containsExactly("first", message);
        driver.complete("done");
        assertThat(stores.findActive(task.id())).isEmpty();
        assertThat(service.steerQueued(task.id(), first.id(), input.id()).id()).isEqualTo(delivery.id());
    }

    @Test
    void neverRequeuesFailedOrUncertainQueuedSteers()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        for (RuntimeException failure : ImmutableList.of(new AgentUnavailableException("refused"), new AgentDeliveryUncertainException("no response", null))) {
            QueuedAgentInput input = service.enqueue(new QueueAgentInputCommand(
                    task.id(), taskService.get(task.id()).version(), "queued " + failure.getMessage(), ImmutableList.of(), failure.getMessage()));
            driver.failNextSteer(failure);
            AgentInputDelivery delivery = service.steerQueued(task.id(), first.id(), input.id());
            assertThat(delivery.state()).isEqualTo(failure instanceof AgentDeliveryUncertainException ? AgentInputDelivery.State.UNKNOWN : AgentInputDelivery.State.FAILED);
            assertThat(service.steerQueued(task.id(), first.id(), input.id())).isEqualTo(delivery);
            assertThat(service.queued(task.id())).isEmpty();
        }
        driver.complete("done");
        assertThat(stores.findActive(task.id())).isEmpty();
    }

    @Test
    void refusesQueuedSteeringForAnotherTaskRemovedInputOrStaleTurn()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        QueuedAgentInput input = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "later", ImmutableList.of(), "queue-1"));
        assertThatThrownBy(() -> service.steerQueued(TaskId.random(), first.id(), input.id())).isInstanceOf(AgentConflictException.class);
        assertThat(service.queued(task.id())).hasSize(1);
        service.removeQueued(task.id(), input.id());
        assertThatThrownBy(() -> service.steerQueued(task.id(), first.id(), input.id())).isInstanceOf(AgentConflictException.class);
        driver.complete("done");
        AgentTurn second = service.start(command("second", "key-2"));
        QueuedAgentInput later = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "later again", ImmutableList.of(), "queue-2"));
        assertThatThrownBy(() -> service.steerQueued(task.id(), first.id(), later.id())).isInstanceOf(AgentConflictException.class).hasMessageContaining("not running");
        assertThat(service.queued(task.id())).extracting(QueuedAgentInput::id).containsExactly(later.id());
        assertThat(driver.steers()).isEmpty();
        assertThat(service.get(second.id()).state()).isEqualTo(TurnState.RUNNING);
    }

    @Test
    void findsAcceptedRequestsBeforeACallerRevalidatesMutableInput()
    {
        AgentTurn turn = service.start(command("first", "key-1"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "later", ImmutableList.of(), "queue-1"));
        AgentInputDelivery steer = service.steer(new SteerAgentTurnCommand(turn.id(), "more", ImmutableList.of(), "steer-1"));

        assertThat(service.findTurnByIdempotencyKey(task.id(), "key-1")).contains(turn);
        assertThat(service.findQueuedByIdempotencyKey(task.id(), "queue-1")).contains(queued);
        assertThat(service.findSteerByIdempotencyKey(turn.id(), "steer-1")).contains(steer);
        assertThat(service.findTurnByIdempotencyKey(task.id(), "missing")).isEmpty();
        assertThat(service.findQueuedByIdempotencyKey(task.id(), "missing")).isEmpty();
        assertThat(service.findSteerByIdempotencyKey(turn.id(), "missing")).isEmpty();
    }

    @Test
    void retriesAnAcceptedSteerThatFailedBeforeTheProviderSend()
    {
        AgentRuntimeRegistry runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {});
        service = service(driver, runtimeRegistry);
        AgentTurn first = service.start(command("first", "key-1"));
        FailOnceDrainEventService failingEvents = new FailOnceDrainEventService(events, false);
        failingEvents.failNextDrain();
        service = service(driver, runtimeRegistry, failingEvents);
        var originalAttachment = resources.storeAttachment(new StoreAgentAttachmentCommand(
                task.id(),
                "original.png",
                "image/png",
                Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")));
        SteerAgentTurnCommand steer = new SteerAgentTurnCommand(first.id(), "also this", ImmutableList.of(originalAttachment.input()), "steer-1");

        assertThatThrownBy(() -> service.steer(steer))
                .isInstanceOf(AgentStorageException.class)
                .hasMessage("blocked event log");
        assertThat(stores.steers()).singleElement().satisfies(delivery -> assertThat(delivery.state()).isEqualTo(AgentInputDelivery.State.ACCEPTED));
        assertThat(driver.steers()).isEmpty();
        assertThat(service.findSteerByIdempotencyKey(first.id(), "steer-1"))
                .hasValueSatisfying(delivery -> assertThat(delivery.state()).isEqualTo(AgentInputDelivery.State.ACCEPTED));

        AgentInputDelivery retried = service.steer(new SteerAgentTurnCommand(
                first.id(),
                "different text",
                ImmutableList.of(new AgentAttachment("different.png", "image/png", temporaryDirectory.resolve("different.png").toAbsolutePath())),
                "steer-1"));

        assertThat(retried.state()).isEqualTo(AgentInputDelivery.State.DELIVERED);
        assertThat(driver.steers()).extracting(AgentTurnInput::message).containsExactly("also this");
        assertThat(driver.steers().getFirst().attachments()).containsExactly(originalAttachment.input());
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("first", "also this");
    }

    @Test
    void failsAnAcceptedSteerWhenItsTurnEndsBeforeRetry()
    {
        AgentRuntimeRegistry sharedRuntime = new AgentRuntimeRegistry(stores, ignored -> {});
        service = service(driver, sharedRuntime);
        AgentTurn first = service.start(command("first", "key-1"));
        FailOnceDrainEventService failingEvents = new FailOnceDrainEventService(events, false);
        failingEvents.failNextDrain();
        service = service(driver, sharedRuntime, failingEvents);

        assertThatThrownBy(() -> service.steer(new SteerAgentTurnCommand(first.id(), "never sent", ImmutableList.of(), "steer-1")))
                .isInstanceOf(AgentStorageException.class)
                .hasMessage("blocked event log");
        assertThat(stores.steers()).singleElement().satisfies(delivery -> assertThat(delivery.state()).isEqualTo(AgentInputDelivery.State.ACCEPTED));

        driver.complete("done");

        assertThat(stores.steers()).singleElement().satisfies(delivery -> {
            assertThat(delivery.state()).isEqualTo(AgentInputDelivery.State.FAILED);
            assertThat(delivery.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("before the input was sent"));
        });
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.payload() instanceof AgentEventPayload.Error error
                        && error.code().equals("STEER_FAILED"))
                .hasSize(1);
    }

    @Test
    void treatsAStrandedSendingSteerAsUnknownWithoutResending()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        AgentInputDelivery stranded = AgentInputDelivery.accepted(InputDeliveryId.random(), task.id(), first.id(), "steer-1", NOW)
                .sending(NOW);
        stores.insert(stranded);
        assertThat(service.findSteerByIdempotencyKey(first.id(), "steer-1")).contains(stranded);

        AgentInputDelivery recovered = service.steer(new SteerAgentTurnCommand(first.id(), "must not resend", ImmutableList.of(), "steer-1"));

        assertThat(recovered.state()).isEqualTo(AgentInputDelivery.State.UNKNOWN);
        assertThat(driver.steers()).isEmpty();
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.payload() instanceof AgentEventPayload.Error error
                        && error.code().equals("STEER_UNKNOWN"))
                .hasSize(1);
    }

    @Test
    void refusesASettledSteerKeyReusedForAnotherTurn()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        service.steer(new SteerAgentTurnCommand(first.id(), "one", ImmutableList.of(), "steer-1"));
        driver.complete("first done");
        AgentTurn second = service.start(command("second", "key-2"));

        assertThatThrownBy(() -> service.steer(new SteerAgentTurnCommand(second.id(), "two", ImmutableList.of(), "steer-1")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("belongs to another turn");
        assertThatThrownBy(() -> service.findSteerByIdempotencyKey(second.id(), "steer-1"))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("belongs to another turn");
        assertThat(driver.steers()).extracting(AgentTurnInput::message).containsExactly("one");
    }

    @Test
    void completionCanFinishBeforeTheSteerResponseWithoutDeadlocking()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        driver.beforeSteer(() -> {
            try {
                CompletableFuture.runAsync(() -> driver.complete("done")).get(5, TimeUnit.SECONDS);
            }
            catch (Exception failure) {
                throw new AssertionError("completion callback waited for the steer response", failure);
            }
        });

        AgentInputDelivery delivery = service.steer(new SteerAgentTurnCommand(first.id(), "too late", ImmutableList.of(), "steer-1"));

        assertThat(delivery.state()).isEqualTo(AgentInputDelivery.State.FAILED);
        assertThat(service.get(first.id()).state()).isEqualTo(TurnState.COMPLETED);
        assertThat(stores.steers()).noneMatch(candidate -> candidate.state() == AgentInputDelivery.State.SENDING);
    }

    @Test
    void serializesSteerAcceptanceAndProviderDeliveryOrder()
            throws Exception
    {
        AgentTurn first = service.start(command("first", "key-1"));
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Void> firstEntered = new CompletableFuture<>();
        CompletableFuture<Void> releaseFirst = new CompletableFuture<>();
        CompletableFuture<Void> secondEntered = new CompletableFuture<>();
        driver.beforeSteer(() -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.complete(null);
                releaseFirst.join();
            }
            else {
                secondEntered.complete(null);
            }
        });

        CompletableFuture<AgentInputDelivery> firstSteer = CompletableFuture.supplyAsync(() ->
                service.steer(new SteerAgentTurnCommand(first.id(), "one", ImmutableList.of(), "steer-1")));
        firstEntered.get(5, TimeUnit.SECONDS);
        CompletableFuture<AgentInputDelivery> secondSteer = CompletableFuture.supplyAsync(() ->
                service.steer(new SteerAgentTurnCommand(first.id(), "two", ImmutableList.of(), "steer-2")));

        assertThatThrownBy(() -> secondEntered.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(stores.steers()).singleElement().satisfies(delivery -> assertThat(delivery.state()).isEqualTo(AgentInputDelivery.State.SENDING));

        releaseFirst.complete(null);
        assertThat(firstSteer.get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentInputDelivery.State.DELIVERED);
        assertThat(secondSteer.get(5, TimeUnit.SECONDS).state()).isEqualTo(AgentInputDelivery.State.DELIVERED);
        assertThat(driver.steers()).extracting(AgentTurnInput::message).containsExactly("one", "two");
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.payload() instanceof AgentEventPayload.UserMessage message
                        && message.deliveryKind() == AgentEventPayload.DeliveryKind.STEER)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("one", "two");
    }

    @Test
    void recordsARefusedSteerAsFailedAndAnUnansweredOneAsUnknown()
    {
        AgentTurn first = service.start(command("first", "key-1"));

        driver.failNextSteer(new AgentUnavailableException("provider refused the input"));
        AgentInputDelivery refused = service.steer(new SteerAgentTurnCommand(first.id(), "refused", ImmutableList.of(), "steer-1"));
        driver.failNextSteer(new AgentDeliveryUncertainException("no answer before the timeout", null));
        AgentInputDelivery unanswered = service.steer(new SteerAgentTurnCommand(first.id(), "silent", ImmutableList.of(), "steer-2"));

        assertThat(refused.state()).isEqualTo(AgentInputDelivery.State.FAILED);
        assertThat(refused.failure()).contains("provider refused the input");
        assertThat(unanswered.state()).isEqualTo(AgentInputDelivery.State.UNKNOWN);
        assertThat(unanswered.failure()).contains("no answer before the timeout");
        // Both outcomes are visible on the turn, beside the messages that were already shown.
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.ERROR)
                .extracting(event -> ((AgentEventPayload.Error) event.payload()).code())
                .containsExactly("STEER_FAILED", "STEER_UNKNOWN");
        // Nothing resends: repeating the unknown key returns the unknown delivery as it is.
        assertThat(service.steer(new SteerAgentTurnCommand(first.id(), "silent", ImmutableList.of(), "steer-2")).state())
                .isEqualTo(AgentInputDelivery.State.UNKNOWN);
        assertThat(driver.steers()).isEmpty();
        driver.complete("done");
    }

    @Test
    void startsQueuedInputInOrderOnlyAfterASuccessfulTurn()
    {
        AgentTurn first = service.start(command("first", "key-1"));
        long version = taskService.get(task.id()).version();
        QueuedAgentInput second = service.enqueue(new QueueAgentInputCommand(task.id(), version, "second", ImmutableList.of(), "queue-1"));
        QueuedAgentInput third = service.enqueue(new QueueAgentInputCommand(task.id(), version + 1, "third", ImmutableList.of(), "queue-2"));

        // Queued input waits in the queue, not in the transcript, and repeating a key adds nothing.
        assertThat(service.enqueue(new QueueAgentInputCommand(task.id(), version + 2, "ignored", ImmutableList.of(), "queue-1")).id()).isEqualTo(second.id());
        assertThat(service.queued(task.id())).extracting(QueuedAgentInput::preview).containsExactly("second", "third");
        assertThat(driver.commands()).hasSize(1);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .hasSize(1);

        driver.complete("first response");

        // Success starts the head by itself: one turn, one user-message event, in queue order.
        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("first", "second");
        assertThat(stores.find(second.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.STARTED);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.QUEUED_INPUT_STARTED)
                .singleElement()
                .satisfies(event -> assertThat(event.turnId()).contains(stores.find(second.id()).orElseThrow().turnId().orElseThrow()));
        assertThat(service.queued(task.id())).extracting(QueuedAgentInput::preview).containsExactly("third");
        AgentTurn secondTurn = stores.findActive(task.id()).orElseThrow();
        assertThat(stores.find(second.id()).orElseThrow().turnId()).contains(secondTurn.id());
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).deliveryKind())
                .containsExactly(AgentEventPayload.DeliveryKind.TURN, AgentEventPayload.DeliveryKind.QUEUED);
        assertThat(stores.find(first.id()).orElseThrow().state()).isEqualTo(TurnState.COMPLETED);

        driver.complete("second response");
        driver.complete("third response");

        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("first", "second", "third");
        assertThat(service.queued(task.id())).isEmpty();
        assertThat(stores.find(third.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.STARTED);
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
    }

    @Test
    void keepsAnAcknowledgedQueuedTurnRunningWhenItsRefreshEventCannotDrain()
    {
        FailLifecycleDrainEventService failingEvents = new FailLifecycleDrainEventService(events, stores, AgentEventPayload.LifecyclePhase.QUEUED_INPUT_STARTED);
        service = service(driver, runtimeRegistry, failingEvents);
        service.start(command("first", "key-1"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "second", ImmutableList.of(), "queue-1"));

        driver.complete("first response");

        AgentTurn second = stores.findActive(task.id()).orElseThrow();
        assertThat(second.state()).isEqualTo(TurnState.RUNNING);
        assertThat(runtimeRegistry.running(second.id())).isPresent();
        assertThat(stores.find(queued.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.STARTED);
        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("first", "second");
        AgentEventOutboxEntry pendingRefresh = stores.outboxEntries().stream()
                .filter(entry -> entry.sequence().isEmpty())
                .filter(entry -> entry.payload() instanceof AgentEventOutboxPayload.Inline inline
                        && inline.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.QUEUED_INPUT_STARTED)
                .findFirst()
                .orElseThrow();

        failingEvents.drain(pendingRefresh.eventId());

        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.QUEUED_INPUT_STARTED)
                .hasSize(1);
    }

    @Test
    void pausesTheQueueAfterANonSuccessAndResumesOnlyOnRequest()
    {
        service.start(command("first", "key-1"));
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                task.id(),
                taskService.get(task.id()).version(),
                new AgentTaskDefaults(Optional.of("model-a"), Optional.of("high"), AgentPermissionPolicy.AUTO, Optional.of("fast"))));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "second", ImmutableList.of(), "queue-1"));
        assertThat(queued.settings().effort()).contains("high");
        assertThat(queued.settings().serviceTier()).contains("fast");

        driver.failCompletion(new IllegalStateException("provider crashed"));

        assertThat(driver.commands()).hasSize(1);
        assertThat(service.queued(task.id())).singleElement().satisfies(input -> {
            assertThat(input.state()).isEqualTo(QueuedAgentInput.State.PAUSED);
            assertThat(input.pauseReason()).hasValueSatisfying(reason -> assertThat(reason).contains("interrupted"));
        });
        assertThatThrownBy(() -> service.resumeQueued(task.id())).isInstanceOf(AgentConflictException.class);
        service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());
        taskService.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                task.id(),
                taskService.get(task.id()).version(),
                new AgentTaskDefaults(Optional.of("model-a"), Optional.of("low"), AgentPermissionPolicy.AUTO)));

        AgentTurn resumed = service.resumeQueued(task.id());

        assertThat(resumed.state()).isEqualTo(TurnState.RUNNING);
        assertThat(resumed.settings()).contains(queued.settings());
        assertThat(driver.commands().getLast().effort()).contains("high");
        assertThat(driver.commands().getLast().serviceTier()).contains("fast");
        assertThat(driver.openedContexts().getLast().effort()).contains("high");
        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("first", "second");
        assertThat(stores.find(queued.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.STARTED);
        assertThatThrownBy(() -> service.resumeQueued(task.id())).isInstanceOf(AgentConflictException.class);
        driver.complete("second response");
        // Nothing waits any more, and an empty queue is not something to resume.
        assertThatThrownBy(() -> service.resumeQueued(task.id())).isInstanceOf(AgentConflictException.class).hasMessageContaining("no queued input");
    }

    @Test
    void publishesARefreshAfterBackgroundAutoStartPausesTheQueue()
    {
        service.start(command("first", "key-1"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "second", ImmutableList.of(), "queue-1"));
        driver.unavailable();

        driver.complete("first response");

        assertThat(stores.find(queued.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        List<AgentEventEnvelope> replay = events.readAfter(task.id(), 0, 100);
        AgentEventEnvelope completed = replay.stream()
                .filter(event -> event.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.TURN_COMPLETED)
                .findFirst()
                .orElseThrow();
        AgentEventEnvelope paused = replay.stream()
                .filter(event -> event.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.QUEUED_INPUT_PAUSED)
                .findFirst()
                .orElseThrow();
        assertThat(paused.sequence()).isGreaterThan(completed.sequence());
        assertThat(replay)
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("first");
    }

    @Test
    void pausesTheQueueWhenPendingEventsCannotDrainBeforeAutoStart()
    {
        service = service(driver, runtimeRegistry, events, approvals, CLOCK, command -> {
            stores.failNextTaskPendingList(new AgentStorageException("blocked outbox read"));
            command.run();
        });
        service.start(command("first", "key-1"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "second", ImmutableList.of(), "queue-1"));

        driver.complete("first response");

        assertThat(driver.commands()).hasSize(1);
        assertThat(stores.find(queued.id()).orElseThrow()).satisfies(input -> {
            assertThat(input.state()).isEqualTo(QueuedAgentInput.State.PAUSED);
            assertThat(input.pauseReason()).hasValueSatisfying(reason -> assertThat(reason).contains("blocked outbox read"));
        });
        AgentTurn resumed = service.resumeQueued(task.id());
        assertThat(resumed.state()).isEqualTo(TurnState.RUNNING);
        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("first", "second");
        driver.complete("second response");
    }

    @Test
    void returnsTheFullQueuedDraftAndAttachmentsBeforeRemovingIt()
    {
        service.start(command("first", "key-1"));
        var attachment = resources.storeAttachment(new StoreAgentAttachmentCommand(task.id(), "queued.png", "image/png",
                Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")));
        String message = "Full queued instructions\n".repeat(30);
        QueuedAgentInput input = service.enqueue(new QueueAgentInputCommand(
                task.id(), taskService.get(task.id()).version(), message, ImmutableList.of(attachment.input()), "queue-edit"));

        var draft = service.editQueued(task.id(), input.id());

        assertThat(draft.message()).isEqualTo(message);
        assertThat(draft.settings()).isEqualTo(input.settings());
        assertThat(draft.attachments()).containsExactly(attachment);
        assertThat(service.queued(task.id())).isEmpty();
        assertThat(service.editQueued(task.id(), input.id())).isEqualTo(draft);
        driver.complete("done");
        assertThat(driver.commands()).hasSize(1);
    }

    @Test
    void refusesToEditClaimedInputOrLoseUnreadableInput()
    {
        service.start(command("first", "key-1"));
        QueuedAgentInput waiting = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "waiting", ImmutableList.of(), "queue-edit"));
        QueuedAgentInput claimed = waiting.starting(TurnId.random(), EventId.random(), NOW);
        for (QueuedAgentInput input : ImmutableList.of(claimed, claimed.uncertain("unknown delivery", NOW), claimed.paused("retry", NOW))) {
            QueuedAgentInput stored = stores.update(input.withVersion(stores.find(input.id()).orElseThrow().version()));
            assertThatThrownBy(() -> service.editQueued(task.id(), input.id())).isInstanceOf(AgentConflictException.class);
            assertThat(stores.find(input.id())).contains(stored);
        }
        stores.update(waiting.paused("provider unavailable", NOW).withVersion(stores.find(waiting.id()).orElseThrow().version()));
        assertThat(service.editQueued(task.id(), waiting.id()).message()).isEqualTo("waiting");

        QueuedAgentInput unreadable = QueuedAgentInput.queued(QueuedInputId.random(), task.id(), 2, "unreadable",
                new BlobRef("blobs/queue/missing", "text/plain; charset=utf-8", 7), "missing", waiting.settings(), NOW);
        stores.insert(unreadable);
        assertThatThrownBy(() -> service.editQueued(task.id(), unreadable.id())).isInstanceOf(RuntimeException.class);
        assertThat(stores.find(unreadable.id())).contains(unreadable);
    }

    @Test
    void removesWaitingInputAndAbandonsUncertainInputWithoutResendingIt()
    {
        service.start(command("first", "key-1"));
        long version = taskService.get(task.id()).version();
        QueuedAgentInput waiting = service.enqueue(new QueueAgentInputCommand(task.id(), version, "waiting", ImmutableList.of(), "queue-1"));
        // A claim that was sent and never acknowledged: the turn has a start time and no provider reference.
        TurnId uncertainTurn = TurnId.random();
        stores.insert(AgentTurn.accepted(uncertainTurn, task.id(), "queue:uncertain", waiting.settings(), NOW)
                .markDeliveryStarted(NOW)
                .finish(TurnState.INTERRUPTED, AgentMetrics.empty(), Optional.of("restarted"), NOW));
        QueuedAgentInput uncertain = QueuedAgentInput.queued(QueuedInputId.random(), task.id(), 0 + 2, "queue-2", waiting.textBlob(), "uncertain", waiting.settings(), NOW)
                .starting(uncertainTurn, EventId.random(), NOW)
                .uncertain("Backend restarted while the input was being sent", NOW);
        stores.insert(uncertain);

        QueuedAgentInput removed = service.removeQueued(task.id(), waiting.id());
        assertThat(removed.state()).isEqualTo(QueuedAgentInput.State.REMOVED);
        assertThat(service.removeQueued(task.id(), waiting.id())).isEqualTo(removed);
        driver.failCompletion(new IllegalStateException("provider failed"));
        service.stopRuntime(task.id(), service.stopImpact(task.id()).generation());
        // The uncertain head blocks the queue until the user decides; resuming never resends it.
        assertThatThrownBy(() -> service.resumeQueued(task.id()))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("may already have been delivered");
        assertThat(driver.commands()).hasSize(1);
        QueuedAgentInput abandoned = service.removeQueued(task.id(), uncertain.id());
        assertThat(abandoned.state()).isEqualTo(QueuedAgentInput.State.ABANDONED);
        assertThat(service.removeQueued(task.id(), uncertain.id())).isEqualTo(abandoned);
        assertThat(service.queued(task.id())).isEmpty();
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.QUEUE_CHANGED)
                .extracting(event -> ((AgentEventPayload.Lifecycle) event.payload()).message())
                .containsExactly("Queued input added", "Queued input removed", "Queued input abandoned");
        assertThatThrownBy(() -> service.removeQueued(task.id(), QueuedInputId.random())).isInstanceOf(QueuedInputNotFoundException.class);
    }

    @Test
    void refusesToRemoveAnotherTasksQueuedInputBeforeMutatingIt()
    {
        QueuedAgentInput foreign = QueuedAgentInput.queued(
                QueuedInputId.random(),
                TaskId.random(),
                1,
                "foreign-queue",
                new BlobRef("blobs/queue/foreign", "text/plain; charset=utf-8", 7),
                "foreign",
                service.effectiveSettings(task.id()),
                NOW);
        stores.insert(foreign);

        assertThatThrownBy(() -> service.editQueued(task.id(), foreign.id()))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("does not belong");
        assertThatThrownBy(() -> service.removeQueued(task.id(), foreign.id()))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("does not belong");
        assertThat(stores.find(foreign.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.QUEUED);
    }

    @Test
    void rejectsAQueueWriteWithAStaleVersionOrAClosingTask()
    {
        long version = taskService.get(task.id()).version();
        assertThatThrownBy(() -> service.enqueue(new QueueAgentInputCommand(task.id(), version + 1, "stale", ImmutableList.of(), "queue-1")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> service.enqueue(new QueueAgentInputCommand(task.id(), version, "idle", ImmutableList.of(), "queue-idle")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("no active turn");

        AgentTask claimed = stores.update(stores.find(task.id()).orElseThrow().claimWorktreeMutation(
                new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, "completion-1", NOW), NOW));
        assertThatThrownBy(() -> service.enqueue(new QueueAgentInputCommand(task.id(), claimed.version(), "closing", ImmutableList.of(), "queue-2")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("COMPLETION");
        assertThat(service.queued(task.id())).isEmpty();
    }

    @Test
    void rejectsAQueueBehindAnActiveTurnThatDoesNotOwnTheTaskClaim()
    {
        service.start(command("first", "key-1"));
        AgentTask claimed = stores.find(task.id()).orElseThrow();
        WorktreeMutationClaim actualClaim = claimed.worktreeMutationClaim().orElseThrow();
        AgentTask released = stores.update(claimed.releaseWorktreeMutation(actualClaim, NOW));
        AgentTask mismatched = stores.update(released.claimWorktreeMutation(WorktreeMutationClaim.turn(TurnId.random(), NOW), NOW));

        assertThatThrownBy(() -> service.enqueue(new QueueAgentInputCommand(task.id(), mismatched.version(), "must not queue", ImmutableList.of(), "queue-1")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("claimed by TURN");
        assertThat(service.queued(task.id())).isEmpty();
    }

    @Test
    void rejectsAStartWhenDefaultsCouldHaveChangedAfterCallerValidation()
    {
        long validatedVersion = taskService.get(task.id()).version();
        taskService.rename(new RenameAgentTaskCommand(task.id(), validatedVersion, "Changed task"));

        assertThatThrownBy(() -> service.start(new StartAgentTurnCommand(task.id(), validatedVersion, "image prompt", ImmutableList.of(), "key-1")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("version changed");
        assertThat(driver.commands()).isEmpty();
    }

    @Test
    void concurrentIdleEnqueueAndCompleteCannotLeaveQueuedWorkOnATerminalTask()
            throws Exception
    {
        AgentTurn turn = service.start(command("first", "key-1"));
        driver.complete("done");
        assertThat(service.get(turn.id()).state()).isEqualTo(TurnState.COMPLETED);
        long idleVersion = taskService.get(task.id()).version();
        DefaultAgentCompletionService completion = completionService();
        CompletableFuture<Void> start = new CompletableFuture<>();

        CompletableFuture<Object> enqueue = CompletableFuture.supplyAsync(() -> {
            start.join();
            try {
                return service.enqueue(new QueueAgentInputCommand(task.id(), idleVersion, "stale queue", ImmutableList.of(), "queue-race"));
            }
            catch (RuntimeException failure) {
                return failure;
            }
        });
        CompletableFuture<Object> complete = CompletableFuture.supplyAsync(() -> {
            start.join();
            try {
                return completion.complete(new CompleteAgentTaskCommand(task.id(), Optional.empty()));
            }
            catch (RuntimeException failure) {
                return failure;
            }
        });

        start.complete(null);
        assertThat(enqueue.get(5, TimeUnit.SECONDS)).isInstanceOf(AgentConflictException.class);
        assertThat(complete.get(5, TimeUnit.SECONDS)).isInstanceOf(TaskCompletion.class);
        assertThat(stores.find(task.id()).orElseThrow().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(stores.listQueued(task.id())).isEmpty();
    }

    @Test
    void pausesTheQueueHeadWhenItsOwnStartFailsBeforeSendingAndRetriesWithoutASecondMessage()
    {
        service = service(driver, new AgentRuntimeRegistry(stores, ignored -> {}), new FailQueuedMessageDrainEventService(events, stores));
        service.start(command("first", "key-1"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "second", ImmutableList.of(), "queue-1"));

        driver.complete("first response");

        // The claimed turn failed before anything was sent, so the input is known unsent and waits again.
        assertThat(driver.commands()).hasSize(1);
        assertThat(stores.find(queued.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        AgentTurn failed = stores.list(task.id()).stream().filter(turn -> turn.idempotencyKey().startsWith("queue:")).findFirst().orElseThrow();
        assertThat(failed.state()).isEqualTo(TurnState.FAILED);
        assertThat(failed.startedAt()).isEmpty();
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();

        // The explicit resume is a second attempt on the same request, not a second message.
        AgentTurn retried = service.resumeQueued(task.id());
        assertThat(retried.state()).isEqualTo(TurnState.RUNNING);
        assertThat(retried.id()).isNotEqualTo(failed.id());
        assertThat(stores.find(queued.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.STARTED);
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.type() == AgentEventPayload.Kind.USER_MESSAGE)
                .extracting(event -> ((AgentEventPayload.UserMessage) event.payload()).text())
                .containsExactly("first", "second");
        assertThat(events.readAfter(task.id(), 0, 100))
                .filteredOn(event -> event.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.RETRYING)
                .singleElement()
                .satisfies(event -> assertThat(event.turnId()).contains(retried.id()));
        driver.complete("second response");
    }

    @Test
    void keepsAQueuedInputRetryableWhenItsStartedLifecycleCannotPublish()
    {
        FailLifecycleDrainEventService failingEvents = new FailLifecycleDrainEventService(events, stores, AgentEventPayload.LifecyclePhase.TURN_STARTED, false);
        service = service(driver, runtimeRegistry, failingEvents);
        service.start(command("first", "key-1"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "second", ImmutableList.of(), "queue-1"));
        failingEvents.failNextDrain();

        driver.complete("first response");

        assertThat(driver.commands()).hasSize(1);
        assertThat(stores.find(queued.id()).orElseThrow()).satisfies(input -> {
            assertThat(input.state()).isEqualTo(QueuedAgentInput.State.PAUSED);
            assertThat(input.pauseReason()).hasValueSatisfying(reason -> assertThat(reason).contains("blocked lifecycle event"));
        });
        assertThat(stores.list(task.id())).filteredOn(turn -> turn.idempotencyKey().startsWith("queue:"))
                .singleElement()
                .satisfies(turn -> assertThat(turn.knownUnsent()).isTrue());
        AgentTurn resumed = service.resumeQueued(task.id());
        assertThat(resumed.state()).isEqualTo(TurnState.RUNNING);
        assertThat(driver.commands()).extracting(AgentTurnCommand::message).containsExactly("first", "second");
        driver.complete("second response");
    }

    @Test
    void marksTheQueueHeadUncertainWhenItsStartWasNeverAcknowledged()
    {
        service.start(command("first", "key-1"));
        QueuedAgentInput queued = service.enqueue(new QueueAgentInputCommand(task.id(), taskService.get(task.id()).version(), "second", ImmutableList.of(), "queue-1"));
        driver.failNextStartUncertainly();

        driver.complete("first response");

        // Sent, never acknowledged: the session is stopped at once, and nobody can say whether the provider ran the
        // input; only the user may decide.
        assertThat(stores.find(queued.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.UNCERTAIN);
        assertThat(stores.find(task.id()).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThatThrownBy(() -> service.resumeQueued(task.id()))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("may already have been delivered");
        assertThat(service.removeQueued(task.id(), queued.id()).state()).isEqualTo(QueuedAgentInput.State.ABANDONED);
        assertThat(driver.commands()).hasSize(1);
    }

    private DefaultAgentTurnService service(DeterministicAgentDriver configuredDriver, AgentRuntimeRegistry runtimeRegistry)
    {
        return service(configuredDriver, runtimeRegistry, events);
    }

    private DefaultAgentTurnService service(DeterministicAgentDriver configuredDriver, AgentRuntimeRegistry runtimeRegistry, AgentEventService configuredEvents)
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
        return service(configuredDriver, runtimeRegistry, configuredEvents, configuredApprovals, clock, Runnable::run);
    }

    private DefaultAgentTurnService service(
            DeterministicAgentDriver configuredDriver,
            AgentRuntimeRegistry runtimeRegistry,
            AgentEventService configuredEvents,
            AgentApprovalService configuredApprovals,
            Clock clock,
            Executor queueStarter)
    {
        return new DefaultAgentTurnService(
                stores,
                stores,
                stores,
                stores,
                stores,
                stores,
                logs,
                resources,
                scopedRegistry.orElseGet(() -> new StaticAgentDriverRegistry(ImmutableList.of(configuredDriver))),
                configuredEvents,
                metrics,
                configuredApprovals,
                runtimeRegistry,
                (worktree, query) -> {
                    queriedWorktree = worktree;
                    if (codeQueryUnavailable) {
                        throw new IllegalStateException("IDE not ready");
                    }
                    return "symbols:" + query.query().orElseThrow();
                },
                instructionTask -> {
                    instructionTasks.add(instructionTask);
                    return managedInstructions.get();
                },
                new ReviewTurnInputResolver()
                {
                    @Override
                    public String prepare(AgentTask acceptedTask, AgentTurn turn, String message)
                    {
                        return reviewTurnInput.prepare(acceptedTask, turn, message);
                    }

                    @Override
                    public Reference reference(AgentTask acceptedTask, AgentTurn turn)
                    {
                        return reviewTurnInput.reference(acceptedTask, turn);
                    }

                    @Override
                    public void finished(AgentTask endedTask, AgentTurn turn)
                    {
                        reviewTurnInput.finished(endedTask, turn);
                    }
                },
                clock,
                Duration.ofMinutes(5),
                queueStarter,
                ignored -> developmentListener.isPresent(),
                (acceptedTask, turn) -> developmentAccepted.accept(acceptedTask, turn),
                completion -> developmentListener.orElseThrow().accept(completion),
                stoppedTask -> stopChildren.accept(stoppedTask));
    }

    private DefaultAgentCompletionService completionService()
    {
        return new DefaultAgentCompletionService(
                stores,
                stores,
                stores,
                stores,
                stores,
                events,
                logs,
                worktrees,
                resources,
                runtimeRegistry,
                metrics,
                () -> Duration.ofDays(7),
                task -> {},
                CLOCK);
    }

    private DefaultAgentApprovalService durableApprovals(AgentRuntimeRegistry runtimeRegistry)
    {
        return new DefaultAgentApprovalService(stores, stores, stores, stores, events, scheduler, CLOCK, runtimeRegistry);
    }

    @Test
    void sharesTaskBoundLanguageQueriesWithTheProvider()
    {
        service.start(command("first", "key-1"));

        CodeQueryBridge bridge = driver.openedContexts().getFirst().codeQueries().orElseThrow();
        assertThat(bridge.instruction()).isEqualTo(DefaultAgentTurnService.CODE_QUERY_INSTRUCTIONS);
        CodeQuery query = new CodeQuery(CodeQuery.Operation.SYMBOLS, "src/main.go", Optional.of("cleanup"), OptionalInt.empty(), OptionalInt.empty(), 20);
        assertThat(bridge.query().apply(query)).isEqualTo("symbols:cleanup");
        assertThat(queriedWorktree).isEqualTo(task.taskWorktree().orElseThrow());
    }

    @Test
    void languageRuntimeFailuresRemainFailOpenForTheAgent()
    {
        codeQueryUnavailable = true;
        service.start(command("first", "key-1"));
        CodeQuery query = new CodeQuery(CodeQuery.Operation.DOCUMENT_SYMBOLS, "src/main.go", Optional.empty(), OptionalInt.empty(), OptionalInt.empty(), 20);
        assertThat(driver.openedContexts().getFirst().codeQueries().orElseThrow().query().apply(query))
                .contains("unavailable", "normal source and search", "IDE not ready");
    }

    @Test
    void languageQueriesAreAvailableToNewTasks()
    {
        AgentTask newTask = interactiveTask("Code query");
        service.start(new StartAgentTurnCommand(newTask.id(), "first", ImmutableList.<AgentAttachment>of(), "key-off"));
        assertThat(driver.openedContexts().getFirst().codeQueries()).isPresent();
    }

    private StartAgentTurnCommand command(String message, String idempotencyKey)
    {
        return new StartAgentTurnCommand(task.id(), message, ImmutableList.<AgentAttachment>of(), idempotencyKey);
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
        return new ProviderUsageSample(Optional.of(eventId), ProviderUsageSample.Semantics.DELTA, input, cached, output, Optional.<BigDecimal>empty());
    }

    private static ProviderApprovalRequest approvalRequest(String requestId)
    {
        return new ProviderApprovalRequest(
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
        @Override
        public void withdraw(ApprovalId approvalId)
        {
            throw new UnsupportedOperationException();
        }

        private final List<ApprovalRequest> requests = new ArrayList<>();
        private final List<TurnId> deniedTurns = new ArrayList<>();
        private Runnable beforeDeny = () -> {};
        private RuntimeException denyFailure;

        @Override
        public List<ApprovalRequest> listPending(TurnId turnId)
        {
            return ImmutableList.of();
        }

        @Override
        public CompletionStage<ApprovalDecision> request(ApprovalRequest request, Duration timeout)
        {
            requests.add(request);
            return CompletableFuture.completedFuture(new ApprovalDecision(request.id(), ApprovalDecision.Decision.ALLOW, Optional.of("command:run"), false, NOW, false));
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

    /** Fails the first drain of a queued user message, which is the last step before the provider is reached. */
    private static final class FailQueuedMessageDrainEventService
            implements AgentEventService
    {
        private final AgentEventService delegate;
        private final InMemoryAgentStores stores;
        private boolean failed;

        private FailQueuedMessageDrainEventService(AgentEventService delegate, InMemoryAgentStores stores)
        {
            this.delegate = delegate;
            this.stores = stores;
        }

        @Override
        public AgentEventEnvelope append(UnsequencedAgentEvent event)
        {
            return delegate.append(event);
        }

        @Override
        public AgentEventEnvelope drain(EventId eventId)
        {
            boolean queuedMessage = stores.find(eventId)
                    .map(entry -> entry.payload() instanceof AgentEventOutboxPayload.UserMessageBlob blob
                            && blob.deliveryKind() == AgentEventPayload.DeliveryKind.QUEUED)
                    .orElse(false);
            if (queuedMessage && !failed) {
                failed = true;
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
        public AgentHistoryPage readHistory(TaskId taskId, OptionalLong before, OptionalLong after)
        {
            return delegate.readHistory(taskId, before, after);
        }

        @Override
        public Flow.Publisher<AgentEventEnvelope> subscribe(TaskId taskId, long afterSequence)
        {
            return delegate.subscribe(taskId, afterSequence);
        }
    }

    private static final class FailLifecycleDrainEventService
            implements AgentEventService
    {
        private final AgentEventService delegate;
        private final InMemoryAgentStores stores;
        private final AgentEventPayload.LifecyclePhase phase;
        private boolean fail;

        private FailLifecycleDrainEventService(AgentEventService delegate, InMemoryAgentStores stores, AgentEventPayload.LifecyclePhase phase)
        {
            this(delegate, stores, phase, true);
        }

        private FailLifecycleDrainEventService(AgentEventService delegate, InMemoryAgentStores stores, AgentEventPayload.LifecyclePhase phase, boolean fail)
        {
            this.delegate = delegate;
            this.stores = stores;
            this.phase = phase;
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
            boolean targetLifecycle = stores.find(eventId)
                    .map(AgentEventOutboxEntry::payload)
                    .filter(AgentEventOutboxPayload.Inline.class::isInstance)
                    .map(AgentEventOutboxPayload.Inline.class::cast)
                    .map(AgentEventOutboxPayload.Inline::payload)
                    .filter(AgentEventPayload.Lifecycle.class::isInstance)
                    .map(AgentEventPayload.Lifecycle.class::cast)
                    .filter(lifecycle -> lifecycle.phase() == phase)
                    .isPresent();
            if (targetLifecycle && fail) {
                fail = false;
                throw new AgentStorageException("blocked lifecycle event");
            }
            return delegate.drain(eventId);
        }

        @Override
        public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
        {
            return delegate.readAfter(taskId, afterSequence, limit);
        }

        @Override
        public AgentHistoryPage readHistory(TaskId taskId, OptionalLong before, OptionalLong after)
        {
            return delegate.readHistory(taskId, before, after);
        }

        @Override
        public Flow.Publisher<AgentEventEnvelope> subscribe(TaskId taskId, long afterSequence)
        {
            return delegate.subscribe(taskId, afterSequence);
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
        public AgentHistoryPage readHistory(TaskId taskId, OptionalLong before, OptionalLong after)
        {
            return delegate.readHistory(taskId, before, after);
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

        private synchronized void advance(Duration duration)
        {
            instant = instant.plus(duration);
        }
    }
}
