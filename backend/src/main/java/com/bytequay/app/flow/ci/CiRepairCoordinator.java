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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.AttributedFixupRebase.BoundaryOutcome;
import com.bytequay.app.flow.ci.CiAutofixRecords.AttemptState;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupSeal;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiLesson;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.QueuedRepair;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepairPlacement;
import com.bytequay.app.flow.ci.CiAutofixRecords.RepairPlacementPolicy;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.CleanupHandoff;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedNonCleanState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.FinalRedRegistration;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.Inspection;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanInspection;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.AgentCompletion;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.ExecutionHandle;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.WriterToolCapability;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

/**
 * The one concrete transaction joining CI evidence to runtime dispatch.
 *
 * <p>It joins exact-head enqueue/selection to one runtime-owned writer run and
 * joins the runtime's stopped-writer transaction to CI attempt finalization.

/** Owns one exact red CI round and its fenced repair writer. */
public final class CiRepairCoordinator
{
    private static final String CI_PROMPT_MANIFEST = "ci-fix-prompt:v1";
    private static final String CI_CAPABILITY_SET = "ci-fix-capabilities:v1";
    private static final Duration REWRITE_TIMEOUT = Duration.ofMinutes(10);
    // ponytail: one flat ceiling for every boundary build in a series. Make it
    // per-Task configuration if a range ever needs longer than this.
    private static final Duration BOUNDARY_PROOF_TIMEOUT = Duration.ofHours(2);

    private final TransactionTemplate transactions;
    private final CiAutofix autofix;
    private final FlowRuntime runtime;
    private final CiLearningCoordinator learning;
    private final FlowWorktreeInspector worktreeInspector =
            new FlowWorktreeInspector();
    private final AttributedFixupRebase rebase = new AttributedFixupRebase();

    public CiRepairCoordinator(
            DataSource dataSource,
            CiAutofix autofix,
            FlowRuntime runtime,
            CiLearningCoordinator learning)
    {
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(
                        requireNonNull(dataSource, "dataSource is null")));
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.learning = requireNonNull(learning, "learning is null");
    }

    public record RepairBinding(CiRepairAttempt attempt, AgentRun run)
    {
        public RepairBinding
        {
            requireNonNull(attempt, "attempt is null");
            requireNonNull(run, "run is null");
        }
    }

    /** Program-inspected writer admission; no caller-authored Git evidence. */
    public record RepairLaunchBinding(
            RepairBinding repair, WriterFence fence,
            Path repositoryRoot, Path worktree)
    {
        public RepairLaunchBinding
        {
            requireNonNull(repair, "repair is null");
            requireNonNull(fence, "fence is null");
            requireNonNull(repositoryRoot, "repositoryRoot is null");
            requireNonNull(worktree, "worktree is null");
        }
    }

    /** Exact round evidence behind zero-ID model tools. */
    public final class RepairToolContext
    {
        private final RepairBinding binding;
        private final String repositoryId;
        private final String goalText;
        private final String policyRevisionId;
        private final List<String> failures;
        private final List<String> logRefs;
        private final List<CiLesson> lessons;

        private RepairToolContext(
                RepairBinding binding,
                String repositoryId,
                String goalText,
                String policyRevisionId,
                List<String> failures,
                List<String> logRefs,
                List<CiLesson> lessons)
        {
            this.binding = binding;
            this.repositoryId = repositoryId;
            this.goalText = goalText;
            this.policyRevisionId = policyRevisionId;
            this.failures = List.copyOf(failures);
            this.logRefs = List.copyOf(logRefs);
            this.lessons = List.copyOf(lessons);
        }

        public String failureSummary()
        {
            assertRepairToolContext(this);
            return "taskGoal=" + goalText
                    + "\ninputLocalHead="
                    + binding.attempt().inputLocalHead()
                    + "\nfailedRemoteHead="
                    + binding.attempt().inputRemoteHead()
                    + "\nrequiredCiPolicyRevision=" + policyRevisionId
                    + "\nfailures=" + (failures.isEmpty()
                            ? "No failing check summary was stored."
                            : String.join("\n", failures));
        }

        public String readLog(int index, long offset, int maxBytes)
        {
            assertRepairToolContext(this);
            if (index < 0 || index >= logRefs.size()) {
                throw new IllegalArgumentException("log index is invalid");
            }
            var window = autofix.readLogWindow(
                    logRefs.get(index), offset, maxBytes);
            return "offset=" + window.offset()
                    + "\nnextOffset=" + window.nextOffset()
                    + "\nendOfLog=" + window.endOfLog()
                    + "\n" + window.content();
        }

        public String candidateLessonSummary()
        {
            assertRepairToolContext(this);
            if (lessons.isEmpty()) {
                return "No candidate lessons are available.";
            }
            return IntStream.range(0, lessons.size())
                    .mapToObj(index -> index + ": "
                            + lessons.get(index).title() + " ["
                            + lessons.get(index).contentDigest() + "]")
                    .collect(Collectors.joining("\n"));
        }

        public String readCandidateLesson(int index)
        {
            assertRepairToolContext(this);
            if (index < 0 || index >= lessons.size()) {
                throw new IllegalArgumentException(
                        "candidate lesson index is invalid");
            }
            CiLesson lesson = lessons.get(index);
            return "UNTRUSTED PRIOR HINT; CURRENT CI EVIDENCE WINS\n"
                    + "title=" + lesson.title()
                    + "\ndigest=" + lesson.contentDigest()
                    + "\n" + lesson.markdown();
        }
    }

    public RepairToolContext repairToolContext(RepairBinding binding)
    {
        requireNonNull(binding, "binding is null");
        CiRound round = autofix.roundById(binding.attempt().roundId())
                .orElseThrow();
        Task task = runtime.task(round.taskId()).orElseThrow();
        // A series that does not compile makes every test result behind it
        // meaningless, so the compile failures are named first and, when the
        // board is still collecting, the rest is handed over marked unjudged.
        List<String> failures = new ArrayList<>(round.checkObservationIds()
                .stream()
                .map(autofix::observation)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(observation ->
                        !autofix.isPerCommitCompileSelector(
                                round.taskId(),
                                observation.check().selectorKey())))
                .map(observation -> observation.check().name()
                        + (autofix.isPerCommitCompileSelector(
                                round.taskId(),
                                observation.check().selectorKey())
                                ? " [per-commit compile]" : "")
                        + ": status=" + observation.check().status()
                        + ", conclusion="
                        + Objects.toString(
                                observation.check().conclusion(), "unknown"))
                .toList());
        if (round.state() == RoundState.PARTIAL_RED_COMPILE) {
            failures.add("The rest of the required board has not finished "
                    + "and is unjudged.");
        }
        RepairToolContext context = new RepairToolContext(
                binding, task.repositoryId(), task.goalText(),
                round.policyRevisionId(), failures,
                round.failedLogRefs(), candidateLessons(task.repositoryId()));
        assertRepairToolContext(context);
        return context;
    }

    private void assertRepairToolContext(RepairToolContext context)
    {
        CiRepairAttempt stored = autofix.repairAttempt(
                context.binding.attempt().attemptId()).orElseThrow();
        CiRound round = autofix.roundById(stored.roundId()).orElseThrow();
        Task task = runtime.task(round.taskId()).orElseThrow();
        if (!stored.equals(context.binding.attempt())
                || !stored.agentRunId().equals(
                        context.binding.run().runId())
                || !round.failedLogRefs().equals(context.logRefs)
                || !task.repositoryId().equals(context.repositoryId)
                || !task.goalText().equals(context.goalText)
                || !round.policyRevisionId().equals(
                        context.policyRevisionId)
                || !candidateLessons(context.repositoryId).equals(
                        context.lessons)
                || context.lessons.stream().anyMatch(value ->
                        !learning.lesson(value.lessonId()).filter(value::equals)
                                .isPresent())) {
            throw new IllegalStateException(
                    "CI repair tool context changed after binding");
        }
    }

    private List<CiLesson> candidateLessons(String repositoryId)
    {
        return learning.candidateLessons(repositoryId);
    }

    public QueuedRepair enqueueRepair(String roundId)
    {
        requireText(roundId, "roundId");
        return requireNonNull(transactions.execute(ignored -> {
            CiRound round = autofix.roundById(roundId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown CI round: " + roundId));
            Task task = requireExactPublishedSubject(round);
            if (isTerminal(task.status())) {
                var refreshed = autofix.finalizeHeadSnapshot(
                        round.prId(), round.remoteHead());
                if (!(refreshed instanceof FinalizedRound finalized)
                        || (!CiAutofix.admitsRepair(finalized.round().state())
                        && finalized.round().state() != RoundState.QUEUED)) {
                    throw new IllegalStateException(
                            "Terminal Task has no current red CI evidence");
                }
                round = finalized.round();
                FinalRedRegistration registration = runtime.registerFinalRed(
                        round.roundId(),
                        round.taskId(),
                        round.prId(),
                        round.remoteHead(),
                        "ci-round:" + round.roundId());
                return new QueuedRepair(
                        round,
                        registration.inboxId(),
                        registration.reconciliationOperationId(),
                        registration.terminalReason());
            }
            if (!isParkable(task.status())) {
                throw new IllegalStateException(
                        "CI round Task cannot accept repair work");
            }
            CiRound queued = autofix.queueCurrentFinalRed(roundId);
            FinalRedRegistration registration = runtime.registerFinalRed(
                    queued.roundId(),
                    queued.taskId(),
                    queued.prId(),
                    queued.remoteHead(),
                    "ci-round:" + queued.roundId());
            return new QueuedRepair(
                    queued,
                    registration.inboxId(),
                    registration.reconciliationOperationId(),
                    registration.terminalReason());
        }), "enqueue transaction returned null");
    }

    /**
     * Rechecks provider evidence before delegating one selection to the
     * runtime. Newer red evidence is queued before the stale cause is consumed,
     * so a missed or duplicate provider delivery cannot strand repair work.
     */
    public Optional<Operation> selectNext(Claim reconciliationClaim)
    {
        requireNonNull(reconciliationClaim, "reconciliationClaim is null");
        return requireNonNull(transactions.execute(ignored -> {
            while (true) {
                Optional<PendingWork> candidate =
                        runtime.nextPendingForReconciliation(
                                reconciliationClaim);
                if (candidate.isEmpty()
                        || candidate.get().kind() != PendingKind.FINAL_RED) {
                    return runtime.selectNext(reconciliationClaim);
                }
                PendingWork pending = candidate.get();
                CiRound queued = autofix.roundById(pending.externalKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "Runtime references an unknown CI round"));
                var refreshed = autofix.finalizeHeadSnapshot(
                        queued.prId(), queued.remoteHead());
                if (refreshed instanceof FinalizedRound finalized
                        && finalized.round().roundId().equals(queued.roundId())
                        && finalized.round().state() == RoundState.QUEUED) {
                    return runtime.selectNext(reconciliationClaim);
                }
                if (refreshed instanceof FinalizedRound finalized
                        && CiAutofix.admitsRepair(finalized.round().state())
                        && exactNonterminalSubject(finalized.round())) {
                    CiRound successor = autofix.queueCurrentFinalRed(
                            finalized.round().roundId());
                    runtime.registerFinalRed(
                            successor.roundId(),
                            successor.taskId(),
                            successor.prId(),
                            successor.remoteHead(),
                            "ci-round:" + successor.roundId());
                }
                CiRound current = autofix.roundById(queued.roundId())
                        .orElseThrow();
                if (current.state() == RoundState.QUEUED) {
                    autofix.supersedeQueuedRound(current.roundId());
                }
                runtime.discardPendingFinalRed(
                        reconciliationClaim, pending.pendingId());
            }
        }), "selection transaction returned null");
    }

    /** Atomically binds one selected current CI round to its persistent run. */
    public RepairBinding beginRepair(
            String roundId, Claim claim, WriterFence fence)
    {
        requireText(roundId, "roundId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        return requireNonNull(transactions.execute(ignored -> {
            Optional<CiRepairAttempt> existing =
                    autofix.repairAttemptForRound(roundId, 0);
            if (existing.isPresent()) {
                return redeliverRepairBinding(existing.get(), claim, fence);
            }
            CiRound round = requireCurrentRepairRound(
                    roundId, RoundState.QUEUED, RoundState.ACTIVE);
            Task task = requireExactPublishedSubject(round);
            Operation operation = requireCiOperation(round, claim, fence, task);
            ChangeSetRevision input = runtime.currentChangeSet(task.taskId())
                    .orElseThrow(() -> new IllegalStateException(
                            "CI repair Task has no inspected change set"));
            if (!input.headSha().equals(round.remoteHead())
                    || !input.changeSetRevisionId().equals(
                            task.currentChangeSetRevisionId())) {
                throw new IllegalStateException(
                        "CI repair input change set is not the remote head");
            }
            AgentRun run = runtime.startWriterAgent(
                    claim,
                    fence,
                    CI_PROMPT_MANIFEST,
                    CI_CAPABILITY_SET);
            if (!run.operationId().equals(operation.operationId())
                    || run.role() != AgentRole.CI_FIXER
                    || !run.headSha().equals(round.remoteHead())) {
                throw new IllegalStateException(
                        "CI repair run is not bound to the exact input head");
            }
            CiRepairAttempt attempt = autofix.bindRepairAttempt(
                    round,
                    operation.operationId(),
                    run.runId(),
                    task.currentHeadSha(),
                    input.changeSetRevisionId());
            return new RepairBinding(attempt, run);
        }), "repair begin transaction returned null");
    }

    /**
     * Production admission for a repair: derive the round and worktree from
     * the claimed operation, mechanically inspect the immutable current
     * change set, then consume that proof into the fence and run atomically.
     */
    public RepairLaunchBinding beginInspectedRepair(
            Claim claim, Duration leaseTtl)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(leaseTtl, "leaseTtl is null");
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown CI repair operation"));
        if (operation.kind() != OperationKind.RUN_CI_FIXER
                || !operation.ownerKind().equals("CI_ROUND")) {
            throw new IllegalArgumentException(
                    "claim is not a CI repair round");
        }
        Task task = runtime.task(operation.taskId()).orElseThrow();
        ChangeSetRevision changeSet = runtime.currentChangeSet(task.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI repair has no current change set"));
        Path repositoryRoot = Path.of(task.repositoryRoot());
        Path worktree = Path.of(task.worktreePath());
        Inspection inspection = worktreeInspector.inspect(
                repositoryRoot,
                worktree,
                task.branchName(),
                task.currentBaseSha(),
                task.currentHeadSha());
        if (!inspection.headSha().equals(changeSet.headSha())
                || !inspection.headTreeDigest().equals(
                        changeSet.headTreeDigest())
                || !inspection.baseToHeadDiffDigest().equals(
                        changeSet.diffDigest())) {
            throw new IllegalStateException(
                    "CI repair worktree differs from its immutable change set");
        }
        return requireNonNull(transactions.execute(ignored -> {
            Operation currentOperation = runtime.operation(
                    operation.operationId()).orElseThrow();
            Task currentTask = runtime.task(task.taskId()).orElseThrow();
            ChangeSetRevision currentChangeSet = runtime.currentChangeSet(
                    task.taskId()).orElseThrow();
            if (!currentOperation.equals(operation)
                    || !currentTask.equals(task)
                    || !currentChangeSet.equals(changeSet)) {
                throw new IllegalStateException(
                        "CI repair owner changed after inspection");
            }
            WriterFence fence = runtime.acquireWriterLease(
                    claim,
                    AgentRole.CI_FIXER,
                    new WorktreeSnapshot(
                            inspection.headSha(),
                            inspection.headTreeDigest(),
                            "ci-change-set:"
                                    + changeSet.changeSetRevisionId()),
                    leaseTtl);
            RepairBinding repair = beginRepair(
                    operation.ownerId(), claim, fence);
            return new RepairLaunchBinding(
                    repair, fence, repositoryRoot, worktree);
        }), "CI repair admission transaction returned null");
    }

    /** Launches only with the attempt-bound CI finalizer route. */
    public ExecutionHandle launchRepair(
            InProcessWriterAgentSupervisor supervisor,
            RepairBinding binding,
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            Function<WriterToolCapability, AgentCompletion> body)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireNonNull(body, "body is null");
        requireLaunchBinding(binding, claim, fence);
        String finalizerKey = finalizerKey(binding.attempt().attemptId());
        return supervisor.launch(
                binding.run().runId(),
                claim,
                fence,
                finalizerKey,
                (runId, currentClaim, currentFence, completion) ->
                        finalizeRepairAttempt(
                                binding.attempt().attemptId(),
                                runId,
                                currentClaim,
                                currentFence,
                                completion,
                                programOwnedRepositoryRoot),
                body);
    }

    public AgentResult awaitRepair(
            InProcessWriterAgentSupervisor supervisor,
            RepairBinding binding,
            ExecutionHandle handle,
            Duration timeout)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        return supervisor.awaitAndFinalize(
                handle, timeout, finalizerKey(binding.attempt().attemptId()));
    }

    /** Re-inspects and starts the sole sealed CI cleanup successor. */

    /** Replays only a durably STOPPED repair; no body is relaunched. */
    public AgentResult recoverExpiredStoppedRepair(
            String operationId, long generation, Duration ttl)
    {
        FlowRuntime.StoppedWriterRecovery recovery =
                runtime.reviveExpiredStoppedWriter(
                        operationId, generation, ttl);
        Operation operation = runtime.operation(operationId).orElseThrow();
        if (!operation.ownerKind().equals("CI_ROUND")) {
            throw new IllegalArgumentException(
                    "stopped repair has an unknown owner");
        }
        Task task = runtime.task(operation.taskId()).orElseThrow();
        AgentCompletion completion = new AgentCompletion(
                recovery.completion().terminalOutcome(),
                recovery.completion().finalContent(),
                recovery.completion().errorRef());
        return finalizeRepairAttempt(
                operation.ownerId(), recovery.run().runId(),
                recovery.claim(), recovery.fence(), completion,
                Path.of(task.repositoryRoot()));
    }

    AgentResult finalizeRepairAttempt(
            String attemptId,
            String runId,
            Claim claim,
            WriterFence fence,
            AgentCompletion completion,
            Path programOwnedRepositoryRoot)
    {
        requireText(attemptId, "attemptId");
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(completion, "completion is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        CiRepairAttempt attempt = autofix.repairAttempt(attemptId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown CI repair attempt: " + attemptId));
        assertAttemptIdentity(attempt, runId, claim, fence);
        if (attempt.state() == AttemptState.NON_CLEAN_HANDOFF) {
            CiCleanupSeal seal = autofix.cleanupSealForRepair(attemptId)
                    .orElseThrow(() -> new IllegalStateException(
                            "CI cleanup attempt has no durable seal"));
            AgentResult replay = runtime.replayStoppedCiCleanupHandoff(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    attemptId,
                    seal.cleanupId(),
                    attempt.inputChangeSetRevisionId(),
                    attempt.inputLocalHead(),
                    new NonCleanInspection(
                            seal.actualHead(),
                            seal.branchHead(),
                            seal.attachmentState(),
                            seal.kind(),
                            seal.operations(),
                            seal.stateDigest()),
                    seal.stateDigest(),
                    seal.successorOperationId());
            if (!attempt.resultRef().equals(replay.resultId())) {
                throw new IllegalStateException(
                        "CI cleanup attempt changed predecessor result");
            }
            return replay;
        }
        if (attempt.state() == AttemptState.FIX_PREPARED
                || attempt.state() == AttemptState.NO_HEAD_CHANGE) {
            CiFixOutcome outcome = toCiFixOutcome(attempt.state());
            return runtime.finishCiAgentRun(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    attemptId,
                    outcome,
                    attempt.outputLocalHead(),
                    attempt.outputChangeSetRevisionId());
        }
        runtime.assertWriterRunStopped(runId, claim);
        requireActiveAttemptSubject(attempt, runId, claim, fence, true);
        FlowRuntime.PreparedChangeSet prepared;
        try {
            prepared = runtime.prepareChangeSet(
                    claim,
                    fence,
                    programOwnedRepositoryRoot,
                    attempt.inputChangeSetRevisionId());
        }
        catch (InspectionFailure failure) {
            if (failure.code() == FailureCode.DIRTY
                    || failure.code() == FailureCode.GIT_OPERATION_IN_PROGRESS) {
                return handoffDirtyRepair(
                        attempt,
                        runId,
                        claim,
                        fence,
                        completion,
                        programOwnedRepositoryRoot);
            }
            throw failure;
        }
        // Attributed placement publishes a reviewable series, so the repair is
        // repositioned behind the commit it names and proven at the boundaries
        // before anything adopts it. What the Task Agent and the reviewer see
        // is then the series that gets pushed.
        AttributedSeriesResult series = attributeAndProve(
                attempt, claim, fence, programOwnedRepositoryRoot, prepared);
        FlowRuntime.PreparedChangeSet adopted = series.prepared();
        return requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt current = autofix.repairAttempt(attemptId)
                    .orElseThrow();
            assertAttemptIdentity(current, runId, claim, fence);
            requireActiveAttemptSubject(current, runId, claim, fence, true);
            ChangeSetRevision output = runtime.adoptPreparedChangeSet(
                    claim, fence, adopted);
            if (series.boundaries() != null) {
                autofix.storeBoundaryCompileProof(
                        autofix.roundById(current.roundId())
                                .orElseThrow().taskId(),
                        attemptId,
                        output.headSha(),
                        series.profileRevisionId(),
                        series.boundaries());
            }
            CiFixOutcome outcome = output.headSha().equals(
                    current.inputLocalHead())
                    ? CiFixOutcome.NO_HEAD_CHANGE
                    : CiFixOutcome.FIX_PREPARED;
            requireActiveAttemptSubject(current, runId, claim, fence, false);
            AgentResult result = runtime.finishCiAgentRun(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    attemptId,
                    outcome,
                    output.headSha(),
                    output.changeSetRevisionId());
            autofix.completeRepairAttempt(
                    attemptId,
                    output.headSha(),
                    output.changeSetRevisionId(),
                    result.resultId(),
                    toAttemptState(outcome));
            return result;
        }), "repair finalization transaction returned null");
    }

    /**
     * Repositions this Task's series and proves it, when its placement says so.
     *
     * <p>Returns the adoption to use. A Task that does not rewrite, a rewrite
     * that cannot be generated, and a proof that is incomplete or red all fall
     * back to the fixer's own head with no proof stored, which leaves the
     * repair visible and blocks publication rather than pushing a series this
     * program could not prove.
     */
    private AttributedSeriesResult attributeAndProve(
            CiRepairAttempt attempt,
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            FlowRuntime.PreparedChangeSet prepared)
    {
        CiRound round = autofix.roundById(attempt.roundId()).orElseThrow();
        RepairPlacementPolicy placement = autofix.placementPolicy(
                round.taskId());
        if (placement.placement() != RepairPlacement.ATTRIBUTED_FIXUP
                || placement.boundaryBuildCommand().isEmpty()) {
            return new AttributedSeriesResult(prepared, null, null);
        }
        Task task = runtime.task(round.taskId()).orElseThrow();
        Path worktree = Path.of(task.worktreePath());
        String baseSha = task.currentBaseSha();
        String head = prepared.headSha();
        List<BoundaryOutcome> boundaries;
        String rewritten;
        try {
            rewritten = rebase.reposition(
                    worktree, baseSha, head, REWRITE_TIMEOUT).outputHead();
            boundaries = rebase.proveBoundaries(
                    worktree,
                    baseSha,
                    rewritten,
                    placement.boundaryBuildCommand(),
                    BOUNDARY_PROOF_TIMEOUT);
        }
        catch (AttributedFixupRebase.RebaseFailure failure) {
            return new AttributedSeriesResult(prepared, null, null);
        }
        if (boundaries.isEmpty()
                || !boundaries.stream().allMatch(BoundaryOutcome::passed)) {
            return new AttributedSeriesResult(prepared, null, null);
        }
        FlowRuntime.PreparedChangeSet adoption = rewritten.equals(head)
                ? prepared
                : runtime.prepareRewrittenChangeSet(
                        claim,
                        fence,
                        programOwnedRepositoryRoot,
                        attempt.inputChangeSetRevisionId(),
                        rewritten);
        return new AttributedSeriesResult(
                adoption,
                boundaries,
                "boundary-build:" + placement.boundaryBuildCommand().hashCode());
    }

    private record AttributedSeriesResult(
            FlowRuntime.PreparedChangeSet prepared,
            List<BoundaryOutcome> boundaries,
            String profileRevisionId) {}

    private AgentResult handoffDirtyRepair(
            CiRepairAttempt attempt,
            String runId,
            Claim claim,
            WriterFence fence,
            AgentCompletion completion,
            Path programOwnedRepositoryRoot)
    {
        PreparedNonCleanState prepared;
        try {
            prepared = runtime.prepareNonCleanState(
                    claim,
                    fence,
                    programOwnedRepositoryRoot,
                    attempt.inputChangeSetRevisionId());
        }
        catch (InspectionFailure failure) {
            if (blocksCleanupSeal(failure.code())) {
                blockUnsealableDirtyRepair(
                        attempt, runId, claim, fence);
            }
            throw failure;
        }
        return requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt current = autofix.repairAttempt(
                    attempt.attemptId()).orElseThrow();
            assertAttemptIdentity(current, runId, claim, fence);
            requireActiveAttemptSubject(current, runId, claim, fence, true);
            CleanupHandoff handoff = runtime.handoffStoppedCiRunToCleanup(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    attempt.attemptId(),
                    prepared);
            autofix.storeCleanupSeal(attempt.attemptId(), handoff);
            return handoff.predecessorResult();
        }), "dirty repair handoff transaction returned null");
    }

    private void blockUnsealableDirtyRepair(
            CiRepairAttempt attempt,
            String runId,
            Claim claim,
            WriterFence fence)
    {
        requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt current = autofix.repairAttempt(
                    attempt.attemptId()).orElseThrow();
            assertAttemptIdentity(current, runId, claim, fence);
            runtime.assertWriterRunStopped(runId, claim);
            requireActiveAttemptSubject(
                    current, runId, claim, fence, true);
            autofix.blockDirtyRepairAttempt(attempt.attemptId());
            return Boolean.TRUE;
        }), "dirty repair block transaction returned null");
    }

    static boolean blocksCleanupSeal(FailureCode code)
    {
        return code == FailureCode.UNTRUSTED_REPOSITORY_STATE
                || code == FailureCode.OUTPUT_LIMIT
                || code == FailureCode.NOT_WORKTREE
                || code == FailureCode.WRONG_REPOSITORY
                || code == FailureCode.DETACHED_HEAD
                || code == FailureCode.WRONG_BRANCH
                || code == FailureCode.BRANCH_HEAD_MISMATCH
                || code == FailureCode.BASE_NOT_FOUND
                || code == FailureCode.PREDECESSOR_NOT_FOUND
                || code == FailureCode.BASE_NOT_ANCESTOR
                || code == FailureCode.PREDECESSOR_NOT_ANCESTOR;
    }

    private RepairBinding redeliverRepairBinding(
            CiRepairAttempt attempt, Claim claim, WriterFence fence)
    {
        if (attempt.state() != AttemptState.ACTIVE) {
            throw new IllegalStateException(
                    "CI repair attempt is no longer active");
        }
        AgentRun run = runtime.runForOperation(claim.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI repair run is not durable"));
        assertAttemptIdentity(attempt, run.runId(), claim, fence);
        CiRound round = requireBoundActiveRound(attempt);
        Task task = requireExactPublishedSubject(round);
        requireCiOperation(round, claim, fence, task);
        if ((run.state() != RunState.QUEUED
                && run.state() != RunState.RUNNING)
                || !run.runId().equals(attempt.agentRunId())
                || !run.headSha().equals(attempt.inputLocalHead())
                || !task.currentHeadSha().equals(attempt.inputLocalHead())
                || !task.currentChangeSetRevisionId().equals(
                        attempt.inputChangeSetRevisionId())) {
            throw new IllegalStateException(
                    "CI repair redelivery changed bound identity");
        }
        return new RepairBinding(attempt, run);
    }

    private void requireLaunchBinding(
            RepairBinding binding, Claim claim, WriterFence fence)
    {
        CiRepairAttempt stored = autofix.repairAttempt(
                binding.attempt().attemptId()).orElseThrow(() ->
                        new IllegalStateException(
                                "CI repair attempt is not durable"));
        if (!stored.equals(binding.attempt())
                || stored.state() != AttemptState.ACTIVE
                || !stored.agentRunId().equals(binding.run().runId())) {
            throw new IllegalStateException(
                    "CI repair binding is not the current active attempt");
        }
        assertAttemptIdentity(stored, binding.run().runId(), claim, fence);
        CiRound round = requireBoundActiveRound(stored);
        Task task = requireExactPublishedSubject(round);
        AgentRun current = runtime.runForOperation(claim.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI repair run is not durable"));
        if (!current.equals(binding.run())
                || current.role() != AgentRole.CI_FIXER
                || (current.state() != RunState.QUEUED
                        && current.state() != RunState.RUNNING)
                || task.status() != TaskStatus.ACTIVE
                || !task.selectedWriterOperationId().equals(
                        claim.operationId())
                || !task.currentHeadSha().equals(stored.inputLocalHead())
                || !task.currentChangeSetRevisionId().equals(
                        stored.inputChangeSetRevisionId())) {
            throw new IllegalStateException(
                    "CI repair binding changed before launch");
        }
        runtime.assertWriterFence(claim, fence);
    }

    private static CiFixOutcome toCiFixOutcome(AttemptState state)
    {
        return switch (state) {
            case FIX_PREPARED -> CiFixOutcome.FIX_PREPARED;
            case NO_HEAD_CHANGE -> CiFixOutcome.NO_HEAD_CHANGE;
            default -> throw new IllegalArgumentException(
                    "CI attempt has no clean outcome: " + state);
        };
    }

    private static AttemptState toAttemptState(CiFixOutcome outcome)
    {
        return switch (outcome) {
            case FIX_PREPARED -> AttemptState.FIX_PREPARED;
            case NO_HEAD_CHANGE -> AttemptState.NO_HEAD_CHANGE;
        };
    }

    private void requireActiveAttemptSubject(
            CiRepairAttempt attempt,
            String runId,
            Claim claim,
            WriterFence fence,
            boolean requireInputLocalHead)
    {
        if (attempt.state() != AttemptState.ACTIVE) {
            throw new IllegalStateException("CI repair attempt is not active");
        }
        CiRound round = requireBoundActiveRound(attempt);
        Task task = runtime.task(round.taskId()).orElseThrow();
        PullRequestSubject pr = runtime.pullRequest(round.prId()).orElseThrow();
        AgentRun run = runtime.runForOperation(claim.operationId())
                .orElseThrow();
        if (!pr.published()
                || !pr.taskId().equals(task.taskId())
                || !pr.currentRemoteHead().equals(attempt.inputRemoteHead())
                || !round.remoteHead().equals(attempt.inputRemoteHead())
                || task.status() != TaskStatus.ACTIVE
                || !claim.operationId().equals(
                        task.selectedWriterOperationId())
                || !run.runId().equals(runId)
                || run.role() != AgentRole.CI_FIXER
                || run.state() != RunState.RUNNING
                || (requireInputLocalHead
                        && (!task.currentHeadSha().equals(
                                attempt.inputLocalHead())
                        || !task.currentChangeSetRevisionId().equals(
                                attempt.inputChangeSetRevisionId())))) {
            throw new IllegalStateException(
                    "CI repair subject changed before finalization");
        }
        runtime.assertWriterFence(claim, fence);
    }

    private CiRound requireBoundActiveRound(CiRepairAttempt attempt)
    {
        CiRound bound = autofix.roundById(attempt.roundId()).orElseThrow();
        autofix.finalizeHeadSnapshot(bound.prId(), bound.remoteHead());
        CiRound refreshed = autofix.roundById(attempt.roundId()).orElseThrow();
        if (refreshed.state() != RoundState.ACTIVE
                && refreshed.state() != RoundState.SUPERSEDED) {
            throw new IllegalStateException(
                    "CI repair bound round is not active or superseded");
        }
        return refreshed;
    }

    private CiRound requireCurrentRepairRound(
            String roundId, RoundState... allowedStates)
    {
        CiRound requested = autofix.roundById(roundId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown CI round: " + roundId));
        var refreshed = autofix.finalizeHeadSnapshot(
                requested.prId(), requested.remoteHead());
        if (!(refreshed instanceof FinalizedRound finalized)
                || !finalized.round().roundId().equals(roundId)
                || Arrays.stream(allowedStates).noneMatch(
                        state -> state == finalized.round().state())) {
            throw new IllegalStateException(
                    "CI repair round is no longer current");
        }
        return finalized.round();
    }

    private Operation requireCiOperation(
            CiRound round, Claim claim, WriterFence fence, Task task)
    {
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI repair operation does not exist"));
        if (claim.kind() != OperationKind.RUN_CI_FIXER
                || fence.holderKind() != AgentRole.CI_FIXER
                || !operation.operationId().equals(
                        task.selectedWriterOperationId())
                || operation.kind() != OperationKind.RUN_CI_FIXER
                || !operation.ownerKind().equals("CI_ROUND")
                || !operation.ownerId().equals(round.roundId())) {
            throw new IllegalStateException(
                    "CI repair operation is not the selected round owner");
        }
        runtime.assertWriterFence(claim, fence);
        return operation;
    }

    private static void assertAttemptIdentity(
            CiRepairAttempt attempt,
            String runId,
            Claim claim,
            WriterFence fence)
    {
        if (!attempt.operationId().equals(claim.operationId())
                || !attempt.agentRunId().equals(runId)
                || !fence.operationId().equals(claim.operationId())
                || fence.holderKind() != AgentRole.CI_FIXER) {
            throw new IllegalStateException(
                    "CI repair finalizer does not own this attempt");
        }
    }

    private static String finalizerKey(String attemptId)
    {
        return "CI_REPAIR:" + attemptId;
    }

    private Task requireExactPublishedSubject(CiRound round)
    {
        Task task = runtime.task(round.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI round Task does not exist"));
        PullRequestSubject pr = runtime.pullRequest(round.prId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI round PR does not exist"));
        if (!pr.published()
                || !pr.taskId().equals(task.taskId())
                || !round.prId().equals(task.prId())
                || !round.remoteHead().equals(task.currentHeadSha())
                || !round.remoteHead().equals(pr.currentRemoteHead())) {
            throw new IllegalStateException(
                    "CI round is not the exact published Task/PR head");
        }
        return task;
    }

    private boolean exactNonterminalSubject(CiRound round)
    {
        Task task = requireExactPublishedSubject(round);
        return isParkable(task.status());
    }

    private static boolean isParkable(TaskStatus status)
    {
        return status == TaskStatus.ACTIVE
                || status == TaskStatus.WAITING_USER
                || status == TaskStatus.NEEDS_ATTENTION;
    }

    private static boolean isTerminal(TaskStatus status)
    {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.CANCELED;
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
