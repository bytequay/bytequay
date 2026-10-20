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
import com.bytequay.agent.core.AgentAttachmentReference;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentQueuedInputDraft;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.CodeQueryService;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnCompletion;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InputDeliveryId;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProviderAgentEventAdapter;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.QueuedInputNotFoundException;
import com.bytequay.agent.core.ReviewTurnInputResolver;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.SteerAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.SteerDeliveryStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentDeliveryUncertainException;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.AgentTurnInput;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.CodeQuery;
import com.bytequay.agent.spi.CodeQueryBridge;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.google.common.collect.ImmutableList;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingInline;
import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingLifecycle;
import static com.bytequay.agent.core.AgentEventSource.root;
import static com.bytequay.agent.core.impl.AgentEventOutboxDrainer.drainPending;
import static com.bytequay.agent.core.impl.AgentFailures.failureMessage;
import static com.bytequay.agent.core.impl.AgentFailures.message;
import static com.bytequay.agent.core.impl.AgentFailures.suppressFailure;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentTurnService
        implements AgentTurnService
{
    static final String CODE_QUERY_INSTRUCTIONS = """
            ByteQuay provides code_query for Java, Go and Rust in this task's own working copy. \
            It shares the IDE's language services. Use SYMBOLS for a name search, DOCUMENT_SYMBOLS \
            for an outline, or DEFINITIONS, REFERENCES, IMPLEMENTATIONS, INCOMING_CALLS and \
            OUTGOING_CALLS at a source position. DIAGNOSTICS reads currently published diagnostics; \
            no diagnostics is not proof of a successful build or test. Always name a repository-relative \
            source path to activate its language. Positions and ranges use one-based lines and UTF-16 \
            columns. Read current source before choosing a position. Supply a limit from 1 to 200. \
            SYMBOLS also requires query; positional operations require line and column. \
            Results include task, revision and working-copy fingerprint. They cover imported projects \
            and can be partial while indexing. Check status and truncation, inspect the actual source, \
            and use compiler/test/runtime evidence for correctness. Stale or unsaved results must be \
            retried after the source is reconciled. If unavailable or unsupported, continue with normal \
            source and text search; do not install or start another index. This is not natural-language \
            architecture search or a repository dependency graph. Use ordinary source search for those \
            questions and never describe text matches as semantic callers.""";

    private static final System.Logger log = System.getLogger(DefaultAgentTurnService.class.getName());
    private static final System.Logger sendTiming = System.getLogger("com.bytequay.agent.SendTiming");

    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final QueuedInputStore queueStore;
    private final SteerDeliveryStore steerStore;
    private final AgentUnitOfWork unitOfWork;
    private final AgentEventOutboxStore outboxStore;
    private final TaskLogStore logStore;
    private final AgentResourceService resourceService;
    private final AgentDriverRegistry driverRegistry;
    private final AgentEventService eventService;
    private final AgentEventCommitter eventCommitter;
    private final AgentMetricsService metricsService;
    private final AgentApprovalService approvalService;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final CodeQueryService codeQueries;
    private final Function<AgentTask, String> managedInstructions;
    private final ReviewTurnInputResolver reviewTurnInput;
    private final Clock clock;
    private final Duration maximumTurnRuntime;
    /** Runs the queue's next turn off the thread that finished the last one, which may be the provider's own. */
    private final Executor queueStarter;
    private final Predicate<AgentTask> developmentEligibility;
    private final BiConsumer<AgentTask, AgentTurn> developmentAccepted;
    private final Consumer<DevelopmentTurnCompletion> developmentCompletion;
    /** Stops linked CI repairs still writing an interactive task's branch, before a user's turn or once its automation stops. */
    private final Consumer<TaskId> stopChildren;
    private final UnaryOperator<AgentTask> idleWorktreePreparation;
    /**
     * Each turn from its launch until its terminal state is saved, with the log sequence of its first event. A provider
     * event for a turn missing here came too late, and counting a turn's tools reads back no further than its first event.
     */
    private final ConcurrentMap<TurnId, Long> launchedTurns = new ConcurrentHashMap<>();

    /** How long a closed process may take to report its turn interrupted before the stop is a failure. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    /** Refuses a key another kind of input already used. The desktop matches this text to clear a draft it already sent. */
    static final String ALREADY_SENT = "This message was already sent";

    /** How long a passed readiness check spares the next Send on that task from launching the provider CLI again. */
    private static final Duration READINESS_REUSE = Duration.ofMinutes(10);

    /**
     * Until when each task's lane and model count as ready for a Send. A failed check is never kept, so a fixed sign-in
     * applies to the next Send; a sign-out inside the window surfaces as that turn's own provider failure.
     */
    private final ConcurrentMap<String, Instant> readyUntil = new ConcurrentHashMap<>();

    public DefaultAgentTurnService(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            QueuedInputStore queueStore,
            SteerDeliveryStore steerStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            TaskLogStore logStore,
            AgentResourceService resourceService,
            AgentDriverRegistry driverRegistry,
            AgentEventService eventService,
            AgentMetricsService metricsService,
            AgentApprovalService approvalService,
            AgentRuntimeRegistry runtimeRegistry,
            CodeQueryService codeQueries,
            Function<AgentTask, String> managedInstructions,
            ReviewTurnInputResolver reviewTurnInput,
            Clock clock,
            Duration maximumTurnRuntime,
            Executor queueStarter,
            Predicate<AgentTask> developmentEligibility,
            BiConsumer<AgentTask, AgentTurn> developmentAccepted,
            Consumer<DevelopmentTurnCompletion> developmentCompletion,
            Consumer<TaskId> stopChildren,
            UnaryOperator<AgentTask> idleWorktreePreparation)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.queueStore = requireNonNull(queueStore, "queueStore is null");
        this.steerStore = requireNonNull(steerStore, "steerStore is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.logStore = requireNonNull(logStore, "logStore is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.driverRegistry = requireNonNull(driverRegistry, "driverRegistry is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.eventCommitter = new AgentEventCommitter(unitOfWork, outboxStore, eventService);
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.approvalService = requireNonNull(approvalService, "approvalService is null");
        this.runtimeRegistry = requireNonNull(runtimeRegistry, "runtimeRegistry is null");
        this.codeQueries = requireNonNull(codeQueries, "codeQueries is null");
        this.managedInstructions = requireNonNull(managedInstructions, "managedInstructions is null");
        this.reviewTurnInput = requireNonNull(reviewTurnInput, "reviewTurnInput is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.maximumTurnRuntime = requireNonNull(maximumTurnRuntime, "maximumTurnRuntime is null");
        checkArgument(!maximumTurnRuntime.isNegative(), "maximumTurnRuntime must not be negative");
        this.queueStarter = requireNonNull(queueStarter, "queueStarter is null");
        this.developmentEligibility = requireNonNull(developmentEligibility, "developmentEligibility is null");
        this.developmentAccepted = requireNonNull(developmentAccepted, "developmentAccepted is null");
        this.developmentCompletion = requireNonNull(developmentCompletion, "developmentCompletion is null");
        this.stopChildren = requireNonNull(stopChildren, "stopChildren is null");
        this.idleWorktreePreparation = requireNonNull(idleWorktreePreparation, "idleWorktreePreparation is null");
    }

    @Override
    public AgentTurn start(StartAgentTurnCommand command)
    {
        requireNonNull(command, "command is null");
        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=turn.recovery.begin epochMs=" + System.currentTimeMillis() + " taskId=" + command.taskId() + " turnId=-");
        retryCompletedTurn(command.taskId());
        sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=turn.recovery.end epochMs=" + System.currentTimeMillis() + " taskId=" + command.taskId() + " turnId=-");
        if (!command.useTrustedTaskCreationReadiness() && turnStore.findByIdempotencyKey(command.taskId(), command.idempotencyKey()).isEmpty()) {
            // A cold check launches the provider CLI several times. Nothing else on this task, such as a retry looking up
            // its key, may wait behind it, so it runs before the task lock; the check under the lock then reuses it.
            requireRecentlyReady(taskStore.require(command.taskId()));
            stopChildWriters(command.taskId());
        }
        TurnAcceptance acceptance;
        sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=turn.lock.wait epochMs=" + System.currentTimeMillis() + " taskId=" + command.taskId() + " turnId=-");
        synchronized (runtimeRegistry.taskLock(command.taskId())) {
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.lock.acquired epochMs=" + System.currentTimeMillis() + " taskId=" + command.taskId() + " turnId=-");
            drainPending(outboxStore, eventService, command.taskId());
            Optional<AgentTurn> repeated = turnStore.findByIdempotencyKey(command.taskId(), command.idempotencyKey());
            if (repeated.isPresent()) {
                return repeated.orElseThrow();
            }
            requireUnusedKey(command.taskId(), command.idempotencyKey());

            command.expectedTaskVersion().ifPresent(expectedVersion -> {
                if (taskStore.require(command.taskId()).version() != expectedVersion) {
                    throw new AgentConflictException("Task version changed: " + command.taskId());
                }
            });
            // Recovery may update the saved branch; the caller's version describes the task before that internal repair.
            AgentTask task = requireStartableTask(command);
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.preflight.end epochMs=" + System.currentTimeMillis() + " taskId=" + command.taskId() + " turnId=-");
            acceptance = acceptTurnLocked(task, command);
        }
        return launchAccepted(acceptance);
    }

    @Override
    public AgentTask claimLocalRun(TaskId taskId, String runId)
    {
        requireNonNull(taskId, "taskId is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = requireStartableTaskWithoutReadiness(taskId);
            if (queueStore.listQueued(taskId).stream().anyMatch(input -> !input.state().historical())) {
                throw new AgentConflictException("Remove or resume the queued question");
            }
            Instant now = Instant.now(clock);
            return updateWorktreeTask(task.claimWorktreeMutation(WorktreeMutationClaim.localRun(runId, now), now));
        }
    }

    @Override
    public void releaseLocalRun(TaskId taskId, String runId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(runId, "runId is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = taskStore.require(taskId);
            task.worktreeMutationClaim()
                    .filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.LOCAL_RUN && claim.ownerId().equals(runId))
                    .ifPresent(claim -> updateWorktreeTask(task.releaseWorktreeMutation(claim, Instant.now(clock))));
        }
    }

    @Override
    public void synchronizeWorktree(TaskId taskId, long expectedVersion, Consumer<AgentTask> action)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(action, "action is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            if (taskStore.require(taskId).version() != expectedVersion) {
                throw new AgentConflictException("Task version changed: " + taskId);
            }
            // As for Send, a verified branch repair may advance the version while this lock excludes other writers.
            AgentTask task = requireStartableTaskWithoutReadiness(taskId);
            if (queueStore.listQueued(taskId).stream().anyMatch(input -> !input.state().historical())) {
                throw new AgentConflictException("Remove queued input before updating the review worktree");
            }
            Instant now = Instant.now(clock);
            // The existing completion claim is recovered only after quiescence if the backend stops here.
            WorktreeMutationClaim claim = new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, "review-worktree:" + taskId, now);
            AgentTask claimed = updateWorktreeTask(task.claimWorktreeMutation(claim, now));
            try {
                action.accept(claimed);
                AgentTask current = taskStore.require(taskId);
                Instant finishedAt = Instant.now(clock);
                updateWorktreeTask(current.releaseWorktreeMutation(claim, finishedAt));
            }
            catch (RuntimeException failure) {
                suppressFailure(failure, () -> {
                    AgentTask current = taskStore.require(taskId);
                    if (current.worktreeMutationClaim().equals(Optional.of(claim))) {
                        updateWorktreeTask(current.releaseWorktreeMutation(claim, Instant.now(clock)));
                    }
                });
                throw failure;
            }
        }
    }

    /** A worktree writer changes the version used by every task action, including the composer. */
    private AgentTask updateWorktreeTask(AgentTask task)
    {
        AgentEventOutboxEntry event = pendingLifecycle(EventId.random(), task.id(), Optional.empty(),
                AgentEventPayload.LifecyclePhase.WORKTREE_UPDATED, "Working copy updated", task.updatedAt());
        AgentTask updated = unitOfWork.execute(() -> {
            AgentTask stored = taskStore.update(task);
            outboxStore.insert(event);
            return stored;
        });
        try {
            eventService.drain(event.eventId());
        }
        catch (RuntimeException failure) {
            // The ownership change is already durable. A notification failure must not strand a
            // successfully claimed run or turn a released writer into a failed operation; the
            // outbox retains this refresh for the next task request or startup recovery.
            log.log(System.Logger.Level.WARNING, "Working-copy refresh will retry for task " + task.id(), failure);
        }
        return updated;
    }

    @Override
    public CompletionStage<DevelopmentTurnCompletion> startGeneratedTurn(TaskId taskId, DevelopmentTurnOrigin origin, String message)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(origin, "origin is null");
        checkText(message, "message");
        TurnAcceptance acceptance;
        CompletableFuture<DevelopmentTurnCompletion> completion;
        synchronized (runtimeRegistry.taskLock(taskId)) {
            requireDevelopmentOwner(taskId, origin.parentUserTurnId());
            Optional<CompletableFuture<DevelopmentTurnCompletion>> repeated = runtimeRegistry.developmentTurn(taskId, origin);
            if (repeated.isPresent()) {
                return repeated.orElseThrow();
            }
            AgentTask task = requireReady(requireStartableTaskWithoutReadiness(taskId, Optional.of(origin.parentUserTurnId())));
            boolean refinement = origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY;
            AgentTurn source = requireContinuationReady(taskId, origin.parentUserTurnId(), !refinement);
            AgentTurnSettings settings = source.settings().orElseThrow();
            DevelopmentAutomation automation = settings.developmentAutomation().orElse(DevelopmentAutomation.LOCAL_ONLY);
            boolean enabled = switch (origin.kind()) {
                case AUTO_COMMIT -> automation.autoCommit();
                case CREATE_DRAFT_PR -> automation.autoDraftPr();
                case REFINE_SUMMARY -> true;
            };
            checkArgument(enabled, "The accepted automation settings do not allow this follow-up");
            List<AgentAttachment> attachments = settings.attachments().stream()
                    .map(reference -> resourceService.readAttachment(taskId, reference.id()).input())
                    .collect(toImmutableList());
            completion = runtimeRegistry.registerDevelopmentTurn(taskId, origin);
            try {
                acceptance = acceptTurnLocked(task, "development:" + origin.parentUserTurnId() + ":" + origin.kind(), message, attachments, settings, Optional.of(origin));
            }
            catch (RuntimeException failure) {
                completion.completeExceptionally(failure);
                return completion;
            }
        }
        try {
            launchAccepted(acceptance);
        }
        catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    @Override
    public boolean handoffDevelopmentContinuationToQueue(TaskId taskId, TurnId sourceTurnId)
    {
        TurnAcceptance acceptance;
        synchronized (runtimeRegistry.taskLock(taskId)) {
            requireDevelopmentOwner(taskId, sourceTurnId);
            boolean delivery = turnStore.require(sourceTurnId).settings().flatMap(AgentTurnSettings::developmentAutomation).filter(DevelopmentAutomation::enabled).isPresent();
            requireContinuationReady(taskId, sourceTurnId, delivery);
            checkArgument(!waitingForDevelopmentAnswer(taskId), "Development work is waiting for a user answer");
            Optional<QueuedAgentInput> head = queueHead(taskId).filter(input -> input.state() == QueuedAgentInput.State.QUEUED);
            if (head.isEmpty()) {
                return false;
            }
            AgentTask task = requireReady(requireStartableTaskWithoutReadiness(taskId, Optional.of(sourceTurnId)), head.orElseThrow().settings());
            // The queued turn's claim is stored before releasing this owner. Send never sees an
            // unclaimed gap, including while the next turn captures its baseline outside the lock.
            acceptance = acceptQueuedLocked(task.withDevelopmentContinuation(false, Instant.now(clock)), head.orElseThrow());
            runtimeRegistry.releaseDevelopmentOwner(taskId, sourceTurnId);
        }
        launchAccepted(acceptance);
        return true;
    }

    @Override
    public void finishDevelopmentContinuation(TaskId taskId, TurnId sourceTurnId)
    {
        synchronized (runtimeRegistry.taskLock(taskId)) {
            requireDevelopmentOwner(taskId, sourceTurnId);
            AgentTask task = requireStartableTaskWithoutReadiness(taskId, Optional.of(sourceTurnId));
            updateDevelopmentMarker(task, false, "Development automation settled");
            runtimeRegistry.releaseDevelopmentOwner(taskId, sourceTurnId);
        }
        drainPending(outboxStore, eventService, taskId);
    }

    private AgentTask updateDevelopmentMarker(AgentTask task, boolean unfinished, String message)
    {
        Instant now = Instant.now(clock);
        return unitOfWork.execute(() -> {
            AgentTask updated = taskStore.update(task.withDevelopmentContinuation(unfinished, now));
            outboxStore.insert(pendingLifecycle(EventId.random(), task.id(), Optional.empty(), AgentEventPayload.LifecyclePhase.DEVELOPMENT_AUTOMATION_UPDATED, message, now));
            return updated;
        });
    }

    @Override
    public AgentTask failDevelopmentContinuation(TaskId taskId, TurnId sourceTurnId, String reason)
    {
        requireNonNull(sourceTurnId, "sourceTurnId is null");
        checkText(reason, "reason");
        synchronized (runtimeRegistry.stopLock(taskId)) {
            List<CompletableFuture<DevelopmentTurnCompletion>> pending;
            synchronized (runtimeRegistry.taskLock(taskId)) {
                AgentTask task = taskStore.require(taskId);
                if (runtimeRegistry.closing() || task.state().terminal() || !task.developmentContinuationUnfinished()) {
                    return task;
                }
                Optional<TurnId> owner = runtimeRegistry.developmentOwner(taskId);
                if (owner.isPresent() && !owner.orElseThrow().equals(sourceTurnId)
                        || !latestDevelopmentSource(taskId, sourceTurnId)) {
                    throw new AgentConflictException("Development continuation owner changed: " + taskId);
                }
                pending = runtimeRegistry.stopDevelopment(taskId);
            }
            pending.forEach(future -> future.completeExceptionally(new AgentConflictException(reason)));
            return stopDevelopmentContinuation(taskId, sourceTurnId, reason);
        }
    }

    @Override
    public AgentTask failLostDevelopmentContinuation(TaskId taskId)
    {
        synchronized (runtimeRegistry.stopLock(taskId)) {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                AgentTask task = taskStore.require(taskId);
                if (task.state().terminal() || !task.developmentContinuationUnfinished() || runtimeRegistry.hasDevelopmentOwner(taskId) || runtimeRegistry.closing()) {
                    return task;
                }
                runtimeRegistry.stopDevelopment(taskId);
            }
            stopChildren.accept(taskId);
            synchronized (runtimeRegistry.taskLock(taskId)) {
                AgentTask task = taskStore.require(taskId);
                if (task.worktreeMutationClaim().isPresent() || turnStore.findActive(taskId).isPresent() || runtimeRegistry.unverifiedClose(taskId).isPresent()) {
                    return task;
                }
            }
            return settleStoppedDevelopmentContinuation(taskId, "Backend restarted during development automation. Send another message to continue.");
        }
    }

    private AgentTask stopDevelopmentContinuation(TaskId taskId, TurnId sourceTurnId, String reason)
    {
        Optional<AgentTurn> active;
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = taskStore.require(taskId);
            if (task.state().terminal() || runtimeRegistry.closing() || !task.developmentContinuationUnfinished()) {
                return task;
            }
            if (!latestDevelopmentSource(taskId, sourceTurnId)) {
                throw new AgentConflictException("Development continuation owner changed: " + taskId);
            }
            active = turnStore.findActive(taskId);
            active.ifPresent(turn -> turnStore.update(turn.requestCancellation(Instant.now(clock))));
        }
        stopDevelopmentRuntimes(taskId, active);
        return settleStoppedDevelopmentContinuation(taskId, reason);
    }

    private AgentTask settleStoppedDevelopmentContinuation(TaskId taskId, String reason)
    {
        AgentTask stopped;
        synchronized (runtimeRegistry.taskLock(taskId)) {
            releaseStaleTurnClaim(taskStore.require(taskId));
            AgentTask task = taskStore.require(taskId);
            if (task.worktreeMutationClaim().isPresent() || turnStore.findActive(taskId).isPresent() || runtimeRegistry.unverifiedClose(taskId).isPresent()) {
                throw new AgentConflictException("Development automation stopped, but its provider could not be verified stopped: " + taskId);
            }
            if (!task.developmentContinuationUnfinished()) {
                return task;
            }
            String message = reason.startsWith("Automation stopped.") ? reason : "Automation stopped. " + reason;
            stopped = unitOfWork.execute(() -> {
                Instant now = Instant.now(clock);
                runtimeRegistry.latestAcceptedTurn(taskId).map(turnStore::require)
                        .or(() -> turnStore.list(taskId).reversed().stream().findFirst())
                        .filter(turn -> turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent())
                        .filter(turn -> turn.developmentResult().flatMap(DevelopmentResultReport::learningSummary).isPresent())
                        .ifPresent(turn -> turnStore.update(turn.withoutLearningSummary()));
                for (QueuedAgentInput input : queueStore.listQueued(taskId)) {
                    if (input.state() == QueuedAgentInput.State.QUEUED) {
                        queueStore.update(input.paused(message, now));
                    }
                }
                AgentTask updated = taskStore.update(task.withDevelopmentContinuation(false, now));
                outboxStore.insert(pendingLifecycle(EventId.random(), taskId, Optional.empty(), AgentEventPayload.LifecyclePhase.DEVELOPMENT_AUTOMATION_UPDATED, message, now));
                return updated;
            });
            runtimeRegistry.developmentOwner(taskId).ifPresent(owner -> runtimeRegistry.releaseDevelopmentOwner(taskId, owner));
        }
        drainPending(outboxStore, eventService, taskId);
        return stopped;
    }

    private boolean latestDevelopmentSource(TaskId taskId, TurnId sourceTurnId)
    {
        return runtimeRegistry.latestAcceptedTurn(taskId).map(turnStore::require)
                .map(turn -> turn.developmentOrigin().map(DevelopmentTurnOrigin::parentUserTurnId).orElse(turn.id()))
                .filter(sourceTurnId::equals).isPresent();
    }

    private void stopDevelopmentRuntimes(TaskId taskId, Optional<AgentTurn> active)
    {
        runtimeRegistry.drainDevelopmentActions(taskId);
        try {
            stopChildren.accept(taskId);
        }
        catch (RuntimeException failure) {
            suppressFailure(failure, () -> stopProviderRuntime(taskId, active));
            throw failure;
        }
        stopProviderRuntime(taskId, active);
    }

    private void stopProviderRuntime(TaskId taskId, Optional<AgentTurn> active)
    {
        RuntimeException failure = null;
        try {
            active.ifPresent(turn -> approvalService.denyPending(turn.id()));
        }
        catch (RuntimeException approvalFailure) {
            failure = approvalFailure;
        }
        try {
            runtimeRegistry.closeSession(taskId);
            active.ifPresent(this::awaitInterruption);
        }
        catch (RuntimeException stopFailure) {
            if (failure == null) {
                failure = stopFailure;
            }
            else if (failure != stopFailure) {
                failure.addSuppressed(stopFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean ownsDevelopmentContinuation(TaskId taskId, TurnId sourceTurnId)
    {
        return runtimeRegistry.ownsDevelopmentContinuation(taskId, sourceTurnId) && !runtimeRegistry.closing();
    }

    @Override
    public void executeDevelopmentAction(TaskId taskId, TurnId sourceTurnId, Runnable action)
    {
        runtimeRegistry.executeDevelopmentAction(taskId, sourceTurnId, () -> {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                requireDevelopmentOwner(taskId, sourceTurnId);
                requireContinuationReady(taskId, sourceTurnId);
                requireStartableTaskWithoutReadiness(taskId, Optional.of(sourceTurnId));
            }
            action.run();
        });
    }

    @Override
    public void reportDevelopmentProgress(TaskId taskId, TurnId sourceTurnId, String message)
    {
        checkText(message, "message");
        checkArgument(message.length() <= 8 * 1024, "Development progress message is too long");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            requireDevelopmentOwner(taskId, sourceTurnId);
            outboxStore.insert(pendingLifecycle(EventId.random(), taskId, Optional.empty(),
                    AgentEventPayload.LifecyclePhase.DEVELOPMENT_AUTOMATION_UPDATED, message, Instant.now(clock)));
        }
        drainPending(outboxStore, eventService, taskId);
    }

    private void requireDevelopmentOwner(TaskId taskId, TurnId sourceTurnId)
    {
        if (!ownsDevelopmentContinuation(taskId, sourceTurnId)) {
            throw new AgentConflictException("Development continuation is no longer active: " + taskId);
        }
    }

    private AgentTurn requireContinuationReady(TaskId taskId, TurnId sourceTurnId)
    {
        return requireContinuationReady(taskId, sourceTurnId, true);
    }

    private AgentTurn requireContinuationReady(TaskId taskId, TurnId sourceTurnId, boolean requireDeliveryReport)
    {
        AgentTurn source = turnStore.require(sourceTurnId);
        checkArgument(source.taskId().equals(taskId) && source.developmentOrigin().isEmpty(), "Invalid development source turn");
        for (AgentTurn prior : continuationTurns(taskId, sourceTurnId)) {
            checkArgument(prior.state() == TurnState.COMPLETED, "Development turn did not complete successfully");
            checkArgument(prior.cancellationRequestedAt().isEmpty(), "Development turn was cancelled");
            checkArgument(!requireDeliveryReport || prior.developmentResult().filter(DevelopmentResultReport::readyForDelivery).isPresent(),
                    "Development work is waiting for a readiness report or user answer");
        }
        return source;
    }

    private List<AgentTurn> continuationTurns(TaskId taskId, TurnId sourceTurnId)
    {
        return turnStore.list(taskId).stream()
                .filter(turn -> turn.id().equals(sourceTurnId)
                        || turn.developmentOrigin().map(origin -> origin.parentUserTurnId().equals(sourceTurnId)).orElse(false))
                .collect(toImmutableList());
    }

    @Override
    public Optional<AgentTurn> findTurnByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        checkText(idempotencyKey, "idempotencyKey");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            taskStore.require(taskId);
            return turnStore.findByIdempotencyKey(taskId, idempotencyKey);
        }
    }

    /** Prepares accepted review input and development scope outside the task lock, before provider launch. */
    private AgentTurn launchAccepted(TurnAcceptance acceptance)
    {
        AgentTurn turn = acceptance.turn();
        TaskId taskId = turn.taskId();
        Launch launch;
        boolean launchAttempted = false;
        try {
            AgentTask task;
            synchronized (runtimeRegistry.taskLock(taskId)) {
                task = requireAcceptedLaunch(turn);
                // The development baseline capture below takes seconds of Git reads; publish
                // the accepted message first so the transcript shows it while Send is still waiting.
                launchedTurns.put(turn.id(), eventService.drain(acceptance.deliveryEventId()).sequence());
            }
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.user_message.published epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=" + turn.id());
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.review_input.begin epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=" + turn.id());
            ReviewTurnInputResolver.Reference reference = reviewTurnInput.reference(task, turn);
            String message = reviewTurnInput.prepare(task, turn, reference.append(acceptance.message()));
            checkText(message, "prepared review input");
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.review_input.end epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=" + turn.id());
            if (turn.developmentOrigin().isEmpty() && developmentTurnEligible(task, turn)) {
                sendTiming.log(System.Logger.Level.DEBUG,
                        () -> "send-timing phase=turn.development_baseline.begin epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=" + turn.id());
                developmentAccepted.accept(task, turn);
                sendTiming.log(System.Logger.Level.DEBUG,
                        () -> "send-timing phase=turn.development_baseline.end epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=" + turn.id());
            }
            synchronized (runtimeRegistry.taskLock(taskId)) {
                task = requireAcceptedLaunch(turn);
                if (turn.developmentOrigin().isEmpty() && task.developmentContinuationUnfinished()) {
                    task = taskStore.update(task.withDevelopmentContinuation(false, Instant.now(clock)));
                }
                launchAttempted = true;
                launch = launchLocked(task, new TurnAcceptance(turn, acceptance.deliveryEventId(), message, acceptance.attachments(), reference));
            }
        }
        catch (RuntimeException failure) {
            if (launchAttempted) {
                throw failure;
            }
            synchronized (runtimeRegistry.taskLock(taskId)) {
                if (runtimeRegistry.closing()) {
                    throw failure;
                }
                recordKnownUnsentFailure(turn, failure, runtimeRegistry.unverifiedClose(taskId).isEmpty());
                if (turn.developmentOrigin().isEmpty() && developmentTurnEligible(taskStore.require(taskId), turn)) {
                    reserveDevelopmentContinuation(turnStore.require(turn.id()));
                }
                launch = new Launch(turn, Optional.empty(), Optional.of(failure));
            }
        }
        return settleLaunch(launch);
    }

    private AgentTask requireAcceptedLaunch(AgentTurn accepted)
    {
        AgentTask task = taskStore.require(accepted.taskId());
        AgentTurn turn = turnStore.require(accepted.id());
        if (runtimeRegistry.closing()) {
            throw new AgentConflictException("Backend is shutting down");
        }
        if (turn.developmentOrigin().isPresent()) {
            requireDevelopmentOwner(task.id(), turn.developmentOrigin().orElseThrow().parentUserTurnId());
        }
        else if (runtimeRegistry.hasDevelopmentOwner(task.id())) {
            throw new AgentConflictException("Development automation owns this task: " + task.id());
        }
        if (task.state() != TaskState.OPEN || turn.state() != TurnState.ACCEPTED || turn.cancellationRequestedAt().isPresent()) {
            throw new AgentConflictException("Accepted turn can no longer start: " + turn.id());
        }
        WorktreeMutationClaim claim = WorktreeMutationClaim.turn(turn.id(), turn.createdAt());
        if (!task.worktreeMutationClaim().equals(Optional.of(claim))) {
            throw new AgentConflictException("Accepted turn no longer owns its worktree: " + turn.id());
        }
        runtimeRegistry.unverifiedClose(task.id()).ifPresent(failure -> {
            throw new AgentConflictException("Task provider process is not verified stopped: " + failureMessage(failure));
        });
        runtimeRegistry.checkBranchAdmission(task);
        return task;
    }

    /**
     * Sends an accepted turn to the provider.
     *
     * <p>A failure before the send is recorded as a known-unsent failed turn here; a failure after
     * it leaves the turn as it is, because the provider may be running it.
     */
    private Launch launchLocked(AgentTask task, TurnAcceptance acceptance)
    {
        AgentTurn accepted = acceptance.turn();
        boolean deliveryAttempted = false;
        AgentSession session = null;
        Optional<RunningTurn> started = Optional.empty();
        try {
            AgentTurnSettings settings = accepted.settings().orElseThrow();
            eventService.drain(acceptance.deliveryEventId());
            session = session(task, accepted.id(), settings);
            markDeliveryStarted(accepted, acceptance.reference());
            deliveryAttempted = true;
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.provider_send.begin epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + accepted.id());
            RunningTurn runningTurn = session.start(new AgentTurnCommand(
                    accepted.id().toString(),
                    acceptance.message(),
                    acceptance.attachments(),
                    settings.maximumRuntime(),
                    settings.model(),
                    settings.effort(),
                    settings.serviceTier()));
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.provider_send.end epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + accepted.id());
            started = Optional.of(runningTurn);
            registerRunningTurn(accepted, runningTurn, session.reference());
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.running.persisted epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + accepted.id());
            try {
                reviewTurnInput.delivered(task, turnStore.require(accepted.id()));
            }
            catch (RuntimeException ignored) {
                // Provider delivery is already durable; usage projection may be retried from the saved offer.
            }
            return new Launch(accepted, started, Optional.empty());
        }
        catch (RuntimeException failure) {
            // A started turn is observed and cancelled by the caller. Otherwise a provider reports a start it cannot rule out
            // as uncertain, and any other failure means it did not start the turn.
            if (started.isEmpty()) {
                boolean uncertain = deliveryAttempted && failure instanceof AgentDeliveryUncertainException;
                boolean providerQuiescent = runtimeRegistry.unverifiedClose(task.id()).isEmpty();
                if (session != null) {
                    try {
                        // Nothing observes this session's process once its start failed, so it is stopped either way.
                        runtimeRegistry.closeSession(task.id());
                    }
                    catch (RuntimeException closeFailure) {
                        providerQuiescent = false;
                        if (failure != closeFailure) {
                            failure.addSuppressed(closeFailure);
                        }
                    }
                }
                if (!uncertain) {
                    recordKnownUnsentFailure(accepted, failure, providerQuiescent);
                }
                else {
                    AgentTurn current = turnStore.require(accepted.id());
                    if (current.state().active()) {
                        // The provider may have taken the turn, so it is never resent; its process is stopped, not left unobserved.
                        persistTerminalTurn(current, TurnState.INTERRUPTED, Optional.of(failureMessage(failure)), providerQuiescent);
                    }
                }
            }
            if (developmentTurnEligible(task, accepted)) {
                AgentTurn current = turnStore.require(accepted.id());
                if (current.state().active()) {
                    persistTerminalTurn(current, TurnState.INTERRUPTED, Optional.of(failureMessage(failure)), false);
                }
                reserveDevelopmentContinuation(turnStore.require(accepted.id()));
            }
            return new Launch(accepted, started, Optional.of(failure));
        }
    }

    /** Finishes a launch outside the task lock: watches the run, or cleans up and rethrows. */
    private AgentTurn settleLaunch(Launch launch)
    {
        AgentTurn turn = launch.turn();
        if (launch.failure().isPresent()) {
            RuntimeException failure = launch.failure().orElseThrow();
            launch.runningTurn().ifPresent(running -> {
                observeCompletion(turn, running);
                suppressFailure(failure, running::cancel);
            });
            suppressFailure(failure, () -> approvalService.denyPending(turn.id()));
            AgentTurn terminal = turnStore.require(turn.id());
            if (terminal.state().terminal() && developmentTurnEligible(taskStore.require(turn.taskId()), terminal)) {
                notifyDevelopmentCompletion(terminal.id(), Optional.of(failureMessage(failure)));
            }
            throw failure;
        }
        RunningTurn runningTurn = launch.runningTurn().orElseThrow();
        try {
            observeCompletion(turn, runningTurn);
        }
        catch (RuntimeException failure) {
            synchronized (runtimeRegistry.taskLock(turn.taskId())) {
                runtimeRegistry.removeRunning(turn.id(), runningTurn);
            }
            suppressFailure(failure, runningTurn::cancel);
            suppressFailure(failure, () -> approvalService.denyPending(turn.id()));
            throw failure;
        }
        return turnStore.require(turn.id());
    }

    @Override
    public AgentInputDelivery steer(SteerAgentTurnCommand command)
    {
        requireNonNull(command, "command is null");
        return steer(command.turnId(), command.idempotencyKey(), (task, turn) -> acceptSteerLocked(task, turn, command, Optional.empty()));
    }

    @Override
    public AgentInputDelivery steerQueued(TaskId taskId, TurnId turnId, QueuedInputId queuedInputId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(queuedInputId, "queuedInputId is null");
        if (!turnStore.require(turnId).taskId().equals(taskId) || !requireQueued(queuedInputId).taskId().equals(taskId)) {
            throw new AgentConflictException("Turn and queued input must belong to task " + taskId);
        }
        String idempotencyKey = "queue-steer:" + queuedInputId;
        return steer(turnId, idempotencyKey, (task, turn) -> {
            QueuedAgentInput input = requireQueued(queuedInputId);
            if ((input.state() != QueuedAgentInput.State.QUEUED && input.state() != QueuedAgentInput.State.PAUSED) || input.messageEventId().isPresent()) {
                throw new AgentConflictException("Queued input is no longer waiting for its first delivery: " + input.id());
            }
            List<AgentAttachment> attachments = input.settings().attachments().stream()
                    .map(reference -> resourceService.readAttachment(taskId, reference.id()).input())
                    .collect(toImmutableList());
            return acceptSteerLocked(task, turn, new SteerAgentTurnCommand(turnId, readUtf8(taskId, input.textBlob()), attachments, idempotencyKey), Optional.of(input));
        });
    }

    private AgentInputDelivery steer(TurnId turnId, String idempotencyKey, BiFunction<AgentTask, AgentTurn, SteerAcceptance> accept)
    {
        TaskId taskId = turnStore.require(turnId).taskId();
        retryCompletedTurn(taskId);
        // The provider has no steer idempotency key. Keep acceptance and the corresponding send in
        // one task-local order, while leaving the task state lock free during provider I/O.
        synchronized (runtimeRegistry.steerLock(taskId)) {
            SteerAcceptance acceptance;
            RunningTurn running;
            synchronized (runtimeRegistry.taskLock(taskId)) {
                drainPending(outboxStore, eventService, taskId);
                Optional<AgentInputDelivery> repeated = steerStore.findSteerByIdempotencyKey(taskId, idempotencyKey);
                if (repeated.isPresent()) {
                    AgentInputDelivery existing = repeated.orElseThrow();
                    if (!existing.turnId().equals(turnId)) {
                        throw steeredElsewhere(existing);
                    }
                    if (existing.state() == AgentInputDelivery.State.SENDING) {
                        return settleSteer(existing, AgentInputDelivery.State.UNKNOWN, Optional.of("Previous steer attempt ended without a recorded provider response"));
                    }
                    if (existing.state() != AgentInputDelivery.State.ACCEPTED) {
                        return existing;
                    }
                }
                else {
                    requireUnusedKey(taskId, idempotencyKey);
                }
                AgentTurn turn = turnStore.require(turnId);
                // The turn the user was looking at, and only that one: a turn that has since ended
                // is stale, and its successor must not receive input meant for it.
                if (turn.state() != TurnState.RUNNING) {
                    throw new AgentConflictException("Turn is not running: " + turn.id());
                }
                AgentTask task = taskStore.require(taskId);
                requireNoDevelopmentContinuation(task);
                if (!driverRegistry.require(task.laneId()).capabilities().steering()) {
                    throw new AgentUnavailableException("Provider " + task.laneId() + " cannot add input to a running turn");
                }
                running = runtimeRegistry.running(turn.id())
                        .orElseThrow(() -> new AgentConflictException("Turn has no live provider handle: " + turn.id()));
                if (repeated.isPresent()) {
                    AgentInputDelivery accepted = repeated.orElseThrow();
                    acceptance = new SteerAcceptance(accepted, EventId.steerMessage(accepted.id()), persistedSteerInput(accepted));
                }
                else {
                    acceptance = accept.apply(task, turn);
                }
                eventService.drain(acceptance.messageEventId());
                drainPending(outboxStore, eventService, taskId);
                steerStore.update(acceptance.delivery().sending(Instant.now(clock)));
            }

            try {
                running.steer(acceptance.input());
            }
            catch (AgentDeliveryUncertainException uncertain) {
                return settleSteer(acceptance.delivery(), AgentInputDelivery.State.UNKNOWN, Optional.of(message(uncertain)));
            }
            catch (RuntimeException refused) {
                return settleSteer(acceptance.delivery(), AgentInputDelivery.State.FAILED, Optional.of(message(refused)));
            }
            return settleSteer(acceptance.delivery(), AgentInputDelivery.State.DELIVERED, Optional.empty());
        }
    }

    @Override
    public Optional<AgentInputDelivery> findSteerByIdempotencyKey(TurnId turnId, String idempotencyKey)
    {
        requireNonNull(turnId, "turnId is null");
        checkText(idempotencyKey, "idempotencyKey");
        AgentTurn requestedTurn = turnStore.require(turnId);
        TaskId taskId = requestedTurn.taskId();
        synchronized (runtimeRegistry.taskLock(taskId)) {
            Optional<AgentInputDelivery> found = steerStore.findSteerByIdempotencyKey(taskId, idempotencyKey);
            found.filter(delivery -> !delivery.turnId().equals(turnId)).ifPresent(delivery -> {
                throw steeredElsewhere(delivery);
            });
            return found;
        }
    }

    /** A key steered into an earlier turn names that message; only a delivery known to have failed sent nothing. */
    private static AgentConflictException steeredElsewhere(AgentInputDelivery delivery)
    {
        return new AgentConflictException(delivery.state() == AgentInputDelivery.State.FAILED
                ? "Steer idempotency key belongs to another turn: " + delivery.turnId()
                : ALREADY_SENT);
    }

    private SteerAcceptance acceptSteerLocked(AgentTask task, AgentTurn turn, SteerAgentTurnCommand command, Optional<QueuedAgentInput> queued)
    {
        Instant acceptedAt = Instant.now(clock);
        BlobRef messageBlob = logStore.writeBlob(task.id(), ByteBuffer.wrap(command.message().getBytes(UTF_8)), "text/plain; charset=utf-8");
        InputDeliveryId deliveryId = InputDeliveryId.random();
        EventId messageEventId = EventId.steerMessage(deliveryId);
        AgentInputDelivery delivery = AgentInputDelivery.accepted(deliveryId, task.id(), turn.id(), command.idempotencyKey(), acceptedAt);
        unitOfWork.execute(() -> {
            steerStore.insert(delivery);
            queued.ifPresent(input -> {
                queueStore.update(input.steered(turn.id(), messageEventId, acceptedAt));
                outboxStore.insert(pendingLifecycle(EventId.random(), task.id(), Optional.of(turn.id()),
                        AgentEventPayload.LifecyclePhase.QUEUE_CHANGED, "Queued input steered into the running turn", acceptedAt));
            });
            outboxStore.insert(new AgentEventOutboxEntry(
                    messageEventId,
                    task.id(),
                    Optional.of(turn.id()),
                    root(),
                    new AgentEventOutboxPayload.UserMessageBlob(messageBlob, references(command.attachments()), AgentEventPayload.DeliveryKind.STEER),
                    acceptedAt,
                    Optional.empty()));
            return null;
        });
        return new SteerAcceptance(delivery, messageEventId, new AgentTurnInput(command.message(), command.attachments()));
    }

    private AgentTurnInput persistedSteerInput(AgentInputDelivery delivery)
    {
        AgentEventOutboxEntry entry = outboxStore.find(EventId.steerMessage(delivery.id()))
                .orElseThrow(() -> new AgentStorageException("Steer message event is absent: " + delivery.id()));
        if (!(entry.payload() instanceof AgentEventOutboxPayload.UserMessageBlob message)
                || message.deliveryKind() != AgentEventPayload.DeliveryKind.STEER) {
            throw new AgentStorageException("Steer message event has an invalid payload: " + delivery.id());
        }
        List<AgentAttachment> attachments = message.attachments().stream()
                .map(reference -> resourceService.readAttachment(delivery.taskId(), reference.id()).input())
                .collect(toImmutableList());
        return new AgentTurnInput(readUtf8(delivery.taskId(), message.textBlob()), attachments);
    }

    /**
     * Records how a steer ended. A failure is also an error event on the turn, so the message the
     * transcript already shows is visibly not one the agent received, or not surely.
     */
    private AgentInputDelivery settleSteer(AgentInputDelivery accepted, AgentInputDelivery.State outcome, Optional<String> failure)
    {
        checkArgument(outcome == AgentInputDelivery.State.DELIVERED
                || outcome == AgentInputDelivery.State.FAILED
                || outcome == AgentInputDelivery.State.UNKNOWN, "outcome is not terminal");
        synchronized (runtimeRegistry.taskLock(accepted.taskId())) {
            AgentInputDelivery current = steerStore.findSteerByIdempotencyKey(accepted.taskId(), accepted.idempotencyKey()).orElseThrow();
            Instant now = Instant.now(clock);
            AgentInputDelivery settled = switch (outcome) {
                case DELIVERED -> current.delivered(now);
                case FAILED -> current.failed(failure.orElseThrow(), now);
                case UNKNOWN -> current.unknown(failure.orElseThrow(), now);
                case ACCEPTED, SENDING -> throw new IllegalArgumentException("outcome is not terminal: " + outcome);
            };
            Optional<String> errorCode = switch (outcome) {
                case FAILED -> Optional.of("STEER_FAILED");
                case UNKNOWN -> Optional.of("STEER_UNKNOWN");
                case DELIVERED -> Optional.empty();
                case ACCEPTED, SENDING -> throw new IllegalArgumentException("outcome is not terminal: " + outcome);
            };
            if (errorCode.isEmpty()) {
                return unitOfWork.execute(() -> steerStore.update(settled));
            }
            return eventCommitter.commit(
                    pendingInline(
                            EventId.random(),
                            settled.taskId(),
                            Optional.of(settled.turnId()),
                            root(),
                            new AgentEventPayload.Error(errorCode.orElseThrow(), failure.orElseThrow(), false),
                            now),
                    () -> steerStore.update(settled));
        }
    }

    @Override
    public QueuedAgentInput enqueue(QueueAgentInputCommand command)
    {
        requireNonNull(command, "command is null");
        retryCompletedTurn(command.taskId());
        synchronized (runtimeRegistry.taskLock(command.taskId())) {
            drainPending(outboxStore, eventService, command.taskId());
            Optional<QueuedAgentInput> repeated = queueStore.findQueuedByIdempotencyKey(command.taskId(), command.idempotencyKey());
            if (repeated.isPresent()) {
                return displayInput(repeated.orElseThrow());
            }
            requireUnusedKey(command.taskId(), command.idempotencyKey());
            AgentTask task = taskStore.require(command.taskId());
            if (task.state() != TaskState.OPEN) {
                throw new AgentConflictException("Task cannot queue input in state " + task.state());
            }
            requireNoDevelopmentContinuation(task);
            if (task.version() != command.expectedVersion()) {
                throw new AgentConflictException("Task version changed: " + task.id());
            }
            // A queue is later work behind the task's own active turn. Accepting one after that
            // turn released its claim would leave an idle QUEUED row with nothing to start it.
            Optional<WorktreeMutationClaim> claim = task.worktreeMutationClaim();
            Optional<AgentTurn> activeTurn = turnStore.findActive(task.id());
            boolean ownTurnRunning = claim
                    .filter(held -> held.kind() == WorktreeMutationClaim.Kind.TURN)
                    .filter(held -> activeTurn.stream().anyMatch(turn -> held.ownerId().equals(turn.id().toString())))
                    .isPresent();
            if (!ownTurnRunning) {
                throw new AgentConflictException(claim.isPresent() ? claim.orElseThrow().conflictMessage() : "Task has no active turn to queue behind: " + task.id());
            }
            Instant queuedAt = Instant.now(clock);
            BlobRef textBlob = logStore.writeBlob(task.id(), ByteBuffer.wrap(command.message().getBytes(UTF_8)), "text/plain; charset=utf-8");
            long position = queueStore.listQueued(task.id()).stream().mapToLong(QueuedAgentInput::position).max().orElse(0) + 1;
            QueuedAgentInput input = QueuedAgentInput.queued(
                    QueuedInputId.random(),
                    task.id(),
                    position,
                    command.idempotencyKey(),
                    textBlob,
                    command.message(),
                    turnSettings(task, references(command.attachments()), command.developmentAutomation())
                            .withRepositoryContext(command.useRepositoryContext().orElse(true)),
                    queuedAt);
            return eventCommitter.commit(
                    pendingLifecycle(EventId.random(), task.id(), Optional.empty(), AgentEventPayload.LifecyclePhase.QUEUE_CHANGED, "Queued input added", queuedAt),
                    () -> {
                        // The version write is what serializes this against Complete.
                        taskStore.update(task.withUpdatedAt(queuedAt));
                        queueStore.insert(input);
                        return input;
                    });
        }
    }

    @Override
    public Optional<QueuedAgentInput> findQueuedByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        checkText(idempotencyKey, "idempotencyKey");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            taskStore.require(taskId);
            return queueStore.findQueuedByIdempotencyKey(taskId, idempotencyKey).map(this::displayInput);
        }
    }

    @Override
    public List<QueuedAgentInput> queued(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        taskStore.require(taskId);
        return queueStore.listQueued(taskId).stream()
                .filter(input -> !input.state().historical())
                .map(this::displayInput)
                .collect(toImmutableList());
    }

    @Override
    public QueuedAgentInput removeQueued(TaskId taskId, QueuedInputId queuedInputId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(queuedInputId, "queuedInputId is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            drainPending(outboxStore, eventService, taskId);
            taskStore.require(taskId);
            QueuedAgentInput input = requireQueued(queuedInputId);
            if (!input.taskId().equals(taskId)) {
                throw new AgentConflictException("Queued input does not belong to task " + taskId + ": " + queuedInputId);
            }
            Instant now = Instant.now(clock);
            QueuedAgentInput removed = switch (input.state()) {
                case QUEUED, PAUSED -> input.removed(now);
                // The user is declining to resend it, which says nothing about whether it arrived.
                case UNCERTAIN -> input.abandoned("Discarded without resending it", now);
                // A lost HTTP response must not turn the same removal into a conflict on retry.
                case REMOVED, ABANDONED -> input;
                case STARTING, STARTED -> throw new AgentConflictException("Queued input can no longer be removed: " + input.state());
            };
            if (removed == input) {
                return displayInput(input);
            }
            QueuedAgentInput stored = eventCommitter.commit(
                    pendingLifecycle(
                            EventId.random(),
                            taskId,
                            Optional.empty(),
                            AgentEventPayload.LifecyclePhase.QUEUE_CHANGED,
                            removed.state() == QueuedAgentInput.State.ABANDONED ? "Queued input abandoned" : "Queued input removed",
                            now),
                    () -> queueStore.update(removed));
            return displayInput(stored);
        }
    }

    @Override
    public AgentQueuedInputDraft editQueued(TaskId taskId, QueuedInputId queuedInputId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(queuedInputId, "queuedInputId is null");
        synchronized (runtimeRegistry.taskLock(taskId)) {
            taskStore.require(taskId);
            QueuedAgentInput input = requireQueued(queuedInputId);
            if (!input.taskId().equals(taskId)) {
                throw new AgentConflictException("Queued input does not belong to task " + taskId + ": " + queuedInputId);
            }
            if (input.messageEventId().isPresent() || (input.state() != QueuedAgentInput.State.QUEUED
                    && input.state() != QueuedAgentInput.State.PAUSED && input.state() != QueuedAgentInput.State.REMOVED)) {
                throw new AgentConflictException("Queued input can no longer be edited: " + input.state());
            }
            // Read every resource before removing the row, so an unreadable draft stays queued.
            AgentQueuedInputDraft draft = new AgentQueuedInputDraft(readUtf8(taskId, input.textBlob()), input.settings(),
                    input.settings().attachments().stream()
                            .map(reference -> resourceService.readAttachment(taskId, reference.id()))
                            .collect(toImmutableList()));
            removeQueued(taskId, queuedInputId);
            return draft;
        }
    }

    @Override
    public AgentTurn resumeQueued(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        retryCompletedTurn(taskId);
        stopChildWriters(taskId);
        TurnAcceptance acceptance;
        synchronized (runtimeRegistry.taskLock(taskId)) {
            drainPending(outboxStore, eventService, taskId);
            AgentTask task = requireStartableTaskWithoutReadiness(taskId);
            if (waitingForDevelopmentAnswer(taskId)) {
                throw new AgentConflictException("Answer the development question before resuming queued input");
            }
            QueuedAgentInput head = queueHead(taskId)
                    .orElseThrow(() -> new AgentConflictException("Task has no queued input: " + taskId));
            sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=queue.resume.begin epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=-");
            if (head.state() == QueuedAgentInput.State.UNCERTAIN) {
                throw new AgentConflictException("The next queued input may already have been delivered; discard it first: " + head.id());
            }
            requireReady(task, head.settings());
            acceptance = acceptQueuedLocked(task, head);
        }
        return launchAccepted(acceptance);
    }

    /**
     * The user's turn goes first; stopping waits for the writers outside the task lock, as stopping a runtime does. A task
     * that is still working refuses the turn under its lock anyway, so its children keep going.
     */
    private void stopChildWriters(TaskId taskId)
    {
        AgentTask task = taskStore.require(taskId);
        if (task.interactive() && task.worktreeMutationClaim().isEmpty() && !task.developmentContinuationUnfinished()) {
            stopChildren.accept(taskId);
        }
    }

    /** The next input that is not history: waiting, paused, or uncertain. */
    private Optional<QueuedAgentInput> queueHead(TaskId taskId)
    {
        return queueStore.listQueued(taskId).stream()
                .filter(input -> !input.state().historical())
                .findFirst();
    }

    /**
     * Starts the queue head after a turn ended well.
     *
     * <p>Only a waiting input starts by itself; a paused one waits for the user, and an uncertain
     * one for a decision. Whatever stops the start is written on the input as its pause reason,
     * because this runs on no request that could carry the failure back to anyone.
     */
    private void startQueuedHead(TaskId taskId)
    {
        TurnAcceptance acceptance;
        synchronized (runtimeRegistry.taskLock(taskId)) {
            Optional<QueuedAgentInput> head = queueHead(taskId).filter(input -> input.state() == QueuedAgentInput.State.QUEUED);
            if (head.isEmpty()) {
                return;
            }
            sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=queue.dispatch.begin epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=-");
            try {
                drainPending(outboxStore, eventService, taskId);
            }
            catch (RuntimeException unavailable) {
                pauseQueuedHead(head.orElseThrow(), "Not started: " + message(unavailable));
                return;
            }
            AgentTask task;
            try {
                task = requireStartableTaskWithoutReadiness(taskId);
            }
            catch (AgentConflictException busy) {
                // A turn or development follow-up that got there first starts the queue again when it ends. Anything else,
                // such as a local run or a review on the shared working copy, would leave the input waiting with nothing
                // to start it, so it waits for the user instead.
                AgentTask current = taskStore.require(taskId);
                if (turnStore.findActive(taskId).isEmpty() && !current.developmentContinuationUnfinished() && !runtimeRegistry.hasDevelopmentOwner(taskId)) {
                    pauseQueuedHead(head.orElseThrow(), "Not started: " + message(busy));
                }
                return;
            }
            catch (RuntimeException unavailable) {
                pauseQueuedHead(head.orElseThrow(), "Not started: " + message(unavailable));
                return;
            }
            try {
                requireReady(task, head.orElseThrow().settings());
                acceptance = acceptQueuedLocked(task, head.orElseThrow());
            }
            catch (RuntimeException failure) {
                pauseQueuedHead(requireQueued(head.orElseThrow().id()), "Not started: " + message(failure));
                return;
            }
        }
        try {
            launchAccepted(acceptance);
        }
        catch (RuntimeException failure) {
            // Already on the turn and its input; there is no caller to tell.
        }
    }

    /** Persists a background queue mutation and only then publishes the refresh signal for it. */
    private void pauseQueuedHead(QueuedAgentInput input, String reason)
    {
        Instant pausedAt = Instant.now(clock);
        eventCommitter.commit(
                pendingLifecycle(EventId.random(), input.taskId(), Optional.empty(), AgentEventPayload.LifecyclePhase.QUEUED_INPUT_PAUSED, reason, pausedAt),
                () -> queueStore.update(input.paused(reason, pausedAt)));
    }

    /**
     * Claims the queue head for a new turn: the input, the task's worktree, and the turn move
     * together. The one user-message event is emitted on the first claim; a later attempt after a
     * known pre-send failure reuses it, so the transcript shows one request, not two.
     */
    private TurnAcceptance acceptQueuedLocked(AgentTask task, QueuedAgentInput head)
    {
        List<AgentAttachment> attachments = head.settings().attachments().stream()
                .map(reference -> resourceService.readAttachment(task.id(), reference.id()).input())
                .collect(toImmutableList());
        String text = readUtf8(task.id(), head.textBlob());
        TurnId turnId = TurnId.random();
        Instant acceptedAt = Instant.now(clock);
        AgentTurnSettings settings = head.settings();
        AgentTurn turn = AgentTurn.accepted(turnId, task.id(), "queue:" + head.id() + ":" + turnId, settings, acceptedAt);
        EventId messageEventId = head.messageEventId().orElseGet(EventId::random);
        EventId deliveryEventId = head.messageEventId().isPresent() ? EventId.random() : messageEventId;
        AgentTask executionTask = selectProvider(task, settings, acceptedAt);
        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=queue.persist.begin epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
        unitOfWork.execute(() -> {
            taskStore.update(claimAcceptedTurn(executionTask, turn));
            turnStore.insert(turn);
            queueStore.update(head.starting(turnId, messageEventId, acceptedAt));
            if (head.messageEventId().isEmpty()) {
                outboxStore.insert(new AgentEventOutboxEntry(
                        messageEventId,
                        task.id(),
                        Optional.of(turnId),
                        root(),
                        new AgentEventOutboxPayload.UserMessageBlob(head.textBlob(), settings.attachments(), AgentEventPayload.DeliveryKind.QUEUED),
                        acceptedAt,
                        Optional.empty()));
            }
            else {
                outboxStore.insert(pendingLifecycle(
                        deliveryEventId,
                        task.id(),
                        Optional.of(turnId),
                        AgentEventPayload.LifecyclePhase.RETRYING,
                        "Retrying queued input",
                        acceptedAt));
            }
            return null;
        });
        sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=queue.persist.end epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
        runtimeRegistry.recordAcceptedTurn(turn);
        return new TurnAcceptance(turn, deliveryEventId, text, attachments);
    }

    private String readUtf8(TaskId taskId, BlobRef blobRef)
    {
        try {
            return UTF_8.newDecoder().decode(logStore.readBlob(taskId, blobRef)).toString();
        }
        catch (CharacterCodingException e) {
            throw new InvalidAgentRequestException("Queued text is not valid UTF-8");
        }
    }

    private QueuedAgentInput displayInput(QueuedAgentInput input)
    {
        return input.withPreview(readUtf8(input.taskId(), input.textBlob()));
    }

    private static List<AgentAttachmentReference> references(List<AgentAttachment> attachments)
    {
        return attachments.stream()
                .map(attachment -> new AgentAttachmentReference(attachment.id(), attachment.name(), attachment.mediaType()))
                .collect(toImmutableList());
    }

    private QueuedAgentInput requireQueued(QueuedInputId queuedInputId)
    {
        return queueStore.find(queuedInputId).orElseThrow(() -> new QueuedInputNotFoundException(queuedInputId));
    }

    private AgentTask requireStartableTask(StartAgentTurnCommand command)
    {
        AgentTask task = requireStartableTaskWithoutReadiness(command.taskId());
        if (command.useTrustedTaskCreationReadiness()) {
            boolean trustedInitialTurn = task.interactive()
                    && task.version() == 0
                    && command.expectedTaskVersion().isPresent()
                    && command.expectedTaskVersion().getAsLong() == 0
                    && turnStore.list(task.id()).isEmpty();
            if (!trustedInitialTurn) {
                throw new InvalidAgentRequestException("Task-creation readiness applies only to a fresh interactive task");
            }
            return task;
        }
        return requireRecentlyReady(task);
    }

    private void requireNoDevelopmentContinuation(AgentTask task)
    {
        if (task.developmentContinuationUnfinished() || runtimeRegistry.hasDevelopmentOwner(task.id())) {
            throw new AgentConflictException("Development automation owns this task: " + task.id());
        }
    }

    private AgentTask requireStartableTaskWithoutReadiness(TaskId taskId)
    {
        return requireStartableTaskWithoutReadiness(taskId, Optional.empty());
    }

    private AgentTask requireStartableTaskWithoutReadiness(TaskId taskId, Optional<TurnId> continuationOwner)
    {
        AgentTask task = taskStore.require(taskId);
        task.taskWorktree().filter(worktree -> !task.ownsWorktree()).ifPresent(worktree -> {
            AgentTask owner = taskStore.require(worktree.taskId());
            if (owner.state() != TaskState.OPEN || owner.worktreeRemovedAt().isPresent()) {
                throw new AgentConflictException("The development working copy is unavailable");
            }
        });
        if (runtimeRegistry.closing()) {
            throw new AgentConflictException("Backend is shutting down");
        }
        if ((task.developmentContinuationUnfinished() || runtimeRegistry.hasDevelopmentOwner(taskId))
                && continuationOwner.filter(owner -> ownsDevelopmentContinuation(taskId, owner)).isEmpty()) {
            throw new AgentConflictException("Development automation owns this task: " + taskId);
        }
        if (task.state() != TaskState.OPEN) {
            throw new AgentConflictException("Task cannot start a turn in state " + task.state());
        }
        turnStore.findActive(task.id()).ifPresent(active -> {
            throw new AgentConflictException("Task already has an active turn: " + active.id());
        });
        task.worktreeMutationClaim().ifPresent(claim -> {
            throw new AgentConflictException(claim.conflictMessage());
        });
        runtimeRegistry.unverifiedClose(task.id()).ifPresent(failure -> {
            throw new AgentConflictException("Task provider process is not verified stopped: " + failureMessage(failure));
        });
        runtimeRegistry.checkBranchAdmission(task);
        return idleWorktreePreparation.apply(task);
    }

    private AgentTask requireReady(AgentTask task)
    {
        return requireReady(task, turnSettings(task, ImmutableList.of()));
    }

    private AgentTask requireReady(AgentTask task, AgentTurnSettings settings)
    {
        return requireReady(task, settings, false);
    }

    /** Readiness for a person's Send, which reuses a check passed in the last few minutes instead of launching the CLI again. */
    private AgentTask requireRecentlyReady(AgentTask task)
    {
        return requireReady(task, turnSettings(task, ImmutableList.of()), true);
    }

    private AgentTask requireReady(AgentTask task, AgentTurnSettings settings, boolean reuseRecentCheck)
    {
        var lane = settings.laneId().orElse(task.laneId());
        driverRegistry.checkWorkspaceAccess(lane, task.workspaceId());
        var driver = driverRegistry.require(lane);
        String check = task.id() + ":" + lane.value() + ":" + settings.model().orElse("");
        Instant now = Instant.now(clock);
        if (reuseRecentCheck && readyUntil.getOrDefault(check, Instant.MIN).isAfter(now)) {
            sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=turn.readiness.reused epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=-");
            return task;
        }
        sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=turn.readiness.begin epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=-");
        AgentProviderStatus status = driver.probe(settings.model());
        sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=turn.readiness.end epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=-");
        if (!status.available()) {
            readyUntil.remove(check);
            throw new AgentProviderUnavailableException(status);
        }
        readyUntil.values().removeIf(until -> !until.isAfter(now));
        readyUntil.put(check, now.plus(READINESS_REUSE));
        return task;
    }

    /**
     * A key names one message whichever way it was sent, so a retry by another route, such as Queue after a Send whose
     * answer was lost, cannot run it twice. Only input known never to have reached the provider frees its key.
     */
    private void requireUnusedKey(TaskId taskId, String idempotencyKey)
    {
        if (turnStore.findByIdempotencyKey(taskId, idempotencyKey).filter(turn -> !turn.knownUnsent()).isPresent()
                || queueStore.findQueuedByIdempotencyKey(taskId, idempotencyKey).filter(input -> input.state() != QueuedAgentInput.State.REMOVED).isPresent()
                || steerStore.findSteerByIdempotencyKey(taskId, idempotencyKey).filter(delivery -> delivery.state() != AgentInputDelivery.State.FAILED).isPresent()) {
            throw new AgentConflictException(ALREADY_SENT);
        }
    }

    @Override
    public AgentTurnSettings effectiveSettings(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        // Attachments belong to a message, not to a task, so a described turn carries none.
        return turnSettings(taskStore.require(taskId), ImmutableList.of());
    }

    private TurnAcceptance acceptTurnLocked(AgentTask task, StartAgentTurnCommand command)
    {
        return acceptTurnLocked(task, command.idempotencyKey(), command.message(), command.attachments(),
                turnSettings(task, references(command.attachments()), command.developmentAutomation())
                        .withRepositoryContext(command.useRepositoryContext().orElse(true)), Optional.empty());
    }

    private TurnAcceptance acceptTurnLocked(
            AgentTask task,
            String idempotencyKey,
            String message,
            List<AgentAttachment> attachments,
            AgentTurnSettings settings,
            Optional<DevelopmentTurnOrigin> origin)
    {
        TurnId turnId = TurnId.random();
        Instant acceptedAt = Instant.now(clock);
        AgentTurn accepted = AgentTurn.accepted(turnId, task.id(), idempotencyKey, settings, acceptedAt);
        AgentTurn turn = origin.map(accepted::withDevelopmentOrigin).orElse(accepted);
        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=turn.persist.begin epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
        BlobRef messageBlob = logStore.writeBlob(task.id(), ByteBuffer.wrap(message.getBytes(UTF_8)), "text/plain; charset=utf-8");
        EventId messageEventId = EventId.random();
        AgentTask executionTask = selectProvider(task, settings, acceptedAt);
        unitOfWork.execute(() -> {
            taskStore.update(claimAcceptedTurn(executionTask, turn));
            turnStore.insert(turn);
            outboxStore.insert(new AgentEventOutboxEntry(
                    messageEventId,
                    turn.taskId(),
                    Optional.of(turn.id()),
                    root(),
                    new AgentEventOutboxPayload.UserMessageBlob(messageBlob, settings.attachments(), AgentEventPayload.DeliveryKind.TURN),
                    acceptedAt,
                    Optional.empty()));
            return null;
        });
        sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=turn.persist.end epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
        runtimeRegistry.recordAcceptedTurn(turn);
        return new TurnAcceptance(turn, messageEventId, message, attachments);
    }

    private AgentTask selectProvider(AgentTask task, AgentTurnSettings settings, Instant acceptedAt)
    {
        var lane = settings.laneId().orElse(task.laneId());
        if (lane.equals(task.laneId())) {
            return task;
        }
        runtimeRegistry.closeSession(task.id());
        return task.withLaneId(lane, acceptedAt);
    }

    private AgentTask claimAcceptedTurn(AgentTask task, AgentTurn turn)
    {
        AgentTask claimed = task.claimWorktreeMutation(WorktreeMutationClaim.turn(turn.id(), turn.createdAt()), turn.createdAt());
        if (turn.developmentOrigin().isEmpty() && developmentTurnEligible(task, turn)) {
            return claimed.withDevelopmentContinuation(true, turn.createdAt());
        }
        return claimed;
    }

    private void registerRunningTurn(AgentTurn accepted, RunningTurn runningTurn, AgentSessionRef reference)
    {
        ProviderTurnRef providerTurnReference = runningTurn.reference();
        Instant acknowledgedAt = Instant.now(clock);
        Optional<QueuedAgentInput> queuedInput = queueStore.listQueued(accepted.taskId()).stream()
                .filter(input -> input.state() == QueuedAgentInput.State.STARTING && input.turnId().equals(Optional.of(accepted.id())))
                .findFirst();
        Optional<EventId> queueEventId = queuedInput.map(input -> EventId.random());
        AgentTurn running = unitOfWork.execute(() -> {
            AgentTurn updated = turnStore.update(turnStore.require(accepted.id()).running(providerTurnReference, Optional.empty()).withSetup(runningTurn.setup()));
            // The thread reference is persisted only now that a turn has run on it. A thread that
            // was opened but never turned has no rollout, so resuming it after a restart fails
            // forever; forgetting it means the next turn starts a fresh thread instead.
            AgentTask task = taskStore.require(updated.taskId());
            if (task.sessionReference().isEmpty()) {
                taskStore.update(task.withSessionReference(reference, acknowledgedAt));
            }
            // The provider acknowledged the turn, so a queued input that became it is consumed for
            // good, whatever that turn does next.
            queuedInput.ifPresent(input -> queueStore.update(input.started(acknowledgedAt)));
            queueEventId.ifPresent(eventId -> outboxStore.insert(pendingLifecycle(
                    eventId,
                    updated.taskId(),
                    Optional.of(updated.id()),
                    AgentEventPayload.LifecyclePhase.QUEUED_INPUT_STARTED,
                    "Queued input started",
                    acknowledgedAt)));
            return updated;
        });
        runtimeRegistry.registerRunning(running.taskId(), running.id(), runningTurn);
        queueEventId.ifPresent(eventId -> {
            try {
                eventService.drain(eventId);
            }
            catch (RuntimeException ignored) {
                // The provider already acknowledged the turn. The outbox row is durable and a
                // later request/recovery drain can publish it; delivery failure must not cancel a
                // provider execution that is now writing to the task worktree.
            }
        });
    }

    private void markDeliveryStarted(AgentTurn accepted, ReviewTurnInputResolver.Reference reference)
    {
        Instant startedAt = Instant.now(clock);
        eventCommitter.commit(
                pendingLifecycle(EventId.random(), accepted.taskId(), Optional.of(accepted.id()), AgentEventPayload.LifecyclePhase.TURN_STARTED, "Turn started", startedAt),
                () -> turnStore.update(turnStore.require(accepted.id()).markDeliveryStarted(startedAt).withReference(reference.guidelines(), reference.memories())));
    }

    @Override
    public AgentTurn cancel(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        AgentTurn initial = turnStore.require(turnId);
        retryCompletedTurn(initial);
        RunningTurn running = null;
        Optional<TurnId> cancelledDevelopment = Optional.empty();
        List<CompletableFuture<DevelopmentTurnCompletion>> pending = ImmutableList.of();
        synchronized (runtimeRegistry.taskLock(initial.taskId())) {
            AgentTurn turn = turnStore.require(turnId);
            if (turn.state().terminal()) {
                TurnId sourceTurnId = turn.developmentOrigin().map(DevelopmentTurnOrigin::parentUserTurnId).orElse(turn.id());
                Optional<TurnId> owner = runtimeRegistry.developmentOwner(turn.taskId());
                boolean settlingLiveTurn = initial.state().active() && owner.isEmpty()
                        && taskStore.require(turn.taskId()).developmentContinuationUnfinished()
                        && runtimeRegistry.latestAcceptedTurn(turn.taskId()).filter(turn.id()::equals).isPresent();
                if (!owner.filter(sourceTurnId::equals).isPresent() && !settlingLiveTurn) {
                    return turn;
                }
                pending = runtimeRegistry.stopDevelopment(turn.taskId());
                cancelledDevelopment = Optional.of(sourceTurnId);
            }
            else {
                AgentTurn cancellation = turn.requestCancellation(Instant.now(clock));
                if (cancellation != turn) {
                    turnStore.update(cancellation);
                }
                running = runtimeRegistry.running(turnId)
                        .orElseThrow(() -> new AgentConflictException("Turn has no live provider handle: " + turnId));
            }
        }
        if (cancelledDevelopment.isPresent()) {
            pending.forEach(future -> future.completeExceptionally(new AgentConflictException("Development automation cancelled")));
            failDevelopmentContinuation(initial.taskId(), cancelledDevelopment.orElseThrow(), "The current request was cancelled. Send another message to continue.");
            return turnStore.require(turnId);
        }
        // Approval and provider cancellation may complete callbacks that
        // re-enter this service, so both run after releasing the task lock.
        try {
            approvalService.denyPending(turnId);
        }
        catch (RuntimeException failure) {
            RunningTurn providerTurn = requireNonNull(running, "running is null");
            suppressFailure(failure, providerTurn::cancel);
            throw failure;
        }
        requireNonNull(running, "running is null").cancel();
        return turnStore.require(turnId);
    }

    @Override
    public AgentTurn get(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        AgentTurn turn = turnStore.require(turnId);
        retryCompletedTurn(turn);
        return turnStore.require(turnId);
    }

    @Override
    public AgentRuntimeStopImpact stopImpact(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        retryCompletedTurn(taskId);
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTask task = taskStore.require(taskId);
            Optional<AgentTurn> active = turnStore.findActive(taskId);
            return new AgentRuntimeStopImpact(taskId, generation(task, active), active.map(AgentTurn::id), runtimeRegistry.session(taskId).isPresent());
        }
    }

    private static String generation(AgentTask task, Optional<AgentTurn> active)
    {
        // A running turn is the thing being stopped, so it names the generation; otherwise the
        // task version does, and starting a turn bumps it.
        return active.map(turn -> "turn:" + turn.id()).orElseGet(() -> "task:" + task.version());
    }

    @Override
    public AgentTask stopRuntime(TaskId taskId, String generation)
    {
        requireNonNull(taskId, "taskId is null");
        if (generation == null || generation.isBlank()) {
            throw new InvalidAgentRequestException("generation must not be blank");
        }
        synchronized (runtimeRegistry.stopLock(taskId)) {
            retryCompletedTurn(taskId);
            Optional<AgentTurn> active;
            List<CompletableFuture<DevelopmentTurnCompletion>> pending;
            boolean development;
            synchronized (runtimeRegistry.taskLock(taskId)) {
                AgentTask task = taskStore.require(taskId);
                if (task.state().terminal()) {
                    throw new AgentConflictException("Task is already terminal: " + taskId);
                }
                active = turnStore.findActive(taskId);
                if (!generation.equals(generation(task, active))) {
                    throw new AgentConflictException("Task runtime changed since the stop was confirmed: " + taskId);
                }
                if (!task.developmentContinuationUnfinished() && active.filter(turn -> developmentTurnEligible(task, turn)).isPresent()) {
                    updateDevelopmentMarker(task, true, "Development automation stopped");
                }
                development = taskStore.require(taskId).developmentContinuationUnfinished();
                pending = development ? runtimeRegistry.stopDevelopment(taskId) : ImmutableList.of();
                active.ifPresent(turn -> {
                    AgentTurn cancellation = turn.requestCancellation(Instant.now(clock));
                    if (cancellation != turn) {
                        turnStore.update(cancellation);
                    }
                });
            }
            // Denying approvals and closing the session both complete provider callbacks that take the
            // task lock, so neither may run under it.
            pending.forEach(future -> future.completeExceptionally(new AgentConflictException("Development automation stopped")));
            if (development) {
                synchronized (runtimeRegistry.taskLock(taskId)) {
                    AgentTask task = taskStore.require(taskId);
                    // A synchronous completion callback may already have settled this stop.
                    if (!task.developmentContinuationUnfinished() || !runtimeRegistry.developmentStopped(taskId)) {
                        return task;
                    }
                }
                stopDevelopmentRuntimes(taskId, active);
                return settleStoppedDevelopmentContinuation(taskId, "Send another message to continue.");
            }
            stopProviderRuntime(taskId, active);
            AgentTask stopped;
            synchronized (runtimeRegistry.taskLock(taskId)) {
                releaseStaleTurnClaim(taskStore.require(taskId));
                stopped = taskStore.require(taskId);
            }
            return stopped;
        }
    }

    /**
     * Waits for a closed process to report its turn interrupted, then records that.
     *
     * <p>The close returns once the process tree is verified stopped, but the turn learns of it
     * through the provider's own callbacks, so the stop has to wait for them before the task can
     * be read as settled.
     */
    private void awaitInterruption(AgentTurn turn)
    {
        Optional<RunningTurn> running = runtimeRegistry.running(turn.id());
        if (running.isEmpty()) {
            // Either the close already ran the turn's completion, which drops the handle, or the
            // turn failed before a provider run existed. In the second case the closed process
            // was all there was, so the verified stop is what settles it.
            synchronized (runtimeRegistry.taskLock(turn.taskId())) {
                AgentTurn current = turnStore.require(turn.id());
                if (current.state().active()) {
                    persistTerminalTurn(current, TurnState.INTERRUPTED, Optional.of("Provider process stopped before the turn was acknowledged"), true);
                }
            }
            drainPending(outboxStore, eventService, turn.taskId());
            approvalService.denyPending(turn.id());
            return;
        }
        try {
            running.orElseThrow().completion().toCompletableFuture().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Interrupted while stopping turn " + turn.id(), e);
        }
        catch (ExecutionException | CancellationException ignored) {
            // An exceptional completion is still a completion; the terminal transaction records it.
        }
        catch (TimeoutException e) {
            throw new AgentUnavailableException("Turn " + turn.id() + " did not report itself stopped after its process was closed", e);
        }
        get(turn.id());
    }

    /**
     * Releases a claim left by a finished turn now that the task's process tree is verified gone.
     *
     * <p>A turn keeps its claim when its provider could not be shown quiet; the only thing that
     * could still have been writing was that process, so a verified stop is what makes the
     * worktree safe again.
     */
    private void releaseStaleTurnClaim(AgentTask task)
    {
        Optional<WorktreeMutationClaim> claim = task.worktreeMutationClaim()
                .filter(held -> held.kind() == WorktreeMutationClaim.Kind.TURN);
        if (claim.isEmpty() || turnStore.findActive(task.id()).isPresent()) {
            return;
        }
        Instant releasedAt = Instant.now(clock);
        unitOfWork.execute(() -> taskStore.update(taskStore.require(task.id()).releaseWorktreeMutation(claim.orElseThrow(), releasedAt)));
    }

    private Optional<CodeQueryBridge> codeQueriesBridge(AgentTask task)
    {
        return task.taskWorktree()
                .filter(worktree -> !task.laneId().value().startsWith("deepseek-key-"))
                .map(worktree -> new CodeQueryBridge(query -> queryForAgent(worktree, query), CODE_QUERY_INSTRUCTIONS));
    }

    private String queryForAgent(TaskWorktree worktree, CodeQuery query)
    {
        try {
            return codeQueries.query(worktree, query);
        }
        catch (RuntimeException failure) {
            return "Code query unavailable. Continue with normal source and search tools. " + failure.getMessage();
        }
    }

    private AgentSession session(AgentTask task, TurnId turnId, AgentTurnSettings settings)
    {
        Optional<AgentSession> existing = runtimeRegistry.session(task.id());
        if (existing.isPresent()) {
            // A provider launch profile may depend on the accepted model. Closing before delivery
            // lets the stored provider thread resume under the new turn's immutable snapshot.
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.session_close.begin epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
            runtimeRegistry.closeSession(task.id());
            sendTiming.log(System.Logger.Level.DEBUG,
                    () -> "send-timing phase=turn.session_close.end epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
        }

        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=turn.session_context.begin epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
        driverRegistry.checkWorkspaceAccess(task.laneId(), task.workspaceId());
        AgentDriver driver = driverRegistry.require(task.laneId());
        TaskResources resources = resourceService.require(task.id());
        AgentSessionContext context = new AgentSessionContext(
                task.id().toString(),
                task.worktree(),
                resources.runtimeDirectory(),
                resources.providerDirectory(),
                settings.permissionPolicy(),
                task.sessionReference(),
                (request, timeout) -> requestApproval(task.id(), turnId, request, timeout),
                settings.model(),
                settings.effort(),
                task.taskWorktree().isPresent() && !task.ownsWorktree() ? SessionAccess.REVIEW_READ_ONLY : SessionAccess.FULL,
                codeQueriesBridge(task),
                managedInstructions.apply(task) + developmentInstructions(settings));
        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=turn.session_context.end epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=turn.session_open.begin epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
        AgentSession opened = requireNonNull(driver.open(context,
                event -> routeEvent(task.id(), turnId, event)), "opened session is null");
        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=turn.session_open.end epochMs=" + System.currentTimeMillis() + " taskId=" + task.id() + " turnId=" + turnId);
        AgentSession registered = runtimeRegistry.registerSession(task.id(), opened);
        try {
            AgentSessionRef reference = requireNonNull(registered.reference(), "session reference is null");
            if (!reference.laneId().equals(task.laneId())) {
                throw new AgentUnavailableException("Provider returned a session reference for another lane");
            }
            AgentTask current = taskStore.require(task.id());
            if (current.sessionReference().isPresent()
                    && !current.sessionReference().orElseThrow().equals(reference)) {
                throw new AgentUnavailableException("Provider resumed a different session reference");
            }
            if (current.sessionReference().isEmpty()) {
                eventCommitter.publish(pendingLifecycle(
                        EventId.random(),
                        current.id(),
                        Optional.of(turnId),
                        AgentEventPayload.LifecyclePhase.SESSION_OPENED,
                        "Provider session opened",
                        Instant.now(clock)));
            }
            return registered;
        }
        catch (RuntimeException failure) {
            // Keep a close failure in the registry. It is the only durable-in-process proof that
            // the provider may still be writing, and lets an explicit stop retry the same session.
            suppressFailure(failure, () -> runtimeRegistry.closeSession(task.id()));
            throw failure;
        }
    }

    private void routeEvent(TaskId taskId, TurnId boundTurnId, ProviderAgentEvent event)
    {
        requireNonNull(event, "event is null");
        if (event instanceof ProviderAgentEvent.Lifecycle lifecycle && stateCoupled(lifecycle.phase())) {
            return;
        }
        if (event instanceof ProviderAgentEvent.Usage usage) {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                recordUsage(taskId, requireActiveTurn(taskId), usage);
            }
            return;
        }
        if (event instanceof ProviderAgentEvent.Plan plan
                && plan.source().parentProviderThreadId().isEmpty()
                && plan.source().logicalAgentPath().map(path -> path.equals("/") || path.equals("root")).orElse(true)) {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                AgentTurn turn = requireActiveTurn(taskId);
                checkArgument(turn.id().equals(boundTurnId), "Plan belongs to another turn");
                checkArgument(turn.providerTurnReference().isEmpty() || plan.source().providerTurnId().isEmpty()
                                || plan.source().providerTurnId().equals(turn.providerTurnReference().map(ProviderTurnRef::value)),
                        "Plan belongs to another provider turn");
                ProviderAgentEventAdapter.CanonicalEvent update = ProviderAgentEventAdapter.adapt(plan);
                eventCommitter.commitIfAbsent(
                        pendingInline(EventId.random(), taskId, Optional.of(turn.id()), update.source(), update.payload(), Instant.now(clock)),
                        () -> turnStore.update(turn.withPlan(plan.items())));
            }
            return;
        }
        if (event instanceof ProviderAgentEvent.DevelopmentResult result) {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                AgentTurn turn = requireActiveTurn(taskId);
                if (!turn.id().equals(boundTurnId) || turn.cancellationRequestedAt().isPresent()) {
                    throw new AgentConflictException("Development report belongs to a stopped or replaced turn");
                }
                recordDevelopmentResult(taskId, turn, result);
            }
            return;
        }
        // Streamed display events check the in-memory launch record, not the database, once per event.
        if (!launchedTurns.containsKey(boundTurnId)) {
            throw new AgentConflictException("Task has no active turn: " + taskId);
        }
        eventService.append(new UnsequencedAgentEvent(taskId, Optional.of(boundTurnId), event));
    }

    private static String developmentInstructions(AgentTurnSettings settings)
    {
        return settings.developmentAutomation().map(automation -> {
            if (!automation.enabled()) {
                return """

                        No automatic delivery is enabled for this turn. Keep investigation and experiments local
                        unless the user explicitly requests a commit, push, or PR update; those requests follow
                        the shared development authorization rules. This does not change the task's development scope.
                        """;
            }
            return """

                    Accepted development delivery for this turn:
                    Commit finished development work: %s. Auto draft PR: %s. Auto CI fix: %s.
                    Normal development commits finished task work in focused commits using the repository's commit
                    conventions, after resolving user decisions. No commit message carries an attribution,
                    co-authorship, or generated-with trailer. A publication-only assignment must not create commits.
                    ByteQuay requests push and draft creation in a separate scoped follow-up. Do not publish during
                    this development turn unless the user's request explicitly asks you to. Auto CI fix selects the
                    later bounded CI service; it does not authorize an agent to start its own unattended loop.
                    The accepted settings alone control subsequent delivery, including ready promotion after Auto CI fix.
                    If an explicit user request conflicts with delivery, report readyForDelivery=false and explain
                    the conflict. Preserve unfinished work and wait for the user's decision; do not execute the conflicting step.
                    A review-before-publication hold does not prohibit authorized local commits. Reviewers share
                    this task's working copy and can review uncommitted changes. Do not commit solely to enable review.
                    Keep readyForDelivery=false until the publication hold is lifted.
                    A generated request to create a draft is one step, not a user instruction to skip later enabled steps.
                    """.formatted(automation.autoCommit(), automation.autoDraftPr(), automation.autoCiFix());
        }).orElse("");
    }

    private void recordDevelopmentResult(TaskId taskId, AgentTurn turn, ProviderAgentEvent.DevelopmentResult result)
    {
        checkArgument(turn.settings().flatMap(AgentTurnSettings::developmentAutomation).filter(DevelopmentAutomation::enabled).isPresent()
                        || turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent(),
                "Development reporting is not enabled for this turn");
        checkArgument(result.report().learningSummary().isEmpty()
                        || turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent(),
                "Learning summaries require a generated refinement turn");
        checkArgument(result.source().parentProviderThreadId().isEmpty()
                        && result.source().logicalAgentPath().map(path -> path.equals("/") || path.equals("root")).orElse(true),
                "Development report is not from the root agent");
        checkArgument(turn.providerTurnReference().isEmpty()
                        || result.source().providerTurnId().equals(turn.providerTurnReference().map(ProviderTurnRef::value)),
                "Development report belongs to another provider turn");
        String itemId = result.source().providerItemId().orElseThrow(() -> new IllegalArgumentException("Development report has no native tool identity"));
        EventId eventId = EventId.developmentResult(taskId, turn.id(), itemId);
        ProviderAgentEventAdapter.CanonicalEvent event = ProviderAgentEventAdapter.adapt(result);
        eventCommitter.commitIfAbsent(
                pendingInline(eventId, taskId, Optional.of(turn.id()), event.source(), event.payload(), Instant.now(clock)),
                () -> turnStore.update(turn.withDevelopmentResult(result.report())));
    }

    private void recordUsage(TaskId taskId, AgentTurn turn, ProviderAgentEvent.Usage usage)
    {
        EventId eventId = usage.providerEventId()
                .map(providerEventId -> EventId.providerUsage(taskId, turn.id(), providerEventId))
                // Without provider identity, distinct callbacks cannot be durably deduplicated.
                .orElseGet(EventId::random);
        ProviderAgentEventAdapter.CanonicalEvent event = ProviderAgentEventAdapter.adapt(usage);
        Instant occurredAt = Instant.now(clock);
        eventCommitter.commitIfAbsent(
                pendingInline(eventId, taskId, Optional.of(turn.id()), event.source(), event.payload(), occurredAt),
                () -> metricsService.record(taskId, turn.id(), usage.sample()));
    }

    private static boolean stateCoupled(ProviderAgentEvent.LifecyclePhase phase)
    {
        return switch (phase) {
            case SESSION_OPENED, TURN_STARTED, TURN_COMPLETED, INTERRUPTED -> true;
            // Subagent and backfill phases are display-only; they change no task or turn state.
            case RETRYING, RATE_LIMITED, AGENT_STARTED, AGENT_COMPLETED, AGENT_FAILED, BACKFILL_STARTED, BACKFILL_COMPLETED -> false;
        };
    }

    private CompletionStage<ProviderApprovalDecision> requestApproval(TaskId taskId, TurnId boundTurnId, ProviderApprovalRequest request, Duration timeout)
    {
        synchronized (runtimeRegistry.taskLock(taskId)) {
            AgentTurn turn = requireActiveTurn(taskId);
            if (!turn.id().equals(boundTurnId)) {
                throw new AgentConflictException("Approval request belongs to a stopped or replaced turn: " + boundTurnId);
            }
            if (turn.cancellationRequestedAt().isPresent()) {
                throw new AgentConflictException("Task turn is being cancelled: " + turn.id());
            }
            ApprovalRequest approval = new ApprovalRequest(ApprovalId.random(), taskId, turn.id(), request, Instant.now(clock));
            CompletableFuture<ProviderApprovalDecision> decision = approvalService.request(approval, timeout)
                    .thenApply(DefaultAgentTurnService::toProviderDecision)
                    .toCompletableFuture();
            // A provider that settled its own request cancels this stage; its card closes rather than waiting to expire.
            decision.whenComplete((ignored, failure) -> {
                if (failure instanceof CancellationException) {
                    try {
                        approvalService.withdraw(approval.id());
                    }
                    catch (RuntimeException withdrawFailure) {
                        // The card then closes when its turn ends or its wait expires.
                        log.log(System.Logger.Level.WARNING, "Cannot withdraw approval " + approval.id(), withdrawFailure);
                    }
                }
            });
            return decision;
        }
    }

    private void observeCompletion(AgentTurn turn, RunningTurn runningTurn)
    {
        runningTurn.completion().whenComplete((result, failure) -> finishTurn(turn, runningTurn, result, failure, true));
    }

    private void finishTurn(AgentTurn observedTurn, RunningTurn runningTurn, AgentTurnResult providerResult, Throwable completionFailure, boolean liveCompletion)
    {
        boolean notifyDevelopment = false;
        // The provider's response reader must not wait for the steer lock: its holder can be
        // waiting for this callback. Reserve follow-up admission before releasing the task lock.
        try {
            synchronized (runtimeRegistry.taskLock(observedTurn.taskId())) {
                AgentTurn turn = turnStore.require(observedTurn.id());
                if (!turn.state().terminal()) {
                    if (completionFailure == null) {
                        AgentTurnResult result = requireNonNull(providerResult, "providerResult is null");
                        TurnState terminalState = terminalState(result.state());
                        persistTerminalTurn(turn, terminalState, result.terminalReason(), result.providerQuiescent(), result.failureKind(), false);
                    }
                    else {
                        persistTerminalTurn(turn, TurnState.INTERRUPTED, Optional.of(failureMessage(completionFailure)), false);
                    }
                }
                if (liveCompletion) {
                    notifyDevelopment = reserveDevelopmentContinuation(turnStore.require(turn.id()));
                }
            }
        }
        catch (RuntimeException failure) {
            if (liveCompletion) {
                failAfterLivePersistenceFailure(observedTurn, failure);
            }
            throw failure;
        }
        RuntimeException cleanupFailure = null;
        try {
            finishTerminalCallbacks(observedTurn, runningTurn);
        }
        catch (RuntimeException failure) {
            cleanupFailure = failure;
        }
        if (cleanupFailure == null) {
            finished(observedTurn);
        }
        if (liveCompletion && (notifyDevelopment || observedTurn.developmentOrigin().isPresent())) {
            notifyDevelopmentCompletion(observedTurn.id(), Optional.ofNullable(cleanupFailure).map(AgentFailures::failureMessage));
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
        if (queueMayStart(observedTurn)) {
            queueStarter.execute(() -> startQueuedHead(observedTurn.taskId()));
        }
    }

    /** Tells the reference resolver a turn ended, now that its events are in the log; what it does there never changes the settlement. */
    private void finished(AgentTurn observedTurn)
    {
        try {
            reviewTurnInput.finished(taskStore.require(observedTurn.taskId()), turnStore.require(observedTurn.id()));
        }
        catch (RuntimeException failure) {
            log.log(System.Logger.Level.WARNING, "The end of turn " + observedTurn.id() + " was not reported to the reference resolver", failure);
        }
    }

    private void failAfterLivePersistenceFailure(AgentTurn turn, RuntimeException failure)
    {
        TurnId sourceTurnId = turn.developmentOrigin().map(DevelopmentTurnOrigin::parentUserTurnId).orElse(turn.id());
        synchronized (runtimeRegistry.taskLock(turn.taskId())) {
            AgentTask task = taskStore.require(turn.taskId());
            if (!developmentTurnEligible(task, turn) || runtimeRegistry.closing() || task.state().terminal() || !latestDevelopmentSource(task.id(), sourceTurnId)) {
                return;
            }
            suppressFailure(failure, () -> {
                if (!task.developmentContinuationUnfinished()) {
                    updateDevelopmentMarker(task, true, "Development automation could not store its result");
                }
                if (!runtimeRegistry.hasDevelopmentOwner(task.id()) && !runtimeRegistry.developmentStopped(task.id())) {
                    runtimeRegistry.reserveDevelopmentOwner(task.id(), sourceTurnId);
                }
            });
        }
        suppressFailure(failure, () -> queueStarter.execute(() -> suppressFailure(failure, () ->
                failDevelopmentContinuation(turn.taskId(), sourceTurnId, "Development automation could not store its result. Send another message to continue."))));
    }

    private boolean reserveDevelopmentContinuation(AgentTurn turn)
    {
        AgentTask task = taskStore.require(turn.taskId());
        if (turn.developmentOrigin().isPresent() || runtimeRegistry.developmentStopped(task.id())
                || !developmentTurnEligible(task, turn) || runtimeRegistry.closing()
                || task.state() != TaskState.OPEN || runtimeRegistry.hasDevelopmentOwner(task.id())
                || runtimeRegistry.latestAcceptedTurn(task.id()).filter(turn.id()::equals).isEmpty()) {
            return false;
        }
        if (!task.developmentContinuationUnfinished()) {
            return false;
        }
        runtimeRegistry.reserveDevelopmentOwner(task.id(), turn.id());
        return true;
    }

    private boolean developmentTurnEligible(AgentTask task, AgentTurn turn)
    {
        return task.interactive()
                && turn.settings().flatMap(AgentTurnSettings::developmentAutomation).filter(DevelopmentAutomation::enabled).isPresent()
                && developmentEligibility.test(task);
    }

    private void notifyDevelopmentCompletion(TurnId turnId, Optional<String> failure)
    {
        DevelopmentTurnCompletion outcome;
        Optional<CompletableFuture<DevelopmentTurnCompletion>> generated;
        boolean sourceOwned;
        synchronized (runtimeRegistry.taskLock(turnStore.require(turnId).taskId())) {
            AgentTurn turn = turnStore.require(turnId);
            AgentTask task = taskStore.require(turn.taskId());
            boolean quiescent = task.worktreeMutationClaim().isEmpty() && runtimeRegistry.unverifiedClose(task.id()).isEmpty();
            outcome = new DevelopmentTurnCompletion(task, turn, quiescent, failure.or(turn::terminalReason));
            generated = turn.developmentOrigin().flatMap(origin -> runtimeRegistry.developmentTurn(task.id(), origin));
            sourceOwned = turn.developmentOrigin().isEmpty() && ownsDevelopmentContinuation(task.id(), turn.id());
        }
        // Completing a stage may run the coordinator immediately. No provider or caller callback
        // executes while the task's admission lock is held.
        if (generated.isPresent()) {
            generated.orElseThrow().complete(outcome);
        }
        else if (sourceOwned) {
            developmentCompletion.accept(outcome);
        }
    }

    /** Only a clean terminal turn outside a continuation may dispatch ordinary queued input. */
    private boolean queueMayStart(AgentTurn observedTurn)
    {
        synchronized (runtimeRegistry.taskLock(observedTurn.taskId())) {
            AgentTask task = taskStore.require(observedTurn.taskId());
            return observedTurn.developmentOrigin().isEmpty() && turnStore.require(observedTurn.id()).state() == TurnState.COMPLETED && task.state() == TaskState.OPEN
                    && task.worktreeMutationClaim().isEmpty() && !task.developmentContinuationUnfinished()
                    && !runtimeRegistry.hasDevelopmentOwner(task.id()) && !runtimeRegistry.closing() && !waitingForDevelopmentAnswer(task.id());
        }
    }

    private boolean waitingForDevelopmentAnswer(TaskId taskId)
    {
        Optional<AgentTurn> latest = turnStore.list(taskId).reversed().stream()
                .filter(turn -> turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isEmpty())
                .filter(turn -> turn.developmentResult().isPresent()).findFirst();
        return latest.filter(turn -> turn.state() == TurnState.COMPLETED)
                .flatMap(AgentTurn::developmentResult).filter(result -> !result.readyForDelivery()).isPresent();
    }

    private void finishTerminalCallbacks(AgentTurn turn, RunningTurn runningTurn)
    {
        RuntimeException drainFailure = null;
        try {
            drainPending(outboxStore, eventService, turn.taskId());
        }
        catch (RuntimeException drainError) {
            drainFailure = drainError;
        }
        // Completing an approval can run provider continuations. The terminal
        // turn is the barrier that rejects new requests after releasing the lock.
        try {
            approvalService.denyPending(turn.id());
        }
        catch (RuntimeException approvalFailure) {
            if (drainFailure == null) {
                throw approvalFailure;
            }
            if (drainFailure != approvalFailure) {
                drainFailure.addSuppressed(approvalFailure);
            }
        }
        if (drainFailure != null) {
            throw drainFailure;
        }
        runtimeRegistry.removeRunning(turn.id(), runningTurn);
    }

    private void recordKnownUnsentFailure(AgentTurn acceptedTurn, RuntimeException failure, boolean providerQuiescent)
    {
        suppressFailure(failure, () -> {
            AgentTurn turn = turnStore.require(acceptedTurn.id());
            if (!turn.state().terminal()) {
                Optional<String> reason = Optional.of(failureMessage(failure));
                persistTerminalTurn(turn, TurnState.FAILED, reason, providerQuiescent, Optional.empty(), true);
                drainPending(outboxStore, eventService, turn.taskId());
            }
        });
    }

    /**
     * Ends a turn, releasing its worktree claim only when the provider was shown quiet.
     *
     * @param providerQuiescent whether the provider proved nothing it started is still running. A
     *     turn that cannot show this keeps the claim, so no later turn edits a worktree something
     *     may still be writing to. A later confirmed Stop may retry the retained process proof and
     *     release that claim only after the provider is verified stopped.
     */
    private void persistTerminalTurn(AgentTurn turn, TurnState terminalState, Optional<String> reason, boolean providerQuiescent)
    {
        persistTerminalTurn(turn, terminalState, reason, providerQuiescent, Optional.empty(), false);
    }

    private void persistTerminalTurn(
            AgentTurn turn,
            TurnState terminalState,
            Optional<String> reason,
            boolean providerQuiescent,
            Optional<AgentTurnFailureKind> failureKind,
            boolean knownUnsent)
    {
        EventId eventId = EventId.random();
        Long firstSequence = launchedTurns.remove(turn.id());
        // Read before the unit of work opens: the log can be long, and the unit of work holds the database.
        ToolCounters counters = knownUnsent ? ToolCounters.ZERO
                : metricsService.countTools(turn.taskId(), turn.id(), firstSequence == null ? OptionalLong.empty() : OptionalLong.of(firstSequence));
        unitOfWork.execute(() -> {
            AgentMetrics finalMetrics = metricsService.finishTurn(turn.id(), terminalState, counters);
            Instant endedAt = Instant.now(clock);
            AgentTurn latest = turnStore.require(turn.id());
            AgentTurn finished = knownUnsent
                    ? latest.finishKnownUnsent(finalMetrics, reason.orElseThrow(), endedAt)
                    : latest.finish(terminalState, finalMetrics, reason, failureKind, endedAt);
            AgentTurn terminal = turnStore.update(finished);
            WorktreeMutationClaim claim = WorktreeMutationClaim.turn(latest.id(), latest.createdAt());
            AgentTask task = taskStore.require(terminal.taskId());
            AgentTask updated = providerQuiescent ? task.releaseWorktreeMutation(claim, endedAt) : task;
            if (terminal.developmentOrigin().isEmpty() && developmentTurnEligible(task, terminal) && !runtimeRegistry.closing()) {
                updated = updated.withDevelopmentContinuation(true, endedAt);
            }
            if (updated != task) {
                taskStore.update(updated);
            }
            settleQueue(terminal, providerQuiescent, lifecycleMessage(terminalState, reason), endedAt);
            settleSteersForTerminalTurn(terminal.id(), endedAt);
            outboxStore.insert(pendingLifecycle(
                    eventId,
                    terminal.taskId(),
                    Optional.of(terminal.id()),
                    lifecyclePhase(terminalState),
                    lifecycleMessage(terminalState, reason),
                    endedAt));
            return null;
        });
    }

    /** A turn cannot finish with steer input that is known not to have reached the provider. */
    private void settleSteersForTerminalTurn(TurnId turnId, Instant endedAt)
    {
        for (AgentInputDelivery delivery : steerStore.listUnsettled()) {
            if (!delivery.turnId().equals(turnId) || delivery.state() != AgentInputDelivery.State.ACCEPTED) {
                continue;
            }
            AgentInputDelivery settled = delivery.failed("Turn ended before the input was sent", endedAt);
            steerStore.update(settled);
            outboxStore.insert(pendingInline(
                    EventId.random(),
                    settled.taskId(),
                    Optional.of(settled.turnId()),
                    root(),
                    new AgentEventPayload.Error("STEER_FAILED", settled.failure().orElseThrow(), false),
                    endedAt));
        }
    }

    /**
     * What a turn's end means for the queue behind it: the input this turn was claimed from is
     * settled by what the turn proves, and anything less than a clean success pauses the rest.
     */
    private void settleQueue(AgentTurn terminal, boolean providerQuiescent, String outcome, Instant endedAt)
    {
        boolean deliveryGate = developmentTurnEligible(taskStore.require(terminal.taskId()), terminal)
                && terminal.settings().flatMap(AgentTurnSettings::developmentAutomation).filter(DevelopmentAutomation::enabled).isPresent();
        boolean ready = terminal.developmentResult().filter(DevelopmentResultReport::readyForDelivery).isPresent();
        boolean success = terminal.state() == TurnState.COMPLETED && providerQuiescent && (!deliveryGate || ready);
        String pauseReason = terminal.state() != TurnState.COMPLETED ? outcome
                : !providerQuiescent ? "Turn completed but its work could not be shown stopped"
                : terminal.developmentResult().flatMap(DevelopmentResultReport::message).orElse("Waiting for the agent's development readiness report");
        for (QueuedAgentInput input : queueStore.listQueued(terminal.taskId())) {
            if (input.state() == QueuedAgentInput.State.STARTING && input.turnId().equals(Optional.of(terminal.id()))) {
                queueStore.update(input.settledBy(terminal, outcome, outcome, endedAt));
            }
            else if (input.state() == QueuedAgentInput.State.QUEUED && !success) {
                queueStore.update(input.paused(pauseReason, endedAt));
            }
        }
    }

    private void retryCompletedTurn(TaskId taskId)
    {
        // Only a turn with a live handle can need it, and the registry knows those without reading every turn row.
        runtimeRegistry.runningTurns(taskId).forEach(turnId -> turnStore.find(turnId).ifPresent(this::retryCompletedTurn));
    }

    private void retryCompletedTurn(AgentTurn turn)
    {
        runtimeRegistry.running(turn.id()).ifPresent(runningTurn -> {
            CompletableFuture<AgentTurnResult> completion = runningTurn.completion().toCompletableFuture();
            if (!completion.isDone()) {
                return;
            }
            if (completion.isCancelled()) {
                finishTurn(turn, runningTurn, null, new IllegalStateException("Provider completion stage was cancelled"), false);
            }
            else if (completion.isCompletedExceptionally()) {
                finishTurn(turn, runningTurn, null, completion.exceptionNow(), false);
            }
            else {
                finishTurn(turn, runningTurn, completion.resultNow(), null, false);
            }
        });
    }

    /**
     * The settings a turn on this task records.
     *
     * <p>One derivation, shared by accepting a turn and by describing the next one, so what the
     * user is shown before sending cannot disagree with what is snapshotted when they do.
     */
    private AgentTurnSettings turnSettings(AgentTask task, List<AgentAttachmentReference> attachments)
    {
        return turnSettings(task, attachments, Optional.empty());
    }

    private AgentTurnSettings turnSettings(AgentTask task, List<AgentAttachmentReference> attachments, Optional<DevelopmentAutomation> automation)
    {
        if (automation.isPresent() && !task.interactive()) {
            throw new InvalidAgentRequestException("Development automation requires an interactive task");
        }
        Optional<DevelopmentAutomation> effective = automation.or(task.defaults()::developmentAutomation);
        if (task.interactive()) {
            // A person's request always commits; only a trusted caller, such as a learning card's read-only question, asks for a
            // turn that commits nothing.
            if (automation.filter(requested -> !requested.autoCommit()).isEmpty()) {
                effective = Optional.of(effective.orElse(DevelopmentAutomation.DEFAULT).forDevelopment());
            }
        }
        return new AgentTurnSettings(
                task.defaults().model(),
                task.defaults().effort(),
                task.permissionPolicy(),
                maximumTurnRuntime,
                attachments,
                task.defaults().serviceTier(),
                effective).withLaneId(task.defaults().laneId().orElse(task.laneId()));
    }

    private AgentTurn requireActiveTurn(TaskId taskId)
    {
        return turnStore.findActive(taskId)
                .orElseThrow(() -> new AgentConflictException("Task has no active turn: " + taskId));
    }

    private static ProviderApprovalDecision toProviderDecision(ApprovalDecision decision)
    {
        if (decision.timedOut()) {
            return ProviderApprovalDecision.expired();
        }
        return switch (decision.decision()) {
            case ALLOW -> ProviderApprovalDecision.allow(decision.scopedPermission(), decision.answers());
            case DENY -> ProviderApprovalDecision.deny();
        };
    }

    private static TurnState terminalState(AgentTurnResult.State state)
    {
        return switch (state) {
            case COMPLETED -> TurnState.COMPLETED;
            case FAILED -> TurnState.FAILED;
            case CANCELLED -> TurnState.CANCELLED;
            case INTERRUPTED -> TurnState.INTERRUPTED;
        };
    }

    private static AgentEventPayload.LifecyclePhase lifecyclePhase(TurnState state)
    {
        return state == TurnState.INTERRUPTED ? AgentEventPayload.LifecyclePhase.INTERRUPTED : AgentEventPayload.LifecyclePhase.TURN_COMPLETED;
    }

    private static String lifecycleMessage(TurnState state, Optional<String> reason)
    {
        return switch (state) {
            case COMPLETED -> "Turn completed";
            case FAILED -> "Turn failed: " + reason.orElseThrow();
            case CANCELLED -> "Turn cancelled: " + reason.orElseThrow();
            case INTERRUPTED -> "Turn interrupted: " + reason.orElseThrow();
            case ACCEPTED, RUNNING -> throw new IllegalArgumentException("Turn is not terminal: " + state);
        };
    }

    private record TurnAcceptance(AgentTurn turn, EventId deliveryEventId, String message, List<AgentAttachment> attachments, ReviewTurnInputResolver.Reference reference)
    {
        private TurnAcceptance
        {
            requireNonNull(turn, "turn is null");
            requireNonNull(deliveryEventId, "deliveryEventId is null");
            requireNonNull(message, "message is null");
            attachments = ImmutableList.copyOf(attachments);
            requireNonNull(reference, "reference is null");
        }

        private TurnAcceptance(AgentTurn turn, EventId deliveryEventId, String message, List<AgentAttachment> attachments)
        {
            this(turn, deliveryEventId, message, attachments, ReviewTurnInputResolver.Reference.NONE);
        }
    }

    /** What launching an accepted turn produced: a watched run, or the failure and what it left behind. */
    private record Launch(AgentTurn turn, Optional<RunningTurn> runningTurn, Optional<RuntimeException> failure)
    {
        private Launch
        {
            requireNonNull(turn, "turn is null");
            requireNonNull(runningTurn, "runningTurn is null");
            requireNonNull(failure, "failure is null");
            checkArgument(runningTurn.isPresent() || failure.isPresent(), "a successful launch requires a running turn");
        }
    }

    private record SteerAcceptance(AgentInputDelivery delivery, EventId messageEventId, AgentTurnInput input)
    {
        private SteerAcceptance
        {
            requireNonNull(delivery, "delivery is null");
            requireNonNull(messageEventId, "messageEventId is null");
            requireNonNull(input, "input is null");
        }
    }
}
