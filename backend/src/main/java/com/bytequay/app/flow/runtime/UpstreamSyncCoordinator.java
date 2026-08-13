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

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.AgentCompletion;
import com.bytequay.app.flow.runtime.InitialTaskCoordinator.InitialToolCapability;
import com.bytequay.app.flow.upstream.UpstreamPicker;
import com.bytequay.app.flow.upstream.UpstreamPicker.PickResult;
import com.bytequay.app.flow.upstream.UpstreamPicker.UnresolvedRepairException;
import com.bytequay.app.flow.upstream.UpstreamSync;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.FixupKind;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.PickState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamPick;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRequest;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.ToolExecutor.ToolCallResult;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
 * The run's park-before-push already is that gate, so nothing about the user's
 * authorization step is invented here; it is reached. Publication through the
 * flow's own effect is also what lets generic CI Autofix adopt the pull
 * request afterwards, because its only entry is a gate-authorized receipt.
 */
public final class UpstreamSyncCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(
            UpstreamSyncCoordinator.class);
    private static final int MAX_REPAIR_TOOL_CALLS = 24;
    private static final int MAX_TOOL_RESULT_CHARS = 256 * 1024;
    private static final int MAX_DRAFT_BODY_COMMITS = 50;

    private final FlowRuntime runtime;
    private final UpstreamSync upstreamSync;
    private final NewFlowAgentLaunches launches;
    private final TurnRunner runner;
    private final ObjectMapper mapper;

    public UpstreamSyncCoordinator(
            FlowRuntime runtime,
            UpstreamSync upstreamSync,
            NewFlowAgentLaunches launches,
            TurnRunner runner,
            ObjectMapper mapper)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.upstreamSync = requireNonNull(
                upstreamSync, "upstreamSync is null");
        this.launches = requireNonNull(launches, "launches is null");
        this.runner = requireNonNull(runner, "runner is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** True when this Task's branch is one this component builds. */
    public boolean owns(String taskId)
    {
        return upstreamSync.runForTask(taskId).isPresent();
    }

    /**
     * Runs one INITIAL Task turn for an upstream sync Task.
     *
     * <p>The first turn picks the confirmed range and requests review; the
     * continuation turn accepts the adversarial review and asks for the exact
     * initial-publish gate. Both are the ordinary INITIAL contract — this
     * component only replaces what happens between them.
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
        UpstreamSyncRun run = upstreamSync.runForTask(taskId).orElseThrow(
                () -> new IllegalStateException(
                        "Task is not owned by upstream synchronization"));
        try {
            if (reviewContinuation) {
                capability.readyForInitialPublish();
                upstreamSync.advanceState(
                        run.runId(), RunState.WAITING_INITIAL_PUBLISH);
                return new AgentCompletion(
                        TerminalOutcome.COMPLETED,
                        "upstream range accepted for initial publication",
                        null);
            }
            return pickRange(binding, worktree, capability, taskId, run);
        }
        catch (RuntimeException failure) {
            // The supervisor's own catch reports one opaque code for every
            // body, which cannot tell a refused pick from a stale fence.
            log.warn("upstream synchronization turn failed", failure);
            return new AgentCompletion(
                    TerminalOutcome.FAILED, null,
                    "UPSTREAM_SYNC_FAILED:" + describe(failure));
        }
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
        upstreamSync.advanceState(startRun.runId(), RunState.PICKING);
        List<String> selected = request.selectedUpstreamShas();
        for (int ordinal = 0; ordinal < selected.size(); ordinal++) {
            String upstreamSha = selected.get(ordinal);
            String parkReason = applyOne(
                    binding, worktree, capability, picker,
                    taskId, startRun.runId(), ordinal, upstreamSha);
            if (parkReason != null) {
                return park(picker, startRun.runId(), parkReason);
            }
        }
        String verification;
        try {
            verification = verifyHistory(
                    picker, startRun.runId(), selected);
        }
        catch (UnresolvedRepairException unproven) {
            log.warn("upstream history verification refused the candidate",
                    unproven);
            return park(picker, startRun.runId(), "HISTORY_UNVERIFIED");
        }
        upstreamSync.recordVerification(
                startRun.runId(), RunState.FINAL_REVIEW, verification);
        Task task = runtime.task(taskId).orElseThrow();
        capability.requestReview(
                draftTitle(request, selected.size()),
                draftBody(picker, request, startRun.runId(), task));
        return new AgentCompletion(
                TerminalOutcome.COMPLETED,
                "upstream range constructed and submitted for review", null);
    }

    /** Applies one commit; returns a park reason, or null to keep going. */
    private String applyOne(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
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
        Optional<UpstreamPick> recorded = upstreamSync.picks(runId).stream()
                .filter(pick -> pick.upstreamSha().equals(upstreamSha))
                .findFirst();
        if (recorded.isPresent()) {
            UpstreamPick pick = recorded.orElseThrow();
            return pick.state() == PickState.CONFLICTED
                    ? repair(binding, worktree, capability, picker, taskId,
                            runId, pick)
                    : null;
        }
        String preHead = capability.callTool(picker::head);
        PickResult result;
        try {
            result = capability.callTool(() -> picker.pick(upstreamSha));
        }
        catch (UnresolvedRepairException refused) {
            log.warn("upstream pick refused to advance", refused);
            return "PICK_REFUSED";
        }
        switch (result.outcome()) {
            case EMPTY -> {
                // The fork already carries it. Skipping is the only sound
                // outcome: Git will not record an empty commit, so parking a
                // human here asks for a decision that does not exist.
                upstreamSync.recordPick(
                        runId, ordinal, upstreamSha, preHead, null, null,
                        PickState.SKIPPED_EMPTY, List.of(), false, null);
                return null;
            }
            case CLEAN -> {
                upstreamSync.recordPick(
                        runId, ordinal, upstreamSha, preHead, result.head(),
                        result.commitSha(), PickState.CLEAN, List.of(),
                        result.provenanceVerified(),
                        adopt(capability, taskId, result.head()));
                return null;
            }
            case CONFLICTED -> {
                UpstreamPick pick = upstreamSync.recordPick(
                        runId, ordinal, upstreamSha, preHead, result.head(),
                        result.commitSha(), PickState.CONFLICTED,
                        result.conflictedPaths(),
                        result.provenanceVerified(),
                        adopt(capability, taskId, result.head()));
                return repair(
                        binding, worktree, capability, picker, taskId, runId,
                        pick);
            }
            default -> throw new IllegalStateException(
                    "unreachable pick outcome");
        }
    }

    /**
     * Resumes the Task Agent for the one semantic part of a pick.
     *
     * <p>Git's own three-way resolution is already committed by the time this
     * runs, which is what keeps the sequencer closed — and also why the repair
     * is verified rather than believed: a file the agent reports resolved but
     * never opened would otherwise reach the pull request carrying markers.
     */
    private String repair(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
            InitialToolCapability capability,
            UpstreamPicker picker,
            String taskId,
            String runId,
            UpstreamPick pick)
    {
        UpstreamSyncRun current = upstreamSync.run(runId).orElseThrow();
        if (current.remainingRepairTurns() <= 0) {
            return "BUDGET_SPENT";
        }
        upstreamSync.spendRepairTurn(runId);
        String targetSubject = capability.callTool(
                () -> picker.subject(pick.resultCommitSha()));
        boolean hadFixup = upstreamSync.fixup(pick.pickId()).isPresent();
        AtomicBoolean resolved = new AtomicBoolean();
        AtomicBoolean declined = new AtomicBoolean();
        NewFlowWorkspaceTools workspace = new NewFlowWorkspaceTools(worktree);
        ToolExecutor executor = bounded(resolved, declined, call ->
                switch (call.name()) {
                    case "read_pick_conflict_context" -> safe(() ->
                            conflictContext(capability, pick, targetSubject));
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
                    case "delete_file" -> guarded(capability, () -> {
                        workspace.deleteFile(text(call, "path"));
                        return "deleted";
                    });
                    // The terminal repair tool.
                    case "commit_pick_repair" -> safe(() -> {
                        String head = capability.callTool(() -> {
                            String committed = picker.commitFixup(
                                    targetSubject, hadFixup);
                            picker.verifyRepair(pick.conflictedPaths());
                            return committed;
                        });
                        upstreamSync.recordFixup(
                                runId, pick, FixupKind.ADJACENT_FIXUP, head,
                                capability.callTool(() -> picker.changedPaths(
                                        pick.resultCommitSha(), head)),
                                null, adopt(capability, taskId, head));
                        resolved.set(true);
                        return "conflict repair attributed to its pick";
                    });
                    // An explicit decline, which parks the run for the user.
                    // This used to be spelled `request_initial_review`, whose
                    // own program prompt instructs the agent to call it — so a
                    // declined conflict was one prompt-following away.
                    case "decline_pick_repair" -> {
                        declined.set(true);
                        yield ToolCallResult.error(
                                "the conflict was declined; the run parks for "
                                        + "the user");
                    }
                    default -> ToolCallResult.error("tool is not available");
                });
        TurnResult turn = runTurn(
                binding, executor, () -> resolved.get() || declined.get());
        if (resolved.get()) {
            return null;
        }
        log.info("upstream conflict repair did not resolve pick {} ({})",
                pick.pickId(), turn.end());
        return declined.get() ? "CONFLICT_DECLINED" : "CONFLICT_UNRESOLVED";
    }

    /**
     * Proves the constructed range before anything leaves the machine.
     *
     * <p>Selection, order, provenance and one-fixup-per-owner are all
     * mechanical, so none of them is taken from agent prose.
     */
    private String verifyHistory(
            UpstreamPicker picker, String runId, List<String> selected)
    {
        List<UpstreamPick> picks = upstreamSync.picks(runId);
        if (picks.size() != selected.size()) {
            throw new UnresolvedRepairException(
                    "the range has an unrecorded commit");
        }
        MessageDigest digest = sha256();
        frame(digest, "upstream-verification:v1");
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

    private AgentCompletion park(
            UpstreamPicker picker, String runId, String reason)
    {
        try {
            picker.abortSequencer();
        }
        catch (RuntimeException failure) {
            log.warn("upstream sequencer could not be closed while parking",
                    failure);
        }
        upstreamSync.park(runId, reason);
        return new AgentCompletion(
                TerminalOutcome.FAILED,
                "upstream synchronization parked for the user",
                "UPSTREAM_SYNC_PARKED:" + reason);
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

    private TurnResult runTurn(
            NewFlowAgentLaunches.Binding binding,
            ToolExecutor executor,
            Supplier<Boolean> sealed)
    {
        if (!binding.isApi()) {
            // ponytail: an out-of-process conflict repair needs the CLI body's
            // death receipt and its loopback tool bridge. Refused rather than
            // downgraded to an API engine, which would overrule the user's
            // billing and privacy choice.
            throw new NewFlowAgentLaunches.LaunchUnavailableException(
                    "upstream conflict repair has no CLI transport yet; run "
                            + binding.runId() + " cannot repair");
        }
        ArrayNode messages = mapper.createArrayNode();
        String system = launches.systemPrompt(
                NewFlowAgentLaunches.Program.UPSTREAM_PICK_REPAIR);
        if (binding.transport() == TurnSpec.Transport.OPENAI_COMPAT) {
            messages.addObject().put("role", "system").put("content", system);
            system = null;
        }
        messages.addObject().put("role", "user").put(
                "content", "Resolve only the exact program-selected conflict.");
        return runner.runTurn(
                new TurnSpec(
                        binding.transport(),
                        binding.endpoint(),
                        launches.resolveSecret(binding),
                        binding.model(),
                        binding.reasoningEffort(),
                        system,
                        messages,
                        launches.tools(
                                NewFlowAgentLaunches.Program.UPSTREAM_PICK_REPAIR,
                                binding.transport()),
                        binding.maxOutputTokens(),
                        binding.maxToolIterations()),
                executor,
                new TurnHooks()
                {
                    @Override
                    public boolean interrupted()
                    {
                        return sealed.get()
                                || Thread.currentThread().isInterrupted();
                    }
                });
    }

    private String conflictContext(
            InitialToolCapability capability,
            UpstreamPick pick,
            String targetSubject)
    {
        return capability.callTool(() -> "upstreamSha=" + pick.upstreamSha()
                + "\nordinal=" + pick.ordinal()
                + "\npickedCommit=" + pick.resultCommitSha()
                + "\npickedSubject=" + targetSubject
                + "\nconflictedPaths="
                + String.join(", ", pick.conflictedPaths())
                + "\nThe three-way resolution is already committed and may"
                + " still carry conflict markers. Edit those paths, then"
                + " commit; the repair is attributed to this pick and"
                + " rejected if any marker survives.");
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

    private ToolExecutor bounded(
            AtomicBoolean resolved, AtomicBoolean declined,
            ToolExecutor delegate)
    {
        AtomicInteger calls = new AtomicInteger();
        return call -> {
            if (resolved.get() || declined.get()) {
                return ToolCallResult.error("terminal tool already accepted");
            }
            if (calls.incrementAndGet() > MAX_REPAIR_TOOL_CALLS) {
                return ToolCallResult.error("tool-call bound reached");
            }
            ToolCallResult result = delegate.execute(call);
            if (result.text().length() <= MAX_TOOL_RESULT_CHARS) {
                return result;
            }
            return new ToolCallResult(
                    result.text().substring(0, MAX_TOOL_RESULT_CHARS),
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
            return ToolCallResult.error("tool failed closed");
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
}
