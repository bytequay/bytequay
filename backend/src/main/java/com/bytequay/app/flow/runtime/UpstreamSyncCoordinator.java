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

import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckConclusion;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.AgentCompletion;
import com.bytequay.app.flow.runtime.InitialTaskCoordinator.InitialToolCapability;
import com.bytequay.app.flow.upstream.RunLinePublisher;
import com.bytequay.app.flow.upstream.UpstreamPicker;
import com.bytequay.app.flow.upstream.UpstreamPicker.PickResult;
import com.bytequay.app.flow.upstream.UpstreamPicker.UnresolvedRepairException;
import com.bytequay.app.flow.upstream.UpstreamSync;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PickState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamPick;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRequest;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.ToolExecutor.ToolCallResult;
import com.bytequay.app.service.agents.TurnResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Owns phase 1 of an upstream cherry-pick range on the flow runtime.
 *
 * <p>The picks are deterministic program work in the Task's own worktree and
 * on its own branch, so a hundred clean commits cost no model turns. Only a
 * conflict is semantic, and only a conflict resumes the Task Agent.
 *
 * <p>This body deliberately ends where every ordinary Task ends — at the exact
 * review request and then the {@code INITIAL_PUBLISH} gate. The program makes
 * that request itself once the range is complete; the repair agent has no tool
 * for it, because asking for review mid-range is not something it should be
 * able to do.
 *
 * <p>The run's park-before-push already is that gate, so nothing about the user's
 * authorization step is invented here; it is reached. Publication through the
 * flow's own effect is also what lets generic CI Autofix adopt the pull
 * request afterwards, because its only entry is a gate-authorized receipt.
 */
@SuppressWarnings("StringConcatToTextBlock")
public final class UpstreamSyncCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(
            UpstreamSyncCoordinator.class);
    /** A backstop on real build runs, not a ration: each attempt is a full
     *  repository check, so the only turn this ends is one that is looping. */
    private static final int MAX_CHECK_ATTEMPTS = 25;
    private static final int MAX_TOOL_RESULT_CHARS = 256 * 1024;
    private static final int MAX_UPSTREAM_DIFF_CHARS = 128 * 1024;
    private static final int MAX_DRAFT_BODY_COMMITS = 50;

    private final FlowRuntime runtime;
    private final UpstreamSync upstreamSync;
    private final NewFlowAgentLaunches launches;
    private final NewFlowAgentBodies bodies;
    private final ObjectMapper mapper;
    private final TaskProvisioning provisioning;
    private final UpstreamSyncPolicyPublisher policies;
    private final RunLinePublisher live;

    public UpstreamSyncCoordinator(
            FlowRuntime runtime,
            UpstreamSync upstreamSync,
            NewFlowAgentLaunches launches,
            NewFlowAgentBodies bodies,
            ObjectMapper mapper,
            TaskProvisioning provisioning,
            UpstreamSyncPolicyPublisher policies,
            RunLinePublisher live)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.upstreamSync = requireNonNull(
                upstreamSync, "upstreamSync is null");
        this.launches = requireNonNull(launches, "launches is null");
        this.bodies = requireNonNull(bodies, "bodies is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.provisioning = requireNonNull(
                provisioning, "provisioning is null");
        this.policies = requireNonNull(policies, "policies is null");
        this.live = requireNonNull(live, "live is null");
    }

    /** True when this Task's branch is one this component builds. */
    public boolean owns(String taskId)
    {
        return upstreamSync.runForTask(taskId).isPresent();
    }

    /** Live read-only reviewer activity belongs in the same run conversation. */
    public Consumer<StreamEvent> reviewerActivity(String taskId)
    {
        UpstreamSyncRun run = upstreamSync.runForTask(taskId).orElseThrow(
                () -> new IllegalStateException(
                        "Task is not owned by upstream synchronization"));
        return agentActivity(run.runId(), false);
    }

    /** Marks the handoff so the next streamed line cannot look like Task work. */
    public void reviewerState(
            String taskId, String agentRunId, boolean running)
    {
        UpstreamSyncRun run = upstreamSync.runForTask(taskId).orElseThrow(
                () -> new IllegalStateException(
                        "Task is not owned by upstream synchronization"));
        ObjectNode line = mapper.createObjectNode();
        line.put("type", "bytequay_agent");
        line.put("role", "reviewer");
        line.put("run_id", agentRunId);
        line.put("running", running);
        publish(run.runId(), line.toString());
    }

    /** Completes teardown after a boundary close's writer has finalized. */
    void finishCanceledTask(String taskId)
    {
        upstreamSync.runForTask(taskId)
                .filter(run -> run.state() == RunState.CANCELED)
                .ifPresent(run -> UpstreamSyncTeardown.close(
                        runtime,
                        provisioning,
                        upstreamSync,
                        runtime.task(taskId).orElseThrow(),
                        run.runId(),
                        "UPSTREAM_SYNC_CLOSED"));
    }

    /**
     * Runs one INITIAL Task turn for an upstream sync Task.
     *
     * <p>The first turn picks the confirmed range and requests review; the
     * continuation turn resumes the ordinary review-result program. Both are
     * the ordinary INITIAL contract — this component only replaces range
     * construction and refreshes its mechanical proof after a correction.
     */
    public AgentCompletion runTurn(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
            InitialToolCapability capability,
            String taskId,
            boolean reviewContinuation)
    {
        requireNonNull(binding, "binding is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(capability, "capability is null");
        if (!reviewContinuation) {
            launches.requireSealedAs(
                    binding, NewFlowAgentLaunches.Program.UPSTREAM_PICK_REPAIR);
        }
        UpstreamSyncRun run = upstreamSync.runForTask(taskId).orElseThrow(
                () -> new IllegalStateException(
                        "Task is not owned by upstream synchronization"));
        try {
            if (reviewContinuation) {
                if (upstreamSync.closeRequested(run.runId())) {
                    return closeReviewContinuationHere(run.runId());
                }
                AgentCompletion completion = bodies.initialTask(
                        binding, worktree, capability, true,
                        agentActivity(run.runId(), false));
                if (upstreamSync.closeRequested(run.runId())) {
                    return closeReviewContinuationHere(run.runId());
                }
                if (completion.terminalOutcome() == TerminalOutcome.COMPLETED) {
                    if (runtime.readyForReviewRequestForRun(binding.runId())
                            .isPresent()) {
                        upstreamSync.advanceState(
                                run.runId(), RunState.WAITING_INITIAL_PUBLISH);
                    }
                    else {
                        UpstreamSyncRequest request = upstreamSync
                                .request(run.requestId()).orElseThrow();
                        UpstreamPicker picker = new UpstreamPicker(worktree);
                        String verification = verifyHistory(
                                picker, run.runId(), taskId,
                                request.targetRef(),
                                request.selectedUpstreamShas());
                        upstreamSync.recordVerification(
                                run.runId(), RunState.FINAL_REVIEW,
                                picker.head(), verification);
                        Task task = runtime.task(taskId).orElseThrow();
                        String targetBaseRef = provisioning.targetBaseRef(taskId);
                        policies.publish(
                                taskId, task.repositoryId(), targetBaseRef,
                                targetBaseRef.substring(
                                        "refs/heads/".length()),
                                worktree, picker.head());
                    }
                }
                return completion;
            }
            return pickRange(binding, worktree, capability, taskId, run);
        }
        catch (RuntimeException failure) {
            if (isCancellation(failure, Thread.currentThread().isInterrupted())) {
                log.info("upstream synchronization turn was canceled");
                return new AgentCompletion(
                        TerminalOutcome.CANCELED, null, "CANCELED");
            }
            // The supervisor's own catch reports one opaque code for every
            // body, which cannot tell a refused pick from a stale fence.
            log.warn("upstream synchronization turn failed", failure);
            return new AgentCompletion(
                    TerminalOutcome.FAILED, null,
                    "UPSTREAM_SYNC_FAILED:" + describe(failure));
        }
    }

    static boolean isCancellation(Throwable failure, boolean interrupted)
    {
        if (interrupted) {
            return true;
        }
        for (Throwable cursor = failure;
                cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof InterruptedException
                    || cursor instanceof InspectionFailure inspection
                    && inspection.code() == FailureCode.INTERRUPTED) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable failure)
    {
        StringBuilder described = new StringBuilder();
        for (Throwable cursor = failure;
                cursor != null && described.length() < 480;
                cursor = cursor.getCause()) {
            if (!described.isEmpty()) {
                described.append(" <- ");
            }
            described.append(cursor.getClass().getSimpleName())
                    .append(':').append(cursor.getMessage());
        }
        return described.length() <= 512
                ? described.toString() : described.substring(0, 512);
    }

    private AgentCompletion pickRange(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
            InitialToolCapability capability,
            String taskId,
            UpstreamSyncRun startRun)
    {
        UpstreamSyncRequest request = upstreamSync
                .request(startRun.requestId()).orElseThrow();
        UpstreamPicker picker = new UpstreamPicker(worktree);
        Task task = runtime.task(taskId).orElseThrow();
        String expectedHead = startRun.currentHead() == null
                ? request.targetRef() : startRun.currentHead();
        if (!request.targetRef().equals(task.currentBaseSha())
                || !expectedHead.equals(picker.head())) {
            return park(capability, picker, startRun.runId(),
                    "TARGET_BASE_MISMATCH");
        }
        picker.requireObjects(request.selectedUpstreamShas());
        String targetBaseRef = provisioning.targetBaseRef(taskId);
        if (!targetBaseRef.startsWith("refs/heads/")
                || targetBaseRef.length() == "refs/heads/".length()) {
            throw new IllegalStateException(
                    "Task provisioning did not freeze a branch target");
        }
        upstreamSync.advanceState(startRun.runId(), RunState.PICKING);
        List<String> selected = request.selectedUpstreamShas();
        boolean agentFinalized = false;
        for (int ordinal = 0; ordinal < selected.size(); ordinal++) {
            // The user's stop, honoured between commits. Mid-pick there is a
            // sequencer in flight and no recorded head to wait at; here the
            // tree is exactly what the last pick record describes, which is
            // the same boundary a conflict park uses.
            if (upstreamSync.closeRequested(startRun.runId())) {
                return closeHere(capability, picker, startRun.runId());
            }
            if (upstreamSync.pauseRequested(startRun.runId())) {
                return park(capability, picker, startRun.runId(), "USER_PAUSED");
            }
            PickStep step = applyOne(
                    capability, picker, taskId, startRun.runId(), ordinal,
                    selected.get(ordinal));
            if (step.parkReason() != null) {
                return park(capability, picker, startRun.runId(),
                        step.parkReason());
            }
            if (step.conflict() != null) {
                String parkReason = repairRange(
                        binding, worktree, capability, picker, taskId,
                        startRun.runId(), request, task, targetBaseRef,
                        selected, step.conflict());
                if (parkReason != null) {
                    return park(capability, picker, startRun.runId(),
                            parkReason);
                }
                agentFinalized = true;
                break;
            }
        }
        if (!agentFinalized) {
            publishPolicies(task, targetBaseRef, picker);
            String blocker = finalReview(
                    binding, worktree, capability, picker, task, request,
                    startRun.runId(), selected);
            if (blocker != null) {
                return park(capability, picker, startRun.runId(), blocker);
            }
        }
        return new AgentCompletion(
                TerminalOutcome.COMPLETED,
                "upstream range constructed and submitted for review", null);
    }

    private record PickStep(String parkReason, UpstreamPick conflict)
    {
        private static PickStep advanced()
        {
            return new PickStep(null, null);
        }
    }

    /** Applies one deterministic commit and stops at semantic work. */
    private PickStep applyOne(
            InitialToolCapability capability,
            UpstreamPicker picker,
            String taskId,
            String runId,
            int ordinal,
            String upstreamSha)
    {
        // A recorded pick is already in history. Re-applying it would either
        // record a second commit or collide with its own row, so a resumed
        // range re-enters at the repair rather than at the pick.
        Optional<UpstreamPick> recorded = upstreamSync.pick(runId, ordinal);
        if (recorded.isPresent()) {
            UpstreamPick pick = recorded.orElseThrow();
            if (!pick.upstreamSha().equals(upstreamSha)) {
                throw new IllegalStateException(
                        "durable pick does not match the confirmed range");
            }
            if (pick.state() != PickState.CONFLICTED) {
                return PickStep.advanced();
            }
            try {
                capability.callTool(() -> {
                    regenerateConflict(picker, pick);
                    return null;
                });
            }
            catch (UnresolvedRepairException refused) {
                log.warn("a resumed conflict could not be regenerated",
                        refused);
                return new PickStep("PICK_REFUSED", null);
            }
            return new PickStep(null, pick);
        }
        String preHead = capability.callTool(picker::head);
        PickResult result;
        try {
            result = capability.callTool(() -> picker.pick(upstreamSha));
        }
        catch (UnresolvedRepairException refused) {
            log.warn("upstream pick refused to advance", refused);
            return new PickStep("PICK_REFUSED", null);
        }
        switch (result.outcome()) {
            case EMPTY -> {
                // The fork already carries it. Skipping is the only sound
                // outcome: Git will not record an empty commit, so parking a
                // human here asks for a decision that does not exist.
                upstreamSync.recordPick(
                        runId, ordinal, upstreamSha, preHead, null, null,
                        PickState.SKIPPED_EMPTY, List.of(), false, null);
                return PickStep.advanced();
            }
            case CLEAN -> {
                upstreamSync.recordPick(
                        runId, ordinal, upstreamSha, preHead, result.head(),
                        result.commitSha(), PickState.CLEAN, List.of(),
                        result.provenanceVerified(),
                        adopt(capability, taskId, result.head()));
                return PickStep.advanced();
            }
            case CONFLICTED -> {
                UpstreamPick pick = upstreamSync.recordPick(
                        runId, ordinal, upstreamSha, preHead, result.head(),
                        result.commitSha(), PickState.CONFLICTED,
                        result.conflictedPaths(),
                        result.provenanceVerified(), null);
                return new PickStep(null, pick);
            }
            default -> throw new IllegalStateException(
                    "unreachable pick outcome");
        }
    }

    /**
     * Resumes the Task Agent for the one semantic part of a pick.
     *
     * <p>The conflicted index and sequencer remain intact while the agent
     * edits. The program verifies the edit before it continues the pick.
     */
    private String repairRange(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
            InitialToolCapability capability,
            UpstreamPicker picker,
            String taskId,
            String runId,
            UpstreamSyncRequest request,
            Task task,
            String targetBaseRef,
            List<String> selected,
            UpstreamPick firstConflict)
    {
        if (!upstreamSync.run(runId).orElseThrow().repairTurnsRemaining()) {
            return "BUDGET_SPENT";
        }
        upstreamSync.spendRepairTurn(runId);
        AtomicReference<UpstreamPick> active = new AtomicReference<>(
                firstConflict);
        AtomicReference<String> targetSubject = new AtomicReference<>(
                capability.callTool(() -> picker.subject(
                        firstConflict.upstreamSha())));
        AtomicReference<String> stopReason = new AtomicReference<>();
        AtomicBoolean declined = new AtomicBoolean();
        AtomicBoolean finalReady = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicInteger checkAttempts = new AtomicInteger();
        AtomicBoolean checksUnavailable = new AtomicBoolean();
        NewFlowWorkspaceTools workspace = new NewFlowWorkspaceTools(worktree);
        ToolExecutor executor = bounded(terminal, call ->
                switch (call.name()) {
                    case "read_pick_conflict_context" -> safe(() ->
                            conflictContext(
                                    capability, picker, request.goalText(), active.get(),
                                    targetSubject.get()));
                    case "read_upstream_review_context" -> finalReady.get()
                            ? safe(() -> finalReviewContext(
                                    request, runId, picker))
                            : ToolCallResult.error(
                                    "the confirmed range is not complete");
                    case "read_candidate_diff" -> finalReady.get()
                            ? safe(() -> candidateDiff(
                                    capability, worktree, request.targetRef(),
                                    picker.head()))
                            : ToolCallResult.error(
                                    "the confirmed range is not complete");
                    case "run_checks" -> finalReady.get()
                            ? runChecks(checkAttempts, checksUnavailable,
                                    capability, call)
                            : ToolCallResult.error(
                                    "the confirmed range is not complete");
                    case "list_repository" -> guarded(capability,
                            () -> json(workspace.listRepository()));
                    case "read_file" -> guarded(capability,
                            () -> workspace.readFile(text(call, "path")));
                    case "search_repository" -> guarded(capability,
                            () -> json(workspace.search(text(call, "query"))));
                    case "write_file" -> guarded(capability, () -> {
                        workspace.writeFile(
                                text(call, "path"), text(call, "content"));
                        return "written";
                    });
                    case "replace_file_lines" -> guarded(capability, () -> {
                        workspace.replaceFileLines(
                                text(call, "path"),
                                integer(call, "start_line"),
                                integer(call, "end_line"),
                                string(call, "content"));
                        return "line range replaced";
                    });
                    case "delete_file" -> guarded(capability, () -> {
                        workspace.deleteFile(text(call, "path"));
                        return "deleted";
                    });
                    case "commit_initial_change" -> finalReady.get()
                            ? safe(() -> {
                                String head = capability.callTool(
                                        workspace::commitTaskChange);
                                capability.adoptCommittedHead(head);
                                return "upstream correction committed";
                            })
                            : ToolCallResult.error(
                                    "the confirmed range is not complete");
                    case "request_initial_review" -> finalReady.get()
                            ? safe(() -> {
                                requestFinalReview(
                                        capability, picker, task, request,
                                        runId, selected,
                                        text(call, "title"),
                                        text(call, "body"));
                                terminal.set(true);
                                return "upstream review requested";
                            })
                            : ToolCallResult.error(
                                    "the confirmed range is not complete");
                    // Terminal only when deterministic advancement reaches
                    // the end; otherwise it installs the next conflict.
                    case "commit_pick_repair" -> safe(() -> {
                        UpstreamPick repaired = active.get();
                        PickResult continued = capability.callTool(() ->
                                picker.continuePick(
                                        repaired.preHead(),
                                        repaired.upstreamSha(),
                                        repaired.conflictedPaths()));
                        if (!continued.provenanceVerified()) {
                            throw new UnresolvedRepairException(
                                    "continued pick carries no -x provenance");
                        }
                        String revision = adopt(
                                capability, taskId, continued.head());
                        upstreamSync.resolvePick(
                                repaired.pickId(), continued.head(),
                                continued.commitSha(),
                                continued.provenanceVerified(), revision);
                        for (int ordinal = repaired.ordinal() + 1;
                                ordinal < selected.size(); ordinal++) {
                            PickStep step = applyOne(
                                    capability, picker, taskId, runId, ordinal,
                                    selected.get(ordinal));
                            if (step.parkReason() != null) {
                                stopReason.set(step.parkReason());
                                terminal.set(true);
                                return "repair recorded; the next pick was "
                                        + "refused and the range will park";
                            }
                            if (step.conflict() != null) {
                                if (!upstreamSync.run(runId).orElseThrow()
                                        .repairTurnsRemaining()) {
                                    stopReason.set("BUDGET_SPENT");
                                    terminal.set(true);
                                    return "repair recorded; the next conflict "
                                            + "exhausted the repair budget";
                                }
                                upstreamSync.spendRepairTurn(runId);
                                active.set(step.conflict());
                                targetSubject.set(capability.callTool(() ->
                                        picker.subject(step.conflict()
                                                .upstreamSha())));
                                return "conflicted pick continued; another conflict is "
                                        + "ready, so read its context and "
                                        + "repair it next";
                            }
                        }
                        finalReady.set(true);
                        try {
                            publishPolicies(task, targetBaseRef, picker);
                        }
                        catch (RuntimeException failure) {
                            stopReason.set("FINAL_REVIEW_SETUP_FAILED:"
                                    + describe(failure));
                            terminal.set(true);
                            return "repair recorded; final review setup failed "
                                    + "and the range will park";
                        }
                        return "conflicted pick continued; the confirmed range "
                                + "is complete, so inspect it and request review";
                    });
                    // An explicit decline, which parks the run for the user.
                    // This used to be spelled `request_initial_review`, whose
                    // own program prompt instructs the agent to call it — so a
                    // declined conflict was one prompt-following away.
                    case "decline_pick_repair" -> {
                        declined.set(true);
                        terminal.set(true);
                        yield ToolCallResult.error(
                                "the conflict was declined; the run parks for "
                                        + "the user");
                    }
                    default -> ToolCallResult.error("tool is not available");
                });
        TurnResult turn = bodies.upstreamPickRepair(
                binding,
                runId,
                binding.isApi() ? executor : watched(runId, executor),
                terminal, worktree, capability,
                agentActivity(runId, !binding.isApi()));
        publishTurnEnd(runId, turn);
        if (stopReason.get() != null) {
            return stopReason.get();
        }
        if (terminal.get() && !declined.get()) {
            return null;
        }
        String reason = unfinishedRepairReason(
                finalReady.get(), declined.get());
        log.info("upstream sync turn parked after pick {} ({}, {})",
                active.get().pickId(), turn.end(), reason);
        return reason;
    }

    static String unfinishedRepairReason(boolean finalReady, boolean declined)
    {
        if (declined) {
            return "CONFLICT_DECLINED";
        }
        return finalReady ? "FINAL_REVIEW_UNRESOLVED" : "CONFLICT_UNRESOLVED";
    }

    private String finalReview(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
            InitialToolCapability capability,
            UpstreamPicker picker,
            Task task,
            UpstreamSyncRequest request,
            String runId,
            List<String> selected)
    {
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicInteger checkAttempts = new AtomicInteger();
        AtomicBoolean checksUnavailable = new AtomicBoolean();
        NewFlowWorkspaceTools workspace = new NewFlowWorkspaceTools(worktree);
        ToolExecutor executor = bounded(terminal, call ->
                switch (call.name()) {
                    case "read_upstream_review_context" -> safe(() ->
                            finalReviewContext(request, runId, picker));
                    case "read_candidate_diff" -> safe(() ->
                            candidateDiff(capability, worktree,
                                    request.targetRef(), picker.head()));
                    case "run_checks" -> runChecks(
                            checkAttempts, checksUnavailable,
                            capability, call);
                    case "list_repository" -> guarded(capability,
                            () -> json(workspace.listRepository()));
                    case "read_file" -> guarded(capability,
                            () -> workspace.readFile(text(call, "path")));
                    case "search_repository" -> guarded(capability,
                            () -> json(workspace.search(text(call, "query"))));
                    case "write_file" -> guarded(capability, () -> {
                        workspace.writeFile(
                                text(call, "path"), text(call, "content"));
                        return "written";
                    });
                    case "replace_file_lines" -> guarded(capability, () -> {
                        workspace.replaceFileLines(
                                text(call, "path"),
                                integer(call, "start_line"),
                                integer(call, "end_line"),
                                string(call, "content"));
                        return "line range replaced";
                    });
                    case "delete_file" -> guarded(capability, () -> {
                        workspace.deleteFile(text(call, "path"));
                        return "deleted";
                    });
                    case "commit_initial_change" -> safe(() -> {
                        String head = capability.callTool(
                                workspace::commitTaskChange);
                        capability.adoptCommittedHead(head);
                        return "upstream correction committed";
                    });
                    case "request_initial_review" -> safe(() -> {
                        requestFinalReview(
                                capability, picker, task, request, runId,
                                selected, text(call, "title"),
                                text(call, "body"));
                        terminal.set(true);
                        return "upstream review requested";
                    });
                    default -> ToolCallResult.error("tool is not available");
                });
        TurnResult turn = bodies.upstreamPickRepair(
                binding,
                runId,
                binding.isApi() ? executor : watched(runId, executor),
                terminal, worktree, capability,
                agentActivity(runId, !binding.isApi()));
        publishTurnEnd(runId, turn);
        return terminal.get() ? null : "FINAL_REVIEW_UNRESOLVED";
    }

    private String finalReviewContext(
            UpstreamSyncRequest request, String runId, UpstreamPicker picker)
    {
        return "goal=" + request.goalText()
                + "\nrunId=" + runId
                + "\nexpectedBase=" + request.targetRef()
                + "\ncurrentHead=" + picker.head()
                + "\nselectedUpstreamShas="
                + String.join(",", request.selectedUpstreamShas());
    }

    private static String candidateDiff(
            InitialToolCapability capability,
            Path worktree,
            String base,
            String head)
    {
        return capability.callTool(() -> new String(
                new ImmutableGitObjectReader(
                        worktree, base, head).readDiff(),
                StandardCharsets.UTF_8));
    }

    private static String checkSummary(List<LocalCheckRun> checks)
    {
        return checks.stream()
                .map(check -> {
                    String summary = check.profileId() + ":"
                            + check.conclusion().name();
                    if (check.conclusion() == LocalCheckConclusion.PASSED
                            || check.outputText() == null
                            || check.outputText().isBlank()) {
                        return summary;
                    }
                    return summary + "\n"
                            + NewFlowAgentBodies.tailOf(check.outputText());
                })
                .collect(Collectors.joining("\n"));
    }

    private ToolCallResult runChecks(
            AtomicInteger attempts,
            AtomicBoolean unavailable,
            InitialToolCapability capability,
            ToolCall call)
    {
        CheckRequest request;
        try {
            request = checkRequest(call);
        }
        catch (RuntimeException invalid) {
            return ToolCallResult.error("tool argument is invalid");
        }
        if (unavailable.get()) {
            return ToolCallResult.error(
                    "local validation is unavailable in this environment");
        }
        if (attempts.incrementAndGet() > MAX_CHECK_ATTEMPTS) {
            return ToolCallResult.error("local-check attempt bound reached");
        }
        try {
            List<LocalCheckRun> runs = capability.runChecks(
                    request.command(), request.workingDirectory());
            unavailable.set(runs.stream().anyMatch(check ->
                    check.conclusion() == LocalCheckConclusion.UNAVAILABLE));
            return ToolCallResult.ok(checkSummary(runs));
        }
        catch (RuntimeException failure) {
            return ToolCallResult.error(
                    "local validation failed closed: " + describe(failure));
        }
    }

    private static CheckRequest checkRequest(ToolCall call)
    {
        JsonNode input = call.input();
        JsonNode commandNode = input == null ? null : input.get("command");
        JsonNode directoryNode = input == null
                ? null : input.get("working_directory");
        if (input == null || !input.isObject() || input.size() != 2
                || commandNode == null || !commandNode.isArray()
                || commandNode.isEmpty()
                || commandNode.size() > 128
                || directoryNode == null || !directoryNode.isTextual()
                || directoryNode.textValue().isBlank()
                || directoryNode.textValue().length() > 4_096
                || directoryNode.textValue().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid local check request");
        }
        List<String> command = new ArrayList<>();
        for (JsonNode argument : commandNode) {
            if (!argument.isTextual() || argument.textValue().isEmpty()
                    || argument.textValue().length() > 4_096
                    || argument.textValue().indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "invalid local check argument");
            }
            command.add(argument.textValue());
        }
        return new CheckRequest(
                List.copyOf(command), directoryNode.textValue());
    }

    private void requestFinalReview(
            InitialToolCapability capability,
            UpstreamPicker picker,
            Task task,
            UpstreamSyncRequest request,
            String runId,
            List<String> selected,
            String title,
            String body)
    {
        String verification = verifyHistory(
                picker, runId, task.taskId(), request.targetRef(), selected);
        upstreamSync.recordVerification(
                runId, RunState.FINAL_REVIEW, picker.head(), verification);
        // A title the user typed is the title, not a suggestion: the agent only
        // names the PR when the picker left that field empty.
        String requested = request.prTitle();
        capability.requestReview(
                requested == null || requested.isBlank() ? title : requested,
                body);
    }

    private void publishPolicies(
            Task task, String targetBaseRef, UpstreamPicker picker)
    {
        policies.publish(
                task.taskId(), task.repositoryId(), targetBaseRef,
                targetBaseRef.substring("refs/heads/".length()),
                Path.of(task.worktreePath()), picker.head());
    }

    /**
     * Proves the constructed range before anything leaves the machine.
     *
     * <p>Selection, order, provenance and one-fixup-per-owner are all
     * mechanical, so none of them is taken from agent prose.
     */
    private String verifyHistory(
            UpstreamPicker picker,
            String runId,
            String taskId,
            String expectedBaseSha,
            List<String> selected)
    {
        List<UpstreamPick> picks = upstreamSync.picks(runId);
        if (picks.size() != selected.size()) {
            throw new UnresolvedRepairException(
                    "the range has an unrecorded commit");
        }
        MessageDigest digest = sha256();
        frame(digest, "upstream-verification:v2");
        frame(digest, runId);
        frame(digest, taskId);
        frame(digest, expectedBaseSha);
        for (int ordinal = 0; ordinal < picks.size(); ordinal++) {
            UpstreamPick pick = picks.get(ordinal);
            if (pick.ordinal() != ordinal
                    || !pick.upstreamSha().equals(selected.get(ordinal))) {
                throw new UnresolvedRepairException(
                        "the recorded range is out of order");
            }
            if (pick.state() == PickState.CONFLICTED
                    || pick.state() == PickState.NEEDS_ATTENTION) {
                throw new UnresolvedRepairException(
                        "a pick is still unresolved");
            }
            if (pick.landedCommit() && !pick.provenanceVerified()) {
                throw new UnresolvedRepairException(
                        "a picked commit carries no upstream provenance");
            }
            frame(digest, pick.pickId());
            frame(digest, pick.state().name());
            frame(digest, String.valueOf(pick.resultCommitSha()));
            upstreamSync.fixup(pick.pickId()).ifPresent(
                    fixup -> frame(digest, fixup.currentCommitSha()));
        }
        if (!picker.clean()) {
            throw new UnresolvedRepairException(
                    "the worktree is not clean at final review");
        }
        String head = picker.head();
        frame(digest, head);
        return "upstream-verification:" + HexFormat.of()
                .formatHex(digest.digest()).substring(0, 32);
    }

    /**
     * The user's terminal stop, taken at the boundary that asked for it.
     *
     * <p>The sequencer is cleaned away first for the same reason a park does
     * it: the worktree has to be at its recorded head before anything reads or
     * removes it, so a half-applied pick is never what is left behind.
     *
     * <p>Only the run is closed here. This code runs inside the turn, so the
     * turn's own writer lease is live and the Task's lifecycle cannot move —
     * releasing the checkout from under the process still holding it is
     * exactly what that lease exists to prevent. The lease clears when this
     * completion ends the turn, and the release happens from there.
     */
    private AgentCompletion closeHere(
            InitialToolCapability capability,
            UpstreamPicker picker,
            String runId)
    {
        capability.callTool(() -> {
            picker.abortSequencer();
            return null;
        });
        upstreamSync.advanceState(runId, RunState.CANCELED);
        return new AgentCompletion(
                TerminalOutcome.FAILED,
                "upstream synchronization closed by the user",
                "UPSTREAM_SYNC_CLOSED");
    }

    private AgentCompletion closeReviewContinuationHere(String runId)
    {
        upstreamSync.advanceState(runId, RunState.CANCELED);
        return new AgentCompletion(
                TerminalOutcome.FAILED,
                "upstream synchronization closed by the user",
                "UPSTREAM_SYNC_CLOSED");
    }

    private AgentCompletion park(
            InitialToolCapability capability,
            UpstreamPicker picker,
            String runId,
            String reason)
    {
        // Return the worktree to its clean recorded head before the run
        // waits. The conflict's evidence lives in the pick record, and a
        // resumed run re-picks the same commit onto the same head, which
        // regenerates the identical conflict — so nothing an agent needs is
        // lost, while the clean tree is what lets a later resumed INITIAL
        // turn pass writer admission.
        capability.callTool(() -> {
            picker.abortSequencer();
            return null;
        });
        upstreamSync.park(runId, reason);
        return new AgentCompletion(
                TerminalOutcome.FAILED,
                "upstream synchronization parked for the user",
                "UPSTREAM_SYNC_PARKED:" + reason);
    }

    /**
     * Reopens a recorded conflict whose sequencer a park cleaned away.
     *
     * <p>Re-picking the same commit onto the same head is deterministic, so
     * the regenerated conflict must match the recorded one exactly; anything
     * else means the worktree is not the one the record describes.
     */
    private static void regenerateConflict(
            UpstreamPicker picker, UpstreamPick pick)
    {
        if (picker.sequencerActive()) {
            return;
        }
        if (!picker.head().equals(pick.preHead())) {
            throw new UnresolvedRepairException(
                    "a resumed conflict is not at its recorded head");
        }
        PickResult regenerated = picker.pick(pick.upstreamSha());
        if (regenerated.outcome() != UpstreamPicker.Outcome.CONFLICTED
                || !Set.copyOf(regenerated.conflictedPaths()).equals(
                        Set.copyOf(pick.conflictedPaths()))) {
            throw new UnresolvedRepairException(
                    "re-picking the recorded conflict did not reproduce it");
        }
    }

    /** One change-set revision per pick, chained through the same capability. */
    private String adopt(
            InitialToolCapability capability, String taskId, String head)
    {
        capability.adoptCommittedHead(head);
        Optional<ChangeSetRevision> current = runtime.currentChangeSet(taskId);
        if (current.isEmpty() || !current.orElseThrow().headSha().equals(head)) {
            throw new UnresolvedRepairException(
                    "the adopted change set is not the picked head");
        }
        return current.orElseThrow().changeSetRevisionId();
    }

    private String conflictContext(
            InitialToolCapability capability,
            UpstreamPicker picker,
            String taskGoal,
            UpstreamPick pick,
            String targetSubject)
    {
        return capability.callTool(() -> "taskGoal=" + taskGoal
                + "\nupstreamSha=" + pick.upstreamSha()
                + "\nordinal=" + pick.ordinal()
                + "\nupstreamSubject=" + targetSubject
                + "\npreHead=" + pick.preHead()
                + "\nmeasuredCurrentHead=" + picker.head()
                + "\nsequencerActive=" + picker.sequencerActive()
                + "\nunmergedPaths="
                + String.join(", ", picker.unmergedPaths())
                + "\nconflictedPaths="
                + String.join(", ", pick.conflictedPaths())
                + "\nupstreamDiff:\n"
                + picker.upstreamDiff(
                        pick.upstreamSha(), MAX_UPSTREAM_DIFF_CHARS)
                + "\nThe conflicted index and cherry-pick sequencer are still"
                + " open. Edit the conflicted paths, then call the repair"
                + " tool; the program rejects markers or unmerged entries"
                + " before running cherry-pick --continue.");
    }

    private String draftTitle(UpstreamSyncRequest request, int count)
    {
        return "Sync " + count + " upstream commit"
                + (count == 1 ? "" : "s") + " from "
                + request.sourceRemote() + "/" + request.sourceToRef();
    }

    private String draftBody(
            UpstreamPicker picker,
            UpstreamSyncRequest request,
            String runId,
            Task task)
    {
        StringBuilder body = new StringBuilder(request.goalText())
                .append("\n\nRange ").append(request.sourceFromRef())
                .append("..").append(request.sourceToRef())
                .append(" onto ").append(task.baseRef())
                .append(" at ").append(task.currentBaseSha())
                .append(".\n\n");
        List<UpstreamPick> picks = upstreamSync.picks(runId);
        for (UpstreamPick pick : picks.subList(
                0, Math.min(picks.size(), MAX_DRAFT_BODY_COMMITS))) {
            body.append("- ").append(pick.upstreamSha()).append(' ')
                    .append(pick.state().name());
            if (pick.landedCommit()) {
                body.append(" — ").append(
                        picker.subject(pick.resultCommitSha()));
            }
            upstreamSync.fixup(pick.pickId()).ifPresent(fixup ->
                    body.append(" (+1 attributed fixup)"));
            body.append('\n');
        }
        if (picks.size() > MAX_DRAFT_BODY_COMMITS) {
            body.append("- … ")
                    .append(picks.size() - MAX_DRAFT_BODY_COMMITS)
                    .append(" more\n");
        }
        return body.toString();
    }

    /**
     * Reports each tool call as the turn makes it.
     *
     * <p>The run's log only gains a line when a turn ends, so without this a
     * repair that compiles for minutes reads as a stalled run. Emitted in the
     * shape the run view already parses, and never allowed to fail the turn:
     * this is a view of the work, not part of it.
     */
    private ToolExecutor watched(String runId, ToolExecutor delegate)
    {
        return call -> {
            publish(runId, toolLine(call));
            ToolCallResult result = delegate.execute(call);
            publish(runId, toolResultLine(
                    call.id(), result.text(), result.isError()));
            return result;
        };
    }

    private String toolLine(ToolCall call)
    {
        return toolLine(
                call.id(), call.name(), call.input() == null
                        ? mapper.createObjectNode() : call.input());
    }

    private String toolLine(String callId, String name, JsonNode input)
    {
        ObjectNode line = mapper.createObjectNode();
        line.put("type", "assistant");
        ObjectNode block = line.putObject("message").putArray("content")
                .addObject();
        block.put("type", "tool_use");
        block.put("id", callId);
        block.put("name", name);
        block.set("input", input);
        return line.toString();
    }

    private String toolResultLine(
            String callId, String result, boolean isError)
    {
        ObjectNode line = mapper.createObjectNode();
        line.put("type", "user");
        ObjectNode block = line.putObject("message").putArray("content")
                .addObject();
        block.put("type", "tool_result");
        block.put("tool_use_id", callId);
        block.set("content", jsonOrText(result));
        block.put("is_error", isError);
        return line.toString();
    }

    private String assistantLine(String text)
    {
        ObjectNode line = mapper.createObjectNode();
        line.put("type", "assistant");
        ObjectNode block = line.putObject("message").putArray("content")
                .addObject();
        block.put("type", "text");
        block.put("text", text);
        return line.toString();
    }

    /** CLI/API activity, optionally excluding calls already shown by watched. */
    private Consumer<StreamEvent> agentActivity(
            String runId, boolean suppressBridgedCalls)
    {
        Set<String> bridgedCalls = ConcurrentHashMap.newKeySet();
        return event -> {
            if (suppressBridgedCalls
                    && event instanceof StreamEvent.ToolCallStarted started
                    && started.toolName().startsWith("mcp__bytequay__")) {
                bridgedCalls.add(started.callId());
                return;
            }
            if (suppressBridgedCalls
                    && event instanceof StreamEvent.ToolCallDone done
                    && bridgedCalls.remove(done.callId())) {
                return;
            }
            String line = switch (event) {
                case StreamEvent.AssistantText text ->
                        assistantLine(text.text());
                case StreamEvent.ToolCallStarted started -> toolLine(
                        started.callId(), started.toolName(),
                        jsonOrText(started.inputJson()));
                case StreamEvent.ToolCallDone done -> toolResultLine(
                        done.callId(), done.outputJson(), done.isError());
                case StreamEvent.ErrorOccurred error -> assistantLine(
                        "Agent error: " + error.message());
                default -> null;
            };
            if (line != null) {
                publish(runId, line);
            }
        };
    }

    private JsonNode jsonOrText(String value)
    {
        try {
            return mapper.readTree(value == null ? "" : value);
        }
        catch (JsonProcessingException ignored) {
            return mapper.getNodeFactory().textNode(value == null ? "" : value);
        }
    }

    private void publish(String runId, String line)
    {
        try {
            live.publish(runId, line);
        }
        catch (RuntimeException ignored) {
            // A watcher that broke is not the agent's problem.
        }
    }

    private void publishTurnEnd(String runId, TurnResult turn)
    {
        try {
            ObjectNode line = mapper.createObjectNode();
            line.put("type", "result");
            line.put("is_error", turn.end() != TurnResult.End.COMPLETED);
            line.put("num_turns", turn.rounds());
            line.put("total_cost_usd", turn.costMilliUsd() / 1000.0);
            publish(runId, line.toString());
        }
        catch (RuntimeException ignored) {
            // As above: the durable record of the turn is elsewhere.
        }
    }

    /**
     * No call counting: every tool here is program-served, lease-guarded, and
     * permitted by the role's manifest, so a permitted call is a permitted
     * call however many came before it. The turn ends on its terminal tool,
     * the check-attempt bound, or the agent's own context — not on a quota.
     */
    private ToolExecutor bounded(
            AtomicBoolean terminal,
            ToolExecutor delegate)
    {
        return call -> {
            if (terminal.get()) {
                return ToolCallResult.error("terminal tool already accepted");
            }
            ToolCallResult result = delegate.execute(call);
            if (result.text().length() <= MAX_TOOL_RESULT_CHARS) {
                return result;
            }
            return new ToolCallResult(
                    result.text().substring(0, MAX_TOOL_RESULT_CHARS)
                            + "\n[result truncated; narrow the request to"
                            + " see the rest]",
                    result.isError());
        };
    }

    private ToolCallResult guarded(
            InitialToolCapability capability, Supplier<String> action)
    {
        return safe(() -> capability.callTool(action));
    }

    private static ToolCallResult safe(Supplier<String> action)
    {
        try {
            return ToolCallResult.ok(action.get());
        }
        catch (RuntimeException failure) {
            return ToolCallResult.error(
                    "tool failed closed: " + describe(failure));
        }
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "tool result encoding failed", failure);
        }
    }

    private static String text(ToolCall call, String name)
    {
        JsonNode value = call.input() == null
                ? null : call.input().get(name);
        if (value == null || !value.isTextual()
                || value.textValue().isBlank()) {
            throw new IllegalArgumentException("tool argument is invalid");
        }
        return value.textValue();
    }

    private static String string(ToolCall call, String name)
    {
        JsonNode value = call.input() == null
                ? null : call.input().get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("tool argument is invalid");
        }
        return value.textValue();
    }

    private static int integer(ToolCall call, String name)
    {
        JsonNode value = call.input() == null
                ? null : call.input().get(name);
        if (value == null || !value.canConvertToInt()
                || value.intValue() < 1) {
            throw new IllegalArgumentException("tool argument is invalid");
        }
        return value.intValue();
    }

    private static MessageDigest sha256()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void frame(MessageDigest digest, String value)
    {
        byte[] bytes = value == null
                ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length)
                .getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private record CheckRequest(
            List<String> command, String workingDirectory) {}
}
