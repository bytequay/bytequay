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

import com.bytequay.app.flow.ci.CiAutofixRecords.AttemptState;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupCompletion;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupSeal;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.CleanupOutcome;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.gate.UserGateRecords.ReadyForReviewAcceptance;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedTaskWriterAdmission;
import com.bytequay.app.flow.runtime.FlowRuntime.ReviewerStart;
import com.bytequay.app.flow.runtime.FlowRuntime.TaskWriterStart;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetSource;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixReviewOrigin;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixSourceKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.ImmutableGitObjectReader;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor.AgentCompletion;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor.ExecutionHandle;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor.ReviewerToolCapability;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.WriterToolCapability;
import com.bytequay.app.flow.runtime.LocalChecks;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Exact CI-fix inspection and fresh adversarial-review round trip. */
public final class CiFixReviewCoordinator
{
    private static final String TASK_PROMPT = "task-ci-inspection-prompt:v2";
    private static final String TASK_CAPABILITIES =
            "task-ci-inspection-capabilities:v2";
    private static final String REVIEWER_PROMPT =
            "adversarial-reviewer-prompt:v3";
    private static final String REVIEWER_CAPABILITIES =
            "immutable-git-object-reader:v1";

    private final CiAutofix autofix;
    private final FlowRuntime runtime;
    private final LocalChecks localChecks;
    private final UserGates userGates;

    public CiFixReviewCoordinator(
            CiAutofix autofix,
            FlowRuntime runtime,
            LocalChecks localChecks,
            UserGates userGates)
    {
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.localChecks = requireNonNull(localChecks, "localChecks is null");
        this.userGates = requireNonNull(userGates, "userGates is null");
    }

    public enum FixSource
    {
        REPAIR_ATTEMPT,
        CLEANUP
    }

    /** Program-owned projection; no payload or model content is parsed. */
    public record CiFixReviewProjection(
            FixSource source,
            String sourceId,
            String roundId,
            String taskId,
            String prId,
            String inputRemoteHead,
            AgentResult fixerResult,
            ChangeSetRevision output,
            GateIntent intendedGateKind)
    {
        public CiFixReviewProjection
        {
            requireNonNull(source, "source is null");
            requireText(sourceId, "sourceId");
            requireText(roundId, "roundId");
            requireText(taskId, "taskId");
            requireText(prId, "prId");
            requireText(inputRemoteHead, "inputRemoteHead");
            requireNonNull(fixerResult, "fixerResult is null");
            requireNonNull(output, "output is null");
            requireNonNull(intendedGateKind,
                    "intendedGateKind is null");
        }
    }

    public record TaskInspectionBinding(
            CiFixReviewProjection projection,
            PendingWork input,
            Path repositoryRoot,
            WriterFence fence,
            AgentRun run)
    {
        public TaskInspectionBinding
        {
            requireNonNull(projection, "projection is null");
            requireNonNull(input, "input is null");
            requireNonNull(repositoryRoot, "repositoryRoot is null");
            requireNonNull(fence, "fence is null");
            requireNonNull(run, "run is null");
        }
    }

    /** Task tools with the terminal reviewer subject fixed by the program. */
    public static final class TaskInspectionToolCapability
    {
        private final WriterToolCapability writer;
        private final FlowRuntime runtime;
        private final LocalChecks localChecks;
        private final Path repositoryRoot;
        private final String taskId;
        private final String runId;
        private String changeSetRevisionId;
        private final GateIntent intendedGateKind;
        private final CiFixReviewOrigin origin;
        private final UserGates userGates;
        private final ReviewerRequest completedReviewerRequest;
        private final AgentResult completedReviewerResult;

        private TaskInspectionToolCapability(
                WriterToolCapability writer,
                TaskInspectionBinding binding,
                FlowRuntime runtime,
                LocalChecks localChecks,
                UserGates userGates)
        {
            this(
                    writer,
                    runtime,
                    localChecks,
                    binding.repositoryRoot(),
                    binding.projection().taskId(),
                    binding.run().runId(),
                    binding.run().inputChangeSetRevisionId(),
                    binding.run().intendedGateKind(),
                    new CiFixReviewOrigin(
                            binding.input().pendingId(),
                            CiFixSourceKind.valueOf(
                                    binding.projection().source().name()),
                            binding.projection().sourceId()),
                    userGates,
                    null,
                    null);
        }

        private TaskInspectionToolCapability(
                WriterToolCapability writer,
                FlowRuntime runtime,
                LocalChecks localChecks,
                Path repositoryRoot,
                String taskId,
                String runId,
                String changeSetRevisionId,
                GateIntent intendedGateKind,
                CiFixReviewOrigin origin,
                UserGates userGates,
                ReviewerRequest completedReviewerRequest,
                AgentResult completedReviewerResult)
        {
            this.writer = requireNonNull(writer, "writer is null");
            this.runtime = requireNonNull(runtime, "runtime is null");
            this.localChecks = requireNonNull(
                    localChecks, "localChecks is null");
            this.repositoryRoot = requireNonNull(
                    repositoryRoot, "repositoryRoot is null");
            this.taskId = requireNonNull(taskId, "taskId is null");
            this.runId = requireNonNull(runId, "runId is null");
            this.changeSetRevisionId = requireNonNull(
                    changeSetRevisionId, "changeSetRevisionId is null");
            this.intendedGateKind = requireNonNull(
                    intendedGateKind, "intendedGateKind is null");
            this.origin = requireNonNull(origin, "origin is null");
            this.userGates = requireNonNull(userGates, "userGates is null");
            this.completedReviewerRequest = completedReviewerRequest;
            this.completedReviewerResult = completedReviewerResult;
        }

        public <T> T callTool(Supplier<T> effect)
        {
            return writer.callTool(effect);
        }

        /** Straight through: the group belongs to the writer turn beneath. */
        public void recordAgentGroup(
                long agentPid, long agentPgid, Instant agentStartedAt)
        {
            writer.recordAgentGroup(agentPid, agentPgid, agentStartedAt);
        }

        /** What this turn spent, and the handle its successor resumes. */
        public void recordAgentTurnUsage(
                String providerSessionId,
                long tokensIn,
                long tokensOut,
                long costMilliUsd)
        {
            writer.recordAgentTurnUsage(
                    providerSessionId, tokensIn, tokensOut, costMilliUsd);
        }

        public void runTool(Runnable effect)
        {
            writer.runTool(effect);
        }

        public List<LocalCheckRun> runChecks()
        {
            return writer.runChecks(localChecks, repositoryRoot, null);
        }

        public List<LocalCheckRun> runChecks(String profileName)
        {
            requireText(profileName, "profileName");
            return writer.runChecks(
                    localChecks, repositoryRoot, profileName);
        }

        public List<LocalCheckRun> runChecks(
                List<String> command, String workingDirectory)
        {
            return writer.runChecks(
                    localChecks, repositoryRoot, command, workingDirectory);
        }

        /** Program-only adoption after the fixed local commit tool returns. */
        public void adoptCurrentChangeSet()
        {
            ChangeSetRevision adopted = writer.adoptChangeSet(
                    repositoryRoot, changeSetRevisionId);
            changeSetRevisionId = adopted.changeSetRevisionId();
        }

        /** Terminal command over the exact program-owned review subject. */
        public ReviewerRequest spawnAdversarialReviewer()
        {
            ReviewerRequest existing = runtime.reviewerRequestForParentRun(
                    runId).orElse(null);
            if (existing != null) {
                return writer.replayAdversarialReviewer(
                        repositoryRoot,
                        changeSetRevisionId,
                        origin,
                        existing.localCheckPolicyRevisionId(),
                        existing.checkRunRefs());
            }
            ChangeSetRevision current = runtime.currentChangeSet(taskId)
                    .orElseThrow(() -> new IllegalStateException(
                            "review subject has no current change set"));
            return writer.spawnAdversarialReviewer(
                    repositoryRoot,
                    changeSetRevisionId,
                    origin,
                    localChecks.reviewerEvidence(
                            taskId,
                            current.changeSetRevisionId(),
                            intendedGateKind));
        }

        /** Terminal zero-argument declaration over program-owned evidence. */
        public ReadyForReviewAcceptance readyForReview()
        {
            if (completedReviewerRequest == null
                    || completedReviewerResult == null) {
                throw UserGates.missingExactReview();
            }
            return writer.readyForReview(
                    userGates,
                    repositoryRoot,
                    completedReviewerRequest,
                    completedReviewerResult,
                    origin);
        }

        @Override
        public String toString()
        {
            return "TaskInspectionToolCapability[opaque]";
        }
    }

    public record ReviewerResultBinding(
            ReviewerRequest request,
            AgentResult result,
            PendingWork input,
            Path repositoryRoot,
            WriterFence fence,
            AgentRun run)
    {
        public ReviewerResultBinding
        {
            requireNonNull(request, "request is null");
            requireNonNull(result, "result is null");
            requireNonNull(input, "input is null");
            requireNonNull(repositoryRoot, "repositoryRoot is null");
            requireNonNull(fence, "fence is null");
            requireNonNull(run, "run is null");
        }
    }

    /** Exact CI aggregate and immutable diff behind two zero-choice tools. */
    public final class TaskToolContext
    {
        private final AgentRun run;
        private final Path repositoryRoot;
        private final String taskId;
        private final String goalText;
        private final String baseHead;
        private final String reviewedHead;
        private final String summary;

        private TaskToolContext(
                AgentRun run,
                Path repositoryRoot,
                String taskId,
                String goalText,
                String baseHead,
                String reviewedHead,
                String summary)
        {
            this.run = run;
            this.repositoryRoot = repositoryRoot;
            this.taskId = taskId;
            this.goalText = goalText;
            this.baseHead = baseHead;
            this.reviewedHead = reviewedHead;
            this.summary = summary;
        }

        public String readCiFixContext()
        {
            assertTaskToolContext(this);
            return "taskGoal=" + goalText + "\n" + summary;
        }

        public byte[] readCandidateDiff()
        {
            assertTaskToolContext(this);
            return new ImmutableGitObjectReader(
                    repositoryRoot, baseHead, reviewedHead).readDiff();
        }
    }

    public TaskToolContext taskToolContext(TaskInspectionBinding binding)
    {
        requireNonNull(binding, "binding is null");
        AgentResult result = binding.projection().fixerResult();
        return taskToolContext(
                binding.run(),
                binding.repositoryRoot(),
                binding.projection().output().baseSha(),
                binding.projection().output().headSha(),
                "fixerOutcome=" + result.terminalOutcome()
                        + "\nfixerError="
                        + Objects.toString(result.errorRef(), "")
                        + "\nfixerSummary="
                        + bounded(result.finalContent()));
    }

    public TaskToolContext taskToolContext(ReviewerResultBinding binding)
    {
        requireNonNull(binding, "binding is null");
        return taskToolContext(
                binding.run(),
                binding.repositoryRoot(),
                binding.request().baseHeadSha(),
                binding.request().reviewedHeadSha(),
                "reviewOutcome=" + binding.result().terminalOutcome()
                        + "\nreviewError="
                        + Objects.toString(binding.result().errorRef(), "")
                        + "\nreviewSummary="
                        + bounded(binding.result().finalContent()));
    }

    private TaskToolContext taskToolContext(
            AgentRun run,
            Path repositoryRoot,
            String baseHead,
            String reviewedHead,
            String summary)
    {
        Operation operation = runtime.operation(run.operationId())
                .orElseThrow();
        Task task = runtime.task(operation.taskId()).orElseThrow();
        TaskToolContext context = new TaskToolContext(
                run, repositoryRoot, task.taskId(), task.goalText(),
                baseHead, reviewedHead, summary);
        assertTaskToolContext(context);
        return context;
    }

    private void assertTaskToolContext(TaskToolContext context)
    {
        AgentRun current = runtime.run(context.run.runId()).orElseThrow();
        Operation operation = runtime.operation(current.operationId())
                .orElseThrow();
        Task task = runtime.task(context.taskId).orElseThrow();
        if (!current.runId().equals(context.run.runId())
                || !current.operationId().equals(context.run.operationId())
                || !current.sessionId().equals(context.run.sessionId())
                || !current.headSha().equals(context.run.headSha())
                || !current.inputRef().equals(context.run.inputRef())
                || !operation.taskId().equals(context.taskId)
                || !task.goalText().equals(context.goalText)
                || current.role() != AgentRole.TASK_AGENT
                || (current.state() != RunState.QUEUED
                    && current.state() != RunState.RUNNING)) {
            throw new IllegalStateException(
                    "Task tool context changed after binding");
        }
    }

    private static String bounded(String content)
    {
        if (content == null) {
            return "";
        }
        return content.length() <= 16_384
                ? content : content.substring(0, 16_384);
    }

    /** Cross-validates the CI aggregate, then consumes inspected admission. */
    public TaskInspectionBinding beginTaskInspection(
            Claim claim,
            Path programOwnedRepositoryRoot,
            Duration leaseTtl)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireNonNull(leaseTtl, "leaseTtl is null");
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown Task inspection operation"));
        if (operation.kind() != OperationKind.RUN_TASK_TURN
                || !operation.ownerKind().equals("CI_ATTEMPT")) {
            throw new IllegalArgumentException(
                    "claim is not a CI-fix Task inspection");
        }
        PendingWork input = runtime.pendingWork(operation.taskId()).stream()
                .filter(item -> Objects.equals(
                        item.selectedByOperationId(), operation.operationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Task inspection has no selected input"));
        if (input.kind() != PendingKind.CI_FIX_READY
                || input.intendedGateKind() != GateIntent.CI_UPDATE
                || !input.externalKey().equals(operation.ownerId())) {
            throw new IllegalStateException(
                    "Task inspection input is not exact CI_FIX_READY");
        }
        CiFixReviewProjection projection = requireProjection(input);
        Path repositoryRoot = canonicalRepositoryRoot(
                programOwnedRepositoryRoot);
        PreparedTaskWriterAdmission prepared =
                runtime.prepareTaskWriterAdmission(
                        claim,
                        repositoryRoot,
                        input.pendingId(),
                        projection.output().changeSetRevisionId(),
                        projection.inputRemoteHead());
        TaskWriterStart start = runtime.startInspectedTaskWriter(
                claim,
                prepared,
                leaseTtl,
                TASK_PROMPT,
                TASK_CAPABILITIES);
        if (!start.run().headSha().equals(projection.output().headSha())
                || !start.run().inputRemoteHeadSha().equals(
                        projection.inputRemoteHead())) {
            throw new IllegalStateException(
                    "Task inspection run changed candidate subject");
        }
        return new TaskInspectionBinding(
                projection,
                input,
                repositoryRoot,
                start.fence(),
                start.run());
    }

    /** Launches only with the review-aware stopped parent finalizer. */
    public InProcessWriterAgentSupervisor.ExecutionHandle launchTaskInspection(
            InProcessWriterAgentSupervisor supervisor,
            TaskInspectionBinding binding,
            Claim claim,
            Function<TaskInspectionToolCapability,
                    InProcessWriterAgentSupervisor.AgentCompletion> body)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        requireNonNull(body, "body is null");
        return supervisor.launch(
                binding.run().runId(),
                claim,
                binding.fence(),
                taskFinalizerKey(binding.run().runId()),
                this::finishTaskTurn,
                writer -> body.apply(
                        new TaskInspectionToolCapability(
                                writer,
                                binding,
                                runtime,
                                localChecks,
                                userGates)));
    }

    public AgentResult awaitTaskInspection(
            InProcessWriterAgentSupervisor supervisor,
            TaskInspectionBinding binding,
            InProcessWriterAgentSupervisor.ExecutionHandle handle,
            Duration timeout)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        return supervisor.awaitAndFinalize(
                handle, timeout, taskFinalizerKey(binding.run().runId()));
    }

    public ReviewerStart beginReviewer(String requestId, Claim claim)
    {
        return runtime.startReviewerAgent(
                requestId,
                claim,
                REVIEWER_PROMPT,
                REVIEWER_CAPABILITIES);
    }

    public ExecutionHandle launchReviewer(
            InProcessReviewerAgentSupervisor supervisor,
            ReviewerStart start,
            Claim claim,
            Function<ReviewerToolCapability, AgentCompletion> body)
    {
        requireNonNull(supervisor, "supervisor is null");
        return supervisor.launch(start, claim, body);
    }

    public AgentResult awaitReviewer(
            InProcessReviewerAgentSupervisor supervisor,
            ExecutionHandle handle,
            Duration timeout)
    {
        requireNonNull(supervisor, "supervisor is null");
        return supervisor.awaitAndFinish(handle, timeout);
    }

    /** Admits the exact opaque reviewer result back into the same Task seat. */
    public ReviewerResultBinding beginReviewerResultContinuation(
            Claim claim, Duration leaseTtl)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(leaseTtl, "leaseTtl is null");
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown reviewer-result operation"));
        if (operation.kind() != OperationKind.RUN_TASK_TURN
                || !operation.ownerKind().equals("AGENT_RUN")) {
            throw new IllegalArgumentException(
                    "claim is not a reviewer-result Task continuation");
        }
        PendingWork input = runtime.pendingWork(operation.taskId()).stream()
                .filter(item -> Objects.equals(
                        item.selectedByOperationId(), operation.operationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "reviewer result has no selected input"));
        AgentRun reviewerRun = runtime.run(operation.ownerId())
                .orElseThrow(() -> new IllegalStateException(
                        "reviewer result has no AgentRun"));
        AgentResult result = runtime.resultForRun(reviewerRun.runId())
                .orElseThrow(() -> new IllegalStateException(
                        "reviewer result is not durable"));
        ReviewerRequest request = runtime.reviewerRequestForReviewerRun(
                reviewerRun.runId()).orElseThrow(() ->
                        new IllegalStateException(
                                "reviewer result has no immutable request"));
        Task task = runtime.task(operation.taskId()).orElseThrow();
        PullRequestSubject pr = runtime.pullRequest(task.prId()).orElseThrow();
        if (input.kind() != PendingKind.AGENT_RESULT_READY
                || input.intendedGateKind() != request.intendedGateKind()
                || !input.externalKey().equals(reviewerRun.runId())
                || !Objects.equals(input.agentResultId(), result.resultId())
                || !input.subjectHead().equals(request.reviewedHeadSha())
                || !input.payloadRef().equals(reviewerResultPayload(
                        request.requestId(), result.resultId()))
                || !operation.ownerId().equals(reviewerRun.runId())
                || !reviewerRun.operationId().equals(
                        request.reviewerOperationId())
                || reviewerRun.role() != AgentRole.ADVERSARIAL_REVIEWER
                || !isTerminalReviewerRun(reviewerRun, result)
                || !request.taskId().equals(task.taskId())
                || !request.changeSetRevisionId().equals(
                        task.currentChangeSetRevisionId())
                || !request.reviewedHeadSha().equals(task.currentHeadSha())
                || !pr.published()
                || !pr.taskId().equals(task.taskId())
                || pr.currentRemoteHead() == null) {
            throw new IllegalStateException(
                    "AGENT_RESULT_READY no longer matches its review subject");
        }
        Path repositoryRoot = canonicalRepositoryRoot(
                Path.of(request.repositoryRoot()));
        if (!repositoryRoot.toString().equals(request.repositoryRoot())) {
            throw new IllegalStateException(
                    "reviewer repository root changed after review");
        }
        PreparedTaskWriterAdmission prepared =
                runtime.prepareTaskWriterAdmission(
                        claim,
                        repositoryRoot,
                        input.pendingId(),
                        request.changeSetRevisionId(),
                        pr.currentRemoteHead());
        TaskWriterStart start = runtime.startInspectedTaskWriter(
                claim,
                prepared,
                leaseTtl,
                TASK_PROMPT,
                TASK_CAPABILITIES);
        AgentRun parent = runtime.run(request.parentRunId()).orElseThrow();
        if (!start.run().sessionId().equals(parent.sessionId())
                || start.run().intendedGateKind()
                        != request.intendedGateKind()) {
            throw new IllegalStateException(
                    "reviewer result did not resume the same Task session");
        }
        return new ReviewerResultBinding(
                request,
                result,
                input,
                repositoryRoot,
                start.fence(),
                start.run());
    }

    public InProcessWriterAgentSupervisor.ExecutionHandle
            launchReviewerResultContinuation(
                    InProcessWriterAgentSupervisor supervisor,
                    ReviewerResultBinding binding,
                    Claim claim,
                    Function<TaskInspectionToolCapability,
                            InProcessWriterAgentSupervisor.AgentCompletion> body)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        requireNonNull(body, "body is null");
        return supervisor.launch(
                binding.run().runId(),
                claim,
                binding.fence(),
                taskFinalizerKey(binding.run().runId()),
                this::finishTaskTurn,
                writer -> body.apply(new TaskInspectionToolCapability(
                        writer,
                        runtime,
                        localChecks,
                        binding.repositoryRoot(),
                        binding.request().taskId(),
                        binding.run().runId(),
                        binding.run().inputChangeSetRevisionId(),
                        binding.run().intendedGateKind(),
                        new CiFixReviewOrigin(
                                binding.request().originCiFixPendingId(),
                                CiFixSourceKind.valueOf(binding.request()
                                        .originCiFixSourceKind()),
                                binding.request().originCiFixSourceId()),
                        userGates,
                        binding.request(),
                        binding.result())));
    }

    public AgentResult awaitReviewerResultContinuation(
            InProcessWriterAgentSupervisor supervisor,
            ReviewerResultBinding binding,
            InProcessWriterAgentSupervisor.ExecutionHandle handle,
            Duration timeout)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        return supervisor.awaitAndFinalize(
                handle, timeout, taskFinalizerKey(binding.run().runId()));
    }

    /** Replays only the exact durable STOPPED Task completion. */
    public AgentResult recoverExpiredStoppedTaskTurn(
            String operationId, long generation, Duration ttl)
    {
        FlowRuntime.StoppedWriterRecovery recovery =
                runtime.reviveExpiredStoppedWriter(
                        operationId, generation, ttl);
        Operation operation = runtime.operation(operationId).orElseThrow();
        if (operation.kind() != OperationKind.RUN_TASK_TURN
                || (!operation.ownerKind().equals("CI_ATTEMPT")
                    && !operation.ownerKind().equals("AGENT_RUN"))) {
            throw new IllegalArgumentException(
                    "stopped Task turn is not a CI continuation");
        }
        return finishTaskTurn(
                recovery.run().runId(), recovery.claim(), recovery.fence(),
                new InProcessWriterAgentSupervisor.AgentCompletion(
                        recovery.completion().terminalOutcome(),
                        recovery.completion().finalContent(),
                        recovery.completion().errorRef()));
    }

    /** Replays only the exact durable STOPPED read-only reviewer completion. */
    public AgentResult recoverExpiredStoppedReviewer(
            String operationId, long generation, Duration ttl)
    {
        FlowRuntime.StoppedReviewerRecovery recovery =
                runtime.reviveExpiredStoppedReviewer(
                        operationId, generation, ttl);
        return runtime.finishReviewerAgentRun(
                recovery.run().runId(), recovery.claim(),
                recovery.completion().terminalOutcome(),
                recovery.completion().finalContent(),
                recovery.completion().errorRef());
    }

    private AgentResult finishTaskTurn(
            String runId,
            Claim claim,
            WriterFence fence,
            InProcessWriterAgentSupervisor.AgentCompletion completion)
    {
        if (runtime.readyForReviewRequestForRun(runId).isPresent()) {
            UserGates.PreparedReadyFinalization prepared =
                    userGates.prepareFinalization(runId, claim, fence);
            return userGates.finalizeReady(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    prepared);
        }
        return runtime.finishTaskAgentReviewTurn(
                runId,
                claim,
                fence,
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef());
    }

    private static boolean isTerminalReviewerRun(
            AgentRun run, AgentResult result)
    {
        return switch (result.terminalOutcome()) {
            case COMPLETED -> run.state() == RunState.COMPLETED;
            case FAILED -> run.state() == RunState.FAILED;
            case CANCELED -> run.state() == RunState.CANCELED;
        };
    }

    private static String reviewerResultPayload(
            String requestId, String resultId)
    {
        return "reviewer-request:" + requestId + ":result:" + resultId;
    }

    private CiFixReviewProjection requireProjection(PendingWork input)
    {
        CiRepairAttempt attempt = autofix.repairAttempt(
                input.externalKey()).orElse(null);
        if (attempt != null) {
            return repairProjection(input, attempt);
        }
        CiCleanupCompletion cleanup = autofix.cleanupCompletion(
                input.externalKey()).orElseThrow(() ->
                        new IllegalStateException(
                                "CI_FIX_READY has no CI owner aggregate"));
        return cleanupProjection(input, cleanup);
    }

    private CiFixReviewProjection repairProjection(
            PendingWork input, CiRepairAttempt attempt)
    {
        if (attempt.state() != AttemptState.FIX_PREPARED
                && attempt.state() != AttemptState.NO_HEAD_CHANGE) {
            throw new IllegalStateException(
                    "CI repair attempt has no clean output");
        }
        CiRound round = requireReviewableRound(attempt.roundId());
        AgentResult result = runtime.resultForRun(attempt.agentRunId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI repair has no durable AgentResult"));
        ChangeSetRevision output = requireOutput(
                attempt.outputChangeSetRevisionId(),
                attempt.outputLocalHead(),
                attempt.operationId(),
                attempt.agentRunId(),
                round.taskId());
        requireExactRuntimeSubject(
                input,
                round,
                attempt.inputRemoteHead(),
                attempt.resultRef(),
                result,
                output);
        return new CiFixReviewProjection(
                FixSource.REPAIR_ATTEMPT,
                attempt.attemptId(),
                round.roundId(),
                round.taskId(),
                round.prId(),
                attempt.inputRemoteHead(),
                result,
                output,
                GateIntent.CI_UPDATE);
    }

    private CiFixReviewProjection cleanupProjection(
            PendingWork input, CiCleanupCompletion completion)
    {
        if (completion.outcome() != CleanupOutcome.FIX_PREPARED
                && completion.outcome() != CleanupOutcome.NO_HEAD_CHANGE) {
            throw new IllegalStateException(
                    "CI cleanup has no clean output");
        }
        CiCleanupSeal seal = autofix.cleanupSeal(completion.cleanupId())
                .orElseThrow();
        CiRepairAttempt predecessor = autofix.repairAttempt(
                seal.repairAttemptId()).orElseThrow();
        CiRound round = requireReviewableRound(predecessor.roundId());
        AgentResult result = runtime.resultForRun(completion.runId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI cleanup has no durable AgentResult"));
        ChangeSetRevision output = requireOutput(
                completion.outputChangeSetRevisionId(),
                completion.outputHead(),
                seal.successorOperationId(),
                completion.runId(),
                round.taskId());
        requireExactRuntimeSubject(
                input,
                round,
                predecessor.inputRemoteHead(),
                completion.resultRef(),
                result,
                output);
        return new CiFixReviewProjection(
                FixSource.CLEANUP,
                completion.cleanupId(),
                round.roundId(),
                round.taskId(),
                round.prId(),
                predecessor.inputRemoteHead(),
                result,
                output,
                GateIntent.CI_UPDATE);
    }

    private static Path canonicalRepositoryRoot(Path repositoryRoot)
    {
        try {
            return repositoryRoot.toRealPath();
        }
        catch (IOException e) {
            throw new IllegalArgumentException(
                    "program-owned repository root is unavailable", e);
        }
    }

    private CiRound requireReviewableRound(String roundId)
    {
        CiRound round = autofix.roundById(roundId).orElseThrow();
        if (round.state() != RoundState.FIX_PREPARED
                && round.state() != RoundState.SUPERSEDED) {
            throw new IllegalStateException(
                    "CI fix round is not mechanically reviewable");
        }
        return round;
    }

    private ChangeSetRevision requireOutput(
            String revisionId,
            String head,
            String operationId,
            String runId,
            String taskId)
    {
        ChangeSetRevision output = runtime.currentChangeSet(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "CI fix has no current ChangeSetRevision"));
        if (!output.changeSetRevisionId().equals(revisionId)
                || !output.headSha().equals(head)
                || output.source() != ChangeSetSource.CI_FIXER
                || !output.sourceOperationId().equals(operationId)
                || !Objects.equals(output.sourceRunId(), runId)) {
            throw new IllegalStateException(
                    "CI fix output provenance changed before review");
        }
        return output;
    }

    private void requireExactRuntimeSubject(
            PendingWork input,
            CiRound round,
            String remoteHead,
            String resultRef,
            AgentResult result,
            ChangeSetRevision output)
    {
        Task task = runtime.task(round.taskId()).orElseThrow();
        PullRequestSubject pr = runtime.pullRequest(round.prId()).orElseThrow();
        if (task.status() != TaskStatus.ACTIVE
                || !task.prId().equals(pr.prId())
                || !pr.taskId().equals(task.taskId())
                || !pr.currentRemoteHead().equals(remoteHead)
                || !round.remoteHead().equals(remoteHead)
                || !result.resultId().equals(resultRef)
                || !result.resultId().equals(input.agentResultId())
                || !input.taskId().equals(task.taskId())
                || !Objects.equals(input.prId(), pr.prId())
                || !input.subjectHead().equals(output.headSha())
                || !task.currentHeadSha().equals(output.headSha())
                || !task.currentChangeSetRevisionId().equals(
                        output.changeSetRevisionId())) {
            throw new IllegalStateException(
                    "CI_FIX_READY aggregate no longer matches runtime state");
        }
    }

    private static String taskFinalizerKey(String runId)
    {
        return "TASK_REVIEW:" + runId;
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
