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

import com.bytequay.app.flow.ci.CiAutofixCoordinator.RepairToolContext;
import com.bytequay.app.flow.ci.CiFixReviewCoordinator.TaskInspectionToolCapability;
import com.bytequay.app.flow.ci.CiFixReviewCoordinator.TaskToolContext;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.InProcessCiLearningAgentSupervisor.CiLearningToolCapability;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor.ReviewerToolCapability;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.WriterToolCapability;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.CI_CLEANUP;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.CI_LEARNER;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.CI_REPAIR;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.REVIEWER;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.TASK_CI_FIX;
import static com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Program.TASK_CI_REVIEW_RESULT;
import static java.util.Objects.requireNonNull;

/** Neutral TurnRunner bodies behind role-specific, zero-owner-id tools. */
final class NewFlowAgentBodies
{
    private static final int MAX_TOOL_CALLS = 16;
    private static final int MAX_TOOL_RESULT_CHARS = 256 * 1024;

    private final NewFlowAgentLaunches launches;
    private final TurnRunner runner;
    private final ObjectMapper mapper;
    private final LocalChecks localChecks;

    NewFlowAgentBodies(
            NewFlowAgentLaunches launches,
            TurnRunner runner,
            ObjectMapper mapper,
            LocalChecks localChecks)
    {
        this.launches = requireNonNull(launches, "launches is null");
        this.runner = requireNonNull(runner, "runner is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.localChecks = requireNonNull(localChecks, "localChecks is null");
    }

    NewFlowAgentLaunches.Binding bindRepair(AgentRun run)
    {
        return launches.bind(run, CI_REPAIR);
    }

    NewFlowAgentLaunches.Binding bindCleanup(AgentRun run)
    {
        return launches.bind(run, CI_CLEANUP);
    }

    NewFlowAgentLaunches.Binding bindTaskFix(AgentRun run)
    {
        return launches.bind(run, TASK_CI_FIX);
    }

    NewFlowAgentLaunches.Binding bindTaskReviewResult(AgentRun run)
    {
        return launches.bind(run, TASK_CI_REVIEW_RESULT);
    }

    NewFlowAgentLaunches.Binding bindReviewer(AgentRun run)
    {
        return launches.bind(run, REVIEWER);
    }

    NewFlowAgentLaunches.Binding bindLearner(AgentRun run)
    {
        return launches.bind(run, CI_LEARNER);
    }

    InProcessWriterAgentSupervisor.AgentCompletion repair(
            NewFlowAgentLaunches.Binding binding,
            Path repositoryRoot,
            Path worktree,
            RepairToolContext context,
            WriterToolCapability capability)
    {
        NewFlowWorkspaceTools workspace = new NewFlowWorkspaceTools(worktree);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean checksUsed = new AtomicBoolean();
        ToolExecutor executor = bounded(stop, call -> switch (call.name()) {
            case "read_ci_failure_context" -> guarded(capability,
                    context::failureSummary);
            case "read_ci_log" -> guarded(capability, () -> context.readLog(
                    integer(call, "index"),
                    nonnegativeLong(call, "offset"), 64 * 1024));
            case "list_candidate_lessons" -> guarded(
                    capability, context::candidateLessonSummary);
            case "read_candidate_lesson" -> guarded(capability,
                    () -> context.readCandidateLesson(
                            integer(call, "index")));
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
            case "run_checks" -> checks(
                    checksUsed, capability, repositoryRoot, call);
            case "commit_repair" -> guarded(capability, () -> {
                workspace.commitRepair();
                return "committed";
            });
            default -> ToolCallResult.error("tool is not available");
        });
        return writerCompletion(run(binding, CI_REPAIR, executor, stop));
    }

    InProcessWriterAgentSupervisor.AgentCompletion cleanup(
            NewFlowAgentLaunches.Binding binding,
            String sealedStateSummary,
            Path worktree,
            WriterToolCapability capability)
    {
        NewFlowWorkspaceTools workspace = new NewFlowWorkspaceTools(worktree);
        AtomicBoolean stop = new AtomicBoolean();
        ToolExecutor executor = bounded(stop, call -> switch (call.name()) {
            case "inspect_cleanup" -> guarded(capability,
                    () -> sealedStateSummary);
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
            case "finish_cleanup" -> guarded(capability, () -> {
                workspace.commitRepair();
                return "cleanup inspection finished";
            });
            default -> ToolCallResult.error("tool is not available");
        });
        return writerCompletion(run(binding, CI_CLEANUP, executor, stop));
    }

    InProcessWriterAgentSupervisor.AgentCompletion taskFixReview(
            NewFlowAgentLaunches.Binding binding,
            Path worktree,
            TaskToolContext context,
            TaskInspectionToolCapability capability,
            boolean reviewerResultContinuation)
    {
        NewFlowWorkspaceTools workspace = new NewFlowWorkspaceTools(worktree);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicBoolean checksUsed = new AtomicBoolean();
        var program = reviewerResultContinuation
                ? TASK_CI_REVIEW_RESULT : TASK_CI_FIX;
        ToolExecutor executor = bounded(terminal, call -> switch (call.name()) {
            case "read_ci_fix_context" -> guarded(capability,
                    context::readCiFixContext);
            case "read_candidate_diff" -> guarded(capability, () -> utf8(
                    context.readCandidateDiff()));
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
            case "run_checks" -> taskChecks(checksUsed, capability, call);
            case "commit_task_change" -> safe(() -> {
                capability.runTool(workspace::commitRepair);
                capability.adoptCurrentChangeSet();
                return "Task change committed and inspected";
            });
            case "spawn_adversarial_reviewer" -> safe(() -> {
                capability.spawnAdversarialReviewer();
                terminal.set(true);
                return "review requested";
            });
            case "ready_for_review" -> {
                if (!reviewerResultContinuation) {
                    yield ToolCallResult.error("tool is not available");
                }
                yield safe(() -> {
                    capability.readyForReview();
                    terminal.set(true);
                    return "review accepted";
                });
            }
            default -> ToolCallResult.error("tool is not available");
        });
        return sealedWriterCompletion(
                run(binding, program, executor, terminal), terminal.get());
    }

    InProcessReviewerAgentSupervisor.AgentCompletion reviewer(
            NewFlowAgentLaunches.Binding binding,
            ReviewerToolCapability capability)
    {
        AtomicBoolean stop = new AtomicBoolean();
        ToolExecutor executor = bounded(stop, call -> switch (call.name()) {
            case "list_tree" -> safe(() -> json(capability.listTree().stream()
                    .map(entry -> entry.mode() + " " + entry.objectType()
                            + " " + entry.path())
                    .toList()));
            case "read_diff" -> safe(() -> utf8(capability.readDiff()));
            case "read_reviewed_blob" -> safe(() -> utf8(
                    capability.readReviewedBlob(text(call, "path"))));
            case "read_base_blob" -> safe(() -> utf8(
                    capability.readBaseBlob(text(call, "path"))));
            default -> ToolCallResult.error("tool is not available");
        });
        TurnResult result = run(binding, REVIEWER, executor, stop);
        return new InProcessReviewerAgentSupervisor.AgentCompletion(
                terminal(result), result.finalText(), error(result));
    }

    InProcessCiLearningAgentSupervisor.AgentCompletion learner(
            NewFlowAgentLaunches.Binding binding,
            List<String> programLogRefs,
            CiLearningToolCapability capability)
    {
        List<String> logs = List.copyOf(programLogRefs);
        AtomicBoolean saved = new AtomicBoolean();
        ToolExecutor executor = bounded(saved, call -> switch (call.name()) {
            case "read_repair_evidence" -> safe(
                    capability::readCiRepairEvidence);
            case "read_ci_log" -> safe(() -> {
                int index = integer(call, "index");
                long offset = nonnegativeLong(call, "offset");
                if (index < 0 || index >= logs.size()) {
                    throw new IllegalArgumentException("log index is invalid");
                }
                return capability.readCiLog(
                        logs.get(index), offset, 32_768);
            });
            case "save_ci_lesson" -> safe(() -> {
                capability.saveCiLesson(
                        text(call, "title"), text(call, "markdown"));
                saved.set(true);
                return "lesson saved";
            });
            default -> ToolCallResult.error("tool is not available");
        });
        TurnResult result = run(binding, CI_LEARNER, executor, saved);
        TerminalOutcome outcome = saved.get()
                ? TerminalOutcome.COMPLETED : terminal(result);
        String failure = saved.get() ? null : error(result);
        if (outcome == TerminalOutcome.COMPLETED && !saved.get()) {
            outcome = TerminalOutcome.FAILED;
            failure = "MISSING_TERMINAL_TOOL";
        }
        return new InProcessCiLearningAgentSupervisor.AgentCompletion(
                outcome, result.finalText(), failure);
    }

    private TurnResult run(
            NewFlowAgentLaunches.Binding binding,
            NewFlowAgentLaunches.Program program,
            ToolExecutor executor,
            AtomicBoolean terminalSeal)
    {
        ArrayNode messages = mapper.createArrayNode();
        String system = launches.systemPrompt(program);
        if (binding.transport() == TurnSpec.Transport.OPENAI_COMPAT) {
            messages.addObject().put("role", "system").put("content", system);
            system = null;
        }
        messages.addObject().put("role", "user").put(
                "content", "Work only on the exact program-selected subject.");
        String secret = launches.resolveSecret(binding);
        return runner.runTurn(
                new TurnSpec(
                        binding.transport(),
                        binding.endpoint(),
                        secret,
                        binding.model(),
                        binding.reasoningEffort(),
                        system,
                        messages,
                        launches.tools(program, binding.transport()),
                        binding.maxOutputTokens(),
                        binding.maxToolIterations()),
                executor,
                new TurnHooks()
                {
                    @Override
                    public boolean interrupted()
                    {
                        return terminalSeal.get()
                                || Thread.currentThread().isInterrupted();
                    }
                });
    }

    private ToolExecutor bounded(
            AtomicBoolean terminalSeal, ToolExecutor delegate)
    {
        AtomicInteger calls = new AtomicInteger();
        return call -> {
            if (terminalSeal.get()) {
                return ToolCallResult.error("terminal tool already accepted");
            }
            if (!validArguments(call)) {
                return ToolCallResult.error("tool argument is invalid");
            }
            if (calls.incrementAndGet() > MAX_TOOL_CALLS) {
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

    private boolean validArguments(ToolCall call)
    {
        JsonNode input;
        try {
            input = call.rawArguments() == null
                    || call.rawArguments().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(call.rawArguments());
        }
        catch (JsonProcessingException malformed) {
            return false;
        }
        if (input == null || !input.isObject()) {
            return false;
        }
        Set<String> allowed = switch (call.name()) {
            case "read_file", "read_reviewed_blob", "read_base_blob",
                    "delete_file" -> Set.of("path");
            case "search_repository" -> Set.of("query");
            case "write_file" -> Set.of("path", "content");
            case "run_checks" -> Set.of("profile");
            case "read_ci_log" -> Set.of("index", "offset");
            case "read_candidate_lesson" -> Set.of("index");
            case "save_ci_lesson" -> Set.of("title", "markdown");
            default -> Set.of();
        };
        return input.propertyStream().allMatch(
                entry -> allowed.contains(entry.getKey()));
    }

    private ToolCallResult checks(
            AtomicBoolean used, WriterToolCapability capability,
            Path repositoryRoot, ToolCall call)
    {
        String profile;
        try {
            profile = optionalText(call, "profile");
        }
        catch (RuntimeException invalid) {
            return ToolCallResult.error("tool argument is invalid");
        }
        if (!used.compareAndSet(false, true)) {
            return ToolCallResult.error("local checks already ran");
        }
        return safe(() -> checkSummary(capability.runChecks(
                localChecks, repositoryRoot, profile)));
    }

    private ToolCallResult taskChecks(
            AtomicBoolean used,
            TaskInspectionToolCapability capability, ToolCall call)
    {
        String profile;
        try {
            profile = optionalText(call, "profile");
        }
        catch (RuntimeException invalid) {
            return ToolCallResult.error("tool argument is invalid");
        }
        if (!used.compareAndSet(false, true)) {
            return ToolCallResult.error("local checks already ran");
        }
        return safe(() -> {
            return checkSummary(profile == null
                    ? capability.runChecks()
                    : capability.runChecks(profile));
        });
    }

    private static String checkSummary(List<LocalCheckRun> runs)
    {
        return runs.stream()
                .map(run -> run.conclusion().name())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("no checks");
    }

    private ToolCallResult guarded(
            WriterToolCapability capability,
            Supplier<String> action)
    {
        return safe(() -> capability.callTool(action));
    }

    private ToolCallResult guarded(
            TaskInspectionToolCapability capability,
            Supplier<String> action)
    {
        return safe(() -> capability.callTool(action));
    }

    private static ToolCallResult safe(
            Supplier<String> action)
    {
        try {
            return ToolCallResult.ok(action.get());
        }
        catch (RuntimeException failure) {
            return ToolCallResult.error("tool failed closed");
        }
    }

    private InProcessWriterAgentSupervisor.AgentCompletion sealedWriterCompletion(
            TurnResult result, boolean terminalToolUsed)
    {
        if (terminalToolUsed) {
            return new InProcessWriterAgentSupervisor.AgentCompletion(
                    TerminalOutcome.COMPLETED, result.finalText(), null);
        }
        TerminalOutcome outcome = terminal(result);
        String failure = error(result);
        if (outcome == TerminalOutcome.COMPLETED) {
            outcome = TerminalOutcome.FAILED;
            failure = "MISSING_TERMINAL_TOOL";
        }
        return new InProcessWriterAgentSupervisor.AgentCompletion(
                outcome, result.finalText(), failure);
    }

    private InProcessWriterAgentSupervisor.AgentCompletion writerCompletion(
            TurnResult result)
    {
        return new InProcessWriterAgentSupervisor.AgentCompletion(
                terminal(result), result.finalText(), error(result));
    }

    private static TerminalOutcome terminal(TurnResult result)
    {
        return switch (result.end()) {
            case COMPLETED, MAX_STEPS -> TerminalOutcome.COMPLETED;
            case INTERRUPTED -> TerminalOutcome.CANCELED;
            case ABORTED -> TerminalOutcome.FAILED;
        };
    }

    private static String error(TurnResult result)
    {
        return switch (result.end()) {
            case COMPLETED, MAX_STEPS -> null;
            case INTERRUPTED -> "TURN_INTERRUPTED";
            case ABORTED -> "TURN_ABORTED";
        };
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("tool result encoding failed", failure);
        }
    }

    private static String utf8(byte[] bytes)
    {
        if (bytes.length > MAX_TOOL_RESULT_CHARS) {
            throw new IllegalArgumentException("immutable Git object is too large");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String text(ToolCall call, String name)
    {
        JsonNode value = call.input().get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("tool argument is invalid");
        }
        return value.textValue();
    }

    private static String optionalText(ToolCall call, String name)
    {
        JsonNode value = call.input().get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("tool argument is invalid");
        }
        return value.textValue();
    }

    private static int integer(ToolCall call, String name)
    {
        JsonNode value = call.input().get(name);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalArgumentException("tool argument is invalid");
        }
        return value.intValue();
    }

    private static long nonnegativeLong(ToolCall call, String name)
    {
        JsonNode value = call.input().get(name);
        if (value == null || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw new IllegalArgumentException("tool argument is invalid");
        }
        return value.longValue();
    }
}
