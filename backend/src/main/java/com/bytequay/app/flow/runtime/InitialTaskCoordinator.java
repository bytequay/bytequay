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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.gate.UserGateRecords.ReadyForReviewAcceptance;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.InitialPublishRecords;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedInitialTaskAdmission;
import com.bytequay.app.flow.runtime.FlowRuntime.ReviewerStart;
import com.bytequay.app.flow.runtime.FlowRuntime.TaskWriterStart;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor.AgentCompletion;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor.ExecutionHandle;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor.ReviewerToolCapability;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.WriterToolCapability;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Exact owner for ordinary unpublished INITIAL Task and reviewer turns. */
public final class InitialTaskCoordinator
{
    private static final String TASK_PROMPT = "task-initial-prompt:v1";
    private static final String TASK_CAPABILITIES =
            "task-initial-capabilities:v1";
    private static final String TASK_REVIEW_PROMPT =
            "task-initial-review-prompt:v1";
    private static final String TASK_REVIEW_CAPABILITIES =
            "task-initial-review-capabilities:v1";
    private static final String REVIEWER_PROMPT =
            "adversarial-reviewer-prompt:v1";
    private static final String REVIEWER_CAPABILITIES =
            "immutable-git-object-reader:v1";

    public record TaskBinding(
            PendingWork input,
            Path repositoryRoot,
            WriterFence fence,
            AgentRun run,
            String goalText,
            ReviewerRequest completedReview,
            AgentResult completedReviewResult)
    {
        public TaskBinding
        {
            requireNonNull(input, "input is null");
            requireNonNull(repositoryRoot, "repositoryRoot is null");
            requireNonNull(fence, "fence is null");
            requireNonNull(run, "run is null");
            requireText(goalText, "goalText");
            if ((completedReview == null) != (completedReviewResult == null)) {
                throw new IllegalArgumentException(
                        "review request/result must be all present or absent");
            }
        }

        public boolean reviewContinuation()
        {
            return completedReview != null;
        }
    }

    /** Program-derived tools over one exact INITIAL Task run. */
    public final class InitialToolCapability
    {
        private final WriterToolCapability writer;
        private final TaskBinding binding;
        private final Supplier<InitialPublishRecords.RepositoryObservation>
                observation;
        private String changeSetRevisionId;

        private InitialToolCapability(
                WriterToolCapability writer,
                TaskBinding binding,
                Supplier<InitialPublishRecords.RepositoryObservation>
                        observation)
        {
            this.writer = requireNonNull(writer, "writer is null");
            this.binding = requireNonNull(binding, "binding is null");
            this.observation = requireNonNull(observation,
                    "observation is null");
            this.changeSetRevisionId = runtime.currentInitialTaskChangeSet(
                    binding.run().runId())
                    .map(ChangeSetRevision::changeSetRevisionId)
                    .orElse(null);
        }

        public <T> T callTool(Supplier<T> action)
        {
            return writer.callTool(action);
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

        public String readContext()
        {
            return writer.callTool(() -> {
                Task task = requireCurrentTask(binding);
                String review = binding.completedReviewResult() == null
                        ? "" : "\nreviewOutcome="
                                + binding.completedReviewResult()
                                        .terminalOutcome()
                                + "\nreviewError=" + bounded(
                                        binding.completedReviewResult()
                                                .errorRef())
                                + "\nreviewSummary=" + bounded(
                                        binding.completedReviewResult()
                                                .finalContent());
                return "taskGoal=" + task.goalText()
                        + "\ninitialBase=" + task.currentBaseSha()
                        + review;
            });
        }

        public byte[] readCandidateDiff()
        {
            if (!binding.reviewContinuation()) {
                throw new IllegalStateException(
                        "initial first turn has no reviewed candidate");
            }
            return writer.callTool(() -> new ImmutableGitObjectReader(
                    binding.repositoryRoot(),
                    binding.completedReview().baseHeadSha(),
                    binding.completedReview().reviewedHeadSha()).readDiff());
        }

        public void adoptCommittedHead(String committedHead)
        {
            requireText(committedHead, "committedHead");
            ChangeSetRevision exactCurrent = runtime
                    .currentInitialTaskChangeSet(binding.run().runId())
                    .orElse(null);
            if (exactCurrent != null
                    && exactCurrent.headSha().equals(committedHead)) {
                changeSetRevisionId = exactCurrent.changeSetRevisionId();
                return;
            }
            ChangeSetRevision adopted = changeSetRevisionId == null
                    ? writer.adoptInitialChangeSet(binding.repositoryRoot())
                    : writer.adoptChangeSet(
                            binding.repositoryRoot(), changeSetRevisionId);
            changeSetRevisionId = adopted.changeSetRevisionId();
        }

        /** Saves draft, runs fixed checks, then seals exact reviewer request. */
        public ReviewerRequest requestReview(String title, String body)
        {
            ReviewerRequest replay = runtime.reviewerRequestForParentRun(
                    binding.run().runId()).orElse(null);
            if (replay != null) {
                return writer.replayInitialAdversarialReviewer(
                        binding.repositoryRoot(),
                        changeSetRevisionId,
                        replay.localCheckPolicyRevisionId(),
                        replay.checkRunRefs());
            }
            ChangeSetRevision current = runtime.currentChangeSet(
                    binding.input().taskId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "INITIAL review has no change set"));
            if (!current.changeSetRevisionId().equals(
                    changeSetRevisionId)) {
                throw new IllegalStateException(
                        "INITIAL review change set is stale");
            }
            Task task = requireCurrentTask(binding);
            String targetBaseRef = provisioning.targetBaseRef(task.taskId());
            PullRequestSubject pr;
            if (task.prId() == null) {
                pr = writer.callTool(() -> runtime.materializePullRequest(
                        task.taskId(), current.changeSetRevisionId(),
                        task.baseRef(), targetBaseRef, targetBaseRef));
            }
            else {
                pr = runtime.pullRequest(task.prId()).orElseThrow();
                if (!pr.taskId().equals(task.taskId())
                        || !pr.repositoryId().equals(task.repositoryId())
                        || !pr.baseRef().equals(task.baseRef())
                        || !pr.targetBaseRef().equals(targetBaseRef)
                        || !pr.scopeKey().equals(targetBaseRef)
                        || !pr.branchName().equals(task.branchName())
                        || pr.published()
                        || pr.currentRemoteHead() != null) {
                    throw new IllegalStateException(
                            "INITIAL local PR changed identity");
                }
            }
            writer.savePrDraft(
                    pr.prId(), current.changeSetRevisionId(),
                    current.headSha(), title, body);
            writer.runChecks(localChecks, binding.repositoryRoot(), null);
            LocalChecks.ReviewerEvidence evidence =
                    localChecks.reviewerEvidence(
                            task.taskId(), current.changeSetRevisionId(),
                            GateIntent.INITIAL_PUBLISH);
            return writer.spawnInitialAdversarialReviewer(
                    binding.repositoryRoot(), current.changeSetRevisionId(),
                    evidence);
        }

        public ReadyForReviewAcceptance readyForInitialPublish()
        {
            if (!binding.reviewContinuation()) {
                throw new IllegalStateException(
                        "initial ready requires an exact reviewer result");
            }
            if (binding.completedReviewResult().terminalOutcome()
                    != TerminalOutcome.COMPLETED) {
                throw new IllegalStateException(
                        "initial ready requires a completed reviewer result");
            }
            return writer.readyForInitialPublish(
                    userGates, binding.repositoryRoot(), observation);
        }

        @Override
        public String toString()
        {
            return "InitialToolCapability[opaque]";
        }
    }

    private final FlowRuntime runtime;
    private final TaskProvisioning provisioning;
    private final LocalChecks localChecks;
    private final UserGates userGates;

    public InitialTaskCoordinator(
            FlowRuntime runtime,
            TaskProvisioning provisioning,
            LocalChecks localChecks,
            UserGates userGates)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.provisioning = requireNonNull(
                provisioning, "provisioning is null");
        this.localChecks = requireNonNull(localChecks, "localChecks is null");
        this.userGates = requireNonNull(userGates, "userGates is null");
    }

    public TaskBinding beginTask(Claim claim, Duration leaseTtl)
    {
        requireNonNull(claim, "claim is null");
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown INITIAL Task operation"));
        if (operation.kind() != OperationKind.RUN_TASK_TURN
                || (!operation.ownerKind().equals("TASK")
                    && !operation.ownerKind().equals("AGENT_RUN"))) {
            throw new IllegalArgumentException(
                    "claim is not an INITIAL Task turn");
        }
        PendingWork input = selectedInput(operation);
        boolean first = input.kind() == PendingKind.INITIAL_TASK
                && operation.ownerKind().equals("TASK");
        boolean continuation = input.kind() == PendingKind.AGENT_RESULT_READY
                && operation.ownerKind().equals("AGENT_RUN")
                && input.intendedGateKind() == GateIntent.INITIAL_PUBLISH;
        if (!first && !continuation) {
            throw new IllegalArgumentException(
                    "Task turn is not owned by INITIAL flow");
        }
        Task task = runtime.task(operation.taskId()).orElseThrow();
        Path repositoryRoot = Path.of(task.repositoryRoot());
        PreparedInitialTaskAdmission prepared =
                runtime.prepareInitialTaskAdmission(claim, repositoryRoot);
        TaskWriterStart start = runtime.startInspectedInitialTaskWriter(
                claim, prepared, leaseTtl,
                continuation ? TASK_REVIEW_PROMPT : TASK_PROMPT,
                continuation ? TASK_REVIEW_CAPABILITIES
                        : TASK_CAPABILITIES);
        ReviewerRequest review = continuation
                ? runtime.reviewerRequestForReviewerRun(input.externalKey())
                        .orElseThrow()
                : null;
        AgentResult result = continuation
                ? runtime.resultForRun(input.externalKey()).orElseThrow()
                : null;
        return new TaskBinding(
                input, repositoryRoot, start.fence(), start.run(),
                task.goalText(), review, result);
    }

    public InProcessWriterAgentSupervisor.ExecutionHandle launchTask(
            InProcessWriterAgentSupervisor supervisor,
            TaskBinding binding,
            Claim claim,
            Supplier<InitialPublishRecords.RepositoryObservation> observation,
            Function<InitialToolCapability,
                    InProcessWriterAgentSupervisor.AgentCompletion> body)
    {
        return supervisor.launch(
                binding.run().runId(), claim, binding.fence(),
                finalizerKey(binding.run().runId()), this::finishTask,
                writer -> body.apply(new InitialToolCapability(
                        writer, binding, observation)));
    }

    public AgentResult awaitTask(
            InProcessWriterAgentSupervisor supervisor,
            TaskBinding binding,
            InProcessWriterAgentSupervisor.ExecutionHandle handle,
            Duration timeout)
    {
        return supervisor.awaitAndFinalize(
                handle, timeout, finalizerKey(binding.run().runId()));
    }

    public ReviewerStart beginReviewer(String requestId, Claim claim)
    {
        ReviewerRequest request = runtime.reviewerRequest(requestId)
                .orElseThrow();
        if (request.intendedGateKind() != GateIntent.INITIAL_PUBLISH) {
            throw new IllegalArgumentException(
                    "reviewer is not owned by INITIAL flow");
        }
        return runtime.startReviewerAgent(
                requestId, claim, REVIEWER_PROMPT, REVIEWER_CAPABILITIES);
    }

    public ExecutionHandle launchReviewer(
            InProcessReviewerAgentSupervisor supervisor,
            ReviewerStart start,
            Claim claim,
            Function<ReviewerToolCapability, AgentCompletion> body)
    {
        return supervisor.launch(start, claim, body);
    }

    public AgentResult awaitReviewer(
            InProcessReviewerAgentSupervisor supervisor,
            ExecutionHandle handle,
            Duration timeout)
    {
        return supervisor.awaitAndFinish(handle, timeout);
    }

    public AgentResult recoverExpiredStoppedTask(
            String operationId, long generation, Duration ttl)
    {
        FlowRuntime.StoppedWriterRecovery recovery =
                runtime.reviveExpiredStoppedWriter(
                        operationId, generation, ttl);
        Operation operation = runtime.operation(operationId).orElseThrow();
        if (operation.kind() != OperationKind.RUN_TASK_TURN
                || (!operation.ownerKind().equals("TASK")
                    && !operation.ownerKind().equals("AGENT_RUN"))) {
            throw new IllegalArgumentException(
                    "stopped writer is not INITIAL-owned");
        }
        return finishTask(
                recovery.run().runId(), recovery.claim(), recovery.fence(),
                new InProcessWriterAgentSupervisor.AgentCompletion(
                        recovery.completion().terminalOutcome(),
                        recovery.completion().finalContent(),
                        recovery.completion().errorRef()));
    }

    public AgentResult recoverExpiredStoppedReviewer(
            String operationId, long generation, Duration ttl)
    {
        FlowRuntime.StoppedReviewerRecovery recovery =
                runtime.reviveExpiredStoppedReviewer(
                        operationId, generation, ttl);
        ReviewerRequest request = runtime.reviewerRequestForReviewerRun(
                recovery.run().runId()).orElseThrow();
        if (request.intendedGateKind()
                != GateIntent.INITIAL_PUBLISH) {
            throw new IllegalArgumentException(
                    "stopped reviewer is not INITIAL-owned");
        }
        return runtime.finishReviewerAgentRun(
                recovery.run().runId(), recovery.claim(),
                recovery.completion().terminalOutcome(),
                recovery.completion().finalContent(),
                recovery.completion().errorRef());
    }

    private AgentResult finishTask(
            String runId,
            Claim claim,
            WriterFence fence,
            InProcessWriterAgentSupervisor.AgentCompletion completion)
    {
        if (runtime.readyForReviewRequestForRun(runId).isPresent()) {
            UserGates.PreparedInitialFinalization prepared =
                    userGates.prepareInitialFinalization(runId, claim, fence);
            return userGates.finalizeInitialReady(
                    runId, claim, fence, completion.terminalOutcome(),
                    completion.finalContent(), completion.errorRef(),
                    prepared);
        }
        return runtime.finishTaskAgentReviewTurn(
                runId, claim, fence, completion.terminalOutcome(),
                completion.finalContent(), completion.errorRef());
    }

    private PendingWork selectedInput(Operation operation)
    {
        return runtime.pendingWork(operation.taskId()).stream()
                .filter(input -> Objects.equals(
                        input.selectedByOperationId(), operation.operationId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "INITIAL operation has no selected input"));
    }

    private Task requireCurrentTask(TaskBinding binding)
    {
        AgentRun run = runtime.run(binding.run().runId()).orElseThrow();
        Operation operation = runtime.operation(run.operationId())
                .orElseThrow();
        Task task = runtime.task(operation.taskId()).orElseThrow();
        if (!run.runId().equals(binding.run().runId())
                || !run.operationId().equals(binding.run().operationId())
                || !run.sessionId().equals(binding.run().sessionId())
                || !run.headSha().equals(binding.run().headSha())
                || !run.promptManifestRef().equals(
                        binding.run().promptManifestRef())
                || !run.capabilitySetRef().equals(
                        binding.run().capabilitySetRef())
                || !run.inputRef().equals(binding.run().inputRef())
                || !Objects.equals(run.inputChangeSetRevisionId(),
                        binding.run().inputChangeSetRevisionId())
                || run.wakeKind() != binding.run().wakeKind()
                || run.intendedGateKind()
                        != binding.run().intendedGateKind()
                || run.state()
                        != FlowRuntimeRecords.RunState.RUNNING
                || !operation.operationId().equals(
                        binding.input().selectedByOperationId())
                || !task.goalText().equals(binding.goalText())) {
            throw new IllegalStateException(
                    "INITIAL Task context changed after binding");
        }
        return task;
    }

    private static String finalizerKey(String runId)
    {
        return "initial-task-finalizer:" + runId;
    }

    private static String bounded(String content)
    {
        if (content == null) {
            return "";
        }
        return content.length() <= 16_384
                ? content : content.substring(0, 16_384);
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
