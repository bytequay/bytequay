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
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
    private final UpstreamSyncProgram program;

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
        this.program = new UpstreamSyncProgram(runtime, upstreamSync);
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

    /** Runs exactly one semantic conflict or final-review agent turn. */
    public AgentCompletion runAgentTurn(
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
                    return closeAtBoundary(run.runId());
                }
                AgentCompletion completion = bodies.initialTask(
                        binding, worktree, capability, true,
                        agentActivity(run.runId(), false));
                if (upstreamSync.closeRequested(run.runId())) {
                    return closeAtBoundary(run.runId());
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
                                run.runId(), picker.head(), verification);
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
            return switch (run.state()) {
                case WAITING_CONFLICT_REPAIR -> conflictTurn(
                        binding, worktree, capability, taskId, run);
                case FINAL_REVIEW -> finalReviewTurn(
                        binding, worktree, capability, taskId, run);
                default -> throw new IllegalStateException(
                        "upstream agent launched outside a semantic boundary: "
                                + run.state());
            };
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

    private AgentCompletion conflictTurn(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
            InitialToolCapability capability,
            String taskId,
            UpstreamSyncRun run)
    {
        UpstreamPicker picker = new UpstreamPicker(worktree);
        if (upstreamSync.closeRequested(run.runId())) {
            return closeHere(capability, picker, run.runId());
        }
        if (upstreamSync.pauseRequested(run.runId())) {
            return park(capability, picker, run.runId(), "USER_PAUSED");
        }
        int ordinal = run.currentIndex() - 1;
        UpstreamPick conflict = upstreamSync.pick(run.runId(), ordinal)
                .filter(pick -> pick.state() == PickState.CONFLICTED)
                .orElseThrow(() -> new IllegalStateException(
                        "upstream repair has no exact conflicted pick"));
        upstreamSync.reenterConflictRepair(conflict.pickId());
        capability.callTool(() -> {
            regenerateConflict(picker, conflict);
            return null;
        });
        UpstreamSyncRequest request = upstreamSync.request(run.requestId())
                .orElseThrow();
        String blocker = repairRange(
                binding, worktree, capability, picker, taskId,
                run.runId(), request, conflict);
        if (blocker != null) {
            return park(capability, picker, run.runId(), blocker);
        }
        return new AgentCompletion(
                TerminalOutcome.COMPLETED,
                "conflict resolved; deterministic picking scheduled", null);
    }

    private AgentCompletion finalReviewTurn(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
            InitialToolCapability capability,
            String taskId,
            UpstreamSyncRun run)
    {
        if (upstreamSync.closeRequested(run.runId())) {
            return closeAtBoundary(run.runId());
        }
        Task task = runtime.task(taskId).orElseThrow();
        UpstreamSyncRequest request = upstreamSync.request(run.requestId())
                .orElseThrow();
        UpstreamPicker picker = new UpstreamPicker(worktree);
        String targetBaseRef = provisioning.targetBaseRef(taskId);
        publishPolicies(task, targetBaseRef, picker);
        String blocker = finalReview(
                binding, worktree, capability, picker, task, request,
                run.runId(), request.selectedUpstreamShas());
        if (blocker != null) {
            return park(capability, picker, run.runId(), blocker);
        }
        return new AgentCompletion(
                TerminalOutcome.COMPLETED,
                "upstream range submitted for review", null);
    }

    /** Runs only deterministic picks, never a model turn. */
    public void runProgram(Claim claim, Duration leaseTtl)
    {
        program.run(claim, leaseTtl);
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

    static String describe(Throwable failure)
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

    /**
     * Resumes the Task Agent for the one semantic part of a pick.
     *
     * <p>The agent deterministically reopens the recorded conflict, edits it,
     * and commits exactly that pick before handing control back to the program.
     */
    private String repairRange(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
            InitialToolCapability capability,
            UpstreamPicker picker,
            String taskId,
            String runId,
            UpstreamSyncRequest request,
            UpstreamPick conflict)
    {
        if (!upstreamSync.run(runId).orElseThrow().repairTurnsRemaining()) {
            return "BUDGET_SPENT";
        }
        upstreamSync.spendRepairTurn(runId);
        String targetSubject = capability.callTool(() ->
                picker.subject(conflict.upstreamSha()));
        AtomicBoolean declined = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();
        NewFlowWorkspaceTools workspace = new NewFlowWorkspaceTools(worktree);
        ToolExecutor executor = bodies.bounded(terminal, call ->
                switch (call.name()) {
                    case "read_pick_conflict_context" -> NewFlowAgentBodies.safe(() ->
                            conflictContext(
                                    capability, picker, request.goalText(),
                                    conflict, targetSubject));
                    case "list_repository" -> bodies.guarded(capability,
                            () -> bodies.json(workspace.listRepository()));
                    case "read_file" -> bodies.guarded(capability,
                            () -> workspace.readFile(
                                    NewFlowAgentBodies.text(call, "path")));
                    case "search_repository" -> bodies.guarded(capability,
                            () -> bodies.json(workspace.search(
                                    NewFlowAgentBodies.text(call, "query"))));
                    case "write_file" -> bodies.guarded(capability, () -> {
                        workspace.writeFile(
                                NewFlowAgentBodies.text(call, "path"),
                                NewFlowAgentBodies.text(call, "content"));
                        return "written";
                    });
                    case "replace_file_lines" -> bodies.guarded(
                            capability, () -> {
                        workspace.replaceFileLines(
                                NewFlowAgentBodies.text(call, "path"),
                                integer(call, "start_line"),
                                integer(call, "end_line"),
                                string(call, "content"));
                        return "line range replaced";
                    });
                    case "delete_file" -> bodies.guarded(capability, () -> {
                        workspace.deleteFile(
                                NewFlowAgentBodies.text(call, "path"));
                        return "deleted";
                    });
                    case "commit_pick_repair" -> NewFlowAgentBodies.safe(() -> {
                        PickResult continued = capability.callTool(() ->
                                picker.continuePick(
                                        conflict.preHead(),
                                        conflict.upstreamSha(),
                                        conflict.conflictedPaths()));
                        if (!continued.provenanceVerified()) {
                            throw new UnresolvedRepairException(
                                    "continued pick carries no -x provenance");
                        }
                        String revision = adopt(
                                capability, taskId, continued.head());
                        upstreamSync.resolvePick(
                                conflict.pickId(), continued.head(),
                                continued.commitSha(),
                                continued.provenanceVerified(), revision);
                        capability.continueUpstreamSync();
                        terminal.set(true);
                        return "conflicted pick continued; the program will "
                                + "pick the next commit in a new turn";
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
        if (terminal.get() && !declined.get()) {
            return null;
        }
        String reason = declined.get()
                ? "CONFLICT_DECLINED" : "CONFLICT_UNRESOLVED";
        log.info("upstream sync turn parked after pick {} ({}, {})",
                conflict.pickId(), turn.end(), reason);
        return reason;
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
        ToolExecutor executor = bodies.bounded(terminal, call ->
                switch (call.name()) {
                    case "read_upstream_review_context" -> NewFlowAgentBodies.safe(() ->
                            finalReviewContext(request, runId, picker));
                    case "read_candidate_diff" -> NewFlowAgentBodies.safe(() ->
                            candidateDiff(capability, worktree,
                                    request.targetRef(), picker.head()));
                    case "run_checks" -> runChecks(
                            checkAttempts, checksUnavailable,
                            capability, call);
                    case "list_repository" -> bodies.guarded(capability,
                            () -> bodies.json(workspace.listRepository()));
                    case "read_file" -> bodies.guarded(capability,
                            () -> workspace.readFile(
                                    NewFlowAgentBodies.text(call, "path")));
                    case "search_repository" -> bodies.guarded(capability,
                            () -> bodies.json(workspace.search(
                                    NewFlowAgentBodies.text(call, "query"))));
                    case "write_file" -> bodies.guarded(capability, () -> {
                        workspace.writeFile(
                                NewFlowAgentBodies.text(call, "path"),
                                NewFlowAgentBodies.text(call, "content"));
                        return "written";
                    });
                    case "replace_file_lines" -> bodies.guarded(
                            capability, () -> {
                        workspace.replaceFileLines(
                                NewFlowAgentBodies.text(call, "path"),
                                integer(call, "start_line"),
                                integer(call, "end_line"),
                                string(call, "content"));
                        return "line range replaced";
                    });
                    case "delete_file" -> bodies.guarded(capability, () -> {
                        workspace.deleteFile(
                                NewFlowAgentBodies.text(call, "path"));
                        return "deleted";
                    });
                    case "commit_initial_change" -> NewFlowAgentBodies.safe(() -> {
                        String head = capability.callTool(
                                workspace::commitTaskChange);
                        capability.adoptCommittedHead(head);
                        return "upstream correction committed";
                    });
                    case "request_initial_review" -> NewFlowAgentBodies.safe(() -> {
                        requestFinalReview(
                                capability, picker, task, request, runId,
                                selected,
                                NewFlowAgentBodies.text(call, "title"),
                                NewFlowAgentBodies.text(call, "body"));
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
                runId, picker.head(), verification);
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
        picker.verifyLandedHistory(expectedBaseSha, picks.stream()
                .filter(UpstreamPick::landedCommit)
                .map(pick -> new UpstreamPicker.LandedPick(
                        pick.upstreamSha(), pick.resultCommitSha()))
                .toList());
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
        return closeAtBoundary(runId);
    }

    private AgentCompletion closeAtBoundary(String runId)
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
